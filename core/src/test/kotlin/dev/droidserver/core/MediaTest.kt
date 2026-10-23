package dev.droidserver.core

import dev.droidserver.core.ai.AiChat
import dev.droidserver.core.ai.AiImage
import dev.droidserver.core.ai.AiReply
import dev.droidserver.core.api.droidServerModule
import dev.droidserver.core.config.AiConfig
import dev.droidserver.core.config.AuthConfig
import dev.droidserver.core.config.AuthMode
import dev.droidserver.core.config.CollectionConfig
import dev.droidserver.core.config.ConfigValidator
import dev.droidserver.core.config.FieldConfig
import dev.droidserver.core.config.FieldType
import dev.droidserver.core.config.FileAiConfig
import dev.droidserver.core.config.ImageFormat
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.db.SchemaMigrator
import dev.droidserver.core.db.SqlDriver
import dev.droidserver.core.files.ExifGps
import dev.droidserver.core.files.FileStore
import dev.droidserver.core.files.ImageResizer
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Faz 18.5: konum bilgisini silme, dönüşüm, Range ve önbellek başlıkları, YZ ile etiket ve OCR. */
class MediaTest {
    private val key = "yonetici-anahtari-1234567890"
    private val photos = CollectionConfig(
        "photos",
        listOf(
            FieldConfig("image", FieldType.FILE, mimeTypes = listOf("image/*")),
            FieldConfig("raw", FieldType.FILE, keepLocation = true),
            FieldConfig("small", FieldType.FILE, convert = ImageFormat.WEBP, maxDimension = 1024),
            FieldConfig("scan", FieldType.FILE, ai = FileAiConfig(tags = "tags", text = "ocr")),
            FieldConfig("tags", FieldType.MULTISELECT, options = listOf("deniz", "kum", "dağ")),
            FieldConfig("ocr", FieldType.TEXT),
        ),
    )
    private val config = ServerConfig(auth = AuthConfig(AuthMode.API_KEY, apiKeys = listOf(key)), collections = listOf(photos), ai = AiConfig(enabled = true, apiKey = "x"))
    private lateinit var db: SqlDriver
    private lateinit var dir: File

    @Before
    fun open() {
        db = TestDb.create(Dialect.SQLITE).open()
        SchemaMigrator(db).migrate(config.schemaCollections())
        dir = Files.createTempDirectory("ds-media").toFile()
    }

    @After
    fun close() {
        db.close()
        dir.deleteRecursively()
    }

    /** IFD0: yön (6) ve GPS bölümü; GPS: enlem işareti (satır içi) ve enlem (24 bayt dışarıda). */
    private fun tiff(): ByteArray {
        val b = ByteBuffer.allocate(92).order(ByteOrder.LITTLE_ENDIAN)
        b.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(8)
        b.putShort(2)
        b.putShort(0x0112).putShort(3).putInt(1).putShort(6).putShort(0)
        b.putShort(0x8825.toShort()).putShort(4).putInt(1).putInt(38)
        b.putInt(0)
        b.putShort(2)
        b.putShort(1).putShort(2).putInt(2).put('N'.code.toByte()).put(0).putShort(0)
        b.putShort(2).putShort(5).putInt(3).putInt(68)
        b.putInt(0)
        b.putInt(41).putInt(1).putInt(0).putInt(1).putInt(30).putInt(1)
        return b.array()
    }

    private fun segment(marker: Int, payload: ByteArray) = byteArrayOf(0xFF.toByte(), marker.toByte(), ((payload.size + 2) shr 8).toByte(), (payload.size + 2).toByte()) + payload

    private fun jpeg(): ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
        segment(0xE1, "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiff()) +
        segment(0xE1, "http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta><exif:GPSLatitude>41,0N</exif:GPSLatitude></x:xmpmeta>".toByteArray()) +
        segment(0xDA, ByteArray(6)) + ByteArray(40) { 7 } + byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val t = type.toByteArray(Charsets.ISO_8859_1)
        val crc = CRC32().apply { update(t); update(data) }.value.toInt()
        return ByteBuffer.allocate(12 + data.size).putInt(data.size).put(t).put(data).putInt(crc).array()
    }

    private fun png() = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
        chunk("IHDR", ByteArray(13) { if (it in listOf(3, 7)) 1 else 0 }) + chunk("eXIf", tiff()) + chunk("IEND", ByteArray(0))

    private fun webp(): ByteArray {
        val exif = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiff()
        val body = "WEBP".toByteArray() + "VP8 ".toByteArray() + le(10) + ByteArray(10) + "EXIF".toByteArray() + le(exif.size) + exif
        return "RIFF".toByteArray() + le(body.size) + body
    }

