package com.plyg.droidserver.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.plyg.droidserver.app.graph
import com.plyg.droidserver.app.service.ServerService
import com.plyg.droidserver.app.ui.theme.DroidServerTheme
import com.plyg.droidserver.core.i18n.tr
import com.plyg.droidserver.core.server.EngineState

class MainActivity : ComponentActivity() {
    private val locked = mutableStateOf(false)
    private var pendingConfirm: ((Boolean) -> Unit)? = null
    private val confirmLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        pendingConfirm?.invoke(r.resultCode == RESULT_OK)
        pendingConfirm = null
    }

    /** Bildirim izni (sonuç beklenmez: verilmezse sorun giderici "bildirimler kapalı" der). */
    private val notificationLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val g = graph
        // Dil değişikliği için yeniden açıldıysa, öncesinde çalışan sunucu yeniden başlatılır.
        if (savedInstanceState == null && g.settings.startServerOnLaunch) {
            g.settings.startServerOnLaunch = false
            ServerService.start(this)
        }
        AppLock.legacyConfirm = { intent, done -> pendingConfirm = done; confirmLauncher.launch(intent) }
        // API 33+ hedefleyen sürüm (Play) bildirim iznini kendisi ister; eski hedefte Android bunu ilk bildirimde sorar.
        if (savedInstanceState == null && Build.VERSION.SDK_INT >= 33 && applicationInfo.targetSdkVersion >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            DroidServerTheme { if (locked.value) LockScreen(::unlock) else AppNavigation(g) }
        }
    }

    override fun onStart() {
        super.onStart()
        AppLock.applySecureFlag(this)
        if (AppLock.shouldLock(this)) {
            AppLock.lock()
            locked.value = true
            unlock()
        }
    }

    override fun onResume() {
        super.onResume()
        val g = graph
        val store = g.store ?: return
        store.checkForUpdate()
        // Değerlendirme bir kez, uygulama bir haftadır kuruluyken ve sunucu çalışırken istenir (Play penceresi göstermeyebilir).
        val installedAt = packageManager.getPackageInfo(packageName, 0).firstInstallTime
        if (g.settings.reviewAskedAt == 0L && System.currentTimeMillis() - installedAt > REVIEW_AFTER_MS &&
            g.engine.state.value is EngineState.Running
        ) {
            g.settings.reviewAskedAt = System.currentTimeMillis()
            store.requestReview(this)
        }
    }

    override fun onStop() {
        AppLock.onStop()
        super.onStop()
    }

    private fun unlock() = AppLock.authenticate(this, tr("DroidServer'ın kilidini açın")) { ok -> if (ok) locked.value = false }

    private companion object {
        const val REVIEW_AFTER_MS = 7L * 24 * 60 * 60 * 1000
    }
}

@Composable
private fun LockScreen(onUnlock: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Default.Lock, contentDescription = null)
            Text(tr("DroidServer kilitli"), style = MaterialTheme.typography.titleLarge)
            Text(tr("Sunucu arka planda çalışmaya devam eder."), style = MaterialTheme.typography.bodySmall)
            Button(onClick = onUnlock) { Text(tr("Kilidi aç")) }
        }
    }
}
