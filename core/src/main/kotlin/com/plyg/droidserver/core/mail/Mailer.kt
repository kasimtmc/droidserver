package com.plyg.droidserver.core.mail

import com.plyg.droidserver.core.config.EmailTemplate
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.i18n.tr
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

    /** [lang]: alıcının dili (isteğin `Accept-Language`'ı); o dilde şablon varsa o kullanılır. */
    fun verification(to: String, token: String, lang: String? = null) {
        val link = base?.let { "$it/_auth/verify?token=${enc(token)}" }
        if (sendTemplate(EmailTemplate.VERIFY, lang, to, token, link)) return
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

    fun reset(to: String, token: String, lang: String? = null) {
        val link = base?.let { "$it/_auth/reset?token=${enc(token)}" }
        if (sendTemplate(EmailTemplate.RESET, lang, to, token, link)) return
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
    fun loginCode(to: String, code: String, linkBase: String?, lang: String? = null) {
        val link = linkBase?.trim()?.takeIf { it.isNotEmpty() }?.let { b ->
            b + (if ('?' in b) "&" else "?") + "email=${enc(to)}&code=$code"
        }
        if (sendTemplate(EmailTemplate.LOGIN, lang, to, code, link)) return
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

    /** Tür ve dil için şablon varsa onunla gönderir. */
    private fun sendTemplate(kind: String, lang: String?, to: String, code: String, link: String?): Boolean {
        val t = template(config.email.templates, kind, lang) ?: return false
        val (subject, text, html) = render(t, mapOf("app" to name, "email" to to, "code" to code, "link" to (link ?: code)))
        send(to, subject, text, html)
        return true
    }

    private fun send(to: String, subject: String, text: String, html: String? = null) {
        try {
            sender.send(to, subject, text, html)
            log("E-posta gönderildi: ${mask(to)} ($subject)")
        } catch (e: Exception) {
            log("E-posta gönderilemedi (${mask(to)}): ${e.message}")
        }
    }

    companion object {
        private const val MINUTE = 60_000L
        private const val HOUR = 3_600_000L

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        /** [kind] için [lang] dilindeki şablon, yoksa dilsiz olanı. */
        fun template(templates: List<EmailTemplate>, kind: String, lang: String?): EmailTemplate? =
            templates.firstOrNull { it.kind == kind && lang != null && it.lang == lang } ?: templates.firstOrNull { it.kind == kind && it.lang == null }

        /** Konu, metin ve HTML; `{ad}` yer tutucuları [vars] ile (HTML'de kaçışlanarak) doldurulur, bilinmeyenler boş. */
        fun render(t: EmailTemplate, vars: Map<String, String>): Triple<String, String, String?> {
            fun fill(s: String, escape: Boolean) = VAR.replace(s) { m -> vars[m.groupValues[1]].orEmpty().let { v -> if (escape) html(v) else v } }
            // Konu tek satır kalmalı (değerlerde satır sonu olmaz ama yine de).
            return Triple(fill(t.subject, false).replace(Regex("[\\r\\n]+"), " "), fill(t.text, false), t.html?.let { fill(it, true) })
        }

        /** Önizleme için örnek değerlerle (uygulamadaki şablon düzenleyici). */
        fun preview(t: EmailTemplate, app: String): Triple<String, String, String?> =
            render(t, mapOf("app" to app, "email" to "ornek@example.com", "code" to "123456", "link" to "https://ornek.example/_auth/${t.kind}?token=…"))

        /** `Accept-Language` başlığının ilk dili (`tr-TR,tr;q=0.9` → `tr`). */
        fun language(header: String?): String? =
            header?.split(',')?.firstOrNull()?.substringBefore(';')?.substringBefore('-')?.trim()?.lowercase()?.takeIf { Regex("^[a-z]{2}$").matches(it) }

        private val VAR = Regex("\\{([A-Za-z_][A-Za-z0-9_]*)\\}")

        private fun html(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

        /** Loglarda adresin yalnızca ilk harfi ve alan adı: `a***@example.com`. */
        fun mask(address: String): String = address.substringBefore('@').take(1) + "***@" + address.substringAfter('@')

        /** Konfigürasyondan SMTP gönderici (parola çözülmüş olmalı). */
        fun smtp(config: ServerConfig): MailSender {
            val client = SmtpClient(config.email, config.email.password)
            return MailSender { to, subject, text, html -> client.send(to, subject, text, html) }
        }
    }
}
