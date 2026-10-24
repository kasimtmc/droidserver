package com.plyg.droidserver.app.ui.config

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.plyg.droidserver.app.AppGraph
import com.plyg.droidserver.app.ui.common.ErrorText
import com.plyg.droidserver.app.ui.common.RadioRow
import com.plyg.droidserver.app.ui.common.ScreenScaffold
import com.plyg.droidserver.app.ui.common.SectionCard
import com.plyg.droidserver.app.ui.common.WarningText
import com.plyg.droidserver.core.config.FieldType
import com.plyg.droidserver.core.data.Csv
import com.plyg.droidserver.core.data.DataTransfer
import com.plyg.droidserver.core.data.ImportMode
import com.plyg.droidserver.core.data.ImportResult
import com.plyg.droidserver.core.data.TransferFormat
import com.plyg.droidserver.core.i18n.tr
import com.plyg.droidserver.core.server.EngineState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Dışa aktarma biçimi: CSV (virgül), Excel için CSV (noktalı virgül, Türkçe Excel) veya JSON. */
private enum class ExportKind(val title: String, val format: TransferFormat, val delimiter: Char) {
    CSV("CSV", TransferFormat.CSV, ','),
    EXCEL(tr("CSV, Excel (noktalı virgül)"), TransferFormat.CSV, ';'),
    JSON("JSON", TransferFormat.JSON, ','),
}

