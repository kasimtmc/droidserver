package com.plyg.droidserver.core.api

import com.plyg.droidserver.core.auth.Caller
import com.plyg.droidserver.core.config.AppAccess
import com.plyg.droidserver.core.config.AppConfig
import com.plyg.droidserver.core.i18n.tr
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.headers
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentLength
import io.ktor.http.contentType
import io.ktor.server.request.contentLength
import io.ktor.server.request.contentType
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.route
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.net.ConnectException

/**
 * Kullanıcı uygulamalarına ters vekil: [AppConfig.publicPath] altındaki istekler `http://127.0.0.1:<port>`'a akıtılır
 * (gövde ve yanıt bellekte biriktirilmez; SSE gibi akışlar sürer). Erişim kuralı sunucuda uygulanır; uygulamaya
 * istemci adresi `X-Forwarded-*`, kimlik (auth/admin) `X-DroidServer-User` / `-Role` / `-Admin` başlıklarıyla gider.
 * İstemcinin gönderdiği `X-DroidServer-*` başlıkları atılır (taklit edilemesin).
 */
internal object AppProxy {
    /** Vekilin iletmediği bağlantıya özgü başlıklar. */
    private val HOP = setOf(
        HttpHeaders.Connection, "Keep-Alive", HttpHeaders.ProxyAuthenticate, HttpHeaders.ProxyAuthorization, HttpHeaders.TE,
        HttpHeaders.Trailer, HttpHeaders.TransferEncoding, HttpHeaders.Upgrade, HttpHeaders.Host, HttpHeaders.ContentLength,
        HttpHeaders.ContentType, HttpHeaders.Expect,
    ).map { it.lowercase() }.toSet()

    /** WebSocket el sıkışmasının başlıkları (istemci kütüphanesi kendisi üretir). */
    private val WS_HANDSHAKE = setOf("sec-websocket-key", "sec-websocket-version", "sec-websocket-extensions", "sec-websocket-accept")

    private const val OWN_PREFIX = "x-droidserver-"

    /** Yanıttan atılanlar: sunucunun zaten eklediği güvenlik başlığı. */
    private val DROP_RESPONSE = setOf("x-content-type-options")

