package dev.droidserver.core

import dev.droidserver.core.api.droidServerModule
import dev.droidserver.core.config.AuthConfig
import dev.droidserver.core.config.AuthMode
import dev.droidserver.core.config.CollectionConfig
import dev.droidserver.core.config.FieldConfig
import dev.droidserver.core.config.FieldType
import dev.droidserver.core.config.Rule
import dev.droidserver.core.config.Rules
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.config.SyncConfig
import dev.droidserver.core.config.SyncConflict
import dev.droidserver.core.config.SystemCollections
import dev.droidserver.core.config.UsersConfig
import dev.droidserver.core.db.SchemaMigrator
import dev.droidserver.core.db.SqlDriver
import dev.droidserver.core.sync.SyncLog
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Faz 18.1: çevrimdışı eşitleme (çekme, gönderme, çakışma), iki lehçede. */
@RunWith(Parameterized::class)
class SyncTest(private val dialect: Dialect) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun dialects() = Dialect.entries.toList()
    }

    private val key = "yonetici-anahtari-1234567890"
    private val notes = CollectionConfig(
        "notes",
        listOf(
            FieldConfig("title", FieldType.TEXT, required = true),
            FieldConfig("body", FieldType.TEXT),
            FieldConfig("done", FieldType.BOOLEAN),
            FieldConfig("owner", FieldType.RELATION, collection = SystemCollections.USERS),
        ),
        rules = Rules(Rule.OWNER, Rule.OWNER, Rule.AUTH, Rule.OWNER, Rule.OWNER),
        ownerField = "owner",
        sync = SyncConfig(),
    )
    private val tasks = CollectionConfig(
        "tasks",
        listOf(FieldConfig("title", FieldType.TEXT, required = true), FieldConfig("state", FieldType.TEXT)),
        rules = Rules(Rule.PUBLIC, Rule.PUBLIC, Rule.PUBLIC, Rule.PUBLIC, Rule.PUBLIC),
        sync = SyncConfig(conflict = SyncConflict.MANUAL),
    )
    private val config = ServerConfig(
        auth = AuthConfig(AuthMode.API_KEY, apiKeys = listOf(key), users = UsersConfig(enabled = true, sendVerification = false)),
        collections = listOf(notes, tasks),
    )
    private lateinit var db: SqlDriver

    @Before
    fun open() {
        db = TestDb.create(dialect).open()
    }

    @After
    fun close() = db.close()

    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject
    private fun JsonObject.str(k: String) = getValue(k).jsonPrimitive.content
    private fun JsonObject.long(k: String) = str(k).toLong()

    private suspend fun HttpClient.send(method: String, path: String, body: String? = null, token: String? = key): JsonObject {
        val r = when (method) {
            "GET" -> get(path) { token?.let { bearerAuth(it) } }
            "PATCH" -> patch(path) { token?.let { bearerAuth(it) }; contentType(ContentType.Application.Json); setBody(body ?: "{}") }
            else -> post(path) { token?.let { bearerAuth(it) }; contentType(ContentType.Application.Json); setBody(body ?: "{}") }
        }
        assertTrue(r.status.value in 200..299, "$method $path → ${r.status}: ${r.bodyAsText()}")
        return json(r.bodyAsText())
    }

    private suspend fun HttpClient.signup(email: String) =
        send("POST", "/api/auth/signup", """{"email":"$email","password":"parola-1234"}""", token = null).str("token")

    @Test
    fun `cekme, gonderme ve alan bazinda cakisma`() = testApplication {
        // Eşitleme açılmadan önce var olan kayıt da ize eklenir.
        SchemaMigrator(db).migrate(config.schemaCollections())
        db.execute("INSERT INTO \"tasks\" (\"title\", \"created_at\", \"updated_at\") VALUES ('eski', '2026-10-23T00:00:00Z', '2026-10-23T00:00:00Z')")
        application { droidServerModule(config, db) }
        val ali = client.signup("ali@example.com")
        val ayse = client.signup("ayse@example.com")

        // İlk çekme: kendi kayıtları; başkasının kaydı bu istemci için "silinmiş".
        val a1 = client.send("POST", "/api/collections/notes/records", """{"title":"Süt","body":"2 litre"}""", ali)
        client.send("POST", "/api/collections/notes/records", """{"title":"Ayşe'nin"}""", ayse)
        val first = client.send("GET", "/api/sync/notes", token = ali)
        val epoch = first.str("epoch")
        assertTrue(epoch.isNotEmpty())
        assertFalse(first.getValue("reset").jsonPrimitive.content.toBoolean())
        val changes = first.getValue("changes").jsonArray.map { it.jsonObject }
        assertEquals(listOf("upsert", "delete"), changes.map { it.str("action") })
        assertEquals("Süt", changes[0].getValue("record").jsonObject.str("title"))
        assertEquals(1L, changes[0].long("version"))
        val cursor = first.long("next")

        // Artımlı: güncelleme ve API dışı yazma (tetikleyici) yeni sürüm üretir.
        val id = a1.long("id")
        client.send("PATCH", "/api/collections/notes/records/$id", """{"body":"3 litre"}""", ali)
        val second = client.send("GET", "/api/sync/notes?since=$cursor&epoch=$epoch", token = ali)
        assertEquals(listOf(2L), second.getValue("changes").jsonArray.map { it.jsonObject.long("version") })
        val c2 = second.long("next")
        assertEquals(emptyList(), client.send("GET", "/api/sync/notes?since=$c2", token = ali).getValue("changes").jsonArray.toList())

        // Gönderme: oluşturma (geçici kimlik), güncel sürümle güncelleme.
        val pushed = client.send(
            "POST", "/api/sync/notes",
            """{"now":${System.currentTimeMillis()},"changes":[{"op":"create","tempId":"t1","fields":{"title":"Ekmek"}},{"op":"update","id":$id,"base":2,"fields":{"done":true},"times":{"done":${System.currentTimeMillis()}}}]}""",
            ali,
        ).getValue("results").jsonArray.map { it.jsonObject }
        assertEquals(listOf("ok", "ok"), pushed.map { it.str("status") })
        assertEquals("t1", pushed[0].str("tempId"))
        assertEquals("true", pushed[1].getValue("record").jsonObject.str("done"))
        assertEquals(3L, pushed[1].long("version"))

        // Çevrimdışıyken (taban 3) başkası body'yi değiştirdi. Eski düzenleme sunucuya yenik, yenisi kazanır; diğer alan uygulanır.
        client.send("PATCH", "/api/collections/notes/records/$id", """{"body":"sunucu"}""", ali)
        val past = System.currentTimeMillis() - 60_000
        val lost = client.send(
            "POST", "/api/sync/notes",
            """{"now":${System.currentTimeMillis()},"changes":[{"op":"update","id":$id,"base":3,"fields":{"body":"istemci","title":"Süt!"},"times":{"body":$past,"title":$past}}]}""",
            ali,
        ).getValue("results").jsonArray.single().jsonObject
        assertEquals("merged", lost.str("status"))
        assertEquals("sunucu", lost.getValue("record").jsonObject.str("body"))
        assertEquals("Süt!", lost.getValue("record").jsonObject.str("title"), "çakışmayan alan uygulanır")
        assertEquals("istemci", lost.getValue("conflicts").jsonObject.str("body"))
        val base = lost.long("version")
        client.send("PATCH", "/api/collections/notes/records/$id", """{"body":"sunucu 2"}""", ali)
        val won = client.send(
            "POST", "/api/sync/notes",
            """{"now":${System.currentTimeMillis()},"changes":[{"op":"update","id":$id,"base":$base,"fields":{"body":"istemci"},"times":{"body":${System.currentTimeMillis() + 1000}}}]}""",
            ali,
        ).getValue("results").jsonArray.single().jsonObject
        assertEquals("ok", won.str("status"))
        assertEquals("istemci", won.getValue("record").jsonObject.str("body"))

        // Kural: başkasının kaydı güncellenemez (hata, diğerleri etkilenmez).
        val other = client.send("GET", "/api/collections/notes/records", token = ayse).getValue("items").jsonArray.single().jsonObject.long("id")
        val denied = client.send("POST", "/api/sync/notes", """{"changes":[{"op":"update","id":$other,"base":1,"fields":{"title":"x"}}]}""", ali)
            .getValue("results").jsonArray.single().jsonObject
        assertEquals("error", denied.str("status"))
        assertEquals("404", denied.str("code"))

        // Silme: sunucuda daha yeni değişiklik varsa (lww) silinmez; güncel sürümle silinir; olmayan kayıt "ok".
        val v = SyncLog.entry(db, "notes", id)!!.version
        val stale = client.send("POST", "/api/sync/notes", """{"changes":[{"op":"delete","id":$id,"base":1,"time":$past}]}""", ali)
        assertEquals("conflict", stale.getValue("results").jsonArray.single().jsonObject.str("status"))
        val gone = client.send("POST", "/api/sync/notes", """{"changes":[{"op":"delete","id":$id,"base":$v},{"op":"delete","id":$id,"base":$v}]}""", ali)
        assertEquals(listOf("ok", "ok"), gone.getValue("results").jsonArray.map { it.jsonObject.str("status") })
        assertEquals("gone", client.send("POST", "/api/sync/notes", """{"changes":[{"op":"update","id":$id,"base":$v,"fields":{"title":"x"}}]}""", ali)
            .getValue("results").jsonArray.single().jsonObject.str("status"))

        // Elle çözme: aynı alan sunucuda değiştiyse uygulanmaz ve çakışma döner; başka alan uygulanır.
        val all = client.send("GET", "/api/sync/tasks", token = null).getValue("changes").jsonArray.map { it.jsonObject }
        assertEquals(listOf("eski"), all.map { it.getValue("record").jsonObject.str("title") }, "sonradan açılan eşitleme")
        val task = all.single().long("id")
        client.send("PATCH", "/api/collections/tasks/records/$task", """{"state":"sunucu"}""", null)
        val manual = client.send(
            "POST", "/api/sync/tasks",
            """{"changes":[{"op":"update","id":$task,"base":1,"fields":{"state":"istemci","title":"yeni"},"times":{"state":${System.currentTimeMillis() + 5000}}}]}""",
            null,
        ).getValue("results").jsonArray.single().jsonObject
        assertEquals("conflict", manual.str("status"))
        assertEquals("sunucu", manual.getValue("record").jsonObject.str("state"))
        assertEquals("yeni", manual.getValue("record").jsonObject.str("title"))

        // API dışı yazma da izlenir.
        db.execute("UPDATE \"tasks\" SET \"state\" = 'elle' WHERE \"id\" = ?", listOf(task))
        val fields = SyncLog.fieldVersions(db, "tasks", task)
        assertEquals(SyncLog.entry(db, "tasks", task)!!.version, fields.getValue("state").first)

        // İz temizliği: süresi dolan silme izi silinir; silmeyi görmemiş (daha eski imleçli) istemci baştan eşitlenir,
        // görmüş olan devam eder. Değişen dönem de baştan eşitletir.
        val del = client.send("POST", "/api/collections/tasks/records", """{"title":"silinecek"}""", null).long("id")
        val before = client.send("GET", "/api/sync/tasks", token = null).long("next")
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/collections/tasks/records/$del").status)
        val sawDelete = client.send("GET", "/api/sync/tasks?since=$before", token = null).long("next")
        client.send("POST", "/api/collections/tasks/records", """{"title":"sonra"}""", null)
        assertEquals(1, SyncLog.purge(db, "tasks", 1, now = System.currentTimeMillis() + 2 * 86_400_000L))
        assertTrue(client.send("GET", "/api/sync/tasks?since=$before", token = null).getValue("reset").jsonPrimitive.content.toBoolean())
        val cont = client.send("GET", "/api/sync/tasks?since=$sawDelete", token = null)
        assertFalse(cont.getValue("reset").jsonPrimitive.content.toBoolean())
        assertEquals(listOf("sonra"), cont.getValue("changes").jsonArray.map { it.jsonObject.getValue("record").jsonObject.str("title") })
        assertTrue(client.send("GET", "/api/sync/tasks?since=$sawDelete&epoch=baska", token = null).getValue("reset").jsonPrimitive.content.toBoolean())
        // Son numaralı iz silinmez: yeni değişiklik daha önce verilmiş numarayı almaz.
        val top = SyncLog.latest(db)
        client.send("POST", "/api/collections/tasks/records", """{"title":"en son"}""", null)
        assertTrue(SyncLog.latest(db) > top)
        assertNull(SyncLog.entry(db, "tasks", 999_999))
        assertEquals(HttpStatusCode.Unauthorized.value, client.get("/api/sync/notes").status.value, "kural: giriş gerekir")
    }
}
