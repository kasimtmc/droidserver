package dev.droidserver.core

import dev.droidserver.core.api.droidServerModule
import dev.droidserver.core.config.AuthConfig
import dev.droidserver.core.config.AuthMode
import dev.droidserver.core.config.CollectionConfig
import dev.droidserver.core.config.ConfigValidator
import dev.droidserver.core.config.FieldConfig
import dev.droidserver.core.config.FieldType
import dev.droidserver.core.config.FieldVisibility
import dev.droidserver.core.config.OnDelete
import dev.droidserver.core.config.RoleConfig
import dev.droidserver.core.config.Rule
import dev.droidserver.core.config.Rules
import dev.droidserver.core.config.ScriptsConfig
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.config.SystemCollections
import dev.droidserver.core.config.UsersConfig
import dev.droidserver.core.db.SchemaMigrator
import dev.droidserver.core.db.SqlDriver
import dev.droidserver.core.script.ScriptLoadException
import dev.droidserver.core.script.ScriptRuntime
import dev.droidserver.core.script.ScriptSource
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Özel roller: kural izinleri, kullanıcıya atama, script uçları ve doğrulama, iki lehçede. */
@RunWith(Parameterized::class)
class RolesTest(private val dialect: Dialect) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun dialects() = Dialect.entries.toList()
    }

    private val key = "yonetici-anahtari-1234567890"

    // Herkes okur, yazar kendi yazısını düzenler; editör hepsini düzenler, moderatör siler.
    private val posts = CollectionConfig(
        "posts",
        listOf(
            FieldConfig("title", FieldType.TEXT, required = true),
            FieldConfig("score", FieldType.INTEGER, visibility = FieldVisibility.ADMIN),
            FieldConfig("author", FieldType.RELATION, collection = SystemCollections.USERS, onDelete = OnDelete.CASCADE),
        ),
        rules = Rules(
            Rule.PUBLIC, Rule.PUBLIC, Rule.AUTH, Rule.OWNER, Rule.ADMIN,
            roles = mapOf("update" to listOf("editor"), "delete" to listOf("editor", "moderator")),
        ),
        ownerField = "author",
    )
    private val config = ServerConfig(
        auth = AuthConfig(
            AuthMode.API_KEY, apiKeys = listOf(key),
            users = UsersConfig(enabled = true, roles = listOf(RoleConfig("editor", "Editör"), RoleConfig("moderator"))),
        ),
        collections = listOf(posts),
        scripts = ScriptsConfig(enabled = true),
    )
    private lateinit var db: SqlDriver

    @Before
    fun open() {
        db = TestDb.create(dialect).open()
        SchemaMigrator(db).migrate(config.schemaCollections())
    }

    @After
    fun close() = db.close()

    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject
    private suspend fun HttpClient.send(method: String, path: String, token: String?, body: String? = null): HttpResponse {
        val build: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            token?.let { bearerAuth(it) }
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }
        return when (method) {
            "GET" -> get(path, build)
            "POST" -> post(path, build)
            "PATCH" -> patch(path, build)
            else -> delete(path, build)
        }
    }
    private suspend fun HttpClient.signup(email: String): Pair<Long, String> {
        val r = json(send("POST", "/api/auth/signup", null, """{"email":"$email","password":"parola-123"}""").bodyAsText())
        return r.getValue("user").jsonObject.getValue("id").jsonPrimitive.content.toLong() to r.getValue("token").jsonPrimitive.content
    }
    private suspend fun HttpClient.login(email: String): String =
        json(send("POST", "/api/auth/login", null, """{"email":"$email","password":"parola-123"}""").bodyAsText()).getValue("token").jsonPrimitive.content

    @Test
    fun `rol izinleri ve script ucu`() = testApplication {
        val scripts = ScriptRuntime.load(
            listOf(ScriptSource("main.js", """routerAdd("GET", "/editor", (req, ctx) => ({ role: ctx.auth.role }), "role:editor")""")),
            config.scripts, setOf("posts"), roles = setOf("editor", "moderator"),
        )
        application { droidServerModule(config, db, scripts = scripts) }
        val (aliceId, alice) = client.signup("alice@example.com")
        val (_, bob) = client.signup("bob@example.com")
        val (edId, _) = client.signup("ed@example.com")
        val (modId, _) = client.signup("mod@example.com")

        // Rolü yalnızca yönetici atar; tanımsız rol reddedilir.
        assertEquals(HttpStatusCode.Forbidden, client.send("PATCH", "/api/auth/users/$edId", alice, """{"role":"editor"}""").status)
        val bad = client.send("PATCH", "/api/auth/users/$edId", key, """{"role":"yazar"}""")
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        assertTrue("editor" in bad.bodyAsText(), "geçerli roller listelenir")
        assertEquals(HttpStatusCode.OK, client.send("PATCH", "/api/auth/users/$edId", key, """{"role":"editor"}""").status)
        assertEquals(HttpStatusCode.OK, client.send("PATCH", "/api/auth/users/$modId", key, """{"role":"moderator"}""").status)
        val ed = client.login("ed@example.com")
        val mod = client.login("mod@example.com")

        val base = "/api/collections/posts/records"
        val post = json(client.send("POST", base, alice, """{"title":"Alice yazısı"}""").bodyAsText())
        val id = post.getValue("id").jsonPrimitive.content
        assertEquals(aliceId.toString(), post.getValue("author").jsonPrimitive.content)

        // Sahip kuralı: başka kullanıcı düzenleyemez; editör rolü düzenler (sahip değişmez), yönetici alanı yine gizli.
        assertEquals(HttpStatusCode.NotFound, client.send("PATCH", "$base/$id", bob, """{"title":"bob"}""").status)
        val edited = client.send("PATCH", "$base/$id", ed, """{"title":"Editörden"}""")
        assertEquals(HttpStatusCode.OK, edited.status, edited.bodyAsText())
        assertEquals(aliceId.toString(), json(edited.bodyAsText()).getValue("author").jsonPrimitive.content)
        assertFalse("score" in json(edited.bodyAsText()), "rol yönetici alanlarını göstermez")
        assertTrue("yalnızca yönetici" in client.send("PATCH", "$base/$id", ed, """{"score":5}""").bodyAsText())
        // Moderatör düzenleyemez (sahip kuralında başkasının kaydı yok gibi) ama silebilir; sıradan kullanıcı silemez.
        assertEquals(HttpStatusCode.NotFound, client.send("PATCH", "$base/$id", mod, """{"title":"mod"}""").status)
        assertEquals(HttpStatusCode.Forbidden, client.send("DELETE", "$base/$id", alice).status)
        assertEquals(HttpStatusCode.NoContent, client.send("DELETE", "$base/$id", mod).status)
        assertEquals(0, json(client.send("GET", base, null).bodyAsText()).getValue("items").jsonArray.size)

        // Script ucu: yalnızca editör (ve yönetici).
        assertEquals(HttpStatusCode.Unauthorized, client.send("GET", "/api/editor", null).status)
        assertEquals(HttpStatusCode.Forbidden, client.send("GET", "/api/editor", alice).status)
        assertEquals("editor", json(client.send("GET", "/api/editor", ed).bodyAsText()).getValue("role").jsonPrimitive.content)
        assertEquals(HttpStatusCode.OK, client.send("GET", "/api/editor", key).status)

        // OpenAPI belgesi rolün yapabildiklerini gösterir.
        val doc = json(client.send("GET", "/api/_openapi.json", ed).bodyAsText()).getValue("paths").jsonObject
        assertTrue("patch" in doc.getValue("/collections/posts/records/{id}").jsonObject)
        assertTrue("/editor" in doc)
        assertFalse("/editor" in json(client.send("GET", "/api/_openapi.json", alice).bodyAsText()).getValue("paths").jsonObject)
    }

    @Test
    fun `rol dogrulamasi`() {
        val errors = { c: ServerConfig -> ConfigValidator.validateDetailed(c).map { it.path }.toSet() }
        assertEquals(emptySet(), errors(config))
        val users = config.auth.users
        val names = errors(config.copy(auth = config.auth.copy(users = users.copy(roles = listOf(RoleConfig("Editor"), RoleConfig("admin"), RoleConfig("editor"), RoleConfig("editor"))))))
        assertEquals(
            setOf("auth.users.roles[0].name", "auth.users.roles[1].name", "auth.users.roles[2].name", "auth.users.roles[3].name", "collections[0].rules.roles.delete"),
            names, "geçersiz ad, yerleşik ad, tekrar; moderatör artık tanımsız",
        )
        assertEquals(
            setOf("collections[0].rules.roles.update"),
            errors(config.copy(collections = listOf(posts.copy(rules = posts.rules.copy(roles = mapOf("update" to listOf("yazar"))))))),
        )
        assertEquals(
            setOf("auth.users.roles"),
            errors(config.copy(auth = config.auth.copy(users = users.copy(enabled = false)), collections = emptyList())),
        )
        assertFailsWith<ScriptLoadException> {
            ScriptRuntime.load(listOf(ScriptSource("a.js", """routerAdd("GET", "/x", () => 1, "role:yazar")""")), config.scripts, emptySet(), roles = setOf("editor"))
        }
    }
}
