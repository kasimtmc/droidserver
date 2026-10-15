package dev.droidserver.core.webdav

import dev.droidserver.core.config.WebDavConfig
import dev.droidserver.core.i18n.tr
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.http.content.LocalFileContent
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.util.AttributeKey
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Paylaşılan klasör: adres parçası, yerel klasör, salt okunur mu, nokta ile başlayan adlar gizli mi. */
data class DavRoot(val name: String, val dir: File, val readOnly: Boolean, val hideDotFiles: Boolean = false)

/** İstekte kimlik bilgisi yoktu: 401 bir meydan okumadır, hatalı deneme sayılmaz (güvenlik duvarı sayacı). */
val DavChallenge = AttributeKey<Boolean>("DavChallenge")

/**
 * WebDAV sunucusu (RFC 4918, sınıf 1 ve 2): PROPFIND, GET/HEAD, PUT, DELETE, MKCOL, COPY, MOVE, PROPPATCH, LOCK/UNLOCK.
 * Windows Gezgini, macOS Finder, Linux (GNOME/KDE, davfs2) ve dosya yöneticisi uygulamalarıyla çalışır.
 *
 * Güvenlik:
 * - Kimlik Digest (MD5, qop=auth; parola ağda açık gitmez) veya yalnızca HTTPS üzerinden Basic. Hatalı denemeler genel
 *   güvenlik duvarı sayacına girer; kimliksiz ilk istek (meydan okuma) sayılmaz.
 * - Yollar yalnızca paylaşılan kökün içinde çözülür (`..`, sembolik bağlantı ve kök dışına çıkan yol reddedilir).
 * - Kilitler gerçek değildir (istemciler yazmadan önce kilit ister; tek kullanıcılı sürücüde yeterli).
 */
class WebDav(private val config: WebDavConfig, roots: List<DavRoot>, private val clock: () -> Instant = Instant::now) {
    private val roots = roots.associateBy { it.name }
    private val base = config.path.trimEnd('/')
    private val maxBytes = config.maxFileMb.toLong() * 1024 * 1024
    private val nonceKey = ByteArray(32).also(SecureRandom()::nextBytes)

    fun install(route: Route) {
        route.route(base) {
            handle { serve(call, emptyList()) }
            route("{path...}") { handle { serve(call, call.parameters.getAll("path").orEmpty()) } }
        }
    }

    private suspend fun serve(call: ApplicationCall, segments: List<String>) {
        if (!authorized(call)) return
        val method = call.request.httpMethod.value.uppercase()
        val target = resolve(segments) ?: return call.respond(HttpStatusCode.Forbidden)
        // Olmayan paylaşım.
        if (target.root == null && target.segments.isNotEmpty() && method != "OPTIONS") return call.respond(HttpStatusCode.NotFound)
        when (method) {
            "OPTIONS" -> {
                call.response.header("DAV", "1, 2")
                call.response.header("MS-Author-Via", "DAV")
                call.response.header(HttpHeaders.Allow, ALLOW)
                call.respond(HttpStatusCode.OK)
            }
            "PROPFIND" -> propfind(call, target)
            "GET", "HEAD" -> get(call, target, method == "HEAD")
            "PUT" -> put(call, target)
            "DELETE" -> delete(call, target)
            "MKCOL" -> mkcol(call, target)
            "COPY", "MOVE" -> copyOrMove(call, target, method == "MOVE")
            "PROPPATCH" -> proppatch(call, target)
            "LOCK" -> lock(call, target)
            "UNLOCK" -> call.respond(HttpStatusCode.NoContent)
            else -> {
                call.response.header(HttpHeaders.Allow, ALLOW)
                call.respond(HttpStatusCode.MethodNotAllowed)
            }
        }
    }

    // ---- kimlik ----

