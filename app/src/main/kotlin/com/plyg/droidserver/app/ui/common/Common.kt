package com.plyg.droidserver.app.ui.common

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.plyg.droidserver.app.AppGraph
import com.plyg.droidserver.app.service.ServerService
import com.plyg.droidserver.app.ui.wizard.PasswordMethod
import com.plyg.droidserver.app.ui.wizard.SET_PASSWORD_TASK
import com.plyg.droidserver.app.ui.wizard.WizardViewModel
import com.plyg.droidserver.core.config.ConfigSecrets
import com.plyg.droidserver.core.config.ConfigSummary
import com.plyg.droidserver.core.config.PostgresDefaults
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.db.DatabaseUnavailableException
import com.plyg.droidserver.core.db.MigrationPlan
import com.plyg.droidserver.core.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

fun copyToClipboard(ctx: Context, label: String, text: String) {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
}

/** Sırrı panoya kopyalar; uygulama kilidi açıksa önce telefonun doğrulamasını ister. */
fun copySecret(ctx: Context, label: String, text: String) =
    com.plyg.droidserver.app.ui.AppLock.guard(ctx, tr("Sırrı kopyalamak için doğrulayın")) { copyToClipboard(ctx, label, text) }

/** Sırrı ekranda kısaltarak gösterir: `ds_AbC…9xYz`. */
fun mask(secret: String): String = if (secret.length <= 12) "•".repeat(secret.length) else secret.take(6) + "…" + secret.takeLast(4)

/** Başlık çubuklu ekran iskeleti. [onBack] verilirse geri oku gösterilir. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    subtitle: String? = null,
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Geri")) }
                    }
                },
                actions = actions,
            )
        },
        bottomBar = bottomBar,
    ) { pad -> content(Modifier.padding(pad)) }
}

@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.fillMaxWidth(), colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
fun ErrorText(message: String?) {
    if (message != null) Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

@Composable
fun WarningText(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Default.Warning, contentDescription = tr("Uyarı"), tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

/** Tek seçimli kart (kimlik doğrulama modu, veritabanı, erişim). */
@Composable
fun ChoiceCard(
    selected: Boolean,
    title: String,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    badge: String? = null,
) {
    OutlinedCard(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null, enabled = enabled)
            Column(Modifier.padding(start = 8.dp).weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    badge?.let {
                        Spacer(Modifier.width(8.dp))
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                    }
                }
                Text(description, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Bölüm içindeki küçük tek seçim satırı (kartla gösterilecek kadar önemli olmayan alt seçenekler). */
@Composable
fun RadioRow(selected: Boolean, title: String, description: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.padding(start = 8.dp).weight(1f)) {
            val color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            Text(title, style = MaterialTheme.typography.bodyLarge, color = color)
            description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = color) }
        }
    }
}

