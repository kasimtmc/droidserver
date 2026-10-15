package dev.droidserver.app.ui.wizard

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.droidserver.app.ui.common.ErrorText
import dev.droidserver.app.ui.common.copySecret
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.config.WebhookConfig
import dev.droidserver.core.i18n.tr

private val EVENT_TITLES = mapOf("create" to tr("Eklenince"), "update" to tr("Değişince"), "delete" to tr("Silinince"))

/** Webhook'lar: kayıt olaylarında dış adrese imzalı POST (Zapier, n8n, Discord, kendi servisiniz). */
@Composable
internal fun WebhooksSection(vm: WizardViewModel, draft: ServerConfig) {
    Text(tr("Webhook'lar"), style = MaterialTheme.typography.titleMedium)
    Text(
        tr("Kayıt eklenince, değişince veya silinince seçtiğiniz adrese bildirim (JSON POST) gider; Zapier, n8n, Make veya kendi " +
            "servisinizle bağlamak için. İmza sırrıyla alıcı isteğin bu sunucudan geldiğini doğrular. Başarısız teslim üç kez " +
            "yeniden denenir; son teslimler Sunucu → Webhook'lar ekranında."),
        style = MaterialTheme.typography.bodySmall,
    )
    draft.webhooks.forEachIndexed { i, h -> WebhookCard(vm, draft, i, h) }
    OutlinedButton(onClick = vm::addWebhook) {
        Icon(Icons.Default.Add, contentDescription = null)
        Text(tr("Webhook ekle"), Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun WebhookCard(vm: WizardViewModel, draft: ServerConfig, index: Int, h: WebhookConfig) {
    val ctx = LocalContext.current
    val path = "webhooks[$index]"
    // Yeni üretilen sır yalnızca bu ekranda, bir kez gösterilir.
    var shownSecret by remember(h.name) { mutableStateOf<String?>(null) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = h.name, onValueChange = { v -> vm.updateWebhook(index) { it.copy(name = v.trim()) } },
                    label = { Text(tr("Ad")) }, singleLine = true, modifier = Modifier.weight(1f),
                    isError = vm.errorAt("$path.name") != null,
                )
                Switch(checked = h.enabled, onCheckedChange = { on -> vm.updateWebhook(index) { it.copy(enabled = on) } })
                IconButton(onClick = { vm.removeWebhook(index) }) { Icon(Icons.Default.Delete, contentDescription = tr("Webhook'u sil")) }
            }
            OutlinedTextField(
                value = h.url, onValueChange = { v -> vm.updateWebhook(index) { it.copy(url = v.trim()) } },
                label = { Text(tr("Adres")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                isError = vm.errorAt("$path.url") != null,
            )
            ErrorText(vm.errorAt("$path.name") ?: vm.errorAt("$path.url"))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                WebhookConfig.EVENTS.forEach { e ->
                    FilterChip(
                        selected = e in h.events,
                        onClick = { vm.updateWebhook(index) { it.copy(events = if (e in it.events) it.events - e else WebhookConfig.EVENTS.filter { x -> x in it.events || x == e }) } },
                        label = { Text(EVENT_TITLES.getValue(e)) },
                    )
                }
            }
            ErrorText(vm.errorAt("$path.events"))
            Text(if (h.collections.isEmpty()) tr("Koleksiyonlar: hepsi") else tr("Koleksiyonlar:"), style = MaterialTheme.typography.bodySmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                draft.collections.forEach { c ->
                    FilterChip(
                        selected = c.name in h.collections,
                        onClick = { vm.updateWebhook(index) { it.copy(collections = if (c.name in it.collections) it.collections - c.name else it.collections + c.name) } },
                        label = { Text(c.name) },
                    )
                }
            }
            ErrorText(vm.errorAt("$path.collections"))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (h.secret != null) tr("İmza sırrı: ✓ ayarlı") else tr("İmza sırrı: yok (istekler imzasız)"), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { shownSecret = vm.newWebhookSecret(index) }) { Text(if (h.secret != null) tr("Yenile") else tr("Sır üret")) }
                if (h.secret != null) TextButton(onClick = { vm.updateWebhook(index) { it.copy(secret = null) } }) { Text(tr("Kaldır")) }
            }
            ErrorText(vm.errorAt("$path.secret"))
            shownSecret?.let { s ->
                Text(s, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Alıcı serviste bu sırrı girin; bir daha gösterilmez."), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { copySecret(ctx, tr("webhook sırrı"), s) }) { Text(tr("Kopyala")) }
                }
            }
        }
    }
}
