package dev.droidserver.app.ui.wizard

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.droidserver.app.ui.common.ErrorText
import dev.droidserver.core.config.CalendarConfig
import dev.droidserver.core.config.CollectionConfig
import dev.droidserver.core.config.ContactsConfig
import dev.droidserver.core.config.FieldType
import dev.droidserver.core.i18n.tr
import java.security.SecureRandom

/** Koleksiyonun takvim (çakışma kuralı, abonelik, CalDAV) ve kişi (CardDAV) ayarları. */
@Composable
internal fun CalendarSection(vm: WizardViewModel, index: Int, c: CollectionConfig) {
    val path = "collections[$index]"
    val draft by vm.draft.collectAsState()
    val davOn = draft.webdav.enabled
    fun names(vararg types: FieldType) = c.fields.filter { it.type in types && !it.encrypted }.map { it.name }
    Text(tr("Takvim ve kişiler"), style = MaterialTheme.typography.titleMedium)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Toggle(tr("Takvim"), tr("Kayıtlar etkinlik olur: üst üste binen rezervasyonu engelleme, takvim aboneliği (.ics), telefon ve bilgisayar takvimiyle iki yönlü eşitleme."), c.calendar != null) { on ->
            vm.updateCollection(index) { col ->
                col.copy(calendar = if (!on) null else {
                    val times = col.fields.filter { it.type == FieldType.DATETIME }.map { it.name }
                    CalendarConfig(
                        title = col.fields.firstOrNull { it.type == FieldType.TEXT }?.name.orEmpty(),
                        start = times.firstOrNull().orEmpty(),
                        end = times.getOrNull(1),
                    )
                })
            }
        }
        c.calendar?.let { cal ->
            val p = "$path.calendar"
            fun set(change: (CalendarConfig) -> CalendarConfig) = vm.updateCollection(index) { it.copy(calendar = it.calendar?.let(change)) }
            FieldPick(tr("Başlık"), names(FieldType.TEXT), cal.title, optional = false) { v -> set { it.copy(title = v.orEmpty()) } }
            ErrorText(vm.errorAt("$p.title"))
            FieldPick(tr("Başlangıç"), names(FieldType.DATETIME), cal.start, optional = false) { v -> set { it.copy(start = v.orEmpty()) } }
            ErrorText(vm.errorAt("$p.start"))
            FieldPick(tr("Bitiş"), names(FieldType.DATETIME), cal.end) { v -> set { it.copy(end = v, noOverlap = it.noOverlap && v != null) } }
            ErrorText(vm.errorAt("$p.end"))
            FieldPick(tr("Açıklama"), names(FieldType.TEXT), cal.description) { v -> set { it.copy(description = v) } }
            FieldPick(tr("Yer"), names(FieldType.TEXT, FieldType.GEO), cal.location) { v -> set { it.copy(location = v) } }
            FieldPick(tr("Tüm gün"), names(FieldType.BOOLEAN), cal.allDay) { v -> set { it.copy(allDay = v) } }
            FieldPick(tr("Tekrar (RRULE)"), names(FieldType.TEXT), cal.recurrence) { v -> set { it.copy(recurrence = v) } }
            Toggle(tr("Çakışma olmasın"), tr("Aynı kaynakta zamanı üst üste binen kayıt reddedilir (409). Uç uca eklenenler olur."), cal.noOverlap) { on ->
                set { it.copy(noOverlap = on, resource = it.resource.takeIf { on }) }
            }
            ErrorText(vm.errorAt("$p.noOverlap"))
            if (cal.noOverlap) {
                FieldPick(tr("Kaynak (oda, kişi…)"), names(FieldType.TEXT, FieldType.SELECT, FieldType.INTEGER, FieldType.RELATION), cal.resource) { v -> set { it.copy(resource = v) } }
                ErrorText(vm.errorAt("$p.resource"))
            }
            Toggle(tr("Takvim aboneliği (.ics)"), tr("Takvim uygulamaları /api/calendar/{0}.ics adresine abone olur (liste kuralıyla).", c.name), cal.feed) { on ->
                set { it.copy(feed = on, feedToken = it.feedToken.takeIf { on }) }
            }
            if (cal.feed) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (cal.feedToken == null) tr("Gizli adres yok: abonelik kurallara uyar.") else tr("Gizli adres: ?token={0}", cal.feedToken),
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { set { it.copy(feedToken = randomToken()) } }) { Text(if (cal.feedToken == null) tr("Oluştur") else tr("Yenile")) }
                    if (cal.feedToken != null) TextButton(onClick = { set { it.copy(feedToken = null) } }) { Text(tr("Kaldır")) }
                }
                ErrorText(vm.errorAt("$p.feedToken"))
            }
            Toggle(
                tr("Telefon takvimiyle eşitle (CalDAV)"),
                if (davOn) tr("DAVx⁵, iOS, Thunderbird: ağ sürücüsünün adresi ve kullanıcı adı/parolasıyla hesap ekleyin.") else tr("Önce Ağ sürücüsünü (WebDAV) açın; aynı adres ve parola kullanılır."),
                cal.dav,
            ) { on -> set { it.copy(dav = on) } }
            ErrorText(vm.errorAt("$p.dav"))
        }
        Toggle(
            tr("Kişiler (CardDAV)"),
            if (davOn) tr("Kayıtlar telefonun rehberinde kişi olarak görünür ve oradan düzenlenir (ağ sürücüsünün kimliğiyle).") else tr("Önce Ağ sürücüsünü (WebDAV) açın; aynı adres ve parola kullanılır."),
            c.contacts != null,
        ) { on -> vm.updateCollection(index) { col -> col.copy(contacts = if (on) ContactsConfig(col.fields.firstOrNull { it.type == FieldType.TEXT }?.name.orEmpty()) else null) } }
        c.contacts?.let { con ->
            val p = "$path.contacts"
            fun set(change: (ContactsConfig) -> ContactsConfig) = vm.updateCollection(index) { it.copy(contacts = it.contacts?.let(change)) }
            val text = names(FieldType.TEXT)
            ErrorText(vm.errorAt(p))
            FieldPick(tr("Ad"), text, con.name, optional = false) { v -> set { it.copy(name = v.orEmpty()) } }
            ErrorText(vm.errorAt("$p.name"))
            FieldPick(tr("E-posta"), text, con.email) { v -> set { it.copy(email = v) } }
            FieldPick(tr("Telefon"), text, con.phone) { v -> set { it.copy(phone = v) } }
            FieldPick(tr("Kurum"), text, con.organization) { v -> set { it.copy(organization = v) } }
            FieldPick(tr("Not"), text, con.note) { v -> set { it.copy(note = v) } }
        }
    }
}

@Composable
private fun Toggle(title: String, description: String, on: Boolean, onToggle: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = on, onCheckedChange = onToggle)
    }
}

/** Alan seçimi: uygun alanlar (ve isteğe bağlıysa "yok"). */
@Composable
private fun FieldPick(label: String, choices: List<String>, value: String?, optional: Boolean = true, onPick: (String?) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$label:", style = MaterialTheme.typography.bodySmall)
        if (optional) FilterChip(selected = value == null, onClick = { onPick(null) }, label = { Text(tr("Yok")) })
        choices.forEach { name -> FilterChip(selected = value == name, onClick = { onPick(name) }, label = { Text(name) }) }
        if (choices.isEmpty() && !optional) Text(tr("uygun alan yok"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

private fun randomToken(): String {
    val alphabet = "abcdefghijkmnpqrstuvwxyz23456789"
    val r = SecureRandom()
    return (1..24).map { alphabet[r.nextInt(alphabet.length)] }.joinToString("")
}
