package com.plyg.droidserver.core.process

import com.plyg.droidserver.core.config.RestartPolicy
import com.plyg.droidserver.core.i18n.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Native yan servisleri (postgres, redis, cloudflared ...) başlatır, izler, çökünce yeniden başlatır.
 * Linux'taki systemd/supervisord'un küçük bir karşılığı.
 *
 * Android notu: 12+ sürümlerde "phantom process killer" uygulamanın alt süreçlerini
 * öldürebilir. Yeniden başlatma politikası bunu tolere eder, kalıcı çözüm için README'ye bakın.
 */
class ProcessSupervisor(
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = ::println,
) {
    /**
     * Süreç tanımı. Data class eşitliği (komut, ortam, sürüm ...) parmak izi görevi görür:
     * aynı tanımla tekrar [start] çağrılırsa çalışan süreç yeniden başlatılmaz.
     */
    data class Spec(
        val id: String,
        val command: List<String>,
        val workDir: File,
        val env: Map<String, String> = emptyMap(),
        val restart: RestartPolicy = RestartPolicy.ON_FAILURE,
        val maxRestarts: Int = 10,
        /** Paket sürümü; komut metni aynı kalsa da sürüm değişince süreç yeniden başlatılır. */
        val version: String = "",
        /** Sunucunun deploy öncesi beklemesi gereken hazır olma koşulu (bkz. ReadinessProbe). */
        val readiness: Readiness? = null,
        /** Temiz kapanış komutu (örn. `pg_ctl stop -m fast`); boşsa doğrudan SIGTERM. */
        val stopCommand: List<String> = emptyList(),
    )

    enum class Phase { STARTING, RUNNING, BACKOFF, EXITED, FAILED, STOPPED }

    data class Status(val id: String, val phase: Phase, val pid: Long? = null, val restarts: Int = 0, val lastExit: Int? = null)

    private class Managed(val spec: Spec) {
        @Volatile var process: Process? = null
        @Volatile var stopping = false
        var job: Job? = null
        val logs = ArrayDeque<String>()
    }

    private val managed = ConcurrentHashMap<String, Managed>()
    private val _statuses = MutableStateFlow<Map<String, Status>>(emptyMap())
    val statuses: StateFlow<Map<String, Status>> = _statuses.asStateFlow()

    /**
     * Süreci başlatır ve true döner. Aynı tanımla zaten yaşıyorsa (çalışıyor veya yeniden başlatma
     * döngüsünde) dokunmaz ve false döner; tanım değiştiyse ya da süreç kalıcı olarak durduysa
     * (EXITED/FAILED) eskisini durdurup yeniden başlatır.
     */
    fun start(spec: Spec): Boolean {
        val current = managed[spec.id]
        if (current != null && current.spec == spec && current.job?.isActive == true) return false
        stop(spec.id)
        val m = Managed(spec)
        managed[spec.id] = m
        m.job = scope.launch(Dispatchers.IO) { runLoop(m) }
        return true
    }

    /** Yönetilen süreç kimlikleri (kalıcı olarak durmuş ama durdurulmamış olanlar dahil). */
    fun ids(): Set<String> = managed.keys.toSet()

    /** Süreç çalışıyor veya yeniden başlatılmayı bekliyor mu? */
    fun isAlive(id: String): Boolean = managed[id]?.job?.isActive == true

    private suspend fun runLoop(m: Managed) {
        val spec = m.spec
        var restarts = 0
        var backoffMs = 1_000L
        while (scope.isActive && !m.stopping) {
            setStatus(Status(spec.id, Phase.STARTING, restarts = restarts))
            val exit: Int = try {
                val pb = ProcessBuilder(spec.command)
                    .directory(spec.workDir.apply { mkdirs() })
                    .redirectErrorStream(true)
                pb.environment().putAll(spec.env)
                val p = pb.start()
                m.process = p
                setStatus(Status(spec.id, Phase.RUNNING, pid = pidOf(p), restarts = restarts))
                log("[${spec.id}] başladı pid=${pidOf(p)}")
                val started = System.currentTimeMillis()

                runInterruptible {
                    p.inputStream.bufferedReader().useLines { lines -> lines.forEach { appendLog(m, it) } }
                    p.waitFor()
                }.also {
                    // 60 sn'den uzun yaşadıysa sağlıklı sayılır; geri çekilme süresi sıfırlanır.
                    if (System.currentTimeMillis() - started > 60_000) backoffMs = 1_000L
                }
            } catch (e: Exception) {
                appendLog(m, tr("başlatılamadı: {0}", e.message))
                -1
            } finally {
                m.process = null
            }

            log("[${spec.id}] çıktı, kod=$exit")
            if (m.stopping) break

            val shouldRestart = when (spec.restart) {
                RestartPolicy.NEVER -> false
                RestartPolicy.ON_FAILURE -> exit != 0
                RestartPolicy.ALWAYS -> true
            }
            if (!shouldRestart) {
                setStatus(Status(spec.id, Phase.EXITED, restarts = restarts, lastExit = exit)); return
            }
            if (restarts >= spec.maxRestarts) {
                setStatus(Status(spec.id, Phase.FAILED, restarts = restarts, lastExit = exit))
                log("[${spec.id}] yeniden başlatma sınırı aşıldı"); return
            }
            restarts++
            setStatus(Status(spec.id, Phase.BACKOFF, restarts = restarts, lastExit = exit))
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(60_000)
        }
        setStatus(Status(spec.id, Phase.STOPPED, restarts = restarts))
    }

    /**
     * Varsa önce [Spec.stopCommand] çalışır ve sürecin kapanması 30 sn beklenir (Postgres'e SIGTERM
     * "akıllı kapanış" demektir, bağlantılar bitene kadar sürer). Sonra SIGTERM, 5 sn içinde kapanmazsa SIGKILL.
     */
    fun stop(id: String) {
        val m = managed.remove(id) ?: return
        m.stopping = true
        m.process?.let { p ->
            if (m.spec.stopCommand.isNotEmpty() && runStopCommand(m.spec)) p.waitFor(30, TimeUnit.SECONDS)
            if (p.isAlive) {
                p.destroy()
                if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly()
            }
        }
        m.job?.cancel()
        setStatus(Status(id, Phase.STOPPED))
    }

    private fun runStopCommand(spec: Spec): Boolean = try {
        val p = ProcessBuilder(spec.stopCommand).directory(spec.workDir).redirectErrorStream(true)
            .apply { environment().putAll(spec.env) }.start()
        // Çıktı kısa (pg_ctl birkaç satır); önce bitmesi beklenir ki takılan komut stop()'u kilitlemesin.
        val finished = p.waitFor(60, TimeUnit.SECONDS)
        if (!finished) p.destroyForcibly()
        val ok = finished && p.exitValue() == 0
        if (!ok) log("[${spec.id}] durdurma komutu başarısız: ${runCatching { p.inputStream.bufferedReader().readText().trim() }.getOrDefault("")}")
        ok
    } catch (e: Exception) {
        log("[${spec.id}] durdurma komutu çalıştırılamadı: ${e.message}")
        false
    }

    fun stopAll() = managed.keys.toList().forEach(::stop)

    fun logs(id: String): List<String> = managed[id]?.let { m -> synchronized(m.logs) { m.logs.toList() } } ?: emptyList()

    private fun appendLog(m: Managed, line: String) = synchronized(m.logs) {
        m.logs.addLast(line)
        while (m.logs.size > 500) m.logs.removeFirst()
    }

    private fun setStatus(s: Status) = _statuses.update { it + (s.id to s) }
}

/** Process.pid() Java 9 API'sidir; eski Android sürümlerinde yoksa gizli "pid" alanına bakılır. */
internal fun pidOf(p: Process): Long? = runCatching { p.pid() }.getOrElse {
    runCatching { p.javaClass.getDeclaredField("pid").apply { isAccessible = true }.getInt(p).toLong() }.getOrNull()
}
