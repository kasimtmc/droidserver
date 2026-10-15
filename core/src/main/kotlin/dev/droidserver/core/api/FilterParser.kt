package dev.droidserver.core.api

import dev.droidserver.core.config.CollectionConfig
import dev.droidserver.core.config.FieldType
import dev.droidserver.core.i18n.tr
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Liste sorgusu parametreleri: `alan=değer` (eşit) veya `alan[op]=değer`.
 *
 * | op | anlamı | alan tipleri |
 * |---|---|---|
 * | `eq`, `ne` | eşit / eşit değil (boş değerler `ne`'ye dahil) | json hariç hepsi |
 * | `gt`, `gte`, `lt`, `lte` | büyük / küçük | integer, real, datetime, id, created_at, updated_at |
 * | `like` | içerir (büyük/küçük harf duyarsız) | text |
 * | `in` | virgülle ayrılmış değerlerden biri (en çok 100) | text, integer, real, datetime, relation, id |
 * | `null` | `true`: boş, `false`: dolu | id dışında hepsi |
 *
 * Seçim (select) alanları metin gibi `eq`, `ne`, `in`, `null` kullanır. Çoklu seçimde (multiselect) `eq` "bu seçeneği
 * içerir" demektir; yalnızca `eq` ve `null` kullanılır.
 *
 * JSON alanlarına göre filtrelenemez; dosya alanlarında yalnızca `null` (dosya var mı) kullanılır. Değerler alan tipine göre ayrıştırılır; uymayan değer 400 döner.
 * Köşeli parantezsiz bilinmeyen parametreler yok sayılır (istemcilerin kendi parametreleri olabilir), köşeli
 * parantezli olanlar 400 döner.
 */
object FilterParser {
    enum class Op(val key: String) {
        EQ("eq"), NE("ne"), GT("gt"), GTE("gte"), LT("lt"), LTE("lte"), LIKE("like"), IN("in"), NULL("null");

        companion object {
            fun of(key: String): Op? = entries.firstOrNull { it.key == key }
        }
    }

    /**
     * Ayrıştırılmış koşul. [values] ortak biçimdedir (bkz. SqlDialect): Long, Double, Boolean, Instant veya String;
     * [Op.NULL] için tek Boolean (true: boş olmalı).
     */
    data class Condition(val field: String, val type: FieldType, val op: Op, val values: List<Any>)

    /** Filtre olarak yorumlanmayan liste parametreleri. */
    val RESERVED = setOf("page", "perPage", "sort", "fields", "expand", "q")

    const val MAX_IN = 100

    private val KEY = Regex("^([A-Za-z_][A-Za-z0-9_]*)\\[([A-Za-z]+)\\]$")
    private val ORDERED = setOf(Op.GT, Op.GTE, Op.LT, Op.LTE)

    /** Sistem alanları ve tipleri; `id` boş olamaz. */
    private val SYSTEM = mapOf("id" to FieldType.INTEGER, "created_at" to FieldType.DATETIME, "updated_at" to FieldType.DATETIME)

    fun parse(params: List<Pair<String, String>>, collection: CollectionConfig): List<Condition> {
        val types = SYSTEM + collection.fields.associate { it.name to it.type }
        return params.filter { it.first !in RESERVED }.mapNotNull { (key, raw) ->
            val m = KEY.matchEntire(key)
            val name = m?.groupValues?.get(1) ?: key
            val type = types[name] ?: if (m == null) return@mapNotNull null else throw ApiException(400, tr("Bilinmeyen alan: {0}", name))
            val op = if (m == null) Op.EQ else Op.of(m.groupValues[2])
                ?: throw ApiException(400, tr("Bilinmeyen operatör: {0} (geçerli: {1})", m.groupValues[2], Op.entries.joinToString { it.key }))
            check(name, type, op)
            Condition(name, type, op, values(name, type, op, raw))
        }
    }

    private fun check(name: String, type: FieldType, op: Op) {
        fun no(why: String): Nothing = throw ApiException(400, tr("{0}[{1}] kullanılamaz: {2}", name, op.key, why))
        when {
            type == FieldType.JSON -> no(tr("json alanlarına göre filtrelenemez"))
            type == FieldType.FILE && op != Op.NULL -> no(tr("dosya alanlarında yalnızca null (dosya var mı) kullanılır"))
            op == Op.NULL && name == "id" -> no(tr("id hiçbir zaman boş değildir"))
            op == Op.LIKE && type != FieldType.TEXT -> no(tr("like yalnızca metin alanlarında kullanılır"))
            op in ORDERED && type !in setOf(FieldType.INTEGER, FieldType.REAL, FieldType.DATETIME) ->
                no(tr("büyük/küçük karşılaştırması yalnızca sayı ve zaman alanlarında yapılır"))
            op == Op.IN && type == FieldType.BOOLEAN -> no(tr("true/false için eq kullanın"))
            type == FieldType.MULTISELECT && op != Op.EQ && op != Op.NULL -> no(tr("çoklu seçimde yalnızca eq (içerir) ve null kullanılır"))
        }
    }

    private fun values(name: String, type: FieldType, op: Op, raw: String): List<Any> = when (op) {
        Op.NULL -> listOf(raw.toBooleanStrictOrNull() ?: throw ApiException(400, tr("{0}[null] true veya false olmalı", name)))
        Op.LIKE -> listOf(raw.also { if (it.isEmpty()) throw ApiException(400, tr("{0}[like] boş olamaz", name)) })
        Op.IN -> raw.split(',').also {
            if (raw.isEmpty()) throw ApiException(400, tr("{0}[in] en az bir değer içermeli", name))
            if (it.size > MAX_IN) throw ApiException(400, tr("{0}[in] en çok {1} değer alabilir", name, MAX_IN))
        }.map { value(name, type, it) }
        else -> listOf(value(name, type, raw))
    }

    private fun value(name: String, type: FieldType, raw: String): Any = when (type) {
        FieldType.TEXT, FieldType.SELECT, FieldType.MULTISELECT -> raw
        FieldType.INTEGER, FieldType.RELATION -> raw.trim().toLongOrNull() ?: throw ApiException(400, tr("{0} tam sayı olmalı", name))
        FieldType.REAL -> raw.trim().toDoubleOrNull()?.takeIf { it.isFinite() } ?: throw ApiException(400, tr("{0} sayı olmalı", name))
        FieldType.BOOLEAN -> raw.trim().toBooleanStrictOrNull() ?: throw ApiException(400, tr("{0} true veya false olmalı", name))
        FieldType.DATETIME -> try {
            Instant.parse(raw.trim())
        } catch (_: DateTimeParseException) {
            throw ApiException(400, tr("{0} ISO-8601 zaman olmalı, örn. 2026-10-13T10:00:00Z", name))
        }
        FieldType.JSON -> throw ApiException(400, tr("json alanlarına göre filtrelenemez: {0}", name))
        FieldType.FILE -> throw ApiException(400, tr("dosya alanlarında yalnızca null kullanılır: {0}", name))
    }

    /**
     * `fields=title,done`: yanıtta yalnızca bu alanlar (ve her zaman `id`). null: tüm alanlar.
     * Bilinmeyen alan 400 döner.
     */
    fun parseFields(raw: String?, collection: CollectionConfig): List<String>? {
        if (raw.isNullOrBlank()) return null
        val known = SYSTEM.keys + collection.fields.map { it.name }
        val names = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        names.firstOrNull { it !in known }?.let { throw ApiException(400, tr("Bilinmeyen alan: {0}", it)) }
        return (listOf("id") + names).distinct()
    }
}
