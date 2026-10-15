package dev.droidserver.core.api

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import dev.droidserver.core.admin.AdminHost
import dev.droidserver.core.admin.AdminSessions
import dev.droidserver.core.admin.adminPanel
import dev.droidserver.core.apps.AppsHost
import dev.droidserver.core.apps.GitWebhook
import io.ktor.client.HttpClient
import io.ktor.server.application.ApplicationStopped
import dev.droidserver.core.alerts.AlertKind
import dev.droidserver.core.alerts.HealthMonitor
import dev.droidserver.core.audit.AuditLog
import dev.droidserver.core.ai.AiChat
import dev.droidserver.core.ai.AiHost
import dev.droidserver.core.ai.Embedder
import dev.droidserver.core.ai.HttpEmbedder
import dev.droidserver.core.ai.SemanticIndex
import dev.droidserver.core.auth.SmsGuard
import dev.droidserver.core.auth.TeamService
import dev.droidserver.core.webdav.DavRoot
import dev.droidserver.core.webdav.WebDav
import dev.droidserver.core.automation.AutomationEngine
import dev.droidserver.core.automation.AutomationHost
import dev.droidserver.core.automation.AutomationOutputs
import dev.droidserver.core.graphql.GqlBackend
import dev.droidserver.core.graphql.GqlOps
import dev.droidserver.core.graphql.GraphQL
import dev.droidserver.core.i18n.tr
import dev.droidserver.core.jobs.Job
import dev.droidserver.core.phone.PhoneService
import dev.droidserver.core.realtime.ChannelHub
import dev.droidserver.core.replication.ChangeLog
import dev.droidserver.core.replication.FailoverPort
import dev.droidserver.core.mqtt.MqttBroker
import dev.droidserver.core.mqtt.MqttHost
import dev.droidserver.core.mqtt.MqttIngest
import dev.droidserver.core.jobs.JobQueue
import dev.droidserver.core.jobs.JobRunner
import dev.droidserver.core.script.JobSink
import dev.droidserver.core.auth.ApiKeyUsage
import dev.droidserver.core.auth.ApiKeys
import dev.droidserver.core.auth.Caller
import dev.droidserver.core.auth.DailyCounter
import dev.droidserver.core.auth.QuotaService
import dev.droidserver.core.push.PushService
import dev.droidserver.core.push.WebPushSender
import dev.droidserver.core.auth.OAuthEndpoints
import dev.droidserver.core.auth.OAuthException
import dev.droidserver.core.auth.OAuthService
import dev.droidserver.core.config.OAuthProvider
import dev.droidserver.core.auth.UserService
import dev.droidserver.core.data.DataTransfer
import dev.droidserver.core.data.ImportMode
import dev.droidserver.core.data.TransferFormat
import dev.droidserver.core.config.AuthMode
import dev.droidserver.core.config.CollectionConfig
import dev.droidserver.core.config.ConfigJson
import dev.droidserver.core.config.Rules
import dev.droidserver.core.config.Rule
import dev.droidserver.core.script.HookEvent
import dev.droidserver.core.script.ScriptDb
import dev.droidserver.core.script.ScriptException
import dev.droidserver.core.script.ScriptRequest
import dev.droidserver.core.script.ScriptRoute
import dev.droidserver.core.script.ScriptRuntime
import dev.droidserver.core.security.FieldEncryption
import io.ktor.server.request.receiveText
import dev.droidserver.core.config.FieldConfig
import dev.droidserver.core.config.FieldDefaults
import dev.droidserver.core.config.FieldType
import dev.droidserver.core.config.OnDelete
import dev.droidserver.core.config.FileLimits
import dev.droidserver.core.config.StaticConfig
import dev.droidserver.core.config.StrictJson
import dev.droidserver.core.files.FileStore
import dev.droidserver.core.files.ImageResizer
import dev.droidserver.core.files.MimeTypes
import dev.droidserver.core.files.StaticSite
import dev.droidserver.core.files.Upload
import dev.droidserver.core.metrics.Metrics
import dev.droidserver.core.metrics.MetricsPlugin
import dev.droidserver.core.mail.MailSender
import dev.droidserver.core.mail.Mailer
import dev.droidserver.core.realtime.RealtimeHub
import dev.droidserver.core.webhook.WebhookDispatcher
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import dev.droidserver.core.realtime.RecordEvent
import io.ktor.server.application.ApplicationStopping
import io.ktor.http.ContentType
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.defaultForFile
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.request.contentType
import io.ktor.server.request.path
import io.ktor.server.request.receiveMultipart
import io.ktor.http.withCharset
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import java.io.ByteArrayOutputStream
import java.io.File
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.config.SystemCollections
import dev.droidserver.core.db.DatabaseUnavailableException
import dev.droidserver.core.db.SqlDriver
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.methodoverride.XHttpMethodOverride
import io.ktor.server.request.httpMethod
import io.ktor.server.plugins.compression.deflate
import io.ktor.server.plugins.compression.excludeContentType
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.matchContentType
import io.ktor.server.plugins.compression.minimumSize
import io.ktor.server.plugins.conditionalheaders.ConditionalHeaders
import io.ktor.http.content.EntityTagVersion
import io.ktor.http.content.TextContent
import io.ktor.http.content.ByteArrayContent
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.http.Cookie
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date

/**
 * Konfigürasyondan tüm HTTP katmanını kurar.
 *
 * Uçlar (basePath varsayılan /api):
 *   GET    /api/health
 *   GET    /api/_schema                          (yönetici)
 *   GET    /api/_metrics                         (yönetici; ?format=prometheus)
 *   GET    /api/collections/{ad}/records         ?page=&perPage=&sort=-created_at&fields=a,b&expand=ilişki&<alan>[op]=<değer>
 *   GET    /api/collections/{ad}/records/{id}
 *   POST   /api/collections/{ad}/records
 *   PATCH  /api/collections/{ad}/records/{id}
 *   DELETE /api/collections/{ad}/records/{id}
 *   GET    /api/files/{koleksiyon}/{id}/{dosya}  ?thumb=100x100   (kaydın view kuralı)
 *   /api/auth/...                                 (kullanıcı hesapları açıksa; bkz. authRoutes)
 *
 * Her koleksiyon işlemi koleksiyonun kuralına göre denetlenir (bkz. [Access]).
 */
