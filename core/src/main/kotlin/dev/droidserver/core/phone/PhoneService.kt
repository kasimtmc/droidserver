package dev.droidserver.core.phone

import dev.droidserver.core.api.ApiException
import dev.droidserver.core.audit.AuditLog
import dev.droidserver.core.config.PhoneConfig
import dev.droidserver.core.i18n.tr
import dev.droidserver.core.metrics.DeviceStats
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
}

data class PhoneLocation(val latitude: Double, val longitude: Double, val accuracyM: Float?, val at: Instant, val provider: String?)

class PhoneException(message: String) : RuntimeException(message)

/**
 * Telefon özellikleri, konfigürasyondaki izinlerle ([PhoneConfig]). SMS'te numara E.164 olmalı ve izinli bir önekle
 * başlamalı; günlük sınır sayacı [counterFile]'da (uygulama yeniden başlasa da sürer). Her SMS denetim kaydına yazılır
 * (numaranın son 4 hanesi gizlenir, metin yazılmaz).
 */
class PhoneService(
    private val config: PhoneConfig,
    private val host: PhoneHost?,
    private val stats: DeviceStats? = null,
    private val counterFile: File? = null,
    private val audit: AuditLog? = null,
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

    /** SMS gönderir; bugünkü sayı ve kalan hak. */
    @Synchronized
    fun sendSms(to: String, text: String, actor: String): JsonObject {
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
        val phone = host ?: throw ApiException(503, tr("Telefon özellikleri bu ortamda yok"))
        try {
            phone.sendSms(number, text)
        } catch (e: PhoneException) {
            throw ApiException(503, tr("SMS gönderilemedi: {0}", e.message))
        }
        count++
        save()
        audit?.record(actor, "phone.sms", number.dropLast(4) + "****", tr("{0} karakter", text.length))
        return buildJsonObject {
            put("sent", JsonPrimitive(true))
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
        val E164 = Regex("^\\+[1-9][0-9]{7,14}$")
        val PREFIX = Regex("^\\+[0-9]{1,6}$")
    }
}