    private fun le(n: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(n).array()

    /** GPS enlem verisi (41/1) ve XMP'deki konum kaldı mı. */
    private fun hasGps(b: ByteArray): Boolean {
        val lat = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(41).putInt(1).array()
        return b.toList().windowed(8).any { it == lat.toList() } || "41,0N" in String(b, Charsets.ISO_8859_1)
    }

    /** Yön girdisi (0x0112, SHORT, 1, 6) duruyor mu. */
    private fun hasOrientation(b: ByteArray) = b.toList().windowed(10).any { it == listOf<Byte>(0x12, 0x01, 3, 0, 1, 0, 0, 0, 6, 0) }

    @Test
    fun `konum bilgisi boyut degismeden silinir`() {
        for ((name, bytes, mime) in listOf(Triple("a.jpg", jpeg(), "image/jpeg"), Triple("a.png", png(), "image/png"), Triple("a.webp", webp(), "image/webp"))) {
            val f = File(dir, name).apply { writeBytes(bytes) }
            assertTrue(hasGps(bytes) && hasOrientation(bytes), name)
            assertTrue(ExifGps.strip(f, mime), name)
            val out = f.readBytes()
            assertEquals(bytes.size, out.size, name)
            assertFalse(hasGps(out), "$name: konum kaldı")
            assertTrue(hasOrientation(out), "$name: yön silinmemeli")
            assertFalse(ExifGps.strip(f, mime), "$name: ikinci kez bir şey yok")
            if (mime == "image/png") {
                // eXIf parçasının CRC'si yeniden hesaplandı.
                val at = out.size - 12 - (12 + 92)
                val data = out.copyOfRange(at + 8, at + 8 + 92)
                val crc = CRC32().apply { update("eXIf".toByteArray()); update(data) }.value.toInt()
                assertEquals(crc, ByteBuffer.wrap(out, at + 8 + 92, 4).int)
            }
        }
        // Bozuk dosya: dokunulmaz.
        val junk = File(dir, "x.jpg").apply { writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)) }
        assertFalse(ExifGps.strip(junk, "image/jpeg"))
    }

    private fun form(field: String, name: String, bytes: ByteArray) = MultiPartFormDataContent(formData {
        append(field, bytes, Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"$name\"") })
    })

    private suspend fun HttpClient.upload(field: String, name: String, bytes: ByteArray): JsonObject {
        val r: HttpResponse = post("/api/collections/photos/records") { bearerAuth(key); setBody(form(field, name, bytes)) }
        assertEquals(201, r.status.value, r.bodyAsText())
        return Json.parseToJsonElement(r.bodyAsText()).jsonObject
    }

    @Test
    fun `yukleme, donusum, range ve yz`() = testApplication {
        val seen = mutableListOf<AiImage>()
        val chat = AiChat { _, messages ->
            seen += messages.flatMap { it.images }
            AiReply("```json\n{\"tags\": [\"Deniz\", \"kum\", \"uzay\"], \"text\": \"MERHABA\\nDÜNYA\"}\n```", "end_turn", "test", 1, 1)
        }
        val converter = object : ImageResizer {
            override fun resize(source: File, target: File, width: Int, height: Int) = false
            override fun convert(source: File, target: File, ext: String, maxDimension: Int?): Boolean {
                target.writeBytes("RIFF0000WEBPVP8 ".toByteArray() + ByteArray(20))
                return ext == "webp" && maxDimension == 1024
            }
        }
        val store = FileStore(File(dir, "storage"), converter)
        application { droidServerModule(config, db, files = store, resizer = converter, aiChat = chat) }

        // Varsayılan: konum silinir; keepLocation: kalır.
        val a = client.upload("image", "deniz.jpg", jpeg())
        val id = a.getValue("id").jsonPrimitive.content
        val name = a.getValue("image").jsonPrimitive.content
        val stored = client.get("/api/files/photos/$id/$name") { bearerAuth(key) }
        assertFalse(hasGps(stored.bodyAsBytes()))
        assertTrue("immutable" in stored.headers[HttpHeaders.CacheControl].orEmpty(), stored.headers.toString())
        val b = client.upload("raw", "ham.jpg", jpeg())
        assertTrue(hasGps(store.file("photos", b.getValue("id").jsonPrimitive.content.toLong(), b.getValue("raw").jsonPrimitive.content)!!.readBytes()))

        // Range: ileri sarma.
        val part = client.get("/api/files/photos/$id/$name") { bearerAuth(key); header(HttpHeaders.Range, "bytes=2-11") }
        assertEquals(206, part.status.value)
        assertEquals("bytes 2-11/${jpeg().size}", part.headers[HttpHeaders.ContentRange])
        assertEquals(10, part.bodyAsBytes().size)
        assertEquals("bytes", stored.headers[HttpHeaders.AcceptRanges])

        // Dönüşüm (telefondaki kodlayıcıyla): ad ve tip webp.
        val c = client.upload("small", "buyuk.jpg", jpeg())
        val webp = c.getValue("small").jsonPrimitive.content
        assertTrue(webp.endsWith(".webp"), webp)
        assertEquals("image/webp", client.get("/api/files/photos/${c.getValue("id").jsonPrimitive.content}/$webp") { bearerAuth(key) }.headers[HttpHeaders.ContentType])

        // YZ: etiketler yalnızca seçeneklerden, metin OCR alanına; arka planda yazılır.
        val d = client.upload("scan", "fis.jpg", jpeg())
        val did = d.getValue("id").jsonPrimitive.content
        var rec = JsonObject(emptyMap())
        for (i in 0 until 100) {
            rec = Json.parseToJsonElement(client.get("/api/collections/photos/records/$did") { bearerAuth(key) }.bodyAsText()).jsonObject
            if (rec["ocr"].toString() != "null") break
            Thread.sleep(50)
        }
        assertEquals(listOf("deniz", "kum"), rec.getValue("tags").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("MERHABA\nDÜNYA", rec.getValue("ocr").jsonPrimitive.content)
        assertEquals("image/jpeg", seen.single().mime)
    }

    @Test
    fun `medya ayarlarinin dogrulamasi`() {
        val bad = config.copy(
            ai = AiConfig(),
            collections = listOf(photos.copy(fields = photos.fields.map {
                when (it.name) {
                    "small" -> it.copy(maxDimension = 10)
                    "ocr" -> it.copy(convert = ImageFormat.WEBP)
                    "scan" -> it.copy(ai = FileAiConfig(tags = "ocr", text = "tags"))
                    else -> it
                }
            })),
        )
        val errors = ConfigValidator.validateDetailed(bad).map { it.path }
        for (p in listOf("collections[0].fields[2].maxDimension", "collections[0].fields[5].type", "collections[0].fields[3].ai", "collections[0].fields[3].ai.text")) {
            assertTrue(p in errors, "$p yok: $errors")
        }
    }
}
