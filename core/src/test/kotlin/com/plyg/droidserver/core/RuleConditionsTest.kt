package com.plyg.droidserver.core

import com.plyg.droidserver.core.api.Access
import com.plyg.droidserver.core.api.RecordService
import com.plyg.droidserver.core.api.RuleConditions
import com.plyg.droidserver.core.api.Scope
import com.plyg.droidserver.core.api.droidServerModule
import com.plyg.droidserver.core.auth.Caller
import com.plyg.droidserver.core.config.AuthConfig
import com.plyg.droidserver.core.config.AuthMode
import com.plyg.droidserver.core.config.CollectionConfig
import com.plyg.droidserver.core.config.ConfigValidator
import com.plyg.droidserver.core.config.FieldConfig
import com.plyg.droidserver.core.config.FieldType
import com.plyg.droidserver.core.config.FieldVisibility
import com.plyg.droidserver.core.config.OnDelete
import com.plyg.droidserver.core.config.Rule
import com.plyg.droidserver.core.config.RuleCondition
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
import kotlin.test.assertTrue

/** Koşullu kurallar ve alan gizliliği, iki lehçede. */
@RunWith(Parameterized::class)
class RuleConditionsTest(private val dialect: Dialect) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun dialects() = Dialect.entries.toList()
    }

    private val key = "yonetici-anahtari-1234567890"
    private val published = RuleCondition("status=published", owner = true)

    // Blog: herkes yayınlananları, yazar taslaklarını da görür; yalnızca taslak eklenir ve taslak düzenlenir.
    private val posts = CollectionConfig(
        "posts",
        listOf(
            FieldConfig("title", FieldType.TEXT, required = true),
            FieldConfig("status", FieldType.TEXT, default = JsonPrimitive("draft")),
            FieldConfig("notes", FieldType.TEXT, visibility = FieldVisibility.OWNER),
            FieldConfig("score", FieldType.INTEGER, visibility = FieldVisibility.ADMIN),
            FieldConfig("author", FieldType.RELATION, collection = SystemCollections.USERS, onDelete = OnDelete.CASCADE),
        ),
        rules = Rules(
            Rule.PUBLIC, Rule.PUBLIC, Rule.AUTH, Rule.OWNER, Rule.OWNER,
            conditions = mapOf("list" to published, "view" to published, "create" to RuleCondition("status=draft"), "update" to RuleCondition("status=draft")),
        ),
        ownerField = "author",
    )

    // Görevler: herkes yalnızca kendine atananları görür.
    private val tasks = CollectionConfig(
        "tasks",
        listOf(
            FieldConfig("title", FieldType.TEXT),
            FieldConfig("assignee", FieldType.RELATION, collection = SystemCollections.USERS, onDelete = OnDelete.SET_NULL),
            FieldConfig("post", FieldType.RELATION, collection = "posts", onDelete = OnDelete.SET_NULL),
        ),
        rules = Rules(Rule.AUTH, Rule.AUTH, conditions = mapOf("list" to RuleCondition("assignee=@me"), "view" to RuleCondition("assignee=@me"))),
    )

    private val config = ServerConfig(
        auth = AuthConfig(AuthMode.API_KEY, apiKeys = listOf(key), users = UsersConfig(enabled = true)),
        collections = listOf(posts, tasks),
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
    private suspend fun HttpClient.titles(path: String, token: String?): List<String> {
        val r = send("GET", path, token)
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return json(r.bodyAsText()).getValue("items").jsonArray.map { it.jsonObject.getValue("title").jsonPrimitive.content }.sorted()
    }

    @Test
    fun `kosullu kurallar ve gizli alanlar`() = testApplication {
        application { droidServerModule(config, db) }
        val (aId, a) = client.signup("a@example.com")
        val (bId, b) = client.signup("b@example.com")
        val base = "/api/collections/posts/records"

        // Oluşturma koşulu: yalnızca taslak; gizli yönetici alanı yazılamaz.
        val draft = json(client.send("POST", base, a, """{"title":"A taslak","notes":"özel"}""").bodyAsText())
        assertEquals("özel", draft.getValue("notes").jsonPrimitive.content, "sahibi kendi notunu görür")
        assertFalse("score" in draft, "yönetici alanı gizli")
        assertEquals(HttpStatusCode.Forbidden, client.send("POST", base, a, """{"title":"x","status":"published"}""").status)
        assertTrue("yalnızca yönetici" in client.send("POST", base, a, """{"title":"x","score":5}""").bodyAsText())
        val draftId = draft.getValue("id").jsonPrimitive.content
        // Yönetici yayınlanmış kayıtlar ekler (koşula bağlı değil).
        client.send("POST", base, key, """{"title":"A yayın","status":"published","author":$aId,"notes":"a notu","score":9}""")
        client.send("POST", base, key, """{"title":"B yayın","status":"published","author":$bId,"notes":"b notu","score":3}""")
        client.send("POST", base, key, """{"title":"B taslak","author":$bId}""")

        assertEquals(listOf("A yayın", "B yayın"), client.titles(base, null), "anonim yalnızca yayınlananlar")
        assertEquals(listOf("A taslak", "A yayın", "B yayın"), client.titles(base, a), "sahibi taslaklarını da görür")
        assertEquals(listOf("A taslak", "A yayın", "B taslak", "B yayın"), client.titles(base, key))
        assertEquals(HttpStatusCode.NotFound, client.send("GET", "$base/$draftId", b).status, "başkasının taslağı yok gibi")
        assertEquals(HttpStatusCode.OK, client.send("GET", "$base/$draftId", a).status)

        // Gizlilik: notlar sahibine, puan yöneticiye.
        val items = json(client.send("GET", base, b).bodyAsText()).getValue("items").jsonArray.map { it.jsonObject }
        val mine = items.first { it.getValue("title").jsonPrimitive.content == "B yayın" }
        val theirs = items.first { it.getValue("title").jsonPrimitive.content == "A yayın" }
        assertEquals("b notu", mine.getValue("notes").jsonPrimitive.content)
        assertFalse("notes" in theirs || "score" in mine)
        val selected = json(client.send("GET", "$base?fields=title,notes", b).bodyAsText()).getValue("items").jsonArray.map { it.jsonObject }
        assertEquals(setOf("id", "title", "notes"), selected.first { it.getValue("title").jsonPrimitive.content == "B yayın" }.keys, "alan seçiminde de sahibi görür")
        assertEquals(setOf("id", "title"), selected.first { it.getValue("title").jsonPrimitive.content == "A yayın" }.keys)
        assertTrue("score" in json(client.send("GET", "$base?sort=-score", key).bodyAsText()).getValue("items").jsonArray.first().jsonObject)
        // Gizli alana göre filtre, sıralama, özet: değeri ele vereceği için yönetici dışında reddedilir.
        assertEquals(HttpStatusCode.BadRequest, client.send("GET", "$base?score[gt]=5", b).status)
        assertEquals(HttpStatusCode.BadRequest, client.send("GET", "$base?sort=notes", b).status)
        assertEquals(HttpStatusCode.BadRequest, client.send("GET", "/api/collections/posts/aggregate?max=score", null).status)
        assertEquals("2", json(client.send("GET", "/api/collections/posts/aggregate", null).bodyAsText()).getValue("count").jsonPrimitive.content, "özet de koşula uyar")

        // Güncelleme koşulu mevcut kayıtta: taslak yayınlanabilir, yayınlanmış olan düzenlenemez.
        val publishedNow = client.send("PATCH", "$base/$draftId", a, """{"status":"published"}""")
        assertEquals(HttpStatusCode.OK, publishedNow.status, publishedNow.bodyAsText())
        assertEquals(HttpStatusCode.NotFound, client.send("PATCH", "$base/$draftId", a, """{"title":"değişti"}""").status)
        assertEquals(HttpStatusCode.OK, client.send("PATCH", "$base/$draftId", key, """{"title":"değişti"}""").status)

        // @me: görevler yalnızca atanana.
        val tbase = "/api/collections/tasks/records"
        val postId = json(client.send("GET", base, key).bodyAsText()).getValue("items").jsonArray.first().jsonObject.getValue("id").jsonPrimitive.content
        client.send("POST", tbase, key, """{"title":"a işi","assignee":$aId,"post":$postId}""")
        client.send("POST", tbase, key, """{"title":"b işi","assignee":$bId}""")
        client.send("POST", tbase, key, """{"title":"sahipsiz"}""")
        assertEquals(listOf("a işi"), client.titles(tbase, a))
        assertEquals(listOf("b işi"), client.titles(tbase, b))
        // Genişletmede hedefin koşulu ve gizliliği: B, A'nın yayınlanmış yazısını görür ama notunu görmez.
        val expanded = json(client.send("GET", "$tbase?expand=post", a).bodyAsText()).getValue("items").jsonArray.single().jsonObject
        assertTrue("notes" in expanded.getValue("expand").jsonObject.getValue("post").jsonObject, "A kendi yazısının notunu görür")

        // Toplu işlemde de koşul ve gizlilik.
        val batch = client.send("POST", "/api/batch", b, """{"requests":[{"method":"POST","collection":"posts","body":{"title":"B yeni","notes":"n"}}]}""")
        assertEquals(HttpStatusCode.OK, batch.status, batch.bodyAsText())
        assertFalse("score" in batch.bodyAsText())
        assertEquals(HttpStatusCode.Forbidden, client.send("POST", "/api/batch", b, """{"requests":[{"method":"POST","collection":"posts","body":{"title":"x","status":"published"}}]}""").status)
    }

    @Test
    fun `bellekteki eslesme SQL ile ayni`() {
        val svc = RecordService(db, posts, collections = config.schemaCollections())
        val records = listOf(
            """{"title":"a","status":"published","notes":"x"}""",
            """{"title":"b","status":"draft"}""",
            """{"title":"c","status":"archived","notes":"y"}""",
            """{"title":"d"}""",
        ).map { svc.create(json(it)) }
        val filters = listOf(
            "status=published", "status[ne]=draft", "status[in]=draft,archived", "notes[null]=true", "notes[null]=false",
            "id[gt]=2", "id[lte]=1&status=published", "created_at[gte]=2000-01-01T00:00:00Z", "status[ne]=published&notes[null]=false",
        )
        for (f in filters) {
            val conds = RuleConditions.parse(posts, f, null)
            val scope = Scope(admin = false, condition = conds)
            val viaSql = svc.list(1, 100, null, emptyList(), scope = Scope(admin = true, condition = conds)).items.map { it.getValue("id") }.toSet()
            val inMemory = records.filter { RuleConditions.visible(posts, it, scope) }.map { it.getValue("id") }.toSet()
            assertEquals(viaSql, inMemory, f)
        }
    }

    @Test
    fun `canli abonelik kapsami ve dogrulama`() {
        val record = buildJsonObject {
            put("id", JsonPrimitive(1)); put("title", JsonPrimitive("t")); put("status", JsonPrimitive("draft"))
            put("notes", JsonPrimitive("n")); put("score", JsonPrimitive(3)); put("author", JsonPrimitive(7))
        }
        val owner = Access.scope(posts, Rules.Op.VIEW, Caller.User(7, false))
        val other = Access.scope(posts, Rules.Op.VIEW, Caller.User(8, false))
        assertTrue(RuleConditions.visible(posts, record, owner))
        assertFalse(RuleConditions.visible(posts, record, other), "taslak başkasına gitmez")
        assertFalse(RuleConditions.visible(posts, record, Access.scope(posts, Rules.Op.VIEW, Caller.Anonymous)))
        assertEquals(setOf("id", "title", "status", "notes", "author"), RuleConditions.present(posts, record, owner).keys)
        assertEquals(record, RuleConditions.present(posts, record, Access.scope(posts, Rules.Op.VIEW, Caller.Admin)))
        val me = Access.scope(tasks, Rules.Op.LIST, Caller.User(5, false)).condition.single()
        assertEquals(5L, me.values.single(), "@me istekteki kullanıcı")

        fun errors(c: CollectionConfig) = ConfigValidator.validate(config.copy(collections = listOf(c, tasks)))
        assertTrue(errors(posts).isEmpty(), errors(posts).toString())
        assertTrue(errors(posts.copy(rules = posts.rules.withCondition(Rules.Op.LIST, RuleCondition("title[like]=a")))).any { "like" in it })
        assertTrue(errors(posts.copy(rules = posts.rules.withCondition(Rules.Op.LIST, RuleCondition("yok=1")))).any { "bilinmeyen alan" in it })
        assertTrue(errors(posts.copy(rules = posts.rules.withCondition(Rules.Op.LIST, RuleCondition("title=@me")))).any { "@me" in it })
        assertTrue(errors(posts.copy(rules = posts.rules.withCondition(Rules.Op.CREATE, RuleCondition("status=draft", owner = true)))).any { "sahip istisnası" in it })
        assertTrue(errors(posts.copy(rules = posts.rules.with(Rules.Op.LIST, Rule.ADMIN))).any { "yönetici koşula bağlı değildir" in it })
        assertTrue(errors(posts.copy(search = listOf("notes"))).any { "aranabilir" in it })
        assertTrue(errors(posts.copy(fields = posts.fields.map { if (it.name == "author") it.copy(visibility = FieldVisibility.OWNER) else it })).any { "gizlenemez" in it })
        assertTrue(errors(posts.copy(fields = posts.fields.map { if (it.name == "score") it.copy(required = true) else it })).any { "varsayılan değer" in it })
        val json = ServerConfig.fromJson(config.toJson())
        assertEquals(config.collections, json.collections, "koşul ve görünürlük dosyaya yazılır ve okunur")
    }
}
