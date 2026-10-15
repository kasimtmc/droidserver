package dev.droidserver.core

import dev.droidserver.core.api.ApiPaths
import dev.droidserver.core.api.droidServerModule
import dev.droidserver.core.config.ApiConfig
import dev.droidserver.core.config.AuthConfig
import dev.droidserver.core.config.AuthMode
import dev.droidserver.core.config.CollectionConfig
import dev.droidserver.core.config.ConfigValidator
import dev.droidserver.core.config.EndpointConfig
import dev.droidserver.core.config.FieldConfig
import dev.droidserver.core.config.FieldType
import dev.droidserver.core.config.Rule
import dev.droidserver.core.config.RuleCondition
import dev.droidserver.core.config.Rules
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.config.SystemFieldConfig
import dev.droidserver.core.config.UsersConfig
import dev.droidserver.core.db.SchemaMigrator
import dev.droidserver.core.db.SqlDriver
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Uzman modu: uç yolları, kapatılan uçlar, sistem alanlarının API adları. */
class ExpertModeTest {
    private val key = "yonetici-anahtari-1234567890"
    private val todos = CollectionConfig(
        "todos",
        listOf(FieldConfig("title", FieldType.TEXT)),
        rules = Rules(Rule.PUBLIC, Rule.PUBLIC, Rule.PUBLIC, Rule.PUBLIC, Rule.PUBLIC, conditions = mapOf("list" to RuleCondition("kimlik[gte]=1"))),
        endpoints = mapOf(
            "list" to EndpointConfig(path = "/todos"),
            "view" to EndpointConfig(path = "/todos/{id}"),
            "create" to EndpointConfig(path = "/todos"),
            "update" to EndpointConfig(path = "/todos/{id}"),
            "delete" to EndpointConfig(enabled = false),
        ),
        systemFields = mapOf(
            "id" to SystemFieldConfig(name = "kimlik"),
            "created_at" to SystemFieldConfig(name = "olusturulma"),
            "updated_at" to SystemFieldConfig(removed = true),
        ),
    )
    private val config = ServerConfig(
        auth = AuthConfig(
            AuthMode.API_KEY, apiKeys = listOf(key),
            users = UsersConfig(
                enabled = true,
                systemFields = mapOf(
                    "email" to SystemFieldConfig(name = "eposta"),
                    "password" to SystemFieldConfig(name = "parola"),
                    "role" to SystemFieldConfig(removed = true),
                ),
            ),
        ),
        collections = listOf(todos),
        api = ApiConfig(
            mapOf(
                "health" to EndpointConfig(path = "/durum"),
                "auth.signup" to EndpointConfig(path = "/hesap/kayit"),
                "auth.login" to EndpointConfig(path = "/hesap/giris"),
                "batch" to EndpointConfig(enabled = false),
            ),
        ),
    )
    private lateinit var db: SqlDriver

    @BeforeTest
    fun open() {
        db = TestDb.create(Dialect.SQLITE).open()
        SchemaMigrator(db).migrate(config.schemaCollections())
    }

    @AfterTest
    fun close() = db.close()

    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject
    private suspend fun HttpClient.send(path: String, body: String, token: String? = null): HttpResponse = post(path) {
        token?.let { bearerAuth(it) }; contentType(ContentType.Application.Json); setBody(body)
    }

