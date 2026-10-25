package com.plyg.droidserver.core.components

import com.plyg.droidserver.core.config.ComponentConfig
import com.plyg.droidserver.core.config.ConfigJson
import com.plyg.droidserver.core.config.PostgresDefaults
import com.plyg.droidserver.core.db.PostgresAdmin
import com.plyg.droidserver.core.i18n.tr
import com.plyg.droidserver.core.process.ProcessSupervisor
import com.plyg.droidserver.core.process.Readiness
import kotlinx.serialization.Serializable
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Bileşen deposu manifesti (kendi sunucunuzda / GitHub Releases'ta barındırılan components.json).
 *
 * {
 *   "packages": [{
 *     "id": "cloudflared", "version": "2026.9.0", "description": "Cloudflare Tunnel",
 *     "builds": { "arm64-v8a": { "url": "https://.../cloudflared-arm64.zip", "sha256": "..." } },
 *     "executables": ["bin/cloudflared"],
 *     "command": ["${PKG}/bin/cloudflared", "tunnel", "--no-autoupdate", "run"],
 *     "setup": [],
 *     "env": {}
 *   }]
 * }
 *
 * Yer tutucular: ${PKG} paket klasörü, ${DATA} bileşenin kalıcı veri klasörü, ${env:AD} ortam değişkeni (komutta,
 * `setupFiles`'ta ve bakım işinin girdisinde).
 * Opsiyoneller:
 *  - `"readiness": { "tcpPort": 5432, "timeoutSec": 30 }`: sunucu bu port açılana kadar bekler.
 *  - `"setupFiles": { "${DATA}/.pwfile": "${env:PGPASSWORD}" }`: ilk kurulum komutlarından önce yazılır
 *    (yalnızca sahibi okuyabilir), kurulum bitince (başarısız olsa da) silinir. Sırlar diskte kalmaz.
 *  - `"stopCommand": [...]`: durdururken SIGTERM yerine önce bu komut çalışır (örn. `pg_ctl stop -m fast`).
 *  - `"tasks": { "set-password": { "title": "...", "command": [...], "stdin": "..." } }`: kullanıcının
 *    çalıştırabileceği bakım işleri (bkz. [ComponentTask]).
 */
@Serializable
data class ComponentManifest(val packages: List<ComponentPackage>)

@Serializable
data class ComponentPackage(
    val id: String,
    val version: String,
    val description: String = "",
    /** ABI -> indirme (arm64-v8a, armeabi-v7a, x86_64). */
    val builds: Map<String, Build>,
    /** Çalıştırma izni verilecek dosyalar (zip izin bitlerini korumaz). */
    val executables: List<String> = emptyList(),
    /** Süreç komutu; çalışma ortamı paketlerinde ([kind] `runtime`) boştur. */
    val command: List<String> = emptyList(),
    /** `service`: arka planda çalışan süreç (Postgres, Redis); `runtime`: uygulamaların kullandığı çalışma ortamı (Node.js, Python). */
    val kind: String = KIND_SERVICE,
    /** Çalışma ortamında PATH'e eklenecek klasörler (`${PKG}` yer tutucusuyla), örn. `${PKG}/bin`. */
    val paths: List<String> = emptyList(),
    /** İlk kurulumda bir kez çalışan komutlar, örn. postgres için initdb. */
    val setup: List<List<String>> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    /** Verilirse sunucu deploy edilmeden önce bu bileşenin hazır olması beklenir (örn. veritabanı portu). */
    val readiness: Readiness? = null,
    /** Yol -> içerik; ilk kurulum süresince var olan geçici dosyalar (örn. initdb parola dosyası). */
    val setupFiles: Map<String, String> = emptyMap(),
    /** Hızlı ve temiz kapanış komutu; boşsa SIGTERM gönderilir. */
    val stopCommand: List<String> = emptyList(),
    /**
     * Çalışan süreci gösteren kilit dosyaları (ilk satırı PID), örn. Postgres `postmaster.pid`. Telefon kapanınca dosya
     * kalır; yeniden açılışta aynı PID başka bir sürece verilmişse program "başka bir kopya çalışıyor" diye açılmaz.
     * Başlatmadan önce PID bu programa ait canlı bir süreç değilse dosya silinir.
     */
    val lockFiles: List<String> = emptyList(),
    /** Kimlik -> bakım işi (örn. Postgres parolasını sıfırla). */
    val tasks: Map<String, ComponentTask> = emptyMap(),
    /**
     * Bileşenin ihtiyaç duyduğu sırlar (ortak ortam değişkeni adları, örn. `REDIS_PASSWORD`): katalogdan çalıştırılınca
     * ortak ortamda yoksa rastgele üretilip şifreli depoya eklenir; komutta `${env:AD}` ile kullanılır.
     */
    val secrets: List<String> = emptyList(),
) {
    @Serializable
    data class Build(val url: String, val sha256: String)

    val isRuntime: Boolean get() = kind == KIND_RUNTIME

    companion object {
        const val KIND_SERVICE = "service"
        const val KIND_RUNTIME = "runtime"
    }
}

/**
 * Bileşenin bakım işi; ilk kurulum yapıldıktan sonra kullanıcı isteğiyle çalışır.
 * [command] ve [stdin]'de paket yer tutucuları geçerlidir; [stdin]'de ayrıca `${sqlstr:AD}`: ortam
 * değişkeninin SQL metin değeri (tırnaklanmış). Sır içeren değerler loglara ve hata iletisine yazılmaz.
 */
@Serializable
data class ComponentTask(
    val title: String,
    val description: String = "",
    val command: List<String>,
    /** Komutun standart girdisi (örn. tek kullanıcı modundaki postgres'e SQL). */
    val stdin: String? = null,
    /** true: bileşen önce durdurulur (veri klasörünü tek başına kullanan işler için). */
    val stopFirst: Boolean = true,
    val timeoutSec: Long = 120,
)

/** DeployOrchestrator'ın bileşen bakım işleri için kullandığı işlemler (app: ComponentManager). */
interface ComponentAdmin {
    fun task(componentId: String, taskId: String): ComponentTask?

    /** İşi çalıştırır, çıktısını (sırlar gizlenmiş) döndürür; başarısızsa hata fırlatır. */
    fun runTask(cfg: ComponentConfig, taskId: String): String

    /** Bileşenin veri klasörünü siler; sonraki başlatmada ilk kurulum (örn. initdb) yeniden yapılır. */
    fun resetData(componentId: String)

    object None : ComponentAdmin {
        override fun task(componentId: String, taskId: String): ComponentTask? = null
        override fun runTask(cfg: ComponentConfig, taskId: String): String = error(tr("Bileşen yönetimi yok"))
        override fun resetData(componentId: String) = error(tr("Bileşen yönetimi yok"))
    }
}

/** Kurulu çalışma ortamı: [binDirs] PATH'e eklenir, [env] (örn. `LD_LIBRARY_PATH`, `JAVA_HOME`) uygulamaya verilir. */
data class RuntimeInfo(val id: String, val version: String, val home: File, val binDirs: List<File>, val env: Map<String, String>)

/** Kurulu bileşen kaydı; paket tanımı da saklanır ki servisler internetsiz başlatılabilsin. */
@Serializable
data class InstalledComponent(
    val id: String,
    val version: String,
    val pkg: ComponentPackage,
    val setupDone: Boolean = false,
    /** Kurulan zip'in sha256'sı: aynı sürümün düzeltilmiş paketi yayınlanınca güncelleme önerilir. */
    val sha256: String? = null,
)

/**
 * İndirir, sha256 doğrular, açar, çalıştırma izni verir ve ProcessSupervisor için Spec üretir.
 *
 * Neden çalışır: GitHub sürümü targetSdk 28 ile derlenir; böylece Android 10+'daki W^X kısıtı (yazılabilir klasörden
 * exec yasağı) bu uygulamaya uygulanmaz. Play sürümünde ([moduleLibDir] verilir) hiçbir şey indirilmez: paketin
 * dosyaları Play modülüyle uygulamanın yerel kitaplık klasörüne `lib<id>_<n>.so` adıyla kurulur (oradan
 * çalıştırılabilir), paket klasöründe yalnızca onlara giden sembolik bağlantılar olur (`bin/postgres` ->
 * `<kitaplık klasörü>/libpostgres_7.so`). Hangi yolun hangi dosya olduğu modüldeki `lib<id>_files.so`'da (JSON).
 */
class ComponentManager(
    private val rootDir: File,
    private val abi: String,
    /** İlk kurulum komutlarının (örn. initdb) her biri için üst süre. */
    private val setupTimeoutSec: Long = 600,
    /**
     * Termux derlemelerine gömülü geçici klasörün (`/data/data/com.termux/files/usr/tmp`) yerine kullanılacak, uygulamanın
     * kendi klasörü (en çok 35 karakter). Termux'suz telefonda o klasör yoktur ve libandroid-shmem orada sonsuz döngüye
     * girer (initdb "selecting default max_connections" adımında takılır). null: binary'lere dokunulmaz.
     */
    private val tmpDir: String? = null,
    /**
     * Play sürümünde Play modüllerinin kurduğu programların klasörü (uygulamanın `nativeLibraryDir`'i; uygulama ve
     * modüller güncellendikçe değişir, her seferinde sorulur). null: paketler indirilir (GitHub sürümü).
     */
    private val moduleLibDir: (() -> File)? = null,
    /**
     * Play sürümünün kataloğu (uygulamayla gelir). Modüller uygulamayla birlikte güncellendiği için kayıttaki paket
     * tanımı katalogdakinden farklıysa bileşen kullanılırken yenilenir.
     */
    private val moduleCatalog: (() -> List<ComponentPackage>)? = null,
    private val log: (String) -> Unit = ::println,
) : ComponentAdmin {
    private companion object {
        // Android'in regex motoru (ICU) kaçışsız `}` kabul etmez (JVM eder): süslü parantezler hep kaçışlı.
        val ENV_REF = Regex("\\$\\{env:([A-Za-z_][A-Za-z0-9_]*)\\}")
        /** Bakım işi girdisinde: `${env:AD}` düz, `${sqlstr:AD}` SQL metni olarak. */
        val TASK_REF = Regex("\\$\\{(env|sqlstr):([A-Za-z_][A-Za-z0-9_]*)\\}")
        /**
         * Binary'lerdeki Termux geçici klasör yolu ve paketleme betiğinin (tools/patch-termux-paths.py) yazdığı hali: eski
         * uygulama kimliğiyle (`dev.droidserver.app`) paketlenmiş bileşenler ve yenisiyle paketlenenler. Hepsi 35 bayt.
         */
        val TMP_PATHS = listOf("/data/data/com.termux/files/usr/tmp", "////data/data/dev.droidserver.app/t", "///data/data/com.plyg.droidserver/t")
            .map { it.toByteArray() }
        const val RELOCATED_MARKER = ".ds-tmp-relocated"
        const val POSTGRES_LOCK = "\${DATA}/pgdata/postmaster.pid"
        /** Veri klasöründe ilk kurulumun tamamlandığını gösteren dosya. */
        const val SETUP_MARKER = ".ds-setup-done"
        const val ASIDE_SUFFIX = ".yarim-kurulum-"
        /** Play sürümünde paket klasörünün bağlantılarının gösterdiği kitaplık klasörü. */
        const val LIBDIR_MARKER = ".ds-libdir"
    }

    /** Bu süreçte bağlantı kalıntılarına karşı denetlenmiş paket klasörleri. */
    private val repaired = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Kurulu paketin klasörleri ve genişletilmiş ortamı. */
    private inner class Context(val inst: InstalledComponent, cfg: ComponentConfig) {
        val pkg = inst.pkg
        val pkgDir = File(pkgRoot, "${pkg.id}/${inst.version}")
        val dataDir = File(dataRoot, pkg.id).apply { mkdirs() }
        fun expand(s: String) = s.replace("\${PKG}", pkgDir.absolutePath).replace("\${DATA}", dataDir.absolutePath)
        val env = (pkg.env + cfg.env).mapValues { expand(it.value) } + moduleEnv(pkgDir)

        fun envValue(ref: MatchResult, name: String = ref.groupValues[1]): String =
            env[name] ?: error(tr("{0}: {1} için ortam değişkeni yok", pkg.id, ref.value))
    }

    /**
     * Play sürümünde programların ortamına eklenen: kendi yerini realpath() ile bulan programlar kitaplık klasörüne
     * değil paket klasörüne baksın diye uygulamanın libdsexec.so'su (bkz. app/src/main/cpp/dsexec.c).
     */
    private fun moduleEnv(pkgDir: File): Map<String, String> {
        val libDir = moduleLibDir?.invoke() ?: return emptyMap()
        val preload = File(libDir, "libdsexec.so").takeIf { it.isFile } ?: return emptyMap()
        return mapOf("DS_LIBDIR" to libDir.path, "DS_PKG" to pkgDir.path, "LD_PRELOAD" to preload.path)
    }

    private fun context(cfg: ComponentConfig): Context =
        Context(moduleSynced(installed()[cfg.id] ?: error(tr("{0} kurulu değil — önce Bileşenler ekranından kurun", cfg.id))), cfg).also { ctx ->
            // Kitaplık klasörü uygulama ya da başka bir modül güncellenince değişir; bağlantılar yenisine çevrilir.
            moduleLibDir?.let { linkModule(ctx.pkg, ctx.pkgDir, it()) }
            // Eski paketlerle kurulmuş bileşenler yeniden indirilmeden onarılır.
            if (repaired.add(ctx.pkgDir.path)) {
                repairSymlinks(ctx.pkgDir).takeIf { it > 0 }?.let { log("${cfg.id}: $it sembolik bağlantı onarıldı") }
                relocateTmp(ctx.pkgDir).takeIf { it > 0 }?.let { log("${cfg.id}: $it dosyada geçici klasör yolu uygulamanınkine çevrildi") }
            }
        }

    /** Play sürümünde kayıttaki tanım katalogdakinden farklıysa (uygulama güncellendi) paketi yeniden bağlar. */
    private fun moduleSynced(inst: InstalledComponent): InstalledComponent {
        val libDir = moduleLibDir ?: return inst
        val fresh = moduleCatalog?.invoke()?.firstOrNull { it.id == inst.id }?.takeIf { it != inst.pkg } ?: return inst
        synchronized(this) { installFromModule(fresh, libDir()) }
        return installed().getValue(inst.id)
    }

    /** Depodaki paket kurulu olandan farklı mı (yeni sürüm veya aynı sürümün düzeltilmiş paketi)? */
    fun updateAvailable(pkg: ComponentPackage): Boolean {
        val inst = installed()[pkg.id] ?: return false
        val sha = pkg.builds[abi]?.sha256 ?: return false
        return inst.version != pkg.version || !sha.equals(inst.sha256, ignoreCase = true)
    }

    private val pkgRoot = File(rootDir, "packages")
    private val dataRoot = File(rootDir, "data")
    private val registry = File(rootDir, "installed.json")

    fun fetchManifest(url: String): ComponentManifest =
        ConfigJson.decodeFromString(ComponentManifest.serializer(), download(url).decodeToString())

    fun installed(): Map<String, InstalledComponent> =
        if (!registry.exists()) emptyMap()
        else ConfigJson.decodeFromString<List<InstalledComponent>>(registry.readText()).associateBy { it.id }

    fun install(pkg: ComponentPackage) {
        moduleLibDir?.let { return installFromModule(pkg, it()) }
        val build = pkg.builds[abi] ?: error(tr("{0}: '{1}' mimarisi için derleme yok", pkg.id, abi))
        log("${pkg.id} ${pkg.version} indiriliyor…")
        val bytes = download(build.url)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        require(digest.equals(build.sha256, ignoreCase = true)) { tr("{0}: sha256 uyuşmuyor", pkg.id) }

        val target = File(pkgRoot, "${pkg.id}/${pkg.version}")
        val tmp = File(pkgRoot, "${pkg.id}/.tmp-${pkg.version}")
        tmp.deleteRecursively()
        unzip(bytes, tmp)
        repairSymlinks(tmp)
        relocateTmp(tmp)
        for (rel in pkg.executables) {
            val f = File(tmp, rel)
            require(f.isFile) { tr("{0}: çalıştırılabilir bulunamadı: {1}", pkg.id, rel) }
            f.setExecutable(true, true)
        }
        target.deleteRecursively()
        require(tmp.renameTo(target)) { tr("{0}: kurulum klasörü taşınamadı", pkg.id) }

        // Aynı sürüm yeniden kurulduysa ilk kurulum yapıldı bilgisi (veri klasörü) korunur.
        val setupDone = installed()[pkg.id]?.takeIf { it.version == pkg.version }?.setupDone ?: false
        saveRegistry(installed() + (pkg.id to InstalledComponent(pkg.id, pkg.version, pkg, setupDone, build.sha256)))
        repaired += target.path
        log("${pkg.id} kuruldu: $target")
    }

    /** Play modülünün kurduğu dosyalara bağlantılar oluşturur ve paketi kurulu sayar (indirme yok). */
    private fun installFromModule(pkg: ComponentPackage, libDir: File) {
        val target = File(pkgRoot, "${pkg.id}/${pkg.version}")
        val tmp = File(pkgRoot, "${pkg.id}/.tmp-${pkg.version}")
        tmp.deleteRecursively()
        linkModule(pkg, tmp, libDir)
        target.deleteRecursively()
        require(tmp.renameTo(target)) { tr("{0}: kurulum klasörü taşınamadı", pkg.id) }
        // Önceki sürümün klasöründe yalnızca bağlantılar var.
        File(pkgRoot, pkg.id).listFiles { f -> f != target && !f.name.startsWith(".") }.orEmpty().forEach { it.deleteRecursively() }
        val setupDone = installed()[pkg.id]?.takeIf { it.version == pkg.version }?.setupDone ?: false
        saveRegistry(installed() + (pkg.id to InstalledComponent(pkg.id, pkg.version, pkg, setupDone, pkg.builds[abi]?.sha256)))
        log("${pkg.id} kuruldu (Play modülü): $target")
    }

    /**
     * [dir]'i modülün dosyalarına bağlantılarla kurar (bkz. sınıf açıklaması). Kitaplık klasörü değişmediyse (işaret
     * dosyası) dokunulmaz; değiştiyse (uygulama ya da modül güncellendi) eski bağlantılar silinip yeniden kurulur.
     */
    private fun linkModule(pkg: ComponentPackage, dir: File, libDir: File) {
        val marker = File(dir, LIBDIR_MARKER)
        if (marker.isFile && marker.readText() == libDir.path) return
        val indexFile = File(libDir, "lib${pkg.id}_files.so")
        require(indexFile.isFile) { tr("{0}: Play modülü kurulu değil", pkg.id) }
        val index = ConfigJson.decodeFromString<Map<String, String>>(indexFile.readText())
        pkg.executables.firstOrNull { it !in index }?.let { throw IllegalArgumentException(tr("{0}: Play modülünde {1} yok", pkg.id, it)) }
        if (dir.isDirectory) {
            dir.walkBottomUp().forEach { f -> if (Files.isSymbolicLink(f.toPath()) || (f != dir && f.isDirectory && f.list().isNullOrEmpty())) f.delete() }
        }
        for ((rel, lib) in index) {
            val link = File(dir, rel).toPath()
            Files.createDirectories(link.parent)
            Files.deleteIfExists(link)
            Files.createSymbolicLink(link, File(libDir, lib).toPath())
        }
        marker.writeText(libDir.path)
    }

    fun uninstall(id: String, keepData: Boolean = true) {
        File(pkgRoot, id).deleteRecursively()
        if (!keepData) File(dataRoot, id).deleteRecursively()
        saveRegistry(installed() - id)
    }

    /**
     * Kurulu çalışma ortamı paketinin klasörleri ve ortamı (uygulamalar için); kurulu değilse null. `${DATA}` yer
     * tutucusu ortamın kendi veri klasörüne çözülür (örn. npm önbelleği).
     */
    fun runtime(id: String): RuntimeInfo? {
        val inst = installed()[id] ?: return null
        val ctx = context(ComponentConfig(id))
        val bins = inst.pkg.paths.ifEmpty { listOf("\${PKG}/bin") }.map { File(ctx.expand(it)) }
        return RuntimeInfo(id, inst.version, ctx.pkgDir, bins, ctx.env)
    }

    /** İlk çalıştırma kurulumunu (gerekiyorsa) yapar ve süreç tanımını döndürür. */
    fun prepare(cfg: ComponentConfig): ProcessSupervisor.Spec {
        val ctx = context(cfg)
        val inst = ctx.inst
        val pkg = ctx.pkg
        check(!pkg.isRuntime && pkg.command.isNotEmpty()) { tr("{0} bir çalışma ortamı; tek başına çalıştırılmaz, uygulamalar kullanır", pkg.id) }
        val dataDir = ctx.dataDir
        val env = ctx.env
        fun expand(s: String) = ctx.expand(s)

        // İlk kurulumun bittiği veri klasöründe de işaretlenir: kayıt sıfırlansa (eski sürümlerde "Güncelle") veya
        // paket sürümü değişse de kurulum dolu klasör üzerinde yeniden çalışmaz.
        val marker = File(dataDir, SETUP_MARKER)
        if (pkg.setup.isNotEmpty() && inst.setupDone && !marker.exists()) runCatching { marker.writeText(inst.version) }
        if (!inst.setupDone && pkg.setup.isNotEmpty() && marker.exists()) {
            log("${pkg.id}: ilk kurulum daha önce yapılmış; yeniden yapılmıyor")
            saveRegistry(installed() + (pkg.id to inst.copy(setupDone = true)))
        } else if (!inst.setupDone && pkg.setup.isNotEmpty()) {
            setAsideIncomplete(pkg.id, dataDir)
            val files = pkg.setupFiles.entries.associate { (path, content) ->
                File(expand(path)) to ENV_REF.replace(content) { m -> ctx.envValue(m) }
            }
            try {
                files.forEach { (file, content) -> writePrivate(file, content) }
                for (cmd in pkg.setup) {
                    log("${pkg.id} kurulum: ${cmd.joinToString(" ")}")
                    val p = ProcessBuilder(cmd.map(::expand)).directory(dataDir).redirectErrorStream(true)
                        .apply { environment().putAll(env) }.start()
                    p.outputStream.close() // girdi bekleyen komut takılmasın
                    // Çıktı geldikçe loga yazılır: uzun süren veya takılan adımda nerede kalındığı görünsün.
                    val out = StringBuffer()
                    val reader = Thread {
                        runCatching {
                            p.inputStream.bufferedReader().forEachLine { line ->
                                out.append(line).append('\n')
                                log("[${pkg.id}] $line")
                            }
                        }
                    }.apply { isDaemon = true; start() }
                    if (!p.waitFor(setupTimeoutSec, TimeUnit.SECONDS)) {
                        p.destroyForcibly()
                        error(tr("{0} kurulum adımı {1} sn içinde bitmedi ve durduruldu:\n{2}", pkg.id, setupTimeoutSec, out))
                    }
                    reader.join(2_000)
                    check(p.exitValue() == 0) { tr("{0} kurulum adımı başarısız (çıkış kodu {1}):\n{2}", pkg.id, p.exitValue(), out) }
                }
            } finally {
                files.keys.forEach { it.delete() }
            }
            marker.writeText(inst.version)
            saveRegistry(installed() + (pkg.id to inst.copy(setupDone = true)))
        }

        // Komutta `${env:AD}`: bileşenin ortam değişkeni (örn. Redis parolası); yoksa anlaşılır hata.
        val command = (if (cfg.args.isNotEmpty()) listOf(pkg.command.first()) + cfg.args else pkg.command)
            .map { arg -> ENV_REF.replace(expand(arg)) { m -> ctx.envValue(m) } }
        // Eski sürümlerin kaydettiği Postgres tanımında lockFiles yok.
        val lockFiles = pkg.lockFiles.ifEmpty { if (pkg.id == PostgresDefaults.COMPONENT_ID) listOf(POSTGRES_LOCK) else emptyList() }
        lockFiles.map { File(expand(it)) }.forEach { removeStaleLock(pkg.id, it, File(command.first()).name) }
        return ProcessSupervisor.Spec(
            id = pkg.id, command = command, workDir = dataDir, env = env, restart = cfg.restart,
            version = inst.version, readiness = pkg.readiness, stopCommand = pkg.stopCommand.map(::expand),
        )
    }

    override fun task(componentId: String, taskId: String): ComponentTask? = installed()[componentId]?.pkg?.tasks?.get(taskId)

    override fun runTask(cfg: ComponentConfig, taskId: String): String {
        val ctx = context(cfg)
        val task = ctx.pkg.tasks[taskId] ?: error(tr("{0}: '{1}' bakım işi yok", cfg.id, taskId))
        check(ctx.inst.setupDone || ctx.pkg.setup.isEmpty()) { tr("{0} henüz kurulmadı (ilk deploy'da kurulur)", cfg.id) }
        val secrets = mutableListOf<String>()
        // Tek geçişte: değerin içindeki "${...}" metni yeniden genişletilmesin.
        val stdin = task.stdin?.let { text ->
            TASK_REF.replace(text) { m ->
                val value = ctx.envValue(m, m.groupValues[2]).also { secrets += it }
                if (m.groupValues[1] == "sqlstr") PostgresAdmin.dollarQuote(value) else value
            }
        }
        fun redact(s: String) = secrets.filter { it.isNotEmpty() }.fold(s) { acc, v -> acc.replace(v, "***") }

        log("${cfg.id}: ${task.title}")
        val p = ProcessBuilder(task.command.map(ctx::expand)).directory(ctx.dataDir).redirectErrorStream(true)
            .apply { environment().putAll(ctx.env) }.start()
        val output = StringBuilder()
        val reader = Thread { p.inputStream.bufferedReader().forEachLine { output.appendLine(it) } }.apply { start() }
        // Süreç girdiyi okumadan çıkarsa yazma hata verir; asıl neden çıktıda ve çıkış kodundadır.
        runCatching { p.outputStream.use { out -> stdin?.let { out.write(it.toByteArray()) } } }
        val finished = p.waitFor(task.timeoutSec, TimeUnit.SECONDS)
        if (!finished) p.destroyForcibly()
        reader.join(5_000)
        val text = redact(output.toString().trim())
        check(finished) { tr("{0} '{1}' {2} sn içinde bitmedi:\n{3}", cfg.id, task.title, task.timeoutSec, text) }
        check(p.exitValue() == 0) { tr("{0} '{1}' başarısız (çıkış {2}):\n{3}", cfg.id, task.title, p.exitValue(), text) }
        return text
    }

    override fun resetData(componentId: String) {
        val inst = installed()[componentId] ?: error(tr("{0} kurulu değil", componentId))
        File(dataRoot, componentId).deleteRecursively()
        dataRoot.listFiles { f -> f.name.startsWith("$componentId$ASIDE_SUFFIX") }.orEmpty().forEach { it.deleteRecursively() }
        saveRegistry(installed() + (componentId to inst.copy(setupDone = false)))
        log("$componentId verisi silindi; sonraki başlatmada ilk kurulum yeniden yapılacak")
    }

    /** Kilit dosyasındaki PID [program] adlı canlı bir süreç değilse dosyayı siler. */
    private fun removeStaleLock(id: String, lock: File, program: String) {
        if (!lock.isFile) return
        val pid = runCatching { lock.useLines { it.firstOrNull()?.trim()?.toLongOrNull() } }.getOrNull()
        val proc = pid?.let { File("/proc/$it") }
        // Android başka uygulamaların süreçlerini /proc'ta göstermez; görünüyorsa komut satırına bakılır.
        val alive = proc != null && proc.exists() &&
            runCatching { runsProgram(File(proc, "cmdline").readText(), program) }.getOrDefault(true)
        if (alive) return
        lock.delete()
        log("$id: eski kilit dosyası silindi (${lock.name}, PID ${pid ?: "?"} artık bu programa ait değil)")
    }

    /**
     * Yarım kalmış bir ilk kurulumdan (kesilen veya başarısız initdb) kalan dosyalar silinmez, yanına
     * `<id>.yarim-kurulum-<zaman>` olarak taşınır; kurulum boş klasörde yeniden yapılır. En yeni iki kopya tutulur.
     */
    private fun setAsideIncomplete(id: String, dataDir: File) {
        if (dataDir.list().isNullOrEmpty()) return
        val stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val aside = File(dataRoot, "$id$ASIDE_SUFFIX$stamp")
        check(dataDir.renameTo(aside)) { tr("{0}: yarım kalmış kurulum klasörü kenara alınamadı ({1})", id, dataDir.path) }
        dataDir.mkdirs()
        log("$id: önceki yarım kalmış kurulumun dosyaları ${aside.name} klasörüne taşındı; ilk kurulum yeniden yapılıyor")
        dataRoot.listFiles { f -> f.isDirectory && f.name.startsWith("$id$ASIDE_SUFFIX") }.orEmpty()
            .sortedByDescending { it.name }.drop(2).forEach { it.deleteRecursively() }
    }

    /** Yalnızca sahibinin okuyup yazabildiği dosya (izinler içerik yazılmadan önce daraltılır). */
    private fun writePrivate(file: File, content: String) {
        file.parentFile?.mkdirs()
        file.delete()
        file.createNewFile()
        file.setReadable(false, false); file.setWritable(false, false); file.setExecutable(false, false)
        file.setReadable(true, true); file.setWritable(true, true)
        file.writeText(content)
    }

    private fun saveRegistry(map: Map<String, InstalledComponent>) {
        rootDir.mkdirs()
        val tmp = File(rootDir, "installed.json.tmp")
        tmp.writeText(ConfigJson.encodeToString(map.values.toList()))
        tmp.renameTo(registry)
    }

    private fun download(url: String): ByteArray {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        try {
            check(conn.responseCode == 200) { tr("İndirme başarısız ({0}): {1}", conn.responseCode, url) }
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    /** Bkz. [repairZipSymlinks]. */
    internal fun repairSymlinks(dir: File): Int = repairZipSymlinks(dir)

    /**
     * Paket dosyalarındaki Termux geçici klasör yolunu [tmpDir] ile değiştirir (aynı uzunlukta: baştaki fazladan
     * eğik çizgiler yolu değiştirmez). Paket başına bir kez yapılır (işaret dosyası). Değişen dosya sayısını döndürür.
     */
    internal fun relocateTmp(dir: File): Int {
        val target = tmpDir ?: return 0
        val marker = File(dir, RELOCATED_MARKER)
        if (!dir.isDirectory || marker.exists()) return 0
        val width = TMP_PATHS.first().size
        if (target.length > width) {
            log("Geçici klasör yolu çok uzun, binary'lere uygulanamıyor: $target")
            return 0
        }
        val replacement = ("/".repeat(width - target.length) + target).toByteArray()
        var changed = 0
        dir.walkTopDown().filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }.forEach { f ->
            val data = f.readBytes()
            var hit = false
            for (old in TMP_PATHS) {
                if (old.contentEquals(replacement)) continue
                var i = indexOf(data, old, 0)
                while (i >= 0) {
                    replacement.copyInto(data, i)
                    hit = true
                    i = indexOf(data, old, i + old.size)
                }
            }
            if (hit) {
                f.writeBytes(data) // yerinde yazılır: çalıştırma izni korunur
                changed++
            }
        }
        marker.writeText(target)
        return changed
    }

    private fun indexOf(data: ByteArray, pattern: ByteArray, from: Int): Int {
        val first = pattern[0]
        var i = from
        val last = data.size - pattern.size
        while (i <= last) {
            if (data[i] == first) {
                var j = 1
                while (j < pattern.size && data[i + j] == pattern[j]) j++
                if (j == pattern.size) return i
            }
            i++
        }
        return -1
    }

    private fun unzip(bytes: ByteArray, dest: File) = unzipSafely(bytes.inputStream(), dest)
}

/**
 * Komut satırı (`/proc/<pid>/cmdline`, NUL ayrımlı) [program]'ı mı çalıştırıyor: ilk iki argümandan birinin dosya adı
 * (betikte ilki yorumlayıcıdır) ya da süreç adını değiştiren Postgres alt süreçleri gibi `program:` biçimi. Alt dize
 * araması yetmez: yoldaki başka bir klasör adı (`/home/runner`) programın adını içerebilir.
 */
internal fun runsProgram(cmdline: String, program: String): Boolean =
    cmdline.split('\u0000').take(2).any { arg ->
        val first = arg.substringBefore(' ')
        first.substringAfterLast('/') == program || first == "$program:"
    }
