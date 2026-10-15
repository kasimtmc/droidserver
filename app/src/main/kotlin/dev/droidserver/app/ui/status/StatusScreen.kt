package dev.droidserver.app.ui.status

import android.os.PowerManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.droidserver.app.AppGraph
import dev.droidserver.app.data.NetUtil
import dev.droidserver.app.data.UpdateState
import dev.droidserver.app.service.ServerService
import dev.droidserver.app.ui.AppLock
import dev.droidserver.app.ui.common.ConfigUpgradeCard
import dev.droidserver.app.ui.common.ListRow
import dev.droidserver.app.ui.common.Pill
import dev.droidserver.app.ui.common.RowGroup
import dev.droidserver.app.ui.common.ScreenScaffold
import dev.droidserver.app.ui.common.SectionHeader
import dev.droidserver.app.ui.common.Sparkline
import dev.droidserver.app.ui.common.StatTile
import dev.droidserver.app.ui.common.StatusDot
import dev.droidserver.app.ui.common.TipCard
import dev.droidserver.app.ui.common.copyToClipboard
import dev.droidserver.app.ui.theme.LocalStatusColors
import dev.droidserver.app.ui.wizard.WizardViewModel
import dev.droidserver.core.api.issueJwt
import dev.droidserver.core.config.AuthMode
import dev.droidserver.core.config.ConfigSecrets
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.i18n.tr
import dev.droidserver.core.metrics.Metrics
import dev.droidserver.core.process.ProcessSupervisor
import dev.droidserver.core.server.EngineState
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant

/**
 * Pano: sunucunun durumu ve adresi (tek dokunuşla kopyalanır), başlat/durdur, son bir saatin istekleri, pil, çalışan
 * bileşen ve uygulamalar, etkin uyarılar; altında erişim, kopya, web paneli ve depolama kartları. Ayarlar (dil, tema,
 * kilit, otomatik başlatma) üst çubuktaki dişliden açılır.
 */
