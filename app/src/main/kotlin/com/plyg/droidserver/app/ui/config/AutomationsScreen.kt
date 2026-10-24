package com.plyg.droidserver.app.ui.config

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.runtime.mutableIntStateOf
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
import com.plyg.droidserver.app.AppGraph
import com.plyg.droidserver.app.ui.common.EmptyState
import com.plyg.droidserver.app.ui.common.ErrorText
import com.plyg.droidserver.app.ui.common.ListRow
import com.plyg.droidserver.app.ui.common.Pill
import com.plyg.droidserver.app.ui.common.ScreenScaffold
import com.plyg.droidserver.app.ui.common.SectionCard
import com.plyg.droidserver.app.ui.common.SectionHeader
import com.plyg.droidserver.app.ui.common.SoftCard
import com.plyg.droidserver.app.ui.common.TipCard
import com.plyg.droidserver.app.ui.common.saveConfigChange
import com.plyg.droidserver.app.ui.theme.LocalStatusColors
import com.plyg.droidserver.app.ui.wizard.WizardViewModel
import com.plyg.droidserver.core.automation.AutomationRun
import com.plyg.droidserver.core.config.AutomationAction
import com.plyg.droidserver.core.config.AutomationActionType
import com.plyg.droidserver.core.config.FieldType
import com.plyg.droidserver.core.config.AutomationConfig
import com.plyg.droidserver.core.config.AutomationTrigger
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.config.WebhookConfig
import com.plyg.droidserver.core.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Otomasyon kuralları: liste, son çalışmalar, "şimdi dene" ve kod yazmadan kural düzenleme. */
@Composable
fun AutomationsScreen(g: AppGraph, wizard: WizardViewModel, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val active by wizard.active.collectAsState()
    val rules = active?.automations.orEmpty()
    var editing by remember { mutableStateOf<AutomationConfig?>(null) }
    var creating by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    val runs = remember(refresh, rules) { g.engine.automations.runs() }

    val edit = editing
    if (edit != null || creating) {
        AutomationEditor(g, wizard, active ?: ServerConfig(), edit) { editing = null; creating = false; refresh++ }
        return
    }

    ScreenScaffold(
        tr("Otomasyonlar"),
        subtitle = tr("{0} kural", rules.size),
        onBack = onBack,
        actions = { IconButton(onClick = { creating = true }) { Icon(Icons.Default.Add, contentDescription = tr("Kural ekle")) } },
    ) { modifier ->
        LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                if (rules.isEmpty()) {
                    EmptyState(
                        Icons.Default.AutoMode, tr("Henüz otomasyon yok"),
                        tr("Kod yazmadan \"şu olunca şunu yap\" kuralları kurun: yeni sipariş gelince e-posta gönder, durumu değişince bildirim at, her sabah geciken kayıtları işaretle."),
                        action = tr("Kural ekle"), onAction = { creating = true },
                    )
                } else {
                    TipCard(g, "automations-intro", tr("Kurallar kaydedilince deploy edilir. \"Şimdi dene\" zamanlanmış kuralı hemen çalıştırır; kayıt olayı kuralını koşula uyan son kayıtla dener."))
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            items(rules, key = { it.name }) { rule ->
                SoftCard(onClick = { editing = rule }) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(rule.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            if (!rule.enabled) Pill(tr("Kapalı"))
                        }
                        Text(triggerText(rule), style = MaterialTheme.typography.bodyMedium)
                        rule.condition?.takeIf { it.isNotBlank() }?.let { Text(tr("Koşul: {0}", it), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                        Text(rule.actions.joinToString(" → ") { it.type.title }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(enabled = rule.enabled, onClick = {
                                scope.launch {
                                    message = runCatching { withContext(Dispatchers.IO) { g.engine.automations.runNow(rule.name) } }
                                        .fold({ r -> if (r.ok) tr("{0}: {1}", rule.name, r.message) else tr("{0} çalışmadı: {1}", rule.name, r.message) }, { it.message })
                                    refresh++
                                }
                            }) { Text(tr("Şimdi dene")) }
                            TextButton(onClick = { editing = rule }) { Text(tr("Düzenle")) }
                        }
                    }
                }
            }
            if (runs.isNotEmpty()) {
                item { SectionHeader(tr("Son çalışmalar")) }
                items(runs.take(30)) { RunRow(it) }
            }
        }
    }
}

