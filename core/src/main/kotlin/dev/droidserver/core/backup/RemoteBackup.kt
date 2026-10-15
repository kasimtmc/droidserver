package dev.droidserver.core.backup

import dev.droidserver.core.config.RemoteBackupConfig
import dev.droidserver.core.config.RemoteKind
import dev.droidserver.core.i18n.tr
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutputStreamContent
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.runBlocking
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.xml.parsers.DocumentBuilderFactory

/** Uzak depodaki bir yedek. */
data class RemoteEntry(val name: String, val size: Long, val modified: Instant?)

class RemoteBackupException(message: String) : RuntimeException(message)

/** Dosya koyma, listeleme, indirme ve silme; bloklayıcı. */
interface RemoteStore {
    fun upload(name: String, file: File)
    fun list(): List<RemoteEntry>
    fun download(name: String, target: File)
    fun delete(name: String)
}

/**
 * Bulut yedeği: yerel yedek (zip) şifrelenip `.dsbk` olarak yüklenir, eskiler `keep`'e göre silinir; indirilen yedek
 * çözülüp zip olarak verilir. Yalnızca `.dsbk` dosyalarına dokunulur (klasördeki başka dosyalar korunur).
 */
class RemoteBackups(private val config: RemoteBackupConfig, private val store: RemoteStore = storeFor(config)) {

    /** Yerel yedeği şifreleyip yükler; yüklenen adı döndürür. */
    fun upload(zip: File): String {
        val name = zip.name.removeSuffix(".zip") + EXT
        val temp = File.createTempFile("yedek", EXT, zip.parentFile)
        try {
            zip.inputStream().buffered().use { input -> temp.outputStream().buffered().use { BackupCrypto.encrypt(input, it, config.passphrase) } }
            store.upload(name, temp)
        } finally {
            temp.delete()
        }
        return name
    }

    /** Yeniden eskiye. */
    fun list(): List<RemoteEntry> = store.list().filter { it.name.endsWith(EXT) }.sortedByDescending { it.name }

    /** Fazlaları siler; silinen adlar. */
    fun prune(): List<String> = list().drop(config.keep).map { it.name }.onEach(store::delete)

    /** İndirip çözer; [target] zip olur. Parola yanlışsa [BackupCrypto.WrongPassphraseException]. */
    fun download(name: String, target: File, passphrase: String = config.passphrase) {
        require(name.endsWith(EXT) && '/' !in name) { tr("Geçersiz yedek adı: {0}", name) }
        val temp = File.createTempFile("indirilen", EXT, target.parentFile)
        try {
            store.download(name, temp)
            temp.inputStream().buffered().use { input -> target.outputStream().buffered().use { BackupCrypto.decrypt(input, it, passphrase) } }
        } catch (t: Throwable) {
            target.delete()
            throw t
        } finally {
            temp.delete()
        }
    }

    fun delete(name: String) = store.delete(name)

    /** Bağlantı denemesi: küçük bir dosya yükler, listede görür ve siler. */
    fun test(): String {
        val probe = File.createTempFile("deneme", EXT)
        try {
            probe.writeText("droidserver")
            val name = ".droidserver-baglanti-denemesi$EXT"
            store.upload(name, probe)
            val seen = store.list().any { it.name == name }
            store.delete(name)
            if (!seen) throw RemoteBackupException(tr("Dosya yüklendi ama listede görünmedi; klasör/önek ayarını kontrol edin"))
            return tr("Bağlantı çalışıyor ({0} yedek var)", store.list().count { it.name.endsWith(EXT) })
        } finally {
            probe.delete()
        }
    }

    companion object {
        const val EXT = ".dsbk"

        fun storeFor(c: RemoteBackupConfig): RemoteStore = when (c.kind) {
            RemoteKind.WEBDAV -> WebDavStore(c.url, c.username, c.password)
            RemoteKind.S3 -> S3Store(c.url, c.bucket.orEmpty(), c.region?.takeIf { it.isNotBlank() } ?: "us-east-1", c.username, c.password, c.prefix)
        }
    }
}

