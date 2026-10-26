package com.plyg.droidserver.app.ui.wizard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import com.plyg.droidserver.app.AppGraph
import com.plyg.droidserver.app.data.TemplateRequest
import com.plyg.droidserver.app.ui.common.ScreenScaffold
import com.plyg.droidserver.app.ui.common.WarningText
import com.plyg.droidserver.core.config.PostgresDefaults
import com.plyg.droidserver.core.i18n.tr
import com.plyg.droidserver.core.templates.AppTemplate

/**
 * Tam ekran sihirbaz: Genel → Veritabanı → Koleksiyonlar → Güvenlik → Erişim → Özet ve deploy.
 * Adım geçerli değilse "İleri" kapalıdır; hatalar ilgili alanın altında gösterilir.
 */
@Composable
fun WizardScreen(
    g: AppGraph,
    vm: WizardViewModel,
    onClose: () -> Unit,
    onDeployed: () -> Unit,
    onAdvanced: () -> Unit,
) {
    val step by vm.step.collectAsState()
    val draft by vm.draft.collectAsState()
    val errors by vm.errors.collectAsState()
    val editing by vm.editingCollection.collectAsState()
    val expert by vm.expert.collectAsState()
    val conflicts by vm.conflicts.collectAsState()
    fun readInstalled() = runCatching { g.components.installed().keys }.getOrDefault(emptySet())
    var installedIds by remember { mutableStateOf(readInstalled()) }
    val goBack = { if (!vm.back()) onClose() }
    BackHandler(onBack = goBack)

    // Şablonla açılan yeni profil: şablon ilk açılışta taslağa uygulanır (örnek veri ve site deploy'dan sonra).
    LaunchedEffect(Unit) {
        val pending = g.pendingTemplate.get()?.takeIf { it.applyDraft } ?: return@LaunchedEffect
        val t = AppTemplate.of(pending.id) ?: return@LaunchedEffect
        if (vm.applyTemplate(t, pending.site) == null) g.pendingTemplate.set(pending.copy(applyDraft = false))
    }

    // JSON düzenleyicide koleksiyon silinmişse açık düzenleyici kapanır.
    LaunchedEffect(editing, draft.collections.size) {
        if (editing != null && editing !in draft.collections.indices) vm.openCollection(null)
    }

    ScreenScaffold(
        title = if (editing != null) tr("Koleksiyonu düzenle") else step.title,
        subtitle = if (editing != null) null else tr("Sihirbaz"),
        onBack = goBack,
        actions = {
            // Uzman modu: otomatik eklenen alanlar ve uçlar da düzenlenir.
            TextButton(onClick = { vm.setExpert(!expert) }) { Text(if (expert) tr("Uzman ✓") else tr("Uzman")) }
            TextButton(onClick = onAdvanced) { Text("JSON") }
        },
        bottomBar = {
            if (editing == null) {
                val stepErrors = errors.count { step.owns(it.path) }
                Surface(tonalElevation = 3.dp) {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(onClick = goBack) { Text(if (step.ordinal == 0) tr("Kapat") else tr("Geri")) }
                        Spacer(Modifier.weight(1f))
                        if (stepErrors > 0 && step != WizardStep.SUMMARY) {
                            Text(
                                tr("{0} sorun", stepErrors), Modifier.padding(end = 8.dp),
                                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (step != WizardStep.SUMMARY) {
                            val next = WizardStep.entries.getOrNull(step.ordinal + 1)
                            Button(onClick = { vm.next() }, enabled = stepErrors == 0) {
                                Text(next?.let { tr("İleri: {0}", it.title) } ?: tr("İleri"))
                            }
                        }
                    }
                }
            }
        },
    ) { modifier ->
        Column(modifier.fillMaxSize()) {
            if (conflicts.isNotEmpty()) {
                // Dosya sihirbaz dışında değişti ve taslak aynı yerleri farklı değiştirmişti (WizardViewModel.onFileChanged).
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    WarningText(tr(
                        "Konfigürasyon siz düzenlerken başka yerden değişti (web paneli, paket ya da geri yükleme). Diğer değişiklikler taslağa eklendi; şu bölümlerde taslağınız kaldı, kaydederseniz oradaki yeni hal kaybolur: {0}",
                        conflicts.joinToString(", ") { it.ifEmpty { tr("tümü") } },
                    ))
                    Row {
                        TextButton(onClick = { vm.discard() }) { Text(tr("Taslağı at")) }
                        TextButton(onClick = { vm.dismissConflicts() }) { Text(tr("Tamam")) }
                    }
                }
            }
            if (editing == null) {
                val count = WizardStep.entries.size
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("Adım {0}/{1}", step.ordinal + 1, count), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.weight(1f))
                        val total = errors.count { e -> WizardStep.entries.any { it != WizardStep.SUMMARY && it.owns(e.path) } }
                        if (total > 0) Text(tr("{0} sorun", total), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                    }
                    LinearProgressIndicator(progress = { (step.ordinal + 1f) / count }, modifier = Modifier.fillMaxWidth())
                }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    WizardStep.entries.forEach { s ->
                        val hasErrors = s != WizardStep.SUMMARY && errors.any { s.owns(it.path) }
                        FilterChip(
                            selected = s == step,
                            onClick = { vm.goTo(s) },
                            label = { Text("${s.ordinal + 1}. ${s.title}") },
                            leadingIcon = if (hasErrors) {
                                { Icon(Icons.Default.ErrorOutline, contentDescription = tr("Hata var"), Modifier.size(16.dp)) }
                            } else null,
                        )
                    }
                }
            }
            Box(Modifier.weight(1f)) {
                val current = editing
                when {
                    current != null && current in draft.collections.indices -> CollectionEditor(vm, current, draft)
                    step == WizardStep.GENERAL -> GeneralStep(vm, draft)
                    step == WizardStep.DATABASE -> DatabaseStep(
                        g, vm, draft,
                        postgresInstalled = PostgresDefaults.COMPONENT_ID in installedIds,
                        onInstalled = { installedIds = readInstalled() },
                    )
                    step == WizardStep.USERS -> UsersStep(vm, draft)
                    step == WizardStep.COLLECTIONS -> CollectionsStep(
                        vm, draft,
                        siteExists = remember { g.engine.siteDir?.listFiles()?.isNotEmpty() == true },
                    ) { t, samples, site ->
                        vm.applyTemplate(t, site) ?: null.also { g.pendingTemplate.set(TemplateRequest(t.id, samples, site)) }
                    }
                    step == WizardStep.SECURITY -> SecurityStep(vm, draft)
                    step == WizardStep.ACCESS -> AccessStep(g, vm, draft, installedIds, onInstalled = { installedIds = readInstalled() })
                    else -> SummaryStep(g, vm, draft, errors, onDeployed)
                }
            }
        }
    }
}

/** Adım içeriği: kaydırılabilir, klavye açılınca alanlar görünür kalır. */
@Composable
internal fun StepColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
internal fun StepIntro(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
