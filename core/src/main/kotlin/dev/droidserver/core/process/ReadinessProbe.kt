package dev.droidserver.core.process

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Bileşenin hizmete hazır sayılma koşulu (bileşen manifestinde `"readiness"`).
 * Örn. Postgres için `{ "tcpPort": 5432, "timeoutSec": 30 }`: port bağlantı kabul edince hazırdır.
 */
@Serializable
data class Readiness(
    val tcpPort: Int,
    val timeoutSec: Int = 30,
    val host: String = "127.0.0.1",
)

/** TCP portu bağlantı kabul edene kadar bekler. Sunucudan önce ayakta olması gereken bileşenler için. */
class ReadinessProbe(private val pollMillis: Long = 250) {

    /**
     * Hazırsa true. Süre dolarsa veya [alive] false dönerse (süreç çıktı ve yeniden başlatılmayacak) false.
     */
    suspend fun await(readiness: Readiness, alive: () -> Boolean = { true }): Boolean {
        val deadline = System.nanoTime() + readiness.timeoutSec * 1_000_000_000L
        while (true) {
            if (canConnect(readiness)) return true
            if (!alive() || System.nanoTime() >= deadline) return false
            delay(pollMillis)
        }
    }

    private suspend fun canConnect(r: Readiness): Boolean = withContext(Dispatchers.IO) {
        runCatching { Socket().use { it.connect(InetSocketAddress(r.host, r.tcpPort), 500) } }.isSuccess
    }
}
