package dev.droidserver.app.ui.wizard

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp
import dev.droidserver.app.ui.common.ErrorText
import dev.droidserver.app.ui.common.WarningText
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.i18n.tr

/** Dakikada istek seçenekleri (null: sınırsız). */
internal val RATE_CHOICES = listOf(null to tr("Sınırsız"), 30 to "30/dk", 120 to "120/dk", 600 to "600/dk")

/** Güvenlik duvarı: izin/engel listeleri (IP veya CIDR), yerel ağ istisnası, kullanıcı başına sınır; denetim kaydı süresi. */
@Composable
internal fun FirewallSection(vm: WizardViewModel, draft: ServerConfig) {
    val fw = draft.firewall
    Text(tr("Güvenlik duvarı"), style = MaterialTheme.typography.titleMedium)
    Text(
        tr("Belirli adresleri veya aralıkları (örn. 203.0.113.7, 10.0.0.0/8, 2001:db8::/32) engelleyin ya da yalnızca izin " +
            "verdiklerinizi kabul edin. Telefonun kendisi hiçbir zaman engellenmez. Çok sayıda hatalı kimlik denemesi yapan " +
            "adresler zaten otomatik engellenir; o listeyi Durum > Güvenlik duvarı ekranında görürsünüz."),
        style = MaterialTheme.typography.bodySmall,
    )
    AddressList(
        label = tr("Engellenen adresler"),
        hint = tr("Her satıra bir adres veya aralık. Bu adreslerden gelen istekler 403 alır."),
        values = fw.deny,
        error = fw.deny.indices.firstNotNullOfOrNull { vm.errorAt("firewall.deny[$it]") },
    ) { list -> vm.updateFirewall { it.copy(deny = list) } }
    AddressList(
        label = tr("Yalnızca izin verilenler"),
        hint = tr("Boşsa herkes (engellenenler hariç). Doluysa yalnızca bu adresler") +
            if (fw.allowLocalNetwork) tr(" ve yerel ağ erişebilir.") else tr(" erişebilir."),
        values = fw.allow,
        error = fw.allow.indices.firstNotNullOfOrNull { vm.errorAt("firewall.allow[$it]") },
    ) { list -> vm.updateFirewall { it.copy(allow = list) } }
    if (fw.allow.isNotEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("Yerel ağ her zaman erişsin"))
                Text(tr("Ev/ofis ağındaki cihazlar izin listesinde olmasa da bağlanır."), style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = fw.allowLocalNetwork, onCheckedChange = { on -> vm.updateFirewall { it.copy(allowLocalNetwork = on) } })
        }
        if (!fw.allowLocalNetwork && draft.access.mode == dev.droidserver.core.config.AccessMode.LOCAL) {
            WarningText(tr("Erişim yalnızca yerel ağ ve yerel ağ izinli değil: yalnızca listedeki adresler bağlanabilir."))
        }
    }
    if (draft.auth.users.enabled) {
        Text(tr("Kullanıcı başına dakikada istek"), style = MaterialTheme.typography.labelLarge)
        RateChips(fw.userRequestsPerMinute) { n -> vm.updateFirewall { it.copy(userRequestsPerMinute = n) } }
        Text(
            tr("Giriş yapmış her hesap için ayrı sayılır (aynı adresi paylaşan kullanıcılar birbirini etkilemez). Aşılırsa 429."),
            style = MaterialTheme.typography.bodySmall,
        )
        ErrorText(vm.errorAt("firewall.userRequestsPerMinute"))
    }

    Text(tr("Denetim kaydı"), style = MaterialTheme.typography.labelLarge)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(30 to tr("30 gün"), 90 to tr("90 gün"), 365 to tr("1 yıl"), 1825 to tr("5 yıl")).forEach { (d, title) ->
            FilterChip(selected = draft.audit.keepDays == d, onClick = { vm.updateAudit { it.copy(keepDays = d) } }, label = { Text(title) })
        }
    }
    Text(tr("Yönetici işlemleri bu kadar süre saklanır."), style = MaterialTheme.typography.bodySmall)
    ErrorText(vm.errorAt("audit.keepDays"))
}

@Composable
internal fun RateChips(value: Int?, onValue: (Int?) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val choices = if (RATE_CHOICES.any { it.first == value }) RATE_CHOICES else RATE_CHOICES + (value to "$value/dk")
        choices.forEach { (n, title) -> FilterChip(selected = value == n, onClick = { onValue(n) }, label = { Text(title) }) }
    }
}

/** Satır başına bir adres; boş satırlar atılır. */
@Composable
private fun AddressList(label: String, hint: String, values: List<String>, error: String?, onValue: (List<String>) -> Unit) {
    var text by rememberSaveable(label) { mutableStateOf(values.joinToString("\n")) }
    OutlinedTextField(
        value = text,
        onValueChange = { v -> text = v; onValue(v.split('\n', ',').map(String::trim).filter(String::isNotEmpty)) },
        label = { Text(label) }, modifier = Modifier.fillMaxWidth(), minLines = 2,
        isError = error != null, supportingText = { Text(error ?: hint) },
    )
}
