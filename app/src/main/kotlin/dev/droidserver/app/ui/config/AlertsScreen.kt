package dev.droidserver.app.ui.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.droidserver.app.AppGraph
import dev.droidserver.app.ui.common.ScreenScaffold
import dev.droidserver.core.alerts.Alert
import dev.droidserver.core.alerts.AlertKind
import dev.droidserver.core.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Süren sağlık sorunları, son gönderilen uyarılar ve kanalları denemek için deneme uyarısı. */
@Composable
fun AlertsScreen(g: AppGraph, onBack: () -> Unit) {
    val health = g.engine.health
    var tick by remember { mutableIntStateOf(0) }
    val active = remember(tick) { health.active() }
    val recent = remember(tick) { health.recent() }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { while (true) { delay(10_000); tick++ } }
    val time = remember { DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault()) }

    ScreenScaffold(tr("Sağlık uyarıları"), onBack = onBack) { modifier ->
        LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(
                    if (health.config.enabled) tr("Sunucu çalışırken yarım dakikada bir denetlenir. Kanallar ve eşikler: Sihirbaz > Genel > Sağlık uyarıları.")
                    else tr("Sağlık uyarıları kapalı (Sihirbaz > Genel)."),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = {
                    status = tr("Gönderiliyor…")
                    scope.launch {
                        val sent = withContext(Dispatchers.IO) { health.event(AlertKind.TEST, tr("Bu bir deneme uyarısıdır; uyarı kanalları çalışıyor.")) }
                        status = if (sent != null) tr("Gönderildi.") else tr("Hiçbir kanala gönderilemedi: sunucu çalışıyor mu, kanallar ayarlı mı? Loglara bakın.")
                        tick++
                    }
                }) { Text(tr("Deneme uyarısı gönder")) }
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text(tr("Süren sorunlar"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                if (active.isEmpty()) Text(tr("Her şey yolunda."), style = MaterialTheme.typography.bodySmall)
            }
            items(active) { a -> AlertCard(a, time, highlight = true) }
            item { Text(tr("Son uyarılar"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
            if (recent.isEmpty()) item { Text(tr("Henüz uyarı gönderilmedi."), style = MaterialTheme.typography.bodySmall) }
            items(recent) { a -> AlertCard(a, time, highlight = false) }
        }
    }
}

@Composable
private fun AlertCard(a: Alert, time: DateTimeFormatter, highlight: Boolean) {
    Card(
        Modifier.fillMaxWidth(),
        colors = if (highlight && a.critical) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer) else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(a.title, style = MaterialTheme.typography.titleSmall)
            Text(runCatching { time.format(Instant.parse(a.at)) }.getOrDefault(a.at), style = MaterialTheme.typography.bodySmall)
            if (!a.resolved) Text(a.message, style = MaterialTheme.typography.bodySmall)
        }
    }
}
