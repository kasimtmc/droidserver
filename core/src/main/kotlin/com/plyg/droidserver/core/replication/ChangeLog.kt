package com.plyg.droidserver.core.replication

import com.plyg.droidserver.core.config.CollectionConfig
import com.plyg.droidserver.core.config.ConfigJson
import com.plyg.droidserver.core.config.FieldType
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.config.SystemCollections
import com.plyg.droidserver.core.db.PostgresDialect
import com.plyg.droidserver.core.db.SqlDialect
import com.plyg.droidserver.core.db.SqlDriver
import com.plyg.droidserver.core.db.quoteIdent
import com.plyg.droidserver.core.i18n.tr
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Canlı kopyanın ana sunucu tarafı. Kopyalanan her tabloya tetikleyici kurulur: ekleme/güncelleme/silme (içe aktarma,
 * hesap işlemleri, script'ler ve ilişki zinciriyle silinenler dahil) `_changes`'e sıra numarasıyla yazılır. Kopya
 * [changes] ile o sıradan sonrasını, her kaydın güncel haliyle alır; ilk kurulumda [snapshot] ile tabloları sayfa sayfa
 * kopyalar.
 */
object ChangeLog {
    private const val PREFIX = "_ds_rep_"
    private val SKIP = setOf(SystemCollections.SESSIONS, SystemCollections.JOBS, SystemCollections.CHANGES)
    private val changes = quoteIdent(SystemCollections.CHANGES)

    /** Kopyalanan tablolar, ilişki hedefleri önce (kopyada yabancı anahtar sırası). Oturumlar ve iş kuyruğu yerel kalır. */
    fun tables(config: ServerConfig): List<CollectionConfig> {
        val all = config.schemaCollections().filter { it.name !in SKIP }
        val byName = all.associateBy { it.name }
        val ordered = LinkedHashSet<String>()
        fun visit(c: CollectionConfig, path: Set<String>) {
            if (c.name in ordered || c.name in path) return
            c.fields.filter { it.type == FieldType.RELATION }.mapNotNull { byName[it.collection] }.forEach { visit(it, path + c.name) }
            ordered += c.name
        }
        all.forEach { visit(it, emptySet()) }
        return ordered.map(byName::getValue)
    }

    /** Tetikleyicileri (yeniden) kurar; önceki kurulumdan kalanlar silinir. */
    fun install(db: SqlDriver, tables: List<String>) {
        uninstall(db)
        val pg = db.dialect == PostgresDialect
        if (pg) {
            db.execute(
                """CREATE OR REPLACE FUNCTION "${PREFIX}log"() RETURNS trigger AS ${'$'}${'$'}
                BEGIN
                  IF TG_OP = 'DELETE' THEN
                    INSERT INTO $changes ("collection", "record", "action", "created_at", "updated_at") VALUES (TG_TABLE_NAME, OLD."id", 'delete', now(), now());
                    RETURN OLD;
                  END IF;
                  INSERT INTO $changes ("collection", "record", "action", "created_at", "updated_at") VALUES (TG_TABLE_NAME, NEW."id", 'upsert', now(), now());
                  RETURN NEW;
                END ${'$'}${'$'} LANGUAGE plpgsql""".trimIndent(),
            )
        }
        for (t in tables) {
            val table = quoteIdent(t)
            if (pg) {
                db.execute("CREATE OR REPLACE TRIGGER ${quoteIdent(PREFIX + t)} AFTER INSERT OR UPDATE OR DELETE ON $table FOR EACH ROW EXECUTE FUNCTION \"${PREFIX}log\"()")
            } else {
                val now = "strftime('%Y-%m-%dT%H:%M:%fZ', 'now')"
                fun trigger(suffix: String, event: String, ref: String, action: String) = db.execute(
                    "CREATE TRIGGER ${quoteIdent(PREFIX + t + suffix)} AFTER $event ON $table BEGIN " +
                        "INSERT INTO $changes (\"collection\", \"record\", \"action\", \"created_at\", \"updated_at\") VALUES ('$t', $ref.\"id\", '$action', $now, $now); END",
                )
                trigger("_i", "INSERT", "NEW", "upsert")
                trigger("_u", "UPDATE", "NEW", "upsert")
                trigger("_d", "DELETE", "OLD", "delete")
            }
        }
    }

    /** Bu sunucunun kurduğu tüm tetikleyicileri kaldırır (kopyalanabilirlik kapatılınca). */
    fun uninstall(db: SqlDriver) {
        if (db.dialect == PostgresDialect) {
            db.query("SELECT t.tgname AS name, c.relname AS tbl FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid WHERE left(t.tgname, ${PREFIX.length}) = '$PREFIX'")
                .forEach { db.execute("DROP TRIGGER IF EXISTS ${quoteIdent(it["name"].toString())} ON ${quoteIdent(it["tbl"].toString())}") }
        } else {
            db.query("SELECT name FROM sqlite_master WHERE type = 'trigger' AND substr(name, 1, ${PREFIX.length}) = '$PREFIX'")
                .forEach { db.execute("DROP TRIGGER IF EXISTS ${quoteIdent(it["name"].toString())}") }
        }
    }

    /** Son sıra numarası (günlük boşsa 0). */
    fun latest(db: SqlDriver): Long = (db.query("SELECT MAX(\"id\") AS n FROM $changes").first()["n"] as Number?)?.toLong() ?: 0

    /** [keepDays] günden eski kayıtları siler (son kayıt hep kalır: sıra numarası geri gitmez). */
    fun trim(db: SqlDriver, keepDays: Int, now: Instant = Instant.now()): Int {
        val last = latest(db)
        val d = db.dialect
        return db.execute(
            "DELETE FROM $changes WHERE \"id\" < ? AND ${d.comparable(FieldType.DATETIME, "\"created_at\"")} < ${d.comparable(FieldType.DATETIME, "?")}",
            listOf(last, d.encode(FieldType.DATETIME, now - Duration.ofDays(keepDays.toLong()))),
        )
    }

    /**
     * [after]'dan sonraki değişiklikler (en çok [limit] günlük satırı): kayıt başına tek giriş, ilk görüldüğü sırada,
     * güncel haliyle (kayıt artık yoksa `delete`). `reset`: istenen sıra günlükten silinmiş, kopya baştan eşitlenmeli.
     */
    fun changes(db: SqlDriver, config: ServerConfig, after: Long, limit: Int = 500): JsonObject {
        val latest = latest(db)
        val min = (db.query("SELECT MIN(\"id\") AS n FROM $changes").first()["n"] as Number?)?.toLong()
        if (after > 0 && min != null && after < min - 1) {
            return buildJsonObject { put("latest", JsonPrimitive(latest)); put("next", JsonPrimitive(after)); put("reset", JsonPrimitive(true)); put("changes", JsonArray(emptyList())) }
        }
        val rows = db.query("SELECT \"id\", \"collection\", \"record\", \"action\" FROM $changes WHERE \"id\" > ? ORDER BY \"id\" LIMIT ?", listOf(after, limit.coerceIn(1, 5000)))
        val next = rows.lastOrNull()?.let { (it["id"] as Number).toLong() } ?: after
        // Kayıt başına ilk görülme sırası (ilişki hedefi önce yazılır), veri güncel hal.
        val touched = LinkedHashMap<Pair<String, Long>, Long>()
        for (r in rows) touched.putIfAbsent(r["collection"].toString() to (r["record"] as Number).toLong(), (r["id"] as Number).toLong())
        val tables = tables(config).associateBy { it.name }
        val current = touched.keys.groupBy({ it.first }, { it.second }).mapValues { (t, ids) ->
            val c = tables[t] ?: return@mapValues emptyMap()
            ids.chunked(500).flatMap { chunk ->
                db.query("SELECT * FROM ${quoteIdent(t)} WHERE \"id\" IN (${chunk.joinToString(",") { "?" }})", chunk)
            }.associateBy { (it["id"] as Number).toLong() }.mapValues { (_, row) -> RowCodec.encode(db.dialect, c, row) }
        }
        val list = touched.map { (key, seq) ->
            val (t, id) = key
            val row = current[t]?.get(id)
            buildJsonObject {
                put("seq", JsonPrimitive(seq))
                put("table", JsonPrimitive(t))
                put("id", JsonPrimitive(id))
                put("action", JsonPrimitive(if (row == null) "delete" else "upsert"))
                row?.let { put("row", it) }
            }
        }
        return buildJsonObject {
            put("latest", JsonPrimitive(latest))
            put("next", JsonPrimitive(next))
            put("reset", JsonPrimitive(false))
            put("changes", JsonArray(list))
        }
    }

    /** Tablonun [afterId]'den sonraki satırları (kimlik sırasıyla). */
    fun snapshot(db: SqlDriver, config: ServerConfig, table: String, afterId: Long, limit: Int = 500): JsonObject {
        val c = tables(config).firstOrNull { it.name == table } ?: throw IllegalArgumentException(tr("Kopyalanmayan tablo: {0}", table))
        val n = limit.coerceIn(1, 5000)
        val rows = db.query("SELECT * FROM ${quoteIdent(table)} WHERE \"id\" > ? ORDER BY \"id\" LIMIT ?", listOf(afterId, n))
        return buildJsonObject {
            put("rows", JsonArray(rows.map { RowCodec.encode(db.dialect, c, it) }))
            put("done", JsonPrimitive(rows.size < n))
        }
    }

    /**
     * Kopyaya aktarılan konfigürasyon parçasının özeti: sunucunun kendine özgü ayarları (port, veritabanı, erişim,
     * bileşenler, yedek, uyarılar, güvenlik duvarı, kopya ayarları) hariç. Özet değişince kopya yeniden kurulur.
     */
    fun hash(config: ServerConfig): String {
        val shared = shared(config, ServerConfig())
        val bytes = MessageDigest.getInstance("SHA-256").digest(ConfigJson.encodeToString(ServerConfig.serializer(), shared).toByteArray())
        return bytes.take(16).joinToString("") { "%02x".format(it) }
    }

    /** Ana sunucunun konfigürasyonundan kopyanınkini üretir: paylaşılan kısım anadan, sunucuya özgü kısım [local]'den. */
    fun merge(local: ServerConfig, primary: ServerConfig): ServerConfig {
        val source = local.replication.replicaOf ?: throw IllegalArgumentException(tr("Bu sunucu kopya değil"))
        return shared(primary, local).copy(replication = local.replication.copy(source = false, replicaOf = source.copy(configHash = hash(primary))))
    }

    private fun shared(from: ServerConfig, own: ServerConfig) = from.copy(
        server = own.server, database = own.database, access = own.access, components = own.components, backup = own.backup,
        alerts = own.alerts, firewall = own.firewall, audit = own.audit, replication = own.replication, version = own.version,
    )
}

