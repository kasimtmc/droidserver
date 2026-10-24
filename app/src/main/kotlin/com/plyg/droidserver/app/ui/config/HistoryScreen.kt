package com.plyg.droidserver.app.ui.config

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import com.plyg.droidserver.app.ui.common.EmptyState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.plyg.droidserver.app.AppGraph
import com.plyg.droidserver.app.ui.common.ErrorText
import com.plyg.droidserver.app.ui.common.PlanView
import com.plyg.droidserver.app.ui.common.ScreenScaffold
import com.plyg.droidserver.app.ui.common.WarningText
import com.plyg.droidserver.app.ui.common.allowsDeploy
import com.plyg.droidserver.app.ui.common.planFor
import com.plyg.droidserver.app.ui.common.saveAndDeploy
import com.plyg.droidserver.app.ui.wizard.WizardViewModel
import com.plyg.droidserver.core.config.ConfigDiff
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.db.MigrationPlan
import com.plyg.droidserver.core.i18n.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Önceki konfigürasyonlar (her kayıtta bir kopya, son 20). Bir sürüm seçilirse etkin konfigürasyonla,
 * iki sürüm seçilirse birbirleriyle farkı gösterilir. "Bu sürüme dön" önce şema planını gösterir.
 */
@Composable
fun HistoryScreen(g: AppGraph, vm: WizardViewModel, onBack: () -> Unit, onDeployed: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val files = remember { g.configRepo.history() }
    val activeText = remember { runCatching { if (g.configRepo.hasConfig()) g.configRepo.loadText() else "" }.getOrDefault("") }
    var selected by rememberSaveable { mutableStateOf(listOf<String>()) }
    var restoring by remember { mutableStateOf<File?>(null) }
    var restorePlan by remember { mutableStateOf<Result<MigrationPlan>?>(null) }
    var restoreError by remember { mutableStateOf<String?>(null) }

    val diff = remember(selected) {
        val chosen = files.filter { it.name in selected }.sortedBy { it.name }
        when (chosen.size) {
            1 -> ConfigDiff.configs(chosen[0].readText(), activeText)
            2 -> ConfigDiff.configs(chosen[0].readText(), chosen[1].readText())
            else -> null
        }?.let { ConfigDiff.hunks(it) }
    }

    ScreenScaffold(tr("Geçmiş"), onBack = onBack) { modifier ->
        LazyColumn(
            modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    tr("Bir sürüm seçin: şu anki konfigürasyonla farkı görünür. İki sürüm seçerseniz ikisi karşılaştırılır."),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (files.isEmpty()) {
                item { EmptyState(Icons.Default.History, tr("Henüz geçmiş yok"), tr("Henüz geçmiş yok; her kayıtta önceki sürüm burada saklanır (son 20).")) }
            }
            items(files, key = { it.name }) { f ->
                val isSelected = f.name in selected
                OutlinedCard(
                    onClick = { selected = if (isSelected) selected - f.name else (selected + f.name).takeLast(2) },
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(
                        if (isSelected) 2.dp else 1.dp,
                        if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    ),
                ) {
                    Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = isSelected, onCheckedChange = null)
                        Text(historyLabel(f), Modifier.weight(1f).padding(start = 8.dp))
                        TextButton(onClick = {
                            restoring = f
                            restorePlan = null
                            restoreError = null
                            scope.launch {
                                restorePlan = runCatching { ServerConfig.fromJson(f.readText()) }
                                    .fold({ planFor(g, it) }, { Result.failure(it) })
                            }
                        }) { Text(tr("Bu sürüme dön")) }
                    }
                }
            }
            if (diff != null) {
                item {
                    Text(
                        if (selected.size == 1) tr("Seçilen sürüm → şu anki") else tr("Eski → yeni"),
                        style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (diff.isEmpty()) item { Text(tr("Fark yok."), style = MaterialTheme.typography.bodySmall) }
                items(diff) { line -> DiffLine(line) }
            }
        }
    }

    restoring?.let { file ->
        AlertDialog(
            onDismissRequest = { restoring = null },
            title = { Text(tr("Bu sürüme dönülsün mü?")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(historyLabel(file))
                    PlanView(restorePlan)
                    if (vm.hasUnsavedChanges) WarningText(tr("Kaydedilmemiş taslak atılacak."))
                    ErrorText(restoreError)
                }
            },
            confirmButton = {
                Button(
                    enabled = restorePlan.allowsDeploy(),
                    onClick = {
                        scope.launch {
                            try {
                                saveAndDeploy(ctx, g, vm, file.readText())
                                restoring = null
                                onDeployed()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                restoreError = tr("Geri yüklenemedi: {0}", e.message)
                            }
                        }
                    },
                ) { Text(tr("Geri yükle ve deploy et")) }
            },
            dismissButton = { TextButton(onClick = { restoring = null }) { Text(tr("Vazgeç")) } },
        )
    }
}

@Composable
private fun DiffLine(line: ConfigDiff.Line?) {
    if (line == null) {
        Text("⋯", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        return
    }
    val (prefix, background) = when (line.kind) {
        ConfigDiff.Kind.ADDED -> "+ " to MaterialTheme.colorScheme.primaryContainer
        ConfigDiff.Kind.REMOVED -> "- " to MaterialTheme.colorScheme.errorContainer
        ConfigDiff.Kind.SAME -> "  " to MaterialTheme.colorScheme.surface
    }
    Text(
        prefix + line.text,
        Modifier.fillMaxWidth().background(background).padding(horizontal = 4.dp),
        fontFamily = FontFamily.Monospace, fontSize = 11.sp,
    )
}

private val labelFormat = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm:ss")

/** `server-2026-10-13T01-02-03.456Z.json` → yerel saatle "13 Eki 2026 04:02:03". */
private fun historyLabel(file: File): String {
    val stamp = file.name.removePrefix("server-").removeSuffix(".json")
    val iso = stamp.replaceFirst(Regex("T(\\d{2})-(\\d{2})-(\\d{2})"), "T$1:$2:$3")
    return runCatching { labelFormat.format(Instant.parse(iso).atZone(ZoneId.systemDefault())) }.getOrDefault(stamp)
}
