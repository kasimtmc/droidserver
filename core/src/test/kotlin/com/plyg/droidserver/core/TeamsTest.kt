package com.plyg.droidserver.core

import com.plyg.droidserver.core.api.droidServerModule
import com.plyg.droidserver.core.config.AuthConfig
import com.plyg.droidserver.core.config.AuthMode
import com.plyg.droidserver.core.config.CollectionConfig
import com.plyg.droidserver.core.config.ConfigValidator
import com.plyg.droidserver.core.config.FieldConfig
import com.plyg.droidserver.core.config.FieldType
import com.plyg.droidserver.core.config.OnDelete
import com.plyg.droidserver.core.config.Rule
import com.plyg.droidserver.core.config.Rules
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.config.TeamsConfig
import com.plyg.droidserver.core.config.UsersConfig
import com.plyg.droidserver.core.db.SchemaMigrator
import com.plyg.droidserver.core.db.SqlDriver
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Faz 16.10: takımlar, üyelik yönetimi ve `team` kuralı. */
class TeamsTest {
    private val projects = CollectionConfig(
        "projects",
        listOf(
            FieldConfig("title", FieldType.TEXT, required = true),
            FieldConfig("team", FieldType.RELATION, collection = "_teams", onDelete = OnDelete.CASCADE),
        ),
        rules = Rules(Rule.TEAM, Rule.TEAM, Rule.TEAM, Rule.TEAM, Rule.TEAM),
        teamField = "team",
    )
    private val config = ServerConfig(
        auth = AuthConfig(
            AuthMode.API_KEY, apiKeys = listOf("yonetici-anahtari-1234567890"),
            users = UsersConfig(enabled = true, sendVerification = false, teams = TeamsConfig(enabled = true, maxMembers = 3)),
        ),
        collections = listOf(projects),
    )
    private lateinit var db: SqlDriver

    @BeforeTest
    fun open() {
        db = TestDb.create(Dialect.SQLITE).open()
        SchemaMigrator(db).migrate(config.schemaCollections())
    }

    @AfterTest
    fun close() = db.close()

