package dev.droidserver.app.ui.wizard

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.droidserver.app.ui.common.ErrorText
import dev.droidserver.core.config.CollectionConfig
import dev.droidserver.core.config.FieldType
import dev.droidserver.core.config.HistoryConfig
import dev.droidserver.core.config.RetentionConfig
import dev.droidserver.core.config.SyncConfig
import dev.droidserver.core.config.SyncConflict
import dev.droidserver.core.config.TrashConfig
import dev.droidserver.core.i18n.tr

/** Koleksiyonun kayıt geçmişi, çöp kutusu, saklama süresi ve çevrimdışı eşitleme ayarları. */
@Composable
internal fun ArchiveSection(vm: WizardViewModel, index: Int, c: CollectionConfig) {
    val path = "collections[$index]"
    Text(tr("Geçmiş, çöp kutusu, saklama ve eşitleme"), style = MaterialTheme.typography.titleMedium)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ToggleWithNumber(
            key = "$index/history",
            title = tr("Kayıt geçmişi"),
            description = tr("Her değişiklikte kaydın hali, kimin yaptığı ve zamanı saklanır; eski bir sürüme dönülebilir."),
            on = c.history != null,
            number = c.history?.keep ?: HistoryConfig().keep,
            numberLabel = tr("Kayıt başına sürüm"),
            onToggle = { on -> vm.updateCollection(index) { it.copy(history = if (on) HistoryConfig() else null) } },
            onNumber = { n -> vm.updateCollection(index) { it.copy(history = it.history?.copy(keep = n)) } },
        )
        ErrorText(vm.errorAt("$path.history.keep"))
        ToggleWithNumber(
            key = "$index/trash",
            title = tr("Çöp kutusu"),
            description = tr("Silinen kayıt (dosyalarıyla) bu süre saklanır ve geri yüklenebilir; süre dolunca kalıcı silinir."),
            on = c.trash != null,
            number = c.trash?.days ?: TrashConfig().days,
            numberLabel = tr("Saklama (gün)"),
            onToggle = { on -> vm.updateCollection(index) { it.copy(trash = if (on) TrashConfig() else null) } },
            onNumber = { n -> vm.updateCollection(index) { it.copy(trash = it.trash?.copy(days = n)) } },
        )
        ErrorText(vm.errorAt("$path.trash.days"))
        ToggleWithNumber(
            key = "$index/retention",
            title = tr("Saklama süresi"),
            description = tr("Bu süreden eski kayıtlar saatte bir otomatik silinir (örn. loglar, geçici kayıtlar, KVKK gereği eski veriler)."),
            on = c.retention != null,
            number = c.retention?.days ?: RetentionConfig().days,
            numberLabel = tr("Gün"),
            onToggle = { on -> vm.updateCollection(index) { it.copy(retention = if (on) RetentionConfig() else null) } },
            onNumber = { n -> vm.updateCollection(index) { it.copy(retention = it.retention?.copy(days = n)) } },
        )
        ErrorText(vm.errorAt("$path.retention.days"))
        c.retention?.let { r -> RetentionDetails(vm, index, c, r) }
        ToggleWithNumber(
            key = "$index/sync",
            title = tr("Çevrimdışı eşitleme"),
            description = tr("İstemci SDK'ları kayıtları cihazda tutar, bağlantı yokken de yazar ve bağlanınca eşitler. Sayı: silinen kayıtların izi kaç gün tutulur (daha uzun bağlanmayan cihaz baştan eşitlenir)."),
            on = c.sync != null,
            number = c.sync?.keepDays ?: SyncConfig().keepDays,
            numberLabel = tr("Silme izi (gün)"),
            onToggle = { on -> vm.updateCollection(index) { it.copy(sync = if (on) SyncConfig() else null) } },
            onNumber = { n -> vm.updateCollection(index) { it.copy(sync = it.sync?.copy(keepDays = n)) } },
        )
        ErrorText(vm.errorAt("$path.sync.keepDays"))
        c.sync?.let { s ->
            Text(tr("Aynı alanı iki taraf da değiştirdiyse:"), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(SyncConflict.LWW to tr("Son yazan kazanır"), SyncConflict.MANUAL to tr("Uygulamaya bırak (elle çöz)")).forEach { (mode, label) ->
                    FilterChip(selected = s.conflict == mode, onClick = { vm.updateCollection(index) { it.copy(sync = s.copy(conflict = mode)) } }, label = { Text(label) })
                }
            }
        }
        if (c.history != null || c.trash != null) {
            Text(
                tr("Geçmişi ve çöp kutusunu yalnızca yönetici görür: web yönetim panelinde (kayıt satırındaki Geçmiş, koleksiyondaki Çöp kutusu) veya API ile."),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ToggleWithNumber(
    key: String,
    title: String,
    description: String,
    on: Boolean,
    number: Int,
    numberLabel: String,
    onToggle: (Boolean) -> Unit,
    onNumber: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = on, onCheckedChange = onToggle)
    }
    if (on) {
        var text by rememberSaveable(key) { mutableStateOf(number.toString()) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { v -> text = v.filter(Char::isDigit).take(5); text.toIntOrNull()?.let(onNumber) },
                label = { Text(numberLabel) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(180.dp),
            )
        }
    }
}

/** Yaşın ölçüldüğü zaman alanı ve isteğe bağlı koşul (örn. yalnızca tamamlananlar). */
@Composable
private fun RetentionDetails(vm: WizardViewModel, index: Int, c: CollectionConfig, r: RetentionConfig) {
    val path = "collections[$index].retention"
    val choices = listOf("created_at" to tr("Oluşturulma"), "updated_at" to tr("Son değişiklik")) +
        c.fields.filter { it.type == FieldType.DATETIME }.map { it.name to it.name }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(tr("Ölçüt:"), style = MaterialTheme.typography.bodySmall)
        choices.forEach { (name, label) ->
            val selected = (r.field ?: "created_at") == name
            FilterChip(selected = selected, onClick = {
                vm.updateCollection(index) { it.copy(retention = r.copy(field = name.takeIf { n -> n != "created_at" })) }
            }, label = { Text(label) })
        }
    }
    ErrorText(vm.errorAt("$path.field"))
    var filter by rememberSaveable("$index/retention/filter") { mutableStateOf(r.filter.orEmpty()) }
    OutlinedTextField(
        value = filter,
        onValueChange = { v -> filter = v; vm.updateCollection(index) { it.copy(retention = r.copy(filter = v.trim().ifEmpty { null })) } },
        label = { Text(tr("Yalnızca bunlar (isteğe bağlı)")) },
        placeholder = { Text("status=done") },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
        isError = vm.errorAt("$path.filter") != null,
        supportingText = { Text(vm.errorAt("$path.filter") ?: tr("Liste filtresi sözdizimi; boşsa süresi dolan tüm kayıtlar.")) },
    )
    Text(
        tr("Silme normal silme gibidir: ilişki ayarları, dosyalar ve çöp kutusu uygulanır; webhook ve canlı abonelik olayı üretmez."),
        style = MaterialTheme.typography.bodySmall,
    )
}
