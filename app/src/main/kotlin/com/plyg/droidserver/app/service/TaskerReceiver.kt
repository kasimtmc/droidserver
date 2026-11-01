package com.plyg.droidserver.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.plyg.droidserver.app.data.AutomationBridge
import com.plyg.droidserver.app.graph
import com.plyg.droidserver.core.i18n.tr

/**
 * Otomasyon uygulamalarından gelen komutlar (açık yayın: paket `com.plyg.droidserver`, sınıf bu sınıf). Köprü kapalıysa
 * veya anahtar yanlışsa hiçbir şey yapılmaz; kabul edilen ve reddedilen her komut Loglar'a yazılır (anahtar yazılmaz).
 */
class TaskerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val g = context.graph
        val action = intent.action ?: return
        val name = action.substringAfterLast('.')
        if (!g.automation.accepts(intent.getStringExtra(AutomationBridge.EXTRA_TOKEN))) {
            g.logs.add(tr("Otomasyon komutu reddedildi ({0}): köprü kapalı veya anahtar hatalı", name))
            return
        }
        val hasConfig = g.configRepo.hasConfig()
        when (action) {
            AutomationBridge.ACTION_START -> if (hasConfig) ServerService.start(context)
            AutomationBridge.ACTION_DEPLOY -> if (hasConfig) ServerService.deploy(context)
            AutomationBridge.ACTION_STOP -> ServerService.stop(context)
            else -> return
        }
        g.logs.add(if (hasConfig || action == AutomationBridge.ACTION_STOP) tr("Otomasyon komutu: {0}", name) else tr("Otomasyon komutu ({0}): önce sihirbazı tamamlayın", name))
    }
}
