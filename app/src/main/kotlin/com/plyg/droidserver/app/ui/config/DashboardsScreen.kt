package com.plyg.droidserver.app.ui.config

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.plyg.droidserver.app.AppGraph
import com.plyg.droidserver.app.data.NetUtil
import com.plyg.droidserver.app.ui.common.EmptyState
import com.plyg.droidserver.app.ui.common.ErrorText
import com.plyg.droidserver.app.ui.common.ScreenScaffold
import com.plyg.droidserver.app.ui.common.SectionCard
import com.plyg.droidserver.app.ui.common.SoftCard
import com.plyg.droidserver.app.ui.common.copyToClipboard
import com.plyg.droidserver.app.ui.common.saveConfigChange
import com.plyg.droidserver.app.ui.wizard.WizardViewModel
import com.plyg.droidserver.core.api.ViewService
import com.plyg.droidserver.core.config.ChartKind
import com.plyg.droidserver.core.config.DashboardConfig
import com.plyg.droidserver.core.config.FieldType
import com.plyg.droidserver.core.config.GeoPoint
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.config.WidgetConfig
import com.plyg.droidserver.core.config.WidgetType
import com.plyg.droidserver.core.i18n.tr
import com.plyg.droidserver.core.server.EngineState
import com.plyg.droidserver.core.server.ServerEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.security.SecureRandom
import java.text.NumberFormat