@Composable
fun StatusScreen(
    g: AppGraph,
    wizard: WizardViewModel,
    onStartWizard: () -> Unit,
    onConnect: () -> Unit = {},
    onTroubleshoot: () -> Unit = {},
    onSettings: () -> Unit = {},
    onOpen: (String) -> Unit = {},
) {
    val ctx = LocalContext.current
    val state by g.engine.state.collectAsState()
    val statuses by g.supervisor.statuses.collectAsState()
    var info by remember { mutableStateOf<String?>(null) }
    val hasConfig = remember(state) { g.configRepo.hasConfig() }
    val activeConfig = remember(state) { runCatching { g.configRepo.loadActiveOrNull() }.getOrNull() }
    var series by remember { mutableStateOf<List<Metrics.Minute>>(emptyList()) }
    LaunchedEffect(state) {
        while (true) {
            series = g.engine.metrics.series()
            delay(15_000)
        }
    }

    ScreenScaffold(
        "DroidServer",
        subtitle = g.profile.name.takeIf { !g.profile.isDefault },
        actions = {
            if (hasConfig) IconButton(onClick = { AppLock.guard(ctx) { onConnect() } }) { Icon(Icons.Default.QrCode2, contentDescription = tr("Bağlan (QR)")) }
            IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = tr("Ayarlar")) }
        },
    ) { modifier ->
        Column(
            modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (hasConfig) ConfigUpgradeCard(g, wizard, dismissible = true)
            val update by g.updater.state.collectAsState()
            if (update is UpdateState.Available || update is UpdateState.Downloading || update is UpdateState.Installing) UpdateCard(g)

            if (!hasConfig) WelcomeCard(onStartWizard) else ServerHero(state, activeConfig)

            if (hasConfig) {
                val pm = ctx.getSystemService(PowerManager::class.java)
                if (!pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
                    TipCard(
                        g, "battery",
                        tr("Android sunucuyu arka planda durdurabilir. DroidServer'ı pil optimizasyonundan muaf tutmanız önerilir."),
                        action = tr("Ayarlara git"), onAction = onSettings,
                    )
                }
            }

            if (state is EngineState.Running) {
                val total = series.sumOf { it.requests }
                val errors = series.sumOf { it.errors }
                val device = series.lastOrNull { it.device != null }?.device
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile(
                        Icons.Default.ShowChart, tr("Son 1 saat"), tr("{0} istek", total), Modifier.weight(1f).fillMaxHeight(),
                        detail = if (errors > 0) tr("{0} sunucu hatası", errors) else tr("hata yok"),
                        chart = { Sparkline(series.takeLast(60).map { it.requests.toFloat() }) },
                    )
                    StatTile(
                        if (device?.charging == true) Icons.Default.BatteryChargingFull else Icons.Default.BatteryStd,
                        tr("Pil"), device?.batteryPercent?.let { "%$it" } ?: "—", Modifier.weight(1f).fillMaxHeight(),
                        detail = listOfNotNull(
                            device?.batteryTempC?.let { "%.1f °C".format(it) },
                            tr("şarjda").takeIf { device?.charging == true },
                        ).joinToString(" · ").ifEmpty { null },
                        tint = if ((device?.batteryTempC ?: 0.0) >= 45.0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
                val procs = statuses.values
                val apps = procs.filter { it.id.startsWith(dev.droidserver.core.config.AppConfig.PROCESS_PREFIX) }
                val comps = procs - apps.toSet()
                val failed = procs.count { it.phase == ProcessSupervisor.Phase.FAILED }
                val alerts = remember(series) { g.engine.health.active() }
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile(
                        Icons.Default.Extension, tr("Süreçler"),
                        "${procs.count { it.phase == ProcessSupervisor.Phase.RUNNING }}/${procs.size}", Modifier.weight(1f).fillMaxHeight(),
                        detail = if (failed > 0) tr("{0} sorunlu", failed) else tr("{0} bileşen, {1} uygulama", comps.size, apps.size),
                        tint = if (failed > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        onClick = { onOpen(dev.droidserver.app.ui.Routes.COMPONENTS) },
                    )
                    StatTile(
                        Icons.Default.NotificationsActive, tr("Uyarılar"), alerts.size.toString(), Modifier.weight(1f).fillMaxHeight(),
                        detail = alerts.firstOrNull()?.title ?: tr("her şey yolunda"),
                        tint = if (alerts.isNotEmpty()) LocalStatusColors.current.warning else MaterialTheme.colorScheme.primary,
                        onClick = { onOpen(dev.droidserver.app.ui.Routes.ALERTS) },
                    )
                }
                activeConfig?.let { AccessStatusCard(g, it) }
                activeConfig?.let { ReplicaCard(g, it) }
                activeConfig?.takeIf { it.adminPanel.enabled }?.let { AdminPanelCard(g, it) }
            }
            activeConfig?.let { StorageCard(g, it) }

            SectionHeader(tr("Araçlar"))
            RowGroup {
                ListRow(Icons.Default.BugReport, tr("Sorun giderici"), subtitle = tr("Bağlantı, erişim ve izinleri tek ekranda denetler"), onClick = onTroubleshoot)
                if (hasConfig) ListRow(Icons.Default.QrCode2, tr("Bağlan (QR)"), subtitle = tr("Adres, erişim bilgisi ve panel girişi"), onClick = { AppLock.guard(ctx) { onConnect() } })
                ListRow(Icons.Default.Key, tr("İstemci erişimi / token"), subtitle = tr("API anahtarını veya JWT'yi panoya kopyalar"), onClick = {
                    AppLock.guard(ctx) { info = clientToken(ctx, g) }
                })
            }
            info?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** İlk açılış: ne yapılacağını anlatan karşılama kartı. */
@Composable
private fun WelcomeCard(onStartWizard: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.Dns, contentDescription = null, modifier = Modifier.size(40.dp))
            Text(tr("Telefonunuzu sunucuya çevirin"), style = MaterialTheme.typography.headlineSmall)
            Text(tr("Sihirbaz birkaç adımda veritabanını, koleksiyonları, güvenliği ve erişimi sorar; sonunda sunucu çalışır."), style = MaterialTheme.typography.bodyMedium)
            listOf(
                tr("Koleksiyonlarınızı seçin veya hazır bir uygulamayla başlayın"),
                tr("Güvenliği ve erişimi ayarlayın (yerel ağ, internet)"),
                tr("Kaydedin; API ve web paneli hazır"),
            ).forEachIndexed { i, s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pill("${i + 1}", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(10.dp))
                    Text(s, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Button(onClick = onStartWizard, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(tr("Sihirbazı başlat"))
            }
        }
    }
}

/** Sunucunun durum kartı: renk duruma göre; adresler dokununca kopyalanır. */
@Composable
private fun ServerHero(state: EngineState, config: ServerConfig?) {
    val ctx = LocalContext.current
    val status = LocalStatusColors.current
    val (container, content, dot) = when (state) {
        is EngineState.Running -> Triple(status.successContainer, status.onSuccessContainer, status.success)
        is EngineState.Failed -> Triple(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer, MaterialTheme.colorScheme.error)
        EngineState.Stopped -> Triple(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurface, MaterialTheme.colorScheme.outline)
    }
    var copied by remember { mutableStateOf<String?>(null) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = container, contentColor = content)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(dot, 12.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    when (state) {
                        is EngineState.Running -> tr("Çalışıyor")
                        is EngineState.Failed -> tr("Başlatılamadı")
                        EngineState.Stopped -> tr("Durduruldu")
                    },
                    style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f),
                )
                config?.let { Pill(":" + (if (state is EngineState.Running) state.port else it.server.port), content.copy(alpha = 0.12f), content) }
            }
            when (state) {
                is EngineState.Running -> {
                    NetUtil.localIpv4().ifEmpty { listOf("127.0.0.1") }.forEach { ip ->
                        val url = "http://$ip:${state.port}"
                        Row(
                            Modifier.fillMaxWidth().clickable { copyToClipboard(ctx, "adres", url); copied = url },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(url, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Icon(Icons.Default.ContentCopy, contentDescription = tr("Adresi kopyala"), modifier = Modifier.size(18.dp))
                        }
                    }
                    Text(
                        listOfNotNull(
                            tr("{0} süredir açık", uptime(state.since)),
                            config?.name,
                            copied?.let { tr("kopyalandı") },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = content.copy(alpha = 0.8f),
                    )
                    if (state.writesPaused) Text(tr("Salt okunur: veriler taşınıyor, yazma istekleri geçici olarak 503 alır."), style = MaterialTheme.typography.bodySmall)
                }
                is EngineState.Failed -> Text(state.reason, style = MaterialTheme.typography.bodyMedium)
                EngineState.Stopped -> Text(tr("Sunucu kapalı. Başlatınca yerel ağdaki cihazlar bağlanabilir."), style = MaterialTheme.typography.bodyMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state is EngineState.Running) {
                    FilledTonalButton(
                        onClick = { ServerService.stop(ctx) },
                        colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(containerColor = content.copy(alpha = 0.1f), contentColor = content),
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(tr("Durdur"))
                    }
                    OutlinedButton(onClick = { ServerService.deploy(ctx) }, colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = content)) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(tr("Yeniden deploy"))
                    }
                } else {
                    Button(onClick = { ServerService.start(ctx) }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(tr("Başlat"))
                    }
                    if (state is EngineState.Failed) TextButton(onClick = { ServerService.deploy(ctx) }) { Text(tr("Yeniden dene")) }
                }
            }
        }
    }
}

