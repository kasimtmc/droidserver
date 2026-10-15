package dev.droidserver.core.apps

import dev.droidserver.core.config.GitProvider
import dev.droidserver.core.config.GitSource
import dev.droidserver.core.i18n.tr
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Git deposundan kod: sağlayıcının API'siyle dalın son commit'i öğrenilir ve o commit'in zip arşivi indirilir (telefonda
 * `git` gerekmez). Token yalnızca deponun kendi sunucusuna gönderilir; başka bir sunucuya yönlendirmede (GitHub'ın
 * codeload'u) gönderilmez.
 */
class GitFetcher(
    /** Testlerde sahte sunucu için: sağlayıcının API/arşiv kökü (örn. `http://127.0.0.1:1234`). */
    private val baseOverride: String? = null,
    private val maxBytes: Long = 500L * 1024 * 1024,
) {
    data class Fetched(val file: File, val commit: String?)

    private fun base(git: GitSource): String = baseOverride ?: when (git.provider) {
        GitProvider.GITHUB -> "https://api.github.com"
        GitProvider.GITLAB -> "https://${git.host ?: "gitlab.com"}/api/v4"
        GitProvider.GITEA -> "https://${git.host}/api/v1"
        GitProvider.URL -> ""
    }

    private fun authHeaders(git: GitSource): Map<String, String> {
        val token = git.token?.takeIf { it.isNotBlank() } ?: return emptyMap()
        return when (git.provider) {
            GitProvider.GITHUB, GitProvider.URL -> mapOf("Authorization" to "Bearer $token")
            GitProvider.GITLAB -> mapOf("PRIVATE-TOKEN" to token)
            GitProvider.GITEA -> mapOf("Authorization" to "token $token")
        }
    }

    private fun commonHeaders(git: GitSource): Map<String, String> = buildMap {
        put("User-Agent", "DroidServer")
        if (git.provider == GitProvider.GITHUB) {
            put("Accept", "application/vnd.github+json")
            put("X-GitHub-Api-Version", "2022-11-28")
        }
        putAll(authHeaders(git))
    }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")

    /** Dalın son commit'i; zip adresinde bilinemez (null). */
    fun latestCommit(git: GitSource): String? {
        val b = base(git)
        val json = when (git.provider) {
            GitProvider.URL -> return null
            GitProvider.GITHUB -> get("$b/repos/${git.repo}/commits/${enc(git.branch)}", git)
            GitProvider.GITLAB -> get("$b/projects/${enc(git.repo)}/repository/commits/${enc(git.branch)}", git)
            GitProvider.GITEA -> get("$b/repos/${git.repo}/branches/${enc(git.branch)}", git)
        }
        val o = Json.parseToJsonElement(json.decodeToString()) as? JsonObject ?: throw AppException(tr("Depo yanıtı anlaşılamadı"))
        val sha = when (git.provider) {
            GitProvider.GITHUB -> o["sha"]
            GitProvider.GITLAB -> o["id"]
            else -> (o["commit"] as? JsonObject)?.get("id")
        }
        return (sha as? JsonPrimitive)?.content ?: throw AppException(tr("Depo yanıtında commit yok"))
    }

    /** Arşivi [dir]'e indirir. [commit] verilmezse dalın son hali. */
    fun download(git: GitSource, dir: File, commit: String? = null): Fetched {
        val ref = commit ?: latestCommit(git) ?: git.branch
        val b = base(git)
        val url = when (git.provider) {
            GitProvider.URL -> baseOverride?.let { it + URI(git.repo).rawPath } ?: git.repo
            GitProvider.GITHUB -> "$b/repos/${git.repo}/zipball/${enc(ref)}"
            GitProvider.GITLAB -> "$b/projects/${enc(git.repo)}/repository/archive.zip?sha=${enc(ref)}"
            GitProvider.GITEA -> "$b/repos/${git.repo}/archive/${enc(ref)}.zip"
        }
        dir.mkdirs()
        val file = File.createTempFile("git-", ".zip", dir)
        try {
            fetchTo(url, git, file)
        } catch (t: Throwable) {
            file.delete()
            throw t
        }
        return Fetched(file, commit ?: ref.takeIf { git.provider != GitProvider.URL })
    }

    private fun get(url: String, git: GitSource): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        open(url, git, 0) { input -> input.copyTo(out) }
        return out.toByteArray()
    }

    private fun fetchTo(url: String, git: GitSource, file: File) {
        open(url, git, 0) { input ->
            file.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) throw AppException(tr("Arşiv çok büyük (en çok {0} MB)", maxBytes / (1024 * 1024)))
                    o.write(buf, 0, n)
                }
            }
        }
    }

    /** Yönlendirmeler elle izlenir: başka sunucuya giderken kimlik başlıkları gönderilmez. */
    private fun open(url: String, git: GitSource, hops: Int, read: (java.io.InputStream) -> Unit) {
        if (hops > 5) throw AppException(tr("Çok fazla yönlendirme"))
        val uri = URI(url)
        if (uri.scheme != "https" && baseOverride == null) throw AppException(tr("Yalnızca https adresleri: {0}", url))
        val conn = uri.toURL().openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        val sameHost = hops == 0 || uri.host == originHost(git)
        (if (sameHost) commonHeaders(git) else mapOf("User-Agent" to "DroidServer")).forEach { (k, v) -> conn.setRequestProperty(k, v) }
        try {
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location") ?: throw AppException(tr("Yönlendirme adresi yok"))
                return open(uri.resolve(location).toString(), git, hops + 1, read)
            }
            if (code == 401 || code == 403) throw AppException(tr("Depoya erişim reddedildi (HTTP {0}); gizli depoysa token girin ve okuma izni olduğundan emin olun", code))
            if (code == 404) throw AppException(tr("Depo, dal veya arşiv bulunamadı (HTTP 404); gizli depoda token gerekir"))
            if (code !in 200..299) throw AppException(tr("Depo HTTP {0} döndü", code))
            conn.inputStream.use(read)
        } finally {
            conn.disconnect()
        }
    }

    private fun originHost(git: GitSource): String? = runCatching {
        URI(baseOverride ?: if (git.provider == GitProvider.URL) git.repo else base(git)).host
    }.getOrNull()
}

