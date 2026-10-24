package com.plyg.droidserver.core

import com.plyg.droidserver.core.backup.BackupCrypto
import com.plyg.droidserver.core.backup.RemoteBackups
import com.plyg.droidserver.core.backup.SigV4
import com.plyg.droidserver.core.config.ConfigSecrets
import com.plyg.droidserver.core.config.RemoteBackupConfig
import com.plyg.droidserver.core.config.RemoteKind
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.utils.io.toByteArray
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Faz 12.6: yedek şifreleme, WebDAV ve S3 (SigV4) depoları. */
class RemoteBackupTest {
    private val dir: File = Files.createTempDirectory("ds-remote").toFile()
    private var server: EmbeddedServer<*, *>? = null

    @AfterTest
    fun cleanup() {
        server?.stop(100, 500)
        dir.deleteRecursively()
    }

    private fun encrypt(data: ByteArray, pass: String) = ByteArrayOutputStream().also { BackupCrypto.encrypt(ByteArrayInputStream(data), it, pass) }.toByteArray()
    private fun decrypt(data: ByteArray, pass: String) = ByteArrayOutputStream().also { BackupCrypto.decrypt(ByteArrayInputStream(data), it, pass) }.toByteArray()

    @Test
    fun `sifreleme parcali, dogrulamali ve parolaya bagli`() {
        for (size in listOf(0, 10, 1 shl 20, 3 * (1 shl 20) + 12345)) {
            val data = Random(size).nextBytes(size)
            val sealed = encrypt(data, "uzun-bir-parola-123")
            assertContentEquals(data, decrypt(sealed, "uzun-bir-parola-123"), "boyut $size")
            assertTrue(sealed.size > data.size)
        }
        val data = Random(1).nextBytes(2 * (1 shl 20) + 5)
        val sealed = encrypt(data, "parola-parola-1")
        assertFailsWith<BackupCrypto.WrongPassphraseException> { decrypt(sealed, "yanlis-parola") }
        val tampered = sealed.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
        assertFailsWith<RuntimeException> { decrypt(tampered, "parola-parola-1") }
        // Son parça kesilirse fark edilir (yalnızca tam parçalar kalsa da).
        val firstTwoChunks = 33 + 2 * (4 + (1 shl 20) + 16)
        assertFailsWith<IllegalArgumentException> { decrypt(sealed.copyOf(firstTwoChunks), "parola-parola-1") }
        assertFailsWith<IllegalArgumentException> { decrypt("PK\u0003\u0004 zip".toByteArray(), "x") }
    }

    @Test
    fun `aws imza v4 test vektoru`() {
        // AWS belgesindeki "GET Object" örneği.
        val auth = SigV4.authorization(
            "GET", "/test.txt", emptyMap(),
            mapOf("host" to "examplebucket.s3.amazonaws.com", "range" to "bytes=0-9", "x-amz-content-sha256" to SigV4.EMPTY_SHA256, "x-amz-date" to "20130524T000000Z"),
            SigV4.EMPTY_SHA256, "AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", "us-east-1", "s3",
            Instant.parse("2013-05-24T00:00:00Z"),
        )
        assertTrue(auth.endsWith("Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41"), auth)
        assertTrue("SignedHeaders=host;range;x-amz-content-sha256;x-amz-date" in auth)
        assertEquals("a%20b%2Fc~", SigV4.encode("a b/c~", slash = false))
    }

    /** Bellekte dosya tutan sahte sunucu; [handle] yolu ve gövdeyi alır. */
    private fun fakeServer(handle: suspend io.ktor.server.application.ApplicationCall.(ByteArray) -> Unit): Int {
        val s = embeddedServer(CIO, port = 0) {
            intercept(ApplicationCallPipeline.Call) {
                val body = context.receiveChannel().toByteArray()
                context.handle(body)
                finish()
            }
        }.start(wait = false)
        server = s
        return runBlocking { s.engine.resolvedConnectors().first().port }
    }

    private fun backupFile(): File = File(dir, "yedek-2026-10-14_0400-zamanli.zip").apply { writeBytes(Random(7).nextBytes(300_000)) }

    @Test
    fun `webdav deposu`() {
        val files = ConcurrentHashMap<String, ByteArray>()
        val auth = "Basic " + Base64.getEncoder().encodeToString("ali:uygulama-parolasi".toByteArray())
        var mkcol = 0
        val port = fakeServer { body ->
            if (request.headers["Authorization"] != auth) return@fakeServer respondText("", status = HttpStatusCode.Unauthorized)
            val p = request.path()
            when (request.httpMethod.value) {
                "MKCOL" -> { mkcol++; respondText("", status = HttpStatusCode.Created) }
                "PUT" -> if (mkcol == 0) respondText("", status = HttpStatusCode.Conflict) else { files[p] = body; respondText("", status = HttpStatusCode.Created) }
                "GET" -> files[p]?.let { respondBytes(it) } ?: respondText("", status = HttpStatusCode.NotFound)
                "DELETE" -> { files.remove(p); respondText("", status = HttpStatusCode.NoContent) }
                "PROPFIND" -> {
                    assertEquals("1", request.headers["Depth"])
                    val items = files.entries.filter { it.key.startsWith(p) }.joinToString("") { (k, v) ->
                        "<d:response><d:href>$k</d:href><d:propstat><d:prop><d:getcontentlength>${v.size}</d:getcontentlength>" +
                            "<d:getlastmodified>Wed, 14 Oct 2026 04:00:00 GMT</d:getlastmodified><d:resourcetype/></d:prop></d:propstat></d:response>"
                    }
                    respondText(
                        """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:"><d:response><d:href>$p</d:href></d:response>$items</d:multistatus>""",
                        io.ktor.http.ContentType.Application.Xml, HttpStatusCode.MultiStatus,
                    )
                }
                else -> respondText("", status = HttpStatusCode.MethodNotAllowed)
            }
        }
        val config = RemoteBackupConfig(RemoteKind.WEBDAV, url = "http://127.0.0.1:$port/dav/yedekler", username = "ali", password = "uygulama-parolasi", passphrase = "gizli-parola-12345", keep = 2)
        roundTrip(RemoteBackups(config))
        assertEquals(1, mkcol, "klasör bir kez oluşturuldu")
        assertTrue(files.keys.all { it.startsWith("/dav/yedekler/") && it.endsWith(".dsbk") }, files.keys.toString())
        assertTrue(files.values.none { String(it).contains("PK") && it.size == 300_000 }, "depoda düz zip yok")
        val wrong = RemoteBackups(config.copy(password = "yanlis"))
        assertTrue("HTTP 401" in assertFailsWith<RuntimeException> { wrong.list() }.message.orEmpty())
    }

