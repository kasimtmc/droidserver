package dev.droidserver.core

import dev.droidserver.core.api.droidServerModule
import dev.droidserver.core.config.ApiConfig
import dev.droidserver.core.config.AuthConfig
import dev.droidserver.core.config.AuthMode
import dev.droidserver.core.config.CollectionConfig
import dev.droidserver.core.config.ConfigValidator
import dev.droidserver.core.config.FieldConfig
import dev.droidserver.core.config.FieldType
import dev.droidserver.core.config.Rule
import dev.droidserver.core.config.Rules
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.db.SchemaMigrator
import dev.droidserver.core.db.SqlDriver
import dev.droidserver.core.graphql.GqlField
import dev.droidserver.core.graphql.GqlFragmentSpread
import dev.droidserver.core.graphql.GqlParser
import dev.droidserver.core.graphql.GqlSyntaxException
import dev.droidserver.core.graphql.GqlValue
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** GraphQL: ayrıştırıcı, sorgu/mutasyon, ilişki genişletme, REST'le aynı kurallar ve hata biçimi. */
class GraphQLTest {
    private val key = "yonetici-anahtari-1234567890"
    private val projects = CollectionConfig(
        "projects", listOf(FieldConfig("name", FieldType.TEXT, required = true)),
        rules = Rules(list = Rule.PUBLIC, view = Rule.PUBLIC),
    )
    private val tasks = CollectionConfig(
        "tasks",
        listOf(
            FieldConfig("title", FieldType.TEXT, required = true),
            FieldConfig("done", FieldType.BOOLEAN),
            FieldConfig("points", FieldType.INTEGER),
            FieldConfig("project", FieldType.RELATION, collection = "projects"),
        ),
    )
    private val config = ServerConfig(auth = AuthConfig(AuthMode.API_KEY, apiKeys = listOf(key)), collections = listOf(projects, tasks), api = ApiConfig(graphql = true))
    private lateinit var db: SqlDriver

    @BeforeTest
    fun open() {
        db = TestDb.create(Dialect.SQLITE).open()
        SchemaMigrator(db).migrate(config.schemaCollections())
    }

    @AfterTest
    fun close() = db.close()

    @Test
    fun `ayristirici`() {
        val doc = GqlParser.parse(
            """
            # yorum
            query Liste(${'$'}n: Int = 5, ${'$'}f: String) {
              ilk: tasks(perPage: ${'$'}n, filter: ${'$'}f, sort: "-id") { items { ...T id @include(if: true) } totalItems }
              x: tasks_by_id(id: "3") { title }
            }
            fragment T on Tasks { title done project { name } }
            mutation M { create_tasks(data: {title: ""${'"'}çok
              satırlı""${'"'}, points: -12, ratio: 1.5e2, tags: [A, null]}) { id } }
            """.trimIndent(),
        )
        assertEquals(2, doc.operations.size)
        val q = doc.operations.first()
        assertEquals("Liste", q.name)
        assertEquals(listOf("n", "f"), q.variables.map { it.name })
        assertEquals(JsonPrimitive(5L), q.variables.first().default!!.resolve(emptyMap()))
        val ilk = q.selection.first() as GqlField
        assertEquals("ilk", ilk.key)
        assertEquals("tasks", ilk.name)
        assertTrue(ilk.args.getValue("perPage") is GqlValue.Variable)
        assertTrue((ilk.selection!!.first() as GqlField).selection!!.first() is GqlFragmentSpread)
        assertEquals("Tasks", doc.fragments.getValue("T").typeCondition)
        val data = ((doc.operations[1].selection.single() as GqlField).args.getValue("data") as GqlValue.ObjectValue).resolve(emptyMap()).jsonObject
        assertEquals("çok\nsatırlı", data.getValue("title").jsonPrimitive.content)
        assertEquals("-12", data.getValue("points").toString())
        assertEquals(150.0, data.getValue("ratio").jsonPrimitive.content.toDouble())
        assertEquals("[\"A\",null]", data.getValue("tags").toString())
        assertFailsWith<GqlSyntaxException> { GqlParser.parse("{ tasks { ") }
        assertFailsWith<GqlSyntaxException> { GqlParser.parse("subscription { tasks }") }
        assertFailsWith<GqlSyntaxException> { GqlParser.parse("{ a(x: \"kapanmamış) }") }
        assertFailsWith<GqlSyntaxException> { GqlParser.parse("{" + "a {".repeat(10) + "b" + "}".repeat(11)) }
    }

