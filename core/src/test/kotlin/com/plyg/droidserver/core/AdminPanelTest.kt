package com.plyg.droidserver.core

import com.plyg.droidserver.core.admin.AdminHost
import com.plyg.droidserver.core.admin.AdminSessions
import com.plyg.droidserver.core.api.droidServerModule
import com.plyg.droidserver.core.backup.BackupInfo
import com.plyg.droidserver.core.backup.BackupKind
import com.plyg.droidserver.core.backup.BackupStore
import com.plyg.droidserver.core.config.AdminPanelConfig
import com.plyg.droidserver.core.config.AuthConfig
import com.plyg.droidserver.core.config.AuthMode
import com.plyg.droidserver.core.config.AutomationAction
import com.plyg.droidserver.core.config.AutomationActionType
import com.plyg.droidserver.core.config.AutomationConfig
import com.plyg.droidserver.core.config.AutomationTrigger
import com.plyg.droidserver.core.config.CollectionConfig
import com.plyg.droidserver.core.config.FieldConfig
import com.plyg.droidserver.core.config.FailoverConfig
import com.plyg.droidserver.core.config.FieldType
import com.plyg.droidserver.core.config.ReplicaSource
import com.plyg.droidserver.core.config.ReplicationConfig
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.config.TeamsConfig
import com.plyg.droidserver.core.config.UsersConfig
import com.plyg.droidserver.core.db.SchemaMigrator
import com.plyg.droidserver.core.replication.ReplicaStatus
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.time.Instant
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
        var replica: ReplicaStatus? = null
        var tookOver = false
        override fun replica() = replica
        override fun takeover() { tookOver = true }
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

        val solo = json(client.get("/_admin/api/replica") { bearerAuth(token) }.bodyAsText())
        assertEquals(JsonNull, solo.getValue("primary"))
        assertEquals(HttpStatusCode.Conflict, client.post("/_admin/api/replica/takeover") { bearerAuth(token) }.status, "kopya olmayan sunucu devralamaz")

        assertEquals(HttpStatusCode.NoContent, client.post("/_admin/api/logout") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/_admin/api/info") { bearerAuth(token) }.status)
        db.close()
        dir.deleteRecursively()
    }

    /** 17.4: panelin Otomasyon ve Servisler sekmeleri ile Kullanıcılar'daki Takımlar'ın kullandığı uçlar, panel oturumuyla. */
    @Test
    fun `otomasyon, servisler ve takimlar`() = testApplication {
        val cfg = config.copy(
            auth = config.auth.copy(users = UsersConfig(enabled = true, sendVerification = false, teams = TeamsConfig(enabled = true))),
            automations = listOf(AutomationConfig("yeni", AutomationTrigger("todos"), actions = listOf(AutomationAction(AutomationActionType.LOG, body = "deneme")))),
        )
        val db = TestDb.create(Dialect.SQLITE).open()
        SchemaMigrator(db).migrate(cfg.schemaCollections())
        val sessions = AdminSessions()
        val host = FakeHost(BackupStore(Files.createTempDirectory("ds-admin").toFile()))
        application { droidServerModule(cfg, db, adminSessions = sessions, adminHost = host) }
        val token = json(client.post("/_admin/api/pair") { contentType(ContentType.Application.Json); setBody("""{"code":"${sessions.newCode().code}"}""") }.bodyAsText())
            .getValue("token").jsonPrimitive.content
        suspend fun get(path: String) = client.get(path) { bearerAuth(token) }
        suspend fun send(method: String, path: String, body: String = "{}") = when (method) {
            "PATCH" -> client.patch(path) { bearerAuth(token); contentType(ContentType.Application.Json); setBody(body) }
            "DELETE" -> client.delete(path) { bearerAuth(token) }
            else -> client.post(path) { bearerAuth(token); contentType(ContentType.Application.Json); setBody(body) }
        }

        val info = json(get("/_admin/api/info").bodyAsText())
        assertEquals(JsonPrimitive(true), info.getValue("teams"))
        val paths = info.getValue("paths").jsonObject
        assertEquals("/_automations/{name}/run", paths.getValue("automations.run").jsonPrimitive.content)
        assertEquals("/teams/{id}/members/{user}", paths.getValue("teams.removeMember").jsonPrimitive.content)

        // Otomasyon: kurallar ve "Şimdi dene".
        val rules = json(get("/api/_automations").bodyAsText()).getValue("rules").jsonArray
        assertEquals("yeni", rules.single().jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals(HttpStatusCode.Created, send("POST", "/api/collections/todos/records", """{"title":"ekmek al"}""").status)
        val run = json(send("POST", "/api/_automations/yeni/run").bodyAsText())
        assertEquals(JsonPrimitive(true), run.getValue("ok"), run.toString())

        // Servisler: kapalı özellikler panelde "kapalı" görünür (404), MQTT durumu her zaman okunur.
        val mqtt = json(get("/api/_mqtt").bodyAsText())
        assertEquals(JsonPrimitive(false), mqtt.getValue("listening"))
        assertEquals(HttpStatusCode.NotFound, send("POST", "/api/_mqtt/publish", """{"topic":"ev/lamba","payload":"on"}""").status)
        assertEquals(HttpStatusCode.NotFound, get("/api/_ai").status)
        assertEquals(HttpStatusCode.NotFound, get("/api/_apps").status)
        assertEquals(HttpStatusCode.OK, get("/api/_cli").status)

        // Takımlar: panel yönetici olarak kurar, üye ekler, rol verir, çıkarır, siler.
        assertEquals(HttpStatusCode.Created, send("POST", "/api/auth/users", """{"email":"ayse@example.com","password":"parola-12345"}""").status)
        val team = json(send("POST", "/api/teams", """{"name":"Ev"}""").bodyAsText()).getValue("id").jsonPrimitive.content
        assertEquals(HttpStatusCode.Created, send("POST", "/api/teams/$team/members", """{"email":"ayse@example.com"}""").status)
        val member = json(get("/api/teams/$team").bodyAsText()).getValue("members").jsonArray.single().jsonObject
        assertEquals("member", member.getValue("role").jsonPrimitive.content)
        val user = member.getValue("user").jsonPrimitive.content
        assertTrue(send("DELETE", "/api/teams/$team/members/$user").status.value in 200..299)
        assertEquals(0, json(get("/api/teams").bodyAsText()).getValue("items").jsonArray.single().jsonObject.getValue("memberCount").jsonPrimitive.content.toInt())
        assertEquals(HttpStatusCode.Created, send("POST", "/api/teams/$team/members", """{"email":"ayse@example.com"}""").status)
        assertEquals(HttpStatusCode.OK, send("PATCH", "/api/teams/$team/members/$user", """{"role":"owner"}""").status)
        assertEquals(HttpStatusCode.Conflict, send("DELETE", "/api/teams/$team/members/$user").status, "takımın son sahibi çıkarılamaz")
        assertTrue(send("DELETE", "/api/teams/$team").status.value in 200..299)
        assertTrue(json(get("/api/teams").bodyAsText()).getValue("items").jsonArray.isEmpty())
        db.close()
    }

    @Test
    fun `canli kopya ekrani`() = testApplication {
        val cfg = config.copy(
            replication = ReplicationConfig(
                replicaOf = ReplicaSource("http://192.168.1.20:8080/api", "secret:replica_key"),
                failover = FailoverConfig(auto = true, witness = "http://192.168.1.1"),
            ),
        )
        val db = TestDb.create(Dialect.SQLITE).open()
        SchemaMigrator(db).migrate(listOf(todos))
        val sessions = AdminSessions()
        val host = FakeHost(BackupStore(Files.createTempDirectory("ds-admin").toFile()))
        host.replica = ReplicaStatus(ReplicaStatus.Phase.SYNCING, appliedSeq = 40, latestSeq = 42, lastContact = Instant.parse("2026-10-23T08:00:00Z"))
        application { droidServerModule(cfg, db, adminSessions = sessions, adminHost = host) }
        val token = json(client.post("/_admin/api/pair") { contentType(ContentType.Application.Json); setBody("""{"code":"${sessions.newCode().code}"}""") }.bodyAsText())
            .getValue("token").jsonPrimitive.content

        val r = json(client.get("/_admin/api/replica") { bearerAuth(token) }.bodyAsText())
        assertEquals("http://192.168.1.20:8080/api", r.getValue("primary").jsonPrimitive.content)
        assertEquals(JsonPrimitive(true), r.getValue("failover").jsonObject.getValue("auto"))
        assertEquals("http://192.168.1.1", r.getValue("failover").jsonObject.getValue("witness").jsonPrimitive.content)
        val status = r.getValue("status").jsonObject
        assertEquals("SYNCING", status.getValue("phase").jsonPrimitive.content)
        assertEquals(42L, status.getValue("latestSeq").jsonPrimitive.content.toLong())
        assertEquals("2026-10-23T08:00:00Z", status.getValue("lastContact").jsonPrimitive.content)
        assertTrue("key" !in r.toString() && "replica_key" !in r.toString(), "anahtar panele gitmez")

        assertEquals(HttpStatusCode.Unauthorized, client.post("/_admin/api/replica/takeover").status)
        assertEquals(HttpStatusCode.Accepted, client.post("/_admin/api/replica/takeover") { bearerAuth(token) }.status)
        assertTrue(host.tookOver)
        db.close()
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
