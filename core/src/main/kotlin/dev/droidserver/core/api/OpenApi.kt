package dev.droidserver.core.api

import dev.droidserver.core.auth.Caller
import dev.droidserver.core.config.CollectionConfig
import dev.droidserver.core.config.ConfigMigrations
import dev.droidserver.core.config.FieldConfig
import dev.droidserver.core.config.FieldType
import dev.droidserver.core.config.FieldVisibility
import dev.droidserver.core.config.Rule
import dev.droidserver.core.config.Rules
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.config.SystemCollections
import dev.droidserver.core.config.TextFormat
import dev.droidserver.core.i18n.tr
import dev.droidserver.core.script.ScriptRoute
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Konfigürasyondan OpenAPI 3.1 dokümanı (`GET <basePath>/_openapi.json`). İsteyenin yetkisine göre üretilir: yönetici
 * her şeyi, diğerleri yalnızca kurallarının izin verdiği koleksiyon işlemlerini görür (kapalı uçlar dokümanda yok).
 */
object OpenApi {
    fun document(config: ServerConfig, caller: Caller, scriptRoutes: List<ScriptRoute> = emptyList()): JsonObject {
        val secured = config.auth.mode != dev.droidserver.core.config.AuthMode.NONE
        val paths = linkedMapOf<String, MutableMap<String, JsonObject>>()
        // Uzman modu: varsayılan yollar etkin yollara çevrilir, kapalı uçlar belgeye girmez.
        val api = ApiPaths(config)
        val remap = HashMap<String, String?>()
        Endpoint.entries.forEach { remap["${it.method} ${it.path}"] = api.path(it) }
        config.collections.forEach { c -> CollectionEndpoint.entries.forEach { remap["${it.method} ${it.defaultPath(c.name)}"] = api.path(c, it) } }
        fun op(path: String, method: String, o: JsonObject) {
            val key = "${method.uppercase()} $path"
            val actual = when {
                key in remap -> remap[key] ?: return
                path.startsWith("/files/") -> api.path(Endpoint.FILES)?.replace("{collection}", path.split('/')[2]) ?: return
                else -> path
            }
            paths.getOrPut(actual) { linkedMapOf() }[method] = o
        }
        val userNames = ApiNames.ofUsers(config.auth.users)
        fun u(name: String) = userNames.api(name) ?: name
        val schemas = linkedMapOf<String, JsonElement>()
        schemas["Error"] = obj(
            "type" to str("object"),
            "properties" to obj("status" to type("integer"), "message" to type("string"), "details" to obj("type" to str("object"))),
        )

        op("/health", "get", operation(tr("Sunucu"), tr("Sunucu durumu"), public = true, responses = ok(type("object"))))

        val visible = config.collections.mapNotNull { c ->
            val ops = Rules.Op.entries.filter { allowed(c, it, caller) }
            // Yöneticiye özel alanlar başkalarının belgesinde yok.
            val shown = if (caller.isAdmin) c else c.copy(fields = c.fields.filter { it.visibility != FieldVisibility.ADMIN })
            if (ops.isEmpty()) null else shown to ops
        }
        for ((c, ops) in visible) {
            schemas[c.name] = recordSchema(c)
            schemas["${c.name}Input"] = inputSchema(c)
            val base = "/collections/${c.name}/records"
            fun public(o: Rules.Op) = !secured || c.rules[o] == Rule.PUBLIC
            val tag = c.name
            if (Rules.Op.LIST in ops) {
                schemas["${c.name}Page"] = obj(
                    "type" to str("object"),
                    "properties" to obj(
                        "items" to obj("type" to str("array"), "items" to ref(c.name)),
                        "page" to type("integer"), "perPage" to type("integer"),
                        "totalItems" to type("integer"), "totalPages" to type("integer"),
                    ),
                )
                val filters = buildList {
                    if (c.search.isNotEmpty()) add(query("q", "string", tr("Arama ({0}): büyük/küçük harf ve Türkçe karakterden bağımsız", c.search.joinToString())))
                    for (f in c.fields.filter { it.type != FieldType.JSON && it.type != FieldType.FILE }) {
                        add(query(f.name, schemaType(f), tr("Eşittir. Diğer işleçler: {0}[ne|gt|gte|lt|lte|like|in|null]=değer", f.name)))
                    }
                }
                val params = buildJsonArray {
                    add(query("page", "integer", tr("Sayfa (1'den)")))
                    add(query("perPage", "integer", tr("Sayfa başına kayıt (1-500, varsayılan 20)")))
                    add(query("sort", "string", tr("Alan adları, virgülle; azalan için başına - (örn. -created_at)")))
                    add(query("fields", "string", tr("Yalnızca bu alanlar (virgülle)")))
                    if (c.fields.any { it.type == FieldType.RELATION }) add(query("expand", "string", tr("Gömülecek ilişki alanları (virgülle)")))
                    filters.forEach { add(it) }
                }
                op(base, "get", operation(tag, tr("Listele"), public(Rules.Op.LIST), params = params, responses = ok(ref("${c.name}Page")), rule = c.rules[Rules.Op.LIST]))
                val aggParams = buildJsonArray {
                    add(query("count", "boolean", tr("Kayıt sayısı (her zaman döner)")))
                    add(query("sum", "string", tr("Toplanacak sayı alanları (virgülle)")))
                    add(query("avg", "string", tr("Ortalaması alınacak sayı alanları (virgülle)")))
                    add(query("min", "string", tr("En küçük değer: sayı, zaman veya metin alanları (virgülle)")))
                    add(query("max", "string", tr("En büyük değer: sayı, zaman veya metin alanları (virgülle)")))
                    add(query("groupBy", "string", tr("Gruplanacak alan; zaman alanında alan:day|month|year")))
                    filters.forEach { add(it) }
                }
                val exportParams = buildJsonArray {
                    add(obj("name" to str("format"), "in" to str("query"), "schema" to obj("type" to str("string"), "enum" to buildJsonArray { add(str("csv")); add(str("json")); add(str("zip")) })))
                    add(query("delimiter", "string", tr("semicolon: Türkçe Excel için noktalı virgül")))
                    add(query("sort", "string", tr("Sıralama")))
                    filters.forEach { add(it) }
                }
                op("/collections/${c.name}/export", "get", operation(tag, tr("Dışa aktar (CSV, JSON veya dosyalarıyla zip)"), public(Rules.Op.LIST), params = exportParams,
                    responses = obj("200" to obj("description" to str(tr("Dosya")), "content" to obj(
                        "text/csv" to obj("schema" to type("string")),
                        "application/json" to obj("schema" to type("array")),
                        "application/zip" to obj("schema" to obj("type" to str("string"), "format" to str("binary"))),
                    )))))
                if (caller.isAdmin) {
                    val importParams = buildJsonArray {
                        add(obj("name" to str("format"), "in" to str("query"), "schema" to obj("type" to str("string"), "enum" to buildJsonArray { add(str("csv")); add(str("json")); add(str("zip")) })))
                        add(obj("name" to str("mode"), "in" to str("query"), "schema" to obj("type" to str("string"), "enum" to buildJsonArray { add(str("insert")); add(str("upsert")) })))
                        add(query("dryRun", "boolean", tr("Yalnızca denetle ve önizle, yazma")))
                        add(query("mapping", "string", tr("JSON: {\"Sütun\": \"alan\"}; boşsa adlar eşleşir")))
                    }
                    val importBody = obj("required" to JsonPrimitive(true), "content" to obj(
                        "text/csv" to obj("schema" to type("string")),
                        "application/json" to obj("schema" to obj("type" to str("array"), "items" to obj("type" to str("object")))),
                        "application/zip" to obj("schema" to obj("type" to str("string"), "format" to str("binary"), "description" to str(tr("data.json veya data.csv ve files/<id>/<ad> dosyaları")))),
                    ))
                    op("/collections/${c.name}/import", "post", operation(tag, tr("İçe aktar (hepsi ya da hiçbiri)"), public = false, params = importParams, body = importBody,
                        responses = ok(obj("type" to str("object"), "description" to str(tr("{columns, mapping, total, created, updated, errors, sample, dryRun, applied}"))))))
                    op("/collections/${c.name}/import/template", "get", operation(tag, tr("İçe aktarma şablonu (CSV: başlıklar, ipuçları, örnek satır)"), public = false,
                        params = buildJsonArray { add(query("delimiter", "string", tr("semicolon: Türkçe Excel için noktalı virgül"))) },
                        responses = obj("200" to obj("description" to str(tr("Dosya")), "content" to obj("text/csv" to obj("schema" to type("string")))))))
                }
                op("/collections/${c.name}/aggregate", "get", operation(tag, tr("Özet: sayı, toplam, ortalama, gruplama"), public(Rules.Op.LIST), params = aggParams,
                    responses = ok(obj("type" to str("object"), "description" to str(tr("{count, sum_alan, ...} veya {groups:[{key, count, ...}], truncated}")))), rule = c.rules[Rules.Op.LIST]))
            }
            if (Rules.Op.CREATE in ops) {
                op(base, "post", operation(tag, tr("Oluştur"), public(Rules.Op.CREATE), body = body(c), responses = ok(ref(c.name), "201"), rule = c.rules[Rules.Op.CREATE]))
            }
            val idParam = buildJsonArray { add(obj("name" to str("id"), "in" to str("path"), "required" to JsonPrimitive(true), "schema" to type("integer"))) }
            if (Rules.Op.VIEW in ops) op("$base/{id}", "get", operation(tag, tr("Tek kayıt"), public(Rules.Op.VIEW), params = idParam, responses = ok(ref(c.name)), rule = c.rules[Rules.Op.VIEW]))
            if (Rules.Op.UPDATE in ops) {
                op("$base/{id}", "patch", operation(tag, tr("Güncelle (yalnızca gönderilen alanlar)"), public(Rules.Op.UPDATE), params = idParam, body = body(c), responses = ok(ref(c.name)), rule = c.rules[Rules.Op.UPDATE]))
            }
            if (Rules.Op.DELETE in ops) {
                op("$base/{id}", "delete", operation(tag, tr("Sil"), public(Rules.Op.DELETE), params = idParam, responses = obj("204" to obj("description" to str(tr("Silindi")))), rule = c.rules[Rules.Op.DELETE]))
            }
            if (caller.isAdmin && (c.history != null || c.trash != null)) {
                val pageParams = buildJsonArray {
                    add(query("page", "integer", tr("Sayfa (1'den)")))
                    add(query("perPage", "integer", tr("Sayfa başına (1-500, varsayılan 50)")))
                }
                val list = { what: String -> ok(obj("type" to str("object"), "description" to str(what))) }
                if (c.history != null) {
                    op("$base/{id}/history", "get", operation(tag, tr("Kaydın geçmişi (yeniden eskiye)"), public = false, params = JsonArray(idParam + pageParams),
                        responses = list(tr("{items:[{version, action (create|update|delete|restore|revert), at, actor, data}], totalItems}"))))
                    val versionParams = JsonArray(idParam + obj("name" to str("version"), "in" to str("path"), "required" to JsonPrimitive(true), "schema" to type("integer")))
                    op("$base/{id}/history/{version}/revert", "post", operation(tag, tr("Sürüme dön (dosya ve hesaplanan alanlar hariç)"), public = false,
                        params = JsonArray(versionParams + query("fields", "string", tr("Yalnızca bu alanlar (virgülle); boşsa hepsi"))), responses = ok(ref(c.name))))
                }
                if (c.trash != null) {
                    op("/collections/${c.name}/trash", "get", operation(tag, tr("Çöp kutusu ({0} gün)", c.trash.days), public = false, params = pageParams,
                        responses = list(tr("{items:[{id, deletedAt, expiresAt, actor, data}], totalItems}"))))
                    op("/collections/${c.name}/trash/{id}/restore", "post", operation(tag, tr("Çöp kutusundan geri yükle (aynı kimlikle)"), public = false,
                        params = idParam, responses = ok(ref(c.name))))
                    op("/collections/${c.name}/trash/{id}", "delete", operation(tag, tr("Kalıcı sil (dosyalarıyla)"), public = false, params = idParam,
                        responses = obj("204" to obj("description" to str(tr("Silindi"))))))
                }
            }
            if (Rules.Op.VIEW in ops && c.fields.any { it.type == FieldType.FILE }) {
                val params = buildJsonArray {
                    add(obj("name" to str("id"), "in" to str("path"), "required" to JsonPrimitive(true), "schema" to type("integer")))
                    add(obj("name" to str("name"), "in" to str("path"), "required" to JsonPrimitive(true), "schema" to type("string")))
                    add(query("thumb", "string", tr("Küçük resim boyutu, örn. 100x100")))
                }
                op("/files/${c.name}/{id}/{name}", "get", operation(tag, tr("Dosyayı indir"), public(Rules.Op.VIEW), params = params,
                    responses = obj("200" to obj("description" to str(tr("Dosya")), "content" to obj("application/octet-stream" to obj("schema" to obj("type" to str("string"), "format" to str("binary"))))))))
            }
        }

        val batchable = visible.filter { (c, ops) ->
            c.fields.none { it.type == FieldType.FILE } && ops.any { it == Rules.Op.CREATE || it == Rules.Op.UPDATE || it == Rules.Op.DELETE }
        }
        if (batchable.isNotEmpty()) {
            val request = obj(
                "type" to str("object"),
                "required" to buildJsonArray { add(str("method")); add(str("collection")) },
                "properties" to obj(
                    "method" to obj("type" to str("string"), "enum" to buildJsonArray { listOf("POST", "PATCH", "DELETE").forEach { add(str(it)) } }),
                    "collection" to obj("type" to str("string"), "enum" to buildJsonArray { batchable.forEach { add(str(it.first.name)) } }),
                    "id" to obj("type" to str("integer"), "description" to str(tr("PATCH ve DELETE için"))),
                    "body" to obj("type" to str("object")),
                ),
            )
            val body = obj("required" to JsonPrimitive(true), "content" to obj("application/json" to obj("schema" to obj(
                "type" to str("object"),
                "required" to buildJsonArray { add(str("requests")) },
                "properties" to obj("requests" to obj("type" to str("array"), "maxItems" to JsonPrimitive(100), "items" to request)),
            ))))
            val result = obj("type" to str("object"), "properties" to obj("results" to obj("type" to str("array"), "items" to obj(
                "type" to str("object"), "properties" to obj("status" to type("integer"), "body" to obj("type" to str("object"))),
            ))))
            op("/batch", "post", operation(tr("Toplu"), tr("Toplu işlem: hepsi ya da hiçbiri (en çok 100 istek, her biri kendi kuralıyla)"),
                public = !secured || caller == Caller.Anonymous,
                body = body, responses = ok(result)))
        }

        if (config.realtime.enabled && visible.any { Rules.Op.LIST in it.second }) {
            val names = visible.filter { Rules.Op.LIST in it.second }.map { it.first.name }
            op("/realtime", "get", operation(
                tr("Canlı"), tr("Kayıt değişikliklerine abone ol (Server-Sent Events)"), public = !secured,
                params = buildJsonArray {
                    add(obj("name" to str("collections"), "in" to str("query"), "required" to JsonPrimitive(true),
                        "schema" to type("string"), "description" to str(tr("Virgülle: {0}", names.joinToString(",")))))
                    add(query("token", "string", tr("Başlık gönderemeyen EventSource için token")))
                },
                responses = obj("200" to obj(
                    "description" to str("data: {\"action\":\"create|update|delete\",\"collection\":..,\"record\":{..}}"),
                    "content" to obj("text/event-stream" to obj("schema" to type("string"))),
                )),
            ))
        }

        val users = config.auth.users
        if (users.enabled) {
            val auth = "/auth"
            fun bodyOf(vararg props: Pair<String, String>) = obj("required" to JsonPrimitive(true), "content" to obj("application/json" to obj("schema" to obj(
                "type" to str("object"),
                "properties" to JsonObject(props.associate { (k, t) -> u(k) to type(t) }),
            ))))
            val tokens = obj("type" to str("object"), "properties" to obj(
                "token" to type("string"), "refreshToken" to type("string"), "expiresIn" to type("integer"), "user" to obj("type" to str("object")),
            ))
            if (users.allowSignup) op("$auth/signup", "post", operation(tr("Hesap"), tr("Kayıt ol"), public = true, body = bodyOf("email" to "string", "password" to "string"), responses = ok(tokens, "201")))
            op("$auth/login", "post", operation(tr("Hesap"), tr("Giriş"), public = true, body = bodyOf("email" to "string", "password" to "string"), responses = ok(tokens)))
            op("$auth/refresh", "post", operation(tr("Hesap"), tr("Token yenile"), public = true, body = bodyOf("refreshToken" to "string"), responses = ok(tokens)))
            op("$auth/logout", "post", operation(tr("Hesap"), tr("Çıkış"), public = true, body = bodyOf("refreshToken" to "string"), responses = obj("204" to obj("description" to str(tr("Tamam"))))))
            val sessionList = obj("type" to str("object"), "description" to str("{items: [{id, device, ip, createdAt, lastUsedAt, current}]}"))
            val sessionId = buildJsonArray { add(obj("name" to str("id"), "in" to str("path"), "required" to JsonPrimitive(true), "schema" to type("integer"))) }
            op("$auth/sessions", "get", operation(tr("Hesap"), tr("Açık oturumlarım (cihaz, adres, son kullanım)"), public = false, responses = ok(sessionList)))
            op("$auth/sessions", "delete", operation(tr("Hesap"), tr("Diğer oturumlarımı kapat (bu oturum kalır)"), public = false,
                responses = ok(obj("type" to str("object"), "description" to str("{closed}")))))
            op("$auth/sessions/{id}", "delete", operation(tr("Hesap"), tr("Oturumu kapat; erişim token'ı da hemen geçersiz olur"), public = false, params = sessionId,
                responses = obj("204" to obj("description" to str(tr("Kapatıldı"))))))
            if (config.push.enabled) {
                op("/push/key", "get", operation(tr("Bildirim"), tr("VAPID açık anahtarı (applicationServerKey)"), public = true, responses = ok(obj("type" to str("object")))))
                op("/push/subscribe", "post", operation(tr("Bildirim"), tr("Tarayıcıyı bildirime abone et (PushSubscription.toJSON())"), public = false,
                    body = obj("required" to JsonPrimitive(true), "content" to obj("application/json" to obj("schema" to obj("type" to str("object"))))), responses = ok(obj("type" to str("object")), "201")))
                op("/push/unsubscribe", "post", operation(tr("Bildirim"), tr("Aboneliği kaldır"), public = false, body = bodyOf("endpoint" to "string"), responses = obj("204" to obj("description" to str(tr("Tamam"))))))
                if (caller.isAdmin) {
                    op("/push/send", "post", operation(tr("Bildirim"), tr("Kullanıcıya bildirim gönder"), public = false,
                        body = bodyOf("user" to "integer", "title" to "string", "body" to "string", "url" to "string"), responses = ok(obj("type" to str("object")))))
                }
            }
            if (users.oauth.enabled) {
                op("$auth/oauth/providers", "get", operation(tr("Hesap"), tr("Sosyal giriş sağlayıcıları"), public = true, responses = ok(obj("type" to str("array")))))
                op("$auth/oauth/{provider}", "get", operation(
                    tr("Hesap"), tr("Sosyal girişi başlat (tarayıcıda açın; sağlayıcıya yönlendirir)"), public = true,
                    params = buildJsonArray {
                        add(obj("name" to str("provider"), "in" to str("path"), "required" to JsonPrimitive(true),
                            "schema" to obj("type" to str("string"), "enum" to buildJsonArray { users.oauth.providers.filter { it.enabled }.forEach { add(str(it.provider.name.lowercase())) } })))
                        add(query("redirect", "string", tr("Girişten sonra dönülecek izinli adres; ?code= veya ?error= eklenir")))
                    },
                    responses = obj("302" to obj("description" to str(tr("Sağlayıcıya yönlendirme")))),
                ))
                op("$auth/oauth/exchange", "post", operation(tr("Hesap"), tr("Tek kullanımlık kodu token'a çevir"), public = true, body = bodyOf("code" to "string"), responses = ok(tokens)))
            }
            op("$auth/me", "get", operation(tr("Hesap"), tr("Hesabım (kullanıcı token'ı)"), public = false, responses = ok(obj("type" to str("object")))))
            op("$auth/me", "patch", operation(tr("Hesap"), tr("Hesabımı güncelle (parola için currentPassword)"), public = false, body = bodyOf("password" to "string", "currentPassword" to "string"), responses = ok(obj("type" to str("object")))))
            op("$auth/me/usage", "get", operation(tr("Hesap"), tr("Kota kullanımım"), public = false,
                responses = ok(obj("type" to str("object"), "description" to str(tr("{records:{koleksiyon:{used, limit}}, storage:{usedBytes, limitBytes}, requests:{today, limit, resetsAt}}; sınırsızda limit null"))))))
            if (users.twoFactor.enabled) {
                val challengeNote = tr("Giriş uçları (login, login-code, oauth/exchange) iki adımlı doğrulaması açık kullanıcıda token yerine {twoFactorRequired: true, challenge, expiresIn} döner")
                op("$auth/2fa/verify", "post", operation(tr("Hesap"), tr("İki adımlı giriş: doğrulayıcı veya yedek kod. {0}", challengeNote), public = true,
                    body = bodyOf("challenge" to "string", "code" to "string"), responses = ok(tokens)))
                op("$auth/2fa", "get", operation(tr("Hesap"), tr("İki adımlı doğrulama durumu"), public = false,
                    responses = ok(obj("type" to str("object"), "description" to str(tr("{enabled, required, recoveryCodesLeft}"))))))
                op("$auth/2fa/setup", "post", operation(tr("Hesap"), tr("Anahtar al (onaylanana kadar geçersiz)"), public = false,
                    responses = ok(obj("type" to str("object"), "description" to str(tr("{secret, uri}: uri doğrulayıcı uygulamaya ekler (otpauth://)"))))))
                op("$auth/2fa/enable", "post", operation(tr("Hesap"), tr("Doğrulayıcı koduyla aç"), public = false, body = bodyOf("code" to "string"),
                    responses = ok(obj("type" to str("object"), "description" to str(tr("{recoveryCodes}: bir kez gösterilir"))))))
                op("$auth/2fa/disable", "post", operation(tr("Hesap"), tr("Kapat (doğrulayıcı veya yedek kod)"), public = false, body = bodyOf("code" to "string"),
                    responses = obj("204" to obj("description" to str(tr("Kapatıldı"))))))
                op("$auth/2fa/recovery-codes", "post", operation(tr("Hesap"), tr("Yeni yedek kodlar"), public = false, body = bodyOf("code" to "string"),
                    responses = ok(obj("type" to str("object"), "description" to str("{recoveryCodes}")))))
            }
            if (users.emailLogin && config.email.enabled) {
                op("$auth/request-login-code", "post", operation(tr("Hesap"), tr("E-postayla giriş kodu iste (8 hane, 10 dk)"), public = true, body = bodyOf("email" to "string"),
                    responses = obj("202" to obj("description" to str(tr("Hesap varsa e-posta gönderildi"))))))
                op("$auth/login-code", "post", operation(tr("Hesap"), tr("E-posta koduyla giriş"), public = true, body = bodyOf("email" to "string", "code" to "string"), responses = ok(tokens)))
            }
            if (users.smsLogin && config.phone.sms) {
                op("$auth/request-sms-code", "post", operation(tr("Hesap"), tr("SMS ile giriş kodu iste (6 hane, 10 dk); numara +90… biçiminde"), public = true, body = bodyOf("phone" to "string"),
                    responses = obj("202" to obj("description" to str(tr("Hesap varsa SMS gönderildi"))))))
                op("$auth/sms-code", "post", operation(tr("Hesap"), tr("SMS koduyla giriş"), public = true, body = bodyOf("phone" to "string", "code" to "string"), responses = ok(tokens)))
            }
            if (config.auth.passkeys.enabled) {
                val options = obj("type" to str("object"), "description" to str(tr("navigator.credentials.create/get için publicKey seçenekleri (challenge ve kimlikler base64url)")))
                val credential = obj("required" to JsonPrimitive(true), "content" to obj("application/json" to obj("schema" to obj(
                    "type" to str("object"),
                    "description" to str(tr("{name?, credential: {id, response: {clientDataJSON, attestationObject | authenticatorData, signature}}}; ikili değerler base64url")),
                ))))
                val key = obj("type" to str("object"), "description" to str("{id, name, createdAt, lastUsedAt}"))
                val id = buildJsonArray { add(obj("name" to str("id"), "in" to str("path"), "required" to JsonPrimitive(true), "schema" to type("integer"))) }
                op("$auth/passkeys/register-options", "post", operation(tr("Hesap"), tr("Passkey ekleme seçenekleri (5 dk geçerli)"), public = false, responses = ok(options)))
                op("$auth/passkeys", "post", operation(tr("Hesap"), tr("Passkey ekle (tarayıcının yanıtı)"), public = false, body = credential, responses = ok(key, "201")))
                op("$auth/passkeys", "get", operation(tr("Hesap"), tr("Passkey'lerim"), public = false, responses = ok(obj("type" to str("object"), "description" to str("{items: [{id, name, createdAt, lastUsedAt}]}")))))
                op("$auth/passkeys/{id}", "delete", operation(tr("Hesap"), tr("Passkey'i sil"), public = false, params = id, responses = obj("204" to obj("description" to str(tr("Silindi"))))))
                op("$auth/passkeys/login-options", "post", operation(tr("Hesap"), tr("Passkey ile giriş seçenekleri (tarayıcı cihazdaki passkey'i önerir)"), public = true, responses = ok(options)))
                op("$auth/passkeys/login", "post", operation(tr("Hesap"), tr("Passkey ile giriş; iki adımlı doğrulamanın ikinci adımı istenmez"), public = true, body = credential, responses = ok(tokens)))
            }
            if (users.teams.enabled) {
                val tag = tr("Takımlar")
                fun p(vararg names: String) = buildJsonArray { names.forEach { add(obj("name" to str(it), "in" to str("path"), "required" to JsonPrimitive(true), "schema" to type("integer"))) } }
                val team = obj("type" to str("object"), "description" to str("{id, name, role, memberCount, members: [{user, email, role}]}"))
                op("/teams", "get", operation(tag, tr("Takımlarım (yönetici: hepsi)"), public = false, responses = ok(obj("type" to str("object"), "description" to str("{items: [{id, name, role, memberCount}]}")))))
                op("/teams", "post", operation(tag, tr("Takım kur (kuran sahibi olur)"), public = false, body = bodyOf("name" to "string"), responses = ok(team, "201")))
                op("/teams/{id}", "get", operation(tag, tr("Takım ve üyeleri (yalnızca üyeler)"), public = false, params = p("id"), responses = ok(team)))
                op("/teams/{id}", "patch", operation(tag, tr("Takımı yeniden adlandır (sahip)"), public = false, params = p("id"), body = bodyOf("name" to "string"), responses = ok(team)))
                op("/teams/{id}", "delete", operation(tag, tr("Takımı sil (sahip); takım alanı bağlı kayıtlar onDelete'e göre"), public = false, params = p("id"),
                    responses = obj("204" to obj("description" to str(tr("Silindi"))))))
                op("/teams/{id}/members", "post", operation(tag, tr("Üye ekle (sahip): email veya user, role owner/member"), public = false, params = p("id"),
                    body = bodyOf("email" to "string", "user" to "integer", "role" to "string"), responses = ok(team, "201")))
                op("/teams/{id}/members/{user}", "patch", operation(tag, tr("Üyenin rolü (sahip)"), public = false, params = p("id", "user"), body = bodyOf("role" to "string"), responses = ok(team)))
                op("/teams/{id}/members/{user}", "delete", operation(tag, tr("Üyeyi çıkar (sahip) veya takımdan ayrıl (kendisi)"), public = false, params = p("id", "user"),
                    responses = obj("204" to obj("description" to str(tr("Çıkarıldı"))))))
            }
            if (config.email.enabled) {
                val accepted = obj("202" to obj("description" to str(tr("Hesap varsa e-posta gönderildi"))))
                op("$auth/request-verification", "post", operation(tr("Hesap"), tr("Doğrulama e-postası"), public = true, body = bodyOf("email" to "string"), responses = accepted))
                op("$auth/confirm-verification", "post", operation(tr("Hesap"), tr("E-postayı doğrula"), public = true, body = bodyOf("token" to "string"), responses = ok(obj("type" to str("object")))))
                op("$auth/request-reset", "post", operation(tr("Hesap"), tr("Parolamı unuttum"), public = true, body = bodyOf("email" to "string"), responses = accepted))
                op("$auth/confirm-reset", "post", operation(tr("Hesap"), tr("Yeni parola"), public = true, body = bodyOf("token" to "string", "password" to "string"), responses = obj("204" to obj("description" to str(tr("Parola değişti"))))))
            }
            if (caller.isAdmin) {
                op("$auth/users", "get", operation(tr("Hesap"), tr("Kullanıcılar (yönetici)"), public = false, responses = ok(obj("type" to str("object")))))
                op("$auth/users", "post", operation(tr("Hesap"), tr("Kullanıcı aç (yönetici)"), public = false, body = bodyOf("email" to "string", "password" to "string", "role" to "string"), responses = ok(obj("type" to str("object")), "201")))
            }
        }

        for (v in ViewService(config, emptyMap()).visible(caller)) {
            val params = buildJsonArray {
                if (v.report == null) {
                    add(query("page", "integer", tr("Sayfa (1'den)")))
                    add(query("perPage", "integer", tr("Sayfa başına kayıt (1-500, varsayılan 50)")))
                }
                add(query("format", "string", tr("csv: Excel uyumlu dosya")))
            }
            val summary = if (v.report != null) tr("Rapor: {0}", v.label) else tr("Görünüm: {0}", v.label)
            op("/views/${v.name}", "get", operation(tr("Görünümler"), summary, public = !secured || v.access == Rule.PUBLIC, params = params, responses = ok(obj("type" to str("object")))))
        }

        for (r in scriptRoutes) {
            val visibleRoute = when {
                caller.isAdmin -> true
                r.role != null -> (caller as? Caller.User)?.role == r.role
                r.access == Rule.PUBLIC -> true
                r.access == Rule.AUTH -> caller !is Caller.Anonymous
                else -> false
            }
            if (!visibleRoute) continue
            val path = r.path.split('/').joinToString("/") { if (it.startsWith(":")) "{${it.drop(1)}}" else it }
            val params = buildJsonArray {
                r.path.split('/').filter { it.startsWith(":") }.forEach {
                    add(obj("name" to str(it.drop(1)), "in" to str("path"), "required" to JsonPrimitive(true), "schema" to type("string")))
                }
            }
            op(path, r.method.lowercase(), operation("Script", tr("Script ucu ({0})", r.source), public = r.access == Rule.PUBLIC || !secured, params = params,
                responses = ok(obj("type" to str("object")))))
        }

        if (caller.isAdmin) {
            op("/_metrics", "get", operation(tr("Sunucu"), tr("Metrikler (yönetici; ?format=prometheus)"), public = false, responses = ok(obj("type" to str("object")))))
            op("/_schema", "get", operation(tr("Sunucu"), tr("Koleksiyon tanımları (yönetici)"), public = false, responses = ok(obj("type" to str("array")))))
            op("/_sql", "post", operation(tr("Sunucu"), tr("Salt okunur SQL sorgusu (yönetici; tek SELECT, en çok 1000 satır, 5 sn)"), public = false,
                body = obj("required" to JsonPrimitive(true), "content" to obj("application/json" to obj("schema" to obj(
                    "type" to str("object"), "required" to buildJsonArray { add(str("query")) },
                    "properties" to obj("query" to type("string"), "limit" to type("integer")),
                )))),
                responses = ok(obj("type" to str("object"), "description" to str(tr("{columns, rows, truncated, ms}"))))))
            if (config.broadcasts) {
                op("/_broadcast", "post", operation(tr("Sunucu"), tr("Kullanıcılara toplu e-posta / web push (yönetici; kuyrukta, /_jobs'tan izlenir ve iptal edilir)"), public = false,
                    body = obj("required" to JsonPrimitive(true), "content" to obj("application/json" to obj("schema" to obj(
                        "type" to str("object"), "required" to buildJsonArray { add(str("subject")); add(str("text")) },
                        "properties" to obj(
                            "channel" to obj("type" to str("string"), "enum" to buildJsonArray { add(str("email")); add(str("push")) }),
                            "subject" to type("string"), "text" to type("string"), "html" to type("string"), "url" to type("string"),
                            "role" to type("string"), "team" to type("integer"), "verifiedOnly" to type("boolean"),
                            "where" to obj("type" to str("string"), "description" to str(tr("_users alanlarına liste filtresi, örn. created_at[gt]=2026-01-01"))),
                            "perMinute" to type("integer"), "dryRun" to type("boolean"),
                        ),
                    )))),
                    responses = ok(obj("type" to str("object"), "description" to str(tr("dryRun: {recipients}; değilse 202 {job, recipients}"))))))
            }
            if (config.collections.isNotEmpty()) {
                op("/_sample/{collection}", "post", operation(tr("Sunucu"), tr("Örnek kayıt üret (yönetici; 1-500, kurallara uyar, olay üretmez)"), public = false,
                    params = buildJsonArray {
                        add(obj("name" to str("collection"), "in" to str("path"), "required" to JsonPrimitive(true),
                            "schema" to obj("type" to str("string"), "enum" to buildJsonArray { config.collections.forEach { add(str(it.name)) } })))
                    },
                    body = obj("content" to obj("application/json" to obj("schema" to obj("type" to str("object"), "properties" to obj("count" to type("integer")))))),
                    responses = ok(obj("type" to str("object"), "description" to str(tr("{created}"))), "201")))
            }
        }

        return buildJsonObject {
            put("openapi", str("3.1.0"))
            put("info", obj(
                "title" to str(config.name),
                "version" to str("v${ConfigMigrations.CURRENT}"),
                "description" to str(tr("DroidServer API. Hatalar: {status, message, details}. Bu doküman isteyenin yetkisine göre üretilir.")),
            ))
            put("servers", buildJsonArray { add(obj("url" to str(config.server.basePath.trimEnd('/').ifEmpty { "/" }))) })
            put("paths", JsonObject(paths.mapValues { JsonObject(it.value) }))
            put("components", obj(
                "schemas" to JsonObject(schemas),
                "securitySchemes" to obj("bearer" to obj("type" to str("http"), "scheme" to str("bearer"), "description" to str(
                    tr("API anahtarı, yönetici JWT'si veya kullanıcı token'ı: Authorization: Bearer <token>"),
                ))),
            ))
        }
    }