private val TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm:ss").withZone(ZoneId.systemDefault())

@Composable
private fun RunRow(r: AutomationRun) {
    val status = LocalStatusColors.current
    ListRow(
        if (r.ok) Icons.Default.AutoMode else Icons.Default.Delete,
        "${r.rule} · ${r.trigger}",
        subtitle = "${TIME.format(r.at)} · ${r.message}",
        iconTint = if (r.ok) status.success else MaterialTheme.colorScheme.error,
    )
}

private fun triggerText(rule: AutomationConfig): String {
    val t = rule.trigger
    return if (t.onMqtt) {
        tr("MQTT mesajında: {0}", t.mqtt!!)
    } else if (t.scheduled) {
        tr("Zamanlama: {0}", SCHEDULES.firstOrNull { it.second == t.schedule }?.first ?: t.schedule!!) +
            (t.collection?.let { tr(" · {0}", it) } ?: "")
    } else {
        tr("{0} koleksiyonunda: {1}", t.collection ?: "?", t.events.joinToString { eventTitle(it) })
    }
}

private fun eventTitle(e: String) = when (e) {
    "create" -> tr("eklenince")
    "update" -> tr("güncellenince")
    "delete" -> tr("silinince")
    else -> e
}

private val SCHEDULES get() = listOf(
    tr("Her saat başı") to "0 * * * *",
    tr("Her gün 08:00") to "0 8 * * *",
    tr("Her gece 03:00") to "0 3 * * *",
    tr("Pazartesi 09:00") to "0 9 * * 1",
)

