package dev.droidserver.core.update

import dev.droidserver.core.i18n.tr
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/** GitHub Releases'taki bir uygulama sürümü (yalnızca APK'sı olanlar). */
data class ReleaseInfo(
    /** `v` öneki atılmış sürüm, örn. `1.2.0` veya `1.3.0-beta.1`. */
    val version: String,
    val name: String,
    /** Sürüm notları (Markdown). */
    val notes: String,
    val prerelease: Boolean,
    val apkName: String,
    val apkUrl: String,
    val apkSize: Long,
    val sha256Url: String?,
    val publishedAt: String?,
    val pageUrl: String?,
)

/**
 * Uygulamanın kendini güncellemesi için GitHub Releases istemcisi. Sürüm etiketi `v1.2.3` (ön sürüm `v1.3.0-beta.1`),
 * varlıklar `droidserver-<sürüm>.apk` ve yanında `droidserver-<sürüm>.apk.sha256` (bkz. `.github/workflows/release.yml`).
 * APK'sı olmayan sürümler (ör. bileşen paketlerinin `components` sürümü) atlanır.
 */
class ReleaseChecker(
    private val repo: String,
    private val apiUrl: String = "https://api.github.com",
    private val timeoutMs: Int = 15_000,
) {
    /** [current]'ten yeni olan en son sürüm; yoksa null. Ön sürümler yalnızca [includePrerelease] ile. */
    fun newerThan(current: String, includePrerelease: Boolean): ReleaseInfo? =
        latest(includePrerelease)?.takeIf { Versions.compare(it.version, current) > 0 }

    fun latest(includePrerelease: Boolean): ReleaseInfo? =
        parse(get("$apiUrl/repos/$repo/releases?per_page=30"), includePrerelease)

    /**
     * APK'yı [dest]'e indirir; boyutu ve sürümdeki `.sha256` dosyasıyla özeti doğrular (uyuşmazsa dosya silinir ve
     * istisna atılır). Özet dosyası olmayan sürüm kurulmaz.
     */
    fun download(release: ReleaseInfo, dest: File, progress: (Long, Long) -> Unit = { _, _ -> }): File {
        val shaUrl = release.sha256Url ?: throw IOException(tr("Sürümde {0}.sha256 dosyası yok; güvenle doğrulanamadığı için kurulmaz", release.apkName))
        val expected = get(shaUrl).trim().substringBefore(' ').lowercase()
        if (!Regex("^[0-9a-f]{64}$").matches(expected)) throw IOException(tr("Özet dosyası okunamadı"))
        dest.parentFile?.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        val c = open(release.apkUrl, accept = "application/octet-stream")
        try {
            if (c.responseCode != 200) throw IOException(tr("İndirme başarısız (HTTP {0})", c.responseCode))
            var read = 0L
            c.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        read += n
                        if (read > MAX_APK) throw IOException(tr("Dosya beklenenden büyük"))
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                        progress(read, release.apkSize)
                    }
                }
            }
            if (release.apkSize > 0 && read != release.apkSize) throw IOException(tr("İndirme yarım kaldı ({0} / {1} bayt)", read, release.apkSize))
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (actual != expected) throw IOException(tr("İndirilen dosyanın özeti tutmuyor; dosya bozuk veya değiştirilmiş"))
            return dest
        } catch (e: Exception) {
            dest.delete()
            throw e
        } finally {
            c.disconnect()
        }
    }

    private fun open(url: String, accept: String): HttpURLConnection =
        (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "DroidServer-Updater")
            // GitHub varlık indirmeleri başka bir sunucuya (objects.githubusercontent.com) yönlendirir; yalnızca HTTPS.
            instanceFollowRedirects = true
        }

    private fun get(url: String): String {
        val c = open(url, accept = "application/vnd.github+json")
        try {
            if (c.responseCode != 200) throw IOException(tr("GitHub yanıt vermedi (HTTP {0})", c.responseCode))
            return c.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            c.disconnect()
        }
    }

    companion object {
        private const val MAX_APK = 300L * 1024 * 1024
        private val APK = Regex("^droidserver-.*\\.apk$")

        /** `/releases` yanıtından ilk uygun sürüm (GitHub en yeniyi başta verir). */
        fun parse(json: String, includePrerelease: Boolean): ReleaseInfo? {
            val releases = Json.parseToJsonElement(json).jsonArray.map { it.jsonObject }
            return releases.asSequence()
                .filter { it.bool("draft") != true }
                .filter { includePrerelease || it.bool("prerelease") != true }
                .mapNotNull(::toInfo)
                .filter { Versions.parse(it.version) != null }
                .maxWithOrNull { a, b -> Versions.compare(a.version, b.version) }
        }

        private fun toInfo(r: JsonObject): ReleaseInfo? {
            val tag = r.str("tag_name") ?: return null
            val assets = r["assets"]?.jsonArray?.map { it.jsonObject }.orEmpty()
            val apk = assets.firstOrNull { APK.matches(it.str("name").orEmpty()) } ?: return null
            val apkName = apk.str("name")!!
            val sha = assets.firstOrNull { it.str("name") == "$apkName.sha256" }
            return ReleaseInfo(
                version = tag.removePrefix("v"),
                name = r.str("name")?.takeIf { it.isNotBlank() } ?: tag,
                notes = r.str("body").orEmpty(),
                prerelease = r.bool("prerelease") == true,
                apkName = apkName,
                apkUrl = apk.str("browser_download_url") ?: return null,
                apkSize = (apk["size"] as? JsonPrimitive)?.longOrNull ?: 0,
                sha256Url = sha?.str("browser_download_url"),
                publishedAt = r.str("published_at"),
                pageUrl = r.str("html_url"),
            )
        }

        private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull
    }
}

/** Anlamsal sürüm karşılaştırması (`1.2.3`, `1.3.0-beta.2`; ön sürüm aynı sürümün kararlısından eskidir). */
object Versions {
    data class Version(val numbers: List<Int>, val pre: List<String>)

    fun parse(text: String): Version? {
        val t = text.trim().removePrefix("v").substringBefore('+')
        val core = t.substringBefore('-')
        val numbers = core.split('.').map { it.toIntOrNull() ?: return null }
        if (numbers.isEmpty() || numbers.size > 4) return null
        val pre = if ('-' in t) t.substringAfter('-').split('.').filter { it.isNotEmpty() } else emptyList()
        return Version(numbers + List(4 - numbers.size) { 0 }, pre)
    }

    /** a > b ise pozitif; çözülemeyen sürüm en eski sayılır. */
    fun compare(a: String, b: String): Int {
        val x = parse(a)
        val y = parse(b)
        if (x == null || y == null) return (if (x == null) 0 else 1) - (if (y == null) 0 else 1)
        for (i in x.numbers.indices) x.numbers[i].compareTo(y.numbers[i]).takeIf { it != 0 }?.let { return it }
        if (x.pre.isEmpty() || y.pre.isEmpty()) return y.pre.size.coerceAtMost(1) - x.pre.size.coerceAtMost(1)
        for (i in 0 until minOf(x.pre.size, y.pre.size)) {
            val p = x.pre[i]
            val q = y.pre[i]
            val c = when {
                p.toIntOrNull() != null && q.toIntOrNull() != null -> p.toInt().compareTo(q.toInt())
                p.toIntOrNull() != null -> -1
                q.toIntOrNull() != null -> 1
                else -> p.compareTo(q)
            }
            if (c != 0) return c
        }
        return x.pre.size.compareTo(y.pre.size)
    }
}
