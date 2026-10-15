package dev.droidserver.core.api

import dev.droidserver.core.config.CollectionConfig
import dev.droidserver.core.config.FieldType
import dev.droidserver.core.config.FieldVisibility
import dev.droidserver.core.config.SystemCollections
import dev.droidserver.core.i18n.tr
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant

/**
 * Kural koşulları ([dev.droidserver.core.config.RuleCondition]) ve alan gizliliği. Koşul liste filtresi sözdizimidir
 * (`status=published&qty[gt]=0`); API'de SQL'e çevrilir ([RecordService]), canlı abonelikte kaydın JSON'u üzerinde
 * burada değerlendirilir. İki yol aynı sonucu versin diye `like` (harf duyarsızlığı veritabanına göre değişir) yoktur.
 */
object RuleConditions {
    /** İstekteki kullanıcı; anonim istekte hiçbir kayda eşit olmayan 0. */
    const val ME = "@me"

    /**
     * [filter]'ı ayrıştırır; `@me` [user] ile değiştirilir. Hatalı koşul [ApiException] (400) fırlatır; konfigürasyon
     * doğrulaması aynı ayrıştırmayı kullanır.
     */
    fun parse(collection: CollectionConfig, filter: String, user: Long?): List<FilterParser.Condition> {
        val pairs = filter.split('&').map { it.trim() }.filter { it.isNotEmpty() }.map { part ->
            val eq = part.indexOf('=')
            if (eq <= 0) throw ApiException(400, tr("Koşul alan=değer biçiminde olmalı: '{0}'", part))
            part.substring(0, eq).trim() to part.substring(eq + 1).trim()
        }
        if (pairs.isEmpty()) throw ApiException(400, tr("Koşul boş"))
        val fields = collection.fields.associateBy { it.name }
        val n = ApiNames.of(collection)
        val resolved = pairs.map { (rawKey, value) ->
            // Sistem alanının API adı (uzman modu) özgün ada çevrilir.
            val key = n.canonical(rawKey.substringBefore('[')) + rawKey.substring(rawKey.substringBefore('[').length)
            val name = key.substringBefore('[')
            if (name in FilterParser.RESERVED || (name !in fields && name !in setOf("id", "created_at", "updated_at"))) {
                throw ApiException(400, tr("Koşulda bilinmeyen alan: {0}", name))
            }
            if (key.endsWith("[like]")) throw ApiException(400, tr("Koşulda like kullanılamaz; eq, ne, in, null, gt, gte, lt, lte kullanın"))
            if (fields[name]?.encrypted == true) throw ApiException(400, tr("Koşulda şifreli alan kullanılamaz: {0}", name))
            if (ME !in value) return@map key to value
            val f = fields[name]
            if (f?.type != FieldType.RELATION || f.collection != SystemCollections.USERS) {
                throw ApiException(400, tr("{0} yalnızca kullanıcılara bağlı ilişki alanlarında kullanılır ({1})", ME, name))
            }
            key to value.split(',').joinToString(",") { v -> if (v.trim() == ME) (user ?: 0L).toString() else v }
        }
        return FilterParser.parse(resolved, collection)
    }

    /** Kayıt (API'nin döndürdüğü JSON) koşulların hepsine uyuyor mu. */
    fun matches(conditions: List<FilterParser.Condition>, record: JsonObject): Boolean = conditions.all { c ->
        val v = value(c.type, record[c.field])
        if (c.type == FieldType.MULTISELECT && c.op == FilterParser.Op.EQ) {
            return@all (v as? JsonArray)?.any { (it as? JsonPrimitive)?.content == c.values.single() } == true
        }
        when (c.op) {
            FilterParser.Op.EQ -> v != null && same(v, c.values.single())
            FilterParser.Op.NE -> v == null || !same(v, c.values.single())
            FilterParser.Op.IN -> v != null && c.values.any { same(v, it) }
            FilterParser.Op.NULL -> (v == null) == c.values.single()
            FilterParser.Op.GT -> v != null && compare(v, c.values.single()) > 0
            FilterParser.Op.GTE -> v != null && compare(v, c.values.single()) >= 0
            FilterParser.Op.LT -> v != null && compare(v, c.values.single()) < 0
            FilterParser.Op.LTE -> v != null && compare(v, c.values.single()) <= 0
            FilterParser.Op.LIKE -> false
        }
    }

