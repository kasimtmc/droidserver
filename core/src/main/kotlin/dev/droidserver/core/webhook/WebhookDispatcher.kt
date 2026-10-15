package dev.droidserver.core.webhook

import dev.droidserver.core.config.WebhookConfig
import dev.droidserver.core.i18n.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.net.HttpURLConnection
import java.net.URI
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Bir teslim denemesinin sonucu (uygulamada ve panelde gösterilir). */
data class Delivery(
    val id: String,
    val webhook: String,
    val event: String,
    val attempt: Int,
    /** HTTP durum kodu; bağlantı kurulamadıysa null. */
    val status: Int?,
    val error: String?,
    val durationMs: Long,
    val at: Instant,
    /** Son deneme mi (başarılı veya denemeler bitti). */
    val final: Boolean,
) {
    val ok: Boolean get() = status != null && status in 200..299

    fun toJson(): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(id))
        put("webhook", JsonPrimitive(webhook))
        put("event", JsonPrimitive(event))
        put("attempt", JsonPrimitive(attempt))
        put("status", JsonPrimitive(status))
        put("error", JsonPrimitive(error))
        put("durationMs", JsonPrimitive(durationMs))
        put("at", JsonPrimitive(at.toString()))
        put("ok", JsonPrimitive(ok))
        put("final", JsonPrimitive(final))
    }
}

/**
 * Webhook'lar: kayıt olaylarını seçilen adreslere imzalı POST olarak iletir. Gövde
 * `{"id","event","collection","record","timestamp"}`; başlıklar `X-DroidServer-Event` (`todos.create`),
 * `X-DroidServer-Delivery`, `X-DroidServer-Timestamp` (Unix saniye) ve sır tanımlıysa
 * `X-DroidServer-Signature: sha256=<HMAC-SHA256(sır, "<zaman>.<gövde>")>` (zaman damgası imzaya dahil: eski isteğin
 * yeniden gönderilmesi ayırt edilir). 2xx başarıdır; değilse [retryDelaysMs] aralıklarıyla yeniden denenir.
 * Yönlendirme izlenmez. Bekleyen teslimler bellektedir (uygulama kapanırsa kaybolur); sunucu yeniden deploy edilse de
 * sürer ve son [historySize] deneme saklanır.
 */
class WebhookDispatcher(
    private val log: (String) -> Unit = {},
    private val retryDelaysMs: List<Long> = listOf(10_000, 60_000, 300_000),
    private val timeoutMs: Int = 10_000,
    private val historySize: Int = 200,
    private val maxPending: Int = 1_000,
    private val clock: () -> Instant = Instant::now,
) : AutoCloseable {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(4))
    private val history = ArrayDeque<Delivery>()
    private val pending = AtomicInteger()

    @Volatile
    var hooks: List<WebhookConfig> = emptyList()
        private set

    /** Deploy'da: sırları çözülmüş webhook listesi. */
    fun configure(hooks: List<WebhookConfig>) {
        this.hooks = hooks
    }

    fun deliveries(): List<Delivery> = synchronized(history) { history.toList().asReversed() }

    /** Kayıt olayı; eşleşen webhook'lara arka planda gönderilir. */
    fun publish(collection: String, action: String, record: JsonObject) {
        for (h in hooks) {
            if (!h.enabled || action !in h.events) continue
            if (h.collections.isNotEmpty() && collection !in h.collections) continue
            if (pending.get() >= maxPending) {
                log("Webhook '${h.name}': bekleyen teslim sınırı (${maxPending}) doldu, olay atlandı")
                continue
            }
            enqueue(h, "$collection.$action", collection, record)
        }
    }

    /** Otomasyon eylemi: adı verilen webhook'a, olay ve koleksiyon süzgeçlerine bakmadan gönderir. */
    fun send(name: String, event: String, collection: String?, record: JsonObject) {
        val h = hooks.firstOrNull { it.name == name } ?: throw IllegalArgumentException(tr("'{0}' adlı webhook yok", name))
        if (!h.enabled) throw IllegalStateException(tr("'{0}' webhook'u kapalı", name))
        if (pending.get() >= maxPending) throw IllegalStateException(tr("Webhook '{0}': bekleyen teslim sınırı doldu", name))
        enqueue(h, event, collection, record)
    }

    /** "Deneme gönder": `ping` olayı, beklemeden ve yeniden denemeden. */
    suspend fun test(name: String): Delivery {
        val h = hooks.firstOrNull { it.name == name } ?: throw IllegalArgumentException(tr("'{0}' adlı webhook yok", name))
        val id = UUID.randomUUID().toString()
        return withContext(Dispatchers.IO) { attempt(h, id, "ping", body(id, "ping", null, null), 1, final = true) }
    }

    private fun enqueue(h: WebhookConfig, event: String, collection: String?, record: JsonObject) {
        val id = UUID.randomUUID().toString()
        val body = body(id, event, collection, record)
        pending.incrementAndGet()
        scope.launch {
            try {
                val attempts = retryDelaysMs.size + 1
                for (n in 1..attempts) {
                    val d = attempt(h, id, event, body, n, final = n == attempts)
                    if (d.ok) return@launch
                    if (n == attempts) {
                        log("Webhook '${h.name}' teslim edilemedi ($event): ${d.error ?: "HTTP ${d.status}"}")
                        return@launch
                    }
                    delay(retryDelaysMs[n - 1])
                }
            } finally {
                pending.decrementAndGet()
            }
        }
    }

    private fun body(id: String, event: String, collection: String?, record: JsonObject?) = buildJsonObject {
        put("id", JsonPrimitive(id))
        put("event", JsonPrimitive(event))
        collection?.let { put("collection", JsonPrimitive(it)) }
        record?.let { put("record", it) }
        put("timestamp", JsonPrimitive(clock().toString()))
    }.toString()

    private fun attempt(h: WebhookConfig, id: String, event: String, body: String, n: Int, final: Boolean): Delivery {
        val started = System.nanoTime()
        val (status, error) = try {
            post(h, id, event, body) to null
        } catch (e: Exception) {
            null to (e.message ?: e.javaClass.simpleName)
        }
        val ok = status != null && status in 200..299
        val d = Delivery(
            id, h.name, event, n, status, error ?: if (!ok) "HTTP $status" else null,
            (System.nanoTime() - started) / 1_000_000, clock(), final || ok,
        )
        synchronized(history) {
            history.addLast(d)
            while (history.size > historySize) history.removeFirst()
        }
        return d
    }

    private fun post(h: WebhookConfig, id: String, event: String, body: String): Int {
        val c = URI(h.url).toURL().openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.instanceFollowRedirects = false
            c.doOutput = true
            val ts = clock().epochSecond.toString()
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.setRequestProperty("User-Agent", "DroidServer-Webhook")
            c.setRequestProperty("X-DroidServer-Event", event)
            c.setRequestProperty("X-DroidServer-Delivery", id)
            c.setRequestProperty("X-DroidServer-Timestamp", ts)
            h.secret?.takeIf { it.isNotEmpty() }?.let { c.setRequestProperty("X-DroidServer-Signature", "sha256=" + sign(it, "$ts.$body")) }
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            (if (code < 400) c.inputStream else c.errorStream)?.use { s -> s.skip(64 * 1024) }
            return code
        } finally {
            c.disconnect()
        }
    }

    override fun close() = scope.cancel()

    companion object {
        /** HMAC-SHA256, küçük harf onaltılık. Alıcı aynı hesabı yapıp sabit zamanlı karşılaştırmalı. */
        fun sign(secret: String, data: String): String {
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
            return mac.doFinal(data.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
}
