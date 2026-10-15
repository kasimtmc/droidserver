package dev.droidserver.core

import dev.droidserver.core.config.AuthConfig
import dev.droidserver.core.config.AuthMode
import dev.droidserver.core.config.CollectionConfig
import dev.droidserver.core.config.ConfigValidator
import dev.droidserver.core.config.DatabaseConfig
import dev.droidserver.core.config.DbEngine
import dev.droidserver.core.config.FieldConfig
import dev.droidserver.core.config.FieldType
import dev.droidserver.core.config.PostgresUrl
import dev.droidserver.core.config.RestartPolicy
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.process.ProcessSupervisor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Veritabanından bağımsız çekirdek testleri; CRUD, kimlik doğrulama ve migration DialectContractTest'te (SQLite + PostgreSQL). */
class CoreTest {
    private val todos = CollectionConfig(
        name = "todos",
        fields = listOf(
            FieldConfig("title", FieldType.TEXT, required = true),
            FieldConfig("done", FieldType.BOOLEAN, default = JsonPrimitive(false)),
            FieldConfig("priority", FieldType.INTEGER),
            FieldConfig("slug", FieldType.TEXT, unique = true),
            FieldConfig("meta", FieldType.JSON),
        ),
    )

    @Test
    fun `validator gecersiz konfigurasyonu yakalar`() {
        val bad = ServerConfig(
            server = ServerConfig().server.copy(port = 80),
            auth = AuthConfig(mode = AuthMode.JWT, jwtSecret = "kisa"),
            collections = listOf(CollectionConfig("Bad-Name", listOf(FieldConfig("id", FieldType.TEXT)))),
        )
        val errors = ConfigValidator.validate(bad)
        assertEquals(4, errors.size, errors.joinToString("\n"))
        assertTrue(ConfigValidator.validate(ServerConfig(collections = listOf(todos))).isEmpty())
    }

    @Test
    fun `postgres adresindeki veritabani adi`() {
        val url = "jdbc:postgresql://127.0.0.1:5432/postgres?sslmode=disable"
        assertEquals("postgres", PostgresUrl.databaseName(url))
        assertEquals("jdbc:postgresql://127.0.0.1:5432/uygulama?sslmode=disable", PostgresUrl.withDatabase(url, "uygulama"))
        assertEquals("jdbc:postgresql://h/db", PostgresUrl.withDatabase("jdbc:postgresql://h", "db"))
        assertNull(PostgresUrl.databaseName("jdbc:postgresql://h:5432/"))

        fun errors(url: String, create: Boolean) = ConfigValidator.validateDetailed(
            ServerConfig(database = DatabaseConfig(DbEngine.POSTGRES, url = url, user = "droid", createIfMissing = create)),
        ).map { it.path }
        assertEquals(listOf("database.name"), errors("jdbc:postgresql://h/Kotu-Ad", create = true))
        assertTrue(errors("jdbc:postgresql://h/Kotu-Ad", create = false).isEmpty(), "var olan veritabanının adı kısıtlanmaz")
        assertTrue(errors("jdbc:postgresql://h/uygulama", create = true).isEmpty())
        assertEquals(
            listOf("database.url"),
            errors("jdbc:postgresql://h/db?ssl=true&sslfactory=org.postgresql.ssl.NonValidatingFactory", create = false),
            "sertifika doğrulamayan SSL reddedilir",
        )
        assertTrue(errors("jdbc:postgresql://h/db?ssl=true&sslmode=verify-full", create = false).isEmpty())
        // Seçilmediyse JSON'a yazılmaz.
        assertFalse(ServerConfig().toJson().contains("createIfMissing"))
    }

    @Test
    fun `konfigurasyon json gidis donus`() {
        val cfg = ServerConfig(collections = listOf(todos))
        assertEquals(cfg, ServerConfig.fromJson(cfg.toJson()))
    }

    @Test
    fun `supervisor coken sureci yeniden baslatir`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val sup = ProcessSupervisor(scope) {}
        val dir = File(System.getProperty("java.io.tmpdir"), "ds-sup-test").apply { mkdirs() }
        sup.start(ProcessSupervisor.Spec("crasher", listOf("sh", "-c", "echo merhaba; exit 3"), dir, restart = RestartPolicy.ON_FAILURE, maxRestarts = 2))
        val final = withTimeout(15_000) {
            sup.statuses.first { it["crasher"]?.phase == ProcessSupervisor.Phase.FAILED }["crasher"]!!
        }
        assertEquals(2, final.restarts)
        assertEquals(3, final.lastExit)
        scope.cancel()
    }
}
