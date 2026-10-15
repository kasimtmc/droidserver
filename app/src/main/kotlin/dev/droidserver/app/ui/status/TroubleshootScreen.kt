package dev.droidserver.app.ui.status

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import dev.droidserver.app.AppGraph
import dev.droidserver.app.data.NetUtil
import dev.droidserver.app.service.ServerService
import dev.droidserver.app.ui.common.ScreenScaffold
import dev.droidserver.core.config.AccessMode
import dev.droidserver.core.config.CloudflareTunnel
import dev.droidserver.core.config.ConfigSecrets
import dev.droidserver.core.config.ConfigValidator
import dev.droidserver.core.config.QuickTunnel
import dev.droidserver.core.diagnostics.DiagnosticInput
import dev.droidserver.core.diagnostics.Diagnostics
import dev.droidserver.core.diagnostics.Finding
import dev.droidserver.core.diagnostics.Fix
import dev.droidserver.core.diagnostics.Level
import dev.droidserver.core.i18n.tr
import dev.droidserver.core.process.ProcessSupervisor
import dev.droidserver.core.server.EngineState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/** Tek ekranda teşhis: sunucu, ağ, internetten erişim, sertifika, bileşenler, pil, bildirimler, depolama. */
@Composable
fun TroubleshootScreen(g: AppGraph, onBack: () -> Unit, onFix: (Fix) -> Unit) {
    val ctx = LocalContext.current
    var findings by remember { mutableStateOf<List<Finding>?>(null) }
    var round by remember { mutableIntStateOf(0) }
    LaunchedEffect(round) {
        findings = null
        findings = withContext(Dispatchers.IO) { Diagnostics.run(collect(ctx, g)) }
    }
    ScreenScaffold(
        tr("Sorun giderici"), onBack = onBack,
        actions = { IconButton(onClick = { round++ }, enabled = findings != null) { Icon(Icons.Default.Refresh, contentDescription = tr("Yeniden denetle")) } },
    ) { modifier ->
        LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(tr("Sunucuya ulaşılamıyorsa buradan başlayın: her bulgunun yanında önerilen çözüm var."), style = MaterialTheme.typography.bodySmall)
                if (findings == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                    Text(tr("Denetleniyor… (internet denetimi birkaç saniye sürebilir)"), style = MaterialTheme.typography.bodySmall)
                }
            }
            items(findings.orEmpty()) { f -> FindingCard(f) { fix -> handle(ctx, fix, onFix) } }
        }
    }
}

@Composable
private fun FindingCard(f: Finding, onFix: (Fix) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            val (icon, color) = when (f.level) {
                Level.PROBLEM -> Icons.Default.Error to MaterialTheme.colorScheme.error
                Level.WARNING -> Icons.Default.Warning to MaterialTheme.colorScheme.tertiary
                Level.INFO -> Icons.Default.Info to MaterialTheme.colorScheme.secondary
                Level.OK -> Icons.Default.CheckCircle to MaterialTheme.colorScheme.primary
            }
            Icon(icon, contentDescription = null, tint = color)
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(f.title, style = MaterialTheme.typography.titleSmall)
                if (f.detail.isNotBlank()) Text(f.detail, style = MaterialTheme.typography.bodySmall)
                f.fix?.let { fix -> TextButton(onClick = { onFix(fix) }) { Text(fixLabel(fix)) } }
            }
        }
    }
}

private fun fixLabel(fix: Fix) = when (fix) {
    Fix.START_SERVER -> tr("Sunucuyu başlat")
    Fix.BATTERY_SETTINGS -> tr("Pil ayarını aç")
    Fix.NOTIFICATION_SETTINGS -> tr("Bildirim ayarlarını aç")
    Fix.OPEN_WIZARD -> tr("Sihirbazı aç")
    Fix.OPEN_ACCESS -> tr("Erişim adımına git")
    Fix.OPEN_COMPONENTS -> tr("Bileşenlere git")
    Fix.OPEN_LOGS -> tr("Loglara git")
    Fix.OPEN_WIFI_SETTINGS -> tr("Wi-Fi ayarlarını aç")
}

