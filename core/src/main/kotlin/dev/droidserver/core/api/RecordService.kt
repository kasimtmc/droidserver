package dev.droidserver.core.api

import dev.droidserver.core.auth.QuotaService
import dev.droidserver.core.config.CollectionConfig
import dev.droidserver.core.db.PostgresDialect
import dev.droidserver.core.config.FieldConfig
import dev.droidserver.core.config.Expression
import dev.droidserver.core.config.FieldRules
import dev.droidserver.core.config.FieldType
import dev.droidserver.core.config.GeoPoint
import dev.droidserver.core.config.TypeConversions
import dev.droidserver.core.config.FieldVisibility
import dev.droidserver.core.config.OnDelete
import dev.droidserver.core.config.StrictJson
import dev.droidserver.core.config.FileLimits
import dev.droidserver.core.config.SystemCollections
import dev.droidserver.core.db.SqlDriver
import dev.droidserver.core.db.quoteIdent
import dev.droidserver.core.files.FileStore
import dev.droidserver.core.files.Upload
import dev.droidserver.core.i18n.tr
import dev.droidserver.core.security.FieldCipher
import dev.droidserver.core.security.FieldCipherException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.format.DateTimeParseException

class ApiException(val status: Int, message: String, val details: Map<String, String> = emptyMap()) :
    RuntimeException(message)

/** Özet sorgusunda en çok grup sayısı. */
private const val MAX_GROUPS = 1000

data class Page(val items: List<JsonObject>, val page: Int, val perPage: Int, val totalItems: Long)

/**
 * İsteği yapanın kayıtlar üzerindeki kapsamı (koleksiyon kurallarından, bkz. [Access]).
 * Yönetici değilse koleksiyonun sahip alanı ([CollectionConfig.ownerField]) istemciden yazılamaz: yeni kayıtta
 * istekteki kullanıcıyla doldurulur, var olan kayıtta değiştirilemez.
 */
data class Scope(
    val admin: Boolean = true,
    /** İstekteki kullanıcı; anonimse veya yönetici anahtarıysa null. */
    val user: Long? = null,
    /** Yalnızca bu kullanıcıya ait kayıtlar (`owner` kuralı); null: hepsi. */
    val ownedBy: Long? = null,
    /** Kural koşulu ([dev.droidserver.core.config.RuleCondition]): kayıtlar bunların hepsine uymalı. */
    val condition: List<FilterParser.Condition> = emptyList(),
    /** Kaydın sahibi koşula bakılmaksızın erişir. */
    val ownerBypass: Boolean = false,
    /** Koşulun metni (hata iletisi için). */
    val conditionText: String? = null,
    /** Geçmiş ve çöp kutusunda "kim": `user:5`, `key:ad`, `admin`, `anonymous`; null: sunucunun kendisi (script, içe aktarma). */
    val actor: String? = null,
    /** `team` kuralı: kullanıcının takımları; takım alanına yalnızca bunlar yazılabilir. null: kısıt yok. */
    val teams: Set<Long>? = null,
) {
    companion object {
        val ADMIN = Scope()
    }
}

/**
 * Bir koleksiyon için CRUD iş mantığı. HTTP'den bağımsızdır, doğrudan test edilebilir.
 * Tüm SQL parametreli kurulur; tablo/alan adları konfigürasyondan (doğrulanmış) gelir,
 * istemciden gelen adlar yalnızca bilinen alanlarla eşleşirse kullanılır.
 *
 * İlişkiler (`relation` alanları) veritabanı kısıtıyla değil burada uygulanır: yazarken hedef kaydın varlığı aynı
 * işlem içinde denetlenir (PostgreSQL'de hedef satır kilitlenir), silerken bu koleksiyona bağlanan alanların
 * `onDelete` davranışı (cascade / set_null / restrict) tek işlemde uygulanır. Böylece kural değişiklikleri,
 * tip değişiklikleri ve veritabanları arası taşıma tablo yeniden kurmadan ve sıra gözetmeden çalışır.
 */
