package dev.droidserver.core

import dev.droidserver.core.api.ApiException
import dev.droidserver.core.api.RecordService
import dev.droidserver.core.api.SearchText
import dev.droidserver.core.config.CollectionConfig
import dev.droidserver.core.config.ConfigValidator
import dev.droidserver.core.config.FieldConfig
import dev.droidserver.core.config.FieldType
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.db.SchemaMigrator
import dev.droidserver.core.db.SqlDriver
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Arama (`?q=`) ve alan indeksleri, iki lehçede. */
@RunWith(Parameterized::class)
class SearchTest(private val dialect: Dialect) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun dialects() = Dialect.entries.toList()
    }

    private val base = CollectionConfig(
        "places",
        listOf(FieldConfig("name", FieldType.TEXT), FieldConfig("note", FieldType.TEXT), FieldConfig("stars", FieldType.INTEGER)),
    )
    private lateinit var db: SqlDriver

    @Before
    fun open() {
        db = TestDb.create(dialect).open()
    }

    @After
    fun close() = db.close()

    private fun obj(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
        pairs.forEach { (k, v) -> put(k, if (v is Number) JsonPrimitive(v) else JsonPrimitive(v as String?)) }
    }

    private fun RecordService.find(q: String): List<String> =
        list(1, 50, "id", listOf("q" to q)).items.map { it.getValue("name").jsonPrimitive.content }

    @Test
    fun `normalize - Turkce ve buyuk kucuk harf`() {
        assertEquals("istanbul'da sut ve cig kofte", SearchText.normalize("  İSTANBUL'da  Süt ve ÇİĞ köfte "))
        assertEquals("ilik isik", SearchText.normalize("ILIK ışık"))
        assertEquals("cafe", SearchText.normalize("Café"))
        assertEquals(listOf("sut", "al"), SearchText.terms("Süt  AL süt"))
    }

    @Test
    fun `arama Turkce karakterden bagimsiz, kelimelerin hepsi gecmeli`() {
        val c = base.copy(search = listOf("name", "note"))
        SchemaMigrator(db).migrate(listOf(c))
        val svc = RecordService(db, c)
        svc.create(obj("name" to "İstanbul Çorbacısı", "note" to "Işıklı salon, güler yüzlü"))
        svc.create(obj("name" to "Ankara Kebap", "note" to "100% yerli, %50 indirim_yok"))
        svc.create(obj("name" to "İzmir Boyoz"))

        assertEquals(listOf("İstanbul Çorbacısı"), svc.find("istanbul"))
        assertEquals(listOf("İstanbul Çorbacısı"), svc.find("CORBACISI ışık"), "iki alanda, hepsi geçmeli")
        assertEquals(emptyList(), svc.find("istanbul kebap"))
        assertEquals(listOf("Ankara Kebap"), svc.find("%50"), "% harfiyen aranır")
        assertEquals(listOf("Ankara Kebap"), svc.find("indirim_yok"))
        assertEquals(emptyList(), svc.find("indirimXyok"), "_ joker değil")
        assertEquals(3, svc.list(1, 50, null, listOf("q" to "  ")).items.size, "boş sorgu filtre değil")

        // Güncelleme gönderilmeyen alanlarla birlikte yeniden hesaplanır.
        val id = svc.create(obj("name" to "Bursa", "note" to "iskender")).getValue("id").jsonPrimitive.content.toLong()
        svc.update(id, obj("name" to "Bursa Uludağ"))
        assertEquals(listOf("Bursa Uludağ"), svc.find("uludag iskender"))
        assertTrue("_search" !in svc.get(id), "gizli sütun yanıtta yok")
        assertFailsWith<ApiException> { svc.create(obj("name" to "x", "_search" to "hile")) }

        // Diğer filtrelerle birlikte.
        assertEquals(listOf("İzmir Boyoz"), svc.list(1, 50, null, listOf("q" to "boyoz", "name[like]" to "zmir")).items.map { it.getValue("name").jsonPrimitive.content })
        assertEquals(400, assertFailsWith<ApiException> { RecordService(db, base).list(1, 10, null, listOf("q" to "x")) }.status)
    }

    @Test
    fun `arama alanlari degisince ve hesaplanmamis satirlarda yeniden doldurulur`() {
        SchemaMigrator(db).migrate(listOf(base))
        RecordService(db, base).create(obj("name" to "Eski Kayıt", "note" to "Şöhretli"))

        val c = base.copy(search = listOf("name"))
        val plan = SchemaMigrator(db).plan(listOf(c))
        assertTrue("arama dizini yeniden hesaplanacak (places)" in plan.summary(), plan.summary())
        SchemaMigrator(db).migrate(listOf(c))
        assertEquals(listOf("Eski Kayıt"), RecordService(db, c).find("kayit"))
        assertEquals(emptyList(), RecordService(db, c).find("sohretli"))
        assertEquals("Şema güncel", SchemaMigrator(db).plan(listOf(c)).summary())

        val both = c.copy(search = listOf("name", "note"))
        SchemaMigrator(db).migrate(listOf(both))
        assertEquals(listOf("Eski Kayıt"), RecordService(db, both).find("sohretli"))

        // Veri taşıma / geri yükleme gibi dışarıdan eklenen satır (arama sütunu boş) bir sonraki deploy'da hesaplanır.
        db.execute("""INSERT INTO "places" ("name", "created_at", "updated_at") VALUES ('Dışarıdan', '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z')""")
        assertTrue("arama dizini" in SchemaMigrator(db).plan(listOf(both)).summary())
        SchemaMigrator(db).migrate(listOf(both))
        assertEquals(listOf("Dışarıdan"), RecordService(db, both).find("disaridan"))

        // Arama kapatılınca sütun yetim sayılmaz.
        assertEquals("Şema güncel", SchemaMigrator(db).plan(listOf(base)).summary())
    }

    @Test
    fun `alan indeksi eklenir ve kaldirilir`() {
        SchemaMigrator(db).migrate(listOf(base))
        val indexed = base.copy(fields = base.fields.map { if (it.name == "stars") it.copy(index = true) else it })
        assertEquals("1 indeks eklenecek", SchemaMigrator(db).plan(listOf(indexed)).summary())
        SchemaMigrator(db).migrate(listOf(indexed))
        val name = SchemaMigrator.relationIndexName(dev.droidserver.core.db.ColumnRef("places", "stars"))
        assertTrue(name in db.dialect.listIndexes(db, "places"))
        assertEquals("Şema güncel", SchemaMigrator(db).plan(listOf(indexed)).summary())
        assertEquals("1 indeks kaldırılacak", SchemaMigrator(db).plan(listOf(base)).summary())
        SchemaMigrator(db).migrate(listOf(base))
        assertTrue(name !in db.dialect.listIndexes(db, "places"))
    }

    @Test
    fun `dogrulama`() {
        fun errors(c: CollectionConfig) = ConfigValidator.validate(ServerConfig(collections = listOf(c)))
        assertTrue(errors(base.copy(search = listOf("yok"))).any { "arama alanı 'yok' yok" in it })
        assertTrue(errors(base.copy(search = listOf("stars"))).any { "yalnızca metin" in it })
        assertTrue(errors(base.copy(fields = base.fields + FieldConfig("meta", FieldType.JSON, index = true))).any { "indekslenemez" in it })
        assertTrue(errors(base.copy(search = listOf("name"))).isEmpty())
    }
}
