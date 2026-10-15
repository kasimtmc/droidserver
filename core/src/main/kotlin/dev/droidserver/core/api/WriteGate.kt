package dev.droidserver.core.api

import dev.droidserver.core.i18n.tr
import java.util.concurrent.atomic.AtomicInteger

/** Yazmalar kapalı (veri taşınıyor veya sunucu salt okunur kopya); API 503 döner, [reason] istemciye gösterilir. */
class WritesPausedException(val reason: String? = null) : RuntimeException(reason ?: tr("Yazma geçici olarak kapalı"))

/**
 * Veri taşıma sırasında sunucunun salt okunur çalışması için: [pause] yeni yazmaları reddeder ve süren
 * yazmaların bitmesini bekler, böylece taşıma başladıktan sonra kaynağa yazılıp kaybolan kayıt olmaz.
 */
class WriteGate {
    @Volatile
    private var paused = false
    private val inFlight = AtomicInteger()

    val isPaused: Boolean get() = paused

    /** Kapalıyken istemciye verilen açıklama; null: veri taşıma. */
    @Volatile
    var reason: String? = null
        private set

    fun <T> write(block: () -> T): T {
        inFlight.incrementAndGet()
        try {
            if (paused) throw WritesPausedException(reason)
            return block()
        } finally {
            inFlight.decrementAndGet()
        }
    }

    /** Yazmaları kapatır; süren yazmalar [timeoutMs] içinde bitmezse kapıyı geri açar ve hata verir. */
    fun pause(timeoutMs: Long = 30_000, reason: String? = null) {
        this.reason = reason
        paused = true
        val deadline = System.currentTimeMillis() + timeoutMs
        while (inFlight.get() > 0) {
            if (System.currentTimeMillis() > deadline) {
                paused = false
                throw IllegalStateException(tr("Süren yazmalar {0} sn içinde bitmedi", timeoutMs / 1000))
            }
            Thread.sleep(10)
        }
    }

    fun resume() {
        paused = false
        reason = null
    }
}
