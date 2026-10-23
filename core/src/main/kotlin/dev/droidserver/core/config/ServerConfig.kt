@file:OptIn(ExperimentalSerializationApi::class)

package dev.droidserver.core.config

import dev.droidserver.core.i18n.tr
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject

/**
 * Kullanıcının sihirbazda yaptığı tüm seçimlerin tek kaynağı.
 * Sunucu bu dosyayı okuyup kendini kurar: şema, API uçları, kimlik doğrulama, yan servisler.
 * Kod üretip derlemek yerine bu konfigürasyon çalışma anında yorumlanır.
 */
@Serializable
data class ServerConfig(
    /** Şema sürümü; eski dosyalar okunurken [ConfigMigrations] ile güncellenir. */
    val version: Int = ConfigMigrations.CURRENT,
    val name: String = "droidserver",
    val server: HttpConfig = HttpConfig(),
    val auth: AuthConfig = AuthConfig(),
    val database: DatabaseConfig = DatabaseConfig(),
    val collections: List<CollectionConfig> = emptyList(),
    val components: List<ComponentConfig> = emptyList(),
    /** Sunucuya dışarıdan nasıl erişileceği; varsayılan (yalnızca yerel ağ) dosyaya yazılmaz. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val access: AccessConfig = AccessConfig(),
    /** Statik site (uygulamadan zip ile yüklenen dosyalar); kapalıyken dosyaya yazılmaz. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val static: StaticConfig = StaticConfig(),
    /** `files/scripts` klasöründeki .js dosyaları: özel uçlar, kayıt olaylarına bağlı mantık, zamanlanmış görevler; kapalıyken dosyaya yazılmaz. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val scripts: ScriptsConfig = ScriptsConfig(),
    /** Zamanlanmış yedek; kapalıyken dosyaya yazılmaz. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val backup: BackupConfig = BackupConfig(),
    /** Tarayıcıdan yönetim paneli (`/_admin`); kapalıyken dosyaya yazılmaz. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val adminPanel: AdminPanelConfig = AdminPanelConfig(),
    /** Kayıt değişikliklerine canlı abonelik (`/api/realtime`, Server-Sent Events); kapalıyken dosyaya yazılmaz. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val realtime: RealtimeConfig = RealtimeConfig(),
    /** E-posta gönderimi (SMTP): hesap doğrulama, parola sıfırlama, script'lerden `ctx.mail`; kapalıyken dosyaya yazılmaz. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val email: EmailConfig = EmailConfig(),
    /** Kayıt olaylarında dış adreslere imzalı POST; boşken dosyaya yazılmaz. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val webhooks: List<WebhookConfig> = emptyList(),
    /** Telefon özellikleri (SMS, konum, cihaz durumu); hepsi kapalıyken dosyaya yazılmaz. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val phone: PhoneConfig = PhoneConfig(),
    /** Salt okunur görünümler ve raporlar (`/views/{ad}`); boşken dosyaya yazılmaz. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val views: List<ViewConfig> = emptyList(),
    /** Tarayıcılara web push bildirimi (kullanıcı hesapları gerekir); kapalıyken dosyaya yazılmaz. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val push: PushConfig = PushConfig(),
    /** Uzman modu: yerleşik uçların yolları ve açık/kapalı durumu (koleksiyon uçları koleksiyonda). */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val api: ApiConfig = ApiConfig(),
    /** Denetim kaydı (yönetici işlemleri; profil klasöründe `audit.jsonl`). */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val audit: AuditConfig = AuditConfig(),
    /** IP izin/engel listeleri ve kimlik başına hız sınırı. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val firewall: FirewallConfig = FirewallConfig(),
    /** Sağlık uyarıları: eşikler, kanallar (bildirim, e-posta, Telegram) ve sessiz saatler. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val alerts: AlertsConfig = AlertsConfig(),
    /** Canlı kopya: bu sunucu kopyalanabilir ([ReplicationConfig.source]) veya başka bir sunucunun kopyası. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val replication: ReplicationConfig = ReplicationConfig(),
    /**
     * Ortak ortam değişkenleri (Ortam ve sırlar ekranı, `.env` biçiminde düzenlenir): script'ler (`ctx.env`), uygulamalar ve
     * isteyen bileşenler ([ComponentConfig.sharedEnv]) kullanır. Değer `secret:<ad>` referansıysa şifreli depodadır.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val env: Map<String, String> = emptyMap(),
    /** Kendi uygulamalarınız (Node.js, Python, Java, ikili); boşken dosyaya yazılmaz. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val apps: List<AppConfig> = emptyList(),
    /** Otomasyon kuralları (kodsuz "şu olunca şunu yap"); boşken dosyaya yazılmaz. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val automations: List<AutomationConfig> = emptyList(),
    /** Herkese açık formlar (`/forms/<ad>`); boşken dosyaya yazılmaz. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val forms: List<FormConfig> = emptyList(),
    /** Yapay zekâ: Claude vekil ucu, kayıtlar üzerinde soru-cevap ve anlamsal arama; kapalıyken dosyaya yazılmaz. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val ai: AiConfig = AiConfig(),
    /** Ağ sürücüsü (WebDAV); kapalıyken dosyaya yazılmaz. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val webdav: WebDavConfig = WebDavConfig(),
    /** MQTT (IoT): cihaz mesajlarını koleksiyonlara yazar, kayıt olaylarını yayınlar. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val mqtt: MqttConfig = MqttConfig(),
    /** Sensör panosu, eşik uyarıları ve Home Assistant keşfi; bkz. [SensorConfig]. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val sensors: List<SensorConfig> = emptyList(),
) {
    fun toJson(): String = ConfigJson.encodeToString(serializer(), this)

    /** Kullanıcılara toplu e-posta / web push gönderilebilir mi (hesaplar ve en az bir kanal açık). */
    val broadcasts: Boolean get() = auth.users.enabled && (email.enabled || push.enabled)

    /** Kalıcı iş kuyruğu (`_jobs`): script işleri ve toplu bildirimler. */
    val jobQueue: Boolean get() = scripts.enabled || broadcasts

    /** Veritabanında tablosu olan tüm koleksiyonlar: kullanıcınınkiler ve (açıksa) kullanıcı sistem tabloları. */
    fun schemaCollections(): List<CollectionConfig> =
        collections + SystemCollections.of(auth.users, push.enabled) + SystemCollections.archive(collections) +
            listOfNotNull(
                SystemCollections.jobs.takeIf { jobQueue }, SystemCollections.unsubscribed.takeIf { broadcasts && email.enabled },
                SystemCollections.changes.takeIf { replication.source },
                SystemCollections.passkeys.takeIf { auth.passkeys.enabled && auth.users.enabled },
                SystemCollections.panelPasskeys.takeIf { auth.passkeys.enabled && adminPanel.enabled },
            )

    companion object {
        /** Eski sürümleri günceller (sırlar yerinde kalır; depoya taşımak için ConfigSecrets.secureText). */
        fun fromJson(text: String): ServerConfig =
            ConfigJson.decodeFromJsonElement(serializer(), ConfigMigrations.upgrade(ConfigJson.parseToJsonElement(text).jsonObject))
    }
}