fun Application.droidServerModule(
    config: ServerConfig,
    db: SqlDriver,
    startedAt: Instant = Instant.now(),
    /** Veri taşıma sırasında yazmaları kapatmak için (bkz. ServerEngine.pauseWrites). */
    writes: WriteGate = WriteGate(),
    /** Dosya alanlarının deposu (`files/storage`); yoksa dosya yüklenemez. */
    files: FileStore? = null,
    /** Statik site klasörü (`files/www`); `static.enabled` iken yayınlanır. */
    www: File? = null,
    /** Küçük resim üretici; yoksa `?thumb=` özgün dosyayı döndürür. */
    resizer: ImageResizer? = null,
    /** Deploy'da yüklenmiş script'ler (özel uçlar ve kayıt olayları); kapalıysa null. */
    scripts: ScriptRuntime? = null,
    /** İstek metrikleri (`/_metrics`); sunucu yeniden kurulsa da süren sayaçlar. */
    metrics: Metrics? = null,
    /** Web paneli oturumları (eşleştirme kodu); `adminPanel.enabled` iken. */
    adminSessions: AdminSessions? = null,
    /** Panelin konfigürasyon, log ve yedek işleri (app). */
    adminHost: AdminHost? = null,
    /** E-posta göndericisi; null ise `email` ayarından SMTP (testlerde sahte gönderici). */
    mailSender: MailSender? = null,
    /** Uygulamanın log ekranına (e-posta gönderimi gibi arka plan işleri). */
    log: (String) -> Unit = {},
    /** Webhook teslimleri (deploy'lar arasında süren; ServerEngine yapılandırır). */
    webhooks: WebhookDispatcher? = null,
    /** Otomasyon motorunun uygulamaya açıldığı yer (son çalışmalar, "şimdi dene"). */
    automationHost: AutomationHost? = null,
    /** Kullanıcıların günlük istek sayaçları (kota; deploy'lar arasında süren). */
    requestCounts: DailyCounter = DailyCounter(),
    /** Ağ sürücüsü klasörleri: `drive` ve `scripts` (site ve yüklenen dosyalar [www] ve [files]'tan). */
    davDirs: Map<String, File?> = emptyMap(),
    /** SMS girişi sınırları (deploy'lar arasında süren). */
    smsGuard: SmsGuard = SmsGuard(),
    /** Yönetici işlemlerinin kaydı (deploy'lar arasında süren; ServerEngine tutar). */
    audit: AuditLog? = null,
    /** İstemci sınırları ve engeller (deploy'lar arasında süren). */
    clientGuard: ClientGuard = ClientGuard(),
    health: HealthMonitor? = null,
    /** Adlandırılmış API anahtarlarının son kullanım zamanları (deploy'lar arasında süren). */
    keyUsage: ApiKeyUsage = ApiKeyUsage(),
    /** Sosyal giriş sağlayıcılarının adresleri (testlerde sahte sağlayıcı). */
    oauthEndpoints: Map<OAuthProvider, OAuthEndpoints> = OAuthEndpoints.DEFAULT,
    /** Web push; null ise `push` ayarından kurulur (testlerde sahte itme servisi). */
    pushService: PushService? = null,
    /** Telefon özellikleri (SMS, konum, durum); app'te ServerEngine kurar. */
    phone: PhoneService? = null,
    /** Uygulamaların durumu ve Git'ten deploy (app). */
    appsHost: AppsHost? = null,
    /** Uygulamalara istek ileten HTTP istemcisi (testlerde); yoksa kurulur ve sunucu durunca kapanır. */
    appClient: HttpClient? = null,
    /** YZ kullanımı ve anlamsal dizin (deploy'lar arasında süren; ServerEngine tutar). */
    aiHost: AiHost = AiHost(),
    /** YZ sağlayıcısı; null ise `ai` ayarından Claude (testlerde sahte). */
    aiChat: AiChat? = null,
    /** Gömme servisi; null ise `ai.embeddings` ayarından (testlerde sahte; o zaman dizin eşzamanlı çalışır). */
    embedder: Embedder? = null,
    /** Devralma: sunucu kimliği, kopyaların adresleri ve "devredildin" bildirimi (ServerEngine kurar). */
    failover: FailoverPort? = null,
    /** MQTT durumu (uygulama ekranı; ServerEngine tutar). */
    mqttHost: MqttHost? = null,
) {
    if (metrics != null) {
        install(MetricsPlugin) {
            this.metrics = metrics
            basePath = config.server.basePath
        }
    }
    install(RequestGuard) {
        limits = config.server.limits
        basePath = config.server.basePath
        firewall = config.firewall
        guard = clientGuard
        onBan = { ip, minutes -> audit?.record("sistem", "firewall.ban", ip, tr("{0} dk; art arda hatalı kimlik denemesi", minutes)) }
        // Uygulamalar ve ağ sürücüsü gövdeyi akış halinde işler (kendi sınırlarıyla).
        appPaths = config.apps.filter { it.enabled }.map { it.publicPath } + listOfNotNull(config.webdav.path.takeIf { config.webdav.enabled })
    }
    install(ContentNegotiation) { json(ConfigJson) }
    val channelHub = config.realtime.channels.takeIf { config.realtime.enabled && it.isNotEmpty() }
        ?.let { ChannelHub(it, config.realtime.maxConnections) }
    val liveApps = config.apps.filter { it.enabled }
    if (channelHub != null || liveApps.any { it.websockets }) {
        install(WebSockets) {
            pingPeriod = 30.seconds
            timeout = 60.seconds
            // Kanal iletileri küçük; uygulamalar daha büyük çerçeve gönderebilir.
            maxFrameSize = if (liveApps.any { it.websockets }) 1024 * 1024L else 64 * 1024L
        }
    }
    val proxyClient = if (liveApps.isEmpty()) null else AppProxy.client(appClient ?: HttpClient(io.ktor.client.engine.cio.CIO).also { c ->
        monitor.subscribe(ApplicationStopped) { c.close() }
    })
    // PATCH/DELETE yapamayan istemciler (JVM'nin HttpURLConnection'ı) için POST + X-HTTP-Method-Override.
    install(XHttpMethodOverride)
    // GET yanıtlarına içerik özeti (ETag); istemci If-None-Match gönderirse değişmemiş yanıt gövdesiz 304 döner.
    install(ConditionalHeaders) {
        version { call, content ->
            if (call.request.httpMethod != HttpMethod.Get) return@version emptyList()
            val bytes = when (content) {
                is TextContent -> content.text.toByteArray()
                is ByteArrayContent -> content.bytes()
                else -> return@version emptyList()
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            // Zayıf: sıkıştırılmış ve sıkıştırılmamış hali aynı özeti taşır.
            listOf(EntityTagVersion(Base64.getUrlEncoder().withoutPadding().encodeToString(digest).take(27), weak = true))
        }
    }
    // Metin yanıtları (JSON, HTML, JS, CSS) 1 KB üstündeyse gzip/deflate; canlı abonelik (SSE) ve ikili dosyalar sıkıştırılmaz.
    install(Compression) {
        gzip {
            priority = 1.0
            minimumSize(1024)
            matchContentType(ContentType.Text.Any, ContentType.Application.Json, ContentType.Application.JavaScript, ContentType.Application.Xml, ContentType.Image.SVG)
            excludeContentType(ContentType.Text.EventStream)
        }
        deflate {
            priority = 0.9
            minimumSize(1024)
            matchContentType(ContentType.Text.Any, ContentType.Application.Json, ContentType.Application.JavaScript, ContentType.Application.Xml, ContentType.Image.SVG)
            excludeContentType(ContentType.Text.EventStream)
        }
    }

    install(StatusPages) {
        exception<ApiException> { call, e ->
            call.respond(HttpStatusCode.fromValue(e.status), apiError(e.status, e.message ?: "", e.details))
        }
        exception<DatabaseUnavailableException> { call, e ->
            call.application.environment.log.warn(tr("Veritabanına ulaşılamıyor: {0}", e.message))
            call.respond(HttpStatusCode.ServiceUnavailable, apiError(503, tr("Veritabanına şu an ulaşılamıyor; birazdan tekrar deneyin")))
        }
        exception<ScriptException> { call, e ->
            call.respond(HttpStatusCode.fromValue(e.status), apiError(e.status, e.message ?: tr("Script hatası")))
        }
        exception<WritesPausedException> { call, e ->
            if (e.reason == null) call.response.headers.append(HttpHeaders.RetryAfter, "30")
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                apiError(503, e.reason ?: tr("Veriler taşınıyor; yazma geçici olarak kapalı, birazdan tekrar deneyin")),
            )
        }
        exception<BadRequestException> { call, e ->
            call.respond(HttpStatusCode.BadRequest, apiError(400, tr("Geçersiz istek gövdesi: {0}", e.message)))
        }
        exception<Throwable> { call, e ->
            call.application.environment.log.error(tr("İşlenmeyen hata"), e)
            call.respond(HttpStatusCode.InternalServerError, apiError(500, tr("Sunucu hatası")))
        }
    }

    if (config.server.cors.enabled) {
        install(CORS) {
            listOf(HttpMethod.Get, HttpMethod.Post, HttpMethod.Patch, HttpMethod.Delete, HttpMethod.Options)
                .forEach(::allowMethod)
            allowHeader("Authorization")
            allowHeader("Content-Type")
            val hosts = config.server.cors.allowedHosts
            if ("*" in hosts) anyHost() else hosts.forEach { allowHost(it, schemes = listOf("http", "https")) }
        }
    }

    val auth = config.auth
    val cipher = FieldEncryption.cipher(config)
    val users = if (auth.users.enabled) UserService(db, config, files = files, audit = audit) else null
    val quotas = QuotaService(db, config, files, requestCounts)
    val byName = config.collections.associateBy { it.name }
    val adminJwt = if (auth.mode == AuthMode.JWT) {
        JWT.require(Algorithm.HMAC256(auth.jwtSecret!!)).withIssuer(auth.jwtIssuer).withAudience(auth.jwtAudience).build()
    } else null
    val apiKeys = auth.apiKeys.map { it.toByteArray() }
    val namedKeys = ApiKeys(auth.keys, keyUsage, rate = clientGuard::hit)

    /**
     * İsteği yapan: kimlik doğrulama kapalıysa herkes yönetici; API anahtarı / yönetici JWT'si yönetici; kullanıcı
     * token'ı kullanıcı. Geçersiz veya süresi dolmuş token anonim sayılır (herkese açık uçlar yine çalışır).
     */
    suspend fun resolve(token: String?): Caller {
        if (auth.mode == AuthMode.NONE) return Caller.Admin
        if (token.isNullOrEmpty()) return Caller.Anonymous
        val bytes = token.toByteArray()
        if (apiKeys.any { MessageDigest.isEqual(it, bytes) }) return Caller.Admin
        namedKeys.resolve(token)?.let { return it }
        if (adminJwt != null && runCatching { adminJwt.verify(token) }.isSuccess) return Caller.Admin
        if (config.adminPanel.enabled && adminSessions?.valid(token) == true) return Caller.Admin
        return users?.let { u -> io { u.resolve(token) } } ?: Caller.Anonymous
    }

    suspend fun RoutingCall.caller(): Caller {
        attributes.getOrNull(CALLER)?.let { return it }
        val token = request.headers[HttpHeaders.Authorization]?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substring(7)?.trim()?.takeIf { it.isNotEmpty() }
        val caller = resolve(token).also { attributes.put(CALLER, it) }
        if (caller is Caller.User && !caller.isAdmin) {
            clientGuard.hit("user:${caller.id}", config.firewall.userRequestsPerMinute)?.let {
                throw ApiException(429, tr("Kullanıcı başına dakika sınırı ({0}) aşıldı; {1} sn sonra tekrar deneyin", config.firewall.userRequestsPerMinute, it))
            }
            quotas.hit(caller.id)
        }
        return caller
    }

    /** Denetim kaydına: isteği yapan ve adresi. */
    suspend fun RoutingCall.audit(action: String, target: String? = null, detail: String? = null) {
        audit?.record(Access.actor(caller()), action, target, detail, origin(request).key)
    }

    /**
     * `expand=a,b` → alan adı ve hedef koleksiyonun görüntüleme kapsamı. Hedefin görüntüleme kuralı geçerlidir:
     * izin yoksa istek reddedilir, `owner` kuralında yalnızca kullanıcının kendi kayıtları, koşullu kuralda koşula
     * uyanlar gömülür; gizli alanlar çıkarılır. Kullanıcı kayıtlarından (`_users`) yalnızca herkese görünen alanlar gelir.
     */
    fun expandParam(call: RoutingCall, collection: CollectionConfig, caller: Caller): Map<String, Scope?> {
        val names = call.request.queryParameters["expand"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        return names.associateWith { name ->
            val target = collection.fields.firstOrNull { it.name == name }?.collection?.let(byName::get) ?: return@associateWith null
            try {
                Access.scope(target, Rules.Op.VIEW, caller)
            } catch (e: ApiException) {
                throw ApiException(e.status, "expand=$name: ${e.message}")
            }
        }
    }

    val services = config.collections.associate {
        it.name to RecordService(db, it, collections = config.schemaCollections(), publicUserFields = auth.users.publicFields, files = files, quota = quotas, cipher = cipher)
    }

    val paths = ApiPaths(config)
    // Uzman modu: sistem alanlarının API adları; çeviri HTTP sınırında (kayıt servisi özgün adlarla çalışır).
    val names = config.collections.associate { it.name to ApiNames.of(it) }
    val userNames = ApiNames.ofUsers(auth.users)
    fun namesOf(collection: String): ApiNames? = if (collection == SystemCollections.USERS) userNames else names[collection]
    fun outRecord(c: CollectionConfig, record: JsonObject): JsonObject =
        ApiNames.outWithExpand(record, names.getValue(c.name)) { field -> c.fields.firstOrNull { it.name == field }?.collection?.let(::namesOf) }
    val transfer = DataTransfer(db, config, files, writes)
    val views = ViewService(config, services)

    // Bakım, açılışta ve saatte bir: saklama süresi dolan kayıtlar silinir, çöp kutusunda süresi dolanlar kalıcı silinir.
    // Canlı kopyada çalışmaz (yazmalar kapalı; silmeler ana sunucudan gelir).
    if (config.collections.any { it.trash != null || it.retention != null }) {
        val janitor = launch(Dispatchers.IO) {
            while (true) {
                if (!writes.isPaused) {
                    runCatching { writes.write { Retention.run(config, services, Instant.now(), log) } }
                        .onSuccess { it.forEach { (c, n) -> log("Saklama süresi: '$c' koleksiyonunda $n eski kayıt silindi") } }
                        .onFailure { log("Saklama süresi uygulanamadı: ${it.message}") }
                    runCatching { writes.write { RecordService.purgeExpired(db, files, Instant.now()) } }
                        .onSuccess { if (it > 0) log("Çöp kutusu: süresi dolan $it kayıt kalıcı silindi") }
                        .onFailure { log("Çöp kutusu temizlenemedi: ${it.message}") }
                }
                delay(3_600_000)
            }
        }
        monitor.subscribe(ApplicationStopping) { janitor.cancel() }
    }

    val realtime = if (config.realtime.enabled) RealtimeHub(config.realtime.maxConnections) else null
    if (realtime != null) monitor.subscribe(ApplicationStopping) { realtime.closeAll() }

    /** Kayıt değişikliği: canlı abonelere ve webhook'lara. */
    val push = pushService ?: if (config.push.enabled && users != null) {
        PushService(db, config.push, WebPushSender(config.push.publicKey, config.push.privateKey, config.push.subject), log)
    } else null
    if (push != null && pushService == null) monitor.subscribe(ApplicationStopping) { push.close() }

    /** `push.notify` kuralları: kayıt olayında kaydın sahibine bildirim. */
    fun notifyOwner(collection: String, action: RecordEvent.Action, record: JsonObject) {
        val p = push ?: return
        val owner = byName[collection]?.ownerField?.let { (record[it] as? JsonPrimitive)?.longOrNull } ?: return
        for (rule in config.push.notify) {
            if (rule.collection != collection || action.wire !in rule.events) continue
            runCatching {
                // Yer tutucularda hem özgün hem API adları geçer.
                val values = JsonObject(record + (names[collection]?.out(record) ?: record))
                p.sendToUser(owner, p.message(PushService.fill(rule.title, values), PushService.fill(rule.body, values), rule.url?.let { PushService.fill(it, values) }, null))
            }.onFailure { log("Push kuralı ($collection): ${it.message}") }
        }
    }

    // Otomasyon motoru e-posta ve push hazır olunca kurulur; olaylar ondan önce gelmez (rotalar sonra açılır).
    var automation: AutomationEngine? = null

    // YZ: seçilen sağlayıcının istemcisi ve (seçildiyse) anlamsal dizin.
    val ai = config.ai.takeIf { it.enabled }
    val chat: AiChat? = ai?.let { aiChat ?: AiChat.of(it).also { c -> if (c is java.io.Closeable) monitor.subscribe(ApplicationStopping) { c.close() } } }
    val semantic: SemanticIndex? = ai?.embeddings?.takeIf { ai.semantic.isNotEmpty() }?.let { e ->
        SemanticIndex(
            db, ai.semantic, services, embedder ?: HttpEmbedder(e, e.apiKey), "${e.provider.name}/${e.effectiveModel}", writes, log,
            synchronous = embedder != null,
        )
    }
    if (semantic != null) {
        aiHost.index = semantic
        semantic.start()
        monitor.subscribe(ApplicationStopping) {
            semantic.close()
            if (aiHost.index === semantic) aiHost.index = null
        }
    } else {
        aiHost.index = null
    }

    // MQTT (IoT): cihaz mesajları koleksiyona yazılır (aşağıda, kayıt ekleme tanımlandıktan sonra bağlanır).
    var mqttIngest: ((String, ByteArray) -> String)? = null
    val mqtt = config.mqtt.takeIf { it.enabled }?.let { m ->
        MqttBroker(
            m.port, m.username, m.password, config.server.host, m.maxClients,
            onPublish = { topic, payload, _ -> mqttIngest?.invoke(topic, payload) },
            authFailed = { ip -> clientGuard.authFailed(ip).also { if (it) audit?.record(AuditLog.SYSTEM, "firewall.ban", ip, tr("MQTT: art arda hatalı giriş")) } },
            banned = { ip -> clientGuard.bans().any { it.client == ip } },
            log = log,
        )
    }?.takeIf { b ->
        runCatching { b.start() }
            .onSuccess { log("MQTT ${config.mqtt.port} portunda dinliyor") }
            .onFailure { log("MQTT başlatılamadı (${config.mqtt.port}): ${it.message}") }
            .isSuccess
    }
    if (mqtt != null) {
        mqttHost?.broker = mqtt
        monitor.subscribe(ApplicationStopping) {
            mqtt.stop()
            if (mqttHost?.broker === mqtt) mqttHost.broker = null
        }
    }

    fun changedAt(collection: String, action: RecordEvent.Action, record: JsonObject, depth: Int) {
        if (mqtt != null && collection in config.mqtt.events) {
            mqtt.publish("${config.mqtt.eventPrefix}/$collection/${action.wire}", (names[collection]?.out(record) ?: record).toString().toByteArray())
        }
        realtime?.publish(collection, action, record)
        webhooks?.publish(collection, action.wire, names[collection]?.out(record) ?: record)
        if (config.push.notify.isNotEmpty()) notifyOwner(collection, action, record)
        automation?.onRecord(collection, action, record, depth)
        semantic?.onRecord(collection, action == RecordEvent.Action.DELETE, record)
    }

    fun changed(collection: String, action: RecordEvent.Action, record: JsonObject) = changedAt(collection, action, record, 0)

    scripts?.db = ScriptDbBridge(services, writes, ::changed)

    /** Kayıt ekleme (REST ve GraphQL): kural, script kancaları ve olaylar. Dönen kayıt istekte görülebilecek haliyle. */
    fun createRecord(c: CollectionConfig, caller: Caller, body: JsonObject, uploads: List<Upload> = emptyList()): JsonObject {
        val svc = services.getValue(c.name)
        val scope = Access.scope(c, Rules.Op.CREATE, caller)
        val data = scripts?.takeIf { it.hasHook(HookEvent.BEFORE_CREATE, c.name) }?.before(HookEvent.BEFORE_CREATE, c.name, body, null, caller) ?: body
        val created = writes.write { svc.create(data, scope, uploads) }
            .also { changed(c.name, RecordEvent.Action.CREATE, it) }
            .also { scripts?.after(HookEvent.AFTER_CREATE, c.name, it, caller) }
        return svc.present(created, scope)
    }

    mqttIngest = ingest@{ topic, payload ->
        val b = mqtt ?: return@ingest ""
        val route = config.mqtt.routes.firstOrNull { MqttBroker.matches(it.topic, topic) }
        if (route == null) {
            b.note(topic, payload.size, tr("yönlendirme yok"))
            return@ingest ""
        }
        val c = byName.getValue(route.collection)
        val record = createRecord(c, Caller.Admin, MqttIngest.body(c, route, topic, payload))
        val id = record["id"]?.toString().orEmpty()
        b.note(topic, payload.size, "${c.name} #$id")
        id
    }

    fun updateRecord(c: CollectionConfig, caller: Caller, id: Long, body: JsonObject, uploads: List<Upload> = emptyList()): JsonObject {
        val svc = services.getValue(c.name)
        val scope = Access.scope(c, Rules.Op.UPDATE, caller)
        val data = scripts?.takeIf { it.hasHook(HookEvent.BEFORE_UPDATE, c.name) }
            ?.before(HookEvent.BEFORE_UPDATE, c.name, body, svc.current(id, scope), caller) ?: body
        val updated = writes.write { svc.update(id, data, scope, uploads) }
            .also { changed(c.name, RecordEvent.Action.UPDATE, it) }
            .also { scripts?.after(HookEvent.AFTER_UPDATE, c.name, it, caller) }
        return svc.present(updated, scope)
    }

    fun deleteRecord(c: CollectionConfig, caller: Caller, id: Long) {
        val svc = services.getValue(c.name)
        val scope = Access.scope(c, Rules.Op.DELETE, caller)
        val hooked = scripts?.takeIf { it.hasHook(HookEvent.BEFORE_DELETE, c.name) || it.hasHook(HookEvent.AFTER_DELETE, c.name) }
        // Olay dinleyen varsa (canlı abone, webhook, otomasyon) silinmeden önceki hali gerekir.
        val current = if (hooked != null || realtime != null || webhooks != null || automation != null || semantic?.indexes(c.name) == true) svc.current(id, scope) else null
        if (hooked != null && current != null) hooked.before(HookEvent.BEFORE_DELETE, c.name, current, current, caller)
        writes.write { svc.delete(id, scope) }
        if (current != null) changed(c.name, RecordEvent.Action.DELETE, current)
        if (hooked != null && current != null) hooked.after(HookEvent.AFTER_DELETE, c.name, current, caller)
    }

    // Canlı kopya kaynağı: kopyalanan tablolara tetikleyiciler; günlük saatte bir kısaltılır. Kaynak değilse kaldırılır.
    if (config.replication.source) {
        ChangeLog.install(db, ChangeLog.tables(config).map { it.name })
        val trimmer = launch(Dispatchers.IO) {
            while (true) {
                runCatching { ChangeLog.trim(db, config.replication.keepDays) }.onFailure { log("Değişiklik günlüğü kısaltılamadı: ${it.message}") }
                delay(3_600_000)
            }
        }
        monitor.subscribe(ApplicationStopping) { trimmer.cancel() }
    } else {
        runCatching { ChangeLog.uninstall(db) }
    }

    // Arka plan işleri: kuyruk script'ler açıkken; çalıştırıcı script'te iş türü tanımlıysa.
    val jobQueue = if (config.scripts.enabled) JobQueue(db) else null
    val jobRunner = scripts?.takeIf { it.jobTypes.isNotEmpty() && jobQueue != null }?.let { JobRunner(jobQueue!!, it, config.scripts.jobs, writes, log) }
    scripts?.jobs = jobQueue?.let { q ->
        JobSink { name, data, delay, attempts, key ->
            writes.write { q.enqueue(name, data, Duration.ofSeconds(delay), attempts, key) }.also { jobRunner?.wake() }
        }
    }
    scripts?.channels = channelHub?.let { hub -> { channel, data -> hub.publish(channel, data) } }
    scripts?.phone = phone?.takeIf { config.phone.any }
    if (jobRunner != null) {
        jobRunner.start()
        monitor.subscribe(ApplicationStopping) { jobRunner.stop() }
    }

    val mailer = if (config.email.enabled) Mailer(config, mailSender ?: Mailer.smtp(config), log) else null
    scripts?.mail = mailer?.let { m -> m::custom }
    scripts?.push = push?.let { p -> { user, title, body, url -> p.sendToUser(user, p.message(title, body, url, null)) } }

    if (config.automations.any { it.enabled }) {
        val engine = AutomationEngine(
            config.automations, config.collections, services, writes,
            AutomationOutputs(
                mail = mailer?.let { m -> { to, subject, text -> m.custom(to, subject, text, null) } },
                push = push?.let { p -> { user, title, body, url -> p.sendToUser(user, p.message(title, body, url, null)) } },
                webhook = webhooks?.let { w -> { name, event, collection, record -> w.send(name, event, collection, collection?.let { names[it]?.out(record) } ?: record) } },
            ),
            ::changedAt, log,
        )
        automation = engine
        automationHost?.engine = engine
        engine.start()
        monitor.subscribe(ApplicationStopping) {
            engine.close()
            if (automationHost?.engine === engine) automationHost.engine = null
        }
    }

    routing {
        if (proxyClient != null) {
            for (app in liveApps) appRoutes(app, proxyClient) { c, allowQuery ->
                val header = c.request.headers[HttpHeaders.Authorization]?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)?.trim()
                resolve(header ?: if (allowQuery) c.request.queryParameters["token"] else null)
            }
        }
        if (config.adminPanel.enabled && adminSessions != null) adminPanel(config, adminSessions, adminHost, audit) { call.caller() }
        if (config.forms.isNotEmpty()) {
            formPages(config.name, config.forms, config.collections.associateBy { it.name }, services, writes, FormGuard(), ::changed)
        }
        if (config.webdav.enabled) {
            val dav = config.webdav
            val roots = listOfNotNull(
                davDirs["drive"]?.let { DavRoot("drive", it, dav.readOnly) },
                www?.takeIf { dav.site }?.let { DavRoot("site", it, readOnly = false) },
                davDirs["scripts"]?.takeIf { dav.scripts }?.let { DavRoot("scripts", it, readOnly = false) },
                files?.root?.takeIf { dav.uploads }?.let { DavRoot("uploads", it, readOnly = true, hideDotFiles = true) },
            )
            WebDav(dav, roots).install(this)
        }
        if (config.static.enabled && www != null) staticSite(config, StaticSite(www))
        if (users != null && mailer != null) authPages(config.name, users, config.auth.users.minPasswordLength, writes)
        route(config.server.basePath) {
            endpoint(paths, Endpoint.HEALTH) {
                call.respond(buildJsonObject {
                    put("status", JsonPrimitive("ok"))
                    put("name", JsonPrimitive(config.name))
                    put("startedAt", JsonPrimitive(startedAt.toString()))
                    put("collections", JsonPrimitive(config.collections.size))
                })
            }

            endpoint(paths, Endpoint.MQTT) {
                requireAdmin(call.caller())
                call.respond(buildJsonObject {
                    put("enabled", JsonPrimitive(config.mqtt.enabled))
                    put("port", JsonPrimitive(config.mqtt.port))
                    put("listening", JsonPrimitive(mqtt != null))
                    mqtt?.let { b ->
                        put("received", JsonPrimitive(b.received.get()))
                        put("delivered", JsonPrimitive(b.delivered.get()))
                        put("failed", JsonPrimitive(b.failed.get()))
                        put("clients", JsonArray(b.clients().map { cl ->
                            buildJsonObject {
                                put("id", JsonPrimitive(cl.id))
                                put("address", JsonPrimitive(cl.address))
                                put("connectedAt", JsonPrimitive(cl.connectedAt.toString()))
                                put("subscriptions", JsonArray(cl.subscriptions.map(::JsonPrimitive)))
                            }
                        }))
                        put("recent", JsonArray(b.recent().map { m ->
                            buildJsonObject {
                                put("at", JsonPrimitive(m.at.toString()))
                                put("topic", JsonPrimitive(m.topic))
                                put("bytes", JsonPrimitive(m.bytes))
                                put("result", JsonPrimitive(m.result))
                            }
                        }))
                    }
                })
            }
            endpoint(paths, Endpoint.MQTT_PUBLISH) {
                requireAdmin(call.caller())
                val b = mqtt ?: throw ApiException(404, tr("MQTT kapalı"))
                val body = call.receive<JsonObject>()
                val topic = body.text("topic")?.takeIf { it.isNotBlank() && '+' !in it && '#' !in it }
                    ?: throw ApiException(400, tr("topic gerekli (joker olmadan, örn. ev/salon/lamba)"))
                val payload = when (val p = body["payload"]) {
                    null -> ByteArray(0)
                    is JsonPrimitive -> if (p.isString) p.content.toByteArray() else p.toString().toByteArray()
                    else -> p.toString().toByteArray()
                }
                if (payload.size > MqttBroker.MAX_PACKET) throw ApiException(413, tr("Mesaj çok büyük"))
                val n = b.publish(topic, payload)
                b.note(topic, payload.size, tr("sunucudan; {0} cihaza", n))
                call.audit("mqtt.publish", topic)
                call.respond(buildJsonObject { put("delivered", JsonPrimitive(n)) })
            }

            // Komut satırı aracı: sır içermeyen tek dosya; kimlik doğrulamasız indirilir.
            endpoint(paths, Endpoint.CLI) {
                val text = Endpoint::class.java.getResourceAsStream("/cli/droidserver.mjs")?.use { it.readBytes() }
                    ?: throw ApiException(404, tr("Komut satırı aracı bu sürümde yok"))
                call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"droidserver.mjs\"")
                call.respondBytes(text, ContentType.parse("text/javascript; charset=utf-8"))
            }

            endpoint(paths, Endpoint.METRICS) {
                requireAdmin(call.caller())
                val m = metrics ?: throw ApiException(404, tr("Metrikler kapalı"))
                if (call.request.queryParameters["format"] == "prometheus") {
                    call.respondText(m.toPrometheus(), ContentType.parse("text/plain; version=0.0.4"))
                } else {
                    call.respond(m.toJson())
                }
            }

            if (webhooks != null) {
                endpoint(paths, Endpoint.WEBHOOKS) {
                    requireAdmin(call.caller())
                    call.respond(buildJsonObject {
                        put("webhooks", JsonArray(webhooks.hooks.map { h ->
                            buildJsonObject {
                                put("name", JsonPrimitive(h.name))
                                put("url", JsonPrimitive(h.url))
                                put("enabled", JsonPrimitive(h.enabled))
                                put("events", JsonArray(h.events.map(::JsonPrimitive)))
                                put("collections", JsonArray(h.collections.map(::JsonPrimitive)))
                            }
                        }))
                        put("deliveries", JsonArray(webhooks.deliveries().map { it.toJson() }))
                    })
                }
                endpoint(paths, Endpoint.WEBHOOK_TEST) {
                    requireAdmin(call.caller())
                    val d = try {
                        webhooks.test(call.parameters["name"].orEmpty())
                    } catch (e: IllegalArgumentException) {
                        throw ApiException(404, e.message ?: tr("Webhook yok"))
                    }
                    call.respond(d.toJson())
                }
            }

            if (config.automations.isNotEmpty()) {
                endpoint(paths, Endpoint.AUTOMATIONS) {
                    requireAdmin(call.caller())
                    call.respond(buildJsonObject {
                        put("rules", JsonArray(config.automations.map { a ->
                            buildJsonObject {
                                put("name", JsonPrimitive(a.name))
                                put("enabled", JsonPrimitive(a.enabled))
                                put("collection", JsonPrimitive(a.trigger.collection))
                                put("events", JsonArray(a.trigger.events.map(::JsonPrimitive)))
                                put("schedule", JsonPrimitive(a.trigger.schedule))
                                put("actions", JsonArray(a.actions.map { JsonPrimitive(it.type.name.lowercase()) }))
                            }
                        }))
                        put("runs", JsonArray(automation?.runs().orEmpty().map { it.toJson() }))
                    })
                }
                endpoint(paths, Endpoint.AUTOMATION_RUN) {
                    requireAdmin(call.caller())
                    val engine = automation ?: throw ApiException(409, tr("Etkin otomasyon kuralı yok"))
                    val run = try {
                        withContext(Dispatchers.IO) { engine.runNow(call.parameters["name"].orEmpty()) }
                    } catch (e: IllegalArgumentException) {
                        throw ApiException(404, e.message ?: tr("Otomasyon kuralı yok"))
                    }
                    call.respond(run.toJson())
                }
            }

            if (config.apps.isNotEmpty()) {
                endpoint(paths, Endpoint.APPS) {
                    requireAdmin(call.caller())
                    call.respond(appsHost?.status() ?: JsonArray(config.apps.map { a ->
                        buildJsonObject {
                            put("name", JsonPrimitive(a.name))
                            put("path", JsonPrimitive(a.publicPath))
                            put("enabled", JsonPrimitive(a.enabled))
                        }
                    }))
                }
                endpoint(paths, Endpoint.APP_DEPLOY) {
                    requireAdmin(call.caller())
                    val app = config.apps.firstOrNull { it.name == call.parameters["name"] } ?: throw ApiException(404, tr("Uygulama yok"))
                    val host = appsHost ?: throw ApiException(503, tr("Uygulama yönetimi bu ortamda yok"))
                    val actor = Access.actor(call.caller())
                    if (app.git != null) host.pullAndDeploy(app.name, null, tr("{0} isteği", actor)) else host.redeploy(tr("{0} isteği", actor))
                    audit?.record(actor, "apps.deploy", app.name, if (app.git != null) "git" else "")
                    call.respond(HttpStatusCode.Accepted, buildJsonObject { put("accepted", JsonPrimitive(true)) })
                }
                endpoint(paths, Endpoint.APP_HOOK) {
                    val app = config.apps.firstOrNull { it.name == call.parameters["name"] && it.enabled } ?: throw ApiException(404, tr("Uygulama yok"))
                    val git = app.git ?: throw ApiException(404, tr("Bu uygulamada Git kaynağı yok"))
                    val secret = git.webhookSecret ?: throw ApiException(404, tr("Bu uygulamada webhook kapalı"))
                    val body = call.receive<ByteArray>()
                    if (!GitWebhook.verify({ call.request.headers[it] }, { call.request.queryParameters[it] }, body, secret)) {
                        throw ApiException(401, tr("Webhook imzası geçersiz"))
                    }
                    if (GitWebhook.isPing { call.request.headers[it] }) {
                        call.respond(buildJsonObject { put("ok", JsonPrimitive(true)); put("ping", JsonPrimitive(true)) })
                        return@endpoint
                    }
                    val branch = GitWebhook.pushedBranch(body)
                    if (branch != null && branch != git.branch) {
                        call.respond(HttpStatusCode.Accepted, buildJsonObject { put("ignored", JsonPrimitive(tr("'{0}' dalı izlenmiyor ({1} izleniyor)", branch, git.branch))) })
                        return@endpoint
                    }
                    val host = appsHost ?: throw ApiException(503, tr("Uygulama yönetimi bu ortamda yok"))
                    val commit = GitWebhook.commit(body)
                    host.pullAndDeploy(app.name, commit, tr("webhook"))
                    audit?.record("webhook", "apps.deploy", app.name, commit?.take(12) ?: "")
                    call.respond(HttpStatusCode.Accepted, buildJsonObject { put("accepted", JsonPrimitive(true)); commit?.let { put("commit", JsonPrimitive(it)) } })
                }
            }

            endpoint(paths, Endpoint.OPENAPI) {
                call.respond(OpenApi.document(config, call.caller(), scripts?.routes.orEmpty()))
            }

            // Tipli istemci: şemayı içerdiği için yalnızca yönetici (geliştirici anahtarıyla indirir).
            endpoint(paths, Endpoint.SDK) {
                requireAdmin(call.caller())
                val lang = SdkGenerator.Lang.of(call.parameters["lang"].orEmpty())
                    ?: throw ApiException(404, tr("Bilinmeyen dil; ts veya kotlin"))
                val pkg = call.request.queryParameters["package"]?.takeIf { it.isNotBlank() } ?: "droidserver.client"
                if (!Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*$").matches(pkg)) throw ApiException(400, tr("Geçersiz paket adı: {0}", pkg))
                call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"${lang.fileName}\"")
                call.respondText(SdkGenerator.generate(config, lang, pkg), ContentType.Text.Plain.withCharset(Charsets.UTF_8))
            }
            // Güvenlik duvarı: geçici engeller (otomatik ve elle) ve listeler.
            endpoint(paths, Endpoint.FIREWALL) {
                requireAdmin(call.caller())
                call.respond(buildJsonObject {
                    put("bans", JsonArray(clientGuard.bans().map { b ->
                        buildJsonObject {
                            put("client", JsonPrimitive(b.client))
                            put("until", JsonPrimitive(Instant.ofEpochMilli(b.untilMs).toString()))
                            put("reason", JsonPrimitive(b.reason))
                        }
                    }))
                    put("allow", JsonArray(config.firewall.allow.map(::JsonPrimitive)))
                    put("deny", JsonArray(config.firewall.deny.map(::JsonPrimitive)))
                })
            }
            endpoint(paths, Endpoint.FIREWALL_BAN) {
                requireAdmin(call.caller())
                val body = call.receive<JsonObject>()
                val client = body.text("client")?.trim()?.takeIf { dev.droidserver.core.net.Cidr.parse(it) != null && '/' !in it }
                    ?: throw ApiException(400, tr("Doğrulama hatası"), mapOf("client" to tr("IP adresi bekleniyor")))
                val minutes = (body["minutes"] as? JsonPrimitive)?.content?.toIntOrNull()?.takeIf { it in 1..525_600 } ?: 60
                val reason = body.text("reason")?.take(200) ?: tr("elle engellendi")
                clientGuard.ban(client, minutes, reason)
                call.audit("firewall.ban", client, tr("{0} dk; {1}", minutes, reason))
                call.respond(HttpStatusCode.NoContent)
            }
            endpoint(paths, Endpoint.FIREWALL_UNBAN) {
                requireAdmin(call.caller())
                val client = call.parameters["client"].orEmpty()
                if (!clientGuard.unban(client)) throw ApiException(404, tr("{0} engelli değil", client))
                call.audit("firewall.unban", client)
                call.respond(HttpStatusCode.NoContent)
            }
            endpoint(paths, Endpoint.AUDIT) {
                requireAdmin(call.caller())
                val q = call.request.queryParameters
                val entries = io {
                    audit?.list(q["limit"]?.toIntOrNull() ?: 200, q["action"], q["actor"], q["q"]).orEmpty()
                }
                if (q["format"] == "csv") {
                    call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"denetim.csv\"")
                    call.respondText(AuditLog.csv(entries), ContentType.Text.CSV.withCharset(Charsets.UTF_8))
                } else {
                    call.respond(buildJsonObject { put("items", ConfigJson.encodeToJsonElement(entries)) })
                }
            }
            endpoint(paths, Endpoint.ALERTS) {
                requireAdmin(call.caller())
                val h = health ?: throw ApiException(404, tr("Sağlık uyarıları bu sunucuda yok"))
                call.respond(buildJsonObject {
                    put("enabled", JsonPrimitive(h.config.enabled))
                    put("active", ConfigJson.encodeToJsonElement(h.active()))
                    put("recent", ConfigJson.encodeToJsonElement(h.recent()))
                })
            }
            endpoint(paths, Endpoint.ALERTS_TEST) {
                requireAdmin(call.caller())
                val h = health ?: throw ApiException(404, tr("Sağlık uyarıları bu sunucuda yok"))
                val sent = io { h.event(AlertKind.TEST, tr("Bu bir deneme uyarısıdır; uyarı kanalları çalışıyor.")) }
                    ?: throw ApiException(502, tr("Deneme uyarısı hiçbir kanala gönderilemedi (kanal yok veya hepsi başarısız; loglara bakın)"))
                call.respond(ConfigJson.encodeToJsonElement(sent))
            }
            endpoint(paths, Endpoint.REPLICATION_CONFIG) {
                requireAdmin(call.caller())
                if (!config.replication.source) throw ApiException(404, tr("Bu sunucu kopyalanabilir değil (Sihirbaz → Veritabanı → Canlı kopya)"))
                call.respond(buildJsonObject {
                    // Sırlar dahil: kopya devralınca istemciler aynı anahtar ve token'larla çalışmaya devam eder.
                    put("config", ConfigJson.encodeToJsonElement(config))
                    put("hash", JsonPrimitive(ChangeLog.hash(config)))
                    put("tables", JsonArray(ChangeLog.tables(config).map { JsonPrimitive(it.name) }))
                    put("latest", JsonPrimitive(io { ChangeLog.latest(db) }))
                })
            }
            endpoint(paths, Endpoint.REPLICATION_CHANGES) {
                requireAdmin(call.caller())
                if (!config.replication.source) throw ApiException(404, tr("Bu sunucu kopyalanabilir değil"))
                failover?.let { f ->
                    call.response.headers.append("X-Instance-Id", f.instanceId)
                    peerUrl(call)?.let(f.replicaSeen)
                }
                val q = call.request.queryParameters
                val after = q["after"]?.toLongOrNull() ?: 0
                call.response.headers.append("X-Config-Hash", ChangeLog.hash(config))
                call.respond(io { ChangeLog.changes(db, config, after, q["limit"]?.toIntOrNull() ?: 500) })
            }
            endpoint(paths, Endpoint.REPLICATION_SNAPSHOT) {
                requireAdmin(call.caller())
                if (!config.replication.source) throw ApiException(404, tr("Bu sunucu kopyalanabilir değil"))
                val q = call.request.queryParameters
                val table = q["table"] ?: throw ApiException(400, tr("table gerekli"))
                call.respond(io {
                    runCatching { ChangeLog.snapshot(db, config, table, q["after"]?.toLongOrNull() ?: 0, q["limit"]?.toIntOrNull() ?: 500) }
                        .getOrElse { throw ApiException(404, it.message ?: tr("Tablo yok")) }
                })
            }
            // Devralma: rol (kimliksiz; yalnızca kimlikler), "devredildin", rolü devretmek için yazmayı durdur / aç.
            val replicated = config.replication.source || config.replication.replicaOf != null || config.replication.formerPrimary != null
            if (replicated && failover != null) {
                val stepDown = java.util.concurrent.atomic.AtomicReference<kotlinx.coroutines.Job?>()
                endpoint(paths, Endpoint.REPLICATION_ROLE) {
                    call.respond(buildJsonObject {
                        put("role", JsonPrimitive(if (config.replication.replicaOf != null) "replica" else if (failover.isFenced) "fenced" else "primary"))
                        put("id", JsonPrimitive(failover.instanceId))
                        config.replication.formerPrimary?.id?.let { put("formerPrimaryId", JsonPrimitive(it)) }
                    })
                }
                endpoint(paths, Endpoint.REPLICATION_FENCE) {
                    requireAdmin(call.caller())
                    if (config.replication.replicaOf != null) {
                        call.respond(buildJsonObject { put("status", JsonPrimitive("replica")) })
                        return@endpoint
                    }
                    val key = call.request.headers[HttpHeaders.Authorization]?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)?.trim()
                        ?: throw ApiException(400, tr("Anahtar gerekli"))
                    val url = peerUrl(call) ?: throw ApiException(400, tr("X-Replica-Port ve X-Replica-Path gerekli"))
                    val first = !failover.isFenced
                    if (first) {
                        failover.isFenced = true
                        stepDown.getAndSet(null)?.cancel()
                        io { runCatching { writes.pause(reason = tr("Bu sunucu devredildi; yeni ana sunucu: {0}", url)) } }
                        log("Devredildi: yeni ana sunucu $url; bu sunucu onun kopyası olacak")
                        failover.fenced(url, key)
                    }
                    call.respond(HttpStatusCode.Accepted, buildJsonObject { put("status", JsonPrimitive("fenced")) })
                }
                endpoint(paths, Endpoint.REPLICATION_STEP_DOWN) {
                    requireAdmin(call.caller())
                    if (config.replication.replicaOf != null || !config.replication.source) throw ApiException(409, tr("Bu sunucu ana sunucu değil"))
                    io { writes.pause(reason = tr("Ana sunucu rolü devrediliyor; birazdan yeniden deneyin")) }
                    // Devir gelmezse (kopya yetişemedi veya koptu) yazmalar kendiliğinden açılır.
                    stepDown.getAndSet(call.application.launch(Dispatchers.IO) {
                        kotlinx.coroutines.delay(failover.timing.stepDownMs)
                        if (!failover.isFenced) writes.resume()
                    })?.cancel()
                    log("Ana rol devrediliyor: yazmalar durdu")
                    call.respond(buildJsonObject { put("latest", JsonPrimitive(io { ChangeLog.latest(db) })) })
                }
                endpoint(paths, Endpoint.REPLICATION_STEP_UP) {
                    requireAdmin(call.caller())
                    stepDown.getAndSet(null)?.cancel()
                    if (!failover.isFenced && config.replication.replicaOf == null) writes.resume()
                    call.respond(buildJsonObject { put("writable", JsonPrimitive(!writes.isPaused)) })
                }
            }
            // Görünümler ve raporlar: koleksiyon kurallarından bağımsız, görünümün erişimiyle.
            if (config.views.isNotEmpty()) {
                endpoint(paths, Endpoint.VIEWS) {
                    val caller = call.caller()
                    call.respond(buildJsonObject {
                        put("items", JsonArray(views.visible(caller).map { v ->
                            buildJsonObject {
                                put("name", JsonPrimitive(v.name))
                                put("title", JsonPrimitive(v.label))
                                put("collection", JsonPrimitive(v.collection))
                                put("report", JsonPrimitive(v.report != null))
                                put("columns", JsonArray(views.columns(v).map(::JsonPrimitive)))
                            }
                        }))
                    })
                }
                endpoint(paths, Endpoint.VIEW) {
                    val view = views.find(call.parameters["name"].orEmpty())
                    val caller = call.caller()
                    val query = call.request.queryParameters.entries().flatMap { (k, vs) -> vs.map { k to it } }
                    val q = call.request.queryParameters
                    if (q["format"] == "csv") {
                        views.authorize(view, caller)
                        io { views.run(view, caller, query.filter { it.first != "format" && it.first != "delimiter" } + ("perPage" to "1")) } // hatalı filtre yanıt başlamadan 400
                        val date = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString().replace("-", "")
                        call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"${view.name}-$date.csv\"")
                        call.respondTextWriter(ContentType.parse("text/csv; charset=utf-8")) {
                            withContext(Dispatchers.IO) { views.csv(view, caller, query, this@respondTextWriter, if (q["delimiter"] == "semicolon") ';' else ',') }
                        }
                    } else {
                        call.respond(io { views.run(view, caller, query) })
                    }
                }
            }
            // Telefon özellikleri: yalnızca yönetici; her biri konfigürasyonda ayrı açılır.
            if (phone != null && config.phone.any) {
                endpoint(paths, Endpoint.PHONE_SMS) {
                    requireAdmin(call.caller())
                    val body = call.receive<JsonObject>()
                    fun text(k: String) = (body[k] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw ApiException(400, tr("{0} gerekli", k))
                    val actor = Access.actor(call.caller())
                    call.respond(io { phone.sendSms(text("to"), text("text"), actor) })
                }
                endpoint(paths, Endpoint.PHONE_LOCATION) {
                    requireAdmin(call.caller())
                    call.respond(io { phone.location() })
                }
                endpoint(paths, Endpoint.PHONE_STATUS) {
                    requireAdmin(call.caller())
                    call.respond(io { phone.status() })
                }
            }
            endpoint(paths, Endpoint.JOBS) {
                requireAdmin(call.caller())
                val q = jobQueue ?: throw ApiException(404, tr("İş kuyruğu kapalı (script'ler kapalı)"))
                val p = call.request.queryParameters
                val (items, total) = io { q.list(p["status"]?.takeIf { it.isNotBlank() }, p["name"]?.takeIf { it.isNotBlank() }, p["page"]?.toIntOrNull() ?: 1, p["perPage"]?.toIntOrNull() ?: 50) }
                val counts = io { q.counts() }
                call.respond(buildJsonObject {
                    put("items", JsonArray(items.map { it.toJson() }))
                    put("totalItems", JsonPrimitive(total))
                    put("counts", JsonObject(Job.STATUSES.associateWith { JsonPrimitive(counts[it] ?: 0) }))
                    put("types", JsonArray(scripts?.jobTypes?.values.orEmpty().map { t ->
                        buildJsonObject {
                            put("name", JsonPrimitive(t.name))
                            put("attempts", JsonPrimitive(t.attempts))
                            put("timeoutSec", JsonPrimitive(t.timeoutSec))
                            put("concurrency", JsonPrimitive(t.concurrency))
                        }
                    }))
                })
            }
            endpoint(paths, Endpoint.JOBS_ENQUEUE) {
                requireAdmin(call.caller())
                val q = jobQueue ?: throw ApiException(404, tr("İş kuyruğu kapalı (script'ler kapalı)"))
                val body = call.receive<JsonObject>()
                val name = body.text("name").orEmpty()
                val type = scripts?.jobTypes?.get(name) ?: throw ApiException(400, tr("Doğrulama hatası"), mapOf("name" to tr("'{0}' iş türü script'lerde tanımlı değil", name)))
                val delay = (body["delaySec"] as? JsonPrimitive)?.content?.toLongOrNull()?.coerceIn(0, 30L * 86_400) ?: 0
                val id = io { writes.write { q.enqueue(name, body["data"] ?: JsonObject(emptyMap()), Duration.ofSeconds(delay), type.attempts, body.text("key")) } }
                jobRunner?.wake()
                call.audit("job.enqueue", "$name#$id")
                call.respond(HttpStatusCode.Created, io { q.get(id).toJson() })
            }
            endpoint(paths, Endpoint.JOB_RETRY) {
                requireAdmin(call.caller())
                val q = jobQueue ?: throw ApiException(404, tr("İş kuyruğu kapalı (script'ler kapalı)"))
                val id = call.parameters["id"]?.toLongOrNull() ?: throw ApiException(404, tr("İş bulunamadı"))
                val job = io { writes.write { q.retry(id) } }
                jobRunner?.wake()
                call.audit("job.retry", "${job.name}#$id")
                call.respond(job.toJson())
            }
            endpoint(paths, Endpoint.JOB_CANCEL) {
                requireAdmin(call.caller())
                val q = jobQueue ?: throw ApiException(404, tr("İş kuyruğu kapalı (script'ler kapalı)"))
                val id = call.parameters["id"]?.toLongOrNull() ?: throw ApiException(404, tr("İş bulunamadı"))
                val job = io { writes.write { q.cancel(id) } }
                call.audit("job.cancel", "${job.name}#$id")
                call.respond(job.toJson())
            }
            endpoint(paths, Endpoint.SCHEMA) {
                requireAdmin(call.caller())
                call.respond(ConfigJson.encodeToJsonElement(config.collections))
            }

            if (push != null) pushRoutes(push, paths) { call.caller() }
            if (users != null) {
                val oauth = config.auth.users.oauth.takeIf { it.enabled }?.let {
                    OAuthService(it, config.server.basePath, oauthEndpoints, callbackPath = paths.path(Endpoint.OAUTH_CALLBACK))
                }
                authRoutes(users, writes, mailer, config, oauth, paths, userNames, phone?.takeIf { config.phone.sms }, smsGuard) { call.caller() }
                endpoint(paths, Endpoint.AUTH_USAGE) {
                    val id = requireUser(call.caller())
                    call.respond(io { quotas.usage(id) })
                }
                if (config.auth.users.teams.enabled) {
                    val teams = TeamService(db, config)
                    fun RoutingCall.teamId() = parameters["id"]?.toLongOrNull() ?: throw ApiException(404, tr("Takım bulunamadı"))
                    fun RoutingCall.memberId() = parameters["user"]?.toLongOrNull() ?: throw ApiException(404, tr("Kullanıcı bu takımda değil"))
                    endpoint(paths, Endpoint.TEAMS) { val c = call.caller(); call.respond(io { teams.list(c) }) }
                    endpoint(paths, Endpoint.TEAMS_CREATE) {
                        val c = call.caller()
                        val body = call.receive<JsonObject>()
                        call.respond(HttpStatusCode.Created, io { writes.write { teams.create(c, body.text("name")) } })
                    }
                    endpoint(paths, Endpoint.TEAM) { val c = call.caller(); val id = call.teamId(); call.respond(io { teams.get(c, id) }) }
                    endpoint(paths, Endpoint.TEAM_UPDATE) {
                        val c = call.caller()
                        val id = call.teamId()
                        val body = call.receive<JsonObject>()
                        call.respond(io { writes.write { teams.rename(c, id, body.text("name")) } })
                    }
                    endpoint(paths, Endpoint.TEAM_DELETE) {
                        val c = call.caller()
                        val id = call.teamId()
                        io { writes.write { teams.delete(c, id) } }
                        call.respond(HttpStatusCode.NoContent)
                    }
                    endpoint(paths, Endpoint.TEAM_ADD_MEMBER) {
                        val c = call.caller()
                        val id = call.teamId()
                        val body = call.receive<JsonObject>()
                        val user = (body["user"] as? JsonPrimitive)?.let { it.longOrNull ?: it.content.toLongOrNull() }
                        call.respond(HttpStatusCode.Created, io { writes.write { teams.addMember(c, id, body.text("email"), user, body.text("role")) } })
                    }
                    endpoint(paths, Endpoint.TEAM_UPDATE_MEMBER) {
                        val c = call.caller()
                        val id = call.teamId()
                        val user = call.memberId()
                        val body = call.receive<JsonObject>()
                        call.respond(io { writes.write { teams.setRole(c, id, user, body.text("role")) } })
                    }
                    endpoint(paths, Endpoint.TEAM_REMOVE_MEMBER) {
                        val c = call.caller()
                        val id = call.teamId()
                        val user = call.memberId()
                        io { writes.write { teams.removeMember(c, id, user) } }
                        call.respond(HttpStatusCode.NoContent)
                    }
                }
            }
            if (realtime != null) {
                realtimeRoute(realtime, byName, paths.path(Endpoint.REALTIME), { c -> names[c] }) {
                    // Tarayıcının EventSource'u başlık gönderemez; yalnızca bu uçta `?token=` de kabul edilir.
                    val header = call.request.headers[HttpHeaders.Authorization]
                    if (header == null) resolve(call.request.queryParameters["token"]?.trim()) else call.caller()
                }
            }
            if (channelHub != null) {
                paths.path(Endpoint.REALTIME_WS)?.let { wsPath ->
                    webSocket(wsPath) {
                        // Tarayıcının WebSocket'i başlık gönderemez: `?token=` de kabul edilir.
                        val token = call.request.headers[HttpHeaders.Authorization]?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)?.trim()
                            ?: call.request.queryParameters["token"]?.trim()
                        val caller = runCatching { resolve(token) }
                            .getOrElse { close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, it.message.orEmpty().take(120))); return@webSocket }
                        val conn = channelHub.connect(caller)
                            ?: run { close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, tr("Bağlantı sınırı dolu"))); return@webSocket }
                        val writer = launch {
                            for (text in conn.outgoing) send(Frame.Text(text))
                            if (conn.overflowed) close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, tr("İstemci iletileri yeterince hızlı okumuyor")))
                        }
                        try {
                            for (frame in incoming) if (frame is Frame.Text) channelHub.handle(conn, frame.readText())
                        } finally {
                            channelHub.disconnect(conn)
                            writer.cancel()
                        }
                    }
                }
            }
            scripts?.routes?.forEach { r -> scriptRoute(scripts, r) { call.caller() } }

            endpoint(paths, Endpoint.FILES) {
                val collection = byName[call.parameters["collection"]] ?: throw ApiException(404, tr("Koleksiyon bulunamadı"))
                val scope = Access.scope(collection, Rules.Op.VIEW, call.caller())
                val id = call.idParam()
                val name = call.parameters["name"].orEmpty()
                val record = io { services.getValue(collection.name).get(id, scope = scope) }
                val field = collection.fields.firstOrNull { f ->
                    f.type == FieldType.FILE && when (val v = record[f.name]) {
                        is JsonArray -> v.any { (it as? JsonPrimitive)?.content == name }
                        is JsonPrimitive -> v.content == name
                        else -> false
                    }
                } ?: throw ApiException(404, tr("Dosya bulunamadı"))
                val file = files?.file(collection.name, id, name)?.takeIf { it.isFile } ?: throw ApiException(404, tr("Dosya bulunamadı"))
                val mime = MimeTypes.forExtension(name.substringAfterLast('.'))
                val served = call.request.queryParameters["thumb"]?.let { size ->
                    if (size !in field.thumbSizes) {
                        throw ApiException(400, tr("Bu boyutta küçük resim yok; izinli: {0}", field.thumbSizes.joinToString()))
                    }
                    io { thumbnail(files!!, resizer, collection.name, id, name, mime, size) }
                } ?: file
                val servedMime = if (served == file) mime else MimeTypes.forExtension(served.extension)
                val disposition = if (MimeTypes.isInline(servedMime)) "inline" else "attachment"
                call.response.headers.append(HttpHeaders.ContentDisposition, "$disposition; filename=\"$name\"")
                // Yüklenen içerik bu sunucunun adresinden betik çalıştıramasın.
                if (servedMime != "application/pdf") call.response.headers.append("Content-Security-Policy", "default-src 'none'; sandbox")
                call.respond(LocalFileContent(served, ContentType.parse(servedMime)))
            }

            if (config.api.graphql) {
                val gql = GraphQL(
                    config.collections,
                    { c ->
                        GqlOps(
                            list = paths.path(c, CollectionEndpoint.LIST) != null, view = paths.path(c, CollectionEndpoint.VIEW) != null,
                            create = paths.path(c, CollectionEndpoint.CREATE) != null, update = paths.path(c, CollectionEndpoint.UPDATE) != null,
                            delete = paths.path(c, CollectionEndpoint.DELETE) != null,
                        )
                    },
                    auth.users.publicFields.toSet(),
                )
                endpoint(paths, Endpoint.GRAPHQL_SCHEMA) {
                    call.respondText(gql.sdl(), ContentType.parse("text/plain; charset=utf-8"))
                }
                endpoint(paths, Endpoint.GRAPHQL) {
                    val caller = call.caller()
                    val request = call.receive<JsonObject>()
                    // İlişki genişletme REST'teki expand gibi: hedef koleksiyonun görüntüleme kuralıyla.
                    fun expand(c: CollectionConfig, fields: Set<String>): Map<String, Scope?> = fields.associateWith { name ->
                        c.fields.firstOrNull { it.name == name }?.collection?.let(byName::get)?.let { Access.scope(it, Rules.Op.VIEW, caller) }
                    }
                    val backend = object : GqlBackend {
                        override fun list(c: CollectionConfig, page: Int, perPage: Int, sort: String?, params: List<Pair<String, String>>, expand: Set<String>): Page {
                            val n = names.getValue(c.name)
                            val p = services.getValue(c.name).list(
                                page, perPage, sort?.let { n.params(listOf("sort" to it)).single().second }, n.params(params),
                                expand = expand(c, expand), scope = Access.scope(c, Rules.Op.LIST, caller),
                            )
                            return p.copy(items = p.items.map { outRecord(c, it) })
                        }
                        override fun get(c: CollectionConfig, id: Long, expand: Set<String>) =
                            outRecord(c, services.getValue(c.name).get(id, null, expand(c, expand), Access.scope(c, Rules.Op.VIEW, caller)))
                        override fun create(c: CollectionConfig, data: JsonObject) = outRecord(c, createRecord(c, caller, names.getValue(c.name).inBody(data)))
                        override fun update(c: CollectionConfig, id: Long, data: JsonObject) = outRecord(c, updateRecord(c, caller, id, names.getValue(c.name).inBody(data)))
                        override fun delete(c: CollectionConfig, id: Long) = deleteRecord(c, caller, id)
                    }
                    call.respond(io { gql.execute(request, backend) })
                }
            }

            if (chat != null) aiRoutes(config, paths, services, names, chat, semantic, aiHost.usage, ::outRecord) { call.caller() }

            endpoint(paths, Endpoint.BATCH) {
                val caller = call.caller()
                val requests = (call.receive<JsonObject>()["requests"] as? JsonArray)
                    ?: throw ApiException(400, tr("requests dizisi gerekli: [{method, collection, id?, body?}]"))
                if (requests.isEmpty() || requests.size > MAX_BATCH) throw ApiException(400, tr("1-{0} istek gönderilebilir", MAX_BATCH))
                val ops = requests.mapIndexed { i, el -> batchOp(i, el, byName, services, caller) }
                    .map { op -> names.getValue(op.collection.name).let { n -> if (n.isIdentity) op else op.copy(body = n.inBody(op.body)) } }
                val done = io {
                    writes.write {
                        db.transaction {
                            ops.map { op ->
                                try {
                                    op to runBatchOp(op, scripts, caller)
                                } catch (e: ApiException) {
                                    throw ApiException(e.status, tr("İstek {0}: {1}", op.index + 1, e.message), e.details + ("index" to op.index.toString()))
                                }
                            }
                        }
                    }
                }
                // İşlem tamamlandıktan sonra: canlı abonelere, webhook'lara ve script'lerin after olaylarına.
                val results = done.map { (op, record) ->
                    changed(op.collection.name, op.action, record)
                    val event = when (op.action) {
                        RecordEvent.Action.CREATE -> HookEvent.AFTER_CREATE
                        RecordEvent.Action.UPDATE -> HookEvent.AFTER_UPDATE
                        RecordEvent.Action.DELETE -> HookEvent.AFTER_DELETE
                    }
                    if (scripts?.hasHook(event, op.collection.name) == true) io { scripts.after(event, op.collection.name, record, caller) }
                    buildJsonObject {
                        put("status", JsonPrimitive(if (op.action == RecordEvent.Action.CREATE) 201 else if (op.action == RecordEvent.Action.DELETE) 204 else 200))
                        if (op.action != RecordEvent.Action.DELETE) put("body", outRecord(op.collection, op.svc.present(record, op.scope)))
                    }
                }
                call.respond(buildJsonObject { put("results", JsonArray(results)) })
            }

            for (collection in config.collections) {
                val svc = services.getValue(collection.name)
                val n = names.getValue(collection.name)
                fun RoutingCall.query(skip: Set<String> = emptySet()) =
                    n.params(request.queryParameters.entries().filter { it.key !in skip }.flatMap { (k, vs) -> vs.map { k to it } })
                // Dışa aktarma: liste gibi kurallar, filtre ve arama; gizli alanlar hariç.
                endpoint(paths, collection, CollectionEndpoint.EXPORT) {
                    val scope = Access.scope(collection, Rules.Op.LIST, call.caller())
                    val q = call.request.queryParameters
                    val format = TransferFormat.of(q["format"] ?: "csv")
                    val delimiter = if (q["delimiter"] == "semicolon") ';' else ','
                    val params = call.query(setOf("format", "delimiter"))
                    val sort = params.lastOrNull { it.first == "sort" }?.second
                    io { svc.list(1, 1, sort, params, scope = scope) } // hatalı filtre yanıt başlamadan 400
                    val date = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString().replace("-", "")
                    call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"${collection.name}-$date.${format.extension}\"")
                    call.respondTextWriter(ContentType.parse("${format.mime}; charset=utf-8")) {
                        withContext(Dispatchers.IO) { transfer.export(collection.name, format, this@respondTextWriter, params, sort, scope, delimiter) }
                    }
                }
                // İçe aktarma: yalnızca yönetici; gövde dosyanın kendisi (CSV veya JSON).
                endpoint(paths, collection, CollectionEndpoint.IMPORT) {
                    val caller = call.caller()
                    if (caller is Caller.Anonymous) throw ApiException(401, tr("İçe aktarma için yönetici anahtarı gerekli"))
                    val keyAllows = caller is Caller.Key && caller.allows(collection.name, write = true)
                    if (!caller.isAdmin && !keyAllows) throw ApiException(403, tr("İçe aktarma yalnızca yönetici içindir"))
                    val q = call.request.queryParameters
                    val format = TransferFormat.of(q["format"] ?: "csv")
                    val text = call.receiveText()
                    val result = io {
                        transfer.import(collection.name, format, text, DataTransfer.parseMapping(q["mapping"]), ImportMode.of(q["mode"]), q["dryRun"] == "true")
                    }
                    if (result.applied) {
                        log("İçe aktarıldı: ${collection.name}, ${result.created} yeni, ${result.updated} güncellendi")
                        call.audit("data.import", collection.name, tr("{0} yeni, {1} güncellendi", result.created, result.updated))
                    }
                    val status = if (result.errors.isNotEmpty()) HttpStatusCode.BadRequest else HttpStatusCode.OK
                    call.respond(status, result.toJson())
                }
                endpoint(paths, collection, CollectionEndpoint.AGGREGATE) {
                    val scope = Access.scope(collection, Rules.Op.LIST, call.caller())
                    call.respond(io { svc.aggregate(call.query(), scope) })
                }
                run {
                    endpoint(paths, collection, CollectionEndpoint.LIST) {
                        val caller = call.caller()
                        val scope = Access.scope(collection, Rules.Op.LIST, caller)
                        val q = call.request.queryParameters
                        val params = call.query()
                        val fields = FilterParser.parseFields(params.lastOrNull { it.first == "fields" }?.second, collection)
                        val expand = expandParam(call, collection, caller)
                        val page = io {
                            svc.list(
                                page = q["page"]?.toIntOrNull() ?: 1,
                                perPage = q["perPage"]?.toIntOrNull() ?: 20,
                                sort = params.lastOrNull { it.first == "sort" }?.second,
                                params = params,
                                fields = fields,
                                expand = expand,
                                scope = scope,
                            )
                        }
                        call.respond(page.copy(items = page.items.map { outRecord(collection, it) }).toJson())
                    }
                    endpoint(paths, collection, CollectionEndpoint.VIEW) {
                        val caller = call.caller()
                        val scope = Access.scope(collection, Rules.Op.VIEW, caller)
                        val fields = FilterParser.parseFields(call.query().lastOrNull { it.first == "fields" }?.second, collection)
                        val expand = expandParam(call, collection, caller)
                        call.respond(outRecord(collection, io { svc.get(call.idParam(), fields, expand, scope) }))
                    }
                    endpoint(paths, collection, CollectionEndpoint.CREATE) {
                        val caller = call.caller()
                        val scope = Access.scope(collection, Rules.Op.CREATE, caller)
                        val (raw, uploads) = receiveBody(call, collection, files, config.server.limits.maxBodyKb * 1024L)
                        val body = n.inBody(raw)
                        try {
                            val created = io { createRecord(collection, caller, body, uploads) }
                            call.respond(HttpStatusCode.Created, outRecord(collection, created))
                        } finally {
                            uploads.forEach { it.temp.delete() }
                        }
                    }
                    endpoint(paths, collection, CollectionEndpoint.UPDATE) {
                        val caller = call.caller()
                        val scope = Access.scope(collection, Rules.Op.UPDATE, caller)
                        val id = call.idParam()
                        val (raw, uploads) = receiveBody(call, collection, files, config.server.limits.maxBodyKb * 1024L)
                        val body = n.inBody(raw)
                        try {
                            val updated = io { updateRecord(collection, caller, id, body, uploads) }
                            call.respond(outRecord(collection, updated))
                        } finally {
                            uploads.forEach { it.temp.delete() }
                        }
                    }
                    endpoint(paths, collection, CollectionEndpoint.DELETE) {
                        val caller = call.caller()
                        val scope = Access.scope(collection, Rules.Op.DELETE, caller)
                        val id = call.idParam()
                        io { deleteRecord(collection, caller, id) }
                        call.respond(HttpStatusCode.NoContent)
                    }
                    // Geçmiş ve çöp kutusu: gizli alanlar dahil tam kayıt içerir; yalnızca yönetici ve yetkili anahtar.
                    fun RoutingCall.page() = request.queryParameters["page"]?.toIntOrNull() ?: 1
                    fun RoutingCall.perPage() = request.queryParameters["perPage"]?.toIntOrNull() ?: 50
                    fun JsonObject.mapItems(): JsonObject = JsonObject(this + ("items" to JsonArray(getValue("items").jsonArray.map { item ->
                        val o = item.jsonObject
                        JsonObject(o + ("data" to ((o["data"] as? JsonObject)?.let(n::out) ?: JsonNull)))
                    })))
                    endpoint(paths, collection, CollectionEndpoint.HISTORY) {
                        archiveScope(collection, Rules.Op.VIEW, call.caller())
                        call.respond(io { svc.history(call.idParam(), call.page(), call.perPage()) }.mapItems())
                    }
                    endpoint(paths, collection, CollectionEndpoint.REVERT) {
                        val scope = archiveScope(collection, Rules.Op.UPDATE, call.caller())
                        val version = call.parameters["version"]?.toLongOrNull() ?: throw ApiException(400, tr("version sayı olmalı"))
                        val updated = io {
                            writes.write { svc.revert(call.idParam(), version, scope) }.also { changed(collection.name, RecordEvent.Action.UPDATE, it) }
                        }
                        call.audit("record.revert", "${collection.name}#${call.idParam()}", tr("sürüm {0}", version))
                        call.respond(outRecord(collection, updated))
                    }
                    endpoint(paths, collection, CollectionEndpoint.TRASH) {
                        archiveScope(collection, Rules.Op.LIST, call.caller())
                        call.respond(io { svc.trash(call.page(), call.perPage()) }.mapItems())
                    }
                    endpoint(paths, collection, CollectionEndpoint.RESTORE) {
                        val scope = archiveScope(collection, Rules.Op.CREATE, call.caller())
                        val restored = io {
                            writes.write { svc.restore(call.idParam(), scope) }.also { changed(collection.name, RecordEvent.Action.CREATE, it) }
                        }
                        call.audit("record.restore", "${collection.name}#${call.idParam()}")
                        call.respond(outRecord(collection, restored))
                    }
                    endpoint(paths, collection, CollectionEndpoint.PURGE) {
                        archiveScope(collection, Rules.Op.DELETE, call.caller())
                        io { writes.write { svc.purge(call.idParam()) } }
                        call.audit("record.purge", "${collection.name}#${call.idParam()}")
                        call.respond(HttpStatusCode.NoContent)
                    }
                }
            }
        }
    }
}

