package com.plyg.droidserver.core

import com.plyg.droidserver.core.api.RecordService
import com.plyg.droidserver.core.api.droidServerModule
import com.plyg.droidserver.core.api.issueJwt
import com.plyg.droidserver.core.config.AuthConfig
import com.plyg.droidserver.core.config.AuthMode
import com.plyg.droidserver.core.config.CollectionConfig
import com.plyg.droidserver.core.config.FieldConfig
import com.plyg.droidserver.core.config.FieldType
import com.plyg.droidserver.core.config.OnDelete
import com.plyg.droidserver.core.config.Rule
import com.plyg.droidserver.core.config.Rules
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.config.SystemCollections
import com.plyg.droidserver.core.config.UsersConfig
import com.plyg.droidserver.core.db.SchemaMigrator
import com.plyg.droidserver.core.db.SqlDriver
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Kullanıcı hesapları ve koleksiyon kuralları, HTTP üzerinden, iki lehçede. */
@RunWith(Parameterized::class)
class UsersHttpTest(private val dialect: Dialect) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun dialects() = Dialect.entries.toList()
    }

    private val key = "yonetici-anahtari-1234567890"

    /** Her kural için tüm işlemleri o kurala bağlı bir koleksiyon. */
    private fun ruled(rule: Rule) = CollectionConfig(
        "c_${rule.name.lowercase()}",
        listOf(
            FieldConfig("title", FieldType.TEXT),
            FieldConfig("owner", FieldType.RELATION, collection = SystemCollections.USERS, onDelete = OnDelete.CASCADE),
        ),
        rules = Rules(rule, rule, rule, rule, rule),
        ownerField = "owner",
    )

    private val notes = CollectionConfig(
        "notes",
        listOf(
            FieldConfig("body", FieldType.TEXT),
            FieldConfig("author", FieldType.RELATION, collection = SystemCollections.USERS, required = true, onDelete = OnDelete.CASCADE),
        ),
        rules = Rules(list = Rule.PUBLIC, view = Rule.PUBLIC, create = Rule.AUTH, update = Rule.OWNER, delete = Rule.OWNER),
        ownerField = "author",
    )

    private val config = ServerConfig(
        auth = AuthConfig(
            AuthMode.API_KEY,
            apiKeys = listOf(key),
            users = UsersConfig(
                enabled = true,
                extraFields = listOf(FieldConfig("name", FieldType.TEXT, required = true), FieldConfig("phone", FieldType.TEXT)),
                publicFields = listOf("name"),
            ),
        ),
        collections = Rule.entries.filter { it != Rule.TEAM }.map(::ruled) + notes, // takım kuralı: TeamsTest
    )

    private lateinit var db: SqlDriver

    @Before
    fun open() {
        db = TestDb.create(dialect).open()
        SchemaMigrator(db).migrate(config.schemaCollections())
    }

    @After
    fun close() = db.close()

    private fun json(r: String) = Json.parseToJsonElement(r).jsonObject
    private suspend fun HttpResponse.obj() = json(bodyAsText())
    private fun JsonObject.str(k: String) = getValue(k).jsonPrimitive.content
    private fun JsonObject.id() = str("id").toLong()

    private suspend fun HttpClient.send(method: String, path: String, token: String?, body: String? = null): HttpResponse {
        val build: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            token?.let { bearerAuth(it) }
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }
        return when (method) {
            "GET" -> get(path, build)
            "POST" -> post(path, build)
            "PATCH" -> patch(path, build)
            "DELETE" -> delete(path, build)
            else -> error(method)
        }
    }

    private suspend fun HttpClient.signup(email: String, name: String = "Ad"): JsonObject {
        val r = send("POST", "/api/auth/signup", null, """{"email":"$email","password":"parola-123","name":"$name"}""")
        assertEquals(HttpStatusCode.Created, r.status, r.bodyAsText())
        return r.obj()
    }

    @Test
    fun `kural matrisi - 5 kural x 5 islem x anonim, kullanici, baska kullanici, yonetici`() = testApplication {
        application { droidServerModule(config, db) }
        val a = client.signup("a@example.com")
        val b = client.signup("b@example.com")
        val aId = a.getValue("user").jsonObject.id()
        val tokens = mapOf("anonim" to null, "sahip" to a.str("token"), "başkası" to b.str("token"), "yönetici" to key)

        val ok = mapOf("list" to 200, "view" to 200, "create" to 201, "update" to 200, "delete" to 204)
        fun expected(rule: Rule, op: String, who: String): Int = when {
            rule == Rule.NOBODY -> 403
            who == "yönetici" || rule == Rule.PUBLIC -> ok.getValue(op)
            who == "anonim" -> 401
            rule == Rule.AUTH -> ok.getValue(op)
            rule == Rule.ADMIN -> 403
            // owner: başkasının kaydı yokmuş gibi; listede görünmez, oluşturma serbest (sahibi kendisi olur)
            who == "başkası" && op in setOf("view", "update", "delete") -> 404
            else -> ok.getValue(op)
        }

        val failures = mutableListOf<String>()
        for (rule in Rule.entries.filter { it != Rule.TEAM }) {
            val c = config.collections.first { it.name == "c_${rule.name.lowercase()}" }
            val fixtures = RecordService(db, c, collections = config.schemaCollections())
            val base = "/api/collections/${c.name}/records"
            for (op in ok.keys) for ((who, token) in tokens) {
                // Her denemede A'ya ait yeni bir kayıt (silme/güncelleme sonraki denemeyi etkilemesin).
                val id = fixtures.create(buildJsonObject { put("title", JsonPrimitive("a'nın")); put("owner", JsonPrimitive(aId)) }).id()
                val r = when (op) {
                    "list" -> client.send("GET", base, token)
                    "view" -> client.send("GET", "$base/$id", token)
                    "create" -> client.send("POST", base, token, """{"title":"yeni"}""")
                    "update" -> client.send("PATCH", "$base/$id", token, """{"title":"değişti"}""")
                    else -> client.send("DELETE", "$base/$id", token)
                }
                val want = expected(rule, op, who)
                if (r.status.value != want) failures += "$rule/$op/$who: ${r.status.value} (beklenen $want) ${r.bodyAsText()}"
                if (rule == Rule.OWNER && op == "list" && r.status.value == 200) {
                    val owners = r.obj().getValue("items").jsonArray.map { it.jsonObject["owner"] }.toSet()
                    if (who == "başkası" && owners.isNotEmpty()) failures += "owner/list/başkası A'nın kayıtlarını gördü: $owners"
                    if (who == "sahip" && owners != setOf(JsonPrimitive(aId))) failures += "owner/list/sahip: $owners"
                }
                if (op == "create" && r.status.value == 201) {
                    val owner = r.obj()["owner"]
                    val want2 = when (who) {
                        "sahip" -> JsonPrimitive(aId)
                        "başkası" -> b.getValue("user").jsonObject.getValue("id")
                        else -> JsonNull
                    }
                    if (owner != want2) failures += "$rule/create/$who: sahip $owner (beklenen $want2)"
                }
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `kayit, giris, yenileme tek kullanimlik, cikis`() = testApplication {
        application { droidServerModule(config, db) }
        val s = client.signup("Ali@Example.com ", "Ali")
        val user = s.getValue("user").jsonObject
        assertEquals("ali@example.com", user.str("email"), "e-posta küçük harfe çevrilir")
        assertEquals("user", user.str("role"))
        assertFalse("password_hash" in user, "özet yanıtta yok")
        assertEquals(900, s.str("expiresIn").toInt())

        assertEquals(HttpStatusCode.Conflict, client.send("POST", "/api/auth/signup", null, """{"email":"ali@example.com","password":"parola-123","name":"x"}""").status)
        val bad = client.send("POST", "/api/auth/signup", null, """{"email":"x","password":"kisa","role":"admin"}""")
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        val details = bad.obj().getValue("details").jsonObject
        assertEquals(setOf("role"), details.keys, "bilinmeyen alan önce reddedilir: ${bad.bodyAsText()}")
        val bad2 = client.send("POST", "/api/auth/signup", null, """{"email":"x","password":"kisa"}""").obj().getValue("details").jsonObject
        assertEquals(setOf("email", "password"), bad2.keys)

        val me = client.send("GET", "/api/auth/me", s.str("token"))
        assertEquals(HttpStatusCode.OK, me.status)
        assertEquals("Ali", me.obj().str("name"))
        assertEquals(HttpStatusCode.Unauthorized, client.send("GET", "/api/auth/me", null).status)
        assertEquals(HttpStatusCode.BadRequest, client.send("GET", "/api/auth/me", key).status, "yönetici anahtarının hesabı yok")

        assertEquals(HttpStatusCode.Unauthorized, client.send("POST", "/api/auth/login", null, """{"email":"ali@example.com","password":"yanlis-parola"}""").status)
        val login = client.send("POST", "/api/auth/login", null, """{"email":"ALI@example.com","password":"parola-123"}""")
        assertEquals(HttpStatusCode.OK, login.status, login.bodyAsText())
        val refresh1 = login.obj().str("refreshToken")

        val r1 = client.send("POST", "/api/auth/refresh", null, """{"refreshToken":"$refresh1"}""")
        assertEquals(HttpStatusCode.OK, r1.status)
        val refresh2 = r1.obj().str("refreshToken")
        assertNotEquals(refresh1, refresh2)
        assertEquals(HttpStatusCode.Unauthorized, client.send("POST", "/api/auth/refresh", null, """{"refreshToken":"$refresh1"}""").status, "tek kullanımlık")
        assertEquals(HttpStatusCode.OK, client.send("GET", "/api/auth/me", r1.obj().str("token")).status)

        assertEquals(HttpStatusCode.NoContent, client.send("POST", "/api/auth/logout", null, """{"refreshToken":"$refresh2"}""").status)
        assertEquals(HttpStatusCode.Unauthorized, client.send("POST", "/api/auth/refresh", null, """{"refreshToken":"$refresh2"}""").status)

        // Veritabanında yalnızca özetler.
        val row = db.query("SELECT \"password_hash\" FROM \"_users\"").single()
        val hash = row["password_hash"] as String
        assertTrue(hash.startsWith("pbkdf2$310000$"), hash)
        assertFalse("parola-123" in hash)
        val sessions = db.query("SELECT \"token_hash\" FROM \"_sessions\"").map { it["token_hash"] as String }
        assertTrue(sessions.none { it == refresh1 || it == refresh2 || it == s.str("refreshToken") })

        // Kullanıcı token'ı yönetici yerine geçmez.
        assertEquals(HttpStatusCode.Forbidden, client.send("GET", "/api/_schema", s.str("token")).status)
        assertEquals(HttpStatusCode.Forbidden, client.send("GET", "/api/auth/users", s.str("token")).status)
    }

    @Test
    fun `giris denemesi sinirlanir`() = testApplication {
        application { droidServerModule(config, db) }
        client.signup("c@example.com")
        repeat(5) {
            assertEquals(HttpStatusCode.Unauthorized, client.send("POST", "/api/auth/login", null, """{"email":"c@example.com","password":"yanlis-$it"}""").status)
        }
        val blocked = client.send("POST", "/api/auth/login", null, """{"email":"c@example.com","password":"parola-123"}""")
        assertEquals(HttpStatusCode.TooManyRequests, blocked.status, "doğru parola da beklemeli")
        // Başka hesap etkilenmez.
        client.signup("d@example.com")
        assertEquals(HttpStatusCode.OK, client.send("POST", "/api/auth/login", null, """{"email":"d@example.com","password":"parola-123"}""").status)
    }

    @Test
    fun `sahip alani istemciden yazilamaz ve kullanici expand ile yalnizca acik alanlarini gosterir`() = testApplication {
        application { droidServerModule(config, db) }
        val a = client.signup("a@example.com", "Ayşe")
        val b = client.signup("b@example.com", "Burak")
        val aId = a.getValue("user").jsonObject.id()
        val bId = b.getValue("user").jsonObject.id()
        val base = "/api/collections/notes/records"

        assertEquals(HttpStatusCode.Unauthorized, client.send("POST", base, null, """{"body":"x"}""").status)
        assertEquals(HttpStatusCode.Forbidden, client.send("POST", base, a.str("token"), """{"body":"x","author":$bId}""").status)
        val note = client.send("POST", base, a.str("token"), """{"body":"merhaba","author":$aId}""")
        assertEquals(HttpStatusCode.Created, note.status, note.bodyAsText())
        val id = note.obj().id()
        assertEquals(HttpStatusCode.Forbidden, client.send("PATCH", "$base/$id", a.str("token"), """{"author":$bId}""").status)
        assertEquals(HttpStatusCode.Forbidden, client.send("PATCH", "$base/$id", a.str("token"), """{"author":null}""").status)
        assertEquals(HttpStatusCode.OK, client.send("PATCH", "$base/$id", a.str("token"), """{"author":$aId,"body":"aynı sahip"}""").status)
        assertEquals(HttpStatusCode.NotFound, client.send("PATCH", "$base/$id", b.str("token"), """{"body":"çaldım"}""").status)
        // Yönetici sahibi değiştirebilir.
        assertEquals(HttpStatusCode.OK, client.send("PATCH", "$base/$id", key, """{"author":$bId}""").status)
        assertEquals(HttpStatusCode.OK, client.send("PATCH", "$base/$id", key, """{"author":$aId}""").status)

        val listed = client.send("GET", "$base?expand=author", null)
        assertEquals(HttpStatusCode.OK, listed.status, listed.bodyAsText())
        val author = listed.obj().getValue("items").jsonArray.single().jsonObject.getValue("expand").jsonObject.getValue("author").jsonObject
        assertEquals(setOf("id", "name"), author.keys, "e-posta, telefon ve özet gizli")
        assertEquals("Ayşe", author.str("name"))

        // Kullanıcı silinince notları (cascade) ve oturumları silinir; token'ı artık geçmez.
        assertEquals(HttpStatusCode.NoContent, client.send("DELETE", "/api/auth/users/$aId", key).status)
        assertEquals(0, client.send("GET", base, null).obj().str("totalItems").toInt())
        assertEquals(HttpStatusCode.Unauthorized, client.send("GET", "/api/auth/me", a.str("token")).status)
        assertEquals(HttpStatusCode.Unauthorized, client.send("POST", "/api/auth/refresh", null, """{"refreshToken":"${a.str("refreshToken")}"}""").status)
    }

    @Test
    fun `yonetici kullanicilari yonetir`() = testApplication {
        application { droidServerModule(config, db) }
        val created = client.send("POST", "/api/auth/users", key, """{"email":"yeni@example.com","password":"ilk-parola-1","name":"Yeni","role":"admin","verified":true}""")
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val id = created.obj().id()
        val list = client.send("GET", "/api/auth/users?email=yeni", key).obj()
        assertEquals(1, list.str("totalItems").toInt())
        assertFalse("password_hash" in list.getValue("items").jsonArray.single().jsonObject)

        val login = client.send("POST", "/api/auth/login", null, """{"email":"yeni@example.com","password":"ilk-parola-1"}""").obj()
        // Rolü admin olan kullanıcı yönetici sayılır.
        assertEquals(HttpStatusCode.OK, client.send("GET", "/api/collections/c_admin/records", login.str("token")).status)
        assertEquals(HttpStatusCode.OK, client.send("PATCH", "/api/auth/users/$id", key, """{"role":"user"}""").status)
        assertEquals(HttpStatusCode.Forbidden, client.send("GET", "/api/collections/c_admin/records", login.str("token")).status, "rol hemen geçerli")

        // Parola sıfırlama tüm oturumları kapatır.
        assertEquals(HttpStatusCode.OK, client.send("PATCH", "/api/auth/users/$id", key, """{"password":"yeni-parola-2"}""").status)
        assertEquals(HttpStatusCode.Unauthorized, client.send("POST", "/api/auth/refresh", null, """{"refreshToken":"${login.str("refreshToken")}"}""").status)
        assertEquals(HttpStatusCode.OK, client.send("POST", "/api/auth/login", null, """{"email":"yeni@example.com","password":"yeni-parola-2"}""").status)

        // Kullanıcı kendi parolasını mevcut parolasıyla değiştirir.
        val t = client.send("POST", "/api/auth/login", null, """{"email":"yeni@example.com","password":"yeni-parola-2"}""").obj().str("token")
        assertEquals(HttpStatusCode.BadRequest, client.send("PATCH", "/api/auth/me", t, """{"password":"ucuncu-parola"}""").status)
        assertEquals(HttpStatusCode.OK, client.send("PATCH", "/api/auth/me", t, """{"password":"ucuncu-parola","currentPassword":"yeni-parola-2","name":"Değişti"}""").status)
        assertEquals("Değişti", client.send("GET", "/api/auth/me", t).obj().str("name"))
        assertEquals(HttpStatusCode.BadRequest, client.send("PATCH", "/api/auth/me", t, """{"role":"admin"}""").status, "rolünü kendisi değiştiremez")
    }

    @Test
    fun `kayit kapali ve yonetici jwt`() = testApplication {
        val cfg = config.copy(auth = config.auth.copy(mode = AuthMode.JWT, apiKeys = emptyList(), jwtSecret = "s".repeat(40), users = config.auth.users.copy(allowSignup = false)))
        application { droidServerModule(cfg, db) }
        assertEquals(HttpStatusCode.Forbidden, client.send("POST", "/api/auth/signup", null, """{"email":"a@example.com","password":"parola-123","name":"A"}""").status)
        val admin = issueJwt(cfg, "uygulama")
        val created = client.send("POST", "/api/auth/users", admin, """{"email":"a@example.com","password":"parola-123","name":"A"}""")
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val user = client.send("POST", "/api/auth/login", null, """{"email":"a@example.com","password":"parola-123"}""").obj().str("token")
        assertEquals(HttpStatusCode.OK, client.send("GET", "/api/auth/me", user).status)
        assertEquals(HttpStatusCode.Forbidden, client.send("GET", "/api/collections/c_admin/records", user).status)
        assertEquals(HttpStatusCode.OK, client.send("GET", "/api/collections/c_admin/records", admin).status)
    }
}
