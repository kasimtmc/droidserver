package com.plyg.droidserver.core.phone

import com.plyg.droidserver.core.api.ApiException
import com.plyg.droidserver.core.audit.AuditLog
import com.plyg.droidserver.core.config.PhoneConfig
import com.plyg.droidserver.core.config.SmsProviderConfig
import com.plyg.droidserver.core.config.SmsSim
import com.plyg.droidserver.core.i18n.tr
import com.plyg.droidserver.core.metrics.DeviceStats
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Telefonun kendisi (app sağlar): SMS gönderme ve son bilinen konum. İzin yoksa [PhoneException]. */
interface PhoneHost {
    fun sendSms(to: String, text: String)
    fun location(): PhoneLocation?

    /**
     * Kullanıcı onaylı SIM: isteği kuyruğa ekler ve hemen döner; telefonun sahibi bildirimden "Gönder" deyince SMS
     * uygulaması açılır. [ttlSeconds] dolunca istek düşer; [replace]: aynı numaraya bekleyen istek varsa onun yerine
     * geçer (yeni giriş kodu eskisini geçersiz kılar).
     */
    fun queueSms(to: String, text: String, ttlSeconds: Long, replace: Boolean): Unit =
        throw PhoneException(tr("Onaylı SMS bu ortamda yok"))
}

data class PhoneLocation(val latitude: Double, val longitude: Double, val accuracyM: Float?, val at: Instant, val provider: String?)

class PhoneException(message: String) : RuntimeException(message)

/**
 * Telefon özellikleri, konfigürasyondaki izinlerle ([PhoneConfig]). SMS'te numara E.164 olmalı ve izinli bir önekle
 * başlamalı; günlük sınır sayacı [counterFile]'da (uygulama yeniden başlasa da sürer). Her SMS denetim kaydına yazılır
 * (numaranın son 4 hanesi gizlenir, metin yazılmaz).
 *
 * SMS yolu: sağlayıcı varsa önce o; olmazsa ya da yoksa SIM ([PhoneConfig.smsSim]): doğrudan ya da kullanıcı onaylı
 * kuyruk (o zaman yanıt `status: "awaiting_user"`, SMS telefonun sahibi onaylayınca gider). Kuyruğa giren de sınırdan
 * düşer.
 */
