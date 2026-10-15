package dev.droidserver.core.api

import dev.droidserver.core.auth.UserService
import dev.droidserver.core.i18n.tr
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * E-postadaki bağlantıların açtığı küçük sayfalar (istemci uygulama yazmadan da akış çalışsın):
 *   GET  /_auth/verify?token=   → hesabı doğrular
 *   GET  /_auth/reset?token=    → yeni parola formu (JavaScript yok)
 *   POST /_auth/reset           → parolayı değiştirir
 * Sayfalar başka kaynak yüklemez, çerçeveye konamaz, önbelleğe alınmaz.
 */
internal fun Route.authPages(serverName: String, users: UserService, minPassword: Int, writes: WriteGate) {
    route("/_auth") {
        get("/verify") {
            val token = call.request.queryParameters["token"]
            val result = runCatching { io { writes.write { users.confirmVerification(token) } } }
            call.page(
                if (result.isSuccess) HttpStatusCode.OK else HttpStatusCode.BadRequest,
                serverName,
                if (result.isSuccess) tr("E-posta adresiniz doğrulandı") else tr("Doğrulanamadı"),
                if (result.isSuccess) tr("<p>Teşekkürler. Uygulamaya dönebilirsiniz.</p>") else "<p>${esc(result.exceptionOrNull()?.message)}</p>",
            )
        }
        get("/reset") {
            val token = call.request.queryParameters["token"].orEmpty()
            call.page(HttpStatusCode.OK, serverName, tr("Yeni parola"), form(token, minPassword, null))
        }
        post("/reset") {
            val p = call.receiveParameters()
            val token = p["token"].orEmpty()
            val password = p["password"].orEmpty()
            if (password != p["repeat"]) {
                return@post call.page(HttpStatusCode.BadRequest, serverName, tr("Yeni parola"), form(token, minPassword, tr("Parolalar aynı değil")))
            }
            try {
                io { writes.write { users.confirmReset(token, password) } }
                call.page(HttpStatusCode.OK, serverName, tr("Parolanız değişti"), tr("<p>Yeni parolanızla giriş yapabilirsiniz. Diğer cihazlardaki oturumlar kapatıldı.</p>"))
            } catch (e: ApiException) {
                val message = e.details["password"]?.let { tr("Parola {0}", it) } ?: e.message
                val again = e.details.isNotEmpty()
                call.page(
                    HttpStatusCode.BadRequest, serverName, tr("Yeni parola"),
                    if (again) form(token, minPassword, message) else "<p>${esc(message)}</p>",
                )
            }
        }
    }
}

private fun form(token: String, minPassword: Int, error: String?) = buildString {
    error?.let { append("<p class=\"e\">${esc(it)}</p>") }
    append("<form method=\"post\" action=\"/_auth/reset\">")
    append("<input type=\"hidden\" name=\"token\" value=\"${esc(token)}\">")
    append("<label>${tr("Yeni parola")}<input type=\"password\" name=\"password\" minlength=\"$minPassword\" required autocomplete=\"new-password\"></label>")
    append("<label>${tr("Tekrar")}<input type=\"password\" name=\"repeat\" minlength=\"$minPassword\" required autocomplete=\"new-password\"></label>")
    append(tr("<button type=\"submit\">Parolayı değiştir</button></form>"))
}

internal suspend fun RoutingCall.page(status: HttpStatusCode, server: String, title: String, body: String) {
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    response.headers.append("Referrer-Policy", "no-referrer")
    response.headers.append(
        "Content-Security-Policy",
        "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'",
    )
    val html = """<!doctype html><html lang="tr"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        |<title>${esc(title)} · ${esc(server)}</title><style>
        |body{font:16px/1.5 system-ui,sans-serif;max-width:420px;margin:10vh auto;padding:0 16px;color:#1c1c1f;background:#f4faf8}
        |@media(prefers-color-scheme:dark){body{color:#ececef;background:#0f1514}input{background:#171d1c;color:#dde4e2}button{background:#82d5ce;color:#003734}}
        |label{display:block;margin:12px 0}input{display:block;width:100%;box-sizing:border-box;padding:8px;margin-top:4px;font:inherit;border:1px solid #888;border-radius:6px}
        |button{padding:9px 14px;font:inherit;border:0;border-radius:6px;background:#006a65;color:#fff;cursor:pointer}.e{color:#c62828}small{color:#888}
        |</style></head><body><h2>${esc(title)}</h2>$body<p><small>${esc(server)}</small></p></body></html>""".trimMargin()
    respondText(html, ContentType.Text.Html, status)
}

internal fun esc(s: String?): String = s.orEmpty()
    .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