    /** Kayıt [scope] ile görülebilir mi: sahiplik ve koşul (yönetici her zaman). */
    fun visible(collection: CollectionConfig, record: JsonObject, scope: Scope): Boolean {
        if (scope.admin) return true
        val owner = collection.ownerField?.let { (record[it] as? JsonPrimitive)?.longOrNull }
        if (scope.ownedBy != null && owner != scope.ownedBy) return false
        if (scope.condition.isEmpty()) return true
        if (scope.ownerBypass && scope.user != null && owner == scope.user) return true
        return matches(scope.condition, record)
    }

    /**
     * Gizli alanları ([FieldVisibility]) çıkarır. [owner] kaydın sahibi; verilmezse kayıttaki sahip alanından okunur.
     * Gizli ilişki alanının `expand` girdisi de çıkarılır.
     */
    fun present(collection: CollectionConfig, record: JsonObject, scope: Scope, owner: Long? = null): JsonObject {
        if (scope.admin) return record
        val restricted = collection.fields.filter { it.visibility != null }
        if (restricted.isEmpty()) return record
        val recordOwner = owner ?: collection.ownerField?.let { (record[it] as? JsonPrimitive)?.longOrNull }
        val isOwner = scope.user != null && recordOwner == scope.user
        val hidden = restricted.filter { it.visibility == FieldVisibility.ADMIN || !isOwner }.map { it.name }.toSet()
        if (hidden.isEmpty()) return record
        val out = record.filterKeys { it !in hidden }.toMutableMap()
        (record["expand"] as? JsonObject)?.let { e -> out["expand"] = JsonObject(e.filterKeys { it !in hidden }) }
        return JsonObject(out)
    }

    /**
     * Yönetici olmayan istek gizli alana göre filtreleyemez, sıralayamaz, özetleyemez, genişletemez (yoksa değer
     * sorgu sonucundan çıkarılabilir). İstisna: sahibine görünen alan, istek yalnızca kendi kayıtlarını kapsıyorsa.
     */
    fun requireReadable(collection: CollectionConfig, name: String, scope: Scope, what: String) {
        val field = collection.fields.firstOrNull { it.name == name }
        // Şifreli değer veritabanında karşılaştırılamaz (yönetici için de).
        if (field?.encrypted == true) throw ApiException(400, tr("{0}: '{1}' alanı şifreli; filtre, sıralama ve özette kullanılamaz", what, name))
        if (scope.admin) return
        val v = field?.visibility ?: return
        if (v == FieldVisibility.OWNER && scope.ownedBy != null) return
        throw ApiException(400, tr("{0}: '{1}' alanı gizli ({2})", what, name, v.title.lowercase()))
    }

    private fun value(type: FieldType, e: JsonElement?): Any? {
        if (e == null || e is JsonNull) return null
        if (e is JsonArray) return e.takeIf { it.isNotEmpty() } // çoklu dosya alanı: yalnızca null işleci
        val p = e as? JsonPrimitive ?: return null
        return when (type) {
            FieldType.INTEGER, FieldType.RELATION -> p.longOrNull
            FieldType.REAL -> p.doubleOrNull
            FieldType.BOOLEAN -> p.booleanOrNull
            FieldType.DATETIME -> runCatching { Instant.parse(p.content) }.getOrNull()
            else -> p.content
        }
    }

    private fun same(a: Any, b: Any): Boolean = if (a is Number && b is Number) compareNumbers(a, b) == 0 else a == b

    private fun compare(a: Any, b: Any): Int = when {
        a is Number && b is Number -> compareNumbers(a, b)
        a is Instant && b is Instant -> a.compareTo(b)
        else -> a.toString().compareTo(b.toString())
    }

    private fun compareNumbers(a: Number, b: Number): Int =
        if (a is Long && b is Long) a.compareTo(b) else a.toDouble().compareTo(b.toDouble())
}