class RecordService(
    private val db: SqlDriver,
    private val collection: CollectionConfig,
    private val clock: () -> Instant = Instant::now,
    /** Tüm koleksiyonlar: ilişki hedefleri ve bu koleksiyona bağlanan alanlar için. */
    private val collections: List<CollectionConfig> = listOf(collection),
    /** `_users` kayıtları genişletilirken (`expand`) gösterilen ek alanlar; e-posta ve diğerleri gizli kalır. */
    private val publicUserFields: List<String> = emptyList(),
    /** Dosya alanlarının dosyaları; yoksa dosya yüklenemez (kayıtlardaki adlar yine okunur). */
    private val files: FileStore? = null,
    /** Kullanıcı kotaları (yönetici olmayan kullanıcının yeni kaydı ve yüklediği dosyalar). */
    private val quota: QuotaService? = null,
    /** Şifreli alanların anahtarı (`database.fieldKey`); yoksa şifreli alana yazılamaz, okunan değerler boş döner. */
    private val cipher: FieldCipher? = null,
) {
    private val byName = collections.associateBy { it.name } + (collection.name to collection)
    private val dialect = db.dialect
    private val table = quoteIdent(collection.name)
    private val fieldsByName = collection.fields.associateBy { it.name }
    private val sortable = fieldsByName.keys + setOf("id", "created_at", "updated_at")
    private val systemTypes = mapOf("id" to FieldType.INTEGER, "created_at" to FieldType.DATETIME, "updated_at" to FieldType.DATETIME)

    private fun typeOf(name: String): FieldType = systemTypes[name] ?: fieldsByName.getValue(name).type

    /**
     * Sayfalı liste. [params] sorgu parametreleridir (bkz. [FilterParser]: `alan=değer`, `alan[op]=değer`);
     * [fields] null değilse yanıtta yalnızca bu alanlar bulunur (`id` her zaman). [expand] ilişki alanı adından
     * genişletmede uygulanacak sahip kısıtına (null: kısıt yok) eşlenir; anahtarlar genişletilecek alanlardır.
     */
    fun list(
        page: Int,
        perPage: Int,
        sort: String?,
        params: List<Pair<String, String>>,
        fields: List<String>? = null,
        expand: Map<String, Scope?> = emptyMap(),
        scope: Scope = Scope.ADMIN,
    ): Page {
        val expandFields = expandFields(expand.keys, scope)
        if (page < 1) throw ApiException(400, tr("page >= 1 olmalı"))
        if (perPage !in 1..500) throw ApiException(400, tr("perPage 1-500 aralığında olmalı"))

        val (whereSql, args) = whereClause(params, scope)
        // Tam metin aramasında sıralama verilmemişse alakaya göre (eşleşen kelimeler en çok geçen önce).
        val relevanceArgs = mutableListOf<Any?>()
        val relevance = params.lastOrNull { it.first == "q" }?.second?.takeIf { collection.fullText && sort.isNullOrBlank() && it.isNotBlank() }
            ?.let(SearchText::queryTokens)?.takeIf { it.isNotEmpty() }
            ?.let { "ORDER BY ${SearchTerms.relevance("$table.\"id\"", collection.name, it, relevanceArgs)} DESC, $table.\"id\" ASC" }

        // `alan[near]` verilip sıralama verilmemişse yakından uzağa.
        val nearest = FilterParser.parse(params, collection).lastOrNull { it.op == FilterParser.Op.NEAR }?.takeIf { sort.isNullOrBlank() }?.let { c ->
            val column = quoteIdent(c.field)
            "ORDER BY ${GeoPoint.approxSquaredSql(dialect.geoLat(column), dialect.geoLng(column), c.values[0] as Double, c.values[1] as Double)} ASC, $table.\"id\" ASC"
        }
        val orderSql = relevance ?: nearest ?: sort?.takeIf { it.isNotBlank() }?.split(",")?.joinToString(", ") { part ->
            val desc = part.startsWith("-")
            val name = part.removePrefix("-").removePrefix("+").trim()
            if (name !in sortable) throw ApiException(400, tr("Sıralanamayan alan: {0}", name))
            if (typeOf(name) == FieldType.GEO) throw ApiException(400, tr("Konum alanına göre sıralanamaz; yakından uzağa için {0}[near] kullanın", name))
            RuleConditions.requireReadable(collection, name, scope, "sort")
            "${dialect.comparable(typeOf(name), quoteIdent(name))} ${if (desc) "DESC" else "ASC"}"
        }?.let { "ORDER BY $it, \"id\" ASC" } ?: "ORDER BY \"id\" ASC"

        val total = (db.query("SELECT COUNT(*) AS c FROM $table $whereSql", args).first()["c"] as Number).toLong()
        // LIMIT/OFFSET doğrulanmış tam sayılar; bağlama yerine doğrudan yazılır (sürücüler arası tutarlılık).
        val rows = db.query(
            "SELECT ${selectList(fields, expandFields, scope)} FROM $table $whereSql $orderSql LIMIT $perPage OFFSET ${(page - 1) * perPage}",
            args + relevanceArgs,
        )
        return Page(withExpand(rows, fields, expandFields, expand, scope), page, perPage, total)
    }

    /**
     * Özet: `count`, `sum=a,b`, `avg=`, `min=`, `max=` ve isteğe bağlı `groupBy=alan` (zaman alanında `alan:day|month|year`).
     * Filtreler, arama ve sahiplik listedeki gibi uygulanır. Gruplu sonuç anahtara göre sıralı, en çok [MAX_GROUPS] grup.
     */
    fun aggregate(params: List<Pair<String, String>>, scope: Scope = Scope.ADMIN): JsonObject {
        val metricKeys = setOf("sum", "avg", "min", "max")
        val select = mutableListOf("COUNT(*) AS \"count\"")
        val outputs = mutableListOf<Triple<String, String, FieldType>>() // takma ad, işlev, alan tipi
        for ((fn, raw) in params.filter { it.first in metricKeys }) {
            for (name in raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
                if (name !in sortable) throw ApiException(400, tr("{0}: bilinmeyen alan: {1}", fn, name))
                RuleConditions.requireReadable(collection, name, scope, fn)
                val type = typeOf(name)
                val ok = when (fn) {
                    "sum", "avg" -> type == FieldType.INTEGER || type == FieldType.REAL
                    else -> type in setOf(FieldType.INTEGER, FieldType.REAL, FieldType.DATETIME, FieldType.TEXT, FieldType.SELECT)
                }
                if (!ok) throw ApiException(400, tr("{0}: '{1}' alanında kullanılamaz ({2})", fn, name, type.name.lowercase()))
                val alias = "${fn}_$name"
                if (outputs.none { it.first == alias }) {
                    select += "${fn.uppercase()}(${quoteIdent(name)}) AS ${quoteIdent(alias)}"
                    outputs += Triple(alias, fn, type)
                }
            }
        }
        val group = params.lastOrNull { it.first == "groupBy" }?.second?.trim()?.takeIf { it.isNotEmpty() }?.let { g ->
            val name = g.substringBefore(':')
            val unit = g.substringAfter(':', "").ifEmpty { null }
            if (name !in sortable) throw ApiException(400, tr("groupBy: bilinmeyen alan: {0}", name))
            RuleConditions.requireReadable(collection, name, scope, "groupBy")
            val type = typeOf(name)
            when {
                type == FieldType.DATETIME -> {
                    if (unit !in setOf("day", "month", "year")) throw ApiException(400, tr("groupBy: zaman alanında birim gerekli: {0}:day, {1}:month veya {2}:year", name, name, name))
                    dialect.dateBucket(quoteIdent(name), unit!!) to null
                }
                unit != null -> throw ApiException(400, tr("groupBy: birim yalnızca zaman alanlarında"))
                type in setOf(FieldType.TEXT, FieldType.SELECT, FieldType.INTEGER, FieldType.BOOLEAN, FieldType.RELATION, FieldType.REAL) -> quoteIdent(name) to type
                else -> throw ApiException(400, tr("groupBy: '{0}' alanına göre gruplanamaz ({1})", name, type.name.lowercase()))
            }
        }
        val filters = params.filterNot { it.first in metricKeys || it.first == "groupBy" || it.first == "count" }
        val (whereSql, args) = whereClause(filters, scope)

        fun metrics(row: Map<String, Any?>): Map<String, JsonElement> = buildMap {
            put("count", JsonPrimitive((row["count"] as Number).toLong()))
            for ((alias, fn, type) in outputs) {
                val v = row[alias]
                put(alias, when {
                    v == null -> JsonNull
                    fn == "avg" -> JsonPrimitive((v as Number).toDouble())
                    type == FieldType.INTEGER -> JsonPrimitive((v as Number).toLong())
                    type == FieldType.REAL -> JsonPrimitive((v as Number).toDouble())
                    type == FieldType.DATETIME -> JsonPrimitive(dialect.decode(FieldType.DATETIME, v).toString())
                    else -> JsonPrimitive(v.toString())
                })
            }
        }

        if (group == null) {
            val row = db.query("SELECT ${select.joinToString()} FROM $table $whereSql", args).first()
            return JsonObject(metrics(row))
        }
        val (expr, keyType) = group
        val rows = db.query(
            "SELECT $expr AS \"_key\", ${select.joinToString()} FROM $table $whereSql GROUP BY $expr ORDER BY $expr LIMIT ${MAX_GROUPS + 1}",
            args,
        )
        val groups = rows.take(MAX_GROUPS).map { row ->
            val raw = row["_key"]
            val key: JsonElement = when {
                raw == null -> JsonNull
                keyType == null -> JsonPrimitive(raw.toString())
                else -> when (val v = dialect.decode(keyType, raw)) {
                    is Boolean -> JsonPrimitive(v)
                    is Number -> JsonPrimitive(v)
                    else -> JsonPrimitive(v.toString())
                }
            }
            JsonObject(mapOf("key" to key) + metrics(row))
        }
        return buildJsonObject {
            put("groups", JsonArray(groups))
            put("truncated", JsonPrimitive(rows.size > MAX_GROUPS))
        }
    }

    /** Liste ve özet sorgularının ortak WHERE'i: arama (`q`), sahiplik ve filtreler (bkz. [FilterParser]). */
    internal fun whereClause(params: List<Pair<String, String>>, scope: Scope): Pair<String, List<Any?>> {
        val where = mutableListOf<String>()
        val args = mutableListOf<Any?>()
        params.lastOrNull { it.first == "q" }?.second?.takeIf { it.isNotBlank() }?.let { q ->
            if (collection.search.isEmpty()) throw ApiException(400, tr("'{0}' koleksiyonunda arama alanı seçilmemiş (q kullanılamaz)", collection.name))
            if (collection.fullText) {
                where += SearchTerms.filter("$table.\"id\"", collection.name, SearchText.queryTokens(q), args)
                return@let
            }
            for (term in SearchText.terms(q)) {
                where += dialect.likeSql(quoteIdent(SearchText.COLUMN))
                args += "%" + escapeLike(term) + "%"
            }
        }
        scopeSql(scope)?.let { (sql, a) -> where += sql; args.addAll(a) }
        val conditions = FilterParser.parse(params, collection)
        conditions.forEach { RuleConditions.requireReadable(collection, it.field, scope, tr("Filtre")) }
        conditionsSql(conditions)?.let { (sql, a) -> where += sql; args.addAll(a) }
        return (if (where.isEmpty()) "" else "WHERE " + where.joinToString(" AND ")) to args
    }

    /** Kapsamın SQL'i: sahiplik ve kural koşulu (sahip istisnasıyla). null: kısıt yok. */
    internal fun scopeSql(scope: Scope): Pair<String, List<Any?>>? {
        val where = mutableListOf<String>()
        val args = mutableListOf<Any?>()
        scope.ownedBy?.let { owner ->
            where += "${quoteIdent(ownerField())} = ?"
            args += dialect.encode(FieldType.RELATION, owner)
        }
        conditionsSql(scope.condition)?.let { (sql, a) ->
            if (scope.ownerBypass && scope.user != null) {
                where += "($sql OR ${quoteIdent(ownerField())} = ?)"
                args.addAll(a)
                args += dialect.encode(FieldType.RELATION, scope.user)
            } else {
                where += sql
                args.addAll(a)
            }
        }
        return if (where.isEmpty()) null else where.joinToString(" AND ") to args
    }

    /** Filtre koşulları, VE ile birleşmiş ve parantez içinde. null: koşul yok. */
    private fun conditionsSql(conditions: List<FilterParser.Condition>): Pair<String, List<Any?>>? {
        if (conditions.isEmpty()) return null
        val where = mutableListOf<String>()
        val args = mutableListOf<Any?>()
        for (c in conditions) {
            val column = quoteIdent(c.field)
            val col = dialect.comparable(c.type, column)
            val ph = dialect.comparable(c.type, "?")
            // Çoklu seçim JSON dizisi olarak saklanır; eq "bu seçeneği içerir" (seçenekte " ve \ yoktur).
            if (c.type == FieldType.MULTISELECT && c.op == FilterParser.Op.EQ) {
                where += dialect.containsSql(column)
                args += JsonPrimitive(c.values.single() as String).toString()
                continue
            }
            if (c.op == FilterParser.Op.NEAR || c.op == FilterParser.Op.BOX) {
                where += geoSql(c)
                continue
            }
            where += when (c.op) {
                FilterParser.Op.NEAR, FilterParser.Op.BOX -> error("geo")
                FilterParser.Op.EQ -> "$col = $ph"
                FilterParser.Op.NE -> "($column IS NULL OR $col <> $ph)"
                FilterParser.Op.GT -> "$col > $ph"
                FilterParser.Op.GTE -> "$col >= $ph"
                FilterParser.Op.LT -> "$col < $ph"
                FilterParser.Op.LTE -> "$col <= $ph"
                FilterParser.Op.LIKE -> dialect.likeSql(column)
                FilterParser.Op.IN -> "$col IN (${c.values.joinToString { ph }})"
                FilterParser.Op.NULL -> if (c.values.single() == true) "$column IS NULL" else "$column IS NOT NULL"
            }
            when (c.op) {
                FilterParser.Op.NULL -> {}
                FilterParser.Op.LIKE -> args += "%" + escapeLike(c.values.single() as String) + "%"
                else -> c.values.forEach { args += dialect.encode(c.type, it) }
            }
        }
        return "(" + where.joinToString(" AND ") + ")" to args
    }

    /**
     * Konum koşulu; sayılar FilterParser'da doğrulandığından SQL'e doğrudan yazılır. `near`: önce kutu (enlem ve boylam
     * aralığı), sonra [GeoPoint.approxKm] ile aynı yaklaşık uzaklık. 180. boylamı aşan arama iki aralığa bölünür.
     */
    private fun geoSql(c: FilterParser.Condition): String {
        val column = quoteIdent(c.field)
        val lat = dialect.geoLat(column)
        val lng = dialect.geoLng(column)
        val v = c.values.map { it as Double }
        if (c.op == FilterParser.Op.BOX) {
            val (s, w, n, e) = v
            return "($column IS NOT NULL AND $lat BETWEEN ($s) AND ($n) AND $lng BETWEEN ($w) AND ($e))"
        }
        val (lat0, lng0, km) = v
        val dLat = km / GeoPoint.KM_PER_DEGREE
        val south = maxOf(-90.0, lat0 - dLat)
        val north = minOf(90.0, lat0 + dLat)
        // Kutunun kutba en yakın enleminde bir boylam derecesi en kısadır; aralık oraya göre.
        val cosEdge = Math.cos(Math.toRadians(minOf(90.0, maxOf(kotlin.math.abs(south), kotlin.math.abs(north)))))
        val dLng = if (cosEdge < 1e-9) 360.0 else dLat / cosEdge
        val lngSql = when {
            dLng >= 180 -> null
            lng0 - dLng < -180 -> "($lng >= (${lng0 - dLng + 360}) OR $lng <= (${lng0 + dLng}))"
            lng0 + dLng > 180 -> "($lng >= (${lng0 - dLng}) OR $lng <= (${lng0 + dLng - 360}))"
            else -> "$lng BETWEEN (${lng0 - dLng}) AND (${lng0 + dLng})"
        }
        val radius = km / GeoPoint.KM_PER_DEGREE
        return "(" + listOfNotNull(
            "$column IS NOT NULL",
            "$lat BETWEEN ($south) AND ($north)",
            lngSql,
            "${GeoPoint.approxSquaredSql(lat, lng, lat0, lng0)} <= (${radius * radius})",
        ).joinToString(" AND ") + ")"
    }

    /** Başkasına ait kayıt ([Scope.ownedBy]) yokmuş gibi 404 döner. */
    fun get(id: Long, fields: List<String>? = null, expand: Map<String, Scope?> = emptyMap(), scope: Scope = Scope.ADMIN): JsonObject {
        val expandFields = expandFields(expand.keys, scope)
        val (cond, args) = idCondition(id, scope)
        val rows = db.query("SELECT ${selectList(fields, expandFields, scope)} FROM $table WHERE $cond", args)
        if (rows.isEmpty()) throw ApiException(404, tr("Kayıt bulunamadı"))
        return withExpand(rows, fields, expandFields, expand, scope).single()
    }

    /**
     * Kayıt [scope] ile görülebiliyorsa gizli alanları dahil tamamı (script olayları, webhook'lar ve canlı abonelik
     * için; istemciye [present] ile gönderilir), değilse 404.
     */
    fun current(id: Long, scope: Scope): JsonObject {
        val (cond, args) = idCondition(id, scope)
        if (db.query("SELECT \"id\" FROM $table WHERE $cond", args).isEmpty()) throw ApiException(404, tr("Kayıt bulunamadı"))
        return get(id)
    }

    /** İstemciye giden kayıt: gizli alanlar çıkarılmış (bkz. [RuleConditions.present]). */
    fun present(record: JsonObject, scope: Scope): JsonObject = RuleConditions.present(collection, record, scope)

    private fun idCondition(id: Long, scope: Scope): Pair<String, List<Any?>> {
        val (sql, args) = scopeSql(scope) ?: return "\"id\" = ?" to listOf(id)
        return "\"id\" = ? AND $sql" to listOf<Any?>(id) + args
    }

    private fun ownerField(): String = collection.ownerField ?: error(tr("'{0}' koleksiyonunun sahip alanı yok", collection.name))

    /** Okunacak sütunlar; genişletilecek ilişki alanları seçilmemiş olsa da okunur. */
    /** Sahibine görünen alanlar için sahip sütunu da okunur (seçilmemiş olsa da). */
    private fun selectList(fields: List<String>?, expand: List<FieldConfig>, scope: Scope): String {
        fields ?: return "*"
        val owner = collection.ownerField?.takeIf { !scope.admin && collection.fields.any { it.visibility == FieldVisibility.OWNER } }
        return (fields + expand.map { f -> f.name } + listOfNotNull(owner)).distinct().joinToString { quoteIdent(it) }
    }

    /** `expand=author,category`: bu koleksiyonun ilişki alanları olmalı (tek seviye). */
    private fun expandFields(names: Collection<String>, scope: Scope): List<FieldConfig> = names.distinct().map { name ->
        val f = fieldsByName[name] ?: throw ApiException(400, tr("expand: bilinmeyen alan: {0}", name))
        if (f.type != FieldType.RELATION) throw ApiException(400, tr("expand: {0} bir ilişki alanı değil", name))
        RuleConditions.requireReadable(collection, name, scope, "expand")
        f
    }

    /**
     * Kayıtları JSON'a çevirir; [expand] alanlarının hedef kayıtları `expand` nesnesine gömülür. Her ilişki alanı
     * için tek sorgu (`IN (...)`, 500'lük parçalar): sorgu sayısı kayıt sayısına bağlı değildir. Hedefte olmayan
     * kimlik (veya sahip kısıtına uymayan kayıt) null olur.
     */
    private fun withExpand(
        rows: List<Map<String, Any?>>,
        fields: List<String>?,
        expand: List<FieldConfig>,
        scopes: Map<String, Scope?>,
        scope: Scope,
    ): List<JsonObject> {
        val related = expand.associate { f ->
            val ids = rows.mapNotNull { (it[f.name] as? Number)?.toLong() }.distinct()
            f.name to relatedRecords(f, ids, scopes[f.name])
        }
        val ownerColumn = collection.ownerField
        return rows.map { row ->
            val base = toJson(row, fields)
            val full = if (expand.isEmpty()) base else JsonObject(
                base + ("expand" to buildJsonObject {
                    for (f in expand) put(f.name, (row[f.name] as? Number)?.toLong()?.let { related.getValue(f.name)[it] } ?: JsonNull)
                }),
            )
            val owner = ownerColumn?.let { (dialect.decode(FieldType.RELATION, row[it]) as? Number)?.toLong() }
            RuleConditions.present(collection, full, scope, owner)
        }
    }

    /** [scope] hedef koleksiyonun görüntüleme kapsamı (null: kısıt yok); kısıta uymayan kayıt gömülmez. */
    private fun relatedRecords(f: FieldConfig, ids: List<Long>, scope: Scope?): Map<Long, JsonObject> {
        if (ids.isEmpty()) return emptyMap()
        val target = byName.getValue(f.collection!!)
        val svc = if (target.name == collection.name) this else RecordService(db, target, clock, collections, publicUserFields, files, cipher = cipher)
        // Kullanıcı kayıtlarından yalnızca herkese görünen alanlar.
        val visible = if (target.name == SystemCollections.USERS) listOf("id") + publicUserFields else null
        val (scopeSql, scopeArgs) = scope?.let { svc.scopeSql(it) }?.let { (sql, a) -> " AND $sql" to a } ?: ("" to emptyList())
        return ids.chunked(500).flatMap { chunk ->
            db.query("SELECT * FROM ${quoteIdent(target.name)} WHERE \"id\" IN (${chunk.joinToString { "?" }})$scopeSql", chunk + scopeArgs)
        }.associate { row ->
            val json = svc.toJson(row, visible)
            (row["id"] as Number).toLong() to if (scope == null) json else RuleConditions.present(target, json, scope)
        }
    }

    /** `like` değerindeki `%`, `_` ve `\` harfiyen aranır. */
    private fun escapeLike(s: String) = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    // İlişki hedeflerinin varlık kontrolü ile yazma aynı işlemde: kontrol ile yazma arasında hedef silinemez.
    // Yüklenen dosyalar kayıt yazıldıktan sonra, aynı işlem içinde yerine taşınır.
    fun create(body: JsonObject, scope: Scope = Scope.ADMIN, uploads: List<Upload> = emptyList()): JsonObject = db.transaction {
        requireStore(uploads)
        quotaUser(scope)?.let { u ->
            quota!!.checkRecords(collection, u)
            quota.checkStorage(u, uploads.sumOf { it.size })
        }
        val owned = teamBody(ownedBody(body, scope, current = null), scope, partial = false)
        val errors = adminOnlyErrors(owned, scope)
        val fileValues = planFiles(owned, uploads, emptyMap(), partial = false, errors).first
        val values = validate(owned, partial = false, errors)
        fileValues.forEach { (f, names) -> values[f.name] = encodeFiles(f, names) }
        applyComputed(values, emptyMap())
        val now = dialect.encode(FieldType.DATETIME, clock())
        val search = if (collection.search.isEmpty()) emptyMap() else mapOf(SearchText.COLUMN to SearchText.of(collection) { values[it] })
        val cols = values.keys.toList() + search.keys + listOf("created_at", "updated_at")
        val args = values.values.toList() + search.values + listOf(now, now)
        val id = wrapConstraint { db.insert(dialect.insertReturningId(collection.name, cols), args) }
        if (collection.fullText) SearchTerms.index(db, collection.name, id, search[SearchText.COLUMN], clock())
        checkCalendar(id)
        // Koşul yazılan kayıtta denetlenir; uymazsa işlem geri alınır.
        conditionsSql(scope.condition)?.let { (sql, a) ->
            if (db.query("SELECT \"id\" FROM $table WHERE \"id\" = ? AND $sql", listOf<Any?>(id) + a).isEmpty()) {
                throw ApiException(403, tr("Kayıt oluşturma kuralının koşuluna uymuyor ({0})", scope.conditionText))
            }
        }
        uploads.forEach { files!!.commit(it, collection.name, id) }
        get(id).also { if (collection.history != null) logHistory(id, "create", snapshot(id), scope.actor) }
    }

    /** Kotaya tabi kullanıcı: yönetici olmayan, giriş yapmış kullanıcı (kota açıksa). */
    private fun quotaUser(scope: Scope): Long? = scope.user?.takeIf { !scope.admin && quota?.active == true }

    private val computedFields = collection.fields.filter { it.computed != null }.map { it to Expression.parse(it.computed!!) }

    /**
     * Hesaplanmış alanları [values]'a yazar (veritabanı biçiminde). Girdiler mevcut satır ([current]) ile yeni değerlerin
     * birleşimidir; alanlar tanım sırasıyla hesaplanır (önceki hesaplanan alana başvurulabilir).
     */
    private fun applyComputed(values: LinkedHashMap<String, Any?>, current: Map<String, Any?>) {
        if (computedFields.isEmpty()) return
        val merged = HashMap(current).apply { putAll(values) }
        fun canonical(name: String): Any? {
            val raw = merged[name] ?: return null
            val type = systemTypes[name] ?: fieldsByName[name]?.type ?: return null
            return when (val v = dialect.decode(type, raw)) {
                is Instant -> v.toString()
                else -> v
            }
        }
        for ((f, e) in computedFields) {
            val inputs = e.references.associateWith(::canonical)
            val result = runCatching { FieldRules.computedValue(f, e.evaluate(inputs)) }.getOrNull()
            val encoded = dialect.encode(f.type, result)
            values[f.name] = encoded
            merged[f.name] = encoded
        }
    }

    /** Yönetici olmayanlar yalnızca yöneticiye görünen alanları yazamaz. */
    private fun adminOnlyErrors(body: JsonObject, scope: Scope): LinkedHashMap<String, String> {
        val errors = linkedMapOf<String, String>()
        if (scope.admin) return errors
        collection.fields.filter { it.visibility == FieldVisibility.ADMIN && it.name in body }.forEach { errors[it.name] = tr("yalnızca yönetici yazabilir") }
        return errors
    }

    /**
     * Dosya alanı gövdede `null` (dosyaları kaldır) veya mevcut adlardan tutulacaklar olarak gönderilebilir; yeni
     * dosyalar [uploads] ile gelir. Kaldırılan dosyalar işlem tamamlanınca silinir; [removedFiles] verilirse silinmez,
     * adları oraya eklenir (dış işlem geri alınabilecekse çağıran, işlem kalıcı olunca siler).
     */
    fun update(id: Long, body: JsonObject, scope: Scope = Scope.ADMIN, uploads: List<Upload> = emptyList(), removedFiles: MutableList<String>? = null): JsonObject =
        update(id, body, scope, uploads, historyAction = "update", removedFiles)

    private fun update(id: Long, body: JsonObject, scope: Scope, uploads: List<Upload>, historyAction: String, removedFiles: MutableList<String>? = null): JsonObject {
        requireStore(uploads)
        if (uploads.isNotEmpty()) quotaUser(scope)?.let { quota!!.checkStorage(it, uploads.sumOf { u -> u.size }) }
        val removed = db.transaction {
            val owner = collection.ownerField?.takeIf { !scope.admin && it in body }
            val touchedFiles = fileFields.filter { f -> f.name in body || uploads.any { it.field == f.name } }
            val readCols = listOfNotNull(owner) + touchedFiles.map { it.name }
            val row = if (readCols.isEmpty()) null else {
                val (cond, args) = idCondition(id, scope)
                db.query("SELECT ${readCols.joinToString { quoteIdent(it) }} FROM $table WHERE $cond", args).firstOrNull()
                    ?: throw ApiException(404, tr("Kayıt bulunamadı"))
            }
            val current = owner?.let { o -> (dialect.decode(FieldType.RELATION, row!![o]) as? Number)?.toLong() }
            val owned = teamBody(ownedBody(body, scope, current, partial = true), scope, partial = true)
            val errors = adminOnlyErrors(owned, scope)
            val currentFiles = touchedFiles.associate { it.name to fileNames(row!![it.name]) }
            val (fileValues, removed) = planFiles(owned, uploads, currentFiles, partial = true, errors)
            val values = validate(owned, partial = true, errors)
            fileValues.forEach { (f, names) -> values[f.name] = encodeFiles(f, names) }
            if (values.isEmpty()) {
                get(id, scope = scope) // görünmüyorsa 404
                return@transaction emptyList()
            }
            if (computedFields.isNotEmpty()) {
                val (cond, condArgs) = idCondition(id, scope)
                val current = db.query("SELECT * FROM $table WHERE $cond", condArgs).firstOrNull() ?: throw ApiException(404, tr("Kayıt bulunamadı"))
                applyComputed(values, current)
            }
            val sets = values.keys.map { "${quoteIdent(it)} = ?" } + "\"updated_at\" = ?"
            val (cond, condArgs) = idCondition(id, scope)
            val args = values.values.toList() + dialect.encode(FieldType.DATETIME, clock()) + condArgs
            val n = wrapConstraint { db.execute("UPDATE $table SET ${sets.joinToString()} WHERE $cond", args) }
            if (n == 0) throw ApiException(404, tr("Kayıt bulunamadı"))
            if (collection.calendar?.let { cal -> listOfNotNull(cal.start, cal.end, cal.resource).any { it in values } } == true) checkCalendar(id)
            if (collection.search.any { it in values }) refreshSearch(id)
            uploads.forEach { files!!.commit(it, collection.name, id) }
            if (collection.history != null) logHistory(id, historyAction, snapshot(id), scope.actor)
            removed
        }
        if (removedFiles != null) removedFiles += removed else removed.forEach { files?.delete(collection.name, id, it) }
        // Tamamı: güncellenen kayıt artık koşula uymasa da (örn. taslak yayınlandı) yanıt döner; istemciye [present] ile.
        return get(id)
    }

    /**
     * Takvim kuralları, yazılan kaydın son halinde (işlem içinde; uymazsa geri alınır): bitiş başlangıçtan önce olamaz;
     * çakışma kuralı açıksa aynı kaynakta (boşsa boş kaynaklılarda) zamanı üst üste binen başka kayıt olamaz (uç uca
     * eklenen olur). PostgreSQL'de eşzamanlı iki yazma koleksiyon kilidiyle sıraya girer.
     */
    private fun checkCalendar(id: Long) {
        val cal = collection.calendar ?: return
        val endField = cal.end ?: return
        val start = quoteIdent(cal.start)
        val end = quoteIdent(endField)
        val resource = cal.resource?.let(::quoteIdent)
        val row = db.query("SELECT $start AS \"s\", $end AS \"e\"${resource?.let { ", $it AS \"r\"" }.orEmpty()} FROM $table WHERE \"id\" = ?", listOf(id)).firstOrNull() ?: return
        val s = dialect.decode(FieldType.DATETIME, row["s"]) as? Instant ?: return
        val e = dialect.decode(FieldType.DATETIME, row["e"]) as? Instant ?: return
        if (e < s) throw ApiException(400, tr("Doğrulama hatası"), mapOf(endField to tr("bitiş başlangıçtan önce olamaz")))
        if (!cal.noOverlap) return
        if (dialect == PostgresDialect) db.query("SELECT pg_advisory_xact_lock(hashtext(?)) AS \"l\"", listOf("droidserver.calendar." + collection.name))
        val cmp = { x: String -> dialect.comparable(FieldType.DATETIME, x) }
        val args = mutableListOf<Any?>(id, dialect.encode(FieldType.DATETIME, e), dialect.encode(FieldType.DATETIME, s))
        val same = resource?.let { r -> if (row["r"] == null) " AND $r IS NULL" else " AND $r = ?".also { args += row["r"] } }.orEmpty()
        val other = db.query(
            "SELECT \"id\" FROM $table WHERE \"id\" <> ? AND ${cmp(start)} < ${cmp("?")} AND ${cmp(end)} > ${cmp("?")}$same LIMIT 1",
            args,
        ).firstOrNull()?.get("id") ?: return
        throw ApiException(409, tr("Bu zaman aralığı #{0} kaydıyla çakışıyor", other), mapOf(cal.start to tr("#{0} ile çakışıyor", other)))
    }

    /** Güncellemeden sonra arama sütunu, kaydın son halinden (gönderilmeyen alanlar dahil). */
    private fun refreshSearch(id: Long) {
        val row = db.query("SELECT ${collection.search.joinToString { quoteIdent(it) }} FROM $table WHERE \"id\" = ?", listOf(id)).firstOrNull() ?: return
        val text = SearchText.of(collection) { row[it] }
        db.execute("UPDATE $table SET ${quoteIdent(SearchText.COLUMN)} = ? WHERE \"id\" = ?", listOf(text, id))
        if (collection.fullText) SearchTerms.index(db, collection.name, id, text, clock())
    }

    /**
     * `team` kuralında takım alanına yalnızca kullanıcının takımları yazılır; yeni kayıtta boşsa ve kullanıcının tek
     * takımı varsa o takım yazılır.
     */
    private fun teamBody(body: JsonObject, scope: Scope, partial: Boolean): JsonObject {
        val field = collection.teamField ?: return body
        val teams = scope.teams?.takeIf { !scope.admin } ?: return body
        val sent = body[field]
        if (sent == null || sent is JsonNull) {
            if (partial && sent == null) return body
            if (partial) throw ApiException(403, tr("{0}: kayıt bir takıma ait olmalı", field))
            val only = teams.singleOrNull() ?: throw ApiException(
                400, if (teams.isEmpty()) tr("{0}: kayıt eklemek için önce bir takıma katılın", field) else tr("{0}: takım seçin (üyesi olduğunuz takımlardan biri)", field),
            )
            return JsonObject(body + (field to JsonPrimitive(only)))
        }
        val id = (sent as? JsonPrimitive)?.let { it.longOrNull ?: it.content.toLongOrNull() }
        if (id == null || id !in teams) throw ApiException(403, tr("{0}: yalnızca üyesi olduğunuz takıma yazabilirsiniz", field))
        return body
    }

    /**
     * Yönetici olmayan isteklerde sahip alanı istemciden alınmaz: yeni kayıtta istekteki kullanıcı yazılır (anonimse boş),
     * güncellemede alan yalnızca mevcut değeriyle ([current]) gönderilebilir.
     */
    private fun ownedBody(body: JsonObject, scope: Scope, current: Long?, partial: Boolean = false): JsonObject {
        val owner = collection.ownerField ?: return body
        if (scope.admin) return body
        val sent = body[owner]
        val changes = when (sent) {
            null -> false
            is JsonNull -> partial && current != null
            else -> (sent as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull != if (partial) current else scope.user
        }
        if (changes) throw ApiException(403, tr("{0} alanı kaydın sahibidir; istemci tarafından değiştirilemez", owner))
        if (partial) return JsonObject(body - owner)
        return JsonObject(body + (owner to (scope.user?.let(::JsonPrimitive) ?: JsonNull)))
    }

    /**
     * Kaydı ve `onDelete` davranışına göre ona bağlı kayıtları tek işlemde siler; restrict ihlalinde hiçbir şey silinmez.
     * Başkasına ait kayıt ([Scope.ownedBy]) yokmuş gibi 404 döner.
     */
    fun delete(id: Long, scope: Scope = Scope.ADMIN) {
        val deleted = db.transaction {
            val (cond, args) = idCondition(id, scope)
            if (db.query("SELECT \"id\" FROM $table WHERE $cond", args).isEmpty()) throw ApiException(404, tr("Kayıt bulunamadı"))
            mutableSetOf<Pair<String, Long>>().also { deleteRecord(collection.name, id, it, scope.actor) }
        }
        // Silinen kayıtların (cascade dahil) dosyaları, işlem tamamlandıktan sonra; çöp kutusundakiler süre dolunca.
        val store = files ?: return
        for ((t, recordId) in deleted) {
            val c = byName[t] ?: continue
            if (c.trash == null && c.fields.any { it.type == FieldType.FILE }) store.deleteRecord(t, recordId)
        }
    }

    private fun deleteRecord(table: String, id: Long, deleting: MutableSet<Pair<String, Long>>, actor: String?) {
        if (!deleting.add(table to id)) return // döngüsel cascade: bu kayıt zaten siliniyor
        for ((c, f) in referencing(table)) {
            val t = quoteIdent(c.name)
            val col = quoteIdent(f.name)
            when (f.effectiveOnDelete) {
                OnDelete.RESTRICT -> {
                    val blocking = db.query("SELECT \"id\" FROM $t WHERE $col = ? LIMIT 1001", listOf(id))
                        .map { (it["id"] as Number).toLong() }.filter { (c.name to it) !in deleting }
                    if (blocking.isNotEmpty()) {
                        val n = if (blocking.size > 1000) tr("1000'den fazla") else blocking.size.toString()
                        throw ApiException(409, tr("Silinemez: {0}.{1} alanında bu kayda bağlı {2} kayıt var", c.name, f.name, n))
                    }
                }
                OnDelete.SET_NULL -> db.execute(
                    "UPDATE $t SET $col = NULL, \"updated_at\" = ? WHERE $col = ?",
                    listOf(dialect.encode(FieldType.DATETIME, clock()), id),
                )
                OnDelete.CASCADE -> db.query("SELECT \"id\" FROM $t WHERE $col = ?", listOf(id))
                    .forEach { deleteRecord(c.name, (it["id"] as Number).toLong(), deleting, actor) }
            }
        }
        byName[table]?.takeIf { it.history != null || it.trash != null }?.let { peer(it.name).archiveDeleted(id, actor) }
        if (byName[table]?.fullText == true) SearchTerms.delete(db, table, id)
        db.execute("DELETE FROM ${quoteIdent(table)} WHERE \"id\" = ?", listOf(id))
    }

    /** [table] koleksiyonuna bağlanan ilişki alanları. */
    private fun referencing(table: String): List<Pair<CollectionConfig, FieldConfig>> = byName.values.flatMap { c ->
        c.fields.filter { it.type == FieldType.RELATION && it.collection == table }.map { c to it }
    }

    private fun exists(table: String, id: Long, lock: Boolean): Boolean = db.query(
        "SELECT \"id\" FROM ${quoteIdent(table)} WHERE \"id\" = ?" + if (lock) dialect.lockForShare() else "",
        listOf(id),
    ).isNotEmpty()

    // ---- dosyalar ----

    private val fileFields = collection.fields.filter { it.type == FieldType.FILE }

    private fun requireStore(uploads: List<Upload>) {
        if (uploads.isNotEmpty() && files == null) throw ApiException(503, tr("Dosya deposu yok; dosya yüklenemez"))
    }

    /** Kayıttaki adlar (tekli alanda ad, çokluda JSON dizi saklanır). */
    private fun fileNames(raw: Any?): List<String> {
        val s = dialect.decode(FieldType.FILE, raw) as String? ?: return emptyList()
        if (!s.startsWith("[")) return listOf(s)
        return runCatching { Json.parseToJsonElement(s).jsonArray.map { it.jsonPrimitive.content } }.getOrDefault(emptyList())
    }

    private fun encodeFiles(f: FieldConfig, names: List<String>): Any? = when {
        names.isEmpty() -> null
        f.isMultiple -> JsonArray(names.map(::JsonPrimitive)).toString()
        else -> names.single()
    }

    /**
     * Dosya alanlarının yeni değerleri ve silinecek eski adlar. Tekli alanda yeni dosya eskisinin yerine geçer, çoklu
     * alanda tutulanlara eklenir. Gövdedeki ad yalnızca kayıtta zaten olan bir ad olabilir (başka kaydın dosyasına
     * bağlanılamaz).
     */
    private fun planFiles(
        body: JsonObject,
        uploads: List<Upload>,
        current: Map<String, List<String>>,
        partial: Boolean,
        errors: MutableMap<String, String>,
    ): Pair<Map<FieldConfig, List<String>>, List<String>> {
        uploads.map { it.field }.distinct().filter { name -> fileFields.none { it.name == name } }.forEach { errors[it] = tr("dosya alanı değil") }
        val out = linkedMapOf<FieldConfig, List<String>>()
        val removed = mutableListOf<String>()
        for (f in fileFields) {
            val incoming = uploads.filter { it.field == f.name }.map { it.storedName }
            if (partial && f.name !in body && incoming.isEmpty()) continue
            val cur = current[f.name].orEmpty()
            val kept: List<String> = when (val v = body[f.name]) {
                null -> cur
                is JsonNull -> emptyList()
                is JsonPrimitive -> if (v.isString) listOf(v.content) else { errors[f.name] = tr("dosya adı bekleniyor"); continue }
                is JsonArray -> v.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: "" }
                else -> { errors[f.name] = tr("dosya adı bekleniyor"); continue }
            }
            kept.firstOrNull { it !in cur }?.let {
                errors[f.name] = if (partial) tr("'{0}' bu kayıtta yok", it) else tr("yeni kayıtta dosyalar multipart/form-data ile yüklenir")
                continue
            }
            val names = when {
                f.isMultiple -> (kept + incoming).distinct()
                incoming.size > 1 -> { errors[f.name] = tr("bu alan tek dosya alır"); continue }
                else -> incoming.ifEmpty { kept }
            }
            if (!f.isMultiple && names.size > 1) { errors[f.name] = tr("bu alan tek dosya alır"); continue }
            if (names.size > FileLimits.MAX_FILES) { errors[f.name] = tr("en çok {0} dosya", FileLimits.MAX_FILES); continue }
            if (f.required && names.isEmpty()) { errors[f.name] = tr("zorunlu alan"); continue }
            out[f] = names
            removed += cur - names.toSet()
        }
        return out to removed
    }

    // ---- doğrulama ve dönüşüm ----

    private fun validate(
        body: JsonObject,
        partial: Boolean,
        errors: MutableMap<String, String> = linkedMapOf(),
    ): LinkedHashMap<String, Any?> {
        val out = linkedMapOf<String, Any?>()

        for (key in body.keys) {
            if (key !in fieldsByName && key !in setOf("id", "created_at", "updated_at")) errors[key] = tr("bilinmeyen alan")
        }
        for (f in collection.fields) {
            if (f.type == FieldType.FILE) continue // bkz. planFiles
            if (f.computed != null) {
                if (f.name in body) errors[f.name] = tr("hesaplanan alan; yazılamaz ({0})", f.computed)
                continue
            }
            val present = f.name in body
            val value: JsonElement? = when {
                present -> body[f.name]
                !partial -> f.default
                else -> null
            }
            if (!present && partial) continue
            if (value == null || value is JsonNull) {
                if (f.required) errors[f.name] = tr("zorunlu alan")
                else out[f.name] = null
                continue
            }
            try {
                val sql = toSql(f, value)
                if (sql == null && f.required) errors[f.name] = tr("zorunlu alan (en az bir seçim)") else out[f.name] = sql
            } catch (e: IllegalArgumentException) {
                errors[f.name] = e.message ?: tr("geçersiz değer")
            }
        }
        if (errors.isNotEmpty()) throw ApiException(400, tr("Doğrulama hatası"), errors)
        return out
    }

    /** JSON değeri doğrular, ortak biçime (bkz. SqlDialect) ve oradan veritabanı değerine çevirir. */
    private fun toSql(f: FieldConfig, v: JsonElement): Any? {
        require(StrictJson.isValid(v)) { tr("geçerli JSON değil; metinleri tırnak içinde yazın") }
        fun prim() = v as? JsonPrimitive ?: throw IllegalArgumentException(tr("{0} bekleniyor", f.type.name.lowercase()))
        val value: Any = when (f.type) {
            FieldType.TEXT -> prim().also { require(it.isString) { tr("metin bekleniyor") } }.content
            FieldType.INTEGER -> prim().longOrNull ?: throw IllegalArgumentException(tr("tam sayı bekleniyor"))
            FieldType.REAL -> prim().doubleOrNull ?: throw IllegalArgumentException(tr("sayı bekleniyor"))
            FieldType.SELECT -> prim().also { require(it.isString) { tr("seçeneklerden biri (metin) bekleniyor") } }.content
            FieldType.MULTISELECT -> {
                val list = (v as? JsonArray ?: throw IllegalArgumentException(tr("seçenek listesi bekleniyor, örn. [\"a\", \"b\"]")))
                    .map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw IllegalArgumentException(tr("seçenekler metin olmalı")) }
                FieldRules.check(f, list)?.let { throw IllegalArgumentException(it) }
                // Boş seçim null saklanır (null filtresi çalışsın diye).
                return if (list.isEmpty()) null else dialect.encode(f.type, TypeConversions.multiText(list))
            }
            FieldType.BOOLEAN -> prim().booleanOrNull ?: throw IllegalArgumentException(tr("true/false bekleniyor"))
            FieldType.DATETIME -> {
                val s = prim().also { require(it.isString) { tr("ISO-8601 metin bekleniyor") } }.content
                try { Instant.parse(s) } catch (_: DateTimeParseException) {
                    throw IllegalArgumentException(tr("ISO-8601 zaman bekleniyor, örn. 2026-10-13T10:00:00Z"))
                }
            }
            FieldType.JSON -> v.toString()
            FieldType.GEO -> GeoPoint.of(v).text()
            FieldType.FILE -> throw IllegalArgumentException(tr("dosyalar multipart/form-data ile yüklenir"))
            FieldType.RELATION -> {
                val id = prim().takeIf { !it.isString }?.longOrNull?.takeIf { it > 0 }
                    ?: throw IllegalArgumentException(tr("kayıt kimliği (pozitif tam sayı) bekleniyor"))
                val target = f.collection!!
                require(exists(target, id, lock = true)) { tr("'{0}' koleksiyonunda {1} kimlikli kayıt yok", target, id) }
                id
            }
        }
        FieldRules.check(f, value)?.let { throw IllegalArgumentException(it) }
        return dialect.encode(f.type, seal(f, value))
    }

    /** Şifreli alanın değeri veritabanına şifreli gider; JSON sütununda JSON metni olarak. */
    private fun seal(f: FieldConfig, value: Any): Any {
        if (!f.encrypted) return value
        val c = cipher ?: throw ApiException(500, tr("Alan şifreleme anahtarı yok; konfigürasyonu sihirbazdan yeniden kaydedin"))
        val sealed = c.encrypt(value.toString())
        return if (f.type == FieldType.JSON) JsonPrimitive(sealed).toString() else sealed
    }

    /** Şifreli değeri açar; şifresiz (eski) değer olduğu gibi, açılamayan (başka anahtar) değer boş döner. */
    private fun open(f: FieldConfig, v: JsonElement): JsonElement {
        val text = (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return v
        if (!FieldCipher.isEncrypted(text)) return v
        val plain = try {
            cipher?.decrypt(text)
        } catch (_: FieldCipherException) {
            null
        } ?: return JsonNull
        return if (f.type == FieldType.JSON) runCatching { Json.parseToJsonElement(plain) }.getOrElse { JsonPrimitive(plain) } else JsonPrimitive(plain)
    }

    /** Geçmiş ve çöp kutusundaki (şifreli saklanan) verinin açılmış hali. */
    private fun opened(data: JsonObject): JsonObject = if (collection.fields.none { it.encrypted }) data else
        JsonObject(data.mapValues { (k, v) -> fieldsByName[k]?.takeIf { it.encrypted }?.let { open(it, v) } ?: v })

    /** [decrypt] false: şifreli alanlar şifreli kalır (geçmiş ve çöp kutusuna yazılan anlık görüntü). */
    internal fun toJson(row: Map<String, Any?>, fields: List<String>? = null, decrypt: Boolean = true): JsonObject = buildJsonObject {
        fun wanted(name: String) = fields == null || name in fields
        put("id", JsonPrimitive((row["id"] as Number).toLong()))
        for (f in collection.fields) {
            if (!wanted(f.name)) continue
            if (f.type == FieldType.FILE) {
                val names = fileNames(row[f.name])
                put(f.name, if (f.isMultiple) JsonArray(names.map(::JsonPrimitive)) else names.firstOrNull()?.let(::JsonPrimitive) ?: JsonNull)
                continue
            }
            if (f.type == FieldType.GEO) {
                put(f.name, GeoPoint.json(dialect.decode(f.type, row[f.name]) as String?))
                continue
            }
            if (f.type == FieldType.MULTISELECT) {
                val text = dialect.decode(f.type, row[f.name]) as String?
                put(f.name, JsonArray(text?.let(TypeConversions::multiValues).orEmpty().map(::JsonPrimitive)))
                continue
            }
            val value = when (val v = dialect.decode(f.type, row[f.name])) {
                null -> JsonNull
                is Boolean -> JsonPrimitive(v)
                is Number -> JsonPrimitive(v)
                else -> if (f.type == FieldType.JSON) {
                    runCatching { Json.parseToJsonElement(v.toString()) }.getOrElse { JsonPrimitive(v.toString()) }
                } else JsonPrimitive(v.toString())
            }
            put(f.name, if (f.encrypted && decrypt) open(f, value) else value)
        }
        if (wanted("created_at")) put("created_at", JsonPrimitive(dialect.decode(FieldType.DATETIME, row["created_at"]).toString()))
        if (wanted("updated_at")) put("updated_at", JsonPrimitive(dialect.decode(FieldType.DATETIME, row["updated_at"]).toString()))
    }

    // ---- geçmiş ve çöp kutusu ----

    private val historyTable = quoteIdent(SystemCollections.HISTORY)
    private val trashTable = quoteIdent(SystemCollections.TRASH)
    private val peers = HashMap<String, RecordService>()

    /** Cascade ile silinen başka koleksiyondaki kayıt için o koleksiyonun servisi. */
    private fun peer(name: String): RecordService = if (name == collection.name) this else
        peers.getOrPut(name) { RecordService(db, byName.getValue(name), clock, collections, publicUserFields, files, cipher = cipher) }

    /** Kaydın tam hali (gizli alanlar dahil; geçmiş ve çöp kutusu yalnızca yöneticiye açıktır). */
    private fun snapshot(id: Long): JsonObject? =
        db.query("SELECT * FROM $table WHERE \"id\" = ?", listOf(id)).firstOrNull()?.let { toJson(it, decrypt = false) }

    /** Geçmişe yeni sürüm ekler; kayıt başına en çok `history.keep` sürüm kalır. */
    private fun logHistory(id: Long, action: String, data: JsonObject?, actor: String?) {
        val keep = collection.history?.keep ?: return
        val last = db.query(
            "SELECT MAX(\"version\") AS v FROM $historyTable WHERE \"collection\" = ? AND \"record\" = ?",
            listOf(collection.name, id),
        ).first()["v"]?.let { (it as Number).toLong() } ?: 0L
        val now = dialect.encode(FieldType.DATETIME, clock())
        db.insert(
            dialect.insertReturningId(SystemCollections.HISTORY, listOf("collection", "record", "version", "action", "data", "actor", "created_at", "updated_at")),
            listOf(collection.name, id, last + 1, action, data?.let { dialect.encode(FieldType.JSON, it.toString()) }, actor, now, now),
        )
        db.execute(
            "DELETE FROM $historyTable WHERE \"collection\" = ? AND \"record\" = ? AND \"version\" <= ?",
            listOf(collection.name, id, last + 1 - keep),
        )
    }

    /** Silmeden hemen önce (aynı işlemde): geçmişe "delete", çöp kutusu açıksa kaydın hali oraya. */
    private fun archiveDeleted(id: Long, actor: String?) {
        val data = snapshot(id) ?: return
        logHistory(id, "delete", data, actor)
        val days = collection.trash?.days ?: return
        val now = clock()
        db.execute("DELETE FROM $trashTable WHERE \"collection\" = ? AND \"record\" = ?", listOf(collection.name, id))
        db.insert(
            dialect.insertReturningId(SystemCollections.TRASH, listOf("collection", "record", "data", "actor", "expires_at", "created_at", "updated_at")),
            listOf(
                collection.name, id, dialect.encode(FieldType.JSON, data.toString()), actor,
                dialect.encode(FieldType.DATETIME, now.plusSeconds(days * 86_400L)),
                dialect.encode(FieldType.DATETIME, now), dialect.encode(FieldType.DATETIME, now),
            ),
        )
    }

    private fun parseObject(raw: Any?): JsonObject =
        (dialect.decode(FieldType.JSON, raw) as String?)?.let { StrictJson.parse(it) as? JsonObject } ?: JsonObject(emptyMap())

    /** Geçmişteki veya çöp kutusundaki halden yazılabilen alanlar (dosya ve hesaplanan alanlar hariç). */
    private fun writable(data: JsonObject): JsonObject =
        JsonObject(data.filterKeys { k -> fieldsByName[k]?.let { it.type != FieldType.FILE && it.computed == null } == true })

    private fun page(page: Int, perPage: Int): Pair<Int, Int> {
        if (page < 1) throw ApiException(400, tr("page >= 1 olmalı"))
        if (perPage !in 1..500) throw ApiException(400, tr("perPage 1-500 aralığında olmalı"))
        return perPage to (page - 1) * perPage
    }

    /** Kaydın sürümleri, yeniden eskiye: `{items:[{version, action, at, actor, data}], totalItems}`. */
    fun history(id: Long, page: Int = 1, perPage: Int = 50): JsonObject {
        if (collection.history == null) throw ApiException(404, tr("'{0}' koleksiyonunda geçmiş kapalı", collection.name))
        val (limit, offset) = page(page, perPage)
        val args = listOf<Any?>(collection.name, id)
        val where = "WHERE \"collection\" = ? AND \"record\" = ?"
        val total = (db.query("SELECT COUNT(*) AS c FROM $historyTable $where", args).first()["c"] as Number).toLong()
        val rows = db.query("SELECT * FROM $historyTable $where ORDER BY \"version\" DESC LIMIT $limit OFFSET $offset", args)
        return buildJsonObject {
            put("items", JsonArray(rows.map { r ->
                buildJsonObject {
                    put("version", JsonPrimitive((r["version"] as Number).toLong()))
                    put("action", JsonPrimitive(r["action"].toString()))
                    put("at", JsonPrimitive(dialect.decode(FieldType.DATETIME, r["created_at"]).toString()))
                    put("actor", r["actor"]?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                    put("data", if (r["data"] == null) JsonNull else opened(parseObject(r["data"])))
                }
            }))
            put("totalItems", JsonPrimitive(total))
        }
    }

    /**
     * Kaydı geçmişteki bir sürümün değerlerine döndürür (yeni bir sürüm olarak; dosya ve hesaplanan alanlar hariç).
     * [fields] verilirse yalnızca o alanlar (sürümde olmayan alan boşalır). Silinmiş kayıt önce çöp kutusundan geri
     * yüklenmeli.
     */
    fun revert(id: Long, version: Long, scope: Scope = Scope.ADMIN, fields: Set<String>? = null): JsonObject {
        fields?.firstOrNull { f -> fieldsByName[f]?.let { it.type == FieldType.FILE || it.computed != null } != false }?.let {
            throw ApiException(400, tr("'{0}' geri alınabilir bir alan değil", it))
        }
        if (collection.history == null) throw ApiException(404, tr("'{0}' koleksiyonunda geçmiş kapalı", collection.name))
        val row = db.query(
            "SELECT \"data\" FROM $historyTable WHERE \"collection\" = ? AND \"record\" = ? AND \"version\" = ?",
            listOf(collection.name, id, version),
        ).firstOrNull() ?: throw ApiException(404, tr("Bu kaydın {0}. sürümü yok", version))
        if (!exists(collection.name, id, lock = false)) {
            throw ApiException(404, tr("Kayıt silinmiş") + if (collection.trash != null) tr("; önce çöp kutusundan geri yükleyin") else "")
        }
        val data = writable(opened(parseObject(row["data"])))
        val values = if (fields == null) data else JsonObject(fields.associateWith { data[it] ?: JsonNull })
        return update(id, values, scope, emptyList(), historyAction = "revert")
    }

    /** Çöp kutusu, son silinen önce: `{items:[{id, deletedAt, expiresAt, actor, data}], totalItems}`. */
    fun trash(page: Int = 1, perPage: Int = 50): JsonObject {
        if (collection.trash == null) throw ApiException(404, tr("'{0}' koleksiyonunda çöp kutusu kapalı", collection.name))
        val (limit, offset) = page(page, perPage)
        val args = listOf<Any?>(collection.name)
        val total = (db.query("SELECT COUNT(*) AS c FROM $trashTable WHERE \"collection\" = ?", args).first()["c"] as Number).toLong()
        val rows = db.query(
            "SELECT * FROM $trashTable WHERE \"collection\" = ? ORDER BY \"id\" DESC LIMIT $limit OFFSET $offset", args,
        )
        return buildJsonObject {
            put("items", JsonArray(rows.map { r ->
                buildJsonObject {
                    put("id", JsonPrimitive((r["record"] as Number).toLong()))
                    put("deletedAt", JsonPrimitive(dialect.decode(FieldType.DATETIME, r["created_at"]).toString()))
                    put("expiresAt", JsonPrimitive(dialect.decode(FieldType.DATETIME, r["expires_at"]).toString()))
                    put("actor", r["actor"]?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                    put("data", opened(parseObject(r["data"])))
                }
            }))
            put("totalItems", JsonPrimitive(total))
        }
    }

    /**
     * Çöp kutusundaki kaydı aynı kimlikle geri yükler: değerler bugünkü kurallarla doğrulanır (ilişki hedefi silinmişse,
     * yeni zorunlu alan varsa 400), dosyaları yerinde durur, hesaplanan alanlar yeniden hesaplanır.
     */
    fun restore(id: Long, scope: Scope = Scope.ADMIN): JsonObject = db.transaction {
        if (collection.trash == null) throw ApiException(404, tr("'{0}' koleksiyonunda çöp kutusu kapalı", collection.name))
        val row = db.query("SELECT \"data\" FROM $trashTable WHERE \"collection\" = ? AND \"record\" = ?", listOf(collection.name, id))
            .firstOrNull() ?: throw ApiException(404, tr("Çöp kutusunda {0} kimlikli kayıt yok", id))
        if (exists(collection.name, id, lock = false)) throw ApiException(409, tr("{0} kimlikli kayıt zaten var", id))
        val data = opened(parseObject(row["data"]))
        val values = validate(writable(data), partial = false)
        for (f in fileFields) {
            val names = when (val v = data[f.name]) {
                is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.content }
                is JsonPrimitive -> listOfNotNull(v.takeIf { it.isString }?.content)
                else -> emptyList()
            }
            values[f.name] = encodeFiles(f, names)
        }
        applyComputed(values, emptyMap())
        val created = (data["created_at"] as? JsonPrimitive)?.content?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: clock()
        val search = if (collection.search.isEmpty()) emptyMap() else mapOf(SearchText.COLUMN to SearchText.of(collection) { values[it] })
        val cols = listOf("id") + values.keys + search.keys + listOf("created_at", "updated_at")
        val args = listOf<Any?>(id) + values.values + search.values +
            listOf(dialect.encode(FieldType.DATETIME, created), dialect.encode(FieldType.DATETIME, clock()))
        wrapConstraint { db.execute(dialect.insertWithIdSql(collection.name, cols), args) }
        if (collection.fullText) SearchTerms.index(db, collection.name, id, search[SearchText.COLUMN], clock())
        db.execute("DELETE FROM $trashTable WHERE \"collection\" = ? AND \"record\" = ?", listOf(collection.name, id))
        if (collection.history != null) logHistory(id, "restore", snapshot(id), scope.actor)
        get(id)
    }

    /** Saklama süresi ([CollectionConfig.retention]) dolan kayıtların kimlikleri, en eskiden; en çok [limit]. */
    fun expiredIds(now: Instant, limit: Int): List<Long> {
        val r = collection.retention ?: return emptyList()
        val field = r.field ?: "created_at"
        val cutoff = now.minusSeconds(r.days * 86_400L)
        val args = mutableListOf<Any?>(dialect.encode(FieldType.DATETIME, cutoff))
        var where = "${dialect.comparable(FieldType.DATETIME, quoteIdent(field))} < ${dialect.comparable(FieldType.DATETIME, "?")}"
        r.filter?.let { RuleConditions.parse(collection, it, null) }?.let(::conditionsSql)?.let { (sql, a) ->
            where += " AND $sql"
            args.addAll(a)
        }
        return db.query("SELECT \"id\" FROM $table WHERE $where ORDER BY \"id\" LIMIT $limit", args).map { (it["id"] as Number).toLong() }
    }

    /** Çöp kutusundaki kaydı dosyalarıyla kalıcı olarak siler. */
    fun purge(id: Long) {
        if (collection.trash == null) throw ApiException(404, tr("'{0}' koleksiyonunda çöp kutusu kapalı", collection.name))
        val n = db.execute("DELETE FROM $trashTable WHERE \"collection\" = ? AND \"record\" = ?", listOf(collection.name, id))
        if (n == 0) throw ApiException(404, tr("Çöp kutusunda {0} kimlikli kayıt yok", id))
        if (fileFields.isNotEmpty() && !exists(collection.name, id, lock = false)) files?.deleteRecord(collection.name, id)
    }

    companion object {
        /** Süresi dolan çöp kutusu kayıtlarını (dosyalarıyla) siler; silinen sayı. Tablo yoksa 0. */
        fun purgeExpired(db: SqlDriver, files: FileStore?, now: Instant): Int {
            if (SystemCollections.TRASH !in db.dialect.listTables(db)) return 0
            val t = quoteIdent(SystemCollections.TRASH)
            val cond = "${db.dialect.comparable(FieldType.DATETIME, "\"expires_at\"")} < ${db.dialect.comparable(FieldType.DATETIME, "?")}"
            val arg = listOf(db.dialect.encode(FieldType.DATETIME, now))
            val expired = db.query("SELECT \"collection\", \"record\" FROM $t WHERE $cond", arg)
            if (expired.isEmpty()) return 0
            db.execute("DELETE FROM $t WHERE $cond", arg)
            for (r in expired) files?.deleteRecord(r["collection"].toString(), (r["record"] as Number).toLong())
            return expired.size
        }
    }

    private inline fun <T> wrapConstraint(block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        if (dialect.isUniqueViolation(e)) throw ApiException(409, tr("Benzersiz alan çakışması"))
        throw e
    }
}

internal fun Page.toJson(): JsonObject = buildJsonObject {
    put("page", JsonPrimitive(page))
    put("perPage", JsonPrimitive(perPage))
    put("totalItems", JsonPrimitive(totalItems))
    put("totalPages", JsonPrimitive(if (totalItems == 0L) 0 else ((totalItems + perPage - 1) / perPage)))
    put("items", JsonArray(items))
}
