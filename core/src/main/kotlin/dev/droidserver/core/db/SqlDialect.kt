package dev.droidserver.core.db

import dev.droidserver.core.config.FieldType
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/**
 * Veritabanına özgü SQL farkları. SchemaMigrator, RecordService ve DataCopier yalnızca bu arayüz üzerinden
 * motor-özel SQL üretir; geri kalan SQL (SELECT/UPDATE/DELETE, LIMIT/OFFSET, IF [NOT] EXISTS) taşınabilirdir.
 *
 * Tablo ve sütun adları tırnaksız verilir; lehçe `quoteIdent()` ile tırnaklar.
 *
 * Alan değerlerinin ortak (lehçeden bağımsız) biçimi: TEXT → String, INTEGER → Long, REAL → Double,
 * BOOLEAN → Boolean, DATETIME → Instant, JSON → JSON metni (String). [encode] bu biçimden veritabanına,
 * [decode] veritabanından bu biçime çevirir.
 */
interface SqlDialect {
    /** Kullanıcı tabloları (motorun iç tabloları hariç; `_ds_` önekli sistem tabloları dahil). */
    fun listTables(db: SqlDriver): Set<String>

    /** Sütun adı -> veritabanında tanımlı tip. */
    fun listColumns(db: SqlDriver, table: String): Map<String, String>

    /** Tablodaki indeks adları. */
    fun listIndexes(db: SqlDriver, table: String): Set<String>

    /** Sistem sütunlarıyla (`id`, `created_at`, `updated_at`) yeni tablo. */
    fun createTableSql(table: String): String

    fun columnType(t: FieldType): String

    /** Veritabanındaki sütun tipi alan tipine karşılık geliyor mu? */
    fun matchesType(dbType: String, t: FieldType): Boolean = dbType.trim().equals(columnType(t), ignoreCase = true)

    fun isUniqueViolation(e: Throwable): Boolean

    /** Sunucuya ulaşılamıyor / bağlantı koptu mu? Havuz bağlantıyı atar, API 503 döner. */
    fun isConnectionFailure(e: Throwable): Boolean = false

    /** Oluşan satırın id'sini [SqlDriver.insert] ile döndürecek INSERT cümlesi. */
    fun insertReturningId(table: String, cols: List<String>): String

    /** `id` dahil, kimliği koruyarak ekleme (veri taşıma). */
    fun insertWithIdSql(table: String, cols: List<String>): String

    /** Kimlikler elle verildikten sonra sayacı en büyük id'ye getiren sorgu; gerekmiyorsa null. */
    fun resetIdSequenceSql(table: String): String? = null

    /**
     * [column]'u siler ve [replacement] sütununu (tipiyle ve verisiyle) onun adına, onun yerine taşır.
     * Tip değişikliğinde kullanılır; [column] üzerindeki indeksler önceden kaldırılmış olmalı.
     */
    fun replaceColumn(db: SqlDriver, table: String, column: String, replacement: String)

    /**
     * Karşılaştırma ve sıralamada kullanılacak ifade ([expr] sütun adı veya `?`). Varsayılan: kendisi. SQLite'ta zaman
     * metin olarak ve değişken kesir hanesiyle saklandığından (`…00Z`, `…00.5Z`) zamana çevrilerek karşılaştırılır.
     */
    fun comparable(type: FieldType, expr: String): String = expr

    /**
     * İşlem bitene kadar okunan satırın silinmesini engelleyen SELECT eki (ilişki hedefinin varlık kontrolü).
     * SQLite yazmaları zaten sıraya koyar; PostgreSQL'de `FOR SHARE`.
     */
    fun lockForShare(): String = ""

    /** Büyük/küçük harf duyarsız "içerir" koşulu; değer `%…%` biçiminde, `\` kaçış karakteriyle bağlanır. */
    fun likeSql(column: String): String

    /** Büyük/küçük harf duyarlı "metin içerir" koşulu; değer olduğu gibi bağlanır (çoklu seçim filtresi). */
    fun containsSql(column: String): String

    /** Zaman damgasını UTC'de gün (`YYYY-MM-DD`), ay (`YYYY-MM`) veya yıl metnine çevirir (özetlerde gruplama). */
    fun dateBucket(expr: String, unit: String): String

    /** Ortak biçimdeki değeri bağlanacak değere çevirir. */
    fun encode(type: FieldType, value: Any?): Any?