/**
 * Son kullanıcı uçları (`auth.users.enabled`):
 *   POST   /auth/signup    {email, password, ...ek alanlar}   → 201 {token, refreshToken, expiresIn, user}
 *   POST   /auth/login     {email, password}                  → {token, refreshToken, expiresIn, user}
 *   POST   /auth/refresh   {refreshToken}                     → yeni token çifti (eski yenileme token'ı geçersizleşir)
 *   POST   /auth/logout    {refreshToken}                     → 204
 *   POST   /auth/request-verification  {email} (veya kullanıcı token'ıyla)  → 202 (e-posta açıksa)
 *   POST   /auth/confirm-verification  {token}                              → kullanıcı
 *   POST   /auth/request-reset         {email}                              → 202 (hesap yoksa da)
 *   POST   /auth/confirm-reset         {token, password}                    → 204 (tüm oturumlar kapanır)
 *   GET    /auth/me                                           → kullanıcı
 *   PATCH  /auth/me        {...ek alanlar, password, currentPassword}
 *   GET    /auth/users     ?page=&perPage=&email=             (yönetici)
 *   POST   /auth/users     {email, password, role, verified, ...}   (yönetici)
 *   PATCH  /auth/users/{id}                                   (yönetici; parola sıfırlama dahil)
 *   DELETE /auth/users/{id}                                   (yönetici)
 */
