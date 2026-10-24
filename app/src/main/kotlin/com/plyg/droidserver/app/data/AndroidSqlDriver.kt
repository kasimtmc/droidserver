package com.plyg.droidserver.app.data

import android.content.Context
import java.io.File
import android.database.Cursor
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteProgram
import com.plyg.droidserver.core.config.DatabaseConfig
import com.plyg.droidserver.core.config.DbEngine
import com.plyg.droidserver.core.db.PostgresDriver
import com.plyg.droidserver.core.db.SqlDialect
import com.plyg.droidserver.core.db.SqlDriver
import com.plyg.droidserver.core.db.SqliteDialect
import android.os.CancellationSignal
import android.os.OperationCanceledException
import com.plyg.droidserver.core.api.ApiException
import com.plyg.droidserver.core.db.ReadOnlyResult
import com.plyg.droidserver.core.i18n.tr
import java.util.Timer
import kotlin.concurrent.timerTask

/** Android'in yerleşik SQLite'ı üzerinde SqlDriver. Ek süreç gerektirmez. */
class AndroidSqlDriver(private val db: SQLiteDatabase) : SqlDriver {

    companion object {
        /** [dir]: SQLite dosyasının klasörü (profil); null ise uygulamanın veritabanı klasörü. */
        fun open(context: Context, cfg: DatabaseConfig, dir: File? = null): SqlDriver = when (cfg.engine) {
            DbEngine.SQLITE -> {
                val file = (dir?.let { File(it, cfg.file) } ?: context.getDatabasePath(cfg.file)).apply { parentFile?.mkdirs() }
                val db = SQLiteDatabase.openOrCreateDatabase(file, null)
                db.enableWriteAheadLogging()
                db.execSQL("PRAGMA foreign_keys = ON")
                AndroidSqlDriver(db)
            }
            // Postgres bileşeni (veya kendi sunucunuz) pgjdbc ile; bağlantılar ilk kullanımda açılır.
            DbEngine.POSTGRES -> PostgresDriver.open(cfg)
        }
    }

    override val dialect: SqlDialect = SqliteDialect

    /**
     * Aynı dosyayı ayrı ve salt okunur açar (yazma kilidi tutulmaz); süre dolunca sorgu [CancellationSignal] ile iptal
     * edilir.
     */
    override fun readOnly(sql: String, maxRows: Int, timeoutSec: Int): ReadOnlyResult {
        val ro = SQLiteDatabase.openDatabase(db.path, null, SQLiteDatabase.OPEN_READONLY)
        val cancel = CancellationSignal()
        val timer = Timer("ds-sql-timeout", true).apply { schedule(timerTask { cancel.cancel() }, timeoutSec * 1000L) }
        try {
            return ro.rawQuery(sql, null, cancel).use { c ->
                val columns = c.columnNames.toList()
                val rows = ArrayList<List<Any?>>()
                while (rows.size <= maxRows && c.moveToNext()) {
                    rows += (0 until c.columnCount).map { i ->
                        when (c.getType(i)) {
                            Cursor.FIELD_TYPE_NULL -> null
                            Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                            Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                            Cursor.FIELD_TYPE_BLOB -> "<${c.getBlob(i).size} bayt>"
                            else -> c.getString(i)
                        }
                    }
                }
                ReadOnlyResult(columns, rows.take(maxRows), rows.size > maxRows)
            }
        } catch (e: OperationCanceledException) {
            throw ApiException(408, tr("Sorgu {0} saniyede bitmedi", timeoutSec))
        } finally {
            timer.cancel()
            ro.close()
        }
    }

    override fun execute(sql: String, args: List<Any?>): Int = db.compileStatement(sql).use { st ->
        bind(st, args)
        if (isDdl(sql)) { st.execute(); 0 } else st.executeUpdateDelete()
    }

    override fun insert(sql: String, args: List<Any?>): Long = db.compileStatement(sql).use { st ->
        bind(st, args)
        st.executeInsert()
    }

    override fun query(sql: String, args: List<Any?>): List<Map<String, Any?>> {
        // rawQuery yalnızca metin argüman kabul eder ve null'ı reddeder; argümanlar imleç
        // oluşturulurken tipleriyle bağlanır (IS NULL karşılaştırmaları ve sayısal sütunlar için).
        val factory = SQLiteDatabase.CursorFactory { _, driver, editTable, query ->
            bind(query, args)
            SQLiteCursor(driver, editTable, query)
        }
        // editTable yalnızca eski (kullanımdan kalkmış) imleç güncellemeleri içindir; boş geçilir.
        return db.rawQueryWithFactory(factory, sql, null, "").use { c ->
            val rows = ArrayList<Map<String, Any?>>(c.count)
            while (c.moveToNext()) {
                val row = LinkedHashMap<String, Any?>(c.columnCount)
                for (i in 0 until c.columnCount) {
                    row[c.getColumnName(i)] = when (c.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> null
                        Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                        Cursor.FIELD_TYPE_BLOB -> c.getBlob(i)
                        else -> c.getString(i)
                    }
                }
                rows += row
            }
            rows
        }
    }

    /** SQLiteDatabase iç içe işlemleri kendisi destekler. */
    override fun <T> transaction(block: () -> T): T {
        db.beginTransaction()
        try {
            val result = block()
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    override fun close() = db.close()

    private fun isDdl(sql: String): Boolean {
        val head = sql.trimStart().take(8).uppercase()
        return head.startsWith("CREATE") || head.startsWith("ALTER") || head.startsWith("DROP") || head.startsWith("PRAGMA")
    }

    /** SQLiteStatement ve SQLiteQuery ortak atası SQLiteProgram üzerinden bağlar. */
    private fun bind(program: SQLiteProgram, args: List<Any?>) {
        args.forEachIndexed { i, v ->
            val idx = i + 1
            when (v) {
                null -> program.bindNull(idx)
                is Long -> program.bindLong(idx, v)
                is Int -> program.bindLong(idx, v.toLong())
                is Boolean -> program.bindLong(idx, if (v) 1 else 0)
                is Double -> program.bindDouble(idx, v)
                is Float -> program.bindDouble(idx, v.toDouble())
                is ByteArray -> program.bindBlob(idx, v)
                else -> program.bindString(idx, v.toString())
            }
        }
    }
}
