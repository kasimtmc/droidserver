package dev.droidserver.core

import dev.droidserver.core.api.droidServerModule
import dev.droidserver.core.config.CollectionTemplate
import dev.droidserver.core.config.DatabaseConfig
import dev.droidserver.core.config.DbEngine
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.db.DatabaseUnavailableException
import dev.droidserver.core.db.PostgresDriver
import dev.droidserver.core.db.SchemaMigrator
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Postgres çökünce (bileşen supervisor tarafından yeniden başlatılırken) API 503 döner;
 * sunucu geri gelince havuz yeni bağlantı açar ve istekler kendiliğinden düzelir.
 */
class PostgresResilienceTest {

    @Test
    fun `postgres kapaninca 503, geri gelince yeniden baglanir`() {
        NonRootPostgres.binDir // root ile koşuyorsa Postgres root olmayan kullanıcıyla çalışır
        val dir = Files.createTempDirectory("ds-pg-restart").toFile()
        val port = ServerSocket(0).use { it.localPort }
        fun startPostgres() = EmbeddedPostgres.builder().setDataDirectory(dir).setCleanDataDirectory(false).setPort(port).start()

        var pg = startPostgres()
        val todos = CollectionTemplate.TODOS.create()
        val db = PostgresDriver.open(
            DatabaseConfig(engine = DbEngine.POSTGRES, url = "jdbc:postgresql://localhost:$port/postgres", user = "postgres"),
        )
        try {
            SchemaMigrator(db).migrate(listOf(todos))
            testApplication {
                application { droidServerModule(ServerConfig(collections = listOf(todos)), db) }
                val base = "/api/collections/todos/records"
                val created = client.post(base) { contentType(ContentType.Application.Json); setBody("""{"title":"kalıcı"}""") }
                assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())

                pg.close() // veri klasörü korunur
                val down = client.get(base)
                assertEquals(HttpStatusCode.ServiceUnavailable, down.status, down.bodyAsText())
                assertTrue(down.bodyAsText().contains("ulaşılamıyor"))

                pg = startPostgres()
                val back = client.get(base)
                assertEquals(HttpStatusCode.OK, back.status, back.bodyAsText())
                assertTrue(back.bodyAsText().contains("kalıcı"))
            }
        } finally {
            db.close()
            pg.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `sorgu ortasinda kopan baglanti atilir, sonraki istek yeni baglantiyla calisir`() {
        val testDb = TestDb.create(Dialect.POSTGRES)
        testDb.open().use { db ->
            val e = assertFailsWith<DatabaseUnavailableException> {
                db.transaction {
                    val pid = db.query("SELECT pg_backend_pid() AS pid").first()["pid"]
                    TestDb.server.postgresDatabase.connection.use { admin ->
                        admin.createStatement().execute("SELECT pg_terminate_backend($pid)")
                    }
                    db.query("SELECT 1 AS x")
                }
            }
            assertTrue(e.message!!.contains("koptu"), e.message)
            assertEquals(1L, db.query("SELECT 1 AS x").first()["x"])
        }
    }
}