    fun client(engine: HttpClient): HttpClient = engine.config {
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            // Akışlar (SSE, uzun yoklama) için istek süresi sınırsız; sessiz kalan bağlantı 10 dk sonra kesilir.
            requestTimeoutMillis = io.ktor.client.plugins.HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            socketTimeoutMillis = 10 * 60_000L
        }
        install(io.ktor.client.plugins.websocket.WebSockets)
    }

    /** Uygulamaya iletilecek yol: önek atılır ([AppConfig.stripPath]); sorgu korunur (yalnızca kimlik `token`'ı atılır). */
    fun upstreamTarget(app: AppConfig, path: String, rawQuery: String): String {
        val p = if (app.stripPath) path.removePrefix(app.publicPath).ifEmpty { "/" } else path
        val query = rawQuery.split('&').filter { it.isNotEmpty() && (app.access == AppAccess.PUBLIC || !it.startsWith("token=")) }.joinToString("&")
        return p + if (query.isEmpty()) "" else "?$query"
    }

    /** Yönlendirme adresi uygulamanın iç adresini veya önek atılmış yolu gösteriyorsa dışarıdaki yola çevrilir. */
    fun rewriteLocation(app: AppConfig, location: String): String {
        val local = listOf("http://127.0.0.1:${app.port}", "http://localhost:${app.port}").firstOrNull { location.startsWith(it) }
        val rel = if (local != null) location.removePrefix(local).ifEmpty { "/" } else location
        return if (app.stripPath && rel.startsWith("/") && !rel.startsWith("//") && !StaticConfigPath.under(rel, app.publicPath)) app.publicPath + rel else rel
    }

    /** Erişim kuralı; izin yoksa ApiException. Kimliği uygulamaya iletmek için çağıranı döndürür. */
    fun authorize(app: AppConfig, caller: Caller) {
        when (app.access) {
            AppAccess.PUBLIC -> Unit
            AppAccess.AUTH -> {
                if (caller is Caller.Anonymous) throw ApiException(401, tr("Giriş gerekli"))
                if ((caller as? Caller.User)?.restricted == true) throw ApiException(403, tr("Önce iki adımlı doğrulamayı kurun"))
            }
            AppAccess.ADMIN -> {
                if (caller is Caller.Anonymous) throw ApiException(401, tr("Giriş gerekli"))
                if (!caller.isAdmin) throw ApiException(403, tr("Bu uygulama yalnızca yönetici içindir"))
            }
        }
    }

    private fun identityHeaders(app: AppConfig, caller: Caller?): Map<String, String> = buildMap {
        if (app.access == AppAccess.PUBLIC || caller == null) return@buildMap
        when (caller) {
            is Caller.User -> {
                put("X-DroidServer-User", caller.id.toString())
                put("X-DroidServer-Role", caller.role)
            }
            is Caller.Key -> put("X-DroidServer-Key", caller.name)
            else -> Unit
        }
        put("X-DroidServer-Admin", caller.isAdmin.toString())
    }

    private fun forwardedHeaders(app: AppConfig, call: ApplicationCall): Map<String, String> {
        val o = origin(call.request)
        val loopback = o.key == ClientGuard.LOCAL || call.request.local.remoteAddress.let { it == "127.0.0.1" || it == "::1" || it == "0:0:0:0:0:0:0:1" }
        val client = if (o.key == ClientGuard.LOCAL) call.request.local.remoteAddress else o.key
        return buildMap {
            put(HttpHeaders.XForwardedFor, client)
            put(HttpHeaders.XForwardedProto, call.request.header(HttpHeaders.XForwardedProto)?.takeIf { loopback } ?: call.request.local.scheme)
            call.request.header(HttpHeaders.Host)?.let { put(HttpHeaders.XForwardedHost, it) }
            put("X-Forwarded-Prefix", app.publicPath)
        }
    }

    private fun keepRequestHeader(app: AppConfig, name: String): Boolean {
        val n = name.lowercase()
        if (n in HOP || n.startsWith(OWN_PREFIX) || n.startsWith("x-forwarded-") || n == "forwarded") return false
        // Sunucunun kimlik bilgisi, kimliği sunucunun doğruladığı uygulamaya iletilmez.
        if (n == "authorization" && app.access != AppAccess.PUBLIC) return false
        return true
    }

    suspend fun forward(app: AppConfig, client: HttpClient, call: RoutingCall, caller: Caller?) {
        val target = "http://127.0.0.1:${app.port}" + upstreamTarget(app, call.request.path(), call.request.local.uri.substringAfter('?', ""))
        val method = call.request.httpMethod
        val maxBody = app.maxBodyMb * 1024L * 1024
        call.request.contentLength()?.let { if (it > maxBody) throw ApiException(413, tr("İstek gövdesi çok büyük (en çok {0} MB)", app.maxBodyMb)) }
        var tooLarge = false
        val hasBody = method !in setOf(HttpMethod.Get, HttpMethod.Head, HttpMethod.Options) || call.request.contentLength()?.let { it > 0 } == true ||
            call.request.header(HttpHeaders.TransferEncoding) != null
        try {
            client.prepareRequest(target) {
                this.method = method
                headers {
                    call.request.headers.forEach { name, values -> if (keepRequestHeader(app, name)) values.forEach { append(name, it) } }
                    forwardedHeaders(app, call).forEach { (k, v) -> append(k, v) }
                    identityHeaders(app, caller).forEach { (k, v) -> append(k, v) }
                }
                if (hasBody) {
                    val source = call.receiveChannel()
                    setBody(object : OutgoingContent.WriteChannelContent() {
                        override val contentType: ContentType? = call.request.contentType().takeIf { it != ContentType.Any }
                        override val contentLength: Long? = call.request.contentLength()
                        override suspend fun writeTo(channel: ByteWriteChannel) {
                            if (!copyLimited(source, channel, maxBody)) {
                                tooLarge = true
                                throw ApiException(413, tr("İstek gövdesi çok büyük (en çok {0} MB)", app.maxBodyMb))
                            }
                        }
                    })
                }
            }.execute { resp ->
                resp.headers.forEach { name, values ->
                    val n = name.lowercase()
                    if (n in HOP || n in DROP_RESPONSE) return@forEach
                    values.forEach { v ->
                        call.response.headers.append(name, if (n == "location") rewriteLocation(app, v) else v, safeOnly = false)
                    }
                }
                val status = resp.status
                val type = resp.contentType()
                val length = resp.contentLength()
                if (method == HttpMethod.Head || status == HttpStatusCode.NoContent || status == HttpStatusCode.NotModified || length == 0L) {
                    call.respond(object : OutgoingContent.NoContent() {
                        override val status = status
                        override val contentType = type
                        override val contentLength = if (method == HttpMethod.Head) length else null
                    })
                } else {
                    val body = resp.bodyAsChannel()
                    call.respond(object : OutgoingContent.ReadChannelContent() {
                        override val status = status
                        override val contentType = type
                        override val contentLength = length
                        override fun readFrom(): ByteReadChannel = body
                    })
                }
            }
        } catch (e: ApiException) {
            throw e
        } catch (e: ConnectException) {
            throw ApiException(502, tr("'{0}' uygulaması çalışmıyor veya henüz başlamadı (port {1})", app.name, app.port))
        } catch (e: Exception) {
            if (tooLarge) throw ApiException(413, tr("İstek gövdesi çok büyük (en çok {0} MB)", app.maxBodyMb))
            if (call.response.isCommitted) throw e
            throw ApiException(502, tr("'{0}' uygulamasına iletilemedi: {1}", app.name, e.message ?: e.javaClass.simpleName))
        }
    }

    /** [limit]'ten fazlası gelirse false (kopyalama durur). */
    private suspend fun copyLimited(from: ByteReadChannel, to: ByteWriteChannel, limit: Long): Boolean {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = from.readAvailable(buffer)
            if (n < 0) break
            if (n == 0) continue
            total += n
            if (total > limit) return false
            to.writeFully(buffer, 0, n)
        }
        to.flushAndClose()
        return true
    }

    /** WebSocket: iki yönlü çerçeve aktarımı; biri kapanınca diğeri de kapanır. */
    suspend fun forwardWebSocket(app: AppConfig, client: HttpClient, session: DefaultWebSocketServerSession, caller: Caller?) {
        val call = session.call
        val target = "ws://127.0.0.1:${app.port}" + upstreamTarget(app, call.request.path(), call.request.local.uri.substringAfter('?', ""))
        try {
            client.webSocket(target, request = {
                headers {
                    call.request.headers.forEach { name, values ->
                        if (keepRequestHeader(app, name) && name.lowercase() !in WS_HANDSHAKE) values.forEach { append(name, it) }
                    }
                    forwardedHeaders(app, call).forEach { (k, v) -> append(k, v) }
                    identityHeaders(app, caller).forEach { (k, v) -> append(k, v) }
                }
            }) {
                val upstream = this
                coroutineScope {
                    val toApp = launch {
                        for (f in session.incoming) if (f is Frame.Text || f is Frame.Binary) upstream.send(f.copy())
                        upstream.close()
                    }
                    for (f in upstream.incoming) if (f is Frame.Text || f is Frame.Binary) session.send(f.copy())
                    toApp.cancel()
                }
            }
            session.close(CloseReason(CloseReason.Codes.NORMAL, ""))
        } catch (e: Exception) {
            session.close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, tr("Uygulamaya bağlanılamadı").take(120)))
        }
    }
}

