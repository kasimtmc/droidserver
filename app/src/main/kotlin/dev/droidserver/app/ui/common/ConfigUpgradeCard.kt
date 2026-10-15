package dev.droidserver.app.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.droidserver.app.AppGraph
import dev.droidserver.app.data.ConfigUpgrade
import dev.droidserver.app.ui.wizard.WizardViewModel
import dev.droidserver.core.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Etkin konfigürasyon dosyası eski biçimdeyse yükseltme önerisi. Yükseltme kendiliğinden yapılmaz: kullanıcı
 * "Sürümü yükselt" der (dosya eski uygulama sürümleriyle uyumlu kalsın isteyebilir). [dismissible] ise
 * "Şimdi değil" ile bu sürüm için bir daha sorulmaz; Sunucu ekranındaki düğme her zaman durur.
 */
@Composable
fun ConfigUpgradeCard(g: AppGraph, vm: WizardViewModel, dismissible: Boolean) {
    val scope = rememberCoroutineScope()
    var upgrade by remember { mutableStateOf(runCatching { g.configRepo.pendingUpgrade() }.getOrNull()) }
    var dismissed by remember { mutableIntStateOf(g.settings.dismissedConfigUpgrade) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val u: ConfigUpgrade = upgrade ?: return
    if (dismissible && dismissed == u.to) return

    SectionCard(if (dismissible) tr("Konfigürasyon sürümü yükseltilsin mi?") else tr("Konfigürasyon biçimi: v{0}", u.from)) {
        Text(
            tr("server.json v{0} biçiminde kayıtlı; bu uygulama v{1} kullanıyor. Yükseltmeden de çalışır ve dosya " +
                "eski uygulama sürümleriyle uyumlu kalır. Yükseltince önceki hali Geçmiş'e yedeklenir. Değişiklikler:", u.from, u.to),
            style = MaterialTheme.typography.bodySmall,
        )
        u.changes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        Row {
            Button(enabled = !busy, onClick = {
                scope.launch {
                    busy = true
                    error = null
                    runCatching { withContext(Dispatchers.IO) { g.configRepo.upgradeFile() } }
                        .onSuccess { vm.onFileUpgraded(it); upgrade = null }
                        .onFailure { error = tr("Yükseltilemedi: {0}", it.message) }
                    busy = false
                }
            }) { Text(if (busy) tr("Yükseltiliyor…") else tr("Sürümü yükselt")) }
            if (dismissible) {
                TextButton(onClick = { g.settings.dismissedConfigUpgrade = u.to; dismissed = u.to }) { Text(tr("Şimdi değil")) }
            }
        }
        ErrorText(error)
    }
}
