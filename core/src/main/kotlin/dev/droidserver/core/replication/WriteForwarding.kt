package dev.droidserver.core.replication

import dev.droidserver.core.api.ApiPaths
import dev.droidserver.core.api.CollectionEndpoint
import dev.droidserver.core.api.Endpoint
import dev.droidserver.core.api.EndpointGroup
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.i18n.tr
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/** İletilen isteği yapan kopyanın kimliği (ana sunucunun API anahtarı); ana sunucu bununla `X-Forwarded-For`'a güvenir. */
const val REPLICA_HEADER = "X-DroidServer-Replica"

internal class WriteForwardingSettings {
    lateinit var config: ServerConfig

    /** İstemcinin adresi (kopyanın gördüğü); yerel istek için null (ana sunucu kopyanın adresini görür). */
    var clientAddress: (ApplicationCall) -> String? = { null }
}

/**
 * Kopyada: veri yazmaları (kayıt, hesap, takım, push aboneliği, toplu işlem, GraphQL ve eşitleme uçları; uzman modundaki
 * yollarıyla) 503 yerine ana sunucuya iletilir. İstek istemcinin yetki başlığıyla aynen gider; kural ve doğrulamayı yalnızca
 * ana sunucu uygular, yanıtı istemciye döner. İstemcinin adresi `X-Forwarded-For` ile, kopyanın kimliği [REPLICA_HEADER]
 * ile gönderilir (ana sunucunun istek sınırı ve güvenlik duvarı istemciye uygulanır). Ana sunucuya ulaşılamazsa 502.
 */
internal val WriteForwarding = createApplicationPlugin("DroidServerWriteForwarding", ::WriteForwardingSettings) {
    val config = pluginConfig.config
    val clientAddress = pluginConfig.clientAddress
    val source = config.replication.replicaOf ?: return@createApplicationPlugin
    val primary = source.url.trimEnd('/')
    val base = config.server.basePath.trimEnd('/')
    // Yol parametresi ({id}) tek bir bölüme uyar.
    val routes = forwardedRoutes(config).map { (method, path) ->
        method to Regex("^" + (base + path).split('/').joinToString("/") { s -> if (s.startsWith("{") && s.endsWith("}")) "[^/]+" else Regex.escape(s) } + "$")
    }

    onCall { call ->
        val method = call.request.httpMethod.value
        val raw = call.request.local.uri
        val path = raw.substringBefore('?')
        if (routes.none { (m, r) -> m == method && r.matches(path) }) return@onCall
        forward(call, method, primary + path.removePrefix(base) + raw.substringAfter('?', "").let { if (it.isEmpty()) "" else "?$it" }, source.key, clientAddress(call))
    }
}

/** Ana sunucuya iletilen uçlar: yöntem ve (API yoluna göre) yol kalıbı. */
internal fun forwardedRoutes(config: ServerConfig): List<Pair<String, String>> {
    val api = ApiPaths(config)
    val groups = setOf(EndpointGroup.AUTH, EndpointGroup.OAUTH, EndpointGroup.PUSH, EndpointGroup.TEAMS)
    val endpoints = Endpoint.entries.filter { e ->
        (e.group in groups && e.method != "GET") || e in setOf(Endpoint.BATCH, Endpoint.GRAPHQL, Endpoint.SAMPLE, Endpoint.BROADCAST)
    }.mapNotNull { e -> api.path(e)?.let { e.method to it } }
    val collections = config.collections.flatMap { c ->
        CollectionEndpoint.entries.filter { it.method != "GET" || it == CollectionEndpoint.SYNC }.mapNotNull { e -> api.path(c, e)?.let { e.method to it } }
    }
    return endpoints + collections
}

private suspend fun forward(call: ApplicationCall, method: String, target: String, key: String, client: String?) {
    val headers = listOf(HttpHeaders.Authorization, HttpHeaders.ContentType, HttpHeaders.Accept, HttpHeaders.AcceptLanguage, HttpHeaders.UserAgent)
        .mapNotNull { h -> call.request.headers[h]?.let { h to it } }
    val hasBody = method != "GET" && method != "DELETE"
    val body = if (hasBody) call.receiveChannel().toInputStream() else null
    val result = withContext(Dispatchers.IO) {
        try {
            val c = URI(target).toURL().openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 60_000
            c.instanceFollowRedirects = false
            // JVM'nin HttpURLConnection'ı PATCH yapamaz; sunucu X-HTTP-Method-Override'ı tanır.
            if (method == "PATCH") {
                c.requestMethod = "POST"
                c.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            } else {
                c.requestMethod = method
            }
            headers.forEach { (h, v) -> c.setRequestProperty(h, v) }
            c.setRequestProperty(REPLICA_HEADER, key)
            client?.let { c.setRequestProperty(HttpHeaders.XForwardedFor, it) }
            if (body != null) {
                c.doOutput = true
                c.setChunkedStreamingMode(64 * 1024)
                c.outputStream.use { out -> body.copyTo(out) }
            }
            val status = c.responseCode
            Result.success(c to status)
        } catch (e: IOException) {
            Result.failure(e)
        }
    }
    val (conn, status) = result.getOrElse { e ->
        val text = buildJsonObject {
            put("status", JsonPrimitive(502))
            put("message", JsonPrimitive(tr("Bu sunucu bir kopya; yazma ana sunucuya iletilemedi ({0})", e.message)))
        }.toString()
        return call.respondText(text, ContentType.Application.Json, HttpStatusCode.BadGateway)
    }
    try {
        if (status == HttpStatusCode.NoContent.value) return call.respond(HttpStatusCode.NoContent)
        listOf(HttpHeaders.RetryAfter, HttpHeaders.Location).forEach { h -> conn.getHeaderField(h)?.let { call.response.headers.append(h, it) } }
        val type = conn.contentType?.let { runCatching { ContentType.parse(it) }.getOrNull() } ?: ContentType.Application.Json
        val stream = (if (status >= 400) conn.errorStream else conn.inputStream)
        call.respondOutputStream(type, HttpStatusCode.fromValue(status)) {
            withContext(Dispatchers.IO) { stream?.use { it.copyTo(this@respondOutputStream) } }
        }
    } finally {
        conn.disconnect()
    }
}
