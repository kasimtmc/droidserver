package com.plyg.droidserver.core.api

import com.plyg.droidserver.core.auth.Caller
import com.plyg.droidserver.core.auth.UserService
import com.plyg.droidserver.core.config.CollectionConfig
import com.plyg.droidserver.core.config.FieldConfig
import com.plyg.droidserver.core.config.FieldType
import com.plyg.droidserver.core.config.GeoPoint
import com.plyg.droidserver.core.config.PageConfig
import com.plyg.droidserver.core.config.PageLayout
import com.plyg.droidserver.core.config.Rules
import com.plyg.droidserver.core.config.SystemCollections
import com.plyg.droidserver.core.i18n.I18n
import com.plyg.droidserver.core.i18n.tr
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Kodsuz sayfaların dışarıdan aldıkları: kimlik çözme, dosya gönderme, istemci adresi. */
internal class PageHost(
    val resolve: suspend (String?) -> Caller,
    val sendFile: suspend RoutingCall.(CollectionConfig, Long, String, Scope) -> Unit,
    val client: (RoutingCall) -> String,
)

private const val ACCESS_COOKIE = "ds_page"
private const val REFRESH_COOKIE = "ds_page_r"
private val LOGIN = PageConfig.PATH + "/_login"
private val LOGOUT = PageConfig.PATH + "/_logout"

/**
 * `GET /pages/<ad>` liste (arama `q`, sıralama `sort`, sayfa `page`), `GET /pages/<ad>/<id>` detay,
 * `GET /pages/<ad>/<id>/files/<dosya>` kaydın dosyası. Giriş gereken sayfada oturum çerezdedir (yalnızca `/pages`
 * altında, HttpOnly, SameSite=Lax); erişim token'ı süresi dolunca yenileme token'ıyla sessizce yenilenir.
 */
