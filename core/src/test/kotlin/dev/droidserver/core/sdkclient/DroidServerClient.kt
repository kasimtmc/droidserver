// DroidServer istemcisi: "SDK deneme" sunucusu için üretildi (konfigürasyon v19).
// Konfigürasyon değişince yeniden indirin: GET <API>/_sdk/{ts|kotlin|dart|swift} (yönetici) veya uygulama/panel.
// Gerekenler: kotlinx-serialization-json (ve Kotlin serialization eklentisi). JVM ve Android'de çalışır.
@file:Suppress("unused", "MemberVisibilityCanBePrivate", "PropertyName")

package dev.droidserver.core.sdkclient

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** `todo_items` kaydı. Gizli alanlar yetkiye göre gelmeyebilir; bu yüzden alanlar boş olabilir. */
@Serializable
data class TodoItems(
    val id: Long,
    val title: String? = null,
    val done: Boolean? = null,
    val qty: Long? = null,
    val price: Double? = null,
    val status: String? = null,
    val tags: List<String>? = null,
    val meta: JsonElement? = null,
    val `in`: String? = null,
    val due: String? = null,
    val place: GeoPoint? = null,
    val photo: String? = null,
    val total: Double? = null,
    val owner: Long? = null,
    val olusturulma: String? = null,
    val updated_at: String? = null,
    val expand: JsonObject? = null,
)

/** `todo_items` oluşturma/güncelleme gövdesi: verilmeyen (null) alanlar gönderilmez. Boşaltmak için `RecordCollection.updateRaw`. */
@Serializable
data class TodoItemsInput(
    val title: String? = null,
    val done: Boolean? = null,
    val qty: Long? = null,
    val price: Double? = null,
    val status: String? = null,
    val tags: List<String>? = null,
    val meta: JsonElement? = null,
    val `in`: String? = null,
    val due: String? = null,
    val place: GeoPoint? = null,
    val owner: Long? = null,
)

/**
 * SDK deneme istemcisi. [baseUrl]: API adresi, örn. `https://telefon.example.com/api`; [fallbackUrls]: canlı kopyaların
 * adresleri (ana sunucuya ulaşılamazsa veya 503 dönerse sıradaki denenir). [readUrls]: okumaların gideceği kopyalar
 * (sırayla; ulaşılamazsa ana adres); bir yazmadan sonra [readYourWritesMs] boyunca okumalar ana sunucudan.
 */
class DroidServerClient(
    baseUrl: String, apiKey: String? = null, token: String? = null, refreshToken: String? = null, fallbackUrls: List<String> = emptyList(),
    readUrls: List<String> = emptyList(), readYourWritesMs: Long = 5_000,
) : DroidServerBase(
    baseUrl, apiKey, token, refreshToken, realtimePath = "/realtime", refreshPath = "/auth/refresh",
    filesPath = "/files/{collection}/{id}/{name}", fallbackUrls = fallbackUrls, readUrls = readUrls, readYourWritesMs = readYourWritesMs,
) {
    val todoItems = RecordCollection(this, "todo_items", CollectionPaths("/collections/todo_items/records", "/collections/todo_items/records/{id}", "/collections/todo_items/records", "/collections/todo_items/records/{id}", "/collections/todo_items/records/{id}", "/sync/todo_items", "id"), TodoItems.serializer(), TodoItemsInput.serializer())
    val auth = Auth(
        this, signup = "/auth/signup", login = "/auth/login", logout = "/auth/logout", me = "/auth/me",
        verify2fa = null, requestLoginCode = null, loginCode = null,
        requestSmsCode = null, smsCode = null, usage = "/auth/me/usage",
    )
    val teams = Teams(
        this, list = "/teams", create = "/teams", get = "/teams/{id}", update = "/teams/{id}", delete = "/teams/{id}",
        addMember = "/teams/{id}/members", updateMember = "/teams/{id}/members/{user}", removeMember = "/teams/{id}/members/{user}",
    )

    companion object {
        /** QR / bağlantı metninden (`droidserver://connect?url=…&token=…` veya düz adres): JWT token, değilse API anahtarı olur. */
        fun fromLink(link: String): DroidServerClient {
            val l = ConnectionLink.parse(link)
            val jwt = l.token?.count { it == '.' } == 2
            return DroidServerClient(l.url, apiKey = l.token?.takeIf { !jwt }, token = l.token?.takeIf { jwt })
        }

        /** Yerel ağdaki DroidServer'lar (mDNS). Android'de `WifiManager.MulticastLock` gerekebilir. */
        fun discover(timeoutMs: Long = 2_000): List<FoundServer> = LocalDiscovery.find(timeoutMs)
    }
}

@Serializable
data class Page<T>(val items: List<T>, val page: Int, val perPage: Int, val totalItems: Long, val totalPages: Long)

class DroidServerException(val status: Int, message: String, val details: Map<String, String> = emptyMap()) : IOException(message)

/** Konum alanı; filtre: `"konum[near]" to "41.01,28.97,5"` (km, yakından uzağa) veya `"konum[box]" to "güney,batı,kuzey,doğu"`. */
@Serializable
data class GeoPoint(val lat: Double, val lng: Double, val accuracy: Double? = null)

