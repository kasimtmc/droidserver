package com.plyg.droidserver.app.ui.config

import androidx.compose.material.icons.filled.Key
import com.plyg.droidserver.app.ui.common.EmptyState
import com.plyg.droidserver.app.ui.common.TipCard
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.plyg.droidserver.app.AppGraph
import com.plyg.droidserver.app.ui.AppLock
import com.plyg.droidserver.app.ui.common.ErrorText
import com.plyg.droidserver.app.ui.common.ScreenScaffold
import com.plyg.droidserver.app.ui.common.WarningText
import com.plyg.droidserver.app.ui.common.saveConfigChange
import com.plyg.droidserver.app.ui.wizard.WizardViewModel
import com.plyg.droidserver.core.config.EnvFile
import com.plyg.droidserver.core.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Düzenlenen değişken; [secret] ise şifreli depoda saklanır. */
private data class EnvRow(val name: String, val value: String, val secret: Boolean)

/**
 * Ortak ortam değişkenleri ve sırlar (`env`): script'ler (`ctx.env`), uygulamalar ve "ortak ortamı kullan" açık
 * bileşenler kullanır. Sırlar Android Keystore ile şifreli depoda durur; dosyada yalnızca referansları vardır.
 * Değerleri göstermek, `.env` metni olarak düzenlemek ve dışa aktarmak (uygulama kilidi açıksa) kimlik doğrulaması ister.
 */