/** Kural ekleme/düzenleme; kaydedince deploy edilir. */
@Composable
private fun AutomationEditor(g: AppGraph, wizard: WizardViewModel, cfg: ServerConfig, initial: AutomationConfig?, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val collections = cfg.collections.map { it.name }
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var enabled by remember { mutableStateOf(initial?.enabled ?: true) }
    var scheduled by remember { mutableStateOf(initial?.trigger?.scheduled ?: false) }
    var onMqtt by remember { mutableStateOf(initial?.trigger?.onMqtt ?: false) }
    var mqttTopic by remember { mutableStateOf(initial?.trigger?.mqtt ?: "ev/+/hareket") }
    var collection by remember { mutableStateOf(initial?.trigger?.collection ?: collections.firstOrNull()) }
    var events by remember { mutableStateOf(initial?.trigger?.events?.toSet() ?: setOf("create")) }
    var schedule by remember { mutableStateOf(initial?.trigger?.schedule ?: "0 8 * * *") }
    var condition by remember { mutableStateOf(initial?.condition.orEmpty()) }
    val actions = remember { mutableStateListOf<AutomationAction>().apply { addAll(initial?.actions ?: listOf(AutomationAction(AutomationActionType.LOG, body = "{rule}: {id}"))) } }
    var errors by remember { mutableStateOf<List<String>>(emptyList()) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun build() = AutomationConfig(
        name = name.trim(),
        trigger = AutomationTrigger(
            collection = collection.takeIf { !onMqtt },
            events = if (scheduled || onMqtt) listOf("create") else WebhookConfig.EVENTS.filter { it in events },
            schedule = schedule.trim().takeIf { scheduled },
            mqtt = mqttTopic.trim().takeIf { onMqtt },
        ),
        condition = condition.trim().ifEmpty { null }.takeIf { !onMqtt },
        actions = actions.toList(),
        enabled = enabled,
    )

    fun save(remove: Boolean = false) = scope.launch {
        val rule = build()
        errors = saveConfigChange(ctx, g, wizard, deploy = true, scope = listOf("automations")) { c ->
            val rest = c.automations.filter { it.name != initial?.name }
            c.copy(automations = if (remove) rest else if (initial == null) c.automations + rule else c.automations.map { if (it.name == initial.name) rule else it })
        }
        if (errors.isEmpty()) onDone()
    }

    ScreenScaffold(
        initial?.name ?: tr("Yeni otomasyon"), onBack = onDone,
        actions = { if (initial != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, contentDescription = tr("Kuralı sil")) } },
    ) { modifier ->
        Column(modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard(tr("Kural")) {
                OutlinedTextField(
                    name, { name = it.trim() }, label = { Text(tr("Ad")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text(tr("Harf, rakam, - ve _ (örn. yeni-siparis)")) },
                )
                Row(verticalAlignment = Alignment.CenterVertically) { Text(tr("Etkin"), Modifier.weight(1f)); Switch(enabled, { enabled = it }) }
            }

            SectionCard(tr("Ne zaman?")) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!scheduled && !onMqtt, { scheduled = false; onMqtt = false }, label = { Text(tr("Kayıt olayında")) })
                    FilterChip(scheduled, { scheduled = true; onMqtt = false }, label = { Text(tr("Belirli zamanlarda")) })
                    if (cfg.mqtt.enabled || onMqtt) FilterChip(onMqtt, { onMqtt = true; scheduled = false }, label = { Text(tr("MQTT mesajında")) })
                }
                if (onMqtt) {
                    OutlinedTextField(
                        mqttTopic, { mqttTopic = it.trim() }, label = { Text(tr("Konu süzgeci")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text(tr("Cihazdan gelen mesaj kayda yazılmadan kuralı tetikler; + bir bölümü, # kalanını eşler. İçerik JSON ise alanları {alan}, ayrıca {topic} ve {payload}.")) },
                    )
                } else {
                    Text(if (scheduled) tr("Koleksiyon (isteğe bağlı: koşula uyan kayıtlar sayılır ve güncellenir)") else tr("Koleksiyon"), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (scheduled) FilterChip(collection == null, { collection = null }, label = { Text(tr("Yok")) })
                        collections.forEach { c -> FilterChip(collection == c, { collection = c }, label = { Text(c) }) }
                    }
                    if (collections.isEmpty()) ErrorText(tr("Önce sihirbazda bir koleksiyon ekleyin."))
                    if (scheduled) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SCHEDULES.forEach { (label, cron) -> FilterChip(schedule == cron, { schedule = cron }, label = { Text(label) }) }
                        }
                        OutlinedTextField(
                            schedule, { schedule = it }, label = { Text(tr("Cron ifadesi")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            supportingText = { Text(tr("dakika saat gün ay haftanın-günü (telefonun saatiyle)")) },
                        )
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            WebhookConfig.EVENTS.forEach { e ->
                                FilterChip(e in events, { events = if (e in events) events - e else events + e }, label = { Text(eventTitle(e)) })
                            }
                        }
                    }
                    OutlinedTextField(
                        condition, { condition = it }, label = { Text(tr("Koşul (isteğe bağlı)")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        enabled = collection != null,
                        supportingText = { Text(tr("Liste filtresi gibi: status=new veya qty[gte]=10&paid=true")) },
                    )
                }
            }

            SectionHeader(tr("Ne yapılsın?"))
            Text(tr("Metinlerde {alan} kaydın değeriyle dolar (örn. {title}, {email}); ayrıca {now}, {count}, {rule}, {collection}."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            actions.forEachIndexed { i, a ->
                ActionCard(cfg, collection.takeIf { !onMqtt }, scheduled || onMqtt, a, onChange = { actions[i] = it }, onRemove = { actions.removeAt(i); Unit }.takeIf { actions.size > 1 })
            }
            OutlinedButton(onClick = { actions += AutomationAction(AutomationActionType.LOG, body = "") }, enabled = actions.size < 10) {
                Icon(Icons.Default.Add, contentDescription = null); Text(tr("Eylem ekle"))
            }

            errors.forEach { ErrorText(it) }
            Button(onClick = { save() }, modifier = Modifier.fillMaxWidth(), enabled = name.isNotBlank()) { Text(tr("Kaydet ve deploy et")) }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(tr("Kural silinsin mi?")) },
            text = { Text(tr("'{0}' kuralı silinir ve sunucu yeniden deploy edilir.", initial?.name.orEmpty())) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; save(remove = true) }) { Text(tr("Sil")) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(tr("Vazgeç")) } },
        )
    }
}