    /** Okunan değeri ortak biçime çevirir. */
    fun decode(type: FieldType, raw: Any?): Any?

    /** İşlemin ilk komutu: birden çok tablonun aynı anın görüntüsünden okunması için (yedek); gerekmiyorsa null. */
    fun snapshotSql(): String? = null
}

private fun insertSql(table: String, cols: List<String>, beforeValues: String = "") =
    "INSERT INTO ${quoteIdent(table)} (${cols.joinToString { quoteIdent(it) }})$beforeValues VALUES (${cols.joinToString { "?" }})"

private fun parseInstantOrRaw(s: String): Any = try {
    Instant.parse(s)
} catch (_: DateTimeParseException) {
    s
}

/** SQLite (Android'in yerleşik SQLite'ı ve testlerde sqlite-jdbc). Boolean 0/1, zaman ISO-8601 metin. */
object SqliteDialect : SqlDialect {

    override fun comparable(type: FieldType, expr: String): String =
        if (type == FieldType.DATETIME) "julianday($expr)" else expr

    // SQLite LIKE yalnızca ASCII harflerde büyük/küçük harf duyarsızdır.
    override fun likeSql(column: String): String = "$column LIKE ? ESCAPE '\\'"

    override fun containsSql(column: String): String = "instr($column, ?) > 0"

    override fun dateBucket(expr: String, unit: String): String =
        "strftime('${mapOf("day" to "%Y-%m-%d", "month" to "%Y-%m", "year" to "%Y").getValue(unit)}', $expr)"

    override fun listTables(db: SqlDriver): Set<String> =
        db.query("SELECT name FROM sqlite_master WHERE type = 'table'")
            .map { it["name"] as String }
            .filterNot { it.startsWith("sqlite_") }
            .toSet()

    override fun listColumns(db: SqlDriver, table: String): Map<String, String> =
        db.query("PRAGMA table_info(${quoteIdent(table)})")
            .associate { it["name"] as String to (it["type"] as String? ?: "") }

    override fun listIndexes(db: SqlDriver, table: String): Set<String> =
        db.query("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = ?", listOf(table))
            .map { it["name"] as String }
            .toSet()

    override fun createTableSql(table: String) = """CREATE TABLE ${quoteIdent(table)} (
        "id" INTEGER PRIMARY KEY AUTOINCREMENT,
        "created_at" TEXT NOT NULL,
        "updated_at" TEXT NOT NULL
    )""".trimIndent()

    override fun columnType(t: FieldType) = when (t) {
        FieldType.TEXT, FieldType.DATETIME, FieldType.JSON, FieldType.FILE, FieldType.SELECT, FieldType.MULTISELECT -> "TEXT"
        FieldType.INTEGER, FieldType.BOOLEAN, FieldType.RELATION -> "INTEGER"
        FieldType.REAL -> "REAL"
    }

