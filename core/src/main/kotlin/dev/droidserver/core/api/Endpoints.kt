package dev.droidserver.core.api

import dev.droidserver.core.config.CollectionConfig
import dev.droidserver.core.config.EndpointConfig
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.i18n.tr

/** Uçların gruplanması (uygulamada başlıklar). */
enum class EndpointGroup(val title: String) {
    SERVER(tr("Sunucu")),
    ADMIN(tr("Yönetici")),
    AUTH(tr("Hesap")),
    OAUTH(tr("Sosyal giriş")),
    PUSH(tr("Web push")),
    TEAMS(tr("Takımlar")),
}

/**
 * Yerleşik uçlar ve varsayılan yolları (API yoluna, örn. `/api`, göre). Uzman modunda her biri kapatılabilir veya
 * yolu değiştirilebilir ([ServerConfig.api]); yoldaki `{...}` parametreleri korunmalıdır.
 */
enum class Endpoint(val key: String, val method: String, val path: String, val title: String, val group: EndpointGroup) {
    HEALTH("health", "GET", "/health", tr("Sunucu durumu"), EndpointGroup.SERVER),
    BATCH("batch", "POST", "/batch", tr("Toplu işlem"), EndpointGroup.SERVER),
    GRAPHQL("graphql", "POST", "/graphql", tr("GraphQL sorgusu"), EndpointGroup.SERVER),
    GRAPHQL_SCHEMA("graphql.schema", "GET", "/graphql/schema", tr("GraphQL şeması (SDL)"), EndpointGroup.SERVER),
    GRAPHQL_WS("graphql.ws", "GET", "/graphql/ws", tr("GraphQL abonelikleri (WebSocket, graphql-transport-ws)"), EndpointGroup.SERVER),
    AI_CHAT("ai.chat", "POST", "/ai/chat", tr("YZ: sohbet"), EndpointGroup.SERVER),
    AI_ASK("ai.ask", "POST", "/ai/ask/{collection}", tr("YZ: kayıtlara soru sor"), EndpointGroup.SERVER),
    AI_SEARCH("ai.search", "GET", "/ai/search/{collection}", tr("Anlamsal arama"), EndpointGroup.SERVER),
    VIEWS("views", "GET", "/views", tr("Görünümler"), EndpointGroup.SERVER),
    VIEW("views.get", "GET", "/views/{name}", tr("Görünüm ve rapor"), EndpointGroup.SERVER),
    REALTIME("realtime", "GET", "/realtime", tr("Canlı abonelik (SSE)"), EndpointGroup.SERVER),
    REALTIME_WS("realtime.ws", "GET", "/realtime/ws", tr("WebSocket kanalları"), EndpointGroup.SERVER),
    FILES("files", "GET", "/files/{collection}/{id}/{name}", tr("Dosya indirme"), EndpointGroup.SERVER),
    OPENAPI("openapi", "GET", "/_openapi.json", tr("OpenAPI belgesi"), EndpointGroup.SERVER),
    CLI("cli", "GET", "/_cli", tr("Komut satırı aracı (Node.js)"), EndpointGroup.SERVER),
    SCHEMA("schema", "GET", "/_schema", tr("Şema"), EndpointGroup.ADMIN),
    AUDIT("audit", "GET", "/_audit", tr("Denetim kaydı"), EndpointGroup.ADMIN),
    ALERTS("alerts", "GET", "/_alerts", tr("Sağlık uyarıları"), EndpointGroup.ADMIN),
    ALERTS_TEST("alerts.test", "POST", "/_alerts/test", tr("Deneme uyarısı gönder"), EndpointGroup.ADMIN),
    REPLICATION_CONFIG("replication.config", "GET", "/_replication/config", tr("Kopya: konfigürasyon"), EndpointGroup.ADMIN),
    REPLICATION_CHANGES("replication.changes", "GET", "/_replication/changes", tr("Kopya: değişiklikler"), EndpointGroup.ADMIN),
    REPLICATION_SNAPSHOT("replication.snapshot", "GET", "/_replication/snapshot", tr("Kopya: tablo içeriği"), EndpointGroup.ADMIN),
    REPLICATION_REPLICAS("replication.replicas", "GET", "/_replication/replicas", tr("Kopyalar: gecikme ve son temas"), EndpointGroup.ADMIN),
    REPLICATION_ROLE("replication.role", "GET", "/_replication/role", tr("Kopya: sunucunun rolü"), EndpointGroup.ADMIN),
    REPLICATION_FENCE("replication.fence", "POST", "/_replication/fence", tr("Kopya: devredildin (kopyaya dön)"), EndpointGroup.ADMIN),
    REPLICATION_STEP_DOWN("replication.stepDown", "POST", "/_replication/step-down", tr("Kopya: ana rolü devretmek için yazmayı durdur"), EndpointGroup.ADMIN),
    REPLICATION_STEP_UP("replication.stepUp", "POST", "/_replication/step-up", tr("Kopya: devir iptal, yazmayı aç"), EndpointGroup.ADMIN),
    JOBS("jobs", "GET", "/_jobs", tr("Arka plan işleri"), EndpointGroup.ADMIN),
    PHONE_SMS("phone.sms", "POST", "/_phone/sms", tr("SMS gönder"), EndpointGroup.ADMIN),
    PHONE_LOCATION("phone.location", "GET", "/_phone/location", tr("Telefonun konumu"), EndpointGroup.ADMIN),
    PHONE_STATUS("phone.status", "GET", "/_phone/status", tr("Telefonun durumu"), EndpointGroup.ADMIN),
    JOBS_ENQUEUE("jobs.enqueue", "POST", "/_jobs", tr("İş ekle"), EndpointGroup.ADMIN),
    JOB_RETRY("jobs.retry", "POST", "/_jobs/{id}/retry", tr("İşi yeniden çalıştır"), EndpointGroup.ADMIN),
    JOB_CANCEL("jobs.cancel", "POST", "/_jobs/{id}/cancel", tr("İşi iptal et"), EndpointGroup.ADMIN),
    FIREWALL("firewall", "GET", "/_firewall", tr("Güvenlik duvarı: engeller"), EndpointGroup.ADMIN),
    FIREWALL_BAN("firewall.ban", "POST", "/_firewall/bans", tr("Adres engelle"), EndpointGroup.ADMIN),
    FIREWALL_UNBAN("firewall.unban", "DELETE", "/_firewall/bans/{client}", tr("Engeli kaldır"), EndpointGroup.ADMIN),
    SDK("sdk", "GET", "/_sdk/{lang}", tr("İstemci SDK'sı (ts, kotlin, dart, swift)"), EndpointGroup.ADMIN),
    METRICS("metrics", "GET", "/_metrics", tr("Metrikler"), EndpointGroup.ADMIN),
    WEBHOOKS("webhooks", "GET", "/_webhooks", tr("Webhook teslimleri"), EndpointGroup.ADMIN),
    WEBHOOK_TEST("webhooks.test", "POST", "/_webhooks/{name}/test", tr("Webhook deneme"), EndpointGroup.ADMIN),
    AUTOMATIONS("automations", "GET", "/_automations", tr("Otomasyonlar: kurallar ve son çalışmalar"), EndpointGroup.ADMIN),
    AUTOMATION_RUN("automations.run", "POST", "/_automations/{name}/run", tr("Otomasyonu şimdi çalıştır"), EndpointGroup.ADMIN),
    AI_STATUS("ai.status", "GET", "/_ai", tr("YZ: kullanım ve dizin durumu"), EndpointGroup.ADMIN),
    AI_REINDEX("ai.reindex", "POST", "/_ai/reindex", tr("Anlamsal dizini yeniden kur"), EndpointGroup.ADMIN),
    APPS("apps", "GET", "/_apps", tr("Uygulamalar: durum"), EndpointGroup.ADMIN),
    MQTT("mqtt", "GET", "/_mqtt", tr("MQTT: bağlı cihazlar ve son mesajlar"), EndpointGroup.ADMIN),
    MQTT_PUBLISH("mqtt.publish", "POST", "/_mqtt/publish", tr("MQTT: cihazlara mesaj gönder"), EndpointGroup.ADMIN),
    MQTT_SCENE("mqtt.scene", "POST", "/_mqtt/scenes/{name}", tr("MQTT: sahneyi çalıştır"), EndpointGroup.ADMIN),
    SENSORS("sensors", "GET", "/_sensors", tr("Sensör panosu: son değerler ve grafik"), EndpointGroup.ADMIN),
    SQL("sql", "POST", "/_sql", tr("Salt okunur SQL sorgusu"), EndpointGroup.ADMIN),
    SAMPLE("sample", "POST", "/_sample/{collection}", tr("Örnek veri üret"), EndpointGroup.ADMIN),
    BROADCAST("broadcast", "POST", "/_broadcast", tr("Toplu e-posta / web push"), EndpointGroup.ADMIN),
    APP_DEPLOY("apps.deploy", "POST", "/_apps/{name}/deploy", tr("Uygulamayı çek ve deploy et"), EndpointGroup.ADMIN),
    APP_HOOK("apps.hook", "POST", "/_apps/{name}/hook", tr("Uygulama: Git push webhook'u"), EndpointGroup.SERVER),

