package com.plyg.droidserver.app.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.plyg.droidserver.app.R
import com.plyg.droidserver.app.data.AlertNotifier
import com.plyg.droidserver.app.data.AndroidDeviceStats
import com.plyg.droidserver.app.data.CrashMarker
import com.plyg.droidserver.app.data.NetUtil
import com.plyg.droidserver.app.data.ServiceRestart
import com.plyg.droidserver.app.AppGraph
import com.plyg.droidserver.app.graph
import com.plyg.droidserver.app.ui.MainActivity
import com.plyg.droidserver.core.alerts.AlertChannels
import com.plyg.droidserver.core.alerts.AlertKind
import com.plyg.droidserver.core.alerts.HealthInput
import com.plyg.droidserver.core.alerts.TelegramActions
import com.plyg.droidserver.core.alerts.TelegramBot
import com.plyg.droidserver.core.backup.BackupKind
import com.plyg.droidserver.core.backup.BackupSchedule
import com.plyg.droidserver.core.config.ConfigSecrets
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.config.TelegramConfig
import com.plyg.droidserver.core.i18n.tr
import com.plyg.droidserver.core.process.ProcessSupervisor
import com.plyg.droidserver.core.server.DeployException
import com.plyg.droidserver.core.server.EngineState
import com.plyg.droidserver.core.templates.AppTemplate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime

/**
 * Sunucuyu uygulama kapalıyken de ayakta tutan foreground service.
 * Kalıcı bildirim zorunludur; bildirimde adres ve "Durdur" düğmesi gösterilir.
 */
class ServerService : Service() {

