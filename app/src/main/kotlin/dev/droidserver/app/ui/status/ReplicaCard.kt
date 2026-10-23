package dev.droidserver.app.ui.status

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.droidserver.app.AppGraph
import dev.droidserver.app.service.ServerService
import dev.droidserver.app.ui.common.SectionCard
import dev.droidserver.app.ui.common.WarningText
import dev.droidserver.core.audit.AuditLog
import dev.droidserver.core.config.ConfigMigrations
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.i18n.tr
import dev.droidserver.core.replication.Failover
import dev.droidserver.core.replication.ReplicaRegistry
import dev.droidserver.core.replication.ReplicaStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Kopya sunucunun eşitleme durumu; baştan eşitleme ve devralma (ana sunucu olma). */
@Composable
internal fun ReplicaCard(g: AppGraph, config: ServerConfig) {
    val source = config.replication.replicaOf ?: return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by g.engine.replica.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    val time = remember { DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()) }
    SectionCard(tr("Canlı kopya")) {
        Text(tr("Ana sunucu: {0}", source.url), style = MaterialTheme.typography.bodySmall)
        val s = status
        if (s == null) {
            Text(tr("Eşitleme başlamadı."), style = MaterialTheme.typography.bodySmall)
        } else {
            Text(
                s.phase.title + when {
                    s.phase == ReplicaStatus.Phase.SYNCING && s.behind == 0L -> tr(" · güncel")
                    s.phase == ReplicaStatus.Phase.SYNCING -> tr(" · {0} değişiklik geride", s.behind)
                    else -> ""
                },
                style = MaterialTheme.typography.titleSmall,
            )
            s.progress?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text(
                listOfNotNull(
                    s.lastContact?.let { tr("son temas {0}", time.format(it)) },
                    s.lastApplied?.let { tr("son eşitleme {0}", time.format(it)) },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
            )
            s.error?.let { WarningText(it) }
            s.failoverNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        val f = config.replication.failover
        if (f.auto) {
            val at = s?.takeoverAt?.takeIf { s.error != null }
            Text(
                if (at != null) tr("Otomatik devralma: ana sunucuya ulaşılamıyor; {0} itibarıyla bu sunucu devralır.", time.format(at))
                else tr("Otomatik devralma açık: ana sunucuya {0} sn ulaşılamazsa bu sunucu devralır.", f.afterSec),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (source.demoted && f.failback) {
            Text(tr("Bu sunucu devredilmişti; güncel kalınca ana rolü kendiliğinden geri alır."), style = MaterialTheme.typography.bodySmall)
        }
        Text(tr("Kopya salt okunurdur: yazma, giriş ve kayıt istekleri 503 alır."), style = MaterialTheme.typography.bodySmall)
        Row {
            TextButton(onClick = { g.engine.resyncReplica() }) { Text(tr("Baştan eşitle")) }
            TextButton(onClick = { confirm = true }) { Text(tr("Devral")) }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(tr("Bu sunucu ana sunucu olsun mu?")) },
            text = {
                Text(
                    tr("Eşitleme durur ve bu sunucu yazılabilir olur. Eski ana sunucuya ulaşılınca yazmayı keser ve bu sunucunun " +
                        "kopyası olur (önce kendi verisini yedekler). Son birkaç saniyedeki yazmalar kaybolabilir. İstemcileri bu sunucunun " +
                        "adresine yönlendirin (anahtarlar aynı) veya aynı DuckDNS adını kullanın."),
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirm = false
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            runCatching {
                                val cfg = g.configRepo.load()
                                g.configRepo.saveText(Failover.promote(cfg, status?.primaryId).toJson())
                                g.engine.audit.record(AuditLog.APP, "replica.promote", source.url)
                            }.onFailure { g.logs.add("Devralınamadı: ${it.message}") }
                        }
                        ServerService.deploy(ctx)
                    }
                }) { Text(tr("Devral")) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(tr("Vazgeç")) } },
        )
    }
}

/** Ana sunucuda: bağlanan kopyalar, gecikmeleri ve konfigürasyon sürümleri (yarım dakikada bir yenilenir). */
@Composable
internal fun PrimaryReplicasCard(g: AppGraph, config: ServerConfig) {
    if (!config.replication.source || config.replication.replicaOf != null) return
    var table by remember { mutableStateOf<Pair<Long?, List<ReplicaRegistry.Report>>?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            table = withContext(Dispatchers.IO) { runCatching { g.engine.replicaTable() }.getOrNull() }
            delay(30_000)
        }
    }
    val (latest, reports) = table ?: return
    if (reports.isEmpty()) return
    val time = remember { DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()) }
    SectionCard(tr("Kopyalar")) {
        reports.forEach { r ->
            val online = Duration.between(r.lastSeen, Instant.now()).seconds < 120
            val behind = r.appliedSeq?.let { a -> latest?.let { (it - a).coerceAtLeast(0) } }
            Text(r.url, style = MaterialTheme.typography.titleSmall)
            Text(
                listOfNotNull(
                    if (online) tr("bağlı") else tr("bağlı değil"),
                    behind?.let { if (it == 0L) tr("güncel") else tr("{0} değişiklik geride", it) },
                    r.version?.let { v -> if (v == ConfigMigrations.CURRENT) "v$v" else tr("v{0} (bu sunucu v{1}; uygulamayı güncelleyin)", v, ConfigMigrations.CURRENT) },
                    tr("son temas {0}", time.format(r.lastSeen)),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = if (online && (r.version == null || r.version == ConfigMigrations.CURRENT)) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        }
    }
}

