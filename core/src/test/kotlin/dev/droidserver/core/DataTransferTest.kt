package dev.droidserver.core

import dev.droidserver.core.api.ApiException
import dev.droidserver.core.api.RecordService
import dev.droidserver.core.api.droidServerModule
import dev.droidserver.core.config.AuthConfig
import dev.droidserver.core.config.AuthMode
import dev.droidserver.core.config.CollectionConfig
import dev.droidserver.core.config.FieldConfig
import dev.droidserver.core.config.FieldType
import dev.droidserver.core.config.FieldVisibility
import dev.droidserver.core.config.Rule
import dev.droidserver.core.config.Rules
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.data.Csv
import dev.droidserver.core.data.DataTransfer
import dev.droidserver.core.data.ImportMode
import dev.droidserver.core.data.TransferFormat
import dev.droidserver.core.db.SchemaMigrator
import dev.droidserver.core.db.SqlDriver
import dev.droidserver.core.files.FileStore
import dev.droidserver.core.files.Upload
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.StringWriter
import java.net.URLEncoder
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.assertContentEquals
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** CSV/JSON içe ve dışa aktarma, iki lehçede. */
@RunWith(Parameterized::class)
class DataTransferTest(private val dialect: Dialect) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun dialects() = Dialect.entries.toList()
    }

    private val key = "yonetici-anahtari-1234567890"
    private val products = CollectionConfig(
        "products",
        listOf(
            FieldConfig("name", FieldType.TEXT, required = true, unique = true),
            FieldConfig("price", FieldType.REAL),
            FieldConfig("stock", FieldType.INTEGER),
            FieldConfig("active", FieldType.BOOLEAN),
            FieldConfig("since", FieldType.DATETIME),
            FieldConfig("meta", FieldType.JSON),
            FieldConfig("cost", FieldType.REAL, visibility = FieldVisibility.ADMIN),
        ),
        rules = Rules(list = Rule.PUBLIC, view = Rule.PUBLIC),
    )
    private val config = ServerConfig(auth = AuthConfig(AuthMode.API_KEY, apiKeys = listOf(key)), collections = listOf(products))
    private lateinit var db: SqlDriver
    private lateinit var transfer: DataTransfer

    @Before
    fun open() {
        db = TestDb.create(dialect).open()
        SchemaMigrator(db).migrate(config.collections)
        transfer = DataTransfer(db, config)
    }

    @After
    fun close() = db.close()

    private fun count() = (db.query("SELECT COUNT(*) AS c FROM \"products\"", emptyList()).first()["c"] as Number).toInt()

    @Test
    fun `csv ice aktarma - eslesme, donusum, onizleme, hepsi ya da hicbiri`() {
        // Türkçe Excel: noktalı virgül, virgüllü ondalık, BOM, evet/hayır, tarih.
        val csv = "﻿Ürün;Fiyat;Stok;Aktif;Tarih;Not\r\n" +
            "Çay;12,5;10;evet;2026-10-14;x\r\n" +
            "\"Kahve; çekirdek\";40;;hayır;2026-10-14 09:30;\r\n" +
            "'=1+1;1;1;1;;\r\n"
        val mapping = mapOf("Ürün" to "name", "Fiyat" to "price", "Stok" to "stock", "Aktif" to "active", "Tarih" to "since", "Not" to null)
        val preview = transfer.import("products", TransferFormat.CSV, csv, mapping, dryRun = true)
        assertTrue(preview.errors.isEmpty(), preview.errors.toString())
        assertEquals(3, preview.total)
        assertEquals(3, preview.created)
        assertFalse(preview.applied)
        assertEquals(0, count(), "deneme yazmaz")
        val first = preview.sample.first()
        assertEquals("12.5", first.getValue("price").jsonPrimitive.content)
        assertEquals("true", first.getValue("active").jsonPrimitive.content)
        assertEquals("2026-10-14T00:00:00Z", first.getValue("since").jsonPrimitive.content)
        assertEquals("Kahve; çekirdek", preview.sample[1].getValue("name").jsonPrimitive.content)
        assertFalse("stock" in preview.sample[1], "boş hücre gönderilmez")
        assertEquals("=1+1", preview.sample[2].getValue("name").jsonPrimitive.content, "formül koruması geri alınır")

        val done = transfer.import("products", TransferFormat.CSV, csv, mapping)
        assertTrue(done.applied)
        assertEquals(3, count())

        // Benzersizlik ihlali ve dönüştürme hatası: hiçbiri yazılmaz, satırlar raporlanır.
        val bad = "name,price\r\nYeni,5\r\nYeni2,abc\r\nÇay,1\r\n"
        val r = transfer.import("products", TransferFormat.CSV, bad)
        assertFalse(r.applied)
        assertEquals(listOf(2, 3), r.errors.map { it.row })
        assertTrue("price" in r.errors[0].details)
        assertEquals(3, count())

        // Otomatik eşleme (büyük/küçük harf), bilinmeyen sütun yok sayılır; dosya hataları.
        assertEquals(mapOf("NAME" to "name", "renk" to null), transfer.import("products", TransferFormat.CSV, "NAME,renk\nx,mavi\n", dryRun = true).mapping)
        assertFailsWith<Exception> { transfer.import("products", TransferFormat.CSV, "renk\nmavi\n") }
        assertFailsWith<Exception> { transfer.import("products", TransferFormat.CSV, "name\n\"kapanmamış\n") }
        assertFailsWith<Exception> { transfer.import("products", TransferFormat.CSV, "name,price\nx,1\n", mapOf("name" to "name", "price" to "name")) }
    }

    @Test
    fun `json ve upsert, disa aktarma geri okunur`() {
        transfer.import("products", TransferFormat.JSON, """[{"name":"Çay","price":10,"meta":{"a":1}},{"name":"Su","stock":3,"cost":1.5}]""")
        assertEquals(2, count())
        val out = StringWriter()
        assertEquals(2, transfer.export("products", TransferFormat.CSV, out))
        val rows = Csv.parse(out.toString())
        assertEquals(listOf("id", "name", "price", "stock", "active", "since", "meta", "cost", "created_at", "updated_at"), rows[0])
        assertEquals("{\"a\":1}", rows[1][6])

        // Dışa aktarılan dosya düzenlenip upsert ile geri yüklenir: id'si olan güncellenir, olmayan eklenir.
        val edited = out.toString().replace("Çay,10.0", "Çay,11.0") + ",Ayran,7,,,,,,,\r\n"
        val r = transfer.import("products", TransferFormat.CSV, edited, mode = ImportMode.UPSERT)
        assertTrue(r.applied, r.errors.toString())
        assertEquals(2 to 1, r.updated to r.created)
        assertEquals(3, count())
        assertEquals(11.0, (db.query("SELECT \"price\" FROM \"products\" WHERE \"name\" = ?", listOf("Çay")).first()["price"] as Number).toDouble())

        val json = StringWriter().also { transfer.export("products", TransferFormat.JSON, it, params = listOf("name" to "Su")) }.toString()
        assertEquals(listOf("Su"), Json.parseToJsonElement(json).jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content })
        assertEquals("[]\n", StringWriter().also { transfer.export("products", TransferFormat.JSON, it, params = listOf("name" to "yok")) }.toString())
    }

    @Test
    fun `http uclari - yetki, sinir, gizli alanlar`() = testApplication {
        application { droidServerModule(config, db) }
        transfer.import("products", TransferFormat.JSON, """[{"name":"=HYPERLINK(\"x\")","cost":2},{"name":"Su","cost":1}]""")
        val csv = client.get("/api/collections/products/export?format=csv&delimiter=semicolon&sort=name")
        assertEquals(HttpStatusCode.OK, csv.status)
        assertTrue("products-" in csv.headers[HttpHeaders.ContentDisposition].orEmpty())
        val text = csv.bodyAsText()
        assertFalse("cost" in text, "yöneticiye özel alan herkese açık dışa aktarmada yok")
        assertTrue("'=HYPERLINK" in text, "formül olarak çalışmaz: $text")
        assertTrue(text.lines().first().contains(';'))
        assertTrue("cost" in client.get("/api/collections/products/export") { bearerAuth(key) }.bodyAsText())
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/collections/products/export?yok[gt]=1").status)

        suspend fun import(body: String, query: String = "", token: String? = key) = client.post("/api/collections/products/import?$query") {
            token?.let { bearerAuth(it) }
            setBody(body)
        }
        assertEquals(HttpStatusCode.Unauthorized, import("name\nx\n", token = null).status)
        val dry = import("Ad\nÇay\n", "dryRun=true&mapping=" + URLEncoder.encode("""{"Ad":"name"}""", "UTF-8"))
        assertEquals(HttpStatusCode.OK, dry.status, dry.bodyAsText())
        assertEquals("false", Json.parseToJsonElement(dry.bodyAsText()).jsonObject.getValue("applied").jsonPrimitive.content)
        val failed = import("name\nSu\n")
        assertEquals(HttpStatusCode.BadRequest, failed.status)
        assertEquals("1", Json.parseToJsonElement(failed.bodyAsText()).jsonObject.getValue("errors").jsonArray.single().jsonObject.getValue("row").jsonPrimitive.content)
        // Genel gövde sınırını (1 MB) aşan dosya içe aktarmada kabul edilir.
        val big = "name\n" + (1..60_000).joinToString("\n") { "ürün-$it-" + "x".repeat(12) } + "\n"
        val tooMany = import(big, "dryRun=true")
        assertEquals(HttpStatusCode.BadRequest, tooMany.status)
        assertTrue("En çok 10000 satır" in tooMany.bodyAsText(), tooMany.bodyAsText())
    }

    @Test
    fun `sablon - ipuclu basliklar, ornek satir geri okunur`() {
        val tasks = CollectionConfig(
            "tasks",
            listOf(
                FieldConfig("title", FieldType.TEXT, required = true),
                FieldConfig("status", FieldType.SELECT, required = true, options = listOf("açık", "bitti")),
                FieldConfig("tags", FieldType.MULTISELECT, options = listOf("ev", "iş")),
                FieldConfig("done", FieldType.BOOLEAN),
                FieldConfig("product", FieldType.RELATION, collection = "products"),
            ),
        )
        val cfg = config.copy(collections = listOf(products, tasks))
        SchemaMigrator(db).migrate(cfg.collections)
        val t = DataTransfer(db, cfg)
        val csv = t.template("tasks")
        val lines = csv.removePrefix(Csv.BOM.toString()).trim().lines()
        assertEquals(2, lines.size, csv)
        assertTrue("status (zorunlu; açık|bitti)" in lines[0], lines[0])
        assertTrue("tags (ev|iş; birden çok: virgülle)" in lines[0], lines[0])
        // Hedefi boş ilişki örnekte boş kalır; şablon olduğu gibi içe aktarılabilir.
        val r = t.import("tasks", TransferFormat.CSV, csv, dryRun = true)
        assertTrue(r.errors.isEmpty(), r.errors.toString())
        assertEquals(listOf("title", "status", "tags", "done", "product"), r.mapping.values.toList())
    }

    @Test
    fun `zip - dosyalarla tasima, gecersiz dosya, geri almada dosyalar silinir`() = testApplication {
        val docs = CollectionConfig(
            "docs",
            listOf(
                FieldConfig("title", FieldType.TEXT, required = true),
                FieldConfig("photo", FieldType.FILE, mimeTypes = listOf("image/*")),
                FieldConfig("extra", FieldType.FILE, multiple = true),
            ),
        )
        val cfg = config.copy(collections = listOf(products, docs))
        SchemaMigrator(db).migrate(cfg.collections)
        val dir = Files.createTempDirectory("ds-zip").toFile()
        val store = FileStore(File(dir, "storage"))
        application { droidServerModule(cfg, db, files = store) }
        val t = DataTransfer(db, cfg, store)
        val svc = RecordService(db, docs, collections = cfg.schemaCollections(), files = store)
        fun upload(field: String, name: String, bytes: ByteArray): Upload =
            store.accept(docs.fields.first { it.name == field }, store.newTemp().apply { writeBytes(bytes) }, bytes.size.toLong(), bytes.copyOf(minOf(bytes.size, 512)), name)
        fun zip(vararg entries: Pair<String, ByteArray>) = ByteArrayOutputStream().also { o ->
            ZipOutputStream(o).use { z -> entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        }.toByteArray()
        fun unzip(bytes: ByteArray) = ZipInputStream(bytes.inputStream()).use { z -> generateSequence { z.nextEntry }.map { it.name to z.readBytes() }.toList().toMap() }
        fun diskFiles() = File(store.root, "docs").walk().filter { it.isFile }.count()
        fun str(o: JsonObject, k: String) = o.getValue(k).jsonPrimitive.content
        try {
            val png = FilesHttpTest.PNG
            val a = svc.create(Json.parseToJsonElement("""{"title":"a"}""").jsonObject, uploads = listOf(upload("photo", "kedi.png", png), upload("extra", "not.txt", "merhaba".toByteArray())))
            svc.create(Json.parseToJsonElement("""{"title":"b"}""").jsonObject)
            val aId = str(a, "id")
            val photo = str(a, "photo")
            val extra = a.getValue("extra").jsonArray.single().jsonPrimitive.content

            // Dışa aktarma: data.json ve kaydın dosyaları.
            val exported = ByteArrayOutputStream().also { assertEquals(2, t.exportZip("docs", it)) }.toByteArray()
            val entries = unzip(exported)
            assertEquals(setOf("data.json", "files/$aId/$photo", "files/$aId/$extra"), entries.keys)
            assertContentEquals(png, entries.getValue("files/$aId/$photo"))
            assertEquals(2, diskFiles())

            // Deneme: kayıt ve dosya kalmaz.
            val dry = t.importZip("docs", exported.inputStream(), dryRun = true)
            assertTrue(dry.errors.isEmpty(), dry.errors.toString())
            assertEquals(2, dry.created)
            assertFalse(dry.applied)
            assertEquals(2, diskFiles(), "deneme dosya bırakmaz")

            // Gerçek aktarma: dosyalar yeni kayda kopyalanır.
            val done = t.importZip("docs", exported.inputStream())
            assertTrue(done.applied, done.errors.toString())
            assertEquals(4, diskFiles())
            val copy = svc.list(1, 10, "-id", listOf("title" to "a")).items.first()
            val copyId = str(copy, "id").toLong()
            assertTrue(copyId.toString() != aId)
            assertContentEquals(png, store.file("docs", copyId, str(copy, "photo"))!!.readBytes())

            // Bir satır hatalıysa önceki satırların taşınan dosyaları da silinir.
            val data = """[{"id":$aId,"title":"c","photo":"$photo"},{"id":99,"title":null}]"""
            val failed = t.importZip("docs", zip("data.json" to data.toByteArray(), "files/$aId/$photo" to png).inputStream())
            assertFalse(failed.applied)
            assertEquals(listOf(2), failed.errors.map { it.row })
            assertEquals(4, diskFiles(), "geri alınan aktarma dosya bırakmaz")

            // İçerik tipi alanın izinlerine uymazsa, zip'te olmayan dosya ve id'siz satır: satır hatası.
            val bad = t.importZip(
                "docs",
                zip(
                    "data.json" to """[{"id":1,"title":"x","photo":"sahte.png"},{"id":2,"title":"y","photo":"yok.png"},{"title":"z","extra":["a.txt"]}]""".toByteArray(),
                    "files/1/sahte.png" to "metin".toByteArray(),
                ).inputStream(),
            )
            assertEquals(listOf(1, 2, 3), bad.errors.map { it.row }, bad.errors.toString())
            assertTrue(bad.errors.all { "photo" in it.details || "extra" in it.details })

            // Güncelleme: eski dosya ancak işlem kalıcı olunca silinir.
            val upsert = """[{"id":$aId,"title":"a2","photo":"yeni.png"}]""".toByteArray()
            val newPng = png.copyOf().also { it[100] = 7 }
            assertFalse(t.importZip("docs", zip("data.json" to upsert, "files/$aId/yeni.png" to newPng).inputStream(), mode = ImportMode.UPSERT, dryRun = true).applied)
            assertTrue(store.file("docs", aId.toLong(), photo)!!.isFile, "deneme eski dosyayı silmez")
            assertTrue(t.importZip("docs", zip("data.json" to upsert, "files/$aId/yeni.png" to newPng).inputStream(), mode = ImportMode.UPSERT).applied)
            val updated = svc.get(aId.toLong())
            assertFalse(store.file("docs", aId.toLong(), photo)!!.exists())
            assertContentEquals(newPng, store.file("docs", aId.toLong(), str(updated, "photo"))!!.readBytes())
            assertEquals(extra, updated.getValue("extra").jsonArray.single().jsonPrimitive.content, "verideki olmayan alan değişmez")

            assertEquals(400, assertFailsWith<ApiException> { t.importZip("docs", zip("x.txt" to byteArrayOf(1)).inputStream()) }.status)
            assertEquals(400, assertFailsWith<ApiException> { t.import("docs", TransferFormat.JSON, """[{"title":"x","photo":"a.png"}]""") }.status)

            // HTTP: format=zip ile aynı uçlar.
            val http = client.get("/api/collections/docs/export?format=zip") { bearerAuth(key) }
            assertEquals(HttpStatusCode.OK, http.status)
            assertEquals("application/zip", http.headers[HttpHeaders.ContentType])
            assertTrue("data.json" in unzip(http.bodyAsBytes()).keys)
            val imported = client.post("/api/collections/docs/import?format=zip&dryRun=true") { bearerAuth(key); setBody(http.bodyAsBytes()) }
            assertEquals(HttpStatusCode.OK, imported.status, imported.bodyAsText())
            assertEquals("4", Json.parseToJsonElement(imported.bodyAsText()).jsonObject.getValue("created").jsonPrimitive.content)
        } finally {
            dir.deleteRecursively()
        }
    }
}