@Composable
fun EnvScreen(g: AppGraph, wizard: WizardViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val rows = remember { mutableStateListOf<EnvRow>() }
    var original by remember { mutableStateOf<List<EnvRow>?>(null) }
    var missing by remember { mutableStateOf(emptySet<String>()) }
    var revealed by remember { mutableStateOf(false) }
    var textMode by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Int?>(null) }
    var adding by remember { mutableStateOf(false) }
    var errors by remember { mutableStateOf<List<String>>(emptyList()) }
    var notice by remember { mutableStateOf<String?>(null) }
    var confirmExport by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) { EnvFile.fromConfig(g.configRepo.load().env, g.secrets::get) }
        val list = loaded.values.map { EnvRow(it.name, it.value, it.name in loaded.secret) }
        rows.clear(); rows.addAll(list)
        original = list
        missing = loaded.missing
    }
    val dirty = original != null && rows.toList() != original

    /** Metindeki değerleri listeye uygular: var olanların sır işareti korunur, yeniler sır sayılır. */
    fun applyParsed(parsed: EnvFile.Parsed, replace: Boolean) {
        val secretOf = rows.associate { it.name to it.secret }
        val incoming = parsed.entries.map { EnvRow(it.name, it.value, secretOf[it.name] ?: true) }
        if (replace) {
            rows.clear(); rows.addAll(incoming)
        } else {
            incoming.forEach { r -> val i = rows.indexOfFirst { it.name == r.name }; if (i >= 0) rows[i] = r else rows += r }
        }
    }

    fun save(deploy: Boolean) = scope.launch {
        val env = withContext(Dispatchers.IO) {
            EnvFile.toConfig(rows.map { EnvFile.Entry(it.name, it.value) }, rows.filter { it.secret }.map { it.name }.toSet(), g.secrets)
        }
        errors = saveConfigChange(ctx, g, wizard, deploy, listOf("env")) { it.copy(env = env) }
        if (errors.isEmpty()) {
            original = rows.toList()
            missing = emptySet()
            notice = if (deploy) tr("Kaydedildi; deploy başladı (sonuç Loglar'da)") else tr("Kaydedildi; sonraki deploy'da geçerli olur")
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val text = withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull() }
            if (text == null) { errors = listOf(tr("Dosya okunamadı")); return@launch }
            val parsed = EnvFile.parse(text)
            errors = parsed.errors.map { it.toString() }
            if (parsed.errors.isEmpty()) {
                applyParsed(parsed, replace = false)
                notice = tr("{0} değişken içe aktarıldı (yeniler sır olarak işaretlendi); kaydetmeyi unutmayın", parsed.entries.size)
            }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(EnvFile.format(rows.associate { r -> r.name to r.value }).toByteArray()) } }.isSuccess
            }
            notice = if (ok) tr("Dışa aktarıldı") else tr("Dosyaya yazılamadı")
        }
    }

    val text = textMode
    if (text != null) {
        val parsed = remember(text) { EnvFile.parse(text) }
        ScreenScaffold(tr(".env metni"), onBack = { textMode = null }) { modifier ->
            Column(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    tr("AD=değer satırları; # yorum. Boşluk veya özel karakter içeren değeri tırnak içine alın; çok satırlı değer (sertifika) çift tırnakta \\n ile veya tırnak içinde birden çok satır olarak yazılabilir. Yeni değişkenler sır olarak saklanır; listede değiştirebilirsiniz."),
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = parsed.errors.isEmpty(), onClick = { applyParsed(parsed, replace = true); textMode = null }) { Text(tr("Listeye uygula")) }
                    TextButton(onClick = { textMode = null }) { Text(tr("Vazgeç")) }
                }
                parsed.problems.forEach { p -> if (p.warning) WarningText(p.toString()) else ErrorText(p.toString()) }
                OutlinedTextField(
                    value = text, onValueChange = { textMode = it },
                    modifier = Modifier.fillMaxWidth().weight(1f).heightIn(min = 200.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                )
            }
        }
        return
    }

    ScreenScaffold(
        tr("Ortam ve sırlar") + if (dirty) " •" else "",
        onBack = onBack,
        actions = { IconButton(onClick = { adding = true }) { Icon(Icons.Default.Add, contentDescription = tr("Değişken ekle")) } },
    ) { modifier ->
        LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                TipCard(g, "env-intro", tr("Ortak ortam değişkenleri script'lere (ctx.env), kendi uygulamalarınıza ve \"ortak ortamı kullan\" açık bileşenlere verilir. Kilitli olanlar telefonun şifreli deposunda saklanır; server.json'da ve yedeklerde yalnızca referansları bulunur (yeni telefona taşırken yeniden girin veya .env olarak dışa aktarın)."))
                if (missing.isNotEmpty()) WarningText(tr("Şu sırların değeri bu telefonda yok, yeniden girin: {0}", missing.joinToString()))
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { if (revealed) revealed = false else AppLock.guard(ctx, tr("Değerleri göster")) { revealed = true } }) {
                        Text(if (revealed) tr("Değerleri gizle") else tr("Değerleri göster"))
                    }
                    OutlinedButton(onClick = {
                        AppLock.guard(ctx, tr(".env metni olarak düzenle")) { textMode = EnvFile.format(rows.associate { it.name to it.value }) }
                    }) { Text(tr(".env metni")) }
                    OutlinedButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) { Text(tr("İçe aktar")) }
                    OutlinedButton(enabled = rows.isNotEmpty(), onClick = { confirmExport = true }) { Text(tr("Dışa aktar")) }
                }
            }
            if (rows.isEmpty()) item {
                EmptyState(
                    Icons.Default.Key, tr("Henüz değişken yok"), tr("Parola, API anahtarı ve adres gibi değerleri ekleyin veya bir .env dosyasını içe aktarın."),
                    action = tr("Değişken ekle"), onAction = { adding = true },
                )
            }
            itemsIndexed(rows, key = { _, r -> r.name }) { i, r ->
                OutlinedCard(onClick = { AppLock.guard(ctx, tr("Değişkeni düzenle")) { editing = i } }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.name, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleSmall)
                            Text(
                                when {
                                    r.name in missing -> tr("(değer yok)")
                                    r.secret && !revealed -> "••••••••"
                                    r.value.isEmpty() -> tr("(boş)")
                                    else -> r.value.lines().first().take(80) + if (r.value.lines().size > 1 || r.value.length > 80) " …" else ""
                                },
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        IconButton(onClick = { rows[i] = r.copy(secret = !r.secret) }) {
                            Icon(
                                if (r.secret) Icons.Default.Lock else Icons.Default.LockOpen,
                                contentDescription = if (r.secret) tr("Sır (şifreli); düz metin yap") else tr("Düz metin; sır yap"),
                                tint = if (r.secret) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            item {
                errors.forEach { ErrorText(it) }
                notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (dirty) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = dirty, onClick = { save(deploy = true) }) { Text(tr("Kaydet ve deploy et")) }
                    OutlinedButton(enabled = dirty, onClick = { save(deploy = false) }) { Text(tr("Kaydet")) }
                    if (dirty) TextButton(onClick = { rows.clear(); rows.addAll(original.orEmpty()) }) { Text(tr("Geri al")) }
                }
            }
        }
    }

    if (adding || editing != null) {
        val current = editing?.let { rows.getOrNull(it) }
        EnvDialog(
            initial = current,
            taken = rows.map { it.name }.toSet() - setOfNotNull(current?.name),
            onDismiss = { adding = false; editing = null },
            onDelete = current?.let { { rows.remove(it); editing = null } },
            onSave = { row ->
                val idx = editing
                if (idx != null) rows[idx] = row else rows += row
                adding = false; editing = null
            },
        )
    }

    if (confirmExport) {
        AlertDialog(
            onDismissRequest = { confirmExport = false },
            title = { Text(tr("Düz metin olarak dışa aktarılsın mı?")) },
            text = { Text(tr("Dosyada sırlar dahil tüm değerler şifresiz yazılır. Yalnızca güvendiğiniz bir yere kaydedin ve işiniz bitince silin.")) },
            confirmButton = {
                TextButton(onClick = {
                    confirmExport = false
                    AppLock.guard(ctx, tr("Sırları dışa aktar")) { exportLauncher.launch("droidserver.env") }
                }) { Text(tr("Dışa aktar")) }
            },
            dismissButton = { TextButton(onClick = { confirmExport = false }) { Text(tr("Vazgeç")) } },
        )
    }
}