    @Test
    fun `s3 deposu imzali istekler`() {
        val objects = ConcurrentHashMap<String, ByteArray>()
        val port = fakeServer { body ->
            // İmza, alınan başlık ve gövdeden yeniden hesaplanıp karşılaştırılır.
            val authz = request.headers["Authorization"].orEmpty()
            val signedNames = Regex("SignedHeaders=([^,]+)").find(authz)!!.groupValues[1].split(';')
            val headers = signedNames.associateWith { request.headers[it].orEmpty() }
            val hash = request.headers["x-amz-content-sha256"]!!
            assertEquals(SigV4.sha256Hex(body), hash, "yük özeti")
            val query = request.queryParameters.entries().associate { it.key to it.value.single() }
            val expected = SigV4.authorization(
                request.httpMethod.value, request.path(), query, headers, hash, "AKID", "gizli-anahtar", "eu-central-003", "s3",
                Instant.from(SigV4.AMZ_DATE.withZone(java.time.ZoneOffset.UTC).parse(request.headers["x-amz-date"])),
            )
            if (expected != authz) return@fakeServer respondText("SignatureDoesNotMatch", status = HttpStatusCode.Forbidden)
            val p = request.path()
            when (request.httpMethod.value) {
                "PUT" -> { objects[p] = body; respondText("") }
                "DELETE" -> { objects.remove(p); respondText("", status = HttpStatusCode.NoContent) }
                "GET" -> if (p == "/kova") {
                    val prefix = request.queryParameters["prefix"].orEmpty()
                    val items = objects.entries.filter { it.key.startsWith("/kova/$prefix") }.joinToString("") { (k, v) ->
                        "<Contents><Key>${k.removePrefix("/kova/")}</Key><LastModified>2026-10-14T04:00:00.000Z</LastModified><Size>${v.size}</Size></Contents>"
                    }
                    respondText("""<?xml version="1.0"?><ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/"><IsTruncated>false</IsTruncated>$items</ListBucketResult>""", io.ktor.http.ContentType.Application.Xml)
                } else objects[p]?.let { respondBytes(it) } ?: respondText("", status = HttpStatusCode.NotFound)
                else -> respondText("", status = HttpStatusCode.MethodNotAllowed)
            }
        }
        val config = RemoteBackupConfig(
            RemoteKind.S3, url = "http://127.0.0.1:$port", bucket = "kova", region = "eu-central-003", prefix = "evim",
            username = "AKID", password = "gizli-anahtar", passphrase = "gizli-parola-12345", keep = 2,
        )
        roundTrip(RemoteBackups(config))
        assertTrue(objects.keys.all { it.startsWith("/kova/evim/") }, objects.keys.toString())
        val bad = RemoteBackups(config.copy(password = "baska"))
        assertTrue("HTTP 403" in assertFailsWith<RuntimeException> { bad.list() }.message.orEmpty())
    }

    /** Yükle, listele, eskileri sil, indirip çöz; bağlantı denemesi. */
    private fun roundTrip(remote: RemoteBackups) {
        val zip = backupFile()
        val names = listOf("2026-10-12", "2026-10-13", "2026-10-14").map { day ->
            remote.upload(File(dir, "yedek-${day}_0400-zamanli.zip").apply { writeBytes(zip.readBytes()) })
        }
        assertEquals(names.reversed(), remote.list().map { it.name })
        assertEquals(listOf(names[0]), remote.prune())
        assertEquals(names.drop(1).reversed(), remote.list().map { it.name })
        val target = File(dir, "indirilen.zip")
        remote.download(names[2], target)
        assertContentEquals(zip.readBytes(), target.readBytes())
        assertFailsWith<BackupCrypto.WrongPassphraseException> { remote.download(names[2], File(dir, "x.zip"), passphrase = "yanlis") }
        assertTrue("Bağlantı çalışıyor" in remote.test())
        assertEquals(2, remote.list().size, "deneme dosyası silindi")
    }

    @Test
    fun `sirlar depoya tasinir`() {
        val json = """{"version":13,"backup":{"remote":{"kind":"s3","url":"https://s3.example.com","username":"AK","password":"gizli","passphrase":"parola-uzun-1"}}}"""
        val stored = linkedMapOf<String, String>()
        val secured = ConfigSecrets.secureText(json) { kind, value -> "${kind}_${stored.size + 1}".also { stored[it] = value } }!!
        assertTrue("\"secret:backup_password_" in secured && "\"secret:backup_passphrase_" in secured && "gizli\"" !in secured, secured)
        val cfg = ConfigSecrets.resolve(com.plyg.droidserver.core.config.ServerConfig.fromJson(secured)) { stored[it] }
        assertEquals("parola-uzun-1", cfg.backup.remote!!.passphrase)
    }
}