@Composable
fun SummaryTable(lines: List<ConfigSummary.Line>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (line in lines) {
            Row {
                Text(
                    line.label, Modifier.width(128.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(line.value, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Şema planı: yükleniyor / hata / özet + uyarılar + engelleyici sorunlar. */
@Composable
fun PlanView(plan: Result<MigrationPlan>?) {
    when {
        plan == null -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(tr("Şema planı hesaplanıyor…"), style = MaterialTheme.typography.bodySmall)
        }
        plan.exceptionOrNull() is DatabaseUnavailableException -> WarningText(
            tr("Veritabanı henüz çalışmıyor (bileşen deploy sırasında başlatılır). Şema deploy sırasında uygulanır; " +
                "engelleyici sorun çıkarsa deploy reddedilir ve çalışan sunucuya dokunulmaz."),
        )
        plan.isFailure -> ErrorText(tr("Şema planı çıkarılamadı: {0}", plan.exceptionOrNull()?.message))
        else -> {
            val p = plan.getOrThrow()
            Text(p.summary(), style = MaterialTheme.typography.bodyMedium)
            p.changeTypes.forEach { c ->
                val detail = when {
                    c.action == null -> tr("kayıtlı değer yok")
                    c.cleared == 0L -> tr("{0} değerin hepsi çevrilecek", c.values)
                    else -> tr("{0} değerden {1} tanesi boşalacak", c.values, c.cleared)
                }
                Text("• $c: $detail", style = MaterialTheme.typography.bodySmall)
            }
            p.warnings.forEach { WarningText(it) }
            p.blockers.forEach { ErrorText(tr("Engelleyici: {0}", it)) }
        }
    }
}

/** Şema planı (dry-run) çalışan veritabanına karşı; arka planda. Postgres parolası için sırlar çözülür. */
suspend fun planFor(g: AppGraph, config: ServerConfig): Result<MigrationPlan> =
    withContext(Dispatchers.IO) { runCatching { g.engine.plan(ConfigSecrets.resolve(config, g.secrets::get)) } }

/**
 * Plan deploy'a izin veriyor mu? Engelleyici sorun yoksa evet. Veritabanı henüz çalışmıyorsa da evet
 * (ilk kez Postgres'e geçerken bileşen deploy'da başlar); motor deploy sırasında planı yine uygular ve engeller.
 */
fun Result<MigrationPlan>?.allowsDeploy(): Boolean =
    this != null && (getOrNull()?.hasBlockers == false || exceptionOrNull() is DatabaseUnavailableException)

/**
 * Taslağı kullanıcının bir kezlik seçimleriyle uygular (sihirbaz özeti ve JSON düzenleyici): veritabanı değişiyor ve
 * taşıma seçildiyse veriyi taşır, Postgres parolası değiştiyse seçilen yöntemle değiştirir, sonra kaydedip deploy eder.
 * Ekran kapansa bile yarıda kalmasın diye iptal edilemez. Hata: [DeployException] veya kaydetme hatası.
 */
suspend fun applyAndDeploy(ctx: Context, g: AppGraph, vm: WizardViewModel, draft: ServerConfig, progress: (String) -> Unit) =
    withContext(NonCancellable) {
        val previous = vm.active.value
        if (previous != null && vm.shouldMoveData()) {
            progress(tr("Veriler taşınıyor… (ayrıntılar Loglar'da)"))
            val options = vm.moveOptions.value
            withContext(Dispatchers.IO) { g.deployLock.withLock { g.orchestrator.copyDatabase(previous, draft, options) } }
        } else if (previous != null && vm.postgresPasswordChanged) {
            progress(tr("Parola değiştiriliyor…"))
            val method = vm.passwordMethod.value
            withContext(Dispatchers.IO) {
                g.deployLock.withLock {
                    when (method) {
                        PasswordMethod.CHANGE -> g.orchestrator.changeDatabasePassword(previous, draft)
                        PasswordMethod.RESET -> g.orchestrator.runComponentTask(draft, PostgresDefaults.COMPONENT_ID, SET_PASSWORD_TASK)
                    }
                }
            }
        }
        progress(tr("Kaydediliyor…"))
        saveAndDeploy(ctx, g, vm, draft.toJson())
    }

/** Konfigürasyonu kaydeder (sırlar depoya taşınır), sihirbazı günceller ve deploy'u başlatır. */
suspend fun saveAndDeploy(ctx: Context, g: AppGraph, wizard: WizardViewModel, text: String): ServerConfig {
    val saved = withContext(Dispatchers.IO) { g.configRepo.saveText(text, notify = false) }
    wizard.onSaved(saved)
    ServerService.deploy(ctx)
    return saved
}

/**
 * Sihirbaz dışındaki bir ekranın konfigürasyon değişikliği: etkin dosyaya [patch] uygulanıp kaydedilir (sırlar depoya
 * taşınır), kaydedilmemiş sihirbaz taslağı korunur ([WizardViewModel.onExternalChange]). [deploy] ise deploy başlar.
 * Yolu [scope] öneklerinden biriyle başlayan doğrulama hatası varsa kaydedilmez ve hatalar döner.
 */
suspend fun saveConfigChange(
    ctx: Context, g: AppGraph, wizard: WizardViewModel, deploy: Boolean, scope: List<String>, patch: (ServerConfig) -> ServerConfig,
): List<String> {
    val (saved, errors) = withContext(Dispatchers.IO) {
        val next = patch(g.configRepo.load())
        val resolved = runCatching { ConfigSecrets.resolve(next, g.secrets::get) }.getOrDefault(next)
        val errors = com.plyg.droidserver.core.config.ConfigValidator.validateDetailed(resolved)
            .filter { e -> scope.any { e.path.startsWith(it) } }.map { it.message }
        if (errors.isNotEmpty()) null to errors else g.configRepo.saveText(next.toJson(), notify = false) to emptyList()
    }
    if (saved == null) return errors
    wizard.onExternalChange(saved, patch)
    if (deploy) ServerService.deploy(ctx)
    return emptyList()
}
