package com.plyg.droidserver.core

import com.plyg.droidserver.core.api.droidServerModule
import com.plyg.droidserver.core.config.AuthConfig
import com.plyg.droidserver.core.config.AuthMode
import com.plyg.droidserver.core.config.CollectionConfig
import com.plyg.droidserver.core.config.ConfigValidator
import com.plyg.droidserver.core.config.FieldConfig
import com.plyg.droidserver.core.config.FieldType
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.db.SchemaMigrator
import com.plyg.droidserver.core.db.SqlDriver
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Faz 12.5: ETag/304, sıkıştırma ve tam metin dizini, iki lehçede. */
@RunWith(Parameterized::class)
class PerformanceTest(private val dialect: Dialect) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun dialects() = Dialect.entries.toList()
    }

    private val key = "yonetici-anahtari-1234567890"
    private val places = CollectionConfig(
        "places", listOf(FieldConfig("name", FieldType.TEXT), FieldConfig("note", FieldType.TEXT)),
        search = listOf("name", "note"), fullText = true,
    )
    private val config = ServerConfig(auth = AuthConfig(AuthMode.API_KEY, apiKeys = listOf(key)), collections = listOf(places))
    private lateinit var db: SqlDriver

    @Before
    fun open() {
        db = TestDb.create(dialect).open()
    }

    @After
    fun close() = db.close()

    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject
    private suspend fun HttpClient.create(name: String, note: String = ""): String = post("/api/collections/places/records") {
        bearerAuth(key); contentType(ContentType.Application.Json); setBody("""{"name":"$name","note":"$note"}""")
    }.let { json(it.bodyAsText()).getValue("id").jsonPrimitive.content }
    private suspend fun HttpClient.search(q: String, extra: String = ""): List<String> {
        val r = get("/api/collections/places/records?q=${java.net.URLEncoder.encode(q, "UTF-8")}$extra") { bearerAuth(key) }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return json(r.bodyAsText()).getValue("items").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }
    }

    @Test
    fun `etag, sikistirma ve tam metin aramasi`() = testApplication {
        SchemaMigrator(db).migrate(config.schemaCollections())
        application { droidServerModule(config, db) }
        assertTrue(ConfigValidator.validate(config).isEmpty())

        val bistro = client.create("Bistro Menüsü", "akşam yemeği")
        client.create("İstanbul Boğazı", "vapur")
        client.create("Ankara Kalesi", "istanbul istanbul yolu")
        repeat(20) { client.create("Kayıt $it", "x".repeat(100)) }

        // Kelime başı; Türkçe karakterden bağımsız; alaka sırası (istanbul iki kez geçen önce); kelimelerin hepsi.
        assertEquals(listOf("Ankara Kalesi", "İstanbul Boğazı"), client.search("ist"))
        assertEquals(listOf("İstanbul Boğazı", "Ankara Kalesi"), client.search("ist", "&sort=id"), "sıralama verilirse o")
        assertEquals(listOf("Ankara Kalesi"), client.search("İSTANBUL yol"))
        assertEquals(listOf("Bistro Menüsü"), client.search("aksam yeme"))
        assertEquals(emptyList(), client.search("stro"), "kelime ortası eşleşmez")
        assertEquals(listOf("Bistro Menüsü"), client.search("bi"), "kısa kelime başı")
        client.patch("/api/collections/places/records/$bistro") { bearerAuth(key); contentType(ContentType.Application.Json); setBody("""{"name":"Bistro İstanbul"}""") }
        assertEquals(3, client.search("istanbul").size, "güncellenen kayıt dizinde")
        client.delete("/api/collections/places/records/$bistro") { bearerAuth(key) }
        assertEquals(2, client.search("istanbul").size, "silinen kayıt dizinden çıkar")

        // ETag: aynı yanıt 304 (gövdesiz), değişince yeni özet; yazma isteklerinde yok.
        val first = client.get("/api/collections/places/records?perPage=5") { bearerAuth(key) }
        val etag = first.headers[HttpHeaders.ETag]
        assertTrue(etag != null && etag.startsWith("W/\""), "ETag: $etag")
        val again = client.get("/api/collections/places/records?perPage=5") { bearerAuth(key); header(HttpHeaders.IfNoneMatch, etag) }
        assertEquals(HttpStatusCode.NotModified, again.status)
        assertEquals(0, again.bodyAsBytes().size)
        client.create("Yeni")
        assertEquals(HttpStatusCode.OK, client.get("/api/collections/places/records?perPage=5&sort=-id") { bearerAuth(key); header(HttpHeaders.IfNoneMatch, etag) }.status)

        // Sıkıştırma: 1 KB üstü JSON gzip'lenir; açılınca aynı içerik.
        val plain = client.get("/api/collections/places/records?perPage=50&sort=id") { bearerAuth(key) }.bodyAsText()
        val gz = client.get("/api/collections/places/records?perPage=50&sort=id") { bearerAuth(key); header(HttpHeaders.AcceptEncoding, "gzip") }
        assertEquals("gzip", gz.headers[HttpHeaders.ContentEncoding])
        val bytes = gz.bodyAsBytes()
        assertTrue(bytes.size < plain.length)
        assertEquals(plain, GZIPInputStream(bytes.inputStream()).readBytes().decodeToString())
        val small = client.get("/api/health") { header(HttpHeaders.AcceptEncoding, "gzip") }
        assertEquals(null, small.headers[HttpHeaders.ContentEncoding], "küçük yanıt sıkıştırılmaz")
    }

    @Test
    fun `tam metin dizini sonradan acilinca mevcut kayitlardan kurulur`() = testApplication {
        val off = config.copy(collections = listOf(places.copy(fullText = false)))
        SchemaMigrator(db).migrate(off.schemaCollections())
        val svc = com.plyg.droidserver.core.api.RecordService(db, off.collections.single(), collections = off.schemaCollections())
        svc.create(Json.parseToJsonElement("""{"name":"Bistro"}""").jsonObject)
        svc.create(Json.parseToJsonElement("""{"name":"İstanbul"}""").jsonObject)
        assertEquals(2, svc.list(1, 10, null, listOf("q" to "st")).items.size, "kapalıyken parça araması")

        val plan = SchemaMigrator(db).plan(config.schemaCollections())
        assertEquals(listOf("places"), plan.rebuildTerms)
        assertTrue("tam metin dizini kurulacak" in plan.summary(), plan.summary())
        SchemaMigrator(db).migrate(config.schemaCollections())
        assertTrue(SchemaMigrator(db).plan(config.schemaCollections()).rebuildTerms.isEmpty(), "bir kez kurulur")
        application { droidServerModule(config, db) }
        assertEquals(listOf("İstanbul"), client.search("ist"))
        assertEquals(emptyList(), client.search("st"))

        // Kapatılınca dizin silinir, arama parça aramasına döner.
        val drop = SchemaMigrator(db).plan(off.schemaCollections())
        assertEquals(listOf("places"), drop.dropTerms)
        assertTrue(ConfigValidator.validate(config.copy(collections = listOf(places.copy(search = emptyList())))).any { "tam metin" in it })
    }
}
