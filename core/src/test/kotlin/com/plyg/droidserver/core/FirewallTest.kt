package com.plyg.droidserver.core

import com.plyg.droidserver.core.api.ClientGuard
import com.plyg.droidserver.core.api.Firewall
import com.plyg.droidserver.core.api.droidServerModule
import com.plyg.droidserver.core.audit.AuditLog
import com.plyg.droidserver.core.auth.ApiKeys
import com.plyg.droidserver.core.config.ApiKeyConfig
import com.plyg.droidserver.core.config.AuthConfig
import com.plyg.droidserver.core.config.AuthMode
import com.plyg.droidserver.core.config.CollectionConfig
import com.plyg.droidserver.core.config.ConfigValidator
import com.plyg.droidserver.core.config.FieldConfig
import com.plyg.droidserver.core.config.FieldType
import com.plyg.droidserver.core.config.FirewallConfig
import com.plyg.droidserver.core.config.HttpConfig
import com.plyg.droidserver.core.config.LimitsConfig
import com.plyg.droidserver.core.config.Rule
import com.plyg.droidserver.core.config.Rules
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.config.UsersConfig
import com.plyg.droidserver.core.db.SchemaMigrator
import com.plyg.droidserver.core.db.SqlDriver
import com.plyg.droidserver.core.net.Cidr
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Faz 13.2: güvenlik duvarı (izin/engel listeleri, elle engel, anahtar ve kullanıcı başına sınır). */
class FirewallTest {
    private val key = "yonetici-anahtari-1234567890"
    private val notes = CollectionConfig("notes", listOf(FieldConfig("text", FieldType.TEXT)), rules = Rules(list = Rule.AUTH, view = Rule.AUTH))
    private lateinit var db: SqlDriver

    private fun config(firewall: FirewallConfig = FirewallConfig(), keys: List<ApiKeyConfig> = emptyList(), limits: LimitsConfig = LimitsConfig()) = ServerConfig(
        server = HttpConfig(limits = limits),
        auth = AuthConfig(AuthMode.API_KEY, apiKeys = listOf(key), keys = keys, users = UsersConfig(enabled = true, sendVerification = false)),
        collections = listOf(notes),
        firewall = firewall,
    )

    @BeforeTest
    fun open() {
        db = TestDb.create(Dialect.SQLITE).open()
        SchemaMigrator(db).migrate(config().schemaCollections())
    }

    @AfterTest
    fun close() = db.close()

    @Test
    fun `adres araliklari`() {
        val v4 = assertNotNull(Cidr.parse("10.1.0.0/16"))
        assertTrue(v4.contains(InetAddress.getByName("10.1.200.3")))
        assertFalse(v4.contains(InetAddress.getByName("10.2.0.1")))
        assertFalse(v4.contains(InetAddress.getByName("::1")))
        assertTrue(assertNotNull(Cidr.parse("2001:db8::/32")).contains(InetAddress.getByName("2001:db8:5::1")))
        assertTrue(assertNotNull(Cidr.parse("203.0.113.7")).contains(InetAddress.getByName("203.0.113.7")))
        listOf("", "example.com", "10.0.0.0/33", "1.2.3", "::/129", "10.0.0.1/x").forEach { assertNull(Cidr.parse(it), it) }

        val fw = Firewall(FirewallConfig(allow = listOf("198.51.100.0/24"), deny = listOf("198.51.100.66")))
        assertEquals(null, fw.blocked("198.51.100.5"))
        assertNotNull(fw.blocked("198.51.100.66"), "engel izinden önce")
        assertNotNull(fw.blocked("203.0.113.1"), "izin listesinde değil")
        assertNull(fw.blocked("192.168.1.20"), "yerel ağ izinli")
        assertNull(fw.blocked(ClientGuard.LOCAL), "telefonun kendisi")
        assertNotNull(Firewall(FirewallConfig(allow = listOf("198.51.100.0/24"), allowLocalNetwork = false)).blocked("192.168.1.20"))

        val errors = ConfigValidator.validateDetailed(config(FirewallConfig(allow = listOf("ev"), deny = listOf("10.0.0.0/40"), userRequestsPerMinute = 0)))
        assertEquals(setOf("firewall.allow[0]", "firewall.deny[0]", "firewall.userRequestsPerMinute"), errors.map { it.path }.filter { it.startsWith("firewall") }.toSet())
    }

    @Test
    fun `izin ve engel listeleri istekte uygulanir`() = testApplication {
        application { droidServerModule(config(FirewallConfig(allow = listOf("198.51.100.0/24"), deny = listOf("198.51.100.66"))), db) }
        suspend fun from(ip: String?) = client.get("/api/collections/notes/records") { bearerAuth(key); ip?.let { header("X-Forwarded-For", it) } }.status
        assertEquals(HttpStatusCode.OK, from("198.51.100.5"))
        assertEquals(HttpStatusCode.Forbidden, from("198.51.100.66"))
        assertEquals(HttpStatusCode.Forbidden, from("203.0.113.1"))
        assertEquals(HttpStatusCode.OK, from("192.168.1.20"))
        assertEquals(HttpStatusCode.OK, from(null), "telefonun kendisi")
    }

