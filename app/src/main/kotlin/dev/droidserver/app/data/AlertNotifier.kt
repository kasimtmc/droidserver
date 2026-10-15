package dev.droidserver.app.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.droidserver.app.R
import dev.droidserver.app.graph
import dev.droidserver.app.ui.MainActivity
import dev.droidserver.core.alerts.Alert
import dev.droidserver.core.alerts.AlertChannel
import dev.droidserver.core.i18n.tr
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