@Composable
private fun ActionCard(cfg: ServerConfig, collection: String?, scheduled: Boolean, a: AutomationAction, onChange: (AutomationAction) -> Unit, onRemove: (() -> Unit)?) {
    val available = AutomationActionType.entries.filter { t ->
        when (t) {
            AutomationActionType.EMAIL -> cfg.email.enabled || a.type == t
            AutomationActionType.PUSH -> (cfg.push.enabled && cfg.auth.users != null) || a.type == t
            AutomationActionType.WEBHOOK -> cfg.webhooks.isNotEmpty() || a.type == t
            AutomationActionType.UPDATE -> collection != null || a.type == t
            AutomationActionType.MQTT -> cfg.mqtt.enabled || a.type == t
            AutomationActionType.AI -> (cfg.ai.enabled && collection != null && !scheduled) || a.type == t
            AutomationActionType.PAYMENT -> (cfg.payments.enabled && collection != null && !scheduled) || a.type == t
            else -> true
        }
    }
    SoftCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(a.type.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                onRemove?.let { IconButton(onClick = it) { Icon(Icons.Default.Delete, contentDescription = tr("Eylemi kaldır")) } }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                available.forEach { t -> FilterChip(a.type == t, { if (t != a.type) onChange(AutomationAction(t)) }, label = { Text(t.title) }) }
            }
            fun field(label: String, value: String?, hint: String? = null, single: Boolean = true, set: (String) -> AutomationAction) =
                @Composable {
                    OutlinedTextField(
                        value.orEmpty(), { onChange(set(it)) }, label = { Text(label) }, singleLine = single, modifier = Modifier.fillMaxWidth(),
                        supportingText = hint?.let { h -> { Text(h) } }, minLines = if (single) 1 else 3,
                    )
                }
            when (a.type) {
                AutomationActionType.EMAIL -> {
                    field(tr("Alıcı"), a.to, tr("Adres veya kayıttaki alan, örn. {email}")) { a.copy(to = it) }()
                    field(tr("Konu"), a.subject) { a.copy(subject = it) }()
                    field(tr("Metin"), a.body, single = false) { a.copy(body = it) }()
                }
                AutomationActionType.PUSH -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!scheduled) FilterChip(a.to == AutomationAction.OWNER, { onChange(a.copy(to = AutomationAction.OWNER)) }, label = { Text(tr("Kaydın sahibi")) })
                        FilterChip(a.to != AutomationAction.OWNER, { onChange(a.copy(to = "")) }, label = { Text(tr("Belirli kullanıcı")) })
                    }
                    if (a.to != AutomationAction.OWNER) field(tr("Kullanıcı kimliği"), a.to, tr("Sayı veya kayıttaki alan, örn. {customer}")) { a.copy(to = it) }()
                    field(tr("Başlık"), a.subject) { a.copy(subject = it) }()
                    field(tr("Metin"), a.body) { a.copy(body = it) }()
                    field(tr("Açılacak adres (isteğe bağlı)"), a.url) { a.copy(url = it) }()
                }
                AutomationActionType.WEBHOOK -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    cfg.webhooks.forEach { h -> FilterChip(a.webhook == h.name, { onChange(a.copy(webhook = h.name)) }, label = { Text(h.name) }) }
                }
                AutomationActionType.UPDATE -> {
                    Text(if (scheduled) tr("Koşula uyan tüm kayıtlarda (en çok 1000):") else tr("Tetikleyen kayıtta:"), style = MaterialTheme.typography.bodySmall)
                    ValuesEditor(cfg, collection, a.values) { onChange(a.copy(values = it)) }
                }
                AutomationActionType.CREATE -> {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        cfg.collections.forEach { c -> FilterChip(a.collection == c.name, { onChange(a.copy(collection = c.name, values = emptyMap())) }, label = { Text(c.name) }) }
                    }
                    if (a.collection != null) ValuesEditor(cfg, a.collection, a.values) { onChange(a.copy(values = it)) }
                }
                AutomationActionType.LOG -> field(tr("Log metni"), a.body) { a.copy(body = it) }()
                AutomationActionType.AI -> {
                    field(tr("İstem"), a.body, tr("Örn. Şu metni bir cümleyle özetle: {body}"), single = false) { a.copy(body = it) }()
                    Text(tr("Yanıtın yazılacağı alan (tetikleyen kayıtta):"), style = MaterialTheme.typography.bodySmall)
                    val targets = cfg.collections.firstOrNull { it.name == collection }?.fields.orEmpty()
                        .filter { it.computed == null && it.type in setOf(FieldType.TEXT, FieldType.SELECT, FieldType.MULTISELECT, FieldType.INTEGER, FieldType.REAL, FieldType.BOOLEAN) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        targets.forEach { f -> FilterChip(a.field == f.name, { onChange(a.copy(field = f.name)) }, label = { Text(f.name) }) }
                    }
                    Text(tr("Seçim alanında yanıt seçeneklerden biri olmalı (sınıflandırma). Yerel modelde yavaş olabilir; olay sırayla işlenir."), style = MaterialTheme.typography.bodySmall)
                }
                AutomationActionType.PAYMENT -> {
                    field(tr("Tutar"), a.amount, tr("Sayı, alan ya da hesap: {total}, {service.price}, {product.price} * {quantity}")) { a.copy(amount = it) }()
                    field(tr("Para birimi"), a.currency, tr("Üç harf; boşsa TRY")) { a.copy(currency = it.trim().uppercase().ifEmpty { null }) }()
                    field(tr("Açıklama"), a.subject, tr("Ödeme sayfasında görünür, örn. Sipariş #{id}")) { a.copy(subject = it) }()
                    field(tr("Alıcının e-postası (isteğe bağlı)"), a.to, tr("Örn. {email}")) { a.copy(to = it) }()
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(a.provider == null, { onChange(a.copy(provider = null)) }, label = { Text(tr("İlk sağlayıcı")) })
                        cfg.payments.providers.filter { it.enabled }.forEach { p -> FilterChip(a.provider == p.provider, { onChange(a.copy(provider = p.provider)) }, label = { Text(p.provider.title) }) }
                    }
                    Text(tr("Ödeme sayfasının adresinin yazılacağı alan:"), style = MaterialTheme.typography.bodySmall)
                    val targets = cfg.collections.firstOrNull { it.name == collection }?.fields.orEmpty().filter { it.computed == null && it.type == FieldType.TEXT }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        targets.forEach { f -> FilterChip(a.field == f.name, { onChange(a.copy(field = if (a.field == f.name) null else f.name)) }, label = { Text(f.name) }) }
                    }
                    Text(
                        tr("Sonraki eylemlerde {payment_url} kullanılır (ör. e-postayla gönderin). Güncellemede çalışacaksa koşula {0}[null]=true ekleyin; bağlantı bir kez üretilir.", a.field ?: "payment_url"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                AutomationActionType.MQTT -> {
                    field(tr("Konu"), a.topic, tr("Joker olmadan, örn. ev/salon/lamba veya ev/{oda}/lamba")) { a.copy(topic = it) }()
                    field(tr("İçerik"), a.body, tr("Düz metin veya JSON; {alan} kayıttan dolar")) { a.copy(body = it) }()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("Tutulan mesaj (sonradan bağlanan cihaz da alır)"), Modifier.weight(1f))
                        Switch(a.retain, { onChange(a.copy(retain = it)) })
                    }
                }
            }
        }
    }
}

/** Alan → değer şablonu; yalnızca yazılabilir alanlar listelenir, boş bırakılan alan yazılmaz. */
@Composable
private fun ValuesEditor(cfg: ServerConfig, collection: String?, values: Map<String, String>, onChange: (Map<String, String>) -> Unit) {
    val fields = cfg.collections.firstOrNull { it.name == collection }?.fields.orEmpty()
        .filter { it.computed == null && it.type != com.plyg.droidserver.core.config.FieldType.FILE }
    if (fields.isEmpty()) { ErrorText(tr("Bu koleksiyonda yazılabilir alan yok")); return }
    fields.forEach { f ->
        val on = f.name in values
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(on, { onChange(if (it) values + (f.name to "") else values - f.name) })
            if (on) {
                OutlinedTextField(
                    values[f.name].orEmpty(), { onChange(values + (f.name to it)) }, label = { Text("${f.name} (${f.type.name.lowercase()})") },
                    singleLine = true, modifier = Modifier.padding(start = 8.dp).fillMaxWidth(),
                )
            } else {
                Text(f.name, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
