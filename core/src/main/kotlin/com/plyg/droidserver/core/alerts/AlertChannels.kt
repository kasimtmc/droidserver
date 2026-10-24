package com.plyg.droidserver.core.alerts

import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.mail.MailSender
import com.plyg.droidserver.core.mail.Mailer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/**
 * Telegram botuyla mesaj (`sendMessage`). [base] testlerde sahte sunucu. Token adreste geçtiği için hata
 * mesajlarına adres yazılmaz.
 */
class TelegramChannel(
    private val token: String,
    private val chatId: String,
    private val serverName: String,
    private val base: String = API,
) : AlertChannel {
    override fun send(alert: Alert) {
        val icon = if (alert.resolved) "✅" else if (alert.critical) "🚨" else "⚠️"
        call(base, token, "sendMessage", "chat_id=${enc(chatId)}&text=${enc("$icon ${alert.text(serverName)}")}&disable_web_page_preview=true")
    }

    companion object {
        const val API = "https://api.telegram.org"

        /**
         * Bota en son mesaj atan sohbetin kimliği (kullanıcı bota "/start" yazdıktan sonra). Token geçersizse
         * [IOException]; mesaj yoksa null.
         */
        fun discoverChat(token: String, base: String = API): Pair<String, String>? {
            val body = call(base, token, "getUpdates", "limit=50")
            val updates = (Json.parseToJsonElement(body).jsonObject["result"] as? JsonArray).orEmpty()
            val chat = updates.asReversed().firstNotNullOfOrNull { u ->
                val o = u as? JsonObject ?: return@firstNotNullOfOrNull null
                (o["message"] ?: o["channel_post"] ?: o["my_chat_member"])?.let { (it as? JsonObject)?.get("chat") as? JsonObject }
            } ?: return null
            val id = (chat["id"] as? JsonPrimitive)?.content ?: return null
            val name = listOf("title", "username", "first_name").firstNotNullOfOrNull { (chat[it] as? JsonPrimitive)?.content } ?: id
            return id to name
        }

        internal fun call(base: String, token: String, method: String, form: String, readTimeoutMs: Int = 15_000): String {
            val conn = URI("${base.trimEnd('/')}/bot$token/$method").toURL().openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 10_000
                conn.readTimeout = readTimeoutMs
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.outputStream.use { it.write(form.toByteArray()) }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
                if (code !in 200..299) {
                    val description = runCatching { (Json.parseToJsonElement(text).jsonObject["description"] as? JsonPrimitive)?.content }.getOrNull()
                    throw IOException("Telegram $code" + (description?.let { ": $it" } ?: ""))
                }
                return text
            } finally {
                conn.disconnect()
            }
        }

        internal fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    }
}

/** E-postayla uyarı (sunucunun SMTP ayarlarıyla); her alıcıya ayrı gönderilir. */
class EmailChannel(private val sender: MailSender, private val to: List<String>, private val serverName: String) : AlertChannel {
    override fun send(alert: Alert) {
        val subject = "$serverName: ${alert.title}"
        val text = alert.message + "\n\n" + alert.at + "\n— DroidServer"
        var failure: Exception? = null
        to.forEach { runCatching { sender.send(it, subject, text, null) }.onFailure { e -> failure = e as? Exception ?: IOException(e) } }
        failure?.let { throw it }
    }
}

object AlertChannels {
    /** Konfigürasyondaki kanallar (sırlar çözülmüş olmalı); [notification] telefon bildirimi (app verir). */
    fun from(config: ServerConfig, notification: AlertChannel?, mailSender: MailSender? = null): Map<String, AlertChannel> = buildMap {
        val a = config.alerts
        if (a.notification && notification != null) put("bildirim", notification)
        if (a.email.isNotEmpty() && config.email.enabled) put("e-posta", EmailChannel(mailSender ?: Mailer.smtp(config), a.email, config.name))
        a.telegram?.takeIf { it.botToken.isNotBlank() && !it.botToken.startsWith("secret:") }?.let { put("Telegram", TelegramChannel(it.botToken, it.chatId, config.name)) }
    }
}