private fun uptime(since: Instant): String {
    val d = Duration.between(since, Instant.now()).coerceAtLeast(Duration.ZERO)
    return when {
        d.toDays() > 0 -> tr("{0} gün {1} sa", d.toDays(), d.toHours() % 24)
        d.toHours() > 0 -> tr("{0} sa {1} dk", d.toHours(), d.toMinutes() % 60)
        else -> tr("{0} dk", d.toMinutes())
    }
}

private fun clientToken(ctx: android.content.Context, g: AppGraph): String = runCatching {
    // Sırlar server.json'da "secret:<ad>" referansı olarak durur; değerler SecretStore'da.
    val active = g.configRepo.loadActiveOrNull() ?: return@runCatching tr("Henüz konfigürasyon yok")
    val cfg = ConfigSecrets.resolve(active, g.secrets::get)
    when (cfg.auth.mode) {
        AuthMode.JWT -> issueJwt(cfg, "istemci").also { copyToClipboard(ctx, "token", it) }.let { tr("JWT panoya kopyalandı (30 gün geçerli)") }
        AuthMode.API_KEY -> cfg.auth.apiKeys.firstOrNull()?.let {
            copyToClipboard(ctx, "token", it)
            tr("API anahtarı panoya kopyalandı; istekte 'Authorization: Bearer <anahtar>' gönderin")
        } ?: tr("API anahtarı tanımlı değil")
        AuthMode.NONE -> tr("Kimlik doğrulama kapalı — yerel ağ dışına açmayın!")
    }
}.getOrElse { tr("Hata: {0}", it.message) }
