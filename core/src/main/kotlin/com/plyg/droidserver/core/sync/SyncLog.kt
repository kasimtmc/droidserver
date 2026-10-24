package com.plyg.droidserver.core.sync

import com.plyg.droidserver.core.api.SearchText
import com.plyg.droidserver.core.config.CollectionConfig
import com.plyg.droidserver.core.db.PostgresDialect
import com.plyg.droidserver.core.db.SchemaMigrator
import com.plyg.droidserver.core.db.SqlDriver
import com.plyg.droidserver.core.db.quoteIdent
import java.security.SecureRandom
import java.util.Base64

/**
 * Çevrimdışı eşitlemenin izi. Eşitlenen her koleksiyona tetikleyici kurulur; ekleme/güncelleme/silme (içe aktarma,
 * script'ler ve ilişki zinciriyle silinenler dahil) şunları günceller:
 *
 * - `_sync`: kayıt başına sürüm (her değişiklikte +1), genel sıra numarası (`seq`, istemcinin imleci), silinme izi ve
 *   son değişiklik zamanı (ms).
 * - `_sync_fields`: alan başına son değiştiği sürüm ve zaman (alan bazında "son yazan kazanır" ve çakışma tespiti).
 *
 * Tetikleyiciler eski Android SQLite'ında da çalışır (JSON fonksiyonu, UPSERT ve pencere fonksiyonu kullanılmaz).
 * PostgreSQL'de sıra numarası işlem boyunca tutulan bir kilitle verilir; böylece numara sırası işlemlerin tamamlanma
 * sırasıyla aynıdır ve istemci imleci hiçbir değişikliği atlamaz. Tablolar konfigürasyon koleksiyonu değildir:
 * kopyalara aktarılmaz (eşitleme ana sunucuyla yapılır).
 */
object SyncLog {
    const val TABLE = "_sync"
    const val FIELDS = "_sync_fields"
    private const val PREFIX = "_ds_sync_"
    private const val LOCK = 7262533L

    /** Eşitleme izinin kimliği: tablo yeniden kurulunca (örn. veritabanı değişti) değişir, istemci baştan eşitlenir. */
    private const val EPOCH_KEY = "sync.epoch"
    private const val PURGED_PREFIX = "sync.purged."

    private val sync = quoteIdent(TABLE)
    private val fields = quoteIdent(FIELDS)
    private val meta = quoteIdent(SchemaMigrator.META_TABLE)

    private fun nowMs(db: SqlDriver) =
        if (db.dialect == PostgresDialect) "(extract(epoch from clock_timestamp()) * 1000)::bigint" else "CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)"

