package dev.droidserver.core

import dev.droidserver.core.config.AuthConfig
import dev.droidserver.core.config.AuthMode
import dev.droidserver.core.config.CollectionConfig
import dev.droidserver.core.config.ConfigJson
import dev.droidserver.core.config.ConfigSecrets
import dev.droidserver.core.config.ConfigValidator
import dev.droidserver.core.config.FailoverConfig
import dev.droidserver.core.config.FieldConfig
import dev.droidserver.core.config.FieldType
import dev.droidserver.core.config.HttpConfig
import dev.droidserver.core.config.ReplicaSource
import dev.droidserver.core.config.ReplicationConfig
import dev.droidserver.core.config.Rule
import dev.droidserver.core.config.Rules
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.replication.ChangeLog
import dev.droidserver.core.replication.Failover
import dev.droidserver.core.replication.FailoverHost
import dev.droidserver.core.replication.FailoverTiming
import dev.droidserver.core.server.ServerEngine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Faz 16.9: otomatik devralma, eski ana sunucunun kopyaya dönmesi ve ana rolün kayıpsız geri alınması. */
class FailoverTest {
    private val key = "yonetici-anahtari-1234567890"
    private val notes = CollectionConfig(
        "notes", listOf(FieldConfig("title", FieldType.TEXT, required = true)),
        rules = Rules(Rule.PUBLIC, Rule.PUBLIC, Rule.PUBLIC, Rule.PUBLIC, Rule.PUBLIC),
    )
    private val timing = FailoverTiming(afterScaleMs = 50, fenceIntervalMs = 200, caughtUpMs = 500, stepDownMs = 10_000, probeTimeoutMs = 1_000)
    private val dirs = mutableListOf<File>()
    private val nodes = mutableListOf<Node>()

    @AfterTest
    fun stop() {
        nodes.forEach { runCatching { it.engine.stop() } }
        dirs.forEach { it.deleteRecursively() }
    }

    /** Uygulamanın yaptığını yapan sunucu: konfigürasyonu tutar, değişince ayrı iş parçacığında yeniden deploy eder. */
    private inner class Node(val port: Int, @Volatile var config: ServerConfig) {
        val events = CopyOnWriteArrayList<String>()
        val engine = run {
            val db = TestDb.create(Dialect.SQLITE)
            val dir = Files.createTempDirectory("ds-fo").toFile().also { dirs += it }
            ServerEngine({ db.open() }, dataDir = dir) {}
        }
        val url = "http://127.0.0.1:$port/api"

        init {
            nodes += this
            engine.failoverTiming = timing
            engine.failoverHost = FailoverHost { event, _, _, edit ->
                events += event
                config = edit(config)
                redeploy()
            }
            engine.onPrimaryConfig = { primary, _ ->
                config = ChangeLog.merge(config, primary)
                redeploy()
            }
        }

        fun redeploy() = Thread { runCatching { engine.deploy(config) }.onFailure { events += "hata: ${it.message}" } }.start()
        val role: String? get() = Failover.role(url, 1_000)?.role
    }

    private fun freePort() = ServerSocket(0).use { it.localPort }

    private fun primary(port: Int, failover: FailoverConfig = FailoverConfig()) = ServerConfig(
        name = "Ana",
        server = HttpConfig(port = port, host = "127.0.0.1"),
        auth = AuthConfig(AuthMode.API_KEY, apiKeys = listOf(key)),
        collections = listOf(notes),
        replication = ReplicationConfig(source = true, failover = failover),
    )

    private fun replicaOf(primary: Node, port: Int, failover: FailoverConfig): ServerConfig {
        val local = ServerConfig(
            server = HttpConfig(port = port, host = "127.0.0.1"),
            replication = ReplicationConfig(replicaOf = ReplicaSource(primary.url, key, intervalSec = 1), failover = failover),
        )
        return ChangeLog.merge(local, primary.config)
    }

