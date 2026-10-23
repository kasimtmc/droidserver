package dev.droidserver.core.db

import dev.droidserver.core.config.DatabaseConfig
import dev.droidserver.core.config.PostgresUrl
import dev.droidserver.core.i18n.tr
import java.sql.Connection
import java.sql.SQLException
import java.util.Properties
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** Veritabanı sunucusuna ulaşılamıyor (kapalı, yeniden başlıyor, bağlantı koptu). HTTP katmanı 503 döner. */
class DatabaseUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Küçük JDBC bağlantı havuzu (en çok [size] bağlantı). HikariCP Android'de ağır; burada gerekenler:
 *  - bir işlem (transaction) boyunca aynı bağlantı (iş parçacığına bağlı),
 *  - boşta beklerken kopmuş bağlantının ödünç verilirken ayıklanması,
 *  - kopan bağlantının atılıp sonraki istekte yenisinin açılması (sunucu yeniden başlayınca kendiliğinden düzelir).
 */
class PooledJdbcDriver(
    private val connect: () -> Connection,
    override val dialect: SqlDialect,
    private val size: Int = 4,
    private val borrowTimeoutMillis: Long = 10_000,
) : SqlDriver {
    private val idle = LinkedBlockingDeque<Connection>()
    private val permits = Semaphore(size)
    private val current = ThreadLocal<Connection?>()

    @Volatile private var closed = false

    override fun execute(sql: String, args: List<Any?>): Int = withConnection { Jdbc.execute(it, sql, args) }

    override fun insert(sql: String, args: List<Any?>): Long = withConnection { Jdbc.insert(it, sql, args) }

    override fun query(sql: String, args: List<Any?>): List<Map<String, Any?>> = withConnection { Jdbc.query(it, sql, args) }

    /** Havuzdan bir bağlantıda READ ONLY işlem ve `statement_timeout`; sonunda geri alınır. */
    override fun readOnly(sql: String, maxRows: Int, timeoutSec: Int): ReadOnlyResult = withConnection { c ->
        val auto = c.autoCommit
        c.autoCommit = false
        try {
            c.createStatement().use { st ->
                st.execute("SET TRANSACTION READ ONLY")
                st.execute("SET LOCAL statement_timeout = ${timeoutSec * 1000}")
            }
            Jdbc.readOnly(c, sql, maxRows, timeoutSec)
        } finally {
            runCatching { c.rollback() }
            c.autoCommit = auto
        }
    }

    override fun <T> transaction(block: () -> T): T {
        if (current.get() != null) return block() // iç içe çağrılar dış işleme katılır
        val conn = borrow()
        current.set(conn)
        var broken = false
        try {
            conn.autoCommit = false
            val result = block()
            conn.commit()
            return result
        } catch (t: Throwable) {
            broken = isBroken(t)
            if (!broken) runCatching { conn.rollback() }
            throw translate(t)
        } finally {
            current.remove()
            if (!broken) broken = runCatching { conn.autoCommit = true }.isFailure
            release(conn, broken)
        }
    }

    override fun close() {
        closed = true
        generateSequence { idle.pollFirst() }.forEach { runCatching { it.close() } }
    }

    private fun <T> withConnection(block: (Connection) -> T): T {
        current.get()?.let { return block(it) } // işlem içindeyiz; hata işlemin kendisinde ele alınır
        val conn = borrow()
        var broken = false
        try {
            return block(conn)
        } catch (t: Throwable) {
            broken = isBroken(t)
            throw translate(t)
        } finally {
            release(conn, broken)
        }
    }

    private fun borrow(): Connection {
        check(!closed) { tr("Sürücü kapatıldı") }
        if (!permits.tryAcquire(borrowTimeoutMillis, TimeUnit.MILLISECONDS)) {
            throw DatabaseUnavailableException(tr("Veritabanı bağlantı havuzu dolu"))
        }
        try {
            while (true) {
                val conn = idle.pollFirst() ?: break
                if (runCatching { conn.isValid(2) }.getOrDefault(false)) return conn
                runCatching { conn.close() } // sunucu yeniden başladıysa boşta bekleyen bağlantılar ölüdür
            }
            return try {
                connect()
            } catch (e: SQLException) {
                throw DatabaseUnavailableException(tr("Veritabanına bağlanılamadı: {0}", e.message), e)
            }
        } catch (t: Throwable) {
            permits.release()
            throw t
        }
    }

    private fun release(conn: Connection, broken: Boolean) {
        if (broken || closed) runCatching { conn.close() } else idle.offerFirst(conn)
        permits.release()
    }

    private fun isBroken(t: Throwable) = t is DatabaseUnavailableException || dialect.isConnectionFailure(t)

    private fun translate(t: Throwable): Throwable =
        if (t !is DatabaseUnavailableException && dialect.isConnectionFailure(t)) {
            DatabaseUnavailableException(tr("Veritabanı bağlantısı koptu: {0}", t.message), t)
        } else t
}