    /**
     * Android (SQLiteConstraintException) ve sqlite-jdbc (SQLiteException) aynı SQLite iletisini taşır:
     * "UNIQUE constraint failed: tablo.sütun". Çekirdek iki sürücüye de bağımlı olmadığı için iletiye bakılır.
     */
    override fun isUniqueViolation(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.any { it.message?.contains("UNIQUE constraint failed", ignoreCase = true) == true }

    /** SQLite 3.35 öncesinde RETURNING yok (Android 8 = 3.18); id sürücüden (last_insert_rowid) alınır. */
    override fun insertReturningId(table: String, cols: List<String>) = insertSql(table, cols)

    /** AUTOINCREMENT sayacı (sqlite_sequence) elle verilen id'lerle kendiliğinden ilerler. */
    override fun insertWithIdSql(table: String, cols: List<String>) = insertSql(table, cols)

    /**
     * Android 8'in SQLite'ında (3.18) DROP/RENAME COLUMN yok; tablo SQLite'ın önerdiği gibi yeniden kurulur:
     * yeni tablo, veriyi kopyala, eskisini sil, yenisinin adını değiştir, indeksleri ve id sayacını geri yükle.
     */
    override fun replaceColumn(db: SqlDriver, table: String, column: String, replacement: String) {
        val t = quoteIdent(table)
        val createSql = db.query("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?", listOf(table))
            .single()["sql"] as String
        val indexSql = db.query(
            "SELECT sql FROM sqlite_master WHERE type = 'index' AND tbl_name = ? AND sql IS NOT NULL",
            listOf(table),
        ).map { it["sql"] as String }
        val sequence = if ("sqlite_sequence" in allTables(db)) {
            db.query("SELECT seq FROM sqlite_sequence WHERE name = ?", listOf(table)).firstOrNull()?.get("seq")
        } else null

        val info = db.query("PRAGMA table_info($t)")
        val typeOf = info.associate { it["name"] as String to (it["type"] as String? ?: "") }
        val defs = mutableListOf<String>()
        val targetCols = mutableListOf<String>()
        val sourceCols = mutableListOf<String>()
        for (row in info) {
            val name = row["name"] as String
            if (name == replacement) continue
            val source = if (name == column) replacement else name
            defs += when {
                (row["pk"] as Number).toInt() > 0 ->
                    "${quoteIdent(name)} INTEGER PRIMARY KEY" + if (createSql.contains("AUTOINCREMENT", ignoreCase = true)) " AUTOINCREMENT" else ""
                else -> "${quoteIdent(name)} ${typeOf.getValue(source)}" + if ((row["notnull"] as Number).toInt() != 0) " NOT NULL" else ""
            }
            targetCols += quoteIdent(name)
            sourceCols += quoteIdent(source)
        }
        val rebuild = quoteIdent(REBUILD_TABLE)
        db.execute("CREATE TABLE $rebuild (${defs.joinToString(", ")})")
        db.execute("INSERT INTO $rebuild (${targetCols.joinToString()}) SELECT ${sourceCols.joinToString()} FROM $t")
        db.execute("DROP TABLE $t")
        db.execute("ALTER TABLE $rebuild RENAME TO $t")
        for (sql in indexSql) db.execute(sql)
        if (sequence != null) {
            // Silinmiş en büyük id'ler yeniden kullanılmasın.
            db.execute("DELETE FROM sqlite_sequence WHERE name = ?", listOf(table))
            db.execute("INSERT INTO sqlite_sequence (name, seq) VALUES (?, ?)", listOf(table, sequence))
        }
    }

    private const val REBUILD_TABLE = "_ds_rebuild"

    private fun allTables(db: SqlDriver): Set<String> =
        db.query("SELECT name FROM sqlite_master WHERE type = 'table'").map { it["name"] as String }.toSet()

    override fun encode(type: FieldType, value: Any?): Any? = when (value) {
        is Boolean -> if (value) 1L else 0L
        is Instant -> value.toString()
        else -> value
    }

    override fun decode(type: FieldType, raw: Any?): Any? = when {
        raw == null -> null
        type == FieldType.BOOLEAN -> (raw as Number).toLong() != 0L
        type == FieldType.INTEGER || type == FieldType.RELATION -> (raw as Number).toLong()
        type == FieldType.REAL -> (raw as Number).toDouble()
        type == FieldType.DATETIME -> parseInstantOrRaw(raw.toString())
        else -> raw.toString()
    }
}

/**
 * PostgreSQL. Tipler yerel: BIGINT, DOUBLE PRECISION, BOOLEAN, TIMESTAMPTZ, JSONB; id bir kimlik sütunu.
 * Bağlantı `stringtype=unspecified` ile açılır (bkz. PostgresDriver): JSON metni sütun tipinden JSONB'ye çevrilir.
 */
object PostgresDialect : SqlDialect {
    override fun snapshotSql() = "SET TRANSACTION ISOLATION LEVEL REPEATABLE READ"


    override fun likeSql(column: String): String = "$column ILIKE ? ESCAPE '\\'"

    override fun containsSql(column: String): String = "strpos($column, ?) > 0"

    override fun dateBucket(expr: String, unit: String): String =
        "to_char($expr AT TIME ZONE 'UTC', '${mapOf("day" to "YYYY-MM-DD", "month" to "YYYY-MM", "year" to "YYYY").getValue(unit)}')"

    override fun lockForShare(): String = " FOR SHARE"

    override fun listTables(db: SqlDriver): Set<String> =
        db.query(
            "SELECT table_name AS name FROM information_schema.tables " +
                "WHERE table_schema = current_schema() AND table_type = 'BASE TABLE'",
        ).map { it["name"] as String }.toSet()

