package com.plyg.droidserver.core.config

import com.plyg.droidserver.core.i18n.tr
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Alan tipi değiştiğinde mevcut değerlerin yeni tipe çevrilmesi ([TypeChangeAction.CONVERT]).
 * Değerler ortak biçimdedir (bkz. SqlDialect): TEXT → String, INTEGER → Long, REAL → Double,
 * BOOLEAN → Boolean, DATETIME → Instant, JSON → JSON metni. Çevrilemeyen değer boşalır; şema planı
 * deploy'dan önce kaç değerin boşalacağını örnekleriyle gösterir.
 */
object TypeConversions {
    private val TARGETS: Map<FieldType, Set<FieldType>> = mapOf(
        FieldType.TEXT to setOf(FieldType.INTEGER, FieldType.REAL, FieldType.BOOLEAN, FieldType.DATETIME, FieldType.JSON, FieldType.RELATION, FieldType.SELECT, FieldType.MULTISELECT, FieldType.GEO),
        FieldType.INTEGER to setOf(FieldType.TEXT, FieldType.REAL, FieldType.BOOLEAN, FieldType.JSON, FieldType.RELATION),
        FieldType.REAL to setOf(FieldType.TEXT, FieldType.INTEGER, FieldType.BOOLEAN, FieldType.JSON),
        FieldType.BOOLEAN to setOf(FieldType.TEXT, FieldType.INTEGER, FieldType.REAL, FieldType.JSON),
        FieldType.DATETIME to setOf(FieldType.TEXT, FieldType.JSON),
        FieldType.JSON to setOf(FieldType.TEXT, FieldType.INTEGER, FieldType.REAL, FieldType.BOOLEAN, FieldType.DATETIME, FieldType.MULTISELECT),
        // Kimlik tam sayıdır; tam sayıya, metne ve JSON'a çevrilir.
        FieldType.RELATION to setOf(FieldType.TEXT, FieldType.INTEGER, FieldType.REAL, FieldType.JSON),
        // Dosya adları başka bir tipte anlamsız; dosya alanına da başka tipten dönüştürülmez (yalnızca boşaltma).
        FieldType.FILE to emptySet(),
        FieldType.GEO to setOf(FieldType.TEXT),
        // Seçimler metindir: metne, birbirine ve JSON'a çevrilir (seçenek dışında kalan değer yazılırken reddedilir).
        FieldType.SELECT to setOf(FieldType.TEXT, FieldType.MULTISELECT, FieldType.JSON),
        FieldType.MULTISELECT to setOf(FieldType.TEXT, FieldType.SELECT, FieldType.JSON),
    )

    private val DECIMAL = Regex("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$")

    /** Değerler anlamlı biçimde çevrilebiliyor mu? Çevrilemiyorsa yalnızca boşaltma seçilebilir. */
    fun canConvert(from: FieldType, to: FieldType): Boolean = from == to || to in TARGETS.getValue(from)

    /** Hangi değerlerin çevrileceğini anlatan kısa açıklama (sihirbaz). */
    fun describe(from: FieldType, to: FieldType): String = when {
        !canConvert(from, to) -> tr("Bu tipler arasında dönüştürme yok; değerler ancak boşaltılabilir.")
        from == FieldType.JSON && to != FieldType.TEXT -> tr("Yalnızca tek değerler (sayı, metin, true/false) çevrilir; nesne ve diziler boşalır.")
        to == FieldType.GEO -> tr("Yalnızca enlem,boylam biçimindeki metinler (41.01,28.97) çevrilir; diğerleri boşalır.")
        to == FieldType.RELATION -> tr("Tam sayılar kayıt kimliği sayılır (hedef koleksiyonda olmayan kimlikler expand'de boş görünür); diğerleri boşalır.")
        from == FieldType.RELATION -> tr("Kayıt kimlikleri çevrilir.")
        to == FieldType.INTEGER && from != FieldType.BOOLEAN -> tr("Yalnızca tam sayılar (42, 2.0) çevrilir; diğerleri boşalır.")
        to == FieldType.REAL && from == FieldType.TEXT -> tr("Yalnızca sayılar (3.14) çevrilir; diğerleri boşalır.")
        to == FieldType.BOOLEAN -> tr("Yalnızca true/false ve 1/0 çevrilir; diğerleri boşalır.")
        to == FieldType.DATETIME -> tr("Yalnızca ISO-8601 zamanlar (2026-10-13T10:00:00Z, 2026-10-13) çevrilir; saat dilimi yoksa UTC sayılır. Diğerleri boşalır.")
        to == FieldType.JSON && from == FieldType.TEXT -> tr("Geçerli JSON olduğu gibi kalır, diğer metinler JSON metni (\"...\") olur.")
        to == FieldType.SELECT && from == FieldType.MULTISELECT -> tr("Her kaydın ilk seçimi kalır.")
        to == FieldType.MULTISELECT && from == FieldType.TEXT ->
            tr("Metin virgüllerden bölünerek seçimlere dönüşür (\"a, b\" → [\"a\", \"b\"]); seçenekler arasında olmayanlar kayıt düzenlenirken düzeltilmeli.")
        to == FieldType.SELECT || to == FieldType.MULTISELECT -> tr("Değerler aynen kalır; seçenekler arasında olmayanlar kayıt düzenlenirken düzeltilmeli.")
        else -> tr("Tüm değerler çevrilir.")
    }

