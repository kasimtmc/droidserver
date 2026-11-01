package com.plyg.droidserver.app.ui.wizard

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.plyg.droidserver.app.AppGraph
import com.plyg.droidserver.app.ui.common.ErrorText
import com.plyg.droidserver.app.ui.common.PlanView
import com.plyg.droidserver.app.ui.common.SectionCard
import com.plyg.droidserver.app.ui.common.SummaryTable
import com.plyg.droidserver.app.ui.common.WarningText
import com.plyg.droidserver.app.ui.common.allowsDeploy
import com.plyg.droidserver.app.ui.common.applyAndDeploy
import com.plyg.droidserver.app.ui.common.planFor
import com.plyg.droidserver.core.config.ConfigSecrets
import com.plyg.droidserver.core.config.ConfigSummary
import com.plyg.droidserver.core.config.DbEngine
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.config.ValidationError
import com.plyg.droidserver.core.db.MigrationPlan
import com.plyg.droidserver.core.i18n.tr
import com.plyg.droidserver.core.server.DeployException
import com.plyg.droidserver.core.server.DeployOrchestrator.DuringMove
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Son adım: okunur özet, çalışan veritabanına karşı şema planı ve "Kaydet ve deploy et". */
@Composable
internal fun SummaryStep(
    g: AppGraph,
    vm: WizardViewModel,
    draft: ServerConfig,
    errors: List<ValidationError>,
    onDeployed: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // Kullanılmayan tablolar silinince plan ve liste yeniden hesaplanır.
    var dropped by remember { mutableIntStateOf(0) }
    val plan by produceState<Result<MigrationPlan>?>(null, draft, dropped) { value = null; value = planFor(g, draft) }
    var deploying by remember { mutableStateOf(false) }
    var deployError by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<String?>(null) }
    val active by vm.active.collectAsState()

    StepColumn {
        SectionCard(tr("Konfigürasyon")) { SummaryTable(ConfigSummary.lines(draft)) }

        if (errors.isNotEmpty()) {
            SectionCard(tr("Düzeltilmesi gerekenler")) {
                errors.forEach { e ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(e.message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { vm.goTo(vm.stepFor(e)) }) { Text(tr("Düzelt")) }
                    }
                }
            }
        }

        val warnings = vm.securityWarnings() +
            vm.removedCollections().map { tr("'{0}' koleksiyonu kaldırıldı: tablo ve verisi veritabanında kalır ama API'de görünmez.", it) }
        if (warnings.isNotEmpty()) SectionCard(tr("Uyarılar")) { warnings.forEach { WarningText(it) } }

        SectionCard(tr("Şema planı")) { PlanView(plan) }

        UnusedTablesCard(g, vm.active.collectAsState().value, draft, dropped) { dropped++ }

        val upgrade = remember(active) { runCatching { g.configRepo.pendingUpgrade() }.getOrNull() }
        if (upgrade != null) {
            Text(
                tr("Kaydedince konfigürasyon dosyası v{0} → v{1} biçimine yükseltilir (önceki hali Geçmiş'te).", upgrade.from, upgrade.to),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        val moving = vm.shouldMoveData()
        val moveOptions by vm.moveOptions.collectAsState()
        val passwordChanged = vm.postgresPasswordChanged
        val passwordMethod by vm.passwordMethod.collectAsState()
        val previous = active
        if (moving && previous != null) {
            SectionCard(tr("Veri taşıma")) {
                Text(
                    tr("Kaydederken kayıtlar {0} → {1} taşınır. ", describe(previous), describe(draft)) +
                        when (moveOptions.duringMove) {
                            DuringMove.STOP -> tr("Bu sırada sunucu durur")
                            DuringMove.READ_ONLY -> tr("Bu sırada sunucu salt okunur çalışır (yazmalar 503 alır)")
                        } + tr("; bir sorun olursa eski veritabanıyla devam edilir. Hedefte kayıt varsa ") +
                        if (moveOptions.replaceExisting) tr("oradaki kayıtlar silinir.") else tr("taşıma durur."),
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { vm.goTo(WizardStep.DATABASE) }) { Text(tr("Seçenekleri değiştir")) }
            }
        }
        if (passwordChanged) {
            SectionCard(tr("PostgreSQL parolası")) {
                Text(
                    when (passwordMethod) {
                        PasswordMethod.CHANGE -> tr("Kaydederken Postgres'teki parola eski parolayla bağlanılarak değiştirilir.")
                        PasswordMethod.RESET -> tr("Kaydederken Postgres kısa süre durur ve parola sıfırlanır.")
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        val blocked = plan?.getOrNull()?.hasBlockers == true
        if (blocked) ErrorText(tr("Engelleyici şema sorunları çözülmeden deploy edilemez."))
        Button(
            onClick = {
                scope.launch {
                    deploying = true
                    deployError = null
                    try {
                        applyAndDeploy(ctx, g, vm, draft) { progress = it }
                        onDeployed()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: DeployException) {
                        deployError = e.errors.joinToString("\n") +
                            if (passwordChanged && passwordMethod == PasswordMethod.CHANGE) {
                                tr("\nEski parola artık geçmiyorsa Veritabanı adımında 'Parolayı sıfırla'yı seçin.")
                            } else ""
                    } catch (e: Exception) {
                        deployError = tr("Kaydedilemedi: {0}", e.message)
                    } finally {
                        deploying = false
                        progress = null
                    }
                }
            },
            enabled = errors.isEmpty() && plan.allowsDeploy() && !deploying,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(progress ?: tr("Kaydet ve deploy et")) }
        ErrorText(deployError)
        Text(tr("Deploy ilerlemesi Loglar sekmesinde canlı görünür."), style = MaterialTheme.typography.bodySmall)
    }
}

private fun describe(config: ServerConfig) = when (config.database.engine) {
    DbEngine.SQLITE -> "SQLite (${config.database.file})"
    DbEngine.POSTGRES -> "PostgreSQL"
}

/**
 * Kaldırılmış koleksiyonlardan kalan tablolar (etkin veritabanında) ve onayla kalıcı silme. Taslakta ya da çalışan
 * sunucuda kullanılan adlar listelenmez ([com.plyg.droidserver.core.server.ServerEngine.unusedTables]).
 */
@Composable
private fun UnusedTablesCard(g: AppGraph, active: ServerConfig?, draft: ServerConfig, refresh: Int, onDropped: () -> Unit) {
    val scope = rememberCoroutineScope()
    val keep = draft.schemaCollections().map { it.name }.toSet()
    val unused by produceState(emptyMap<String, Long>(), active, keep, refresh) {
        value = active?.let { cfg ->
            withContext(Dispatchers.IO) { runCatching { g.engine.unusedTables(ConfigSecrets.resolve(cfg, g.secrets::get), keep) }.getOrNull() }
        }.orEmpty()
    }
    var confirm by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    if (unused.isEmpty() || active == null) return
    SectionCard(tr("Kullanılmayan tablolar")) {
        Text(tr("Kaldırılmış koleksiyonlardan kalan tablolar: verileri duruyor ama API'de görünmüyor."), style = MaterialTheme.typography.bodySmall)
        unused.forEach { (table, rows) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("{0}: {1} kayıt", table, rows), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(enabled = !busy, onClick = { confirm = table }) { Text(tr("Sil"), color = MaterialTheme.colorScheme.error) }
            }
        }
        ErrorText(error)
    }
    confirm?.let { table ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(tr("'{0}' tablosu silinsin mi?", table)) },
            text = {
                Text(tr("{0} kayıt kalıcı olarak silinir; geçmişleri, çöp kutusundaki halleri ve dosyaları da. Geri alınamaz; gerekirse önce yedek alın (Sunucu → Yedekler).", unused[table] ?: 0))
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    busy = true
                    scope.launch {
                        error = runCatching {
                            withContext(Dispatchers.IO) { g.engine.dropUnusedTables(ConfigSecrets.resolve(active, g.secrets::get), listOf(table), keep) }
                        }.exceptionOrNull()?.message
                        busy = false
                        onDropped()
                    }
                }) { Text(tr("Sil"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(tr("Vazgeç")) } },
        )
    }
}
