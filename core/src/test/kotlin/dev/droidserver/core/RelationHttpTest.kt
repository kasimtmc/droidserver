package dev.droidserver.core

import dev.droidserver.core.api.droidServerModule
import dev.droidserver.core.config.AuthConfig
import dev.droidserver.core.config.AuthMode
import dev.droidserver.core.config.CollectionConfig
import dev.droidserver.core.config.Rules
import dev.droidserver.core.config.Rule
import dev.droidserver.core.config.ConfigMigrations
import dev.droidserver.core.config.ConfigValidator
import dev.droidserver.core.config.FieldConfig
import dev.droidserver.core.config.FieldType
import dev.droidserver.core.config.OnDelete
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.config.TypeConversions
import dev.droidserver.core.db.SchemaMigrator
import dev.droidserver.core.db.SqlDriver
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RelationHttpTest {
    private val key = "dogru-anahtar-1234567890"
    private val authors = CollectionConfig("authors", listOf(FieldConfig("name", FieldType.TEXT)))
    private val posts = CollectionConfig(
        "posts",
        listOf(FieldConfig("title", FieldType.TEXT), FieldConfig("author", FieldType.RELATION, collection = "authors")),
        rules = Rules(list = Rule.PUBLIC, view = Rule.PUBLIC),
    )
    private val config = ServerConfig(
        auth = AuthConfig(AuthMode.API_KEY, apiKeys = listOf(key)),
        collections = listOf(authors, posts),
    )
    private lateinit var db: SqlDriver

    @Before
    fun open() {
        db = TestDb.create(Dialect.SQLITE).open()
        SchemaMigrator(db).migrate(config.collections)
    }

    @After
    fun close() = db.close()

    @Test
    fun `herkese acik uctan korumali koleksiyona expand kimlik ister`() = testApplication {
        application { droidServerModule(config, db) }
        suspend fun post(path: String, body: String) = client.post(path) {
            bearerAuth(key); contentType(ContentType.Application.Json); setBody(body)
        }
        assertEquals(HttpStatusCode.Created, post("/api/collections/authors/records", """{"name":"Ali"}""").status)
        assertEquals(HttpStatusCode.Created, post("/api/collections/posts/records", """{"title":"Bir","author":1}""").status)
        val bad = post("/api/collections/posts/records", """{"title":"İki","author":42}""")
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        assertTrue("42 kimlikli kayıt yok" in bad.bodyAsText(), bad.bodyAsText())

        val url = "/api/collections/posts/records"
        assertEquals(HttpStatusCode.OK, client.get(url).status, "herkese açık okuma sürer")
        val leak = client.get("$url?expand=author")
        assertEquals(HttpStatusCode.Unauthorized, leak.status, "korumalı yazar kaydı sızmamalı")
        assertEquals(HttpStatusCode.Unauthorized, client.get("$url?expand=author") { bearerAuth("yanlis") }.status)
        val ok = client.get("$url?expand=author") { bearerAuth(key) }
        assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
        val item = Json.parseToJsonElement(ok.bodyAsText()).jsonObject.getValue("items").let { it as kotlinx.serialization.json.JsonArray }[0].jsonObject
        assertEquals("Ali", item.getValue("expand").jsonObject.getValue("author").jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals(HttpStatusCode.OK, client.get("$url/1?expand=author") { bearerAuth(key) }.status)

        // Yazarın yazısı var; set_null (varsayılan, alan zorunlu değil): silinince alan boşalır.
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/collections/authors/records/1") { bearerAuth(key) }.status)
        val after = Json.parseToJsonElement(client.get("$url/1").bodyAsText()).jsonObject
        assertEquals(kotlinx.serialization.json.JsonNull, after["author"])
    }

    @Test
    fun `iliski alani dogrulama, surum ve tip donusumu`() {
        fun errors(vararg fields: FieldConfig) =
            ConfigValidator.validateDetailed(ServerConfig(collections = listOf(authors, CollectionConfig("posts", fields.toList())))).map { it.path to it.message }

        assertTrue(errors(FieldConfig("author", FieldType.RELATION)).any { it.first == "collections[1].fields[0].collection" })
        assertTrue(errors(FieldConfig("author", FieldType.RELATION, collection = "yok")).any { "'yok' yok" in it.second })
        assertTrue(
            errors(FieldConfig("author", FieldType.RELATION, required = true, collection = "authors", onDelete = OnDelete.SET_NULL))
                .any { it.first.endsWith("onDelete") },
        )
        assertTrue(errors(FieldConfig("n", FieldType.INTEGER, collection = "authors")).any { "ilişki alanı değil" in it.second })
        assertTrue(errors(FieldConfig("author", FieldType.RELATION, collection = "authors")).isEmpty())
        assertEquals(OnDelete.RESTRICT, FieldConfig("a", FieldType.RELATION, required = true, collection = "x").effectiveOnDelete)
        assertEquals(OnDelete.SET_NULL, FieldConfig("a", FieldType.RELATION, collection = "x").effectiveOnDelete)

        assertEquals(19, ConfigMigrations.CURRENT)
        val json = ServerConfig(collections = listOf(authors, posts)).toJson()
        assertTrue("\"relation\"" in json && "\"collection\": \"authors\"" in json, json)
        assertTrue("onDelete" !in json, "belirtilmeyen davranış dosyaya yazılmaz")

        assertEquals(7L, TypeConversions.convert(FieldType.INTEGER, FieldType.RELATION, 7L))
        assertEquals(null, TypeConversions.convert(FieldType.INTEGER, FieldType.RELATION, 0L))
        assertEquals(12L, TypeConversions.convert(FieldType.TEXT, FieldType.RELATION, " 12 "))
        assertEquals("7", TypeConversions.convert(FieldType.RELATION, FieldType.TEXT, 7L))
        assertTrue(!TypeConversions.canConvert(FieldType.BOOLEAN, FieldType.RELATION))
    }
}