val ConfigJson = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
data class HttpConfig(
    val host: String = "0.0.0.0",
    /** Root olmadan 1024 altı portlar açılamaz. */
    val port: Int = 8080,
    val basePath: String = "/api",
    val cors: CorsConfig = CorsConfig(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val limits: LimitsConfig = LimitsConfig(),
)

/**
 * Kötüye kullanıma karşı sınırlar; istemci adresine göre uygulanır (Cloudflare/HTTPS ucundan gelenlerde gerçek adres).
 * Telefonun kendisinden gelen istekler (uygulama) sınırlanmaz.
 */
@Serializable
data class LimitsConfig(
    /** İstek gövdesi üst sınırı (KB); aşılırsa 413. */
    val maxBodyKb: Int = 1024,
    /** Bir istemcinin dakikada en çok istek sayısı; aşılırsa 429. 0: sınırsız. */
    val requestsPerMinute: Int = 600,
    /** Bu kadar hatalı kimlik denemesinden (401) sonra istemci [banMinutes] dakika engellenir. 0: kapalı. */
    val authFailures: Int = 10,
    val banMinutes: Int = 15,
)

/**
 * Zamanlanmış yedek: veritabanı (motordan bağımsız, kayıt kayıt), konfigürasyon, yüklenen dosyalar, script'ler ve site
 * tek bir zip'e. Yedekler uygulama klasöründe tutulur; kullanıcı ayrıca bir klasör seçtiyse oraya da kopyalanır.
 */
@Serializable
data class BackupConfig(
    val enabled: Boolean = false,
    /** Cron ifadesi (telefonun saati); varsayılan her gece 04:00. */
    val schedule: String = "0 4 * * *",
    /** Saklanacak en çok zamanlanmış yedek; eskiler silinir (elle alınanlar sayılmaz). */
    val keep: Int = 7,
    /** Yedeklerin şifrelenip yüklendiği bulut deposu (WebDAV veya S3 uyumlu); null: yalnızca telefonda. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val remote: RemoteBackupConfig? = null,
)

@Serializable
enum class RemoteKind(val title: String) {
    @SerialName("webdav") WEBDAV("WebDAV (Nextcloud, ownCloud, Synology…)"),
    @SerialName("s3") S3(tr("S3 uyumlu (Backblaze B2, Cloudflare R2, MinIO, AWS)")),
}

/**
 * Bulut yedeği. Yedekler telefonda [passphrase] ile şifrelenip (AES-256-GCM, anahtar PBKDF2) yüklenir; depo sağlayıcısı
 * içeriği göremez. Parola başka telefona geri yüklemek için gerekir; kaybolursa yedekler açılamaz.
 */
@Serializable
data class RemoteBackupConfig(
    val kind: RemoteKind = RemoteKind.WEBDAV,
    /** WebDAV: yedek klasörünün adresi; S3: uç adresi (örn. `https://s3.eu-central-003.backblazeb2.com`). */
    val url: String = "",
    /** S3: kova adı. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val bucket: String? = null,
    /** S3: bölge (boşsa `us-east-1`; R2 için `auto`). */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val region: String? = null,
    /** S3: nesne adlarının öneki (klasör gibi). */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val prefix: String = "droidserver/",
    /** WebDAV kullanıcı adı veya S3 erişim anahtarı kimliği. */
    val username: String = "",
    /** WebDAV parolası (uygulama parolası) veya S3 gizli anahtarı; `secret:` referansı. */
    val password: String = "",
    /** Şifreleme parolası; `secret:` referansı. */
    val passphrase: String = "",
    /** Depoda tutulacak en çok yedek; eskiler silinir. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val keep: Int = 30,
    /** Elle alınan yedekler de yüklensin (zamanlanmışlar her zaman). */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val uploadManual: Boolean = true,
)

/** Web yönetim paneli: tarayıcıdan `/_admin`; giriş telefonda gösterilen tek kullanımlık eşleştirme koduyla. */
@Serializable
data class AdminPanelConfig(
    val enabled: Boolean = false,
    /** Panel oturumunun ömrü (saat). */
    val sessionHours: Int = 12,
    /**
     * Girişte eşleştirme koduna ek olarak doğrulayıcı uygulama kodu (TOTP gizli anahtarı, Base32; `secret:` referansı).
     * Boşsa yalnızca eşleştirme kodu.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val totpSecret: String? = null,
)

/**
 * Script'ler (JavaScript, Rhino). Deploy'da derlenir; sözdizimi hatası deploy'u reddeder. Script'ler Java sınıflarına
 * erişemez, süre sınırıyla çalışır; dışarıya yalnızca [allowedHosts]'taki adreslere istek atabilir.
 */
@Serializable
data class ScriptsConfig(
    val enabled: Boolean = false,
    /** Uç ve kayıt olayı başına süre sınırı (ms); aşan script durdurulur (500). */
    val timeoutMs: Int = 2_000,
    /** Zamanlanmış görev başına süre sınırı (sn). */
    val cronTimeoutSec: Int = 60,
    /** `ctx.http`'nin istek atabileceği adresler: `api.example.com` veya `*.example.com`. */
    val allowedHosts: List<String> = emptyList(),
    /** `ctx.env`: script'lere verilen değerler; `secret:<ad>` referansı olabilir (deploy'da çözülür). */
    val env: Map<String, String> = emptyMap(),
    /** Arka plan iş kuyruğu (`jobAdd`, `ctx.jobs.enqueue`). */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val jobs: JobsConfig = JobsConfig(),
)

/** İş kuyruğu: aynı anda en çok [concurrency] iş; biten/başarısız işler [keepDays] gün sonra silinir. */
@Serializable
data class JobsConfig(
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val concurrency: Int = 2,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val keepDays: Int = 7,
)

/** `files/www/` altındaki dosyaları [path] altında yayınlar; API yolu (basePath) her zaman önceliklidir. */
/** Webhook: kayıt olaylarında [url]'ye imzalı POST (bkz. WebhookDispatcher). */
@Serializable
data class WebhookConfig(
    /** Kısa ad (loglarda ve teslim geçmişinde). */
    val name: String,
    /** `https://...` (yerel ağdaki bir servis için `http://` da olabilir). */
    val url: String,
    /** Boşsa tüm koleksiyonlar. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val collections: List<String> = emptyList(),
    /** `create`, `update`, `delete`. */
    val events: List<String> = EVENTS,
    /** İmza sırrı (HMAC-SHA256); `secret:<ad>` referansı, kaydedince şifreli depoya taşınır. Boşsa imzasız. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val secret: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val enabled: Boolean = true,
) {
    companion object {
        val EVENTS = listOf("create", "update", "delete")
    }
}

@Serializable
enum class SmtpSecurity(val title: String) {
    /** Düz bağlantı, sonra şifreleme (genellikle 587). */
    @SerialName("starttls") STARTTLS("STARTTLS"),

    /** Baştan şifreli (genellikle 465). */
    @SerialName("ssl") SSL("SSL/TLS"),

    /** Şifresiz; yalnızca yerel ağdaki bir posta sunucusu için (parola gönderilmez). */
    @SerialName("none") NONE(tr("Şifresiz")),
}

/** SMTP ile e-posta gönderimi (hesap doğrulama ve parola sıfırlama e-postaları). */
@Serializable
data class EmailConfig(
    val enabled: Boolean = false,
    val host: String = "",
    val port: Int = 587,
    val security: SmtpSecurity = SmtpSecurity.STARTTLS,
    val username: String? = null,
    /** SMTP parolası (Gmail/Outlook'ta uygulama şifresi); `secret:<ad>` referansı, kaydedince şifreli depoya taşınır. */
    val password: String? = null,
    /** Gönderen adresi, örn. evim@gmail.com. */
    val from: String = "",
    /** Gönderen adı, örn. "Evim Sunucusu"; boşsa sunucu adı. */
    val fromName: String? = null,
    /**
     * E-postadaki bağlantıların başı, örn. `https://evim.duckdns.org:8443`. Boşsa e-postada bağlantı yerine kod
     * gönderilir (istemci uygulama kodu kendisi sorar).
     */
    val publicUrl: String? = null,
    /** Hesap e-postalarının şablonları; tür ve dil için şablon yoksa yerleşik metin. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val templates: List<EmailTemplate> = emptyList(),
)

/**
 * Hesap e-postası şablonu. [kind]: `verify` (adres doğrulama), `reset` (parola sıfırlama), `login` (giriş kodu).
 * [lang] (`tr`, `en`, …) verilirse yalnızca o dili isteyene (`Accept-Language`); boşsa diğer herkese. Yer tutucular:
 * `{app}` (gönderen adı), `{email}`, `{code}` (kod), `{link}` (bağlantı; bağlantı kurulamıyorsa kod). [html] isteğe
 * bağlı; içine yazılan değerler HTML'e göre kaçışlanır.
 */
@Serializable
data class EmailTemplate(
    val kind: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val lang: String? = null,
    val subject: String,
    val text: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val html: String? = null,
) {
    companion object {
        const val VERIFY = "verify"
        const val RESET = "reset"
        const val LOGIN = "login"
        val KINDS = listOf(VERIFY, RESET, LOGIN)
        val PLACEHOLDERS = setOf("app", "email", "code", "link")
    }
}

/**
 * Canlı abonelik: istemciler `GET <basePath>/realtime?collections=a,b` ile kayıt oluşturma/güncelleme/silme olaylarını
 * anında alır. Her olayda koleksiyonun görüntüleme kuralı uygulanır.
 */
@Serializable
data class RealtimeConfig(
    val enabled: Boolean = false,
    /** Aynı anda açık en çok canlı bağlantı (her biri bir istemci sekmesi/uygulaması). */
    val maxConnections: Int = 100,
    /** WebSocket kanalları (`/realtime/ws`): sohbet, oyun, canlı imleç gibi iki yönlü mesajlaşma; boşken kapalı. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val channels: List<ChannelConfig> = emptyList(),
)

/**
 * İki yönlü mesaj kanalı. [name] tam ad (`lobby`) veya `*` ile biten kalıp (`room:*` → `room:42`, `room:abc`).
 * [join] kanala kimin katılıp mesajları alabileceği, [publish] kimin mesaj gönderebileceği (`public`, `auth`,
 * `admin`; `admin` kuralında ayrıca [roles]). [presence]: katılanların listesi ve giriş/çıkış bildirimleri.
 * [history]: yeni katılana gönderilen son mesaj sayısı (bellekte; sunucu yeniden başlayınca silinir).
 */
@Serializable
data class ChannelConfig(
    val name: String,
    val join: Rule = Rule.AUTH,
    val publish: Rule = Rule.AUTH,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val roles: List<String> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val presence: Boolean = true,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val history: Int = 0,
)

@Serializable
data class StaticConfig(
    val enabled: Boolean = false,
    val path: String = "/",
    /** Bulunamayan sayfa adreslerinde `index.html` döner (tek sayfalık uygulamalar, örn. React yönlendirmesi). */
    val spaFallback: Boolean = true,
) {
    companion object {
        /** [path], [prefix] yolunun kendisi veya altında mı (`/api` → `/api`, `/api/x`; `/` → hepsi). */
        fun underPath(path: String, prefix: String): Boolean =
            prefix == "/" || path == prefix || path.startsWith(prefix.trimEnd('/') + "/")
    }
}

@Serializable
data class CorsConfig(
    val enabled: Boolean = false,
    /** "*" tüm kaynaklara izin verir; aksi halde "example.com" gibi host listesi. */
    val allowedHosts: List<String> = listOf("*"),
)

@Serializable
enum class AuthMode {
    @SerialName("none") NONE,
    @SerialName("api_key") API_KEY,
    @SerialName("jwt") JWT,
}

@Serializable
data class AuthConfig(
    val mode: AuthMode = AuthMode.NONE,
    /** mode=api_key: istemci `Authorization: Bearer <anahtar>` gönderir. Değer `secret:<ad>` referansı olabilir. */
    val apiKeys: List<String> = emptyList(),
    /** mode=jwt: HS256 imza anahtarı (en az 32 karakter). Değer `secret:<ad>` referansı olabilir. */
    val jwtSecret: String? = null,
    val jwtIssuer: String = "droidserver",
    val jwtAudience: String = "droidserver-api",
    /** Son kullanıcı hesapları (kayıt / giriş); kapalıyken dosyaya yazılmaz. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val users: UsersConfig = UsersConfig(),
    /** Adlandırılmış, kapsamı sınırlanabilen API anahtarları (her iki modda da geçerli); bkz. [ApiKeyConfig]. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val keys: List<ApiKeyConfig> = emptyList(),
    /** Passkey (WebAuthn) ile parolasız giriş: kullanıcı hesapları ve web paneli için; bkz. [PasskeysConfig]. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val passkeys: PasskeysConfig = PasskeysConfig(),
)

/**
 * Passkey (WebAuthn). [rpId]: sunucunun tarayıcıda açıldığı gerçek alan adı (örn. `ev.duckdns.org` veya tünel adı);
 * passkey bu ada bağlanır, ad değişirse eskileri çalışmaz. WebAuthn HTTPS ve alan adı ister: yerel IP ile çalışmaz
 * (yalnızca geliştirmede `localhost`). [rpName]: tarayıcının gösterdiği ad (boşsa sunucu adı).
 */
@Serializable
data class PasskeysConfig(
    val enabled: Boolean = false,
    val rpId: String = "",
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val rpName: String? = null,
)

/**
 * Adlandırılmış API anahtarı. Anahtarın kendisi saklanmaz (oluşturulurken bir kez gösterilir); yalnızca SHA-256 özeti.
 * Salt okunur değilse ve koleksiyon seçilmemişse tam yönetici yetkisi taşır; aksi halde yalnızca seçili koleksiyonlarda
 * (boşsa hepsinde) kuralları aşarak okur (salt okunursa) veya okur-yazar; yönetici uçlarına (şema, metrikler,
 * kullanıcılar, yedekler) erişemez.
 */
@Serializable
data class ApiKeyConfig(
    val name: String,
    /** SHA-256 özeti, küçük harf onaltılık. */
    val hash: String,
    /** Anahtarı tanımak için başı (örn. `dsk_a1b2c3d4`). */
    val prefix: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val readOnly: Boolean = false,
    /** Boşsa tüm koleksiyonlar. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val collections: List<String> = emptyList(),
    /** Son kullanma zamanı (ISO-8601); boşsa süresiz. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val expiresAt: String? = null,
    /** false: iptal edilmiş (istek 401 alır). */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val enabled: Boolean = true,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val createdAt: String? = null,
    /** Bu anahtarla dakikada en çok istek (aşılınca 429); boşsa yalnızca IP sınırı. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val requestsPerMinute: Int? = null,
) {
    /** Tam yönetici: salt okunur değil ve koleksiyonla sınırlı değil. */
    val isFull: Boolean get() = !readOnly && collections.isEmpty()
}

/**
 * Uygulamanızı kullananların hesapları (`/auth/signup`, `/auth/login` ...). Kayıtlar sistem koleksiyonu
 * `_users`'ta durur; koleksiyonlardaki `ownerField` bu koleksiyona bağlanan bir ilişki alanıdır.
 * API anahtarı / yönetici JWT'si yönetici erişimi olarak kalır.
 */
@Serializable
data class UsersConfig(
    val enabled: Boolean = false,
    /** false: yeni hesapları yalnızca yönetici açar (uygulamadan veya `/auth/users` ile). */
    val allowSignup: Boolean = true,
    /** `email`, `password_hash`, `verified`, `role` dışındaki alanlar (örn. ad); kayıt olurken gönderilir. */
    val extraFields: List<FieldConfig> = emptyList(),
    /** Başkalarına görünen ek alanlar (örn. yazının yazarını `expand` ile gösterirken). Diğerlerini yalnızca kullanıcı görür. */
    val publicFields: List<String> = emptyList(),
    val minPasswordLength: Int = 8,
    /** Erişim token'ının ömrü (dakika). */
    val accessTokenMinutes: Int = 15,
    /** Yenileme token'ının ömrü (gün); her kullanımda yenisi verilir. */
    val refreshTokenDays: Int = 30,
    /** Kayıt olunca doğrulama e-postası gönderilsin (e-posta açıksa). */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val sendVerification: Boolean = true,
    /** E-postası doğrulanmamış kullanıcı giriş yapamasın (e-posta açık olmalı). */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val requireVerified: Boolean = false,
    /** Google / GitHub ile giriş. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val oauth: OAuthConfig = OAuthConfig(),
    /**
     * Uzman modu: `email`, `password`, `verified`, `role`, `id`, `created_at`, `updated_at` alanlarının API'deki adı veya
     * kaldırılması. `email` ya da `password` kaldırılırsa parolayla kayıt/giriş kapanır (yalnızca sosyal giriş kalır).
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val systemFields: Map<String, SystemFieldConfig> = emptyMap(),
    /** Aynı istemciden aynı hesaba en çok hatalı giriş (sonra [loginBlockMinutes] dakika 429). */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val loginAttempts: Int = 5,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val loginBlockMinutes: Int = 15,
    /** Kullanıcı başına kotalar (yönetici rolü ve API anahtarları muaf). */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val quotas: QuotaConfig = QuotaConfig(),
    /** İki adımlı doğrulama (doğrulayıcı uygulama kodu, yedek kodlar). */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val twoFactor: TwoFactorConfig = TwoFactorConfig(),
    /** E-postayla gelen tek kullanımlık kodla (parolasız) giriş; e-posta ayarı gerekir. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val emailLogin: Boolean = false,
    /**
     * E-postadaki giriş bağlantısının adresi (istemci uygulamanızın sayfası, örn. `https://uygulamam.com/giris`);
     * `?email=…&code=…` eklenir. Boşsa e-postada yalnızca kod bulunur.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val emailLoginUrl: String? = null,
    /**
     * SMS'le gelen tek kullanımlık kodla (parolasız) giriş: kod sunucu telefonunun kendi SIM'inden gider (Telefon
     * özellikleri → SMS açık olmalı; önek ve günlük SMS sınırı geçerli). Hesabın numarası [phoneField] ek alanındadır.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val smsLogin: Boolean = false,
    /** SMS girişinde kullanıcının telefon numarasının durduğu ek alan (metin, benzersiz; uluslararası biçimde saklanır). */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val phoneField: String = "phone",
    /**
     * `user` ve `admin` dışındaki roller (örn. `editor`, `moderator`). Kullanıcıya atanır; koleksiyon kurallarında
     * işlem başına izin verilir ([Rules.roles]). Rol yönetici yetkisi vermez.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val roles: List<RoleConfig> = emptyList(),
    /** Takımlar: kullanıcılar takımlara katılır, kayıtlar bir takıma ait olur (bkz. [CollectionConfig.teamField]). */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val teams: TeamsConfig = TeamsConfig(),
)

/**
 * [allowCreate]: kullanıcılar kendi takımlarını kurabilir (kuran takımın sahibi olur); kapalıysa takımları yalnızca
 * yönetici kurar. [maxTeamsPerUser]: bir kullanıcının üye olabileceği takım sayısı, [maxMembers]: takım başına üye.
 */
@Serializable
data class TeamsConfig(
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val enabled: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val allowCreate: Boolean = true,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val maxTeamsPerUser: Int = 20,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val maxMembers: Int = 100,
)

/** Özel rol: [name] kullanıcının `role` alanına yazılır; [title] arayüzde görünen ad. */
@Serializable
data class RoleConfig(
    val name: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val title: String? = null,
) {
    val label: String get() = title?.takeIf { it.isNotBlank() } ?: name
}

/** İki adımlı doğrulama (RFC 6238 TOTP). */
@Serializable
data class TwoFactorConfig(
    val enabled: Boolean = false,
    /** Zorunlu: kurmamış kullanıcı giriş yapar ama kurana kadar yalnızca hesap uçlarını kullanabilir. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val required: Boolean = false,
    /** Doğrulayıcı uygulamada görünen ad; boşsa sunucu adı. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val issuer: String? = null,
)

/**
 * Kullanıcı başına kotalar; boş olan sınırsızdır. Kayıt sınırı yalnızca sahip alanı (`ownerField`) olan
 * koleksiyonlarda, kullanıcının sahibi olduğu kayıtlar sayılarak uygulanır.
 */
@Serializable
data class QuotaConfig(
    /** Koleksiyon başına en çok kayıt. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val records: Int? = null,
    /** Koleksiyona özel kayıt sınırı ([records] yerine). */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val collections: Map<String, Int> = emptyMap(),
    /** Sahibi olduğu kayıtlardaki dosyaların toplamı (MB; küçük resimler hariç). */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val storageMb: Int? = null,
    /** UTC günü başına API isteği (kullanıcı token'ıyla yapılanlar). */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val dailyRequests: Int? = null,
) {
    val isSet: Boolean get() = records != null || collections.isNotEmpty() || storageMb != null || dailyRequests != null
}

/**
 * Web push (VAPID, RFC 8292; şifreleme RFC 8291). Anahtar çifti telefonda üretilir; özel anahtar şifreli depoda.
 * Tarayıcı aboneliği kullanıcıya bağlı saklanır; bildirim script'lerden (`ctx.push.send`) veya [notify] kurallarıyla gider.
 */
@Serializable
data class PushConfig(
    val enabled: Boolean = false,
    /** İtme servislerinin sorun olursa ulaşacağı adres: `mailto:siz@example.com` veya `https://...`. */
    val subject: String = "",
    /** VAPID açık anahtarı (P-256, sıkıştırılmamış, base64url); tarayıcıda `applicationServerKey`. */
    val publicKey: String = "",
    /** VAPID özel anahtarı (PKCS#8, base64url); `secret:<ad>` referansı. */
    val privateKey: String = "",
    /** Kayıt olayında kaydın sahibine bildirim. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val notify: List<PushRule> = emptyList(),
)

/** [collection]'da [events] olunca kaydın sahibine (sahip alanı) bildirim; metinlerde `{alan}` kaydın değeriyle değişir. */
@Serializable
data class PushRule(
    val collection: String,
    val events: List<String> = listOf("create"),
    val title: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val body: String = "",
    /** Bildirime tıklanınca açılacak adres (`{id}` gibi alanlar kullanılabilir). */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val url: String? = null,
)

@Serializable
enum class OAuthProvider(val title: String, val consoleUrl: String) {
    @SerialName("google") GOOGLE("Google", "https://console.cloud.google.com/apis/credentials"),
    @SerialName("github") GITHUB("GitHub", "https://github.com/settings/developers"),
}

/**
 * Sosyal giriş (OAuth2 yetkilendirme kodu + PKCE). Kullanıcı tarayıcıda sağlayıcıya gider, dönüşte doğrulanmış e-postası
 * hesapla eşleşir (yoksa kayıt açıksa hesap açılır) ve istemci tek kullanımlık kodla token alır.
 */
@Serializable
data class OAuthConfig(
    /**
     * Sunucunun tarayıcıdan erişilen adresi, örn. `https://evim.duckdns.org:8443`. Sağlayıcıya kaydedilecek dönüş adresi
     * `<publicUrl><basePath>/auth/oauth/<sağlayıcı>/callback`.
     */
    val publicUrl: String = "",
    val providers: List<OAuthProviderConfig> = emptyList(),
    /**
     * Girişten sonra istemcinin döneceği izinli adresler (örn. `https://uygulamam.com/giris`, `uygulamam://giris`);
     * istekteki `redirect` bunlardan biriyle başlamalı. Boşsa yalnızca sunucunun sonuç sayfası.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val redirectUrls: List<String> = emptyList(),
) {
    val enabled: Boolean get() = providers.any { it.enabled }
}

@Serializable
data class OAuthProviderConfig(
    val provider: OAuthProvider,
    val clientId: String,
    /** `secret:<ad>` referansı olabilir (kaydederken şifreli depoya taşınır). */
    val clientSecret: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val enabled: Boolean = true,
)

/**
 * Alan düzeyinde gizlilik: kural kaydı gösterse de alan yalnızca bunlara döner. Yönetici olmayanlar bu alana göre
 * filtreleyemez, sıralayamaz, özetleyemez (sahip alanı olan koleksiyonda sahibi kendi kayıtları içinde yapabilir).
 */
@Serializable
enum class FieldVisibility(val title: String) {
    /** Kaydın sahibi ve yönetici (koleksiyonun sahip alanı olmalı). */
    @SerialName("owner") OWNER(tr("Kaydın sahibi ve yönetici")),

    /** Yalnızca yönetici; yönetici olmayanlar bu alanı yazamaz da. */
    @SerialName("admin") ADMIN(tr("Yalnızca yönetici")),
}

/** Bir işlemi kimin yapabileceği. */
@Serializable
enum class Rule(val title: String) {
    @SerialName("public") PUBLIC(tr("Herkes")),

    /** Giriş yapmış her kullanıcı. */
    @SerialName("auth") AUTH(tr("Giriş yapmış kullanıcılar")),

    /** Yalnızca kaydın sahibi ([CollectionConfig.ownerField] = istekteki kullanıcı). Oluşturmada `auth` ile aynı. */
    @SerialName("owner") OWNER(tr("Kaydın sahibi")),

    /**
     * Kaydın takımının üyeleri ([CollectionConfig.teamField] istekteki kullanıcının takımlarından biri). Oluşturmada
     * kullanıcı yalnızca üyesi olduğu takıma kayıt ekler.
     */
    @SerialName("team") TEAM(tr("Takım üyeleri")),

    /** API anahtarı, yönetici JWT'si veya rolü `admin` olan kullanıcı. */
    @SerialName("admin") ADMIN(tr("Yalnızca yönetici")),

    /** İşlem kapalı; yönetici de yapamaz. */
    @SerialName("nobody") NOBODY(tr("Kapalı")),
}

/**
 * Kurala eklenen koşul: kural izin verse de yalnızca [filter]'a uyan kayıtlar. Oluşturmada gönderilen kayıt, diğer
 * işlemlerde mevcut kayıt koşula uymalıdır (uymayan kayıt listede görünmez, tek kayıtta 404, oluşturmada 403).
 */
@Serializable
data class RuleCondition(
    /**
     * Liste filtresi sözdizimi, `&` ile VE: `status=published`, `status[in]=published,archived&archived=false`.
     * `like` kullanılamaz. Kullanıcıya bağlı ilişki alanında `@me` istekteki kullanıcıdır (`assignee=@me`).
     */
    val filter: String,
    /** Kaydın sahibi koşula bakılmaksızın erişir (örn. herkes yayınlananları, sahibi taslaklarını da görür). */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val owner: Boolean = false,
)

/** Koleksiyon işlemlerinin kuralları; yönetici [Rule.NOBODY] dışındaki tüm kuralları aşar. */
@Serializable
data class Rules(
    val list: Rule = Rule.ADMIN,
    val view: Rule = Rule.ADMIN,
    val create: Rule = Rule.ADMIN,
    val update: Rule = Rule.ADMIN,
    val delete: Rule = Rule.ADMIN,
    /** İşlem anahtarından (`list`, `view`, `create`, `update`, `delete`) koşula; yönetici koşullara bağlı değildir. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val conditions: Map<String, RuleCondition> = emptyMap(),
    /**
     * İşlem anahtarından ek izin verilen özel rollere (`{"update": ["editor"]}`): kural izin vermese de bu roldeki
     * kullanıcı işlemi koşulsuz ve tüm kayıtlarda yapar (sahip sınırı yok). [Rule.NOBODY] yine kapalıdır.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val roles: Map<String, List<String>> = emptyMap(),
) {
    enum class Op(val key: String, val title: String) {
        LIST("list", tr("Listeleme")), VIEW("view", tr("Görüntüleme")), CREATE("create", tr("Oluşturma")),
        UPDATE("update", tr("Güncelleme")), DELETE("delete", tr("Silme")),
    }

    operator fun get(op: Op): Rule = when (op) {
        Op.LIST -> list
        Op.VIEW -> view
        Op.CREATE -> create
        Op.UPDATE -> update
        Op.DELETE -> delete
    }

    fun condition(op: Op): RuleCondition? = conditions[op.key]

    fun roles(op: Op): List<String> = roles[op.key].orEmpty()

    fun withRoles(op: Op, names: List<String>): Rules =
        copy(roles = if (names.isEmpty()) roles - op.key else roles + (op.key to names))

    fun withCondition(op: Op, condition: RuleCondition?): Rules =
        copy(conditions = if (condition == null) conditions - op.key else conditions + (op.key to condition))

    fun with(op: Op, rule: Rule): Rules = when (op) {
        Op.LIST -> copy(list = rule)
        Op.VIEW -> copy(view = rule)
        Op.CREATE -> copy(create = rule)
        Op.UPDATE -> copy(update = rule)
        Op.DELETE -> copy(delete = rule)
    }

    val all: List<Rule> get() = Op.entries.map(::get)
}

@Serializable
enum class DbEngine {
    /** Uygulamanın içinde, ek süreç gerektirmez. Varsayılan. */
    @SerialName("sqlite") SQLITE,

    /** İndirilen PostgreSQL bileşeni (veya kendi sunucunuz) ile, pgjdbc üzerinden. */
    @SerialName("postgres") POSTGRES,
}

@Serializable
data class DatabaseConfig(
    val engine: DbEngine = DbEngine.SQLITE,
    /** SQLite için uygulamanın veri klasöründeki dosya adı. */
    val file: String = "data.db",
    /** Postgres için JDBC URL'i, örn. jdbc:postgresql://127.0.0.1:5432/postgres. */
    val url: String? = null,
    /** Postgres kullanıcısı. */
    val user: String? = null,
    /** Postgres parolası; `secret:<ad>` referansı olabilir (kaydederken şifreli depoya taşınır). */
    val password: String? = null,
    /** Postgres: adresteki veritabanı yoksa oluşturulsun mu (kullanıcının yetkisi varsa)? */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val createIfMissing: Boolean = false,
    /**
     * Şifreli alanların anahtarı (32 bayt, base64); `secret:field_key` olarak sır deposunda durur, yedeklere girmez.
     * Yedeği başka telefona yüklerken aynı anahtar girilmelidir.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val fieldKey: String? = null,
)

/** Bir koleksiyon = bir tablo + otomatik CRUD uçları. */
@Serializable
data class CollectionConfig(
    val name: String,
    val fields: List<FieldConfig>,
    /** Kim listeleyebilir, görebilir, ekleyebilir, değiştirebilir, silebilir; varsayılan: yalnızca yönetici. */
    val rules: Rules = Rules(),
    /**
     * Kaydın sahibini tutan ilişki alanı (`_users` koleksiyonuna). Yönetici olmayan kullanıcının eklediği kayıtta
     * istekteki kullanıcıyla doldurulur; istemci başka bir kullanıcı yazamaz. `owner` kuralı için gerekli.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val ownerField: String? = null,
    /**
     * Kaydın takımını tutan ilişki alanı (`_teams` koleksiyonuna). `team` kuralı için gerekli; yönetici olmayan kullanıcı
     * yalnızca üyesi olduğu takımı yazabilir, tek takımı varsa boş bırakılınca o takım yazılır.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val teamField: String? = null,
    /**
     * `?q=` ile aranacak metin alanları. Arama büyük/küçük harf ve Türkçe karakterden bağımsızdır; değerlerin
     * aranabilir hali gizli bir sütunda tutulur (alanlar değişince deploy'da yeniden hesaplanır).
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val search: List<String> = emptyList(),
    /**
     * Tam metin dizini: `?q=` kelime başıyla ve indeksten aranır (çok kayıtta hızlı), sonuçlar alakaya göre sıralanır.
     * Kapalıyken arama, metnin herhangi bir yerinde geçen parçayı bulur (tablo taraması).
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val fullText: Boolean = false,
    /** Uzman modu: koleksiyon uçlarının yolları ve açık/kapalı durumu (anahtar: `list`, `view`, ... bkz. CollectionEndpoint). */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val endpoints: Map<String, EndpointConfig> = emptyMap(),
    /** Uzman modu: `id`, `created_at`, `updated_at` alanlarının API'deki adı veya kaldırılması. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val systemFields: Map<String, SystemFieldConfig> = emptyMap(),
    /** Kayıt geçmişi: her değişiklikte kaydın son hali saklanır; null: kapalı. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val history: HistoryConfig? = null,
    /** Çöp kutusu: silinen kayıtlar bir süre saklanır, geri yüklenebilir; null: kapalı (silme kalıcı). */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val trash: TrashConfig? = null,
    /** Saklama süresi: eski kayıtlar otomatik silinir; null: süresiz. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val retention: RetentionConfig? = null,
)

/**
 * Telefona özgü özellikler; her biri ayrı açılır ve Android izni ister. Script'lerden (`ctx.sms`, `ctx.device`) ve
 * yönetici API'sinden (`/_phone/...`) kullanılır. SMS günlük [smsDailyLimit] ile ve yalnızca [smsPrefixes] ile başlayan
 * numaralara (E.164, örn. `+90…`) gönderilir; her gönderim denetim kaydına yazılır.
 */
@Serializable
data class PhoneConfig(
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val sms: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val smsDailyLimit: Int = 20,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val smsPrefixes: List<String> = listOf("+90"),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val location: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val status: Boolean = false,
) {
    val any: Boolean get() = sms || location || status
}

/**
 * Salt okunur görünüm: [collection]'ın kayıtları, seçilen sütunlarla ([columns]: `title`, ilişki üzerinden bir adım
 * `customer.name`; boşsa koleksiyonun herkese uygun alanları), sabit koşul ([filter], liste filtresi sözdizimi; `@me`
 * istekteki kullanıcı) ve sıralamayla. [report] verilirse kayıtlar yerine özet (gruplama ve toplamlar). Koleksiyon
 * kurallarından bağımsızdır: kim görebilir [access] ve [roles] ile belirlenir (yönetici her zaman).
 */
@Serializable
data class ViewConfig(
    val name: String,
    val collection: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val title: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val columns: List<String> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val filter: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val sort: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val report: ReportConfig? = null,
    /** `public`, `auth` veya `admin` (`owner` ve `nobody` kullanılmaz). */
    val access: Rule = Rule.ADMIN,
    /** `admin` erişiminde ayrıca bu rollerdeki kullanıcılar. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val roles: List<String> = emptyList(),
) {
    val label: String get() = title?.takeIf { it.isNotBlank() } ?: name
}

/** Görünümün özeti: kayıt sayısı her zaman; isteğe bağlı gruplama (`alan` veya zaman alanında `alan:day|month|year`). */
@Serializable
data class ReportConfig(
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val groupBy: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val sum: List<String> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val avg: List<String> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val min: List<String> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val max: List<String> = emptyList(),
) {
    /** Özet ucu (`/aggregate`) parametreleri. */
    fun params(): List<Pair<String, String>> = buildList {
        add("count" to "")
        listOf("sum" to sum, "avg" to avg, "min" to min, "max" to max).forEach { (fn, names) -> if (names.isNotEmpty()) add(fn to names.joinToString(",")) }
        groupBy?.let { add("groupBy" to it) }
    }
}

/**
 * Saklama süresi: [days] günden eski kayıtlar saatte bir otomatik silinir (normal silme gibi: ilişkiler, dosyalar ve
 * çöp kutusu uygulanır). [field]: yaşın ölçüldüğü zaman alanı (varsayılan `created_at`; `updated_at` veya koleksiyonun
 * bir datetime alanı). [filter]: yalnızca bu koşula uyanlar (liste filtresi sözdizimi, örn. `status=done`).
 */
@Serializable
data class RetentionConfig(
    val days: Int = 30,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val field: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val filter: String? = null,
)

/** Kayıt geçmişi. Kayıt başına en çok [keep] sürüm tutulur (eskiler silinir). */
@Serializable
data class HistoryConfig(val keep: Int = 50)

/** Çöp kutusu. Silinen kayıt [days] gün sonra (dosyalarıyla) kalıcı olarak silinir. */
@Serializable
data class TrashConfig(val days: Int = 30)

/**
 * Güvenlik duvarı. Adresler IP veya CIDR (`203.0.113.7`, `192.168.1.0/24`, `2001:db8::/32`). [deny] her zaman önce
 * gelir; [allow] doluysa yalnızca oradaki adresler girer ([allowLocalNetwork] iken yerel ağ da). Telefonun kendisi
 * (uygulama) hiçbir zaman engellenmez. IP başına dakika sınırı ve hatalı denemede geçici engel `server.limits`'te.
 */
@Serializable
data class FirewallConfig(
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val allow: List<String> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val deny: List<String> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val allowLocalNetwork: Boolean = true,
    /** Giriş yapmış kullanıcı başına dakikada en çok istek (yöneticiler hariç); boşsa yalnızca IP sınırı. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val userRequestsPerMinute: Int? = null,
)

/**
 * Sağlık uyarıları. Durum uyarıları (disk, hata oranı, sertifika, pil, sunucu/bileşen) koşul sürdükçe [repeatHours]
 * saatte bir hatırlatılır, düzelince bir kez "düzeldi" gönderilir; olay uyarıları (yedek başarısız, uygulama çöktü)
 * aynı nedenle [repeatHours] içinde tekrar etmez. Sessiz saatlerde yalnızca kritik uyarılar gider; diğerleri sessiz
 * saat bitince hâlâ sürüyorsa gönderilir.
 */
@Serializable
data class AlertsConfig(
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val enabled: Boolean = true,
    /** Telefon bildirimi. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val notification: Boolean = true,
    /** E-posta alıcıları (`email` SMTP ayarlarıyla gönderilir). */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val email: List<String> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val telegram: TelegramConfig? = null,
    /** Boş depolama bu kadar MB'ın altına inince. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val diskFreeMb: Int = 500,
    /** Son 5 dakikada (en az 20 istek) 5xx oranı bu yüzdeyi aşınca. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val errorRatePercent: Int = 20,
    /** Let's Encrypt sertifikasının bitmesine bu kadar gün kalınca (yenileme başarısız demektir). */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val certificateDays: Int = 14,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val batteryTempC: Int = 45,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val quietHours: QuietHours? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val repeatHours: Int = 6,
)

/**
 * Canlı kopya. [source]: bu sunucu kopyalanabilir; her yazma (içe aktarma ve hesap işlemleri dahil) değişiklik
 * günlüğüne (`_changes`) düşer, kopyalar yönetici anahtarıyla çeker. [replicaOf]: bu sunucu başka bir sunucunun salt
 * okunur kopyası.
 */
@Serializable
data class ReplicationConfig(
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val source: Boolean = false,
    /** Değişiklik günlüğü bu kadar gün saklanır; daha uzun kopmuş kopya baştan eşitlenir. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val keepDays: Int = 7,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val replicaOf: ReplicaSource? = null,
    /** Otomatik devralma ve geri alma (bkz. [FailoverConfig]); ana ve kopyada ayrı ayrı ayarlanır. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val failover: FailoverConfig = FailoverConfig(),
    /**
     * Devralmadan sonra eski ana sunucu (adres ve anahtar): bu sunucu ana iken ona ulaşınca kopyaya dönmesini söyler
     * (iki sunucuya ayrı ayrı yazılmasın diye).
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val formerPrimary: ReplicaSource? = null,
)

/**
 * [auto]: kopya, ana sunucuya [afterSec] saniye ulaşamazsa kendiliğinden ana sunucu olur. Kendi ağı yoksa ya da
 * [witness] adresine (örn. modem veya bilinen bir site) ulaşamıyorsa devralmaz: kopan kendisi olabilir.
 * [failback]: bu sunucu devredilip kopyaya dönmüşse, yeni ana sunucuyu yakalayınca ana rolünü kayıpsız geri alır.
 */
@Serializable
data class FailoverConfig(
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val auto: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val afterSec: Int = 120,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val witness: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val failback: Boolean = false,
)

/**
 * Kopyanın kaynağı: ana sunucunun API adresi ve yönetici anahtarı (`secret:` referansı); [intervalSec] saniyede bir
 * çeker. [configHash]: kopyanın konfigürasyonunun üretildiği ana konfigürasyonun özeti (değişince kopya yeniden kurulur).
 */
@Serializable
data class ReplicaSource(
    val url: String,
    val key: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val intervalSec: Int = 5,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val configHash: String? = null,
    /** Ana sunucunun kimliği (`X-Instance-Id`); devralmadan sonra eski ana sunucuyu tanımak için. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val id: String? = null,
    /** Bu sunucu ana iken devredildi ve kopyaya döndü ([FailoverConfig.failback] için). */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val demoted: Boolean = false,
)

/**
 * Telegram botu: [botToken] @BotFather'dan (`secret:` referansı), [chatId] mesajların gideceği sohbet. [adminChatId]
 * verilirse bot o sohbetten yönetim komutlarını (`/durum`, `/yedek`, `/loglar`, `/baslat`, `/durdur`) kabul eder.
 */
@Serializable
data class TelegramConfig(
    val botToken: String,
    val chatId: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val adminChatId: String? = null,
)

/** Sessiz saatler (yerel saat, `HH:mm`); [from] > [to] ise gece yarısını aşar (23:00-07:00). */
@Serializable
data class QuietHours(val from: String = "23:00", val to: String = "07:00")

/** Denetim kaydının saklama süresi (gün); eski satırlar günde bir atılır. */
@Serializable
data class AuditConfig(val keepDays: Int = 365)

/** Uzman modu: yerleşik uçlar (anahtar: `auth.login`, `batch`, ... bkz. Endpoint). */
@Serializable
data class ApiConfig(
    val endpoints: Map<String, EndpointConfig> = emptyMap(),
    /** GraphQL ucu (`/graphql`); REST'le aynı kurallar. Kapalıyken uç yoktur. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val graphql: Boolean = false,
)

/** Yerleşik bir uç: kapatılabilir veya yolu değiştirilebilir (API yoluna göre, örn. `/hesap/giris`). */
@Serializable
data class EndpointConfig(
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val enabled: Boolean = true,
    /** Boşsa varsayılan yol. Yoldaki `{id}` gibi parametreler varsayılandakilerle aynı olmalı. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val path: String? = null,
)

/**
 * Sistem alanının API'deki hali: yeni ad veya kaldırılması. Veritabanı sütunu değişmez (veri taşınmaz, geri almak
 * kolaydır); ad yanıtlarda, filtre/sıralama/alan seçiminde, gövdelerde, canlı abonelikte, webhook'larda ve
 * dışa/içe aktarmada geçerlidir. Script'ler veritabanı adlarını görür.
 */
@Serializable
data class SystemFieldConfig(
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val name: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val removed: Boolean = false,
)

@Serializable
data class FieldConfig(
    val name: String,
    val type: FieldType,
    val required: Boolean = false,
    val unique: Boolean = false,
    /**
     * Bu alana göre sık filtreleme/sıralama yapılıyorsa indeks (benzersiz değil). İlişki ve benzersiz alanlar zaten
     * indekslidir. Çok kayıtta okumayı hızlandırır, yazmayı biraz yavaşlatır.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val index: Boolean = false,
    /** Kayıt oluşturulurken alan gönderilmezse kullanılacak değer. */
    val default: JsonElement? = null,
    /**
     * Kayıtlı veri varken alanın tipi değiştirildiğinde mevcut değerlere ne olacağı (kullanıcının seçimi).
     * Yalnızca veritabanındaki tip [TypeChange.from] ise uygulanır; sonraki tip değişiklikleri yeniden seçim ister.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val typeChange: TypeChange? = null,
    /** `relation` alanının hedef koleksiyonu. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val collection: String? = null,
    /** `relation` alanında hedef kayıt silinince ne olacağı; boşsa zorunlu alanda `restrict`, değilse `set_null`. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val onDelete: OnDelete? = null,
    /** `file`: dosya başına üst sınır (MB); boşsa 10. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val maxSizeMb: Int? = null,
    /**
     * `file`: kabul edilen içerik tipleri, örn. `image/png`, `application/pdf`; bir türün tamamı için alt tür yerine
     * yıldız yazılır (tüm görseller). Boşsa hepsi. Tip dosyanın içeriğinden tespit edilir.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val mimeTypes: List<String>? = null,
    /** `file`: birden çok dosya (en çok [FileLimits.MAX_FILES]). */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val multiple: Boolean? = null,
    /** `file`: görseller için izinli küçük resim boyutları, örn. `["100x100", "400x0"]` (0: en-boy oranını koru). */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val thumbs: List<String>? = null,
    /** Alanı kim görür; boşsa kaydı görebilen herkes. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val visibility: FieldVisibility? = null,
    /** `select` / `multiselect`: izinli değerler. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val options: List<String>? = null,
    /** `text`: biçim denetimi. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val format: TextFormat? = null,
    /** `text`: en az / en çok karakter; `multiselect`: en az / en çok seçim. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val minLength: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val maxLength: Int? = null,
    /** `text`: değerin tamamı bu düzenli ifadeye uymalı (örn. `^[0-9]{5}$`). */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val pattern: String? = null,
    /** `integer` / `real`: en küçük ve en büyük değer. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val min: Double? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val max: Double? = null,
    /**
     * Hesaplanmış alan: kayıt yazılırken bu ifadeden hesaplanır, istemci yazamaz. Örn. `adet * fiyat`,
     * `ad + " " + soyad`, `round(tutar * 1.2, 2)`, `if(stok > 0, "var", "yok")`. Bkz. Expression.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val computed: String? = null,
    /**
     * Değer veritabanında (ve yedeklerde, canlı kopyada, geçmişte) şifreli durur; API'de yetkiliye açık döner. Yalnızca
     * metin ve JSON alanlarında; şifreli alana göre filtrelenemez, sıralanamaz, aranamaz, benzersiz veya indeksli olamaz.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val encrypted: Boolean = false,
) {
    val effectiveOnDelete: OnDelete get() = onDelete ?: if (required) OnDelete.RESTRICT else OnDelete.SET_NULL

    val isMultiple: Boolean get() = multiple == true

    val maxBytes: Long get() = (maxSizeMb ?: FileLimits.DEFAULT_MAX_MB) * 1024L * 1024L

    /** İzinli küçük resim boyutları; `100x100` her zaman izinli. */
    val thumbSizes: Set<String> get() = setOf(FileLimits.DEFAULT_THUMB) + thumbs.orEmpty()
}

object FileLimits {
    const val DEFAULT_MAX_MB = 10
    const val MAX_MB = 1024
    const val MAX_FILES = 20
    const val DEFAULT_THUMB = "100x100"
    const val MAX_THUMB = 2000
    val THUMB = Regex("^(\\d{1,4})x(\\d{1,4})$")
    val MIME_PATTERN = Regex("^[a-z]+/([a-z0-9.+-]+|\\*)$")
}

@Serializable
data class TypeChange(
    /** Veritabanındaki (eski) tip. */
    val from: FieldType,
    val action: TypeChangeAction,
)

@Serializable
enum class TypeChangeAction {
    /** Değerler yeni tipe çevrilir; çevrilemeyenler boşalır (şema planı kaç tane olduğunu gösterir). */
    @SerialName("convert") CONVERT,

    /** Mevcut değerlerin hepsi silinir (alan boşalır). */
    @SerialName("clear") CLEAR,
}

@Serializable
enum class FieldType {
    @SerialName("text") TEXT,
    @SerialName("integer") INTEGER,
    @SerialName("real") REAL,
    @SerialName("boolean") BOOLEAN,
    /** ISO-8601 zaman damgası, örn. 2026-10-13T10:00:00Z */
    @SerialName("datetime") DATETIME,
    /** Serbest JSON (nesne/dizi); TEXT olarak saklanır. */
    @SerialName("json") JSON,

    /** Başka bir koleksiyondaki kaydın kimliği ([FieldConfig.collection]); tam sayı olarak saklanır. */
    @SerialName("relation") RELATION,

    /** Yüklenen dosya(lar); veritabanında yalnızca dosya adı (birden çoksa JSON dizi), dosya `files/storage` altında. */
    @SerialName("file") FILE,

    /** [FieldConfig.options] içinden tek değer; metin olarak saklanır. */
    @SerialName("select") SELECT,

    /** [FieldConfig.options] içinden birden çok değer; JSON dizi metni olarak saklanır. */
    @SerialName("multiselect") MULTISELECT,
}

/** Metin alanının biçimi. */
@Serializable
enum class TextFormat(val title: String) {
    @SerialName("email") EMAIL(tr("E-posta")),
    @SerialName("url") URL(tr("Web adresi (http/https)")),
}

/** İlişkili kayıt silinince bu alanı taşıyan kayıtlara ne olacağı. */
@Serializable
enum class OnDelete(val title: String) {
    /** Bu kayıtlar da silinir. */
    @SerialName("cascade") CASCADE(tr("Bu kayıtlar da silinsin")),

    /** Alan boşaltılır (zorunlu alanda kullanılamaz). */
    @SerialName("set_null") SET_NULL(tr("Alan boşaltılsın")),

    /** Bağlı kayıt varken silme reddedilir (409). */
    @SerialName("restrict") RESTRICT(tr("Silme engellensin")),
}

@Serializable
enum class RestartPolicy {
    @SerialName("never") NEVER,
    @SerialName("on_failure") ON_FAILURE,
    @SerialName("always") ALWAYS,
}

/** Kullanıcının etkinleştirdiği yan servis (Postgres, Redis, cloudflared ...). */
@Serializable
data class ComponentConfig(
    /** Bileşen deposundaki paket kimliği, örn. "cloudflared". */
    val id: String,
    val enabled: Boolean = true,
    /** Paketin varsayılan argümanlarının yerine geçer (boşsa varsayılanlar kullanılır). */
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val restart: RestartPolicy = RestartPolicy.ON_FAILURE,
    /** true: ortak ortam değişkenleri ([ServerConfig.env]) de verilir (bileşenin kendi değişkenleri önceliklidir). */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val sharedEnv: Boolean = false,
)

@Serializable
data class AccessConfig(
    val mode: AccessMode = AccessMode.LOCAL,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val cloudflare: CloudflareAccess = CloudflareAccess(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val direct: DirectAccess = DirectAccess(),
    /** İnternete açık modda kimlik doğrulamasız çalışmayı kullanıcı bilerek onayladı. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val allowNoAuth: Boolean = false,
    /**
     * Yerel ağda mDNS ile duyur (`_droidserver._tcp`; ad, port, API yolu): istemciler adres yazmadan bulur. Sır
     * içermez.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val discovery: Boolean = true,
)

@Serializable
enum class CloudflareTunnel {
    /** Hesap gerekmez; `*.trycloudflare.com` adresi her başlatmada değişir. */
    @SerialName("quick") QUICK,

    /** Cloudflare hesabında oluşturulan tünel; token `cloudflared` bileşeninin `TUNNEL_TOKEN` ortamında. */
    @SerialName("named") NAMED,
}

@Serializable
data class CloudflareAccess(
    val tunnel: CloudflareTunnel = CloudflareTunnel.QUICK,
    /** Adlandırılmış tünelin genel adresi (gösterim ve API örnekleri için), örn. api.example.com. */
    val hostname: String? = null,
)

@Serializable
enum class DirectNetwork(val title: String) {
    /** Ev/iş modemi: IPv4 için modemde port yönlendirme (UPnP/NAT-PMP ile otomatik denenir). */
    @SerialName("wifi") WIFI("Wi-Fi"),

    /** Mobil veri: IPv4 neredeyse her zaman paylaşımlı (CGNAT); IPv6 ile erişilir. */
    @SerialName("mobile") MOBILE(tr("mobil veri")),
}

@Serializable
data class DirectAccess(
    val network: DirectNetwork = DirectNetwork.WIFI,
    /** HTTPS portu (telefonda ve modemde aynı). */
    val port: Int = 8443,
    /** Modemde port yönlendirmeyi UPnP / NAT-PMP ile otomatik dene (Wi-Fi). */
    val portMapping: Boolean = true,
    /** Değişen IP adresini sabit bir ada bağlamak için DuckDNS (isteğe bağlı). */
    val ddns: DdnsConfig? = null,
)

@Serializable
data class DdnsConfig(
    /** DuckDNS alt alan adı (`<ad>.duckdns.org`'un `<ad>` kısmı). */
    val domain: String,
    /** DuckDNS token'ı; `secret:<ad>` referansı olabilir. */
    val token: String,
    /**
     * Bu ad için Let's Encrypt sertifikası al (DNS doğrulaması DuckDNS üzerinden): tarayıcılar uyarı vermez,
     * sabitleme gerekmez. Açmak Let's Encrypt kullanım koşullarını kabul etmek demektir.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val certificate: Boolean = false,
)
