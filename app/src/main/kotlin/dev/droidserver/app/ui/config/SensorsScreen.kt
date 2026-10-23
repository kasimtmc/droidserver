package dev.droidserver.app.ui.config

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.droidserver.app.AppGraph
import dev.droidserver.app.ui.common.ErrorText
import dev.droidserver.app.ui.common.ScreenScaffold
import dev.droidserver.app.ui.common.SectionCard
import dev.droidserver.app.ui.common.saveConfigChange
import dev.droidserver.app.ui.wizard.WizardViewModel
import dev.droidserver.core.config.FieldType
import dev.droidserver.core.config.SensorConfig
import dev.droidserver.core.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Sensör panosu: koleksiyondaki sayısal alanların zaman grafiği, eşikler ve Home Assistant keşfi. */
@Composable
fun SensorsScreen(g: AppGraph, wizard: WizardViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val active by wizard.active.collectAsState()
    val state by g.engine.state.collectAsState()
    val initial = active?.sensors.orEmpty()
    // Grafiğe uygun alanlar: sayı ve evet/hayır.
    val candidates = active?.collections.orEmpty().flatMap { c ->
        c.fields.filter { it.type in setOf(FieldType.INTEGER, FieldType.REAL, FieldType.BOOLEAN) && !it.encrypted }.map { "${c.name}.${it.name}" }
    }

    var sensors by remember(initial) { mutableStateOf(initial) }
    var homeAssistant by remember(active) { mutableStateOf(active?.mqtt?.homeAssistant?.enabled ?: false) }
    var hours by remember { mutableLongStateOf(24L) }
    var series by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var errors by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(hours, state, initial) {
        while (true) {
            series = withContext(Dispatchers.IO) { runCatching { g.engine.sensors(hours)?.map { it.jsonObject } }.getOrNull().orEmpty() }
            delay(30_000)
        }
    }

    fun save() = scope.launch {
        errors = saveConfigChange(ctx, g, wizard, deploy = true, scope = listOf("sensors", "mqtt")) { c ->
            c.copy(sensors = sensors, mqtt = c.mqtt.copy(homeAssistant = c.mqtt.homeAssistant.copy(enabled = homeAssistant)))
        }
    }

    ScreenScaffold(tr("Sensör panosu"), subtitle = tr("{0} sensör", initial.size), onBack = onBack) { modifier ->
        Column(modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (initial.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(6L to tr("6 saat"), 24L to tr("24 saat"), 168L to tr("7 gün")).forEach { (h, label) ->
                        FilterChip(hours == h, { hours = h }, label = { Text(label) })
                    }
                }
                if (series.isEmpty()) Text(tr("Grafik için sunucu çalışıyor olmalı."), style = MaterialTheme.typography.bodySmall)
                series.forEach { SensorCard(it) }
            }

            SectionCard(tr("Sensörler")) {
                Text(
                    tr("Koleksiyondaki sayısal bir alanı sensör yapın: panoda grafiği çizilir, eşik dışındaki yeni değer sağlık uyarısı verir (Telegram, e-posta, bildirim)."),
                    style = MaterialTheme.typography.bodySmall,
                )
                sensors.forEachIndexed { i, s -> SensorEditor(s, onChange = { n -> sensors = sensors.toMutableList().also { it[i] = n } }, onRemove = { sensors = sensors.filterIndexed { j, _ -> j != i } }) }
                val free = candidates.filter { k -> sensors.none { it.key == k } }
                if (free.isNotEmpty()) {
                    Text(tr("Ekle:"), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        free.forEach { k -> FilterChip(false, { sensors = sensors + SensorConfig(k.substringBefore('.'), k.substringAfter('.')) }, label = { Text(k) }) }
                    }
                } else if (candidates.isEmpty()) {
                    Text(tr("Önce bir koleksiyona sayı alanı ekleyin (örn. readings.temp)."), style = MaterialTheme.typography.bodySmall)
                }
            }

            SectionCard(tr("Home Assistant")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Sensörleri Home Assistant'ta göster (MQTT keşfi)"), Modifier.weight(1f))
                    Switch(homeAssistant, { homeAssistant = it })
                }
                Text(
                    tr("Home Assistant'ın MQTT entegrasyonunu bu telefona (MQTT adresi, kullanıcı ve parola) bağlayın; sensörler kendiliğinden eklenir ve her yeni kayıtta güncellenir. MQTT açık olmalı."),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            errors.forEach { ErrorText(it) }
            Button(onClick = { save() }, modifier = Modifier.fillMaxWidth()) { Text(tr("Kaydet ve deploy et")) }
        }
    }
}

