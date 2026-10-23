package dev.droidserver.core.admin

import dev.droidserver.core.api.ApiException
import dev.droidserver.core.api.origin
import dev.droidserver.core.audit.AuditLog
import dev.droidserver.core.auth.PasskeyService
import dev.droidserver.core.auth.Totp
import dev.droidserver.core.api.ApiNames
import dev.droidserver.core.api.ApiPaths
import dev.droidserver.core.api.CollectionEndpoint
import dev.droidserver.core.api.Endpoint
import dev.droidserver.core.auth.Caller
import dev.droidserver.core.backup.BackupInfo
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.config.SystemCollections
import dev.droidserver.core.i18n.I18n
import dev.droidserver.core.i18n.tr
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.defaultForFileExtension
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.writeStringUtf8
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Web yönetim paneli: `/_admin/` altında tek sayfalık uygulama (core kaynaklarında `admin/`) ve panele özgü uçlar.
 * Panel kayıt, kullanıcı ve metrik işleri için normal API'yi (`/api/...`) eşleştirmeden aldığı yönetici token'ıyla
 * kullanır.
 *
 *   POST /_admin/api/pair            {code}  → {token, expiresInSec}
 *   POST /_admin/api/logout
 *   GET  /_admin/api/info                    → sunucu adı, API yolu, özellikler
 *   GET  /_admin/api/config                  → {text}
 *   POST /_admin/api/config/plan     {text}  → doğrulama ve şema planı
 *   POST /_admin/api/config/deploy   {text}  → 202 (sonuç loglarda)
 *   GET  /_admin/api/logs?after=N            → {next, lines}
 *   GET  /_admin/api/logs/stream             → text/event-stream (canlı)
 *   GET  /_admin/api/backups                 → liste;  POST: yeni yedek;  GET /{ad}: indir
 *   POST /_admin/api/passkeys/login-options  → giriş seçenekleri;  POST /_admin/api/passkeys/login → {token, expiresInSec}
 *   GET|POST /_admin/api/passkeys, POST /_admin/api/passkeys/register-options, DELETE /_admin/api/passkeys/{id}
 *   GET  /_admin/api/replica                 → canlı kopya rolü, devralma ayarları ve eşitleme durumu
 *   POST /_admin/api/replica/takeover        → 202 (kopya ana sunucu olur; sonuç loglarda)
 */