    AUTH_SIGNUP("auth.signup", "POST", "/auth/signup", tr("Kayıt ol"), EndpointGroup.AUTH),
    AUTH_LOGIN("auth.login", "POST", "/auth/login", tr("Giriş"), EndpointGroup.AUTH),
    AUTH_REFRESH("auth.refresh", "POST", "/auth/refresh", tr("Token yenile"), EndpointGroup.AUTH),
    AUTH_LOGOUT("auth.logout", "POST", "/auth/logout", tr("Çıkış"), EndpointGroup.AUTH),
    AUTH_ME("auth.me", "GET", "/auth/me", tr("Hesabım"), EndpointGroup.AUTH),
    AUTH_ME_UPDATE("auth.me.update", "PATCH", "/auth/me", tr("Hesabımı güncelle"), EndpointGroup.AUTH),
    AUTH_USAGE("auth.me.usage", "GET", "/auth/me/usage", tr("Kota kullanımım"), EndpointGroup.AUTH),
    AUTH_REQUEST_VERIFICATION("auth.requestVerification", "POST", "/auth/request-verification", tr("Doğrulama e-postası iste"), EndpointGroup.AUTH),
    AUTH_CONFIRM_VERIFICATION("auth.confirmVerification", "POST", "/auth/confirm-verification", tr("E-postayı doğrula"), EndpointGroup.AUTH),
    AUTH_REQUEST_RESET("auth.requestReset", "POST", "/auth/request-reset", tr("Parola sıfırlama iste"), EndpointGroup.AUTH),
    AUTH_CONFIRM_RESET("auth.confirmReset", "POST", "/auth/confirm-reset", tr("Yeni parola"), EndpointGroup.AUTH),
    AUTH_2FA_VERIFY("auth.2fa.verify", "POST", "/auth/2fa/verify", tr("İki adımlı giriş: kod"), EndpointGroup.AUTH),
    AUTH_2FA_STATUS("auth.2fa.status", "GET", "/auth/2fa", tr("İki adımlı doğrulama durumu"), EndpointGroup.AUTH),
    AUTH_2FA_SETUP("auth.2fa.setup", "POST", "/auth/2fa/setup", tr("İki adımlı doğrulama: anahtar al"), EndpointGroup.AUTH),
    AUTH_2FA_ENABLE("auth.2fa.enable", "POST", "/auth/2fa/enable", tr("İki adımlı doğrulama: aç"), EndpointGroup.AUTH),
    AUTH_2FA_DISABLE("auth.2fa.disable", "POST", "/auth/2fa/disable", tr("İki adımlı doğrulama: kapat"), EndpointGroup.AUTH),
    AUTH_2FA_RECOVERY("auth.2fa.recovery", "POST", "/auth/2fa/recovery-codes", tr("Yeni yedek kodlar"), EndpointGroup.AUTH),
    AUTH_REQUEST_LOGIN_CODE("auth.requestLoginCode", "POST", "/auth/request-login-code", tr("E-postayla giriş kodu iste"), EndpointGroup.AUTH),
    AUTH_LOGIN_CODE("auth.loginCode", "POST", "/auth/login-code", tr("E-posta koduyla giriş"), EndpointGroup.AUTH),
    AUTH_REQUEST_SMS_CODE("auth.requestSmsCode", "POST", "/auth/request-sms-code", tr("SMS ile giriş kodu iste"), EndpointGroup.AUTH),
    AUTH_SMS_CODE("auth.smsCode", "POST", "/auth/sms-code", tr("SMS koduyla giriş"), EndpointGroup.AUTH),
    AUTH_PASSKEY_REGISTER_OPTIONS("auth.passkeys.registerOptions", "POST", "/auth/passkeys/register-options", tr("Passkey ekleme seçenekleri"), EndpointGroup.AUTH),
    AUTH_PASSKEY_REGISTER("auth.passkeys.register", "POST", "/auth/passkeys", tr("Passkey ekle"), EndpointGroup.AUTH),
    AUTH_PASSKEYS("auth.passkeys.list", "GET", "/auth/passkeys", tr("Passkey'lerim"), EndpointGroup.AUTH),
    AUTH_PASSKEY_DELETE("auth.passkeys.delete", "DELETE", "/auth/passkeys/{id}", tr("Passkey'i sil"), EndpointGroup.AUTH),
    AUTH_PASSKEY_LOGIN_OPTIONS("auth.passkeys.loginOptions", "POST", "/auth/passkeys/login-options", tr("Passkey ile giriş seçenekleri"), EndpointGroup.AUTH),
    AUTH_PASSKEY_LOGIN("auth.passkeys.login", "POST", "/auth/passkeys/login", tr("Passkey ile giriş"), EndpointGroup.AUTH),
    TEAMS("teams.list", "GET", "/teams", tr("Takımlarım"), EndpointGroup.TEAMS),
    TEAMS_CREATE("teams.create", "POST", "/teams", tr("Takım kur"), EndpointGroup.TEAMS),
    TEAM("teams.get", "GET", "/teams/{id}", tr("Takım ve üyeleri"), EndpointGroup.TEAMS),
    TEAM_UPDATE("teams.update", "PATCH", "/teams/{id}", tr("Takımı yeniden adlandır"), EndpointGroup.TEAMS),
    TEAM_DELETE("teams.delete", "DELETE", "/teams/{id}", tr("Takımı sil"), EndpointGroup.TEAMS),
    TEAM_ADD_MEMBER("teams.addMember", "POST", "/teams/{id}/members", tr("Takıma üye ekle"), EndpointGroup.TEAMS),
    TEAM_UPDATE_MEMBER("teams.updateMember", "PATCH", "/teams/{id}/members/{user}", tr("Üyenin takım rolü"), EndpointGroup.TEAMS),
    TEAM_REMOVE_MEMBER("teams.removeMember", "DELETE", "/teams/{id}/members/{user}", tr("Üyeyi çıkar veya takımdan ayrıl"), EndpointGroup.TEAMS),
    USERS_LIST("auth.users.list", "GET", "/auth/users", tr("Kullanıcıları listele (yönetici)"), EndpointGroup.AUTH),
    USERS_CREATE("auth.users.create", "POST", "/auth/users", tr("Kullanıcı aç (yönetici)"), EndpointGroup.AUTH),
    USERS_UPDATE("auth.users.update", "PATCH", "/auth/users/{id}", tr("Kullanıcıyı güncelle (yönetici)"), EndpointGroup.AUTH),
    USERS_DELETE("auth.users.delete", "DELETE", "/auth/users/{id}", tr("Kullanıcıyı sil (yönetici)"), EndpointGroup.AUTH),
    USERS_SESSIONS("auth.users.sessions", "GET", "/auth/users/{id}/sessions", tr("Kullanıcının oturumları (yönetici)"), EndpointGroup.AUTH),
    USERS_SESSIONS_CLOSE("auth.users.sessions.close", "DELETE", "/auth/users/{id}/sessions", tr("Kullanıcının tüm oturumlarını kapat (yönetici)"), EndpointGroup.AUTH),
    AUTH_SESSIONS("auth.sessions", "GET", "/auth/sessions", tr("Açık oturumlarım"), EndpointGroup.AUTH),
    AUTH_SESSIONS_CLOSE("auth.sessions.close", "DELETE", "/auth/sessions", tr("Diğer oturumlarımı kapat"), EndpointGroup.AUTH),
    AUTH_SESSION_CLOSE("auth.sessions.closeOne", "DELETE", "/auth/sessions/{id}", tr("Oturumu kapat"), EndpointGroup.AUTH),

