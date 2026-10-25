package com.plyg.droidserver.core.apps

import com.plyg.droidserver.core.components.RuntimeInfo
import com.plyg.droidserver.core.components.repairZipSymlinks
import com.plyg.droidserver.core.components.unzipSafely
import com.plyg.droidserver.core.config.AppConfig
import com.plyg.droidserver.core.config.ConfigJson
import com.plyg.droidserver.core.i18n.tr
import com.plyg.droidserver.core.process.ProcessSupervisor
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** Uygulamanın bir sürümü (`apps/<ad>/releases/<id>`). */
@Serializable
data class AppRelease(
    val id: String,
    val createdAt: String,
    /** `upload` (zip yüklendi) veya `git`. */
    val source: String,
    /** Git'ten geldiyse commit (kısa olabilir). */
    val commit: String? = null,
    val sizeBytes: Long = 0,
    /** Kurulum komutları ([AppConfig.build]) bu sürümde çalıştı mı. */
    val built: Boolean = false,
)

/** Çalışan ([current]) ve deploy'u bekleyen ([pending]) sürüm; son başarısız denemeler. */
@Serializable
data class AppState(
    val current: String? = null,
    val pending: String? = null,
    /** Sürüm → neden (son 5). */
    val failed: Map<String, String> = emptyMap(),
)

class AppException(message: String) : RuntimeException(message)

/**
 * Kullanıcı uygulamalarının sürümleri ve süreç tanımları. Klasörler: `apps/<ad>/releases/<sürüm>` (kod; yeni sürüm yeni
 * klasördür, eskisine dokunulmaz), `apps/<ad>/data` (sürümler arası kalıcı veri, `DATA_DIR`), `apps/<ad>/tmp`.
 *
 * Yeni sürüm [stage] ile hazırlanır ve deploy'da devreye girer ([prepare] → başlat → [awaitHealthy] → [promote]);
 * sağlık denetiminden geçmezse [markFailed] ile bırakılır ve çalışan sürüm sürer. Geri dönmek için eski sürüm
 * [activate] edilip deploy edilir.
 */
