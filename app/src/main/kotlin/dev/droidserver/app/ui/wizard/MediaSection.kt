package dev.droidserver.app.ui.wizard

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import dev.droidserver.core.config.FieldConfig
import dev.droidserver.core.config.FieldType
import dev.droidserver.core.config.FileAiConfig
import dev.droidserver.core.config.ImageFormat
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.i18n.tr

/** Dosya alanının görsel ayarları: dönüşüm, küçültme, konum bilgisi ve YZ ile etiket/OCR. */
@Composable
internal fun MediaOptions(vm: WizardViewModel, ci: Int, fi: Int, field: FieldConfig, draft: ServerConfig) {
    val path = "collections[$ci].fields[$fi]"
    val fields = draft.collections.getOrNull(ci)?.fields.orEmpty().filter { it.name != field.name && !it.encrypted && it.computed == null }
    Text(tr("Görseller"), style = MaterialTheme.typography.labelLarge)
    Chips(tr("Dönüştür"), listOf(null to tr("Yok"), ImageFormat.WEBP to "WebP", ImageFormat.JPEG to "JPEG"), field.convert) { v ->
        vm.updateField(ci, fi) { it.copy(convert = v) }
    }
    var dim by rememberSaveable(ci, fi, "dim") { mutableStateOf(field.maxDimension?.toString().orEmpty()) }
    OutlinedTextField(
        value = dim,
        onValueChange = { v -> dim = v.filter(Char::isDigit).take(4); vm.updateField(ci, fi) { it.copy(maxDimension = dim.toIntOrNull()) } },
        label = { Text(tr("En büyük kenar (piksel)")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        isError = vm.errorAt("$path.maxDimension") != null,
        supportingText = { Text(vm.errorAt("$path.maxDimension") ?: tr("Boşsa küçültülmez; örn. 2048. Dönüşüm ve küçültme telefonda yapılır, yön düzeltilir.")) },
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = field.keepLocation, onCheckedChange = { v -> vm.updateField(ci, fi) { it.copy(keepLocation = v) } })
        Text(tr("Konum bilgisini (GPS) koru"))
    }
    Text(tr("Kapalıyken fotoğraftaki konum yüklenirken silinir; yön ve tarih gibi diğer bilgiler kalır."), style = MaterialTheme.typography.bodySmall)
    Text(tr("Yapay zekâ ile etiket ve metin (OCR)"), style = MaterialTheme.typography.labelLarge)
    if (!draft.ai.enabled) Text(tr("Önce Sunucu → Yapay zekâ'yı açın (görsel destekleyen bir modelle)."), style = MaterialTheme.typography.bodySmall)
    val ai = field.ai ?: FileAiConfig()
    fun setAi(next: FileAiConfig) = vm.updateField(ci, fi) { it.copy(ai = next.takeIf { n -> n.tags != null || n.text != null }) }
    Chips(tr("Etiketler"), listOf<Pair<String?, String>>(null to tr("Yok")) + fields.filter { it.type in setOf(FieldType.TEXT, FieldType.MULTISELECT, FieldType.JSON) }.map { it.name to it.name }, ai.tags) { v ->
        setAi(ai.copy(tags = v))
    }
    ErrorText(vm.errorAt("$path.ai.tags"))
    Chips(tr("Görseldeki metin"), listOf<Pair<String?, String>>(null to tr("Yok")) + fields.filter { it.type == FieldType.TEXT }.map { it.name to it.name }, ai.text) { v ->
        setAi(ai.copy(text = v))
    }
    ErrorText(vm.errorAt("$path.ai.text"))
    ErrorText(vm.errorAt("$path.ai"))
}

@Composable
private fun <T> Chips(label: String, choices: List<Pair<T, String>>, value: T, onPick: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$label:", style = MaterialTheme.typography.bodySmall)
        choices.forEach { (v, title) -> FilterChip(selected = v == value, onClick = { onPick(v) }, label = { Text(title) }) }
    }
}
