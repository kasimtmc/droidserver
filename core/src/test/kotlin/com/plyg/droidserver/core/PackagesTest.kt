package com.plyg.droidserver.core

import com.plyg.droidserver.core.admin.AdminHost
import com.plyg.droidserver.core.api.droidServerModule
import com.plyg.droidserver.core.backup.BackupInfo
import com.plyg.droidserver.core.config.AuthConfig
import com.plyg.droidserver.core.config.AuthMode
import com.plyg.droidserver.core.config.CollectionConfig
import com.plyg.droidserver.core.config.FieldConfig
import com.plyg.droidserver.core.config.FieldType
import com.plyg.droidserver.core.config.HttpConfig
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.config.ViewConfig
import com.plyg.droidserver.core.packages.PackageFile
import com.plyg.droidserver.core.packages.PackageSignature
import com.plyg.droidserver.core.packages.Packages
import com.plyg.droidserver.core.replication.ReplicaStatus
import com.plyg.droidserver.core.server.ServerEngine
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Faz 18.8: paket biçimi, imza, plan, kurulum, güncelleme, kaldırma ve uçlar. */
class PackagesTest {
    private val key = "yonetici-anahtari-1234567890"
    private val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun files(version: String = "1.0.0", extraView: Boolean = true): Map<String, ByteArray> = linkedMapOf(
        "package.json" to """{"name":"magaza","version":"$version","title":"Mağaza","publisher":"Örnek Ltd"}""".toByteArray(),
        "config.json" to """{"collections":[{"name":"products","fields":[{"name":"title","type":"text","required":true},{"name":"price","type":"real"}]}],
            |"views":[${if (extraView) """{"name":"ucuzlar","collection":"products","filter":"price[lt]=10"}""" else ""}]}""".trimMargin().toByteArray(),
        "scripts/magaza.js" to "routerAdd(\"GET\", \"/magaza/selam\", (req, ctx) => ({ ok: true }))".toByteArray(),
        "sample/products.json" to """[{"title":"Kalem","price":5},{"title":"Defter","price":15}]""".toByteArray(),
    )

