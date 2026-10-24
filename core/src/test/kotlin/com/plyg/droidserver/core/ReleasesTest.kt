package com.plyg.droidserver.core

import com.sun.net.httpserver.HttpServer
import com.plyg.droidserver.core.update.ReleaseChecker
import com.plyg.droidserver.core.update.Versions
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReleasesTest {
    private val closers = mutableListOf<() -> Unit>()

    @AfterTest
    fun cleanup() = closers.reversed().forEach { runCatching(it) }

    @Test
    fun `surum karsilastirma`() {
        assertTrue(Versions.compare("1.2.0", "1.1.9") > 0)
        assertTrue(Versions.compare("v1.10.0", "1.9.3") > 0, "sayısal, metin değil")
        assertEquals(0, Versions.compare("1.2", "1.2.0"))
        assertTrue(Versions.compare("1.3.0-beta.1", "1.2.9") > 0)
        assertTrue(Versions.compare("1.3.0-beta.1", "1.3.0") < 0, "ön sürüm kararlıdan eski")
        assertTrue(Versions.compare("1.3.0-beta.2", "1.3.0-beta.10") < 0)
        assertTrue(Versions.compare("1.3.0-beta", "1.3.0-alpha.5") > 0)
        assertTrue(Versions.compare("1.3.0-rc.1", "1.3.0-beta.9") > 0)
        assertTrue(Versions.compare("0.1.0", "saçma") > 0)
        assertNull(Versions.parse("components"))
    }

    private fun release(tag: String, prerelease: Boolean = false, draft: Boolean = false, sha: Boolean = true, apk: Boolean = true, base: String = "http://x") = """
        {"tag_name":"$tag","name":"Sürüm $tag","body":"notlar $tag","draft":$draft,"prerelease":$prerelease,
         "html_url":"https://github.com/o/r/releases/tag/$tag","published_at":"2026-10-01T10:00:00Z","assets":[
           ${if (apk) """{"name":"droidserver-${tag.removePrefix("v")}.apk","size":5,"browser_download_url":"$base/apk"}""" else """{"name":"components.json","size":5,"browser_download_url":"$base/c"}"""}
           ${if (sha && apk) """,{"name":"droidserver-${tag.removePrefix("v")}.apk.sha256","size":90,"browser_download_url":"$base/sha"}""" else ""}
         ]}"""

    @Test
    fun `surum listesi ayristirilir`() {
        val json = "[" + listOf(
            release("components", apk = false),
            release("v2.0.0", draft = true),
            release("v1.3.0-beta.1", prerelease = true),
            release("v1.2.0"),
            release("v1.1.0"),
        ).joinToString(",") + "]"
        val stable = assertNotNull(ReleaseChecker.parse(json, includePrerelease = false))
        assertEquals("1.2.0", stable.version)
        assertEquals("droidserver-1.2.0.apk", stable.apkName)
        assertEquals("http://x/sha", stable.sha256Url)
        assertEquals("notlar v1.2.0", stable.notes)
        assertFalse(stable.prerelease)
        assertEquals("1.3.0-beta.1", ReleaseChecker.parse(json, includePrerelease = true)?.version)
        assertNull(ReleaseChecker.parse("[" + release("components", apk = false) + "]", false))
    }

    @Test
    fun `yeni surum bulunur, indirilir ve ozet dogrulanir`() {
        val apk = "APK!!".toByteArray()
        var shaBody = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) } + "  droidserver-1.2.0.apk\n"
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base = "http://127.0.0.1:${srv.address.port}"
        fun send(ex: com.sun.net.httpserver.HttpExchange, b: ByteArray) { ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) } }
        srv.createContext("/repos/o/r/releases") { ex ->
            assertEquals("application/vnd.github+json", ex.requestHeaders.getFirst("Accept"))
            send(ex, ("[" + release("v1.2.0", base = base) + "," + release("v1.1.0", base = base) + "]").toByteArray())
        }
        srv.createContext("/apk") { ex -> send(ex, apk) }
        srv.createContext("/sha") { ex -> send(ex, shaBody.toByteArray()) }
        srv.start()
        closers += { srv.stop(0) }

        val checker = ReleaseChecker("o/r", apiUrl = base)
        assertNull(checker.newerThan("1.2.0", false), "güncel")
        val r = assertNotNull(checker.newerThan("1.1.0", false))
        val dir = Files.createTempDirectory("ds-update").toFile()
        val file = checker.download(r, File(dir, "u.apk"))
        assertEquals("APK!!", file.readText())

        shaBody = "0".repeat(64) + "  droidserver-1.2.0.apk"
        val e = assertFailsWith<IOException> { checker.download(r, File(dir, "v.apk")) }
        assertTrue("özeti tutmuyor" in e.message.orEmpty(), e.message)
        assertFalse(File(dir, "v.apk").exists(), "doğrulanamayan dosya silinir")

        assertFailsWith<IOException> { checker.download(r.copy(sha256Url = null), File(dir, "w.apk")) }
    }
}
