package dev.droidserver.core.realtime

import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Bir kayıt değişikliği. [record] silmede silinmeden önceki halidir (kurallar onunla denetlenir). */
data class RecordEvent(val id: Long, val collection: String, val action: Action, val record: JsonObject) {
    enum class Action(val wire: String) { CREATE("create"), UPDATE("update"), DELETE("delete") }
}

/**
 * Canlı abonelik dağıtıcısı. Olaylar sıra numarası alır ve son [bufferSize] olay saklanır; kopan istemci
 * `Last-Event-ID` ile kaldığı yerden devam eder ([since]). Her abone kendi sınırlı kuyruğunu okur; okuyamayacak kadar
 * yavaş kalan abonenin kuyruğu dolunca abonelik kapanır (istemci yeniden bağlanıp kaldığı yerden devam eder),
 * böylece yavaş bir istemci yazma isteklerini bekletmez.
 */
class RealtimeHub(
    private val maxSubscribers: Int = 100,
    private val bufferSize: Int = 1_000,
    private val queueSize: Int = 256,
) {
    inner class Subscription internal constructor(val collections: Set<String>) : AutoCloseable {
        internal val channel = Channel<RecordEvent>(queueSize)

        suspend fun receive(): RecordEvent = channel.receive()

        /** Kuyruk taştığı için kapatıldı mı (istemciye yeniden bağlanmasını söylemek için). */
        @Volatile var overflowed = false
            internal set

        override fun close() {
            channel.close()
            subscribers.remove(this)
        }
    }

    /**
     * Bu dağıtıcının (sunucu örneğinin) kimliği; olay kimlikleri `<epoch>-<sıra>` biçimindedir. Yeniden deploy'dan
     * sonra gelen eski kimlik tanınmaz ve istemciye yeniden yüklemesi söylenir.
     */
    val epoch: String = java.lang.Long.toHexString(java.security.SecureRandom().nextLong() and 0xFFFFFFFFFFFFL)

    fun eventId(e: RecordEvent) = "$epoch-${e.id}"

    /** `Last-Event-ID` → bu örnekteki sıra numarası; başka örneğe aitse veya bozuksa null. */
    fun parseEventId(text: String?): Long? {
        val (e, n) = text?.split('-', limit = 2)?.takeIf { it.size == 2 } ?: return null
        return if (e == epoch) n.toLongOrNull() else null
    }

    private val subscribers = CopyOnWriteArrayList<Subscription>()
    private val sequence = AtomicInteger()
    private val recent = ArrayDeque<RecordEvent>()

    val subscriberCount: Int get() = subscribers.size

    /** Yeni abonelik; sınır doluysa null. */
    fun subscribe(collections: Set<String>): Subscription? {
        if (subscribers.size >= maxSubscribers) return null
        return Subscription(collections).also { subscribers += it }
    }

    fun publish(collection: String, action: RecordEvent.Action, record: JsonObject) {
        if ((record["id"] as? JsonPrimitive)?.content?.toLongOrNull() == null) return
        // Kuyruklara da kilit içinde yazılır: her abone olayları sıra numarası sırasıyla alır.
        synchronized(recent) {
            val event = RecordEvent(sequence.incrementAndGet().toLong(), collection, action, record)
            recent.addLast(event)
            if (recent.size > bufferSize) recent.removeFirst()
            for (s in subscribers) {
                if (collection !in s.collections) continue
                if (s.channel.trySend(event).isFailure && !s.channel.isClosedForSend) {
                    s.overflowed = true
                    s.close()
                }
            }
        }
    }

    /**
     * [lastId]'den sonraki olaylar (yeniden bağlanan istemci için); [lastId] tampondan eskiyse veya bu sunucu
     * örneğine ait değilse null: istemci kayıtları yeniden yüklemeli.
     */
    fun since(lastId: Long, collections: Set<String>): List<RecordEvent>? = synchronized(recent) {
        val newest = sequence.get().toLong()
        if (lastId > newest) return null
        if (lastId == newest) return emptyList()
        val oldest = recent.firstOrNull()?.id ?: return null
        if (lastId < oldest - 1) return null
        recent.filter { it.id > lastId && it.collection in collections }
    }

    /** Sunucu kapanırken tüm abonelikler biter. */
    fun closeAll() = subscribers.forEach { it.close() }
}