    private fun allowed(c: CollectionConfig, op: Rules.Op, caller: Caller) = runCatching { Access.scope(c, op, caller) }.isSuccess

    private fun recordSchema(c: CollectionConfig) = obj(
        "type" to str("object"),
        "properties" to ApiNames.of(c).out(
            JsonObject(
                linkedMapOf<String, JsonElement>("id" to obj("type" to str("integer"), "readOnly" to JsonPrimitive(true))) +
                    c.fields.associate { it.name to fieldSchema(it) } +
                    mapOf(
                        "created_at" to obj("type" to str("string"), "format" to str("date-time"), "readOnly" to JsonPrimitive(true)),
                        "updated_at" to obj("type" to str("string"), "format" to str("date-time"), "readOnly" to JsonPrimitive(true)),
                    ),
            ),
        ),
    )

    private fun inputSchema(c: CollectionConfig) = buildJsonObject {
        put("type", str("object"))
        put("properties", JsonObject(c.fields.filter { it.type != FieldType.FILE && it.computed == null }.associate { it.name to fieldSchema(it) }))
        val required = c.fields.filter { it.required && it.default == null && it.type != FieldType.FILE && it.name != c.ownerField && it.computed == null }
        if (required.isNotEmpty()) put("required", JsonArray(required.map { str(it.name) }))
    }

