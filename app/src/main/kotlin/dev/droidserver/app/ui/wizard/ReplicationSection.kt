package dev.droidserver.app.ui.wizard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.droidserver.app.ui.common.ErrorText
import dev.droidserver.app.ui.common.WarningText
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.discovery.FoundServer
import dev.droidserver.core.discovery.MdnsDiscovery
import dev.droidserver.core.i18n.tr
import dev.droidserver.core.replication.Replicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Canlı kopya: bu sunucu kopyalanabilir mi; ya da bu telefon başka bir DroidServer'ın salt okunur kopyası mı. */
@Composable
internal fun ReplicationSection(vm: WizardViewModel, draft: ServerConfig) {
    val rep = draft.replication
    Text(tr("Canlı kopya"), style = MaterialTheme.typography.titleMedium)
    val replicaOf = rep.replicaOf
    if (replicaOf != null) {
        Text(
            tr("Bu sunucu {0} adresindeki sunucunun salt okunur kopyası: koleksiyonlar, kurallar ve anahtarlar oradan " +
                "gelir, kayıtlar ve dosyalar {1} saniyede bir eşitlenir. Ana sunucu çökerse \"Kopya modundan çık\" " +
                "ile devralın (Durum ekranından da yapılabilir).", replicaOf.url, replicaOf.intervalSec),
            style = MaterialTheme.typography.bodySmall,
        )
        ErrorText(vm.errorAt("replication.replicaOf.url") ?: vm.errorAt("replication.replicaOf.key") ?: vm.errorAt("replication.replicaOf"))
        FailoverOptions(vm, draft)
        TextButton(onClick = vm::leaveReplica) { Text(tr("Kopya modundan çık (bu sunucu yazılabilir olur)")) }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(tr("Bu sunucu kopyalanabilir"))
            Text(
                tr("Başka bir telefon bu sunucunun canlı kopyasını tutabilir (yönetici anahtarıyla). Her değişiklik günlüğe yazılır; " +
                    "günlük {0} gün saklanır.", rep.keepDays),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(checked = rep.source, onCheckedChange = vm::setReplicationSource)
    }
    ErrorText(vm.errorAt("replication.keepDays"))
    if (rep.source) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("Dönünce ana rolünü geri al"))
                Text(
                    tr("Bu telefon kapanır ve kopya devralırsa: dönünce önce kopyanın kopyası olur (veriler oradan gelir), güncel " +
                        "kalınca kopyaya yazmayı kısa süre durdurtup ana rolü kayıpsız geri alır."),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = rep.failover.failback, onCheckedChange = { on -> vm.updateFailover { it.copy(failback = on) } })
        }
    }

    var open by rememberSaveable { mutableStateOf(false) }
    if (!open) {
        TextButton(onClick = { open = true }) { Text(tr("Bu telefonu başka bir sunucunun kopyası yap…")) }
        return
    }
    val scope = rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf("") }
    // Anahtar ekran durumuna yazılmasın diye rememberSaveable kullanılmaz.
    var key by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<FoundServer>?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    WarningText(
        tr("Bu sunucunun koleksiyonları ve kimlik ayarları ana sunucununkilerle değiştirilir; ilk eşitlemede buradaki kayıtlar " +
            "ana sunucudakilerle değişir. Ana sunucuda \"Bu sunucu kopyalanabilir\" açık olmalı."),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            status = tr("Yerel ağda aranıyor…")
            scope.launch {
                found = withContext(Dispatchers.IO) { runCatching { MdnsDiscovery.discover(2_500) }.getOrDefault(emptyList()) }
                status = if (found.isNullOrEmpty()) tr("Yerel ağda DroidServer bulunamadı; adresi elle yazın.") else null
            }
        }) { Text(tr("Yerel ağda ara")) }
    }
    found?.forEach { s ->
        s.baseUrl()?.let { u -> TextButton(onClick = { url = u }) { Text("${s.name} · $u") } }
    }
    OutlinedTextField(
        value = url, onValueChange = { url = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        label = { Text(tr("Ana sunucunun API adresi")) }, placeholder = { Text("http://192.168.1.20:8080/api") },
    )
    OutlinedTextField(
        value = key, onValueChange = { key = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        label = { Text(tr("Ana sunucunun yönetici anahtarı")) }, visualTransformation = PasswordVisualTransformation(),
        supportingText = { Text(tr("Şifreli depoda saklanır. Adlandırılmış tam yetkili bir anahtar önerilir (iptal edilebilir).")) },
    )
    OutlinedButton(enabled = url.isNotBlank() && key.isNotBlank(), onClick = {
        status = tr("Bağlanılıyor…")
        scope.launch {
            status = runCatching { withContext(Dispatchers.IO) { Replicator.fetchPrimary(url, key) } }.fold(
                { (primary, hash) ->
                    vm.adoptPrimary(url, key, primary, hash)
                    open = false
                    tr("'{0}' kopyası olarak ayarlandı: {1} koleksiyon. Deploy edince ilk kopyalama başlar.", primary.name, primary.collections.size)
                },
                { tr("Bağlanılamadı: {0}", it.message) },
            )
        }
    }) { Text(tr("Bağlan ve kopya olarak ayarla")) }
    status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}

/** Kopyada otomatik devralma: ana sunucuya ulaşılamazsa bu sunucu ana sunucu olur. */
@Composable
private fun FailoverOptions(vm: WizardViewModel, draft: ServerConfig) {
    val f = draft.replication.failover
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(tr("Otomatik devral"))
            Text(
                tr("Ana sunucuya {0} sn boyunca hiç ulaşılamazsa bu sunucu ana sunucu olur. Eski ana sunucu dönünce yazmayı keser " +
                    "ve bu sunucunun kopyası olur; iki sunucuya ayrı ayrı yazılmaz.", f.afterSec),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(checked = f.auto, onCheckedChange = { on -> vm.updateFailover { it.copy(auto = on) } })
    }
    if (!f.auto) return
    NumberField(tr("Ulaşılamazsa devralma süresi (sn)"), f.afterSec, vm.errorAt("replication.failover.afterSec"), tr("En az 30. Kısa süre, ana telefon yeniden başlarken gereksiz devralmaya yol açabilir.")) { n ->
        vm.updateFailover { it.copy(afterSec = n) }
    }
    var witness by rememberSaveable { mutableStateOf(f.witness.orEmpty()) }
    OutlinedTextField(
        value = witness, singleLine = true, modifier = Modifier.fillMaxWidth(),
        onValueChange = { v -> witness = v.trim(); vm.updateFailover { it.copy(witness = witness.ifEmpty { null }) } },
        label = { Text(tr("Tanık adresi (isteğe bağlı)")) }, placeholder = { Text("http://192.168.1.1") },
        isError = vm.errorAt("replication.failover.witness") != null,
        supportingText = {
            Text(vm.errorAt("replication.failover.witness") ?: tr("Örn. modemin adresi. Ulaşılamıyorsa kopan bu telefondur; devralınmaz. Boşsa yalnızca ağ bağlantısına bakılır."))
        },
    )
    Text(
        tr("İstemcilerin yeni sunucuyu bulması için iki telefonda aynı DuckDNS adını kullanın (kopya adı yayınlamaz, devralınca yayınlar) veya istemcide iki adresi de tanımlayın."),
        style = MaterialTheme.typography.bodySmall,
    )
}
