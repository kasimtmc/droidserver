package dev.droidserver.app

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.core.content.edit
import dev.droidserver.app.data.AndroidDeviceStats
import dev.droidserver.app.data.AndroidDiscovery
import dev.droidserver.app.data.AndroidImageResizer
import dev.droidserver.app.data.AppAdminHost
import dev.droidserver.app.data.AppUpdater
import dev.droidserver.app.data.AppsController
import dev.droidserver.app.data.BackupExporter
import dev.droidserver.app.data.AndroidNetwork
import dev.droidserver.app.data.AndroidPhone
import dev.droidserver.app.data.AndroidSqlDriver
import dev.droidserver.app.data.ConfigRepository
import dev.droidserver.app.data.CrashMarker
import dev.droidserver.app.data.FileDraftStore
import dev.droidserver.app.data.LogBuffer
import dev.droidserver.app.data.PendingTemplate
import dev.droidserver.app.data.Profile
import dev.droidserver.app.data.ProfileStore
import dev.droidserver.app.data.SecretStore
import dev.droidserver.app.service.ServerService
import dev.droidserver.core.apps.AppManager
import dev.droidserver.core.components.ComponentManager
import dev.droidserver.core.i18n.I18n
import dev.droidserver.core.i18n.Lang
import dev.droidserver.core.i18n.tr
import dev.droidserver.core.audit.AuditLog
import dev.droidserver.core.backup.BackupKind
import dev.droidserver.core.replication.ChangeLog
import dev.droidserver.core.replication.FailoverHost
import dev.droidserver.core.net.CertificateManager
import dev.droidserver.core.net.DirectAccess
import dev.droidserver.core.net.TlsIdentity
import dev.droidserver.core.process.ProcessSupervisor
import dev.droidserver.core.server.DeployOrchestrator
import dev.droidserver.core.server.ServerEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import dev.droidserver.app.service.QuickControls
import dev.droidserver.core.server.EngineState
import dev.droidserver.app.data.NetUtil
import dev.droidserver.app.data.AutomationBridge
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale

open class DroidServerApp : Application() {
    @Volatile
    lateinit var graph: AppGraph
        private set

    val profiles by lazy { ProfileStore(this) }

    override fun onCreate() {
        super.onCreate()
        // Yeniden başlatma süreci (RestartActivity) yalnızca ana süreci yeniden açar; sunucu kabı kurulmaz.
        if (currentProcessName().endsWith(":restart")) return
        val settings = Settings(this)
        I18n.lang = settings.lang
        dev.droidserver.app.ui.theme.ThemeSettings.mode = settings.themeMode
        dev.droidserver.app.ui.theme.ThemeSettings.dynamic = settings.dynamicColor
        CrashMarker.install(this)
        graph = AppGraph(this, profiles.active(), profiles)
        watchQuickControls()
    }

    /** Sunucu durumu değişince hızlı ayar kutucuğu ve widget'lar güncellenir, otomasyon uygulamalarına olay gider. */
    private fun watchQuickControls() {
        val g = graph
        g.appScope.launch {
            var previous: EngineState? = null
            g.engine.state.collect { state ->
                QuickControls.refresh(this@DroidServerApp)
                if (previous != null && previous!!::class != state::class) when (state) {
                    is EngineState.Running -> g.automation.started(
                        g.profile.name, state.port, NetUtil.localIpv4().firstOrNull()?.let { "http://$it:${state.port}" },
                    )
                    EngineState.Stopped -> g.automation.stopped(g.profile.name)
                    is EngineState.Failed -> g.automation.failed(g.profile.name, state.reason)
                }
                previous = state
            }
        }
    }

    /**
     * Etkin profili değiştirir: eski kabın işleri kapatılır (sunucu önceden durdurulmuş olmalı), yeni profille yeni kap
     * kurulur. Ekranlar etkinlik yeniden başlatılınca yeni kabı kullanır.
     */
    @Synchronized
    fun switchProfile(id: String) {
        profiles.setActive(id)
        val old = graph
        graph = AppGraph(this, profiles.active(), profiles)
        old.close()
        watchQuickControls()
    }
}

private fun currentProcessName(): String =
    if (Build.VERSION.SDK_INT >= 28) Application.getProcessName()
    else runCatching { File("/proc/self/cmdline").readText().trimEnd('\u0000') }.getOrDefault("")

