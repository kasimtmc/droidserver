package dev.droidserver.app.ui.wizard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.droidserver.app.ui.common.ErrorText
import dev.droidserver.app.ui.common.WarningText
import dev.droidserver.core.i18n.tr
import dev.droidserver.core.templates.AppTemplate

/**
 * Hazır uygulama seçimi: şablon, örnek veri ve web sitesi. [siteExists]: profilde zaten site var (şablon sitesi onun
 * yerine geçer; varsayılan kapalı). [onPick] hata metni döndürürse diyalog açık kalır.
 */
@Composable
internal fun AppTemplateDialog(
    siteExists: Boolean,
    confirmLabel: String = tr("Ekle"),
    onDismiss: () -> Unit,
    onPick: (AppTemplate, samples: Boolean, site: Boolean) -> String?,
) {
    var selected by remember { mutableStateOf(AppTemplate.BLOG) }
    var samples by remember { mutableStateOf(true) }
    var site by remember { mutableStateOf(!siteExists) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Hazır uygulama")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AppTemplate.entries.forEach { t ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = selected == t, role = Role.RadioButton) { selected = t; error = null },
                        verticalAlignment = Alignment.Top,
                    ) {
                        RadioButton(selected = selected == t, onClick = null)
                        Column(Modifier.weight(1f)) {
                            Text(t.title, style = MaterialTheme.typography.titleSmall)
                            Text(t.description, style = MaterialTheme.typography.bodySmall)
                            Text(tr("Koleksiyonlar: ") + t.collections().joinToString { it.name }, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = samples, onCheckedChange = { samples = it })
                    Text(tr("Örnek kayıtlar ekle (deploy'dan sonra, boş koleksiyonlara)"))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = site, onCheckedChange = { site = it })
                    Text(tr("Hazır web sitesini kur (API'yi kullanan tek sayfa)"))
                }
                if (site && siteExists) WarningText(tr("Mevcut web siteniz şablonun sitesiyle değiştirilir."))
                if (selected.needsUsers) Text(tr("Kullanıcı hesapları açılır (kayıt ve giriş)."), style = MaterialTheme.typography.bodySmall)
                ErrorText(error)
            }
        },
        confirmButton = { Button(onClick = { error = onPick(selected, samples, site) }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Vazgeç")) } },
    )
}