/**
 * Push webhook'u: imza doğrulama ve dal. Desteklenenler: GitHub (`X-Hub-Signature-256`, gövdenin HMAC-SHA256'sı),
 * Gitea/Forgejo (`X-Gitea-Signature` / `X-Forgejo-Signature`, hex HMAC-SHA256), GitLab (`X-Gitlab-Token`: sırrın
 * kendisi) ve genel istemciler (`X-DroidServer-Token` veya `?token=`: sırrın kendisi). Karşılaştırmalar sabit sürelidir.
 */
object GitWebhook {
    fun verify(header: (String) -> String?, query: (String) -> String?, body: ByteArray, secret: String): Boolean {
        val key = secret.toByteArray()
        header("X-Hub-Signature-256")?.let { sig ->
            return same(sig.removePrefix("sha256=").lowercase(), hmacHex(key, body))
        }
        (header("X-Gitea-Signature") ?: header("X-Forgejo-Signature"))?.let { sig ->
            return same(sig.lowercase(), hmacHex(key, body))
        }
        val token = header("X-Gitlab-Token") ?: header("X-DroidServer-Token") ?: query("token") ?: return false
        return same(token, secret)
    }

    /** Olay ping/test mi (GitHub `ping`)? */
    fun isPing(header: (String) -> String?): Boolean =
        header("X-GitHub-Event") == "ping" || header("X-Gitea-Event") == "ping"

    /** Push edilen dal (`refs/heads/main` → `main`); gövde anlaşılamazsa null. */
    fun pushedBranch(body: ByteArray): String? = runCatching {
        val o = Json.parseToJsonElement(body.decodeToString()) as JsonObject
        (o["ref"] as? JsonPrimitive)?.content?.removePrefix("refs/heads/")
    }.getOrNull()

    /** Push sonrası commit (GitHub/Gitea `after`, GitLab `checkout_sha`). */
    fun commit(body: ByteArray): String? = runCatching {
        val o = Json.parseToJsonElement(body.decodeToString()) as JsonObject
        ((o["checkout_sha"] ?: o["after"]) as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it.any { c -> c != '0' } }
    }.getOrNull()

    fun hmacHex(key: ByteArray, body: ByteArray): String =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(body).joinToString("") { "%02x".format(it) }

    /** Sabit süreli karşılaştırma. */
    private fun same(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
}

/** App tarafının sunucuya verdiği uygulama işlemleri (durum listesi, Git'ten çekip deploy). */
interface AppsHost {
    /** Uygulamaların durumu (yönetici): ad, yol, sürümler, süreç durumu. */
    fun status(): JsonArray

    /** Git'ten yeni sürümü çekip deploy etmeyi arka planda başlatır; [commit] webhook'tan geldiyse o commit. */
    fun pullAndDeploy(app: String, commit: String?, reason: String)

    /** Konfigürasyonu yeniden deploy eder (bekleyen sürüm varsa devreye girer). */
    fun redeploy(reason: String)
}