/** Ortak HTTP yardımcıları (Ktor CIO istemcisi; zaman aşımı yok, büyük dosyalar akışla). */
internal object RemoteHttp {
    fun <T> client(block: suspend (HttpClient) -> T): T = runBlocking {
        HttpClient(CIO) { expectSuccess = false }.use { block(it) }
    }

    fun fileBody(file: File) = OutputStreamContent({ file.inputStream().use { it.copyTo(this) } }, ContentType.Application.OctetStream, contentLength = file.length())

    fun fail(what: String, status: HttpStatusCode, body: String): Nothing {
        val hint = when (status.value) {
            401, 403 -> tr(" (kullanıcı adı/anahtar veya izinler hatalı)")
            404 -> tr(" (adres, klasör veya kova yok)")
            else -> ""
        }
        throw RemoteBackupException(tr("{0} başarısız: HTTP {1}{2}{3}", what, status.value, hint, body.take(200).takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""))
    }

    /** XML (dış varlık ve DTD kapalı). */
    fun xml(text: String): Document {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = true
        f.isExpandEntityReferences = false
        runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        return f.newDocumentBuilder().parse(text.byteInputStream())
    }

    fun Element.child(ns: String?, name: String): String? {
        val list = if (ns == null) getElementsByTagName(name) else getElementsByTagNameNS(ns, name)
        return if (list.length == 0) null else list.item(0).textContent
    }
}

/** WebDAV (Nextcloud, ownCloud, Synology, Apache mod_dav…): temel kimlik doğrulama, PUT/PROPFIND/GET/DELETE. */
class WebDavStore(url: String, private val user: String, private val password: String) : RemoteStore {
    private val base = url.trim().let { if (it.endsWith("/")) it else "$it/" }
    private val auth = "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray())

    private fun target(name: String) = base + java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")

    override fun upload(name: String, file: File) = RemoteHttp.client { c ->
        suspend fun put() = c.request(target(name)) { method = HttpMethod.Put; header("Authorization", auth); setBody(RemoteHttp.fileBody(file)) }
        var r = put()
        if (r.status == HttpStatusCode.NotFound || r.status == HttpStatusCode.Conflict) {
            // Klasör yoksa oluşturulur (yalnızca son klasör; üst klasörler var olmalı).
            c.request(base) { method = HttpMethod("MKCOL"); header("Authorization", auth) }
            r = put()
        }
        if (!r.status.isSuccess()) RemoteHttp.fail(tr("Yükleme"), r.status, r.bodyAsText())
    }

