package com.plyg.droidserver.core

import com.plyg.droidserver.core.api.droidServerModule
import com.plyg.droidserver.core.config.ConfigValidator
import com.plyg.droidserver.core.config.FormConfig
import com.plyg.droidserver.core.config.LimitsConfig
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.config.WebDavConfig
import com.plyg.droidserver.core.db.SchemaMigrator
import com.plyg.droidserver.core.db.SqlDriver
import com.plyg.droidserver.core.files.FileStore
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Ağ sürücüsü (WebDAV): kimlik, dosya işlemleri, salt okunur paylaşım, sınırlar, yol güvenliği. */
class WebDavTest {
    private val dav = WebDavConfig(enabled = true, password = "gizli-parola-1", uploads = true, site = true, maxFileMb = 1)
    private val config = ServerConfig(webdav = dav, server = ServerConfig().server.copy(limits = LimitsConfig(authFailures = 5)))
    private lateinit var db: SqlDriver
    private lateinit var dir: File

    @BeforeTest
    fun open() {
        db = TestDb.create(Dialect.SQLITE).open()
        SchemaMigrator(db).migrate(config.schemaCollections())
        dir = Files.createTempDirectory("ds-dav").toFile()
    }

    @AfterTest
    fun close() {
        db.close()
        dir.deleteRecursively()
    }