/** [path] [prefix]'in altında mı (önek dahil). */
internal object StaticConfigPath {
    fun under(path: String, prefix: String) = path == prefix || path.startsWith("$prefix/")
}

/**
 * Uygulama yolları: `/apps/<ad>` ve altı; WebSocket açıksa el sıkışmaları da. Erişim kuralı her istekte uygulanır.
 * [caller]: isteği yapan; ikinci değer true ise `?token=` de kabul edilir (tarayıcı WebSocket'i başlık gönderemez).
 */
internal fun Route.appRoutes(app: AppConfig, client: HttpClient, caller: suspend (ApplicationCall, Boolean) -> Caller) {
    suspend fun RoutingCall.resolved(): Caller? = if (app.access == AppAccess.PUBLIC) null else caller(this, false).also { AppProxy.authorize(app, it) }
    suspend fun DefaultWebSocketServerSession.ws() {
        val c = if (app.access == AppAccess.PUBLIC) null else caller(call, true)
        if (c != null) {
            try {
                AppProxy.authorize(app, c)
            } catch (e: ApiException) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, (e.message ?: "").take(120)))
                return
            }
        }
        AppProxy.forwardWebSocket(app, client, this, c)
    }
    route(app.publicPath) {
        if (app.websockets) {
            webSocket { ws() }
            webSocket("{...}") { ws() }
        }
        handle { AppProxy.forward(app, client, call, call.resolved()) }
        route("{...}") {
            handle { AppProxy.forward(app, client, call, call.resolved()) }
        }
    }
}