/**
 * Satırların taşınabilir JSON hali (SQLite ↔ PostgreSQL): alan tipine göre (mantıksal değer, zaman, JSON) çözülür;
 * konfigürasyonda olmayan sütunlar (arama sütunları) olduğu gibi.
 */
object RowCodec {
    fun types(c: CollectionConfig): Map<String, FieldType> =
        c.fields.associate { it.name to it.type } + mapOf("id" to FieldType.INTEGER, "created_at" to FieldType.DATETIME, "updated_at" to FieldType.DATETIME)

    fun encode(dialect: SqlDialect, c: CollectionConfig, row: Map<String, Any?>): JsonObject {
        val types = types(c)
        return JsonObject(row.mapValues { (col, raw) -> value(dialect, types[col], raw) })
    }

    private fun value(dialect: SqlDialect, type: FieldType?, raw: Any?): JsonElement {
        if (raw == null) return JsonNull
        val v = if (type != null) dialect.decode(type, raw) else raw
        return when (v) {
            null -> JsonNull
            is Boolean -> JsonPrimitive(v)
            is Number -> JsonPrimitive(v)
            is Instant -> JsonPrimitive(v.toString())
            is java.sql.Timestamp -> JsonPrimitive(v.toInstant().toString())
            is OffsetDateTime -> JsonPrimitive(v.toInstant().toString())
            else -> JsonPrimitive(v.toString())
        }
    }

    /** JSON'daki değeri bu veritabanının sütun değerine çevirir. */
    fun decode(dialect: SqlDialect, type: FieldType?, v: JsonElement): Any? {
        val p = v as? JsonPrimitive ?: return if (v is JsonNull) null else v.toString()
        if (p is JsonNull) return null
        return when (type) {
            FieldType.INTEGER, FieldType.RELATION -> p.longOrNull ?: p.doubleOrNull?.toLong()
            FieldType.REAL -> p.doubleOrNull
            FieldType.BOOLEAN -> dialect.encode(FieldType.BOOLEAN, p.booleanOrNull ?: (p.longOrNull?.let { it != 0L }))
            FieldType.DATETIME -> runCatching { dialect.encode(FieldType.DATETIME, Instant.parse(p.content)) }.getOrElse { p.content }
            FieldType.JSON, FieldType.MULTISELECT -> dialect.encode(FieldType.JSON, p.content)
            null -> if (p.isString) p.content else p.longOrNull ?: p.doubleOrNull ?: p.booleanOrNull
            else -> p.content
        }
    }
}