/** Giriş sonucu: [tokens] ya da iki adımlı doğrulama açıksa [challenge] (`Auth.verify2fa` ile tamamlayın). */
data class LoginResult(val tokens: JsonObject?, val challenge: String?)

data class RealtimeEvent(val action: String, val collection: String, val record: JsonObject)

/** Koleksiyonun uç yolları; [sync] eşitleme kapalıysa null, [idField] kimlik alanının API adı. */
data class CollectionPaths(
    val list: String?,
    val view: String?,
    val create: String?,
    val update: String?,
    val delete: String?,
    val sync: String? = null,
    val idField: String = "id",
)

/** Liste seçenekleri. [filter]: `"done" to "false"`, `"qty[gt]" to "0"`, `"tags" to "yeni"` (sorgu parametresi biçiminde). */
data class ListOptions(
    val page: Int? = null,
    val perPage: Int? = null,
    val sort: String? = null,
    val q: String? = null,
    val fields: List<String>? = null,
    val expand: List<String>? = null,
    val filter: Map<String, String> = emptyMap(),
) {
    internal fun query(): Map<String, String> = buildMap {
        page?.let { put("page", it.toString()) }
        perPage?.let { put("perPage", it.toString()) }
        sort?.let { put("sort", it) }
        q?.let { put("q", it) }
        fields?.let { put("fields", it.joinToString(",")) }
        expand?.let { put("expand", it.joinToString(",")) }
        putAll(filter)
    }
}

val DroidServerJson = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }

