package dev.droidserver.app.ui.config

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import dev.droidserver.app.AppGraph
import dev.droidserver.app.ui.common.ErrorText
import dev.droidserver.app.ui.common.SectionCard
import dev.droidserver.app.ui.common.WarningText
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.files.StaticSite
import dev.droidserver.core.files.StorageUsage
import dev.droidserver.app.data.NetUtil
import dev.droidserver.core.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Statik sitenin dosyaları: zip'ten yükleme (eskisinin yerine geçer) ve kaldırma. Yeniden deploy gerekmez. */
@Composable
internal fun SiteCard(g: AppGraph, config: ServerConfig) {
    val dir = g.engine.siteDir ?: return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var usage by remember { mutableStateOf<StorageUsage?>(null) }
    var hasIndex by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(refresh) {
        withContext(Dispatchers.IO) {
            usage = StorageUsage.of(dir)
            hasIndex = java.io.File(dir, "index.html").isFile
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            error = null
            message = null
            runCatching {
                withContext(Dispatchers.IO) {
                    val input = ctx.contentResolver.openInputStream(uri) ?: error(tr("Dosya açılamadı"))
                    input.use { StaticSite.install(it, dir) }
                }
            }.onSuccess { r ->
                message = tr("{0} dosya yüklendi ({1})", r.files, formatBytes(r.bytes)) +
                    (r.strippedFolder?.let { tr(", '{0}' klasörünün içi köke alındı", it) } ?: "") +
                    if (r.hasIndex) "" else tr(". Kökte index.html yok: ana sayfa açılmaz.")
            }.onFailure { error = tr("Yüklenemedi: {0}. Önceki site yerinde.", it.message) }
            busy = false
            refresh++
        }
    }

    SectionCard(tr("Statik site")) {
        val host = remember { NetUtil.localIpv4().firstOrNull() ?: "127.0.0.1" }
        Text("http://$host:${config.server.port}${config.static.path}", fontFamily = FontFamily.Monospace)
        usage?.let {
            Text(
                if (it.files == 0) tr("Henüz dosya yok.") else tr("{0} dosya, {1}", it.files, formatBytes(it.bytes)),
                style = MaterialTheme.typography.bodySmall,
            )
            if (it.files > 0 && !hasIndex) WarningText(tr("Kökte index.html yok; ana sayfa bulunamaz."))
        }
        Text(
            tr("Sitenizin dosyalarını (index.html ve diğerleri) zip olarak seçin. Yükleme tamamlanınca eskisinin yerine geçer; " +
                "sunucuyu yeniden başlatmak gerekmez. Tek bir klasörü sıkıştırdıysanız o klasörün içi köke alınır."),
            style = MaterialTheme.typography.bodySmall,
        )
        Row {
            Button(enabled = !busy, onClick = { picker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }) {
                Text(if (busy) tr("Yükleniyor…") else tr("Zip yükle"))
            }
            TextButton(enabled = !busy && (usage?.files ?: 0) > 0, onClick = { confirmClear = true }) { Text(tr("Siteyi kaldır")) }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        ErrorText(error)
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(tr("Site dosyaları silinsin mi?")) },
            text = { Text(tr("Yüklenen tüm site dosyaları silinir; ayar açık kalır ve yeni bir zip yükleyebilirsiniz.")) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch {
                        withContext(Dispatchers.IO) { dir.deleteRecursively() }
                        message = tr("Site dosyaları silindi.")
                        refresh++
                    }
                }) { Text(tr("Sil"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(tr("Vazgeç")) } },
        )
    }
}

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