    private fun call(method: String, url: String, body: String? = null): Pair<Int, String> {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 2_000
        conn.setRequestProperty("Authorization", "Bearer $key")
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = conn.responseCode
        val text = (if (code < 400) conn.inputStream else conn.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
        return code to text
    }

    private fun titles(n: Node): List<String> = runCatching {
        Json.parseToJsonElement(call("GET", "${n.url}/collections/notes/records?sort=id").second).jsonObject.getValue("items").jsonArray
            .map { it.jsonObject.getValue("title").jsonPrimitive.content }
    }.getOrDefault(emptyList())

    private fun add(n: Node, title: String) = call("POST", "${n.url}/collections/notes/records", """{"title":"$title"}""")

    private fun waitFor(what: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > end) throw AssertionError("zaman aşımı: $what")
            Thread.sleep(100)
        }
    }

    @Test
    fun `donusumler ve dogrulama`() {
        val replica = ServerConfig(replication = ReplicationConfig(replicaOf = ReplicaSource("http://10.0.0.2:8080/api", "secret:replica_key_ab", id = "x"), failover = FailoverConfig(auto = true)))
        val promoted = Failover.promote(replica, "ana-kimlik")
        assertNull(promoted.replication.replicaOf)
        assertTrue(promoted.replication.source)
        assertEquals(ReplicaSource("http://10.0.0.2:8080/api", "secret:replica_key_ab", id = "ana-kimlik"), promoted.replication.formerPrimary)
        assertEquals(emptyList(), ConfigValidator.validate(promoted))

        val demoted = Failover.demote(promoted, "http://10.0.0.3:8080/api/", "anahtar")
        assertEquals(ReplicaSource("http://10.0.0.3:8080/api", "anahtar", demoted = true), demoted.replication.replicaOf)
        assertTrue(!demoted.replication.source && demoted.replication.formerPrimary == null)

        // Eski ana sunucunun anahtarı da şifreli depoya gider ve çözülür.
        val stored = mutableMapOf<String, String>()
        val plain = ConfigJson.encodeToJsonElement(ServerConfig.serializer(), ServerConfig(replication = ReplicationConfig(source = true, formerPrimary = ReplicaSource("http://a/api", "duz-anahtar")))).jsonObject
        val text = ConfigSecrets.externalize(plain) { kind, v -> "${kind}_1".also { stored[it] = v } }.toString()
        assertTrue("duz-anahtar" !in text && "secret:replica_key_1" in text, text)
        assertEquals("duz-anahtar", ConfigSecrets.resolve(ServerConfig.fromJson(text)) { stored[it] }.replication.formerPrimary?.key)

        fun errors(f: FailoverConfig, rep: ReplicationConfig = ReplicationConfig(source = true)) = ConfigValidator.validate(ServerConfig(replication = rep.copy(failover = f))).joinToString("\n")
        assertTrue("30 sn" in errors(FailoverConfig(afterSec = 5)))
        assertTrue("Tanık" in errors(FailoverConfig(witness = "192.168.1.1")))
        assertTrue("yalnızca kopyada" in errors(FailoverConfig(auto = true), ReplicationConfig()))
    }

    @Test
    fun `ana sunucu dusunce kopya devralir, eski ana donunce kopyaya doner ve rolunu geri alir`() {
        val a = Node(freePort(), ServerConfig()).apply { config = primary(port, FailoverConfig(failback = true)) }
        a.engine.deploy(a.config)
        add(a, "bir")
        val b = Node(freePort(), ServerConfig())
        b.config = replicaOf(a, b.port, FailoverConfig(auto = true, afterSec = 30))
        b.engine.deploy(b.config)
        waitFor("kopya eşitlendi") { titles(b) == listOf("bir") }
        assertEquals("replica", b.role)
        assertEquals(503, add(b, "kopyaya yazılmaz").first)

        // Ana sunucu düşer: kopya 30 × 50 ms sonra devralır ve yazılabilir olur.
        a.engine.stop()
        waitFor("kopya devraldı") { b.role == "primary" && add(b, "iki").first == 201 }
        assertTrue("replica.failover" in b.events, b.events.toString())
        assertEquals(a.url, b.config.replication.formerPrimary?.url)
        assertEquals(a.engine.instanceId, b.config.replication.formerPrimary?.id)

        // Eski ana sunucu döner: yazmadan önce devralındığını görür ve yeni ana sunucunun kopyası olur.
        a.engine.deploy(a.config)
        assertEquals(503, add(a, "çatışma").first)
        waitFor("eski ana kopyaya döndü") { "replica.demoted" in a.events && titles(a) == listOf("bir", "iki") }

        // Güncel kaldıktan sonra rolünü kayıpsız geri alır; devralan yeniden kopya olur.
        waitFor("geri alındı") { a.role == "primary" && b.role == "replica" }
        assertTrue("replica.failback" in a.events, a.events.toString())
        waitFor("ana yazılabilir") { add(a, "üç").first == 201 }
        waitFor("kopya yeniden eşit") { titles(b) == listOf("bir", "iki", "üç") }
        assertEquals(503, add(b, "dört").first)
        assertTrue(a.events.none { it.startsWith("hata") } && b.events.none { it.startsWith("hata") }, "${a.events} ${b.events}")
    }

    @Test
    fun `elle devralinca calisan eski ana sunucu yazmayi keser`() {
        val a = Node(freePort(), ServerConfig()).apply { config = primary(port) }
        a.engine.deploy(a.config)
        val b = Node(freePort(), ServerConfig())
        b.config = replicaOf(a, b.port, FailoverConfig())
        b.engine.deploy(b.config)
        add(a, "bir")
        waitFor("kopya eşitlendi") { titles(b) == listOf("bir") }

        // Uygulamadaki "Devral" düğmesi: ana sunucu hâlâ çalışıyor (ör. ağ bölündü, sonra birleşti).
        b.config = Failover.promote(b.config)
        b.engine.deploy(b.config)
        waitFor("eski ana devredildi") { "replica.demoted" in a.events }
        assertEquals(503, add(a, "ayrışmaz").first)
        waitFor("eski ana kopya oldu") { a.role == "replica" }
        assertEquals(201, add(b, "iki").first)
        waitFor("eşit") { titles(a) == listOf("bir", "iki") }
        // Eski ana sunucunun geri alma isteği yok: kopya kalır.
        Thread.sleep(1_000)
        assertEquals("replica", a.role)
    }
}
