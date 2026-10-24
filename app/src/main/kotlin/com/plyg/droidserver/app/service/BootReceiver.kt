package com.plyg.droidserver.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.plyg.droidserver.app.graph

/**
 * Cihaz açıldığında veya uygulama güncellendiğinde, ayar açıksa sunucuyu başlatır. Uygulama içinden kurulan bir
 * güncellemeden sonra, güncelleme öncesinde çalışıyorsa sunucu ayardan bağımsız olarak yeniden başlatılır.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val settings = context.graph.settings
        val replaced = intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && !replaced) return
        val restart = replaced && settings.restartAfterUpdate
        if (replaced) settings.restartAfterUpdate = false
        if (settings.autoStart || restart) ServerService.start(context)
    }
}