/** Basit bağımlılık kabı (etkin profil için). Proje büyürse Hilt/Koin ile değiştirilebilir. */
class AppGraph(context: Context, val profile: Profile, profiles: ProfileStore) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val logs = LogBuffer()
    val secrets = SecretStore(context)

    /** Tasker/MacroDroid köprüsü: komutlar ve olay yayınları. */
    val automation = AutomationBridge(context, secrets)

    /** Profilin veri klasörü ve SQLite klasörü (varsayılan profilde uygulamanın kendi klasörleri). */
    val dataRoot: File = profiles.root(profile.id)
    private val databaseDir: File? = profiles.databaseDir(profile.id)

    val configRepo = ConfigRepository(context, secrets, dataRoot)
    val draftStore = FileDraftStore(configRepo, appScope, logs::add)
    val settings = Settings(context)

    val engine = ServerEngine(
        openDatabase = { AndroidSqlDriver.open(context, it, databaseDir) },
        dataDir = dataRoot,
        resizer = AndroidImageResizer(),
        device = AndroidDeviceStats(context),
        log = logs::add,
        phone = AndroidPhone(context),
    )

    /** Seçilen uygulama şablonunun deploy sonrası kurulumu (örnek veri, site). */
    val pendingTemplate = PendingTemplate(dataRoot)

    val backups by lazy { BackupExporter(context, configRepo, engine, settings, logs, secrets) }

    val updater = AppUpdater(context, settings, engine, logs::add)

    val supervisor = ProcessSupervisor(appScope, logs::add)

    /** Termux derlemelerinin geçici klasörü: bileşenlerin binary'lerindeki Termux yolu buraya çevrilir. */
    private val componentTmp = File(context.applicationInfo.dataDir, "t").apply { mkdirs() }

    val components = ComponentManager(
        rootDir = File(context.filesDir, "components"),
        abi = Build.SUPPORTED_ABIS.first(),
        tmpDir = componentTmp.absolutePath,
        log = logs::add,
    )

    val network = AndroidNetwork(context)

    /** Yerel ağda mDNS duyurusu (`access.discovery`). */
    val discovery = AndroidDiscovery(context, logs::add)

    /**
     * İnternetten doğrudan erişimin HTTPS sertifikası: ilk kullanımda üretilir, uygulama klasöründe saklanır
     * (parmak izi değişmesin diye); dosyanın parolası şifreli sır deposunda.
     */
    private val tlsPassword by lazy {
        (secrets.get(TLS_SECRET) ?: Base64.getEncoder().encodeToString(ByteArray(24).also(SecureRandom()::nextBytes))
            .also { secrets.put(TLS_SECRET, it) }).toCharArray()
    }

    private val tlsIdentity by lazy { TlsIdentity.loadOrCreate(File(context.filesDir, "tls/server.p12"), tlsPassword, logs::add) }

    /** DuckDNS adı için Let's Encrypt sertifikası (aynı anahtar ve parola; `tls/` altında). */
    private val certificates by lazy { CertificateManager(File(context.filesDir, "tls"), tlsPassword, log = logs::add) }

    val direct = DirectAccess(
        probe = network, identity = { tlsIdentity }, scope = appScope, log = logs::add,
        certificates = certificates,
    )

    /** Kendi uygulamaların sürümleri (`apps/<ad>`, profil klasöründe). */
    val apps = AppsController(
        context, configRepo, secrets,
        AppManager(File(dataRoot, "apps"), runtimes = components::runtime, log = logs::add),
        supervisor, appScope, logs::add,
    )

    /** Deploy sırası: bileşenler -> hazır olmayı bekle -> sunucu (bkz. DeployOrchestrator). */
    val orchestrator = DeployOrchestrator(
        engine = engine,
        supervisor = supervisor,
        prepareComponent = components::prepare,
        resolveSecret = secrets::get,
        log = logs::add,
        openDatabase = { AndroidSqlDriver.open(context, it, databaseDir) },
        components = components,
        access = direct,
        apps = apps.manager,
    )

    private companion object {
        const val TLS_SECRET = "tls_identity_password"
    }

    /** Telefonda çalışan modeller (llama.cpp); reddedilen yükleme sağlık uyarısı olur. */
    val localModels = dev.droidserver.app.ai.LocalModels(context) { msg ->
        engine.health.event(dev.droidserver.core.alerts.AlertKind.LOCAL_AI, msg, "local-ai")
    }

    /** Deploy ve veri taşıma aynı anda çalışmasın (servis ve sihirbaz paylaşır). */
    val deployLock = Mutex()

    init {
        // Web paneli konfigürasyonu, logları ve yedekleri uygulamadaki gibi yönetir.
        engine.adminHost = AppAdminHost(context, configRepo, secrets, engine, logs, backups)
        engine.localAi = localModels
        // MQTT TLS'in yerel kökü: HTTPS sertifikasıyla aynı klasör ve parola; kök ilk TLS bağlantısında üretilir.
        engine.mqttCa = dev.droidserver.core.net.LocalCa(File(context.filesDir, "tls"), tlsPassword, log = logs::add)
        engine.appsHost = apps
        // Kopyada ana sunucunun konfigürasyonu değişince: paylaşılan kısmı alıp kaydet (sırlar şifreli depoya) ve yeniden deploy.
        engine.onPrimaryConfig = { primary, _ ->
            runCatching {
                configRepo.saveText(ChangeLog.merge(configRepo.load(), primary).toJson())
                logs.add(tr("Kopya: ana sunucunun yeni konfigürasyonu kaydedildi, yeniden deploy ediliyor"))
                ServerService.deploy(context.applicationContext)
            }.onFailure { logs.add(tr("Kopya: yeni konfigürasyon uygulanamadı: {0}", it.message)) }
        }
        // Devralma, geri alma ve devredilme: konfigürasyonu kaydet (anahtarlar şifreli depoya), denetim kaydı, yeniden deploy.
        engine.networkUp = network::connected
        engine.failoverHost = FailoverHost { event, detail, backupFirst, edit ->
            runCatching {
                if (backupFirst) runCatching { backups.backup(BackupKind.BEFORE_DEMOTE) }
                    .onSuccess { logs.add(tr("Kopyaya dönmeden önce veriler yedeklendi: {0}", it.name)) }
                    .onFailure { logs.add(tr("Kopyaya dönmeden önce yedek alınamadı: {0}", it.message)) }
                configRepo.saveText(edit(configRepo.load()).toJson())
                engine.audit.record(AuditLog.SYSTEM, event, detail = detail)
                logs.add(detail)
                ServerService.deploy(context.applicationContext)
            }.onFailure { logs.add(tr("Sunucu rolü değiştirilemedi: {0}", it.message)) }
        }
    }

    /** SQLite veritabanı dosyası (depolama kartı için). */
    fun databaseFile(name: String): File = databaseDir?.let { File(it, name) } ?: appContext.getDatabasePath(name)

    private val appContext = context.applicationContext

    /** Profil değişince eski kabın arka plan işleri kapatılır. */
    fun close() {
        engine.saveKeyUsage()
        runCatching { orchestrator.stopAll() }
        discovery.stop()
        appScope.cancel()
    }
}