/** Panolar: liste → görüntüleme (sayı, grafik, tablo, harita kutuları) → düzenleme. */
@Composable
fun DashboardsScreen(g: AppGraph, wizard: WizardViewModel, onBack: () -> Unit) {
    val active by wizard.active.collectAsState()
    val dashboards = active?.dashboards.orEmpty()
    var open by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<DashboardConfig?>(null) }
    var creating by remember { mutableStateOf(false) }
    val edit = editing
    if (edit != null || creating) {
        DashboardEditor(g, wizard, active ?: ServerConfig(), edit) { saved -> editing = null; creating = false; if (saved != null) open = saved }
        return
    }
    val shown = open?.let { n -> dashboards.firstOrNull { it.name == n } }
    if (shown != null) {
        DashboardView(g, shown, onBack = { open = null }, onEdit = { editing = shown })
        return
    }
    ScreenScaffold(
        tr("Panolar"), subtitle = tr("{0} pano", dashboards.size), onBack = onBack,
        actions = { IconButton(onClick = { creating = true }) { Icon(Icons.Default.Add, contentDescription = tr("Pano ekle")) } },
    ) { modifier ->
        LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (dashboards.isEmpty()) item {
                EmptyState(
                    Icons.Default.Dashboard, tr("Henüz pano yok"),
                    tr("Görünümlerinizden sayı, grafik, tablo ve harita kutularıyla bir pano kurun; isterseniz salt okunur bir adresle paylaşın."),
                    action = tr("Pano ekle"), onAction = { creating = true },
                )
            }
            items(dashboards, key = { it.name }) { d ->
                SoftCard(onClick = { open = d.name }) {
                    Text(d.title, style = MaterialTheme.typography.titleMedium)
                    Text(tr("{0} kutu", d.widgets.size) + if (d.shareToken != null) " · " + tr("paylaşılıyor") else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun DashboardView(g: AppGraph, d: DashboardConfig, onBack: () -> Unit, onEdit: () -> Unit) {
    val state by g.engine.state.collectAsState()
    var data by remember { mutableStateOf<JsonObject?>(null) }
    LaunchedEffect(d, state) { data = withContext(Dispatchers.IO) { runCatching { g.engine.dashboard(d.name) }.getOrNull() } }
    ScreenScaffold(d.title, onBack = onBack, actions = { IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = tr("Düzenle")) } }) { modifier ->
        Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val widgets = (data?.get("widgets") as? JsonArray).orEmpty().map { it as JsonObject }
            if (data == null) Text(tr("Sunucu çalışırken gösterilir."), color = MaterialTheme.colorScheme.onSurfaceVariant)
            widgets.forEach { WidgetCard(it) }
        }
    }
}

@Composable
internal fun WidgetCard(w: JsonObject) {
    val title = (w["title"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    SectionCard(title) {
        val error = (w["error"] as? JsonPrimitive)?.contentOrNull
        if (error != null) { ErrorText(error); return@SectionCard }
        when ((w["type"] as? JsonPrimitive)?.content) {
            "number" -> Text(
                (w["value"] as? JsonPrimitive)?.doubleOrNull?.let { NumberFormat.getNumberInstance().apply { maximumFractionDigits = 2 }.format(it) } ?: "—",
                style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold,
            )
            "chart" -> Chart(w)
            "table" -> {
                val columns = (w["columns"] as? JsonArray).orEmpty().map { (it as JsonPrimitive).content }
                val rows = (w["rows"] as? JsonArray).orEmpty().map { r -> (r as JsonArray).map { (it as JsonPrimitive).content } }
                Column(Modifier.horizontalScroll(rememberScrollState())) {
                    Row { columns.forEach { Text(it, Modifier.width(120.dp).padding(4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                    rows.forEach { r -> Row { r.forEach { Text(it, Modifier.width(120.dp).padding(4.dp), style = MaterialTheme.typography.bodySmall, maxLines = 2) } } }
                }
            }
            "map" -> {
                val points = (w["points"] as? JsonArray).orEmpty().mapIndexedNotNull { i, p ->
                    val o = p as JsonObject
                    val lat = (o["lat"] as? JsonPrimitive)?.doubleOrNull ?: return@mapIndexedNotNull null
                    val lng = (o["lng"] as? JsonPrimitive)?.doubleOrNull ?: return@mapIndexedNotNull null
                    ServerEngine.MapPoint(i.toLong(), (o["label"] as? JsonPrimitive)?.contentOrNull, GeoPoint(lat, lng))
                }
                GeoMap(points, Modifier.fillMaxWidth().height(260.dp))
            }
        }
    }
}

@Composable
private fun Chart(w: JsonObject) {
    val points = (w["points"] as? JsonArray).orEmpty().map { it as JsonObject }
        .map { (it["label"] as? JsonPrimitive)?.contentOrNull.orEmpty() to ((it["value"] as? JsonPrimitive)?.doubleOrNull ?: 0.0) }
    if (points.isEmpty()) { Text(tr("Veri yok")); return }
    val line = (w["chart"] as? JsonPrimitive)?.content == "line"
    val color = MaterialTheme.colorScheme.primary
    val max = points.maxOf { it.second }.takeIf { it > 0 } ?: 1.0
    Canvas(Modifier.fillMaxWidth().height(160.dp)) {
        val step = size.width / points.size
        fun y(v: Double) = (size.height * (1 - v / max)).toFloat()
        if (line) {
            val path = Path()
            points.forEachIndexed { i, (_, v) -> val x = step * i + step / 2; if (i == 0) path.moveTo(x, y(v)) else path.lineTo(x, y(v)) }
            drawPath(path, color, style = Stroke(3.dp.toPx()))
        } else {
            points.forEachIndexed { i, (_, v) ->
                drawRoundRect(color, Offset(step * i + step * 0.15f, y(v)), Size(step * 0.7f, size.height - y(v)), CornerRadius(4.dp.toPx()))
            }
        }
    }
    Text(points.joinToString("  ·  ") { (l, v) -> "$l: ${NumberFormat.getNumberInstance().apply { maximumFractionDigits = 2 }.format(v)}" }, style = MaterialTheme.typography.bodySmall)
}

/** Pano ekleme/düzenleme: kutular yukarı/aşağı taşınır (panelde sürükle-bırak). Kaydedince deploy edilir. */
@Composable
private fun DashboardEditor(g: AppGraph, wizard: WizardViewModel, cfg: ServerConfig, initial: DashboardConfig?, onDone: (String?) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by g.engine.state.collectAsState()
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var token by remember { mutableStateOf(initial?.shareToken) }
    val widgets = remember { mutableStateListOf<WidgetConfig>().apply { addAll(initial?.widgets.orEmpty()) } }
    var errors by remember { mutableStateOf<List<String>>(emptyList()) }
    val views = cfg.views

    fun save(remove: Boolean = false) = scope.launch {
        val d = DashboardConfig(name.trim(), title.trim(), widgets.toList(), token)
        errors = saveConfigChange(ctx, g, wizard, deploy = true, scope = listOf("dashboards")) { c ->
            val rest = c.dashboards.filter { it.name != initial?.name }
            c.copy(dashboards = if (remove) rest else if (initial == null) c.dashboards + d else c.dashboards.map { if (it.name == initial.name) d else it })
        }
        if (errors.isEmpty()) onDone(if (remove) null else d.name)
    }

    ScreenScaffold(
        initial?.title ?: tr("Yeni pano"), onBack = { onDone(null) },
        actions = { if (initial != null) IconButton(onClick = { save(remove = true) }) { Icon(Icons.Default.Delete, contentDescription = tr("Panoyu sil")) } },
    ) { modifier ->
        Column(modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard(tr("Pano")) {
                OutlinedTextField(title, { title = it }, label = { Text(tr("Başlık")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(name, { name = it.lowercase().replace(' ', '-').filter { c -> c.isLetterOrDigit() && c.code < 128 || c == '-' } }, label = { Text(tr("Ad")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("Salt okunur adresle paylaş"))
                        Text(tr("Adresi bilen herkes görür (giriş gerekmez)."), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(token != null, { on -> token = if (on) randomToken() else null })
                }
                token?.let { t ->
                    val base = (state as? EngineState.Running)?.let { "http://${NetUtil.localIpv4().firstOrNull() ?: "127.0.0.1"}:${it.port}" } ?: ""
                    val url = "$base${DashboardConfig.PATH}/${name.ifBlank { "…" }}?token=$t"
                    Text(url, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { copyToClipboard(ctx, "dashboard", url) }) { Text(tr("Kopyala")) }
                }
            }
            if (views.isEmpty()) ErrorText(tr("Önce bir görünüm ekleyin (Görünümler); kutular görünümlerden beslenir."))
            widgets.forEachIndexed { i, w ->
                SoftCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("Kutu {0}", i + 1), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        IconButton(enabled = i > 0, onClick = { widgets.add(i - 1, widgets.removeAt(i)) }) { Icon(Icons.Default.ArrowUpward, contentDescription = tr("Yukarı")) }
                        IconButton(enabled = i < widgets.size - 1, onClick = { widgets.add(i + 1, widgets.removeAt(i)) }) { Icon(Icons.Default.ArrowDownward, contentDescription = tr("Aşağı")) }
                        IconButton(onClick = { widgets.removeAt(i) }) { Icon(Icons.Default.Delete, contentDescription = tr("Kutuyu sil")) }
                    }
                    WidgetFields(cfg, w) { widgets[i] = it }
                }
            }
            OutlinedButton(enabled = views.isNotEmpty(), onClick = { widgets += WidgetConfig(WidgetType.TABLE, views.first().name) }, modifier = Modifier.fillMaxWidth()) { Text(tr("Kutu ekle")) }
            errors.forEach { ErrorText(it) }
            Button(onClick = { save() }, modifier = Modifier.fillMaxWidth(), enabled = name.isNotBlank() && title.isNotBlank()) { Text(tr("Kaydet ve deploy et")) }
        }
    }
}

@Composable
private fun WidgetFields(cfg: ServerConfig, w: WidgetConfig, onChange: (WidgetConfig) -> Unit) {
    val types = listOf(WidgetType.NUMBER to tr("Sayı"), WidgetType.CHART to tr("Grafik"), WidgetType.TABLE to tr("Tablo"), WidgetType.MAP to tr("Harita"))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { types.forEach { (t, l) -> FilterChip(w.type == t, { onChange(w.copy(type = t)) }, label = { Text(l) }) } }
    val needsReport = w.type == WidgetType.NUMBER || w.type == WidgetType.CHART
    val fit = cfg.views.filter { (it.report != null) == needsReport && (w.type != WidgetType.CHART || it.report?.groupBy != null) }
    Text(tr("Görünüm:"), style = MaterialTheme.typography.bodySmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { fit.forEach { v -> FilterChip(w.view == v.name, { onChange(w.copy(view = v.name)) }, label = { Text(v.label) }) } }
    if (fit.isEmpty()) Text(if (needsReport) tr("Özetli (rapor) bir görünüm gerekir.") else tr("Raporsuz bir görünüm gerekir."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    val view = cfg.views.firstOrNull { it.name == w.view }
    view?.report?.takeIf { needsReport }?.let { r ->
        val metrics = listOf("count") + r.sum.map { "sum_$it" } + r.avg.map { "avg_$it" } + r.min.map { "min_$it" } + r.max.map { "max_$it" }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { metrics.forEach { m -> FilterChip(w.metric == m, { onChange(w.copy(metric = m)) }, label = { Text(m) }) } }
    }
    if (w.type == WidgetType.CHART) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(w.chart == ChartKind.BAR, { onChange(w.copy(chart = ChartKind.BAR)) }, label = { Text(tr("Çubuk")) })
        FilterChip(w.chart == ChartKind.LINE, { onChange(w.copy(chart = ChartKind.LINE)) }, label = { Text(tr("Çizgi")) })
    }
    if (w.type == WidgetType.MAP && view != null) {
        val c = cfg.collections.firstOrNull { it.name == view.collection }
        val columns = view.columns.ifEmpty { ViewService.defaultColumns(cfg, view) }
        val geo = c?.fields.orEmpty().filter { it.type == FieldType.GEO && it.name in columns }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { geo.forEach { f -> FilterChip(w.field == f.name, { onChange(w.copy(field = f.name)) }, label = { Text(f.name) }) } }
    }
    OutlinedTextField(w.title.orEmpty(), { onChange(w.copy(title = it.ifEmpty { null })) }, label = { Text(tr("Başlık (isteğe bağlı)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(tr("Genişlik:"), style = MaterialTheme.typography.bodySmall)
        (1..3).forEach { n -> FilterChip(w.width == n, { onChange(w.copy(width = n)) }, label = { Text("$n") }) }
    }
}

private fun randomToken(): String {
    val alphabet = "abcdefghijkmnpqrstuvwxyz23456789"
    val r = SecureRandom()
    return (1..24).map { alphabet[r.nextInt(alphabet.length)] }.joinToString("")
}
