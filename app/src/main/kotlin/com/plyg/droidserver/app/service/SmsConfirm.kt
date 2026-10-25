package com.plyg.droidserver.app.service

import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import com.plyg.droidserver.app.R
import com.plyg.droidserver.core.i18n.tr
import com.plyg.droidserver.core.phone.PhoneException

/**
 * Telefonun sahibinin onayıyla SIM'den SMS (PLAN 19.5, B; `SEND_SMS` izni olmadan). İstekler sırayla bekler; tek bildirim
 * kuyruğun başını gösterir (SMS'i gönder / Atla). Gönder, SMS uygulamasını numara ve metinle açar (`ACTION_SENDTO`); SMS'i
 * kullanıcı orada gönderir, uygulama sonucu bilmez ("SMS uygulamasına devredildi"). Ekran yalnızca kullanıcının bildirime
 * dokunmasıyla açılır (arka plandan etkinlik açılmaz). Süresi dolan istek düşer; kilit ekranında numara ve metin gizli.
 * Kuyruk bellekte: uygulama kapanırsa bekleyenler düşer (giriş kodları zaten kısa ömürlü).
 */
class SmsConfirmQueue private constructor(private val context: Context) {
    data class Item(val id: Long, val to: String, val text: String, val expiresAt: Long)

    private val items = ArrayDeque<Item>()
    private var nextId = 1L
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = Runnable { synchronized(this) { show() } }

    @Synchronized
    fun add(to: String, text: String, ttlSeconds: Long, replace: Boolean) {
        if (!context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()) {
            throw PhoneException(tr("Bildirim izni yok; onay bekleyen SMS gösterilemez (Ayarlar → Uygulamalar → DroidServer → Bildirimler)"))
        }
        prune()
        if (replace) items.removeAll { it.to == to }
        if (items.size >= MAX) throw PhoneException(tr("Onay bekleyen SMS kuyruğu dolu ({0}); telefondaki bildirime bakın", MAX))
        items.addLast(Item(nextId++, to, text, System.currentTimeMillis() + ttlSeconds * 1000))
        show()
    }

    /** Kuyruktan çıkarır (gönderilecek ya da atlanan); süresi dolmuşsa null. */
    @Synchronized
    fun take(id: Long): Item? {
        prune()
        val item = items.firstOrNull { it.id == id }?.also { items.remove(it) }
        show()
        return item
    }

    private fun prune() {
        val now = System.currentTimeMillis()
        items.removeAll { it.expiresAt <= now }
    }

    /** Kuyruğun başını bildirir (boşsa bildirimi kaldırır); başın süresi dolunca kendini yeniler. */
    private fun show() {
        handler.removeCallbacks(refresh)
        val nm = context.getSystemService(NotificationManager::class.java)
        val head = items.firstOrNull() ?: return nm.cancel(NOTIFICATION_ID)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, tr("Onay bekleyen SMS"), NotificationManager.IMPORTANCE_HIGH))
        }
        fun pending(code: Int, intent: Intent, activity: Boolean) = if (activity) {
            PendingIntent.getActivity(context, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        } else {
            PendingIntent.getBroadcast(context, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val send = pending(1, Intent(context, SmsSendActivity::class.java).putExtra(EXTRA_ID, head.id), activity = true)
        val skip = pending(2, Intent(context, SmsSkipReceiver::class.java).putExtra(EXTRA_ID, head.id), activity = false)
        val title = tr("{0} numarasına SMS — {1}/{2}", head.to, 1, items.size)
        val public = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_server)
            .setContentTitle(tr("Onay bekleyen SMS ({0})", items.size))
            .build()
        nm.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_server)
                .setContentTitle(title)
                .setContentText(head.text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(head.text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(public)
                .setContentIntent(send)
                .addAction(0, tr("SMS'i gönder"), send)
                .addAction(0, tr("Atla"), skip)
                .setTimeoutAfter((head.expiresAt - System.currentTimeMillis()).coerceAtLeast(1))
                .build(),
        )
        handler.postDelayed(refresh, (head.expiresAt - System.currentTimeMillis()).coerceAtLeast(0) + 500)
    }

    companion object {
        const val MAX = 20
        const val CHANNEL = "sms_onay"
        const val EXTRA_ID = "id"
        private const val NOTIFICATION_ID = 20_000

        // Yalnızca uygulama bağlamını tutar (get), etkinlik sızmaz.
        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: SmsConfirmQueue? = null

        fun get(context: Context): SmsConfirmQueue =
            instance ?: synchronized(this) { instance ?: SmsConfirmQueue(context.applicationContext).also { instance = it } }
    }
}

/** Bildirimdeki "Gönder": kuyruğun başını SMS uygulamasına devreder ve kapanır (görünmez). */
class SmsSendActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val item = SmsConfirmQueue.get(this).take(intent.getLongExtra(SmsConfirmQueue.EXTRA_ID, -1))
        if (item == null) {
            Toast.makeText(this, tr("Bu SMS isteğinin süresi doldu"), Toast.LENGTH_LONG).show()
        } else {
            try {
                startActivity(
                    Intent(Intent.ACTION_SENDTO, "smsto:${item.to}".toUri()).putExtra("sms_body", item.text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, tr("SMS uygulaması bulunamadı"), Toast.LENGTH_LONG).show()
            }
        }
        finish()
    }
}

/** Bildirimdeki "Atla": kuyruğun başını gönderilmeden düşürür. */
class SmsSkipReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SmsConfirmQueue.get(context).take(intent.getLongExtra(SmsConfirmQueue.EXTRA_ID, -1))
    }
}
