package dev.droidserver.core.metrics

import dev.droidserver.core.i18n.tr
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

/** Cihaz durumu (app: pil yayını, depolama, bellek); bilinmeyen değerler null. */
data class DeviceSnapshot(
    val batteryPercent: Int? = null,
    val batteryTempC: Double? = null,
    val charging: Boolean? = null,
    val freeStorageBytes: Long? = null,
    val memoryUsedBytes: Long? = null,
    val memoryTotalBytes: Long? = null,
)

fun interface DeviceStats {
    fun snapshot(): DeviceSnapshot
}

/**
 * İstek metrikleri (bellekte; uygulama yeniden başlayınca sıfırlanır): toplam, durum sınıfı, süren istek, uç başına
 * sayı ve gecikme (son 512 isteğin p50/p95'i) ve son 60 dakikanın dakikalık serisi (istek, 5xx, ortalama süre, pil).
 * Uç adı yoldaki kimlik ve dosya adları soyutlanarak çıkarılır (`/api/collections/todos/records/:id`); en çok
 * [MAX_ROUTES] farklı uç izlenir.
 */
class Metrics(private val clock: () -> Long = System::currentTimeMillis) {
    private val startedAt = clock()
    private val total = AtomicLong()
    private val byStatus = AtomicLongArray(6) // 1xx..5xx; 0 kullanılmaz
    private val active = AtomicInteger()
    private val routes = ConcurrentHashMap<String, RouteStats>()

    private class RouteStats {
        val count = AtomicLong()
        val errors = AtomicLong()
        private val window = LongArray(WINDOW)
        private var next = 0
        private var filled = 0

        @Synchronized
        fun add(ms: Long) {
            window[next] = ms
            next = (next + 1) % WINDOW
            if (filled < WINDOW) filled++
        }

        @Synchronized
        fun quantiles(): Pair<Long, Long>? {
            if (filled == 0) return null
            val sorted = window.copyOf(filled).sorted()
            return percentile(sorted, 0.5) to percentile(sorted, 0.95)
        }
    }

    /** Dakikalık özet. */
    data class Minute(
        val startMs: Long,
        val requests: Long,
        val errors: Long,
        val avgMs: Double?,
        val device: DeviceSnapshot?,
    )

    private class Bucket(val startMs: Long) {
        val requests = AtomicLong()
        val errors = AtomicLong()
        val totalMs = AtomicLong()
        @Volatile var device: DeviceSnapshot? = null
    }

    private val minutes = ArrayDeque<Bucket>()
    @Volatile private var lastDevice: DeviceSnapshot? = null

    fun started() {
        active.incrementAndGet()
    }

    /** İstek bitti: [path] gerçek yol (soyutlanır), [status] HTTP durumu, [ms] süre. */
    /** Sayılmayan istek bitti (metrik okuması); yalnızca "süren istek" sayacı düşer. */
    fun skipped() {
        active.decrementAndGet()
    }

    fun finished(method: String, path: String, status: Int, ms: Long) {
        active.decrementAndGet()
        total.incrementAndGet()
        byStatus.incrementAndGet((status / 100).coerceIn(1, 5))
        val key = "$method ${routeOf(path)}"
        val stats = routes[key] ?: if (routes.size < MAX_ROUTES) routes.computeIfAbsent(key) { RouteStats() } else routes.computeIfAbsent(tr("{0} (diğer)", method)) { RouteStats() }
        stats.count.incrementAndGet()
        if (status >= 500) stats.errors.incrementAndGet()
        stats.add(ms)
        val b = bucket(clock())
        b.requests.incrementAndGet()
        if (status >= 500) b.errors.incrementAndGet()
        b.totalMs.addAndGet(ms)
    }

    /** Dakikalık cihaz örneği (sunucu her dakika çağırır). */
    fun sampleDevice(snapshot: DeviceSnapshot) {
        lastDevice = snapshot
        bucket(clock()).device = snapshot
    }

    @Synchronized
    private fun bucket(now: Long): Bucket {
        val start = now - now % 60_000
        val last = minutes.lastOrNull()
        if (last != null && last.startMs == start) return last
        return Bucket(start).also {
            minutes.addLast(it)
            while (minutes.size > 60 || minutes.first().startMs < start - 59 * 60_000) minutes.removeFirst()
        }
    }