/** HTTP katmanı: yetki başlığı, JSON/çok parçalı gövde, hata, 401'de bir kez token yenileme. Çağrılar bloklayıcıdır. */
abstract class DroidServerBase(
    baseUrl: String,
    @Volatile var apiKey: String?,
    @Volatile var token: String?,
    @Volatile var refreshToken: String?,
    private val realtimePath: String?,
    private val refreshPath: String?,
    private val filesPath: String?,
    fallbackUrls: List<String> = emptyList(),
    readUrls: List<String> = emptyList(),
    private val readYourWritesMs: Long = 5_000,
) {
    private val urls = (listOf(baseUrl) + fallbackUrls).map { it.trimEnd('/') }
    @Volatile private var current = 0
    private val reads = readUrls.map { it.trimEnd('/') }
    @Volatile private var nextRead = 0
    @Volatile private var primaryUntil = 0L

    /** Şu an kullanılan sunucu adresi (yedek adreslerden biri olabilir). */
    val baseUrl: String get() = urls[current]

    /** Token'lar değişince (giriş, yenileme, çıkış): saklamak için. */
    var onTokens: ((token: String?, refreshToken: String?) -> Unit)? = null

    fun setTokens(token: String?, refreshToken: String?) {
        this.token = token
        this.refreshToken = refreshToken
        onTokens?.invoke(token, refreshToken)
    }

    /**
     * Ham istek; [json] veya [multipart] gövde. Yanıt JSON'u (204'te null). Yedek adres varsa: bağlantı kurulamazsa veya
     * 503 dönerse (salt okunur kopya) sıradaki denenir, çalışan adres hatırlanır.
     */
    fun request(method: String, path: String, query: Map<String, String> = emptyMap(), json: JsonElement? = null, multipart: Multipart? = null, retry: Boolean = true): JsonElement? {
        // Okuma kopyalara (sırayla); yazmadan sonra kısa süre ana sunucudan ("kendi yazdığını oku").
        if (method == "GET" && reads.isNotEmpty() && System.currentTimeMillis() >= primaryUntil) {
            repeat(reads.size) {
                val i = nextRead++.mod(reads.size)
                try {
                    return requestAt(reads[i], method, path, query, json, multipart, retry)
                } catch (e: DroidServerException) {
                    if (e.status != 503) throw e
                } catch (_: IOException) {
                }
            }
        }
        if (method != "GET") primaryUntil = System.currentTimeMillis() + readYourWritesMs
        var failed: DroidServerException? = null
        var error: IOException? = null
        for (n in urls.indices) {
            val i = (current + n) % urls.size
            try {
                return requestAt(urls[i], method, path, query, json, multipart, retry).also { current = i }
            } catch (e: DroidServerException) {
                if (e.status != 503) {
                    current = i
                    throw e
                }
                if (failed == null) failed = e
            } catch (e: IOException) {
                if (error == null) error = e
            }
        }
        throw failed ?: error!!
    }

    private fun requestAt(base: String, method: String, path: String, query: Map<String, String>, json: JsonElement?, multipart: Multipart?, retry: Boolean): JsonElement? {
        val qs = if (query.isEmpty()) "" else "?" + query.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }
        val c = URI(base + path + qs).toURL().openConnection() as HttpURLConnection
        try {
            try {
                c.requestMethod = method
            } catch (_: ProtocolException) {
                // JVM'nin HttpURLConnection'ı PATCH yapamaz; sunucu X-HTTP-Method-Override'ı tanır.
                c.requestMethod = "POST"
                c.setRequestProperty("X-HTTP-Method-Override", method)
            }
            (token ?: apiKey)?.let { c.setRequestProperty("Authorization", "Bearer " + it) }
            c.setRequestProperty("Accept", "application/json")
            when {
                json != null -> {
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    c.outputStream.use { it.write(json.toString().toByteArray()) }
                }
                multipart != null -> {
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + multipart.boundary)
                    c.outputStream.use(multipart::writeTo)
                }
            }
            val status = c.responseCode
            if (status == 401 && retry && token != null && refreshToken != null && refreshPath != null && path != refreshPath && tryRefresh()) {
                return requestAt(base, method, path, query, json, multipart, retry = false)
            }
            val body = (if (status >= 400) c.errorStream else c.inputStream)?.use { it.readBytes().decodeToString() }.orEmpty()
            val data = body.takeIf { it.isNotBlank() }?.let { runCatching { DroidServerJson.parseToJsonElement(it) }.getOrNull() }
            if (status >= 400) {
                val o = data as? JsonObject
                val details = (o?.get("details") as? JsonObject)?.mapValues { (it.value as? JsonPrimitive)?.content ?: it.value.toString() }.orEmpty()
                throw DroidServerException(status, (o?.get("message") as? JsonPrimitive)?.content ?: "HTTP " + status, details)
            }
            return data
        } finally {
            c.disconnect()
        }
    }

    private fun tryRefresh(): Boolean = try {
        val t = request("POST", refreshPath!!, json = buildJsonObject { put("refreshToken", JsonPrimitive(refreshToken)) }, retry = false)!!.jsonObject
        setTokens(t["token"]!!.jsonPrimitive.content, t["refreshToken"]!!.jsonPrimitive.content)
        true
    } catch (_: IOException) {
        setTokens(null, null)
        false
    }

    /** Dosyanın adresi (`thumb`: örn. "100x100"). */
    fun fileUrl(collection: String, id: Long, name: String, thumb: String? = null): String? = filesPath?.let { p ->
        baseUrl + p.replace("{collection}", enc(collection)).replace("{id}", id.toString()).replace("{name}", enc(name)) +
            (thumb?.let { "?thumb=" + enc(it) } ?: "")
    }

    /**
     * Canlı abonelik (Server-Sent Events) arka plan iş parçacığında; bağlantı koparsa kaldığı olaydan devam eder.
     * [Closeable.close] aboneliği kapatır.
     */
    fun subscribe(collections: List<String>, onError: (Exception) -> Unit = {}, onEvent: (RealtimeEvent) -> Unit): Closeable {
        val path = realtimePath ?: throw DroidServerException(404, "Canlı abonelik kapalı")
        val open = AtomicBoolean(true)
        val conn = AtomicReference<HttpURLConnection?>()
        val thread = Thread {
            var lastId: String? = null
            while (open.get()) {
                try {
                    val c = URI(baseUrl + path + "?collections=" + enc(collections.joinToString(","))).toURL().openConnection() as HttpURLConnection
                    conn.set(c)
                    (token ?: apiKey)?.let { c.setRequestProperty("Authorization", "Bearer " + it) }
                    c.setRequestProperty("Accept", "text/event-stream")
                    lastId?.let { c.setRequestProperty("Last-Event-ID", it) }
                    if (c.responseCode != 200) throw DroidServerException(c.responseCode, "Canlı abonelik açılamadı")
                    c.inputStream.bufferedReader().use { r ->
                        var event = "message"
                        val data = StringBuilder()
                        while (open.get()) {
                            val line = r.readLine() ?: break
                            when {
                                line.isEmpty() -> {
                                    if (event == "message" && data.isNotEmpty()) {
                                        val o = DroidServerJson.parseToJsonElement(data.toString()).jsonObject
                                        onEvent(RealtimeEvent(o["action"]!!.jsonPrimitive.content, o["collection"]!!.jsonPrimitive.content, o["record"]!!.jsonObject))
                                    }
                                    event = "message"
                                    data.setLength(0)
                                }
                                line.startsWith("id: ") -> lastId = line.removePrefix("id: ")
                                line.startsWith("event: ") -> event = line.removePrefix("event: ")
                                line.startsWith("data: ") -> data.append(line.removePrefix("data: "))
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (open.get()) onError(e)
                }
                if (open.get()) runCatching { Thread.sleep(3000) }
            }
        }
        thread.isDaemon = true
        thread.start()
        return Closeable {
            open.set(false)
            conn.get()?.disconnect()
        }
    }

    internal fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}

/** Çok parçalı gövde: alanlar ve dosyalar. */
class Multipart(private val fields: Map<String, String>, private val files: Map<String, List<File>>) {
    val boundary = "droidserver" + System.nanoTime()

    fun writeTo(out: java.io.OutputStream) {
        fun line(s: String) = out.write((s + "\r\n").toByteArray())
        for ((k, v) in fields) {
            line("--" + boundary); line("Content-Disposition: form-data; name=\"" + k + "\""); line(""); line(v)
        }
        for ((k, list) in files) for (f in list) {
            line("--" + boundary)
            line("Content-Disposition: form-data; name=\"" + k + "\"; filename=\"" + f.name.replace("\"", "") + "\"")
            line("Content-Type: application/octet-stream"); line("")
            f.inputStream().use { it.copyTo(out) }
            line("")
        }
        line("--" + boundary + "--")
    }
}

class RecordCollection<T, I>(
    private val client: DroidServerBase,
    val name: String,
    private val paths: CollectionPaths,
    private val serializer: KSerializer<T>,
    private val input: KSerializer<I>,
) {
    private fun at(p: String?, key: String, id: Long? = null): String =
        (p ?: throw DroidServerException(404, "'" + name + "' koleksiyonunda bu uç kapalı: " + key)).let { if (id == null) it else it.replace("{id}", id.toString()) }

    private fun record(e: JsonElement?): T = DroidServerJson.decodeFromJsonElement(serializer, e!!)

    private fun body(data: I, files: Map<String, List<File>>): Pair<JsonElement?, Multipart?> {
        val json = DroidServerJson.encodeToJsonElement(input, data)
        if (files.isEmpty()) return json to null
        val fields = json.jsonObject.mapValues { (_, v) -> if (v is JsonPrimitive && v.isString) v.content else if (v is JsonNull) "" else v.toString() }
        return null to Multipart(fields, files)
    }

    fun list(options: ListOptions = ListOptions()): Page<T> =
        DroidServerJson.decodeFromJsonElement(Page.serializer(serializer), client.request("GET", at(paths.list, "list"), options.query())!!)

    fun get(id: Long, expand: List<String>? = null): T =
        record(client.request("GET", at(paths.view, "view", id), expand?.let { mapOf("expand" to it.joinToString(",")) }.orEmpty()))

    /** [files]: dosya alanı → dosyalar. */
    fun create(data: I, files: Map<String, List<File>> = emptyMap()): T {
        val (json, multipart) = body(data, files)
        return record(client.request("POST", at(paths.create, "create"), json = json, multipart = multipart))
    }

    fun update(id: Long, data: I, files: Map<String, List<File>> = emptyMap()): T {
        val (json, multipart) = body(data, files)
        return record(client.request("PATCH", at(paths.update, "update", id), json = json, multipart = multipart))
    }

    /** Ham güncelleme (örn. alanı boşaltmak: `buildJsonObject { put("note", JsonNull) }`). */
    fun updateRaw(id: Long, body: JsonObject): T = record(client.request("PATCH", at(paths.update, "update", id), json = body))

    fun delete(id: Long) {
        client.request("DELETE", at(paths.delete, "delete", id))
    }

    /** Çevrimdışı kopya (koleksiyonda eşitleme açık olmalı). [key]: depodaki anahtar; sunucu başına farklı verin. */
    fun offline(store: OfflineStore, key: String = "droidserver-" + name): OfflineCollection<T, I> =
        OfflineCollection(client, name, at(paths.sync, "sync"), paths.idField, serializer, input, store, key)
}

/** Takımlar (`team` kuralı): kullanıcının takımları; üye ekleme, rol ve silme takım sahibinindir. Yanıtlar JSON. */
class Teams(
    private val client: DroidServerBase,
    private val list: String?,
    private val create: String?,
    private val get: String?,
    private val update: String?,
    private val delete: String?,
    private val addMember: String?,
    private val updateMember: String?,
    private val removeMember: String?,
) {
    private fun at(p: String?, key: String, id: Long? = null, user: Long? = null) =
        (p ?: throw DroidServerException(404, "Bu takım ucu kapalı: " + key)).replace("{id}", id.toString()).replace("{user}", user.toString())

    /** `{items: [{id, name, role, memberCount}]}` */
    fun list(): JsonObject = client.request("GET", at(list, "list"))!!.jsonObject

    fun create(name: String): JsonObject = client.request("POST", at(create, "create"), json = buildJsonObject { put("name", JsonPrimitive(name)) })!!.jsonObject

    /** Takım ve üyeleri: `{id, name, role, memberCount, members: [{user, email, role}]}` */
    fun get(id: Long): JsonObject = client.request("GET", at(get, "get", id))!!.jsonObject

    fun rename(id: Long, name: String): JsonObject = client.request("PATCH", at(update, "update", id), json = buildJsonObject { put("name", JsonPrimitive(name)) })!!.jsonObject

    fun delete(id: Long) {
        client.request("DELETE", at(delete, "delete", id))
    }

    /** [email] veya [user] (kimlik); [role] `owner` veya `member`. */
    fun addMember(id: Long, email: String? = null, user: Long? = null, role: String = "member"): JsonObject = client.request(
        "POST", at(addMember, "addMember", id),
        json = buildJsonObject {
            email?.let { put("email", JsonPrimitive(it)) }
            user?.let { put("user", JsonPrimitive(it)) }
            put("role", JsonPrimitive(role))
        },
    )!!.jsonObject

    fun setRole(id: Long, user: Long, role: String): JsonObject =
        client.request("PATCH", at(updateMember, "updateMember", id, user), json = buildJsonObject { put("role", JsonPrimitive(role)) })!!.jsonObject

    /** Sahip üyeyi çıkarır; kullanıcı kendi kimliğiyle çağırınca takımdan ayrılır. */
    fun removeMember(id: Long, user: Long) {
        client.request("DELETE", at(removeMember, "removeMember", id, user))
    }
}

class Auth(
    private val client: DroidServerBase,
    private val signup: String?,
    private val login: String?,
    private val logout: String?,
    private val me: String?,
    private val verify2fa: String?,
    private val requestLoginCode: String?,
    private val loginCode: String?,
    private val requestSmsCode: String?,
    private val smsCode: String?,
    private val usage: String?,
) {
    private fun at(p: String?, key: String) = p ?: throw DroidServerException(404, "Bu hesap ucu kapalı: " + key)

    private fun keep(e: JsonElement?): LoginResult {
        val o = e!!.jsonObject
        val token = (o["token"] as? JsonPrimitive)?.content
        val refresh = (o["refreshToken"] as? JsonPrimitive)?.content
        if (token != null && refresh != null) client.setTokens(token, refresh)
        return LoginResult(o.takeIf { token != null }, (o["challenge"] as? JsonPrimitive)?.content)
    }

    /** Gövde: email, password ve ek alanlar. */
    fun signup(body: JsonObject): LoginResult = keep(client.request("POST", at(signup, "signup"), json = body))

    fun login(email: String, password: String): LoginResult = keep(
        client.request("POST", at(login, "login"), json = buildJsonObject { put("email", JsonPrimitive(email)); put("password", JsonPrimitive(password)) }),
    )

    fun verify2fa(challenge: String, code: String): LoginResult = keep(
        client.request("POST", at(verify2fa, "verify2fa"), json = buildJsonObject { put("challenge", JsonPrimitive(challenge)); put("code", JsonPrimitive(code)) }),
    )

    fun requestLoginCode(email: String) {
        client.request("POST", at(requestLoginCode, "requestLoginCode"), json = buildJsonObject { put("email", JsonPrimitive(email)) })
    }

    fun loginWithCode(email: String, code: String): LoginResult = keep(
        client.request("POST", at(loginCode, "loginCode"), json = buildJsonObject { put("email", JsonPrimitive(email)); put("code", JsonPrimitive(code)) }),
    )

    /** Numara uluslararası biçimde (+905551234567); kod sunucu telefonundan SMS ile gelir. */
    fun requestSmsCode(phone: String) {
        client.request("POST", at(requestSmsCode, "requestSmsCode"), json = buildJsonObject { put("phone", JsonPrimitive(phone)) })
    }

    fun loginWithSms(phone: String, code: String): LoginResult = keep(
        client.request("POST", at(smsCode, "smsCode"), json = buildJsonObject { put("phone", JsonPrimitive(phone)); put("code", JsonPrimitive(code)) }),
    )

    fun logout() {
        client.refreshToken?.let { r -> runCatching { client.request("POST", at(logout, "logout"), json = buildJsonObject { put("refreshToken", JsonPrimitive(r)) }) } }
        client.setTokens(null, null)
    }

    fun me(): JsonObject = client.request("GET", at(me, "me"))!!.jsonObject

    fun usage(): JsonObject = client.request("GET", at(usage, "usage"))!!.jsonObject
}

/** Telefondaki "Bağlan (QR)" ekranının metni: `droidserver://connect?url=…&name=…&token=…&exp=…` veya düz adres. */
data class ConnectionLink(val url: String, val name: String? = null, val token: String? = null, val expiresAtEpochSec: Long? = null) {
    companion object {
        fun parse(link: String): ConnectionLink {
            val t = link.trim()
            if (t.startsWith("http://") || t.startsWith("https://")) return ConnectionLink(t.trimEnd('/'))
            require(t.startsWith("droidserver://connect?")) { "DroidServer bağlantısı değil" }
            val q = t.substringAfter('?').split('&').filter { '=' in it }
                .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
            val url = q["url"]?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: throw IllegalArgumentException("Bağlantıda adres yok")
            return ConnectionLink(url, q["name"], q["token"], q["exp"]?.toLongOrNull())
        }
    }
}

/** Yerel ağda bulunan sunucu; [baseUrl] API adresi. */
data class FoundServer(val name: String, val port: Int, val addresses: List<InetAddress>, val attributes: Map<String, String>) {
    val baseUrl: String?
        get() {
            val a = addresses.firstOrNull { it.address.size == 4 } ?: addresses.firstOrNull() ?: return null
            val host = a.hostAddress.substringBefore('%').let { if (':' in it) "[$it]" else it }
            return "http://$host:$port" + (attributes["path"] ?: "/api")
        }
}

/** `_droidserver._tcp.local` için mDNS PTR sorgusu (geçici porttan; yanıtlar doğrudan gelir). */
object LocalDiscovery {
    private const val SERVICE = "_droidserver._tcp.local"

    fun find(timeoutMs: Long = 2_000, target: InetSocketAddress = InetSocketAddress(InetAddress.getByName("224.0.0.251"), 5353)): List<FoundServer> {
        val records = mutableListOf<Triple<String, Int, Any?>>()
        val sources = HashMap<String, InetAddress>()
        DatagramSocket().use { socket ->
            val q = java.io.ByteArrayOutputStream().apply {
                write(byteArrayOf(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0))
                SERVICE.split('.').forEach { l -> val b = l.toByteArray(); write(b.size); write(b) }
                write(byteArrayOf(0, 0, 12, 0, 1))
            }.toByteArray()
            socket.send(DatagramPacket(q, q.size, target))
            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(9000)
            while (true) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) break
                socket.soTimeout = left.toInt().coerceAtLeast(1)
                val packet = DatagramPacket(buf, buf.size)
                try { socket.receive(packet) } catch (_: SocketTimeoutException) { break }
                val parsed = runCatching { parse(packet.data.copyOf(packet.length)) }.getOrNull() ?: continue
                records += parsed
                parsed.filter { it.second == 12 }.forEach { sources[it.third as String] = packet.address }
            }
        }
        return records.filter { it.second == 12 && it.first == SERVICE.lowercase() }.map { it.third as String }.distinct().mapNotNull { instance ->
            val key = instance.lowercase()
            @Suppress("UNCHECKED_CAST")
            val srv = records.firstOrNull { it.second == 33 && it.first == key }?.third as Pair<Int, String>? ?: return@mapNotNull null
            @Suppress("UNCHECKED_CAST")
            val txt = records.firstOrNull { it.second == 16 && it.first == key }?.third as Map<String, String>? ?: emptyMap()
            val addresses = records.filter { (it.second == 1 || it.second == 28) && it.first == srv.second.lowercase() }.map { it.third as InetAddress }
                .ifEmpty { listOfNotNull(sources[instance]) }
            FoundServer(instance.removeSuffix(".$SERVICE"), srv.first, addresses.distinct(), txt)
        }
    }

    private fun parse(msg: ByteArray): List<Triple<String, Int, Any?>> {
        var pos = 4
        fun u16(): Int = ((msg[pos].toInt() and 0xff) shl 8 or (msg[pos + 1].toInt() and 0xff)).also { pos += 2 }
        fun name(): String {
            val labels = mutableListOf<String>()
            var p = pos
            var jumped = false
            var hops = 0
            while (true) {
                val len = msg[p].toInt() and 0xff
                if (len == 0) { p++; break }
                if (len and 0xc0 == 0xc0) {
                    check(++hops <= 64) { "mDNS: ad döngüsü" }
                    if (!jumped) pos = p + 2
                    jumped = true
                    p = ((len and 0x3f) shl 8) or (msg[p + 1].toInt() and 0xff)
                } else {
                    labels += String(msg, p + 1, len, Charsets.UTF_8); p += len + 1
                }
            }
            if (!jumped) pos = p
            return labels.joinToString(".")
        }
        val questions = u16()
        val total = u16() + u16() + u16()
        repeat(questions) { name(); pos += 4 }
        return List(total) {
            val name = name().lowercase()
            val type = u16()
            pos += 6
            val len = u16()
            val start = pos
            val data: Any? = when (type) {
                12 -> name()
                33 -> { pos += 4; val port = u16(); port to name() }
                16 -> buildMap {
                    while (pos < start + len) {
                        val n = msg[pos++].toInt() and 0xff
                        val s = String(msg, pos, n, Charsets.UTF_8).also { pos += n }
                        if (s.isNotEmpty()) put(s.substringBefore('='), s.substringAfter('=', ""))
                    }
                }
                1, 28 -> InetAddress.getByAddress(msg.copyOfRange(start, start + len))
                else -> null
            }
            pos = start + len
            Triple(name, type, data)
        }
    }
}

