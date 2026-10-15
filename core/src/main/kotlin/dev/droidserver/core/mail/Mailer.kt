package dev.droidserver.core.mail

import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.i18n.tr
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/** E-posta gönderen (SMTP; testlerde sahte). */
fun interface MailSender {
    fun send(to: String, subject: String, text: String, html: String?)
}

/**
 * Hesap e-postaları (doğrulama, parola sıfırlama) ve script'lerin `ctx.mail`'i. Kötüye kullanıma karşı sınırlar:
 * aynı adrese aynı türden en çok dakikada 1 ve saatte 5, sunucu genelinde saatte [hourlyLimit] e-posta. Sınırı aşan istek sessizce
 * atlanır (hesap uçları her durumda aynı yanıtı verir).
 */
class Mailer(
    private val config: ServerConfig,
    private val sender: MailSender,
    private val log: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val hourlyLimit: Int = 200,
) {
    private val perAddress = ConcurrentHashMap<String, MutableList<Long>>()
    private val global = ArrayDeque<Long>()

    private val name = config.email.fromName?.takeIf { it.isNotBlank() } ?: config.name
    private val base = config.email.publicUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }

    /** Sınırlar izin veriyorsa true ve gönderim sayılır. [kind]: e-posta türü (doğrulama, sıfırlama, script). */
    fun allow(address: String, kind: String = ""): Boolean {
        val now = clock()
        val key = kind + ":" + address.lowercase()
        synchronized(global) {
            while (global.isNotEmpty() && now - global.first() > HOUR) global.removeFirst()
            if (global.size >= hourlyLimit) return false
            val times = perAddress.getOrPut(key) { mutableListOf() }
            times.removeAll { now - it > HOUR }
            if (times.size >= 5 || times.any { now - it < MINUTE }) return false
            times += now
            global.addLast(now)
            if (perAddress.size > 10_000) perAddress.entries.removeIf { e -> e.value.all { now - it > HOUR } }
            return true
        }
    }

    fun verification(to: String, token: String) {
        val link = base?.let { "$it/_auth/verify?token=${enc(token)}" }
        val text = buildString {
            appendLine(tr("Merhaba,"))
            appendLine()
            appendLine(tr("{0} hesabınızın e-posta adresini doğrulamak için:", name))
            appendLine()
            if (link != null) appendLine(link) else appendLine(tr("Doğrulama kodu: {0}", token))
            appendLine()
            appendLine(tr("Bağlantı 24 saat geçerlidir. Bu hesabı siz açmadıysanız bu e-postayı yok sayın."))
        }
        send(to, tr("{0}: e-posta adresinizi doğrulayın", name), text)
    }

    fun reset(to: String, token: String) {
        val link = base?.let { "$it/_auth/reset?token=${enc(token)}" }
        val text = buildString {
            appendLine(tr("Merhaba,"))
            appendLine()
            appendLine(tr("{0} hesabınız için parola sıfırlama istendi. Yeni parola belirlemek için:", name))
            appendLine()
            if (link != null) appendLine(link) else appendLine(tr("Sıfırlama kodu: {0}", token))
            appendLine()
            appendLine(tr("Bağlantı 1 saat geçerlidir ve bir kez kullanılabilir. İsteği siz yapmadıysanız bu e-postayı yok sayın; parolanız değişmez."))
        }
        send(to, tr("{0}: parola sıfırlama", name), text)
    }

    /** E-postayla giriş kodu; [linkBase] verilmişse istemci uygulamanın giriş sayfasına bağlantı da. */
    fun loginCode(to: String, code: String, linkBase: String?) {
        val link = linkBase?.trim()?.takeIf { it.isNotEmpty() }?.let { b ->
            b + (if ('?' in b) "&" else "?") + "email=${enc(to)}&code=$code"
        }
        val text = buildString {
            appendLine(tr("Merhaba,"))
            appendLine()
            appendLine(tr("{0} giriş kodunuz: {1}", name, code))
            if (link != null) {
                appendLine()
                appendLine(tr("Veya bu bağlantıyla giriş yapın: {0}", link))
            }
            appendLine()
            appendLine(tr("Kod 10 dakika geçerlidir ve bir kez kullanılabilir. İsteği siz yapmadıysanız bu e-postayı yok sayın."))
        }
        send(to, tr("{0}: giriş kodu", name), text)
    }

    /** Script'lerden: sınırlara tabi; aşılırsa istisna. */
    fun custom(to: String, subject: String, text: String, html: String?) {
        if (!allow(to, "script")) throw IllegalStateException(tr("E-posta sınırı aşıldı (aynı adrese dakikada 1, saatte 5; sunucu genelinde saatte {0})", hourlyLimit))
        sender.send(to, subject, text, html)
    }

    private fun send(to: String, subject: String, text: String) {
        try {
            sender.send(to, subject, text, null)
            log("E-posta gönderildi: ${mask(to)} ($subject)")
        } catch (e: Exception) {
            log("E-posta gönderilemedi (${mask(to)}): ${e.message}")
        }
    }

    companion object {
        private const val MINUTE = 60_000L
        private const val HOUR = 3_600_000L

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        /** Loglarda adresin yalnızca ilk harfi ve alan adı: `a***@example.com`. */
        fun mask(address: String): String = address.substringBefore('@').take(1) + "***@" + address.substringAfter('@')

        /** Konfigürasyondan SMTP gönderici (parola çözülmüş olmalı). */
        fun smtp(config: ServerConfig): MailSender {
            val client = SmtpClient(config.email, config.email.password)
            return MailSender { to, subject, text, html -> client.send(to, subject, text, html) }
        }
    }
}