class Settings(context: Context) {
    companion object {
        /** `.github/workflows/components.yml`'nin yayınladığı manifest. */
        const val DEFAULT_MANIFEST_URL = "https://github.com/kasimtmc/droidserver/releases/download/components/components.json"

        /** Uygulama güncellemelerinin alındığı depo (`.github/workflows/release.yml`'nin yayınladığı sürümler). */
        const val UPDATE_REPO = "kasimtmc/droidserver"
    }

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var autoStart: Boolean
        get() = prefs.getBoolean("autoStart", false)
        set(v) = prefs.edit { putBoolean("autoStart", v) }

    /** Yedeklerin kopyalanacağı klasör (SAF ağaç adresi); null: yalnızca uygulama klasörü. */
    var backupTreeUri: String?
        get() = prefs.getString("backupTreeUri", null)
        set(v) = prefs.edit { if (v == null) remove("backupTreeUri") else putString("backupTreeUri", v) }

    /** "Şimdi değil" denen konfigürasyon yükseltmesinin hedef sürümü; bu sürüm için yeniden sorulmaz. */
    var dismissedConfigUpgrade: Int
        get() = prefs.getInt("dismissedConfigUpgrade", 0)
        set(v) = prefs.edit { putInt("dismissedConfigUpgrade", v) }

    /** GitHub'da yeni uygulama sürümünü günde bir denetle (kapalıysa yalnızca elle). */
    var autoUpdateCheck: Boolean
        get() = prefs.getBoolean("autoUpdateCheck", true)
        set(v) = prefs.edit { putBoolean("autoUpdateCheck", v) }

