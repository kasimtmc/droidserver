package dev.droidserver.core.script

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Zamanlanmış görevleri her dakika başında denetler; zamanı gelen görev ayrı iş parçacığında çalışır (uzun görev
 * diğerlerini geciktirmez). Bir görev hâlâ sürüyorsa aynı görev yeniden başlatılmaz.
 */
class CronRunner(
    private val runtime: ScriptRuntime,
    private val log: (String) -> Unit = {},
    /** false iken görevler atlanır (yazmalar kapalı: veri taşınıyor veya sunucu salt okunur kopya). */
    private val enabled: () -> Boolean = { true },
) {
    private var ticker: ScheduledExecutorService? = null
    private var workers: ExecutorService? = null
    private val running = Collections.synchronizedSet(mutableSetOf<CronJob>())

    @Synchronized
    fun start() {
        if (runtime.crons.isEmpty() || ticker != null) return
        workers = Executors.newCachedThreadPool { r -> Thread(r, "ds-cron").apply { isDaemon = true } }
        ticker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ds-cron-tick").apply { isDaemon = true } }.also { t ->
            val now = LocalDateTime.now()
            val delay = ChronoUnit.MILLIS.between(now, now.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1))
            t.scheduleAtFixedRate({ tick(LocalDateTime.now()) }, delay, 60_000, TimeUnit.MILLISECONDS)
        }
    }

    /** [now] dakikasında zamanı gelen görevleri başlatır; başlatılanları döndürür. */
    fun tick(now: LocalDateTime): List<CronJob> {
        if (!enabled()) return emptyList()
        val minute = now.truncatedTo(ChronoUnit.MINUTES)
        val due = runtime.crons.filter { it.schedule.matches(minute) && running.add(it) }
        for (job in due) {
            val run = Runnable {
                try {
                    runtime.runCron(job)
                } finally {
                    running.remove(job)
                }
            }
            workers?.execute(run) ?: run.run()
        }
        return due
    }

    @Synchronized
    fun stop() {
        ticker?.shutdownNow()
        ticker = null
        workers?.shutdownNow()
        workers = null
    }
}