    private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Digest yanıtını üretir (ilk 401'deki nonce ile). */
    private class Digest(val nonce: String) {
        var nc = 0
    }

    private suspend fun HttpClient.dav(
        method: String, path: String, digest: Digest?, password: String = dav.password, ip: String = "198.51.100.7",
        block: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse = request(path) {
        this.method = HttpMethod.parse(method)
        header(HttpHeaders.XForwardedFor, ip)
        if (digest != null) {
            digest.nc++
            val nc = "%08x".format(digest.nc)
            val ha1 = md5("${dav.username}:DroidServer:$password")
            val ha2 = md5("$method:$path")
            val resp = md5("$ha1:${digest.nonce}:$nc:abc123:auth:$ha2")
            header(
                HttpHeaders.Authorization,
                "Digest username=\"${dav.username}\", realm=\"DroidServer\", nonce=\"${digest.nonce}\", uri=\"$path\", " +
                    "qop=auth, nc=$nc, cnonce=\"abc123\", response=\"$resp\", algorithm=MD5",
            )
        }
        block()
    }

    @Test
    fun `dogrulama`() {
        assertEquals(emptyList(), ConfigValidator.validate(config))
        val errors = ConfigValidator.validate(
            config.copy(webdav = dav.copy(path = "/api/dav", password = "kisa", username = "a b"), forms = listOf(FormConfig("x", "y", "z"))),
        ).joinToString("\n")
        listOf("API yolu", "en az 8", "Kullanıcı adı").forEach { assertTrue(it in errors, errors) }
        assertTrue("formlar" in ConfigValidator.validate(config.copy(webdav = dav.copy(path = "/forms"))).joinToString())
    }

    @Test
    fun `dosya islemleri ve kimlik`() = testApplication {
        val drive = File(dir, "drive")
        val www = File(dir, "www").apply { mkdirs(); resolve("index.html").writeText("<h1>site</h1>") }
        val store = FileStore(File(dir, "storage")).also { File(it.root, "notes/1").mkdirs(); File(it.root, "notes/1/foto.jpg").writeText("jpg"); File(it.root, ".tmp").mkdirs() }
        application { droidServerModule(config, db, files = store, www = www, davDirs = mapOf("drive" to drive)) }

        // Kimliksiz: Digest meydan okuması; düz HTTP'de Basic önerilmez ve kabul edilmez.
        val challenge = client.dav("PROPFIND", "/dav/", null)
        assertEquals(HttpStatusCode.Unauthorized, challenge.status)
        val www1 = challenge.headers.getAll(HttpHeaders.WWWAuthenticate).orEmpty()
        assertTrue(www1.single().startsWith("Digest "), www1.toString())
        val nonce = Regex("nonce=\"([^\"]+)\"").find(www1.single())!!.groupValues[1]
        val basic = "Basic " + Base64.getEncoder().encodeToString("droid:${dav.password}".toByteArray())
        assertEquals(HttpStatusCode.Unauthorized, client.dav("PROPFIND", "/dav/", null) { header(HttpHeaders.Authorization, basic) }.status)
        // Kimliksiz istekler (meydan okuma) güvenlik duvarında hatalı deneme sayılmaz.
        repeat(8) { client.dav("OPTIONS", "/dav/", null) }

        val d = Digest(nonce)
        val root = client.dav("PROPFIND", "/dav/", d) { header("Depth", "1") }
        assertEquals(HttpStatusCode.MultiStatus, root.status, root.bodyAsText())
        val rootXml = root.bodyAsText()
        listOf("/dav/drive/", "/dav/site/", "/dav/uploads/").forEach { assertTrue(it in rootXml, rootXml) }
        assertFalse("/dav/scripts/" in rootXml)

        // Klasör ve dosya.
        assertEquals(HttpStatusCode.Created, client.dav("MKCOL", "/dav/drive/Belgeler", d).status)
        assertEquals(HttpStatusCode.MethodNotAllowed, client.dav("MKCOL", "/dav/drive/Belgeler", d).status)
        assertEquals(HttpStatusCode.Conflict, client.dav("MKCOL", "/dav/drive/yok/alt", d).status)
        assertEquals(HttpStatusCode.Created, client.dav("PUT", "/dav/drive/Belgeler/not%201.txt", d) { setBody("merhaba dünya") }.status)
        assertEquals("merhaba dünya", File(drive, "Belgeler/not 1.txt").readText())
        assertEquals(HttpStatusCode.NoContent, client.dav("PUT", "/dav/drive/Belgeler/not%201.txt", d) { setBody("yeni") }.status)
        val get = client.dav("GET", "/dav/drive/Belgeler/not%201.txt", d)
        assertEquals("yeni", get.bodyAsText())
        assertTrue(get.headers[HttpHeaders.ETag]!!.isNotBlank())

        val list = client.dav("PROPFIND", "/dav/drive/Belgeler/", d) { header("Depth", "1") }.bodyAsText()
        assertTrue("/dav/drive/Belgeler/not%201.txt" in list && "<D:getcontentlength>4</D:getcontentlength>" in list, list)
        assertEquals(HttpStatusCode.NotFound, client.dav("PROPFIND", "/dav/drive/yok.txt", d).status)

        // Kopyala, taşı, sil.
        assertEquals(HttpStatusCode.Created, client.dav("COPY", "/dav/drive/Belgeler/not%201.txt", d) { header("Destination", "http://localhost/dav/drive/kopya.txt") }.status)
        assertEquals(HttpStatusCode.PreconditionFailed, client.dav("COPY", "/dav/drive/Belgeler/not%201.txt", d) {
            header("Destination", "http://localhost/dav/drive/kopya.txt"); header("Overwrite", "F")
        }.status)
        assertEquals(HttpStatusCode.Created, client.dav("MOVE", "/dav/drive/kopya.txt", d) { header("Destination", "/dav/site/kopya.txt") }.status)
        assertTrue(File(www, "kopya.txt").isFile && !File(drive, "kopya.txt").exists())
        assertEquals(HttpStatusCode.NoContent, client.dav("DELETE", "/dav/drive/Belgeler", d).status)
        assertFalse(File(drive, "Belgeler").exists())

        // Kilit ve özellik yazma (istemciler bekler).
        val lock = client.dav("LOCK", "/dav/drive/kilitli.txt", d) { setBody("<?xml version=\"1.0\"?><D:lockinfo xmlns:D=\"DAV:\"/>") }
        assertEquals(HttpStatusCode.Created, lock.status)
        assertTrue(lock.headers["Lock-Token"]!!.startsWith("<opaquelocktoken:"))
        val patch = client.dav("PROPPATCH", "/dav/drive/kilitli.txt", d) {
            setBody("""<?xml version="1.0"?><D:propertyupdate xmlns:D="DAV:" xmlns:Z="urn:schemas-microsoft-com:"><D:set><D:prop><Z:Win32LastModifiedTime>x</Z:Win32LastModifiedTime></D:prop></D:set></D:propertyupdate>""")
        }
        assertEquals(HttpStatusCode.MultiStatus, patch.status)
        assertTrue("Win32LastModifiedTime" in patch.bodyAsText() && "urn:schemas-microsoft-com:" in patch.bodyAsText())

        // Salt okunur yüklenenler: okunur, yazılamaz; gizli klasör görünmez.
        assertEquals("jpg", client.dav("GET", "/dav/uploads/notes/1/foto.jpg", d).bodyAsText())
        assertEquals(HttpStatusCode.Forbidden, client.dav("PUT", "/dav/uploads/notes/1/x.txt", d) { setBody("x") }.status)
        assertEquals(HttpStatusCode.Forbidden, client.dav("DELETE", "/dav/uploads/notes/1/foto.jpg", d).status)
        assertFalse(".tmp" in client.dav("PROPFIND", "/dav/uploads/", d) { header("Depth", "1") }.bodyAsText())
        // Paylaşım kökleri silinemez; olmayan paylaşım 404.
        assertEquals(HttpStatusCode.Forbidden, client.dav("DELETE", "/dav/site", d).status)
        assertEquals(HttpStatusCode.NotFound, client.dav("PROPFIND", "/dav/scripts/", d).status)

        // Boyut sınırı (1 MB): dosya oluşmaz.
        val big = ByteArray(1024 * 1024 + 10)
        assertEquals(HttpStatusCode.PayloadTooLarge, client.dav("PUT", "/dav/drive/buyuk.bin", d) { setBody(big) }.status)
        assertFalse(File(drive, "buyuk.bin").exists())
        assertTrue(drive.listFiles().orEmpty().none { it.name.startsWith(".dav-upload-") })

        // Kök dışına çıkma denemesi.
        val escape = client.dav("GET", "/dav/drive/..%2F..%2Fetc/passwd", d)
        assertTrue(escape.status == HttpStatusCode.Forbidden || escape.status == HttpStatusCode.NotFound, escape.status.toString())
        Files.createSymbolicLink(File(drive, "bag").toPath(), dir.toPath())
        assertEquals(HttpStatusCode.Forbidden, client.dav("PROPFIND", "/dav/drive/bag/", d).status)

        // HTTPS ucundan (yerel TLS vekili) gelen istekte Basic de kabul edilir.
        val viaTls = client.request("/dav/drive/") {
            method = HttpMethod.parse("PROPFIND"); header(HttpHeaders.XForwardedProto, "https"); header(HttpHeaders.Authorization, basic)
        }
        assertEquals(HttpStatusCode.MultiStatus, viaTls.status)

        // Yanlış parolalar sayılır: 5 denemeden sonra adres engellenir.
        repeat(5) { client.dav("PROPFIND", "/dav/", Digest(nonce), password = "yanlis-parola", ip = "198.51.100.9") }
        assertEquals(HttpStatusCode.TooManyRequests, client.dav("PROPFIND", "/dav/", d, ip = "198.51.100.9").status)
        // Meydan okumaları yapan ilk adres engellenmedi.
        assertEquals(HttpStatusCode.MultiStatus, client.dav("PROPFIND", "/dav/", d).status)
    }

    @Test
    fun `kapaliyken yok`() = testApplication {
        application { droidServerModule(config.copy(webdav = WebDavConfig()), db, davDirs = mapOf("drive" to dir)) }
        assertEquals(HttpStatusCode.NotFound, client.request("/dav/") { method = HttpMethod.parse("PROPFIND") }.status)
    }
}
