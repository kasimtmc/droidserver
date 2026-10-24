package com.plyg.droidserver.app.ui.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.plyg.droidserver.app.AppGraph
import com.plyg.droidserver.app.ui.common.ScreenScaffold
import com.plyg.droidserver.core.audit.AuditLog
import com.plyg.droidserver.core.i18n.tr
import com.plyg.droidserver.core.net.Cidr
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Güvenlik duvarı durumu: engelli adresler (otomatik ve elle), engel kaldırma, elle engelleme; kalıcı listeler sihirbazda. */
@Composable
fun FirewallScreen(g: AppGraph, onBack: () -> Unit) {
    val guard = g.engine.guard
    var tick by remember { mutableIntStateOf(0) }
    val bans = remember(tick) { guard.bans().sortedByDescending { it.untilMs } }
    LaunchedEffect(Unit) { while (true) { delay(10_000); tick++ } }
    val time = remember { DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault()) }
    val firewall = remember { runCatching { g.configRepo.loadActiveOrNull()?.firewall }.getOrNull() }

    ScreenScaffold(tr("Güvenlik duvarı"), onBack = onBack) { modifier ->
        LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(
                    tr("Çok sayıda hatalı kimlik denemesi yapan adresler süreli engellenir. Buradan engeli kaldırabilir veya bir " +
                        "adresi elle engelleyebilirsiniz; engeller sunucu yeniden deploy edilse de sürer, uygulama kapanınca sıfırlanır. " +
                        "Kalıcı izin/engel listeleri: Sihirbaz > Güvenlik."),
                    style = MaterialTheme.typography.bodySmall,
                )
                firewall?.let { fw ->
                    if (fw.deny.isNotEmpty()) Text(tr("Engel listesi: ") + fw.deny.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                    if (fw.allow.isNotEmpty()) Text(tr("Yalnızca izinli: ") + fw.allow.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                }
                ManualBan { client, minutes ->
                    guard.ban(client, minutes, tr("elle engellendi"))
                    g.engine.audit.record(AuditLog.APP, "firewall.ban", client, tr("{0} dk; elle engellendi", minutes))
                    tick++
                }
                Text(tr("Engelli adresler"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                if (bans.isEmpty()) Text(tr("Şu an engelli adres yok."), style = MaterialTheme.typography.bodySmall)
            }
            items(bans, key = { it.client }) { b ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(b.client, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleSmall)
                            Text(tr("{0} · bitiş {1}", b.reason, time.format(Instant.ofEpochMilli(b.untilMs))), style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = {
                            if (guard.unban(b.client)) g.engine.audit.record(AuditLog.APP, "firewall.unban", b.client)
                            tick++
                        }) { Text(tr("Engeli kaldır")) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ManualBan(onBan: (String, Int) -> Unit) {
    var ip by rememberSaveable { mutableStateOf("") }
    var minutes by rememberSaveable { mutableStateOf("60") }
    val valid = ip.isNotBlank() && '/' !in ip && Cidr.parse(ip.trim()) != null
    val mins = minutes.toIntOrNull()?.takeIf { it in 1..525_600 }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = ip, onValueChange = { ip = it.take(45) }, label = { Text(tr("IP adresi")) }, singleLine = true,
            isError = ip.isNotBlank() && !valid, modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = minutes, onValueChange = { minutes = it.filter(Char::isDigit).take(6) }, label = { Text(tr("Dakika")) },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(0.5f),
        )
    }
    Button(enabled = valid && mins != null, onClick = { onBan(ip.trim(), mins ?: 60); ip = "" }) { Text(tr("Engelle")) }
}
