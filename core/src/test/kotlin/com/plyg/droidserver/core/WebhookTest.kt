package com.plyg.droidserver.core

import com.sun.net.httpserver.HttpServer
import com.plyg.droidserver.core.api.droidServerModule
import com.plyg.droidserver.core.config.AuthConfig
import com.plyg.droidserver.core.config.AuthMode
import com.plyg.droidserver.core.config.CollectionConfig
import com.plyg.droidserver.core.config.ConfigSecrets
import com.plyg.droidserver.core.config.ConfigValidator
import com.plyg.droidserver.core.config.FieldConfig
import com.plyg.droidserver.core.config.FieldType
import com.plyg.droidserver.core.config.ScriptsConfig
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.config.WebhookConfig
import com.plyg.droidserver.core.db.SchemaMigrator
import com.plyg.droidserver.core.db.SqlDriver
import com.plyg.droidserver.core.script.ScriptRuntime
import com.plyg.droidserver.core.script.ScriptSource
import com.plyg.droidserver.core.webhook.WebhookDispatcher
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebhookTest {
    private val key = "yonetici-anahtari-1234567890"
    private val todos = CollectionConfig("todos", listOf(FieldConfig("title", FieldType.TEXT)))
    private val notes = CollectionConfig("notes", listOf(FieldConfig("body", FieldType.TEXT)))
    private lateinit var db: SqlDriver
    private val closers = mutableListOf<() -> Unit>()

    private data class Hit(val path: String, val headers: Map<String, String>, val body: String)

    private val hits: MutableList<Hit> = Collections.synchronizedList(mutableListOf())
    private val failFirst = AtomicInteger(0)
    private lateinit var base: String

    @BeforeTest
    fun open() {
        db = TestDb.create(Dialect.SQLITE).open()
        SchemaMigrator(db).migrate(listOf(todos, notes))
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/") { ex ->
            hits += Hit(ex.requestURI.path, ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.first() }, ex.requestBody.readBytes().decodeToString())
            val code = if (failFirst.getAndDecrement() > 0) 500 else 204
            ex.sendResponseHeaders(code, -1)
            ex.close()
        }
        srv.start()
        closers += { srv.stop(0) }
        base = "http://127.0.0.1:${srv.address.port}"
    }

    @AfterTest
    fun close() {
        closers.reversed().forEach { runCatching(it) }
        db.close()
    }

    private fun config(hooks: List<WebhookConfig>) = ServerConfig(
        auth = AuthConfig(AuthMode.API_KEY, apiKeys = listOf(key)),
        collections = listOf(todos, notes),
        webhooks = hooks,
        scripts = ScriptsConfig(enabled = true),
    )

    private fun awaitHits(n: Int) {
        val until = System.currentTimeMillis() + 5_000
        while (hits.size < n && System.currentTimeMillis() < until) Thread.sleep(20)
        assertTrue(hits.size >= n, "beklenen $n istek, gelen ${hits.size}")
    }

    @Test
    fun `olaylar imzali iletilir, filtrelenir, basarisizsa yeniden denenir`() = testApplication {
        val hooks = listOf(
            WebhookConfig("tum", "$base/tum", secret = "gizli-sir"),
            WebhookConfig("notlar", "$base/notlar", collections = listOf("notes"), events = listOf("delete")),
        )
        val dispatcher = WebhookDispatcher(retryDelaysMs = listOf(10, 10)).apply { configure(hooks) }
        closers += dispatcher::close
        val runtime = ScriptRuntime.load(
            listOf(ScriptSource("w.js", """routerAdd("POST", "/not", (req, ctx) => ctx.db.create("notes", { body: "script" }), "admin")""")),
            ScriptsConfig(enabled = true), setOf("todos", "notes"),
        )
        application { droidServerModule(config(hooks), db, scripts = runtime, webhooks = dispatcher) }
        suspend fun post(path: String, body: String) = client.post(path) { bearerAuth(key); contentType(ContentType.Application.Json); setBody(body) }

        val created = Json.parseToJsonElement(post("/api/collections/todos/records", """{"title":"süt"}""").bodyAsText()).jsonObject
        awaitHits(1)
        val hit = hits.single()
        assertEquals("/tum", hit.path)
        assertEquals("todos.create", hit.headers["x-droidserver-event"])
        val ts = hit.headers.getValue("x-droidserver-timestamp")
        assertEquals("sha256=" + WebhookDispatcher.sign("gizli-sir", "$ts.${hit.body}"), hit.headers["x-droidserver-signature"], "alıcı imzayı doğrulayabilir")
        val payload = Json.parseToJsonElement(hit.body).jsonObject
        assertEquals("süt", payload.getValue("record").jsonObject.getValue("title").jsonPrimitive.content)
        assertEquals(hit.headers["x-droidserver-delivery"], payload.getValue("id").jsonPrimitive.content)

        // Başarısız teslim yeniden denenir (aynı teslim kimliğiyle).
        failFirst.set(2)
        client.patch("/api/collections/todos/records/${created.getValue("id").jsonPrimitive.content}") {
            bearerAuth(key); contentType(ContentType.Application.Json); setBody("""{"title":"ekmek"}""")
        }
        awaitHits(4)
        val retries = hits.drop(1).take(3)
        assertEquals(1, retries.map { it.headers["x-droidserver-delivery"] }.toSet().size)
        assertTrue(retries.all { it.headers["x-droidserver-event"] == "todos.update" })

        // Koleksiyon ve olay filtresi; script yazmaları da iletilir.
        hits.clear()
        val note = Json.parseToJsonElement(post("/api/not", "{}").bodyAsText()).jsonObject
        awaitHits(1)
        assertEquals(listOf("/tum"), hits.map { it.path }, "notlar webhook'u yalnızca silmede")
        client.delete("/api/collections/notes/records/${note.getValue("id").jsonPrimitive.content}") { bearerAuth(key) }
        awaitHits(3)
        assertEquals(setOf("/tum", "/notlar"), hits.drop(1).map { it.path }.toSet())
        assertEquals(null, hits.first { it.path == "/notlar" }.headers["x-droidserver-signature"], "sırsız webhook imzasız")

        // Teslim geçmişi ve deneme.
        val info = Json.parseToJsonElement(client.get("/api/_webhooks") { bearerAuth(key) }.bodyAsText()).jsonObject
        val deliveries = info.getValue("deliveries").jsonArray.map { it.jsonObject }
        assertTrue(deliveries.any { it.getValue("status").jsonPrimitive.content == "500" && it.getValue("final").jsonPrimitive.content == "false" })
        assertTrue(deliveries.first().getValue("ok").jsonPrimitive.content == "true", "en yenisi başta")
        val test = client.post("/api/_webhooks/notlar/test") { bearerAuth(key) }
        assertEquals(HttpStatusCode.OK, test.status)
        assertEquals("ping", Json.parseToJsonElement(test.bodyAsText()).jsonObject.getValue("event").jsonPrimitive.content)
        assertEquals(HttpStatusCode.NotFound, client.post("/api/_webhooks/yok/test") { bearerAuth(key) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/_webhooks").status)
    }

    @Test
    fun `dogrulama ve sir deposu`() {
        fun errors(h: WebhookConfig) = ConfigValidator.validate(config(listOf(h)))
        assertTrue(errors(WebhookConfig("a", "$base/x")).isEmpty())
        assertTrue(errors(WebhookConfig("a b", "$base/x")).any { "Webhook adı" in it })
        assertTrue(errors(WebhookConfig("a", "ftp://x")).any { "http://" in it })
        assertTrue(errors(WebhookConfig("a", "$base/x", collections = listOf("yok"))).any { "'yok' koleksiyonu yok" in it })
        assertTrue(errors(WebhookConfig("a", "$base/x", events = listOf("read"))).any { "Olaylar" in it })
        assertTrue(ConfigValidator.validate(config(listOf(WebhookConfig("a", "$base/x"), WebhookConfig("a", "$base/y")))).any { "iki kez" in it })

        val stored = mutableMapOf<String, String>()
        val text = config(listOf(WebhookConfig("a", "$base/x", secret = "duz-sir"))).toJson()
        val secured = ConfigSecrets.secureText(text) { kind, value -> "${kind}_1".also { stored[it] = value } }!!
        assertTrue("duz-sir" !in secured && "secret:webhook_secret_1" in secured, secured)
        val resolved = ConfigSecrets.resolve(ServerConfig.fromJson(secured), stored::get)
        assertEquals("duz-sir", resolved.webhooks.single().secret)
        assertTrue(resolved.webhooks.single().collections.isEmpty())
        val hookJson = Json.parseToJsonElement(config(listOf(WebhookConfig("a", "$base/x"))).toJson()).jsonObject.getValue("webhooks").jsonArray.single().jsonObject
        assertEquals(setOf("name", "url", "events"), hookJson.keys, "varsayılanlar dosyaya yazılmaz")
    }
}
