package dev.droidserver.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import dev.droidserver.app.graph
import dev.droidserver.core.i18n.tr

/** [PackageInstaller] oturumunun sonucu: onay penceresini açar veya sonucu güncelleyiciye iletir. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val updater = context.graph.updater
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> updater.onInstallResult(true, null)
            PackageInstaller.STATUS_FAILURE_ABORTED -> updater.onInstallResult(false, null) // kullanıcı vazgeçti
            else -> updater.onInstallResult(false, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: tr("bilinmeyen hata"))
        }
    }
}