    OAUTH_PROVIDERS("oauth.providers", "GET", "/auth/oauth/providers", tr("Sağlayıcılar"), EndpointGroup.OAUTH),
    OAUTH_START("oauth.start", "GET", "/auth/oauth/{provider}", tr("Girişi başlat"), EndpointGroup.OAUTH),
    OAUTH_CALLBACK("oauth.callback", "GET", "/auth/oauth/{provider}/callback", tr("Sağlayıcıdan dönüş"), EndpointGroup.OAUTH),
    OAUTH_EXCHANGE("oauth.exchange", "POST", "/auth/oauth/exchange", tr("Kodu token'a çevir"), EndpointGroup.OAUTH),

    PUSH_KEY("push.key", "GET", "/push/key", tr("VAPID anahtarı"), EndpointGroup.PUSH),
    PUSH_SUBSCRIBE("push.subscribe", "POST", "/push/subscribe", tr("Abone ol"), EndpointGroup.PUSH),
    PUSH_UNSUBSCRIBE("push.unsubscribe", "POST", "/push/unsubscribe", tr("Aboneliği kaldır"), EndpointGroup.PUSH),
    PUSH_SEND("push.send", "POST", "/push/send", tr("Bildirim gönder (yönetici)"), EndpointGroup.PUSH),
    ;

