package com.plyg.droidserver.core

import com.sun.net.httpserver.HttpServer
import com.plyg.droidserver.core.api.droidServerModule
import com.plyg.droidserver.core.config.AuthConfig
import com.plyg.droidserver.core.config.AuthMode
import com.plyg.droidserver.core.config.CollectionConfig
import com.plyg.droidserver.core.config.ConfigValidator
import com.plyg.droidserver.core.config.FieldConfig
import com.plyg.droidserver.core.config.FieldType
import com.plyg.droidserver.core.config.HttpConfig
import com.plyg.droidserver.core.config.Rule
import com.plyg.droidserver.core.config.Rules
import com.plyg.droidserver.core.config.ScriptsConfig
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.db.JdbcSqlDriver
import com.plyg.droidserver.core.db.SchemaMigrator
import com.plyg.droidserver.core.db.SqlDriver
import com.plyg.droidserver.core.script.CronRunner
import com.plyg.droidserver.core.script.CronSchedule
import com.plyg.droidserver.core.script.ScriptHttp
import com.plyg.droidserver.core.script.ScriptLoadException
import com.plyg.droidserver.core.script.ScriptRuntime
import com.plyg.droidserver.core.script.ScriptSource
import com.plyg.droidserver.core.server.DeployException
import com.plyg.droidserver.core.server.ServerEngine
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
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.sql.DriverManager
import java.time.LocalDateTime
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScriptsTest {
    private val key = "yonetici-anahtari-1234567890"
    private val todos = CollectionConfig(
        "todos",
        listOf(FieldConfig("title", FieldType.TEXT, required = true), FieldConfig("done", FieldType.BOOLEAN)),
        rules = Rules(Rule.PUBLIC, Rule.PUBLIC, Rule.PUBLIC, Rule.PUBLIC, Rule.PUBLIC),
    )
    private val audit = CollectionConfig("audit", listOf(FieldConfig("event", FieldType.TEXT)))
    private val scriptsConfig = ScriptsConfig(enabled = true, timeoutMs = 300, env = mapOf("SELAM" to "merhaba"))
    private val config = ServerConfig(
        auth = AuthConfig(AuthMode.API_KEY, apiKeys = listOf(key)),
        collections = listOf(todos, audit),
        scripts = scriptsConfig,
    )
    private lateinit var db: SqlDriver
    private val logs = Collections.synchronizedList(mutableListOf<String>())

    @Before
    fun open() {
        db = TestDb.create(Dialect.SQLITE).open()
        SchemaMigrator(db).migrate(config.schemaCollections())
    }

    @After
    fun close() = db.close()

    private fun load(vararg sources: Pair<String, String>, cfg: ScriptsConfig = scriptsConfig) =
        ScriptRuntime.load(sources.map { ScriptSource(it.first, it.second) }, cfg, setOf("todos", "audit"), logs::add)

    private fun ApplicationTestBuilder.server(runtime: ScriptRuntime) = application { droidServerModule(config, db, scripts = runtime) }

    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test
    fun `ozel uclar`() = testApplication {
        server(load("main.js" to """
            routerAdd("GET", "/hello/:name", (req, ctx) => ({ message: "Merhaba " + req.params.name, q: req.query.dil, env: ctx.env.SELAM }))
            routerAdd("POST", "/echo", (req) => reply(201, { got: req.body, who: req.headers["x-kim"] }, { "X-Ozel": "evet" }))
            routerAdd("GET", "/yasak", () => fail(422, "olmaz"))
            routerAdd("GET", "/gizli", (req, ctx) => ({ auth: ctx.auth.type }), "admin")
            routerAdd("GET", "/patla", () => { throw new Error("içeride") })
            routerAdd("GET", "/sayac", (req, ctx) => {
                ctx.db.create("audit", { event: "sayaç" })
                return ctx.db.list("audit", { filter: { "event[like]": "say" } }).totalItems
            })
        """.trimIndent()))
        val hello = client.get("/api/hello/Ayşe?dil=tr")
        assertEquals(HttpStatusCode.OK, hello.status, hello.bodyAsText())
        val h = json(hello.bodyAsText())
        assertEquals("Merhaba Ayşe", h.getValue("message").jsonPrimitive.content)
        assertEquals("tr", h.getValue("q").jsonPrimitive.content)
        assertEquals("merhaba", h.getValue("env").jsonPrimitive.content)

        val echo = client.post("/api/echo") { contentType(ContentType.Application.Json); setBody("""{"a":[1,2]}"""); headers.append("X-Kim", "test") }
        assertEquals(HttpStatusCode.Created, echo.status)
        assertEquals("evet", echo.headers["X-Ozel"])
        assertEquals("""{"got":{"a":[1,2]},"who":"test"}""", echo.bodyAsText().replace(Regex("\\s"), ""))

        val no = client.get("/api/yasak")
        assertEquals(422, no.status.value)
        assertTrue("olmaz" in no.bodyAsText())
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/gizli").status)
        assertEquals("admin", json(client.get("/api/gizli") { bearerAuth(key) }.bodyAsText()).getValue("auth").jsonPrimitive.content)

        val boom = client.get("/api/patla")
        assertEquals(HttpStatusCode.InternalServerError, boom.status)
        assertFalse("içeride" in boom.bodyAsText(), "ayrıntı istemciye değil loga")
        assertTrue(logs.any { "içeride" in it && "main.js:5" in it }, logs.toString())
        assertEquals("1", client.get("/api/sayac").bodyAsText())
        assertEquals("2", client.get("/api/sayac").bodyAsText())
        assertEquals(HttpStatusCode.OK, client.get("/api/health").status, "sunucu ayakta")
    }

    @Test
    fun `kayit olaylari`() = testApplication {
        server(load("hooks.js" to """
            onBeforeCreate("todos", (record) => {
                record.title = (record.title || "").trim()
                if (!record.title) fail(400, "başlık boş olamaz")
            })
            onAfterCreate("todos", (record, ctx) => { ctx.db.create("audit", { event: "eklendi " + record.id + " " + ctx.auth.type }) })
            onBeforeUpdate("todos", (record, ctx) => { if (ctx.current.done) fail(409, "tamamlanmış iş değişmez") })
            onBeforeDelete("todos", (record) => { if (record.title === "kalıcı") fail(403, "silinemez") })
            onAfterDelete("*", (record, ctx) => { ctx.db.create("audit", { event: "silindi " + record.id }) })
            onAfterUpdate("todos", () => { throw new Error("sonradan") })
        """.trimIndent()))
        suspend fun create(body: String) = client.post("/api/collections/todos/records") { contentType(ContentType.Application.Json); setBody(body) }
        val r = create("""{"title":"  süt al  "}""")
        assertEquals(HttpStatusCode.Created, r.status, r.bodyAsText())
        assertEquals("süt al", json(r.bodyAsText()).getValue("title").jsonPrimitive.content, "before olayı kaydı değiştirdi")
        assertEquals(HttpStatusCode.BadRequest, create("""{"title":"   "}""").status)

        val upd = client.patch("/api/collections/todos/records/1") { contentType(ContentType.Application.Json); setBody("""{"done":true}""") }
        assertEquals(HttpStatusCode.OK, upd.status, "after olayının hatası yanıtı değiştirmez")
        assertTrue(logs.any { "sonradan" in it })
        val again = client.patch("/api/collections/todos/records/1") { contentType(ContentType.Application.Json); setBody("""{"title":"x"}""") }
        assertEquals(HttpStatusCode.Conflict, again.status)

        create("""{"title":"kalıcı"}""")
        assertEquals(HttpStatusCode.Forbidden, client.delete("/api/collections/todos/records/2").status)
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/collections/todos/records/1").status)

        val events = json(client.get("/api/collections/audit/records?sort=id") { bearerAuth(key) }.bodyAsText())
            .getValue("items").jsonArray.map { it.jsonObject.getValue("event").jsonPrimitive.content }
        assertEquals(listOf("eklendi 1 anonymous", "eklendi 2 anonymous", "silindi 1"), events)
    }

    @Test
    fun `sonsuz dongu ve java erisimi`() = testApplication {
        server(load("kotu.js" to """
            routerAdd("GET", "/dongu", () => { while (true) {} })
            routerAdd("GET", "/kacis", () => { try { while (true) {} } catch (e) { return "yakalandı" } })
            routerAdd("GET", "/ozyineleme", () => { const f = () => f(); return f() })
            routerAdd("GET", "/java", () => [typeof java, typeof Packages, typeof importPackage, typeof JavaImporter, typeof getClass].join(","))
            routerAdd("GET", "/sistem", () => java.lang.System.exit(1))
            routerAdd("GET", "/sinif", (req, ctx) => String(ctx.log.getClass) + "," + String("".getClass))
        """.trimIndent()))
        val started = System.nanoTime()
        assertEquals(HttpStatusCode.InternalServerError, client.get("/api/dongu").status)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000, "süre sınırında durdu")
        assertTrue(logs.any { "süre sınırını" in it }, logs.toString())
        val escape = client.get("/api/kacis")
        assertEquals(HttpStatusCode.InternalServerError, escape.status, "zaman aşımı try/catch ile yutulamaz")
        assertEquals(HttpStatusCode.InternalServerError, client.get("/api/ozyineleme").status)
        assertEquals("\"undefined,undefined,undefined,undefined,undefined\"", client.get("/api/java").bodyAsText())
        assertEquals(HttpStatusCode.InternalServerError, client.get("/api/sistem").status)
        assertTrue(logs.any { "java" in it && "kotu.js:5" in it }, logs.toString())
        assertEquals("\"undefined,undefined\"", client.get("/api/sinif").bodyAsText())
        assertEquals(HttpStatusCode.OK, client.get("/api/health").status, "sunucu ayakta")
    }

    @Test
    fun `yukleme hatalari`() {
        fun errors(vararg s: Pair<String, String>) = assertFailsWith<ScriptLoadException> { load(*s) }.errors
        assertTrue(errors("a.js" to "routerAdd(\"GET\", \"/x\", () => {\n  return {\n}").single().startsWith("a.js:"), "sözdizimi hatası satırıyla")
        assertEquals(1, errors("b.js" to """routerAdd("GET", "/collections/x", () => 1)""").size)
        assertTrue("yok" in errors("c.js" to """onBeforeCreate("olmayan", () => {})""").single())
        assertTrue("cron" in errors("d.js" to """cronAdd("her gün", () => {})""").single())
        assertTrue("süre" in errors("e.js" to "while (true) {}").single())
        assertTrue(errors("f.js" to """routerAdd("GET", "/a", () => 1); routerAdd("GET", "/a", () => 2)""").single().contains("iki kez"))
        assertTrue(ScriptRuntime.check(ScriptSource("g.js", "let x = ;")).single().startsWith("g.js:1"))
        assertTrue(ScriptRuntime.check(ScriptSource("h.js", "const f = (a) => `x${'$'}{a}`")).isEmpty(), "ES6: ok fonksiyonu, şablon metin")
    }

    @Test
    fun `deploy sozdizimi hatasini reddeder`() {
        val dir = Files.createTempDirectory("ds-scripts").toFile()
        val file = File(dir, "data.db")
        File(dir, "scripts").mkdirs()
        File(dir, "scripts/main.js").writeText("routerAdd(\"GET\", \"/ping\", () => \"pong\")")
        val port = ServerSocket(0).use { it.localPort }
        val cfg = config.copy(server = HttpConfig(host = "127.0.0.1", port = port))
        val engine = ServerEngine({ JdbcSqlDriver(DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")) }, dataDir = dir) {}
        try {
            engine.deploy(cfg)
            assertEquals("\"pong\"", java.net.URI("http://127.0.0.1:$port/api/ping").toURL().readText())
            File(dir, "scripts/main.js").writeText("routerAdd(\"GET\", \"/ping\", () => {")
            val e = assertFailsWith<DeployException> { engine.deploy(cfg) }
            assertTrue(e.errors.single().startsWith("Script: main.js:"), e.errors.toString())
            assertEquals("\"pong\"", java.net.URI("http://127.0.0.1:$port/api/ping").toURL().readText(), "çalışan sunucu sürer")
        } finally {
            engine.stop()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `zamanlanmis gorevler`() {
        val s = CronSchedule.parse("*/15 3 * * 1-5")
        assertTrue(s.matches(LocalDateTime.of(2026, 10, 13, 3, 30))) // salı
        assertFalse(s.matches(LocalDateTime.of(2026, 10, 13, 3, 31)))
        assertFalse(s.matches(LocalDateTime.of(2026, 10, 18, 3, 30))) // pazar
        assertTrue(CronSchedule.parse("0 0 1 * 0").matches(LocalDateTime.of(2026, 11, 1, 0, 0)), "ayın 1'i veya pazar")
        assertTrue(CronSchedule.parse("0 0 * * 7").matches(LocalDateTime.of(2026, 10, 18, 0, 0)), "7 de pazar")
        for (bad in listOf("* * * *", "60 * * * *", "* * 0 * *", "*/0 * * * *", "5-1 * * * *")) {
            assertFailsWith<IllegalArgumentException>(bad) { CronSchedule.parse(bad) }
        }

        val runtime = load("cron.js" to """cronAdd("0 3 * * *", (ctx) => { ctx.db.create("audit", { event: "gece " + ctx.auth.type }) })""")
        val services = mapOf(
            "audit" to com.plyg.droidserver.core.api.RecordService(db, audit),
            "todos" to com.plyg.droidserver.core.api.RecordService(db, todos),
        )
        runtime.db = object : com.plyg.droidserver.core.script.ScriptDb {
            override fun list(collection: String, query: kotlinx.serialization.json.JsonObject) = error("yok")
            override fun get(collection: String, id: Long) = services.getValue(collection).get(id)
            override fun create(collection: String, data: kotlinx.serialization.json.JsonObject) = services.getValue(collection).create(data)
            override fun update(collection: String, id: Long, data: kotlinx.serialization.json.JsonObject) = error("yok")
            override fun delete(collection: String, id: Long) = error("yok")
        }
        val runner = CronRunner(runtime)
        assertTrue(runner.tick(LocalDateTime.of(2026, 10, 13, 2, 59)).isEmpty())
        assertEquals(1, runner.tick(LocalDateTime.of(2026, 10, 13, 3, 0, 42)).size)
        assertEquals("gece admin", db.query("SELECT \"event\" FROM \"audit\"").single()["event"])
    }

    @Test
    fun `http izin listesi`() {
        val http = ScriptHttp(listOf("api.example.com", "*.ornek.org"))
        assertTrue(http.allowed("api.example.com"))
        assertTrue(http.allowed("a.b.ornek.org"))
        assertFalse(http.allowed("ornek.org"))
        assertFalse(http.allowed("evil-api.example.com"))
        assertFalse(http.allowed("api.example.com.evil.net"))

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/veri") { ex ->
            val body = """{"gelen":"${ex.requestBody.readBytes().decodeToString().replace("\"", "'")}"}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val port = server.address.port
            val runtime = load(
                "http.js" to """
                    routerAdd("GET", "/izinli", (req, ctx) => ctx.http.post("http://localhost:$port/veri", { a: 1 }).json.gelen)
                    routerAdd("GET", "/izinsiz", (req, ctx) => ctx.http.get("http://example.net/").status)
                """.trimIndent(),
                cfg = scriptsConfig.copy(allowedHosts = listOf("localhost")),
            )
            assertEquals(emptyList(), ConfigValidator.validateDetailed(config.copy(scripts = scriptsConfig.copy(allowedHosts = listOf("localhost", "*.example.com")))))
            assertEquals(
                setOf("scripts.allowedHosts[0]", "scripts.timeoutMs", "scripts.env"),
                ConfigValidator.validateDetailed(
                    config.copy(scripts = scriptsConfig.copy(allowedHosts = listOf("http://x.com"), timeoutMs = 1, env = mapOf("1kötü" to "x"))),
                ).map { it.path }.toSet(),
            )
            testApplication {
                server(runtime)
                assertEquals("\"{'a':1}\"", client.get("/api/izinli").bodyAsText())
                assertEquals(HttpStatusCode.InternalServerError, client.get("/api/izinsiz").status)
                assertTrue(logs.any { "izin listesinde değil" in it }, logs.toString())
            }
        } finally {
            server.stop(0)
        }
    }
}
