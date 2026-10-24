package com.plyg.droidserver.app.ui.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.plyg.droidserver.app.AppGraph
import com.plyg.droidserver.app.ui.common.ErrorText
import com.plyg.droidserver.app.ui.common.PlanView
import com.plyg.droidserver.app.ui.common.ScreenScaffold
import com.plyg.droidserver.app.ui.common.allowsDeploy
import com.plyg.droidserver.app.ui.common.applyAndDeploy
import com.plyg.droidserver.app.ui.common.planFor
import com.plyg.droidserver.app.ui.wizard.MOVE_DATA_PATH
import com.plyg.droidserver.app.ui.wizard.WizardViewModel
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.db.MigrationPlan
import com.plyg.droidserver.core.i18n.tr
import com.plyg.droidserver.core.server.DeployException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Gelişmiş mod: sihirbazla aynı taslağın JSON hali. Geçerli JSON yazıldıkça taslak güncellenir
 * (sihirbazda görünür); sihirbazda yapılan değişiklik de buraya yansır.
 */
@Composable
fun JsonEditorScreen(
    g: AppGraph,
    vm: WizardViewModel,
    onBack: () -> Unit,
    onDeployed: () -> Unit,
    onWizard: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val draft by vm.draft.collectAsState()
    val errors by vm.errors.collectAsState()
    var text by rememberSaveable { mutableStateOf(draft.toJson()) }
    var parseError by remember { mutableStateOf<String?>(null) }
    var plan by remember { mutableStateOf<Result<MigrationPlan>?>(null) }
    var showPlan by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var deploying by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<String?>(null) }

    // Taslak başka yerden değiştiyse metni yenile; buradan yazılan değişiklikte metne dokunma.
    LaunchedEffect(draft) {
        if (runCatching { ServerConfig.fromJson(text) }.getOrNull() != draft) {
            text = draft.toJson()
            parseError = null
        }
    }

    ScreenScaffold(
        title = tr("Gelişmiş (JSON)"),
        onBack = onBack,
        actions = { TextButton(onClick = onWizard) { Text(tr("Sihirbaz")) } },
    ) { modifier ->
        Column(modifier.fillMaxSize().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                tr("Sihirbazla aynı taslak. Sırlar \"secret:<ad>\" olarak görünür; düz metin yazılan anahtarlar " +
                    "kaydedilince şifreli depoya taşınır."),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = text,
                onValueChange = { v ->
                    text = v
                    runCatching { ServerConfig.fromJson(v) }
                        .onSuccess { parseError = null; vm.replaceDraft(it) }
                        .onFailure { parseError = it.message }
                },
                modifier = Modifier.fillMaxWidth().weight(1f),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
            )
            when {
                parseError != null -> ErrorText(tr("JSON hatası: {0}", parseError))
                errors.isEmpty() -> Text(tr("✓ Geçerli"), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                else -> {
                    errors.take(5).forEach { ErrorText("${it.path}: ${it.message}") }
                    if (errors.size > 5) ErrorText(tr("… ve {0} sorun daha", errors.size - 5))
                    // Taşıma gibi bir kezlik seçimler konfigürasyonda değil, sihirbazda yapılır.
                    errors.firstOrNull { it.path == MOVE_DATA_PATH }?.let { e ->
                        TextButton(onClick = { vm.goTo(vm.stepFor(e)); onWizard() }) { Text(tr("Sihirbazda seç")) }
                    }
                }
            }
            if (showPlan) PlanView(plan)
            status?.let { ErrorText(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    showPlan = true
                    scope.launch { plan = null; plan = planFor(g, draft) }
                }) { Text(tr("Şema planı")) }
                Button(
                    enabled = parseError == null && errors.isEmpty() && !deploying,
                    onClick = {
                        scope.launch {
                            deploying = true
                            status = null
                            try {
                                val p = planFor(g, draft)
                                if (!p.allowsDeploy()) {
                                    plan = p
                                    showPlan = true
                                    status = if (p.isFailure) tr("Şema planı çıkarılamadı; deploy edilmedi.")
                                    else tr("Şema planı engelleyici sorun içeriyor; deploy edilmedi.")
                                } else {
                                    applyAndDeploy(ctx, g, vm, draft) { progress = it }
                                    onDeployed()
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: DeployException) {
                                status = e.errors.joinToString("\n")
                            } catch (e: Exception) {
                                status = tr("Kaydedilemedi: {0}", e.message)
                            } finally {
                                deploying = false
                                progress = null
                            }
                        }
                    },
                ) { Text(progress ?: tr("Kaydet ve deploy et")) }
            }
        }
    }
}
