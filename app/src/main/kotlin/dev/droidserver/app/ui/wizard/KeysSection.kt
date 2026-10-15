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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import dev.droidserver.app.ui.common.RadioRow
import dev.droidserver.app.ui.common.copySecret
import dev.droidserver.core.auth.ApiKeys
import dev.droidserver.core.config.ApiKeyConfig
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.i18n.tr
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Yeni anahtarın erişimi. */
private enum class KeyAccess(val title: String, val description: String) {
    FULL(tr("Tam yetki"), tr("Yönetici anahtarıyla aynı: tüm koleksiyonlar ve yönetici uçları.")),
    READ(tr("Salt okunur"), tr("Raporlama, pano, yedekleme betiği gibi: okur, yazamaz.")),
    LIMITED(tr("Belirli koleksiyonlar"), tr("Bir entegrasyona yalnızca gereken koleksiyonlar.")),
}

private val VALIDITY = listOf(null to tr("Süresiz"), 30 to tr("30 gün"), 90 to tr("90 gün"), 365 to tr("1 yıl"))

/**
 * Adlandırılmış API anahtarları: her entegrasyona ayrı, kapsamı sınırlı, süreli ve tek tek iptal edilebilir anahtar.
 * Anahtarın kendisi saklanmaz; oluşturulunca bir kez gösterilir.
 */
@Composable
internal fun KeysSection(vm: WizardViewModel, draft: ServerConfig) {
    val ctx = LocalContext.current
    var adding by remember { mutableStateOf(false) }
    var created by remember { mutableStateOf<String?>(null) }
    Text(tr("Adlandırılmış anahtarlar"), style = MaterialTheme.typography.titleMedium)
    Text(
        tr("Her istemciye (entegrasyon, betik, pano) ayrı anahtar verin: salt okunur veya belirli koleksiyonlarla sınırlayın, süre " +
            "koyun; sızan anahtarı diğerlerine dokunmadan iptal edin. Sınırlı anahtar seçili koleksiyonlarda kuralları aşar ama " +
            "şema, metrikler, kullanıcılar ve yedekler gibi yönetici uçlarına giremez. Değişiklikler deploy edilince geçerli olur."),
        style = MaterialTheme.typography.bodySmall,
    )
    val time = remember { DateTimeFormatter.ofPattern("d.MM.yyyy HH:mm").withZone(ZoneId.systemDefault()) }
    draft.auth.keys.forEachIndexed { i, k -> KeyCard(vm, i, k, time) }
    ErrorText(vm.errorAt("auth.keys"))
    OutlinedButton(onClick = { adding = true }) {
        Icon(Icons.Default.Add, contentDescription = null)
        Text(tr("Anahtar oluştur"), Modifier.padding(start = 6.dp))
    }
    if (adding) NewKeyDialog(draft, onDismiss = { adding = false }) { name, readOnly, collections, days ->
        adding = false
        created = vm.addNamedKey(name, readOnly, collections, days)
    }
    created?.let { key ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(tr("Anahtar oluşturuldu")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("Bu anahtar bir daha gösterilmez; şimdi kopyalayıp istemciye girin. Deploy edilince geçerli olur."))
                    Text(key, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { Button(onClick = { copySecret(ctx, tr("API anahtarı"), key) }) { Text(tr("Kopyala")) } },
            dismissButton = { TextButton(onClick = { created = null }) { Text(tr("Tamam")) } },
        )
    }
}

@Composable
private fun KeyCard(vm: WizardViewModel, index: Int, k: ApiKeyConfig, time: DateTimeFormatter) {
    val path = "auth.keys[$index]"
    val expires = k.expiresAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
    val expired = expires != null && !Instant.now().isBefore(expires)
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(k.name + if (!k.enabled) tr(" (iptal edildi)") else if (expired) tr(" (süresi doldu)") else "", style = MaterialTheme.typography.titleSmall)
                    Text("${k.prefix}…", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = k.enabled, onCheckedChange = { on -> vm.updateNamedKey(index) { it.copy(enabled = on) } })
                IconButton(onClick = { vm.removeNamedKey(index) }) { Icon(Icons.Default.Delete, contentDescription = tr("Anahtarı sil")) }
            }
            Text(ApiKeys.describe(k), style = MaterialTheme.typography.bodySmall)
            Text(
                listOfNotNull(
                    expires?.let { tr("Son kullanma: {0}", time.format(it)) } ?: tr("Süresiz"),
                    tr("Son kullanım: ") + (vm.keyLastUsed(k.name)?.let(time::format) ?: tr("henüz yok")),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(tr("Dakikada istek"), style = MaterialTheme.typography.labelMedium)
            RateChips(k.requestsPerMinute) { n -> vm.updateNamedKey(index) { it.copy(requestsPerMinute = n) } }
            ErrorText(listOf("name", "hash", "collections", "expiresAt", "requestsPerMinute").firstNotNullOfOrNull { vm.errorAt("$path.$it") } ?: vm.errorAt(path))
        }
    }
}

@Composable
private fun NewKeyDialog(draft: ServerConfig, onDismiss: () -> Unit, onCreate: (String, Boolean, List<String>, Int?) -> Unit) {
    var name by remember { mutableStateOf("") }
    var access by remember { mutableStateOf(KeyAccess.READ) }
    var readOnly by remember { mutableStateOf(true) }
    var collections by remember { mutableStateOf(emptyList<String>()) }
    var days by remember { mutableStateOf<Int?>(null) }
    val valid = access != KeyAccess.LIMITED || collections.isNotEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Yeni API anahtarı")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.filter { c -> c.isLetterOrDigit() || c == '-' || c == '_' }.take(40) },
                    label = { Text(tr("Ad (örn. n8n, rapor)")) }, singleLine = true,
                )
                KeyAccess.entries.forEach { a -> RadioRow(access == a, a.title, a.description) { access = a } }
                if (access == KeyAccess.LIMITED) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        draft.collections.forEach { c ->
                            FilterChip(
                                selected = c.name in collections,
                                onClick = { collections = if (c.name in collections) collections - c.name else collections + c.name },
                                label = { Text(c.name) },
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("Yalnızca okusun"), Modifier.weight(1f))
                        Switch(checked = readOnly, onCheckedChange = { readOnly = it })
                    }
                }
                Text(tr("Geçerlilik"), style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    VALIDITY.forEach { (d, title) -> FilterChip(selected = days == d, onClick = { days = d }, label = { Text(title) }) }
                }
            }
        },
        confirmButton = {
            Button(enabled = valid, onClick = {
                when (access) {
                    KeyAccess.FULL -> onCreate(name, false, emptyList(), days)
                    KeyAccess.READ -> onCreate(name, true, emptyList(), days)
                    KeyAccess.LIMITED -> onCreate(name, readOnly, collections, days)
                }
            }) { Text(tr("Oluştur")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Vazgeç")) } },
    )
}