    private fun zip(files: Map<String, ByteArray>, prefix: String = "", signed: Boolean = true): ByteArray {
        val all = if (signed) files + ("signature.json" to Packages.sign(files, keys.private, keys.public.encoded).toByteArray()) else files
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> all.forEach { (n, b) -> z.putNextEntry(ZipEntry(prefix + n)); z.write(b); z.closeEntry() } }
        return out.toByteArray()
    }

    private fun read(bytes: ByteArray) = Packages.read(bytes.inputStream())

    @Test
    fun `okuma, imza ve degistirilmis paket`() {
        val pkg = read(zip(files(), prefix = "magaza-main/"))
        assertEquals("magaza", pkg.manifest.name)
        assertEquals(listOf("products"), pkg.config.collections.map { it.name })
        assertEquals(setOf("magaza.js"), pkg.scripts.keys)
        assertEquals(2, pkg.samples.single().second.size)
        assertEquals(Packages.fingerprint(keys.public.encoded), (pkg.signature as PackageSignature.Signed).fingerprint)

        assertEquals(PackageSignature.Unsigned, read(zip(files(), signed = false)).signature)
        // İmzadan sonra içerik değişti.
        val signedFiles = files()
        val sig = Packages.sign(signedFiles, keys.private, keys.public.encoded).toByteArray()
        val tampered = signedFiles + ("scripts/magaza.js" to "kotu();".toByteArray()) + ("signature.json" to sig)
        assertTrue(read(zip(tampered, signed = false)).signature is PackageSignature.Invalid)

        assertFailsWith<IllegalArgumentException> { read(zip(mapOf("x.txt" to "a".toByteArray()), signed = false)) }
        assertFailsWith<IllegalArgumentException> { read(zip(files() + ("../kacis.js" to ByteArray(1)), signed = false)) }
        assertFailsWith<IllegalArgumentException> { read(zip(files() + ("scripts/../x.js" to ByteArray(1)), signed = false)) }
        assertEquals(1, Packages.compareVersions("1.2.10", "1.2.9"))
        assertEquals(-1, Packages.compareVersions("1.0", "1.0.1"))
    }

    @Test
    fun `plan, kurulum, guncelleme ve kaldirma`() {
        val base = ServerConfig(auth = AuthConfig(AuthMode.API_KEY, apiKeys = listOf(key)))
        val pkg = read(zip(files()))
        val plan = Packages.plan(base, pkg, emptySet())
        assertFalse(plan.blocked, plan.toJson().toString())
        assertEquals(listOf("koleksiyon products", "görünüm ucuzlar", "script magaza.js"), plan.adds)
        assertTrue(plan.changes.any { "Script" in it } && plan.changes.any { "örnek" in it })

        val installed = Packages.apply(base, pkg)
        assertTrue(installed.scripts.enabled)
        assertEquals("1.0.0", installed.packages.single().version)
        assertEquals(listOf("products"), installed.packages.single().collections)

        // Aynı ada sahip, pakete ait olmayan koleksiyon: çakışma.
        val clash = base.copy(collections = listOf(CollectionConfig("products", listOf(FieldConfig("x", FieldType.TEXT)))))
        assertTrue(Packages.plan(clash, pkg, emptySet()).conflicts.single().contains("products"))
        assertTrue(Packages.plan(base, pkg, setOf("magaza.js")).conflicts.single().contains("magaza.js"))

        // Güncelleme: kendi tanımları güncellenir, artık olmayan görünüm kaldırılır.
        val v2 = read(zip(files("2.0.0", extraView = false)))
        val up = Packages.plan(installed, v2, setOf("magaza.js"))
        assertFalse(up.blocked, up.toJson().toString())
        assertEquals(listOf("koleksiyon products", "script magaza.js"), up.updates)
        assertEquals(listOf("görünüm ucuzlar"), up.removes)
        val updated = Packages.apply(installed, v2)
        assertEquals(emptyList(), updated.views)
        assertEquals("2.0.0", updated.packages.single().version)
        assertTrue(Packages.plan(updated, read(zip(files("1.5.0"))), setOf("magaza.js")).changes.any { "Eski sürüme" in it })

        // Kaldırma: tanımlar ve kayıt gider, başka tanımlar kalır.
        val other = updated.copy(views = listOf(ViewConfig("benim", "products")))
        val removed = Packages.remove(other, "magaza")
        assertEquals(emptyList(), removed.collections)
        assertEquals(listOf("benim"), removed.views.map { it.name })
        assertEquals(emptyList(), removed.packages)
        assertFailsWith<IllegalArgumentException> { Packages.remove(removed, "magaza") }
    }

    @Test
    fun `motor script yazar ve deploy sonrasi ornek veri ekler`() {
        val dir = Files.createTempDirectory("ds-pkg").toFile()
        val testDb = TestDb.create(Dialect.SQLITE)
        val port = ServerSocket(0).use { it.localPort }
        val base = ServerConfig(server = HttpConfig(host = "127.0.0.1", port = port), auth = AuthConfig(AuthMode.API_KEY, apiKeys = listOf(key)))
        val engine = ServerEngine({ testDb.open() }, dataDir = dir) {}
        try {
            val pkg = read(zip(files()))
            engine.stagePackage(pkg, null)
            assertTrue(File(dir, "scripts/magaza.js").isFile)
            val cfg = Packages.apply(base, pkg)
            engine.deploy(cfg)
            val count = java.net.URI("http://127.0.0.1:$port/api/collections/products/records").toURL().openConnection().apply { setRequestProperty("Authorization", "Bearer $key") }
                .getInputStream().bufferedReader().readText()
            assertEquals("2", Json.parseToJsonElement(count).jsonObject.getValue("totalItems").jsonPrimitive.content)
            assertFalse(File(dir, "packages/pending/magaza.json").exists(), "bekleyen örnek veri bir kez eklenir")
            engine.unstagePackage(cfg.packages.single())
            assertFalse(File(dir, "scripts/magaza.js").exists())
        } finally {
            engine.stop()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `komut satiriyla paketlenen ve imzalanan paket dogrulanir`() {
        val node = runCatching { ProcessBuilder("node", "--version").start().waitFor() == 0 }.getOrDefault(false)
        org.junit.Assume.assumeTrue("node yok", node)
        val dir = Files.createTempDirectory("ds-cli-pkg").toFile()
        try {
            val cli = File(dir, "droidserver.mjs").apply { writeBytes(PackagesTest::class.java.getResourceAsStream("/cli/droidserver.mjs")!!.readBytes()) }
            val src = File(dir, "kaynak").apply { mkdirs() }
            files().forEach { (n, b) -> File(src, n).apply { parentFile.mkdirs() }.writeBytes(b) }
            fun run(vararg args: String): String {
                val p = ProcessBuilder(listOf("node", cli.path) + args).directory(dir).redirectErrorStream(true).start()
                val out = p.inputStream.readBytes().decodeToString()
                assertEquals(0, p.waitFor(), out)
                return out
            }
            val fp = run("package", "key", "-o", "yayimci.key").trim().substringAfter("  ")
            run("package", "build", "kaynak", "-o", "paket.zip", "--key", "yayimci.key")
            val pkg = Packages.read(File(dir, "paket.zip").inputStream())
            assertEquals("magaza", pkg.manifest.name)
            assertEquals(fp, (pkg.signature as PackageSignature.Signed).fingerprint)
            assertEquals(setOf("magaza.js"), pkg.scripts.keys)
            run("package", "build", "kaynak", "-o", "imzasiz.zip")
            assertEquals(PackageSignature.Unsigned, Packages.read(File(dir, "imzasiz.zip").inputStream()).signature)
        } finally {
            dir.deleteRecursively()
        }
    }

    private class Host : AdminHost {
        var installed: PackageFile? = null
        var removed: String? = null
        override fun configText() = "{}"
        override fun plan(text: String): JsonObject = JsonObject(mapOf("errors" to JsonArray(emptyList())))
        override fun deploy(text: String) {}
        override fun logs(after: Long): Pair<Long, List<String>> = 0L to emptyList()
        override fun backups(): List<BackupInfo> = emptyList()
        override fun createBackup(): BackupInfo = throw UnsupportedOperationException()
        override fun replica(): ReplicaStatus? = null
        override fun takeover() {}
        override fun installPackage(pkg: PackageFile): String { installed = pkg; return "kuruluyor" }
        override fun removePackage(name: String): String { require(name == "magaza") { "kurulu değil" }; removed = name; return "kaldırılıyor" }
    }

    @Test
    fun `plan ve kurulum uclari`() = testApplication {
        val host = Host()
        val db = TestDb.create(Dialect.SQLITE).open()
        val config = ServerConfig(auth = AuthConfig(AuthMode.API_KEY, apiKeys = listOf(key)))
        application { droidServerModule(config, db, adminHost = host) }
        assertEquals(401, client.post("/api/_packages/plan") { setBody(zip(files())) }.status.value)
        val r = client.post("/api/_packages/plan") { bearerAuth(key); contentType(ContentType.Application.Zip); setBody(zip(files())) }
        assertEquals(200, r.status.value, r.bodyAsText())
        val plan = Json.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals("signed", plan.getValue("signature").jsonObject.getValue("status").jsonPrimitive.content)
        assertEquals(3, plan.getValue("adds").jsonArray.size)
        val token = plan.getValue("token").jsonPrimitive.content
        val bad = client.post("/api/_packages/plan") { bearerAuth(key); contentType(ContentType.Application.Zip); setBody("zip değil".toByteArray()) }
        assertEquals(400, bad.status.value)

        val ok = client.post("/api/_packages/install") { bearerAuth(key); contentType(ContentType.Application.Json); setBody("""{"token":"$token"}""") }
        assertEquals(200, ok.status.value, ok.bodyAsText())
        assertEquals("magaza", host.installed?.manifest?.name)
        assertEquals(410, client.post("/api/_packages/install") { bearerAuth(key); contentType(ContentType.Application.Json); setBody("""{"token":"$token"}""") }.status.value, "anahtar tek kullanımlık")
        assertEquals(200, client.delete("/api/_packages/magaza") { bearerAuth(key) }.status.value)
        assertEquals(404, client.delete("/api/_packages/yok") { bearerAuth(key) }.status.value)
        assertEquals(Json.parseToJsonElement("""{"items":[]}"""), Json.parseToJsonElement(client.get("/api/_packages") { bearerAuth(key) }.bodyAsText()))
        db.close()
    }
}
