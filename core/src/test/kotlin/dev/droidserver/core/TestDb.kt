package dev.droidserver.core

import dev.droidserver.core.config.DatabaseConfig
import dev.droidserver.core.config.DbEngine
import dev.droidserver.core.db.JdbcSqlDriver
import dev.droidserver.core.db.PostgresDriver
import dev.droidserver.core.db.SqlDriver
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger

enum class Dialect { SQLITE, POSTGRES }

/**
 * Testler için boş, yalıtılmış bir veritabanı. [open] her çağrıda aynı veritabanına yeni bir sürücü açar
 * (ServerEngine kendi bağlantısını açıp kapattığı için).
 *
 * PostgreSQL gerçek bir sunucudur (io.zonky.test:embedded-postgres; ikililer Maven'den iner) ve test JVM'i
 * boyunca bir kez başlatılır; her test kendi veritabanını alır. Postgres root olarak çalışmayı reddeder:
 * root ile koşturulursa Postgres süreçleri root olmayan kullanıcıyla çalıştırılır (bkz. [NonRootPostgres]).
 */
class TestDb private constructor(val config: DatabaseConfig, private val sqliteFile: File?) {

    fun open(): SqlDriver =
        if (sqliteFile != null) JdbcSqlDriver(DriverManager.getConnection("jdbc:sqlite:${sqliteFile.absolutePath}"))
        else PostgresDriver.open(config)

    companion object {
        private val counter = AtomicInteger()

        fun create(dialect: Dialect): TestDb = when (dialect) {
            Dialect.SQLITE -> {
                val f = Files.createTempFile("ds-test", ".db").toFile().apply { deleteOnExit() }
                TestDb(DatabaseConfig(file = f.name), f)
            }
            Dialect.POSTGRES -> {
                val name = "t${counter.incrementAndGet()}"
                server.postgresDatabase.connection.use { it.createStatement().execute("CREATE DATABASE $name") }
                TestDb(
                    DatabaseConfig(engine = DbEngine.POSTGRES, url = "jdbc:postgresql://localhost:${server.port}/$name", user = "postgres"),
                    null,
                )
            }
        }

        val server: EmbeddedPostgres by lazy {
            NonRootPostgres.binDir // root ile koşuyorsa Postgres'i root olmayan kullanıcıyla çalıştırır
            EmbeddedPostgres.builder().start().also { pg -> Runtime.getRuntime().addShutdownHook(Thread { pg.close() }) }
        }

    }
}
