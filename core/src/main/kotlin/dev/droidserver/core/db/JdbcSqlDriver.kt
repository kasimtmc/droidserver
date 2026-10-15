package dev.droidserver.core.db

import java.sql.Connection

/**
 * Tek bağlantılı JDBC sürücüsü (testlerde sqlite-jdbc). Tek bağlantı + kilit: SQLite için yeterli.
 * PostgreSQL için havuzlu sürücü [PooledJdbcDriver] / [PostgresDriver] kullanılır.
 */
class JdbcSqlDriver(
    private val connection: Connection,
    override val dialect: SqlDialect = SqliteDialect,
) : SqlDriver {
    private val lock = Any()
    private var txDepth = 0

    override fun execute(sql: String, args: List<Any?>): Int = synchronized(lock) { Jdbc.execute(connection, sql, args) }

    override fun insert(sql: String, args: List<Any?>): Long = synchronized(lock) { Jdbc.insert(connection, sql, args) }

    override fun query(sql: String, args: List<Any?>): List<Map<String, Any?>> =
        synchronized(lock) { Jdbc.query(connection, sql, args) }

    override fun <T> transaction(block: () -> T): T = synchronized(lock) {
        if (txDepth > 0) return block() // iç içe çağrılar dış işleme katılır
        val prev = connection.autoCommit
        connection.autoCommit = false
        txDepth++
        try {
            val result = block()
            connection.commit()
            result
        } catch (t: Throwable) {
            connection.rollback()
            throw t
        } finally {
            txDepth--
            connection.autoCommit = prev
        }
    }

    override fun close() = synchronized(lock) { connection.close() }
}
