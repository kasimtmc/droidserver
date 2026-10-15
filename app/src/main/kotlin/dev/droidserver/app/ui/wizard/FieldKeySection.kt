package dev.droidserver.app.ui.wizard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.droidserver.app.ui.common.ErrorText
import dev.droidserver.app.ui.common.WarningText
import dev.droidserver.app.ui.common.copySecret
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.i18n.tr
import dev.droidserver.core.security.FieldCipher

/**
 * Şifreli alanların anahtarı: ilk şifreli alanda üretilir, telefonun şifreli deposunda durur ve yedeklere girmez.
 * Yedeği başka telefona yüklemek için not edilmeli; orada "Var olan anahtarı gir" ile girilir.
 */
@Composable
internal fun FieldKeySection(vm: WizardViewModel, draft: ServerConfig) {
    val ctx = LocalContext.current
    val ref = draft.database.fieldKey
    val encrypted = draft.collections.sumOf { c -> c.fields.count { it.encrypted } }
    if (ref == null && encrypted == 0) return
    var entering by remember { mutableStateOf(false) }
    Text(tr("Alan şifreleme"), style = MaterialTheme.typography.titleMedium)
    val key = ref?.let(vm::secretValue)
    val keyId = key?.let { runCatching { FieldCipher.of(it).keyId }.getOrNull() }
    Text(
        tr("{0} alan şifreli saklanıyor. Anahtar telefonun şifreli deposunda durur ve yedeklere girmez: yedeği başka telefona " +
            "yüklemek için anahtarı güvenli bir yere (parola yöneticisi) kaydedin. Anahtar kaybolursa şifreli değerler açılamaz.", encrypted),
        style = MaterialTheme.typography.bodySmall,
    )
    keyId?.let { Text(tr("Anahtar kimliği: {0}", it), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
    ErrorText(vm.errorAt("database.fieldKey"))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (key != null) OutlinedButton(onClick = { copySecret(ctx, tr("şifreleme anahtarı"), key) }) { Text(tr("Anahtarı kopyala")) }
        TextButton(onClick = { entering = true }) { Text(tr("Var olan anahtarı gir")) }
    }
    if (entering) {
        var text by remember { mutableStateOf("") }
        val valid = runCatching { FieldCipher.of(text) }.isSuccess
        AlertDialog(
            onDismissRequest = { entering = false },
            title = { Text(tr("Var olan anahtarı gir")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    WarningText(
                        tr("Yalnızca başka telefondan taşınan yedek için. Bu sunucuda başka anahtarla şifrelenmiş kayıt varsa onlar açılamaz hale gelir."),
                    )
                    OutlinedTextField(
                        value = text, onValueChange = { text = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text(tr("Anahtar (base64)")) }, isError = text.isNotEmpty() && !valid,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    )
                    if (text.isNotEmpty() && !valid) ErrorText(tr("Şifreleme anahtarı 32 baytlık base64 olmalı"))
                }
            },
            confirmButton = {
                TextButton(enabled = valid, onClick = { vm.setFieldKey(text); entering = false }) { Text(tr("Kaydet")) }
            },
            dismissButton = { TextButton(onClick = { entering = false }) { Text(tr("Vazgeç")) } },
        )
    }
}
