package dev.droidserver.app.ui.wizard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.droidserver.app.AppGraph
import dev.droidserver.app.data.DraftStore
import dev.droidserver.core.auth.ApiKeys
import dev.droidserver.core.config.AccessMode
import dev.droidserver.core.config.ApiKeyConfig
import dev.droidserver.core.config.AdminPanelConfig
import dev.droidserver.core.config.AlertsConfig
import dev.droidserver.core.config.AuditConfig
import dev.droidserver.core.config.FirewallConfig
import dev.droidserver.core.config.ReplicaSource
import dev.droidserver.core.config.ReplicationConfig
import dev.droidserver.core.config.ViewConfig
import dev.droidserver.core.i18n.tr
import dev.droidserver.core.replication.ChangeLog
import dev.droidserver.core.config.TelegramConfig
import dev.droidserver.core.api.CollectionEndpoint
import dev.droidserver.core.config.EmailConfig
import dev.droidserver.core.config.EndpointConfig
import dev.droidserver.core.config.LimitsConfig
import dev.droidserver.core.config.SystemFieldConfig
import dev.droidserver.core.config.PushConfig
import dev.droidserver.core.config.PushRule
import dev.droidserver.core.push.Vapid
import dev.droidserver.core.config.OAuthConfig
import dev.droidserver.core.config.OAuthProvider
import dev.droidserver.core.config.OAuthProviderConfig
import dev.droidserver.core.config.SmtpSecurity
import dev.droidserver.core.mail.SmtpClient
import dev.droidserver.core.config.RealtimeConfig
import dev.droidserver.core.config.WebhookConfig
import dev.droidserver.core.config.BackupConfig
import dev.droidserver.core.config.CloudflareTunnel
import dev.droidserver.core.config.DdnsConfig
import dev.droidserver.core.config.DirectNetwork
import dev.droidserver.core.config.DirectAccess as DirectAccessConfig
import dev.droidserver.core.config.AuthMode
import dev.droidserver.core.config.ChangeWarnings
import dev.droidserver.core.config.CollectionConfig
import dev.droidserver.core.config.CollectionTemplate
import dev.droidserver.core.security.FieldCipher
import dev.droidserver.core.templates.AppTemplate
import dev.droidserver.core.config.ConfigDefaults
import dev.droidserver.core.config.ConfigJson
import dev.droidserver.core.config.ConfigSecrets
import dev.droidserver.core.config.ConfigSummary
import dev.droidserver.core.config.ConfigValidator
import dev.droidserver.core.config.CorsConfig
import dev.droidserver.core.config.CorsMode
import dev.droidserver.core.config.DbEngine
import dev.droidserver.core.config.FailoverConfig
import dev.droidserver.core.config.FieldConfig
import dev.droidserver.core.config.FieldDefaults
import dev.droidserver.core.config.FieldRules
import dev.droidserver.core.config.RemoteBackupConfig
import dev.droidserver.core.config.FieldType
import dev.droidserver.core.config.OnDelete
import dev.droidserver.core.config.PostgresDefaults
import dev.droidserver.core.config.PostgresUrl
import dev.droidserver.core.config.Rule
import dev.droidserver.core.config.RoleConfig
import dev.droidserver.core.config.RuleCondition
import dev.droidserver.core.config.Rules
import dev.droidserver.core.config.SecretSink
import dev.droidserver.core.config.ScriptsConfig
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.config.StaticConfig
import dev.droidserver.core.config.SystemCollections
import dev.droidserver.core.config.TeamsConfig
import dev.droidserver.core.config.TypeChange
import dev.droidserver.core.config.TypeChangeAction
import dev.droidserver.core.config.TypeConversions
import dev.droidserver.core.config.UsersConfig
import dev.droidserver.core.config.ValidationError
import dev.droidserver.core.config.accessToken
import dev.droidserver.core.config.location
import dev.droidserver.core.config.postgresPassword
import dev.droidserver.core.config.withAccess
import dev.droidserver.core.config.withAccessToken
import dev.droidserver.core.config.withPostgres
import dev.droidserver.core.config.withSqlite
import dev.droidserver.core.server.DeployOrchestrator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject

/** Erişim adımına özgü hata yolu (konfigürasyonda karşılığı bileşen ortamıdır). */
const val ACCESS_TOKEN_PATH = "access.token"

/** Veri taşıma seçimi için hata yolu (konfigürasyonda saklanmaz). */
const val MOVE_DATA_PATH = "database.move"

/** "Rol ekle"nin sırayla önerdiği adlar. */
private val ROLE_SUGGESTIONS = listOf("editor", "moderator", "staff", "viewer")

val PLACEHOLDER_WARNING: String get() = tr("Örnek API anahtarı kullanılıyor; bu anahtar herkesçe biliniyor. Güvenlik adımında yeniden üretebilirsiniz.")

/** Postgres parolası değiştiğinde nasıl uygulanacağı. */
enum class PasswordMethod {
    /** Eski parolayla bağlanıp değiştirilir (sunucu çalışmaya devam eder). */
    CHANGE,

    /** Eski parola bilinmiyorsa: Postgres kısa süre durdurulur, tek kullanıcı modunda sıfırlanır. */
    RESET,
}

/** Koleksiyon düzenleyicideki hazır kural kümeleri. */
enum class RulePreset(val title: String, val description: String, val rules: Rules, val needsUsers: Boolean) {
    ADMIN_ONLY(tr("Yalnızca yönetici"), tr("API anahtarı / yönetici token'ı olan her şeyi yapar; başkası hiçbir şey."), Rules(), false),
    PUBLIC_READ(
        tr("Herkes okur, yönetici yazar"),
        tr("Ürün kataloğu, duyurular gibi."),
        Rules(list = Rule.PUBLIC, view = Rule.PUBLIC),
        false,
    ),
    USERS_POST(
        tr("Herkes okur, kullanıcılar ekler, sahibi düzenler"),
        tr("Blog yazıları, yorumlar gibi: giriş yapan ekler, yalnızca kendi kaydını değiştirip silebilir."),
        Rules(Rule.PUBLIC, Rule.PUBLIC, Rule.AUTH, Rule.OWNER, Rule.OWNER),
        true,
    ),
    PRIVATE(tr("Herkes yalnızca kendi kayıtlarını görür"), tr("Kişisel yapılacaklar, notlar gibi."), Rules(Rule.OWNER, Rule.OWNER, Rule.AUTH, Rule.OWNER, Rule.OWNER), true),
    TEAM(
        tr("Takım üyeleri görür ve düzenler"),
        tr("Aile alışveriş listesi, ekip görevleri gibi: her takım yalnızca kendi kayıtlarını görür."),
        Rules(Rule.TEAM, Rule.TEAM, Rule.TEAM, Rule.TEAM, Rule.TEAM),
        true,
    ),
}

enum class WizardStep(val title: String, private val paths: List<String>) {
    GENERAL(tr("Genel"), listOf("name", "server.host", "server.port", "server.basePath", "server.limits", "static", "realtime", "api", "alerts", "phone")),
    DATABASE(tr("Veritabanı"), listOf("database", "backup", "replication")),
    USERS(tr("Kullanıcılar"), listOf("auth.users", "email", "api.endpoints.auth", "api.endpoints.oauth")),
    COLLECTIONS(tr("Koleksiyonlar"), listOf("collections", "views")),
    SECURITY(tr("Güvenlik"), listOf("auth.mode", "auth.apiKeys", "auth.jwtSecret", "auth.keys", "server.cors", "scripts", "adminPanel", "webhooks", "firewall", "audit")),
    ACCESS(tr("Erişim"), listOf("components", "access", ACCESS_TOKEN_PATH)),
    SUMMARY(tr("Özet ve deploy"), emptyList());

    /** Hata bu adımın alanlarına mı ait? Özet adımı tüm hataları gösterir. */
    fun owns(path: String) = this == SUMMARY || paths.any { path == it || path.startsWith("$it.") || path.startsWith("$it[") }
}