internal fun Route.pagePages(
    serverName: String,
    pages: List<PageConfig>,
    collections: Map<String, CollectionConfig>,
    services: Map<String, RecordService>,
    users: UserService?,
    host: PageHost,
) {
    val zone = ZoneId.systemDefault()

    suspend fun RoutingCall.visitor(): Caller {
        val access = request.cookies[ACCESS_COOKIE]
        val caller = host.resolve(access)
        if (caller !is Caller.Anonymous || users == null) return caller
        val refresh = request.cookies[REFRESH_COOKIE] ?: return caller
        val tokens = runCatching { io { users.refresh(refresh) } }.getOrNull() ?: run { clearSession(); return caller }
        setSession(tokens)
        return host.resolve(tokens.token)
    }

    if (users != null) {
        get(LOGIN) { call.html(HttpStatusCode.OK, serverName, tr("Giriş"), null, loginForm(call.next(), null, null)) }
        post(LOGIN) {
            val p = call.receiveParameters()
            val next = safeNext(p["next"])
            val result = try {
                io {
                    val challenge = p["challenge"]
                    if (challenge != null) users.verifyTwoFactor(host.client(call), challenge, p["code"])
                    else users.login(host.client(call), p["email"], p["password"])
                }
            } catch (e: ApiException) {
                return@post call.html(HttpStatusCode.fromValue(e.status), serverName, tr("Giriş"), null, loginForm(next, e.message, p["challenge"]))
            }
            when (result) {
                is UserService.Tokens -> {
                    call.setSession(result)
                    call.respondRedirect(next, permanent = false)
                }
                is UserService.Challenge -> call.html(HttpStatusCode.OK, serverName, tr("Giriş"), null, loginForm(next, null, result.challenge))
            }
        }
        post(LOGOUT) {
            call.request.cookies[REFRESH_COOKIE]?.let { r -> runCatching { io { users.logout(r) } } }
            call.clearSession()
            call.respondRedirect(safeNext(call.receiveParameters()["next"]), permanent = false)
        }
    }

    for (page in pages.filter { it.enabled }) {
        val c = collections[page.collection] ?: continue
        val svc = services.getValue(c.name)
        val byName = c.fields.associateBy { it.name }
        val readable = c.fields.filter { it.visibility == null }
        val listFields = page.fields.ifEmpty { readable.filter { it.type != FieldType.JSON }.take(5).map { it.name } }
        val detailFields = page.detailFields.ifEmpty { readable.map { it.name } }
        val sortable = listFields.filter { n -> byName[n]?.type.let { it == null || it !in setOf(FieldType.FILE, FieldType.JSON, FieldType.GEO) } }

        /** Sayfanın kapsamı; giriş gerekiyorsa giriş sayfasına (null döner). */
        suspend fun RoutingCall.scope(op: Rules.Op): Pair<Caller, Scope>? {
            val caller = visitor()
            if (page.login && caller is Caller.Anonymous) { toLogin(); return null }
            return try {
                caller to Access.scope(c, op, caller)
            } catch (e: ApiException) {
                if (e.status == 401 && users != null) { toLogin(); return null }
                html(HttpStatusCode.fromValue(e.status), serverName, page.title, page.accent, "<p class=\"e\">${esc(tr("Bu sayfayı görme yetkiniz yok."))}</p>")
                null
            }
        }

        /** Sabit süzgeç; `@me` ziyaretçinin kimliği. */
        fun fixed(caller: Caller): List<Pair<String, String>> {
            val me = ((caller as? Caller.User)?.id ?: 0L).toString()
            return page.filter.orEmpty().split('&').map { it.trim() }.filter { it.isNotEmpty() }
                .map { it.substringBefore('=').trim() to it.substringAfter('=', "").trim().split(',').joinToString(",") { v -> if (v.trim() == "@me") me else v } }
        }

        get(page.path) {
            val (caller, scope) = call.scope(Rules.Op.LIST) ?: return@get
            val q = call.request.queryParameters["q"]?.trim()?.takeIf { it.isNotEmpty() && c.search.isNotEmpty() }
            val sort = call.request.queryParameters["sort"]?.takeIf { it.removePrefix("-") in sortable } ?: page.sort
            val number = call.request.queryParameters["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            val params = fixed(caller) + listOfNotNull(q?.let { "q" to it })
            val result = io { svc.list(number, page.perPage, sort, params, expand = expand(c, listFields, caller, collections), scope = scope) }
            val items = result.items.map { svc.present(it, scope) }
            val total = ((result.totalItems + page.perPage - 1) / page.perPage).coerceAtLeast(1)
            val body = buildString {
                page.description?.takeIf { it.isNotBlank() }?.let { append("<p class=\"d\">${esc(it).replace("\n", "<br>")}</p>") }
                append(toolbar(page, c, q, sort, sortable))
                if (items.isEmpty()) append("<p class=\"m\">${esc(tr("Kayıt yok."))}</p>")
                val link = { r: JsonObject -> if (page.detail) "${page.path}/${r["id"]}" else null }
                if (page.layout == PageLayout.TABLE) {
                    append("<div class=\"tw\"><table><thead><tr>")
                    listFields.forEach { append("<th>${esc(it)}</th>") }
                    append("</tr></thead><tbody>")
                    items.forEach { r ->
                        append("<tr>")
                        listFields.forEachIndexed { i, n ->
                            val v = value(page, byName[n], n, r, zone, thumb = true)
                            val href = link(r)
                            append("<td>").append(if (i == 0 && href != null) "<a href=\"${esc(href)}\">$v</a>" else v).append("</td>")
                        }
                        append("</tr>")
                    }
                    append("</tbody></table></div>")
                } else {
                    append("<div class=\"cards\">")
                    items.forEach { r ->
                        val href = link(r)
                        append(if (href != null) "<a class=\"card\" href=\"${esc(href)}\">" else "<div class=\"card\">")
                        listFields.forEachIndexed { i, n ->
                            val v = value(page, byName[n], n, r, zone, thumb = true)
                            if (v.isEmpty()) return@forEachIndexed
                            append(if (i == 0) "<h2>$v</h2>" else "<div class=\"f\"><span>${esc(n)}</span> $v</div>")
                        }
                        append(if (href != null) "</a>" else "</div>")
                    }
                    append("</div>")
                }
                if (total > 1) {
                    append("<nav>")
                    fun href(n: Int) = page.path + "?" + listOfNotNull(q?.let { "q=" + it.encodeURLParameter() }, sort?.let { "sort=" + it.encodeURLParameter() }, "page=$n").joinToString("&")
                    if (number > 1) append("<a href=\"${esc(href(number - 1))}\">‹ ${esc(tr("Önceki"))}</a>")
                    append("<span>${esc(tr("Sayfa {0} / {1}", number, total))}</span>")
                    if (number < total) append("<a href=\"${esc(href(number + 1))}\">${esc(tr("Sonraki"))} ›</a>")
                    append("</nav>")
                }
                append(account(caller, page.path))
            }
            call.html(HttpStatusCode.OK, serverName, page.title, page.accent, body, page.description, private = caller !is Caller.Anonymous)
        }

        if (page.detail) {
            get("${page.path}/{id}") {
                val id = call.parameters["id"]?.toLongOrNull() ?: return@get call.notFound(serverName, page)
                val (caller, scope) = call.scope(Rules.Op.VIEW) ?: return@get
                val record = try {
                    io { svc.present(svc.get(id, expand = expand(c, detailFields, caller, collections), scope = scope), scope) }
                } catch (e: ApiException) {
                    if (e.status == 404) return@get call.notFound(serverName, page) else throw e
                }
                val title = detailFields.firstOrNull()?.let { value(page, byName[it], it, record, zone, thumb = false, plain = true) }?.ifBlank { null } ?: "#$id"
                val body = buildString {
                    append("<p><a href=\"${esc(page.path)}\">‹ ${esc(page.title)}</a></p><dl>")
                    detailFields.forEach { n ->
                        val v = value(page, byName[n], n, record, zone, thumb = false)
                        if (v.isNotEmpty()) append("<dt>${esc(n)}</dt><dd>$v</dd>")
                    }
                    append("</dl>")
                    append(account(caller, "${page.path}/$id"))
                }
                call.html(HttpStatusCode.OK, serverName, title, page.accent, body, page.description, private = caller !is Caller.Anonymous)
            }
            get("${page.path}/{id}/files/{name}") {
                val id = call.parameters["id"]?.toLongOrNull() ?: return@get call.notFound(serverName, page)
                val (_, scope) = call.scope(Rules.Op.VIEW) ?: return@get
                with(host) { call.sendFile(c, id, call.parameters["name"].orEmpty(), scope) }
            }
        }
    }
}

/** Listede ve detayda gösterilen ilişkilerin hedefleri (görebiliyorsa); kullanıcılar genişletilmez. */
private fun expand(c: CollectionConfig, names: List<String>, caller: Caller, collections: Map<String, CollectionConfig>): Map<String, Scope?> =
    c.fields.filter { it.name in names && it.type == FieldType.RELATION && it.collection != SystemCollections.USERS && it.visibility == null }.mapNotNull { f ->
        val target = collections[f.collection] ?: return@mapNotNull null
        runCatching { f.name to Access.scope(target, Rules.Op.VIEW, caller) }.getOrNull()
    }.toMap()

private fun toolbar(page: PageConfig, c: CollectionConfig, q: String?, sort: String?, sortable: List<String>): String = buildString {
    if (c.search.isEmpty() && sortable.isEmpty()) return@buildString
    append("<form class=\"bar\" method=\"get\" action=\"${esc(page.path)}\">")
    if (c.search.isNotEmpty()) append("<input type=\"search\" name=\"q\" value=\"${esc(q)}\" placeholder=\"${esc(tr("Ara"))}\" aria-label=\"${esc(tr("Ara"))}\">")
    if (sortable.isNotEmpty()) {
        append("<select name=\"sort\" aria-label=\"${esc(tr("Sırala"))}\"><option value=\"\">${esc(tr("Sıralama"))}</option>")
        for (n in sortable) for ((v, arrow) in listOf(n to "↑", "-$n" to "↓")) {
            append("<option value=\"${esc(v)}\"${if (v == sort) " selected" else ""}>${esc(n)} $arrow</option>")
        }
        append("</select>")
    }
    append("<button type=\"submit\">${esc(tr("Uygula"))}</button></form>")
}

private fun account(caller: Caller, back: String): String = when (caller) {
    is Caller.User -> "<form class=\"out\" method=\"post\" action=\"${esc(LOGOUT)}\"><input type=\"hidden\" name=\"next\" value=\"${esc(back)}\"><button type=\"submit\" class=\"link\">${esc(tr("Çıkış yap"))}</button></form>"
    else -> ""
}

/** Alanın HTML değeri (kaçışlı); [plain]: başlık için düz metin. */
private fun value(page: PageConfig, f: FieldConfig?, name: String, r: JsonObject, zone: ZoneId, thumb: Boolean, plain: Boolean = false): String {
    val v = r[name] ?: return ""
    if (v is JsonNull) return ""
    fun text(s: String) = if (plain) s else esc(s)
    val type = f?.type ?: when (name) { "created_at", "updated_at" -> FieldType.DATETIME; else -> FieldType.INTEGER }
    return when (type) {
        FieldType.BOOLEAN -> text(if ((v as? JsonPrimitive)?.booleanOrNull == true) tr("Evet") else tr("Hayır"))
        FieldType.DATETIME -> (v as? JsonPrimitive)?.contentOrNull?.let { s ->
            runCatching { DATE.withLocale(Locale.forLanguageTag(I18n.lang.code)).format(Instant.parse(s).atZone(zone)) }.getOrDefault(s)
        }?.let(::text).orEmpty()
        FieldType.MULTISELECT -> (v as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .let { list -> if (plain) list.joinToString(", ") else list.joinToString("") { "<span class=\"tag\">${esc(it)}</span>" } }
        FieldType.GEO -> runCatching { GeoPoint.of(v) }.getOrNull()?.let { p ->
            val label = "%.5f, %.5f".format(Locale.ROOT, p.lat, p.lng)
            if (plain) label else "<a href=\"https://www.openstreetmap.org/?mlat=${p.lat}&amp;mlon=${p.lng}#map=16/${p.lat}/${p.lng}\" rel=\"noopener\">${esc(label)}</a>"
        }.orEmpty()
        FieldType.RELATION -> {
            val target = (r["expand"] as? JsonObject)?.get(name) as? JsonObject
            val label = target?.entries?.firstOrNull { (k, e) -> k != "id" && e is JsonPrimitive && e.isString }?.value?.let { (it as JsonPrimitive).content }
            text(label ?: "#" + (v as? JsonPrimitive)?.content)
        }
        FieldType.FILE -> {
            val names = when (v) {
                is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                is JsonPrimitive -> listOf(v.content)
                else -> emptyList()
            }
            if (plain) return names.joinToString(", ")
            names.joinToString(" ") { n ->
                val href = "${page.path}/${r["id"]}/files/${n.encodeURLParameter()}"
                if (n.substringAfterLast('.').lowercase() in IMAGES) {
                    "<img src=\"${esc(href + if (thumb) "?thumb=100x100" else "")}\" alt=\"${esc(n)}\" loading=\"lazy\">"
                } else "<a href=\"${esc(href)}\">${esc(n)}</a>"
            }
        }
        FieldType.JSON -> if (plain) v.toString() else "<pre>${esc(v.toString())}</pre>"
        FieldType.TEXT -> (v as? JsonPrimitive)?.contentOrNull?.let { s -> if (plain) s else esc(s).replace("\n", "<br>") }.orEmpty()
        else -> text((v as? JsonPrimitive)?.contentOrNull ?: v.toString())
    }
}

private val IMAGES = setOf("png", "jpg", "jpeg", "gif", "webp")
private val DATE: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

private fun loginForm(next: String, error: String?, challenge: String?) = buildString {
    error?.let { append("<p class=\"e\" role=\"alert\">${esc(it)}</p>") }
    append("<form class=\"login\" method=\"post\" action=\"${esc(LOGIN)}\"><input type=\"hidden\" name=\"next\" value=\"${esc(next)}\">")
    if (challenge != null) {
        append("<input type=\"hidden\" name=\"challenge\" value=\"${esc(challenge)}\">")
        append("<label for=\"code\">${esc(tr("Doğrulama kodu"))}</label><input id=\"code\" name=\"code\" inputmode=\"numeric\" autocomplete=\"one-time-code\" required autofocus>")
    } else {
        append("<label for=\"email\">${esc(tr("E-posta"))}</label><input id=\"email\" name=\"email\" type=\"email\" autocomplete=\"username\" required autofocus>")
        append("<label for=\"password\">${esc(tr("Parola"))}</label><input id=\"password\" name=\"password\" type=\"password\" autocomplete=\"current-password\" required>")
    }
    append("<button type=\"submit\">${esc(tr("Giriş yap"))}</button></form>")
}

/** Yönlendirme yalnızca sayfaların içine (açık yönlendirme yok). */
private fun safeNext(raw: String?): String = raw?.takeIf { it.startsWith(PageConfig.PATH + "/") && !it.startsWith("//") && it.none { c -> c.isWhitespace() || c == '\\' } } ?: "/"

private fun RoutingCall.next(): String = safeNext(request.queryParameters["next"])

private suspend fun RoutingCall.toLogin() {
    val here = request.local.uri
    respondRedirect("$LOGIN?next=${here.encodeURLParameter()}", permanent = false)
}

private suspend fun RoutingCall.notFound(server: String, page: PageConfig) =
    html(HttpStatusCode.NotFound, server, page.title, page.accent, "<p class=\"e\">${esc(tr("Kayıt bulunamadı."))}</p><p><a href=\"${esc(page.path)}\">‹ ${esc(page.title)}</a></p>")

private fun RoutingCall.secure(): Boolean {
    if (request.local.scheme == "https") return true
    val loopback = request.local.remoteAddress.let { it == "127.0.0.1" || it == "::1" || it == "0:0:0:0:0:0:0:1" || it == "localhost" }
    return loopback && request.headers[HttpHeaders.XForwardedProto]?.equals("https", ignoreCase = true) == true
}

private fun RoutingCall.setSession(t: UserService.Tokens) {
    val secure = secure()
    val lax = mapOf("SameSite" to "Lax")
    response.cookies.append(Cookie(ACCESS_COOKIE, t.token, maxAge = t.expiresIn.toInt(), path = PageConfig.PATH, httpOnly = true, secure = secure, extensions = lax))
    response.cookies.append(Cookie(REFRESH_COOKIE, t.refreshToken, maxAge = 30 * 86_400, path = PageConfig.PATH, httpOnly = true, secure = secure, extensions = lax))
}

private fun RoutingCall.clearSession() {
    listOf(ACCESS_COOKIE, REFRESH_COOKIE).forEach { response.cookies.append(Cookie(it, "", maxAge = 0, path = PageConfig.PATH, httpOnly = true, secure = secure())) }
}

/** Sayfa kabuğu: yalnızca kendi görselleri, satır içi stil; betik yok. Herkese açık sayfa yeniden doğrulamayla önbelleğe alınabilir. */
private suspend fun RoutingCall.html(
    status: HttpStatusCode,
    server: String,
    title: String,
    accent: String?,
    body: String,
    description: String? = null,
    private: Boolean = true,
) {
    response.headers.append(HttpHeaders.CacheControl, if (private) "private, no-store" else "no-cache")
    response.headers.append("Referrer-Policy", "same-origin")
    response.headers.append("X-Content-Type-Options", "nosniff")
    response.headers.append("Content-Security-Policy", "default-src 'none'; img-src 'self'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'")
    val color = accent?.takeIf { PageConfig.COLOR.matches(it) }?.let { ":root{--accent:$it}" }.orEmpty()
    val html = """<!doctype html><html lang="${I18n.lang.code}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        |<title>${esc(title)} · ${esc(server)}</title>${description?.takeIf { it.isNotBlank() }?.let { "<meta name=\"description\" content=\"${esc(it.take(300))}\">" }.orEmpty()}<style>
        |:root{--bg:#f4faf8;--fg:#161d1c;--muted:#5b6563;--card:#fff;--line:#d5e0dd;--accent:#006a65;--accent-fg:#fff;--err:#ba1a1a}
        |@media(prefers-color-scheme:dark){:root{--bg:#0f1514;--fg:#dde4e2;--muted:#9aa6a3;--card:#171d1c;--line:#2c3634;--accent:#82d5ce;--accent-fg:#003734;--err:#ffb4ab}}$color
        |*{box-sizing:border-box}body{margin:0;font:16px/1.5 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;color:var(--fg);background:var(--bg)}
        |main{max-width:960px;margin:4vh auto;padding:24px 20px}h1{font-size:28px;margin:0 0 6px}.d,.m{color:var(--muted)}a{color:var(--accent)}
        |.bar{display:flex;gap:8px;flex-wrap:wrap;margin:16px 0}.bar input,.bar select{flex:1;min-width:140px}
        |input,select{padding:10px 12px;font:inherit;color:var(--fg);background:var(--card);border:1px solid var(--line);border-radius:12px}
        |button{padding:10px 18px;font:inherit;font-weight:600;border:0;border-radius:999px;background:var(--accent);color:var(--accent-fg);cursor:pointer}
        |.cards{display:grid;grid-template-columns:repeat(auto-fill,minmax(260px,1fr));gap:14px}.card{display:block;padding:16px;background:var(--card);border:1px solid var(--line);border-radius:18px;color:inherit;text-decoration:none}
        |a.card:hover{border-color:var(--accent)}.card h2{font-size:18px;margin:0 0 8px}.card .f{font-size:14px;margin:4px 0}.card .f span{color:var(--muted)}
        |.tw{overflow-x:auto}table{width:100%;border-collapse:collapse;background:var(--card);border-radius:14px;overflow:hidden}th,td{padding:10px 12px;border-bottom:1px solid var(--line);text-align:left;vertical-align:top}th{color:var(--muted);font-weight:600;font-size:14px}
        |img{max-width:100%;border-radius:10px}.card img{max-height:160px}.tag{display:inline-block;padding:2px 10px;margin:2px;border-radius:999px;background:var(--line);font-size:13px}
        |dl{background:var(--card);border:1px solid var(--line);border-radius:18px;padding:16px 20px}dt{color:var(--muted);font-size:14px;margin-top:12px}dd{margin:2px 0 0}pre{white-space:pre-wrap;font-size:13px}
        |nav{display:flex;gap:16px;align-items:center;justify-content:center;margin:20px 0}.e{color:var(--err)}
        |.login{max-width:380px;display:flex;flex-direction:column;gap:8px}.login label{font-weight:600;font-size:14px;margin-top:8px}.out{margin-top:24px;text-align:right}.link{background:none;color:var(--accent);padding:0;font-weight:400}
        |footer{text-align:center;color:var(--muted);font-size:12px;margin:14px 0}
        |</style></head><body><main><h1>${esc(title)}</h1>$body</main><footer>${esc(server)}</footer></body></html>""".trimMargin()
    respondText(html, ContentType.Text.Html, status)
}