    companion object {
        private const val ACTION_START = "com.plyg.droidserver.START"
        private const val ACTION_DEPLOY = "com.plyg.droidserver.DEPLOY"
        private const val ACTION_STOP = "com.plyg.droidserver.STOP"
        private const val CHANNEL = "server"
        private const val NOTIF_ID = 1

        fun start(ctx: Context) = send(ctx, ACTION_START)
        fun deploy(ctx: Context) = send(ctx, ACTION_DEPLOY)
        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, ServerService::class.java).setAction(ACTION_STOP))
        }

        private fun send(ctx: Context, action: String) =
            ContextCompat.startForegroundService(ctx, Intent(ctx, ServerService::class.java).setAction(action))
    }

    /** Servisin başladığı profilin kabı: profil değişse de bu servis kendi sunucusunu durdurur. */
    private val owner: AppGraph by lazy { graph }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var unwatchNetwork: (() -> Unit)? = null
    private var networkJob: Job? = null
    private var retryJob: Job? = null
    private var maintenanceJob: Job? = null

    /** Telegram yönetim botu ve başladığı ayar; ayar değişince yeniden başlar. */
    private var bot: Pair<TelegramConfig, Job>? = null

    /** Sunucu Telegram'dan durduruldu; servis yalnızca komutları dinlemek için açık. */
    @Volatile
    private var standby = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        goForeground(tr("Başlatılıyor…"))
        standby = false
        if (intent == null) owner.logs.add(ServiceRestart.record(this))
        acquireLocks()
        watchNetwork()
        startMaintenance()
        // intent == null: sistem servisi öldürüp yeniden başlattı (START_STICKY) -> yeniden deploy et.
        retryJob?.cancel()
        scope.launch { deployAll() }
        return START_STICKY
    }

    /**
     * Bileşenler -> hazır olmayı bekle -> sunucu; sıra ve senkronizasyon DeployOrchestrator'da.
     * Konfigürasyon hatası dışındaki başarısızlıklar (örn. telefon açılırken Postgres henüz hazır değil) giderek
     * uzayan aralıklarla (5 sn → 1 dk) yeniden denenir; yeni bir başlatma/deploy isteği bekleyen denemeyi iptal eder.
     */
    private suspend fun deployAll(attempt: Int = 0): Unit = owner.deployLock.withLock {
        val g = owner
        try {
            g.network.writeDnsConf()
            val result = g.orchestrator.deploy(g.configRepo.load())
            val urls = NetUtil.localIpv4().ifEmpty { listOf("127.0.0.1") }
                .joinToString("  ") { "http://$it:${result.config.server.port}" }
            val problems = result.componentErrors.size
            updateNotification(tr("Çalışıyor: {0}", urls) + if (problems > 0) tr("\n{0} bileşen sorunlu (Loglar)", problems) else "")
            installPendingTemplate(g)
            g.discovery.announce(result.config)
        } catch (e: DeployException) {
            e.errors.forEach { g.logs.add("Konfigürasyon hatası: $it") }
            updateNotification(tr("Deploy başarısız: konfigürasyon hatalı"))
            g.engine.resumeWrites() // veri taşımadan sonra deploy başarısızsa eski sunucu salt okunur kalmasın
        } catch (t: Throwable) {
            g.engine.resumeWrites()
            val waitSec = minOf(60L, 5L shl minOf(attempt, 4))
            g.logs.add("Deploy başarısız: ${t.message}; $waitSec sn sonra yeniden denenecek (deneme ${attempt + 1})")
            updateNotification(tr("Başlatılamadı, {0} sn sonra yeniden denenecek: {1}", waitSec, t.message))
            retryJob = scope.launch {
                delay(waitSec * 1_000)
                deployAll(attempt + 1)
            }
        }
    }

    /** Sihirbazda seçilen hazır uygulamanın örnek verisi ve sitesi (koleksiyonları deploy edildiyse). */
    private fun installPendingTemplate(g: AppGraph) {
        val pending = g.pendingTemplate.get()?.takeIf { !it.applyDraft } ?: return
        val template = AppTemplate.of(pending.id) ?: return g.pendingTemplate.clear()
        runCatching { g.engine.installTemplate(template, pending.samples, pending.site, overwriteSite = pending.site) }
            .onSuccess { g.logs.add("${template.title} şablonu kuruldu: $it"); g.pendingTemplate.clear() }
            .onFailure { g.logs.add("${template.title} şablonu kurulamadı: ${it.message}"); g.pendingTemplate.clear() }
    }

    override fun onDestroy() {
        val g = owner
        unwatchNetwork?.invoke()
        g.orchestrator.stopAll()
        g.discovery.stop()
        g.logs.add("Sunucu durduruldu")
        releaseLocks()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Ağ değişince (Wi-Fi ↔ mobil, yeni IP) DNS dosyası yenilenir, doğrudan erişimde modem yönlendirmesi ve DuckDNS
     * yeniden yapılır. Değişiklikler art arda gelebildiği için birkaç saniye beklenip tek seferde işlenir.
     */
    private fun watchNetwork() {
        if (unwatchNetwork != null) return
        val g = owner
        unwatchNetwork = g.network.onChange {
            networkJob?.cancel()
            networkJob = scope.launch {
                delay(5_000)
                g.network.writeDnsConf()
                g.direct.recheck()
            }
        }
    }

    /**
     * Yarım dakikada bir: zamanlanmış yedeğin zamanı geldiyse yedek alır (ve seçilen klasöre kopyalar), sağlık
     * uyarılarını (disk, hata oranı, sertifika, pil, sunucu/bileşen, önceki çökme) denetler.
     */
    private fun startMaintenance() {
        if (maintenanceJob?.isActive == true) return
        val g = owner
        val device = AndroidDeviceStats(this)
        val notifier = AlertNotifier(this) { g.profile.name }
        maintenanceJob = scope.launch {
            var schedule: BackupSchedule? = null
            var expression: String? = null
            var alertsFor: ServerConfig? = null
            var crashChecked = false
            while (isActive) {
                val active = runCatching { g.configRepo.loadActiveOrNull() }.getOrNull()
                val backup = active?.backup
                if (backup?.enabled == true && g.engine.state.value is EngineState.Running) {
                    if (expression != backup.schedule) {
                        expression = backup.schedule
                        schedule = runCatching { BackupSchedule(backup.schedule) }.getOrNull()
                    }
                    if (schedule?.due(LocalDateTime.now()) == true) {
                        runCatching { g.backups.backup(BackupKind.SCHEDULED) }
                            .onFailure {
                                g.logs.add("Zamanlanmış yedek alınamadı: ${it.message}")
                                g.engine.health.event(AlertKind.BACKUP, tr("Zamanlanmış yedek alınamadı: {0}", it.message))
                            }
                    }
                }
                // Uygulama güncellemesi: ayar açıksa günde bir (yeni sürüm bir kez bildirilir).
                if (g.updater.isDue()) g.updater.check(notify = true)
                if (active != null) runCatching {
                    if (active != alertsFor) {
                        alertsFor = active
                        val resolved = runCatching { ConfigSecrets.resolve(active, g.secrets::get) }.getOrDefault(active)
                        g.engine.health.configure(resolved.alerts, AlertChannels.from(resolved, notifier), resolved.name)
                        syncBot(resolved)
                    }
                    if (!crashChecked) {
                        crashChecked = true
                        val crash = CrashMarker.take(this@ServerService)
                        if (crash != null) g.engine.health.event(AlertKind.CRASH, crash)
                        else ServiceRestart.last?.let { g.engine.health.event(AlertKind.RESTART, it) }
                    }
                    g.engine.health.check(
                        HealthInput(
                            device = runCatching { device.snapshot() }.getOrNull(),
                            minutes = g.engine.metrics.series(),
                            certificateUntil = g.direct.status.value.certificateUntil,
                            serverFailure = (g.engine.state.value as? EngineState.Failed)?.reason,
                            failedComponents = g.supervisor.statuses.value.values.filter { it.phase == ProcessSupervisor.Phase.FAILED }.map { it.id },
                            replicaProblem = g.engine.replica.value?.let { r ->
                                val since = r.lastContact?.let { Duration.between(it, Instant.now()).toMinutes() }
                                when {
                                    r.error == null -> null
                                    since == null -> tr("Henüz eşitlenemedi: {0}.", r.error)
                                    since >= 5 -> tr("{0} dakikadır eşitlenemiyor: {1}.", since, r.error)
                                    else -> null
                                }
                            },
                        ),
                    )
                }.onFailure { g.logs.add("Sağlık denetimi başarısız: ${it.message}") }
                // Widget'taki istek sayısı ve pil.
                StatusWidget.refresh(this@ServerService)
                delay(30_000)
            }
        }
    }

    // ---- Telegram ile yönetim ----

    /** Yönetici sohbeti ayarlıysa botu başlatır; ayar değiştiyse eskisini durdurur. */
    private fun syncBot(config: ServerConfig) {
        val t = config.alerts.telegram?.takeIf { it.adminChatId != null && it.botToken.isNotBlank() && !it.botToken.startsWith("secret:") }
        if (bot?.first == t) return
        bot?.second?.cancel()
        bot = t?.let { it to scope.launch { TelegramBot(it.botToken, it.adminChatId!!, telegramActions, owner.logs::add).run() } }
    }

    private val telegramActions = object : TelegramActions {
        override fun status(): String = buildString {
            val g = owner
            when (val s = g.engine.state.value) {
                is EngineState.Running -> {
                    val minutes = Duration.between(s.since, Instant.now()).toMinutes()
                    appendLine(tr("✅ Çalışıyor ({0} sa {1} dk)", minutes / 60, minutes % 60))
                    NetUtil.localIpv4().forEach { appendLine("http://$it:${s.port}") }
                }
                EngineState.Stopped -> appendLine(if (standby) tr("⏸ Durduruldu; /baslat ile başlatın") else tr("⏸ Durduruldu"))
                is EngineState.Failed -> appendLine(tr("❌ Çalışmıyor: {0}", s.reason))
            }
            val series = g.engine.metrics.series()
            appendLine(tr("Son bir saat: {0} istek, {1} hata", series.sumOf { it.requests }, series.sumOf { it.errors }))
            runCatching { AndroidDeviceStats(this@ServerService).snapshot() }.getOrNull()?.let { d ->
                d.batteryPercent?.let { appendLine(tr("Pil: %{0}", it) + if (d.charging == true) tr(" (şarjda)") else "") }
                d.freeStorageBytes?.let { appendLine(tr("Boş alan: {0} GB", "%.1f".format(it / 1e9))) }
            }
            g.supervisor.statuses.value.values.filter { it.phase == ProcessSupervisor.Phase.FAILED }.takeIf { it.isNotEmpty() }
                ?.let { appendLine(tr("Sorunlu bileşenler: {0}", it.joinToString { c -> c.id })) }
        }.trimEnd()

        override fun backup(): String {
            if (owner.engine.state.value !is EngineState.Running) return tr("Sunucu çalışmıyor; yedek alınamaz.")
            val info = owner.backups.backup(BackupKind.MANUAL, actor = "telegram")
            return tr("Yedek alındı: {0} ({1} MB)", info.name, "%.1f".format(info.size / 1e6))
        }

        override fun logs(count: Int): List<String> = owner.logs.lines.value.takeLast(count)

        override fun start(): String {
            if (owner.engine.state.value is EngineState.Running) return tr("Sunucu zaten çalışıyor.")
            start(this@ServerService)
            return tr("Başlatılıyor; birkaç saniye sonra /durum yazın.")
        }

        override fun stop(): String {
            if (standby) return tr("Sunucu zaten durdurulmuş.")
            standby = true
            retryJob?.cancel()
            val g = owner
            runBlocking { g.deployLock.withLock { g.orchestrator.stopAll() } }
            g.discovery.stop()
            g.logs.add("Sunucu Telegram'dan durduruldu; komutlar dinleniyor")
            // Uzun yoklama için kilit gerekmez (gelen yanıt cihazı uyandırır): pil tasarrufu.
            releaseLocks()
            updateNotification(tr("Durduruldu; Telegram'dan /baslat ile başlatılabilir"))
            return tr("Sunucu durduruldu. Uygulama Telegram komutlarını dinlemeye devam ediyor; /baslat ile başlatın.")
        }
    }

    // ---- bildirim ve kilitler ----

    private fun goForeground(text: String) {
        createChannel()
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(text),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ServerService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_server)
            .setContentTitle(if (owner.profile.isDefault) "DroidServer" else "DroidServer · ${owner.profile.name}")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, tr("Durdur"), stop)
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, tr("Sunucu durumu"), NotificationManager.IMPORTANCE_LOW))
        }
    }

    /**
     * CPU'nun ve Wi-Fi'ın uykuya geçip bağlantıları düşürmesini engeller (pil tüketimini artırır). Kilit bilinçli olarak
     * süresiz: sunucu çalıştığı sürece gerekli, onDestroy'da bırakılır, süreç ölünce sistem bırakır. Süreli kilit uzun
     * yedekte veya bakım döngüsü durursa sunucuyu uyutur (WakelockTimeout gözden geçirildi, Faz 17.2).
     */
    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
    }

    @Suppress("DEPRECATION")
    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DroidServer::server")
                .apply { setReferenceCounted(false); acquire() }
        }
        if (wifiLock == null) {
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(mode, "DroidServer::wifi")
                .apply { setReferenceCounted(false); acquire() }
        }
    }
}
