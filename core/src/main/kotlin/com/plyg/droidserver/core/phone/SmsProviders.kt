package com.plyg.droidserver.core.phone

import com.plyg.droidserver.core.config.SmsProviderConfig
import com.plyg.droidserver.core.config.SmsProviderType
import com.plyg.droidserver.core.i18n.tr
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.Base64

/**
 * SMS sağlayıcıları (operatör üzerinden, otomatik). Başarısızlıkta [PhoneException] (nedeniyle). Numara E.164 gelir
 * (`+905…`); Netgsm ve İleti Merkezi `905…` biçimini ister.
 *  - Netgsm: `POST /sms/rest/v2/send`, Basic (kullanıcı kodu:parola), yanıt `code` "00" başarı.
 *  - İleti Merkezi: `POST /v1/send-sms/json`, API anahtarı + paneldeki hash; yanıt `response.status.code` 200.
 *    `iys: "0"`: işlemsel ileti (doğrulama kodu, bildirim); pazarlama iletisi İYS onayı ister, burada gönderilmez.
 *  - Twilio: `POST /2010-04-01/Accounts/{SID}/Messages.json` (form), Basic (SID:token); gönderen `MG…` ise Messaging
 *    Service, değilse numara.
 */
object SmsProviders {
    fun send(c: SmsProviderConfig, to: String, text: String) {
        when (c.type) {
            SmsProviderType.NETGSM -> netgsm(c, to, text)
            SmsProviderType.ILETIMERKEZI -> iletimerkezi(c, to, text)
            SmsProviderType.TWILIO -> twilio(c, to, text)
        }
    }

    private fun netgsm(c: SmsProviderConfig, to: String, text: String) {
        val body = buildJsonObject {
            put("msgheader", JsonPrimitive(c.sender))
            put("messages", JsonArray(listOf(buildJsonObject { put("msg", JsonPrimitive(text)); put("no", JsonPrimitive(to.removePrefix("+"))) })))
            put("encoding", JsonPrimitive("TR"))
            put("iysfilter", JsonPrimitive("0"))
        }
        val (status, reply) = post(c, (c.baseUrl ?: "https://api.netgsm.com.tr") + "/sms/rest/v2/send", body.toString(), "application/json", basic(c.user, c.secret))
        val json = parse(reply)
        val code = json?.get("code")?.jsonPrimitive?.contentOrNull
        if (status !in 200..299 || code != "00") {
            fail(c, status, json?.get("description")?.jsonPrimitive?.contentOrNull?.let { "$code: $it" } ?: code ?: reply)
        }
    }

    private fun iletimerkezi(c: SmsProviderConfig, to: String, text: String) {
        val body = buildJsonObject {
            put("request", buildJsonObject {
                put("authentication", buildJsonObject { put("key", JsonPrimitive(c.user)); put("hash", JsonPrimitive(c.secret)) })
                put("order", buildJsonObject {
                    put("sender", JsonPrimitive(c.sender))
                    put("iys", JsonPrimitive("0"))
                    put("message", buildJsonObject {
                        put("text", JsonPrimitive(text))
                        // API'deki yazımıyla (receipents).
                        put("receipents", buildJsonObject { put("number", JsonArray(listOf(JsonPrimitive(to.removePrefix("+"))))) })
                    })
                })
            })
        }
        val (status, reply) = post(c, (c.baseUrl ?: "https://api.iletimerkezi.com") + "/v1/send-sms/json", body.toString(), "application/json", emptyMap())
        val st = parse(reply)?.get("response")?.jsonObject?.get("status")?.jsonObject
        val code = st?.get("code")?.jsonPrimitive?.contentOrNull
        if (status !in 200..299 || code != "200") fail(c, status, st?.get("message")?.jsonPrimitive?.contentOrNull?.let { "$code: $it" } ?: reply)
    }

    private fun twilio(c: SmsProviderConfig, to: String, text: String) {
        val from = if (c.sender.startsWith("MG")) "MessagingServiceSid" else "From"
        val form = listOf("To" to to, from to c.sender, "Body" to text)
            .joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, Charsets.UTF_8.name()) }
        val url = (c.baseUrl ?: "https://api.twilio.com") + "/2010-04-01/Accounts/" + URLEncoder.encode(c.user, Charsets.UTF_8.name()) + "/Messages.json"
        val (status, reply) = post(c, url, form, "application/x-www-form-urlencoded", basic(c.user, c.secret))
        if (status !in 200..299) fail(c, status, parse(reply)?.get("message")?.jsonPrimitive?.contentOrNull ?: reply)
    }

    private fun basic(user: String, secret: String) = mapOf("Authorization" to "Basic " + Base64.getEncoder().encodeToString("$user:$secret".toByteArray()))

    private fun parse(text: String): JsonObject? = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()

    private fun fail(c: SmsProviderConfig, status: Int, detail: String): Nothing =
        throw PhoneException(tr("{0} hatası ({1}): {2}", c.type.title, status, detail.take(300)))

    /** (durum, gövde); anahtarlar yalnızca başlıkta ya da gövdede, adreste değil. */
    private fun post(c: SmsProviderConfig, url: String, body: String, contentType: String, headers: Map<String, String>): Pair<Int, String> = try {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "$contentType; charset=utf-8")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            code to (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        } finally {
            conn.disconnect()
        }
    } catch (e: IOException) {
        throw PhoneException(tr("{0} API'sine ulaşılamadı: {1}", c.type.title, e.message ?: e.toString()))
    }
}
