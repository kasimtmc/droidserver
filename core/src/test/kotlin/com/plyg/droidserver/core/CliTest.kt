package com.plyg.droidserver.core

import com.plyg.droidserver.core.api.droidServerModule
import com.plyg.droidserver.core.db.SchemaMigrator
import com.plyg.droidserver.core.db.SqlDriver
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Faz 16.11: sunucunun verdiği komut satırı aracı Node'da gerçek sunucuya karşı (Node 18+ yoksa atlanır). */
class CliTest {
    private lateinit var db: SqlDriver
    private lateinit var dir: File
    private lateinit var server: EmbeddedServer<*, *>
    private var base = ""

    @BeforeTest
    fun start() {
        db = TestDb.create(Dialect.SQLITE).open()
        SchemaMigrator(db).migrate(SdkConfig.config.schemaCollections())
        dir = Files.createTempDirectory("ds-cli").toFile()
        server = embeddedServer(CIO, port = 0) { droidServerModule(SdkConfig.config, db) }.start(wait = false)
        base = "http://127.0.0.1:${runBlocking { server.engine.resolvedConnectors().first().port }}/api"
    }

    @AfterTest
    fun stop() {
        server.stop(100, 1000)
        db.close()
        dir.deleteRecursively()
    }

    private fun node(vararg args: String, env: Map<String, String> = emptyMap()): Pair<Int, String> {
        val p = ProcessBuilder(listOf("node", File(dir, "droidserver.mjs").path) + args).redirectErrorStream(true).apply {
            environment()["DROIDSERVER_CONFIG"] = File(dir, "cfg/cli.json").path
            environment()["LANG"] = "en_US.UTF-8"
            environment().remove("DROIDSERVER_URL")
            environment().putAll(env)
        }.start()
        p.outputStream.close()
        val out = p.inputStream.readBytes().decodeToString()
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "zaman aşımı: ${args.toList()}")
        return p.exitValue() to out
    }

    private fun ok(vararg args: String): String = node(*args).also { (code, out) -> assertEquals(0, code, "${args.toList()}: $out") }.second

    @Test
    fun `komut satiri araci`() {
        val version = runCatching { ProcessBuilder("node", "--version").start().inputStream.readBytes().decodeToString().trim() }.getOrNull()
        if (version == null || version.removePrefix("v").substringBefore('.').toIntOrNull()?.let { it < 18 } != false) {
            println("Node 18+ yok; CLI testi atlandı ($version)")
            return
        }
        // Argümanlar ASCII: test JVM'inin yerel ayarı süreç argümanlarını bozabilir. Sunucu aracı kimliksiz verir.
        File(dir, "droidserver.mjs").writeBytes(URI("$base/_cli").toURL().readBytes())

        ok("help").let { assertTrue("Records:" in it, it) }
        node("status").second.let { assertTrue("Not connected" in it, it) }
        assertEquals(1, node("login", base, "--key", "yanlis-anahtar").first)
        ok("login", base, "--key", SdkConfig.KEY).let { assertTrue("Connected" in it, it) }
        val cfg = File(dir, "cfg/cli.json")
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(cfg.toPath()))
        assertTrue(SdkConfig.KEY !in ok("whoami"), "anahtar ekrana yazılmaz")

        ok("status").let { assertTrue("running" in it, it) }
        ok("collections").let { assertTrue("todo_items" in it, it) }

        val created = Json.parseToJsonElement(ok("create", "todo_items", """{"title":"Milk","qty":2}""", "--json")).jsonObject
        val id = created.getValue("id").jsonPrimitive.content
        ok("create", "todo_items", """{"title":"Bread"}""")
        ok("list", "todo_items", "title=Milk").let { assertTrue("Milk" in it && "total 1" in it, it) }
        assertEquals("4", Json.parseToJsonElement(ok("update", "todo_items", id, """{"qty":4}""", "--json")).jsonObject.getValue("qty").jsonPrimitive.content)
        ok("get", "todo_items", id).let { assertTrue("Milk" in it, it) }

        // Dışa aktar → sil → içe aktar.
        val export = File(dir, "todo.json")
        ok("export", "todo_items", "--format", "json", "-o", export.path)
        assertEquals(2, Json.parseToJsonElement(export.readText()).jsonArray.size)
        ok("delete", "todo_items", id)
        assertEquals(1, node("get", "todo_items", id).first)
        val input = File(dir, "in.json").apply { writeText("""[{"title":"Milk","qty":4}]""") }
        val imported = Json.parseToJsonElement(ok("import", "todo_items", input.path, "--json")).jsonObject
        assertTrue(imported.toString().contains("created") || imported.toString().contains("updated"), imported.toString())

        ok("api", "GET", "/health").let { assertTrue("\"status\": \"ok\"" in it, it) }
        // Web paneli kapalı: anlaşılır hata.
        val (code, out) = node("logs")
        assertEquals(1, code)
        assertTrue("web panel" in out, out)
        // Ortam değişkenleri dosyanın yerine geçer (CI).
        assertEquals(0, node("status", env = mapOf("DROIDSERVER_URL" to base, "DROIDSERVER_KEY" to SdkConfig.KEY)).first)
        ok("logout")
        node("status").second.let { assertTrue("Not connected" in it, it) }
    }
}