/** PostgreSQL bağlantısı (pgjdbc). Bağlantılar ilk kullanımda açılır. */
object PostgresDriver {
    fun open(cfg: DatabaseConfig, poolSize: Int = 4): SqlDriver {
        val url = requireNotNull(cfg.url) { "database.url gerekli" }
        val props = Properties().apply {
            cfg.user?.let { setProperty("user", it) }
            cfg.password?.let { setProperty("password", it) }
            // Metin parametreleri tipsiz gider; sunucu sütundan çıkarır (JSONB, TIMESTAMPTZ için gerekli).
            setProperty("stringtype", "unspecified")
            setProperty("connectTimeout", "5")
            setProperty("socketTimeout", "60")
            setProperty("ApplicationName", "droidserver")
        }
        // DriverManager yerine doğrudan sürücü: Android'de ServiceLoader kaydına güvenilmez.
        val driver = org.postgresql.Driver()
        fun connect(to: String) = driver.connect(to, props) ?: throw SQLException(tr("Geçersiz PostgreSQL adresi: {0}", to), "08001")
        return PooledJdbcDriver(
            connect = {
                try {
                    connect(url)
                } catch (e: SQLException) {
                    if (e.sqlState != INVALID_CATALOG || !cfg.createIfMissing) throw e
                    createDatabase(url) { connect(it) }
                    connect(url)
                }
            },
            dialect = PostgresDialect,
            size = poolSize,
        )
    }

    /** Veritabanı yok. */
    private const val INVALID_CATALOG = "3D000"

    /** Veritabanı zaten var (aynı anda iki bağlantı oluşturmaya çalıştıysa). */
    private const val DUPLICATE_DATABASE = "42P04"

    /** Bakım veritabanına (`postgres`) bağlanıp adresteki veritabanını oluşturur. */
    private fun createDatabase(url: String, connect: (String) -> Connection) {
        val name = requireNotNull(PostgresUrl.databaseName(url)) { tr("Adreste veritabanı adı yok: {0}", url) }
        connect(PostgresUrl.withDatabase(url, "postgres")).use { c ->
            try {
                c.createStatement().use { it.execute("CREATE DATABASE ${quoteIdent(name)}") }
            } catch (e: SQLException) {
                if (e.sqlState != DUPLICATE_DATABASE) throw e
            }
        }
    }
}

/** PostgreSQL yönetim işleri. */
object PostgresAdmin {
    /**
     * Bağlı kullanıcının parolasını değiştirir. ALTER ROLE parametre almadığı için parola dolar tırnağıyla
     * yazılır (`standard_conforming_strings` ayarından bağımsız); etiket parolada geçmeyecek biçimde seçilir.
     */
    fun changePassword(db: SqlDriver, newPassword: String) {
        require(newPassword.isNotEmpty()) { tr("Parola boş olamaz") }
        db.execute("ALTER ROLE CURRENT_USER PASSWORD ${dollarQuote(newPassword)}")
    }

    internal fun dollarQuote(value: String): String {
        val tag = generateSequence(0) { it + 1 }.map { "pw$it" }.first { "\$$it\$" !in value && !value.endsWith("\$$it") }
        return "\$$tag\$$value\$$tag\$"
    }
}
