package dev.droidserver.core.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Standart JSON denetimi. kotlinx tırnaksız kelimeleri (`abc`, `NaN`) de değer sayar ve geri yazarken tırnaksız
 * yazar; bunlar geçerli JSON değildir (SQLite'a bozuk metin olarak yazılır, Postgres JSONB reddeder). İstek
 * gövdeleri, varsayılan değerler ve tip dönüşümleri bu denetimden geçer.
 */
object StrictJson {
    private val NUMBER = Regex("^-?(0|[1-9]\\d*)(\\.\\d+)?([eE][+-]?\\d+)?$")

    /** Öğe (iç içe hepsi) standart JSON mu? Tırnaksız değer yalnızca `true`, `false` veya sayı olabilir. */
    fun isValid(e: JsonElement): Boolean = when (e) {
        is JsonObject -> e.values.all(::isValid)
        is JsonArray -> e.all(::isValid)
        is JsonNull -> true
        is JsonPrimitive -> e.isString || e.content == "true" || e.content == "false" || NUMBER.matches(e.content)
    }

    /** Metni JSON olarak okur; geçersiz veya standart dışıysa null. */
    fun parse(s: String): JsonElement? =
        runCatching { Json.parseToJsonElement(s) }.getOrNull()?.takeIf(::isValid)
}
