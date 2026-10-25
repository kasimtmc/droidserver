package com.plyg.droidserver.core

import com.sun.net.httpserver.HttpServer
import com.plyg.droidserver.core.components.ComponentManager
import com.plyg.droidserver.core.components.ComponentManifest
import com.plyg.droidserver.core.components.ComponentPackage
import com.plyg.droidserver.core.components.runsProgram
import com.plyg.droidserver.core.config.ComponentConfig
import com.plyg.droidserver.core.config.ConfigJson
import com.plyg.droidserver.core.config.DatabaseConfig
import com.plyg.droidserver.core.config.DbEngine
import com.plyg.droidserver.core.config.PostgresDefaults
import com.plyg.droidserver.core.config.PostgresUrl
import com.plyg.droidserver.core.config.RestartPolicy
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.db.PostgresDriver
import com.plyg.droidserver.core.process.ProcessSupervisor
import com.plyg.droidserver.core.process.ReadinessProbe
import com.plyg.droidserver.core.server.DeployOrchestrator
import com.plyg.droidserver.core.server.EngineControl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComponentManagerTest {

    private fun zip(vararg files: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { bos ->
        ZipOutputStream(bos).use { z -> files.forEach { (n, c) -> z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() } }
    }.toByteArray()

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun serve(bytes: ByteArray): Pair<HttpServer, String> {
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/pkg.zip") { ex -> ex.sendResponseHeaders(200, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) } }
        srv.start()
        return srv to "http://127.0.0.1:${srv.address.port}/pkg.zip"
    }

    @Test
    fun `indir, dogrula, kur, ilk kurulumu calistir`() {
        val archive = zip("bin/hello" to "#!/bin/sh\necho \"merhaba $1\"\n", "README" to "test")
        val (srv, url) = serve(archive)
        try {
            val root = Files.createTempDirectory("ds-comp").toFile()
            val mgr = ComponentManager(root, "arm64-v8a") {}
            val pkg = ComponentPackage(
                id = "hello", version = "1.0",
                builds = mapOf("arm64-v8a" to ComponentPackage.Build(url, sha(archive))),
                executables = listOf("bin/hello"),
                command = listOf("\${PKG}/bin/hello", "dunya"),
                setup = listOf(listOf("sh", "-c", "touch \${DATA}/initialized")),
            )
            mgr.install(pkg)
            val spec = mgr.prepare(ComponentConfig("hello", restart = RestartPolicy.NEVER))

            assertTrue(root.resolve("data/hello/initialized").exists(), "setup adımı çalışmalı")
            assertTrue(mgr.installed().getValue("hello").setupDone)
            val out = ProcessBuilder(spec.command).start().inputStream.bufferedReader().readText().trim()
            assertEquals("merhaba dunya", out)

            // bozuk sha256 reddedilmeli
            val bad = pkg.copy(version = "1.1", builds = mapOf("arm64-v8a" to ComponentPackage.Build(url, "00".repeat(32))))
            assertFailsWith<IllegalArgumentException> { mgr.install(bad) }
            assertEquals("1.0", mgr.installed().getValue("hello").version)
        } finally {
            srv.stop(0)
        }
    }

    @Test
    fun `kurulum dosyasi ortamdan yazilir, yalnizca sahibi okur ve kurulumdan sonra silinir`() {
        val archive = zip("bin/run" to "#!/bin/sh\nexit 0\n")
        val (srv, url) = serve(archive)
        try {
            val root = Files.createTempDirectory("ds-setupfile").toFile()
            val mgr = ComponentManager(root, "arm64-v8a") {}
            val pkg = ComponentPackage(
                id = "db", version = "1",
                builds = mapOf("arm64-v8a" to ComponentPackage.Build(url, sha(archive))),
                executables = listOf("bin/run"),
                command = listOf("\${PKG}/bin/run"),
                setup = listOf(listOf("sh", "-c", "cat \${DATA}/.pwfile > \${DATA}/seen; ls -l \${DATA}/.pwfile | cut -c1-10 > \${DATA}/mode")),
                setupFiles = mapOf("\${DATA}/.pwfile" to "\${env:PGPASSWORD}"),
                stopCommand = listOf("\${PKG}/bin/run", "stop"),
            )
            mgr.install(pkg)
            val spec = mgr.prepare(ComponentConfig("db", env = mapOf("PGPASSWORD" to "s3cr3t")))
            assertEquals("s3cr3t", root.resolve("data/db/seen").readText())
            assertEquals("-rw-------", root.resolve("data/db/mode").readText().trim())
            assertFalse(root.resolve("data/db/.pwfile").exists(), "parola dosyası kurulumdan sonra silinmeli")
            assertEquals(listOf(root.resolve("packages/db/1/bin/run").absolutePath, "stop"), spec.stopCommand)

            // Kurulum başarısız olsa da dosya silinir; ortam değişkeni yoksa açık hata.
            mgr.install(pkg.copy(id = "db2", setup = listOf(listOf("sh", "-c", "exit 1"))))
            assertFailsWith<IllegalStateException> { mgr.prepare(ComponentConfig("db2", env = mapOf("PGPASSWORD" to "x"))) }
            assertFalse(root.resolve("data/db2/.pwfile").exists())
            val missing = assertFailsWith<IllegalStateException> { mgr.prepare(ComponentConfig("db2")) }
            assertTrue(missing.message!!.contains("PGPASSWORD"), missing.message)
        } finally {
            srv.stop(0)
        }
    }

    @Test
    fun `calisma ortami ve komutta ortam degiskeni`() {
        val archive = zip("bin/node" to "#!/bin/sh\necho node\n", "bin/redis-server" to "#!/bin/sh\necho \"$@\"\n")
        val (srv, url) = serve(archive)
        try {
            val root = Files.createTempDirectory("ds-rt").toFile()
            val mgr = ComponentManager(root, "arm64-v8a") {}
            val build = mapOf("arm64-v8a" to ComponentPackage.Build(url, sha(archive)))
            val node = ComponentPackage(
                id = "nodejs", version = "24", builds = build, kind = ComponentPackage.KIND_RUNTIME, executables = listOf("bin/node"),
                paths = listOf("\${PKG}/bin"), env = mapOf("LD_LIBRARY_PATH" to "\${PKG}/lib", "CACHE" to "\${DATA}/cache"),
            )
            mgr.install(node)
            val rt = mgr.runtime("nodejs")!!
            assertEquals(listOf(File(root, "packages/nodejs/24/bin")), rt.binDirs)
            assertEquals(File(root, "packages/nodejs/24/lib").absolutePath, rt.env["LD_LIBRARY_PATH"])
            assertEquals(File(root, "data/nodejs/cache").absolutePath, rt.env["CACHE"])
            assertEquals(null, mgr.runtime("yok"))
            assertFailsWith<IllegalStateException> { mgr.prepare(ComponentConfig("nodejs")) }

            val redis = ComponentPackage(
                id = "redis", version = "8", builds = build, executables = listOf("bin/redis-server"),
                command = listOf("\${PKG}/bin/redis-server", "--requirepass", "\${env:REDIS_PASSWORD}"), secrets = listOf("REDIS_PASSWORD"),
            )
            mgr.install(redis)
            val spec = mgr.prepare(ComponentConfig("redis", env = mapOf("REDIS_PASSWORD" to "gizli")))
            assertEquals(listOf("--requirepass", "gizli"), spec.command.drop(1))
            val missing = assertFailsWith<IllegalStateException> { mgr.prepare(ComponentConfig("redis")) }
            assertTrue("REDIS_PASSWORD" in (missing.message ?: ""), missing.message)
        } finally {
            srv.stop(0)
        }
    }

    @Test
    fun `ornek manifestteki katalog girdileri`() {
        val manifest = ConfigJson.decodeFromString(ComponentManifest.serializer(), File("../docs/components.example.json").readText())
        val byId = manifest.packages.associateBy { it.id }
        for (id in listOf("nodejs", "python", "openjdk")) {
            val p = byId.getValue(id)
            assertTrue(p.isRuntime && p.command.isEmpty() && p.paths.isNotEmpty(), id)
        }
        val redis = byId.getValue("redis")
        assertFalse(redis.isRuntime)
        assertTrue("127.0.0.1" in redis.command && "\${env:REDIS_PASSWORD}" in redis.command, "Redis yalnızca yerelde ve parolayla")
        assertEquals(listOf("REDIS_PASSWORD"), redis.secrets)
        assertEquals(6379, redis.readiness?.tcpPort)
    }

    @Test
    fun `ornek manifestteki postgres girdisi uygulamanin varsayilanlariyla uyumlu`() {
        val manifest = ConfigJson.decodeFromString(ComponentManifest.serializer(), File("../docs/components.example.json").readText())
        val pg = manifest.packages.single { it.id == PostgresDefaults.COMPONENT_ID }
        val port = URI(PostgresDefaults.URL.removePrefix("jdbc:")).port
        val initdb = pg.setup.single()
        assertEquals(PostgresDefaults.USER, initdb[initdb.indexOf("-U") + 1], "bağlantı kullanıcısı initdb ile aynı olmalı")
        assertEquals("\${env:${PostgresDefaults.PASSWORD_ENV}}", pg.setupFiles.values.single())
        assertEquals(port.toString(), pg.command[pg.command.indexOf("-p") + 1])
        assertEquals(port, pg.readiness?.tcpPort)
        assertTrue("listen_addresses=127.0.0.1" in pg.command, "Postgres dışarıya açılmamalı")
        assertTrue(pg.stopCommand.isNotEmpty())
    }

    /**
     * Örnek manifestteki postgres girdisi gerçek PostgreSQL ikilileriyle (embedded-postgres'in açtıkları; Termux
     * derlemesi yerine onlara yönlenen betikler): parola dosyalı initdb, parola sıfırlama işi (tek kullanıcı modu),
     * çevrimiçi parola değişikliği, olmayan veritabanını oluşturma ve veri sıfırlama.
     */
    @Test
    fun `ornek manifestteki postgres girdisi gercek postgres ile calisir`() = runBlocking {
        val bin = NonRootPostgres.binDir // root ile koşuyorsa ikililer root olmayan kullanıcıyla çalışır
        val pg = ConfigJson.decodeFromString(ComponentManifest.serializer(), File("../docs/components.example.json").readText())
            .packages.single { it.id == PostgresDefaults.COMPONENT_ID }
        val names = listOf("postgres", "initdb", "pg_ctl")
        val archive = zip(*names.map { "bin/$it" to "#!/bin/sh\nexec ${File(bin, it).absolutePath} \"\$@\"\n" }.toTypedArray())
        val (srv, url) = serve(archive)
        val port = ServerSocket(0).use { it.localPort }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val supervisor = ProcessSupervisor(scope) {}
        try {
            // Postgres root olmayan kullanıcıyla çalışabilsin diye veri klasörüne giden yol herkese açık.
            val root = Files.createTempDirectory("ds-pg").toFile().apply { setReadable(true, false); setExecutable(true, false) }
            val mgr = ComponentManager(root, "x86_64") {}
            mgr.install(
                pg.copy(
                    builds = mapOf("x86_64" to ComponentPackage.Build(url, sha(archive))),
                    executables = names.map { "bin/$it" },
                    command = pg.command.map { it.replace("5432", "$port") },
                    readiness = pg.readiness!!.copy(tcpPort = port),
                ),
            )
            fun config(password: String) = ServerConfig(
                database = DatabaseConfig(DbEngine.POSTGRES, url = "jdbc:postgresql://127.0.0.1:$port/postgres", user = "droid", password = password),
                components = listOf(ComponentConfig(PostgresDefaults.COMPONENT_ID, env = mapOf(PostgresDefaults.PASSWORD_ENV to password))),
            )
            suspend fun start(cfg: ServerConfig) {
                val spec = mgr.prepare(cfg.components.single())
                supervisor.start(spec)
                assertTrue(ReadinessProbe().await(spec.readiness!!) { supervisor.isAlive(spec.id) }, "postgres hazır olmalı")
            }
            fun connects(db: DatabaseConfig) = runCatching { PostgresDriver.open(db).use { it.query("SELECT 1 AS n") } }.isSuccess
            val orch = DeployOrchestrator(
                object : EngineControl {
                    override fun deploy(config: ServerConfig) {}
                    override fun stop() {}
                },
                supervisor, mgr::prepare, log = {}, openDatabase = { PostgresDriver.open(it) }, components = mgr,
            )

            val first = config("eski'parola")
            start(first)
            assertFalse(root.resolve("data/postgres/.pwfile").exists())
            assertTrue(connects(first.database))
            assertFalse(connects(first.database.copy(password = "yanlis")), "parola doğrulanmalı (scram)")

            // Unutulan parola: tek kullanıcı modunda sıfırlanır; çıktıda parola görünmez.
            val reset = config("yeni\$pw0\$parola")
            val output = orch.runComponentTask(reset, PostgresDefaults.COMPONENT_ID, "set-password")
            assertFalse(output.contains("yeni\$pw0"), output)
            assertFalse(supervisor.isAlive(PostgresDefaults.COMPONENT_ID), "iş için durdurulmalı")
            start(reset)
            assertTrue(connects(reset.database))
            assertFalse(connects(first.database))

            // Eski parola biliniyorsa çevrimiçi değişir.
            val changed = config("ucuncu-parola")
            orch.changeDatabasePassword(reset, changed)
            assertTrue(connects(changed.database))
            assertFalse(connects(reset.database))

            // Olmayan veritabanı yalnızca istenirse oluşturulur.
            val appDb = changed.database.copy(url = PostgresUrl.withDatabase(changed.database.url!!, "uygulama"))
            assertFalse(connects(appDb))
            assertTrue(connects(appDb.copy(createIfMissing = true)))
            assertTrue(connects(appDb))

            // Veri sıfırlama: boş veritabanı, konfigürasyondaki parolayla yeniden kurulur.
            orch.resetComponentData(PostgresDefaults.COMPONENT_ID)
            assertFalse(mgr.installed().getValue(PostgresDefaults.COMPONENT_ID).setupDone)
            val fresh = config("dorduncu")
            start(fresh)
            assertTrue(connects(fresh.database))
            assertFalse(connects(appDb.copy(password = "dorduncu")), "eski veritabanları silinmiş olmalı")
        } finally {
            supervisor.stopAll()
            scope.cancel()
            srv.stop(0)
        }
    }

    @Test
    fun `zip slip engellenir`() {
        val archive = zip("../../kacak.sh" to "x")
        val (srv, url) = serve(archive)
        try {
            val mgr = ComponentManager(Files.createTempDirectory("ds-slip").toFile(), "arm64-v8a") {}
            val pkg = ComponentPackage("evil", "1", builds = mapOf("arm64-v8a" to ComponentPackage.Build(url, sha(archive))), command = listOf("x"))
            assertFailsWith<IllegalArgumentException> { mgr.install(pkg) }
        } finally {
            srv.stop(0)
        }
    }

    @Test
    fun `zipteki sembolik baglanti kalintilari onarilir, eski kurulum da baslatilirken onarilir`() {
        // `zip -y` bağlantıyı hedef adını içeren girdi olarak saklar; java.util.zip bunu düz dosya olarak açar
        // (cihazda: "libicui18n.so.78 is too small to be an ELF executable: only found 18 bytes").
        val archive = zip(
            "bin/run" to "#!/bin/sh\ncat \"$(dirname \"$0\")/../lib/libx.so.1\"\n",
            "lib/libx.so.1.2" to "\u007FELF gercek kutuphane",
            "lib/libx.so.1" to "libx.so.1.2",
            "lib/libx.so" to "libx.so.1",
            "share/tz/Europe/Istanbul" to "TZif veri",
            "share/tz/Asia/Istanbul" to "../Europe/Istanbul",
            "share/notes.txt" to "metin: README", // yol gibi görünmeyen küçük dosyalara dokunulmaz
            "lib/dis" to "../../../etc/passwd", // paket dışını gösteren içerik bağlantıya çevrilmez
        )
        val (srv, url) = serve(archive)
        try {
            val root = Files.createTempDirectory("ds-links").toFile()
            val mgr = ComponentManager(root, "arm64-v8a") {}
            val pkg = ComponentPackage(
                id = "pg", version = "1",
                builds = mapOf("arm64-v8a" to ComponentPackage.Build(url, sha(archive))),
                executables = listOf("bin/run"),
                command = listOf("\${PKG}/bin/run"),
            )
            mgr.install(pkg)
            val dir = root.resolve("packages/pg/1")
            fun isLink(rel: String) = Files.isSymbolicLink(dir.resolve(rel).toPath())
            assertTrue(isLink("lib/libx.so.1") && isLink("lib/libx.so") && isLink("share/tz/Asia/Istanbul"))
            assertFalse(isLink("lib/libx.so.1.2") || isLink("share/notes.txt") || isLink("lib/dis"))
            assertEquals("\u007FELF gercek kutuphane", dir.resolve("lib/libx.so").readText())
            assertEquals("TZif veri", dir.resolve("share/tz/Asia/Istanbul").readText())
            val spec = mgr.prepare(ComponentConfig("pg"))
            assertEquals("\u007FELF gercek kutuphane", ProcessBuilder(spec.command).start().inputStream.bufferedReader().readText())
            assertFalse(mgr.updateAvailable(pkg))
            assertTrue(mgr.updateAvailable(pkg.copy(builds = mapOf("arm64-v8a" to ComponentPackage.Build(url, "ab".repeat(32))))))

            // Eski sürümün kurduğu bozuk paket: bağlantı yeniden düz dosya; yeni bir ComponentManager başlatırken onarır.
            Files.delete(dir.resolve("lib/libx.so.1").toPath())
            dir.resolve("lib/libx.so.1").writeText("libx.so.1.2")
            val logs = mutableListOf<String>()
            ComponentManager(root, "arm64-v8a") { logs += it }.prepare(ComponentConfig("pg"))
            assertTrue(isLink("lib/libx.so.1"))
            assertTrue(logs.any { "1 sembolik bağlantı onarıldı" in it }, logs.joinToString())
        } finally {
            srv.stop(0)
        }
    }

    @Test
    fun `kurulum ciktisi gelirken loglanir, takilan kurulum suresi dolunca durdurulur`() {
        val archive = zip("bin/run" to "#!/bin/sh\nexit 0\n")
        val (srv, url) = serve(archive)
        try {
            val logs = java.util.Collections.synchronizedList(mutableListOf<String>())
            val mgr = ComponentManager(Files.createTempDirectory("ds-hang").toFile(), "arm64-v8a", setupTimeoutSec = 2) { logs += it }
            val pkg = ComponentPackage(
                id = "db", version = "1",
                builds = mapOf("arm64-v8a" to ComponentPackage.Build(url, sha(archive))),
                executables = listOf("bin/run"), command = listOf("\${PKG}/bin/run"),
                // girdi bekleyen adım takılmaz (stdin kapalı); sonsuz bekleyen adım süre dolunca kesilir
                setup = listOf(listOf("sh", "-c", "read x; echo adim-bir"), listOf("sh", "-c", "echo bekliyor; sleep 30")),
            )
            mgr.install(pkg)
            val start = System.nanoTime()
            val e = assertFailsWith<IllegalStateException> { mgr.prepare(ComponentConfig("db")) }
            assertTrue((System.nanoTime() - start) / 1_000_000 < 10_000, "süre sınırı uygulanmalı")
            assertTrue(e.message!!.contains("2 sn içinde bitmedi") && e.message!!.contains("bekliyor"), e.message)
            assertTrue("[db] adim-bir" in logs && "[db] bekliyor" in logs, logs.joinToString())
            assertFalse(mgr.installed().getValue("db").setupDone)
        } finally {
            srv.stop(0)
        }
    }

    @Test
    fun `yarim kalan ilk kurulum kenara alinip yeniden yapilir, tamamlanan kurulum tekrarlanmaz`() {
        val archive = zip("bin/run" to "#!/bin/sh\nexit 0\n")
        val (srv, url) = serve(archive)
        try {
            val root = Files.createTempDirectory("ds-resetup").toFile()
            val logs = mutableListOf<String>()
            val mgr = ComponentManager(root, "arm64-v8a") { logs += it }
            // initdb gibi: klasör doluysa reddeder; ilk denemede yarıda kesilir (yarım klasör bırakır)
            val initdb = "test -e \${DATA}/pgdata && { echo 'pgdata dolu'; exit 1; }; mkdir \${DATA}/pgdata; echo v > \${DATA}/pgdata/PG_VERSION"
            val pkg = ComponentPackage(
                id = "pg", version = "1",
                builds = mapOf("arm64-v8a" to ComponentPackage.Build(url, sha(archive))),
                executables = listOf("bin/run"), command = listOf("\${PKG}/bin/run"),
                setup = listOf(listOf("sh", "-c", initdb)),
            )
            mgr.install(pkg)
            root.resolve("data/pg/pgdata").mkdirs()
            root.resolve("data/pg/pgdata/yarim").writeText("kesilen initdb'den")

            mgr.prepare(ComponentConfig("pg"))
            assertTrue(mgr.installed().getValue("pg").setupDone)
            assertTrue(root.resolve("data/pg/pgdata/PG_VERSION").exists())
            val aside = root.resolve("data").listFiles()!!.single { it.name.startsWith("pg.yarim-kurulum-") }
            assertEquals("kesilen initdb'den", aside.resolve("pgdata/yarim").readText(), "yarım dosyalar silinmez, kenara alınır")
            assertTrue(logs.any { "yarım kalmış kurulumun dosyaları" in it }, logs.joinToString())

            // Kayıt sıfırlansa da (eski sürümün "Güncelle"si) veri klasöründeki işaret kurulumu tekrarlatmaz.
            mgr.install(pkg.copy(version = "2"))
            assertFalse(mgr.installed().getValue("pg").setupDone)
            mgr.prepare(ComponentConfig("pg"))
            assertTrue(mgr.installed().getValue("pg").setupDone)
            assertEquals("v", root.resolve("data/pg/pgdata/PG_VERSION").readText().trim())
            assertEquals(1, root.resolve("data").listFiles()!!.count { it.name.startsWith("pg.yarim-kurulum-") })
        } finally {
            srv.stop(0)
        }
    }

    @Test
    fun `binarylerdeki Termux gecici klasor yolu uygulamanin klasorune cevrilir`() {
        val termux = "/data/data/com.termux/files/usr/tmp"
        val archive = zip(
            "bin/run" to "#!/bin/sh\necho $termux/ashv_key_%d\n",
            "lib/libandroid-shmem.so" to "\u007FELF...$termux/ashv_key_%d...$termux/sem.",
            "share/plain.txt" to "Termux yolu yok",
        )
        val (srv, url) = serve(archive)
        try {
            val root = Files.createTempDirectory("ds-tmp").toFile()
            val tmp = "/data/user/0/com.plyg.droidserver/t"
            val logs = mutableListOf<String>()
            val mgr = ComponentManager(root, "arm64-v8a", tmpDir = tmp) { logs += it }
            val pkg = ComponentPackage(
                id = "pg", version = "1",
                builds = mapOf("arm64-v8a" to ComponentPackage.Build(url, sha(archive))),
                executables = listOf("bin/run"), command = listOf("\${PKG}/bin/run"),
            )
            mgr.install(pkg)
            val dir = root.resolve("packages/pg/1")
            val padded = tmp // Termux yoluyla aynı uzunluk (35)
            assertEquals("\u007FELF...$padded/ashv_key_%d...$padded/sem.", dir.resolve("lib/libandroid-shmem.so").readText())
            assertTrue(dir.resolve("bin/run").canExecute(), "çalıştırma izni korunur")
            val spec = mgr.prepare(ComponentConfig("pg"))
            assertEquals("$padded/ashv_key_%d", ProcessBuilder(spec.command).start().inputStream.bufferedReader().readText().trim())

            // Eski sürümün kurduğu paket (işaret yok): başlatılırken bir kez çevrilir; paket betiğinin yazdığı yol da.
            dir.resolve(".ds-tmp-relocated").delete()
            // Eski kimlikle (dev.droidserver.app) ve yenisiyle paketlenmiş bileşenlerin yazdığı yollar.
            dir.resolve("lib/libandroid-shmem.so").writeText("x////data/data/dev.droidserver.app/t/ashv_key_%d ///data/data/com.plyg.droidserver/t/sem.")
            ComponentManager(root, "arm64-v8a", tmpDir = tmp) { logs += it }.prepare(ComponentConfig("pg"))
            assertEquals("x$padded/ashv_key_%d $padded/sem.", dir.resolve("lib/libandroid-shmem.so").readText())
            assertTrue(logs.any { "1 dosyada geçici klasör yolu" in it }, logs.joinToString())
        } finally {
            srv.stop(0)
        }
    }

    @Test
    fun `indirme kapaliysa paket kurulmaz`() {
        val root = Files.createTempDirectory("ds-noindir").toFile()
        val mgr = ComponentManager(root, "arm64-v8a", downloads = false)
        val pkg = ComponentPackage(id = "pg", version = "1", builds = mapOf("arm64-v8a" to ComponentPackage.Build("http://127.0.0.1:1/x.zip", "00")))
        val e = assertFailsWith<IllegalStateException> { mgr.install(pkg) }
        assertTrue("Play" in e.message.orEmpty(), e.message)
        assertTrue(root.resolve("packages").listFiles().isNullOrEmpty(), "hiçbir şey indirilmez ve açılmaz")
    }

    @Test
    fun `kilit sahibi program adiyla taninir`() {
        val n = "\u0000"
        assertTrue(runsProgram("/data/user/0/com.plyg.droidserver/files/packages/pg/16/bin/postgres${n}-D$n/data/pg$n", "postgres"))
        assertTrue(runsProgram("postgres: checkpointer   $n", "postgres"), "Postgres alt süreci adını değiştirir")
        assertTrue(runsProgram("/bin/sh$n/tmp/x/packages/db/1/bin/run$n", "run"), "betikte ilk argüman yorumlayıcı")
        // CI'daki durum: yolda "runner" geçen başka bir süreç (Gradle test işçisi) programın kendisi sanılmamalı.
        assertFalse(runsProgram("/home/runner/.gradle/jdks/bin/java$n-cp$n/home/runner/work/run$n", "run"))
        assertFalse(runsProgram("/system/bin/app_process64${n}com.example.postgres_client$n", "postgres"))
    }

    @Test
    fun `eski kilit dosyasi silinir, calisan surecinki korunur`() {
        val archive = zip("bin/run" to "#!/bin/sh\nsleep 30\n")
        val (srv, url) = serve(archive)
        try {
            val root = Files.createTempDirectory("ds-lock").toFile()
            val logs = mutableListOf<String>()
            val mgr = ComponentManager(root, "arm64-v8a") { logs += it }
            val pkg = ComponentPackage(
                id = "db", version = "1",
                builds = mapOf("arm64-v8a" to ComponentPackage.Build(url, sha(archive))),
                executables = listOf("bin/run"), command = listOf("\${PKG}/bin/run"),
                lockFiles = listOf("\${DATA}/run.pid"),
            )
            mgr.install(pkg)
            val lock = root.resolve("data/db/run.pid").apply { parentFile.mkdirs() }

            // Telefon yeniden başladı: PID artık yok -> silinir.
            lock.writeText("999999\n/data/...\n")
            mgr.prepare(ComponentConfig("db"))
            assertFalse(lock.exists())
            assertTrue(logs.any { "eski kilit dosyası silindi" in it }, logs.joinToString())

            // PID başka bir programa geçti (bu test süreci) -> silinir.
            lock.writeText("${ProcessHandle.current().pid()}\n")
            mgr.prepare(ComponentConfig("db"))
            assertFalse(lock.exists())

            // Programın kendisi hâlâ çalışıyor -> dokunulmaz.
            val running = ProcessBuilder(root.resolve("packages/db/1/bin/run").path).start()
            try {
                lock.writeText("${running.pid()}\n")
                mgr.prepare(ComponentConfig("db"))
                assertTrue(lock.exists(), "çalışan sürecin kilidi silinmemeli")
            } finally {
                running.destroyForcibly()
            }
        } finally {
            srv.stop(0)
        }
    }
}