    override fun list(): List<RemoteEntry> = RemoteHttp.client { c ->
        val body = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:getcontentlength/><d:getlastmodified/><d:resourcetype/></d:prop></d:propfind>"""
        val r = c.request(base) {
            method = HttpMethod("PROPFIND"); header("Authorization", auth); header("Depth", "1")
            setBody(io.ktor.http.content.TextContent(body, ContentType.Application.Xml))
        }
        if (r.status == HttpStatusCode.NotFound) return@client emptyList()
        if (!r.status.isSuccess()) RemoteHttp.fail(tr("Listeleme"), r.status, r.bodyAsText())
        val doc = RemoteHttp.xml(r.bodyAsText())
        val responses = doc.getElementsByTagNameNS("DAV:", "response")
        (0 until responses.length).mapNotNull { i ->
            val e = responses.item(i) as Element
            with(RemoteHttp) {
                val href = e.child("DAV:", "href")?.trim() ?: return@mapNotNull null
                if (href.endsWith("/")) return@mapNotNull null // klasörün kendisi veya alt klasör
                val name = URLDecoder.decode(href.substringAfterLast('/'), "UTF-8")
                val modified = e.child("DAV:", "getlastmodified")?.let { runCatching { ZonedDateTime.parse(it.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull() }
                RemoteEntry(name, e.child("DAV:", "getcontentlength")?.trim()?.toLongOrNull() ?: 0, modified)
            }
        }
    }

    override fun download(name: String, target: File) = RemoteHttp.client { c ->
        c.prepareRequest(target(name)) { method = HttpMethod.Get; header("Authorization", auth) }.execute { r ->
            if (!r.status.isSuccess()) RemoteHttp.fail(tr("İndirme"), r.status, r.bodyAsText())
            target.outputStream().use { out -> r.bodyAsChannel().toInputStream().copyTo(out) }
            Unit
        }
    }

    override fun delete(name: String) = RemoteHttp.client { c ->
        val r = c.request(target(name)) { method = HttpMethod.Delete; header("Authorization", auth) }
        if (!r.status.isSuccess() && r.status != HttpStatusCode.NotFound) RemoteHttp.fail(tr("Silme"), r.status, r.bodyAsText())
    }
}

/**
 * S3 uyumlu depo (AWS, Backblaze B2, Cloudflare R2, MinIO, Wasabi): yol biçimli adresler (`uç/kova/anahtar`) ve
 * AWS Signature V4. Yüklemede içerik özeti imzaya girer (imzasız yük desteklemeyen sağlayıcılar için).
 */
class S3Store(
    endpoint: String,
    private val bucket: String,
    private val region: String,
    private val accessKey: String,
    private val secretKey: String,
    prefix: String,
    private val clock: () -> Instant = Instant::now,
) : RemoteStore {
    private val endpoint = URI(endpoint.trim().trimEnd('/'))
    private val prefix = prefix.trim().trimStart('/').let { if (it.isEmpty() || it.endsWith("/")) it else "$it/" }

    private fun path(key: String?) = "/" + SigV4.encode(bucket, slash = true) + (key?.let { "/" + SigV4.encode(it, slash = true) } ?: "")

    private fun url(path: String, query: String = "") = endpoint.toString() + path + if (query.isEmpty()) "" else "?$query"

    private fun headers(method: String, path: String, query: Map<String, String>, payloadHash: String): Map<String, String> {
        val now = clock()
        val amzDate = SigV4.AMZ_DATE.format(now.atOffset(ZoneOffset.UTC))
        val h = linkedMapOf("host" to SigV4.hostHeader(endpoint), "x-amz-content-sha256" to payloadHash, "x-amz-date" to amzDate)
        h["authorization"] = SigV4.authorization(method, path, query, h, payloadHash, accessKey, secretKey, region, "s3", now)
        return h
    }

    private suspend fun send(c: HttpClient, method: String, key: String?, query: Map<String, String> = emptyMap(), file: File? = null) =
        run {
            val p = path(key)
            val payload = file?.let { SigV4.sha256Hex(it) } ?: SigV4.EMPTY_SHA256
            val h = headers(method, p, query, payload)
            c.prepareRequest(url(p, SigV4.query(query))) {
                this.method = HttpMethod.parse(method)
                h.filterKeys { it != "host" }.forEach { (k, v) -> header(k, v) }
                file?.let { setBody(RemoteHttp.fileBody(it)) }
            }
        }

    override fun upload(name: String, file: File) = RemoteHttp.client { c ->
        send(c, "PUT", prefix + name, file = file).execute { r -> if (!r.status.isSuccess()) RemoteHttp.fail(tr("Yükleme"), r.status, r.bodyAsText()) }
    }

    override fun list(): List<RemoteEntry> = RemoteHttp.client { c ->
        val out = mutableListOf<RemoteEntry>()
        var token: String? = null
        do {
            val q = linkedMapOf("list-type" to "2", "prefix" to prefix)
            token?.let { q["continuation-token"] = it }
            val text = send(c, "GET", null, q).execute { r ->
                if (!r.status.isSuccess()) RemoteHttp.fail(tr("Listeleme"), r.status, r.bodyAsText())
                r.bodyAsText()
            }
            val doc = RemoteHttp.xml(text)
            val contents = doc.getElementsByTagNameNS("*", "Contents")
            for (i in 0 until contents.length) {
                val e = contents.item(i) as Element
                with(RemoteHttp) {
                    val key = e.child("*", "Key") ?: continue
                    val name = key.removePrefix(prefix)
                    if ('/' in name || name.isEmpty()) continue
                    out += RemoteEntry(name, e.child("*", "Size")?.toLongOrNull() ?: 0, e.child("*", "LastModified")?.let { runCatching { Instant.parse(it) }.getOrNull() })
                }
            }
            val root = doc.documentElement
            token = with(RemoteHttp) { if (root.child("*", "IsTruncated") == "true") root.child("*", "NextContinuationToken") else null }
        } while (token != null)
        out
    }

    override fun download(name: String, target: File) = RemoteHttp.client { c ->
        send(c, "GET", prefix + name).execute { r ->
            if (!r.status.isSuccess()) RemoteHttp.fail(tr("İndirme"), r.status, r.bodyAsText())
            target.outputStream().use { out -> r.bodyAsChannel().toInputStream().copyTo(out) }
            Unit
        }
    }

    override fun delete(name: String) = RemoteHttp.client { c ->
        send(c, "DELETE", prefix + name).execute { r ->
            if (!r.status.isSuccess() && r.status != HttpStatusCode.NotFound) RemoteHttp.fail(tr("Silme"), r.status, r.bodyAsText())
        }
    }
}

/** AWS Signature Version 4 (başlıkta imza). */
object SigV4 {
    const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    val AMZ_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

    /** URI kodlaması: harf, rakam ve `-_.~` dışındakiler `%XX`; [slash] true ise `/` korunur. */
    fun encode(s: String, slash: Boolean): String = buildString {
        for (b in s.toByteArray()) {
            val c = b.toInt().toChar()
            when {
                c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' || c == '.' || c == '~' -> append(c)
                c == '/' && slash -> append(c)
                else -> append('%').append("%02X".format(b.toInt() and 0xff))
            }
        }
    }

    fun query(q: Map<String, String>): String =
        q.entries.sortedBy { encode(it.key, false) }.joinToString("&") { "${encode(it.key, false)}=${encode(it.value, false)}" }

    fun hostHeader(u: URI): String {
        val default = (u.scheme == "https" && u.port == 443) || (u.scheme == "http" && u.port == 80) || u.port == -1
        return if (default) u.host else "${u.host}:${u.port}"
    }

    fun sha256Hex(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return hex(md.digest())
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun hmac(key: ByteArray, data: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data.toByteArray())

    /** `Authorization` başlığı. [headers] imzalanacak başlıklar (küçük harf adlar; `host` dahil). */
    fun authorization(
        method: String,
        path: String,
        query: Map<String, String>,
        headers: Map<String, String>,
        payloadHash: String,
        accessKey: String,
        secretKey: String,
        region: String,
        service: String,
        now: Instant,
    ): String {
        val amzDate = AMZ_DATE.format(now.atOffset(ZoneOffset.UTC))
        val date = amzDate.take(8)
        val signed = headers.filterKeys { it != "authorization" }.mapKeys { it.key.lowercase() }.toSortedMap()
        val canonical = listOf(
            method,
            path,
            query(query),
            signed.entries.joinToString("") { "${it.key}:${it.value.trim()}\n" },
            signed.keys.joinToString(";"),
            payloadHash,
        ).joinToString("\n")
        val scope = "$date/$region/$service/aws4_request"
        val toSign = "AWS4-HMAC-SHA256\n$amzDate\n$scope\n${sha256Hex(canonical.toByteArray())}"
        val key = hmac(hmac(hmac(hmac("AWS4$secretKey".toByteArray(), date), region), service), "aws4_request")
        val signature = hex(hmac(key, toSign))
        return "AWS4-HMAC-SHA256 Credential=$accessKey/$scope, SignedHeaders=${signed.keys.joinToString(";")}, Signature=$signature"
    }
}