@Composable
private fun SensorCard(s: JsonObject) {
    val unit = (s["unit"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
    val min = (s["min"] as? JsonPrimitive)?.doubleOrNull
    val max = (s["max"] as? JsonPrimitive)?.doubleOrNull
    val points = s.getValue("points").jsonArray.mapNotNull { p ->
        val a = p.jsonArray
        a[0].jsonPrimitive.longOrNull?.let { t -> a[1].jsonPrimitive.doubleOrNull?.let { t to it } }
    }
    val last = (s["last"] as? JsonObject)?.get("value")?.jsonPrimitive?.doubleOrNull
    SectionCard(s.getValue("name").jsonPrimitive.content) {
        val outside = last != null && ((min != null && last < min) || (max != null && last > max))
        Text(
            (last?.let { num(it) + unit } ?: tr("henüz değer yok")) +
                listOfNotNull(min?.let { tr(" · alt {0}", num(it)) }, max?.let { tr(" · üst {0}", num(it)) }).joinToString(""),
            style = MaterialTheme.typography.titleMedium,
            color = if (outside) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        val line = MaterialTheme.colorScheme.primary
        val limit = MaterialTheme.colorScheme.error
        if (points.size >= 2) Canvas(Modifier.fillMaxWidth().height(96.dp)) {
            val values = points.map { it.second } + listOfNotNull(min, max)
            val lo = values.min()
            val hi = values.max().let { if (it == lo) lo + 1 else it }
            val t0 = points.first().first
            val span = (points.last().first - t0).coerceAtLeast(1)
            fun x(t: Long) = (t - t0).toFloat() / span * size.width
            fun y(v: Double) = size.height - ((v - lo) / (hi - lo)).toFloat() * size.height
            val path = Path().apply {
                points.forEachIndexed { i, (t, v) -> if (i == 0) moveTo(x(t), y(v)) else lineTo(x(t), y(v)) }
            }
            drawPath(path, line, style = Stroke(width = 3f))
            listOfNotNull(min, max).forEach { v ->
                drawLine(limit, Offset(0f, y(v)), Offset(size.width, y(v)), strokeWidth = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
            }
        }
    }
}

/** Sayı: tam sayıysa ondalıksız, değilse en çok iki basamak. */
private fun num(v: Double): String = if (v == Math.floor(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else "%.2f".format(v).trimEnd('0').trimEnd('.', ',')

@Composable
private fun SensorEditor(s: SensorConfig, onChange: (SensorConfig) -> Unit, onRemove: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.key, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = onRemove) { Text(tr("Kaldır"), color = MaterialTheme.colorScheme.error) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(s.name.orEmpty(), { onChange(s.copy(name = it.ifBlank { null })) }, singleLine = true, label = { Text(tr("Ad")) }, modifier = Modifier.weight(2f))
            OutlinedTextField(s.unit.orEmpty(), { onChange(s.copy(unit = it.ifBlank { null })) }, singleLine = true, label = { Text(tr("Birim")) }, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            fun num(text: String) = text.replace(',', '.').toDoubleOrNull()
            var min by remember(s.key) { mutableStateOf(s.min?.let(::num).orEmpty()) }
            var max by remember(s.key) { mutableStateOf(s.max?.let(::num).orEmpty()) }
            OutlinedTextField(
                min, { min = it; onChange(s.copy(min = num(it))) }, singleLine = true, label = { Text(tr("Alt eşik")) }, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            OutlinedTextField(
                max, { max = it; onChange(s.copy(max = num(it))) }, singleLine = true, label = { Text(tr("Üst eşik")) }, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        }
    }
}
