package dev.droidserver.app.ui.wizard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.droidserver.app.ui.common.ErrorText
import dev.droidserver.core.config.EmailTemplate
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.i18n.tr
import dev.droidserver.core.mail.Mailer

/** Hesap e-postalarının şablonları: tür, dil, konu, metin, HTML ve örnek değerlerle önizleme. */
@Composable
internal fun EmailTemplatesSection(vm: WizardViewModel, draft: ServerConfig) {
    val templates = draft.email.templates
    fun update(i: Int, f: (EmailTemplate) -> EmailTemplate) =
        vm.updateEmail { e -> e.copy(templates = e.templates.toMutableList().also { it[i] = f(it[i]) }) }

    Text(tr("Hesap e-postası şablonları"), style = MaterialTheme.typography.titleSmall)
    Text(
        tr("Doğrulama, parola sıfırlama ve giriş kodu e-postalarının metni. Yer tutucular: {app}, {email}, {code}, {link} (bağlantı kurulamıyorsa kod). Dil boşsa herkese; tr/en gibi bir dil yazılırsa yalnızca o dili kullanan tarayıcı ve uygulamalara. Şablonu olmayan e-posta yerleşik metinle gider."),
        style = MaterialTheme.typography.bodySmall,
    )
    templates.forEachIndexed { i, t -> TemplateCard(vm, draft, i, t, { f -> update(i, f) }) }
    ErrorText(vm.errorAt("email.templates"))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(tr("Ekle:"), style = MaterialTheme.typography.labelLarge)
        EmailTemplate.KINDS.forEach { kind ->
            OutlinedButton(onClick = {
                vm.updateEmail { e -> e.copy(templates = e.templates + EmailTemplate(kind, subject = defaultSubject(kind), text = defaultText(kind))) }
            }) { Text(kindTitle(kind)) }
        }
    }
}

@Composable
private fun TemplateCard(vm: WizardViewModel, draft: ServerConfig, i: Int, t: EmailTemplate, update: ((EmailTemplate) -> EmailTemplate) -> Unit) {
    val path = "email.templates[$i]"
    var preview by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    EmailTemplate.KINDS.forEach { k -> FilterChip(t.kind == k, { update { it.copy(kind = k) } }, label = { Text(kindTitle(k)) }) }
                }
                IconButton(onClick = { vm.updateEmail { e -> e.copy(templates = e.templates.filterIndexed { j, _ -> j != i }) } }) {
                    Icon(Icons.Default.Delete, contentDescription = tr("Şablonu sil"))
                }
            }
            OutlinedTextField(
                value = t.lang.orEmpty(), onValueChange = { v -> update { it.copy(lang = v.trim().lowercase().take(2).ifEmpty { null }) } },
                label = { Text(tr("Dil (boşsa herkese)")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                isError = vm.errorAt("$path.lang") != null,
            )
            ErrorText(vm.errorAt("$path.lang") ?: vm.errorAt("$path.kind"))
            OutlinedTextField(
                value = t.subject, onValueChange = { v -> update { it.copy(subject = v) } },
                label = { Text(tr("Konu")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                isError = vm.errorAt("$path.subject") != null,
            )
            ErrorText(vm.errorAt("$path.subject"))
            OutlinedTextField(
                value = t.text, onValueChange = { v -> update { it.copy(text = v) } },
                label = { Text(tr("Metin")) }, minLines = 4, modifier = Modifier.fillMaxWidth(),
                isError = vm.errorAt("$path.text") != null,
            )
            ErrorText(vm.errorAt("$path.text"))
            OutlinedTextField(
                value = t.html.orEmpty(), onValueChange = { v -> update { it.copy(html = v.ifBlank { null }) } },
                label = { Text(tr("HTML (isteğe bağlı)")) }, minLines = 2, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text(tr("Boşsa yalnızca düz metin gider.")) },
            )
            TextButton(onClick = { preview = !preview }) { Text(if (preview) tr("Önizlemeyi gizle") else tr("Önizle")) }
            if (preview) {
                val app = draft.email.fromName?.takeIf { it.isNotBlank() } ?: draft.name
                val (subject, text, html) = Mailer.preview(t, app)
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(subject, style = MaterialTheme.typography.titleSmall)
                        Text(text, style = MaterialTheme.typography.bodySmall)
                        if (html != null) Text(tr("HTML: {0} karakter", html.length), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

private fun kindTitle(kind: String) = when (kind) {
    EmailTemplate.VERIFY -> tr("Doğrulama")
    EmailTemplate.RESET -> tr("Parola sıfırlama")
    else -> tr("Giriş kodu")
}

private fun defaultSubject(kind: String) = when (kind) {
    EmailTemplate.VERIFY -> tr("{app}: e-posta adresinizi doğrulayın")
    EmailTemplate.RESET -> tr("{app}: parola sıfırlama")
    else -> tr("{app}: giriş kodu")
}

private fun defaultText(kind: String) = when (kind) {
    EmailTemplate.VERIFY -> tr("Merhaba,\n\n{app} hesabınızı doğrulamak için: {link}\n\nBu hesabı siz açmadıysanız bu e-postayı yok sayın.")
    EmailTemplate.RESET -> tr("Merhaba,\n\nYeni parola belirlemek için: {link}\n\nİsteği siz yapmadıysanız bu e-postayı yok sayın.")
    else -> tr("Merhaba,\n\n{app} giriş kodunuz: {code}\n\nKod 10 dakika geçerlidir.")
}
