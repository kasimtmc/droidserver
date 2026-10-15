package dev.droidserver.app.ui.config

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.droidserver.app.AppGraph
import dev.droidserver.app.data.NetUtil
import dev.droidserver.app.ui.common.ErrorText
import dev.droidserver.app.ui.common.ScreenScaffold
import dev.droidserver.app.ui.common.SectionCard
import dev.droidserver.app.ui.common.TipCard
import dev.droidserver.app.ui.common.copySecret
import dev.droidserver.app.ui.common.copyToClipboard
import dev.droidserver.app.ui.common.saveConfigChange
import dev.droidserver.app.ui.wizard.WizardViewModel
import dev.droidserver.core.config.CollectionConfig
import dev.droidserver.core.config.ConfigSecrets
import dev.droidserver.core.config.FieldType
import dev.droidserver.core.config.MqttConfig
import dev.droidserver.core.config.MqttRoute
import dev.droidserver.core.i18n.tr
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** MQTT (IoT): cihazların bağlanacağı adres ve kimlik, konu → koleksiyon yönlendirmeleri, kayıt olayları, durum ve deneme. */
@Composable
fun MqttScreen(g: AppGraph, wizard: WizardViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val active by wizard.active.collectAsState()
    val initial = active?.mqtt ?: MqttConfig()
    val collections = active?.collections.orEmpty()

    var enabled by remember(initial) { mutableStateOf(initial.enabled) }
    var port by remember(initial) { mutableStateOf(initial.port.toString()) }
    var username by remember(initial) { mutableStateOf(initial.username) }
    var password by remember { mutableStateOf("") }
    var routes by remember(initial) { mutableStateOf(initial.routes) }
    var events by remember(initial) { mutableStateOf(initial.events) }
    var errors by remember { mutableStateOf<List<String>>(emptyList()) }
    var testTopic by remember { mutableStateOf("ev/salon/lamba") }
    var testPayload by remember { mutableStateOf("on") }
    var tick by remember { mutableIntStateOf(0) }
    // Durum üç saniyede bir yenilenir (sunucu sonradan başlarsa da görünür).
    val broker = tick.let { g.engine.mqtt.broker }
    val time = remember { DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()) }
    LaunchedEffect(Unit) { while (true) { delay(3_000); tick++ } }

    fun savedPassword(): String? = initial.password.takeIf { it.isNotEmpty() }?.let { if (ConfigSecrets.isRef(it)) g.secrets.get(it.removePrefix(ConfigSecrets.PREFIX)) else it }

    fun save() = scope.launch {
        val pw = password.ifEmpty { initial.password.ifEmpty { if (enabled) strongPassword() else "" } }
        errors = saveConfigChange(ctx, g, wizard, deploy = true, scope = listOf("mqtt")) { c ->
            c.copy(mqtt = initial.copy(enabled = enabled, port = port.toIntOrNull() ?: 0, username = username.trim(), password = pw, routes = routes, events = events))
        }
        if (errors.isEmpty()) password = ""
    }

    ScreenScaffold(tr("MQTT (IoT)"), subtitle = if (initial.enabled) tr("Açık · port {0}", initial.port) else tr("Kapalı"), onBack = onBack) { modifier ->
        Column(modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TipCard(
                g, "mqtt-intro",
                tr("Sensörler ve ESP32 gibi cihazlar MQTT ile mesaj gönderir; mesajlar koleksiyona kayıt olarak yazılır, otomasyonlar ve webhook'lar tetiklenir. Cihazlara buradan veya API'den komut da gönderebilirsiniz."),
            )
            SectionCard(tr("Bağlantı")) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text(tr("MQTT açık"), Modifier.weight(1f)); Switch(enabled, { enabled = it }) }
                val ip = NetUtil.localIpv4().firstOrNull()
                if (initial.enabled && ip != null) {
                    Text("mqtt://$ip:${initial.port}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { copyToClipboard(ctx, tr("adres"), "$ip:${initial.port}") }) { Text(tr("Adresi kopyala")) }
                        savedPassword()?.let { pw -> TextButton(onClick = { copySecret(ctx, tr("MQTT parolası"), pw) }) { Text(tr("Parolayı kopyala")) } }
                    }
                }
                OutlinedTextField(port, { port = it.filter(Char::isDigit) }, singleLine = true, label = { Text(tr("Port")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(username, { username = it.trim() }, singleLine = true, label = { Text(tr("Kullanıcı adı")) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    password, { password = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(), label = { Text(tr("Parola")) },
                    supportingText = {
                        Text(if (initial.password.isNotEmpty() && password.isEmpty()) tr("Kayıtlı parola korunuyor; değiştirmek için yenisini yazın.") else tr("Boş bırakılırsa güçlü bir parola üretilir. Şifreli depoda saklanır."))
                    },
                )
                Text(
                    tr("Bağlantı şifresizdir (düz TCP): yalnızca yerel ağda kullanın, modemde bu portu internete açmayın. Hatalı parola denemeleri güvenlik duvarına sayılır."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SectionCard(tr("Mesajları kaydet")) {
                Text(
                    tr("Konuya uyan mesaj koleksiyona eklenir. JSON nesnesinin alanları koleksiyonun alanlarına yazılır; düz değer (23.5) değer alanına. + bir bölümü, # kalanını eşler (sensors/+/temp)."),
                    style = MaterialTheme.typography.bodySmall,
                )
                routes.forEachIndexed { i, r ->
                    RouteEditor(r, collections, onChange = { n -> routes = routes.toMutableList().also { it[i] = n } }, onRemove = { routes = routes.filterIndexed { j, _ -> j != i } })
                }
                TextButton(enabled = collections.isNotEmpty(), onClick = { routes = routes + MqttRoute("sensors/+/temp", collections.first().name) }) { Text(tr("Yönlendirme ekle")) }
                if (collections.isEmpty()) Text(tr("Önce bir koleksiyon oluşturun."), style = MaterialTheme.typography.bodySmall)
            }
            SectionCard(tr("Kayıt olaylarını yayınla")) {
                Text(tr("Seçilen koleksiyonlarda eklenen, değişen, silinen kayıt {0}/<koleksiyon>/<create|update|delete> konusuna JSON olarak yayınlanır.", initial.eventPrefix), style = MaterialTheme.typography.bodySmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    collections.forEach { c ->
                        val on = c.name in events
                        FilterChip(selected = on, onClick = { events = if (on) events - c.name else events + c.name }, label = { Text(c.name) })
                    }
                }
            }
            errors.forEach { ErrorText(it) }
            Button(onClick = { save() }, modifier = Modifier.fillMaxWidth()) { Text(tr("Kaydet ve deploy et")) }

            if (broker != null) {
                SectionCard(tr("Durum")) {
                    Text(tr("{0} mesaj alındı · {1} iletildi · {2} hata", broker.received.get(), broker.delivered.get(), broker.failed.get()), style = MaterialTheme.typography.bodyMedium)
                    val clients = broker.clients()
                    Text(tr("Bağlı cihazlar ({0})", clients.size), style = MaterialTheme.typography.labelLarge)
                    clients.forEach { c -> Text("${c.id} · ${c.address}" + if (c.subscriptions.isEmpty()) "" else " · " + c.subscriptions.joinToString(), style = MaterialTheme.typography.bodySmall) }
                    Text(tr("Son mesajlar"), style = MaterialTheme.typography.labelLarge)
                    broker.recent().take(10).forEach { m ->
                        Text("${time.format(m.at)} ${m.topic} (${m.bytes} B) → ${m.result}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
                SectionCard(tr("Cihazlara mesaj gönder")) {
                    OutlinedTextField(testTopic, { testTopic = it.trim() }, singleLine = true, label = { Text(tr("Konu")) }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(testPayload, { testPayload = it }, singleLine = true, label = { Text(tr("İçerik")) }, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(enabled = testTopic.isNotBlank() && '+' !in testTopic && '#' !in testTopic, onClick = {
                        val n = broker.publish(testTopic, testPayload.toByteArray())
                        broker.note(testTopic, testPayload.length, tr("uygulamadan; {0} cihaza", n))
                        tick++
                    }) { Text(tr("Gönder")) }
                }
            }
        }
    }
}

@Composable
private fun RouteEditor(r: MqttRoute, collections: List<CollectionConfig>, onChange: (MqttRoute) -> Unit, onRemove: () -> Unit) {
    val c = collections.firstOrNull { it.name == r.collection }
    val scalarFields = c?.fields?.filter { it.type != FieldType.FILE && it.type != FieldType.RELATION }?.map { it.name }.orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(r.topic, { onChange(r.copy(topic = it.trim())) }, singleLine = true, label = { Text(tr("Konu")) }, modifier = Modifier.weight(1f))
            IconButton(onClick = onRemove) { Icon(Icons.Default.Delete, contentDescription = tr("Kaldır")) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MqttPicker(tr("Koleksiyon: {0}", r.collection), collections.map { it.name }, allowNone = false) { onChange(MqttRoute(r.topic, it!!)) }
            MqttPicker(tr("Değer alanı: {0}", r.valueField ?: tr("yok")), scalarFields) { onChange(r.copy(valueField = it)) }
            MqttPicker(tr("Konu alanı: {0}", r.topicField ?: tr("yok")), scalarFields) { onChange(r.copy(topicField = it)) }
        }
    }
}

@Composable
private fun MqttPicker(label: String, options: List<String>, allowNone: Boolean = true, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text(label) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (allowNone) DropdownMenuItem(text = { Text(tr("Yok")) }, onClick = { open = false; onPick(null) })
            options.forEach { o -> DropdownMenuItem(text = { Text(o) }, onClick = { open = false; onPick(o) }) }
        }
    }
}
