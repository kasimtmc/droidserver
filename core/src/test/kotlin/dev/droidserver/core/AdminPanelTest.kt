package dev.droidserver.core

import dev.droidserver.core.admin.AdminHost
import dev.droidserver.core.admin.AdminSessions
import dev.droidserver.core.api.droidServerModule
import dev.droidserver.core.backup.BackupInfo
import dev.droidserver.core.backup.BackupKind
import dev.droidserver.core.backup.BackupStore
import dev.droidserver.core.config.AdminPanelConfig
import dev.droidserver.core.config.AuthConfig
import dev.droidserver.core.config.AuthMode
import dev.droidserver.core.config.CollectionConfig
import dev.droidserver.core.config.FieldConfig
import dev.droidserver.core.config.FieldType
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.db.SchemaMigrator
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdminPanelTest {
    private val key = "yonetici-anahtari-1234567890"
    private val todos = CollectionConfig("todos", listOf(FieldConfig("title", FieldType.TEXT)))
    private val config = ServerConfig(
        auth = AuthConfig(AuthMode.API_KEY, apiKeys = listOf(key)),
        collections = listOf(todos),
        adminPanel = AdminPanelConfig(enabled = true),
    )

    private class FakeHost(val store: BackupStore) : AdminHost {
        val log = Collections.synchronizedList(mutableListOf("başladı", "deploy tamam"))
        var deployed: String? = null
        override fun configText() = """{"name":"deneme"}"""
        override fun plan(text: String): JsonObject = buildJsonObject {
            put("errors", JsonArray(if ("kotu" in text) listOf(JsonPrimitive("geçersiz")) else emptyList()))
            put("plan", JsonPrimitive("Şema güncel"))
        }
        override fun deploy(text: String) {
            require("kotu" !in text) { "geçersiz" }
            deployed = text
        }
        override fun logs(after: Long): Pair<Long, List<String>> = log.size.toLong() to log.drop(after.toInt())
        override fun backups(): List<BackupInfo> = store.list()
        override fun createBackup(): BackupInfo {
            store.newFile(BackupKind.MANUAL).writeText("zip")
            return store.list().first()
        }
    }

    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test
    fun `eslestirme kodu`() {
        var now = 0L
        val s = AdminSessions { now }
        assertNull(s.pair("123456", 12), "kod üretilmeden giriş yok")
        val code = s.newCode().code
        repeat(4) { assertNull(s.pair("000000".takeIf { it != code } ?: "111111", 12)) }
        val token = s.pair(code, 12)
        assertNotNull(token)
        assertNull(s.pair(code, 12), "tek kullanımlık")
        assertTrue(s.valid(token))
        now += 13 * 3_600_000L
        assertTrue(!s.valid(token), "oturum süresi doldu")

        val c2 = s.newCode().code
        repeat(5) { s.pair(if (c2 == "999999") "888888" else "999999", 12) }
        assertNull(s.pair(c2, 12), "5 hatalı denemede kod geçersiz")
        val c3 = s.newCode().code
        now += 6 * 60_000L
        assertNull(s.pair(c3, 12), "5 dakika geçerli")
    }

    @Test
    fun `panel ve yonetim uclari`() = testApplication {
        val db = TestDb.create(Dialect.SQLITE).open()
        SchemaMigrator(db).migrate(listOf(todos))
        val sessions = AdminSessions()
        val dir = Files.createTempDirectory("ds-admin").toFile()
        val host = FakeHost(BackupStore(dir))
        application { droidServerModule(config, db, adminSessions = sessions, adminHost = host) }

        val page = client.get("/_admin/")
        assertEquals(HttpStatusCode.OK, page.status)
        assertTrue("<script src=\"app.js\">" in page.bodyAsText())
        assertTrue("frame-ancestors 'none'" in page.headers["Content-Security-Policy"].orEmpty())
        assertTrue("javascript" in client.get("/_admin/app.js").headers[HttpHeaders.ContentType].orEmpty())
        val i18n = client.get("/_admin/i18n.js")
        assertTrue("javascript" in i18n.headers[HttpHeaders.ContentType].orEmpty())
        assertTrue("window.DS_LANG=\"tr\"" in i18n.bodyAsText(), "panel sunucunun dilini alır")
        assertTrue("<title>" in client.get("/_admin/kayitlar").bodyAsText(), "bilinmeyen yol panelin kendisi")
        assertEquals(HttpStatusCode.Unauthorized, client.get("/_admin/api/info").status)

        suspend fun pair(code: String) = client.post("/_admin/api/pair") { contentType(ContentType.Application.Json); setBody("""{"code":"$code"}""") }
        assertEquals(HttpStatusCode.Unauthorized, pair("123456").status)
        val token = json(pair(sessions.newCode().code).bodyAsText()).getValue("token").jsonPrimitive.content

        // Panel token'ı normal API'de yönetici sayılır.
        assertEquals(HttpStatusCode.OK, client.get("/api/collections/todos/records") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/collections/todos/records").status)
        val info = json(client.get("/_admin/api/info") { bearerAuth(token) }.bodyAsText())
        assertEquals(listOf("todos"), info.getValue("collections").jsonArray.map { it.jsonPrimitive.content })

        assertEquals("""{"name":"deneme"}""", json(client.get("/_admin/api/config") { bearerAuth(token) }.bodyAsText()).getValue("text").jsonPrimitive.content)
        val plan = client.post("/_admin/api/config/plan") { bearerAuth(token); contentType(ContentType.Application.Json); setBody("""{"text":"kotu"}""") }
        assertEquals("geçersiz", json(plan.bodyAsText()).getValue("errors").jsonArray.single().jsonPrimitive.content)
        assertEquals(HttpStatusCode.BadRequest, client.post("/_admin/api/config/deploy") { bearerAuth(token); contentType(ContentType.Application.Json); setBody("""{"text":"kotu"}""") }.status)
        assertEquals(HttpStatusCode.Accepted, client.post("/_admin/api/config/deploy") { bearerAuth(token); contentType(ContentType.Application.Json); setBody("""{"text":"{}"}""") }.status)
        assertEquals("{}", host.deployed)

        val logs = json(client.get("/_admin/api/logs?after=1") { bearerAuth(token) }.bodyAsText())
        assertEquals(listOf("deploy tamam"), logs.getValue("lines").jsonArray.map { it.jsonPrimitive.content })

        assertEquals(HttpStatusCode.Created, client.post("/_admin/api/backups") { bearerAuth(token) }.status)
        val list = Json.parseToJsonElement(client.get("/_admin/api/backups") { bearerAuth(token) }.bodyAsText()).jsonArray
        val name = list.single().jsonObject.getValue("name").jsonPrimitive.content
        assertEquals("zip", client.get("/_admin/api/backups/$name") { bearerAuth(token) }.bodyAsText())
        assertEquals(HttpStatusCode.NotFound, client.get("/_admin/api/backups/..%2Fyok.zip") { bearerAuth(token) }.status)

        assertEquals(HttpStatusCode.NoContent, client.post("/_admin/api/logout") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/_admin/api/info") { bearerAuth(token) }.status)
        db.close()
        dir.deleteRecursively()
    }

    @Test
    fun `panel kapaliyken yok`() = testApplication {
        val db = TestDb.create(Dialect.SQLITE).open()
        val sessions = AdminSessions()
        application { droidServerModule(config.copy(adminPanel = AdminPanelConfig()), db, adminSessions = sessions) }
        assertEquals(HttpStatusCode.NotFound, client.get("/_admin/").status)
        val code = sessions.newCode().code
        assertEquals(HttpStatusCode.NotFound, client.post("/_admin/api/pair") { contentType(ContentType.Application.Json); setBody("""{"code":"$code"}""") }.status)
        db.close()
    }

    @Test
    fun `canli log akisi gercek sunucuda`() {
        val db = TestDb.create(Dialect.SQLITE).open()
        val sessions = AdminSessions()
        val host = FakeHost(BackupStore(Files.createTempDirectory("ds-admin").toFile()))
        val port = java.net.ServerSocket(0).use { it.localPort }
        val server = io.ktor.server.engine.embeddedServer(io.ktor.server.cio.CIO, port = port, host = "127.0.0.1") {
            droidServerModule(config, db, adminSessions = sessions, adminHost = host)
        }.start(wait = false)
        try {
            val token = sessions.pair(sessions.newCode().code, 1)!!
            val conn = java.net.URI("http://127.0.0.1:$port/_admin/api/logs/stream?after=1").toURL().openConnection() as java.net.HttpURLConnection
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.readTimeout = 5_000
            assertTrue(conn.contentType.startsWith("text/event-stream"), conn.contentType)
            val reader = conn.inputStream.bufferedReader()
            assertEquals("data: deploy tamam", reader.readLine())
            host.log += "yeni satır"
            assertEquals("", reader.readLine())
            assertEquals("data: yeni satır", generateSequence { reader.readLine() }.first { it.startsWith("data:") })
            conn.disconnect()
        } finally {
            server.stop(100, 500)
            db.close()
        }
    }
}
