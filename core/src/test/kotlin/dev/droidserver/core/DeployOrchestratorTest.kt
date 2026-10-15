package dev.droidserver.core

import dev.droidserver.core.api.RecordService
import dev.droidserver.core.config.AuthConfig
import dev.droidserver.core.config.AuthMode
import dev.droidserver.core.config.CollectionTemplate
import dev.droidserver.core.config.ComponentConfig
import dev.droidserver.core.config.HttpConfig
import dev.droidserver.core.config.RestartPolicy
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.db.SchemaMigrator
import dev.droidserver.core.process.ProcessSupervisor
import dev.droidserver.core.process.Readiness
import dev.droidserver.core.server.DeployException
import dev.droidserver.core.server.DeployOrchestrator
import dev.droidserver.core.server.EngineControl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.Collections
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.measureTime

class DeployOrchestratorTest {
    private class FakeEngine(private val onDeploy: (ServerConfig) -> Unit = {}) : EngineControl {
        val deployed = mutableListOf<ServerConfig>()
        override fun deploy(config: ServerConfig) { onDeploy(config); deployed += config }
        override fun stop() {}
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val supervisor = ProcessSupervisor(scope) {}
    private val dir = Files.createTempDirectory("ds-orch").toFile()
    private val logs: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private fun sleeper(id: String, readiness: Readiness? = null) =
        ProcessSupervisor.Spec(id, listOf("sh", "-c", "sleep 30"), dir, readiness = readiness)

    private fun withComponents(vararg ids: String) = ServerConfig(components = ids.map { ComponentConfig(it) })

    private suspend fun runningPid(id: String, accept: (Long?) -> Boolean = { true }): Long? = withTimeout(10_000) {
        supervisor.statuses.first { s -> s[id]?.let { it.phase == ProcessSupervisor.Phase.RUNNING && accept(it.pid) } == true }
    }.getValue(id).pid

    private fun File.readTextOrEmpty() = if (exists()) readText() else ""

    @AfterTest
    fun cleanup() {
        supervisor.stopAll()
        scope.cancel()
    }

    @Test
    fun `ayni bilesen speciyle ikinci deploy sureci yeniden baslatmaz`() = runBlocking {
        var spec = sleeper("db")
        val orch = DeployOrchestrator(FakeEngine(), supervisor, { spec }, log = logs::add)

        orch.deploy(withComponents("db"))
        val pid = runningPid("db")
        orch.deploy(withComponents("db"))
        assertEquals(pid, runningPid("db"))
        assertTrue(logs.any { it.contains("[db] değişmedi") }, logs.joinToString("\n"))

        spec = spec.copy(version = "2") // sürüm değişince yeniden başlar
        orch.deploy(withComponents("db"))
        assertNotEquals(pid, runningPid("db") { it != pid })
    }

    @Test
    fun `sunucu bilesenler hazir olduktan sonra deploy edilir, istenmeyenler en son durur`() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val engine = FakeEngine {
            Socket("127.0.0.1", port).close() // beklenmeden çağrılsaydı bağlantı reddedilirdi
            logs += "engine.deploy"
        }
        supervisor.start(sleeper("old")) // önceki deploy'dan kalan bileşen
        val orch = DeployOrchestrator(
            engine, supervisor, { sleeper(it.id, Readiness(port, timeoutSec = 10)) }, log = logs::add,
        )

        ServerSocket().use { listener ->
            // Veritabanının geç ayağa kalkmasını taklit eder.
            thread { Thread.sleep(700); listener.bind(InetSocketAddress("127.0.0.1", port)) }
            val result = orch.deploy(withComponents("db"))
            assertTrue(result.componentErrors.isEmpty(), result.componentErrors.joinToString())
        }

        assertEquals(1, engine.deployed.size)
        val engineAt = logs.indexOf("engine.deploy")
        val oldStoppedAt = logs.indexOfFirst { it.startsWith("[old] durduruldu") }
        assertTrue(engineAt in 0 until oldStoppedAt, logs.joinToString("\n"))
        assertFalse(supervisor.isAlive("old"))
        assertTrue(supervisor.isAlive("db"))
    }

    @Test
    fun `hazir olmayan bilesen raporlanir, sunucu yine deploy edilir`() = runBlocking {
        val closedPort = ServerSocket(0).use { it.localPort }
        val engine = FakeEngine()
        val specs = mapOf(
            "yavas" to sleeper("yavas", Readiness(closedPort, timeoutSec = 1)),
            // Hemen çıkan ve yeniden başlatılmayan süreç: zaman aşımını beklemeden vazgeçilir.
            "coken" to ProcessSupervisor.Spec(
                "coken", listOf("sh", "-c", "exit 1"), dir, restart = RestartPolicy.NEVER,
                readiness = Readiness(closedPort, timeoutSec = 30),
            ),
        )
        val orch = DeployOrchestrator(engine, supervisor, { specs.getValue(it.id) }, log = logs::add)

        lateinit var result: DeployOrchestrator.Result
        val took = measureTime { result = orch.deploy(withComponents("yavas", "coken")) }

        assertEquals(2, result.componentErrors.size, result.componentErrors.joinToString())
        assertTrue(took.inWholeSeconds < 10, "çöken süreç için 30 sn beklenmemeli ($took)")
        assertEquals(1, engine.deployed.size)
    }