    private suspend fun ApplicationTestBuilder.gql(query: String, variables: JsonObject? = null, admin: Boolean = true): JsonObject {
        val res = client.post("/api/graphql") {
            if (admin) bearerAuth(key)
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("query", JsonPrimitive(query)); variables?.let { put("variables", it) } }.toString())
        }
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        return Json.parseToJsonElement(res.bodyAsText()).jsonObject
    }

    private fun JsonObject.data(vararg path: Any): kotlinx.serialization.json.JsonElement =
        path.fold(getValue("data") as kotlinx.serialization.json.JsonElement) { e, p -> if (p is Int) e.jsonArray[p] else e.jsonObject.getValue(p as String) }

    private fun JsonObject.errors() = (this["errors"]?.jsonArray ?: emptyList()).map { it.jsonObject }

    @Test
    fun `sorgu ve mutasyonlar REST kurallariyla calisir`() = testApplication {
        assertEquals(emptyList(), ConfigValidator.validate(config))
        application { droidServerModule(config, db) }

        val p = gql("""mutation { p: create_projects(data: {name: "Ev"}) { id name __typename } }""")
        assertEquals(emptyList(), p.errors())
        assertEquals("Projects", p.data("p", "__typename").jsonPrimitive.content)
        val pid = p.data("p", "id").jsonPrimitive.content

        val vars = buildJsonObject { put("d", buildJsonObject { put("title", JsonPrimitive("süt al")); put("points", JsonPrimitive(3)); put("project", JsonPrimitive(pid.toLong())) }) }
        val created = gql("mutation Ekle(\$d: JSON!) { create_tasks(data: \$d) { id title points } }", vars)
        val tid = created.data("create_tasks", "id").jsonPrimitive.content
        gql("""mutation { a: create_tasks(data: {title: "ekmek", done: true}) { id } b: create_tasks(data: {title: "yumurta", points: 8}) { id } }""")

        val list = gql(
            """{
              açık: tasks(filter: "points[gte]=1", sort: "-points", perPage: 10) { totalItems page items { ...F } }
              tek: tasks_by_id(id: $tid) { title project { name __typename } }
            }
            fragment F on Tasks { title points }""".replace("açık", "acik"),
        )
        assertEquals(emptyList(), list.errors(), list.toString())
        assertEquals(2, list.data("acik", "totalItems").jsonPrimitive.content.toInt())
        assertEquals(listOf("yumurta", "süt al"), list.data("acik", "items").jsonArray.map { it.jsonObject.getValue("title").jsonPrimitive.content })
        assertEquals("Ev", list.data("tek", "project", "name").jsonPrimitive.content)
        // Alt seçimsiz ilişki alanı kimliği döndürür.
        assertEquals(pid, gql("{ tasks_by_id(id: $tid) { project } }").data("tasks_by_id", "project").jsonPrimitive.content)

        val updated = gql("mutation { update_tasks(id: \"$tid\", data: {done: true}) { done } }")
        assertEquals("true", updated.data("update_tasks", "done").toString())

        // Kısmi hata: biri başarılı, diğeri 404; veri null ve hata yolu/kodu verilir.
        val partial = gql("{ var: tasks_by_id(id: $tid) { title } yok: tasks_by_id(id: 999) { title } }")
        assertEquals("süt al", partial.data("var", "title").jsonPrimitive.content)
        assertEquals(JsonNull, partial.data("yok"))
        val err = partial.errors().single()
        assertEquals("[\"yok\"]", err.getValue("path").toString())
        assertEquals("404", err.getValue("extensions").jsonObject.getValue("code").toString())

        // Doğrulama hatası REST'teki ayrıntılarla.
        val invalid = gql("""mutation { create_tasks(data: {points: 1}) { id } }""")
        assertEquals("400", invalid.errors().single().getValue("extensions").jsonObject.getValue("code").toString())
        assertTrue(invalid.errors().single().getValue("extensions").jsonObject.getValue("details").jsonObject.containsKey("title"))

        // Bilinmeyen alan ve tip.
        assertTrue(gql("{ tasks { items { yok } } }").errors().single().getValue("message").jsonPrimitive.content.contains("'yok'"))
        assertTrue(gql("{ bilinmeyen { id } }").errors().isNotEmpty())
        assertTrue(gql("{ tasks_by_id(id: $tid) { title { x } } }").errors().isNotEmpty())
        assertTrue(gql("{ __schema { types { name } } }").errors().single().getValue("message").jsonPrimitive.content.contains("/graphql/schema"))

        // Kurallar: projeler herkese açık okunur, görevler yalnızca yöneticiye.
        val anon = gql("{ projects { items { name } } tasks { totalItems } }", admin = false)
        assertEquals("Ev", anon.data("projects", "items", 0, "name").jsonPrimitive.content)
        assertEquals(JsonNull, anon.data("tasks"))
        assertTrue(anon.errors().single().getValue("extensions").jsonObject.getValue("code").jsonPrimitive.content in setOf("401", "403"))
        assertTrue(gql("""mutation { create_projects(data: {name: "x"}) { id } }""", admin = false).errors().isNotEmpty())

        val del = gql("mutation { delete_tasks(id: $tid) { id deleted } }")
        assertEquals("true", del.data("delete_tasks", "deleted").toString())
        assertEquals(JsonNull, gql("{ tasks_by_id(id: $tid) { id } }").data("tasks_by_id"))

        val sdl = client.get("/api/graphql/schema").bodyAsText()
        assertTrue("type Query {" in sdl && "tasks(filter: String, sort: String, page: Int, perPage: Int, q: String): TasksPage!" in sdl, sdl)
        assertTrue("create_tasks(data: JSON!): Tasks!" in sdl && "project: Projects" in sdl && "points: Int" in sdl, sdl)
        // Sözdizimi hatası HTTP 200 ile errors.
        assertTrue(gql("{ tasks { ").errors().single().getValue("message").jsonPrimitive.content.isNotBlank())
    }

    @Test
    fun `kapaliyken uc yok`() = testApplication {
        application { droidServerModule(config.copy(api = ApiConfig()), db) }
        assertEquals(HttpStatusCode.NotFound, client.post("/api/graphql") { bearerAuth(key); contentType(ContentType.Application.Json); setBody("""{"query":"{ tasks { totalItems } }"}""") }.status)
    }
}
