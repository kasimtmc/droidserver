package dev.droidserver.core.db

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Statement

/** JDBC sürücülerinin (tek bağlantılı ve havuzlu) ortak deyim çalıştırma kodu. */
internal object Jdbc {
    private val RETURNING = Regex("\\bRETURNING\\b", RegexOption.IGNORE_CASE)

    fun execute(c: Connection, sql: String, args: List<Any?>): Int = c.prepareStatement(sql).use { st ->
        bind(st, args)
        if (st.execute()) 0 else st.updateCount.coerceAtLeast(0)
    }

    /** `RETURNING` içeren INSERT (Postgres) sorgu olarak, diğerleri üretilen anahtarla çalışır. */
    fun insert(c: Connection, sql: String, args: List<Any?>): Long =
        if (RETURNING.containsMatchIn(sql)) {
            c.prepareStatement(sql).use { st ->
                bind(st, args)
                st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else -1L }
            }
        } else {
            c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { st ->
                bind(st, args)
                st.executeUpdate()
                st.generatedKeys.use { rs -> if (rs.next()) rs.getLong(1) else -1L }
            }
        }

    /** Salt okunur konsol sorgusu: sütun sırası korunur, en çok [maxRows] satır, [timeoutSec] saniye. */
    fun readOnly(c: Connection, sql: String, maxRows: Int, timeoutSec: Int): ReadOnlyResult = c.createStatement().use { st ->
        st.queryTimeout = timeoutSec
        st.maxRows = maxRows + 1
        st.executeQuery(sql).use { rs ->
            val meta = rs.metaData
            val columns = (1..meta.columnCount).map { meta.getColumnLabel(it) }
            val rows = mutableListOf<List<Any?>>()
            while (rs.next() && rows.size <= maxRows) {
                rows += (1..meta.columnCount).map { i ->
                    when (val v = rs.getObject(i)) {
                        is Int -> v.toLong()
                        is Short -> v.toLong()
                        is Float -> v.toDouble()
                        is ByteArray -> "<${v.size} bayt>"
                        null, is Number, is String, is Boolean -> v
                        else -> v.toString()
                    }
                }
            }
            ReadOnlyResult(columns, rows.take(maxRows), rows.size > maxRows)
        }
    }

    fun query(c: Connection, sql: String, args: List<Any?>): List<Map<String, Any?>> = c.prepareStatement(sql).use { st ->
        bind(st, args)
        st.executeQuery().use { rs ->
            val meta = rs.metaData
            val rows = mutableListOf<Map<String, Any?>>()
            while (rs.next()) {
                val row = LinkedHashMap<String, Any?>(meta.columnCount)
                for (i in 1..meta.columnCount) {
                    row[meta.getColumnLabel(i)] = when (val v = rs.getObject(i)) {
                        is Int -> v.toLong()
                        is Short -> v.toLong()
                        is Float -> v.toDouble()
                        else -> v
                    }
                }
                rows += row
            }
            rows
        }
    }

    private fun bind(st: PreparedStatement, args: List<Any?>) {
        args.forEachIndexed { i, v ->
            when (v) {
                null -> st.setObject(i + 1, null)
                is Boolean -> st.setBoolean(i + 1, v)
                else -> st.setObject(i + 1, v)
            }
        }
    }
}