    @Test
    fun `durdurma komutu varsa SIGTERM beklenmez`() = runBlocking {
        val pid = File(dir, "inatci.pid")
        // SIGTERM'ü yok sayan süreç: durdurma komutu olmasa 5 sn bekleyip SIGKILL gerekirdi.
        supervisor.start(
            ProcessSupervisor.Spec(
                "inatci", listOf("sh", "-c", "echo $$ > ${pid.absolutePath}; trap '' TERM; while :; do sleep 1; done"), dir,
                stopCommand = listOf("sh", "-c", "kill -9 $(cat ${pid.absolutePath})"),
            ),
        )
        runningPid("inatci")
        withTimeout(5_000) { while (pid.readTextOrEmpty().isBlank()) delay(50) }
        val took = measureTime { supervisor.stop("inatci") }
        assertTrue(took.inWholeMilliseconds < 3_000, "durdurma komutu kullanılmalı ($took)")
        assertFalse(supervisor.isAlive("inatci"))
    }

    @Test
    fun `veritabani degisirken veri tasinir, basarisizsa eski konfigurasyon geri acilir`() = runBlocking {
        val a = TestDb.create(Dialect.SQLITE)
        val b = TestDb.create(Dialect.SQLITE)
        val todos = CollectionTemplate.TODOS.create()
        a.open().use { db ->
            SchemaMigrator(db).migrate(listOf(todos))
            RecordService(db, todos).create(buildJsonObject { put("title", JsonPrimitive("taşınacak")) })
        }
        val events = mutableListOf<String>()
        val engine = object : EngineControl {
            override fun deploy(config: ServerConfig) { events += "deploy:${config.database.file}" }
            override fun stop() { events += "stop" }
            override fun pauseWrites() { events += "pause" }
            override fun resumeWrites() { events += "resume" }
        }
        val orch = DeployOrchestrator(
            engine, supervisor, { error("bileşen yok") }, log = logs::add,
            openDatabase = { cfg -> if (cfg == a.config) a.open() else b.open() },
        )
        val previous = ServerConfig(database = a.config, collections = listOf(todos))
        val next = previous.copy(database = b.config)

        assertEquals(mapOf("todos" to 1L), orch.copyDatabase(previous, next).rows)
        assertEquals(listOf("stop"), events, "kopyalama boyunca sunucu durur; yeni konfigürasyonu çağıran deploy eder")
        b.open().use { assertEquals(1, RecordService(it, todos).list(1, 10, null, emptyList()).totalItems) }

        // Hedef artık dolu: taşıma reddedilir, sunucu eski konfigürasyonla geri açılır.
        events.clear()
        val e = runCatching { orch.copyDatabase(previous, next) }.exceptionOrNull()
        assertTrue(e is DeployException && e.errors.first().contains("boş değil"), "$e")
        assertEquals(listOf("stop", "deploy:${a.config.file}"), events)

        // Salt okunur seçiminde sunucu durmaz; başarısızlıkta yazmalar yeniden açılır.
        events.clear()
        val readOnly = DeployOrchestrator.MoveOptions(DeployOrchestrator.DuringMove.READ_ONLY)
        assertTrue(runCatching { orch.copyDatabase(previous, next, readOnly) }.exceptionOrNull() is DeployException)
        assertEquals(listOf("pause", "resume"), events)

        // Kullanıcı hedefteki kayıtların silinmesini seçerse taşıma yapılır.
        a.open().use { RecordService(it, todos).create(buildJsonObject { put("title", JsonPrimitive("ikinci")) }) }
        events.clear()
        assertEquals(mapOf("todos" to 2L), orch.copyDatabase(previous, next, readOnly.copy(replaceExisting = true)).rows)
        assertEquals(listOf("pause"), events, "yazmalar yeni konfigürasyonun deploy'una kadar kapalı kalır")
        b.open().use { assertEquals(2, RecordService(it, todos).list(1, 10, null, emptyList()).totalItems) }

        // Hedefe bağlanılamıyorsa (örn. Postgres bileşeni başlatılamadı) sunucuya hiç dokunulmaz, neden önce yazılır.
        events.clear()
        val unreachable = DeployOrchestrator(
            engine, supervisor, { error("CANNOT LINK EXECUTABLE") }, log = logs::add,
            openDatabase = { cfg -> if (cfg == a.config) a.open() else error("Connection refused") },
        )
        val withComponent = next.copy(components = listOf(ComponentConfig("postgres")))
        val err = runCatching { unreachable.copyDatabase(previous, withComponent) }.exceptionOrNull() as DeployException
        assertEquals(emptyList(), events, "sunucu durdurulmaz")
        assertTrue(err.errors.first().contains("[postgres] başlatılamadı: CANNOT LINK EXECUTABLE"), err.errors.toString())
        assertTrue(err.errors.last().contains("Connection refused"), err.errors.toString())
    }

    @Test
    fun `gecersiz konfigurasyon veya eksik sir hicbir seye dokunmaz, referanslar cozulur`() = runBlocking {
        val engine = FakeEngine()
        var prepared = 0
        val secrets = mapOf("jwt_1" to "x".repeat(40))
        val orch = DeployOrchestrator(engine, supervisor, { prepared++; sleeper(it.id) }, secrets::get, log = logs::add)

        val missing = withComponents("db").copy(auth = AuthConfig(AuthMode.JWT, jwtSecret = "secret:yok"))
        val e1 = runCatching { orch.deploy(missing) }.exceptionOrNull()
        assertTrue(e1 is DeployException && e1.errors.single().contains("secret:yok"), "$e1")

        val badPort = withComponents("db").copy(server = HttpConfig(port = 80))
        assertTrue(runCatching { orch.deploy(badPort) }.exceptionOrNull() is DeployException)

        assertEquals(0, prepared)
        assertTrue(engine.deployed.isEmpty())

        orch.deploy(ServerConfig(auth = AuthConfig(AuthMode.JWT, jwtSecret = "secret:jwt_1")))
        assertEquals("x".repeat(40), engine.deployed.single().auth.jwtSecret)
    }
}