    companion object {
        fun of(key: String): Endpoint? = entries.firstOrNull { it.key == key }
    }
}

/** Koleksiyon başına uçlar; varsayılan yolda `{c}` koleksiyon adıdır. */
enum class CollectionEndpoint(val key: String, val method: String, val path: String, val title: String) {
    LIST("list", "GET", "/collections/{c}/records", tr("Listele")),
    VIEW("view", "GET", "/collections/{c}/records/{id}", tr("Tek kayıt")),
    CREATE("create", "POST", "/collections/{c}/records", tr("Oluştur")),
    UPDATE("update", "PATCH", "/collections/{c}/records/{id}", tr("Güncelle")),
    DELETE("delete", "DELETE", "/collections/{c}/records/{id}", tr("Sil")),
    EXPORT("export", "GET", "/collections/{c}/export", tr("Dışa aktar")),
    IMPORT("import", "POST", "/collections/{c}/import", tr("İçe aktar")),
    TEMPLATE("template", "GET", "/collections/{c}/import/template", tr("İçe aktarma şablonu")),
    AGGREGATE("aggregate", "GET", "/collections/{c}/aggregate", tr("Özet")),
    HISTORY("history", "GET", "/collections/{c}/records/{id}/history", tr("Geçmiş")),
    REVERT("revert", "POST", "/collections/{c}/records/{id}/history/{version}/revert", tr("Sürüme dön")),
    TRASH("trash", "GET", "/collections/{c}/trash", tr("Çöp kutusu")),
    RESTORE("restore", "POST", "/collections/{c}/trash/{id}/restore", tr("Geri yükle")),
    PURGE("purge", "DELETE", "/collections/{c}/trash/{id}", tr("Kalıcı sil")),
    SYNC("sync", "GET", "/sync/{c}", tr("Eşitleme: değişiklikler")),
    SYNC_PUSH("sync.push", "POST", "/sync/{c}", tr("Eşitleme: gönder")),
    CALENDAR("calendar", "GET", "/calendar/{c}.ics", tr("Takvim aboneliği (.ics)")),
    ;

