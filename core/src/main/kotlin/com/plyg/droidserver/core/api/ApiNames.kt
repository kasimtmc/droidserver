package com.plyg.droidserver.core.api

import com.plyg.droidserver.core.config.CollectionConfig
import com.plyg.droidserver.core.config.SystemFieldConfig
import com.plyg.droidserver.core.config.UsersConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Sistem alanlarının API'deki adları (uzman modu, [SystemFieldConfig]). Veritabanı ve kayıt servisi her zaman
 * özgün adlarla (`id`, `created_at`, `email` ...) çalışır; bu sınıf HTTP sınırında çevirir: yanıtlarda alan yeni adıyla
 * çıkar ya da kaldırılmışsa hiç çıkmaz, isteklerde (gövde, filtre, sıralama, alan seçimi, özet) yeni ad özgün ada
 * çevrilir. Özgün ad isteklerde de kabul edilir (geçiş kolay olsun diye).
 */
class ApiNames private constructor(
    /** Özgün ad → API adı (null: kaldırıldı); yalnızca değişenler. */
    private val toApi: Map<String, String?>,
) {
    private val toCanonical: Map<String, String> = toApi.entries.mapNotNull { (k, v) -> v?.let { it to k } }.toMap()

    val isIdentity: Boolean get() = toApi.isEmpty()

    /** Yanıttaki ad; kaldırılmışsa null. */
    fun api(name: String): String? = if (name in toApi) toApi[name] else name

    /** İstekteki adın özgün karşılığı. */
    fun canonical(name: String): String = toCanonical[name] ?: name

    fun out(o: JsonObject): JsonObject {
        if (isIdentity) return o
        val out = LinkedHashMap<String, JsonElement>(o.size)
        for ((k, v) in o) api(k)?.let { out[it] = v }
        return JsonObject(out)
    }

    fun inBody(o: JsonObject): JsonObject {
        if (isIdentity) return o
        return JsonObject(o.entries.associate { (k, v) -> canonical(k) to v })
    }

    /** Hata ayrıntılarındaki alan adları (`{"email": "zorunlu alan"}`) yanıt adlarıyla. */
    fun details(d: Map<String, String>): Map<String, String> =
        if (isIdentity) d else d.mapKeys { (k, _) -> api(k) ?: k }

    fun error(e: ApiException): ApiException =
        if (isIdentity || e.details.isEmpty()) e else ApiException(e.status, e.message ?: "", details(e.details))

    /**
     * Sorgu parametreleri: filtre anahtarları (`ad`, `ad[op]`), `sort`, `fields`, `groupBy` ve `sum`/`avg`/`min`/`max`
     * listelerindeki adlar özgün adlara çevrilir.
     */
    fun params(params: List<Pair<String, String>>): List<Pair<String, String>> {
        if (isIdentity) return params
        return params.map { (k, v) ->
            when (k) {
                "sort" -> k to v.split(',').joinToString(",") { part ->
                    val desc = part.trim().startsWith("-")
                    (if (desc) "-" else "") + canonical(part.trim().removePrefix("-").removePrefix("+"))
                }
                "fields", "sum", "avg", "min", "max" -> k to v.split(',').joinToString(",") { canonical(it.trim()) }
                "groupBy" -> k to (canonical(v.substringBefore(':')) + v.substring(v.substringBefore(':').length))
                else -> {
                    val bracket = k.indexOf('[')
                    (if (bracket > 0) canonical(k.substring(0, bracket)) + k.substring(bracket) else canonical(k)) to v
                }
            }
        }
    }

    companion object {
        private val IDENTITY = ApiNames(emptyMap())

        val COLLECTION_FIELDS = listOf("id", "created_at", "updated_at")
        val USER_FIELDS = listOf("email", "password", "verified", "role", "id", "created_at", "updated_at")

        private fun build(map: Map<String, SystemFieldConfig>): ApiNames {
            val changed = map.filter { (_, c) -> c.removed || !c.name.isNullOrBlank() }
            if (changed.isEmpty()) return IDENTITY
            return ApiNames(changed.mapValues { (k, c) -> if (c.removed) null else c.name?.trim()?.takeIf { it.isNotEmpty() } ?: k })
        }

        fun of(collection: CollectionConfig): ApiNames = build(collection.systemFields.filterKeys { it in COLLECTION_FIELDS })

        fun ofUsers(users: UsersConfig): ApiNames = build(users.systemFields.filterKeys { it in USER_FIELDS })

        /** Kaydı ve gömülü `expand` kayıtlarını (her biri kendi koleksiyonunun adlarıyla) çevirir. */
        fun outWithExpand(record: JsonObject, names: ApiNames, targets: (String) -> ApiNames?): JsonObject {
            val expand = record["expand"] as? JsonObject
            val base = names.out(record)
            if (expand == null) return base
            val translated = JsonObject(expand.mapValues { (field, v) ->
                val t = targets(field)
                when {
                    t == null -> v
                    v is JsonObject -> t.out(v)
                    v is JsonArray -> JsonArray(v.map { (it as? JsonObject)?.let(t::out) ?: it })
                    else -> v
                }
            })
            return JsonObject(base + ("expand" to translated))
        }
    }
}
