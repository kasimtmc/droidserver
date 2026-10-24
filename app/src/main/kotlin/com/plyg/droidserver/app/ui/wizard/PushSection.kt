package com.plyg.droidserver.app.ui.wizard

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
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
import com.plyg.droidserver.app.ui.common.ErrorText
import com.plyg.droidserver.app.ui.common.copyToClipboard
import com.plyg.droidserver.core.config.PushRule
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.config.WebhookConfig
import com.plyg.droidserver.core.i18n.tr

private val EVENT_TITLES = mapOf("create" to tr("Eklenince"), "update" to tr("Değişince"), "delete" to tr("Silinince"))

/** Web push: tarayıcılara bildirim (VAPID anahtarları telefonda üretilir), kayıt olayında sahibine bildirim kuralları. */
@Composable
internal fun PushSection(vm: WizardViewModel, draft: ServerConfig) {
    val ctx = LocalContext.current
    val push = draft.push
    Text(tr("Web push bildirimleri"), style = MaterialTheme.typography.titleMedium)
    SwitchRow(
        tr("Tarayıcı bildirimleri"),
        tr("Kullanıcılar web uygulamanızda bildirime izin verir; sunucu onlara (sekme kapalıyken de) bildirim gönderir."),
        push.enabled,
    ) { vm.setPushEnabled(it) }
    if (!push.enabled) return
    OutlinedTextField(
        value = push.subject, onValueChange = { v -> vm.updatePush { it.copy(subject = v.trim()) } },
        label = { Text(tr("İletişim adresi")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("mailto:siz@example.com") },
        isError = vm.errorAt("push.subject") != null,
        supportingText = { Text(vm.errorAt("push.subject") ?: tr("İtme servisleri (Google, Mozilla, Apple) sorun olursa bu adrese ulaşır.")) },
    )
    Text(tr("VAPID açık anahtarı (tarayıcıda applicationServerKey):"), style = MaterialTheme.typography.labelLarge)
    Text(push.publicKey, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { copyToClipboard(ctx, tr("VAPID açık anahtarı"), push.publicKey) }) { Text(tr("Kopyala")) }
        TextButton(onClick = vm::regenerateVapid) { Text(tr("Yeniden üret")) }
    }
    Text(tr("Yeniden üretilirse mevcut abonelikler çalışmaz; kullanıcıların yeniden abone olması gerekir."), style = MaterialTheme.typography.bodySmall)
    ErrorText(vm.errorAt("push.publicKey") ?: vm.errorAt("push.enabled"))
    val base = draft.server.basePath.trimEnd('/') + "/push"
    listOf(tr("GET  {0}/key", base), tr("POST {0}/subscribe   (kullanıcı token'ı)", base), tr("POST {0}/send {user, title, body, url}   (yönetici)", base))
        .forEach { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
    Text(tr("Script'lerden: ctx.push.send(kullanıcıId, \"Başlık\", \"Metin\", \"/adres\")"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)

    Text(tr("Otomatik bildirimler"), style = MaterialTheme.typography.labelLarge)
    Text(
        tr("Kayıt eklenince, değişince veya silinince kaydın sahibine bildirim. Metinde {alan} kaydın değeriyle değişir, örn. \"Siparişiniz: {durum}\"."),
        style = MaterialTheme.typography.bodySmall,
    )
    push.notify.forEachIndexed { i, r -> PushRuleCard(vm, draft, i, r) }
    ErrorText(vm.errorAt("push.notify"))
    OutlinedButton(onClick = vm::addPushRule) {
        Icon(Icons.Default.Add, contentDescription = null)
        Text(tr("Kural ekle"), Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun PushRuleCard(vm: WizardViewModel, draft: ServerConfig, index: Int, r: PushRule) {
    val path = "push.notify[$index]"
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                var open by remember { mutableStateOf(false) }
                Box(Modifier.weight(1f)) {
                    OutlinedButton(onClick = { open = true }) {
                        Text(tr("Koleksiyon: {0}", r.collection.ifEmpty { tr("seçin") }))
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        draft.collections.forEach { c ->
                            DropdownMenuItem(
                                text = { Text(c.name + if (c.ownerField == null) tr(" (sahip alanı yok)") else "") },
                                enabled = c.ownerField != null,
                                onClick = { open = false; vm.updatePushRule(index) { it.copy(collection = c.name) } },
                            )
                        }
                    }
                }
                IconButton(onClick = { vm.removePushRule(index) }) { Icon(Icons.Default.Delete, contentDescription = tr("Kuralı sil")) }
            }
            ErrorText(vm.errorAt("$path.collection"))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                WebhookConfig.EVENTS.forEach { e ->
                    FilterChip(
                        selected = e in r.events,
                        onClick = { vm.updatePushRule(index) { it.copy(events = if (e in it.events) it.events - e else WebhookConfig.EVENTS.filter { x -> x in it.events || x == e }) } },
                        label = { Text(EVENT_TITLES.getValue(e)) },
                    )
                }
            }
            ErrorText(vm.errorAt("$path.events"))
            OutlinedTextField(
                value = r.title, onValueChange = { v -> vm.updatePushRule(index) { it.copy(title = v) } },
                label = { Text(tr("Başlık")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                isError = vm.errorAt("$path.title") != null,
            )
            OutlinedTextField(
                value = r.body, onValueChange = { v -> vm.updatePushRule(index) { it.copy(body = v) } },
                label = { Text(tr("Metin")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = r.url.orEmpty(), onValueChange = { v -> vm.updatePushRule(index) { it.copy(url = v.trim().ifEmpty { null }) } },
                label = { Text(tr("Tıklanınca açılacak adres (isteğe bağlı)")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("/siparis/{id}") },
            )
        }
    }
}
