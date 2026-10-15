package dev.droidserver.app.data

import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import dev.droidserver.core.alerts.Alert
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Tasker, MacroDroid, Automate gibi otomasyon uygulamalarıyla köprü (telefon düzeyinde ayar, profilden bağımsız).
 *
 * - Gelen komutlar ([ACTION_START], [ACTION_STOP], [ACTION_DEPLOY]) `TaskerReceiver`'a açık (explicit) yayınla gelir ve
 *   [EXTRA_TOKEN]'de uygulamada üretilen gizli anahtar olmalıdır: Android yayını gönderen uygulamayı güvenilir biçimde
 *   bildirmediği için yetki anahtarla verilir. Kapalıyken veya anahtar yanlışsa komut yok sayılır.
 * - Olaylar (sunucu başladı/durdu/başlatılamadı, sağlık uyarısı) yalnızca [packages]'taki uygulamalara gönderilir.
 */
class AutomationBridge(private val context: Context, private val secrets: SecretStore) {
    private val prefs = context.getSharedPreferences("automation", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(v) = prefs.edit { putBoolean("enabled", v) }

    /** Olayların gönderileceği uygulamaların paket adları. */
    var packages: Set<String>
        get() = prefs.getStringSet("packages", emptySet()).orEmpty()
        set(v) = prefs.edit { putStringSet("packages", v) }

    /** Komut anahtarı; yoksa üretilir. */
    fun token(): String = secrets.get(TOKEN_SECRET) ?: regenerate()

    /** Yeni anahtar üretir; eskisiyle gelen komutlar artık kabul edilmez. */
    fun regenerate(): String {
        val t = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(18).also(SecureRandom()::nextBytes))
        secrets.put(TOKEN_SECRET, t)
        return t
    }

    /** Komut kabul edilir mi: köprü açık ve anahtar doğru (sabit zamanlı karşılaştırma). */
    fun accepts(token: String?): Boolean {
        if (!enabled || token.isNullOrEmpty()) return false
        val expected = secrets.get(TOKEN_SECRET) ?: return false
        return MessageDigest.isEqual(expected.toByteArray(), token.trim().toByteArray())
    }

    fun started(profile: String, port: Int, url: String?) =
        emit(EVENT_STARTED, mapOf("profile" to profile, "port" to port.toString(), "url" to url.orEmpty()))

    fun stopped(profile: String) = emit(EVENT_STOPPED, mapOf("profile" to profile))

    fun failed(profile: String, reason: String) = emit(EVENT_FAILED, mapOf("profile" to profile, "reason" to reason.take(500)))

    fun alert(profile: String, alert: Alert) = emit(
        EVENT_ALERT,
        mapOf(
            "profile" to profile, "kind" to alert.kind.name.lowercase(), "title" to alert.title, "message" to alert.message.take(1000),
            "resolved" to alert.resolved.toString(), "critical" to alert.critical.toString(),
        ),
    )

    private fun emit(action: String, extras: Map<String, String>) {
        if (!enabled) return
        for (pkg in packages) {
            runCatching {
                context.sendBroadcast(Intent(action).setPackage(pkg).apply { extras.forEach { (k, v) -> putExtra(k, v) } })
            }
        }
    }

    companion object {
        const val ACTION_START = "dev.droidserver.action.START"
        const val ACTION_STOP = "dev.droidserver.action.STOP"
        const val ACTION_DEPLOY = "dev.droidserver.action.DEPLOY"
        const val EXTRA_TOKEN = "token"

        const val EVENT_STARTED = "dev.droidserver.event.STARTED"
        const val EVENT_STOPPED = "dev.droidserver.event.STOPPED"
        const val EVENT_FAILED = "dev.droidserver.event.FAILED"
        const val EVENT_ALERT = "dev.droidserver.event.ALERT"

        private const val TOKEN_SECRET = "automation_token"

        /** Bilinen otomasyon uygulamaları (paket adı → ad); listede olmayan paket de eklenebilir. */
        val KNOWN_APPS = linkedMapOf(
            "net.dinglisch.android.taskerm" to "Tasker",
            "com.arlosoft.macrodroid" to "MacroDroid",
            "com.llamalab.automate" to "Automate",
        )
    }
}