    private fun fieldSchema(f: FieldConfig): JsonObject = buildJsonObject {
        when (f.type) {
            FieldType.TEXT -> put("type", str("string"))
            FieldType.INTEGER -> put("type", str("integer"))
            FieldType.REAL -> put("type", str("number"))
            FieldType.BOOLEAN -> put("type", str("boolean"))
            FieldType.DATETIME -> { put("type", str("string")); put("format", str("date-time")) }
            FieldType.JSON -> put("description", str(tr("Serbest JSON")))
            FieldType.RELATION -> {
                put("type", str("integer"))
                put("description", str(tr("{0} kimliği", if (f.collection == SystemCollections.USERS) tr("Kullanıcı") else tr("'{0}' kaydı", f.collection))))
            }
            FieldType.SELECT -> { put("type", str("string")); put("enum", JsonArray(f.options.orEmpty().map(::str))) }
            FieldType.MULTISELECT -> {
                put("type", str("array"))
                put("items", obj("type" to str("string"), "enum" to JsonArray(f.options.orEmpty().map(::str))))
                f.minLength?.let { put("minItems", JsonPrimitive(it)) }
                f.maxLength?.let { put("maxItems", JsonPrimitive(it)) }
            }
            FieldType.FILE -> if (f.isMultiple) {
                put("type", str("array")); put("items", type("string")); put("description", str(tr("Dosya adları; yükleme multipart/form-data")))
            } else {
                put("type", str("string")); put("description", str(tr("Dosya adı; yükleme multipart/form-data")))
            }
        }
        f.default?.let { put("default", it) }
        if (f.unique) put("x-unique", JsonPrimitive(true))
        f.visibility?.let { put("x-visibility", str(it.name.lowercase())) }
        if (f.type == FieldType.TEXT) {
            f.minLength?.let { put("minLength", JsonPrimitive(it)) }
            f.maxLength?.let { put("maxLength", JsonPrimitive(it)) }
            f.pattern?.let { put("pattern", str(it)) }
            when (f.format) {
                TextFormat.EMAIL -> put("format", str("email"))
                TextFormat.URL -> put("format", str("uri"))
                null -> {}
            }
        }
        f.min?.let { put("minimum", JsonPrimitive(it)) }
        f.max?.let { put("maximum", JsonPrimitive(it)) }
        f.computed?.let { put("readOnly", JsonPrimitive(true)); put("x-computed", str(it)) }
    }

