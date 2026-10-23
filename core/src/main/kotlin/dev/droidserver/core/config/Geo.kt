package dev.droidserver.core.config

import dev.droidserver.core.i18n.tr
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * `geo` alanının değeri. API'de `{"lat": 41.01, "lng": 28.97, "accuracy": 12}`, veritabanında `41.01,28.97[,12]` metni
 * (SQL'de enlem ve boylam bu metinden çıkarılır, bkz. SqlDialect.geoLat).
 */
data class GeoPoint(val lat: Double, val lng: Double, val accuracy: Double? = null) {
    init {
        require(lat.isFinite() && lat in -90.0..90.0) { tr("enlem -90 ile 90 arasında olmalı") }
        require(lng.isFinite() && lng in -180.0..180.0) { tr("boylam -180 ile 180 arasında olmalı") }
        require(accuracy == null || (accuracy.isFinite() && accuracy >= 0)) { tr("doğruluk 0 veya daha büyük olmalı (metre)") }
    }

    /** Veritabanındaki metin. */
    fun text(): String = listOfNotNull(lat, lng, accuracy).joinToString(",") { num(it) }

    fun json(): JsonObject = buildJsonObject {
        put("lat", JsonPrimitive(lat))
        put("lng", JsonPrimitive(lng))
        accuracy?.let { put("accuracy", JsonPrimitive(it)) }
    }

    /** İki nokta arası büyük daire uzaklığı (km). */
    fun distanceKm(other: GeoPoint): Double {
        val dLat = Math.toRadians(other.lat - lat)
        val dLng = Math.toRadians(other.lng - lng)
        val h = sin(dLat / 2).let { it * it } + cos(Math.toRadians(lat)) * cos(Math.toRadians(other.lat)) * sin(dLng / 2).let { it * it }
        return 2 * EARTH_KM * asin(min(1.0, sqrt(h)))
    }

    companion object {
        const val EARTH_KM = 6371.0088

        /** `near` aramasında en büyük yarıçap (km); yaklaşık uzaklık bu ölçeğe kadar yeterince doğrudur. */
        const val MAX_NEAR_KM = 1000.0

        /** Bir enlem derecesinin uzunluğu (km). */
        const val KM_PER_DEGREE = EARTH_KM * Math.PI / 180

        /**
         * `near` filtresinin uzaklığı (km): eşdikdörtgen yaklaşım, ortalama enlemin kosinüsü Taylor serisiyle. SQL'de
         * aynı formül kullanılır (eski Android SQLite'ında trigonometri yok); kural koşulları da bununla değerlendirilir
         * ki iki yol aynı sonucu versin. 1000 km'ye kadar hata binde birkaç düzeyinde.
         */
        fun approxKm(lat: Double, lng: Double, lat0: Double, lng0: Double): Double {
            val dl = kotlin.math.abs(lng - lng0).let { if (it > 180) 360 - it else it }
            val m = (lat + lat0) * (Math.PI / 360)
            val m2 = m * m
            val c = 1 - m2 * (0.5 - m2 * (1.0 / 24 - m2 / 720))
            val x = dl * c
            val y = lat - lat0
            return sqrt(x * x + y * y) * KM_PER_DEGREE
        }

        /** [approxKm]'nin SQL'i (karesi, derece²); [lat] ve [lng] sütundan çıkarılmış ifadeler. Sayılar doğrulanmış. */
        fun approxSquaredSql(lat: String, lng: String, lat0: Double, lng0: Double): String {
            val dl = "(CASE WHEN abs($lng - (${lng0})) > 180 THEN 360 - abs($lng - (${lng0})) ELSE abs($lng - (${lng0})) END)"
            val m2 = "((($lat) + (${lat0})) * ${Math.PI / 360} * (($lat) + (${lat0})) * ${Math.PI / 360})"
            val c = "(1 - $m2 * (0.5 - $m2 * (${1.0 / 24} - $m2 / 720.0)))"
            return "(($dl * $c) * ($dl * $c) + (($lat) - (${lat0})) * (($lat) - (${lat0})))"
        }

        private fun num(d: Double): String = if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) d.toLong().toString() else d.toString()

        /** API değeri: `{lat, lng, accuracy?}`. Uymazsa [IllegalArgumentException] (Türkçe ileti). */
        fun of(v: JsonElement): GeoPoint {
            val o = v as? JsonObject ?: throw IllegalArgumentException(tr("konum bekleniyor: {\"lat\": 41.01, \"lng\": 28.97}"))
            o.keys.firstOrNull { it !in setOf("lat", "lng", "accuracy") }?.let { throw IllegalArgumentException(tr("konumda bilinmeyen anahtar: {0}", it)) }
            fun number(key: String): Double? = when (val e = o[key]) {
                null, JsonNull -> null
                is JsonPrimitive -> e.takeIf { !it.isString }?.doubleOrNull ?: throw IllegalArgumentException(tr("{0} sayı olmalı", key))
                else -> throw IllegalArgumentException(tr("{0} sayı olmalı", key))
            }
            val lat = number("lat") ?: throw IllegalArgumentException(tr("konumda lat (enlem) gerekli"))
            val lng = number("lng") ?: throw IllegalArgumentException(tr("konumda lng (boylam) gerekli"))
            return GeoPoint(lat, lng, number("accuracy"))
        }

        /** `enlem,boylam[,doğruluk]` metni (veritabanı, CSV, form); uymazsa null. */
        fun parse(text: String): GeoPoint? {
            val parts = text.split(',').map { it.trim() }
            if (parts.size !in 2..3) return null
            val nums = parts.map { it.toDoubleOrNull() ?: return null }
            return runCatching { GeoPoint(nums[0], nums[1], nums.getOrNull(2)) }.getOrNull()
        }

        /** Veritabanındaki metnin API hali; bozuksa null. */
        fun json(stored: String?): JsonElement = stored?.let(::parse)?.json() ?: JsonNull
    }
}
