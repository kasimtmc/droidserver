package dev.droidserver.core

import dev.droidserver.core.api.ClientGuard
import dev.droidserver.core.api.droidServerModule
import dev.droidserver.core.api.origin
import dev.droidserver.core.config.AuthConfig
import dev.droidserver.core.config.AuthMode
import dev.droidserver.core.config.CollectionConfig
import dev.droidserver.core.config.FieldConfig
import dev.droidserver.core.config.FieldType
import dev.droidserver.core.config.HttpConfig
import dev.droidserver.core.config.LimitsConfig
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.db.SchemaMigrator
import dev.droidserver.core.db.SqlDriver
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull

class RequestGuardTest {
    private val notes = CollectionConfig(name = "notes", fields = listOf(FieldConfig("text", FieldType.TEXT)))
    private lateinit var db: SqlDriver

    @Before
    fun open() {
        db = TestDb.create(Dialect.SQLITE).open()
        SchemaMigrator(db).migrate(listOf(notes))
    }

    @After
    fun close() = db.close()

    private fun config(limits: LimitsConfig) = ServerConfig(
        server = HttpConfig(limits = limits),
        auth = AuthConfig(mode = AuthMode.API_KEY, apiKeys = listOf("dogru-anahtar-1234567890")),
        collections = listOf(notes),
    )

    @Test
    fun `istemci adresi ve internetten duz HTTP tespiti`() {
        val none: (String) -> String? = { null }
        val spoof: (String) -> String? = { if (it == "X-Forwarded-For") "9.9.9.9" else null }
        // disaridan gelen baglantida yonlendirme basliklari yok sayilir
        assertEquals("85.1.2.3", origin("85.1.2.3", spoof) { false }.key)
        assertTrue(origin("85.1.2.3", none) { false }.internet)
        assertFalse(origin("192.168.1.5", none) { false }.internet)
        assertFalse(origin("100.101.1.2", none) { false }.internet) // Tailscale
        assertTrue(origin("2a02:1:2:3::9", none) { false }.internet)
        assertFalse(origin("2a02:1:2:3::9", none) { true }.internet) // ayni ev agi (/64)
        // telefonun icinden (HTTPS ucu / cloudflared) gelende gercek adres basliktan
        assertEquals("9.9.9.9", origin("127.0.0.1", spoof) { false }.key)
        assertEquals(ClientGuard.LOCAL, origin("127.0.0.1", none) { false }.key)
    }

    @Test
    fun `istek siniri istemci basina uygulanir, uygulama sinirlanmaz`() {
        var now = 0L
        val g = ClientGuard(LimitsConfig(requestsPerMinute = 3), clock = { now })
        repeat(3) { assertNull(g.admit("1.2.3.4")) }
        assertEquals(60L, g.admit("1.2.3.4"))
        assertNull(g.admit("5.6.7.8")) // baska istemci etkilenmez
        now = 60_000
        assertNull(g.admit("1.2.3.4")) // pencere yenilendi
    }

    @Test
    fun `hatali kimlik denemeleri gecici engel getirir`() = testApplication {
        application { droidServerModule(config(LimitsConfig(authFailures = 3, banMinutes = 5)), db) }
        val url = "/api/collections/notes/records"
        repeat(3) {
            val r = client.get(url) { header("CF-Connecting-IP", "203.0.113.9"); bearerAuth("yanlis") }
            assertEquals(HttpStatusCode.Unauthorized, r.status)
        }
        val banned = client.get(url) { header("CF-Connecting-IP", "203.0.113.9"); bearerAuth("dogru-anahtar-1234567890") }
        assertEquals(HttpStatusCode.TooManyRequests, banned.status)
        assertEquals("300", banned.headers["Retry-After"])
        // baska adres ve telefonun kendisi etkilenmez
        val other = client.get(url) { header("X-Forwarded-For", "198.51.100.7"); bearerAuth("dogru-anahtar-1234567890") }
        assertEquals(HttpStatusCode.OK, other.status)
        assertEquals(HttpStatusCode.OK, client.get(url) { bearerAuth("dogru-anahtar-1234567890") }.status)
        assertEquals("nosniff", other.headers["X-Content-Type-Options"])
        assertEquals("no-store", other.headers["Cache-Control"])
    }

    @Test
    fun `buyuk govde reddedilir, uzunluksuz govde dahil`() = testApplication {
        application { droidServerModule(config(LimitsConfig(maxBodyKb = 1)), db) }
        val url = "/api/collections/notes/records"
        val big = """{"text":"${"a".repeat(2000)}"}"""
        val r1 = client.post(url) {
            header("X-Forwarded-For", "203.0.113.9"); bearerAuth("dogru-anahtar-1234567890")
            contentType(ContentType.Application.Json); setBody(big)
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, r1.status, r1.bodyAsText())

        val r2 = client.post(url) {
            header("X-Forwarded-For", "203.0.113.9"); bearerAuth("dogru-anahtar-1234567890")
            setBody(object : OutgoingContent.WriteChannelContent() {
                override val contentType = ContentType.Application.Json
                override suspend fun writeTo(channel: ByteWriteChannel) = channel.writeStringUtf8(big)
            })
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, r2.status, r2.bodyAsText())

        val ok = client.post(url) {
            header("X-Forwarded-For", "203.0.113.9"); bearerAuth("dogru-anahtar-1234567890")
            contentType(ContentType.Application.Json); setBody("""{"text":"kisa"}""")
        }
        assertEquals(HttpStatusCode.Created, ok.status, ok.bodyAsText())
    }
}
