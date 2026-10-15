package dev.droidserver.app.ui.config

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import dev.droidserver.app.AppGraph
import dev.droidserver.app.ui.common.ErrorText
import dev.droidserver.app.ui.common.ScreenScaffold
import dev.droidserver.app.ui.common.SectionCard
import dev.droidserver.app.ui.common.SoftCard
import dev.droidserver.app.ui.common.TipCard
import dev.droidserver.app.ui.common.saveConfigChange
import dev.droidserver.app.ui.wizard.WizardViewModel
import dev.droidserver.core.config.AiAccess
import dev.droidserver.core.config.AiConfig
import dev.droidserver.core.config.AiEffort
import dev.droidserver.core.config.AiModel
import dev.droidserver.core.config.AiProvider
import dev.droidserver.core.config.EmbeddingProvider
import dev.droidserver.core.config.EmbeddingsConfig
import dev.droidserver.core.config.FieldType
import dev.droidserver.core.config.SemanticIndexConfig
import dev.droidserver.core.i18n.tr
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Yapay zekâ ayarları: sağlayıcı (Claude, OpenAI, Gemini), anahtar ve model, kim kullanabilir ve günlük sınır, anlamsal arama (gömme servisi ve
 * dizinlenecek alanlar). Sunucu çalışırken bugünkü kullanım ve dizin durumu gösterilir.
 */
