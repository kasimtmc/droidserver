package dev.droidserver.core.admin

import dev.droidserver.core.backup.BackupInfo
import dev.droidserver.core.replication.ReplicaStatus
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Web paneli oturumları. Panele giriş, telefonda gösterilen tek kullanımlık 6 haneli eşleştirme koduyla yapılır:
 * kod 5 dakika geçerlidir, bir kez kullanılır ve 5 hatalı denemede geçersizleşir (telefona fiziksel erişim gerekir).
 * Başarılı eşleştirme yönetici yetkili bir oturum token'ı verir (bellekte, yalnızca özeti; uygulama kapanınca biter).
 */
class AdminSessions(private val clock: () -> Long = System::currentTimeMillis) {
    private val random = SecureRandom()
    private val sessions = ConcurrentHashMap<String, Long>() // token özeti → bitiş
    private var code: String? = null
    private var codeExpires = 0L
    private var attempts = 0

    data class Pairing(val code: String, val expiresAtMs: Long)

    /** Yeni kod (öncekini geçersiz kılar). */
    @Synchronized
    fun newCode(): Pairing {
        val c = (0 until 6).joinToString("") { random.nextInt(10).toString() }
        code = c
        codeExpires = clock() + CODE_TTL_MS
        attempts = 0
        return Pairing(c, codeExpires)
    }

    /**
     * Kod (ve varsa [second] ikinci etken, örn. doğrulayıcı kodu) doğruysa oturum token'ı, değilse null. İkinci etkenin
     * hatalı denemeleri de kodun deneme hakkından düşer.
     */
    @Synchronized
    fun pair(candidate: String, sessionHours: Int, second: (() -> Boolean)? = null): String? {
        val current = code ?: return null
        if (clock() > codeExpires) {
            code = null
            return null
        }
        if (!MessageDigest.isEqual(current.toByteArray(), candidate.trim().toByteArray())) {
            if (++attempts >= MAX_ATTEMPTS) code = null
            return null
        }
        if (second != null && !second()) {
            if (++attempts >= MAX_ATTEMPTS) code = null
            return null
        }
        code = null
        return open(sessionHours)
    }

    /** Kimliği başka yoldan doğrulanmış (passkey) yönetici için oturum token'ı. */
    fun open(sessionHours: Int): String {
        val token = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        sessions[hash(token)] = clock() + sessionHours * 3_600_000L
        return token
    }

    fun valid(token: String): Boolean {
        val key = hash(token)
        val until = sessions[key] ?: return false
        if (clock() > until) {
            sessions.remove(key)
            return false
        }
        return true
    }

    fun revoke(token: String) {
        sessions.remove(hash(token))
    }

    fun revokeAll() = sessions.clear()

    val activeSessions: Int get() = sessions.values.count { it > clock() }

    private fun hash(token: String) = MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val CODE_TTL_MS = 5 * 60_000L
        const val MAX_ATTEMPTS = 5
    }
}

/**
 * Panelin sunucu dışındaki işleri (app: konfigürasyon dosyası, deploy, log arabelleği, yedekler). Bloklayıcıdır;
 * HTTP katmanı IO iş parçacığında çağırır.
 */
interface AdminHost {
    /** Diskteki konfigürasyon metni (sırlar referans olarak). */
    fun configText(): String?

    /** Metni doğrular ve şema planını özetler: `{ errors: [...], plan: "...", blockers: [...], warnings: [...] }`. */
    fun plan(text: String): JsonObject

    /** Metni kaydeder ve deploy'u başlatır (sonuç loglarda); doğrulama hatası [IllegalArgumentException]. */
    fun deploy(text: String)

    /** [after] sırasından sonraki log satırları ve sonraki sıra. */
    fun logs(after: Long): Pair<Long, List<String>>

    fun backups(): List<BackupInfo>

    fun createBackup(): BackupInfo

    /** Canlı kopyanın eşitleme durumu; bu sunucu kopya değilse null. */
    fun replica(): ReplicaStatus?

    /** Kopyayı yazılabilir ana sunucu yapar ve deploy'u başlatır (uygulamadaki "Devral" ile aynı yol). */
    fun takeover()
}
