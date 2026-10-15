package dev.droidserver.core

import dev.droidserver.core.api.droidServerModule
import dev.droidserver.core.config.AuthConfig
import dev.droidserver.core.config.AuthMode
import dev.droidserver.core.config.ConfigValidator
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.config.UsersConfig
import dev.droidserver.core.db.SchemaMigrator
import dev.droidserver.core.db.SqlDriver
import dev.droidserver.core.templates.AppTemplate
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Faz 13.5: hazır uygulama şablonları, iki lehçede. */
@RunWith(Parameterized::class)
class TemplatesTest(private val dialect: Dialect) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun dialects() = Dialect.entries.toList()
    }

    private val key = "yonetici-anahtari-1234567890"
    private val base = ServerConfig(auth = AuthConfig(AuthMode.API_KEY, apiKeys = listOf(key), users = UsersConfig(sendVerification = false)))
    private var db: SqlDriver? = null

    @After
    fun close() { db?.close() }

    private fun setup(t: AppTemplate): ServerConfig {
        val cfg = t.apply(base)
        assertEquals(emptyList(), ConfigValidator.validateDetailed(cfg).map { "${it.path}: ${it.message}" }, t.id)
        db = TestDb.create(dialect).open().also { SchemaMigrator(it).migrate(cfg.schemaCollections()) }
        return cfg
    }

    private fun HttpResponse.json() = kotlinx.coroutines.runBlocking { Json.parseToJsonElement(bodyAsText()).jsonObject }
    private suspend fun ApplicationTestBuilder.post(path: String, body: String, token: String? = null) =
        client.post(path) { token?.let { bearerAuth(it) }; contentType(ContentType.Application.Json); setBody(body) }
    private suspend fun ApplicationTestBuilder.signup(email: String) =
        post("/api/auth/signup", """{"email":"$email","password":"parola-1234"}""").json().getValue("token").jsonPrimitive.content
    private fun names(o: JsonObject, field: String) = o.getValue("items").jsonArray.map { it.jsonObject.getValue(field).jsonPrimitive.content }

    @Test
    fun `blog`() {
        val cfg = setup(AppTemplate.BLOG)
        assertEquals(7, AppTemplate.BLOG.seed(db!!, cfg))
        assertEquals(0, AppTemplate.BLOG.seed(db!!, cfg), "tekrar çalıştırmak çoğaltmaz")
        testApplication {
            application { droidServerModule(cfg, db!!) }
            val posts = client.get("/api/collections/posts/records?sort=published_at&expand=category").json()
            assertEquals(listOf("Merhaba dünya", "Telefonu sunucu yapmak"), names(posts, "title"), "taslak görünmez")
            assertEquals("Duyurular", posts.getValue("items").jsonArray[0].jsonObject.getValue("expand").jsonObject.getValue("category").jsonObject.getValue("name").jsonPrimitive.content)
            assertEquals(1, client.get("/api/collections/posts/records?q=şarj").json().getValue("items").jsonArray.size, "tam metin arama")
            assertEquals(listOf("Ayşe"), names(client.get("/api/collections/comments/records").json(), "name"), "onaysız yorum görünmez")
            assertEquals(HttpStatusCode.Created, post("/api/collections/comments/records", """{"post":1,"name":"Ziyaretçi","body":"Selam"}""").status)
            assertEquals(HttpStatusCode.Forbidden, post("/api/collections/comments/records", """{"post":1,"name":"Hileci","body":"x","approved":true}""").status)
            assertEquals(1, client.get("/api/collections/comments/records").json().getValue("items").jsonArray.size)
            assertEquals(HttpStatusCode.Unauthorized, post("/api/collections/posts/records", """{"title":"x","slug":"x"}""").status)
        }
        val site = AppTemplate.BLOG.site("/api")
        assertTrue("const API = \"/api\";" in site && "__API__" !in site)
        assertFailsWith<IllegalArgumentException> { AppTemplate.BLOG.apply(cfg) }
        assertEquals(listOf("categories", "posts", "comments"), AppTemplate.BLOG.conflicts(cfg))
        assertTrue(cfg.static.enabled)
        assertTrue(!AppTemplate.BLOG.apply(base, site = false).static.enabled)
    }

    @Test
    fun `magaza`() {
        val cfg = setup(AppTemplate.SHOP)
        assertTrue(cfg.auth.users.enabled)
        assertEquals(4, AppTemplate.SHOP.seed(db!!, cfg))
        testApplication {
            application { droidServerModule(cfg, db!!) }
            assertEquals(3, client.get("/api/collections/products/records").json().getValue("items").jsonArray.size, "satışta olmayan gizli")
            val order = """{"items":[{"product":1,"qty":2}],"total":499.8,"address":"Ankara","phone":"555"}"""
            assertEquals(HttpStatusCode.Unauthorized, post("/api/collections/orders/records", order).status)
            val ali = signup("ali@example.com")
            val created = post("/api/collections/orders/records", order, ali)
            assertEquals(HttpStatusCode.Created, created.status)
            assertEquals("yeni", created.json().getValue("status").jsonPrimitive.content)
            assertEquals(HttpStatusCode.Forbidden, post("/api/collections/orders/records", """{"items":[],"total":1,"address":"x","phone":"1","status":"kargoda"}""", ali).status)
            val ayse = signup("ayse@example.com")
            assertEquals(0, client.get("/api/collections/orders/records") { bearerAuth(ayse) }.json().getValue("items").jsonArray.size, "başkasının siparişi görünmez")
            assertEquals(1, client.get("/api/collections/orders/records") { bearerAuth(ali) }.json().getValue("items").jsonArray.size)
        }
    }

    @Test
    fun `randevu`() {
        val cfg = setup(AppTemplate.APPOINTMENTS)
        assertEquals(2, AppTemplate.APPOINTMENTS.seed(db!!, cfg))
        testApplication {
            application { droidServerModule(cfg, db!!) }
            assertEquals(2, client.get("/api/collections/services/records").json().getValue("items").jsonArray.size)
            val req = """{"service":1,"starts_at":"2026-11-01T10:00:00Z","name":"Can","phone":"555 000"}"""
            assertEquals(HttpStatusCode.Created, post("/api/collections/appointments/records", req).status, "hesapsız randevu")
            assertEquals(HttpStatusCode.Forbidden, post("/api/collections/appointments/records", req.replace("}", ",\"status\":\"onaylandı\"}")).status)
            assertEquals(HttpStatusCode.BadRequest, post("/api/collections/appointments/records", req.replace("}", ",\"email\":\"yok\"}")).status)
            assertTrue(client.get("/api/collections/appointments/records").status.value in setOf(401, 403), "talepleri yalnızca yönetici görür")
            assertEquals(1, client.get("/api/collections/appointments/records") { bearerAuth(key) }.json().getValue("items").jsonArray.size)
        }
    }

    @Test
    fun `gorev takibi`() {
        val cfg = setup(AppTemplate.TASKS)
        assertEquals(0, AppTemplate.TASKS.seed(db!!, cfg), "kullanıcıya özel: örnek yok")
        testApplication {
            application { droidServerModule(cfg, db!!) }
            val ali = signup("ali@example.com")
            val ayse = signup("ayse@example.com")
            val project = post("/api/collections/projects/records", """{"name":"Ev"}""", ali).json().getValue("id").jsonPrimitive.content
            assertEquals(HttpStatusCode.Created, post("/api/collections/tasks/records", """{"project":$project,"title":"Boya al","priority":"yüksek"}""", ali).status)
            val tasks = client.get("/api/collections/tasks/records?project=$project") { bearerAuth(ali) }.json()
            assertEquals(listOf("yapılacak"), names(tasks, "status"))
            assertEquals(0, client.get("/api/collections/projects/records") { bearerAuth(ayse) }.json().getValue("items").jsonArray.size)
            assertEquals(0, client.get("/api/collections/tasks/records") { bearerAuth(ayse) }.json().getValue("items").jsonArray.size)
        }
        AppTemplate.entries.forEach { t -> assertTrue("const API = \"/v1\";" in t.site("/v1"), t.id) }
    }
}
