package com.plyg.droidserver.core.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Üç yönlü birleştirme: sihirbaz taslağı ([mine]) [base]'den başlamışken etkin dosya başka yerden ([theirs]: web paneli,
 * paket, yedekten geri yükleme, kopya) değişti. Yalnızca bir tarafın değiştirdiği yer o taraftan alınır; nesneler alan
 * alan, `name` alanıyla tanınan öğe dizileri (koleksiyonlar, görünümler, paketler…) öğe öğe birleşir. İki taraf aynı yeri
 * farklı değiştirdiyse taslaktaki kalır ve yol [Result.conflicts]'e yazılır.
 */
object ConfigMerge {
    data class Result(val config: ServerConfig, val conflicts: List<String>)

    fun threeWay(base: ServerConfig?, mine: ServerConfig, theirs: ServerConfig): Result {
        val conflicts = mutableListOf<String>()
        val merged = merge(base?.let(::tree), tree(mine), tree(theirs), "", conflicts)
        val config = runCatching { ConfigJson.decodeFromJsonElement(ServerConfig.serializer(), merged!!) }.getOrNull()
            ?: return Result(mine, listOf(""))
        return Result(config, conflicts)
    }

    private fun tree(c: ServerConfig) = ConfigJson.encodeToJsonElement(ServerConfig.serializer(), c)

    /** null: alan ya da öğe yok (silinmiş). */
    private fun merge(base: JsonElement?, mine: JsonElement?, theirs: JsonElement?, path: String, conflicts: MutableList<String>): JsonElement? {
        if (mine == theirs || theirs == base) return mine
        if (mine == base) return theirs
        if (mine is JsonObject && theirs is JsonObject && (base == null || base is JsonObject)) {
            val b = base as JsonObject?
            val out = LinkedHashMap<String, JsonElement>()
            for (k in (mine.keys + theirs.keys)) {
                merge(b?.get(k), mine[k], theirs[k], if (path.isEmpty()) k else "$path.$k", conflicts)?.let { out[k] = it }
            }
            return JsonObject(out)
        }
        val m = named(mine)
        val t = named(theirs)
        val b = if (base == null) emptyMap() else named(base)
        if (m != null && t != null && b != null) {
            return JsonArray((m.keys + t.keys).mapNotNull { n -> merge(b[n], m[n], t[n], "$path[$n]", conflicts) })
        }
        conflicts += path
        return mine
    }

    /** Her öğesi farklı `name`'li nesne olan dizi: ad -> öğe; değilse null. */
    private fun named(e: JsonElement?): Map<String, JsonElement>? {
        if (e !is JsonArray) return null
        val out = LinkedHashMap<String, JsonElement>()
        for (item in e) {
            val name = ((item as? JsonObject)?.get("name") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
            if (out.put(name, item) != null) return null
        }
        return out
    }
}
