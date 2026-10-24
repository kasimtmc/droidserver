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
import androidx.compose.material.icons.filled.Web
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
import com.plyg.droidserver.app.ui.common.SoftCard
import com.plyg.droidserver.app.ui.common.copyToClipboard
import com.plyg.droidserver.app.ui.common.saveConfigChange
import com.plyg.droidserver.app.ui.wizard.WizardViewModel
import com.plyg.droidserver.core.config.PageConfig
import com.plyg.droidserver.core.config.PageLayout
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.i18n.tr
import com.plyg.droidserver.core.server.EngineState
import kotlinx.coroutines.launch

/** Kodsuz sayfalar: liste, adres ve düzenleyici. Sayfalar koleksiyonun kurallarıyla, sunucuda üretilen HTML'dir. */
@Composable
fun PagesScreen(g: AppGraph, wizard: WizardViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val active by wizard.active.collectAsState()
    val state by g.engine.state.collectAsState()
    val pages = active?.pages.orEmpty()
    var editing by remember { mutableStateOf<PageConfig?>(null) }
    var creating by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf<String?>(null) }

    val edit = editing
    if (edit != null || creating) {
        PageEditor(g, wizard, active ?: ServerConfig(), edit) { editing = null; creating = false }
        return
    }
    val base = (state as? EngineState.Running)?.let { "http://${NetUtil.localIpv4().firstOrNull() ?: "127.0.0.1"}:${it.port}" }
    ScreenScaffold(
        tr("Sayfalar"), subtitle = tr("{0} sayfa", pages.size), onBack = onBack,
        actions = { IconButton(onClick = { creating = true }) { Icon(Icons.Default.Add, contentDescription = tr("Sayfa ekle")) } },
    ) { modifier ->
        LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                if (pages.isEmpty()) {
                    EmptyState(
                        Icons.Default.Web, tr("Henüz sayfa yok"),
                        tr("Bir koleksiyonu herkese açık (veya giriş gerektiren) liste ve detay sayfası olarak yayınlayın: ürün kataloğu, duyurular, etkinlikler. Kodsuz, JavaScript'siz, arama motorları okur."),
                        action = tr("Sayfa ekle"), onAction = { creating = true },
                    )
                }
                copied?.let { Text(tr("Kopyalandı: {0}", it), style = MaterialTheme.typography.bodySmall) }
            }
            items(pages, key = { it.name }) { page ->
                val url = base?.let { it + page.path }
                SoftCard(onClick = { editing = page }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(page.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (page.login) Pill(tr("Girişli"))
                        if (!page.enabled) Pill(tr("Kapalı"))
                    }
                    Text(url ?: page.path, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Text(tr("{0} koleksiyonu", page.collection), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = url != null, onClick = { url?.let { copyToClipboard(ctx, "page", it); copied = it } }) { Text(tr("Kopyala")) }
                        OutlinedButton(enabled = url != null && page.enabled, onClick = { url?.let { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, it.toUri())) } } }) { Text(tr("Aç")) }
                        TextButton(onClick = { editing = page }) { Text(tr("Düzenle")) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PageEditor(g: AppGraph, wizard: WizardViewModel, cfg: ServerConfig, initial: PageConfig?, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val collections = cfg.collections
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var description by remember { mutableStateOf(initial?.description.orEmpty()) }
    var collection by remember { mutableStateOf(initial?.collection ?: collections.firstOrNull()?.name) }
    val fields = remember { mutableStateListOf<String>().apply { addAll(initial?.fields.orEmpty()) } }
    var filter by remember { mutableStateOf(initial?.filter.orEmpty()) }
    var sort by remember { mutableStateOf(initial?.sort.orEmpty()) }
    var perPage by remember { mutableStateOf((initial?.perPage ?: 20).toString()) }
    var layout by remember { mutableStateOf(initial?.layout ?: PageLayout.CARDS) }
    var detail by remember { mutableStateOf(initial?.detail ?: true) }
    var login by remember { mutableStateOf(initial?.login ?: false) }
    var accent by remember { mutableStateOf(initial?.accent.orEmpty()) }
    var enabled by remember { mutableStateOf(initial?.enabled ?: true) }
    var errors by remember { mutableStateOf<List<String>>(emptyList()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val target = collections.firstOrNull { it.name == collection }

    fun build() = PageConfig(
        name = name.trim(), collection = collection.orEmpty(), title = title.trim(), description = description.trim().ifEmpty { null },
        fields = fields.toList(), filter = filter.trim().ifEmpty { null }, sort = sort.trim().ifEmpty { null },
        perPage = perPage.toIntOrNull() ?: 0, layout = layout, detail = detail, login = login,
        accent = accent.trim().ifEmpty { null }, enabled = enabled,
    )

    fun save(remove: Boolean = false) = scope.launch {
        val page = build()
        errors = saveConfigChange(ctx, g, wizard, deploy = true, scope = listOf("pages", "server.basePath")) { c ->
            val rest = c.pages.filter { it.name != initial?.name }
            c.copy(pages = if (remove) rest else if (initial == null) c.pages + page else c.pages.map { if (it.name == initial.name) page else it })
        }
        if (errors.isEmpty()) onDone()
    }

    ScreenScaffold(
        initial?.title ?: tr("Yeni sayfa"), onBack = onDone,
        actions = { if (initial != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, contentDescription = tr("Sayfayı sil")) } },
    ) { modifier ->
        Column(modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard(tr("Sayfa")) {
                OutlinedTextField(title, { title = it }, label = { Text(tr("Başlık")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    name, { name = it.lowercase().replace(' ', '-').filter { c -> c.isLetterOrDigit() && c.code < 128 || c == '-' } },
                    label = { Text(tr("Adres")) }, singleLine = true, modifier = Modifier.fillMaxWidth(), prefix = { Text(PageConfig.PATH + "/") },
                )
                OutlinedTextField(description, { description = it }, label = { Text(tr("Açıklama (isteğe bağlı)")) }, minLines = 2, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) { Text(tr("Etkin"), Modifier.weight(1f)); Switch(enabled, { enabled = it }) }
            }
            SectionCard(tr("Kayıtlar")) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    collections.forEach { c -> FilterChip(collection == c.name, { if (collection != c.name) { collection = c.name; fields.clear() } }, label = { Text(c.name) }) }
                }
                if (target != null) {
                    Text(tr("Listede görünen alanlar (boşsa ilk beşi; ilki başlık olur):"), style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        target.fields.filter { it.visibility == null }.forEach { f ->
                            FilterChip(f.name in fields, { if (f.name in fields) fields.remove(f.name) else fields += f.name }, label = { Text(f.name) })
                        }
                    }
                }
                OutlinedTextField(filter, { filter = it }, label = { Text(tr("Süzgeç (isteğe bağlı)")) }, placeholder = { Text("status=published") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(sort, { sort = it.trim() }, label = { Text(tr("Sıralama (isteğe bağlı)")) }, placeholder = { Text("-created_at") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(perPage, { perPage = it.filter(Char::isDigit).take(3) }, label = { Text(tr("Sayfa başına kayıt")) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            }
            SectionCard(tr("Görünüm ve erişim")) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(layout == PageLayout.CARDS, { layout = PageLayout.CARDS }, label = { Text(tr("Kartlar")) })
                    FilterChip(layout == PageLayout.TABLE, { layout = PageLayout.TABLE }, label = { Text(tr("Tablo")) })
                }
                Row(verticalAlignment = Alignment.CenterVertically) { Text(tr("Detay sayfası"), Modifier.weight(1f)); Switch(detail, { detail = it }) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("Giriş gereksin"))
                        Text(tr("Ziyaretçi kullanıcı hesabıyla girer; koleksiyonun kuralları (örn. yalnızca kendi kayıtları) uygulanır."), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(login, { login = it })
                }
                OutlinedTextField(accent, { accent = it.trim().take(7) }, label = { Text(tr("Tema rengi (isteğe bağlı)")) }, placeholder = { Text("#006a65") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            errors.forEach { ErrorText(it) }
            Button(onClick = { save() }, modifier = Modifier.fillMaxWidth(), enabled = name.isNotBlank() && title.isNotBlank() && collection != null) { Text(tr("Kaydet ve deploy et")) }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(tr("Sayfa silinsin mi?")) },
            text = { Text(tr("Sayfanın adresi kapanır; kayıtlar koleksiyonda kalır.")) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; save(remove = true) }) { Text(tr("Sil")) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(tr("Vazgeç")) } },
        )
    }
}