    override fun listColumns(db: SqlDriver, table: String): Map<String, String> =
        db.query(
            "SELECT column_name AS name, data_type AS type FROM information_schema.columns " +
                "WHERE table_schema = current_schema() AND table_name = ?",
            listOf(table),
        ).associate { it["name"] as String to it["type"] as String }

    override fun listIndexes(db: SqlDriver, table: String): Set<String> =
        db.query("SELECT indexname AS name FROM pg_indexes WHERE schemaname = current_schema() AND tablename = ?", listOf(table))
            .map { it["name"] as String }
            .toSet()

    override fun createTableSql(table: String) = """CREATE TABLE ${quoteIdent(table)} (
        "id" BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
        "created_at" TIMESTAMPTZ NOT NULL,
        "updated_at" TIMESTAMPTZ NOT NULL
    )""".trimIndent()

    override fun columnType(t: FieldType) = when (t) {
        FieldType.TEXT, FieldType.FILE, FieldType.SELECT, FieldType.MULTISELECT -> "TEXT"
        FieldType.INTEGER, FieldType.RELATION -> "BIGINT"
        FieldType.REAL -> "DOUBLE PRECISION"
        FieldType.BOOLEAN -> "BOOLEAN"
        FieldType.DATETIME -> "TIMESTAMPTZ"
        FieldType.JSON -> "JSONB"
    }

    /** information_schema uzun adlar döndürür (`timestamp with time zone`). */
    override fun matchesType(dbType: String, t: FieldType): Boolean = dbType.trim().equals(
        when (t) {
            FieldType.TEXT, FieldType.FILE, FieldType.SELECT, FieldType.MULTISELECT -> "text"
            FieldType.INTEGER, FieldType.RELATION -> "bigint"
            FieldType.REAL -> "double precision"
            FieldType.BOOLEAN -> "boolean"
            FieldType.DATETIME -> "timestamp with time zone"
            FieldType.JSON -> "jsonb"
        },
        ignoreCase = true,
    )

    override fun isUniqueViolation(e: Throwable): Boolean = sqlStates(e).any { it == "23505" }

    /** 08xxx: bağlantı hatası; 57P01-03: sunucu kapanıyor / yeniden başlıyor. */
    override fun isConnectionFailure(e: Throwable): Boolean =
        sqlStates(e).any { it.startsWith("08") || it == "57P01" || it == "57P02" || it == "57P03" }

    override fun insertReturningId(table: String, cols: List<String>) = insertSql(table, cols) + " RETURNING \"id\""

    override fun insertWithIdSql(table: String, cols: List<String>) = insertSql(table, cols, " OVERRIDING SYSTEM VALUE")

    override fun replaceColumn(db: SqlDriver, table: String, column: String, replacement: String) {
        db.execute("ALTER TABLE ${quoteIdent(table)} DROP COLUMN ${quoteIdent(column)}")
        db.execute("ALTER TABLE ${quoteIdent(table)} RENAME COLUMN ${quoteIdent(replacement)} TO ${quoteIdent(column)}")
    }

    override fun resetIdSequenceSql(table: String): String {
        val t = quoteIdent(table)
        return "SELECT setval(pg_get_serial_sequence('$t', 'id'), COALESCE((SELECT MAX(\"id\") FROM $t), 1), " +
            "(SELECT MAX(\"id\") FROM $t) IS NOT NULL) AS v"
    }

    override fun encode(type: FieldType, value: Any?): Any? = when (value) {
        is Instant -> OffsetDateTime.ofInstant(value, ZoneOffset.UTC)
        else -> value
    }

    override fun decode(type: FieldType, raw: Any?): Any? = when {
        raw == null -> null
        type == FieldType.BOOLEAN -> raw as? Boolean ?: ((raw as Number).toLong() != 0L)
        type == FieldType.INTEGER || type == FieldType.RELATION -> (raw as Number).toLong()
        type == FieldType.REAL -> (raw as Number).toDouble()
        type == FieldType.DATETIME -> when (raw) {
            is Timestamp -> raw.toInstant()
            is OffsetDateTime -> raw.toInstant()
            else -> parseInstantOrRaw(raw.toString())
        }
        else -> raw.toString() // JSONB: PGobject.toString() JSON metnidir
    }

    private fun sqlStates(e: Throwable): Sequence<String> =
        generateSequence(e) { it.cause }.filterIsInstance<SQLException>().mapNotNull { it.sqlState }
}
