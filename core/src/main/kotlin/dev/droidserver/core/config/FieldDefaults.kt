package dev.droidserver.core.config

import dev.droidserver.core.i18n.tr
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.format.DateTimeParseException

/** Alanların varsayılan değerleri: tip kontrolü (validator) ve kullanıcı girdisinden dönüştürme (sihirbaz). */
object FieldDefaults {

    /** Değer alan tipine uygun değilse açıklama, uygunsa null. Varsayılan olmaması (null) her zaman uygundur. */
    fun problem(type: FieldType, value: JsonElement?): String? {
        if (value == null || value is JsonNull) return null
        if (!StrictJson.isValid(value)) return tr("geçerli JSON değil; metinleri tırnak içinde yazın")
        val prim = value as? JsonPrimitive
        return when (type) {
            FieldType.TEXT -> if (prim?.isString == true) null else tr("metin bekleniyor")
            FieldType.INTEGER -> if (prim != null && !prim.isString && prim.longOrNull != null) null else tr("tam sayı bekleniyor")
            FieldType.REAL -> if (prim != null && !prim.isString && prim.doubleOrNull != null) null else tr("sayı bekleniyor")
            FieldType.BOOLEAN -> if (prim != null && !prim.isString && prim.booleanOrNull != null) null else tr("true/false bekleniyor")
            FieldType.DATETIME -> if (prim?.isString == true && parseInstant(prim.content) != null) null else DATETIME_HINT
            FieldType.JSON -> null
            FieldType.RELATION -> tr("ilişki alanına varsayılan verilemez")
            FieldType.FILE -> tr("dosya alanına varsayılan verilemez")
            FieldType.SELECT -> if (prim?.isString == true) null else tr("seçeneklerden biri (metin) bekleniyor")
            FieldType.MULTISELECT -> if ((value as? kotlinx.serialization.json.JsonArray)?.all { (it as? JsonPrimitive)?.isString == true } == true) null
            else tr("seçenek listesi bekleniyor, örn. [\"a\", \"b\"]")
        }
    }

    /**
     * Kullanıcının yazdığı metni tipe uygun JSON değere çevirir. Boş metin = varsayılan yok (null).
     * Geçersizse açıklamalı [IllegalArgumentException].
     */
    fun parse(type: FieldType, raw: String): JsonElement? {
        if (raw.isBlank()) return null
        val s = raw.trim()
        return when (type) {
            FieldType.TEXT -> JsonPrimitive(raw)
            FieldType.INTEGER -> JsonPrimitive(s.toLongOrNull() ?: throw IllegalArgumentException(tr("tam sayı bekleniyor")))
            FieldType.REAL -> JsonPrimitive(s.toDoubleOrNull() ?: throw IllegalArgumentException(tr("sayı bekleniyor")))
            FieldType.BOOLEAN -> JsonPrimitive(s.toBooleanStrictOrNull() ?: throw IllegalArgumentException(tr("true/false bekleniyor")))
            FieldType.DATETIME -> JsonPrimitive(parseInstant(s)?.toString() ?: throw IllegalArgumentException(DATETIME_HINT))
            FieldType.JSON -> StrictJson.parse(s) ?: throw IllegalArgumentException(tr("geçerli JSON bekleniyor"))
            FieldType.RELATION -> throw IllegalArgumentException(tr("ilişki alanına varsayılan verilemez"))
            FieldType.FILE -> throw IllegalArgumentException(tr("dosya alanına varsayılan verilemez"))
            FieldType.SELECT -> JsonPrimitive(s)
            // Virgülle ayrılmış seçimler.
            FieldType.MULTISELECT -> kotlinx.serialization.json.JsonArray(s.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map(::JsonPrimitive))
        }
    }

    /** Düzenleme alanında gösterilecek metin ([parse]'ın tersi). */
    fun display(value: JsonElement?): String = when (value) {
        null, JsonNull -> ""
        is JsonPrimitive -> value.content
        is kotlinx.serialization.json.JsonArray -> value.joinToString(", ") { (it as? JsonPrimitive)?.content ?: it.toString() }
        else -> value.toString()
    }

    private val DATETIME_HINT: String get() = tr("ISO-8601 zaman bekleniyor, örn. 2026-10-13T10:00:00Z")

    private fun parseInstant(s: String): Instant? = try {
        Instant.parse(s)
    } catch (_: DateTimeParseException) {
        null
    }
}
