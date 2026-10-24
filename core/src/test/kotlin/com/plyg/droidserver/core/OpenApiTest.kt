package com.plyg.droidserver.core

import com.plyg.droidserver.core.api.ApiExamples
import com.plyg.droidserver.core.api.OpenApi
import com.plyg.droidserver.core.api.droidServerModule
import com.plyg.droidserver.core.auth.Caller
import com.plyg.droidserver.core.config.AuthConfig
import com.plyg.droidserver.core.config.AuthMode
import com.plyg.droidserver.core.config.CollectionConfig
import com.plyg.droidserver.core.config.EmailConfig
import com.plyg.droidserver.core.config.FieldConfig
import com.plyg.droidserver.core.config.FieldType
import com.plyg.droidserver.core.config.OnDelete
import com.plyg.droidserver.core.config.RealtimeConfig
import com.plyg.droidserver.core.config.Rule
import com.plyg.droidserver.core.config.Rules
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.config.SystemCollections
import com.plyg.droidserver.core.config.UsersConfig
import com.plyg.droidserver.core.db.SchemaMigrator
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenApiTest {
    private val todos = CollectionConfig(
        "todos",
        listOf(FieldConfig("title", FieldType.TEXT, required = true), FieldConfig("done", FieldType.BOOLEAN), FieldConfig("photo", FieldType.FILE)),
        rules = Rules(list = Rule.PUBLIC, view = Rule.PUBLIC),
        search = listOf("title"),
    )
    private val notes = CollectionConfig(
        "notes",
        listOf(FieldConfig("body", FieldType.TEXT), FieldConfig("author", FieldType.RELATION, collection = SystemCollections.USERS, onDelete = OnDelete.CASCADE)),
        rules = Rules(Rule.OWNER, Rule.OWNER, Rule.AUTH, Rule.OWNER, Rule.OWNER),
        ownerField = "author",
    )
    private val secret = CollectionConfig("secret", listOf(FieldConfig("x", FieldType.TEXT)))
    private val config = ServerConfig(
        name = "Evim",
        auth = AuthConfig(AuthMode.API_KEY, apiKeys = listOf("yonetici-anahtari-1234567890"), users = UsersConfig(enabled = true)),
        collections = listOf(todos, notes, secret),
        realtime = RealtimeConfig(enabled = true),
        email = EmailConfig(enabled = true, host = "smtp.example.com", from = "evim@example.com"),
    )

    private fun JsonObject.paths() = getValue("paths").jsonObject
    private fun JsonObject.methods(path: String) = paths()[path]?.jsonObject?.keys.orEmpty()

    @Test
    fun `dokuman isteyenin yetkisine gore`() {
        val anon = OpenApi.document(config, Caller.Anonymous)
        assertEquals("3.1.0", anon.getValue("openapi").jsonPrimitive.content)
        assertEquals(setOf("get"), anon.methods("/collections/todos/records"), "anonim yalnızca listeler")
        assertEquals(setOf("get"), anon.methods("/collections/todos/records/{id}"))
        assertTrue(anon.methods("/collections/notes/records").isEmpty() && anon.methods("/collections/secret/records").isEmpty())
        assertTrue("/auth/login" in anon.paths() && "/auth/request-reset" in anon.paths())
        assertFalse("/auth/users" in anon.paths() || "/_metrics" in anon.paths())
        assertTrue("todos" in anon.paths().getValue("/realtime").jsonObject.getValue("get").toString())
        assertTrue("/files/todos/{id}/{name}" in anon.paths())
        assertEquals(setOf("get"), anon.methods("/collections/todos/aggregate"))
        assertFalse("/batch" in anon.paths(), "anonim yazamaz")
        val list = anon.paths().getValue("/collections/todos/records").jsonObject.getValue("get").jsonObject
        assertFalse("security" in list, "herkese açık işlemde kimlik yok")
        assertTrue(list.getValue("parameters").jsonArray.any { it.jsonObject.getValue("name").jsonPrimitive.content == "q" })

        val user = OpenApi.document(config, Caller.User(1, false))
        assertEquals(setOf("get", "post"), user.methods("/collections/notes/records"))
        assertTrue("security" in user.paths().getValue("/collections/notes/records").jsonObject.getValue("get").jsonObject)
        assertTrue(user.methods("/collections/secret/records").isEmpty())
        val userBatch = user.paths().getValue("/batch").jsonObject.getValue("post").toString()
        assertTrue("\"notes\"" in userBatch && "\"secret\"" !in userBatch, userBatch)

        val admin = OpenApi.document(config, Caller.Admin)
        assertEquals(setOf("get", "post"), admin.methods("/collections/secret/records"))
        assertTrue("/_metrics" in admin.paths() && "/auth/users" in admin.paths())
        val adminBatch = admin.paths().getValue("/batch").jsonObject.getValue("post").toString()
        assertTrue("\"secret\"" in adminBatch && "\"todos\"" !in adminBatch, "dosya alanlı koleksiyon toplu işlemde yok: $adminBatch")
        val input = admin.getValue("components").jsonObject.getValue("schemas").jsonObject.getValue("todosInput").jsonObject
        assertEquals(listOf("title"), input.getValue("required").jsonArray.map { it.jsonPrimitive.content })
        assertFalse("photo" in input.getValue("properties").jsonObject, "dosya multipart ile")
        val record = admin.getValue("components").jsonObject.getValue("schemas").jsonObject.getValue("todos").jsonObject.getValue("properties").jsonObject
        assertEquals("date-time", record.getValue("created_at").jsonObject.getValue("format").jsonPrimitive.content)
    }

    @Test
    fun `uc ve istemci ornekleri`() = testApplication {
        val db = TestDb.create(Dialect.SQLITE).open()
        SchemaMigrator(db).migrate(config.schemaCollections())
        application { droidServerModule(config, db) }
        val r = client.get("/api/_openapi.json")
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals("Evim", Json.parseToJsonElement(r.bodyAsText()).jsonObject.getValue("info").jsonObject.getValue("title").jsonPrimitive.content)
        db.close()

        val examples = ApiExamples.forCollection(config, secret)
        val create = examples.first { it.method == "POST" }
        val js = create.js("http://192.168.1.5:8080", "Bearer ANAHTAR")
        assertTrue("fetch(\"http://192.168.1.5:8080/api/collections/secret/records\"" in js, js)
        assertTrue("JSON.stringify({\"x\":\"örnek x\"})" in js && "\"Authorization\": \"Bearer ANAHTAR\"" in js, js)
        val kt = create.kotlin("http://192.168.1.5:8080", "Bearer ANAHTAR")
        assertTrue(".post(body)" in kt && "toRequestBody" in kt && ".header(\"Authorization\", \"Bearer ANAHTAR\")" in kt, kt)

        val stream = ApiExamples.forCollection(config, todos).first { it.stream }
        val es = stream.js("http://h", "Bearer K")
        assertTrue("new EventSource(`http://h/api/realtime?collections=todos`)" in es, "herkese açık listede token yok: $es")
        val me = ApiExamples.forUsers(config).first { it.userToken }
        assertTrue("`Bearer \${token}`" in me.js("http://h", null), me.js("http://h", null))
        assertTrue("\"Bearer \$token\"" in me.kotlin("http://h", null))
        val upload = ApiExamples.forCollection(config, todos).first { it.form != null }
        assertTrue("new FormData()" in upload.js("http://h", "Bearer K") && "addFormDataPart(\"photo\"" in upload.kotlin("http://h", "Bearer K"))
    }
}