class AppManager(
    private val rootDir: File,
    /** Kurulu çalışma ortamı (app: ComponentManager.runtime). */
    private val runtimes: (String) -> RuntimeInfo? = { null },
    private val log: (String) -> Unit = ::println,
    private val buildTimeoutSec: Long = 900,
    /** Uygulama sürecinin devraldığı PATH (Android'de `/system/bin` vb.). */
    private val basePath: String = System.getenv("PATH") ?: "/system/bin:/usr/bin:/bin",
    /**
     * Uygulamanın klasöründeki derlenmiş kod (ELF: npm/pip yerel eklentileri `.node`/`.so`, programlar) çalışabilir mi.
     * Play sürümünde hayır: Android, uygulama klasöründen yüklenen derlenmiş kodu engeller (W^X); uygulama anlaşılır
     * bir nedenle başlatılmaz.
     */
    private val nativeCode: Boolean = true,
) {
    private fun appDir(name: String) = File(rootDir, name)
    private fun releasesDir(name: String) = File(appDir(name), "releases")
    fun dataDir(name: String) = File(appDir(name), "data")
    private fun stateFile(name: String) = File(appDir(name), "state.json")
    private fun releaseDir(name: String, id: String) = File(releasesDir(name), id)
    private fun releaseFile(name: String, id: String) = File(releaseDir(name, id), RELEASE_META)

    @Synchronized
    fun state(name: String): AppState =
        stateFile(name).takeIf { it.isFile }?.let { runCatching { ConfigJson.decodeFromString(AppState.serializer(), it.readText()) }.getOrNull() } ?: AppState()

    private fun saveState(name: String, state: AppState) {
        appDir(name).mkdirs()
        val tmp = File(appDir(name), "state.json.tmp")
        tmp.writeText(ConfigJson.encodeToString(AppState.serializer(), state))
        if (!tmp.renameTo(stateFile(name))) { stateFile(name).delete(); tmp.renameTo(stateFile(name)) }
    }

    /** Sürümler, en yeni önce. */
    fun releases(name: String): List<AppRelease> =
        releasesDir(name).listFiles { f -> f.isDirectory && !f.name.startsWith(".") }.orEmpty()
            .mapNotNull { dir -> readRelease(name, dir.name) }
            .sortedByDescending { it.id }

    private fun readRelease(name: String, id: String): AppRelease? =
        releaseFile(name, id).takeIf { it.isFile }?.let { runCatching { ConfigJson.decodeFromString(AppRelease.serializer(), it.readText()) }.getOrNull() }

    private fun writeRelease(name: String, r: AppRelease) = releaseFile(name, r.id).writeText(ConfigJson.encodeToString(AppRelease.serializer(), r))

    /**
     * Zip'i yeni sürüm olarak hazırlar; sonraki deploy'da devreye girer. Arşivdeki tek üst klasör (GitHub arşivleri gibi)
     * atlanır; [subdir] verilirse yalnızca o alt klasör alınır. Betikler (`#!`) ve ELF dosyaları çalıştırılabilir yapılır.
     */
    @Synchronized
    fun stage(name: String, zip: File, source: String, commit: String? = null, subdir: String? = null): AppRelease {
        require(AppConfig.NAME.matches(name)) { tr("Uygulama adı geçersiz: {0}", name) }
        val id = newReleaseId(name)
        val work = File(releasesDir(name), ".tmp-$id").apply { deleteRecursively(); mkdirs() }
        try {
            zip.inputStream().buffered().use { unzipSafely(it, work, MAX_UNPACKED, MAX_ENTRIES) }
            var root = work
            // Tek üst klasör (ör. `depo-ab12cd/`) varsa içine girilir.
            val top = work.listFiles().orEmpty()
            if (top.size == 1 && top[0].isDirectory) root = top[0]
            if (subdir != null) {
                root = File(root, subdir).canonicalFile
                if (!root.path.startsWith(work.canonicalPath + File.separator) || !root.isDirectory) throw AppException(tr("Arşivde '{0}' klasörü yok", subdir))
            }
            if (root.listFiles().isNullOrEmpty()) throw AppException(tr("Arşiv boş"))
            repairZipSymlinks(root)
            markExecutables(root)
            val target = releaseDir(name, id)
            if (!root.renameTo(target)) throw AppException(tr("Sürüm klasörü taşınamadı"))
            val size = target.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            val release = AppRelease(id, Instant.now().toString(), source, commit, size)
            writeRelease(name, release)
            saveState(name, state(name).copy(pending = id))
            log("[$name] yeni sürüm hazır: $id (${size / 1024} KB${commit?.let { ", commit ${it.take(12)}" } ?: ""}); deploy'da devreye girecek")
            return release
        } finally {
            work.deleteRecursively()
        }
    }

    /** Eski bir sürümü sonraki deploy'da çalışacak sürüm yapar (geri dönüş). */
    @Synchronized
    fun activate(name: String, releaseId: String) {
        if (readRelease(name, releaseId) == null) throw AppException(tr("{0} sürümü yok", releaseId))
        val st = state(name)
        saveState(name, st.copy(pending = releaseId.takeIf { it != st.current }))
    }

    /** Bekleyen sürümü bırakır (çalışan sürüm değişmez). */
    @Synchronized
    fun discardPending(name: String) = saveState(name, state(name).copy(pending = null))

    /** Uygulamanın tüm sürümlerini ve verisini siler. */
    @Synchronized
    fun delete(name: String) {
        appDir(name).deleteRecursively()
    }

    /** Deploy'da başlatılacak sürüm ve süreç tanımı. [candidate]: bekleyen yeni sürüm (sağlık denetiminden geçmeli). */
    data class Prepared(val spec: ProcessSupervisor.Spec, val release: String, val candidate: Boolean)

    /**
     * Başlatılacak sürümü seçer (bekleyen varsa o, [currentOnly] ise çalışan), kurulum komutlarını (bir kez) çalıştırır
     * ve süreç tanımını üretir. [sharedEnv]: ortak ortam değişkenleri (sırları çözülmüş). Sürüm yoksa [AppException].
     */
    fun prepare(app: AppConfig, sharedEnv: Map<String, String>, currentOnly: Boolean = false): Prepared {
        val st = state(app.name)
        val id = (if (currentOnly) null else st.pending) ?: st.current
            ?: throw AppException(tr("{0}: henüz kod yüklenmedi (Uygulamalar → Zip yükle veya Git'ten çek)", app.name))
        val release = readRelease(app.name, id) ?: throw AppException(tr("{0}: {1} sürümünün klasörü yok", app.name, id))
        val dir = releaseDir(app.name, id)
        val runtime = app.runtime?.let { rt -> runtimes(rt) ?: throw AppException(tr("{0}: '{1}' çalışma ortamı kurulu değil (Bileşenler → Katalog)", app.name, rt)) }
        val env = environment(app, sharedEnv, runtime, dir)
        if (!release.built && app.build.isNotEmpty()) {
            for (cmd in app.build) runBuild(app.name, cmd, dir, env, runtime)
            writeRelease(app.name, release.copy(built = true))
        }
        if (!nativeCode) nativeFiles(dir).takeIf { it.isNotEmpty() }?.let { found ->
            throw AppException(
                tr("{0}: uygulamanın klasöründe derlenmiş kod var ({1}); Play sürümünde Android uygulama klasöründeki derlenmiş kodu çalıştırmaz. Yerel eklentisi olmayan paketler kullanın ya da GitHub sürümünü kurun.",
                    app.name, found.joinToString()),
            )
        }
        val command = listOf(resolveProgram(app.command.first(), dir, runtime)) + app.command.drop(1)
        val spec = ProcessSupervisor.Spec(
            id = app.processId, command = command, workDir = dir, env = env, restart = app.restart, version = id,
        )
        return Prepared(spec, id, candidate = id != st.current)
    }

    /** Sürüm sağlıklı çalıştı: çalışan sürüm olur, eski sürümler ([AppConfig.keepReleases]) budanır. */
    @Synchronized
    fun promote(app: AppConfig, releaseId: String) {
        val st = state(app.name)
        if (st.current == releaseId && st.pending == null) return
        saveState(app.name, st.copy(current = releaseId, pending = st.pending?.takeIf { it != releaseId }))
        log("[${app.name}] çalışan sürüm: $releaseId")
        prune(app, releaseId)
    }

    /** Sürüm başlatılamadı veya sağlıksız: bekleyen sürüm bırakılır, neden kaydedilir. */
    @Synchronized
    fun markFailed(name: String, releaseId: String, reason: String) {
        val st = state(name)
        val failed = (st.failed + (releaseId to reason)).entries.toList().takeLast(5).associate { it.key to it.value }
        saveState(name, st.copy(pending = st.pending?.takeIf { it != releaseId }, failed = failed))
    }

    private fun prune(app: AppConfig, keep: String) {
        val all = releases(app.name)
        val pending = state(app.name).pending
        all.drop(app.keepReleases).filter { it.id != keep && it.id != pending }.forEach {
            releaseDir(app.name, it.id).deleteRecursively()
            log("[${app.name}] eski sürüm silindi: ${it.id}")
        }
    }

    /**
     * Süreç çalışırken sağlık denetimini bekler: [AppConfig.health] yolu 2xx/3xx dönmeli (yol yoksa port bağlantı kabul
     * etmeli). Sağlıklıysa null, değilse nedeni.
     */
    suspend fun awaitHealthy(app: AppConfig, isAlive: () -> Boolean, now: () -> Long = System::currentTimeMillis): String? {
        val deadline = now() + app.startTimeoutSec * 1000L
        var last = tr("yanıt yok")
        delay(300)
        while (now() < deadline) {
            if (!isAlive()) return tr("süreç kapandı (Loglar'a bakın)")
            val result = runCatching { probe(app) }
            if (result.getOrNull() == true) return null
            last = result.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName } ?: last
            delay(500)
        }
        return tr("{0} sn içinde sağlıklı yanıt vermedi: {1}", app.startTimeoutSec, last)
    }

    private fun probe(app: AppConfig): Boolean {
        val path = app.health
        if (path == null) {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", app.port), 1_000) }
            return true
        }
        val conn = URI("http://127.0.0.1:${app.port}$path").toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 1_000
        conn.readTimeout = 3_000
        conn.instanceFollowRedirects = false
        try {
            val code = conn.responseCode
            if (code in 200..399) return true
            throw AppException("HTTP $code")
        } finally {
            conn.disconnect()
        }
    }

    /** Uygulamanın ortamı: çalışma ortamınınki, ortak ve uygulamaya özel değişkenler; en üstte sunucunun verdikleri. */
    internal fun environment(app: AppConfig, sharedEnv: Map<String, String>, runtime: RuntimeInfo?, dir: File): Map<String, String> {
        val data = dataDir(app.name).apply { mkdirs() }
        val tmp = File(appDir(app.name), "tmp").apply { mkdirs() }
        val path = (runtime?.binDirs.orEmpty().map { it.path } + listOf(File(dir, "node_modules/.bin").path, File(dir, "bin").path) + basePath)
            .joinToString(":")
        return buildMap {
            runtime?.env?.let(::putAll)
            if (app.sharedEnv) putAll(sharedEnv)
            putAll(app.env)
            put("PATH", path)
            put("PORT", app.port.toString())
            put("HOST", "127.0.0.1")
            put("BASE_PATH", app.publicPath)
            put("DATA_DIR", data.absolutePath)
            put("HOME", data.absolutePath)
            put("TMPDIR", tmp.absolutePath)
            put("DROIDSERVER_APP", app.name)
        }
    }

    /** Komutun ilk öğesi: yol içeriyorsa sürüm klasörüne göre; değilse çalışma ortamının `bin`'inde, sonra sürümde aranır. */
    internal fun resolveProgram(program: String, dir: File, runtime: RuntimeInfo?): String {
        if (program.startsWith("/")) return program
        if ('/' in program) {
            val f = File(dir, program).canonicalFile
            if (!f.path.startsWith(dir.canonicalPath + File.separator)) throw AppException(tr("Komut sürüm klasörünün dışında: {0}", program))
            if (!f.isFile) throw AppException(tr("Komut bulunamadı: {0}", program))
            f.setExecutable(true, true)
            return f.path
        }
        runtime?.binDirs?.map { File(it, program) }?.firstOrNull { it.isFile }?.let { return it.path }
        listOf(File(dir, "node_modules/.bin/$program"), File(dir, "bin/$program"), File(dir, program)).firstOrNull { it.isFile }?.let {
            it.setExecutable(true, true)
            return it.path
        }
        // Sistemdeki program (örn. sh): ProcessBuilder üst sürecin PATH'inde arar.
        basePath.split(':').map { File(it, program) }.firstOrNull { it.isFile }?.let { return it.path }
        throw AppException(
            if (runtime == null) tr("'{0}' bulunamadı; çalışma ortamı seçin (örn. nodejs) veya koddaki dosyanın yolunu yazın (./{0})", program)
            else tr("'{0}' bulunamadı ({1} ortamında veya kodda yok)", program, runtime.id),
        )
    }

    private fun runBuild(name: String, cmd: List<String>, dir: File, env: Map<String, String>, runtime: RuntimeInfo?) {
        val command = listOf(resolveProgram(cmd.first(), dir, runtime)) + cmd.drop(1)
        log("[$name] kurulum: ${cmd.joinToString(" ")}")
        val p = ProcessBuilder(command).directory(dir).redirectErrorStream(true).apply { environment().putAll(env) }.start()
        p.outputStream.close()
        val out = StringBuffer()
        val reader = Thread {
            runCatching { p.inputStream.bufferedReader().forEachLine { out.append(it).append('\n'); log("[$name] $it") } }
        }.apply { isDaemon = true; start() }
        if (!p.waitFor(buildTimeoutSec, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            throw AppException(tr("{0}: kurulum komutu {1} sn içinde bitmedi: {2}", name, buildTimeoutSec, cmd.joinToString(" ")))
        }
        reader.join(2_000)
        if (p.exitValue() != 0) {
            throw AppException(tr("{0}: kurulum komutu başarısız (çıkış {1}): {2}\n{3}", name, p.exitValue(), cmd.joinToString(" "), out.toString().takeLast(2_000)))
        }
    }

    private fun newReleaseId(name: String): String {
        val base = STAMP.format(Instant.now())
        var id = base
        var n = 1
        while (releaseDir(name, id).exists()) id = "$base-${n++}"
        return id
    }

    /** Klasördeki ilk birkaç ELF dosyası (göreli yollar). */
    private fun nativeFiles(root: File): List<String> =
        root.walkTopDown().filter { it.isFile && it.length() >= 4 && isElf(it) }.take(3).map { it.relativeTo(root).path }.toList()

    private fun isElf(f: File): Boolean {
        val head = ByteArray(4)
        val n = runCatching { f.inputStream().use { it.read(head) } }.getOrDefault(0)
        return n == 4 && head[0] == 0x7F.toByte() && head[1] == 'E'.code.toByte() && head[2] == 'L'.code.toByte() && head[3] == 'F'.code.toByte()
    }

    /** `#!` ile başlayan betikler ve ELF dosyaları çalıştırılabilir yapılır (zip izin bitlerini korumaz). */
    private fun markExecutables(root: File) {
        root.walkTopDown().filter { it.isFile && it.length() >= 4 }.forEach { f ->
            val head = ByteArray(4)
            val n = runCatching { f.inputStream().use { it.read(head) } }.getOrDefault(0)
            val script = n >= 2 && head[0] == '#'.code.toByte() && head[1] == '!'.code.toByte()
            val elf = n == 4 && head[0] == 0x7F.toByte() && head[1] == 'E'.code.toByte() && head[2] == 'L'.code.toByte() && head[3] == 'F'.code.toByte()
            if (script || elf) f.setExecutable(true, true)
        }
    }

    companion object {
        const val RELEASE_META = ".droidserver-release.json"
        /** Açılmış kodun en büyük boyutu ve dosya sayısı. */
        const val MAX_UNPACKED = 1024L * 1024 * 1024
        const val MAX_ENTRIES = 200_000
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
    }
}