    private suspend fun HttpClient.send(method: String, path: String, token: String?, body: String? = null): HttpResponse {
        val block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            token?.let { header("Authorization", "Bearer $it") }
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }
        return when (method) {
            "GET" -> get(path, block)
            "POST" -> post(path, block)
            "PATCH" -> patch(path, block)
            else -> delete(path, block)
        }
    }

    private suspend fun HttpResponse.obj(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    @Test
    fun `dogrulama`() {
        assertEquals(emptyList(), ConfigValidator.validate(config))
        fun errors(c: ServerConfig) = ConfigValidator.validate(c).joinToString("\n")
        assertTrue("takımını tutan alanı" in errors(config.copy(collections = listOf(projects.copy(teamField = null)))))
        assertTrue("takımlara" in errors(config.copy(collections = listOf(projects.copy(teamField = "title")))))
        val off = config.copy(auth = config.auth.copy(users = config.auth.users.copy(teams = TeamsConfig())))
        assertTrue("önce takımları açın" in errors(off))
    }

    @Test
    fun `takimlar ve takim kurali`() = testApplication {
        application { droidServerModule(config, db) }
        suspend fun signup(email: String): Pair<String, Long> {
            val r = client.send("POST", "/api/auth/signup", null, """{"email":"$email","password":"parola-1234"}""").obj()
            return r.getValue("token").jsonPrimitive.content to r.getValue("user").jsonObject.getValue("id").jsonPrimitive.long
        }
        val (alice, _) = signup("alice@example.com")
        val (bob, bobId) = signup("bob@example.com")
        val (carol, _) = signup("carol@example.com")

        val a = client.send("POST", "/api/teams", alice, """{"name":"Mutfak"}""")
        assertEquals(HttpStatusCode.Created, a.status, a.bodyAsText())
        val teamA = a.obj().getValue("id").jsonPrimitive.long
        assertEquals("owner", a.obj().getValue("role").jsonPrimitive.content)
        val teamB = client.send("POST", "/api/teams", bob, """{"name":"Bahçe"}""").obj().getValue("id").jsonPrimitive.long

        // Sahip e-postayla üye ekler; üye ekleyemez, adı değiştiremez; üye olmayan takımı göremez.
        assertEquals(HttpStatusCode.Created, client.send("POST", "/api/teams/$teamA/members", alice, """{"email":"BOB@example.com"}""").status)
        assertEquals(HttpStatusCode.Conflict, client.send("POST", "/api/teams/$teamA/members", alice, """{"email":"bob@example.com"}""").status)
        assertEquals(HttpStatusCode.NotFound, client.send("POST", "/api/teams/$teamA/members", alice, """{"email":"yok@example.com"}""").status)
        assertEquals(HttpStatusCode.Forbidden, client.send("POST", "/api/teams/$teamA/members", bob, """{"email":"carol@example.com"}""").status)
        assertEquals(HttpStatusCode.Forbidden, client.send("PATCH", "/api/teams/$teamA", bob, """{"name":"x"}""").status)
        assertEquals(HttpStatusCode.NotFound, client.send("GET", "/api/teams/$teamB", alice).status)
        val members = client.send("GET", "/api/teams/$teamA", bob).obj()
        assertEquals(listOf("alice@example.com", "bob@example.com"), members.getValue("members").jsonArray.map { it.jsonObject.getValue("email").jsonPrimitive.content })
        assertEquals(2, client.send("GET", "/api/teams", bob).obj().getValue("items").jsonArray.size)

        // Kayıtlar: tek takımı olanın takımı kendiliğinden yazılır; iki takımlı seçmeli; başka takıma yazılamaz.
        val p1 = client.send("POST", "/api/collections/projects/records", alice, """{"title":"Menü"}""")
        assertEquals(HttpStatusCode.Created, p1.status, p1.bodyAsText())
        assertEquals(teamA, p1.obj().getValue("team").jsonPrimitive.long)
        assertEquals(HttpStatusCode.BadRequest, client.send("POST", "/api/collections/projects/records", bob, """{"title":"Hangi takım?"}""").status)
        assertEquals(HttpStatusCode.Created, client.send("POST", "/api/collections/projects/records", bob, """{"title":"Alışveriş","team":$teamA}""").status)
        assertEquals(HttpStatusCode.Created, client.send("POST", "/api/collections/projects/records", bob, """{"title":"Tohum","team":$teamB}""").status)
        assertEquals(HttpStatusCode.Forbidden, client.send("POST", "/api/collections/projects/records", alice, """{"title":"Sızma","team":$teamB}""").status)
        assertEquals(HttpStatusCode.BadRequest, client.send("POST", "/api/collections/projects/records", carol, """{"title":"Takımsız"}""").status)

        suspend fun titles(token: String) = client.send("GET", "/api/collections/projects/records?sort=id", token).obj()
            .getValue("items").jsonArray.map { it.jsonObject.getValue("title").jsonPrimitive.content }
        assertEquals(listOf("Menü", "Alışveriş"), titles(alice))
        assertEquals(listOf("Menü", "Alışveriş", "Tohum"), titles(bob))
        assertEquals(emptyList(), titles(carol))
        val pid = p1.obj().getValue("id").jsonPrimitive.long
        assertEquals(HttpStatusCode.NotFound, client.send("GET", "/api/collections/projects/records/$pid", carol).status)
        // Üye olmadığı takıma taşıyamaz.
        assertEquals(HttpStatusCode.Forbidden, client.send("PATCH", "/api/collections/projects/records/$pid", alice, """{"team":$teamB}""").status)

        // Son sahip ayrılamaz; üye ayrılınca takımın kayıtlarını hemen göremez.
        val aliceId = members.getValue("members").jsonArray.first().jsonObject.getValue("user").jsonPrimitive.long
        assertEquals(HttpStatusCode.Conflict, client.send("DELETE", "/api/teams/$teamA/members/$aliceId", alice).status)
        assertEquals(HttpStatusCode.NoContent, client.send("DELETE", "/api/teams/$teamA/members/$bobId", bob).status)
        assertEquals(listOf("Tohum"), titles(bob))

        // Üye sınırı (3) ve rol.
        client.send("POST", "/api/teams/$teamA/members", alice, """{"email":"bob@example.com","role":"owner"}""")
        assertEquals(HttpStatusCode.Created, client.send("POST", "/api/teams/$teamA/members", alice, """{"email":"carol@example.com"}""").status)
        val fourth = signup("dave@example.com")
        assertEquals(HttpStatusCode.Conflict, client.send("POST", "/api/teams/$teamA/members", alice, """{"user":${fourth.second}}""").status)
        assertEquals(HttpStatusCode.OK, client.send("PATCH", "/api/teams/$teamA/members/$aliceId", bob, """{"role":"member"}""").status)
        assertEquals(HttpStatusCode.Forbidden, client.send("DELETE", "/api/teams/$teamA", alice).status)

        // Yönetici hepsini görür; takım silinince kayıtları da silinir (cascade).
        val admin = "yonetici-anahtari-1234567890"
        assertEquals(2, client.send("GET", "/api/teams", admin).obj().getValue("items").jsonArray.size)
        assertEquals(HttpStatusCode.NoContent, client.send("DELETE", "/api/teams/$teamA", bob).status)
        assertEquals(listOf("Tohum"), titles(admin))
        assertEquals(HttpStatusCode.Unauthorized, client.send("GET", "/api/teams", null).status)
    }
}