    private suspend fun authorized(call: ApplicationCall): Boolean {
        val header = call.request.headers[HttpHeaders.Authorization]
        val secure = secure(call)
        val ok = when {
            header == null -> false
            header.startsWith("Digest ", ignoreCase = true) -> digestOk(header.substring(7), call.request.httpMethod.value)
            header.startsWith("Basic ", ignoreCase = true) && secure -> basicOk(header.substring(6))
            else -> false
        }
        if (ok) return true
        if (header == null) call.attributes.put(DavChallenge, true)
        call.response.header(HttpHeaders.WWWAuthenticate, "Digest realm=\"$REALM\", qop=\"auth\", algorithm=MD5, nonce=\"${nonce()}\"")
        if (secure) call.response.headers.append(HttpHeaders.WWWAuthenticate, "Basic realm=\"$REALM\", charset=\"UTF-8\"")
        call.respondText(tr("Kimlik doğrulama gerekli"), status = HttpStatusCode.Unauthorized)
        return false
    }

    /** HTTPS mi: doğrudan TLS veya yerel TLS ucu / tünel (yalnızca loopback'ten gelen X-Forwarded-Proto'ya güvenilir). */
    private fun secure(call: ApplicationCall): Boolean {
        if (call.request.local.scheme == "https") return true
        val remote = call.request.local.remoteAddress
        val loopback = remote == "127.0.0.1" || remote == "::1" || remote == "0:0:0:0:0:0:0:1" || remote == "localhost"
        return loopback && call.request.headers[HttpHeaders.XForwardedProto]?.equals("https", ignoreCase = true) == true
    }

    private fun basicOk(value: String): Boolean {
        val decoded = runCatching { String(Base64.getDecoder().decode(value.trim()), Charsets.UTF_8) }.getOrNull() ?: return false
        val user = decoded.substringBefore(':')
        val pass = decoded.substringAfter(':', "")
        return eq(user, config.username) and eq(pass, config.password)
    }

    private fun digestOk(value: String, method: String): Boolean {
        val p = DIGEST_PARAM.findAll(value).associate { it.groupValues[1].lowercase() to (it.groupValues[2].ifEmpty { it.groupValues[3] }) }
        val nonce = p["nonce"] ?: return false
        if (!nonceValid(nonce) || p["realm"] != REALM || !eq(p["username"].orEmpty(), config.username)) return false
        val uri = p["uri"] ?: return false
        val ha1 = md5("${config.username}:$REALM:${config.password}")
        val ha2 = md5("$method:$uri")
        val expected = when (p["qop"]) {
            null -> md5("$ha1:$nonce:$ha2")
            "auth" -> md5("$ha1:$nonce:${p["nc"].orEmpty()}:${p["cnonce"].orEmpty()}:auth:$ha2")
            else -> return false
        }
        return eq(expected, p["response"].orEmpty().lowercase())
    }

    /** Durumsuz nonce: zaman damgası + HMAC; bir saat geçerli. */
    private fun nonce(): String {
        val ts = clock().epochSecond.toString()
        return Base64.getUrlEncoder().withoutPadding().encodeToString("$ts:${hmac(ts)}".toByteArray())
    }

    private fun nonceValid(nonce: String): Boolean {
        val text = runCatching { String(Base64.getUrlDecoder().decode(nonce)) }.getOrNull() ?: return false
        val ts = text.substringBefore(':')
        val age = clock().epochSecond - (ts.toLongOrNull() ?: return false)
        return age in 0..3600 && eq(text.substringAfter(':'), hmac(ts))
    }

