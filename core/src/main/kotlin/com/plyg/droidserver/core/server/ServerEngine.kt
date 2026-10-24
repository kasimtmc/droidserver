package com.plyg.droidserver.core.server

import com.plyg.droidserver.core.admin.AdminHost
import com.plyg.droidserver.core.admin.AdminSessions
import com.plyg.droidserver.core.api.WriteGate
import com.plyg.droidserver.core.ai.AiHost
import com.plyg.droidserver.core.auth.SmsGuard
import com.plyg.droidserver.core.automation.AutomationHost
import com.plyg.droidserver.core.i18n.tr
import com.plyg.droidserver.core.phone.PhoneHost
import com.plyg.droidserver.core.phone.PhoneService
import com.plyg.droidserver.core.security.FieldEncryption
import kotlinx.serialization.json.JsonPrimitive
import com.plyg.droidserver.core.packages.InstalledPackage
import com.plyg.droidserver.core.packages.PackageFile
import com.plyg.droidserver.core.templates.SampleSeed
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import com.plyg.droidserver.core.config.FieldType
import com.plyg.droidserver.core.config.GeoPoint
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import com.plyg.droidserver.core.api.ClientGuard
import com.plyg.droidserver.core.alerts.HealthMonitor
import com.plyg.droidserver.core.audit.AuditLog
import com.plyg.droidserver.core.jobs.JobQueue
import com.plyg.droidserver.core.alerts.AlertKind
import com.plyg.droidserver.core.api.RecordService
import com.plyg.droidserver.core.config.ReplicaSource
import com.plyg.droidserver.core.replication.ChangeLog
import com.plyg.droidserver.core.replication.Failover
import com.plyg.droidserver.core.replication.FailoverHost
import com.plyg.droidserver.core.replication.FailoverPort
import com.plyg.droidserver.core.replication.FailoverTiming
import com.plyg.droidserver.core.replication.ReplicaRegistry
import com.plyg.droidserver.core.replication.ReplicaStatus
import com.plyg.droidserver.core.replication.Replicator
import com.plyg.droidserver.core.templates.AppTemplate
import com.plyg.droidserver.core.auth.ApiKeyUsage
import com.plyg.droidserver.core.auth.DailyCounter
import com.plyg.droidserver.core.auth.QuotaService
import com.plyg.droidserver.core.data.DataTransfer
import com.plyg.droidserver.core.backup.BackupArchive
import com.plyg.droidserver.core.backup.BackupInfo
import com.plyg.droidserver.core.backup.BackupKind
import com.plyg.droidserver.core.backup.BackupStore
import com.plyg.droidserver.core.api.droidServerModule
import com.plyg.droidserver.core.auth.UserService
import com.plyg.droidserver.core.config.ConfigValidator
import com.plyg.droidserver.core.config.DatabaseConfig
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.config.SystemCollections
import com.plyg.droidserver.core.db.MigrationPlan
import com.plyg.droidserver.core.db.SchemaBlockedException
import com.plyg.droidserver.core.db.SchemaMigrator
import com.plyg.droidserver.core.db.SqlDriver
import com.plyg.droidserver.core.files.FileStore
import com.plyg.droidserver.core.files.ImageResizer
import com.plyg.droidserver.core.metrics.DeviceStats
import com.plyg.droidserver.core.metrics.Metrics
import com.plyg.droidserver.core.webhook.WebhookDispatcher
import com.plyg.droidserver.core.script.CronRunner
import com.plyg.droidserver.core.script.ScriptLoadException
import com.plyg.droidserver.core.script.ScriptRuntime
import com.plyg.droidserver.core.sensors.Sensors
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import java.io.File
import java.time.Instant

sealed interface EngineState {
    data object Stopped : EngineState
    data class Running(
        val port: Int,
        val since: Instant,
        val migration: String,
        /** Veri taşıma sırasında sunucu salt okunur; yazmalar 503 alır. */
        val writesPaused: Boolean = false,
    ) : EngineState
    data class Failed(val reason: String) : EngineState
}

class DeployException(val errors: List<String>) : RuntimeException(errors.joinToString("; "))

/** DeployOrchestrator'ın HTTP motorundan beklediği işlemler (testlerde sahtesi kullanılır). */
interface EngineControl {
    fun deploy(config: ServerConfig)
    fun stop()