/** Bir koleksiyonu dosyaya aktarma ve dosyadan içe alma (önizleme, sütun eşleme, hepsi ya da hiçbiri). */
@Composable
fun TransferScreen(g: AppGraph, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val engineState by g.engine.state.collectAsState()
    val running = engineState is EngineState.Running
    val config = remember(engineState) { runCatching { g.configRepo.loadActiveOrNull() }.getOrNull() }
    val collections = config?.collections.orEmpty()
    var selected by remember(collections) { mutableStateOf(collections.firstOrNull()?.name) }
    var exportKind by remember { mutableStateOf(ExportKind.CSV) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    // İçe aktarma durumu: okunan dosya, eşleme ve son denetim sonucu.
    var importText by remember { mutableStateOf<String?>(null) }
    var importName by remember { mutableStateOf("") }
    var importFormat by remember { mutableStateOf(TransferFormat.CSV) }
    var mapping by remember { mutableStateOf<Map<String, String?>?>(null) }
    var mode by remember { mutableStateOf(ImportMode.INSERT) }
    var preview by remember { mutableStateOf<ImportResult?>(null) }

    fun transfer(): DataTransfer = g.engine.dataTransfer() ?: throw IllegalStateException(tr("Sunucu çalışmıyor"))
    fun work(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            error = null
            message = null
            runCatching { block() }.onFailure { error = it.message ?: it.toString() }
            busy = false
        }
    }
    fun check(name: String, text: String, m: Map<String, String?>?) = work {
        preview = withContext(Dispatchers.IO) { transfer().import(name, importFormat, text, m, mode, dryRun = true) }
        mapping = preview?.mapping
    }

    fun exportTo(uri: Uri) = work {
        val name = selected ?: return@work
        val n = withContext(Dispatchers.IO) {
            val out = ctx.contentResolver.openOutputStream(uri) ?: throw IllegalStateException(tr("Dosya açılamadı"))
            out.bufferedWriter(Charsets.UTF_8).use { transfer().export(name, exportKind.format, it, delimiter = exportKind.delimiter) }
        }
        message = tr("{0} kayıt dışa aktarıldı", n)
    }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> uri?.let(::exportTo) }
    val jsonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let(::exportTo) }
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        work {
            val (text, name) = withContext(Dispatchers.IO) {
                val stream = ctx.contentResolver.openInputStream(uri) ?: throw IllegalStateException(tr("Dosya açılamadı"))
                val bytes = stream.use { input ->
                    val buffer = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(64 * 1024)
                    while (buffer.size() <= DataTransfer.MAX_IMPORT_BYTES) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        buffer.write(chunk, 0, n)
                    }
                    buffer.toByteArray()
                }
                if (bytes.size > DataTransfer.MAX_IMPORT_BYTES) throw IllegalStateException(tr("Dosya çok büyük (en çok {0} MB)", DataTransfer.MAX_IMPORT_BYTES / 1024 / 1024))
                bytes.decodeToString() to (uri.lastPathSegment?.substringAfterLast('/') ?: "dosya")
            }
            importText = text
            importName = name
            importFormat = if (name.endsWith(".json", ignoreCase = true) || text.trimStart(Csv.BOM, ' ', '\n', '\r').startsWith("[")) TransferFormat.JSON else TransferFormat.CSV
            preview = null
            mapping = null
            selected?.let { check(it, text, null) }
        }
    }

    ScreenScaffold(tr("İçe / dışa aktarma"), onBack = onBack) { modifier ->
        Column(modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!running) WarningText(tr("Sunucu çalışmıyor; aktarma için sunucuyu başlatın."))
            if (collections.isEmpty()) {
                Text(tr("Çalışan konfigürasyonda koleksiyon yok."), style = MaterialTheme.typography.bodySmall)
                return@Column
            }
            CollectionPicker(collections.map { it.name }, selected) {
                selected = it
                preview = null
                mapping = null
            }

            SectionCard(tr("Dışa aktar")) {
                Text(
                    tr("Tüm kayıtlar (en çok {0}) dosyaya yazılır. CSV'de formül gibi başlayan metinlerin başına ' eklenir " +
                        "(elektronik tabloda çalışmasınlar diye); içe aktarırken geri alınır.", DataTransfer.MAX_EXPORT),
                    style = MaterialTheme.typography.bodySmall,
                )
                ExportKind.entries.forEach { k -> RadioRow(exportKind == k, k.title) { exportKind = k } }
                Button(enabled = running && !busy, onClick = {
                    val file = "${selected}-${LocalDate.now().toString().replace("-", "")}.${exportKind.format.extension}"
                    if (exportKind.format == TransferFormat.JSON) jsonLauncher.launch(file) else csvLauncher.launch(file)
                }) { Text(tr("Dışa aktar")) }
            }

            SectionCard(tr("İçe aktar")) {
                Text(
                    tr("CSV (virgül veya noktalı virgül, ilk satır başlık) ya da JSON dizisi. Önce denetlenir: hatalı satır varsa hiçbiri " +
                        "yazılmaz. Boş hücre alanı göndermez (yeni kayıtta varsayılan değer). Dosya alanları içe aktarılmaz; içe aktarılan " +
                        "kayıtlar webhook, canlı abonelik ve script olayı üretmez. En çok {0} satır.", DataTransfer.MAX_ROWS),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(enabled = running && !busy, onClick = {
                    openLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/json", "application/octet-stream"))
                }) { Text(tr("Dosya seç")) }
                val text = importText
                val result = preview
                val target = collections.firstOrNull { it.name == selected }
                if (text != null && target != null) {
                    Text("$importName · ${importFormat.name}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    ImportMode.entries.forEach { m ->
                        RadioRow(mode == m, m.title, if (m == ImportMode.UPSERT) tr("Dışa aktarılan dosyayı düzenleyip geri yüklemek için.") else null) {
                            mode = m
                            check(target.name, text, mapping)
                        }
                    }
                    val fields = listOf("id") + target.fields.filter { it.type != FieldType.FILE }.map { it.name }
                    mapping?.let { m ->
                        Text(tr("Sütun eşleme"), style = MaterialTheme.typography.labelLarge)
                        m.forEach { (column, field) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(column, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                FieldPicker(fields, field) { picked -> check(target.name, text, m + (column to picked)) }
                            }
                        }
                    }
                    if (result != null) {
                        Text(tr("{0} satır: {1} yeni, {2} güncellenecek", result.total, result.created, result.updated), style = MaterialTheme.typography.bodyMedium)
                        result.errors.take(10).forEach { e ->
                            ErrorText(tr("Satır {0}: {1}", e.row, e.message) + if (e.details.isEmpty()) "" else " (" + e.details.entries.joinToString { "${it.key}: ${it.value}" } + ")")
                        }
                        if (result.errors.size > 10) ErrorText(tr("… ve {0} hata daha", result.errors.size - 10))
                        result.sample.take(3).forEach { Text(it.toString(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                        Button(enabled = running && !busy && result.errors.isEmpty() && result.total > 0, onClick = {
                            work {
                                val done = withContext(Dispatchers.IO) { transfer().import(target.name, importFormat, text, mapping, mode) }
                                if (done.applied) {
                                    message = tr("İçe aktarıldı: {0} yeni, {1} güncellendi", done.created, done.updated)
                                    importText = null
                                    preview = null
                                } else {
                                    preview = done
                                }
                            }
                        }) { Text(tr("İçe aktar")) }
                    }
                }
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            ErrorText(error)
        }
    }
}

@Composable
private fun CollectionPicker(names: List<String>, selected: String?, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) {
            Text(tr("Koleksiyon: {0}", selected ?: tr("seçin")))
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            names.forEach { n -> DropdownMenuItem(text = { Text(n) }, onClick = { open = false; onPick(n) }) }
        }
    }
}

@Composable
private fun FieldPicker(fields: List<String>, selected: String?, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) {
            Text(selected ?: tr("alma"))
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(tr("Alma")) }, onClick = { open = false; onPick(null) })
            fields.forEach { f -> DropdownMenuItem(text = { Text(f) }, onClick = { open = false; onPick(f) }) }
        }
    }
}