/** Android ayarlarıyla çözülenler burada; uygulama içi gezinme [onFix]'e. Pil muafiyeti bir sunucu için gerekli. */
@SuppressLint("BatteryLife")
private fun handle(ctx: Context, fix: Fix, onFix: (Fix) -> Unit) {
    val intent = when (fix) {
        Fix.START_SERVER -> { ServerService.start(ctx); null }
        Fix.BATTERY_SETTINGS -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${ctx.packageName}".toUri())
        Fix.NOTIFICATION_SETTINGS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
        Fix.OPEN_WIFI_SETTINGS -> Intent(Settings.ACTION_WIFI_SETTINGS)
        else -> return onFix(fix)
    }
    intent?.let { runCatching { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
}

/** Girdileri toplar (ağ istekleri dahil; IO iş parçacığında). Boş alan yalnızca bilgi için (ayırma yapılmaz). */
@SuppressLint("UsableSpace")
private fun collect(ctx: Context, g: AppGraph): DiagnosticInput {
    val hasConfig = g.configRepo.hasConfig()
    val active = runCatching { g.configRepo.loadActiveOrNull() }.getOrNull()
    val configErrors = active?.let { cfg ->
        runCatching { ConfigValidator.validate(ConfigSecrets.resolve(cfg, g.secrets::get)) }.getOrElse { listOf(it.message ?: it.toString()) }
    }.orEmpty()
    val state = g.engine.state.value
    val port = active?.server?.port ?: 8080
    val base = active?.server?.basePath?.trimEnd('/') ?: "/api"
    val running = state is EngineState.Running
    val access = active?.access?.mode ?: AccessMode.LOCAL
    val direct = g.direct.status.value
    val publicUrl = when (access) {
        AccessMode.CLOUDFLARE -> if (active?.access?.cloudflare?.tunnel == CloudflareTunnel.QUICK) {
            QuickTunnel.urlIn(g.supervisor.logs(AccessMode.CLOUDFLARE.componentId!!))
        } else active?.access?.cloudflare?.hostname?.let { "https://$it" }
        AccessMode.DIRECT -> direct.ddnsHost?.let { "https://$it:${direct.httpsPort}" } ?: direct.addresses.firstOrNull()?.let { "https://$it:${direct.httpsPort}" }
        else -> null
    }
    val cm = ctx.getSystemService(ConnectivityManager::class.java)
    val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
    return DiagnosticInput(
        hasConfig = hasConfig,
        configErrors = configErrors,
        running = running,
        failure = (state as? EngineState.Failed)?.reason,
        port = port,
        localHealth = if (running) healthy("http://127.0.0.1:$port$base/health") else null,
        localAddresses = NetUtil.localIpv4(),
        wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
        internet = reachable("github.com", 443),
        access = access,
        publicUrl = publicUrl,
        // Doğrudan erişimde telefon kendi dış adresine çoğu modemde ulaşamaz (hairpin); yalnızca tünel denenir.
        publicHealth = if (running && access == AccessMode.CLOUDFLARE && publicUrl != null) healthy("$publicUrl$base/health") else null,
        directProblems = if (access == AccessMode.DIRECT) direct.problems + direct.mappingErrors + listOfNotNull(direct.error, direct.ddnsError, direct.certificateError) else emptyList(),
        certificateUntil = if (access == AccessMode.DIRECT) direct.certificateUntil else null,
        failedComponents = g.supervisor.statuses.value.values.filter { it.phase == ProcessSupervisor.Phase.FAILED }.map { it.id },
        batteryOptimized = ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName)?.not(),
        notificationsAllowed = ctx.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled(),
        freeStorageBytes = g.dataRoot.usableSpace,
        activeAlerts = g.engine.health.active().map { "${it.title}: ${it.message}" },
    )
}

private fun healthy(url: String): Boolean = runCatching {
    (URL(url).openConnection() as HttpURLConnection).run {
        connectTimeout = 4_000
        readTimeout = 4_000
        try { responseCode in 200..299 } finally { disconnect() }
    }
}.getOrDefault(false)

private fun reachable(host: String, port: Int): Boolean = runCatching {
    Socket().use { it.connect(InetSocketAddress(host, port), 4_000) }
    true
}.getOrDefault(false)
