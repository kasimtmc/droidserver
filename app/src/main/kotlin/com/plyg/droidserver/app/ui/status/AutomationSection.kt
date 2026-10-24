package com.plyg.droidserver.app.ui.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.plyg.droidserver.app.AppGraph
import com.plyg.droidserver.app.data.AutomationBridge
import com.plyg.droidserver.app.service.TaskerReceiver
import com.plyg.droidserver.app.ui.common.SoftCard
import com.plyg.droidserver.app.ui.common.copySecret
import com.plyg.droidserver.app.ui.common.copyToClipboard
import com.plyg.droidserver.core.i18n.tr

/**
 * Ayarlar → Tasker ve otomasyon: köprüyü aç/kapat, komut anahtarı (kopyala/yenile), olayların gideceği uygulamalar ve
 * Tasker/MacroDroid'de nasıl kurulacağı.
 */
@Composable
internal fun AutomationSection(g: AppGraph) {
    val ctx = LocalContext.current
    val bridge = g.automation
    var enabled by remember { mutableStateOf(bridge.enabled) }
    var packages by remember { mutableStateOf(bridge.packages) }
    var custom by remember { mutableStateOf("") }
    var confirmRenew by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf<String?>(null) }

    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Otomasyon uygulamalarından kontrol"), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Switch(enabled, { on -> enabled = on; bridge.enabled = on; if (on) bridge.token() })
        }
        Text(
            tr("Tasker, MacroDroid veya Automate sunucuyu başlatabilir, durdurabilir ve yeniden deploy edebilir; sunucu olaylarını (başladı, durdu, başlatılamadı, sağlık uyarısı) seçtiğiniz uygulamalara yayın olarak gönderir."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (enabled) {
            Text(tr("Komut anahtarı"), style = MaterialTheme.typography.titleSmall)
            Text(
                tr("Her komutta token adlı ek değer olarak gönderilir; anahtarı bilmeyen uygulama sunucuyu yönetemez."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { copySecret(ctx, tr("otomasyon anahtarı"), bridge.token()) }) { Text(tr("Anahtarı kopyala")) }
                TextButton(onClick = { confirmRenew = true }) { Text(tr("Yenile")) }
            }

            Text(tr("Olayların gideceği uygulamalar"), style = MaterialTheme.typography.titleSmall)
            val installed = remember { AutomationBridge.KNOWN_APPS.keys.filter { runCatching { ctx.packageManager.getPackageInfo(it, 0) }.isSuccess }.toSet() }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (AutomationBridge.KNOWN_APPS.keys + packages).distinct().forEach { pkg ->
                    val name = AutomationBridge.KNOWN_APPS[pkg] ?: pkg
                    val label = if (pkg in AutomationBridge.KNOWN_APPS && pkg !in installed) tr("{0} (kurulu değil)", name) else name
                    FilterChip(pkg in packages, {
                        packages = if (pkg in packages) packages - pkg else packages + pkg
                        bridge.packages = packages
                    }, label = { Text(label) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    custom, { custom = it.trim() }, singleLine = true, modifier = Modifier.weight(1f),
                    label = { Text(tr("Başka uygulama (paket adı)")) }, placeholder = { Text("com.ornek.uygulama") },
                )
                TextButton(enabled = Regex("^[a-zA-Z][\\w]*(\\.[a-zA-Z][\\w]*)+$").matches(custom), onClick = {
                    packages = packages + custom; bridge.packages = packages; custom = ""
                }) { Text(tr("Ekle")) }
            }
            if (packages.isEmpty()) Text(tr("Uygulama seçilmezse olay gönderilmez; komutlar yine çalışır."), style = MaterialTheme.typography.bodySmall)

            Text(tr("Tasker'da kurulum"), style = MaterialTheme.typography.titleSmall)
            Text(
                tr("Komut: Görev → Sistem → Send Intent. Action aşağıdakilerden biri, Extra token:<anahtar>, Package {0}, Class {1}, Target Broadcast Receiver. MacroDroid'de: Eylem → Uygulamalar → Send Intent (aynı alanlar).", ctx.packageName, TaskerReceiver::class.java.name),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                tr("Olay: Profil → Olay → Sistem → Intent Received, Action aşağıdaki olaylardan biri. Değişkenler: %profile, %url, %port, %reason, %title, %message, %kind, %resolved, %critical."),
                style = MaterialTheme.typography.bodySmall,
            )
            listOf(
                AutomationBridge.ACTION_START, AutomationBridge.ACTION_STOP, AutomationBridge.ACTION_DEPLOY,
                AutomationBridge.EVENT_STARTED, AutomationBridge.EVENT_STOPPED, AutomationBridge.EVENT_FAILED, AutomationBridge.EVENT_ALERT,
            ).forEach { a ->
                Text(a, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
            }
            OutlinedButton(onClick = { copyToClipboard(ctx, "class", TaskerReceiver::class.java.name); copied = TaskerReceiver::class.java.name }) {
                Text(tr("Sınıf adını kopyala"))
            }
            copied?.let { Text(tr("Kopyalandı: {0}", it), style = MaterialTheme.typography.bodySmall) }
        }
    }

    if (confirmRenew) {
        AlertDialog(
            onDismissRequest = { confirmRenew = false },
            title = { Text(tr("Anahtar yenilensin mi?")) },
            text = { Text(tr("Eski anahtarla gönderilen komutlar artık çalışmaz; Tasker görevlerindeki token değerini güncellemeniz gerekir.")) },
            confirmButton = { TextButton(onClick = { confirmRenew = false; bridge.regenerate() }) { Text(tr("Yenile")) } },
            dismissButton = { TextButton(onClick = { confirmRenew = false }) { Text(tr("Vazgeç")) } },
        )
    }
}