@Composable
private fun EnvDialog(initial: EnvRow?, taken: Set<String>, onDismiss: () -> Unit, onDelete: (() -> Unit)?, onSave: (EnvRow) -> Unit) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var value by remember { mutableStateOf(initial?.value.orEmpty()) }
    var secret by remember { mutableStateOf(initial?.secret ?: true) }
    val nameError = when {
        name.isEmpty() -> null
        !EnvFile.NAME.matches(name) -> tr("Harf, rakam ve _; harfle veya _ ile başlar")
        name in taken -> tr("Bu ad zaten var")
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) tr("Yeni değişken") else initial.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    name, { name = it.trim() }, label = { Text(tr("Ad")) }, singleLine = true,
                    isError = nameError != null, supportingText = { nameError?.let { Text(it) } },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
                OutlinedTextField(
                    value, { value = it }, label = { Text(tr("Değer")) }, minLines = 1, maxLines = 6,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    supportingText = { if (value.length > EnvFile.MAX_VALUE) Text(tr("En çok {0} karakter", EnvFile.MAX_VALUE)) },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(secret, { secret = it })
                    Text(tr("Sır: şifreli depoda saklanır, ekranda gizlenir"), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotEmpty() && nameError == null && value.length <= EnvFile.MAX_VALUE, onClick = { onSave(EnvRow(name, value, secret)) }) {
                Text(tr("Tamam"))
            }
        },
        dismissButton = {
            Row {
                onDelete?.let { TextButton(onClick = it) { Text(tr("Sil"), color = MaterialTheme.colorScheme.error) } }
                TextButton(onClick = onDismiss) { Text(tr("Vazgeç")) }
            }
        },
    )
}