    /** Tabloları (yoksa) ve [collections]'ın tetikleyicilerini kurar; izi olmayan mevcut kayıtlar eklenir. */
    fun install(db: SqlDriver, collections: List<CollectionConfig>) = db.transaction {
        val pg = db.dialect == PostgresDialect
        val big = if (pg) "BIGINT" else "INTEGER"
        db.execute("""CREATE TABLE IF NOT EXISTS $meta ("key" TEXT PRIMARY KEY, "value" TEXT NOT NULL)""")
        db.execute(
            """CREATE TABLE IF NOT EXISTS $sync ("collection" TEXT NOT NULL, "record" $big NOT NULL, "seq" $big NOT NULL, "version" $big NOT NULL,
               "deleted" INTEGER NOT NULL DEFAULT 0, "changed_at" $big NOT NULL, PRIMARY KEY ("collection", "record"))""",
        )
        db.execute("""CREATE INDEX IF NOT EXISTS ${quoteIdent(TABLE + "_seq")} ON $sync ("seq")""")
        db.execute(
            """CREATE TABLE IF NOT EXISTS $fields ("collection" TEXT NOT NULL, "record" $big NOT NULL, "field" TEXT NOT NULL, "version" $big NOT NULL,
               "time" $big NOT NULL, PRIMARY KEY ("collection", "record", "field"))""",
        )
        if (db.query("SELECT \"value\" FROM $meta WHERE \"key\" = ?", listOf(EPOCH_KEY)).isEmpty()) {
            val epoch = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(9).also(SecureRandom()::nextBytes))
            db.execute("INSERT INTO $meta (\"key\", \"value\") VALUES (?, ?)", listOf(EPOCH_KEY, epoch))
        }
        if (pg) {
            db.execute("CREATE SEQUENCE IF NOT EXISTS ${quoteIdent(TABLE + "_seq_gen")}")
            db.execute(postgresFunction())
        }
        uninstall(db)
        val names = collections.map { it.name }.toSet()
        // Eşitlemesi kapatılan koleksiyonların izi silinir.
        db.query("SELECT DISTINCT \"collection\" AS c FROM $sync").map { it["c"].toString() }.filter { it !in names }.forEach { c ->
            db.execute("DELETE FROM $sync WHERE \"collection\" = ?", listOf(c))
            db.execute("DELETE FROM $fields WHERE \"collection\" = ?", listOf(c))
        }
        for (c in collections) {
            val table = quoteIdent(c.name)
            if (pg) {
                db.execute("CREATE TRIGGER ${quoteIdent(PREFIX + c.name)} AFTER INSERT OR UPDATE OR DELETE ON $table FOR EACH ROW EXECUTE FUNCTION \"${PREFIX}log\"()")
            } else {
                sqliteTriggers(db, c)
            }
            backfill(db, c.name)
        }
    }

    /** Bu sunucunun kurduğu eşitleme tetikleyicilerini kaldırır (izler kalır). */
    fun uninstall(db: SqlDriver) {
        if (db.dialect == PostgresDialect) {
            db.query("SELECT t.tgname AS name, c.relname AS tbl FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid WHERE left(t.tgname, ${PREFIX.length}) = '$PREFIX'")
                .forEach { db.execute("DROP TRIGGER IF EXISTS ${quoteIdent(it["name"].toString())} ON ${quoteIdent(it["tbl"].toString())}") }
        } else {
            db.query("SELECT name FROM sqlite_master WHERE type = 'trigger' AND substr(name, 1, ${PREFIX.length}) = '$PREFIX'")
                .forEach { db.execute("DROP TRIGGER IF EXISTS ${quoteIdent(it["name"].toString())}") }
        }
    }

    private fun sqliteTriggers(db: SqlDriver, c: CollectionConfig) {
        val t = c.name
        val table = quoteIdent(t)
        val lit = "'" + t.replace("'", "''") + "'"
        val now = nowMs(db)
        val nextSeq = "(SELECT COALESCE(MAX(\"seq\"), 0) + 1 FROM $sync)"
        val where = "\"collection\" = $lit AND \"record\" = %s.\"id\""
        db.execute(
            "CREATE TRIGGER ${quoteIdent(PREFIX + t + "_i")} AFTER INSERT ON $table BEGIN " +
                // Silinmiş bir kaydın kimliği yeniden kullanılabilir: sürüm sürer, alan izi sıfırlanır.
                "INSERT OR REPLACE INTO $sync (\"collection\", \"record\", \"seq\", \"version\", \"deleted\", \"changed_at\") VALUES ($lit, NEW.\"id\", $nextSeq, " +
                "COALESCE((SELECT \"version\" FROM $sync WHERE ${where.format("NEW")}), 0) + 1, 0, $now); " +
                "DELETE FROM $fields WHERE ${where.format("NEW")}; END",
        )
        val changed = c.fields.joinToString(" ") { f ->
            val col = quoteIdent(f.name)
            "INSERT OR REPLACE INTO $fields (\"collection\", \"record\", \"field\", \"version\", \"time\") " +
                "SELECT $lit, NEW.\"id\", '${f.name}', s.\"version\", $now FROM $sync s WHERE s.\"collection\" = $lit AND s.\"record\" = NEW.\"id\" AND (OLD.$col IS NOT NEW.$col);"
        }
        db.execute(
            "CREATE TRIGGER ${quoteIdent(PREFIX + t + "_u")} AFTER UPDATE ON $table BEGIN " +
                "UPDATE $sync SET \"version\" = \"version\" + 1, \"seq\" = $nextSeq, \"changed_at\" = $now WHERE ${where.format("NEW")}; $changed END",
        )
        db.execute(
            "CREATE TRIGGER ${quoteIdent(PREFIX + t + "_d")} AFTER DELETE ON $table BEGIN " +
                "UPDATE $sync SET \"deleted\" = 1, \"version\" = \"version\" + 1, \"seq\" = $nextSeq, \"changed_at\" = $now WHERE ${where.format("OLD")}; " +
                "DELETE FROM $fields WHERE ${where.format("OLD")}; END",
        )
    }

    private fun postgresFunction(): String {
        val skip = listOf("id", "created_at", "updated_at", SearchText.COLUMN).joinToString(", ") { "'$it'" }
        return """CREATE OR REPLACE FUNCTION "${PREFIX}log"() RETURNS trigger AS ${'$'}${'$'}
            DECLARE k text; v bigint; n bigint; now_ms bigint := (extract(epoch from clock_timestamp()) * 1000)::bigint;
            BEGIN
              PERFORM pg_advisory_xact_lock($LOCK);
              n := nextval('"${TABLE}_seq_gen"');
              IF TG_OP = 'DELETE' THEN
                UPDATE $sync SET "deleted" = 1, "version" = "version" + 1, "seq" = n, "changed_at" = now_ms WHERE "collection" = TG_TABLE_NAME AND "record" = OLD."id";
                DELETE FROM $fields WHERE "collection" = TG_TABLE_NAME AND "record" = OLD."id";
                RETURN OLD;
              END IF;
              IF TG_OP = 'INSERT' THEN
                INSERT INTO $sync ("collection", "record", "seq", "version", "deleted", "changed_at") VALUES (TG_TABLE_NAME, NEW."id", n, 1, 0, now_ms)
                  ON CONFLICT ("collection", "record") DO UPDATE SET "version" = $sync."version" + 1, "seq" = n, "deleted" = 0, "changed_at" = now_ms;
                DELETE FROM $fields WHERE "collection" = TG_TABLE_NAME AND "record" = NEW."id";
                RETURN NEW;
              END IF;
              UPDATE $sync SET "version" = "version" + 1, "seq" = n, "changed_at" = now_ms WHERE "collection" = TG_TABLE_NAME AND "record" = NEW."id" RETURNING "version" INTO v;
              IF NOT FOUND THEN
                INSERT INTO $sync ("collection", "record", "seq", "version", "deleted", "changed_at") VALUES (TG_TABLE_NAME, NEW."id", n, 1, 0, now_ms);
                v := 1;
              END IF;
              FOR k IN SELECT jsonb_object_keys(to_jsonb(NEW)) LOOP
                IF k NOT IN ($skip) AND (to_jsonb(OLD) -> k) IS DISTINCT FROM (to_jsonb(NEW) -> k) THEN
                  INSERT INTO $fields ("collection", "record", "field", "version", "time") VALUES (TG_TABLE_NAME, NEW."id", k, v, now_ms)
                    ON CONFLICT ("collection", "record", "field") DO UPDATE SET "version" = v, "time" = now_ms;
                END IF;
              END LOOP;
              RETURN NEW;
            END ${'$'}${'$'} LANGUAGE plpgsql""".trimIndent()
    }

    /** İzi olmayan kayıtlar (eşitleme sonradan açıldı) sürüm 1 olarak eklenir. */
    private fun backfill(db: SqlDriver, collection: String) {
        val missing = db.query(
            "SELECT t.\"id\" FROM ${quoteIdent(collection)} t WHERE NOT EXISTS (SELECT 1 FROM $sync s WHERE s.\"collection\" = ? AND s.\"record\" = t.\"id\") ORDER BY t.\"id\"",
            listOf(collection),
        ).map { (it["id"] as Number).toLong() }
        if (missing.isEmpty()) return
        val pg = db.dialect == PostgresDialect
        val now = System.currentTimeMillis()
        var seq = latest(db)
        for (id in missing) {
            val next = if (pg) (db.query("SELECT nextval('\"${TABLE}_seq_gen\"') AS n").first()["n"] as Number).toLong() else ++seq
            db.execute("INSERT INTO $sync (\"collection\", \"record\", \"seq\", \"version\", \"deleted\", \"changed_at\") VALUES (?, ?, ?, 1, 0, ?)", listOf(collection, id, next, now))
        }
    }

    fun latest(db: SqlDriver): Long = (db.query("SELECT MAX(\"seq\") AS n FROM $sync").first()["n"] as Number?)?.toLong() ?: 0

    fun epoch(db: SqlDriver): String = db.query("SELECT \"value\" FROM $meta WHERE \"key\" = ?", listOf(EPOCH_KEY)).firstOrNull()?.get("value")?.toString().orEmpty()

    data class Entry(val record: Long, val seq: Long, val version: Long, val deleted: Boolean, val changedAt: Long)

    /** [collection]'da [after]'dan sonraki değişiklikler (sıra numarasıyla, en çok [limit]). */
    fun changes(db: SqlDriver, collection: String, after: Long, limit: Int): List<Entry> =
        db.query(
            "SELECT \"record\", \"seq\", \"version\", \"deleted\", \"changed_at\" FROM $sync WHERE \"collection\" = ? AND \"seq\" > ? ORDER BY \"seq\" LIMIT ?",
            listOf(collection, after, limit),
        ).map(::entry)

    fun entry(db: SqlDriver, collection: String, record: Long): Entry? =
        db.query("SELECT \"record\", \"seq\", \"version\", \"deleted\", \"changed_at\" FROM $sync WHERE \"collection\" = ? AND \"record\" = ?", listOf(collection, record))
            .firstOrNull()?.let(::entry)

    private fun entry(r: Map<String, Any?>) = Entry(
        (r["record"] as Number).toLong(), (r["seq"] as Number).toLong(), (r["version"] as Number).toLong(),
        (r["deleted"] as Number).toInt() == 1, (r["changed_at"] as Number).toLong(),
    )

    /** Alan → (son değiştiği sürüm, zaman ms). */
    fun fieldVersions(db: SqlDriver, collection: String, record: Long): Map<String, Pair<Long, Long>> =
        db.query("SELECT \"field\", \"version\", \"time\" FROM $fields WHERE \"collection\" = ? AND \"record\" = ?", listOf(collection, record))
            .associate { it["field"].toString() to ((it["version"] as Number).toLong() to (it["time"] as Number).toLong()) }

    /** Eşitlemeden gelen yazmada alanların zamanı istemcinin (düzeltilmiş) düzenleme zamanı olur. */
    fun setTimes(db: SqlDriver, collection: String, record: Long, times: Map<String, Long>) {
        for ((f, t) in times) {
            db.execute("UPDATE $fields SET \"time\" = ? WHERE \"collection\" = ? AND \"record\" = ? AND \"field\" = ?", listOf(t, collection, record, f))
        }
    }

    /** Bu sıradan önceki silme izleri silinmiş: daha eski imleçli istemci baştan eşitlenmeli. */
    fun purgedBefore(db: SqlDriver, collection: String): Long =
        db.query("SELECT \"value\" FROM $meta WHERE \"key\" = ?", listOf(PURGED_PREFIX + collection)).firstOrNull()?.get("value")?.toString()?.toLongOrNull() ?: 0

    /**
     * [keepDays] günden eski silme izlerini siler; silinen sayısı. En son sıra numaralı satır hiç silinmez: SQLite'ta yeni
     * numara `MAX + 1` olduğu için numara geri gitmez.
     */
    fun purge(db: SqlDriver, collection: String, keepDays: Int, now: Long = System.currentTimeMillis()): Int = db.transaction {
        val cutoff = now - keepDays * 86_400_000L
        val last = latest(db)
        val cond = "\"collection\" = ? AND \"deleted\" = 1 AND \"changed_at\" < ? AND \"seq\" < ?"
        val old = db.query("SELECT MAX(\"seq\") AS n, COUNT(*) AS c FROM $sync WHERE $cond", listOf(collection, cutoff, last)).first()
        val count = (old["c"] as Number).toInt()
        if (count == 0) return@transaction 0
        val mark = (old["n"] as Number).toLong()
        db.execute("DELETE FROM $sync WHERE $cond", listOf(collection, cutoff, last))
        val key = PURGED_PREFIX + collection
        if (db.execute("UPDATE $meta SET \"value\" = ? WHERE \"key\" = ?", listOf(maxOf(mark, purgedBefore(db, collection)).toString(), key)) == 0) {
            db.execute("INSERT INTO $meta (\"key\", \"value\") VALUES (?, ?)", listOf(key, mark.toString()))
        }
        count
    }
}
