package com.plyg.droidserver.app.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import android.view.WindowManager
import com.plyg.droidserver.app.graph
import com.plyg.droidserver.core.i18n.tr

/**
 * Uygulama kilidi: açılışta ve arka planda [com.plyg.droidserver.app.Settings.appLockAfterSec]'ten uzun kalınca telefonun
 * kendi doğrulaması (parmak izi/yüz veya ekran kilidi PIN'i) istenir. Açıkken ekran görüntüsü ve son uygulamalar
 * önizlemesi engellenir; sır kopyalama gibi işlemler ayrıca onay ister ([guard]).
 */
object AppLock {
    /** Son kez arka plana geçiş (elapsedRealtime); null: süreç yeni başladı. */
    @Volatile private var stoppedAt: Long? = null
    @Volatile var unlocked = false
        private set

    /** Android 10 öncesinde ekran kilidi doğrulaması etkinlik sonucu ister; MainActivity bağlar. */
    @Volatile internal var legacyConfirm: ((Intent, (Boolean) -> Unit) -> Unit)? = null

    fun enabled(ctx: Context) = ctx.graph.settings.appLock

    /** Telefonda ekran kilidi (PIN, desen, parola) var mı; yoksa kilit açılamaz. */
    fun deviceSecure(ctx: Context) = ctx.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    /** Doğrulama ekranı açıkken etkinlik durur; bu arada geçen süre kilit sayılmaz. */
    @Volatile private var authenticating = false

    fun onStop() {
        if (!authenticating) stoppedAt = SystemClock.elapsedRealtime()
    }

    /** Ön plana gelince kilitlenmeli mi. */
    fun shouldLock(ctx: Context): Boolean {
        // Ekran kilidi sonradan kaldırıldıysa doğrulanamaz; kullanıcı uygulamanın dışında kalmasın.
        if (!enabled(ctx) || authenticating || !deviceSecure(ctx)) return false
        val at = stoppedAt ?: return !unlocked
        val elapsedSec = (SystemClock.elapsedRealtime() - at) / 1000
        return elapsedSec >= ctx.graph.settings.appLockAfterSec
    }

    fun lock() {
        unlocked = false
    }

    /** Kilit açıkken ekran görüntüsünü ve son uygulamalar önizlemesini engeller. */
    fun applySecureFlag(activity: Activity) {
        if (enabled(activity)) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    /** Telefonun doğrulamasını ister; sonuç ana iş parçacığında. */
    fun authenticate(activity: Activity, title: String, result: (Boolean) -> Unit) {
        if (!deviceSecure(activity)) return result(false)
        authenticating = true
        val onResult = { ok: Boolean ->
            authenticating = false
            if (ok) stoppedAt = null
            result(ok)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val builder = BiometricPrompt.Builder(activity).setTitle(title)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            } else {
                @Suppress("DEPRECATION")
                builder.setDeviceCredentialAllowed(true)
            }
            builder.build().authenticate(CancellationSignal(), activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) = done(true)
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) = done(false)
                private fun done(ok: Boolean) {
                    if (ok) unlocked = true
                    onResult(ok)
                }
            })
        } else {
            @Suppress("DEPRECATION")
            val intent = activity.getSystemService(KeyguardManager::class.java)?.createConfirmDeviceCredentialIntent(title, null)
            val confirm = legacyConfirm
            if (intent == null || confirm == null) return onResult(false)
            confirm(intent) { ok -> if (ok) unlocked = true; onResult(ok) }
        }
    }

    /** Kilit açıksa [action]'dan önce doğrulama ister; kapalıysa doğrudan çalıştırır. */
    fun guard(ctx: Context, title: String = tr("Kimliğinizi doğrulayın"), action: () -> Unit) {
        val activity = ctx.findActivity()
        if (activity == null || !enabled(ctx)) return action()
        authenticate(activity, title) { ok -> if (ok) action() }
    }

    private fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