    private fun hmac(s: String): String = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(nonceKey, "HmacSHA256")) }
        .doFinal(s.toByteArray()).joinToString("") { "%02x".format(it) }

    // ---- yol çözme ----

    /** Kök listesi (null kök) veya kök içindeki dosya. Güvenli olmayan yol null. */
    private data class Target(val root: DavRoot?, val file: File?, val segments: List<String>) {
        val isTop get() = root == null
        val isRootDir get() = root != null && segments.size == 1
    }

    private fun resolve(segments: List<String>): Target? {
        val clean = segments.filter { it.isNotEmpty() }
        if (clean.isEmpty()) return Target(null, null, emptyList())
        if (clean.any { it == "." || it == ".." || it.contains('/') || it.contains('\\') || it.contains('\u0000') }) return null
        val root = roots[clean.first()] ?: return Target(null, null, clean)
        if (root.hideDotFiles && clean.drop(1).any { it.startsWith(".") }) return null
        root.dir.mkdirs()
        val file = clean.drop(1).fold(root.dir) { f, s -> File(f, s) }
        val canonRoot = root.dir.canonicalFile
        val canon = file.canonicalFile
        if (canon != canonRoot && !canon.path.startsWith(canonRoot.path + File.separator)) return null
        // Kök içindeki sembolik bağlantılar izlenmez.
        var f: File? = file
        while (f != null && f != root.dir) {
            if (Files.isSymbolicLink(f.toPath())) return null
            f = f.parentFile
        }
        return Target(root, file, clean)
    }

    private fun href(segments: List<String>, collection: Boolean): String =
        base + "/" + segments.joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") } +
            if (collection && segments.isNotEmpty()) "/" else ""

    // ---- PROPFIND ----

    private suspend fun propfind(call: ApplicationCall, t: Target) {
        readSmallBody(call) // istek gövdesi (prop adları) okunup yok sayılır: her zaman tüm özellikler
        val depth = call.request.headers["Depth"]?.trim()?.lowercase() ?: "infinity"
        val one = depth != "0"
        val xml = withContext(Dispatchers.IO) {
            buildString {
                append("""<?xml version="1.0" encoding="utf-8"?><D:multistatus xmlns:D="DAV:">""")
                when {
                    t.isTop && t.segments.isEmpty() -> {
                        append(entry(href(emptyList(), true), tr("DroidServer"), null, true))
                        if (one) roots.values.forEach { r -> r.dir.mkdirs(); append(entry(href(listOf(r.name), true), r.name, r.dir, true)) }
                    }
                    t.file == null || !t.file.exists() -> return@withContext null
                    else -> {
                        val dir = t.file.isDirectory
                        append(entry(href(t.segments, dir), t.segments.last(), t.file, dir, quota = t.isRootDir))
                        if (dir && one) {
                            t.file.listFiles().orEmpty().sortedBy { it.name.lowercase() }
                                .filter { c -> !(t.root!!.hideDotFiles && c.name.startsWith(".")) && !Files.isSymbolicLink(c.toPath()) && !c.name.startsWith(TMP_PREFIX) }
                                .forEach { c -> append(entry(href(t.segments + c.name, c.isDirectory), c.name, c, c.isDirectory)) }
                        }
                    }
                }
                append("</D:multistatus>")
            }
        } ?: return call.respond(HttpStatusCode.NotFound)
        call.respondText(xml, ContentType.parse("application/xml; charset=utf-8"), HttpStatusCode.MultiStatus)
    }

    private fun entry(href: String, name: String, file: File?, collection: Boolean, quota: Boolean = false): String = buildString {
        append("<D:response><D:href>").append(esc(href)).append("</D:href><D:propstat><D:prop>")
        append("<D:displayname>").append(esc(name)).append("</D:displayname>")
        if (collection) append("<D:resourcetype><D:collection/></D:resourcetype>") else append("<D:resourcetype/>")
        if (file != null) {
            val modified = Instant.ofEpochMilli(file.lastModified())
            append("<D:getlastmodified>").append(HTTP_DATE.format(modified.atOffset(ZoneOffset.UTC))).append("</D:getlastmodified>")
            append("<D:creationdate>").append(modified.toString()).append("</D:creationdate>")
            if (!collection) {
                append("<D:getcontentlength>").append(file.length()).append("</D:getcontentlength>")
                append("<D:getcontenttype>").append(esc(mime(file.name))).append("</D:getcontenttype>")
                append("<D:getetag>\"").append(etag(file)).append("\"</D:getetag>")
            }
            if (quota) {
                append("<D:quota-available-bytes>").append(file.usableSpace).append("</D:quota-available-bytes>")
                append("<D:quota-used-bytes>").append(file.walkTopDown().filter { it.isFile }.sumOf { it.length() }).append("</D:quota-used-bytes>")
            }
        }
        append(SUPPORTED_LOCK)
        append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>")
    }

    // ---- GET / HEAD ----

    private suspend fun get(call: ApplicationCall, t: Target, head: Boolean) {
        val f = t.file
        if (t.isTop || f == null || f.isDirectory) {
            if (f != null && !f.exists()) return call.respond(HttpStatusCode.NotFound)
            // Tarayıcı için basit liste.
            val items = if (t.isTop) roots.keys.map { it to true }
            else f!!.listFiles().orEmpty().filter { c -> !(t.root!!.hideDotFiles && c.name.startsWith(".")) && !c.name.startsWith(TMP_PREFIX) }
                .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })).map { it.name to it.isDirectory }
            val html = buildString {
                append("<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'><title>DroidServer</title><ul>")
                if (t.segments.isNotEmpty()) append("<li><a href=\"${esc(href(t.segments.dropLast(1), true))}\">..</a>")
                items.forEach { (n, dir) -> append("<li><a href=\"${esc(href(t.segments + n, dir))}\">${esc(n)}${if (dir) "/" else ""}</a>") }
                append("</ul>")
            }
            call.response.header("Content-Security-Policy", "default-src 'none'")
            return if (head) call.respond(HttpStatusCode.OK) else call.respondText(html, ContentType.Text.Html)
        }
        if (!f.isFile) return call.respond(HttpStatusCode.NotFound)
        call.response.header(HttpHeaders.ETag, "\"${etag(f)}\"")
        // Yüklenen içerik bu adresten betik çalıştıramasın.
        call.response.header("Content-Security-Policy", "default-src 'none'; sandbox")
        val type = ContentType.parse(mime(f.name))
        if (head) {
            call.respond(object : OutgoingContent.NoContent() {
                override val contentLength = f.length()
                override val contentType = type
            })
        } else {
            call.respond(LocalFileContent(f, type))
        }
    }

    // ---- yazma ----

    private fun writable(t: Target): Boolean = t.root != null && !t.root.readOnly && !t.isRootDir

    private suspend fun put(call: ApplicationCall, t: Target) {
        val f = t.file
        if (!writable(t) || f == null) return call.respond(HttpStatusCode.Forbidden)
        if (f.isDirectory) return call.respond(HttpStatusCode.MethodNotAllowed)
        val parent = f.parentFile
        if (!parent.isDirectory) return call.respond(HttpStatusCode.Conflict)
        val declared = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (declared != null && declared > maxBytes) return call.respondText(tooLarge(), status = HttpStatusCode.PayloadTooLarge)
        val existed = f.exists()
        val tmp = File(parent, TMP_PREFIX + UUID.randomUUID())
        val channel = call.receiveChannel()
        val ok = withContext(Dispatchers.IO) {
            try {
                var total = 0L
                tmp.outputStream().buffered().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = channel.readAvailable(buf, 0, buf.size)
                        if (n < 0) break
                        if (n == 0) { if (channel.isClosedForRead) break else continue }
                        total += n
                        if (total > maxBytes) return@withContext false
                        out.write(buf, 0, n)
                    }
                }
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                true
            } finally {
                tmp.delete()
            }
        }
        if (!ok) return call.respondText(tooLarge(), status = HttpStatusCode.PayloadTooLarge)
        call.response.header(HttpHeaders.ETag, "\"${etag(f)}\"")
        call.respond(if (existed) HttpStatusCode.NoContent else HttpStatusCode.Created)
    }

    private fun tooLarge() = tr("Dosya en çok {0} MB olabilir", config.maxFileMb)

    private suspend fun delete(call: ApplicationCall, t: Target) {
        val f = t.file
        if (!writable(t) || f == null) return call.respond(HttpStatusCode.Forbidden)
        if (!f.exists()) return call.respond(HttpStatusCode.NotFound)
        val ok = withContext(Dispatchers.IO) { f.deleteRecursively() }
        call.respond(if (ok) HttpStatusCode.NoContent else HttpStatusCode.InternalServerError)
    }

    private suspend fun mkcol(call: ApplicationCall, t: Target) {
        val f = t.file
        if (!writable(t) || f == null) return call.respond(HttpStatusCode.Forbidden)
        if (f.exists()) return call.respond(HttpStatusCode.MethodNotAllowed)
        if (!f.parentFile.isDirectory) return call.respond(HttpStatusCode.Conflict)
        if (readSmallBody(call).isNotEmpty()) return call.respond(HttpStatusCode.UnsupportedMediaType)
        call.respond(if (f.mkdir()) HttpStatusCode.Created else HttpStatusCode.InternalServerError)
    }

    private suspend fun copyOrMove(call: ApplicationCall, t: Target, move: Boolean) {
        val src = t.file
        if (t.root == null || src == null || t.isRootDir) return call.respond(HttpStatusCode.Forbidden)
        if (move && t.root.readOnly) return call.respond(HttpStatusCode.Forbidden)
        if (!src.exists()) return call.respond(HttpStatusCode.NotFound)
        val destPath = call.request.headers["Destination"]?.let { runCatching { URI(it).rawPath }.getOrNull() }
            ?: return call.respond(HttpStatusCode.BadRequest)
        if (destPath != base && !destPath.startsWith("$base/")) return call.respond(HttpStatusCode.BadGateway)
        val segments = destPath.removePrefix(base).split('/').filter { it.isNotEmpty() }.map { java.net.URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }
        val dt = resolve(segments) ?: return call.respond(HttpStatusCode.Forbidden)
        val dest = dt.file
        if (!writable(dt) || dest == null) return call.respond(HttpStatusCode.Forbidden)
        if (dest.canonicalPath == src.canonicalPath) return call.respond(HttpStatusCode.Forbidden)
        if (src.isDirectory && dest.canonicalPath.startsWith(src.canonicalPath + File.separator)) return call.respond(HttpStatusCode.Forbidden)
        if (!dest.parentFile.isDirectory) return call.respond(HttpStatusCode.Conflict)
        val overwrite = call.request.headers["Overwrite"]?.trim()?.uppercase() != "F"
        val existed = dest.exists()
        if (existed && !overwrite) return call.respond(HttpStatusCode.PreconditionFailed)
        val ok = withContext(Dispatchers.IO) {
            if (existed) dest.deleteRecursively()
            if (move) src.renameTo(dest) || (src.copyRecursively(dest, overwrite = true) && src.deleteRecursively())
            else src.copyRecursively(dest, overwrite = true)
        }
        call.respond(if (!ok) HttpStatusCode.InternalServerError else if (existed) HttpStatusCode.NoContent else HttpStatusCode.Created)
    }

    /** Özellik yazma desteklenmez ama istemciler (Windows dosya zamanları) başarı bekler: istenenler 200 döner. */
    private suspend fun proppatch(call: ApplicationCall, t: Target) {
        if (t.file == null || !t.file.exists()) return call.respond(HttpStatusCode.NotFound)
        val body = readSmallBody(call)
        val namespaces = XMLNS.findAll(body).associate { it.groupValues[1] to it.groupValues[2] }
        val props = PROP_BLOCK.findAll(body).flatMap { block -> CHILD.findAll(block.groupValues[1]).map { it.groupValues[1] to it.groupValues[2] } }.distinct().toList()
        val xml = buildString {
            append("""<?xml version="1.0" encoding="utf-8"?><D:multistatus xmlns:D="DAV:"><D:response><D:href>""")
            append(esc(href(t.segments, t.file.isDirectory))).append("</D:href><D:propstat><D:prop>")
            props.forEachIndexed { i, (prefix, name) ->
                val ns = namespaces[prefix.removeSuffix(":")] ?: "DAV:"
                append("<p$i:${esc(name)} xmlns:p$i=\"${esc(ns)}\"/>")
            }
            append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response></D:multistatus>")
        }
        call.respondText(xml, ContentType.parse("application/xml; charset=utf-8"), HttpStatusCode.MultiStatus)
    }

    /** Sahte kilit: belirteç verilir, gerçekte kilitlenmez. Olmayan dosya kilitlenirse boş dosya oluşturulur. */
    private suspend fun lock(call: ApplicationCall, t: Target) {
        readSmallBody(call)
        var status = HttpStatusCode.OK
        val f = t.file
        if (f != null && !f.exists()) {
            if (!writable(t) || !f.parentFile.isDirectory) return call.respond(HttpStatusCode.Conflict)
            withContext(Dispatchers.IO) { f.createNewFile() }
            status = HttpStatusCode.Created
        }
        val token = "opaquelocktoken:" + UUID.randomUUID()
        call.response.header("Lock-Token", "<$token>")
        val xml = """<?xml version="1.0" encoding="utf-8"?><D:prop xmlns:D="DAV:"><D:lockdiscovery><D:activelock>""" +
            "<D:locktype><D:write/></D:locktype><D:lockscope><D:exclusive/></D:lockscope><D:depth>0</D:depth>" +
            "<D:timeout>Second-3600</D:timeout><D:locktoken><D:href>$token</D:href></D:locktoken>" +
            "<D:lockroot><D:href>${esc(href(t.segments, f?.isDirectory == true || t.isTop))}</D:href></D:lockroot>" +
            "</D:activelock></D:lockdiscovery></D:prop>"
        call.respondText(xml, ContentType.parse("application/xml; charset=utf-8"), status)
    }

    /** XML gövdeleri küçük olur; en çok 64 KB okunur. */
    private suspend fun readSmallBody(call: ApplicationCall): String {
        val ch = call.receiveChannel()
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < 65_536) {
            val n = ch.readAvailable(buf, 0, buf.size)
            if (n < 0) break
            if (n == 0) { if (ch.isClosedForRead) break else continue }
            out.write(buf, 0, minOf(n, 65_536 - out.size()))
        }
        return out.toString(Charsets.UTF_8.name())
    }

    companion object {
        const val REALM = "DroidServer"
        private const val TMP_PREFIX = ".dav-upload-"
        private const val ALLOW = "OPTIONS, GET, HEAD, PUT, DELETE, PROPFIND, PROPPATCH, MKCOL, COPY, MOVE, LOCK, UNLOCK"
        private val HTTP_DATE: DateTimeFormatter = DateTimeFormatter.RFC_1123_DATE_TIME
        private val DIGEST_PARAM = Regex("""(\w+)=(?:"([^"]*)"|([^\s,]*))""")
        private val XMLNS = Regex("""xmlns:(\w+)="([^"]*)"""")
        private val PROP_BLOCK = Regex("""<(?:\w+:)?prop\b[^>]*>(.*?)</(?:\w+:)?prop>""", RegexOption.DOT_MATCHES_ALL)
        private val CHILD = Regex("""<((?:\w+:)?)([A-Za-z_][\w.-]*)[\s/>]""")
        private const val SUPPORTED_LOCK = "<D:supportedlock><D:lockentry><D:lockscope><D:exclusive/></D:lockscope>" +
            "<D:locktype><D:write/></D:locktype></D:lockentry></D:supportedlock>"

        /** Kullanılan özel yöntemler (Ktor yönlendirmesi herhangi bir yöntemi kabul eder). */
        val METHODS = listOf("PROPFIND", "PROPPATCH", "MKCOL", "COPY", "MOVE", "LOCK", "UNLOCK").map(HttpMethod::parse)

        private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        private fun eq(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

        private fun etag(f: File) = "${f.length().toString(16)}-${f.lastModified().toString(16)}"

        private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        private fun mime(name: String) = dev.droidserver.core.files.MimeTypes.forExtension(name.substringAfterLast('.', "").lowercase())
    }
}