/**
 * Sihirbaz ve JSON düzenleyicinin paylaştığı taslak. Her adım taslağın bir parçasını günceller;
 * her değişiklik [DraftStore]'a yazılır (uygulama kapanıp açılınca kaybolmaz). Ekran döndürmede
 * ViewModel'in kendisi korunur.
 *
 * Saf Kotlin tutulur (Android API'si yok): testleri JVM'de koşar. Sırlar taslağa düz metin yazılmaz;
 * üretilen anahtarlar [SecretSink]'e konur, taslakta `secret:<ad>` referansı durur.
 */
class WizardViewModel(
    initialDraft: ServerConfig,
    active: ServerConfig?,
    private val drafts: DraftStore,
    private val secrets: SecretSink,
    private val resolveSecret: (String) -> String?,
    private val freshDefault: () -> ServerConfig,
    /** Adlandırılmış anahtarın son kullanımı (çalışan sunucudan). */
    val keyLastUsed: (String) -> java.time.Instant? = { null },
    /** Uzman modu: otomatik eklenen alanlar ve uçlar da görünür ve düzenlenir (uygulama ayarı, konfigürasyon değil). */
    expertMode: Boolean = false,
    private val saveExpertMode: (Boolean) -> Unit = {},
) : ViewModel() {
    private val _expert = MutableStateFlow(expertMode)
    val expert: StateFlow<Boolean> = _expert.asStateFlow()

    fun setExpert(on: Boolean) {
        _expert.value = on
        saveExpertMode(on)
    }


    /** Sır adı -> değer. Bir adın değeri hiç değişmediği için önbellek güvenli. */
    private val secretCache = HashMap<String, String?>()

    private val _draft = MutableStateFlow(initialDraft)
    val draft: StateFlow<ServerConfig> = _draft.asStateFlow()

    private val _active = MutableStateFlow(active)
    /** Kaydedilmiş (çalışan) konfigürasyon; veri kaybı uyarıları buna göre hesaplanır. */
    val active: StateFlow<ServerConfig?> = _active.asStateFlow()

    private val _moveData = MutableStateFlow<Boolean?>(null)
    /** Veritabanı değişirken mevcut kayıtlar yenisine taşınsın mı? null: kullanıcı henüz seçmedi. */
    val moveData: StateFlow<Boolean?> = _moveData.asStateFlow()

    private val _moveOptions = MutableStateFlow(DeployOrchestrator.MoveOptions())
    /** Taşıma sırasında sunucu durur mu / salt okunur mu, hedef doluysa ne olur. */
    val moveOptions: StateFlow<DeployOrchestrator.MoveOptions> = _moveOptions.asStateFlow()

    private val _passwordMethod = MutableStateFlow(PasswordMethod.CHANGE)
    /** Postgres parolası değiştiyse kaydederken nasıl uygulanacağı. */
    val passwordMethod: StateFlow<PasswordMethod> = _passwordMethod.asStateFlow()

    private val _errors = MutableStateFlow(computeErrors(initialDraft))
    val errors: StateFlow<List<ValidationError>> = _errors.asStateFlow()

    private val _step = MutableStateFlow(WizardStep.GENERAL)
    val step: StateFlow<WizardStep> = _step.asStateFlow()

    private val _editing = MutableStateFlow<Int?>(null)
    /** Düzenleme ekranı açık koleksiyonun sırası. */
    val editingCollection: StateFlow<Int?> = _editing.asStateFlow()

    val hasUnsavedChanges: Boolean get() = _draft.value != _active.value

    // ---- taslak

    fun update(transform: (ServerConfig) -> ServerConfig) {
        val next = transform(_draft.value)
        if (next == _draft.value) return
        setDraft(next)
        if (next == _active.value) drafts.clear() else drafts.save(next)
    }

    /** JSON düzenleyiciden gelen konfigürasyon. */
    fun replaceDraft(config: ServerConfig) = update { config }

    /** Kaydedilip deploy edildi: taslak, etkin konfigürasyon olur. */
    fun onSaved(saved: ServerConfig) {
        _active.value = saved
        resetChoices()
        setDraft(saved)
        drafts.clear()
        _step.value = WizardStep.GENERAL
        _editing.value = null
    }

    /**
     * Etkin dosya yeni biçime yükseltildi (anlamı aynı; düz metin sırlar referansa dönmüş olabilir).
     * Kaydedilmemiş değişiklik yoksa taslak da güncellenir, varsa korunur.
     */
    fun onFileUpgraded(saved: ServerConfig) {
        val unchanged = _draft.value == _active.value
        _active.value = saved
        if (unchanged) {
            setDraft(saved)
            drafts.clear()
        } else {
            _errors.value = computeErrors(_draft.value)
        }
    }

    /**
     * Sihirbaz dışında (Ortam ve sırlar, Uygulamalar, Bileşenler) yapılan bir değişiklik kaydedildi. Kaydedilmemiş
     * taslak yoksa taslak kaydedilenle aynı olur; varsa aynı değişiklik ([patch]) taslağa da uygulanır, kaybolmaz.
     */
    fun onExternalChange(saved: ServerConfig, patch: (ServerConfig) -> ServerConfig) {
        val unchanged = _draft.value == _active.value
        _active.value = saved
        if (unchanged) {
            setDraft(saved)
            drafts.clear()
        } else {
            update(patch)
        }
    }

    /** Kaydedilmemiş değişiklikleri atar. */
    fun discard() {
        resetChoices()
        setDraft(_active.value ?: freshDefault())
        drafts.clear()
        _step.value = WizardStep.GENERAL
        _editing.value = null
    }

    /** Kayıtta bir kez uygulanan seçimler (konfigürasyonda saklanmaz). */
    private fun resetChoices() {
        _moveData.value = null
        _moveOptions.value = DeployOrchestrator.MoveOptions()
        _passwordMethod.value = PasswordMethod.CHANGE
    }

    private fun setDraft(config: ServerConfig) {
        _draft.value = config
        _errors.value = computeErrors(config)
    }

    // ---- doğrulama ve gezinme

    fun errorsFor(step: WizardStep): List<ValidationError> = _errors.value.filter { step.owns(it.path) }

    fun errorAt(path: String): String? = _errors.value.firstOrNull { it.path == path }?.message

    fun canProceed(step: WizardStep = _step.value) = errorsFor(step).isEmpty()

    /** Hatanın düzeltileceği adım (özetteki "Düzelt" için). */
    fun stepFor(error: ValidationError): WizardStep =
        WizardStep.entries.firstOrNull { it != WizardStep.SUMMARY && it.owns(error.path) } ?: WizardStep.SUMMARY

    fun next(): Boolean {
        val s = _step.value
        if (s == WizardStep.SUMMARY || !canProceed(s)) return false
        _step.value = WizardStep.entries[s.ordinal + 1]
        _editing.value = null
        return true
    }

    /** Önce açık koleksiyon düzenleyicisini kapatır. false: ilk adımdayız, sihirbazdan çıkılmalı. */
    fun back(): Boolean {
        if (_editing.value != null) {
            _editing.value = null
            return true
        }
        val s = _step.value
        if (s.ordinal == 0) return false
        _step.value = WizardStep.entries[s.ordinal - 1]
        return true
    }

    /** Geri adımlara her zaman, ileri adımlara yalnızca aradaki adımlar geçerliyse gidilir. */
    fun goTo(target: WizardStep): Boolean {
        if (target.ordinal > _step.value.ordinal && WizardStep.entries.take(target.ordinal).any { !canProceed(it) }) return false
        _step.value = target
        _editing.value = null
        return true
    }

    // ---- 1. genel, 2. veritabanı

    fun setName(name: String) = update { it.copy(name = name) }

    fun setPort(port: Int) = update { it.copy(server = it.server.copy(port = port)) }

    fun setBasePath(path: String) = update { it.copy(server = it.server.copy(basePath = path)) }

    fun updateStatic(transform: (StaticConfig) -> StaticConfig) = update { it.copy(static = transform(it.static)) }

    fun updateScripts(transform: (ScriptsConfig) -> ScriptsConfig) = update { it.copy(scripts = transform(it.scripts)) }

    fun updateBackup(transform: (BackupConfig) -> BackupConfig) = update { it.copy(backup = transform(it.backup)) }

    fun updateAdminPanel(transform: (AdminPanelConfig) -> AdminPanelConfig) = update { it.copy(adminPanel = transform(it.adminPanel)) }

    fun updateRealtime(transform: (RealtimeConfig) -> RealtimeConfig) = update { it.copy(realtime = transform(it.realtime)) }

    fun updateFirewall(transform: (FirewallConfig) -> FirewallConfig) = update { it.copy(firewall = transform(it.firewall)) }

    fun setReplicationSource(on: Boolean) = update { it.copy(replication = it.replication.copy(source = on)) }

    /**
     * Bu sunucuyu [primary]'nin kopyası yapar: paylaşılan konfigürasyon (koleksiyonlar, kimlik, kurallar…) anadan, bu
     * sunucuya özgü kısım (port, veritabanı, erişim…) taslaktan. Anadan gelen sırlar ve anahtar şifreli depoya.
     */
    fun adoptPrimary(url: String, key: String, primary: ServerConfig, hash: String) {
        val secured = ConfigJson.decodeFromJsonElement(
            ServerConfig.serializer(),
            ConfigSecrets.externalize(ConfigJson.encodeToJsonElement(ServerConfig.serializer(), primary).jsonObject, secrets),
        )
        val local = _draft.value.copy(replication = ReplicationConfig(replicaOf = ReplicaSource(url.trim().trimEnd('/'), storeSecret("replica_key", key.trim()))))
        update {
            val merged = ChangeLog.merge(local, secured)
            merged.copy(replication = merged.replication.copy(replicaOf = merged.replication.replicaOf?.copy(configHash = hash)))
        }
        _editing.value = null
    }

    /** Kopya modundan çıkar (deploy edilince bu sunucu yazılabilir ana sunucu olur). */
    fun leaveReplica() = update { it.copy(replication = it.replication.copy(replicaOf = null)) }

    fun updateFailover(transform: (FailoverConfig) -> FailoverConfig) = update { it.copy(replication = it.replication.copy(failover = transform(it.replication.failover))) }

    fun setDiscovery(on: Boolean) = update { it.copy(access = it.access.copy(discovery = on)) }

    fun updateAudit(transform: (AuditConfig) -> AuditConfig) = update { it.copy(audit = transform(it.audit)) }

    fun updateAlerts(transform: (AlertsConfig) -> AlertsConfig) = update { it.copy(alerts = transform(it.alerts)) }

    /** Telegram bot token'ı şifreli depoya; sohbet kimliği korunur. */
    fun setTelegramToken(token: String) = updateAlerts { a ->
        a.copy(telegram = TelegramConfig(storeSecret("telegram_token", token.trim()), a.telegram?.chatId.orEmpty()))
    }

    /** Taslaktaki Telegram token'ı (çözülmüş; sohbet bulma ve deneme için). */
    fun telegramToken(): String? = _draft.value.alerts.telegram?.botToken?.let { secretValue(it) ?: it }?.takeIf { it.isNotBlank() && !it.startsWith("secret:") }

    // ---- uzman modu: uçlar, sistem alanları, sınırlar

    fun updateLimits(transform: (LimitsConfig) -> LimitsConfig) = update { it.copy(server = it.server.copy(limits = transform(it.server.limits))) }

    /** Varsayılana dönen ayar (açık, yol boş) dosyadan çıkarılır. */
    private fun Map<String, EndpointConfig>.with(key: String, value: EndpointConfig?): Map<String, EndpointConfig> {
        val clean = value?.let { it.copy(path = it.path?.trim()?.takeIf { p -> p.isNotEmpty() }) }
        return if (clean == null || clean == EndpointConfig()) this - key else this + (key to clean)
    }

    fun setEndpoint(key: String, value: EndpointConfig?) = update { it.copy(api = it.api.copy(endpoints = it.api.endpoints.with(key, value))) }

    fun setCollectionEndpoint(collection: Int, key: String, value: EndpointConfig?) =
        updateCollection(collection) { it.copy(endpoints = it.endpoints.with(key, value)) }

    /**
     * Koleksiyonun tüm uçlarını tek taban yola taşır: `/urunler` → `/urunler`, `/urunler/{id}`, `/urunler/export` ...
     * Boş: varsayılan yollar. Kapalı uçlar kapalı kalır.
     */
    fun setCollectionBasePath(collection: Int, base: String) = updateCollection(collection) { c ->
        val b = base.trim().trimEnd('/')
        var endpoints = c.endpoints
        for (e in CollectionEndpoint.entries) {
            val current = endpoints[e.key] ?: EndpointConfig()
            val path = if (b.isEmpty()) null else b + e.path.removePrefix("/collections/{c}").let { suffix ->
                when (e) {
                    CollectionEndpoint.LIST, CollectionEndpoint.CREATE -> ""
                    CollectionEndpoint.VIEW, CollectionEndpoint.UPDATE, CollectionEndpoint.DELETE -> "/{id}"
                    else -> suffix
                }
            }
            endpoints = endpoints.with(e.key, current.copy(path = path))
        }
        c.copy(endpoints = endpoints)
    }

    private fun Map<String, SystemFieldConfig>.with(key: String, value: SystemFieldConfig?): Map<String, SystemFieldConfig> {
        val clean = value?.let { it.copy(name = it.name?.trim()?.takeIf { n -> n.isNotEmpty() && n != key }) }
        return if (clean == null || clean == SystemFieldConfig()) this - key else this + (key to clean)
    }

    fun setCollectionSystemField(collection: Int, key: String, value: SystemFieldConfig?) =
        updateCollection(collection) { it.copy(systemFields = it.systemFields.with(key, value)) }

    fun setUserSystemField(key: String, value: SystemFieldConfig?) = updateUsers { it.copy(systemFields = it.systemFields.with(key, value)) }

    fun updateUsersSettings(transform: (UsersConfig) -> UsersConfig) = updateUsers(transform)

    // ---- görünümler

    fun addView() = update { cfg ->
        val base = cfg.collections.firstOrNull()?.name ?: return@update cfg
        cfg.copy(views = cfg.views + ViewConfig(uniqueName("${base}_view", cfg.views.map { it.name }), base))
    }

    fun updateView(index: Int, transform: (ViewConfig) -> ViewConfig) = update { cfg ->
        cfg.copy(views = cfg.views.mapIndexed { i, v -> if (i == index) transform(v) else v })
    }

    fun removeView(index: Int) = update { cfg -> cfg.copy(views = cfg.views.filterIndexed { i, _ -> i != index }) }

    // ---- webhook'lar

    fun addWebhook() = update { cfg ->
        cfg.copy(webhooks = cfg.webhooks + WebhookConfig(uniqueName("webhook", cfg.webhooks.map { it.name }), "https://"))
    }

    fun updateWebhook(index: Int, transform: (WebhookConfig) -> WebhookConfig) = update { cfg ->
        cfg.copy(webhooks = cfg.webhooks.mapIndexed { i, h -> if (i == index) transform(h) else h })
    }

    fun removeWebhook(index: Int) = update { cfg -> cfg.copy(webhooks = cfg.webhooks.filterIndexed { i, _ -> i != index }) }

    /**
     * Yeni adlandırılmış API anahtarı: konfigürasyona özeti yazılır, anahtarın kendisi döndürülür (bir kez gösterilir).
     * [validDays] null ise süresiz.
     */
    fun addNamedKey(name: String, readOnly: Boolean, collections: List<String>, validDays: Int?): String {
        val now = java.time.Instant.now()
        val taken = _draft.value.auth.keys.map { it.name }
        val created = ApiKeys.generate(
            uniqueName(name.trim().ifEmpty { "anahtar" }, taken), readOnly, collections,
            validDays?.let { now.plus(java.time.Duration.ofDays(it.toLong())) }, now,
        )
        update { cfg -> cfg.copy(auth = cfg.auth.copy(keys = cfg.auth.keys + created.config)) }
        return created.key
    }

    fun updateNamedKey(index: Int, transform: (ApiKeyConfig) -> ApiKeyConfig) = update { cfg ->
        cfg.copy(auth = cfg.auth.copy(keys = cfg.auth.keys.mapIndexed { i, k -> if (i == index) transform(k) else k }))
    }

    fun removeNamedKey(index: Int) = update { cfg -> cfg.copy(auth = cfg.auth.copy(keys = cfg.auth.keys.filterIndexed { i, _ -> i != index })) }

    /** Yeni imza sırrı üretir, depoya koyar ve (alıcıya girilmek üzere) bir kez gösterilecek değeri döndürür. */
    fun newWebhookSecret(index: Int): String {
        val value = ByteArray(24).also(java.security.SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        val ref = storeSecret("webhook_secret", value)
        updateWebhook(index) { it.copy(secret = ref) }
        return value
    }

    /** SQLite'a geçer veya dosya adını değiştirir; Postgres bileşeni varsa kapatılır. */
    fun setSqliteFile(file: String) = update { it.withSqlite(file) }

    /**
     * Bileşenle çalışan PostgreSQL'e geçer. Parola bir kez üretilir ve korunur: Postgres veri klasörü ilk
     * kurulumda bu parolayla oluşturulur, sonradan değişirse bağlantı kurulamaz.
     */
    fun selectPostgres() {
        val cfg = _draft.value
        if (cfg.database.engine == DbEngine.POSTGRES) return
        val password = cfg.postgresPassword() ?: _active.value?.postgresPassword()
            ?: storeSecret("db_password", ConfigDefaults.generateDbPassword())
        update { it.withPostgres(password) }
    }

    fun setPostgresConnection(url: String, user: String) =
        update { it.copy(database = it.database.copy(url = url, user = user)) }

    /** Bağlantı adresindeki veritabanı adını değiştirir. */
    fun setPostgresDatabaseName(name: String) = update { cfg ->
        cfg.copy(database = cfg.database.copy(url = PostgresUrl.withDatabase(cfg.database.url ?: PostgresDefaults.URL, name.trim())))
    }

    fun setCreateIfMissing(create: Boolean) = update { it.copy(database = it.database.copy(createIfMissing = create)) }

    /** Taslaktaki Postgres parolasının değeri (gösterme ve kopyalama için). */
    fun postgresPasswordValue(): String? = _draft.value.postgresPassword()?.let(::secretValue)

    fun regeneratePostgresPassword() {
        val ref = storeSecret("db_password", ConfigDefaults.generateDbPassword())
        update { it.withPostgres(ref) }
    }

    /** Kullanıcının kendi parolası. Geçersizse açıklama döner, taslak değişmez. */
    fun setPostgresPassword(value: String): String? {
        if (value.isEmpty()) return tr("Parola boş olamaz")
        // initdb parola dosyasının ilk satırını, tek kullanıcı modu her satırı ayrı komut olarak okur.
        if (value.any { it.isISOControl() }) return tr("Parola satır sonu veya kontrol karakteri içeremez")
        val ref = storeSecret("db_password", value)
        update { it.withPostgres(ref) }
        return null
    }

    /** Aynı PostgreSQL veritabanında parola değişti mi? Kaydederken [passwordMethod] ile uygulanır. */
    val postgresPasswordChanged: Boolean
        get() {
            val a = _active.value ?: return false
            val d = _draft.value
            if (a.database.engine != DbEngine.POSTGRES || d.database.engine != DbEngine.POSTGRES) return false
            if (a.database.location() != d.database.location()) return false
            val old = a.postgresPassword()?.let(::secretValue)
            val new = d.postgresPassword()?.let(::secretValue)
            return new != null && old != new
        }

    fun setPasswordMethod(method: PasswordMethod) {
        _passwordMethod.value = method
    }

    /** Çalışan konfigürasyondakinden farklı bir veritabanı mı seçildi? */
    val databaseChanged: Boolean get() = databaseChanged(_draft.value)

    private fun databaseChanged(cfg: ServerConfig) =
        _active.value?.let { it.database.location() != cfg.database.location() } ?: false

    fun setMoveData(move: Boolean) {
        _moveData.value = move
        _errors.value = computeErrors(_draft.value)
    }

    fun setMoveOptions(transform: (DeployOrchestrator.MoveOptions) -> DeployOrchestrator.MoveOptions) {
        _moveOptions.value = transform(_moveOptions.value)
    }

    /** Kaydederken veriler taşınacak mı? */
    fun shouldMoveData(): Boolean = databaseChanged && _moveData.value == true

    // ---- 3. koleksiyonlar

    /** Şablondan koleksiyon ekler (ad çakışırsa `_2`, `_3` ...) ve düzenleyicisini açar. */
    fun addCollection(template: CollectionTemplate): Int {
        val base = template.create()
        update { cfg -> cfg.copy(collections = cfg.collections + base.copy(name = uniqueName(base.name, cfg.collections.map { it.name }))) }
        return _draft.value.collections.lastIndex.also { _editing.value = it }
    }

    /**
     * Hazır uygulamanın koleksiyonlarını ekler (gerekirse kullanıcı hesaplarını ve kimlik doğrulamayı açar). Ad
     * çakışmasında hiçbir şey değişmez, hata metni döner.
     */
    fun applyTemplate(template: AppTemplate, site: Boolean): String? {
        template.conflicts(_draft.value).takeIf { it.isNotEmpty() }?.let {
            return tr("Bu adlarda koleksiyon zaten var: {0}. Yeni profil olarak kurun veya önce kaldırın.", it.joinToString())
        }
        if (template.needsUsers) setUsersEnabled(true)
        update { template.apply(it, site) }
        _editing.value = null
        return null
    }

    fun openCollection(index: Int?) {
        _editing.value = index
    }

    fun removeCollection(index: Int) {
        update { cfg -> cfg.copy(collections = cfg.collections.filterIndexed { i, _ -> i != index }) }
        _editing.value = null
    }

    /** Koleksiyonun adını değiştirir; ona bağlanan ilişki alanları yeni adı izler. */
    fun renameCollection(index: Int, name: String) = update { cfg ->
        val old = cfg.collections.getOrNull(index)?.name ?: return@update cfg
        cfg.copy(
            collections = cfg.collections.mapIndexed { i, c ->
                val renamed = if (i == index) c.copy(name = name) else c
                renamed.copy(fields = renamed.fields.map { f -> if (f.type == FieldType.RELATION && f.collection == old) f.copy(collection = name) else f })
            },
        )
    }

    fun updateCollection(index: Int, transform: (CollectionConfig) -> CollectionConfig) = update { cfg ->
        cfg.copy(collections = cfg.collections.mapIndexed { i, c -> if (i == index) transform(c) else c })
    }

    fun addField(collection: Int) = updateCollection(collection) { c ->
        c.copy(fields = c.fields + FieldConfig(uniqueName("alan", c.fields.map { it.name }), FieldType.TEXT))
    }

    /** Sahip alanı silinirse koleksiyonun sahip alanı da boşalır. */
    fun removeField(collection: Int, field: Int) = updateCollection(collection) { c ->
        c.copy(
            fields = c.fields.filterIndexed { i, _ -> i != field },
            ownerField = c.ownerField.takeIf { it != c.fields.getOrNull(field)?.name },
            teamField = c.teamField.takeIf { it != c.fields.getOrNull(field)?.name },
            search = c.search - c.fields.getOrNull(field)?.name.orEmpty(),
        )
    }

    fun moveField(collection: Int, from: Int, to: Int) = updateCollection(collection) { c ->
        if (from !in c.fields.indices || to !in c.fields.indices) c
        else c.copy(fields = c.fields.toMutableList().apply { add(to, removeAt(from)) })
    }

    /**
     * Sahip alanının adı değişirse koleksiyonun sahip alanı yeni adı izler; arama alanı da (metin olmaktan çıkarsa
     * aramadan çıkar).
     */
    fun updateField(collection: Int, field: Int, transform: (FieldConfig) -> FieldConfig) = updateCollection(collection) { c ->
        val old = c.fields.getOrNull(field) ?: return@updateCollection c
        val new = transform(old)
        c.copy(
            fields = c.fields.mapIndexed { i, f -> if (i == field) new else f },
            ownerField = if (c.ownerField == old.name) new.name else c.ownerField,
            teamField = if (c.teamField == old.name) new.name else c.teamField,
            search = c.search.mapNotNull { s -> if (s == old.name) new.name.takeIf { new.type == FieldType.TEXT } else s },
        )
    }

    /** Alanı `?q=` aramasına ekler veya çıkarır (yalnızca metin alanları). */
    fun setSearchable(collection: Int, field: String, on: Boolean) = updateCollection(collection) { c ->
        val search = if (on) (c.search + field).distinct() else c.search - field
        c.copy(search = search, fullText = c.fullText && search.isNotEmpty())
    }

    /**
     * Tip değişince yeni tipe uymayan varsayılan değer silinir. Kayıtlı değerler için yapılmış seçim, artık bu
     * değişikliğe uymuyorsa (tip geri alındı, dönüştürme bu tipe yapılamıyor) silinir; yeniden sorulur.
     */
    fun setFieldType(collection: Int, field: Int, type: FieldType) {
        val old = activeFieldType(collection, field)
        val names = _draft.value.collections.map { it.name }
        val own = names.getOrNull(collection)
        updateField(collection, field) { f ->
            FieldRules.fitTo(f, type).copy(
                type = type,
                // İlişkide hedef olarak önce başka bir koleksiyon önerilir; ilişki değilse hedef ve silme davranışı atılır.
                collection = if (type == FieldType.RELATION) f.collection ?: names.firstOrNull { it != own } ?: own else null,
                onDelete = f.onDelete.takeIf { type == FieldType.RELATION },
                // Dosya ayarları yalnızca dosya alanında; dosya alanı benzersiz olamaz.
                maxSizeMb = f.maxSizeMb.takeIf { type == FieldType.FILE },
                mimeTypes = f.mimeTypes.takeIf { type == FieldType.FILE },
                multiple = f.multiple.takeIf { type == FieldType.FILE },
                thumbs = f.thumbs.takeIf { type == FieldType.FILE },
                unique = f.unique && type != FieldType.FILE,
                index = f.index && type != FieldType.FILE && type != FieldType.JSON,
                default = f.default?.takeIf { FieldDefaults.problem(type, it) == null },
                typeChange = f.typeChange?.takeIf {
                    old != null && type != old && it.from == old &&
                        (it.action != TypeChangeAction.CONVERT || TypeConversions.canConvert(old, type))
                },
            )
        }
    }

    /** Çalışan konfigürasyonda aynı koleksiyonun aynı adlı alanının tipi (kayıtlı verinin tipi). */
    fun activeFieldType(collection: Int, field: Int): FieldType? {
        val c = _draft.value.collections.getOrNull(collection) ?: return null
        val f = c.fields.getOrNull(field) ?: return null
        return activeFieldType(c.name, f.name)
    }

    private fun activeFieldType(collection: String, field: String): FieldType? =
        _active.value?.collections?.firstOrNull { it.name == collection }?.fields?.firstOrNull { it.name == field }?.type

    /** Tipi değişen alanın kayıtlı değerleri için seçim (dönüştür / boşalt). */
    fun setTypeChange(collection: Int, field: Int, action: TypeChangeAction) {
        val old = activeFieldType(collection, field) ?: return
        updateField(collection, field) { it.copy(typeChange = TypeChange(old, action)) }
    }

    fun setFieldDefault(collection: Int, field: Int, value: JsonElement?) = updateField(collection, field) { it.copy(default = value) }

    fun setRelationTarget(collection: Int, field: Int, target: String) = updateField(collection, field) { it.copy(collection = target) }

    /** null: varsayılan (zorunluysa engelle, değilse boşalt). */
    fun setOnDelete(collection: Int, field: Int, onDelete: OnDelete?) = updateField(collection, field) { it.copy(onDelete = onDelete) }

    fun setRule(collection: Int, op: Rules.Op, rule: Rule) = updateCollection(collection) { it.copy(rules = keepConditions(it.rules.with(op, rule))) }

    /** İşlemin kural koşulu; null kaldırır. */
    fun setRuleCondition(collection: Int, op: Rules.Op, condition: RuleCondition?) =
        updateCollection(collection) { it.copy(rules = it.rules.withCondition(op, condition)) }

    /** Koşullar yalnızca herkes / giriş yapmış / sahibi kurallarında anlamlı; yönetici ve kapalıda kaldırılır. */
    private fun keepConditions(rules: Rules): Rules =
        rules.copy(conditions = rules.conditions.filterKeys { k -> Rules.Op.entries.firstOrNull { it.key == k }?.let { rules[it] in CONDITIONAL } ?: false })

    /**
     * Hazır kural kümesi. Sahiplik gerektiren kümede sahip alanı yoksa kullanıcıya bağlı bir `owner` alanı eklenir;
     * kullanıcı hesapları kapalıysa açılır.
     */
    fun applyRulePreset(collection: Int, preset: RulePreset) {
        if (preset.needsUsers && !_draft.value.auth.users.enabled) setUsersEnabled(true)
        updateCollection(collection) { c -> c.copy(rules = keepConditions(preset.rules.copy(conditions = c.rules.conditions))) }
        if (preset.rules.all.contains(Rule.OWNER) && _draft.value.collections.getOrNull(collection)?.ownerField == null) addOwnerField(collection)
        if (preset.rules.all.contains(Rule.TEAM)) {
            if (!_draft.value.auth.users.teams.enabled) updateTeams { it.copy(enabled = true) }
            if (_draft.value.collections.getOrNull(collection)?.teamField == null) addTeamField(collection)
        }
    }

    /** null: sahip alanı yok. */
    fun setOwnerField(collection: Int, field: String?) = updateCollection(collection) { it.copy(ownerField = field) }

    /** Kullanıcılara bağlı `owner` ilişki alanı ekler (kullanıcı silinince kayıtları da silinir) ve sahip alanı yapar. */
    fun addOwnerField(collection: Int) = updateCollection(collection) { c ->
        val name = uniqueName("owner", c.fields.map { it.name })
        c.copy(
            fields = c.fields + FieldConfig(name, FieldType.RELATION, collection = SystemCollections.USERS, onDelete = OnDelete.CASCADE),
            ownerField = name,
        )
    }

    fun setTeamField(collection: Int, field: String?) = updateCollection(collection) { it.copy(teamField = field) }

    /** Takımlara bağlı `team` ilişki alanı ekler (takım silinince kayıtları da silinir) ve takım alanı yapar. */
    fun addTeamField(collection: Int) = updateCollection(collection) { c ->
        val name = uniqueName("team", c.fields.map { it.name })
        c.copy(
            fields = c.fields + FieldConfig(name, FieldType.RELATION, collection = SystemCollections.TEAMS, onDelete = OnDelete.CASCADE),
            teamField = name,
        )
    }

    fun updateTeams(transform: (TeamsConfig) -> TeamsConfig) = update { it.copy(auth = it.auth.copy(users = it.auth.users.copy(teams = transform(it.auth.users.teams)))) }

    /** Etkin konfigürasyondaki aynı adlı koleksiyona göre veri kaybı / deploy engeli uyarıları. */
    fun warningsFor(collection: Int): List<ChangeWarnings.FieldWarning> {
        val c = _draft.value.collections.getOrNull(collection) ?: return emptyList()
        return ChangeWarnings.forCollection(_active.value?.collections?.firstOrNull { it.name == c.name }, c)
    }

    fun removedCollections(): List<String> = ChangeWarnings.removedCollections(_active.value, _draft.value)

    // ---- kullanıcılar

    private fun updateUsers(transform: (UsersConfig) -> UsersConfig) = update { cfg ->
        cfg.copy(auth = cfg.auth.copy(users = transform(cfg.auth.users)))
    }

    /** Kullanıcı hesapları yönetici kimliği ister: kimlik doğrulama kapalıysa bir API anahtarı üretilir. */
    fun setUsersEnabled(enabled: Boolean) {
        if (enabled && _draft.value.auth.mode == AuthMode.NONE) setAuthMode(AuthMode.API_KEY)
        updateUsers { it.copy(enabled = enabled) }
    }

    fun setAllowSignup(allow: Boolean) = updateUsers { it.copy(allowSignup = allow) }

    // ---- alan şifreleme

    /** Şifreleme açılınca anahtar yoksa üretilip şifreli depoya konur; şifreli alan benzersiz, indeksli, aranabilir olamaz. */
    fun setFieldEncrypted(collection: Int, field: Int, on: Boolean) {
        if (on && _draft.value.database.fieldKey == null) {
            val ref = storeSecret("field_key", FieldCipher.generateKey())
            update { it.copy(database = it.database.copy(fieldKey = ref)) }
        }
        val name = _draft.value.collections.getOrNull(collection)?.fields?.getOrNull(field)?.name ?: return
        updateField(collection, field) { it.copy(encrypted = on, unique = it.unique && !on, index = it.index && !on) }
        if (on) setSearchable(collection, name, false)
    }

    /** Başka telefondaki sunucunun anahtarı (yedeği buraya yüklemek için). */
    fun setFieldKey(value: String) {
        FieldCipher.of(value)
        val ref = storeSecret("field_key", value.trim())
        update { it.copy(database = it.database.copy(fieldKey = ref)) }
    }

    // ---- özel roller

    /** Önerilen adlardan (editor, moderator…) henüz kullanılmayan ilki. */
    fun addRole() = updateUsers { u ->
        val used = u.roles.map { it.name }.toSet()
        val name = ROLE_SUGGESTIONS.firstOrNull { it !in used } ?: generateSequence(1) { it + 1 }.map { "rol$it" }.first { it !in used }
        u.copy(roles = u.roles + RoleConfig(name))
    }

    /** Ad değişince koleksiyon kurallarındaki izinler de yeni adı alır. */
    fun updateRole(index: Int, role: RoleConfig) {
        val old = _draft.value.auth.users.roles.getOrNull(index) ?: return
        update { cfg ->
            val users = cfg.auth.users
            cfg.copy(
                auth = cfg.auth.copy(users = users.copy(roles = users.roles.mapIndexed { i, r -> if (i == index) role else r })),
                collections = if (old.name == role.name) cfg.collections else cfg.collections.map { c ->
                    c.copy(rules = c.rules.copy(roles = c.rules.roles.mapValues { (_, names) -> names.map { if (it == old.name) role.name else it } }))
                },
            )
        }
    }

    /** Kaldırılan rolün kurallardaki izinleri de silinir. */
    fun removeRole(index: Int) {
        val old = _draft.value.auth.users.roles.getOrNull(index) ?: return
        update { cfg ->
            val users = cfg.auth.users
            cfg.copy(
                auth = cfg.auth.copy(users = users.copy(roles = users.roles.filterIndexed { i, _ -> i != index })),
                collections = cfg.collections.map { c ->
                    c.copy(rules = c.rules.copy(roles = c.rules.roles.mapValues { (_, names) -> names - old.name }.filterValues { it.isNotEmpty() }))
                },
            )
        }
    }

    /** Koleksiyon işleminde rolün izni. */
    fun setRoleAllowed(collection: Int, op: Rules.Op, role: String, allowed: Boolean) = updateCollection(collection) { c ->
        val names = c.rules.roles(op).let { if (allowed) (it + role).distinct() else it - role }
        c.copy(rules = c.rules.withRoles(op, names))
    }

    fun setSendVerification(on: Boolean) = updateUsers { it.copy(sendVerification = on) }

    fun setRequireVerified(on: Boolean) = updateUsers { it.copy(requireVerified = on) }

    // ---- e-posta

    fun updateEmail(transform: (EmailConfig) -> EmailConfig) = update { it.copy(email = transform(it.email)) }

    /** SMTP parolası sır deposuna konur, konfigürasyonda referansı kalır. */
    fun setEmailPassword(password: String) = updateEmail { e ->
        e.copy(password = password.trim().takeIf { it.isNotEmpty() }?.let { storeSecret("email_password", it) })
    }

    fun updateOAuth(transform: (OAuthConfig) -> OAuthConfig) = update { cfg ->
        cfg.copy(auth = cfg.auth.copy(users = cfg.auth.users.copy(oauth = transform(cfg.auth.users.oauth))))
    }

    /** Sağlayıcıyı ekler (boş kimlikle) veya kaldırır; ilk sağlayıcıda sunucu adresi erişim ayarından önerilir. */
    fun setOAuthProvider(provider: OAuthProvider, on: Boolean) = updateOAuth { o ->
        val providers = if (on) (o.providers.filter { it.provider != provider } + OAuthProviderConfig(provider, "", "")).sortedBy { it.provider.ordinal }
        else o.providers.filter { it.provider != provider }
        o.copy(providers = providers, publicUrl = o.publicUrl.ifBlank { suggestedPublicUrl().orEmpty() })
    }

    fun setOAuthClientId(provider: OAuthProvider, id: String) = updateOAuth { o ->
        o.copy(providers = o.providers.map { if (it.provider == provider) it.copy(clientId = id.trim()) else it })
    }

    /** İstemci sırrı sır deposuna konur, konfigürasyonda referansı kalır. */
    fun setOAuthSecret(provider: OAuthProvider, secret: String) {
        val ref = secret.trim().takeIf { it.isNotEmpty() }?.let { storeSecret("oauth_secret", it) } ?: ""
        updateOAuth { o -> o.copy(providers = o.providers.map { if (it.provider == provider) it.copy(clientSecret = ref) else it }) }
    }

    fun updatePush(transform: (PushConfig) -> PushConfig) = update { it.copy(push = transform(it.push)) }

    /** Açarken anahtar yoksa VAPID çifti üretilir (özel anahtar sır deposuna). */
    fun setPushEnabled(on: Boolean) = updatePush { p ->
        if (!on) return@updatePush p.copy(enabled = false)
        val keyed = if (p.publicKey.isBlank() || p.privateKey.isBlank()) newVapid(p) else p
        keyed.copy(enabled = true, subject = keyed.subject.ifBlank { _draft.value.email.from.takeIf { it.contains('@') }?.let { "mailto:$it" }.orEmpty() })
    }

    /** Yeni VAPID anahtarları: mevcut tarayıcı abonelikleri geçersiz olur (yeniden abone olmaları gerekir). */
    fun regenerateVapid() = updatePush { newVapid(it) }

    private fun newVapid(p: PushConfig): PushConfig {
        val (public, private) = Vapid.generate()
        return p.copy(publicKey = public, privateKey = storeSecret("vapid_private", private))
    }

    fun addPushRule() = updatePush { p ->
        val c = _draft.value.collections.firstOrNull { it.ownerField != null }?.name ?: _draft.value.collections.firstOrNull()?.name ?: ""
        p.copy(notify = p.notify + PushRule(c, listOf("create"), tr("Yeni kayıt"), ""))
    }

    fun updatePushRule(index: Int, transform: (PushRule) -> PushRule) = updatePush { p ->
        p.copy(notify = p.notify.mapIndexed { i, r -> if (i == index) transform(r) else r })
    }

    fun removePushRule(index: Int) = updatePush { p -> p.copy(notify = p.notify.filterIndexed { i, _ -> i != index }) }

    fun applySmtpPreset(preset: SmtpPreset) = updateEmail { e ->
        e.copy(host = preset.host, port = preset.port, security = preset.security, username = e.username ?: e.from.takeIf { it.isNotBlank() })
    }

    /**
     * E-postadaki bağlantılar için önerilen genel adres: erişim ayarından (DuckDNS adı veya Cloudflare alan adı).
     * Yerel ağ veya hızlı tünelde (adres değişir) öneri yok.
     */
    fun suggestedPublicUrl(): String? {
        val access = _draft.value.access
        return when (access.mode) {
            AccessMode.DIRECT -> access.direct.ddns?.let { "https://${it.domain}.duckdns.org:${access.direct.port}" }
            AccessMode.CLOUDFLARE -> access.cloudflare.hostname?.takeIf { access.cloudflare.tunnel == CloudflareTunnel.NAMED }?.let { "https://$it" }
            else -> null
        }
    }

    /** Taslaktaki ayarlarla deneme e-postası; hata mesajı veya başarıda null. Engelleyicidir (IO'da çağırın). */
    fun sendTestEmail(to: String): String? = runCatching {
        val e = _draft.value.email
        val password = e.password?.let(::secretValue)
        SmtpClient(e, password).send(
            to.trim(), tr("{0}: deneme e-postası", _draft.value.name),
            tr("Bu bir deneme e-postasıdır. Bunu görüyorsanız DroidServer e-posta gönderebiliyor."),
        )
    }.exceptionOrNull()?.let { it.message ?: it.toString() }

    fun setMinPasswordLength(length: Int) = updateUsers { it.copy(minPasswordLength = length) }

    fun addUserField() = updateUsers { u ->
        val taken = u.extraFields.map { it.name } + SystemCollections.USER_FIELDS + ConfigValidator.SYSTEM_FIELDS
        u.copy(extraFields = u.extraFields + FieldConfig(uniqueName("alan", taken), FieldType.TEXT))
    }

    fun removeUserField(index: Int) = updateUsers { u ->
        val name = u.extraFields.getOrNull(index)?.name
        u.copy(extraFields = u.extraFields.filterIndexed { i, _ -> i != index }, publicFields = u.publicFields - setOfNotNull(name))
    }

    /** Adı değişen alan "herkese görünür" listesinde de yeni adıyla kalır. */
    fun updateUserField(index: Int, transform: (FieldConfig) -> FieldConfig) = updateUsers { u ->
        val old = u.extraFields.getOrNull(index) ?: return@updateUsers u
        val new = transform(old)
        u.copy(
            extraFields = u.extraFields.mapIndexed { i, f -> if (i == index) new else f },
            publicFields = u.publicFields.map { if (it == old.name) new.name else it },
        )
    }

    fun setUserFieldType(index: Int, type: FieldType) = updateUserField(index) { f ->
        FieldRules.fitTo(f, type).copy(type = type, default = f.default?.takeIf { FieldDefaults.problem(type, it) == null })
    }

    fun setUserFieldPublic(index: Int, public: Boolean) = updateUsers { u ->
        val name = u.extraFields.getOrNull(index)?.name ?: return@updateUsers u
        u.copy(publicFields = if (public) (u.publicFields + name).distinct() else u.publicFields - name)
    }

    // ---- 4. güvenlik

    /** API anahtarı veya JWT seçilince, yoksa yeni bir sır üretilir. */
    fun setAuthMode(mode: AuthMode) = update { cfg ->
        var auth = cfg.auth.copy(mode = mode)
        if (mode == AuthMode.API_KEY && auth.apiKeys.isEmpty()) auth = auth.copy(apiKeys = listOf(newApiKey()))
        if (mode == AuthMode.JWT && auth.jwtSecret.isNullOrBlank()) auth = auth.copy(jwtSecret = newJwtSecret())
        cfg.copy(auth = auth)
    }

    fun addApiKey() = update { it.copy(auth = it.auth.copy(apiKeys = it.auth.apiKeys + newApiKey())) }

    fun regenerateApiKey(index: Int) = update { cfg ->
        cfg.copy(auth = cfg.auth.copy(apiKeys = cfg.auth.apiKeys.mapIndexed { i, k -> if (i == index) newApiKey() else k }))
    }

    fun removeApiKey(index: Int) = update { cfg ->
        cfg.copy(auth = cfg.auth.copy(apiKeys = cfg.auth.apiKeys.filterIndexed { i, _ -> i != index }))
    }

    fun regenerateJwtSecret() = update { it.copy(auth = it.auth.copy(jwtSecret = newJwtSecret())) }

    fun setCors(mode: CorsMode, hosts: List<String> = emptyList()) = update { cfg ->
        val cors = when (mode) {
            CorsMode.OFF -> cfg.server.cors.copy(enabled = false)
            CorsMode.ANY -> CorsConfig(enabled = true, allowedHosts = listOf("*"))
            CorsMode.LIST -> CorsConfig(enabled = true, allowedHosts = hosts.map(String::trim).filter(String::isNotEmpty))
        }
        cfg.copy(server = cfg.server.copy(cors = cors))
    }

    /** Özet ekranındaki güvenlik uyarıları; herkesçe bilinen örnek anahtar da burada (kullanıcı yine de seçebilir). */
    fun securityWarnings(): List<String> {
        val cfg = _draft.value
        val placeholder = cfg.auth.mode == AuthMode.API_KEY &&
            cfg.auth.apiKeys.any { secretValue(it) == ConfigDefaults.PLACEHOLDER_API_KEY }
        return ConfigSummary.securityWarnings(cfg) +
            if (placeholder) listOf(PLACEHOLDER_WARNING) else emptyList()
    }

    /** Referanssa depodaki değer, düz metinse kendisi (kopyalama ve gösterim için). */
    fun secretValue(value: String): String? {
        if (!ConfigSecrets.isRef(value)) return value
        val name = value.removePrefix(ConfigSecrets.PREFIX)
        return secretCache[name] ?: resolveSecret(name)?.also { secretCache[name] = it }
    }

    // ---- bulut yedeği

    /** Açılınca rastgele şifreleme parolası üretilip şifreli depoya konur (kullanıcı not etmeli). */
    fun setRemoteBackup(on: Boolean) = updateBackup { b ->
        b.copy(remote = if (on) b.remote ?: RemoteBackupConfig(passphrase = storeSecret("backup_passphrase", newPassphrase())) else null)
    }

    fun updateRemoteBackup(transform: (RemoteBackupConfig) -> RemoteBackupConfig) = updateBackup { b -> b.copy(remote = b.remote?.let(transform)) }

    fun setRemotePassword(value: String) = updateRemoteBackup { it.copy(password = storeSecret("backup_password", value.trim())) }

    fun setRemotePassphrase(value: String) = updateRemoteBackup { it.copy(passphrase = storeSecret("backup_passphrase", value)) }

    /** Taslaktaki bulut ayarı, sırlar çözülmüş (bağlantı denemesi için). */
    fun resolvedRemoteBackup(): RemoteBackupConfig? = _draft.value.backup.remote?.let { r ->
        r.copy(password = secretValue(r.password) ?: r.password, passphrase = secretValue(r.passphrase) ?: r.passphrase)
    }

    /** 25 karakter (≈ 125 bit), karışmayan harf ve rakamlar: `ABCDE-FGHJK-…`. */
    private fun newPassphrase(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val r = java.security.SecureRandom()
        return (1..25).map { alphabet[r.nextInt(alphabet.length)] }.joinToString("").chunked(5).joinToString("-")
    }

    /** Panel girişinde doğrulayıcı kodu: açılınca yeni anahtar üretilip şifreli depoya konur. */
    fun setPanelTotp(on: Boolean) = updateAdminPanel {
        it.copy(totpSecret = if (on) it.totpSecret ?: storeSecret("panel_totp", dev.droidserver.core.auth.Totp.newSecret()) else null)
    }

    private fun newApiKey() = storeSecret("api_key", ConfigDefaults.generateApiKey())

    private fun newJwtSecret() = storeSecret("jwt", ConfigDefaults.generateJwtSecret())

    private fun storeSecret(kind: String, value: String): String {
        val name = secrets.store(kind, value)
        secretCache[name] = value
        return ConfigSecrets.ref(name)
    }

    // ---- 5. erişim

    fun setAccess(mode: AccessMode) = update { it.withAccess(mode) }

    /** Cloudflare adlandırılmış tünelinin token'ı depoya konur; taslakta yalnızca referansı durur. */
    fun setAccessToken(token: String) {
        if (token.isBlank()) return
        val ref = storeSecret("tunnel_token", token.trim())
        update { it.withAccessToken(AccessMode.CLOUDFLARE, ref) }
    }

    fun clearAccessToken() = update { cfg ->
        val cf = AccessMode.CLOUDFLARE
        cfg.copy(components = cfg.components.map { c -> if (c.id == cf.componentId) c.copy(env = c.env - cf.tokenEnv!!) else c })
    }

    fun setCloudflareTunnel(tunnel: CloudflareTunnel) = update { cfg ->
        cfg.copy(access = cfg.access.copy(cloudflare = cfg.access.cloudflare.copy(tunnel = tunnel)))
    }

    fun setCloudflareHostname(host: String) = update { cfg ->
        val h = host.trim().removePrefix("https://").removePrefix("http://").trimEnd('/').lowercase().ifEmpty { null }
        cfg.copy(access = cfg.access.copy(cloudflare = cfg.access.cloudflare.copy(hostname = h)))
    }

    private fun updateDirect(f: (DirectAccessConfig) -> DirectAccessConfig) = update { cfg ->
        cfg.copy(access = cfg.access.copy(direct = f(cfg.access.direct)))
    }

    fun setDirectNetwork(network: DirectNetwork) = updateDirect { it.copy(network = network) }

    fun setDirectPort(port: Int) = updateDirect { it.copy(port = port) }

    fun setPortMapping(enabled: Boolean) = updateDirect { it.copy(portMapping = enabled) }

    /** DuckDNS: ad küçük harfe çevrilir, `.duckdns.org` eki atılır; token depoya konur. */
    fun setDdns(domain: String, token: String) {
        val name = domain.trim().lowercase().removePrefix("https://").removePrefix("http://").substringBefore('/')
            .removeSuffix(".duckdns.org")
        val ref = storeSecret("ddns_token", token.trim())
        updateDirect { it.copy(ddns = DdnsConfig(name, ref, certificate = it.ddns?.certificate ?: false)) }
    }

    fun clearDdns() = updateDirect { it.copy(ddns = null) }

    /** DuckDNS adı için Let's Encrypt sertifikası (açmak Let's Encrypt koşullarını kabul etmektir). */
    fun setDdnsCertificate(on: Boolean) = updateDirect { d -> d.copy(ddns = d.ddns?.copy(certificate = on)) }

    fun setAllowNoAuth(allow: Boolean) = update { it.copy(access = it.access.copy(allowNoAuth = allow)) }

    // ---- hata hesabı

    /**
     * Validator sırların gerçek değerleri üzerinde koşar (örn. anahtar uzunluğu). Sihirbaza özgü kurallar:
     * tünel seçildiyse token gerekir, referans verilen sır depoda bulunmalı, veritabanı değişiyorsa taşıma ve
     * kayıtlı alanın tipi değişiyorsa değerler için kullanıcı seçim yapmalı.
     */
    private fun computeErrors(cfg: ServerConfig): List<ValidationError> {
        val missing = mutableListOf<ValidationError>()
        fun value(path: String, v: String): String =
            secretValue(v) ?: v.also { missing += ValidationError(path, tr("Sır bulunamadı; yeniden üretin")) }

        val auth = cfg.auth
        val dbPassword = cfg.database.password?.takeIf { cfg.database.engine == DbEngine.POSTGRES }
        val resolved = cfg.copy(
            database = cfg.database.copy(password = dbPassword?.let { value("database.password", it) }),
            webhooks = cfg.webhooks.mapIndexed { i, h ->
                h.copy(secret = h.secret?.let { s -> secretValue(s) ?: s.also { missing += ValidationError("webhooks[$i].secret", tr("İmza sırrı bulunamadı; yenisini üretin")) } })
            },
            email = cfg.email.copy(
                password = cfg.email.password?.takeIf { cfg.email.enabled }?.let { p ->
                    secretValue(p) ?: p.also { missing += ValidationError("email.password", tr("SMTP parolası bulunamadı; yeniden girin")) }
                },
            ),
            auth = when (auth.mode) {
                AuthMode.NONE -> auth
                AuthMode.API_KEY -> auth.copy(apiKeys = auth.apiKeys.mapIndexed { i, k -> value("auth.apiKeys[$i]", k) })
                AuthMode.JWT -> auth.copy(jwtSecret = auth.jwtSecret?.let { value("auth.jwtSecret", it) })
            },
        )
        return buildList {
            addAll(missing)
            addAll(ConfigValidator.validateDetailed(resolved).filter { e -> missing.none { it.path == e.path } })
            if (databaseChanged(cfg) && _moveData.value == null) {
                add(ValidationError(MOVE_DATA_PATH, tr("Veritabanı değişiyor: mevcut kayıtlar taşınsın mı? Seçin.")))
            }
            cfg.collections.forEachIndexed { ci, c ->
                c.fields.forEachIndexed { fi, f ->
                    val old = activeFieldType(c.name, f.name)
                    if (old != null && old != f.type && f.typeChange?.from != old) {
                        add(
                            ValidationError(
                                "collections[$ci].fields[$fi].typeChange",
                                tr("'{0}.{1}' tipi değişti ({2} → {3}): " +
                                    "kayıtlı değerler için seçim yapın", c.name, f.name, old.name.lowercase(), f.type.name.lowercase()),
                            ),
                        )
                    }
                }
            }
            val access = cfg.access
            val customArgs = cfg.components.any { it.id == AccessMode.CLOUDFLARE.componentId && it.args.isNotEmpty() }
            if (access.mode == AccessMode.CLOUDFLARE && access.cloudflare.tunnel == CloudflareTunnel.NAMED && !customArgs) {
                val token = cfg.accessToken(AccessMode.CLOUDFLARE)
                when {
                    token == null -> add(ValidationError(ACCESS_TOKEN_PATH, tr("Kendi alan adınızla tünel için token gerekli")))
                    secretValue(token) == null -> add(ValidationError(ACCESS_TOKEN_PATH, tr("Token bulunamadı; yeniden girin")))
                }
            }
            val ddns = access.direct.ddns
            if (access.mode == AccessMode.DIRECT && ddns != null && secretValue(ddns.token) == null) {
                add(ValidationError("access.direct.ddns.token", tr("DuckDNS token'ı bulunamadı; yeniden girin")))
            }
        }
    }

    companion object {
        private val CONDITIONAL = setOf(Rule.PUBLIC, Rule.AUTH, Rule.OWNER)

        fun uniqueName(base: String, taken: Collection<String>): String =
            if (base !in taken) base else generateSequence(2) { it + 1 }.map { "${base}_$it" }.first { it !in taken }

        /** Etkinlik kapsamlı (sihirbaz ve JSON düzenleyici aynı örneği paylaşır). */
        fun factory(g: AppGraph) = viewModelFactory {
            initializer {
                val active = runCatching { g.configRepo.loadActiveOrNull() }.getOrNull()
                val fresh = { ServerConfig.fromJson(g.configRepo.defaultConfigText()) }
                WizardViewModel(
                    initialDraft = g.draftStore.load() ?: active ?: fresh(),
                    active = active,
                    drafts = g.draftStore,
                    secrets = g.secrets,
                    resolveSecret = g.secrets::get,
                    freshDefault = fresh,
                    keyLastUsed = g.engine.keyUsage::lastUsed,
                    expertMode = g.settings.expertMode,
                    saveExpertMode = { g.settings.expertMode = it },
                )
            }
        }
    }
}

/** Yaygın e-posta sağlayıcılarının SMTP ayarları; parolası çoğunda hesap parolası değil "uygulama şifresi"dir. */
enum class SmtpPreset(val title: String, val host: String, val port: Int, val security: SmtpSecurity, val note: String) {
    GMAIL(tr("Gmail"), "smtp.gmail.com", 587, SmtpSecurity.STARTTLS, tr("Google hesabında iki adımlı doğrulama açıkken \"Uygulama şifreleri\"nden 16 haneli şifre alın.")),
    OUTLOOK(tr("Outlook"), "smtp-mail.outlook.com", 587, SmtpSecurity.STARTTLS, tr("Microsoft hesabında iki adımlı doğrulama açıkken uygulama parolası oluşturun.")),
    YANDEX(tr("Yandex"), "smtp.yandex.com.tr", 465, SmtpSecurity.SSL, tr("Yandex ID → Güvenlik → Uygulama şifreleri → E-posta.")),
    ICLOUD("iCloud", "smtp.mail.me.com", 587, SmtpSecurity.STARTTLS, tr("Apple ID → Oturum açma ve güvenlik → Uygulamaya özel parolalar.")),
}
