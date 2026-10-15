package dev.droidserver.app.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.core.app.NotificationCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import dev.droidserver.app.R
import dev.droidserver.app.Settings
import dev.droidserver.app.service.UpdateReceiver
import dev.droidserver.app.ui.MainActivity
import dev.droidserver.core.i18n.tr
import dev.droidserver.core.server.EngineState
import dev.droidserver.core.server.ServerEngine
import dev.droidserver.core.update.ReleaseChecker
import dev.droidserver.core.update.ReleaseInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val checkedAt: Long) : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState
    data class Downloading(val release: ReleaseInfo, val done: Long, val total: Long) : UpdateState
    /** Android'in kurulum onayı bekleniyor (veya kurulum sürüyor). */
    data class Installing(val release: ReleaseInfo) : UpdateState
    data class Failed(val message: String, val release: ReleaseInfo?) : UpdateState
}

/**
 * Uygulamanın kendini güncellemesi: GitHub Releases'ta yeni sürüm denetimi (ayar açıksa günde bir), APK'yı indirme,
 * özetini (`.sha256`) ve imza sertifikasını kurulu uygulamayla karşılaştırma, [PackageInstaller] ile kurma.
 * Android aynı paketi farklı anahtarla imzalanmış APK ile zaten güncellemez; buradaki imza denetimi kullanıcıya nedenini
 * önceden ve anlaşılır biçimde söylemek içindir (ör. Android Studio'dan kurulmuş debug sürümü).
 */
