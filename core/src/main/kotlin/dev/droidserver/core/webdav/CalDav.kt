package dev.droidserver.core.webdav

import dev.droidserver.core.api.ApiException
import dev.droidserver.core.api.RecordService
import dev.droidserver.core.api.Scope
import dev.droidserver.core.calendar.CalendarMapping
import dev.droidserver.core.calendar.CalendarStore
import dev.droidserver.core.config.CollectionConfig
import dev.droidserver.core.i18n.tr
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import org.w3c.dom.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.ZoneId
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Takvim (CalDAV, RFC 4791) ve kişiler (CardDAV, RFC 6352), ağ sürücüsünün adresi ve kimliğiyle: `…/dav/principal/`,
 * `…/dav/calendars/<koleksiyon>/`, `…/dav/contacts/<koleksiyon>/`. Telefonun takvimi (DAVx⁵, iOS), Thunderbird ve
 * macOS Takvim/Kişiler iki yönlü eşitler. Yazmalar yönetici olarak normal yoldan geçer (doğrulama, çakışma kuralı,
 * script, olaylar). Kayıtların yalnızca eşlenen alanları saklanır; alarm, katılımcı gibi özellikler atılır (sunucu içeriği
 * değiştirdiği için PUT yanıtında ETag yoktur, istemci yeniden okur).
 */
