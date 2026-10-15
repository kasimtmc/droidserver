package dev.droidserver.core

import dev.droidserver.core.api.RecordService
import dev.droidserver.core.backup.BackupArchive
import dev.droidserver.core.backup.BackupKind
import dev.droidserver.core.backup.BackupSchedule
import dev.droidserver.core.backup.BackupStore
import dev.droidserver.core.config.AuthConfig
import dev.droidserver.core.config.AuthMode
import dev.droidserver.core.config.CollectionConfig
import dev.droidserver.core.config.FieldConfig
import dev.droidserver.core.config.FieldType
import dev.droidserver.core.config.HttpConfig
import dev.droidserver.core.config.OnDelete
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.config.UsersConfig
import dev.droidserver.core.db.SchemaMigrator
import dev.droidserver.core.server.ServerEngine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.nio.file.Files
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Yedek al → geri yükle; kaynak lehçe parametre, hedef her iki lehçe (SQLite yedeği PostgreSQL'e de yüklenir). */
@RunWith(Parameterized::class)
class BackupTest(private val source: Dialect) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun dialects() = Dialect.entries.toList()
    }

    private val authors = CollectionConfig("authors", listOf(FieldConfig("name", FieldType.TEXT, unique = true)))
    private val posts = CollectionConfig(
        "posts",
        listOf(
            FieldConfig("title", FieldType.TEXT, required = true),
            FieldConfig("views", FieldType.INTEGER),
            FieldConfig("score", FieldType.REAL),
            FieldConfig("live", FieldType.BOOLEAN),
            FieldConfig("at", FieldType.DATETIME),
            FieldConfig("meta", FieldType.JSON),
            FieldConfig("author", FieldType.RELATION, collection = "authors", onDelete = OnDelete.CASCADE),
            FieldConfig("cover", FieldType.FILE),
        ),
    )
    private val config = ServerConfig(
        auth = AuthConfig(AuthMode.API_KEY, apiKeys = listOf("secret:api_key_1"), users = UsersConfig(enabled = true)),
        collections = listOf(authors, posts),
    )
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("ds-backup").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun seed(db: dev.droidserver.core.db.SqlDriver) {
        SchemaMigrator(db).migrate(config.schemaCollections())
        // PostgreSQL zamanı mikrosaniye hassasiyetinde saklar; karşılaştırma için kaynak da öyle.
        val clock = { java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS) }
        val a = RecordService(db, authors, clock, config.schemaCollections())
        val p = RecordService(db, posts, clock, config.schemaCollections())
        a.create(buildJsonObject { put("name", JsonPrimitive("Ali")) })
        a.create(buildJsonObject { put("name", JsonPrimitive("Ayşe")) })
        a.delete(1) // kimlik boşluğu: geri yüklemede kimlikler korunmalı
        p.create(buildJsonObject {
            put("title", JsonPrimitive("İlk yazı \"tırnaklı\"\nsatırlı"))
            put("views", JsonPrimitive(9007199254740993))
            put("score", JsonPrimitive(0.25))
            put("live", JsonPrimitive(true))
            put("at", JsonPrimitive("2026-10-13T10:15:30.123Z"))
            put("meta", buildJsonObject { put("etiket", JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive(1)))) })
            put("author", JsonPrimitive(2))
        })
        p.create(buildJsonObject { put("title", JsonPrimitive("Boş alanlı")) })
        db.execute("UPDATE \"posts\" SET \"cover\" = ? WHERE \"id\" = 1", listOf("kapak_aaaaaaaaaa.png"))
    }

    private fun dirs(root: File) = BackupArchive.DIRS.associateWith { File(root, it) }

    @Test
    fun `yedek alip iki lehceye geri yukleme`() {
        val src = TestDb.create(source).open()
        val files = File(dir, "kaynak")
        File(files, "storage/posts/1").mkdirs()
        File(files, "storage/posts/1/kapak_aaaaaaaaaa.png").writeText("png")
        File(files, "scripts").mkdirs()
        File(files, "scripts/main.js").writeText("// script")
        File(files, "www/.gizli").apply { parentFile.mkdirs() }.writeText("x")
        val zip = File(dir, "yedek.zip")
        val expected = src.use { db ->
            seed(db)
            val m = zip.outputStream().use { BackupArchive.create(it, db, config, config.toJson(), dirs(files)) }
            assertEquals(mapOf("authors" to 1L, "posts" to 2L, "_users" to 0L, "_sessions" to 0L), m.tables)
            RecordService(db, posts, collections = config.schemaCollections()).list(1, 10, "id", emptyList()).items
        }
        ZipFile(zip).use { z ->
            assertTrue(z.getEntry("files/storage/posts/1/kapak_aaaaaaaaaa.png") != null)
            assertTrue(z.getEntry("files/www/.gizli") == null, "gizli dosyalar yedeğe girmez")
            assertFalse(z.getInputStream(z.getEntry("server.json")).readBytes().decodeToString().contains("ds_"), "sır değerleri yok")
        }

        for (target in Dialect.entries) {
            val db = TestDb.create(target).open()
            db.use {
                // Hedefte önceden başka veri var: yedekteki koleksiyonların tabloları yedekteki hale gelir.
                SchemaMigrator(it).migrate(listOf(CollectionConfig("posts", listOf(FieldConfig("title", FieldType.TEXT)))))
                it.execute("INSERT INTO \"posts\" (\"title\", \"created_at\", \"updated_at\") VALUES ('eski', '2020-01-01T00:00:00Z', '2020-01-01T00:00:00Z')".let { sql ->
                    if (target == Dialect.POSTGRES) sql.replace("'2020-01-01T00:00:00Z'", "now()") else sql
                })
                val out = File(dir, "hedef-$target")
                File(out, "storage/eski.txt").apply { parentFile.mkdirs() }.writeText("silinecek")
                val (_, cfg) = BackupArchive.inspect(zip)
                BackupArchive.restore(zip, it, cfg, dirs(out))
                val svc = RecordService(it, posts, collections = config.schemaCollections())
                assertEquals(expected, svc.list(1, 10, "id", emptyList()).items, "$source → $target")
                assertEquals(listOf(2L), it.query("SELECT \"id\" FROM \"authors\"").map { r -> (r["id"] as Number).toLong() })
                // Kimlik sayacı kaldığı yerden devam eder.
                val next = svc.create(buildJsonObject { put("title", JsonPrimitive("yeni")) })
                assertEquals("3", next["id"].toString())
                assertEquals("png", File(out, "storage/posts/1/kapak_aaaaaaaaaa.png").readText())
                assertFalse(File(out, "storage/eski.txt").exists())
                assertEquals("// script", File(out, "scripts/main.js").readText())
            }
        }
    }

    @Test
    fun `bozuk veya eksik yedek reddedilir, basarisiz geri yukleme hicbir seyi degistirmez`() {
        val src = TestDb.create(source).open()
        val zip = File(dir, "yedek.zip")
        src.use { db ->
            seed(db)
            zip.outputStream().use { BackupArchive.create(it, db, config, config.toJson(), emptyMap()) }
        }
        fun rewrite(name: String, transform: (String, ByteArray) -> ByteArray?): File {
            val out = File(dir, name)
            ZipFile(zip).use { z ->
                ZipOutputStream(out.outputStream()).use { o ->
                    for (e in z.entries()) {
                        val bytes = transform(e.name, z.getInputStream(e).readBytes()) ?: continue
                        o.putNextEntry(ZipEntry(e.name)); o.write(bytes); o.closeEntry()
                    }
                }
            }
            return out
        }
        val truncated = rewrite("eksik.zip") { n, b -> if (n == "db/posts.jsonl") b.decodeToString().lines().first().toByteArray() else b }
        assertTrue("eksik" in assertFailsWith<IOException> { BackupArchive.inspect(truncated) }.message.orEmpty())
        val notBackup = rewrite("baska.zip") { n, b -> if (n == "manifest.json") null else b }
        assertFailsWith<IOException> { BackupArchive.inspect(notBackup) }

        // Benzersiz alanda tekrar eden kayıt: veritabanı işlemi geri alınır, hedefteki veri yerinde kalır.
        val dup = rewrite("tekrar.zip") { n, b ->
            when (n) {
                "db/authors.jsonl" -> (b.decodeToString().trim() + "\n" + b.decodeToString().trim().replace("\"id\":2", "\"id\":3") + "\n").toByteArray()
                "manifest.json" -> b.decodeToString().replace("\"authors\":1", "\"authors\":2").toByteArray()
                else -> b
            }
        }
        val target = TestDb.create(Dialect.SQLITE).open()
        target.use { db ->
            seed(db)
            val before = db.query("SELECT * FROM \"posts\" ORDER BY \"id\"")
            val (_, cfg) = BackupArchive.inspect(dup)
            assertFailsWith<Exception> { BackupArchive.restore(dup, db, cfg, emptyMap()) }
            assertEquals(before, db.query("SELECT * FROM \"posts\" ORDER BY \"id\""))
        }
    }

    @Test
    fun `calisan sunucudan yedek, depolama ve zamanlama`() {
        if (source != Dialect.SQLITE) return
        val testDb = TestDb.create(Dialect.SQLITE)
        val port = ServerSocket(0).use { it.localPort }
        val cfg = config.copy(
            server = HttpConfig(host = "127.0.0.1", port = port),
            auth = AuthConfig(AuthMode.API_KEY, apiKeys = listOf("a".repeat(20)), users = UsersConfig(enabled = true)),
            backup = config.backup.copy(enabled = true, keep = 2),
        )
        val engine = ServerEngine({ testDb.open() }, dataDir = dir) {}
        try {
            engine.deploy(cfg)
            File(dir, "scripts").mkdirs()
            File(dir, "scripts/a.js").writeText("// a")
            val infos = (1..4).map { engine.backup("""{"version":9}""", BackupKind.SCHEDULED) } + engine.backup("{}", BackupKind.MANUAL)
            val store = engine.backups!!
            assertEquals(3, store.list().size, "zamanlanmışlardan son 2 + elle alınan")
            assertTrue(store.list().any { it.name == infos.last().name && it.kind == BackupKind.MANUAL })
            assertTrue(infos[3].name in store.list().map { it.name } && infos[2].name in store.list().map { it.name }, "en yeni iki zamanlanmış kalır")
            assertTrue(ZipFile(infos.last().file).use { it.getEntry("files/scripts/a.js") != null })
        } finally {
            engine.stop()
        }

        val s = BackupStore(File(dir, "x"))
        val name = s.newFile(BackupKind.BEFORE_RESTORE, LocalDateTime.of(2026, 10, 13, 4, 0)).apply { writeText("x") }
        assertEquals(BackupKind.BEFORE_RESTORE, s.list().single().kind)
        assertEquals(name, s.find(name.name))
        val schedule = BackupSchedule("0 4 * * *")
        assertTrue(schedule.due(LocalDateTime.of(2026, 10, 13, 4, 0, 5)))
        assertFalse(schedule.due(LocalDateTime.of(2026, 10, 13, 4, 0, 40)), "aynı dakikada bir kez")
        assertFalse(schedule.due(LocalDateTime.of(2026, 10, 13, 4, 1)))
        assertTrue(schedule.due(LocalDateTime.of(2026, 10, 14, 4, 0)))
    }
}
