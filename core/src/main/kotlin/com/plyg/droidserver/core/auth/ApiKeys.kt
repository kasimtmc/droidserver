package com.plyg.droidserver.core.auth

import com.plyg.droidserver.core.api.ApiException
import com.plyg.droidserver.core.config.ApiKeyConfig
import com.plyg.droidserver.core.i18n.tr
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** Yeni üretilen anahtar: [key] kullanıcıya bir kez gösterilir, konfigürasyona [config] (özet) yazılır. */
data class NewApiKey(val key: String, val config: ApiKeyConfig)

/**
 * Adlandırılmış API anahtarları ([ApiKeyConfig]): üretme, istekteki token'ı çözme ve son kullanım zamanı. Anahtarlar
 * `dsk_` + 256 bit rastgele (base64url); yalnızca SHA-256 özeti saklanır.
 */
class ApiKeys(
    keys: List<ApiKeyConfig>,
    private val usage: ApiKeyUsage = ApiKeyUsage(),
    private val clock: () -> Instant = Instant::now,
    /** Anahtar başına dakika sınırı: (anahtar adı, sınır) → aşıldıysa kaç sn sonra; bkz. ClientGuard.hit. */
    private val rate: (String, Int?) -> Long? = { _, _ -> null },
) {
    private val byHash = keys.associateBy { it.hash }

    /**
     * Token bir anahtarsa çağıran; değilse null. İptal edilmiş veya süresi dolmuş anahtar 401 (sessizce anonim sayılmaz:
     * istemci nedenini görsün).
     */
    fun resolve(token: String): Caller? {
        if (!token.startsWith(PREFIX)) return null
        val k = byHash[hash(token)] ?: return null
        if (!k.enabled) throw ApiException(401, tr("'{0}' API anahtarı iptal edilmiş", k.name))
        val expires = k.expiresAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
        if (expires != null && !clock().isBefore(expires)) throw ApiException(401, tr("'{0}' API anahtarının süresi dolmuş", k.name))
        usage.touch(k.name, clock())
        rate("key:" + k.name, k.requestsPerMinute)?.let { throw ApiException(429, tr("'{0}' anahtarının dakika sınırı ({1}) aşıldı; {2} sn sonra tekrar deneyin", k.name, k.requestsPerMinute, it)) }
        return if (k.isFull) Caller.Admin else Caller.Key(k.name, k.readOnly, k.collections.toSet())
    }

    companion object {
        const val PREFIX = "dsk_"
        private val random = SecureRandom()

        fun generate(
            name: String,
            readOnly: Boolean = false,
            collections: List<String> = emptyList(),
            expiresAt: Instant? = null,
            now: Instant = Instant.now(),
        ): NewApiKey {
            val bytes = ByteArray(32).also(random::nextBytes)
            val key = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            return NewApiKey(
                key,
                ApiKeyConfig(
                    name = name,
                    hash = hash(key),
                    prefix = key.take(PREFIX.length + 8),
                    readOnly = readOnly,
                    collections = collections,
                    expiresAt = expiresAt?.toString(),
                    createdAt = now.toString(),
                ),
            )
        }

        fun hash(key: String): String =
            MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }

        /** Kapsamın kısa açıklaması (uygulamada ve özette). */
        fun describe(k: ApiKeyConfig): String = when {
            k.isFull -> tr("Tam yetki")
            else -> (if (k.readOnly) tr("Salt okunur") else tr("Okuma ve yazma")) + ": " +
                (if (k.collections.isEmpty()) tr("tüm koleksiyonlar") else k.collections.joinToString())
        }
    }
}

/**
 * Anahtarların son kullanım zamanı (bellekte; sunucu yeniden deploy edilse de sürer). Uygulama kapanırken
 * [snapshot] dosyaya yazılıp açılışta [restore] edilir.
 */
class ApiKeyUsage {
    private val lastUsed = ConcurrentHashMap<String, Instant>()

    fun touch(name: String, at: Instant) {
        lastUsed[name] = at
    }

    fun lastUsed(name: String): Instant? = lastUsed[name]

    fun snapshot(): Map<String, Instant> = HashMap(lastUsed)

    fun restore(values: Map<String, Instant>) {
        values.forEach { (k, v) -> lastUsed.merge(k, v) { a, b -> maxOf(a, b) } }
    }
}
