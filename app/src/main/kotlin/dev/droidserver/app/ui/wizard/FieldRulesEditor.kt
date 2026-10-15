package dev.droidserver.app.ui.wizard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.droidserver.app.ui.common.ErrorText
import dev.droidserver.core.config.Expression
import dev.droidserver.core.config.FieldConfig
import dev.droidserver.core.config.FieldRules
import dev.droidserver.core.config.FieldType
import dev.droidserver.core.config.TextFormat
import dev.droidserver.core.i18n.tr

/**
 * Seçim alanının seçenekleri ve "Kurallar" bölümü: biçim, uzunluk, düzenli ifade, sayı aralığı ve hesaplanan alan.
 * Koleksiyon ve kullanıcı alanlarında ortak; [others] hesaplanan alanın başvurabileceği alan adları.
 */
@Composable
internal fun FieldRulesEditor(
    key: String,
    field: FieldConfig,
    path: String,
    errorAt: (String) -> String?,
    others: List<String>,
    update: ((FieldConfig) -> FieldConfig) -> Unit,
) {
    val choice = field.type == FieldType.SELECT || field.type == FieldType.MULTISELECT
    if (choice) {
        SyncedTextField(
            key = "$key/options", type = field.type,
            value = field.options.orEmpty().joinToString("\n"),
            label = tr("Seçenekler (her satıra bir tane)"), singleLine = false,
            parse = { v -> v.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n") },
        ) { v -> update { it.copy(options = v.lines().map { l -> l.trim() }.filter { l -> l.isNotEmpty() }.ifEmpty { null }) } }
        ErrorText(errorAt("$path.options"))
        Text(
            if (field.type == FieldType.SELECT) tr("Değer bu seçeneklerden biri olmalı; listede ?ad=seçenek ile filtrelenir.")
            else tr("Değer seçenek listesidir, örn. [\"a\", \"b\"]; ?ad=seçenek \"bu seçeneği içerenler\" demektir."),
            style = MaterialTheme.typography.bodySmall,
        )
    }

    val text = field.type == FieldType.TEXT
    val number = field.type == FieldType.INTEGER || field.type == FieldType.REAL
    val computable = field.type in FieldRules.COMPUTABLE
    if (!text && !number && !computable && field.type != FieldType.MULTISELECT) return
    var open by rememberSaveable(key) { mutableStateOf(FieldRules.hasConstraints(field) || field.computed != null) }
    TextButton(onClick = { open = !open }) { Text(if (open) tr("Kuralları gizle ▾") else tr("Kurallar ve hesaplama ▸")) }
    if (!open) return

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (computable) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = field.computed != null,
                    onCheckedChange = { on -> update { FieldRules.withComputed(it, if (on) "" else null) } },
                )
                Text(tr("Hesaplanan alan (değeri formülle bulunur, yazılamaz)"))
            }
            val computed = field.computed
            if (computed != null) {
                SyncedTextField(
                    key = "$key/computed", type = field.type, value = computed, label = tr("Formül"), monospace = true,
                ) { v -> update { FieldRules.withComputed(it, v) } }
                ErrorText(errorAt("$path.computed"))
                Text(
                    tr("Örnek: round(price * qty, 2), upper(name), if(qty > 0, 'var', 'yok'), a ?? 0. " +
                        "Alanlar: {0}. İşlevler: {1}.", others.ifEmpty { listOf("—") }.joinToString(), Expression.FUNCTION_NAMES.joinToString()),
                    style = MaterialTheme.typography.bodySmall,
                )
                return@Column // hesaplanan alanın değeri yazılmadığından kısıtlar anlamsız
            }
        }
        if (text) {
            Text(tr("Biçim"), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = field.format == null, onClick = { update { it.copy(format = null) } }, label = { Text(tr("Serbest")) })
                TextFormat.entries.forEach { f ->
                    FilterChip(
                        selected = field.format == f, onClick = { update { it.copy(format = f) } },
                        label = { Text(if (f == TextFormat.EMAIL) tr("E-posta") else tr("Web adresi")) },
                    )
                }
            }
            ErrorText(errorAt("$path.format"))
        }
        if (text || field.type == FieldType.MULTISELECT) {
            val unit = if (text) tr("karakter") else tr("seçim")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberInput("$key/minLength", tr("En az {0}", unit), field.minLength?.toString(), integer = true, Modifier.weight(1f)) { v ->
                    update { it.copy(minLength = v?.toInt()) }
                }
                NumberInput("$key/maxLength", tr("En çok {0}", unit), field.maxLength?.toString(), integer = true, Modifier.weight(1f)) { v ->
                    update { it.copy(maxLength = v?.toInt()) }
                }
            }
            ErrorText(errorAt("$path.minLength") ?: errorAt("$path.maxLength"))
        }
        if (text) {
            SyncedTextField(
                key = "$key/pattern", type = field.type, value = field.pattern.orEmpty(),
                label = tr("Düzenli ifade (isteğe bağlı), örn. [A-Z]{3}-[0-9]+"), monospace = true,
            ) { v -> update { it.copy(pattern = v.ifEmpty { null }) } }
            ErrorText(errorAt("$path.pattern"))
        }
        if (number) {
            fun show(d: Double?) = d?.let { Expression.text(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberInput("$key/min", tr("En küçük"), show(field.min), integer = false, Modifier.weight(1f)) { v -> update { it.copy(min = v) } }
                NumberInput("$key/max", tr("En büyük"), show(field.max), integer = false, Modifier.weight(1f)) { v -> update { it.copy(max = v) } }
            }
            ErrorText(errorAt("$path.min") ?: errorAt("$path.max"))
        }
    }
}

/**
 * Yazılan metni yerel tutan alan: taslak başka yerden değişirse (JSON, tip) yeniden doldurulur. [parse] taslaktaki
 * değerle karşılaştırma için metni normalleştirir.
 */
@Composable
private fun SyncedTextField(
    key: String,
    type: FieldType,
    value: String,
    label: String,
    singleLine: Boolean = true,
    monospace: Boolean = false,
    parse: (String) -> String = { it },
    onChange: (String) -> Unit,
) {
    var text by rememberSaveable(key, type) { mutableStateOf(value) }
    LaunchedEffect(value) { if (parse(text) != value) text = value }
    OutlinedTextField(
        value = text,
        onValueChange = { v -> text = v; onChange(v) },
        label = { Text(label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        textStyle = if (monospace) MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Boş bırakılabilen sayı girişi; geçersiz yazımda taslak değişmez, alanın altında hata görünür. */
@Composable
private fun NumberInput(key: String, label: String, value: String?, integer: Boolean, modifier: Modifier, onValue: (Double?) -> Unit) {
    var text by rememberSaveable(key) { mutableStateOf(value.orEmpty()) }
    var error by remember(key) { mutableStateOf(false) }
    LaunchedEffect(value) {
        val current = text.trim().replace(',', '.').toDoubleOrNull()
        if (current?.let { Expression.text(it) } != value) { text = value.orEmpty(); error = false }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { v ->
            text = v
            val t = v.trim().replace(',', '.')
            val parsed = if (integer) t.toIntOrNull()?.toDouble() else t.toDoubleOrNull()?.takeIf { it.isFinite() }
            error = t.isNotEmpty() && parsed == null
            if (!error) onValue(parsed)
        },
        label = { Text(label) },
        singleLine = true,
        isError = error,
        keyboardOptions = KeyboardOptions(keyboardType = if (integer) KeyboardType.Number else KeyboardType.Decimal),
        modifier = modifier,
    )
}