    private fun schemaType(f: FieldConfig) = when (f.type) {
        FieldType.INTEGER, FieldType.RELATION -> "integer"
        FieldType.REAL -> "number"
        FieldType.BOOLEAN -> "boolean"
        else -> "string"
    }

    private fun body(c: CollectionConfig) = buildJsonObject {
        put("required", JsonPrimitive(true))
        put("content", buildJsonObject {
            put("application/json", obj("schema" to ref("${c.name}Input")))
            if (c.fields.any { it.type == FieldType.FILE }) {
                put("multipart/form-data", obj("schema" to obj("type" to str("object"), "description" to str(tr("Alanlar form değeri, dosyalar dosya parçası")))))
            }
        })
    }

    private fun operation(
        tag: String,
        summary: String,
        public: Boolean,
        params: JsonArray? = null,
        body: JsonObject? = null,
        responses: JsonObject,
        rule: Rule? = null,
    ) = buildJsonObject {
        put("tags", buildJsonArray { add(str(tag)) })
        put("summary", str(summary))
        rule?.let { put("x-rule", str(it.name.lowercase())) }
        if (!public) put("security", buildJsonArray { add(obj("bearer" to JsonArray(emptyList()))) })
        params?.takeIf { it.isNotEmpty() }?.let { put("parameters", it) }
        body?.let { put("requestBody", it) }
        put("responses", JsonObject(responses + ("default" to obj(
            "description" to str(tr("Hata")),
            "content" to obj("application/json" to obj("schema" to ref("Error"))),
        ))))
    }

    private fun ok(schema: JsonObject, code: String = "200") =
        obj(code to obj("description" to str(tr("Başarılı")), "content" to obj("application/json" to obj("schema" to schema))))

    private fun query(name: String, t: String, description: String) =
        obj("name" to str(name), "in" to str("query"), "schema" to type(t), "description" to str(description))

    private fun ref(name: String) = obj("\$ref" to str("#/components/schemas/$name"))
    private fun type(t: String) = obj("type" to str(t))
    private fun str(s: String) = JsonPrimitive(s)
    private fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(linkedMapOf(*pairs))
}