class PhoneService(
    private val config: PhoneConfig,
    private val host: PhoneHost?,
    private val stats: DeviceStats? = null,
    private val counterFile: File? = null,
    private val audit: AuditLog? = null,
    private val provider: (SmsProviderConfig, String, String) -> Unit = SmsProviders::send,
    private val today: () -> LocalDate = { LocalDate.now(ZoneId.systemDefault()) },
) {
    private var day: LocalDate? = null
    private var count = 0

    init {
        runCatching {
            counterFile?.takeIf { it.isFile }?.readText()?.let { kotlinx.serialization.json.Json.parseToJsonElement(it).jsonObject }?.let {
                day = LocalDate.parse(it.getValue("day").jsonPrimitive.content)
                count = it.getValue("count").jsonPrimitive.int
            }
        }
    }

    /**
     * SMS gönderir (ya da onaylı SIM kipinde kuyruğa ekler); yol, bugünkü sayı ve kalan hak. [ttlSeconds] ve [replace]
     * yalnızca onaylı kuyrukta (bkz. [PhoneHost.queueSms]).
     */
    @Synchronized
    fun sendSms(to: String, text: String, actor: String, ttlSeconds: Long = QUEUE_TTL_SECONDS, replace: Boolean = false): JsonObject {
        if (!config.sms) throw ApiException(404, tr("SMS gönderme kapalı (Sihirbaz → Genel → Telefon özellikleri)"))
        val number = to.replace(" ", "").replace("-", "")
        if (!E164.matches(number)) throw ApiException(400, tr("Numara uluslararası biçimde olmalı, örn. +905551234567"))
        if (config.smsPrefixes.none { number.startsWith(it) }) {
            throw ApiException(403, tr("Bu numaraya SMS gönderilemez; izinli önekler: {0}", config.smsPrefixes.joinToString()))
        }
        if (text.isBlank()) throw ApiException(400, tr("SMS metni boş"))
        if (text.length > MAX_TEXT) throw ApiException(400, tr("SMS metni en çok {0} karakter olabilir", MAX_TEXT))
        val d = today()
        if (day != d) { day = d; count = 0 }
        if (count >= config.smsDailyLimit) throw ApiException(429, tr("Günlük SMS sınırı doldu ({0})", config.smsDailyLimit))
        val providerError = config.smsProvider?.let { p ->
            try {
                provider(p, number, text)
                null
            } catch (e: PhoneException) {
                e.message
            }
        }
        val via = when {
            config.smsProvider != null && providerError == null -> "provider"
            config.smsSim == SmsSim.OFF -> throw ApiException(503, tr("SMS gönderilemedi: {0}", providerError))
            else -> {
                val phone = host ?: throw ApiException(503, providerError?.let { tr("SMS gönderilemedi: {0}", it) } ?: tr("Telefon özellikleri bu ortamda yok"))
                try {
                    if (config.smsSim == SmsSim.CONFIRM) phone.queueSms(number, text, ttlSeconds, replace) else phone.sendSms(number, text)
                } catch (e: PhoneException) {
                    throw ApiException(503, tr("SMS gönderilemedi: {0}", listOfNotNull(providerError, e.message).joinToString("; ")))
                }
                if (config.smsSim == SmsSim.CONFIRM) "confirm" else "sim"
            }
        }
        count++
        save()
        val detail = tr("{0} karakter, {1}", text.length, via) + (providerError?.let { " " + tr("(sağlayıcı başarısız: {0})", it) } ?: "")
        audit?.record(actor, "phone.sms", number.dropLast(4) + "****", detail)
        return buildJsonObject {
            put("sent", JsonPrimitive(via != "confirm"))
            put("status", JsonPrimitive(if (via == "confirm") "awaiting_user" else "sent"))
            put("via", JsonPrimitive(via))
            put("today", JsonPrimitive(count))
            put("remaining", JsonPrimitive(config.smsDailyLimit - count))
        }
    }

    fun location(): JsonObject {
        if (!config.location) throw ApiException(404, tr("Konum kapalı (Sihirbaz → Genel → Telefon özellikleri)"))
        val phone = host ?: throw ApiException(503, tr("Telefon özellikleri bu ortamda yok"))
        val loc = try {
            phone.location()
        } catch (e: PhoneException) {
            throw ApiException(503, tr("Konum alınamadı: {0}", e.message))
        } ?: return buildJsonObject { put("available", JsonPrimitive(false)) }
        return buildJsonObject {
            put("available", JsonPrimitive(true))
            put("latitude", JsonPrimitive(loc.latitude))
            put("longitude", JsonPrimitive(loc.longitude))
            put("accuracyM", loc.accuracyM?.let(::JsonPrimitive) ?: JsonNull)
            put("at", JsonPrimitive(loc.at.toString()))
            put("provider", loc.provider?.let(::JsonPrimitive) ?: JsonNull)
        }
    }

    fun status(): JsonObject {
        if (!config.status) throw ApiException(404, tr("Cihaz durumu kapalı (Sihirbaz → Genel → Telefon özellikleri)"))
        val s = stats?.snapshot() ?: throw ApiException(503, tr("Telefon özellikleri bu ortamda yok"))
        fun num(v: Number?) = v?.let(::JsonPrimitive) ?: JsonNull
        return buildJsonObject {
            put("batteryPercent", num(s.batteryPercent))
            put("batteryTempC", num(s.batteryTempC))
            put("charging", s.charging?.let(::JsonPrimitive) ?: JsonNull)
            put("freeStorageBytes", num(s.freeStorageBytes))
            put("memoryUsedBytes", num(s.memoryUsedBytes))
            put("memoryTotalBytes", num(s.memoryTotalBytes))
        }
    }

    private fun save() {
        val file = counterFile ?: return
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(buildJsonObject { put("day", JsonPrimitive(day.toString())); put("count", JsonPrimitive(count)) }.toString())
        }
    }

    companion object {
        const val MAX_TEXT = 480
        /** Onaylı kuyruktaki isteğin ömrü (giriş kodları kendi süreleriyle, 10 dk). */
        const val QUEUE_TTL_SECONDS = 30 * 60L
        val E164 = Regex("^\\+[1-9][0-9]{7,14}$")
        val PREFIX = Regex("^\\+[0-9]{1,6}$")
    }
}