class AppUpdater(
    private val context: Context,
    private val settings: Settings,
    private val engine: ServerEngine,
    private val log: (String) -> Unit,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val pm: PackageManager get() = context.packageManager

    val currentVersion: String by lazy { installedInfo(0)?.versionName ?: "?" }

    private val dir get() = File(context.cacheDir, "guncelleme")

    /** Otomatik denetim açık ve son denetimin üzerinden bir gün geçti mi. */
    fun isDue(now: Long = System.currentTimeMillis()) = settings.autoUpdateCheck && now - settings.lastUpdateCheck > DAY_MS

    /**
     * Yeni sürüm var mı; ağ çağrısıdır. [notify] ile (arka planda) yeni bulunan sürüm için bir kez bildirim gösterilir.
     * Kurulum veya indirme sürerken durumu ezmez.
     */
    suspend fun check(notify: Boolean = false): ReleaseInfo? = withContext(Dispatchers.IO) {
        if (_state.value is UpdateState.Downloading || _state.value is UpdateState.Installing) return@withContext null
        _state.value = UpdateState.Checking
        try {
            val release = ReleaseChecker(Settings.UPDATE_REPO).newerThan(currentVersion, settings.includePrerelease)
            settings.lastUpdateCheck = System.currentTimeMillis()
            _state.value = release?.let { UpdateState.Available(it) } ?: UpdateState.UpToDate(settings.lastUpdateCheck)
            if (release != null && notify && settings.notifiedVersion != release.version) {
                settings.notifiedVersion = release.version
                log("Yeni uygulama sürümü var: ${release.version}")
                notifyAvailable(release)
            }
            release
        } catch (e: Exception) {
            // Ağ yoksa günlük denetim bir saat sonra yeniden denenir (her bakım turunda değil).
            settings.lastUpdateCheck = System.currentTimeMillis() - DAY_MS + RETRY_MS
            _state.value = UpdateState.Failed(tr("Güncelleme denetlenemedi: {0}", e.message), null)
            null
        }
    }

    /** Bilinmeyen kaynaktan kurulum izni (Android 8+: uygulama başına). */
    fun canInstall(): Boolean = pm.canRequestPackageInstalls()

    fun installPermissionIntent(): Intent =
        Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())

    /** İndirir, doğrular ve kurulumu başlatır; Android onay penceresini gösterir. */
    suspend fun downloadAndInstall(release: ReleaseInfo) = withContext(Dispatchers.IO) {
        try {
            dir.deleteRecursively()
            _state.value = UpdateState.Downloading(release, 0, release.apkSize)
            var last = 0L
            val apk = ReleaseChecker(Settings.UPDATE_REPO).download(release, File(dir, release.apkName)) { done, total ->
                if (done - last > 256 * 1024 || done == total) {
                    last = done
                    _state.value = UpdateState.Downloading(release, done, total)
                }
            }
            verify(apk)
            _state.value = UpdateState.Installing(release)
            // Kurulumda uygulama kapanır; sunucu çalışıyorsa yeni sürüm açılınca yeniden başlatılır (BootReceiver).
            settings.restartAfterUpdate = engine.state.value is EngineState.Running
            log("Güncelleme kuruluyor: ${release.version}")
            install(apk)
        } catch (e: Exception) {
            _state.value = UpdateState.Failed(e.message ?: e.toString(), release)
        }
    }

    /** [UpdateReceiver]'dan: kurulum sonucu (başarıda uygulama zaten yeniden başlar). */
    fun onInstallResult(success: Boolean, message: String?) {
        val release = (state.value as? UpdateState.Installing)?.release
        if (success) {
            log("Güncelleme kuruldu")
            return
        }
        settings.restartAfterUpdate = false
        log("Güncelleme kurulamadı: ${message ?: tr("bilinmeyen hata")}")
        _state.value = if (message == null) {
            release?.let { UpdateState.Available(it) } ?: UpdateState.Idle // kullanıcı vazgeçti
        } else {
            UpdateState.Failed(tr("Kurulum başarısız: {0}", message), release)
        }
    }

    /** Paket adı, sürüm kodu ve imza sertifikası kurulu uygulamayla uyumlu mu. */
    private fun verify(apk: File) {
        val archive = archiveInfo(apk) ?: throw IllegalStateException(tr("İndirilen dosya bir Android paketi değil"))
        if (archive.packageName != context.packageName) throw IllegalStateException(tr("Paket adı farklı ({0})", archive.packageName))
        val installed = installedInfo(signatureFlags()) ?: throw IllegalStateException(tr("Kurulu uygulama bilgisi okunamadı"))
        if (PackageInfoCompat.getLongVersionCode(archive) <= PackageInfoCompat.getLongVersionCode(installed)) {
            throw IllegalStateException(tr("İndirilen sürüm ({0}) kurulu sürümden yeni değil", archive.versionName))
        }
        val now = signers(installed)
        val next = signers(archive)
        if (now != null && next != null && now.intersect(next).isEmpty()) {
            throw IllegalStateException(
                tr("Bu sürüm farklı bir anahtarla imzalanmış, kurulu uygulamanın üzerine kurulamaz. Uygulamayı Android Studio'dan " +
                    "(debug) kurduysanız güncellemeler buradan gelmez. Yayın sürümüne geçmek için: Yedekler'den yedek alıp " +
                    "dışa aktarın, uygulamayı kaldırın, sürüm sayfasındaki APK'yı kurun ve yedeği geri yükleyin."),
            )
        }
        if (next == null) log("Güncelleme: APK imzası bu Android sürümünde önceden okunamadı; denetimi Android yapacak")
    }

    private fun install(apk: File) {
        val installer = pm.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("droidserver.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val callback = PendingIntent.getBroadcast(context, id, Intent(context, UpdateReceiver::class.java), flags)
            session.commit(callback.intentSender)
        }
    }

    private fun signatureFlags() =
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES

    private fun installedInfo(flags: Int): PackageInfo? = runCatching { pm.getPackageInfo(context.packageName, flags) }.getOrNull()

    private fun archiveInfo(apk: File): PackageInfo? = pm.getPackageArchiveInfo(apk.path, signatureFlags())

    /** İmza sertifikalarının SHA-256 özetleri (Android 9+: anahtar değişim geçmişi dahil); okunamazsa null. */
    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String>? {
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }
        } else {
            info.signatures
        }
        return sigs?.map { s -> MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }
            ?.toSet()?.takeIf { it.isNotEmpty() }
    }

    private fun notifyAvailable(release: ReleaseInfo) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, tr("Güncellemeler"), NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(context, 3, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(
            NOTIF_ID,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_server)
                .setContentTitle(tr("DroidServer {0} çıktı", release.version))
                .setContentText(tr("Kurmak için uygulamayı açın (Durum → Uygulama güncellemesi)."))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    private companion object {
        const val DAY_MS = 24 * 3_600_000L
        const val RETRY_MS = 3_600_000L
        const val CHANNEL = "guncelleme"
        const val NOTIF_ID = 3
    }
}
