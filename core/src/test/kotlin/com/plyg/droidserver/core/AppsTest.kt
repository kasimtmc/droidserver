package com.plyg.droidserver.core

import com.plyg.droidserver.core.api.droidServerModule
import com.plyg.droidserver.core.apps.AppException
import com.plyg.droidserver.core.apps.AppManager
import com.plyg.droidserver.core.apps.AppsHost
import com.plyg.droidserver.core.apps.GitFetcher
import com.plyg.droidserver.core.apps.GitWebhook
import com.plyg.droidserver.core.components.RuntimeInfo
import com.plyg.droidserver.core.config.AccessConfig
import com.plyg.droidserver.core.config.AccessMode
import com.plyg.droidserver.core.config.AppAccess
import com.plyg.droidserver.core.config.AppConfig
import com.plyg.droidserver.core.config.AuthConfig
import com.plyg.droidserver.core.config.AuthMode
import com.plyg.droidserver.core.config.ConfigValidator
import com.plyg.droidserver.core.config.GitProvider
import com.plyg.droidserver.core.config.GitSource
import com.plyg.droidserver.core.config.RestartPolicy
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.config.UsersConfig
import com.plyg.droidserver.core.db.SchemaMigrator
import com.plyg.droidserver.core.process.ProcessSupervisor
import com.plyg.droidserver.core.server.DeployOrchestrator
import com.plyg.droidserver.core.server.EngineControl
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import java.util.Collections
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Kendi uygulamalar: sürümler, deploy ve geri dönüş, ters vekil, Git'ten çekme ve webhook. */
class AppsTest {
    private val dir = Files.createTempDirectory("ds-apps").toFile()
    private val logs: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val servers = mutableListOf<EmbeddedServer<*, *>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val supervisor = ProcessSupervisor(scope) {}

    @AfterTest
    fun cleanup() {
        supervisor.stopAll()
        scope.cancel()
        servers.forEach { it.stop(100, 500) }
        dir.deleteRecursively()
    }