class CalDav(
    private val base: String,
    collections: List<CollectionConfig>,
    private val services: Map<String, RecordService>,
    private val store: CalendarStore,
    private val writer: Writer,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    /** Yazma yolu (ServerModule'ün kayıt işlemleri, yönetici olarak). */
    interface Writer {
        fun create(c: CollectionConfig, body: JsonObject): JsonObject
        fun update(c: CollectionConfig, id: Long, body: JsonObject): JsonObject
        fun delete(c: CollectionConfig, id: Long)
    }

    private val calendars = collections.filter { it.calendar?.dav == true }.associateBy { it.name }
    private val books = collections.filter { it.contacts != null }.associateBy { it.name }

    val enabled: Boolean get() = calendars.isNotEmpty() || books.isNotEmpty()

    fun handles(segments: List<String>): Boolean = enabled && segments.firstOrNull { it.isNotEmpty() } in setOf(PRINCIPAL, CALENDARS, CONTACTS)

    /** Sürücü kökünün (`/dav/`) PROPFIND yanıtına: istemciler kullanıcıyı buradan bulur. */
    fun rootProps(): String = if (!enabled) "" else "<D:current-user-principal><D:href>${esc(href(PRINCIPAL))}</D:href></D:current-user-principal>"

    // ---- düğümler ----

    private sealed interface Node
    private data object Principal : Node
    private data class Home(val cal: Boolean) : Node
    private data class Coll(val c: CollectionConfig, val cal: Boolean) : Node
    private data class Res(val coll: Coll, val name: String) : Node

    private data class Item(val id: Long, val name: String, val uid: String, val body: String, val etag: String)

    private fun resolve(segments: List<String>): Node? {
        val s = segments.filter { it.isNotEmpty() }
        val cal = s.first() == CALENDARS
        return when {
            s.first() == PRINCIPAL -> Principal.takeIf { s.size == 1 }
            s.size == 1 -> Home(cal)
            else -> {
                val c = (if (cal) calendars else books)[s[1]] ?: return null
                when (s.size) {
                    2 -> Coll(c, cal)
                    3 -> Res(Coll(c, cal), s[2])
                    else -> null
                }
            }
        }
    }

    private fun href(vararg segments: String, collection: Boolean = true): String =
        base + "/" + segments.joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") } + if (collection) "/" else ""

    private fun hrefOf(n: Node): String = when (n) {
        Principal -> href(PRINCIPAL)
        is Home -> href(if (n.cal) CALENDARS else CONTACTS)
        is Coll -> href(if (n.cal) CALENDARS else CONTACTS, n.c.name)
        is Res -> href(if (n.coll.cal) CALENDARS else CONTACTS, n.coll.c.name, n.name, collection = false)
    }

    /** Koleksiyonun etkinlikleri / kartları; içerik her istekte kayıttan üretilir, ETag içeriğin özetidir. */
    private fun items(coll: Coll): List<Item> {
        val c = coll.c
        val svc = services.getValue(c.name)
        val entries = store.entries(c.name)
        val ext = if (coll.cal) ".ics" else ".vcf"
        return CalendarStore.records(svc, Scope.ADMIN).mapNotNull { r ->
            val id = CalendarStore.id(r)
            val e = entries[id]
            val uid = e?.uid ?: CalendarStore.uid(c.name, id)
            val body = if (coll.cal) {
                CalendarMapping.event(c, c.calendar!!, r, uid, zone)?.let { CalendarMapping.calendar(c.name, listOf(it)) } ?: return@mapNotNull null
            } else {
                CalendarMapping.card(c.contacts!!, r, uid)
            }
            Item(id, e?.href ?: "$id$ext", uid, body, "\"" + sha1(body).take(24) + "\"")
        }
    }

    private fun ctag(items: List<Item>) = sha1(items.joinToString("\n") { it.name + it.etag }).take(24)

    // ---- istekler ----

    suspend fun serve(call: ApplicationCall, method: String, segments: List<String>) {
        val node = resolve(segments) ?: return call.respond(HttpStatusCode.NotFound)
        try {
            when (method) {
                "OPTIONS" -> {
                    call.response.header("DAV", DAV_HEADER)
                    call.response.header(HttpHeaders.Allow, ALLOW)
                    call.respond(HttpStatusCode.OK)
                }
                "PROPFIND" -> propfind(call, node)
                "REPORT" -> report(call, node)
                "GET", "HEAD" -> get(call, node, method == "HEAD")
                "PUT" -> put(call, node)
                "DELETE" -> delete(call, node)
                "PROPPATCH" -> proppatch(call, node)
                else -> {
                    call.response.header(HttpHeaders.Allow, ALLOW)
                    call.respond(HttpStatusCode.MethodNotAllowed)
                }
            }
        } catch (e: ApiException) {
            val text = (listOfNotNull(e.message) + e.details.map { (k, v) -> "$k: $v" }).joinToString("\n")
            call.respondText(text, status = HttpStatusCode.fromValue(e.status))
        } catch (e: IllegalArgumentException) {
            call.respondText(e.message ?: tr("Geçersiz istek"), status = HttpStatusCode.BadRequest)
        }
    }

    private suspend fun propfind(call: ApplicationCall, node: Node) {
        val requested = requestedProps(xml(readBody(call, 65_536)))
        val one = (call.request.headers["Depth"]?.trim()?.lowercase() ?: "1") != "0"
        val xml = withContext(Dispatchers.IO) {
            multistatus {
                when (node) {
                    Principal -> append(response(hrefOf(node), props(node, null), requested))
                    is Home -> {
                        append(response(hrefOf(node), props(node, null), requested))
                        if (one) (if (node.cal) calendars else books).values.forEach { c ->
                            val coll = Coll(c, node.cal)
                            append(response(hrefOf(coll), props(coll, items(coll)), requested))
                        }
                    }
                    is Coll -> {
                        val items = items(node)
                        append(response(hrefOf(node), props(node, items), requested))
                        if (one) items.forEach { append(response(hrefOf(Res(node, it.name)), itemProps(node, it), requested)) }
                    }
                    is Res -> {
                        val item = items(node.coll).firstOrNull { it.name == node.name } ?: return@withContext null
                        append(response(hrefOf(node), itemProps(node.coll, item), requested))
                    }
                }
            }
        } ?: return call.respond(HttpStatusCode.NotFound)
        call.respondText(xml, XML, HttpStatusCode.MultiStatus)
    }

    private suspend fun report(call: ApplicationCall, node: Node) {
        val root = xml(readBody(call, 1_048_576)) ?: throw IllegalArgumentException(tr("REPORT gövdesi boş"))
        val coll = when (node) {
            is Coll -> node
            is Res -> node.coll
            else -> return call.respond(HttpStatusCode.Forbidden)
        }
        val requested = requestedProps(root)
        val xml = withContext(Dispatchers.IO) {
            val items = items(coll)
            multistatus {
                when (root.localName) {
                    "calendar-multiget", "addressbook-multiget" -> {
                        for (h in children(root, DAV, "href")) {
                            val raw = h.textContent.trim()
                            val path = runCatching { URI(raw).rawPath ?: raw }.getOrDefault(raw)
                            val name = URLDecoder.decode(path.trimEnd('/').substringAfterLast('/'), "UTF-8")
                            val item = items.firstOrNull { it.name == name }
                            if (item == null) append("<D:response><D:href>${esc(raw)}</D:href><D:status>HTTP/1.1 404 Not Found</D:status></D:response>")
                            else append(response(hrefOf(Res(coll, item.name)), itemProps(coll, item), requested))
                        }
                    }
                    // Süzgeçler uygulanmaz: tümü döner (istemciler fazlasını kendisi ayıklar).
                    "calendar-query", "addressbook-query" -> items.forEach { append(response(hrefOf(Res(coll, it.name)), itemProps(coll, it), requested)) }
                    else -> return@withContext null
                }
            }
        }
        if (xml == null) {
            call.respondText(
                """<?xml version="1.0" encoding="utf-8"?><D:error xmlns:D="DAV:"><D:supported-report/></D:error>""",
                XML, HttpStatusCode.Forbidden,
            )
            return
        }
        call.respondText(xml, XML, HttpStatusCode.MultiStatus)
    }

    private suspend fun get(call: ApplicationCall, node: Node, head: Boolean) {
        val res = node as? Res ?: return call.respond(HttpStatusCode.MethodNotAllowed)
        val item = withContext(Dispatchers.IO) { items(res.coll).firstOrNull { it.name == res.name } } ?: return call.respond(HttpStatusCode.NotFound)
        call.response.header(HttpHeaders.ETag, item.etag)
        val type = if (res.coll.cal) CALENDAR_TYPE else VCARD_TYPE
        if (head) {
            call.response.header(HttpHeaders.ContentType, type.toString())
            call.respond(HttpStatusCode.OK)
        } else {
            call.respondText(item.body, type)
        }
    }

    private suspend fun put(call: ApplicationCall, node: Node) {
        val res = node as? Res ?: return call.respond(HttpStatusCode.MethodNotAllowed)
        val text = readBody(call, 1_048_576)
        val c = res.coll.c
        val status = withContext(Dispatchers.IO) {
            val existing = items(res.coll).firstOrNull { it.name == res.name }
            val ifMatch = call.request.headers[HttpHeaders.IfMatch]?.trim()
            if (ifMatch != null && ifMatch != "*" && ifMatch != existing?.etag) return@withContext HttpStatusCode.PreconditionFailed
            if (ifMatch == "*" && existing == null) return@withContext HttpStatusCode.PreconditionFailed
            if (call.request.headers[HttpHeaders.IfNoneMatch]?.trim() == "*" && existing != null) return@withContext HttpStatusCode.PreconditionFailed
            val (body, uid) = if (res.coll.cal) CalendarMapping.fromEvent(c, c.calendar!!, text, zone) else CalendarMapping.fromCard(c.contacts!!, text)
            if (existing != null) {
                writer.update(c, existing.id, body)
                store.put(c.name, existing.id, res.name, existing.uid)
                HttpStatusCode.NoContent
            } else {
                val id = CalendarStore.id(writer.create(c, body))
                store.put(c.name, id, res.name, uid ?: CalendarStore.uid(c.name, id))
                HttpStatusCode.Created
            }
        }
        call.respond(status)
    }

    private suspend fun delete(call: ApplicationCall, node: Node) {
        val res = node as? Res ?: return call.respond(HttpStatusCode.Forbidden)
        val status = withContext(Dispatchers.IO) {
            val item = items(res.coll).firstOrNull { it.name == res.name } ?: return@withContext HttpStatusCode.NotFound
            val ifMatch = call.request.headers[HttpHeaders.IfMatch]?.trim()
            if (ifMatch != null && ifMatch != "*" && ifMatch != item.etag) return@withContext HttpStatusCode.PreconditionFailed
            writer.delete(res.coll.c, item.id)
            store.remove(res.coll.c.name, item.id)
            HttpStatusCode.NoContent
        }
        call.respond(status)
    }

    /** Renk, ad gibi değişiklikler saklanmaz; istemci hata göstermesin diye kabul edilmiş gibi yanıtlanır. */
    private suspend fun proppatch(call: ApplicationCall, node: Node) {
        val root = xml(readBody(call, 65_536))
        val names = root?.let { r -> elements(r).filter { it.localName == "prop" && it.namespaceURI == DAV }.flatMap { elements(it) } }.orEmpty()
        val xml = multistatus {
            append("<D:response><D:href>${esc(hrefOf(node))}</D:href><D:propstat><D:prop>")
            names.forEachIndexed { i, e -> append("<x$i:${e.localName} xmlns:x$i=\"${esc(e.namespaceURI.orEmpty())}\"/>") }
            append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>")
        }
        call.respondText(xml, XML, HttpStatusCode.MultiStatus)
    }

    // ---- özellikler ----

    private data class Q(val ns: String, val name: String)

    private val privileges = listOf("all", "read", "write", "write-content", "write-properties", "bind", "unbind")
        .joinToString("") { "<D:privilege><D:$it/></D:privilege>" }

    private fun props(node: Node, items: List<Item>?): Map<Q, String> = buildMap {
        val principal = "<D:href>${esc(href(PRINCIPAL))}</D:href>"
        put(Q(DAV, "current-user-principal"), principal)
        when (node) {
            Principal -> {
                put(Q(DAV, "resourcetype"), "<D:principal/>")
                put(Q(DAV, "displayname"), "DroidServer")
                put(Q(DAV, "principal-URL"), principal)
                if (calendars.isNotEmpty()) put(Q(CALDAV, "calendar-home-set"), "<D:href>${esc(href(CALENDARS))}</D:href>")
                if (books.isNotEmpty()) put(Q(CARDDAV, "addressbook-home-set"), "<D:href>${esc(href(CONTACTS))}</D:href>")
            }
            is Home -> {
                put(Q(DAV, "resourcetype"), "<D:collection/>")
                put(Q(DAV, "displayname"), if (node.cal) tr("Takvimler") else tr("Kişiler"))
            }
            is Coll -> {
                put(Q(DAV, "resourcetype"), if (node.cal) "<D:collection/><C:calendar/>" else "<D:collection/><CR:addressbook/>")
                put(Q(DAV, "displayname"), esc(node.c.name))
                put(Q(DAV, "owner"), principal)
                put(Q(DAV, "current-user-privilege-set"), privileges)
                val tag = ctag(items.orEmpty())
                put(Q(CS, "getctag"), tag)
                if (node.cal) {
                    put(Q(CALDAV, "supported-calendar-component-set"), "<C:comp name=\"VEVENT\"/>")
                    put(Q(DAV, "supported-report-set"), report("C:calendar-multiget") + report("C:calendar-query"))
                } else {
                    put(Q(CARDDAV, "supported-address-data"), "<CR:address-data-type content-type=\"text/vcard\" version=\"3.0\"/>")
                    put(Q(DAV, "supported-report-set"), report("CR:addressbook-multiget") + report("CR:addressbook-query"))
                }
            }
            is Res -> {}
        }
    }

    private fun report(name: String) = "<D:supported-report><D:report><$name/></D:report></D:supported-report>"

    private fun itemProps(coll: Coll, item: Item): Map<Q, String> = mapOf(
        Q(DAV, "resourcetype") to "",
        Q(DAV, "getetag") to esc(item.etag),
        Q(DAV, "getcontenttype") to esc((if (coll.cal) CALENDAR_TYPE else VCARD_TYPE).toString()),
        Q(DAV, "getcontentlength") to item.body.toByteArray().size.toString(),
        (if (coll.cal) Q(CALDAV, "calendar-data") else Q(CARDDAV, "address-data")) to esc(item.body),
    )

    /**
     * Tek kaynağın yanıtı: istenen özelliklerden bulunanlar 200, bulunmayanlar 404. [requested] null (allprop) ise içerik
     * (calendar-data) dışında hepsi.
     */
    private fun response(href: String, available: Map<Q, String>, requested: List<Q>?): String = buildString {
        val data = setOf(Q(CALDAV, "calendar-data"), Q(CARDDAV, "address-data"))
        val wanted = requested ?: available.keys.filter { it !in data }
        val (found, missing) = wanted.partition { it in available }
        append("<D:response><D:href>").append(esc(href)).append("</D:href>")
        if (found.isNotEmpty() || missing.isEmpty()) {
            append("<D:propstat><D:prop>")
            for (q in found) {
                val tag = tag(q)
                val value = available.getValue(q)
                if (value.isEmpty()) append("<$tag/>") else append("<$tag>").append(value).append("</$tag>")
            }
            append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>")
        }
        if (missing.isNotEmpty()) {
            append("<D:propstat><D:prop>")
            missing.forEachIndexed { i, q -> append(PREFIX[q.ns]?.let { "<$it:${q.name}/>" } ?: "<x$i:${q.name} xmlns:x$i=\"${esc(q.ns)}\"/>") }
            append("</D:prop><D:status>HTTP/1.1 404 Not Found</D:status></D:propstat>")
        }
        append("</D:response>")
    }

    private fun tag(q: Q) = (PREFIX[q.ns] ?: "D") + ":" + q.name

    private inline fun multistatus(body: StringBuilder.() -> Unit): String = buildString {
        append("""<?xml version="1.0" encoding="utf-8"?><D:multistatus""")
        PREFIX.forEach { (ns, p) -> append(" xmlns:$p=\"$ns\"") }
        append(">")
        body()
        append("</D:multistatus>")
    }

    // ---- XML ----

    /** İstenen özellikler; `allprop`, `propname` veya boş gövde: null. */
    private fun requestedProps(root: Element?): List<Q>? {
        root ?: return null
        val prop = elements(root).firstOrNull { it.localName == "prop" && it.namespaceURI == DAV } ?: return null
        return elements(prop).map { Q(it.namespaceURI.orEmpty(), it.localName) }
    }

    private fun elements(e: Element): List<Element> {
        val nodes = e.childNodes
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }

    private fun children(e: Element, ns: String, name: String) = elements(e).filter { it.namespaceURI == ns && it.localName == name }

    private fun xml(text: String): Element? {
        if (text.isBlank()) return null
        val f = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            // Dış varlık ve DTD yok (XXE); desteklemeyen ayrıştırıcıda yok sayılır.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        }
        if (text.contains("<!DOCTYPE", ignoreCase = true)) throw IllegalArgumentException(tr("DTD kabul edilmez"))
        return runCatching { f.newDocumentBuilder().parse(text.byteInputStream()).documentElement }
            .getOrElse { throw IllegalArgumentException(tr("Geçersiz XML")) }
    }

    private suspend fun readBody(call: ApplicationCall, max: Int): String {
        val ch = call.receiveChannel()
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = ch.readAvailable(buf, 0, buf.size)
            if (n < 0) break
            if (n == 0) { if (ch.isClosedForRead) break else continue }
            if (out.size() + n > max) throw ApiException(413, tr("İstek gövdesi çok büyük"))
            out.write(buf, 0, n)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    companion object {
        const val PRINCIPAL = "principal"
        const val CALENDARS = "calendars"
        const val CONTACTS = "contacts"
        const val DAV_HEADER = "1, 2, 3, calendar-access, addressbook"
        private const val ALLOW = "OPTIONS, GET, HEAD, PUT, DELETE, PROPFIND, PROPPATCH, REPORT"
        private const val DAV = "DAV:"
        private const val CALDAV = "urn:ietf:params:xml:ns:caldav"
        private const val CARDDAV = "urn:ietf:params:xml:ns:carddav"
        private const val CS = "http://calendarserver.org/ns/"
        private val PREFIX = linkedMapOf(DAV to "D", CALDAV to "C", CARDDAV to "CR", CS to "CS")
        private val XML = ContentType.parse("application/xml; charset=utf-8")
        private val CALENDAR_TYPE = ContentType.parse("text/calendar; charset=utf-8")
        private val VCARD_TYPE = ContentType.parse("text/vcard; charset=utf-8")

        private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    }
}
