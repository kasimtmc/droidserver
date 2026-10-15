package dev.droidserver.app.ui.config

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import dev.droidserver.app.ui.common.EmptyState
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.droidserver.app.AppGraph
import dev.droidserver.app.service.ServerService
import dev.droidserver.app.ui.common.ErrorText
import dev.droidserver.app.ui.common.ScreenScaffold
import dev.droidserver.app.ui.common.SectionCard
import dev.droidserver.app.ui.common.WarningText
import dev.droidserver.core.backup.BackupArchive
import dev.droidserver.core.backup.BackupInfo
import dev.droidserver.core.backup.RemoteEntry
import dev.droidserver.core.backup.BackupKind
import dev.droidserver.core.i18n.tr
import dev.droidserver.core.server.EngineState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Yedekler: şimdi yedekle, listedeki yedeği dışa aktar / geri yükle / sil, dosyadan geri yükle ve yedeklerin otomatik
 * kopyalanacağı klasör. Zamanlanmış yedek ayarı Sihirbaz → Veritabanı'nda.
 */
@Composable
fun BackupsScreen(g: AppGraph, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val engineState by g.engine.state.collectAsState()
    val running = engineState is EngineState.Running
    var list by remember { mutableStateOf<List<BackupInfo>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf<File?>(null) }
    var deleting by remember { mutableStateOf<BackupInfo?>(null) }
    var restoring by remember { mutableStateOf<Pair<File, BackupArchive.Manifest>?>(null) }
    var tree by remember { mutableStateOf(g.settings.backupTreeUri) }
    val schedule = remember(engineState) { runCatching { g.configRepo.loadActiveOrNull()?.backup }.getOrNull() }
    val remote = remember(engineState) { g.backups.remoteConfig() }
    var remoteList by remember { mutableStateOf<List<RemoteEntry>?>(null) }
    var remoteDeleting by remember { mutableStateOf<RemoteEntry?>(null) }

    LaunchedEffect(refresh) { list = withContext(Dispatchers.IO) { g.engine.backups?.list().orEmpty() } }

    fun work(done: String? = null, block: suspend () -> Unit) {
        scope.launch {
            busy = true
            error = null
            message = null
            runCatching { block() }.onSuccess { message = done }.onFailure { error = it.message ?: it.toString() }
            busy = false
            refresh++
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val file = exporting
        exporting = null
        if (uri != null && file != null) work(tr("Dışa aktarıldı")) { withContext(Dispatchers.IO) { g.backups.copyToDocument(file, uri) } }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) work { restoring = withContext(Dispatchers.IO) { g.backups.prepareRestore(uri) } }
    }
    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            g.settings.backupTreeUri = uri.toString()
            tree = uri.toString()
        }
    }

    ScreenScaffold(tr("Yedekler"), onBack = onBack) { modifier ->
        LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                SectionCard(tr("Yedek")) {
                    Text(
                        tr("Yedek tek bir zip'tir: veritabanı (kayıt kayıt; SQLite yedeği PostgreSQL'e de yüklenir), konfigürasyon " +
                            "(sırların değerleri hariç), yüklenen dosyalar, script'ler ve statik site."),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        if (schedule?.enabled == true) tr("Zamanlanmış: {0} (son {1} yedek tutulur)", schedule.schedule, schedule.keep)
                        else tr("Zamanlanmış yedek kapalı (Sihirbaz → Veritabanı)."),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (!running) WarningText(tr("Yedek almak için sunucu çalışıyor olmalı."))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = running && !busy, onClick = {
                            work(tr("Yedek alındı")) { withContext(Dispatchers.IO) { g.backups.backup(BackupKind.MANUAL) } }
                        }) { Text(if (busy) tr("Çalışıyor…") else tr("Şimdi yedekle")) }
                        OutlinedButton(enabled = !busy, onClick = { restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream")) }) {
                            Text(tr("Dosyadan geri yükle"))
                        }
                    }
                    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    ErrorText(error)
                }
            }
            item {
                SectionCard(tr("Otomatik kopya klasörü")) {
                    Text(
                        tree?.let { tr("Her yedeğin bir kopyası seçtiğiniz klasöre de yazılır: {0}", it.toUri().lastPathSegment) }
                            ?: tr("Yedekler yalnızca uygulamanın içinde duruyor; uygulama silinirse onlar da gider. Bir klasör seçin " +
                            "(ör. Google Drive veya SD kart) — her yedeğin kopyası oraya da yazılır."),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row {
                        TextButton(onClick = { treeLauncher.launch(null) }) { Text(if (tree == null) tr("Klasör seç") else tr("Değiştir")) }
                        if (tree != null) TextButton(onClick = { g.settings.backupTreeUri = null; tree = null }) { Text(tr("Kaldır")) }
                    }
                }
            }
            item {
                SectionCard(tr("Bulut yedeği")) {
                    if (remote == null) {
                        Text(
                            tr("Kapalı. Yedekleri şifreleyip WebDAV (Nextcloud…) veya S3 uyumlu depoya (Backblaze B2, R2, MinIO) " +
                                "yüklemek için: Sihirbaz → Veritabanı → Bulut yedeği."),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        Text(
                            tr("{0}: {1}{2}. Zamanlanmış", remote.kind.title, remote.url, remote.bucket?.let { " / $it" } ?: "") +
                                (if (remote.uploadManual) tr(" ve elle alınan") else "") + tr(" yedekler şifrelenip yüklenir; son {0} tanesi tutulur.", remote.keep),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(enabled = !busy, onClick = {
                            work { remoteList = withContext(Dispatchers.IO) { g.backups.remote()?.list() } }
                        }) { Text(if (remoteList == null) tr("Buluttaki yedekleri listele") else tr("Yenile")) }
                        remoteList?.let { entries ->
                            if (entries.isEmpty()) Text(tr("Depoda yedek yok."), style = MaterialTheme.typography.bodySmall)
                            entries.forEach { e ->
                                Text(e.name, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                                Text(formatBytes(e.size) + (e.modified?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
                                Row {
                                    TextButton(enabled = !busy, onClick = {
                                        work { restoring = withContext(Dispatchers.IO) { g.backups.prepareRemoteRestore(e.name) } }
                                    }) { Text(tr("İndir ve geri yükle")) }
                                    TextButton(onClick = { remoteDeleting = e }) { Text(tr("Sil"), color = MaterialTheme.colorScheme.error) }
                                }
                            }
                        }
                    }
                }
            }
            if (list.isEmpty()) item {
                EmptyState(Icons.Default.Backup, tr("Henüz yedek yok"), tr("Şimdi bir yedek alın veya zamanlanmış yedeği sihirbazın Veritabanı adımından açın."))
            }
            items(list, key = { it.name }) { b ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(b.name, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        Text("${b.kind.title} · ${formatBytes(b.size)}", style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick = { exporting = b.file; exportLauncher.launch(b.name) }) { Text(tr("Dışa aktar")) }
                            TextButton(enabled = !busy, onClick = {
                                work { restoring = withContext(Dispatchers.IO) { b.file to BackupArchive.inspect(b.file).first } }
                            }) { Text(tr("Geri yükle")) }
                            if (remote != null) TextButton(enabled = !busy, onClick = {
                                work(tr("Buluta yüklendi")) { withContext(Dispatchers.IO) { g.backups.upload(b) } }
                            }) { Text(tr("Buluta yükle")) }
                            TextButton(onClick = { deleting = b }) { Text(tr("Sil"), color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
    }

    restoring?.let { (file, manifest) ->
        AlertDialog(
            onDismissRequest = { restoring = null },
            title = { Text(tr("Bu yedek geri yüklensin mi?")) },
            text = {
                Text(
                    tr("Yedek: {0}, {1} kayıt ({2}).\n\n" +
                        "Mevcut kayıtlar, dosyalar, script'ler ve site yedektekilerle değişir; konfigürasyon yedektekine döner ama " +
                        "veritabanı ayarı bu telefonunki kalır. Önce mevcut durumun yedeği alınır. Sunucu kısa süre durur.", manifest.createdAt, manifest.tables.values.sum(), manifest.tables.entries.joinToString { "${it.key}: ${it.value}" }),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    restoring = null
                    work(tr("Geri yüklendi; sunucu yeniden başlatılıyor")) {
                        // Kendi seçtiğimiz kopya mı, listedeki yedek mi: listedekini silmemek için kopyasıyla çalışılır.
                        withContext(Dispatchers.IO) {
                            val source = if (file.parentFile == g.engine.backups?.dir) {
                                File(ctx.cacheDir, "geri-yukleme.zip").also { file.copyTo(it, overwrite = true) }
                            } else file
                            g.deployLock.withLock { g.backups.restore(source, g.orchestrator) }
                        }
                        ServerService.deploy(ctx)
                    }
                }) { Text(tr("Geri yükle"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { restoring = null }) { Text(tr("Vazgeç")) } },
        )
    }

    remoteDeleting?.let { e ->
        AlertDialog(
            onDismissRequest = { remoteDeleting = null },
            title = { Text(tr("Buluttaki yedek silinsin mi?")) },
            text = { Text(e.name) },
            confirmButton = {
                TextButton(onClick = {
                    remoteDeleting = null
                    work(tr("Silindi")) { withContext(Dispatchers.IO) { g.backups.remote()?.delete(e.name); remoteList = g.backups.remote()?.list() } }
                }) { Text(tr("Sil"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { remoteDeleting = null }) { Text(tr("Vazgeç")) } },
        )
    }

    deleting?.let { b ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(tr("Yedek silinsin mi?")) },
            text = { Text(b.name) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    work(tr("Silindi")) { withContext(Dispatchers.IO) { b.file.delete() } }
                }) { Text(tr("Sil"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(tr("Vazgeç")) } },
        )
    }
}
