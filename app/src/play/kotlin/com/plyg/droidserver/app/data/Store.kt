package com.plyg.droidserver.app.data

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.core.net.toUri
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import com.google.android.play.core.review.ReviewManagerFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@Volatile private var instance: StoreServices? = null

/** Uygulama boyunca tek örnek (indirme durumu profiller arasında da aynı). */
fun storeServices(context: Context): StoreServices? =
    instance ?: synchronized(PlayStore::class) { instance ?: PlayStore(context.applicationContext).also { instance = it } }

/** Play In-App Updates (esnek: kullanıcı kullanmaya devam ederken iner) ve Play In-App Review. */
private class PlayStore(private val context: Context) : StoreServices {
    private val updates = AppUpdateManagerFactory.create(context)
    private val reviews = ReviewManagerFactory.create(context)
    private val state = MutableStateFlow<StoreUpdate>(StoreUpdate.None)
    override val update: StateFlow<StoreUpdate> = state
    private var info: AppUpdateInfo? = null

    init {
        updates.registerListener(InstallStateUpdatedListener { s ->
            state.value = when (s.installStatus()) {
                InstallStatus.DOWNLOADING -> {
                    val total = s.totalBytesToDownload()
                    StoreUpdate.Downloading(if (total > 0) (s.bytesDownloaded() * 100 / total).toInt() else 0)
                }
                InstallStatus.DOWNLOADED -> StoreUpdate.Downloaded
                // Vazgeçildi ya da olmadı: yeniden denenebilsin.
                InstallStatus.CANCELED, InstallStatus.FAILED -> info?.let { StoreUpdate.Available(it.availableVersionCode()) } ?: StoreUpdate.None
                else -> state.value
            }
        })
    }

    override fun checkForUpdate() {
        updates.appUpdateInfo.addOnSuccessListener { i ->
            info = i
            state.value = when {
                i.installStatus() == InstallStatus.DOWNLOADED -> StoreUpdate.Downloaded
                i.installStatus() == InstallStatus.DOWNLOADING -> StoreUpdate.Downloading(0)
                i.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE && i.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) ->
                    StoreUpdate.Available(i.availableVersionCode())
                else -> StoreUpdate.None
            }
        }
    }

    override fun startUpdate(launcher: ActivityResultLauncher<IntentSenderRequest>) {
        val i = info ?: return
        updates.startUpdateFlowForResult(i, launcher, AppUpdateOptions.defaultOptions(AppUpdateType.FLEXIBLE))
    }

    override fun completeUpdate() {
        updates.completeUpdate()
    }

    override fun requestReview(activity: Activity) {
        reviews.requestReviewFlow().addOnSuccessListener { flow -> reviews.launchReviewFlow(activity, flow) }
    }

    override fun storePage(): Intent =
        Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=${context.packageName}".toUri())
            .setPackage("com.android.vending")
}
