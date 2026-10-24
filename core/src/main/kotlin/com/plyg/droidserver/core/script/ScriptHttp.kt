package com.plyg.droidserver.core.script

import com.plyg.droidserver.core.i18n.tr
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.net.HttpURLConnection
import java.net.URI

/**
 * `ctx.http`: yalnızca izin listesindeki adreslere (`api.example.com`, `*.example.com`), yönlendirmeler izlenmeden
 * (izinli adres başka yere yönlendiremesin), 10 sn zaman aşımı ve 1 MB yanıt sınırıyla. Yanıt:
 * `{ status, body (metin), json (JSON ise ayrıştırılmış, değilse null) }`.
 */
class ScriptHttp(private val allowedHosts: List<String>) {
    fun allowed(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        return allowedHosts.any { pattern ->
            val p = pattern.lowercase()
            if (p.startsWith("*.")) h.endsWith(p.substring(1)) && h.length > p.length - 1 else h == p
        }
    }

    fun request(method: String, url: String, body: JsonElement?, options: JsonObject): JsonObject {
        val uri = runCatching { URI(url) }.getOrNull() ?: throw ScriptFail(500, tr("ctx.http: geçersiz adres: {0}", url))
        if (uri.scheme !in setOf("http", "https") || uri.host == null) throw ScriptFail(500, tr("ctx.http: yalnızca http/https adresleri: {0}", url))
        if (!allowed(uri.host)) {
            throw ScriptFail(500, tr("ctx.http: '{0}' izin listesinde değil (Güvenlik adımında script'ler için izinli adreslere ekleyin)", uri.host))
        }
        if (method !in setOf("GET", "POST", "PUT", "PATCH", "DELETE")) throw ScriptFail(500, tr("ctx.http: yöntem desteklenmiyor: {0}", method))
        val conn = uri.toURL().openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            // HttpURLConnection PATCH'i bilmez; yöntem başlığıyla gönderilir.
            if (method == "PATCH") {
                conn.requestMethod = "POST"
                conn.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            } else {
                conn.requestMethod = method
            }
            (options["headers"] as? JsonObject)?.forEach { (k, v) -> conn.setRequestProperty(k, (v as? JsonPrimitive)?.content ?: v.toString()) }
            if (body != null && body !is JsonNull && method != "GET") {
                val bytes = ((body as? JsonPrimitive)?.takeIf { it.isString }?.content ?: body.toString()).toByteArray()
                if (conn.getRequestProperty("Content-Type") == null) {
                    conn.setRequestProperty("Content-Type", if (body is JsonPrimitive && body.isString) "text/plain; charset=utf-8" else "application/json")
                }
                conn.doOutput = true
                conn.outputStream.use { it.write(bytes) }
            }
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            val bytes = stream?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (out.size() + n > MAX_BYTES) throw ScriptFail(500, tr("ctx.http: yanıt 1 MB'tan büyük"))
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            } ?: ByteArray(0)
            val text = bytes.decodeToString()
            return buildJsonObject {
                put("status", JsonPrimitive(status))
                put("body", JsonPrimitive(text))
                put("json", runCatching { Json.parseToJsonElement(text) }.getOrDefault(JsonNull))
            }
        } catch (e: java.io.IOException) {
            throw ScriptFail(500, tr("ctx.http: {0}: {1}", uri.host, e.message))
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000
        const val MAX_BYTES = 1024 * 1024
    }
}
