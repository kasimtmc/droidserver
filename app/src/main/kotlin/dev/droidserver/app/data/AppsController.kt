package dev.droidserver.app.data

import android.content.Context
import android.net.Uri
import dev.droidserver.app.service.ServerService
import dev.droidserver.core.apps.AppManager
import dev.droidserver.core.apps.AppRelease
import dev.droidserver.core.apps.AppsHost
import dev.droidserver.core.apps.GitFetcher
import dev.droidserver.core.config.ConfigSecrets
import dev.droidserver.core.i18n.tr
import dev.droidserver.core.process.ProcessSupervisor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File

/**
 * Uygulamaların yeni sürümleri: zip yükleme ve Git'ten çekme, ardından deploy. Web paneli/API (`/_apps`) ve webhook
 * de buradan geçer ([AppsHost]). Aynı anda tek çekme/yükleme yapılır.
 */
class AppsController(
    private val context: Context,
    private val configRepo: ConfigRepository,
    private val secrets: SecretStore,
    val manager: AppManager,
    private val supervisor: ProcessSupervisor,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
    private val fetcher: GitFetcher = GitFetcher(),
) : AppsHost {
    private val lock = Mutex()
    private val cache = File(context.cacheDir, "app-uploads")

    /** Seçilen zip'i yeni sürüm olarak hazırlar; [deploy] ise deploy başlatır. */
    suspend fun upload(name: String, uri: Uri, deploy: Boolean): AppRelease = lock.withLock {
        withContext(Dispatchers.IO) {
            cache.mkdirs()
            val tmp = File.createTempFile("upload-", ".zip", cache)
            try {
                val input = context.contentResolver.openInputStream(uri) ?: error(tr("Dosya açılamadı"))
                input.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                manager.stage(name, tmp, "upload")
            } finally {
                tmp.delete()
            }
        }
    }.also { if (deploy) ServerService.deploy(context) }

    /**
     * Git'ten son sürümü (veya [commit]'i) çeker. Çalışan veya bekleyen sürüm aynı commit'tense ve [force] değilse
     * indirilmez (null döner).
     */
    suspend fun pull(name: String, commit: String? = null, force: Boolean = false): AppRelease? = lock.withLock {
        withContext(Dispatchers.IO) {
            val cfg = configRepo.load()
            val app = cfg.apps.firstOrNull { it.name == name } ?: error(tr("'{0}' uygulaması yok", name))
            // Yalnızca bu uygulamanın token'ı çözülür: başka bir eksik sır çekmeyi engellemesin.
            val git = app.git?.let { g ->
                g.copy(token = g.token?.let { t ->
                    if (ConfigSecrets.isRef(t)) secrets.get(t.removePrefix(ConfigSecrets.PREFIX)) ?: error(tr("Sır bulunamadı: {0}{1} (yeniden girin)", ConfigSecrets.PREFIX, t.removePrefix(ConfigSecrets.PREFIX))) else t
                })
            } ?: error(tr("'{0}' uygulamasında Git kaynağı yok", name))
            val target = commit ?: fetcher.latestCommit(git)
            if (!force && target != null) {
                val known = manager.state(name).let { st -> listOfNotNull(st.pending, st.current) }
                    .mapNotNull { id -> manager.releases(name).firstOrNull { it.id == id }?.commit }
                if (known.any { it == target }) {
                    log("[$name] Git: ${target.take(12)} zaten yüklü; değişiklik yok")
                    return@withContext null
                }
            }
            cache.mkdirs()
            log("[$name] Git: ${git.repo}@${git.branch} indiriliyor…")
            val fetched = fetcher.download(git, cache, target)
            try {
                manager.stage(name, fetched.file, "git", fetched.commit, git.subdir)
            } finally {
                fetched.file.delete()
            }
        }
    }

    override fun pullAndDeploy(app: String, commit: String?, reason: String) {
        scope.launch {
            runCatching { pull(app, commit) }
                .onSuccess { r ->
                    if (r != null) {
                        log("[$app] Git'ten yeni sürüm ($reason): ${r.commit?.take(12) ?: r.id}; deploy ediliyor")
                        ServerService.deploy(context)
                    }
                }
                .onFailure { log("[$app] Git'ten çekilemedi ($reason): ${it.message}") }
        }
    }

    override fun redeploy(reason: String) {
        log("Deploy isteği: $reason")
        ServerService.deploy(context)
    }

    override fun status(): JsonArray {
        val cfg = runCatching { configRepo.load() }.getOrNull() ?: return JsonArray(emptyList())
        val statuses = supervisor.statuses.value
        return JsonArray(cfg.apps.map { a ->
            val st = manager.state(a.name)
            val releases = manager.releases(a.name)
            buildJsonObject {
                put("name", JsonPrimitive(a.name))
                put("path", JsonPrimitive(a.publicPath))
                put("port", JsonPrimitive(a.port))
                put("enabled", JsonPrimitive(a.enabled))
                put("runtime", a.runtime?.let(::JsonPrimitive) ?: JsonNull)
                put("phase", statuses[a.processId]?.phase?.name?.let(::JsonPrimitive) ?: JsonNull)
                put("current", st.current?.let(::JsonPrimitive) ?: JsonNull)
                put("pending", st.pending?.let(::JsonPrimitive) ?: JsonNull)
                put("commit", releases.firstOrNull { it.id == st.current }?.commit?.let(::JsonPrimitive) ?: JsonNull)
                put("releases", JsonPrimitive(releases.size))
                put("git", JsonPrimitive(a.git != null))
            }
        })
    }
}
