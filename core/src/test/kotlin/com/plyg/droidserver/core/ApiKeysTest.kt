package com.plyg.droidserver.core

import com.plyg.droidserver.core.api.droidServerModule
import com.plyg.droidserver.core.auth.ApiKeyUsage
import com.plyg.droidserver.core.auth.ApiKeys
import com.plyg.droidserver.core.config.AuthConfig
import com.plyg.droidserver.core.config.AuthMode
import com.plyg.droidserver.core.config.CollectionConfig
import com.plyg.droidserver.core.config.ConfigValidator
import com.plyg.droidserver.core.config.FieldConfig
import com.plyg.droidserver.core.config.FieldType
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.db.SchemaMigrator
import com.plyg.droidserver.core.db.SqlDriver
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApiKeysTest {
    private val legacy = "eski-tam-yetkili-anahtar-123"
    private val todos = CollectionConfig("todos", listOf(FieldConfig("title", FieldType.TEXT)))
    private val notes = CollectionConfig("notes", listOf(FieldConfig("body", FieldType.TEXT)))
    private lateinit var db: SqlDriver

    private val full = ApiKeys.generate("ci")
    private val reader = ApiKeys.generate("rapor", readOnly = true)
    private val todoWriter = ApiKeys.generate("todo-bot", collections = listOf("todos"))
    private val expired = ApiKeys.generate("eski", expiresAt = Instant.parse("2020-01-01T00:00:00Z"))
    private val revoked = ApiKeys.generate("iptal").let { it.copy(config = it.config.copy(enabled = false)) }

    private fun config() = ServerConfig(
        auth = AuthConfig(AuthMode.API_KEY, apiKeys = listOf(legacy), keys = listOf(full, reader, todoWriter, expired, revoked).map { it.config }),
        collections = listOf(todos, notes),
    )

    @BeforeTest
    fun open() {
        db = TestDb.create(Dialect.SQLITE).open()
        SchemaMigrator(db).migrate(listOf(todos, notes))
    }

    @AfterTest
    fun close() = db.close()

    private suspend fun HttpClient.create(c: String, key: String) = post("/api/collections/$c/records") {
        bearerAuth(key); contentType(ContentType.Application.Json); setBody("""{"${if (c == "todos") "title" else "body"}":"x"}""")
    }

    @Test
    fun `anahtar kapsamlari, sure ve iptal`() = testApplication {
        val usage = ApiKeyUsage()
        application { droidServerModule(config(), db, keyUsage = usage) }
        assertTrue(full.key.startsWith("dsk_") && full.key.length > 40)
        assertTrue(full.key !in config().toJson() && full.config.hash in config().toJson(), "anahtar değil özeti saklanır")

        // Eski anahtarlar ve tam yetkili anahtar yönetici.
        assertEquals(HttpStatusCode.OK, client.get("/api/_schema") { bearerAuth(legacy) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/_schema") { bearerAuth(full.key) }.status)
        assertEquals(HttpStatusCode.Created, client.create("notes", full.key).status)

        // Salt okunur: tüm koleksiyonlarda okur (kurallar yönetici olsa da), yazamaz, yönetici uçlarına giremez.
        assertEquals(HttpStatusCode.OK, client.get("/api/collections/notes/records") { bearerAuth(reader.key) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/collections/notes/export") { bearerAuth(reader.key) }.status)
        val denied = client.create("todos", reader.key)
        assertEquals(HttpStatusCode.Forbidden, denied.status)
        assertTrue("rapor" in denied.bodyAsText())
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/_schema") { bearerAuth(reader.key) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/_metrics") { bearerAuth(reader.key) }.status)

        // Koleksiyona sınırlı: yalnızca todos'ta okur-yazar (toplu işlem ve içe aktarma dahil).
        assertEquals(HttpStatusCode.Created, client.create("todos", todoWriter.key).status)
        assertEquals(HttpStatusCode.Forbidden, client.create("notes", todoWriter.key).status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/collections/notes/records") { bearerAuth(todoWriter.key) }.status)
        val batch = client.post("/api/batch") {
            bearerAuth(todoWriter.key); contentType(ContentType.Application.Json)
            setBody("""{"requests":[{"method":"POST","collection":"todos","body":{"title":"a"}},{"method":"POST","collection":"notes","body":{"body":"b"}}]}""")
        }
        assertEquals(HttpStatusCode.Forbidden, batch.status)
        assertEquals(HttpStatusCode.OK, client.post("/api/collections/todos/import") { bearerAuth(todoWriter.key); setBody("title\ny\n") }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/collections/notes/import") { bearerAuth(todoWriter.key); setBody("body\ny\n") }.status)
        val doc = client.get("/api/_openapi.json") { bearerAuth(todoWriter.key) }.bodyAsText()
        assertTrue("/collections/todos/records" in doc && "/collections/notes/records" !in doc)

        // Süresi dolmuş ve iptal edilmiş anahtar nedeniyle 401.
        val old = client.get("/api/collections/todos/records") { bearerAuth(expired.key) }
        assertEquals(HttpStatusCode.Unauthorized, old.status)
        assertTrue("süresi dolmuş" in old.bodyAsText())
        assertTrue("iptal" in client.get("/api/collections/todos/records") { bearerAuth(revoked.key) }.bodyAsText())
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/_schema") { bearerAuth("dsk_bilinmeyen") }.status)

        assertNotNull(usage.lastUsed("rapor"))
        assertNotNull(usage.lastUsed("todo-bot"))
        assertNull(usage.lastUsed("eski"), "reddedilen anahtar kullanılmış sayılmaz")
    }

    @Test
    fun `dogrulama`() {
        fun errors(c: ServerConfig) = ConfigValidator.validate(c)
        assertTrue(errors(config()).isEmpty(), errors(config()).toString())
        // Yalnızca tam yetkili adlandırılmış anahtarla da api_key modu geçerli.
        assertTrue(errors(config().copy(auth = config().auth.copy(apiKeys = emptyList()))).isEmpty())
        assertTrue(errors(config().copy(auth = config().auth.copy(apiKeys = emptyList(), keys = listOf(reader.config)))).any { "tam yetkili" in it })
        assertTrue(errors(config().copy(auth = config().auth.copy(keys = listOf(full.config, full.config)))).any { "iki kez" in it })
        assertTrue(errors(config().copy(auth = config().auth.copy(keys = listOf(todoWriter.config.copy(collections = listOf("yok")))))).any { "'yok'" in it })
        assertTrue(errors(config().copy(auth = config().auth.copy(keys = listOf(full.config.copy(hash = "x"))))).any { "özeti" in it })
        assertTrue(errors(config().copy(auth = config().auth.copy(keys = listOf(full.config.copy(expiresAt = "yarın"))))).any { "ISO-8601" in it })
        assertEquals(config(), ServerConfig.fromJson(config().toJson()))
        assertEquals("Salt okunur: tüm koleksiyonlar", ApiKeys.describe(reader.config))
        assertEquals("Okuma ve yazma: todos", ApiKeys.describe(todoWriter.config))
    }
}
