package dev.droidserver.core

import dev.droidserver.core.api.ApiException
import dev.droidserver.core.api.RecordService
import dev.droidserver.core.config.CollectionConfig
import dev.droidserver.core.config.FieldConfig
import dev.droidserver.core.config.FieldType
import dev.droidserver.core.config.HttpConfig
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.config.TypeChange
import dev.droidserver.core.config.TypeChangeAction
import dev.droidserver.core.db.SchemaBlockedException
import dev.droidserver.core.db.SchemaMigrator
import dev.droidserver.core.i18n.I18n
import dev.droidserver.core.i18n.Lang
import dev.droidserver.core.server.DeployException
import dev.droidserver.core.server.EngineState
import dev.droidserver.core.server.ServerEngine
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Şema planı her iki lehçede: SQLite ve PostgreSQL. */
@RunWith(Parameterized::class)
class SchemaPlanTest(private val dialect: Dialect) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun dialects() = Dialect.entries.toList()
    }

    private val notes = CollectionConfig(
        name = "notes",
        fields = listOf(FieldConfig("slug", FieldType.TEXT), FieldConfig("views", FieldType.INTEGER)),
    )

    private fun memDb() = TestDb.create(dialect).open()

    private fun CollectionConfig.withField(f: FieldConfig) = copy(fields = fields.filterNot { it.name == f.name } + f)

    private fun body(vararg pairs: Pair<String, Any?>) = JsonObject(pairs.associate { (k, v) ->
        k to when (v) {
            is String -> JsonPrimitive(v)
            is Number -> JsonPrimitive(v)
            else -> JsonPrimitive(null as String?)
        }
    })

    @Test
    fun `plan veritabanina yazmaz`() {
        memDb().use { db ->
            val plan = SchemaMigrator(db).plan(listOf(notes))
            assertEquals(listOf("notes"), plan.createTables)
            assertEquals(listOf("notes.slug", "notes.views"), plan.addColumns.map { it.ref.toString() })
            assertEquals("1 tablo oluşturulacak", plan.summary())
            assertTrue(db.dialect.listTables(db).isEmpty(), "plan tablo oluşturmamalı (_ds_meta dahil)")

            SchemaMigrator(db).migrate(listOf(notes))
            assertEquals("Şema güncel", SchemaMigrator(db).plan(listOf(notes)).summary())
        }
    }

    @Test
    fun `plan ozeti ve engelleyici sorunlar secilen dilde`() {
        val before = I18n.lang
        I18n.lang = Lang.EN
        try {
            memDb().use { db ->
                assertEquals("1 table(s) will be created", SchemaMigrator(db).plan(listOf(notes)).summary())
                SchemaMigrator(db).migrate(listOf(notes))
                assertEquals("Schema is up to date", SchemaMigrator(db).plan(listOf(notes)).summary())

                RecordService(db, notes).create(body("slug" to "a", "views" to 3))
                val plan = SchemaMigrator(db).plan(listOf(notes.withField(FieldConfig("views", FieldType.TEXT))))
                val blocker = plan.blockers.single()
                assertTrue(blocker.startsWith("notes.views: type change (integer → text), 1 records have a value."), blocker)
                assertTrue(blocker.contains("convert or clear") && blocker.contains("In JSON:"), blocker)
                assertTrue(plan.summary().contains("1 blocking problem(s)"), plan.summary())
            }
        } finally {
            I18n.lang = before
        }
    }

    @Test
    fun `dolu alanda secimsiz tip degisikligi engelleyici sorun olarak gorunur`() {
        memDb().use { db ->
            SchemaMigrator(db).migrate(listOf(notes))
            RecordService(db, notes).create(body("slug" to "a", "views" to 3))

            // Veritabanı tipi de değişiyor (INTEGER -> TEXT).
            val viewsAsText = notes.withField(FieldConfig("views", FieldType.TEXT))
            val plan = SchemaMigrator(db).plan(listOf(viewsAsText))
            assertEquals(1, plan.blockers.size, plan.blockers.joinToString())
            assertTrue(plan.blockers[0].startsWith("notes.views"), plan.blockers[0])
            assertTrue(plan.blockers[0].contains("dönüştür veya boşalt"), plan.blockers[0])
            assertTrue(plan.blockers[0].contains("\"from\": \"integer\""), plan.blockers[0])
            assertTrue(plan.summary().contains("1 engelleyici sorun"))
            assertFailsWith<SchemaBlockedException> { SchemaMigrator(db).migrate(listOf(viewsAsText)) }

            // SQLite'ta ikisi de TEXT'e eşlenir; tip değişikliği _ds_meta'daki kayıttan yakalanır (Postgres'te tip de farklı).
            val slugAsJson = notes.withField(FieldConfig("slug", FieldType.JSON))
            assertTrue(SchemaMigrator(db).plan(listOf(slugAsJson)).hasBlockers)

            // Başka bir eski tip için kaydedilmiş seçim bu değişikliğe uygulanmaz.
            val stale = notes.withField(FieldConfig("views", FieldType.TEXT, typeChange = TypeChange(FieldType.REAL, TypeChangeAction.CONVERT)))
            val stalePlan = SchemaMigrator(db).plan(listOf(stale))
            assertTrue(stalePlan.blockers.single().contains("real tipinden"), stalePlan.blockers.joinToString())

            // Alan konfigürasyondan çıkarılıp başka tiple geri eklenirse de engellenir.
            SchemaMigrator(db).migrate(listOf(notes.copy(fields = notes.fields.take(1))))
            assertTrue(SchemaMigrator(db).plan(listOf(notes.withField(FieldConfig("views", FieldType.BOOLEAN)))).hasBlockers)
        }
    }

    @Test
    fun `bos alanin tipi secim gerekmeden degisir`() {
        memDb().use { db ->
            SchemaMigrator(db).migrate(listOf(notes))
            RecordService(db, notes).create(body("slug" to "a")) // views boş

            val viewsAsReal = notes.withField(FieldConfig("views", FieldType.REAL))
            val plan = SchemaMigrator(db).plan(listOf(viewsAsReal))
            assertFalse(plan.hasBlockers, plan.blockers.joinToString())
            assertEquals("1 alanın tipi değişecek", plan.summary())
            SchemaMigrator(db).migrate(listOf(viewsAsReal))
            assertEquals("Şema güncel", SchemaMigrator(db).plan(listOf(viewsAsReal)).summary())
            assertTrue(db.dialect.matchesType(db.dialect.listColumns(db, "notes").getValue("views"), FieldType.REAL))
            assertEquals(2.5, RecordService(db, viewsAsReal).create(body("views" to 2.5))["views"]!!.jsonPrimitive.double)
        }
    }

    @Test
    fun `donusturme cevrilebilen degerleri cevirir, digerlerini bosaltir`() {
        val unique = notes.withField(FieldConfig("slug", FieldType.TEXT, unique = true))
            .withField(FieldConfig("views", FieldType.TEXT))
        memDb().use { db ->
            SchemaMigrator(db).migrate(listOf(unique))
            val svc = RecordService(db, unique)
            svc.create(body("slug" to "a", "views" to "42"))
            svc.create(body("slug" to "b", "views" to " 7 "))
            svc.create(body("slug" to "c", "views" to "abc"))
            svc.create(body("slug" to "d", "views" to "2.0"))
            svc.create(body("slug" to "e"))
            svc.create(body("slug" to "silinecek"))
            svc.delete(6) // silinen en büyük id yeniden kullanılmamalı

            val converted = unique.withField(
                FieldConfig("views", FieldType.INTEGER, typeChange = TypeChange(FieldType.TEXT, TypeChangeAction.CONVERT)),
            )
            val plan = SchemaMigrator(db).plan(listOf(converted))
            assertFalse(plan.hasBlockers, plan.blockers.joinToString())
            val change = plan.changeTypes.single()
            assertEquals(4L, change.values)
            assertEquals(1L, change.cleared)
            assertEquals(listOf("'abc'"), change.samples)
            assertTrue(plan.warnings.single().contains("1 değer integer tipine çevrilemiyor"), plan.warnings.joinToString())

            val report = SchemaMigrator(db).migrate(listOf(converted))
            assertEquals(listOf("notes.views text → integer"), report.changedTypes)
            assertEquals("Şema güncel", SchemaMigrator(db).plan(listOf(converted)).summary())

            val after = RecordService(db, converted)
            val views = after.list(1, 100, "id", emptyList()).items.map { it["slug"]!!.jsonPrimitive.content to it["views"] }
            assertEquals(
                listOf("a" to JsonPrimitive(42), "b" to JsonPrimitive(7), "c" to JsonNull, "d" to JsonPrimitive(2), "e" to JsonNull),
                views,
            )
            assertEquals(7L, after.create(body("slug" to "f", "views" to 1))["id"]!!.jsonPrimitive.long)
            // Başka alanın benzersiz indeksi yeniden kurulumdan sonra da geçerli.
            assertEquals(409, assertFailsWith<ApiException> { after.create(body("slug" to "a")) }.status)
        }
    }

    @Test
    fun `bosaltma tum degerleri siler, benzersiz indeks yeniden kurulur`() {
        val uniqueViews = notes.withField(FieldConfig("views", FieldType.INTEGER, unique = true))
        memDb().use { db ->
            SchemaMigrator(db).migrate(listOf(uniqueViews))
            RecordService(db, uniqueViews).create(body("slug" to "a", "views" to 5))
            RecordService(db, uniqueViews).create(body("slug" to "b", "views" to 6))

            val cleared = notes.withField(
                FieldConfig("views", FieldType.DATETIME, unique = true, typeChange = TypeChange(FieldType.INTEGER, TypeChangeAction.CLEAR)),
            )
            val plan = SchemaMigrator(db).plan(listOf(cleared))
            assertFalse(plan.hasBlockers, plan.blockers.joinToString())
            assertTrue(plan.warnings.single().contains("2 kayıttaki değer silinecek"), plan.warnings.joinToString())
            assertEquals("1 alanın tipi değişecek", plan.summary())

            SchemaMigrator(db).migrate(listOf(cleared))
            val svc = RecordService(db, cleared)
            assertTrue(svc.list(1, 10, null, emptyList()).items.all { it["views"] == JsonNull })
            svc.create(body("slug" to "c", "views" to "2026-10-13T10:00:00Z"))
            assertEquals(409, assertFailsWith<ApiException> { svc.create(body("slug" to "d", "views" to "2026-10-13T10:00:00Z")) }.status)
        }
    }

    @Test
    fun `donusturulen degerler tekrar ediyorsa benzersizlik engellenir`() {
        val uniqueText = notes.withField(FieldConfig("slug", FieldType.TEXT, unique = true))
        memDb().use { db ->
            SchemaMigrator(db).migrate(listOf(uniqueText))
            RecordService(db, uniqueText).create(body("slug" to "1"))
            RecordService(db, uniqueText).create(body("slug" to "01"))

            val asInt = notes.withField(
                FieldConfig("slug", FieldType.INTEGER, unique = true, typeChange = TypeChange(FieldType.TEXT, TypeChangeAction.CONVERT)),
            )
            val plan = SchemaMigrator(db).plan(listOf(asInt))
            assertTrue(plan.blockers.single().contains("'1' ×2"), plan.blockers.joinToString())
        }
    }

    @Test
    fun `ayni veritabani tipine eslenen degisiklikte metin JSONa cevrilir`() {
        memDb().use { db ->
            SchemaMigrator(db).migrate(listOf(notes))
            RecordService(db, notes).create(body("slug" to "{\"a\": 1}"))
            RecordService(db, notes).create(body("slug" to "düz metin"))

            val asJson = notes.withField(FieldConfig("slug", FieldType.JSON, typeChange = TypeChange(FieldType.TEXT, TypeChangeAction.CONVERT)))
            assertTrue(SchemaMigrator(db).plan(listOf(asJson)).warnings.isEmpty())
            SchemaMigrator(db).migrate(listOf(asJson))
            val slugs = RecordService(db, asJson).list(1, 10, "id", emptyList()).items.map { it["slug"] }
            assertEquals(listOf(buildJsonObject { put("a", JsonPrimitive(1)) }, JsonPrimitive("düz metin")), slugs)
        }
    }

    @Test
    fun `tekrar eden veriye unique eklemek engellenir`() {
        memDb().use { db ->
            SchemaMigrator(db).migrate(listOf(notes))
            val svc = RecordService(db, notes)
            svc.create(body("slug" to "a"))
            svc.create(body("slug" to "a"))
            svc.create(body("slug" to "b"))
            svc.create(body("views" to 1)) // NULL'lar çakışma sayılmaz
            svc.create(body("views" to 2))

            val uniqueSlug = notes.withField(FieldConfig("slug", FieldType.TEXT, unique = true))
            val blocked = SchemaMigrator(db).plan(listOf(uniqueSlug))
            assertEquals(1, blocked.blockers.size, blocked.blockers.joinToString())
            assertTrue(blocked.blockers[0].contains("'a' ×2"), blocked.blockers[0])
            assertTrue(blocked.addUniqueIndexes.isEmpty())

            svc.delete(2)
            val report = SchemaMigrator(db).migrate(listOf(uniqueSlug))
            assertEquals(listOf("notes.slug"), report.addedIndexes)
            val dup = assertFailsWith<ApiException> { svc.create(body("slug" to "b")) }
            assertEquals(409, dup.status) // SqliteDialect.isUniqueViolation

            val dropped = SchemaMigrator(db).plan(listOf(notes))
            assertEquals(listOf("notes.slug"), dropped.dropUniqueIndexes.map { it.toString() })
            assertFalse(dropped.hasBlockers)
        }
    }

    @Test
    fun `sonradan eklenen required bos kayitlar icin uyari verir, engellemez`() {
        memDb().use { db ->
            SchemaMigrator(db).migrate(listOf(notes))
            RecordService(db, notes).create(body("views" to 3)) // slug boş

            val plan = SchemaMigrator(db).plan(
                listOf(
                    notes.withField(FieldConfig("slug", FieldType.TEXT, required = true))
                        .withField(FieldConfig("title", FieldType.TEXT, required = true)),
                ),
            )
            assertFalse(plan.hasBlockers)
            assertEquals(2, plan.warnings.size, plan.warnings.joinToString())
            assertTrue(plan.warnings.any { it.startsWith("notes.slug") })
            assertTrue(plan.warnings.any { it.startsWith("notes.title") })
        }
    }

    @Test
    fun `engelleyici sorunda deploy reddedilir, calisan sunucu korunur`() {
        val testDb = TestDb.create(dialect)
        val engine = ServerEngine({ testDb.open() }) {}
        val port = ServerSocket(0).use { it.localPort }
        val cfg = ServerConfig(server = HttpConfig(host = "127.0.0.1", port = port), collections = listOf(notes))
        try {
            engine.deploy(cfg)
            val running = engine.state.value
            assertTrue(running is EngineState.Running)

            URI("http://127.0.0.1:$port/api/collections/notes/records").toURL().openConnection().let { it as HttpURLConnection }.run {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write("""{"views": 3}""".toByteArray()) }
                assertEquals(201, responseCode)
                disconnect()
            }
            val changed = cfg.copy(collections = listOf(notes.withField(FieldConfig("views", FieldType.TEXT))))
            val e = assertFailsWith<DeployException> { engine.deploy(changed) }
            assertTrue(e.errors.single().contains("notes.views"), e.errors.joinToString())
            assertEquals(running, engine.state.value)

            val conn = URI("http://127.0.0.1:$port/api/health").toURL().openConnection() as HttpURLConnection
            assertEquals(200, conn.responseCode)
            conn.disconnect()
        } finally {
            engine.stop()
        }
    }

    @Test
    fun `farkli tablolarda ayni ada dusen benzersiz indeksler cakismaz`() {
        // Eski adlandırmada ikisi de ux_a_b_c idi; ikincisi sessizce oluşmuyordu.
        val ab = CollectionConfig("a_b", listOf(FieldConfig("c", FieldType.TEXT, unique = true)))
        val a = CollectionConfig("a", listOf(FieldConfig("b_c", FieldType.TEXT, unique = true)))
        memDb().use { db ->
            val report = SchemaMigrator(db).migrate(listOf(ab, a))
            assertEquals(listOf("a_b.c", "a.b_c"), report.addedIndexes)
            assertEquals("Şema güncel", SchemaMigrator(db).plan(listOf(ab, a)).summary())

            RecordService(db, ab).create(body("c" to "x"))
            RecordService(db, a).create(body("b_c" to "x"))
            assertEquals(409, assertFailsWith<ApiException> { RecordService(db, ab).create(body("c" to "x")) }.status)
            assertEquals(409, assertFailsWith<ApiException> { RecordService(db, a).create(body("b_c" to "x")) }.status)
        }
    }

    @Test
    fun `eski adla olusturulmus benzersiz indeks taninir ve kaldirilabilir`() {
        val unique = notes.withField(FieldConfig("slug", FieldType.TEXT, unique = true))
        memDb().use { db ->
            SchemaMigrator(db).migrate(listOf(notes))
            db.execute("CREATE UNIQUE INDEX \"ux_notes_slug\" ON \"notes\" (\"slug\")") // önceki sürümün adı
            assertEquals("Şema güncel", SchemaMigrator(db).plan(listOf(unique)).summary())

            val drop = SchemaMigrator(db).plan(listOf(notes))
            assertEquals(listOf("ux_notes_slug"), drop.dropIndexNames)
            SchemaMigrator(db).apply(drop)
            assertTrue("ux_notes_slug" !in db.dialect.listIndexes(db, "notes"))
        }
    }
}
