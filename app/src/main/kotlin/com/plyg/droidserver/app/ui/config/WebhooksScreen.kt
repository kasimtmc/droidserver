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
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.plyg.droidserver.app.AppGraph
import com.plyg.droidserver.app.ui.common.ScreenScaffold
import com.plyg.droidserver.core.i18n.tr
import com.plyg.droidserver.core.webhook.Delivery
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Webhook'lar: tanımlılar, "deneme gönder" ve son teslimler (3 sn'de bir yenilenir). Düzenleme sihirbazın Güvenlik adımında. */
@Composable
fun WebhooksScreen(g: AppGraph, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var deliveries by remember { mutableStateOf<List<Delivery>>(emptyList()) }
    var result by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            deliveries = g.engine.webhooks.deliveries()
            delay(3_000)
        }
    }
    val hooks = g.engine.webhooks.hooks
    val time = remember { DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()) }

    ScreenScaffold(tr("Webhook'lar"), onBack = onBack) { modifier ->
        LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text(
                    if (hooks.isEmpty()) tr("Çalışan sunucuda webhook yok. Sihirbaz → Güvenlik → Webhook'lar'dan ekleyip deploy edin.")
                    else tr("Webhook'lar sihirbazın Güvenlik adımında düzenlenir. \"Deneme\" bir ping olayı gönderir."),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            items(hooks, key = { "h-" + it.name }) { h ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(h.name + if (h.enabled) "" else tr(" (kapalı)"), style = MaterialTheme.typography.titleSmall)
                            Text(h.url, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedButton(onClick = {
                            scope.launch {
                                val d = runCatching { g.engine.webhooks.test(h.name) }
                                result = d.fold({ "${h.name}: " + if (it.ok) "HTTP ${it.status}, ${it.durationMs} ms" else (it.error ?: "HTTP ${it.status}") }, { tr("Hata: {0}", it.message) })
                                deliveries = g.engine.webhooks.deliveries()
                            }
                        }) { Text(tr("Deneme")) }
                    }
                }
            }
            result?.let { item { Text(it, style = MaterialTheme.typography.bodySmall) } }
            item { Text(tr("Son teslimler"), style = MaterialTheme.typography.titleMedium) }
            if (deliveries.isEmpty()) item { Text(tr("Henüz teslim yok."), style = MaterialTheme.typography.bodySmall) }
            items(deliveries, key = { it.id + "-" + it.attempt }) { d ->
                Text(
                    "${time.format(d.at)}  ${if (d.ok) "✓" else "✕"} ${d.webhook} · ${d.event}" +
                        (if (d.attempt > 1) tr(" · deneme {0}", d.attempt) else "") +
                        " · " + (if (d.ok) "HTTP ${d.status}" else (d.error ?: "HTTP ${d.status}")) + " · ${d.durationMs} ms" +
                        if (!d.ok && !d.final) tr(" (yeniden denenecek)") else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (d.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
