package com.plyg.droidserver.app.data

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.plyg.droidserver.app.R
import com.plyg.droidserver.app.graph
import com.plyg.droidserver.app.ui.MainActivity
import com.plyg.droidserver.core.alerts.Alert
import com.plyg.droidserver.core.alerts.AlertChannel
import com.plyg.droidserver.core.i18n.tr
import java.io.File
import java.time.Instant

/** Sağlık uyarısını telefon bildirimi olarak gösterir (tür ve konu başına bir bildirim; "düzeldi" onu değiştirir). */
class AlertNotifier(private val context: Context, private val serverName: () -> String) : AlertChannel {
    override fun send(alert: Alert) {
        // Otomasyon uygulamalarına da (köprü açıksa ve izinli uygulamalara).
        runCatching { context.graph.automation.alert(serverName(), alert) }
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, tr("Uyarılar"), NotificationManager.IMPORTANCE_DEFAULT))
        }
        if (!nm.areNotificationsEnabled()) throw IllegalStateException(tr("bildirim izni yok"))
        val open = PendingIntent.getActivity(context, 2, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(
            BASE_ID + (alert.kind.name + alert.subject).hashCode() % 1000,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_server)
                .setContentTitle("${serverName()}: ${alert.title}")
                .setContentText(alert.message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(alert.message))
                .setPriority(if (alert.critical) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    companion object {
        const val CHANNEL = "uyarilar"
        private const val BASE_ID = 10_000
    }
}

/**
 * Uygulamanın beklenmedik kapanışı: yakalanmayan hata `crash.txt`'ye yazılır, sonraki açılışta sağlık uyarısı olarak
 * bildirilir ve silinir.
 */
object CrashMarker {
    fun install(context: Context) {
        val file = File(context.filesDir, FILE)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { file.writeText("${Instant.now()}\n${e.stackTraceToString().take(4000)}") }
            previous?.uncaughtException(thread, e)
        }
    }

    /** Önceki çökmenin özeti (zaman ve hatanın ilk satırları) ve dosyayı siler; çökme yoksa null. */
    fun take(context: Context): String? {
        val file = File(context.filesDir, FILE)
        if (!file.isFile) return null
        val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
        file.delete()
        val at = lines.firstOrNull().orEmpty()
        val error = lines.drop(1).take(2).joinToString("\n").ifBlank { tr("bilinmeyen hata") }
        return tr("{0} tarihinde uygulama bir hatayla kapandı.\n{1}", at, error)
    }

    private const val FILE = "crash.txt"
}

/** Sistemin servisi öldürüp yeniden başlatması (START_STICKY): sağlık uyarısı ve sorun giderici için. */
object ServiceRestart {
    /** Bu süreçte servis sistemce yeniden başlatıldıysa açıklaması; süreç her başlatmada yenidir. */
    @Volatile
    var last: String? = null
        private set

    fun record(context: Context): String =
        (tr("{0} tarihinde sistem sunucu servisini durdurup yeniden başlattı.", Instant.now()) + (exitReason(context)?.let { "\n$it" }.orEmpty()))
            .also { last = it }

    /** Önceki sürecin bitiş nedeni (Android 11+). */
    private fun exitReason(context: Context): String? {
        if (Build.VERSION.SDK_INT < 30) return null
        val info = runCatching {
            context.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(null, 0, 1).firstOrNull()
        }.getOrNull() ?: return null
        val reason = when (info.reason) {
            ApplicationExitInfo.REASON_LOW_MEMORY -> tr("bellek yetmedi")
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> tr("aşırı pil veya işlemci kullanımı")
            ApplicationExitInfo.REASON_SIGNALED -> tr("sistem sonlandırdı (sinyal {0})", info.status)
            ApplicationExitInfo.REASON_USER_REQUESTED -> tr("kullanıcı durdurdu (Zorla durdur veya son uygulamalardan kaydırma)")
            ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE -> tr("çökme")
            ApplicationExitInfo.REASON_ANR -> tr("yanıt vermedi (ANR)")
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> tr("izin değişti")
            else -> tr("kod {0}", info.reason)
        }
        return tr("Önceki süreç {0} tarihinde bitti: {1}.", Instant.ofEpochMilli(info.timestamp), reason) +
            (info.description?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty())
    }
}