// ---- Çevrimdışı eşitleme (koleksiyonda `sync` açıkken) ----

/** Çevrimdışı deponun saklama yeri: anahtar → JSON metni. SQLite gibi başka bir depo bu arayüzle takılabilir. */
interface OfflineStore {
    fun read(key: String): String?
    fun write(key: String, value: String)
}

/** Bellekte (uygulama kapanınca silinir; denemeler için). */
class MemoryOfflineStore : OfflineStore {
    private val values = java.util.concurrent.ConcurrentHashMap<String, String>()
    override fun read(key: String): String? = values[key]
    override fun write(key: String, value: String) { values[key] = value }
}

/** Klasörde anahtar başına bir dosya (Android'de `context.filesDir`). Yazma önce geçici dosyaya, sonra yerine. */
class FileOfflineStore(private val dir: File) : OfflineStore {
    private fun file(key: String) = File(dir, key.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json")

    override fun read(key: String): String? = file(key).takeIf { it.isFile }?.readText()

    override fun write(key: String, value: String) {
        dir.mkdirs()
        val target = file(key)
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeText(value)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }
}

/**
 * Eşitleme çakışması. [status]: `merged` (son yazan kazanır: bazı alanlarda sunucunun değeri kaldı) veya `conflict`
 * (elle çözme kipinde değişiklik uygulanmadı ya da silinen kayıt sunucuda daha yeni). [local]: gönderilen alanlar,
 * [server]: kaydın sunucudaki (artık yerelde de olan) hali. Elle çözmek için kaydı yeniden `update` edin.
 */
data class SyncConflictInfo(val id: Long, val status: String, val local: JsonObject, val server: JsonObject?)

/**
 * Bir koleksiyonun çevrimdışı kopyası. Okuma ve yazma yereldir; yazmalar kayıt başına birleştirilerek bekler ve
 * [sync] ile gönderilir, sonra sunucudaki değişiklikler çekilir. Çevrimdışı oluşturulan kaydın kimliği eşitlenene kadar
 * negatiftir. Dosya alanları yalnızca adıyla gelir (dosya indirilmez, çevrimdışıyken yüklenmez). Çevrimdışı oluşturulan
 * kayda başka koleksiyondan ilişki, kayıt eşitlendikten sonra kurulmalı. Durum her değişiklikte depoya yazılır.
 */
class OfflineCollection<T, I> internal constructor(
    private val client: DroidServerBase,
    val name: String,
    private val path: String,
    private val idField: String,
    private val serializer: KSerializer<T>,
    private val input: KSerializer<I>,
    private val store: OfflineStore,
    private val key: String,
) {
    /** Çakışma bildirimi (bkz. [SyncConflictInfo]). */
    var onConflict: ((SyncConflictInfo) -> Unit)? = null

    /** Sunucunun kabul etmediği (doğrulama, kural) ve kuyruktan çıkarılan değişiklik. */
    var onRejected: ((id: Long, error: DroidServerException) -> Unit)? = null

    private class Op(val op: String, var id: Long, val base: Long, val fields: LinkedHashMap<String, JsonElement>, val times: LinkedHashMap<String, Long>, var time: Long)

    private val lock = Any()
    private var cursor = 0L
    private var epoch: String? = null
    private var nextTemp = -1L
    private val records = LinkedHashMap<Long, Pair<Long, JsonObject>>()
    private val pending = mutableListOf<Op>()

    init {
        store.read(key)?.let { text ->
            val o = DroidServerJson.parseToJsonElement(text).jsonObject
            cursor = o["cursor"]?.jsonPrimitive?.content?.toLong() ?: 0
            epoch = (o["epoch"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            nextTemp = o["nextTemp"]?.jsonPrimitive?.content?.toLong() ?: -1
            (o["records"] as? JsonObject)?.forEach { (id, v) ->
                val r = v.jsonObject
                records[id.toLong()] = r["v"]!!.jsonPrimitive.content.toLong() to r["r"]!!.jsonObject
            }
            (o["pending"] as? JsonArray)?.forEach { e ->
                val p = e.jsonObject
                pending += Op(
                    p["op"]!!.jsonPrimitive.content, p["id"]!!.jsonPrimitive.content.toLong(), p["base"]!!.jsonPrimitive.content.toLong(),
                    LinkedHashMap(p["fields"]!!.jsonObject), LinkedHashMap(p["times"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content.toLong() }),
                    p["time"]!!.jsonPrimitive.content.toLong(),
                )
            }
        }
    }

    private fun save() {
        val o = buildJsonObject {
            put("cursor", JsonPrimitive(cursor))
            epoch?.let { put("epoch", JsonPrimitive(it)) }
            put("nextTemp", JsonPrimitive(nextTemp))
            put("records", JsonObject(records.entries.associate { (id, v) -> id.toString() to buildJsonObject { put("v", JsonPrimitive(v.first)); put("r", v.second) } }))
            put("pending", JsonArray(pending.map(::wire)))
        }
        store.write(key, o.toString())
    }

    private fun wire(op: Op) = buildJsonObject {
        put("op", JsonPrimitive(op.op))
        put("id", JsonPrimitive(op.id))
        if (op.op == "create") put("tempId", JsonPrimitive(op.id))
        put("base", JsonPrimitive(op.base))
        put("fields", JsonObject(op.fields))
        put("times", JsonObject(op.times.mapValues { JsonPrimitive(it.value) }))
        put("time", JsonPrimitive(op.time))
    }

    private fun decode(o: JsonObject): T = DroidServerJson.decodeFromJsonElement(serializer, o)

    private fun encode(data: I): JsonObject = DroidServerJson.encodeToJsonElement(input, data).jsonObject

    /** Yereldeki kayıtlar (bekleyen değişiklikler dahil). */
    fun all(): List<T> = synchronized(lock) { records.values.map { decode(it.second) } }

    fun get(id: Long): T? = synchronized(lock) { records[id]?.let { decode(it.second) } }

    /** Gönderilmeyi bekleyen değişiklik sayısı. */
    val pendingCount: Int get() = synchronized(lock) { pending.size }

    fun create(data: I): T = synchronized(lock) {
        val fields = encode(data)
        val id = nextTemp--
        val record = JsonObject(fields + (idField to JsonPrimitive(id)))
        records[id] = 0L to record
        val now = System.currentTimeMillis()
        pending += Op("create", id, 0, LinkedHashMap(fields), LinkedHashMap(fields.keys.associateWith { now }), now)
        save()
        decode(record)
    }

    fun update(id: Long, data: I): T = synchronized(lock) {
        val (version, record) = records[id] ?: throw DroidServerException(404, "Kayıt yerelde yok: " + id)
        val fields = encode(data)
        val now = System.currentTimeMillis()
        val merged = JsonObject(record + fields)
        records[id] = version to merged
        val op = pending.firstOrNull { it.id == id }
        if (op == null) {
            pending += Op("update", id, version, LinkedHashMap(fields), LinkedHashMap(fields.keys.associateWith { now }), now)
        } else {
            op.fields.putAll(fields)
            fields.keys.forEach { op.times[it] = now }
            op.time = now
        }
        save()
        decode(merged)
    }

    fun delete(id: Long): Unit = synchronized(lock) {
        val (version, _) = records.remove(id) ?: return
        val op = pending.firstOrNull { it.id == id }
        op?.let(pending::remove)
        // Henüz gönderilmemiş kayıt: sunucuya hiç gitmez.
        if (op?.op != "create") pending += Op("delete", id, op?.base ?: version, LinkedHashMap(), LinkedHashMap(), System.currentTimeMillis())
        save()
    }

    /**
     * Bekleyen değişiklikleri gönderir, sonra sunucudaki değişiklikleri çeker. Ağ hatasında [IOException] (bekleyenler
     * kalır). Bloklayıcıdır; aynı anda bir eşitleme çalışır.
     */
    fun sync(): Unit = synchronized(lock) {
        push()
        pull()
    }

    private fun push() {
        while (pending.isNotEmpty()) {
            val batch = pending.take(100)
            val body = buildJsonObject {
                put("now", JsonPrimitive(System.currentTimeMillis()))
                put("changes", JsonArray(batch.map(::wire)))
            }
            val results = client.request("POST", path, json = body)!!.jsonObject["results"]!!.jsonArray
            for ((op, r) in batch.zip(results)) {
                val o = r.jsonObject
                val status = o["status"]!!.jsonPrimitive.content
                val id = (o["id"] as? JsonPrimitive)?.content?.toLongOrNull()
                val version = (o["version"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0
                val server = o["record"] as? JsonObject
                pending.remove(op)
                when (status) {
                    "ok", "merged", "conflict" -> {
                        if (op.op == "create") records.remove(op.id)
                        if (server != null && id != null) records[id] = version to server else if (id != null) records.remove(id)
                        if (status != "ok") onConflict?.invoke(SyncConflictInfo(id ?: op.id, status, JsonObject(op.fields), server))
                    }
                    "gone" -> records.remove(op.id)
                    else -> {
                        val code = (o["code"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
                        val details = (o["details"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
                        val error = DroidServerException(code, (o["message"] as? JsonPrimitive)?.content ?: "HTTP " + code, details)
                        if (code >= 500) {
                            // Sunucu tarafı geçici hata: sonra yeniden denenir.
                            pending.add(0, op)
                            save()
                            throw error
                        }
                        onRejected?.invoke(op.id, error)
                    }
                }
            }
            save()
        }
    }

    private fun pull() {
        do {
            val query = buildMap {
                put("since", cursor.toString())
                put("limit", "500")
                epoch?.let { put("epoch", it) }
            }
            val r = client.request("GET", path, query)!!.jsonObject
            if (r["reset"]?.jsonPrimitive?.content == "true") {
                // Sunucudaki iz yenilendi: yerel kopya baştan kurulur (gönderilmemiş yeni kayıtlar kalır).
                records.keys.filter { it > 0 }.forEach(records::remove)
                cursor = 0
            }
            epoch = r["epoch"]?.jsonPrimitive?.content
            for (c in r["changes"]!!.jsonArray) {
                val o = c.jsonObject
                val id = o["id"]!!.jsonPrimitive.content.toLong()
                if (pending.any { it.id == id }) continue // yerel değişiklik bekliyor
                if (o["action"]!!.jsonPrimitive.content == "upsert") {
                    records[id] = o["version"]!!.jsonPrimitive.content.toLong() to o["record"]!!.jsonObject
                } else {
                    records.remove(id)
                }
            }
            cursor = r["next"]!!.jsonPrimitive.content.toLong()
            save()
        } while (r["more"]?.jsonPrimitive?.content == "true")
    }

    /**
     * Canlı abonelikle: sunucuda değişiklik olunca arka planda [sync] çalışır (art arda olaylar birleşir).
     * [Closeable.close] durdurur.
     */
    fun live(onError: (Exception) -> Unit = {}): Closeable {
        val busy = AtomicBoolean(false)
        val again = AtomicBoolean(false)
        fun run() {
            if (!busy.compareAndSet(false, true)) { again.set(true); return }
            Thread {
                try {
                    do {
                        again.set(false)
                        try { sync() } catch (e: Exception) { onError(e) }
                    } while (again.get())
                } finally {
                    busy.set(false)
                }
            }.apply { isDaemon = true }.start()
        }
        run()
        return client.subscribe(listOf(name), onError) { run() }
    }
}
