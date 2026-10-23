package dev.droidserver.core.api

import dev.droidserver.core.config.GeoPoint
import dev.droidserver.core.config.AuthMode
import dev.droidserver.core.config.CollectionConfig
import dev.droidserver.core.config.FieldType
import dev.droidserver.core.config.Rule
import dev.droidserver.core.config.Rules
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.i18n.tr
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.net.HttpURLConnection
import java.net.URI

/** API gezgini: her koleksiyon için hazır `curl` örnekleri. */
object ApiExamples {

    data class Example(
        val title: String,
        val method: String,
        /** basePath dahil yol, örn. `/api/collections/todos/records?page=1`. */
        val path: String,
        val body: String? = null,
        /** İstek kimlik doğrulama başlığı gerektiriyor mu? */
        val needsAuth: Boolean,
        /** Yönetici anahtarı değil, girişte alınan kullanıcı token'ı gerekir. */
        val userToken: Boolean = false,
        /** multipart/form-data parçaları (`curl -F`), örn. `image=@foto.jpg`. */
        val form: List<String>? = null,
        /** Sürekli akış (SSE): curl çıktıyı tamponlamasın (`-N`); uygulamadan denenmez. */
        val stream: Boolean = false,
    ) {
        /** [authorization] örn. `Bearer <anahtar>`; null ise başlık eklenmez. */
        fun curl(baseUrl: String, authorization: String?): String = buildList {
            // -g: curl adresteki köşeli parantezleri (alan[op]) kalıp olarak yorumlamasın.
            val g = if ('[' in path) "-g " else ""
            val n = if (stream) "-N " else ""
            add(if (method == "GET") "curl $n$g${quote(baseUrl + path)}" else "curl $g-X $method ${quote(baseUrl + path)}")
            if (userToken) add("-H ${quote(tr("Authorization: Bearer <giriş yanıtındaki token>"))}")
            else if (needsAuth && authorization != null) add("-H ${quote("Authorization: $authorization")}")
            if (body != null) {
                add("-H 'Content-Type: application/json'")
                add("-d ${quote(body)}")
            }
            form?.forEach { add("-F ${quote(it)}") }
        }.joinToString(" \\\n  ")

        private fun auth(authorization: String?): String? = when {
            userToken -> "Bearer " + "\${token}"
            needsAuth && authorization != null -> authorization
            else -> null
        }

        /** Tarayıcı / Node.js: `fetch` (akışta `EventSource`, dosyada `FormData`). */
        fun js(baseUrl: String, authorization: String?): String = buildString {
            val url = baseUrl + path
            val a = auth(authorization)
            if (stream) {
                append(tr("// EventSource başlık gönderemez; token adreste (yalnızca bu uçta).\n"))
                val sep = if ('?' in path) "&" else "?"
                val tokenPart = a?.removePrefix("Bearer ")?.let { "${sep}token=" + if (userToken) "\${encodeURIComponent(token)}" else it }.orEmpty()
                append("const es = new EventSource(`$url$tokenPart`);\n")
                append("es.onmessage = (m) => {\n  const e = JSON.parse(m.data); // e.action, e.collection, e.record\n};\n")
                append(tr("es.addEventListener(\"reset\", () => { /* kaçırılan olaylar: listeyi yeniden yükleyin */ });"))
                return@buildString
            }
            if (userToken) append(tr("// token: giriş yanıtındaki token\n"))
            if (form != null) {
                append("const form = new FormData();\n")
                form.forEach { part ->
                    val (k, v) = part.split('=', limit = 2)
                    if (v.startsWith("@")) append("form.append(${jsStr(k)}, dosyaInput.files[0]); // ${v.drop(1)}\n")
                    else append("form.append(${jsStr(k)}, ${jsStr(v)});\n")
                }
            }
            append("const res = await fetch(${if (userToken) "`$url`" else jsStr(url)}, {\n")
            append("  method: \"$method\",\n")
            val headers = listOfNotNull(
                a?.let { "\"Authorization\": " + if (userToken) "`$it`" else jsStr(it) },
                "\"Content-Type\": \"application/json\"".takeIf { body != null },
            )
            if (headers.isNotEmpty()) append("  headers: { ${headers.joinToString(", ")} },\n")
            if (body != null) append("  body: JSON.stringify($body),\n")
            if (form != null) append("  body: form,\n")
            append("});\n")
            append(if (method == "DELETE") "console.log(res.status); // 204" else "const data = await res.json();")
        }

        /** Android / JVM: OkHttp. */
        fun kotlin(baseUrl: String, authorization: String?): String = buildString {
            val a = auth(authorization)?.let { if (userToken) "Bearer \$token" else it }
            append("val client = OkHttpClient()\n")
            if (form != null) {
                append("val body = MultipartBody.Builder().setType(MultipartBody.FORM)\n")
                form.forEach { part ->
                    val (k, v) = part.split('=', limit = 2)
                    if (v.startsWith("@")) append("    .addFormDataPart(\"$k\", \"${v.drop(1)}\", File(\"${v.drop(1)}\").asRequestBody())\n")
                    else append("    .addFormDataPart(\"$k\", \"$v\")\n")
                }
                append("    .build()\n")
            } else if (body != null) {
                append("val body = \"\"\"$body\"\"\".toRequestBody(\"application/json\".toMediaType())\n")
            }
            append("val request = Request.Builder()\n")
            append("    .url(\"$baseUrl$path\")\n")
            a?.let { append("    .header(\"Authorization\", \"$it\")\n") }
            when (method) {
                "POST" -> append("    .post(body)\n")
                "PATCH" -> append("    .patch(body)\n")
                "DELETE" -> append("    .delete()\n")
            }
            append("    .build()\n")
            if (stream) {
                append(tr("// Akış: her \"data:\" satırı bir olay (okhttp-sse kütüphanesi de kullanılabilir)\n"))
                append("client.newCall(request).execute().use { r ->\n")
                append("    r.body!!.source().let { s -> while (!s.exhausted()) s.readUtf8Line()?.takeIf { it.startsWith(\"data: \") }?.let(::println) }\n")
                append("}")
            } else {
                append("client.newCall(request).execute().use { println(it.code); println(it.body?.string()) }")
            }
        }
    }

