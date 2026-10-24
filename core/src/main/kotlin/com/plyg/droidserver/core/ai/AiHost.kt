package com.plyg.droidserver.core.ai

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Günlük YZ kullanımı (UTC gününe göre): kimlik başına istek sayısı ve sunucunun toplam token'ı. Deploy'lar arasında
 * sürer (ServerEngine tutar); uygulama yeniden başlayınca sıfırlanır.
 */
class AiUsage(private val clock: () -> Instant = Instant::now) {
    private class Day(val day: Long) {
        val requests = ConcurrentHashMap<String, AtomicInteger>()
        val inputTokens = AtomicLong()
        val outputTokens = AtomicLong()
        val total = AtomicInteger()
    }

    @Volatile
    private var current = Day(-1)

    private fun today(): Day {
        val d = Math.floorDiv(clock().epochSecond, 86_400L)
        val c = current
        if (c.day == d) return c
        return synchronized(this) { current.takeIf { it.day == d } ?: Day(d).also { current = it } }
    }

    /** [who]'nun bugünkü istek sayısını bir artırıp döndürür. */
    fun hit(who: String): Int = today().requests.computeIfAbsent(who) { AtomicInteger() }.incrementAndGet()

    fun count(who: String): Int = today().requests[who]?.get() ?: 0

    /** Yapılan bir Claude çağrısı ve harcadığı token'lar. */
    fun record(input: Long, output: Long) {
        val d = today()
        d.total.incrementAndGet()
        d.inputTokens.addAndGet(input)
        d.outputTokens.addAndGet(output)
    }

    /** Bugünün özeti: Claude çağrısı, girdi token'ı, çıktı token'ı. */
    fun summary(): Triple<Int, Long, Long> = today().let { Triple(it.total.get(), it.inputTokens.get(), it.outputTokens.get()) }
}

/** YZ'nin uygulamaya açıldığı yer: günlük kullanım ve anlamsal dizin (dizin durumu, yeniden kurma). */
class AiHost {
    val usage = AiUsage()

    @Volatile
    var index: SemanticIndex? = null
}
