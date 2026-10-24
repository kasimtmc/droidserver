package com.plyg.droidserver.app.ui.config

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DynamicForm
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.plyg.droidserver.app.AppGraph
import com.plyg.droidserver.app.data.NetUtil
import com.plyg.droidserver.app.ui.common.EmptyState
import com.plyg.droidserver.app.ui.common.ErrorText
import com.plyg.droidserver.app.ui.common.Pill
import com.plyg.droidserver.app.ui.common.ScreenScaffold
import com.plyg.droidserver.app.ui.common.SectionCard
import com.plyg.droidserver.app.ui.common.SectionHeader
import com.plyg.droidserver.app.ui.common.SoftCard
import com.plyg.droidserver.app.ui.common.TipCard
import com.plyg.droidserver.app.ui.common.copyToClipboard
import com.plyg.droidserver.app.ui.common.saveConfigChange
import com.plyg.droidserver.app.ui.wizard.WizardViewModel
import com.plyg.droidserver.core.api.formWritable
import com.plyg.droidserver.core.config.FieldType
import com.plyg.droidserver.core.config.FormConfig
import com.plyg.droidserver.core.config.FormField
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.i18n.tr
import com.plyg.droidserver.core.server.EngineState
import kotlinx.coroutines.launch

/** Herkese açık formlar: liste, adres (kopyala / tarayıcıda aç) ve düzenleyici. Gönderimler koleksiyona kayıt olarak düşer. */
@Composable
fun FormsScreen(g: AppGraph, wizard: WizardViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val active by wizard.active.collectAsState()
    val state by g.engine.state.collectAsState()
    val forms = active?.forms.orEmpty()
    var editing by remember { mutableStateOf<FormConfig?>(null) }
    var creating by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf<String?>(null) }

    val edit = editing
    if (edit != null || creating) {
        FormEditor(g, wizard, active ?: ServerConfig(), edit) { editing = null; creating = false }
        return
    }
    val base = (state as? EngineState.Running)?.let { "http://${NetUtil.localIpv4().firstOrNull() ?: "127.0.0.1"}:${it.port}" }

    ScreenScaffold(
        tr("Formlar"), subtitle = tr("{0} form", forms.size), onBack = onBack,
        actions = { IconButton(onClick = { creating = true }) { Icon(Icons.Default.Add, contentDescription = tr("Form ekle")) } },
    ) { modifier ->
        LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                if (forms.isEmpty()) {
                    EmptyState(
                        Icons.Default.DynamicForm, tr("Henüz form yok"),
                        tr("İletişim, başvuru veya anket formu oluşturun: adresi paylaştığınız herkes doldurabilir, gönderimler koleksiyona kayıt olarak düşer. Spam koruması hazır."),
                        action = tr("Form ekle"), onAction = { creating = true },
                    )
                } else {
                    TipCard(g, "forms-intro", tr("Gönderim gelince e-posta veya bildirim almak için Otomasyonlar'da bu koleksiyona \"eklenince\" kuralı kurun."))
                }
                copied?.let { Text(tr("Kopyalandı: {0}", it), style = MaterialTheme.typography.bodySmall) }
            }
            items(forms, key = { it.name }) { form ->
                val url = base?.let { it + form.path }
                SoftCard(onClick = { editing = form }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(form.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (!form.enabled) Pill(tr("Kapalı"))
                    }
                    Text(url ?: form.path, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Text(tr("{0} koleksiyonuna kayıt ekler", form.collection), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = url != null, onClick = { url?.let { copyToClipboard(ctx, "form", it); copied = it } }) { Text(tr("Kopyala")) }
                        OutlinedButton(enabled = url != null && form.enabled, onClick = {
                            url?.let { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, it.toUri())) } }
                        }) { Text(tr("Aç")) }
                        TextButton(onClick = { editing = form }) { Text(tr("Düzenle")) }
                    }
                }
            }
            if (base == null && forms.isNotEmpty()) item { Text(tr("Adresler sunucu çalışırken gösterilir."), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** Form ekleme/düzenleme; kaydedince deploy edilir. */
@Composable
private fun FormEditor(g: AppGraph, wizard: WizardViewModel, cfg: ServerConfig, initial: FormConfig?, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val collections = cfg.collections
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var description by remember { mutableStateOf(initial?.description.orEmpty()) }
    var collection by remember { mutableStateOf(initial?.collection ?: collections.firstOrNull()?.name) }
    val fields = remember { mutableStateListOf<FormField>().apply { addAll(initial?.fields.orEmpty()) } }
    var submit by remember { mutableStateOf(initial?.submitLabel.orEmpty()) }
    var success by remember { mutableStateOf(initial?.successMessage.orEmpty()) }
    var redirect by remember { mutableStateOf(initial?.redirect.orEmpty()) }
    var limit by remember { mutableStateOf((initial?.maxPerTenMinutes ?: 5).toString()) }
    var enabled by remember { mutableStateOf(initial?.enabled ?: true) }
    var errors by remember { mutableStateOf<List<String>>(emptyList()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val target = collections.firstOrNull { it.name == collection }
    val writable = target?.fields.orEmpty().filter(::formWritable)

    fun selectAll() {
        fields.clear(); fields.addAll(writable.map { FormField(it.name, multiline = it.type == FieldType.TEXT && (it.maxLength ?: 0) > 200) })
    }
    // Boş alan listesi "tüm yazılabilir alanlar" demektir; düzenleyicide açıkça seçili gösterilir.
    LaunchedEffect(collection) { if (fields.isEmpty()) selectAll() }

    fun build() = FormConfig(
        name = name.trim(), collection = collection.orEmpty(), title = title.trim(),
        description = description.trim().ifEmpty { null },
        fields = fields.toList(), submitLabel = submit.trim().ifEmpty { null }, successMessage = success.trim().ifEmpty { null },
        redirect = redirect.trim().ifEmpty { null }, maxPerTenMinutes = limit.toIntOrNull() ?: 0, enabled = enabled,
    )

    fun save(remove: Boolean = false) = scope.launch {
        val form = build()
        errors = saveConfigChange(ctx, g, wizard, deploy = true, scope = listOf("forms", "server.basePath")) { c ->
            val rest = c.forms.filter { it.name != initial?.name }
            c.copy(forms = if (remove) rest else if (initial == null) c.forms + form else c.forms.map { if (it.name == initial.name) form else it })
        }
        if (errors.isEmpty()) onDone()
    }

    ScreenScaffold(
        initial?.title ?: tr("Yeni form"), onBack = onDone,
        actions = { if (initial != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, contentDescription = tr("Formu sil")) } },
    ) { modifier ->
        Column(modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard(tr("Form")) {
                OutlinedTextField(title, { title = it }, label = { Text(tr("Başlık")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    name, { name = it.lowercase().replace(' ', '-').filter { c -> c.isLetterOrDigit() && c.code < 128 || c == '-' } },
                    label = { Text(tr("Adres")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    prefix = { Text(FormConfig.PATH + "/") }, supportingText = { Text(tr("Küçük harf, rakam ve -")) },
                )
                OutlinedTextField(description, { description = it }, label = { Text(tr("Açıklama (isteğe bağlı)")) }, minLines = 2, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) { Text(tr("Etkin"), Modifier.weight(1f)); Switch(enabled, { enabled = it }) }
            }

            SectionCard(tr("Kayıtlar nereye?")) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    collections.forEach { c -> FilterChip(collection == c.name, { if (collection != c.name) { collection = c.name; fields.clear() } }, label = { Text(c.name) }) }
                }
                if (collections.isEmpty()) ErrorText(tr("Önce sihirbazda bir koleksiyon ekleyin."))
            }

            if (target != null) {
                SectionHeader(tr("Alanlar"))
                Text(tr("Formda görünecek alanları seçin; görünen adı ve açıklamayı değiştirebilirsiniz."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                writable.forEach { f ->
                    val i = fields.indexOfFirst { it.name == f.name }
                    val on = i >= 0
                    SoftCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(f.name, style = MaterialTheme.typography.titleSmall)
                                Text(f.type.name.lowercase() + if (f.required) tr(" · zorunlu") else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(on, { if (it) fields += FormField(f.name) else fields.removeAt(i) })
                        }
                        if (on) {
                            val ff = fields[i]
                            OutlinedTextField(ff.label.orEmpty(), { fields[i] = ff.copy(label = it.ifEmpty { null }) }, label = { Text(tr("Görünen ad")) }, placeholder = { Text(f.name) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(ff.help.orEmpty(), { fields[i] = ff.copy(help = it.ifEmpty { null }) }, label = { Text(tr("Açıklama")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            if (f.type == FieldType.TEXT) Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(tr("Çok satırlı"), Modifier.weight(1f)); Switch(ff.multiline, { fields[i] = ff.copy(multiline = it) })
                            }
                        }
                    }
                }
            }

            SectionCard(tr("Gönderim")) {
                OutlinedTextField(submit, { submit = it }, label = { Text(tr("Düğme metni")) }, placeholder = { Text(tr("Gönder")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(success, { success = it }, label = { Text(tr("Teşekkür metni")) }, placeholder = { Text(tr("Teşekkürler, gönderildi.")) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    redirect, { redirect = it.trim() }, label = { Text(tr("Yönlendirme (isteğe bağlı)")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text(tr("Verilirse gönderimden sonra bu adrese gidilir: https://… veya /tesekkurler")) },
                )
                OutlinedTextField(
                    limit, { limit = it.filter(Char::isDigit) }, label = { Text(tr("Aynı adresten 10 dakikada en çok")) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
                )
                Text(tr("Spam koruması: görünmeyen tuzak alanı, en az 2 saniyelik doldurma süresi ve adres başına sınır."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            errors.forEach { ErrorText(it) }
            Button(onClick = { save() }, modifier = Modifier.fillMaxWidth(), enabled = name.isNotBlank() && title.isNotBlank() && collection != null) { Text(tr("Kaydet ve deploy et")) }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(tr("Form silinsin mi?")) },
            text = { Text(tr("Form adresi kapanır; şimdiye kadar gelen kayıtlar koleksiyonda kalır.")) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; save(remove = true) }) { Text(tr("Sil")) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(tr("Vazgeç")) } },
        )
    }
}