    private fun jsStr(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    fun forCollection(config: ServerConfig, collection: CollectionConfig): List<Example> {
        // Uzman modu: etkin yollar ve sistem alanı adları; kapalı uçların örneği yok.
        val api = ApiPaths(config)
        fun at(e: CollectionEndpoint) = api.path(collection, e)?.let { api.full(it.replace("{id}", "1")) }
        val newest = ApiNames.of(collection).api("created_at")?.let { "&sort=-$it" }.orEmpty()
        val secured = config.auth.mode != AuthMode.NONE
        // Yönetici anahtarı tüm kuralları aştığı için örneklerde o kullanılır; kapalı (nobody) işlemlerin örneği yok.
        fun auth(op: Rules.Op) = secured && collection.rules[op] != Rule.PUBLIC
        fun open(op: Rules.Op) = collection.rules[op] != Rule.NOBODY
        val body = sampleBody(collection).toString()
        return buildList {
            val list = at(CollectionEndpoint.LIST)
            if (open(Rules.Op.LIST) && list != null) {
                add(Example(if (newest.isEmpty()) tr("Listele (sayfalı)") else tr("Listele (sayfalı, yeniden eskiye)"), "GET", "$list?page=1&perPage=20$newest", needsAuth = auth(Rules.Op.LIST)))
                filterExample(collection)?.let { q -> add(Example(tr("Filtrele ve alan seç"), "GET", "$list?$q", needsAuth = auth(Rules.Op.LIST))) }
                if (collection.search.isNotEmpty()) {
                    add(Example(tr("Ara ({0} içinde)", collection.search.joinToString()), "GET", "$list?q=ornek", needsAuth = auth(Rules.Op.LIST)))
                }
            }
            at(CollectionEndpoint.VIEW)?.takeIf { open(Rules.Op.VIEW) }?.let { add(Example(tr("Tek kayıt"), "GET", it, needsAuth = auth(Rules.Op.VIEW))) }
            val create = at(CollectionEndpoint.CREATE)
            create?.takeIf { open(Rules.Op.CREATE) }?.let { add(Example(tr("Oluştur"), "POST", it, body, needsAuth = auth(Rules.Op.CREATE))) }
            at(CollectionEndpoint.UPDATE)?.takeIf { open(Rules.Op.UPDATE) }?.let { add(Example(tr("Güncelle"), "PATCH", it, body, needsAuth = auth(Rules.Op.UPDATE))) }
            at(CollectionEndpoint.DELETE)?.takeIf { open(Rules.Op.DELETE) }?.let { add(Example(tr("Sil"), "DELETE", it, needsAuth = auth(Rules.Op.DELETE))) }
            val files = collection.fields.filter { it.type == FieldType.FILE }
            if (files.isNotEmpty()) {
                val text = collection.fields.firstOrNull { it.type == FieldType.TEXT }?.let { tr("{0}=örnek", it.name) }
                val parts = listOfNotNull(text) + files.map { f -> "${f.name}=@${sampleFile(f)}" }
                if (open(Rules.Op.CREATE) && create != null) add(Example(tr("Dosyayla oluştur"), "POST", create, needsAuth = auth(Rules.Op.CREATE), form = parts))
                val download = api.path(Endpoint.FILES)?.replace("{collection}", collection.name)?.replace("{id}", "1")?.replace("{name}", tr("<dosya-adı>"))
                if (open(Rules.Op.VIEW) && download != null) {
                    add(Example(tr("Dosyayı indir (?thumb=100x100: küçük resim)"), "GET", api.full(download), needsAuth = auth(Rules.Op.VIEW)))
                }
            }
            val realtime = api.path(Endpoint.REALTIME)
            if (config.realtime.enabled && open(Rules.Op.LIST) && realtime != null) {
                add(Example(tr("Canlı değişiklikler (akış; Ctrl+C ile durdurun)"), "GET", api.full(realtime) + "?collections=${collection.name}", needsAuth = auth(Rules.Op.LIST), stream = true))
            }
        }
    }

    /** Kullanıcı hesabı uçları (açıksa); `me` örneği kullanıcı token'ı ister (yönetici anahtarı yerine). */
    fun forUsers(config: ServerConfig): List<Example> {
        val users = config.auth.users
        if (!users.enabled) return emptyList()
        val api = ApiPaths(config)
        val n = ApiNames.ofUsers(users)
        val email = n.api("email") ?: "email"
        val password = n.api("password") ?: "password"
        fun at(e: Endpoint) = api.path(e)?.let(api::full)
        val credentials = buildJsonObject {
            put(email, JsonPrimitive("ornek@example.com"))
            put(password, JsonPrimitive("en-az-${users.minPasswordLength}-karakter"))
        }
        val signup = JsonObject(credentials + sampleBody(CollectionConfig("_", users.extraFields)))
        return buildList {
            fun add(title: String, method: String, e: Endpoint, body: String? = null, needsAuth: Boolean = false, userToken: Boolean = false, query: String = "") {
                at(e)?.let { add(Example(title, method, it + query, body, needsAuth = needsAuth, userToken = userToken)) }
            }
            if (users.allowSignup) add(tr("Kayıt ol"), "POST", Endpoint.AUTH_SIGNUP, signup.toString())
            add(tr("Giriş yap"), "POST", Endpoint.AUTH_LOGIN, credentials.toString())
            add(tr("Token yenile"), "POST", Endpoint.AUTH_REFRESH, """{"refreshToken":"<yenileme token'ı>"}""")
            add(tr("Hesabım (kullanıcı token'ı ile)"), "GET", Endpoint.AUTH_ME, userToken = true)
            if (config.email.enabled) {
                add(tr("Parolamı unuttum (e-posta gönderir)"), "POST", Endpoint.AUTH_REQUEST_RESET, """{"$email":"ornek@example.com"}""")
                add(tr("Yeni parola (e-postadaki kodla)"), "POST", Endpoint.AUTH_CONFIRM_RESET, """{"token":"<kod>","$password":"yeni-parola"}""")
                add(tr("Doğrulama e-postasını yeniden gönder"), "POST", Endpoint.AUTH_REQUEST_VERIFICATION, """{"$email":"ornek@example.com"}""")
            }
            add(tr("Kullanıcılar (yönetici)"), "GET", Endpoint.USERS_LIST, needsAuth = true, query = "?page=1&perPage=20")
        }
    }

    /** İlk uygun alana göre bir filtre (bkz. FilterParser) ve ilk iki alanın seçimi; örnek gövdeyle eşleşir. */
    private fun filterExample(collection: CollectionConfig): String? {
        val filter = collection.fields.firstNotNullOfOrNull { f ->
            when (f.type) {
                FieldType.INTEGER, FieldType.REAL -> "${f.name}[gte]=1"
                FieldType.DATETIME -> "${f.name}[gt]=2025-12-31T00:00:00Z"
                FieldType.TEXT -> "${f.name}[like]=${f.name}"
                FieldType.BOOLEAN -> "${f.name}=true"
                FieldType.SELECT, FieldType.MULTISELECT -> f.options?.firstOrNull()?.let { "${f.name}=$it" }
                FieldType.GEO -> "${f.name}[near]=41.01,28.97,5"
                FieldType.JSON, FieldType.RELATION, FieldType.FILE -> null
            }
        } ?: return null
        val fields = collection.fields.take(2).joinToString(",") { it.name }
        return "$filter&fields=$fields"
    }

    private fun sampleFile(f: dev.droidserver.core.config.FieldConfig): String = when {
        f.mimeTypes?.all { it.startsWith("image/") } == true -> "foto.jpg"
        f.mimeTypes == listOf("application/pdf") -> "belge.pdf"
        f.mimeTypes?.all { it.startsWith("audio/") } == true -> "ses.mp3"
        f.mimeTypes?.all { it.startsWith("video/") } == true -> "video.mp4"
        else -> "dosya.pdf"
    }

    /** Alan tiplerine uygun örnek gövde; benzersiz alanlar çakışmasın diye değerler sabit değil, belirgin örneklerdir. */
    fun sampleBody(collection: CollectionConfig): JsonObject = buildJsonObject {
        for (f in collection.fields) {
            if (f.type == FieldType.FILE || f.computed != null) continue // dosyalar multipart ile; hesaplanan alan yazılmaz
            put(f.name, when (f.type) {
                FieldType.TEXT -> JsonPrimitive(tr("örnek {0}", f.name))
                FieldType.INTEGER -> JsonPrimitive(1)
                FieldType.REAL -> JsonPrimitive(9.99)
                FieldType.BOOLEAN -> JsonPrimitive(true)
                FieldType.DATETIME -> JsonPrimitive("2026-01-01T09:00:00Z")
                FieldType.JSON -> JsonArray(listOf(JsonPrimitive("etiket")))
                FieldType.GEO -> GeoPoint(41.0082, 28.9784).json()
                FieldType.RELATION -> JsonPrimitive(1) // hedef koleksiyondaki bir kaydın kimliği
                FieldType.FILE -> error(tr("atlandı"))
                FieldType.SELECT -> JsonPrimitive(f.options?.firstOrNull() ?: "")
                FieldType.MULTISELECT -> JsonArray(f.options.orEmpty().take(1).map(::JsonPrimitive))
            })
        }
    }

    /** Kabuk için tek tırnaklı metin; içindeki `'` kaçırılır. */
    private fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"
}

/** Cihaz içinden sunucuya basit GET isteği (API gezgininin "Dene" düğmesi). */
object ApiProbe {
    data class Response(val status: Int, val body: String)

    fun get(url: String, authorization: String?, timeoutMillis: Int = 5_000): Response {
        // Sorgudaki köşeli parantezler (alan[op]) URI'de kodlanmalı; adresteki IPv6 parantezlerine dokunulmaz.
        val q = url.indexOf('?')
        val safe = if (q < 0) url else url.substring(0, q) + url.substring(q).replace("[", "%5B").replace("]", "%5D")
        val conn = URI(safe).toURL().openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMillis
            conn.readTimeout = timeoutMillis
            authorization?.let { conn.setRequestProperty("Authorization", it) }
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            return Response(status, stream?.use { it.readBytes().decodeToString() }.orEmpty())
        } finally {
            conn.disconnect()
        }
    }
}