    /** Yazmaları kapatır, süren yazmaların bitmesini bekler; okumalar sürer. Yeni deploy yazmaları yeniden açar. */
    fun pauseWrites() {}

    fun resumeWrites() {}
}

/**
 * HTTP sunucusunun yaşam döngüsü. "Deploy" = doğrula -> veritabanını aç -> şemayı uygula ->
 * eski sunucuyu kapat -> yenisini başlat. Doğrulama veya migration başarısız olursa
 * (engelleyici şema sorunu dahil) çalışan sunucuya dokunulmaz.
 */
class ServerEngine(
    private val openDatabase: (DatabaseConfig) -> SqlDriver,
    /** Yüklenen dosyalar (`storage/`) ve statik site (`www/`) bu klasörde; null ise dosya yüklenemez. */
    private val dataDir: File? = null,
    private val resizer: ImageResizer? = null,
    /** Cihaz durumu (pil, depolama, bellek); her dakika metriklere eklenir. */
    private val device: DeviceStats? = null,
    /** SMS ve konum (app); yoksa telefon özellikleri 503 döner. */
    private val phone: PhoneHost? = null,
    private val log: (String) -> Unit = ::println,
) : EngineControl {
    /** İstek ve cihaz metrikleri; deploy'lar arasında sürer. */
    val metrics = Metrics()

    /** Webhook teslimleri; deploy'lar arasında sürer (bekleyen yeniden denemeler ve geçmiş). */
    val webhooks = WebhookDispatcher(log = log)

    /** Otomasyon kuralları: son çalışmalar ve "şimdi dene" (uygulama ekranı). */
    val automations = AutomationHost()

    /** YZ: günlük kullanım ve anlamsal dizin durumu (uygulamadaki YZ ekranı). */
    val ai = AiHost()

    /** Telefonda çalışan modeller (uygulama atar; bir sonraki deploy'da geçerli). */
    @Volatile
    var localAi: com.plyg.droidserver.core.ai.LocalAi? = null

    /** MQTT TLS'in yerel kökü (uygulama atar; bir sonraki deploy'da geçerli). */
    @Volatile
    var mqttCa: com.plyg.droidserver.core.net.LocalCa? = null

    /** Ödemeler: iade ve son ödemeler (uygulamadaki Ödemeler ekranı); sunucu çalışırken dolu. */
    val payments = com.plyg.droidserver.core.payments.PaymentHost()

    /** MQTT: bağlı cihazlar ve son mesajlar (uygulamadaki MQTT ekranı). */
    val mqtt = com.plyg.droidserver.core.mqtt.MqttHost()

    /**
     * Adlandırılmış API anahtarlarının son kullanım zamanları; deploy'lar arasında sürer, sunucu durunca
     * `key-usage.json`'a yazılır ve açılışta okunur.
     */
    val keyUsage = ApiKeyUsage().also { u -> runCatching { usageFile?.takeIf { it.isFile }?.let { u.restore(readUsage(it.readText())) } } }

    /** İstemci sınırları ve geçici engeller; deploy'lar arasında sürer (uygulamadaki Güvenlik duvarı ekranı). */
    val guard = ClientGuard()

    /** Yönetici işlemlerinin kaydı (profil klasöründe `audit.jsonl`; deploy'lar ve geri yüklemeler arasında sürer). */
    val audit = AuditLog(dataDir?.let { File(it, "audit.jsonl") })

    /** Sağlık uyarıları (app her yarım dakikada denetler; panel ve API listeler). */
    val health = HealthMonitor(log = log)

    /** Kopya ise eşitleme durumu (deploy'lar arasında sürer); kopya değilse null. */
    private val _replica = MutableStateFlow<ReplicaStatus?>(null)
    val replica: StateFlow<ReplicaStatus?> = _replica.asStateFlow()

    /**
     * Kopyada ana sunucunun konfigürasyonu değişince (app bağlar): yeni konfigürasyonu kaydedip yeniden deploy etmeli
     * (bkz. ChangeLog.merge). Bağlanmamışsa kopya eski şemayla devam eder.
     */
    @Volatile
    var onPrimaryConfig: ((ServerConfig, String) -> Unit)? = null

    private var replicator: Replicator? = null

    /** Kalıcı sunucu kimliği (devralan sunucunun eski ana sunucuyu tanıması için). */
    val instanceId: String = Failover.instanceId(dataDir)

    /** Bu ana sunucuya bağlanan kopyaların adresleri (açılışta "beni devralan var mı" diye sorulur). */
    private val replicaRegistry = ReplicaRegistry(dataDir?.let { File(it, "replicas.json") })

    /**
     * Devralma, geri alma ve devredilme: konfigürasyonu kalıcı değiştirip yeniden deploy eder (app bağlar; yeniden deploy
     * çağıranın iş parçacığında yapılmamalı). Bağlanmamışsa yalnızca elle devralınır.
     */
    @Volatile
    var failoverHost: FailoverHost? = null

    /** Telefon bir ağa bağlı mı (app); değilse otomatik devralma yapılmaz (kopan bu telefondur). */
    @Volatile
    var networkUp: () -> Boolean = { true }

    internal var failoverTiming = FailoverTiming()
    private var fencer: Thread? = null

    private fun startReplicator(db: SqlDriver, config: ServerConfig) {
        replicator = Replicator(
            db, config, fileStore, dataDir?.let { File(it, "replica-state.json") }, log, onPrimaryConfig, _replica,
            failover(), failoverTiming, networkUp,
        ).apply { start() }
    }

    /** Uygulamanın devralma işleyicisi; önce kritik uyarı gönderilir. */
    private fun failover(): FailoverHost? = failoverHost?.let { h ->
        FailoverHost { event, detail, backupFirst, edit ->
            runCatching { health.event(AlertKind.FAILOVER, detail) }
            h.change(event, detail, backupFirst, edit)
        }
    }

    /** Bu ana sunucu devredildi: yeni ana sunucunun kopyası olur (önce yedek alınır). */
    private fun demote(url: String, key: String) {
        val host = failover() ?: return log("Devredildi; bu sunucu salt okunur kaldı (konfigürasyon değiştirilemedi)")
        Thread({
            runCatching { host.change("replica.demoted", tr("Bu sunucu devredildi; {0} sunucusunun kopyası oluyor", url), true) { Failover.demote(it, url, key) } }
                .onFailure { log("Kopyaya dönülemedi: ${it.message}") }
        }, "ds-demote").apply { isDaemon = true; start() }
    }

    /**
     * Açılışta: bilinen kopyalardan biri bu sunucuyu devraldıysa (ana ve eski ana kimliği bu sunucu) adresi. Kopan
     * sunucu döndüğünde iki sunucuya ayrı ayrı yazılmasın diye yazmalar açılmadan sorulur.
     */
    private fun replacedBy(config: ServerConfig): String? {
        if (!config.replication.source || config.replication.replicaOf != null) return null
        return replicaRegistry.recent().take(3).firstOrNull { url ->
            Failover.role(url, failoverTiming.probeTimeoutMs)?.let { it.role == "primary" && it.formerPrimaryId == instanceId } == true
        }
    }

    /** Devralan sunucu: eski ana sunucuya ulaşana kadar "devredildin" der (o da bu sunucunun kopyası olur). */
    private fun startFencer(former: ReplicaSource, config: ServerConfig) {
        fencer = Thread({
            var warned = false
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Failover.post(
                        former.url, "/_replication/fence", former.key, "{}",
                        headers = mapOf("X-Replica-Port" to config.server.port.toString(), "X-Replica-Path" to config.server.basePath),
                    )
                    log("Eski ana sunucu (${former.url}) devredildiğini öğrendi; bu sunucunun kopyası oluyor")
                    audit.record(AuditLog.SYSTEM, "replica.fenced", former.url)
                    break
                } catch (e: Exception) {
                    if (Thread.currentThread().isInterrupted) break
                    if (!warned) log("Eski ana sunucuya (${former.url}) ulaşılamadı; dönünce kopyaya çevrilecek")
                    warned = true
                }
                try {
                    Thread.sleep(failoverTiming.fenceIntervalMs)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "ds-fence").apply { isDaemon = true; start() }
    }

    /** Kopyayı baştan eşitler (tablolar yeniden kopyalanır). */
    @Synchronized
    fun resyncReplica() {
        replicator?.resync()
    }

    /** Kullanıcıların günlük istek sayaçları (kota); deploy'lar arasında sürer, uygulama kapanınca sıfırlanır. */
    val requestCounts = DailyCounter()

    /** SMS girişi sınırları: numara ve istemci başına (deploy'lar arasında sürer). */
    val smsGuard = SmsGuard()

    private val usageFile: File? get() = dataDir?.let { File(it, "key-usage.json") }

    /** Son kullanım zamanlarını diske yazar (durdururken ve deploy'da). */
    fun saveKeyUsage() {
        val f = usageFile ?: return
        runCatching {
            f.parentFile?.mkdirs()
            f.writeText(JsonObject(keyUsage.snapshot().mapValues { JsonPrimitive(it.value.toString()) }).toString())
        }
    }

    private fun readUsage(text: String): Map<String, Instant> =
        (Json.parseToJsonElement(text) as? JsonObject).orEmpty().mapNotNull { (k, v) ->
            (v as? JsonPrimitive)?.content?.let { runCatching { Instant.parse(it) }.getOrNull() }?.let { k to it }
        }.toMap()

    /** Web paneli oturumları; deploy'lar arasında sürer (uygulama kapanınca biter). */
    val adminSessions = AdminSessions()

    /** Panelin konfigürasyon/log/yedek işleri; app bağlar. Sonraki deploy'da geçerli olur. */
    @Volatile
    var adminHost: AdminHost? = null

    /** Uygulamaların durumu ve Git'ten deploy (`/_apps`); app bağlar. Sonraki deploy'da geçerli olur. */
    @Volatile
    var appsHost: com.plyg.droidserver.core.apps.AppsHost? = null

    private val sampler by lazy {
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ds-metrics").apply { isDaemon = true } }.also { ex ->
            val stats = device
            if (stats != null) {
                ex.scheduleAtFixedRate({ runCatching { metrics.sampleDevice(stats.snapshot()) } }, 0, 60, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
    }

    /** Dosya alanlarının deposu. */
    val fileStore: FileStore? = dataDir?.let { FileStore(File(it, "storage"), resizer) }

    /** Statik site klasörü (uygulamadan zip ile yüklenir). */
    val siteDir: File? = dataDir?.let { File(it, "www") }

    /** Script'ler (`*.js`); uygulamadaki düzenleyiciyle değiştirilir, deploy'da yüklenir. */
    val scriptsDir: File? = dataDir?.let { File(it, "scripts") }

    /** Kurulan paketlerin deploy'dan sonra eklenecek örnek verileri (`<ad>.json`). */
    private val pendingSamples: File? = dataDir?.let { File(it, "packages/pending") }

    /** Ağ sürücüsü (WebDAV) klasörü; yedeğe girmez. */
    val driveDir: File? = dataDir?.let { File(it, "drive") }

    private var cron: CronRunner? = null

    /** Yedekler (`backups/`). */
    val backups: BackupStore? = dataDir?.let { BackupStore(File(it, "backups")) }

    /** Yedeğe girecek klasörler. */
    val backupDirs: Map<String, File?>
        get() = mapOf("storage" to fileStore?.root, "scripts" to scriptsDir, "www" to siteDir)

    private var server: EmbeddedServer<*, *>? = null
    private var driver: SqlDriver? = null
    private var database: DatabaseConfig? = null
    private var writes: WriteGate? = null
    private var running: ServerConfig? = null

    private val _state = MutableStateFlow<EngineState>(EngineState.Stopped)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    @Synchronized
    override fun deploy(config: ServerConfig) {
        val errors = ConfigValidator.validate(config)
        if (errors.isNotEmpty()) throw DeployException(errors)

        // Kopyada veritabanına yazan yalnızca eşitlemedir: şema değişirken aynı anda yazmasın (SQLite iki bağlantıda
        // kilitlenir). Deploy yarıda kalırsa eşitleme eski konfigürasyonla sürer.
        val pausedReplica = replicator?.let { it.stop(); replicator = null; true } == true
        fun resumeReplica() {
            val d = driver
            val c = running
            if (pausedReplica && d != null && c != null) startReplicator(d, c)
        }
        // Yeni veritabanı bağlantısını ve şemayı, eski sunucuyu kapatmadan hazırla.
        val newDriver = openDatabase(config.database)
        val report = try {
            SchemaMigrator(newDriver).migrate(config.schemaCollections())
        } catch (e: SchemaBlockedException) {
            newDriver.close()
            resumeReplica()
            throw DeployException(e.blockers.map { tr("Şema: {0}", it) })
        } catch (t: Throwable) {
            newDriver.close()
            resumeReplica()
            _state.value = EngineState.Failed(tr("Migration hatası: {0}", t.message))
            throw t
        }
        log("Migration: $report")
        report.warnings.forEach { log("Şema uyarısı: $it") }
        runCatching { FieldEncryption.sync(newDriver, config, running) }
            .onSuccess { if (it > 0) log("Alan şifreleme: $it değer dönüştürüldü") }
            .onFailure { log("Alan şifreleme dönüştürülemedi: ${it.message}") }

        // Script'ler eski sunucu kapanmadan derlenir: sözdizimi hatası deploy'u reddeder, çalışan sunucu sürer.
        val scripts = if (!config.scripts.enabled) null else try {
            ScriptRuntime.load(ScriptRuntime.sources(scriptsDir), config.scripts.copy(env = config.env + config.scripts.env), config.collections.map { it.name }.toSet(), log, config.auth.users.roles.map { it.name }.toSet())
        } catch (e: ScriptLoadException) {
            newDriver.close()
            resumeReplica()
            throw DeployException(e.errors.map { tr("Script: {0}", it) })
        }

        val previous = running
        // İlk açılışta (deploy değil): bu ana sunucu yokken bir kopya devraldıysa yazmalar açılmaz.
        val replaced = if (previous == null) replacedBy(config) else null
        stopInternal()
        webhooks.configure(config.webhooks)
        try {
            val startedAt = Instant.now()
            val gate = WriteGate()
            val failoverPort = FailoverPort(instanceId, failoverTiming, replicaRegistry::seen, ::demote, replicaRegistry)
            if (replaced != null) {
                failoverPort.isFenced = true
                gate.pause(reason = tr("Bu sunucu devredildi; yeni ana sunucu: {0}", replaced))
                log("Bu sunucu yokken $replaced devraldı; yazmalar kapalı")
            }
            server = embeddedServer(CIO, port = config.server.port, host = config.server.host) {
                droidServerModule(config, newDriver, startedAt, gate, fileStore, siteDir, resizer, scripts, metrics, adminSessions, adminHost, log = log, webhooks = webhooks, automationHost = automations, aiHost = ai, localAi = localAi, keyUsage = keyUsage, requestCounts = requestCounts, smsGuard = smsGuard, davDirs = mapOf("drive" to driveDir, "scripts" to scriptsDir), audit = audit, clientGuard = guard, health = health,
                    phone = PhoneService(config.phone, phone, device, dataDir?.let { File(it, "phone-sms.json") }, audit), appsHost = appsHost, failover = failoverPort, mqttHost = mqtt,
                    mqttRetained = dataDir?.let { File(it, "mqtt-retained.json") }, paymentHost = payments, mqttCa = mqttCa)
            }.start(wait = false)
            cron = scripts?.let { CronRunner(it, log) { !gate.isPaused }.apply { start() } }
            config.replication.replicaOf?.let { src ->
                gate.pause(reason = tr("Bu sunucu salt okunur bir kopya; yazmalar ana sunucuya ({0}) yapılmalı", src.url))
                startReplicator(newDriver, config)
            } ?: run { _replica.value = null }
            config.replication.formerPrimary?.takeIf { config.replication.replicaOf == null }?.let { startFencer(it, config) }
            // Kendi anahtarı yeni ana sunucuda da geçerli (paylaşılan konfigürasyon); yoksa onun "devredildin"i beklenir.
            if (replaced != null) config.auth.apiKeys.firstOrNull()?.let { demote(replaced, it) }
            sampler
            driver = newDriver
            database = config.database
            writes = gate
            running = config
            _state.value = EngineState.Running(config.server.port, startedAt, report.toString())
            seedPendingPackages(newDriver, config, gate)
            log("Sunucu ${config.server.host}:${config.server.port} üzerinde çalışıyor")
            audit.keepDays = config.audit.keepDays
            if (previous == null) {
                audit.record(AuditLog.APP, "server.start", detail = "port ${config.server.port}")
            } else {
                AuditLog.changes(previous, config).takeIf { it.isNotEmpty() }?.let { audit.record(AuditLog.APP, "deploy", detail = it.joinToString("; ")) }
            }
        } catch (t: Throwable) {
            newDriver.close()
            _state.value = EngineState.Failed(tr("Başlatılamadı: {0}", t.message))
            throw t
        }
    }

    /**
     * Konfigürasyonun şema planı (dry-run; veritabanına yazmaz). Sunucu aynı veritabanıyla çalışıyorsa
     * onun bağlantısı, değilse geçici bir bağlantı kullanılır.
     */
    @Synchronized
    fun plan(config: ServerConfig): MigrationPlan {
        driver?.takeIf { database == config.database }?.let { return SchemaMigrator(it).plan(config.schemaCollections()) }
        return openDatabase(config.database).use { SchemaMigrator(it).plan(config.schemaCollections()) }
    }

    /**
     * Çalışan sunucunun kullanıcı hesapları (uygulamadaki "Kullanıcılar" ekranı için; sunucunun bağlantısını kullanır).
     * Sunucu çalışmıyorsa veya hesaplar kapalıysa null. Bloklayıcıdır; ana iş parçacığında çağırmayın.
     */
    /** Çalışan sunucunun takımları (uygulamadaki "Takımlar" ekranı); takımlar kapalıysa null. Bloklayıcıdır. */
    @Synchronized
    fun teams(): com.plyg.droidserver.core.auth.TeamService? {
        val cfg = running?.takeIf { it.auth.users.enabled && it.auth.users.teams.enabled } ?: return null
        val db = driver ?: return null
        return com.plyg.droidserver.core.auth.TeamService(db, cfg)
    }

    @Synchronized
    fun users(): UserService? {
        val cfg = running?.takeIf { it.auth.users.enabled } ?: return null
        val db = driver ?: return null
        return UserService(db, cfg, files = fileStore, audit = audit)
    }

    /**
     * Çalışan sunucunun iş kuyruğu (uygulamadaki "Arka plan işleri" ekranı); sunucu çalışmıyorsa veya script'ler
     * kapalıysa null. Bloklayıcıdır.
     */
    @Synchronized
    fun jobs(): JobQueue? {
        running?.takeIf { it.scripts.enabled } ?: return null
        return JobQueue(driver ?: return null)
    }

    /**
     * Paketin dosyaları, konfigürasyon kaydedilip deploy edilmeden önce: script'ler yazılır (önceki sürümün artık
     * olmayanları silinir), örnek veri deploy'dan sonra boş koleksiyonlara eklenmek üzere bekletilir.
     */
    fun stagePackage(pkg: PackageFile, previous: InstalledPackage?) {
        val dir = scriptsDir
        if (pkg.scripts.isNotEmpty() && dir == null) throw IllegalStateException(tr("Script klasörü yok"))
        dir?.mkdirs()
        previous?.scripts.orEmpty().filter { it !in pkg.scripts }.forEach { File(dir, it).delete() }
        pkg.scripts.forEach { (name, text) -> File(dir, name).writeText(text) }
        val pending = pendingSamples ?: return
        val file = File(pending, "${pkg.manifest.name}.json")
        if (pkg.samples.isEmpty()) { file.delete(); return }
        pending.mkdirs()
        file.writeText(kotlinx.serialization.json.JsonObject(pkg.samples.associate { (c, records) -> c to kotlinx.serialization.json.JsonArray(records) }).toString())
    }

    /** Kaldırılan paketin script dosyaları ve bekleyen örnek verisi. */
    fun unstagePackage(p: InstalledPackage) {
        p.scripts.forEach { name -> scriptsDir?.let { File(it, name).delete() } }
        pendingSamples?.let { File(it, "${p.name}.json").delete() }
    }

    private fun seedPendingPackages(db: SqlDriver, config: ServerConfig, gate: WriteGate) {
        val files = pendingSamples?.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        for (f in files) {
            runCatching {
                val samples = kotlinx.serialization.json.Json.parseToJsonElement(f.readText()).jsonObject.map { (c, v) -> c to v.jsonArray.map { it.jsonObject } }
                val n = gate.write { SampleSeed.seed(db, config, samples) }
                log(tr("Paket örnek verisi eklendi ({0}): {1} kayıt", f.nameWithoutExtension, n))
            }.onFailure { log(tr("Paket örnek verisi eklenemedi ({0}): {1}", f.nameWithoutExtension, it.message)) }
            f.delete()
        }
    }

    /**
     * Uygulama şablonunun deploy sonrası kurulumu: örnek kayıtlar (boş koleksiyonlara) ve statik site. Site klasörü
     * doluysa [overwriteSite] olmadan dokunulmaz. Yapılanların özeti; şablonun koleksiyonları çalışan konfigürasyonda
     * yoksa [IllegalStateException].
     */
    @Synchronized
    fun installTemplate(template: AppTemplate, samples: Boolean, site: Boolean, overwriteSite: Boolean = false): String {
        val cfg = running ?: throw IllegalStateException(tr("Sunucu çalışmıyor"))
        val db = driver ?: throw IllegalStateException(tr("Sunucu çalışmıyor"))
        val missing = template.collections().map { it.name }.filter { n -> cfg.collections.none { it.name == n } }
        if (missing.isNotEmpty()) throw IllegalStateException(tr("Şablonun koleksiyonları konfigürasyonda yok: {0}", missing.joinToString()))
        val done = mutableListOf<String>()
        if (samples) {
            val n = (writes ?: WriteGate()).write { template.seed(db, cfg) }
            done += if (n > 0) tr("{0} örnek kayıt", n) else tr("örnek kayıt eklenmedi (koleksiyonlar boş değil)")
        }
        val dir = siteDir
        if (site && dir != null) {
            val index = File(dir, "index.html")
            if (dir.listFiles().orEmpty().isNotEmpty() && !overwriteSite) {
                done += tr("site kurulmadı (mevcut site korundu)")
            } else {
                dir.mkdirs()
                index.writeText(template.site(cfg.server.basePath))
                done += tr("web sitesi")
            }
        }
        return done.joinToString(", ").ifEmpty { tr("yapılacak bir şey yok") }
    }

    /** Sensör panosu (uygulamadaki "Sensörler" ekranı): son değerler ve son [hours] saatin grafiği; sensör yoksa null. Bloklayıcıdır. */
    @Synchronized
    fun sensors(hours: Long = 24): JsonArray? {
        val cfg = running?.takeIf { it.sensors.isNotEmpty() } ?: return null
        val db = driver ?: return null
        val since = Instant.now().minusSeconds(hours * 3600)
        return JsonArray(cfg.sensors.mapNotNull { s ->
            cfg.collections.firstOrNull { it.name == s.collection }?.let { c -> Sensors.series(RecordService(db, c, collections = cfg.schemaCollections()), s, since) }
        })
    }

    /** Panonun verisi (uygulamadaki "Panolar" ekranı; bkz. DashboardService); sunucu çalışmıyorsa veya pano yoksa null. Bloklayıcıdır. */
    @Synchronized
    fun dashboard(name: String): JsonObject? {
        val cfg = running ?: return null
        val db = driver ?: return null
        val d = cfg.dashboards.firstOrNull { it.name == name } ?: return null
        val services = cfg.collections.associate { it.name to RecordService(db, it, collections = cfg.schemaCollections()) }
        return com.plyg.droidserver.core.api.DashboardService(cfg, com.plyg.droidserver.core.api.ViewService(cfg, services)).data(d)
    }

    /** Son ödemeler (uygulamadaki "Ödemeler" ekranı), yeniden eskiye; ödemeler kurulmamışsa null. Bloklayıcıdır. */
    fun recentPayments(limit: Int = 30): List<JsonObject>? {
        val cfg = running ?: return null
        val db = driver ?: return null
        if (cfg.payments.providers.isEmpty()) return null
        return RecordService(db, SystemCollections.payments).list(1, limit, "-id", emptyList()).items
    }

    /** Haritadaki bir kayıt: kimlik, ilk metin alanı ve konum. */
    data class MapPoint(val id: Long, val label: String?, val point: GeoPoint)

    /**
     * Haritada gösterilecek kayıtlar (uygulamadaki "Harita" ekranı): [collection]'ın [field] konum alanı dolu olanlar,
     * en yeniden en çok [limit]. Sunucu çalışmıyorsa veya alan yoksa null. Bloklayıcıdır.
     */
    @Synchronized
    fun mapPoints(collection: String, field: String, limit: Int = 500): List<MapPoint>? {
        val cfg = running ?: return null
        val db = driver ?: return null
        val c = cfg.collections.firstOrNull { it.name == collection }?.takeIf { col -> col.fields.any { it.name == field && it.type == FieldType.GEO } } ?: return null
        val label = c.fields.firstOrNull { it.type == FieldType.TEXT && !it.encrypted }?.name
        val page = RecordService(db, c, collections = cfg.schemaCollections()).list(1, limit, "-id", listOf("$field[null]" to "false"), listOfNotNull("id", field, label))
        return page.items.mapNotNull { r ->
            val p = r[field]?.let { runCatching { GeoPoint.of(it) }.getOrNull() } ?: return@mapNotNull null
            MapPoint(r.getValue("id").jsonPrimitive.content.toLong(), label?.let { (r[it] as? JsonPrimitive)?.contentOrNull }, p)
        }
    }

    /** Kullanıcının kota kullanımı (uygulamadaki kullanıcı kartı); sunucu çalışmıyorsa veya hesaplar kapalıysa null. */
    @Synchronized
    fun usage(userId: Long): JsonObject? {
        val cfg = running?.takeIf { it.auth.users.enabled } ?: return null
        val db = driver ?: return null
        return QuotaService(db, cfg, fileStore, requestCounts).usage(userId)
    }

    /**
     * Çalışan sunucunun içe/dışa aktarması (uygulamadaki "İçe / dışa aktarma" ekranı; sunucunun bağlantısını ve yazma
     * kapısını kullanır). Sunucu çalışmıyorsa null. Bloklayıcıdır; ana iş parçacığında çağırmayın.
     */
    @Synchronized
    fun dataTransfer(): DataTransfer? {
        val cfg = running ?: return null
        val db = driver ?: return null
        return DataTransfer(db, cfg, fileStore, writes ?: return null)
    }

    /**
     * Çalışan sunucunun veritabanından yedek alır (sunucu çalışmaya devam eder). [configText]: diskteki konfigürasyon
     * (sırlar referans olarak). Zamanlanmış yedeklerde eskiler `backup.keep`'e göre silinir.
     */
    /** Ana sunucudaki kopya tablosu: anadaki son sıra (kopyalanabilir değilse null) ve bilinen kopyalar. */
    fun replicaTable(): Pair<Long?, List<ReplicaRegistry.Report>> {
        val latest = driver?.takeIf { running?.replication?.source == true }?.let { runCatching { ChangeLog.latest(it) }.getOrNull() }
        return latest to replicaRegistry.reports()
    }

    fun backup(configText: String, kind: BackupKind): BackupInfo {
        val store = backups ?: throw IllegalStateException(tr("Yedek klasörü yok"))
        val (db, cfg) = synchronized(this) { (driver ?: throw IllegalStateException(tr("Sunucu çalışmıyor"))) to running!! }
        val file = store.newFile(kind)
        try {
            val manifest = file.outputStream().buffered().use { BackupArchive.create(it, db, cfg, configText, backupDirs) }
            log("Yedek alındı: ${file.name} (${manifest.tables.values.sum()} kayıt, ${file.length() / 1024} KB)")
        } catch (t: Throwable) {
            file.delete()
            throw t
        }
        if (kind == BackupKind.SCHEDULED) store.prune(cfg.backup.keep).forEach { log("Eski yedek silindi: ${it.name}") }
        return store.list().first { it.file == file }
    }

    @Synchronized
    override fun pauseWrites() {
        val gate = writes ?: return
        gate.pause()
        (_state.value as? EngineState.Running)?.let { _state.value = it.copy(writesPaused = true) }
        log("Yazmalar geçici olarak kapatıldı (sunucu salt okunur)")
    }

    @Synchronized
    override fun resumeWrites() {
        if (running?.replication?.replicaOf != null) return // kopya hep salt okunur
        val gate = writes?.takeIf { it.isPaused } ?: return
        gate.resume()
        (_state.value as? EngineState.Running)?.let { _state.value = it.copy(writesPaused = false) }
        log("Yazmalar yeniden açıldı")
    }

    @Synchronized
    override fun stop() {
        stopInternal()
        _state.value = EngineState.Stopped
    }

    private fun stopInternal() {
        saveKeyUsage()
        replicator?.stop()
        replicator = null
        fencer?.interrupt()
        fencer = null
        cron?.stop()
        cron = null
        server?.stop(gracePeriodMillis = 500, timeoutMillis = 3000)
        server = null
        driver?.close()
        driver = null
        database = null
        writes = null
        running = null
    }
}