    /** İlk açılış rehberi görüldü (atlandı veya bitirildi). */
    var onboardingDone: Boolean
        get() = prefs.getBoolean("onboardingDone", false)
        set(v) = prefs.edit { putBoolean("onboardingDone", v) }

    /** Sihirbazda uzman modu: otomatik eklenen alanlar ve uçlar da düzenlenir. */
    var expertMode: Boolean
        get() = prefs.getBoolean("expertMode", false)
        set(v) = prefs.edit { putBoolean("expertMode", v) }

    /** Ön sürümleri (beta) de öner. */
    var includePrerelease: Boolean
        get() = prefs.getBoolean("includePrerelease", false)
        set(v) = prefs.edit { putBoolean("includePrerelease", v) }

    var lastUpdateCheck: Long
        get() = prefs.getLong("lastUpdateCheck", 0)
        set(v) = prefs.edit { putLong("lastUpdateCheck", v) }

    /** Bildirimi gösterilmiş son sürüm (aynı sürüm için tekrar bildirilmez). */
    var notifiedVersion: String?
        get() = prefs.getString("notifiedVersion", null)
        set(v) = prefs.edit { putString("notifiedVersion", v) }

    /** Uygulama içinden kurulan güncellemeden sonra sunucu yeniden başlatılsın (güncellemede çalışıyordu). */
    var restartAfterUpdate: Boolean
        get() = prefs.getBoolean("restartAfterUpdate", false)
        set(v) = prefs.edit(commit = true) { putBoolean("restartAfterUpdate", v) }

    /** Arayüz dili kodu (`tr`, `en`); null: telefonun dili (desteklenmiyorsa İngilizce). */
    var language: String?
        get() = prefs.getString("language", null)
        set(v) = prefs.edit(commit = true) { if (v == null) remove("language") else putString("language", v) }

    /** Etkin dil; değişikliği uygulama yeniden açılınca geçerli olur (bkz. RestartActivity). */
    val lang: Lang get() = Lang.of(language) ?: Lang.of(Locale.getDefault().language) ?: Lang.EN

    /** Dil değişikliği için uygulama yeniden açılırken sunucu çalışıyordu; açılışta yeniden başlatılır. */
    var startServerOnLaunch: Boolean
        get() = prefs.getBoolean("startServerOnLaunch", false)
        set(v) = prefs.edit(commit = true) { putBoolean("startServerOnLaunch", v) }

    /** Uygulama kilidi: açılışta ve arka planda [appLockAfterSec]'ten uzun kalınca telefonun doğrulaması. */
    var appLock: Boolean
        get() = prefs.getBoolean("appLock", false)
        set(v) = prefs.edit(commit = true) { putBoolean("appLock", v) }

    /** Arka planda bu kadar saniye kalınca kilitlenir (0: hemen). */
    var appLockAfterSec: Int
        get() = prefs.getInt("appLockAfterSec", 0)
        set(v) = prefs.edit { putInt("appLockAfterSec", v) }

    /** Tema: `system` (telefonunki), `light`, `dark`. */
    var themeMode: String
        get() = prefs.getString("themeMode", "system") ?: "system"
        set(v) = prefs.edit { putString("themeMode", v) }

    /** Android 12+: duvar kâğıdından renkler (Material You); kapalıyken DroidServer'ın kendi renkleri. */
    var dynamicColor: Boolean
        get() = prefs.getBoolean("dynamicColor", false)
        set(v) = prefs.edit { putBoolean("dynamicColor", v) }

    /** Kapatılan ipuçları (anahtarları). */
    var dismissedTips: Set<String>
        get() = prefs.getStringSet("dismissedTips", emptySet()).orEmpty()
        set(v) = prefs.edit { putStringSet("dismissedTips", v) }

    /** Bileşen deposu manifest adresi; varsayılan bu projenin GitHub Releases'ı, kendi sunucunuzla değiştirilebilir. */
    var manifestUrl: String
        get() = prefs.getString("manifestUrl", DEFAULT_MANIFEST_URL) ?: DEFAULT_MANIFEST_URL
        set(v) = prefs.edit { putString("manifestUrl", v) }
}

val Context.graph: AppGraph get() = (applicationContext as DroidServerApp).graph