    fun defaultPath(collection: String) = path.replace("{c}", collection)

    /** Uç koleksiyonda açık özelliğe bağlıysa (geçmiş, çöp kutusu) o özellik açık mı. */
    fun available(c: CollectionConfig): Boolean = when (this) {
        HISTORY, REVERT -> c.history != null
        TRASH, RESTORE, PURGE -> c.trash != null
        SYNC, SYNC_PUSH -> c.sync != null
        CALENDAR -> c.calendar?.feed == true
        else -> true
    }

    companion object {
        fun of(key: String): CollectionEndpoint? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Uçların etkin yolları (API yoluna göre); kapalı uç için null. Yol `/` ile başlar; parametreler (`{id}`) Ktor
 * sözdizimindedir.
 */
class ApiPaths(private val config: ServerConfig) {
    private val users = config.auth.users

    /** E-posta veya parola alanı kaldırıldıysa parolayla kayıt/giriş uçları kapanır. */
    private val passwordLogin = users.systemFields["email"]?.removed != true && users.systemFields["password"]?.removed != true

    fun path(e: Endpoint): String? {
        if (!passwordLogin && e in PASSWORD_ENDPOINTS) return null
        if (e in TWO_FACTOR_ENDPOINTS && !users.twoFactor.enabled) return null
        if (e in EMAIL_LOGIN_ENDPOINTS && !(users.emailLogin && config.email.enabled)) return null
        if (e in SMS_LOGIN_ENDPOINTS && !(users.smsLogin && config.phone.sms)) return null
        if (e in PASSKEY_ENDPOINTS && !(users.enabled && config.auth.passkeys.enabled)) return null
        if (e.group == EndpointGroup.TEAMS && !(users.enabled && users.teams.enabled)) return null
        val c = config.api.endpoints[e.key] ?: return e.path
        return if (!c.enabled) null else c.path?.takeIf { it.isNotBlank() } ?: e.path
    }

    fun path(collection: CollectionConfig, e: CollectionEndpoint): String? {
        if (!e.available(collection)) return null
        val c = collection.endpoints[e.key] ?: return e.defaultPath(collection.name)
        return if (!c.enabled) null else c.path?.takeIf { it.isNotBlank() } ?: e.defaultPath(collection.name)
    }

    /** Tam yol (API yoluyla), örneklerde ve OpenAPI dışındaki yerlerde. */
    fun full(path: String): String = config.server.basePath.trimEnd('/') + path

    companion object {
        val PASSWORD_ENDPOINTS = setOf(Endpoint.AUTH_SIGNUP, Endpoint.AUTH_LOGIN, Endpoint.AUTH_REQUEST_RESET, Endpoint.AUTH_CONFIRM_RESET)
        val TWO_FACTOR_ENDPOINTS = setOf(
            Endpoint.AUTH_2FA_VERIFY, Endpoint.AUTH_2FA_STATUS, Endpoint.AUTH_2FA_SETUP, Endpoint.AUTH_2FA_ENABLE,
            Endpoint.AUTH_2FA_DISABLE, Endpoint.AUTH_2FA_RECOVERY,
        )
        val EMAIL_LOGIN_ENDPOINTS = setOf(Endpoint.AUTH_REQUEST_LOGIN_CODE, Endpoint.AUTH_LOGIN_CODE)
        val SMS_LOGIN_ENDPOINTS = setOf(Endpoint.AUTH_REQUEST_SMS_CODE, Endpoint.AUTH_SMS_CODE)
        val PASSKEY_ENDPOINTS = setOf(
            Endpoint.AUTH_PASSKEY_REGISTER_OPTIONS, Endpoint.AUTH_PASSKEY_REGISTER, Endpoint.AUTH_PASSKEYS, Endpoint.AUTH_PASSKEY_DELETE,
            Endpoint.AUTH_PASSKEY_LOGIN_OPTIONS, Endpoint.AUTH_PASSKEY_LOGIN,
        )

        private val SEGMENT = Regex("^([A-Za-z0-9_.~-]+|\\{[A-Za-z]+\\})$")
        private val PARAM = Regex("\\{([A-Za-z]+)\\}")

        /** Yolun parametreleri (`{id}` → `id`). */
        fun params(path: String): Set<String> = PARAM.findAll(path).map { it.groupValues[1] }.toSet()

        /** Yol sözdizimi hatası; geçerliyse null. */
        fun problem(path: String, defaultPath: String): String? {
            if (!path.startsWith("/") || path.length < 2 || path.endsWith("/")) return tr("Yol / ile başlamalı ve / ile bitmemeli, örn. /hesap/giris")
            if (path.drop(1).split('/').any { !SEGMENT.matches(it) }) return tr("Yolda yalnızca harf, rakam, - _ . ~ ve {parametre} bölümleri olabilir")
            val want = params(defaultPath.replace("{c}", "x"))
            val have = params(path)
            if (want != have) return tr("Yol şu parametreleri içermeli: {0}", want.joinToString { "{$it}" }.ifEmpty { tr("(parametre yok)") })
            return null
        }
    }
}
