package dev.droidserver.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.droidserver.app.data.AutomationBridge
import dev.droidserver.app.graph

/**
 * Otomasyon uygulamalarından gelen komutlar (açık yayın: paket `dev.droidserver.app`, sınıf bu sınıf). Köprü kapalıysa
 * veya anahtar yanlışsa hiçbir şey yapılmaz; kabul edilen ve reddedilen her komut Loglar'a yazılır (anahtar yazılmaz).
 */
class TaskerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val g = context.graph
        val action = intent.action ?: return
        val name = action.substringAfterLast('.')
        if (!g.automation.accepts(intent.getStringExtra(AutomationBridge.EXTRA_TOKEN))) {
            g.logs.add("Otomasyon komutu reddedildi ($name): köprü kapalı veya anahtar hatalı")
            return
        }
        val hasConfig = g.configRepo.hasConfig()
        when (action) {
            AutomationBridge.ACTION_START -> if (hasConfig) ServerService.start(context)
            AutomationBridge.ACTION_DEPLOY -> if (hasConfig) ServerService.deploy(context)
            AutomationBridge.ACTION_STOP -> ServerService.stop(context)
            else -> return
        }
        g.logs.add(if (hasConfig || action == AutomationBridge.ACTION_STOP) "Otomasyon komutu: $name" else "Otomasyon komutu ($name): önce sihirbazı tamamlayın")
    }
}