private fun Route.authRoutes(
    users: UserService,
    writes: WriteGate,
    mailer: Mailer?,
    config: ServerConfig,
    oauth: OAuthService?,
    paths: ApiPaths,
    n: ApiNames,
    phone: PhoneService?,
    smsGuard: SmsGuard,
    caller: suspend RoutingContext.() -> Caller,
) {
    /** Gövde API adlarından özgün adlara; doğrulama hatalarındaki alan adları API adlarıyla. */
    suspend fun RoutingCall.body(): JsonObject = n.inBody(receive<JsonObject>())
    fun UserService.LoginResult.out(): JsonObject = toJson().let { j ->
        if (this is UserService.Tokens) JsonObject(j + ("user" to n.out(user))) else j
    }
    suspend fun <T> translated(block: suspend () -> T): T = try {
        block()
    } catch (e: ApiException) {
        throw n.error(e)
    }

    /**
     * Hesap e-postası arka planda gönderilir: yanıt, hesap var olsa da olmasa da aynı ve aynı hızda döner
     * (adresin kayıtlı olup olmadığı sızmaz).
     */
    fun RoutingContext.mailLater(email: String?, purpose: UserService.Purpose) {
        val m = mailer ?: return
        val address = email?.trim()?.lowercase() ?: return
        call.application.launch(Dispatchers.IO) {
            val (id, verified) = users.findByEmail(address) ?: return@launch
            if (purpose == UserService.Purpose.VERIFY && verified) return@launch
            if (!m.allow(address, purpose.claim)) return@launch
            val token = users.purposeToken(id, purpose)
            if (purpose == UserService.Purpose.VERIFY) m.verification(address, token) else m.reset(address, token)
        }
    }

    run {
        if (oauth != null) oauthRoutes(oauth, users, writes, config, paths, n)
        endpoint(paths, Endpoint.AUTH_SIGNUP) {
            val body = call.body()
            // Doğrulama zorunluysa oturum verilmez: e-postadaki bağlantıdan sonra giriş yapılır.
            val verifyFirst = config.auth.users.requireVerified
            val result = translated {
                io {
                    writes.write {
                        if (verifyFirst) buildJsonObject { put("user", n.out(users.register(body))); put("verificationRequired", JsonPrimitive(true)) }
                        else users.signup(body).out()
                    }
                }
            }
            if (config.auth.users.sendVerification || verifyFirst) mailLater(body.text("email"), UserService.Purpose.VERIFY)
            call.respond(HttpStatusCode.Created, result)
        }
        if (mailer != null) {
            val accepted = buildJsonObject { put("message", JsonPrimitive(tr("Bu adresle bir hesap varsa e-posta gönderildi"))) }
            endpoint(paths, Endpoint.AUTH_REQUEST_VERIFICATION) {
                val who = caller()
                val email = if (who is Caller.User) io { users.me(who.id) }.text("email")
                else runCatching { call.body() }.getOrNull()?.text("email")
                mailLater(email, UserService.Purpose.VERIFY)
                call.respond(HttpStatusCode.Accepted, accepted)
            }
            endpoint(paths, Endpoint.AUTH_CONFIRM_VERIFICATION) {
                val body = call.receive<JsonObject>()
                call.respond(n.out(io { writes.write { users.confirmVerification(body.text("token")) } }))
            }
            endpoint(paths, Endpoint.AUTH_REQUEST_RESET) {
                mailLater(call.body().text("email"), UserService.Purpose.RESET)
                call.respond(HttpStatusCode.Accepted, accepted)
            }
            endpoint(paths, Endpoint.AUTH_CONFIRM_RESET) {
                val body = call.body()
                translated { io { writes.write { users.confirmReset(body.text("token"), body.text("password")) } } }
                call.respond(HttpStatusCode.NoContent)
            }
        }
        endpoint(paths, Endpoint.AUTH_LOGIN) {
            val body = call.body()
            val client = origin(call.request).key
            call.respond(io { writes.write { users.login(client, body.text("email"), body.text("password")).out() } })
        }
        // İki adımlı doğrulama: girişte doğrulayıcı kodu; kullanıcı kendi kurar, kapatır, yedek kod yeniler.
        endpoint(paths, Endpoint.AUTH_2FA_VERIFY) {
            val body = call.receive<JsonObject>()
            val client = origin(call.request).key
            call.respond(io { writes.write { users.verifyTwoFactor(client, body.text("challenge"), body.text("code")).out() } })
        }
        endpoint(paths, Endpoint.AUTH_2FA_STATUS) {
            val id = requireUser(caller())
            call.respond(io { users.twoFactorStatus(id) })
        }
        endpoint(paths, Endpoint.AUTH_2FA_SETUP) {
            val id = requireUser(caller())
            call.respond(io { writes.write { users.twoFactorSetup(id) } })
        }
        endpoint(paths, Endpoint.AUTH_2FA_ENABLE) {
            val id = requireUser(caller())
            val body = call.receive<JsonObject>()
            call.respond(io { writes.write { users.twoFactorEnable(id, body.text("code")) } })
        }
        endpoint(paths, Endpoint.AUTH_2FA_DISABLE) {
            val id = requireUser(caller())
            val body = call.receive<JsonObject>()
            io { writes.write { users.twoFactorDisable(id, body.text("code")) } }
            call.respond(HttpStatusCode.NoContent)
        }
        endpoint(paths, Endpoint.AUTH_2FA_RECOVERY) {
            val id = requireUser(caller())
            val body = call.receive<JsonObject>()
            call.respond(io { writes.write { users.regenerateRecoveryCodes(id, body.text("code")) } })
        }
        // E-postayla kodla giriş: istek, hesap olsa da olmasa da aynı yanıtı verir.
        if (mailer != null) {
            endpoint(paths, Endpoint.AUTH_REQUEST_LOGIN_CODE) {
                val email = call.body().text("email")
                val m = mailer
                call.application.launch(Dispatchers.IO) {
                    val address = email?.trim()?.lowercase() ?: return@launch
                    if (users.findByEmail(address) == null || !m.allow(address, "login")) return@launch
                    val (to, code) = writes.write { users.newLoginCode(address) } ?: return@launch
                    m.loginCode(to, code, config.auth.users.emailLoginUrl)
                }
                call.respond(HttpStatusCode.Accepted, buildJsonObject { put("message", JsonPrimitive(tr("Bu adresle bir hesap varsa giriş kodu gönderildi"))) })
            }
            endpoint(paths, Endpoint.AUTH_LOGIN_CODE) {
                val body = call.body()
                val client = origin(call.request).key
                call.respond(io { writes.write { users.loginWithCode(client, body.text("email"), body.text("code")).out() } })
            }
        }
        // SMS ile kodla giriş: biçim ve sınırlar hemen denetlenir; hesap var olsa da olmasa da yanıt aynıdır.
        if (phone != null && config.auth.users.smsLogin) {
            endpoint(paths, Endpoint.AUTH_REQUEST_SMS_CODE) {
                val number = SmsGuard.normalize(call.body().text("phone"))
                    ?: throw ApiException(400, tr("Doğrulama hatası"), mapOf("phone" to tr("uluslararası biçimde olmalı, örn. +905551234567")))
                if (config.phone.smsPrefixes.none { number.startsWith(it) }) {
                    throw ApiException(400, tr("Doğrulama hatası"), mapOf("phone" to tr("bu ülke koduna SMS gönderilmiyor")))
                }
                smsGuard.check(number, origin(call.request).key)
                call.application.launch(Dispatchers.IO) {
                    runCatching {
                        val code = writes.write { users.newSmsCode(number) } ?: return@runCatching
                        phone.sendSms(number, tr("{0} giriş kodunuz: {1}. 10 dakika geçerli; kimseyle paylaşmayın.", config.name, code), "auth.sms")
                    }
                }
                call.respond(HttpStatusCode.Accepted, buildJsonObject { put("message", JsonPrimitive(tr("Bu numarayla bir hesap varsa giriş kodu SMS ile gönderildi"))) })
            }
            endpoint(paths, Endpoint.AUTH_SMS_CODE) {
                val body = call.body()
                val client = origin(call.request).key
                call.respond(io { writes.write { users.loginWithSms(client, body.text("phone"), body.text("code")).out() } })
            }
        }
        endpoint(paths, Endpoint.AUTH_REFRESH) {
            val body = call.receive<JsonObject>()
            call.respond(io { writes.write { users.refresh(body.text("refreshToken")).out() } })
        }
        endpoint(paths, Endpoint.AUTH_LOGOUT) {
            val body = call.receive<JsonObject>()
            io { writes.write { users.logout(body.text("refreshToken")) } }
            call.respond(HttpStatusCode.NoContent)
        }
        endpoint(paths, Endpoint.AUTH_ME) {
            val id = requireUser(caller())
            call.respond(n.out(io { users.me(id) }))
        }
        endpoint(paths, Endpoint.AUTH_ME_UPDATE) {
            val id = requireUser(caller())
            val body = call.body()
            call.respond(n.out(translated { io { writes.write { users.updateMe(id, body) } } }))
        }
        run {
            endpoint(paths, Endpoint.USERS_LIST) {
                requireAdmin(caller())
                val q = call.request.queryParameters
                val page = io { users.list(q["page"]?.toIntOrNull() ?: 1, q["perPage"]?.toIntOrNull() ?: 20, q[n.api("email") ?: "email"] ?: q["email"]) }
                call.respond(page.copy(items = page.items.map(n::out)).toJson())
            }
            endpoint(paths, Endpoint.USERS_CREATE) {
                requireAdmin(caller())
                val body = call.body()
                val by = Access.actor(caller())
                val ip = origin(call.request).key
                call.respond(HttpStatusCode.Created, n.out(translated { io { writes.write { users.create(body, by, ip) } } }))
            }
            endpoint(paths, Endpoint.USERS_UPDATE) {
                requireAdmin(caller())
                val id = call.idParam()
                val body = call.body()
                val by = Access.actor(caller())
                val ip = origin(call.request).key
                call.respond(n.out(translated { io { writes.write { users.adminUpdate(id, body, by, ip) } } }))
            }
            endpoint(paths, Endpoint.USERS_DELETE) {
                requireAdmin(caller())
                val id = call.idParam()
                val by = Access.actor(caller())
                val ip = origin(call.request).key
                io { writes.write { users.delete(id, by, ip) } }
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

private val CALLER = AttributeKey<Caller>("droidserver.caller")

/** Script'teki `routerAdd` ucu: erişim denetimi, istek nesnesi, işleyicinin yanıtı. */
private fun Route.scriptRoute(scripts: ScriptRuntime, r: ScriptRoute, caller: suspend RoutingContext.() -> Caller) {
    val path = r.path.split('/').joinToString("/") { if (it.startsWith(":")) "{${it.drop(1)}}" else it }
    route(path, HttpMethod.parse(r.method)) {
        handle {
            val who = caller()
            when (r.access) {
                Rule.AUTH -> if (who is Caller.Anonymous) throw ApiException(401, tr("Bu uç için giriş yapın"))
                Rule.ADMIN -> if (!who.isAdmin) requireAdmin(who)
                else -> {}
            }
            if (r.role != null && !who.isAdmin && (who as? Caller.User)?.role != r.role) {
                throw ApiException(403, tr("Bu uç '{0}' rolüne açık", r.role))
            }
            val text = if (r.method == "GET") "" else call.receiveText()
            val body = text.takeIf { it.isNotBlank() }?.let { runCatching { ConfigJson.parseToJsonElement(it) }.getOrDefault(JsonPrimitive(it)) }
            val params = r.path.split('/').filter { it.startsWith(":") }.associate { it.drop(1) to call.parameters[it.drop(1)].orEmpty() }
            val request = ScriptRequest(
                method = r.method,
                path = call.request.path(),
                params = params,
                query = call.request.queryParameters.entries().associate { it.key to it.value },
                headers = call.request.headers.entries().associate { it.key.lowercase() to it.value.joinToString(",") },
                body = body,
            )
            val reply = io { scripts.runRoute(r, request, who) }
            reply.headers.filterKeys { it.lowercase() !in setOf("content-length", "content-type", "transfer-encoding") }
                .forEach { (k, v) -> call.response.headers.append(k, v) }
            call.respond(HttpStatusCode.fromValue(reply.status), reply.body)
        }
    }
}

/**
 * `ctx.db`: yönetici yetkisiyle kayıt servisleri; script kayıt olaylarını tetiklemez (sonsuz döngü olmasın), canlı
 * abonelere ise yayınlanır.
 */
private class ScriptDbBridge(
    private val services: Map<String, RecordService>,
    private val writes: WriteGate,
    private val changed: (String, RecordEvent.Action, JsonObject) -> Unit,
) : ScriptDb {
    private fun svc(name: String) = services[name] ?: throw ApiException(400, tr("ctx.db: '{0}' koleksiyonu yok", name))

    override fun list(collection: String, query: JsonObject): JsonObject {
        fun text(key: String) = (query[key] as? JsonPrimitive)?.content
        val filter = (query["filter"] as? JsonObject)?.map { (k, v) -> k to ((v as? JsonPrimitive)?.content ?: v.toString()) }.orEmpty()
        return svc(collection).list(
            page = text("page")?.toDoubleOrNull()?.toInt() ?: 1,
            perPage = text("perPage")?.toDoubleOrNull()?.toInt() ?: 50,
            sort = text("sort"),
            params = filter + listOfNotNull(text("q")?.let { "q" to it }),
        ).toJson()
    }

    override fun get(collection: String, id: Long) = svc(collection).get(id)

    override fun create(collection: String, data: JsonObject) = writes.write { svc(collection).create(data) }
        .also { changed(collection, RecordEvent.Action.CREATE, it) }

    override fun update(collection: String, id: Long, data: JsonObject) = writes.write { svc(collection).update(id, data) }
        .also { changed(collection, RecordEvent.Action.UPDATE, it) }

    override fun delete(collection: String, id: Long) {
        val current = svc(collection).get(id)
        writes.write { svc(collection).delete(id) }
        changed(collection, RecordEvent.Action.DELETE, current)
    }
}

/** Statik site: API yolu hariç [StaticConfig.path] altındaki GET istekleri `files/www`'den. */
private fun Route.staticSite(config: ServerConfig, site: StaticSite) {
    route(config.static.path) {
        get("{path...}") {
            val path = call.request.path()
            if (StaticConfig.underPath(path, config.server.basePath)) throw ApiException(404, tr("Bulunamadı"))
            val segments = call.parameters.getAll("path").orEmpty()
            val file = io { site.resolve(segments, config.static.spaFallback) }
            if (file == null) {
                call.respondText(tr("Bulunamadı"), status = HttpStatusCode.NotFound)
                return@get
            }
            // Tarayıcı her seferinde yeniden ister (site uygulamadan güncellenebilir).
            call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
            call.respond(LocalFileContent(file, ContentType.defaultForFile(file)))
        }
    }
}

/**
 * İstek gövdesi: JSON veya (dosya yüklemede) multipart/form-data. Multipart'ta dosya parçaları geçici dosyalara akıtılır;
 * alanın boyut sınırı akış sırasında uygulanır (aşılırsa okuma durur, 413), içerik tipi ilk baytlardan tespit edilir.
 * Diğer parçalar alan tipine göre JSON değere çevrilir.
 */
private suspend fun receiveBody(call: RoutingCall, collection: CollectionConfig, store: FileStore?, maxBody: Long): Pair<JsonObject, List<Upload>> {
    if (call.request.contentType().match(ContentType.MultiPart.FormData).not()) return call.receive<JsonObject>() to emptyList()
    if (store == null) throw ApiException(503, tr("Dosya deposu yok; dosya yüklenemez"))
    val values = linkedMapOf<String, JsonElement>()
    val uploads = mutableListOf<Upload>()
    var parts = 0
    // Ktor'un parça sınırı dosya parçalarına da uygulanır: en büyük dosya sınırının biraz üstü (alanın kendi sınırı
    // receiveFile'da, akış içinde uygulanır). Metin parçaları ayrıca gövde sınırıyla sınırlanır.
    val partLimit = maxOf(maxBody, collection.fields.filter { it.type == FieldType.FILE }.maxOfOrNull { it.maxBytes } ?: 0L) + 64 * 1024
    try {
        call.receiveMultipart(formFieldLimit = partLimit).forEachPart { part ->
            try {
                if (++parts > MAX_PARTS) throw ApiException(400, tr("Çok fazla form parçası (en çok {0})", MAX_PARTS))
                val name = part.name ?: throw ApiException(400, tr("Adsız form parçası"))
                when (part) {
                    is PartData.FormItem -> {
                        if (part.value.length > maxBody) throw ApiException(413, tr("'{0}' çok büyük (en çok {1} KB)", name, maxBody / 1024))
                        values[name] = formValue(collection, name, part.value)
                    }
                    is PartData.FileItem -> {
                        val f = collection.fields.firstOrNull { it.name == name && it.type == FieldType.FILE }
                            ?: throw ApiException(400, tr("Doğrulama hatası"), mapOf(name to tr("dosya alanı değil")))
                        val count = uploads.count { it.field == name }
                        if (!f.isMultiple && count >= 1 || count >= FileLimits.MAX_FILES) {
                            throw ApiException(400, tr("Doğrulama hatası"), mapOf(name to if (f.isMultiple) tr("en çok {0} dosya", FileLimits.MAX_FILES) else tr("bu alan tek dosya alır")))
                        }
                        uploads += receiveFile(part, f, store)
                    }
                    else -> {}
                }
            } finally {
                part.dispose()
            }
        }
    } catch (t: Throwable) {
        uploads.forEach { it.temp.delete() }
        throw t
    }
    return JsonObject(values) to uploads
}

private const val MAX_PARTS = 100

private suspend fun receiveFile(part: PartData.FileItem, f: FieldConfig, store: FileStore): Upload {
    val temp = store.newTemp()
    try {
        val head = ByteArrayOutputStream()
        var size = 0L
        val channel = part.provider()
        withContext(Dispatchers.IO) {
            temp.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = channel.readAvailable(buffer, 0, buffer.size)
                    if (n < 0) break
                    size += n
                    if (size > f.maxBytes) {
                        throw ApiException(413, tr("'{0}' dosyası en çok {1} MB olabilir", f.name, f.maxBytes / 1024 / 1024))
                    }
                    if (head.size() < MimeTypes.HEAD_BYTES) head.write(buffer, 0, minOf(n, MimeTypes.HEAD_BYTES - head.size()))
                    out.write(buffer, 0, n)
                }
            }
        }
        fun invalid(message: String): Nothing = throw ApiException(400, tr("Doğrulama hatası"), mapOf(f.name to message))
        if (size == 0L) invalid(tr("boş dosya"))
        val original = part.originalFileName.orEmpty().substringAfterLast('/').substringAfterLast('\\')
        val kind = MimeTypes.sniff(head.toByteArray(), original.substringAfterLast('.', "").ifEmpty { null })
        MimeTypes.extensionMismatch(original, kind)?.let(::invalid)
        if (!MimeTypes.matches(kind.mime, f.mimeTypes)) {
            invalid(tr("izin verilmeyen içerik tipi: {0} (izinli: {1})", kind.mime, f.mimeTypes!!.joinToString()))
        }
        return Upload(f.name, temp, kind, original, size, store.newName(original, kind.ext))
    } catch (t: Throwable) {
        temp.delete()
        throw t
    }
}

/** Form alanının metnini alan tipine göre JSON değere çevirir; çevrilemezse metin olarak bırakılır (doğrulama hata verir). */
private fun formValue(collection: CollectionConfig, name: String, raw: String): JsonElement {
    val f = collection.fields.firstOrNull { it.name == name } ?: return JsonPrimitive(raw)
    return when (f.type) {
        FieldType.TEXT -> JsonPrimitive(raw)
        FieldType.FILE -> if (raw.isBlank()) JsonNull else if (raw.trimStart().startsWith("[")) StrictJson.parse(raw) ?: JsonPrimitive(raw) else JsonPrimitive(raw)
        FieldType.RELATION -> if (raw.isBlank()) JsonNull else raw.trim().toLongOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(raw)
        else -> runCatching { FieldDefaults.parse(f.type, raw) ?: JsonNull }.getOrElse { JsonPrimitive(raw) }
    }
}

/**
 * İzinli boyutta küçük resim (önbellekte yoksa üretilir). Görsel değilse, üretici yoksa veya üretilemezse null
 * (özgün dosya döner).
 */
private fun thumbnail(store: FileStore, resizer: ImageResizer?, collection: String, id: Long, name: String, mime: String, size: String): File? {
    if (resizer == null || !MimeTypes.isResizable(mime)) return null
    val target = store.thumb(collection, id, name, size)
    if (target.isFile) return target
    val (w, h) = FileLimits.THUMB.matchEntire(size)!!.destructured
    target.parentFile.mkdirs()
    val temp = File(target.parentFile, ".${target.name}.${System.nanoTime()}")
    return try {
        if (resizer.resize(store.file(collection, id, name)!!, temp, w.toInt(), h.toInt()) && temp.renameTo(target)) target else null
    } catch (_: Exception) {
        null
    } finally {
        temp.delete()
    }
}

/**
 * Sosyal giriş: `GET /auth/oauth/providers`, `GET /auth/oauth/{sağlayıcı}?redirect=` (sağlayıcıya yönlendirir),
 * `GET /auth/oauth/{sağlayıcı}/callback` (dönüş; istemciye `?code=` veya `?error=`), `POST /auth/oauth/exchange {code}`.
 */
private fun Route.oauthRoutes(oauth: OAuthService, users: UserService, writes: WriteGate, config: ServerConfig, paths: ApiPaths, n: ApiNames) {
    val secure = config.auth.users.oauth.publicUrl.startsWith("https://")
    val start = paths.path(Endpoint.OAUTH_START)
    // Çerez yalnızca sağlayıcının dönüş adresine gider.
    fun cookiePath(provider: String) = paths.path(Endpoint.OAUTH_CALLBACK)?.let { paths.full(it.replace("{provider}", provider)) } ?: "/"
    run {
        endpoint(paths, Endpoint.OAUTH_PROVIDERS) {
            call.respond(JsonArray(oauth.providers.map { p ->
                buildJsonObject {
                    put("provider", JsonPrimitive(p.provider.name.lowercase()))
                    put("title", JsonPrimitive(p.provider.title))
                    start?.let { put("url", JsonPrimitive(paths.full(it.replace("{provider}", p.provider.name.lowercase())))) }
                }
            }))
        }
        endpoint(paths, Endpoint.OAUTH_EXCHANGE) {
            call.respond(oauth.exchange(call.receive<JsonObject>().text("code")))
        }
        endpoint(paths, Endpoint.OAUTH_START) {
            val provider = call.parameters["provider"].orEmpty()
            val (url, state) = oauth.start(provider, call.request.queryParameters["redirect"])
            call.response.cookies.append(
                Cookie(OAuthService.COOKIE, state, maxAge = 600, path = cookiePath(provider.lowercase()), secure = secure, httpOnly = true, extensions = mapOf("SameSite" to "Lax")),
            )
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            call.respondRedirect(url)
        }
        endpoint(paths, Endpoint.OAUTH_CALLBACK) {
            val q = call.request.queryParameters
            val provider = call.parameters["provider"].orEmpty()
            call.response.cookies.append(Cookie(OAuthService.COOKIE, "", maxAge = 0, path = cookiePath(provider.lowercase()), secure = secure, httpOnly = true))
            var redirect: String? = null
            try {
                val (email, target) = io {
                    oauth.callback(call.parameters["provider"].orEmpty(), q["code"], q["state"], call.request.cookies[OAuthService.COOKIE], q["error"])
                }
                redirect = target
                val tokens = io { writes.write { users.oauthLogin(email) } }
                val code = oauth.issueCode(tokens.toJson().let { j -> if (tokens is UserService.Tokens) JsonObject(j + ("user" to n.out(tokens.user))) else j })
                if (target != null) {
                    call.respondRedirect(oauth.redirectWith(target, "code", code))
                } else {
                    call.page(HttpStatusCode.OK, config.name, tr("Giriş başarılı"), tr("<p>{0} ile giriş yapıldı. Bu pencereyi kapatabilirsiniz.</p>", esc(email)))
                }
            } catch (e: Exception) {
                if (e !is OAuthException && e !is ApiException) throw e
                val target = (e as? OAuthException)?.redirect ?: redirect
                val message = e.message ?: tr("Giriş başarısız")
                if (target != null) call.respondRedirect(oauth.redirectWith(target, "error", message))
                else call.page(HttpStatusCode.BadRequest, config.name, tr("Giriş başarısız"), "<p class=\"e\">${esc(message)}</p>")
            }
        }
    }
}

/**
 * Web push: `GET /push/key` (VAPID açık anahtarı), `POST /push/subscribe` ve `/push/unsubscribe` (kullanıcı token'ı,
 * gövde `PushSubscription.toJSON()`), `POST /push/send {user, title, body?, url?, data?}` (yönetici).
 */
private fun Route.pushRoutes(push: PushService, paths: ApiPaths, caller: suspend RoutingContext.() -> Caller) {
    run {
        endpoint(paths, Endpoint.PUSH_KEY) { call.respond(buildJsonObject { put("publicKey", JsonPrimitive(push.publicKey)) }) }
        endpoint(paths, Endpoint.PUSH_SUBSCRIBE) {
            val user = requireUser(caller())
            val body = call.receive<JsonObject>()
            io { push.subscribe(user, body) }
            call.respond(HttpStatusCode.Created, buildJsonObject { put("devices", JsonPrimitive(io { push.count(user) })) })
        }
        endpoint(paths, Endpoint.PUSH_UNSUBSCRIBE) {
            val user = requireUser(caller())
            val endpoint = call.receive<JsonObject>().text("endpoint")
            io { push.unsubscribe(user, endpoint) }
            call.respond(HttpStatusCode.NoContent)
        }
        endpoint(paths, Endpoint.PUSH_SEND) {
            requireAdmin(caller())
            val body = call.receive<JsonObject>()
            val user = (body["user"] as? JsonPrimitive)?.longOrNull ?: throw ApiException(400, tr("user (kullanıcı kimliği) gerekli"))
            val title = body.text("title")?.takeIf { it.isNotBlank() } ?: throw ApiException(400, tr("title gerekli"))
            val sent = io { push.sendToUser(user, push.message(title, body.text("body"), body.text("url"), body["data"])) }
            call.respond(buildJsonObject { put("devices", JsonPrimitive(sent)) })
        }
    }
}

/** Kayıt defterindeki yolla uç; kapalıysa kaydedilmez (404). */
internal fun Route.endpoint(paths: ApiPaths, e: Endpoint, body: suspend RoutingContext.() -> Unit) {
    val p = paths.path(e) ?: return
    route(p, HttpMethod.parse(e.method)) { handle(body) }
}

private fun Route.endpoint(paths: ApiPaths, c: CollectionConfig, e: CollectionEndpoint, body: suspend RoutingContext.() -> Unit) {
    val p = paths.path(c, e) ?: return
    route(p, HttpMethod.parse(e.method)) { handle(body) }
}

/** Geçmiş ve çöp kutusu kuralı aşan kapsam ister: yönetici veya koleksiyonda yetkili anahtar. */
private fun archiveScope(collection: CollectionConfig, op: Rules.Op, caller: Caller): Scope {
    val scope = Access.scope(collection, op, caller)
    if (scope.admin) return scope
    if (caller is Caller.Anonymous) throw ApiException(401, tr("Geçmiş ve çöp kutusu için yönetici kimliği gerekli"))
    throw ApiException(403, tr("Geçmiş ve çöp kutusu yalnızca yönetici içindir"))
}

internal fun requireAdmin(caller: Caller) {
    if (caller.isAdmin) return
    if (caller is Caller.Anonymous) throw ApiException(401, tr("Yönetici kimliği gerekli (API anahtarı veya yönetici token'ı)"))
    if (caller is Caller.Key) throw ApiException(403, tr("'{0}' anahtarı yönetici işlemleri için yetkili değil (tam yetkili anahtar gerekir)", caller.name))
    throw ApiException(403, tr("Bu işlem yalnızca yönetici içindir"))
}

private fun requireUser(caller: Caller): Long = when (caller) {
    is Caller.User -> caller.id
    Caller.Anonymous -> throw ApiException(401, tr("Kullanıcı token'ı gerekli (Authorization: Bearer <token>)"))
    Caller.Admin, is Caller.Key -> throw ApiException(400, tr("Bu uç kullanıcı token'ı ister; API anahtarının kullanıcı hesabı yok"))
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** JWT modunda istemcilere token üretmek için (uygulama arayüzündeki "Token oluştur" düğmesi). */
fun issueJwt(config: ServerConfig, subject: String, ttlSeconds: Long = 30L * 24 * 3600): String {
    val auth = config.auth
    require(auth.mode == AuthMode.JWT) { tr("auth.mode jwt değil") }
    return JWT.create()
        .withIssuer(auth.jwtIssuer)
        .withAudience(auth.jwtAudience)
        .withSubject(subject)
        .withExpiresAt(Date(System.currentTimeMillis() + ttlSeconds * 1000))
        .sign(Algorithm.HMAC256(auth.jwtSecret!!))
}

private fun RoutingCall.idParam(): Long =
    parameters["id"]?.toLongOrNull() ?: throw ApiException(400, tr("id sayı olmalı"))

/** Kopyanın kendi API adresi: bağlantının IP'si ile `X-Replica-Port` ve `X-Replica-Path` başlıkları. */
private fun peerUrl(call: io.ktor.server.application.ApplicationCall): String? {
    val port = call.request.headers["X-Replica-Port"]?.toIntOrNull()?.takeIf { it in 1..65_535 } ?: return null
    val path = call.request.headers["X-Replica-Path"]?.takeIf { Regex("^/[A-Za-z0-9/_.~-]{0,63}$").matches(it) } ?: return null
    val ip = origin(call.request).key.let { if (it == ClientGuard.LOCAL) "127.0.0.1" else it }
    return "http://${if (ip.contains(':')) "[$ip]" else ip}:$port${path.trimEnd('/')}"
}

internal suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

internal fun apiError(status: Int, message: String, details: Map<String, String> = emptyMap()) = buildJsonObject {
    put("status", JsonPrimitive(status))
    put("message", JsonPrimitive(message))
    if (details.isNotEmpty()) put("details", JsonObject(details.mapValues { JsonPrimitive(it.value) }))
}

private const val MAX_BATCH = 100

/** Toplu işlemdeki bir istek: kuralı ve kapsamı önceden denetlenmiş. */
private data class BatchOp(
    val index: Int,
    val action: RecordEvent.Action,
    val collection: CollectionConfig,
    val svc: RecordService,
    val id: Long?,
    val body: JsonObject,
    val scope: Scope,
)

private fun batchOp(i: Int, el: JsonElement, byName: Map<String, CollectionConfig>, services: Map<String, RecordService>, caller: Caller): BatchOp {
    fun bad(msg: String): Nothing = throw ApiException(400, tr("İstek {0}: {1}", i + 1, msg), mapOf("index" to i.toString()))
    val o = el as? JsonObject ?: bad(tr("nesne bekleniyor"))
    val method = (o["method"] as? JsonPrimitive)?.content?.uppercase() ?: bad(tr("method gerekli (POST, PATCH, DELETE)"))
    val name = (o["collection"] as? JsonPrimitive)?.content ?: bad(tr("collection gerekli"))
    val collection = byName[name] ?: bad(tr("'{0}' koleksiyonu yok", name))
    val action = when (method) {
        "POST" -> RecordEvent.Action.CREATE
        "PATCH" -> RecordEvent.Action.UPDATE
        "DELETE" -> RecordEvent.Action.DELETE
        else -> bad(tr("method POST, PATCH veya DELETE olmalı"))
    }
    // Dosyalar işlem geri alınsa da diskten silinmiş olabileceği için dosya alanlı koleksiyonlar toplu işleme alınmaz.
    if (collection.fields.any { it.type == FieldType.FILE }) bad(tr("'{0}' dosya alanı içeriyor; tek tek gönderin", name))
    if (action == RecordEvent.Action.DELETE && cascadeReachesFiles(name, byName.values)) {
        bad(tr("'{0}' silinince dosya alanlı kayıtlar da zincirleme silinir; tek tek gönderin", name))
    }
    val id = o["id"]?.let { (it as? JsonPrimitive)?.content?.toLongOrNull() ?: bad(tr("id sayı olmalı")) }
    if (action != RecordEvent.Action.CREATE && id == null) bad(tr("{0} için id gerekli", method))
    val body = when (val b = o["body"]) {
        null, is JsonNull -> JsonObject(emptyMap())
        is JsonObject -> b
        else -> bad(tr("body nesne olmalı"))
    }
    val op = when (action) {
        RecordEvent.Action.CREATE -> Rules.Op.CREATE
        RecordEvent.Action.UPDATE -> Rules.Op.UPDATE
        RecordEvent.Action.DELETE -> Rules.Op.DELETE
    }
    val scope = try {
        Access.scope(collection, op, caller)
    } catch (e: ApiException) {
        throw ApiException(e.status, tr("İstek {0}: {1}", i + 1, e.message), mapOf("index" to i.toString()))
    }
    return BatchOp(i, action, collection, services.getValue(name), id, body, scope)
}

/** İşlem içinde çalışır; script `before` olayları dahil. Silmede silinen kaydı döndürür. */
private fun runBatchOp(op: BatchOp, scripts: ScriptRuntime?, caller: Caller): JsonObject {
    val name = op.collection.name
    return when (op.action) {
        RecordEvent.Action.CREATE -> {
            val data = scripts?.takeIf { it.hasHook(HookEvent.BEFORE_CREATE, name) }?.before(HookEvent.BEFORE_CREATE, name, op.body, null, caller) ?: op.body
            op.svc.create(data, op.scope)
        }
        RecordEvent.Action.UPDATE -> {
            val data = scripts?.takeIf { it.hasHook(HookEvent.BEFORE_UPDATE, name) }
                ?.before(HookEvent.BEFORE_UPDATE, name, op.body, op.svc.current(op.id!!, op.scope), caller) ?: op.body
            op.svc.update(op.id!!, data, op.scope)
        }
        RecordEvent.Action.DELETE -> {
            val current = op.svc.current(op.id!!, op.scope)
            scripts?.takeIf { it.hasHook(HookEvent.BEFORE_DELETE, name) }?.before(HookEvent.BEFORE_DELETE, name, current, current, caller)
            op.svc.delete(op.id, op.scope)
            current
        }
    }
}

/** [collection]'dan `cascade` ilişkileriyle silinmeye ulaşılan koleksiyonlardan biri dosya alanı içeriyor mu. */
private fun cascadeReachesFiles(collection: String, all: Collection<CollectionConfig>): Boolean {
    val seen = mutableSetOf(collection)
    val queue = ArrayDeque(listOf(collection))
    while (queue.isNotEmpty()) {
        val target = queue.removeFirst()
        for (c in all) {
            val cascades = c.fields.any { it.type == FieldType.RELATION && it.collection == target && it.effectiveOnDelete == OnDelete.CASCADE }
            if (!cascades) continue
            if (c.fields.any { it.type == FieldType.FILE }) return true
            if (seen.add(c.name)) queue.addLast(c.name)
        }
    }
    return false
}
