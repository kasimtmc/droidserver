package com.plyg.droidserver.app.data

import android.app.Activity
import android.content.Context
import com.google.android.play.core.splitinstall.SplitInstallManagerFactory
import com.google.android.play.core.splitinstall.SplitInstallRequest
import com.google.android.play.core.splitinstall.SplitInstallStateUpdatedListener
import com.google.android.play.core.splitinstall.model.SplitInstallErrorCode
import com.google.android.play.core.splitinstall.model.SplitInstallSessionStatus
import com.plyg.droidserver.core.components.ComponentManifest
import com.plyg.droidserver.core.config.ConfigJson
import com.plyg.droidserver.core.i18n.tr
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

fun componentModules(context: Context): ComponentModules? = PlayComponentModules(context.applicationContext)

/** Bileşen modüllerini Play Feature Delivery ile kurar; programlar uygulamanın yerel kitaplık klasörüne açılır. */
private class PlayComponentModules(private val context: Context) : ComponentModules {
    private val manager = SplitInstallManagerFactory.create(context)

    override fun catalog(): ComponentManifest =
        ConfigJson.decodeFromString(ComponentManifest.serializer(), context.assets.open(CATALOG).use { it.readBytes().decodeToString() })

    // Modül kurulunca uygulamanın kod klasörü değişir; süreçteki eski ApplicationInfo değil, güncel olanı sorulur.
    override fun libDir() = File(context.packageManager.getApplicationInfo(context.packageName, 0).nativeLibraryDir)

    override suspend fun install(activity: Activity?, id: String, progress: (String) -> Unit) {
        if (id in manager.installedModules) return
        suspendCancellableCoroutine { cont ->
            lateinit var listener: SplitInstallStateUpdatedListener
            fun finish(error: Throwable?) {
                manager.unregisterListener(listener)
                if (!cont.isActive) return
                if (error == null) cont.resume(Unit) else cont.resumeWithException(error)
            }
            listener = SplitInstallStateUpdatedListener { s ->
                if (id !in s.moduleNames()) return@SplitInstallStateUpdatedListener
                when (s.status()) {
                    SplitInstallSessionStatus.DOWNLOADING -> {
                        val total = s.totalBytesToDownload()
                        progress(if (total > 0) tr("Play'den indiriliyor… %{0}", s.bytesDownloaded() * 100 / total) else tr("Play'den indiriliyor…"))
                    }
                    SplitInstallSessionStatus.INSTALLING -> progress(tr("Kuruluyor…"))
                    // Büyük indirmelerde Play kullanıcıya sorar.
                    SplitInstallSessionStatus.REQUIRES_USER_CONFIRMATION ->
                        if (activity != null) @Suppress("DEPRECATION") manager.startConfirmationDialogForResult(s, activity, 0)
                        else finish(IllegalStateException(tr("Play indirme için onay istiyor; Bileşenler ekranından kurun")))
                    SplitInstallSessionStatus.INSTALLED -> finish(null)
                    SplitInstallSessionStatus.CANCELED -> finish(IllegalStateException(tr("Kurulum iptal edildi")))
                    SplitInstallSessionStatus.FAILED -> finish(IllegalStateException(message(s.errorCode())))
                    SplitInstallSessionStatus.PENDING, SplitInstallSessionStatus.DOWNLOADED, SplitInstallSessionStatus.CANCELING,
                    SplitInstallSessionStatus.UNKNOWN -> Unit
                }
            }
            manager.registerListener(listener)
            manager.startInstall(SplitInstallRequest.newBuilder().addModule(id).build())
                .addOnFailureListener { e -> finish(IllegalStateException(message((e as? com.google.android.play.core.splitinstall.SplitInstallException)?.errorCode ?: 0, e.message))) }
            cont.invokeOnCancellation { manager.unregisterListener(listener) }
        }
    }

    override fun uninstall(id: String) {
        manager.deferredUninstall(listOf(id))
    }

    private fun message(code: Int, detail: String? = null) = when (code) {
        SplitInstallErrorCode.APP_NOT_OWNED -> tr("Uygulama Play'den kurulmamış; bileşen modülleri yalnızca Play'den kurulan uygulamaya gelir")
        SplitInstallErrorCode.NETWORK_ERROR -> tr("İnternet bağlantısı yok; bileşen Play'den indirilemedi")
        SplitInstallErrorCode.INSUFFICIENT_STORAGE -> tr("Yer yetersiz; bileşen kurulamadı")
        else -> tr("Bileşen Play'den kurulamadı (hata {0})", if (detail != null) "$code: $detail" else code)
    }

    private companion object {
        const val CATALOG = "play-components.json"
    }
}
