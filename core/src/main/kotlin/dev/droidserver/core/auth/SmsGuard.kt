package dev.droidserver.core.auth

import dev.droidserver.core.api.ApiException
import dev.droidserver.core.i18n.tr
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * SMS girişinde kötüye kullanıma karşı sınırlar (deploy'lar arasında süren; ServerEngine tutar): aynı numaraya
 * [perNumberSeconds] saniyede bir ve günde en çok [perNumberDaily] kod, aynı istemciden saatte en çok [perClientHourly]
 * istek. Hesabın var olup olmadığından bağımsız uygulanır (numaranın kayıtlı olduğu sızmaz). Sunucunun toplam günlük
 * SMS sınırı ayrıca telefon özelliklerinde ([dev.droidserver.core.config.PhoneConfig.smsDailyLimit]) uygulanır.
 */
class SmsGuard(
    private val perNumberSeconds: Long = 60,
    private val perNumberDaily: Int = 5,
    private val perClientHourly: Int = 10,
    private val clock: () -> Instant = Instant::now,
) {
    private val numbers = ConcurrentHashMap<String, MutableList<Long>>()
    private val clients = ConcurrentHashMap<String, MutableList<Long>>()

    /** İzin verirse sayar; vermezse 429. */
    fun check(number: String, client: String) {
        val now = clock().epochSecond
        synchronized(this) {
            val n = numbers.getOrPut(number) { mutableListOf() }.apply { removeAll { now - it >= 86_400 } }
            val c = clients.getOrPut(client) { mutableListOf() }.apply { removeAll { now - it >= 3_600 } }
            n.lastOrNull()?.let { last ->
                val wait = perNumberSeconds - (now - last)
                if (wait > 0) throw ApiException(429, tr("Bu numaraya az önce kod gönderildi; {0} saniye sonra tekrar deneyin", wait))
            }
            if (n.size >= perNumberDaily) throw ApiException(429, tr("Bu numara için bugünkü kod sınırı doldu"))
            if (c.size >= perClientHourly) throw ApiException(429, tr("Çok fazla kod isteği; bir saat sonra tekrar deneyin"))
            n += now
            c += now
        }
    }

    companion object {
        private val E164 = Regex("^\\+[1-9][0-9]{7,14}$")

        /** Boşluk, tire ve parantezleri atar; uluslararası biçim (+905551234567) değilse null. */
        fun normalize(raw: String?): String? = raw?.filterNot { it == ' ' || it == '-' || it == '(' || it == ')' }?.takeIf { E164.matches(it) }
    }
}
