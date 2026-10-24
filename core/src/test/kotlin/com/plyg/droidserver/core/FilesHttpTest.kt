package com.plyg.droidserver.core

import com.plyg.droidserver.core.api.droidServerModule
import com.plyg.droidserver.core.config.AuthConfig
import com.plyg.droidserver.core.config.AuthMode
import com.plyg.droidserver.core.config.CollectionConfig
import com.plyg.droidserver.core.config.FieldConfig
import com.plyg.droidserver.core.config.FieldType
import com.plyg.droidserver.core.config.HttpConfig
import com.plyg.droidserver.core.config.LimitsConfig
import com.plyg.droidserver.core.config.OnDelete
import com.plyg.droidserver.core.config.Rule
import com.plyg.droidserver.core.config.Rules
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.config.StaticConfig
import com.plyg.droidserver.core.db.SchemaMigrator
import com.plyg.droidserver.core.db.SqlDriver
import com.plyg.droidserver.core.files.FileStore
import com.plyg.droidserver.core.files.ImageResizer
import com.plyg.droidserver.core.files.StaticSite
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
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
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Dosya alanı (yükleme, indirme, küçük resim, silme) ve statik site; iki lehçede. */
@RunWith(Parameterized::class)
class FilesHttpTest(private val dialect: Dialect) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun dialects() = Dialect.entries.toList()

        val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(200) { it.toByte() }
        val PDF = "%PDF-1.7\n".toByteArray() + ByteArray(100) { 1 }
    }

    private val key = "yonetici-anahtari-1234567890"
    private val albums = CollectionConfig("albums", listOf(FieldConfig("name", FieldType.TEXT)))
    private val photos = CollectionConfig(
        "photos",
        listOf(
            FieldConfig("title", FieldType.TEXT),
            FieldConfig("image", FieldType.FILE, maxSizeMb = 1, mimeTypes = listOf("image/*"), thumbs = listOf("50x50")),
            FieldConfig("docs", FieldType.FILE, multiple = true),
            FieldConfig("album", FieldType.RELATION, collection = "albums", onDelete = OnDelete.CASCADE),
        ),
        rules = Rules(list = Rule.ADMIN, view = Rule.ADMIN),
    )
    private lateinit var db: SqlDriver
    private lateinit var dir: File
    private lateinit var store: FileStore
    private lateinit var www: File
    private var resized = 0

    /** Test için küçük resim: kaynağı kopyalar. */
    private val resizer = ImageResizer { source, target, _, _ -> resized++; source.copyTo(target, overwrite = true); true }

    private fun config(limits: LimitsConfig = LimitsConfig()) = ServerConfig(
        server = HttpConfig(limits = limits),
        auth = AuthConfig(AuthMode.API_KEY, apiKeys = listOf(key)),
        collections = listOf(albums, photos),
        static = StaticConfig(enabled = true),
    )

    @Before
    fun open() {
        db = TestDb.create(dialect).open()
        SchemaMigrator(db).migrate(config().schemaCollections())
        dir = Files.createTempDirectory("ds-files").toFile()
        store = FileStore(File(dir, "storage"))
        www = File(dir, "www")
    }

    @After
    fun close() {
        db.close()
        dir.deleteRecursively()
    }

    private fun json(r: String) = Json.parseToJsonElement(r).jsonObject
    private fun JsonObject.str(k: String) = getValue(k).jsonPrimitive.content

    private fun form(fields: Map<String, String> = emptyMap(), files: List<Triple<String, String, ByteArray>> = emptyList()) =
        MultiPartFormDataContent(formData {
            fields.forEach { (k, v) -> append(k, v) }
            files.forEach { (field, name, bytes) ->
                append(field, bytes, Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"$name\"") })
            }
        })

    private suspend fun HttpClient.upload(
        fields: Map<String, String> = emptyMap(),
        files: List<Triple<String, String, ByteArray>> = emptyList(),
        id: Long? = null,
    ): HttpResponse = if (id == null) post("/api/collections/photos/records") { bearerAuth(key); setBody(form(fields, files)) }
    else patch("/api/collections/photos/records/$id") { bearerAuth(key); setBody(form(fields, files)) }

    private fun tempFiles() = File(store.root, ".tmp").listFiles().orEmpty().toList()

    @Test
    fun `yukleme, indirme, degistirme, silme`() = testApplication {
        application { droidServerModule(config(LimitsConfig(maxBodyKb = 1)), db, files = store, www = www, resizer = resizer) }
        // Gövde sınırı 1 KB olsa da dosyalar alanın kendi sınırına (1 MB) tabidir; form alanları tiplerine çevrilir.
        val big = PNG + ByteArray(100_000)
        val r = client.upload(mapOf("title" to "deniz"), listOf(Triple("image", "Tatil Fotoğrafı.png", big), Triple("docs", "a.pdf", PDF)))
        assertEquals(HttpStatusCode.Created, r.status, r.bodyAsText())
        val rec = json(r.bodyAsText())
        val id = rec.str("id").toLong()
        val image = rec.str("image")
        assertTrue(Regex("^tatil_foto_raf_[a-z0-9]{10}\\.png$").matches(image), image)
        assertEquals(1, rec.getValue("docs").jsonArray.size)
        assertTrue(store.file("photos", id, image)!!.isFile)

        val dl = client.get("/api/files/photos/$id/$image") { bearerAuth(key) }
        assertEquals(HttpStatusCode.OK, dl.status)
        assertContentEquals(big, dl.bodyAsBytes())
        assertEquals(ContentType.Image.PNG, dl.contentType())
        assertTrue(dl.headers[HttpHeaders.ContentDisposition]!!.startsWith("inline"))
        assertTrue("sandbox" in dl.headers["Content-Security-Policy"].orEmpty())

        // Yetki: kaydın view kuralı (yönetici). Yanlış ad, başka kaydın adı ve yol denemeleri 404.
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/files/photos/$id/$image").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/files/photos/$id/yok_aaaaaaaaaa.png") { bearerAuth(key) }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/files/photos/999/$image") { bearerAuth(key) }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/files/photos/$id/..%2F..%2Fx") { bearerAuth(key) }.status)

        // Küçük resim: izinli boyutlar (100x100 her zaman) üretilir ve önbelleğe alınır.
        assertEquals(HttpStatusCode.OK, client.get("/api/files/photos/$id/$image?thumb=50x50") { bearerAuth(key) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/files/photos/$id/$image?thumb=50x50") { bearerAuth(key) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/files/photos/$id/$image?thumb=100x100") { bearerAuth(key) }.status)
        assertEquals(2, resized, "aynı boyut bir kez üretilir")
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/files/photos/$id/$image?thumb=999x999") { bearerAuth(key) }.status)

        // Tekli alanda yeni dosya eskisinin yerine geçer (eskisi ve küçük resimleri silinir); çoklu alanda eklenir.
        val r2 = client.upload(files = listOf(Triple("image", "yeni.png", PNG), Triple("docs", "b.pdf", PDF)), id = id)
        assertEquals(HttpStatusCode.OK, r2.status, r2.bodyAsText())
        val rec2 = json(r2.bodyAsText())
        assertFalse(store.file("photos", id, image)!!.exists(), "eski dosya silindi")
        assertTrue(File(store.recordDir("photos", id), ".thumbs").listFiles().orEmpty().isEmpty(), "eski küçük resimler silindi")
        val docs = rec2.getValue("docs").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(2, docs.size)

        // JSON ile tutulacakları seçmek: yalnızca kayıttaki adlar.
        val keep = client.patch("/api/collections/photos/records/$id") {
            bearerAuth(key); contentType(ContentType.Application.Json); setBody("""{"docs":["${docs[1]}"]}""")
        }
        assertEquals(HttpStatusCode.OK, keep.status, keep.bodyAsText())
        assertFalse(store.file("photos", id, docs[0])!!.exists())
        val foreign = client.patch("/api/collections/photos/records/$id") {
            bearerAuth(key); contentType(ContentType.Application.Json); setBody("""{"docs":["baska_aaaaaaaaaa.pdf"]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, foreign.status)
        val created = client.post("/api/collections/photos/records") {
            bearerAuth(key); contentType(ContentType.Application.Json); setBody("""{"title":"x","image":"${rec2.str("image")}"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, created.status, "başka kaydın dosyasına bağlanılamaz")

        // Kayıt silinince klasörü de silinir.
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/collections/photos/records/$id") { bearerAuth(key) }.status)
        assertFalse(store.recordDir("photos", id).exists())
        assertTrue(tempFiles().isEmpty(), "geçici dosya kalmadı: ${tempFiles()}")
    }

    @Test
    fun `sinir asan, sahte uzantili ve izinsiz tipte dosya reddedilir`() = testApplication {
        application { droidServerModule(config(), db, files = store, www = www) }
        val tooBig = client.upload(files = listOf(Triple("image", "buyuk.png", PNG + ByteArray(1_200_000))))
        assertEquals(HttpStatusCode.PayloadTooLarge, tooBig.status, tooBig.bodyAsText())

        val fake = client.upload(files = listOf(Triple("image", "resim.png", "<html><script>alert(1)</script>".toByteArray())))
        assertEquals(HttpStatusCode.BadRequest, fake.status)
        assertTrue("uzantısıyla" in fake.bodyAsText(), fake.bodyAsText())

        val notImage = client.upload(files = listOf(Triple("image", "belge.pdf", PDF)))
        assertEquals(HttpStatusCode.BadRequest, notImage.status)
        assertTrue("izin verilmeyen" in notImage.bodyAsText(), notImage.bodyAsText())

        assertEquals(HttpStatusCode.BadRequest, client.upload(files = listOf(Triple("title", "a.png", PNG))).status, "dosya alanı değil")
        assertEquals(HttpStatusCode.BadRequest, client.upload(files = listOf(Triple("image", "a.png", PNG), Triple("image", "b.png", PNG))).status)

        // Uzantısız / bilinmeyen uzantılı metin, tipi içerikten alır ve .txt olarak saklanır (tarayıcıda sayfa olarak açılmaz).
        val html = client.upload(files = listOf(Triple("docs", "sayfa.html", "<html>merhaba</html>".toByteArray())))
        assertEquals(HttpStatusCode.Created, html.status, html.bodyAsText())
        val name = json(html.bodyAsText()).getValue("docs").jsonArray.single().jsonPrimitive.content
        assertTrue(name.endsWith(".txt"), name)
        val dl = client.get("/api/files/photos/${json(html.bodyAsText()).str("id")}/$name") { bearerAuth(key) }
        assertEquals(ContentType.Text.Plain, dl.contentType()?.withoutParameters())

        assertEquals(1, db.query("SELECT COUNT(*) AS n FROM \"photos\"").single()["n"].toString().toInt(), "reddedilenler kayıt oluşturmadı")
        assertTrue(tempFiles().isEmpty(), "geçici dosya kalmadı: ${tempFiles()}")
    }

    @Test
    fun `iliskiyle silinen kayitlarin dosyalari da silinir`() = testApplication {
        application { droidServerModule(config(), db, files = store, www = www) }
        val album = client.post("/api/collections/albums/records") {
            bearerAuth(key); contentType(ContentType.Application.Json); setBody("""{"name":"yaz"}""")
        }
        val albumId = json(album.bodyAsText()).str("id")
        val photo = client.upload(mapOf("album" to albumId), listOf(Triple("image", "a.png", PNG)))
        assertEquals(HttpStatusCode.Created, photo.status, photo.bodyAsText())
        val photoId = json(photo.bodyAsText()).str("id").toLong()
        assertTrue(store.recordDir("photos", photoId).exists())
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/collections/albums/records/$albumId") { bearerAuth(key) }.status)
        assertFalse(store.recordDir("photos", photoId).exists(), "cascade ile silinen kaydın klasörü")
    }

    @Test
    fun `statik site`() = testApplication {
        application { droidServerModule(config(), db, files = store, www = www) }
        val report = StaticSite.install(zip("site/index.html" to "<h1>ana</h1>", "site/app.js" to "x()", "site/img/logo.png" to "png"), www)
        assertEquals("site", report.strippedFolder)
        assertTrue(report.hasIndex)

        assertEquals("<h1>ana</h1>", client.get("/").bodyAsText())
        val js = client.get("/app.js")
        assertEquals("x()", js.bodyAsText())
        assertEquals("no-cache", js.headers[HttpHeaders.CacheControl])
        assertEquals("<h1>ana</h1>", client.get("/urunler/42").bodyAsText(), "SPA yönlendirmesi")
        assertEquals(HttpStatusCode.NotFound, client.get("/yok.png").status)
        val api = client.get("/api/bilinmeyen")
        assertEquals(HttpStatusCode.NotFound, api.status)
        assertTrue(api.bodyAsText().startsWith("{"), "API yolu siteye düşmez")
        assertEquals(HttpStatusCode.OK, client.get("/api/health").status)
        assertEquals("no-store", client.get("/api/health").headers[HttpHeaders.CacheControl])

        val site = StaticSite(www)
        assertNull(site.resolve(listOf("..", "storage"), spaFallback = false))
        assertNull(site.resolve(listOf(".gizli"), spaFallback = true))

        // Klasör dışına yazan zip reddedilir; eski site yerinde kalır.
        val bad = runCatching { StaticSite.install(zip("index.html" to "yeni", "../kacak.txt" to "x"), www) }
        assertTrue(bad.isFailure)
        assertFalse(File(dir, "kacak.txt").exists())
        assertEquals("<h1>ana</h1>", client.get("/").bodyAsText())
    }

    private fun zip(vararg entries: Pair<String, String>): java.io.InputStream {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, text) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(text.toByteArray())
                z.closeEntry()
            }
        }
        return out.toByteArray().inputStream()
    }
}
