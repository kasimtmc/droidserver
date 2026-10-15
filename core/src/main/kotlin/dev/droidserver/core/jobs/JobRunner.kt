package dev.droidserver.core.jobs

import dev.droidserver.core.api.WriteGate
import dev.droidserver.core.config.JobsConfig
import dev.droidserver.core.i18n.tr
import dev.droidserver.core.script.ScriptException
import dev.droidserver.core.script.ScriptRuntime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Kuyruktaki işleri script'teki türlerine göre çalıştırır: aynı anda en çok `concurrency` iş (tür başına da
 * `jobAdd`'deki sınır). Saniyede bir (ve iş eklenince hemen) bakar; veri taşınırken (yazmalar kapalı) iş almaz.
 * İşler "en az bir kez" çalışır: uygulama iş sürerken kapanırsa iş açılışta yeniden kuyruğa alınır.
 */
class JobRunner(
    private val queue: JobQueue,
    private val runtime: ScriptRuntime,
    private val config: JobsConfig,
    private val writes: WriteGate = WriteGate(),
    private val log: (String) -> Unit = {},
    private val pollMs: Long = 1_000,
) {
    private val perType = ConcurrentHashMap<String, AtomicInteger>()
    private val active = AtomicInteger()
    private val lock = Object()
    private var woken = false
    @Volatile private var stopped = false
    private var loop: Thread? = null
    private var workers: ExecutorService? = null
    private var lastPurge = 0L

    /** Yarım kalanları kuyruğa geri alır ve döngüyü başlatır. */
    @Synchronized
    fun start() {
        if (loop != null) return
        runCatching { queue.recover() }.onSuccess { if (it > 0) log(tr("İş kuyruğu: yarım kalan {0} iş yeniden kuyruğa alındı", it)) }
        workers = Executors.newFixedThreadPool(config.concurrency) { r -> Thread(r, "ds-job").apply { isDaemon = true } }
        loop = Thread({
            while (!stopped) {
                runCatching { tick() }.onFailure { if (!stopped) log(tr("İş kuyruğu: {0}", it.message)) }
                synchronized(lock) {
                    if (!woken && !stopped) lock.wait(pollMs)
                    woken = false
                }
            }
        }, "ds-jobs").apply { isDaemon = true; start() }
    }

    /** İş eklendi: beklemeden bak. */
    fun wake() = synchronized(lock) { woken = true; lock.notifyAll() }

    /** Boş yer kadar iş alır ve başlatır (çalıştırıcı yoksa aynı iş parçacığında); başlatılanların sayısı. */
    fun tick(): Int {
        val now = System.currentTimeMillis()
        if (now - lastPurge > PURGE_EVERY_MS) {
            lastPurge = now
            runCatching { writes.write { queue.purge(config.keepDays) } }.onSuccess { if (it > 0) log(tr("İş kuyruğu: eski {0} iş silindi", it)) }
        }
        if (writes.isPaused) return 0
        val types = runtime.jobTypes
        var started = 0
        // Birer birer: her alımda tür sınırları yeniden hesaplanır.
        while (active.get() < config.concurrency) {
            val full = types.values.filter { (perType[it.name]?.get() ?: 0) >= it.concurrency }.map { it.name }.toSet()
            val job = writes.write { queue.claim(types.keys, 1, full) }.firstOrNull() ?: break
            val count = perType.computeIfAbsent(job.name) { AtomicInteger() }
            count.incrementAndGet()
            active.incrementAndGet()
            started++
            val run = Runnable {
                try {
                    execute(job)
                } finally {
                    count.decrementAndGet()
                    active.decrementAndGet()
                    wake()
                }
            }
            workers?.execute(run) ?: run.run()
        }
        return started
    }

    private fun execute(job: Job) {
        val type = runtime.jobTypes.getValue(job.name)
        val outcome = runCatching { runtime.runJob(type, job.id, job.data, job.attempts) }
        runCatching {
            writes.write {
                outcome.fold(
                    { queue.complete(job.id, it) },
                    { e ->
                        val permanent = e is ScriptException && e.status in 400..499
                        val status = queue.fail(job, e.message ?: e.javaClass.simpleName, retry = !permanent)
                        if (status == Job.FAILED) log(tr("İş {0} başarısız ({1}. deneme): {2}", "${job.name}#${job.id}", job.attempts, e.message))
                    },
                )
            }
        }.onFailure { log(tr("İş {0} sonucu yazılamadı: {1} (açılışta yeniden denenir)", "${job.name}#${job.id}", it.message)) }
    }

    /** Döngüyü durdurur; süren işler en çok [waitMs] beklenir (bitmeyenler sonraki açılışta yeniden kuyruğa alınır). */
    @Synchronized
    fun stop(waitMs: Long = 5_000) {
        stopped = true
        wake()
        loop?.join(1_000)
        loop = null
        workers?.let { w ->
            w.shutdown()
            if (!w.awaitTermination(waitMs, TimeUnit.MILLISECONDS)) w.shutdownNow()
        }
        workers = null
    }

    private companion object {
        const val PURGE_EVERY_MS = 3_600_000L
    }
}
