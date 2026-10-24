package com.plyg.droidserver.core

import com.plyg.droidserver.core.api.RecordService
import com.plyg.droidserver.core.api.droidServerModule
import com.plyg.droidserver.core.config.AuthConfig
import com.plyg.droidserver.core.config.AuthMode
import com.plyg.droidserver.core.config.CollectionConfig
import com.plyg.droidserver.core.config.ConfigValidator
import com.plyg.droidserver.core.config.FieldConfig
import com.plyg.droidserver.core.config.FieldType
import com.plyg.droidserver.core.config.HistoryConfig
import com.plyg.droidserver.core.config.OnDelete
import com.plyg.droidserver.core.config.Rule
import com.plyg.droidserver.core.config.Rules
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.config.TrashConfig
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Faz 12.2: kayıt geçmişi, sürüme dönme ve çöp kutusu, iki lehçede. */
@RunWith(Parameterized::class)
class HistoryTrashTest(private val dialect: Dialect) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun dialects() = Dialect.entries.toList()
    }

    private val key = "yonetici-anahtari-1234567890"
    private val lists = CollectionConfig(
        "lists",
        listOf(FieldConfig("title", FieldType.TEXT, required = true), FieldConfig("qty", FieldType.INTEGER)),
        rules = Rules(Rule.PUBLIC, Rule.PUBLIC, Rule.PUBLIC, Rule.PUBLIC, Rule.PUBLIC),
        history = HistoryConfig(keep = 3),
        trash = TrashConfig(days = 7),
        systemFields = mapOf("id" to com.plyg.droidserver.core.config.SystemFieldConfig(name = "kimlik")),
    )
    private val items = CollectionConfig(
        "items",
        listOf(
            FieldConfig("name", FieldType.TEXT),
            FieldConfig("list", FieldType.RELATION, collection = "lists", onDelete = OnDelete.CASCADE),
        ),
        trash = TrashConfig(),
    )
    private val plain = CollectionConfig("plain", listOf(FieldConfig("x", FieldType.TEXT)))
    private val config = ServerConfig(auth = AuthConfig(AuthMode.API_KEY, apiKeys = listOf(key)), collections = listOf(lists, items, plain))
    private lateinit var db: SqlDriver

    @Before
    fun open() {
        db = TestDb.create(dialect).open()
        SchemaMigrator(db).migrate(config.schemaCollections())
    }

    @After
    fun close() = db.close()

    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject
    private suspend fun HttpClient.send(method: String, path: String, body: String? = null, token: String? = key): HttpResponse {
        val block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            token?.let { bearerAuth(it) }
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }
        return when (method) {
            "GET" -> get(path, block)
            "POST" -> post(path, block)
            "PATCH" -> patch(path, block)
            else -> delete(path, block)
        }
    }
    private suspend fun HttpClient.ok(method: String, path: String, body: String? = null, status: HttpStatusCode = HttpStatusCode.OK): JsonObject {
        val r = send(method, path, body)
        assertEquals(status, r.status, "$method $path: ${r.bodyAsText()}")
        return if (status == HttpStatusCode.NoContent) JsonObject(emptyMap()) else json(r.bodyAsText())
    }
    private fun JsonObject.items() = getValue("items").jsonArray.map { it.jsonObject }

    @Test
    fun `gecmis, surume donme, cop kutusu ve geri yukleme`() = testApplication {
        application { droidServerModule(config, db) }
        assertTrue(ConfigValidator.validate(config).isEmpty(), ConfigValidator.validate(config).toString())
        val base = "/api/collections/lists"

        val id = client.ok("POST", "$base/records", """{"title":"Market","qty":1}""", HttpStatusCode.Created).getValue("kimlik").jsonPrimitive.content
        for (q in 2..4) client.ok("PATCH", "$base/records/$id", """{"qty":$q}""")

        // Kayıt başına 3 sürüm: create silindi, en yeni önce; veri API adlarıyla.
        var history = client.ok("GET", "$base/records/$id/history").items()
        assertEquals(listOf(4L, 3L, 2L), history.map { it.getValue("version").jsonPrimitive.content.toLong() })
        assertEquals(listOf("update", "update", "update"), history.map { it.getValue("action").jsonPrimitive.content })
        assertEquals("admin", history.first().getValue("actor").jsonPrimitive.content)
        assertEquals("4", history.first().getValue("data").jsonObject.getValue("qty").jsonPrimitive.content)
        assertTrue("kimlik" in history.first().getValue("data").jsonObject)

        // Sürüme dönme yeni sürüm olarak.
        val reverted = client.ok("POST", "$base/records/$id/history/2/revert")
        assertEquals("2", reverted.getValue("qty").jsonPrimitive.content)
        history = client.ok("GET", "$base/records/$id/history").items()
        assertEquals("revert", history.first().getValue("action").jsonPrimitive.content)
        assertEquals(HttpStatusCode.NotFound, client.send("POST", "$base/records/$id/history/1/revert").status, "silinen sürüm")

        // Alan bazında: yalnızca seçilen alan döner, diğerleri olduğu gibi kalır.
        client.ok("PATCH", "$base/records/$id", """{"title":"Pazar","qty":9}""")
        val one = client.ok("POST", "$base/records/$id/history/5/revert?fields=title")
        assertEquals("Market", one.getValue("title").jsonPrimitive.content)
        assertEquals("9", one.getValue("qty").jsonPrimitive.content)
        assertEquals(HttpStatusCode.BadRequest, client.send("POST", "$base/records/$id/history/5/revert?fields=yok").status)
        client.ok("POST", "$base/records/$id/history/5/revert?fields=qty")

        // Kural herkese açık olsa da geçmiş ve çöp kutusu yöneticiye.
        assertEquals(HttpStatusCode.Unauthorized, client.send("GET", "$base/records/$id/history", token = null).status)
        assertEquals(HttpStatusCode.Unauthorized, client.send("GET", "$base/trash", token = null).status)

        // Silme: kayıt ve cascade ile bağlı kayıt çöp kutusuna.
        val itemId = client.ok("POST", "/api/collections/items/records", """{"name":"süt","list":$id}""", HttpStatusCode.Created)
            .getValue("id").jsonPrimitive.content
        client.ok("DELETE", "$base/records/$id", status = HttpStatusCode.NoContent)
        assertEquals(HttpStatusCode.NotFound, client.send("GET", "$base/records/$id").status)
        val trash = client.ok("GET", "$base/trash").items()
        assertEquals(listOf(id), trash.map { it.getValue("id").jsonPrimitive.content })
        assertEquals("Market", trash.single().getValue("data").jsonObject.getValue("title").jsonPrimitive.content)
        assertEquals(itemId, client.ok("GET", "/api/collections/items/trash").items().single().getValue("id").jsonPrimitive.content)
        assertEquals("delete", client.ok("GET", "$base/records/$id/history").items().first().getValue("action").jsonPrimitive.content)
        assertEquals(HttpStatusCode.NotFound, client.send("POST", "$base/records/$id/history/4/revert").status, "önce geri yüklenmeli")

        // Bağlı kayıt, hedefi çöp kutusundayken geri yüklenemez; önce liste.
        assertEquals(HttpStatusCode.BadRequest, client.send("POST", "/api/collections/items/trash/$itemId/restore").status)
        val restored = client.ok("POST", "$base/trash/$id/restore")
        assertEquals(id, restored.getValue("kimlik").jsonPrimitive.content)
        assertEquals("2", restored.getValue("qty").jsonPrimitive.content)
        assertEquals("süt", client.ok("POST", "/api/collections/items/trash/$itemId/restore").getValue("name").jsonPrimitive.content)
        assertEquals("Market", client.ok("GET", "$base/records/$id").getValue("title").jsonPrimitive.content)
        assertEquals("restore", client.ok("GET", "$base/records/$id/history").items().first().getValue("action").jsonPrimitive.content)
        assertEquals(HttpStatusCode.NotFound, client.send("POST", "$base/trash/$id/restore").status)
        assertTrue(client.ok("GET", "$base/trash").items().isEmpty())

        // Kalıcı silme ve süre dolunca temizlik.
        client.ok("DELETE", "/api/collections/items/records/$itemId", status = HttpStatusCode.NoContent)
        client.ok("DELETE", "/api/collections/items/trash/$itemId", status = HttpStatusCode.NoContent)
        assertEquals(HttpStatusCode.NotFound, client.send("POST", "/api/collections/items/trash/$itemId/restore").status)
        client.ok("DELETE", "$base/records/$id", status = HttpStatusCode.NoContent)
        assertEquals(0, RecordService.purgeExpired(db, null, Instant.now().plus(Duration.ofDays(6))))
        assertEquals(1, RecordService.purgeExpired(db, null, Instant.now().plus(Duration.ofDays(8))))
        assertTrue(client.ok("GET", "$base/trash").items().isEmpty())

        // Kapalı koleksiyonda uçlar yok.
        assertEquals(HttpStatusCode.NotFound, client.send("GET", "/api/collections/plain/trash").status)
        assertEquals(HttpStatusCode.NotFound, client.send("GET", "/api/collections/plain/records/1/history").status)
        val doc = client.send("GET", "/api/_openapi.json").bodyAsText()
        assertTrue("/collections/lists/trash/{id}/restore" in doc && "/collections/plain/trash" !in doc)
    }

    @Test
    fun `ayar dogrulamasi`() {
        val bad = config.copy(collections = listOf(lists.copy(history = HistoryConfig(0), trash = TrashConfig(0))))
        val errors = ConfigValidator.validate(bad)
        assertTrue(errors.any { "sürüm" in it } && errors.any { "çöp kutusu süresi" in it }, errors.toString())
        assertEquals(config, ServerConfig.fromJson(config.toJson()))
        assertTrue("\"history\"" !in ServerConfig.fromJson(ServerConfig(collections = listOf(plain)).toJson()).toJson())
    }
}
