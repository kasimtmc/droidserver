package com.plyg.droidserver.app.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Process

/**
 * Uygulamayı yeniden açar (dil değişikliği: sabit metinler süreç başlarken çevrilir). Ayrı süreçte çalışır: ana süreç
 * kapandıktan sonra ana ekranı yeniden başlatır, sonra kendini kapatır.
 */
class RestartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pid = intent.getIntExtra(EXTRA_PID, -1)
        if (pid > 0 && pid != Process.myPid()) Process.killProcess(pid)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
        Runtime.getRuntime().exit(0)
    }

    companion object {
        private const val EXTRA_PID = "pid"

        /** Ana süreci kapatıp uygulamayı yeniden açar; çalışan sunucu önceden durdurulmuş olmalı. */
        fun restart(activity: Activity) {
            activity.startActivity(Intent(activity, RestartActivity::class.java).putExtra(EXTRA_PID, Process.myPid()))
            activity.finishAffinity()
            Runtime.getRuntime().exit(0)
        }
    }
}