    /** [from] tipindeki değeri [to] tipine çevirir; çevrilemiyorsa null. */
    fun convert(from: FieldType, to: FieldType, value: Any): Any? = try {
        when {
            from == to -> value
            canConvert(from, to) -> convertOrNull(from, to, value)
            else -> null
        }
    } catch (_: RuntimeException) {
        null
    }

    private fun convertOrNull(from: FieldType, to: FieldType, value: Any): Any? = when (to) {
        FieldType.SELECT -> when (from) {
            FieldType.MULTISELECT -> multiValues(value.toString()).firstOrNull()
            else -> value.toString()
        }
        FieldType.MULTISELECT -> when (from) {
            FieldType.JSON, FieldType.MULTISELECT -> multiValues(value.toString()).takeIf { it.isNotEmpty() }?.let(::multiText)
            FieldType.TEXT -> value.toString().split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct().takeIf { it.isNotEmpty() }?.let(::multiText)
            else -> value.toString().takeIf { it.isNotEmpty() }?.let { multiText(listOf(it)) }
        }
        FieldType.TEXT -> when (value) {
            is Double -> BigDecimal.valueOf(value).toPlainString()
            else -> value.toString() // Long, Boolean, Instant, JSON metni
        }
        FieldType.INTEGER -> when (value) {
            is Boolean -> if (value) 1L else 0L
            is Number -> wholeLong(value.toDouble())
            else -> textOf(from, value)?.let(::parseLong)
        }
        FieldType.REAL -> when (value) {
            is Boolean -> if (value) 1.0 else 0.0
            is Number -> value.toDouble()
            else -> textOf(from, value)?.let(::parseDouble)
        }
        FieldType.BOOLEAN -> when (value) {
            is Number -> when (value.toDouble()) {
                0.0 -> false
                1.0 -> true
                else -> null
            }
            else -> when (textOf(from, value)?.trim()?.lowercase()) {
                "true", "1" -> true
                "false", "0" -> false
                else -> null
            }
        }
        FieldType.DATETIME -> when (from) {
            FieldType.TEXT, FieldType.JSON -> textOf(from, value)?.let(::parseInstant)
            else -> null
        }
        FieldType.RELATION -> when (value) {
            is Number -> wholeLong(value.toDouble())
            else -> textOf(from, value)?.let(::parseLong)
        }?.takeIf { it > 0 }
        FieldType.FILE -> null
        FieldType.GEO -> GeoPoint.parse(value.toString())?.text()
        FieldType.JSON -> when (value) {
            is Boolean -> JsonPrimitive(value).toString()
            is Number -> JsonPrimitive(value).toString()
            is Instant -> JsonPrimitive(value.toString()).toString()
            else -> {
                val s = value.toString()
                // Geçerli JSON aynen kalır; düz metin JSON metni olur.
                StrictJson.parse(s)?.toString() ?: JsonPrimitive(s).toString()
            }
        }
    }

    /** Çoklu seçim metni (JSON dizi) → değerler; geçersizse boş. */
    fun multiValues(text: String): List<String> =
        (StrictJson.parse(text) as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty()

    fun multiText(values: List<String>): String = kotlinx.serialization.json.JsonArray(values.map(::JsonPrimitive)).toString()

    /** Metin veya JSON'daki tek değerin metni; JSON nesne/dizi ise null. */
    private fun textOf(from: FieldType, value: Any): String? = when (from) {
        FieldType.JSON -> (StrictJson.parse(value.toString()) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
        else -> value.toString()
    }

    private fun parseLong(s: String): Long? = s.trim().toLongOrNull() ?: parseDouble(s)?.let(::wholeLong)

    private fun parseDouble(s: String): Double? =
        s.trim().takeIf { DECIMAL.matches(it) }?.toDoubleOrNull()?.takeIf { it.isFinite() }

    private fun wholeLong(d: Double): Long? =
        if (d % 1.0 == 0.0 && d >= Long.MIN_VALUE.toDouble() && d < Long.MAX_VALUE.toDouble()) d.toLong() else null

    private fun parseInstant(raw: String): Instant? {
        val s = raw.trim()
        return runCatching { Instant.parse(s) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(s).toInstant() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(s).toInstant(ZoneOffset.UTC) }.getOrNull()
            ?: runCatching { LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant() }.getOrNull()
    }
}
