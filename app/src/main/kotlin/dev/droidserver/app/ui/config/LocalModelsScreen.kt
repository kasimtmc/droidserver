package dev.droidserver.app.ui.config

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.BatteryManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.droidserver.app.AppGraph
import dev.droidserver.app.ai.LocalModel
import dev.droidserver.app.ai.LocalModels
import dev.droidserver.app.ui.common.ErrorText
import dev.droidserver.app.ui.common.Pill
import dev.droidserver.app.ui.common.ScreenScaffold
import dev.droidserver.app.ui.common.SectionCard
import dev.droidserver.app.ui.common.SoftCard
import dev.droidserver.app.ui.common.TipCard
import dev.droidserver.core.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Telefonda çalışan modeller: indirme (boyut, bellek, depolama, pil ve Wi-Fi uyarısı), silme. */
@Composable
fun LocalModelsScreen(g: AppGraph, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val models = g.localModels
    val downloads by models.downloads.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    val free = remember(refresh, downloads) { models.dir.apply { mkdirs() }.usableSpace / 1_048_576 }
    val total = remember { ActivityManager.MemoryInfo().also { ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }.totalMem / 1_048_576 }
    val warnings = remember(refresh) { conditions(ctx) }

    ScreenScaffold(tr("Yerel modeller"), subtitle = tr("{0} MB boş · {1} MB bellek", free, total), onBack = onBack) { modifier ->
        Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!models.supported) ErrorText(tr("Bu telefonun işlemcisinde yerel YZ yok (yalnızca 64 bit ARM ve x86_64)"))
            TipCard(
                g, "local-ai-intro",
                tr("Model telefonda çalışır: veriler dışarı çıkmaz, ücret ve anahtar yok. Karşılığında yanıtlar bulut modellerinden yavaş ve daha basittir; çalışırken pil tüketir ve telefon ısınabilir. Model bellek yetmezse ya da telefon çok ısınırsa çalışmaz (sağlık uyarısı gelir)."),
            )
            warnings.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            SectionCard(tr("Sohbet modelleri")) {
                Text(tr("Sohbet, kayıtlara soru, otomasyonlarda YZ adımı, özetleme ve sınıflandırma."), style = MaterialTheme.typography.bodySmall)
            }
            LocalModel.entries.filter { !it.embedding }.forEach { ModelCard(g, models, it, downloads[it.id], total, free) { refresh++ } }
            SectionCard(tr("Gömme modelleri")) {
                Text(tr("Anlamsal arama ve kayıtlara soru için metni vektöre çevirir."), style = MaterialTheme.typography.bodySmall)
            }
            LocalModel.entries.filter { it.embedding }.forEach { ModelCard(g, models, it, downloads[it.id], total, free) { refresh++ } }
            Text(tr("Modeller Hugging Face'ten indirilir ve SHA-256 ile doğrulanır. Lisanslar modelin sayfasında."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ModelCard(g: AppGraph, models: LocalModels, m: LocalModel, d: LocalModels.Download?, totalRam: Long, freeMb: Long, onChange: () -> Unit) {
    val installed = models.installed(m)
    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(m.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (installed) Pill(tr("İndirildi"))
        }
        Text(m.description, style = MaterialTheme.typography.bodySmall)
        Text(tr("{0} MB · yaklaşık {1} MB bellek · {2}", m.bytes / 1_048_576, m.ram, m.license), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (m.ram * 2 > totalRam) Text(tr("Bu telefonun belleği bu model için az olabilir."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        when (d) {
            is LocalModels.Download.Running -> {
                LinearProgressIndicator(progress = { d.done.toFloat() / m.bytes }, modifier = Modifier.fillMaxWidth())
                Text(tr("{0} / {1} MB", d.done / 1_048_576, m.bytes / 1_048_576), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { models.cancel(m) }) { Text(tr("Durdur")) }
            }
            is LocalModels.Download.Failed -> ErrorText(d.message)
            null -> {}
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!installed && d !is LocalModels.Download.Running) {
                OutlinedButton(enabled = models.supported && freeMb > m.bytes / 1_048_576, onClick = {
                    g.appScope.launch(Dispatchers.IO) { runCatching { models.download(m) }; onChange() }
                }) { Text(if (d is LocalModels.Download.Failed) tr("Sürdür") else tr("İndir")) }
            }
            if (installed) TextButton(onClick = { models.delete(m); onChange() }) { Text(tr("Sil")) }
        }
    }
}

/** İndirmeden önce dikkat edilecekler: ölçülü bağlantı ve pil. */
private fun conditions(ctx: Context): List<String> = buildList {
    val cm = ctx.getSystemService(ConnectivityManager::class.java)
    if (cm?.isActiveNetworkMetered == true) add(tr("Ölçülü (mobil) bağlantıdasınız; modeller yüzlerce MB, Wi-Fi'da indirin."))
    val battery = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    val level = battery?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1) } ?: 100
    if (!plugged && level < 50) add(tr("Pil %{0}; indirme ve model çalıştırma pil tüketir, şarja takın.", level))
}