    @Synchronized
    fun series(): List<Minute> = minutes.map { b ->
        val n = b.requests.get()
        Minute(b.startMs, n, b.errors.get(), if (n == 0L) null else b.totalMs.get().toDouble() / n, b.device)
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("uptimeSec", JsonPrimitive((clock() - startedAt) / 1000))
        put("requests", buildJsonObject {
            put("total", JsonPrimitive(total.get()))
            put("active", JsonPrimitive(active.get()))
            put("byStatus", JsonObject((1..5).associate { "${it}xx" to JsonPrimitive(byStatus.get(it)) }))
        })
        put("routes", JsonArray(routes.entries.sortedByDescending { it.value.count.get() }.map { (key, s) ->
            val q = s.quantiles()
            buildJsonObject {
                put("route", JsonPrimitive(key))
                put("count", JsonPrimitive(s.count.get()))
                put("errors", JsonPrimitive(s.errors.get()))
                put("p50Ms", q?.first?.let(::JsonPrimitive) ?: JsonNull)
                put("p95Ms", q?.second?.let(::JsonPrimitive) ?: JsonNull)
            }
        }))
        put("minutes", JsonArray(series().map { m ->
            buildJsonObject {
                put("start", JsonPrimitive(java.time.Instant.ofEpochMilli(m.startMs).toString()))
                put("requests", JsonPrimitive(m.requests))
                put("errors", JsonPrimitive(m.errors))
                put("avgMs", m.avgMs?.let(::JsonPrimitive) ?: JsonNull)
                m.device?.batteryTempC?.let { put("batteryTempC", JsonPrimitive(it)) }
                m.device?.batteryPercent?.let { put("batteryPercent", JsonPrimitive(it)) }
            }
        }))
        put("device", lastDevice?.let(::deviceJson) ?: JsonNull)
    }

    /** Prometheus metin biçimi. */
    fun toPrometheus(): String = buildString {
        fun line(name: String, value: Any?, labels: String = "") { if (value != null) append("$name$labels ${fmt(value)}\n") }
        append("# TYPE droidserver_requests_total counter\n")
        for (c in 1..5) line("droidserver_requests_total", byStatus.get(c), "{status=\"${c}xx\"}")
        append("# TYPE droidserver_requests_active gauge\n")
        line("droidserver_requests_active", active.get())
        append("# TYPE droidserver_uptime_seconds gauge\n")
        line("droidserver_uptime_seconds", (clock() - startedAt) / 1000)
        append("# TYPE droidserver_route_requests_total counter\n")
        for ((key, s) in routes) line("droidserver_route_requests_total", s.count.get(), "{route=\"${esc(key)}\"}")
        append("# TYPE droidserver_route_duration_ms summary\n")
        for ((key, s) in routes) {
            val q = s.quantiles() ?: continue
            line("droidserver_route_duration_ms", q.first, "{route=\"${esc(key)}\",quantile=\"0.5\"}")
            line("droidserver_route_duration_ms", q.second, "{route=\"${esc(key)}\",quantile=\"0.95\"}")
        }
        lastDevice?.let { d ->
            line("droidserver_battery_percent", d.batteryPercent)
            line("droidserver_battery_temperature_celsius", d.batteryTempC)
            line("droidserver_battery_charging", d.charging?.let { if (it) 1 else 0 })
            line("droidserver_storage_free_bytes", d.freeStorageBytes)
            line("droidserver_memory_used_bytes", d.memoryUsedBytes)
            line("droidserver_memory_total_bytes", d.memoryTotalBytes)
        }
    }

    companion object {
        const val MAX_ROUTES = 200
        private const val WINDOW = 512

        private fun percentile(sorted: List<Long>, p: Double): Long = sorted[((sorted.size - 1) * p).toInt()]

        private fun fmt(v: Any): String = if (v is Double) String.format(Locale.ROOT, "%.2f", v) else v.toString()

        private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

        fun deviceJson(d: DeviceSnapshot) = buildJsonObject {
            d.batteryPercent?.let { put("batteryPercent", JsonPrimitive(it)) }
            d.batteryTempC?.let { put("batteryTempC", JsonPrimitive(it)) }
            d.charging?.let { put("charging", JsonPrimitive(it)) }
            d.freeStorageBytes?.let { put("freeStorageBytes", JsonPrimitive(it)) }
            d.memoryUsedBytes?.let { put("memoryUsedBytes", JsonPrimitive(it)) }
            d.memoryTotalBytes?.let { put("memoryTotalBytes", JsonPrimitive(it)) }
        }

        /** Yoldaki kimlikler `:id`, `/files/…` altındaki dosya adı `:name` olur (sınırsız sayıda farklı uç olmasın). */
        fun routeOf(path: String): String {
            val parts = path.substringBefore('?').split('/')
            val filesAt = parts.indexOf("files")
            return parts.mapIndexed { i, p ->
                when {
                    p.isNotEmpty() && p.all(Char::isDigit) -> ":id"
                    filesAt >= 0 && i == filesAt + 3 && p.isNotEmpty() -> ":name"
                    p.length > 40 -> ":uzun"
                    else -> p
                }
            }.joinToString("/")
        }
    }
}
