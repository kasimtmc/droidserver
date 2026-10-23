package dev.droidserver.app.ui.config

import android.content.Intent
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
import androidx.compose.material3.AlertDialog
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
import dev.droidserver.app.ui.common.WarningText
import dev.droidserver.app.ui.common.copySecret
import dev.droidserver.app.ui.common.copyToClipboard
import dev.droidserver.app.ui.common.saveConfigChange
import dev.droidserver.app.ui.wizard.WizardViewModel
import dev.droidserver.core.config.CollectionConfig
import dev.droidserver.core.config.ConfigSecrets
import dev.droidserver.core.config.FieldType
import dev.droidserver.core.config.MqttConfig
import dev.droidserver.core.config.MqttDevice
import dev.droidserver.core.config.MqttMessage
import dev.droidserver.core.config.MqttRoute
import dev.droidserver.core.config.MqttScene
import dev.droidserver.core.i18n.tr
import dev.droidserver.core.mqtt.runScene
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    var tls by remember(initial) { mutableStateOf(initial.tls) }
    var tlsPort by remember(initial) { mutableStateOf(initial.tlsPort.toString()) }
    var plain by remember(initial) { mutableStateOf(initial.plain) }
    var ca by remember { mutableStateOf<Pair<String, String>?>(null) }
    var username by remember(initial) { mutableStateOf(initial.username) }
    var password by remember { mutableStateOf("") }
    var routes by remember(initial) { mutableStateOf(initial.routes) }
    var events by remember(initial) { mutableStateOf(initial.events) }
    var devices by remember(initial) { mutableStateOf(initial.devices) }
    var scenes by remember(initial) { mutableStateOf(initial.scenes) }
    var editingScene by remember { mutableStateOf<MqttScene?>(null) }
    var sceneResult by remember { mutableStateOf<String?>(null) }
    var sharedLogin by remember(initial) { mutableStateOf(initial.devices.isEmpty() || initial.password.isNotEmpty()) }
    var newDevice by remember { mutableStateOf("") }
    var errors by remember { mutableStateOf<List<String>>(emptyList()) }
    var testTopic by remember { mutableStateOf("ev/salon/lamba") }
    var testPayload by remember { mutableStateOf("on") }
    var tick by remember { mutableIntStateOf(0) }
    // Durum üç saniyede bir yenilenir (sunucu sonradan başlarsa da görünür).
    val broker = tick.let { g.engine.mqtt.broker }
    val time = remember { DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()) }
    LaunchedEffect(Unit) { while (true) { delay(3_000); tick++ } }

    fun savedPassword(): String? = initial.password.takeIf { it.isNotEmpty() }?.let { if (ConfigSecrets.isRef(it)) g.secrets.get(it.removePrefix(ConfigSecrets.PREFIX)) else it }

    /** Kayıtlı cihazın parolası şifreli depodan; yeni eklenen (henüz kaydedilmemiş) düz metin. */
    fun devicePassword(d: MqttDevice): String? = if (ConfigSecrets.isRef(d.password)) g.secrets.get(d.password.removePrefix(ConfigSecrets.PREFIX)) else d.password

    fun save() = scope.launch {
        // Cihaz listesi varken ortak giriş kapatılabilir (parola boş); yoksa ortak parola gerekir.
        val pw = if (devices.isNotEmpty() && !sharedLogin) "" else password.ifEmpty { initial.password.ifEmpty { if (enabled) strongPassword() else "" } }
        errors = saveConfigChange(ctx, g, wizard, deploy = true, scope = listOf("mqtt")) { c ->
            c.copy(
                mqtt = initial.copy(
                    enabled = enabled, port = port.toIntOrNull() ?: 0, plain = plain || !tls, tls = tls, tlsPort = tlsPort.toIntOrNull() ?: 0,
                    username = username.trim(), password = pw, routes = routes, events = events, devices = devices, scenes = scenes,
                ),
            )
        }
        if (errors.isEmpty()) password = ""
    }

    ScreenScaffold(tr("MQTT (IoT)"), subtitle = if (initial.enabled) tr("Açık · port {0}", listOfNotNull(initial.port.toString().takeIf { initial.plain || !initial.tls }, "${initial.tlsPort} (TLS)".takeIf { initial.tls }).joinToString()) else tr("Kapalı"), onBack = onBack) { modifier ->
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
                    tr("MQTT yalnızca yerel ağda çalışır: internetten gelen bağlantılar reddedilir (Tailscale kabul edilir). Hatalı parola denemeleri güvenlik duvarına sayılır. Düz bağlantı şifresizdir; parolaların ağda okunmaması için TLS'i açın."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SectionCard(tr("TLS (şifreli bağlantı)")) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text(tr("TLS açık"), Modifier.weight(1f)); Switch(tls, { tls = it }) }
                if (tls) {
                    OutlinedTextField(tlsPort, { tlsPort = it.filter(Char::isDigit) }, singleLine = true, label = { Text(tr("TLS portu")) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("Düz bağlantı da açık (port {0})", port), Modifier.weight(1f)); Switch(plain, { plain = it })
                    }
                    Text(
                        tr("Telefon kendi yerel kök sertifikasını üretir; sunucu sertifikası telefonun yerel IP adresleri için bu kökle imzalanır ve adres değişince kendiliğinden yenilenir. Kökü cihazlara bir kez yükleyin: mosquitto için --cafile, ESP32'de setCACert, Home Assistant'ta MQTT → Gelişmiş → özel CA."),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    val root = g.engine.mqttCa
                    LaunchedEffect(root) { ca = root?.let { r -> withContext(Dispatchers.IO) { runCatching { r.root.fingerprint to r.rootPem }.getOrNull() } } }
                    ca?.let { (fingerprint, pem) ->
                        Text(tr("Kökün SHA-256 parmak izi"), style = MaterialTheme.typography.labelMedium)
                        Text(fingerprint, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "droidserver-ca.pem").putExtra(Intent.EXTRA_TEXT, pem), tr("Kök sertifika")))
                            }) { Text(tr("Kökü paylaş")) }
                            TextButton(onClick = { copyToClipboard(ctx, tr("Kök sertifika"), pem) }) { Text(tr("Kopyala")) }
                        }
                        val ip = NetUtil.localIpv4().firstOrNull()
                        if (initial.tls && ip != null) Text("mqtts://$ip:${initial.tlsPort}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyLarge)
                    }
                    if (root == null) WarningText(tr("Bu ortamda yerel kök sertifika yok; TLS açılamaz."))
                }
            }
            SectionCard(tr("Cihazlar")) {
                Text(
                    tr("Her cihaza ayrı kullanıcı adı (cihaz adı) ve parola verin: birini silmek diğerlerini etkilemez. Parola kaydedince şifreli depoya taşınır."),
                    style = MaterialTheme.typography.bodySmall,
                )
                devices.forEach { d ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(d.name, Modifier.weight(1f), fontFamily = FontFamily.Monospace)
                        devicePassword(d)?.let { pw -> TextButton(onClick = { copySecret(ctx, tr("{0} parolası", d.name), pw) }) { Text(tr("Parolayı kopyala")) } }
                        TextButton(onClick = { devices = devices - d }) { Text(tr("Sil"), color = MaterialTheme.colorScheme.error) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(newDevice, { newDevice = it.trim() }, singleLine = true, label = { Text(tr("Cihaz adı")) }, modifier = Modifier.weight(1f))
                    TextButton(enabled = newDevice.isNotEmpty() && devices.none { it.name == newDevice }, onClick = {
                        devices = devices + MqttDevice(newDevice, strongPassword())
                        newDevice = ""
                    }) { Text(tr("Ekle")) }
                }
                if (devices.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("Ortak kullanıcı adı ve parolayla girişe de izin ver"), Modifier.weight(1f))
                        Switch(sharedLogin, { sharedLogin = it })
                    }
                }
            }
            SectionCard(tr("Sahneler")) {
                Text(
                    tr("Tek dokunuşla gönderilen mesaj grupları (örn. \"Akşam\": salon lambası açık, perde kapalı). Buradan, ana ekrandaki Sahneler widget'ından, panelden veya POST /_mqtt/scenes/<ad> ile çalışır."),
                    style = MaterialTheme.typography.bodySmall,
                )
                scenes.forEach { sc ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("{0} ({1} mesaj)", sc.name, sc.messages.size), Modifier.weight(1f))
                        if (broker != null && sc in initial.scenes) TextButton(onClick = {
                            scope.launch(Dispatchers.IO) { sceneResult = tr("{0}: {1} cihaza gitti", sc.name, broker.runScene(sc)); tick++ }
                        }) { Text(tr("Çalıştır")) }
                        TextButton(onClick = { editingScene = sc }) { Text(tr("Düzenle")) }
                    }
                }
                sceneResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = { editingScene = MqttScene("", emptyList()) }) { Text(tr("Sahne ekle")) }
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
                    clients.forEach { c -> Text("${c.id} · ${c.address}" + (if (c.tls) " · TLS" else "") + (c.user?.let { " · $it" } ?: "") + if (c.subscriptions.isEmpty()) "" else " · " + c.subscriptions.joinToString(), style = MaterialTheme.typography.bodySmall) }
                    Text(tr("Son mesajlar"), style = MaterialTheme.typography.labelLarge)
                    broker.recent().take(10).forEach { m ->
                        Text("${time.format(m.at)} ${m.topic} (${m.bytes} B) → ${m.result}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
                SectionCard(tr("Cihazlara mesaj gönder")) {
                    OutlinedTextField(testTopic, { testTopic = it.trim() }, singleLine = true, label = { Text(tr("Konu")) }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(testPayload, { testPayload = it }, singleLine = true, label = { Text(tr("İçerik")) }, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(enabled = testTopic.isNotBlank() && '+' !in testTopic && '#' !in testTopic, onClick = {
                        val topic = testTopic
                        val payload = testPayload
                        // Soket yazımı ana iş parçacığında yapılamaz (Android engeller).
                        scope.launch(Dispatchers.IO) {
                            val n = broker.publish(topic, payload.toByteArray())
                            broker.note(topic, payload.length, tr("uygulamadan; {0} cihaza", n))
                            tick++
                        }
                    }) { Text(tr("Gönder")) }
                }
            }
        }
    }
    editingScene?.let { sc ->
        var name by remember(sc) { mutableStateOf(sc.name) }
        var lines by remember(sc) { mutableStateOf(sc.messages.joinToString("\n") { "${it.topic} = ${it.payload}" }) }
        AlertDialog(
            onDismissRequest = { editingScene = null },
            title = { Text(if (sc.name.isEmpty()) tr("Yeni sahne") else sc.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(tr("Ad")) }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        lines, { lines = it }, label = { Text(tr("Mesajlar")) }, minLines = 3, modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text(tr("Satır başına bir mesaj: konu = içerik (örn. ev/salon/lamba = on)")) },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val messages = lines.lines().map { it.trim() }.filter { it.isNotEmpty() }
                        .map { MqttMessage(it.substringBefore('=').trim(), it.substringAfter('=', "").trim()) }
                    val updated = MqttScene(name.trim(), messages)
                    scenes = if (sc.name.isEmpty()) scenes + updated else scenes.map { if (it == sc) updated else it }
                    editingScene = null
                }) { Text(tr("Tamam")) }
            },
            dismissButton = {
                Row {
                    if (sc.name.isNotEmpty()) TextButton(onClick = { scenes = scenes - sc; editingScene = null }) { Text(tr("Sil"), color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { editingScene = null }) { Text(tr("Vazgeç")) }
                }
            },
        )
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