    private fun zip(vararg files: Pair<String, String>): File {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, content) in files) {
                z.putNextEntry(ZipEntry(name))
                z.write(content.toByteArray())
                z.closeEntry()
            }
        }
        return File.createTempFile("app", ".zip", dir).apply { writeBytes(out.toByteArray()) }
    }

    private fun freePort() = ServerSocket(0).use { it.localPort }

    private fun app(port: Int = 3000, vararg tweaks: (AppConfig) -> AppConfig): AppConfig =
        tweaks.fold(AppConfig("api", command = listOf("sh", "run.sh"), port = port)) { a, t -> t(a) }

    @Test
    fun `play surumunde derlenmis kodlu uygulama baslatilmaz`() {
        val elf = "\u007FELF" + "x".repeat(60)
        val mgr = AppManager(File(dir, "apps"), log = logs::add, nativeCode = false)
        mgr.stage("api", zip("run.sh" to "#!/bin/sh\necho hi\n", "node_modules/sqlite3/build/node_sqlite3.node" to elf), "upload")
        val e = assertFailsWith<AppException> { mgr.prepare(app(), emptyMap()) }
        assertTrue("node_sqlite3.node" in e.message.orEmpty(), e.message)
        // GitHub sürümünde aynı kod hazırlanır.
        assertTrue(AppManager(File(dir, "apps")).prepare(app(), emptyMap()).spec.command.isNotEmpty())
    }

    @Test
    fun `surum hazirlama ve surec tanimi`() {
        val mgr = AppManager(File(dir, "apps"), log = logs::add)
        // GitHub arşivi gibi tek üst klasör atlanır; betikler çalıştırılabilir olur.
        val r1 = mgr.stage("api", zip("depo-ab12/run.sh" to "#!/bin/sh\necho hi\n", "depo-ab12/backend/app.py" to "print(1)", "depo-ab12/README" to "x"), "upload")
        val state = mgr.state("api")
        assertEquals(r1.id, state.pending)
        assertNull(state.current)

        val prepared = mgr.prepare(app(), sharedEnv = mapOf("SHARED" to "1", "OVER" to "ortak"))
        assertTrue(prepared.candidate)
        val work = prepared.spec.workDir
        assertTrue(File(work, "run.sh").canExecute(), "betik çalıştırılabilir")
        assertTrue(File(work, "backend/app.py").isFile)
        assertEquals("app:api", prepared.spec.id)
        assertEquals(r1.id, prepared.spec.version)
        assertTrue(prepared.spec.command.first().endsWith("/sh"), prepared.spec.command.toString())
        val env = prepared.spec.env
        assertEquals("3000", env["PORT"])
        assertEquals("/apps/api", env["BASE_PATH"])
        assertEquals("1", env["SHARED"])
        assertTrue(env.getValue("DATA_DIR").endsWith("apps/api/data"))

        // Uygulamanın kendi değişkeni ortağın üstüne yazar; sharedEnv kapalıysa ortak verilmez; PORT değiştirilemez.
        val own = mgr.prepare(app(tweaks = arrayOf({ it.copy(env = mapOf("OVER" to "kendi", "PORT" to "1")) })), mapOf("OVER" to "ortak")).spec.env
        assertEquals("kendi", own["OVER"])
        assertEquals("3000", own["PORT"])
        assertNull(mgr.prepare(app(tweaks = arrayOf({ it.copy(sharedEnv = false) })), mapOf("SHARED" to "1")).spec.env["SHARED"])

        // Alt klasör
        val r2 = mgr.stage("api", zip("depo-ab12/backend/app.py" to "print(2)", "depo-ab12/other.txt" to "x"), "git", commit = "ab12", subdir = "backend")
        assertEquals(listOf("app.py", AppManager.RELEASE_META).sorted(), File(File(dir, "apps/api/releases"), r2.id).list()!!.sorted())
        assertEquals(listOf(r2.id, r1.id), mgr.releases("api").map { it.id })
        assertEquals("ab12", mgr.releases("api").first().commit)

        // Zararlı arşivler
        assertFailsWith<IllegalArgumentException> { mgr.stage("api", zip("../kacak.txt" to "x"), "upload") }
        assertFailsWith<AppException> { mgr.stage("api", zip("a/b.txt" to "x"), "upload", subdir = "yok") }
        assertFalse(File(dir, "apps/kacak.txt").exists())

        // Çalışma ortamı: komut ortamın bin'inde aranır; kurulu değilse anlaşılır hata.
        val missing = assertFailsWith<AppException> { mgr.prepare(app(tweaks = arrayOf({ it.copy(runtime = "nodejs", command = listOf("node", "a.js")) })), emptyMap()) }
        assertTrue("nodejs" in (missing.message ?: ""), missing.message)
        val rtHome = File(dir, "rt").apply { File(this, "bin").mkdirs(); File(this, "bin/node").apply { writeText("#!/bin/sh\n"); setExecutable(true) } }
        val withRt = AppManager(File(dir, "apps"), runtimes = { if (it == "nodejs") RuntimeInfo("nodejs", "22", rtHome, listOf(File(rtHome, "bin")), mapOf("LD_LIBRARY_PATH" to "/rt/lib")) else null })
        val spec = withRt.prepare(app(tweaks = arrayOf({ it.copy(runtime = "nodejs", command = listOf("node", "a.js")) })), emptyMap()).spec
        assertEquals(File(rtHome, "bin/node").path, spec.command.first())
        assertEquals("/rt/lib", spec.env["LD_LIBRARY_PATH"])
        assertTrue(spec.env.getValue("PATH").startsWith(File(rtHome, "bin").path + ":"))
        assertFailsWith<AppException> { mgr.prepare(app(tweaks = arrayOf({ it.copy(command = listOf("yok-boyle-bir-program")) })), emptyMap()) }
        assertFailsWith<AppException> { mgr.prepare(app(tweaks = arrayOf({ it.copy(command = listOf("../../etc/passwd")) })), emptyMap()) }

        // Kurulum komutları sürüm başına bir kez çalışır.
        val build = app(tweaks = arrayOf({ it.copy(build = listOf(listOf("sh", "-c", "echo kuruldu >> built.txt"))) }))
        val b1 = mgr.prepare(build, emptyMap()).spec.workDir
        mgr.prepare(build, emptyMap())
        assertEquals("kuruldu\n", File(b1, "built.txt").readText())
        val failing = app(tweaks = arrayOf({ it.copy(build = listOf(listOf("sh", "-c", "echo hata; exit 4"))) }))
        mgr.stage("api", zip("x.txt" to "x"), "upload")
        val e = assertFailsWith<AppException> { mgr.prepare(failing, emptyMap()) }
        assertTrue("çıkış 4" in (e.message ?: "") && "hata" in (e.message ?: ""), e.message)

        // Geri dönüş: eski sürüm etkinleştirilir; bekleyen bırakılabilir.
        mgr.promote(app(), r1.id)
        mgr.activate("api", r2.id)
        assertEquals(r2.id, mgr.state("api").pending)
        mgr.discardPending("api")
        assertNull(mgr.state("api").pending)
        mgr.markFailed("api", r2.id, "neden")
        assertEquals("neden", mgr.state("api").failed[r2.id])
    }

    private class FakeEngine : EngineControl {
        val deployed = mutableListOf<ServerConfig>()
        override fun deploy(config: ServerConfig) { deployed += config }
        override fun stop() {}
    }

    private fun httpGet(url: String): Int = runCatching {
        (URI(url).toURL().openConnection() as HttpURLConnection).run {
            connectTimeout = 1_000; readTimeout = 2_000
            try { responseCode } finally { disconnect() }
        }
    }.getOrDefault(-1)

    @Test
    fun `deploy, saglik denetimi ve onceki surume donus`() = runBlocking {
        assumeTrue("python3 gerekli", runCatching { ProcessBuilder("python3", "--version").start().waitFor() == 0 }.getOrDefault(false))
        val port = freePort()
        val mgr = AppManager(File(dir, "apps"), log = logs::add)
        val orch = DeployOrchestrator(FakeEngine(), supervisor, { error("bileşen yok") }, log = logs::add, apps = mgr)
        val a = app(port, { it.copy(startTimeoutSec = 5, restart = RestartPolicy.NEVER) })
        val cfg = ServerConfig(apps = listOf(a))

        // Kod yokken: deploy durmaz, sorun bildirilir.
        val empty = orch.deploy(cfg)
        assertTrue(empty.componentErrors.single().contains("henüz kod yüklenmedi"), empty.componentErrors.toString())

        val good = "#!/bin/sh\nexec python3 -m http.server \"\$PORT\" --bind 127.0.0.1\n"
        val r1 = mgr.stage("api", zip("run.sh" to good, "index.html" to "<h1>v1</h1>"), "upload")
        assertEquals(emptyList(), orch.deploy(cfg).componentErrors)
        assertEquals(r1.id, mgr.state("api").current)
        assertEquals(200, httpGet("http://127.0.0.1:$port/"))

        // Bozuk sürüm: sağlık denetiminden geçmez, önceki sürüme dönülür.
        val r2 = mgr.stage("api", zip("run.sh" to "#!/bin/sh\necho bozuk\nexit 3\n"), "upload")
        val result = orch.deploy(cfg)
        assertTrue(result.componentErrors.any { "sağlık denetiminden geçmedi" in it && "Önceki sürüme" in it }, result.componentErrors.toString())
        val st = mgr.state("api")
        assertEquals(r1.id, st.current)
        assertNull(st.pending)
        assertNotNull(st.failed[r2.id])
        assertEquals(200, httpGet("http://127.0.0.1:$port/"))
        assertEquals(r1.id, supervisor.statuses.value["app:api"]?.let { mgr.state("api").current })

        // Kapatılan uygulama durdurulur.
        orch.deploy(ServerConfig(apps = listOf(a.copy(enabled = false))))
        assertFalse(supervisor.isAlive("app:api"))
    }

    /** Sahte uygulama: yankı uçları, yönlendirme, yükleme, akış ve WebSocket. */
    private fun upstream(): Int {
        val s = embeddedServer(CIO, port = 0) {
            install(io.ktor.server.websocket.WebSockets)
            routing {
                suspend fun ApplicationCall.echo() = respondText(
                    buildJsonObject {
                        put("path", JsonPrimitive(request.path()))
                        put("query", JsonPrimitive(request.local.uri.substringAfter('?', "")))
                        for (h in listOf("X-DroidServer-User", "X-DroidServer-Role", "X-DroidServer-Admin", "X-Forwarded-Prefix", "X-Forwarded-For", "Authorization", "X-Custom")) {
                            request.header(h)?.let { put(h, JsonPrimitive(it)) }
                        }
                    }.toString(),
                    ContentType.Application.Json,
                )
                get("/echo") { call.echo() }
                get("/internal/echo") { call.echo() }
                get("/redirect") { call.response.headers.append(HttpHeaders.Location, "/login"); call.respondText("", status = HttpStatusCode.Found) }
                post("/upload") { call.respondText(call.receive<ByteArray>().size.toString()) }
                get("/stream") {
                    call.respondTextWriter(ContentType.Text.EventStream) {
                        repeat(3) { write("data: $it\n\n"); flush() }
                    }
                }
                get("/gz") {
                    val raw = ByteArrayOutputStream().also { o -> java.util.zip.GZIPOutputStream(o).use { it.write("merhaba ".repeat(1000).toByteArray()) } }.toByteArray()
                    call.response.headers.append(HttpHeaders.ContentEncoding, "gzip")
                    call.respondBytes(raw, ContentType.Text.Plain)
                }
                get("/bin") { call.respondBytes(ByteArray(100_000) { (it % 251).toByte() }, ContentType.Application.OctetStream) }
                webSocket("/ws") { for (f in incoming) if (f is Frame.Text) send(Frame.Text("yankı: " + f.readText())) }
            }
        }.start(wait = false)
        servers += s
        return runBlocking { s.engine.resolvedConnectors().first().port }
    }

    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject
    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.content

    @Test
    fun `ters vekil`() = testApplication {
        val key = "yonetici-anahtari-1234567890"
        val cfg = ServerConfig(
            auth = AuthConfig(AuthMode.API_KEY, apiKeys = listOf(key), users = UsersConfig(enabled = true)),
            apps = listOf(
                AppConfig("pub", command = listOf("x"), port = upstream(), websockets = true, maxBodyMb = 1),
                AppConfig("priv", command = listOf("x"), port = upstream(), path = "/internal", stripPath = false, access = AppAccess.AUTH),
                AppConfig("yonetim", command = listOf("x"), port = upstream(), path = "/yonetim", access = AppAccess.ADMIN),
                AppConfig("down", command = listOf("x"), port = freePort()),
            ),
        )
        assertEquals(emptyList(), ConfigValidator.validate(cfg))
        val db = TestDb.create(Dialect.SQLITE).open()
        SchemaMigrator(db).migrate(cfg.schemaCollections())
        application { droidServerModule(cfg, db) }

        val pub = json(client.get("/apps/pub/echo?x=1&token=t") {
            header("X-DroidServer-User", "1") // taklit: atılır
            header("Authorization", "Bearer uygulamanin-kendi-tokeni")
            header("X-Custom", "geçer")
        }.bodyAsText())
        assertEquals("/echo", pub.str("path"))
        assertEquals("x=1&token=t", pub.str("query"), "herkese açık uygulamada sorgu olduğu gibi")
        assertEquals("/apps/pub", pub.str("X-Forwarded-Prefix"))
        assertNull(pub.str("X-DroidServer-User"))
        assertEquals("Bearer uygulamanin-kendi-tokeni", pub.str("Authorization"))
        assertEquals("geçer", pub.str("X-Custom"))

        val noFollow = createClient { followRedirects = false }
        val redirect = noFollow.get("/apps/pub/redirect")
        assertEquals(HttpStatusCode.Found, redirect.status)
        assertEquals("/apps/pub/login", redirect.headers[HttpHeaders.Location])

        assertEquals("100000", client.post("/apps/pub/upload") { setBody(ByteArray(100_000)) }.bodyAsText())
        assertEquals(HttpStatusCode.PayloadTooLarge, client.post("/apps/pub/upload") { setBody(ByteArray(2 * 1024 * 1024)) }.status)
        val stream = client.get("/apps/pub/stream")
        assertEquals("data: 0\n\ndata: 1\n\ndata: 2\n\n", stream.bodyAsText())
        assertTrue(stream.headers[HttpHeaders.ContentType]!!.startsWith("text/event-stream"))
        // Uygulamanın sıkıştırdığı yanıt bir kez daha sıkıştırılmaz.
        val gz = client.get("/apps/pub/gz") { header(HttpHeaders.AcceptEncoding, "gzip") }
        assertEquals("gzip", gz.headers[HttpHeaders.ContentEncoding])
        assertEquals("merhaba ".repeat(1000), java.util.zip.GZIPInputStream(gz.readRawBytes().inputStream()).readBytes().decodeToString())
        val bin = client.get("/apps/pub/bin")
        assertEquals(100_000, bin.bodyAsText(Charsets.ISO_8859_1).length)

        // Giriş gerekli uygulama: kimlik sunucuda doğrulanır ve başlıkla iletilir; sunucunun token'ı iletilmez.
        assertEquals(HttpStatusCode.Unauthorized, client.get("/internal/echo").status)
        val signup = json(client.post("/api/auth/signup") { contentType(ContentType.Application.Json); setBody("""{"email":"a@example.com","password":"parola-123"}""") }.bodyAsText())
        val token = signup.str("token")!!
        val userId = signup.getValue("user").jsonObject.str("id")
        val priv = json(client.get("/internal/echo?token=x&y=2") { bearerAuth(token) }.bodyAsText())
        assertEquals("/internal/echo", priv.str("path"), "stripPath kapalı")
        assertEquals("y=2", priv.str("query"), "kimlik token'ı sorgudan atılır")
        assertEquals(userId, priv.str("X-DroidServer-User"))
        assertEquals("user", priv.str("X-DroidServer-Role"))
        assertEquals("false", priv.str("X-DroidServer-Admin"))
        assertNull(priv.str("Authorization"))

        assertEquals(HttpStatusCode.Forbidden, client.get("/yonetim/echo") { bearerAuth(token) }.status)
        assertEquals("true", json(client.get("/yonetim/echo") { bearerAuth(key) }.bodyAsText()).str("X-DroidServer-Admin"))

        val down = client.get("/apps/down/")
        assertEquals(HttpStatusCode.BadGateway, down.status)
        assertTrue("çalışmıyor" in down.bodyAsText())

        // WebSocket
        val ws = createClient { install(WebSockets) }
        ws.webSocket("/apps/pub/ws") {
            send(Frame.Text("merhaba"))
            assertEquals("yankı: merhaba", (incoming.receive() as Frame.Text).readText())
        }

        // API etkilenmez; uygulama listesi yönetici içindir.
        assertEquals(HttpStatusCode.OK, client.get("/api/health").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/_apps").status)
        assertEquals(4, Json.parseToJsonElement(client.get("/api/_apps") { bearerAuth(key) }.bodyAsText()).let { it as JsonArray }.size)
        db.close()
    }

    private class FakeHost : AppsHost {
        val pulls = mutableListOf<Pair<String, String?>>()
        var redeploys = 0
        override fun status() = JsonArray(emptyList())
        override fun pullAndDeploy(app: String, commit: String?, reason: String) { pulls += app to commit }
        override fun redeploy(reason: String) { redeploys++ }
    }

    @Test
    fun `git webhook ve deploy ucu`() = testApplication {
        val key = "yonetici-anahtari-1234567890"
        val secret = "webhook-sirri-0123456789"
        val cfg = ServerConfig(
            auth = AuthConfig(AuthMode.API_KEY, apiKeys = listOf(key)),
            apps = listOf(
                AppConfig("api", command = listOf("x"), port = 3001, git = GitSource(repo = "o/r", branch = "main", webhookSecret = secret)),
                AppConfig("zip", command = listOf("x"), port = 3002),
            ),
        )
        val host = FakeHost()
        val db = TestDb.create(Dialect.SQLITE).open()
        application { droidServerModule(cfg, db, appsHost = host) }
        val push = """{"ref":"refs/heads/main","after":"abc123def"}"""
        val sig = "sha256=" + GitWebhook.hmacHex(secret.toByteArray(), push.toByteArray())

        assertEquals(HttpStatusCode.Accepted, client.post("/api/_apps/api/hook") { header("X-Hub-Signature-256", sig); setBody(push) }.status)
        val badSig = sig.dropLast(1) + (if (sig.last() == '0') "1" else "0")
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/_apps/api/hook") { header("X-Hub-Signature-256", badSig); setBody(push) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/_apps/api/hook") { setBody(push) }.status)
        val other = """{"ref":"refs/heads/dev","after":"1"}"""
        val ignored = client.post("/api/_apps/api/hook") { header("X-Gitlab-Token", secret); setBody(other) }
        assertTrue("dev" in ignored.bodyAsText())
        assertEquals(HttpStatusCode.OK, client.post("/api/_apps/api/hook") { header("X-GitHub-Event", "ping"); header("X-Hub-Signature-256", "sha256=" + GitWebhook.hmacHex(secret.toByteArray(), "{}".toByteArray())); setBody("{}") }.status)
        assertEquals(HttpStatusCode.Accepted, client.post("/api/_apps/api/hook?token=$secret") { setBody(push) }.status)
        assertEquals(HttpStatusCode.NotFound, client.post("/api/_apps/zip/hook") { setBody(push) }.status)
        assertEquals(2, host.pulls.size)

        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/_apps/api/deploy").status)
        assertEquals(HttpStatusCode.Accepted, client.post("/api/_apps/api/deploy") { bearerAuth(key) }.status)
        assertEquals(HttpStatusCode.Accepted, client.post("/api/_apps/zip/deploy") { bearerAuth(key) }.status)
        assertEquals(listOf("api" to null), host.pulls.drop(2))
        assertEquals(1, host.redeploys)
        db.close()
    }

    @Test
    fun `git arsivi indirme`() {
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val archive = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z -> z.putNextEntry(ZipEntry("o-r-abc123/backend/server.js")); z.write("ok".toByteArray()); z.closeEntry() }
        }.toByteArray()
        val s = embeddedServer(CIO, port = 0) {
            routing {
                get("/repos/o/r/commits/main") {
                    requests += "commit " + call.request.header("Authorization")
                    call.respondText("""{"sha":"abc123"}""", ContentType.Application.Json)
                }
                get("/repos/o/r/zipball/abc123") {
                    requests += "zip " + call.request.header("Authorization")
                    call.response.headers.append(HttpHeaders.Location, "/codeload/o-r-abc123.zip")
                    call.respondText("", status = HttpStatusCode.Found)
                }
                get("/codeload/o-r-abc123.zip") { call.respondBytes(archive) }
                get("/repos/o/gizli/commits/main") { call.respondText("", status = HttpStatusCode.NotFound) }
            }
        }.start(wait = false)
        servers += s
        val port = runBlocking { s.engine.resolvedConnectors().first().port }
        val fetcher = GitFetcher(baseOverride = "http://127.0.0.1:$port")
        val git = GitSource(GitProvider.GITHUB, "o/r", token = "ghp_test", subdir = "backend")

        val fetched = fetcher.download(git, File(dir, "dl"))
        assertEquals("abc123", fetched.commit)
        assertEquals(listOf("commit Bearer ghp_test", "zip Bearer ghp_test"), requests)
        val mgr = AppManager(File(dir, "apps"))
        val r = mgr.stage("api", fetched.file, "git", fetched.commit, git.subdir)
        assertEquals("ok", File(File(dir, "apps/api/releases"), r.id).resolve("server.js").readText())

        val e = assertFailsWith<AppException> { fetcher.latestCommit(git.copy(repo = "o/gizli")) }
        assertTrue("404" in (e.message ?: ""), e.message)
    }

    @Test
    fun `dogrulama`() {
        val base = ServerConfig(apps = listOf(AppConfig("api", command = listOf("node", "a.js"), port = 3000)))
        val errors = { c: ServerConfig -> ConfigValidator.validateDetailed(c).map { it.path }.filter { it.startsWith("apps") }.toSet() }
        fun with(vararg apps: AppConfig) = errors(base.copy(apps = apps.toList()))
        val a = base.apps.single()
        assertEquals(emptySet(), errors(base))
        assertEquals(setOf("apps[0].name"), with(a.copy(name = "Api")))
        assertEquals(setOf("apps[1].name", "apps[1].port", "apps[1].path"), with(a, a.copy()))
        assertEquals(setOf("apps[0].port"), with(a.copy(port = 8080)), "sunucunun portu")
        assertEquals(setOf("apps[0].port"), with(a.copy(port = 80)))
        assertEquals(setOf("apps[0].port"), errors(base.copy(access = AccessConfig(AccessMode.DIRECT), apps = listOf(a.copy(port = 8443)))))
        assertEquals(setOf("apps[0].path"), with(a.copy(path = "/api/x")), "API yoluyla çakışır")
        assertEquals(setOf("apps[0].path"), with(a.copy(path = "/_admin")))
        assertEquals(setOf("apps[0].path"), with(a.copy(path = "/")))
        assertEquals(setOf("apps[0].path"), with(a.copy(path = "/a b")))
        assertEquals(setOf("apps[1].path"), with(a.copy(path = "/x"), a.copy(name = "b", port = 3001, path = "/x/y")))
        assertEquals(setOf("apps[0].command"), with(a.copy(command = emptyList())))
        assertEquals(setOf("apps[0].health"), with(a.copy(health = "saglik")))
        assertEquals(setOf("apps[0].git.repo"), with(a.copy(git = GitSource(repo = "sadece-ad"))))
        assertEquals(setOf("apps[0].git.host"), with(a.copy(git = GitSource(GitProvider.GITEA, "o/r"))))
        assertEquals(setOf("apps[0].git.repo"), with(a.copy(git = GitSource(GitProvider.URL, "http://x/a.zip"))))
        assertEquals(setOf("apps[0].git.subdir"), with(a.copy(git = GitSource(repo = "o/r", subdir = "../x"))))
        assertEquals(setOf("apps[0].git.branch"), with(a.copy(git = GitSource(repo = "o/r", branch = "a..b"))))
        assertEquals(setOf("apps[0].git.webhookSecret"), with(a.copy(git = GitSource(repo = "o/r", webhookSecret = "kisa"))))
        assertEquals(emptySet(), with(a.copy(git = GitSource(GitProvider.GITLAB, "grup/alt/depo", host = "git.example.com"))))
    }
}