    @Test
    fun `yollar, kapali uclar ve sistem alani adlari`() = testApplication {
        application { droidServerModule(config, db) }
        assertTrue(ConfigValidator.validate(config).isEmpty(), ConfigValidator.validate(config).toString())

        // Global uçlar: yeni yol çalışır, eski yol ve kapalı uç 404.
        assertEquals(HttpStatusCode.OK, client.get("/api/durum").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/health").status)
        assertEquals(HttpStatusCode.NotFound, client.send("/api/batch", """{"requests":[]}""", key).status)

        // Kullanıcılar: yeni yol ve alan adları; rol kaldırıldı.
        val signup = client.send("/api/hesap/kayit", """{"eposta":"a@example.com","parola":"parola-123"}""")
        assertEquals(HttpStatusCode.Created, signup.status, signup.bodyAsText())
        val user = json(signup.bodyAsText()).getValue("user").jsonObject
        assertEquals("a@example.com", user.getValue("eposta").jsonPrimitive.content)
        assertFalse("email" in user || "role" in user, user.toString())
        val bad = client.send("/api/hesap/kayit", """{"eposta":"b@example.com"}""")
        assertTrue("parola" in json(bad.bodyAsText()).getValue("details").jsonObject, "hata ayrıntısı API adıyla: ${bad.bodyAsText()}")
        assertEquals(HttpStatusCode.OK, client.send("/api/hesap/giris", """{"eposta":"a@example.com","parola":"parola-123"}""").status)
        assertEquals(HttpStatusCode.NotFound, client.send("/api/auth/login", """{"email":"a@example.com","password":"parola-123"}""").status)

        // Koleksiyon: yeni yollar ve adlar; güncelleme alanı kaldırıldı.
        val created = client.send("/api/todos", """{"title":"süt"}""")
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val todo = json(created.bodyAsText())
        assertEquals(setOf("kimlik", "title", "olusturulma"), todo.keys)
        val id = todo.getValue("kimlik").jsonPrimitive.content
        client.send("/api/todos", """{"title":"ekmek"}""")
        val list = json(client.get("/api/todos?sort=-kimlik&fields=title,olusturulma&olusturulma[gte]=2000-01-01T00:00:00Z").bodyAsText())
        val items = list.getValue("items").jsonArray.map { it.jsonObject }
        assertEquals(listOf("ekmek", "süt"), items.map { it.getValue("title").jsonPrimitive.content })
        assertEquals(setOf("kimlik", "title", "olusturulma"), items.first().keys)
        assertEquals("süt", json(client.get("/api/todos/$id").bodyAsText()).getValue("title").jsonPrimitive.content)
        assertEquals(HttpStatusCode.OK, client.patch("/api/todos/$id") { contentType(ContentType.Application.Json); setBody("""{"title":"çay"}""") }.status)
        assertTrue(client.delete("/api/todos/$id").status in setOf(HttpStatusCode.NotFound, HttpStatusCode.MethodNotAllowed), "silme ucu kapalı")
        assertEquals(HttpStatusCode.NotFound, client.get("/api/collections/todos/records").status)

        // Dışa aktarma, OpenAPI ve örnekler yeni adları kullanır.
        val csv = client.get("/api/collections/todos/export") { bearerAuth(key) }.bodyAsText()
        assertTrue(csv.lines().first().contains("kimlik") && "updated_at" !in csv, csv)
        val doc = client.get("/api/_openapi.json") { bearerAuth(key) }.bodyAsText()
        assertTrue("\"/todos\"" in doc && "\"/todos/{id}\"" in doc && "/collections/todos/records" !in doc && "\"/durum\"" in doc, doc)
        assertTrue("\"kimlik\"" in doc && "\"eposta\"" in doc && "/batch" !in doc)
    }

    @Test
    fun `parola alani kaldirilinca parolali giris kapanir, dogrulama hatalari`() = testApplication {
        val noPassword = config.copy(auth = config.auth.copy(users = config.auth.users.copy(systemFields = mapOf("password" to SystemFieldConfig(removed = true)))))
        application { droidServerModule(noPassword, db) }
        assertEquals(HttpStatusCode.NotFound, client.send("/api/hesap/giris", """{"email":"a@example.com","password":"x"}""").status)
        assertEquals(null, ApiPaths(noPassword).path(dev.droidserver.core.api.Endpoint.AUTH_SIGNUP))

        fun errors(c: ServerConfig) = ConfigValidator.validate(c)
        fun withTodos(t: CollectionConfig) = errors(config.copy(collections = listOf(t)))
        assertTrue(withTodos(todos.copy(endpoints = mapOf("view" to EndpointConfig(path = "/todos/{kayit}")))).any { "{id}" in it })
        assertTrue(withTodos(todos.copy(endpoints = mapOf("view" to EndpointConfig(path = "todos")))).any { "/ ile başlamalı" in it })
        withTodos(todos.copy(endpoints = mapOf("list" to EndpointConfig(path = "/durum")))).let { assertTrue(it.any { e -> "aynı yolu" in e }, it.toString()) }
        assertTrue(withTodos(todos.copy(systemFields = mapOf("id" to SystemFieldConfig(name = "title")))).any { "çakışıyor" in it })
        assertTrue(withTodos(todos.copy(systemFields = mapOf("id" to SystemFieldConfig(name = "created_at")))).any { "başka bir sistem alanında" in it })
        assertTrue(withTodos(todos.copy(systemFields = mapOf("yok" to SystemFieldConfig(name = "x")))).any { "sistem alanı değil" in it })
        assertTrue(errors(config.copy(api = ApiConfig(mapOf("yok" to EndpointConfig())))).any { "Bilinmeyen uç" in it })
        assertEquals(config, ServerConfig.fromJson(config.toJson()), "dosyaya yazılır ve okunur")
        val plain = ServerConfig.fromJson(ServerConfig(collections = listOf(CollectionConfig("a", listOf(FieldConfig("x", FieldType.TEXT))))).toJson()).toJson()
        assertFalse("endpoints" in plain || "systemFields" in plain || "\"api\"" in plain, "varsayılanlar dosyaya yazılmaz: $plain")
    }
}