@Composable
fun AiScreen(g: AppGraph, wizard: WizardViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val active by wizard.active.collectAsState()
    val initial = active?.ai ?: AiConfig()
    val collections = active?.collections.orEmpty()

    var enabled by remember(initial) { mutableStateOf(initial.enabled) }
    var apiKey by remember { mutableStateOf("") }
    var provider0 by remember(initial) { mutableStateOf(initial.provider) }
    var model by remember(initial) { mutableStateOf(initial.effectiveModel) }
    var url by remember(initial) { mutableStateOf(initial.url) }
    var effort by remember(initial) { mutableStateOf(initial.effort) }
    var access by remember(initial) { mutableStateOf(initial.access) }
    var limit by remember(initial) { mutableStateOf(initial.dailyLimit.toString()) }
    var maxTokens by remember(initial) { mutableStateOf(initial.maxTokens.toString()) }
    var system by remember(initial) { mutableStateOf(initial.system) }
    var provider by remember(initial) { mutableStateOf(initial.embeddings?.provider) }
    var embUrl by remember(initial) { mutableStateOf(initial.embeddings?.url.orEmpty()) }
    var embModel by remember(initial) { mutableStateOf(initial.embeddings?.model.orEmpty()) }
    var embKey by remember { mutableStateOf("") }
    // Koleksiyon → dizinlenecek alanlar (boş: kapalı).
    val semantic = remember(initial) { mutableStateMapOf<String, List<String>>().apply { initial.semantic.forEach { put(it.collection, it.fields) } } }
    var errors by remember { mutableStateOf<List<String>>(emptyList()) }
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) { while (true) { delay(3_000); tick++ } }

    fun save() = scope.launch {
        errors = saveConfigChange(ctx, g, wizard, deploy = true, scope = listOf("ai")) { c ->
            val old = c.ai
            c.copy(
                ai = AiConfig(
                    enabled = enabled,
                    // Boş bırakılan anahtar alanı kayıtlı anahtarı korur; yazılan düz metin kaydedilirken şifreli depoya taşınır.
                    // Sağlayıcı değişince eski anahtar taşınmaz (başka sağlayıcının anahtarı).
                    provider = provider0,
                    apiKey = apiKey.trim().ifEmpty { if (provider0 == old.provider) old.apiKey else "" },
                    model = model.trim().takeIf { it != provider0.defaultModel }.orEmpty(),
                    url = if (provider0 == AiProvider.OPENAI) url.trim() else "",
                    effort = effort, access = access,
                    dailyLimit = limit.toIntOrNull() ?: -1, maxTokens = maxTokens.toIntOrNull() ?: 0, system = system.trim(),
                    embeddings = provider?.let { p ->
                        EmbeddingsConfig(p, embUrl.trim(), embModel.trim(), embKey.trim().ifEmpty { old.embeddings?.apiKey.orEmpty() })
                    },
                    semantic = if (provider == null) emptyList() else semantic.filterValues { it.isNotEmpty() }.map { (k, v) -> SemanticIndexConfig(k, v) },
                ),
            )
        }
        if (errors.isEmpty()) { apiKey = ""; embKey = "" }
    }

    ScreenScaffold(tr("Yapay zekâ"), subtitle = if (initial.enabled) AiModel.of(initial.effectiveModel)?.title ?: "${initial.provider.title} · ${initial.effectiveModel}" else tr("Kapalı"), onBack = onBack) { modifier ->
        Column(modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TipCard(
                g, "ai-intro",
                tr("Uygulamalarınız yapay zekâyı sunucu üzerinden kullanır: anahtar telefonda şifreli durur, istemcilere hiç gitmez. Ücreti seçtiğiniz sağlayıcıdaki kendi hesabınızdan ödenir; günlük sınırla harcamayı kontrol edin."),
            )

            // Sunucu çalışırken: bugünkü kullanım ve dizin durumu.
            val usage = remember(tick) { g.engine.ai.usage.summary() }
            val index = remember(tick) { g.engine.ai.index }
            if (initial.enabled) SoftCard {
                Text(tr("Bugün"), style = MaterialTheme.typography.titleSmall)
                Text(tr("{0} istek · {1} girdi, {2} çıktı token'ı", usage.first, usage.second, usage.third), style = MaterialTheme.typography.bodyMedium)
                index?.status()?.forEach { s ->
                    Text(
                        tr("{0}: {1} kayıt dizinde", s.collection, s.indexed) + if (s.pending > 0) tr(" · {0} bekliyor", s.pending) else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                index?.lastError?.let { ErrorText(it) }
                if (index != null) OutlinedButton(onClick = { index.rebuild() }) { Text(tr("Dizini yeniden kur")) }
            }

            SectionCard(tr("Sağlayıcı ve model")) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text(tr("Yapay zekâ açık"), Modifier.weight(1f)); Switch(enabled, { enabled = it }) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AiProvider.entries.forEach { p ->
                        FilterChip(provider0 == p, {
                            if (provider0 != p) { provider0 = p; model = p.defaultModel; apiKey = "" }
                        }, label = { Text(p.title) })
                    }
                }
                val keySaved = initial.apiKey.isNotEmpty() && initial.provider == provider0
                OutlinedTextField(
                    apiKey, { apiKey = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(), label = { Text(tr("{0} API anahtarı", provider0.title)) },
                    supportingText = {
                        Text(
                            when {
                                keySaved && apiKey.isEmpty() -> tr("Kayıtlı anahtar korunuyor; değiştirmek için yenisini yazın.")
                                provider0 == AiProvider.OPENAI && url.isNotBlank() -> tr("Yerel servislerde (Ollama, LM Studio) boş bırakılabilir. Şifreli depoda saklanır.")
                                else -> tr("{0} → API anahtarı. Şifreli depoda saklanır.", provider0.keyUrl.substringAfter("://").substringBefore('/'))
                            },
                        )
                    },
                )
                OutlinedButton(onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, provider0.keyUrl.toUri())) } }) {
                    Text(tr("Anahtar al"))
                }
                Text(tr("Model"), style = MaterialTheme.typography.titleSmall)
                AiModel.of(provider0).forEach { m ->
                    FilterChip(model == m.id, { model = m.id }, label = { Text(m.title) }, modifier = Modifier.fillMaxWidth())
                }
                OutlinedTextField(
                    model, { model = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text(tr("Model kimliği")) },
                    supportingText = { Text(tr("Listede olmayan bir model de yazabilirsiniz.")) },
                )
                if (provider0 == AiProvider.OPENAI) OutlinedTextField(
                    url, { url = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text(tr("Uç adresi (isteğe bağlı)")) },
                    placeholder = { Text("https://api.openai.com/v1") },
                    supportingText = { Text(tr("OpenAI uyumlu servisler için: Ollama (http://127.0.0.1:11434/v1), LM Studio, OpenRouter.")) },
                )
                Text(
                    when {
                        AiModel.of(model)?.fallbacks == true -> tr("Güvenlik denetimi bir isteği reddederse Anthropic'in önerdiği başka bir modelle otomatik yeniden denenir.")
                        else -> tr("Model isteği reddederse yanıtta refused: true döner.")
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(tr("Özen düzeyi"), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AiEffort.entries.forEach { e -> FilterChip(effort == e, { effort = e }, label = { Text(e.title) }) }
                }
                Text(tr("Düşük daha hızlı ve ucuz; yüksek zor sorularda daha özenli. Düşünme düzeyini desteklemeyen modellerde yok sayılır."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            SectionCard(tr("Kim kullanabilir")) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AiAccess.entries.forEach { a -> FilterChip(access == a, { access = a }, label = { Text(a.title) }) }
                }
                OutlinedTextField(
                    limit, { limit = it.filter(Char::isDigit) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), label = { Text(tr("Kişi başı günlük istek")) },
                    supportingText = { Text(tr("0: sınırsız. Yönetici sınırlanmaz; anonim istek hiçbir zaman kabul edilmez.")) },
                )
                OutlinedTextField(
                    maxTokens, { maxTokens = it.filter(Char::isDigit) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), label = { Text(tr("Yanıt uzunluğu (token)")) },
                )
                OutlinedTextField(
                    system, { system = it }, minLines = 2, modifier = Modifier.fillMaxWidth(), label = { Text(tr("Sistem talimatı (isteğe bağlı)")) },
                    placeholder = { Text(tr("Örn. Kısa ve Türkçe yanıtla.")) },
                )
            }

            SectionCard(tr("Anlamsal arama")) {
                Text(
                    tr("Kayıtları kelimeye değil anlama göre bulur (\"taşıt\" araması \"otomobil\" geçen kaydı bulur) ve kayıtlara soru sormayı iyileştirir. Metinleri vektöre çevirmek için bir gömme servisi gerekir."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(provider == null, { provider = null }, label = { Text(tr("Kapalı")) })
                    EmbeddingProvider.entries.forEach { p -> FilterChip(provider == p, { provider = p }, label = { Text(p.title) }) }
                }
                provider?.let { p ->
                    OutlinedTextField(embUrl, { embUrl = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text(tr("Adres")) }, placeholder = { Text(p.defaultUrl) })
                    OutlinedTextField(embModel, { embModel = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text(tr("Model")) }, placeholder = { Text(p.defaultModel) })
                    OutlinedTextField(
                        embKey, { embKey = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(), label = { Text(tr("API anahtarı")) },
                        supportingText = {
                            Text(
                                when {
                                    initial.embeddings?.apiKey?.isNotEmpty() == true && embKey.isEmpty() -> tr("Kayıtlı anahtar korunuyor; değiştirmek için yenisini yazın.")
                                    p == EmbeddingProvider.VOYAGE -> tr("dash.voyageai.com → API Keys")
                                    else -> tr("Yerel Ollama'da boş bırakın (ollama pull nomic-embed-text).")
                                },
                            )
                        },
                    )
                    Text(tr("Dizinlenecek alanlar"), style = MaterialTheme.typography.titleSmall)
                    collections.forEach { c ->
                        val eligible = c.fields.filter { it.type in setOf(FieldType.TEXT, FieldType.SELECT, FieldType.MULTISELECT) && !it.encrypted && it.visibility == null }
                        if (eligible.isEmpty()) return@forEach
                        val chosen = semantic[c.name].orEmpty()
                        Text(c.name, style = MaterialTheme.typography.bodyMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            eligible.forEach { f ->
                                FilterChip(f.name in chosen, { semantic[c.name] = if (f.name in chosen) chosen - f.name else chosen + f.name }, label = { Text(f.name) })
                            }
                        }
                    }
                    Text(
                        tr("Seçilen alanların metni gömme servisine gönderilir. Şifreli ve görünürlüğü kısıtlı alanlar listede yoktur."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            errors.forEach { ErrorText(it) }
            Button(onClick = { save() }, modifier = Modifier.fillMaxWidth()) { Text(tr("Kaydet ve deploy et")) }
        }
    }
}
