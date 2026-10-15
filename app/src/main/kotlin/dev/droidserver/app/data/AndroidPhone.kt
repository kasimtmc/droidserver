package dev.droidserver.app.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.telephony.SmsManager
import dev.droidserver.core.i18n.tr
import dev.droidserver.core.phone.PhoneException
import dev.droidserver.core.phone.PhoneHost
import dev.droidserver.core.phone.PhoneLocation
import java.time.Instant

/** SMS ve son bilinen konum; izin verilmemişse açıklayıcı hata (sihirbaz izni ister). */
class AndroidPhone(private val context: Context) : PhoneHost {
    override fun sendSms(to: String, text: String) {
        require(Manifest.permission.SEND_SMS, tr("SMS izni verilmedi (Sihirbaz → Genel → Telefon özellikleri)"))
        val sms = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
            ?: throw PhoneException(tr("Bu cihaz SMS gönderemiyor"))
        try {
            val parts = sms.divideMessage(text)
            if (parts.size > 1) sms.sendMultipartTextMessage(to, null, parts, null, null) else sms.sendTextMessage(to, null, text, null, null)
        } catch (e: RuntimeException) {
            throw PhoneException(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Sağlayıcıların son bilinen konumlarından en yenisi (GPS'i ayrıca çalıştırmaz, pil harcamaz). */
    @SuppressLint("MissingPermission")
    override fun location(): PhoneLocation? {
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        if (!fine && !granted(Manifest.permission.ACCESS_COARSE_LOCATION)) throw PhoneException(tr("Konum izni verilmedi (Sihirbaz → Genel → Telefon özellikleri)"))
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val best = lm.getProviders(true).mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }.maxByOrNull { it.time } ?: return null
        return PhoneLocation(best.latitude, best.longitude, if (best.hasAccuracy()) best.accuracy else null, Instant.ofEpochMilli(best.time), best.provider)
    }

    private fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun require(permission: String, message: String) {
        if (!granted(permission)) throw PhoneException(message)
    }
}