    @Test
    fun `engeller listelenir, elle eklenir, kaldirilir ve deploylar arasi surer`() {
        val guard = ClientGuard()
        val audit = AuditLog(null)
        val cfg = config(limits = LimitsConfig(authFailures = 3, banMinutes = 10))
        testApplication {
            application { droidServerModule(cfg, db, audit = audit, clientGuard = guard) }
            repeat(3) { client.get("/api/collections/notes/records") { bearerAuth("yanlis-anahtar-000000000"); header("X-Forwarded-For", "203.0.113.50") } }
            assertEquals(HttpStatusCode.TooManyRequests, client.get("/api/collections/notes/records") { header("X-Forwarded-For", "203.0.113.50") }.status)

            val fw = Json.parseToJsonElement(client.get("/api/_firewall") { bearerAuth(key) }.bodyAsText()).jsonObject
            val ban = fw.getValue("bans").jsonArray.single().jsonObject
            assertEquals("203.0.113.50", ban.getValue("client").jsonPrimitive.content)
            assertTrue("hatalı kimlik" in ban.getValue("reason").jsonPrimitive.content)
            assertTrue(audit.list(10, "firewall.ban").isNotEmpty(), "otomatik engel denetim kaydında")

            val manual = client.post("/api/_firewall/bans") {
                bearerAuth(key); contentType(ContentType.Application.Json); setBody("""{"client":"198.51.100.9","minutes":5,"reason":"tarama"}""")
            }
            assertEquals(HttpStatusCode.NoContent, manual.status)
            assertEquals(HttpStatusCode.BadRequest, client.post("/api/_firewall/bans") {
                bearerAuth(key); contentType(ContentType.Application.Json); setBody("""{"client":"10.0.0.0/8"}""")
            }.status)
            assertEquals(HttpStatusCode.TooManyRequests, client.get("/api/collections/notes/records") { bearerAuth(key); header("X-Forwarded-For", "198.51.100.9") }.status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/_firewall") { header("X-Forwarded-For", "192.168.1.2") }.status)

            assertEquals(HttpStatusCode.NoContent, client.delete("/api/_firewall/bans/203.0.113.50") { bearerAuth(key) }.status)
            assertEquals(HttpStatusCode.NotFound, client.delete("/api/_firewall/bans/203.0.113.50") { bearerAuth(key) }.status)
            assertEquals(HttpStatusCode.OK, client.get("/api/collections/notes/records") { bearerAuth(key); header("X-Forwarded-For", "203.0.113.50") }.status)
            assertEquals(listOf("firewall.unban", "firewall.ban", "firewall.ban"), audit.list(10, "firewall").map { it.action })
        }
        // Yeni deploy (yeni modül), aynı koruma: elle engel sürer.
        testApplication {
            application { droidServerModule(cfg, db, clientGuard = guard) }
            assertEquals(HttpStatusCode.TooManyRequests, client.get("/api/collections/notes/records") { bearerAuth(key); header("X-Forwarded-For", "198.51.100.9") }.status)
        }
    }

    @Test
    fun `anahtar ve kullanici basina dakika siniri`() = testApplication {
        val (raw, hashed) = ApiKeys.generate("rapor", readOnly = true, collections = emptyList(), expiresAt = null)
        application {
            droidServerModule(config(FirewallConfig(userRequestsPerMinute = 2), keys = listOf(hashed.copy(requestsPerMinute = 2))), db)
        }
        suspend fun list(token: String, ip: String) = client.get("/api/collections/notes/records") { bearerAuth(token); header("X-Forwarded-For", ip) }.status
        // Anahtarın sınırı adresten bağımsız: farklı adreslerden de olsa üçüncü istek 429.
        assertEquals(HttpStatusCode.OK, list(raw, "198.51.100.1"))
        assertEquals(HttpStatusCode.OK, list(raw, "198.51.100.2"))
        assertEquals(HttpStatusCode.TooManyRequests, list(raw, "198.51.100.3"))
        assertEquals(HttpStatusCode.OK, list(key, "198.51.100.3"), "ana anahtar sınırsız")

        suspend fun signup(email: String) = Json.parseToJsonElement(client.post("/api/auth/signup") {
            contentType(ContentType.Application.Json); setBody("""{"email":"$email","password":"parola-1234"}""")
        }.bodyAsText()).jsonObject.getValue("token").jsonPrimitive.content
        val ali = signup("ali@example.com")
        val ayse = signup("ayse@example.com")
        assertEquals(HttpStatusCode.OK, list(ali, "192.168.1.9"))
        assertEquals(HttpStatusCode.OK, list(ali, "192.168.1.9"))
        assertEquals(HttpStatusCode.TooManyRequests, list(ali, "192.168.1.9"))
        assertEquals(HttpStatusCode.OK, list(ayse, "192.168.1.9"), "aynı adresteki başka kullanıcı etkilenmez")
    }
}