internal fun Route.adminPanel(
    config: ServerConfig,
    sessions: AdminSessions,
    host: AdminHost?,
    audit: AuditLog?,
    /** Panel girişi için passkey'ler (`auth.passkeys` açıksa). */
    passkeys: PasskeyService?,
    caller: suspend RoutingContext.() -> Caller,
) {
    fun RoutingContext.ip() = origin(call.request).key
    suspend fun RoutingContext.admin() {
        val who = caller()
        if (!who.isAdmin) throw ApiException(401, tr("Panel oturumu gerekli; telefondaki eşleştirme koduyla giriş yapın"))
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
    }
    fun requireHost() = host ?: throw ApiException(503, tr("Bu işlem uygulama içinden çalışan sunucuda kullanılabilir"))
    val totpSecret = config.adminPanel.totpSecret?.takeIf { it.isNotBlank() }
    val lastTotpStep = java.util.concurrent.atomic.AtomicLong(-1)

    route("/_admin") {
        get { call.respondRedirect("/_admin/") }
        route("/api") {
            // Girişten önce: panel doğrulayıcı kodu da istiyor mu.
            get("/login") {
                val passkey = passkeys != null && withContext(Dispatchers.IO) { passkeys.any(null) }
                call.respond(buildJsonObject { put("totp", JsonPrimitive(totpSecret != null)); put("passkey", JsonPrimitive(passkey)) })
            }
            if (passkeys != null) {
                // Passkey ile giriş eşleştirme kodunun ve doğrulayıcı kodunun yerine geçer (cihaz + kilit ekranı).
                post("/passkeys/login-options") { call.respond(withContext(Dispatchers.IO) { passkeys.loginOptions(null) }) }
                post("/passkeys/login") {
                    val body = call.receive<JsonObject>()
                    try {
                        withContext(Dispatchers.IO) { passkeys.login(body) }
                    } catch (e: ApiException) {
                        audit?.record("panel", "panel.login.failed", detail = "passkey", ip = ip())
                        throw e
                    }
                    audit?.record("panel", "panel.login", detail = "passkey", ip = ip())
                    call.respond(buildJsonObject {
                        put("token", JsonPrimitive(sessions.open(config.adminPanel.sessionHours)))
                        put("expiresInSec", JsonPrimitive(config.adminPanel.sessionHours * 3600))
                    })
                }
                route("/passkeys") {
                    get {
                        admin()
                        call.respond(withContext(Dispatchers.IO) { passkeys.list(null) })
                    }
                    post("/register-options") {
                        admin()
                        call.respond(withContext(Dispatchers.IO) { passkeys.registrationOptions(null, "admin", tr("{0} yöneticisi", config.name)) })
                    }
                    post {
                        admin()
                        val body = call.receive<JsonObject>()
                        val key = withContext(Dispatchers.IO) { passkeys.register(null, body) }
                        audit?.record("panel", "panel.passkey.add", key["name"]?.toString()?.trim('"'), ip = ip())
                        call.respond(HttpStatusCode.Created, key)
                    }
                    delete("/{id}") {
                        admin()
                        val id = call.parameters["id"]?.toLongOrNull() ?: throw ApiException(404, tr("Passkey bulunamadı"))
                        withContext(Dispatchers.IO) { passkeys.delete(null, id) }
                        audit?.record("panel", "panel.passkey.delete", id.toString(), ip = ip())
                        call.respond(HttpStatusCode.NoContent)
                    }
                }
            }
            post("/pair") {
                val body = call.receive<JsonObject>()
                val code = (body["code"] as? JsonPrimitive)?.content.orEmpty()
                val totp = (body["totp"] as? JsonPrimitive)?.content.orEmpty()
                val second = totpSecret?.let { secret ->
                    {
                        // Aynı doğrulayıcı kodu ikinci kez geçmez (oturum çalınsa da kod yeniden kullanılamaz).
                        Totp.match(secret, totp, Instant.now(), lastTotpStep.get().takeIf { it >= 0 })?.also(lastTotpStep::set) != null
                    }
                }
                val token = sessions.pair(code, config.adminPanel.sessionHours, second)
                    .also { audit?.record("panel", if (it != null) "panel.login" else "panel.login.failed", ip = ip()) }
                    ?: throw ApiException(401, if (totpSecret != null) tr("Kod veya doğrulayıcı kodu yanlış; gerekirse telefonda yeni kod oluşturun") else tr("Kod yanlış veya süresi dolmuş; telefonda yeni kod oluşturun"))
                call.respond(buildJsonObject {
                    put("token", JsonPrimitive(token))
                    put("expiresInSec", JsonPrimitive(config.adminPanel.sessionHours * 3600))
                })
            }
            post("/logout") {
                call.request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim()?.let(sessions::revoke)
                call.respond(HttpStatusCode.NoContent)
            }
            get("/info") {
                admin()
                call.respond(buildJsonObject {
                    put("name", JsonPrimitive(config.name))
                    put("basePath", JsonPrimitive(config.server.basePath))
                    put("users", JsonPrimitive(config.auth.users.enabled))
                    put("roles", JsonArray(config.auth.users.roles.map { r -> buildJsonObject { put("name", JsonPrimitive(r.name)); put("title", JsonPrimitive(r.label)) } }))
                    put("host", JsonPrimitive(host != null))
                    put("jobs", JsonPrimitive(config.scripts.enabled))
                    put("views", JsonPrimitive(config.views.isNotEmpty()))
                    put("realtime", JsonPrimitive(config.realtime.enabled))
                    put("teams", JsonPrimitive(config.auth.users.enabled && config.auth.users.teams.enabled))
                    put("collections", JsonArray(config.collections.map { JsonPrimitive(it.name) }))
                    // Uzman modu: uçların etkin yolları (kapalıysa null) ve sistem alanlarının API adları.
                    val api = ApiPaths(config)
                    fun path(p: String?) = p?.let(::JsonPrimitive) ?: JsonNull
                    put("paths", buildJsonObject {
                        for (e in listOf(
                            Endpoint.SCHEMA, Endpoint.METRICS, Endpoint.OPENAPI, Endpoint.REALTIME, Endpoint.USERS_LIST, Endpoint.USERS_UPDATE, Endpoint.USERS_DELETE, Endpoint.VIEWS, Endpoint.VIEW,
                            Endpoint.USERS_SESSIONS, Endpoint.USERS_SESSIONS_CLOSE,
                            Endpoint.CLI, Endpoint.MQTT, Endpoint.MQTT_PUBLISH, Endpoint.MQTT_SCENE, Endpoint.SENSORS, Endpoint.AI_STATUS, Endpoint.AI_REINDEX, Endpoint.APPS, Endpoint.APP_DEPLOY,
                            Endpoint.AUTOMATIONS, Endpoint.AUTOMATION_RUN, Endpoint.TEAMS, Endpoint.TEAMS_CREATE, Endpoint.TEAM, Endpoint.TEAM_DELETE,
                            Endpoint.TEAM_ADD_MEMBER, Endpoint.TEAM_UPDATE_MEMBER, Endpoint.TEAM_REMOVE_MEMBER,
                        )) {
                            put(e.key, path(api.path(e)))
                        }
                        put("collections", buildJsonObject {
                            for (c in config.collections) put(c.name, buildJsonObject { CollectionEndpoint.entries.forEach { put(it.key, path(api.path(c, it))) } })
                        })
                    })
                    put("names", buildJsonObject {
                        fun names(n: ApiNames, keys: List<String>) = buildJsonObject { keys.forEach { put(it, path(n.api(it))) } }
                        for (c in config.collections) put(c.name, names(ApiNames.of(c), ApiNames.COLLECTION_FIELDS))
                        put(SystemCollections.USERS, names(ApiNames.ofUsers(config.auth.users), ApiNames.USER_FIELDS))
                    })
                })
            }
            get("/config") {
                admin()
                val text = withContext(Dispatchers.IO) { requireHost().configText() }
                call.respond(buildJsonObject { put("text", JsonPrimitive(text.orEmpty())) })
            }
            post("/config/plan") {
                admin()
                val text = call.receive<JsonObject>().text()
                call.respond(withContext(Dispatchers.IO) { requireHost().plan(text) })
            }
            post("/config/deploy") {
                admin()
                val text = call.receive<JsonObject>().text()
                try {
                    withContext(Dispatchers.IO) { requireHost().deploy(text) }
                    audit?.record("panel", "config.save", detail = tr("web panelinden kaydedildi; deploy başladı"), ip = ip())
                } catch (e: IllegalArgumentException) {
                    throw ApiException(400, e.message ?: tr("Konfigürasyon geçersiz"))
                }
                call.respond(HttpStatusCode.Accepted, buildJsonObject { put("message", JsonPrimitive(tr("Deploy başladı; sonuç loglarda"))) })
            }
            get("/logs") {
                admin()
                val after = call.request.queryParameters["after"]?.toLongOrNull() ?: 0L
                val (next, lines) = requireHost().logs(after)
                call.respond(buildJsonObject {
                    put("next", JsonPrimitive(next))
                    put("lines", JsonArray(lines.map(::JsonPrimitive)))
                })
            }
            get("/logs/stream") {
                admin()
                val h = requireHost()
                call.response.headers.append("X-Accel-Buffering", "no")
                call.respondBytesWriter(ContentType.Text.EventStream) {
                    var after = call.request.queryParameters["after"]?.toLongOrNull() ?: 0L
                    // Bağlantı en çok 30 dk açık kalır (unutulan sekmeler); panel bitince yeniden bağlanır.
                    val until = System.currentTimeMillis() + STREAM_MAX_MS
                    while (System.currentTimeMillis() < until) {
                        val (next, lines) = h.logs(after)
                        after = next
                        val text = if (lines.isEmpty()) tr(": canlı\n\n") else lines.joinToString("") { line ->
                            line.lines().joinToString("") { "data: $it\n" } + "\n"
                        }
                        writeStringUtf8(text)
                        flush()
                        delay(1_000)
                    }
                }
            }
            get("/replica") {
                admin()
                val r = config.replication
                call.respond(buildJsonObject {
                    put("source", JsonPrimitive(r.source))
                    put("keepDays", JsonPrimitive(r.keepDays))
                    put("primary", r.replicaOf?.url?.let(::JsonPrimitive) ?: JsonNull)
                    put("formerPrimary", r.formerPrimary?.url?.let(::JsonPrimitive) ?: JsonNull)
                    put("failover", buildJsonObject {
                        put("auto", JsonPrimitive(r.failover.auto))
                        put("afterSec", JsonPrimitive(r.failover.afterSec))
                        put("witness", r.failover.witness?.let(::JsonPrimitive) ?: JsonNull)
                        put("failback", JsonPrimitive(r.failover.failback))
                    })
                    host?.replica()?.let { s ->
                        put("status", buildJsonObject {
                            put("phase", JsonPrimitive(s.phase.name))
                            put("phaseTitle", JsonPrimitive(s.phase.title))
                            put("appliedSeq", JsonPrimitive(s.appliedSeq))
                            put("latestSeq", JsonPrimitive(s.latestSeq))
                            put("lastContact", s.lastContact?.toString()?.let(::JsonPrimitive) ?: JsonNull)
                            put("lastApplied", s.lastApplied?.toString()?.let(::JsonPrimitive) ?: JsonNull)
                            put("error", s.error?.let(::JsonPrimitive) ?: JsonNull)
                            put("progress", s.progress?.let(::JsonPrimitive) ?: JsonNull)
                            put("takeoverAt", s.takeoverAt?.toString()?.let(::JsonPrimitive) ?: JsonNull)
                            put("failoverNote", s.failoverNote?.let(::JsonPrimitive) ?: JsonNull)
                        })
                    }
                })
            }
            post("/replica/takeover") {
                admin()
                val primary = config.replication.replicaOf ?: throw ApiException(409, tr("Bu sunucu kopya değil"))
                withContext(Dispatchers.IO) { requireHost().takeover() }
                audit?.record("panel", "replica.promote", primary.url, ip = ip())
                call.respond(HttpStatusCode.Accepted, buildJsonObject { put("message", JsonPrimitive(tr("Devralma başladı; sonuç loglarda"))) })
            }
            route("/backups") {
                get {
                    admin()
                    val list = withContext(Dispatchers.IO) { requireHost().backups() }
                    call.respond(JsonArray(list.map(::backupJson)))
                }
                post {
                    admin()
                    val info = withContext(Dispatchers.IO) { requireHost().createBackup() }
                    call.respond(HttpStatusCode.Created, backupJson(info))
                }
                get("/{name}") {
                    admin()
                    val name = call.parameters["name"].orEmpty()
                    val file = withContext(Dispatchers.IO) { requireHost().backups().firstOrNull { it.name == name }?.file }
                        ?: throw ApiException(404, tr("Yedek bulunamadı"))
                    call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"${file.name}\"")
                    call.respond(LocalFileContent(file, ContentType.Application.Zip))
                }
            }
        }
        get("/i18n.js") {
            // Panelin dili sunucunun dilidir (uygulama ayarı); katalog index.html'den önce yüklenir.
            call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
            call.respondText(I18n.panelScript(), ContentType.Text.JavaScript)
        }
        get("/{path...}") {
            val path = call.parameters.getAll("path").orEmpty().joinToString("/").ifEmpty { "index.html" }
            val asset = Assets.get(path) ?: Assets.get("index.html") ?: throw ApiException(404, tr("Panel dosyaları bulunamadı"))
            // Panel yalnızca kendi kaynaklarını yükler; başka sitenin çerçevesine konamaz.
            call.response.headers.append(
                "Content-Security-Policy",
                "default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; connect-src 'self'; frame-ancestors 'none'",
            )
            call.response.headers.append("Referrer-Policy", "no-referrer")
            call.respondBytes(asset.second, asset.first)
        }
    }
}

private const val STREAM_MAX_MS = 30 * 60_000L

private fun JsonObject.text(): String = (this["text"] as? JsonPrimitive)?.content ?: throw ApiException(400, tr("text gerekli"))

private fun backupJson(b: BackupInfo) = buildJsonObject {
    put("name", JsonPrimitive(b.name))
    put("kind", JsonPrimitive(b.kind.title))
    put("size", JsonPrimitive(b.size))
    put("createdAt", JsonPrimitive(b.createdAt.toString()))
}

/** `admin/` kaynakları (uygulama paketinde core'un jar'ından gelir); bir kez okunur. */
private object Assets {
    private val cache = ConcurrentHashMap<String, Pair<ContentType, ByteArray>>()
    private val NAME = Regex("^[a-z0-9_-]+\\.(html|js|css|svg|png|ico)$")

    fun get(name: String): Pair<ContentType, ByteArray>? {
        if (!NAME.matches(name)) return null
        cache[name]?.let { return it }
        val bytes = Assets::class.java.classLoader?.getResourceAsStream("admin/$name")?.use { it.readBytes() } ?: return null
        val type = ContentType.defaultForFileExtension(name.substringAfterLast('.'))
        return (type to bytes).also { cache[name] = it }
    }
}
