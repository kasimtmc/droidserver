package dev.droidserver.app.ui.config

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import dev.droidserver.app.AppGraph
import dev.droidserver.app.data.NetUtil
import dev.droidserver.app.ui.common.ScreenScaffold
import dev.droidserver.app.ui.common.WarningText
import dev.droidserver.app.ui.common.copyToClipboard
import dev.droidserver.core.api.ApiExamples
import dev.droidserver.core.api.ApiProbe
import dev.droidserver.core.api.SdkGenerator
import dev.droidserver.core.api.issueJwt
import dev.droidserver.core.config.AuthMode
import dev.droidserver.core.config.ConfigJson
import dev.droidserver.core.config.ConfigSecrets
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.i18n.tr
import dev.droidserver.core.server.EngineState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement

/** Örnek biçimi: komut satırı, tarayıcı/Node.js, Android/JVM. */
private enum class ExampleFormat(val title: String) { CURL("curl"), JS("JavaScript"), KOTLIN(tr("Kotlin")) }

/**
 * Her koleksiyon için hazır curl / JavaScript / Kotlin örnekleri (gerçek anahtarla), cihaz içinden deneme isteği ve
 * OpenAPI dokümanının adresi.
 */
@Composable
fun ApiExplorerScreen(g: AppGraph, onBack: () -> Unit) {
    val engineState by g.engine.state.collectAsState()
    val active = remember { runCatching { g.configRepo.loadActiveOrNull() }.getOrNull() }
    val resolved = remember(active) { active?.let { runCatching { ConfigSecrets.resolve(it, g.secrets::get) }.getOrNull() } }
    val authorization = remember(resolved) {
        when (resolved?.auth?.mode) {
            AuthMode.API_KEY -> resolved.auth.apiKeys.firstOrNull()?.let { "Bearer $it" }
            AuthMode.JWT -> "Bearer " + issueJwt(resolved, "api-gezgini", ttlSeconds = 24L * 3600)
            else -> null
        }
    }
    val host = remember { NetUtil.localIpv4().firstOrNull() ?: "127.0.0.1" }
    var format by rememberSaveable { mutableStateOf(ExampleFormat.CURL) }

    ScreenScaffold(tr("API gezgini"), onBack = onBack) { modifier ->
        if (active == null) {
            Text(tr("Önce bir konfigürasyon kaydedin."), modifier.padding(16.dp))
            return@ScreenScaffold
        }
        val baseUrl = "http://$host:${active.server.port}"
        val running = engineState is EngineState.Running
        LazyColumn(
            modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    tr("Adres: {0}{1}. Örnekler gerçek anahtarla hazırlanır; kopyaladığınız komutları güvenli tutun.", baseUrl, active.server.basePath) +
                        if (active.auth.mode == AuthMode.JWT) tr(" JWT örnekleri 24 saat geçerli bir token içerir.") else "",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (resolved == null) WarningText(tr("Sırlar çözülemedi; örneklerde kimlik doğrulama başlığı yok."))
                if (!running) WarningText(tr("Sunucu çalışmıyor; \"Dene\" için Durum ekranından başlatın."))
                val ctx = LocalContext.current
                val openApi = "$baseUrl${active.server.basePath.trimEnd('/')}/_openapi.json"
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        tr("OpenAPI dokümanı: {0} (Postman, Swagger, istemci kod üreticileri için; yetkiye göre üretilir)", openApi),
                        Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    )
                    IconButton(onClick = { copyToClipboard(ctx, "openapi", openApi) }) { Icon(Icons.Default.ContentCopy, contentDescription = tr("Kopyala")) }
                }
                SdkShare(active)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ExampleFormat.entries.forEach { f -> FilterChip(selected = format == f, onClick = { format = f }, label = { Text(f.title) }) }
                }
            }
            if (active.auth.users.enabled) {
                item(key = "_auth") {
                    ExamplesCard(tr("Kullanıcı hesapları"), ApiExamples.forUsers(active), active, baseUrl, authorization, running, format)
                }
            }
            items(active.collections, key = { it.name }) { c ->
                ExamplesCard(c.name, ApiExamples.forCollection(active, c), active, baseUrl, authorization, running, format)
            }
        }
    }
}

@Composable
private fun ExamplesCard(
    title: String,
    examples: List<ApiExamples.Example>,
    config: ServerConfig,
    baseUrl: String,
    authorization: String?,
    running: Boolean,
    format: ExampleFormat,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // "Dene" yalnızca yönetici kimliğiyle yapılabilen ilk GET örneğini gönderir.
    val probe = examples.firstOrNull { it.method == "GET" && !it.userToken && !it.stream }
    var result by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
            examples.forEach { ex ->
                val code = when (format) {
                    ExampleFormat.CURL -> ex.curl(baseUrl, authorization)
                    ExampleFormat.JS -> ex.js(baseUrl, authorization)
                    ExampleFormat.KOTLIN -> ex.kotlin(baseUrl, authorization)
                }
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${ex.method} · ${ex.title}", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    IconButton(onClick = { copyToClipboard(ctx, format.title, code) }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = tr("Kopyala"))
                    }
                }
                Text(
                    code, Modifier.horizontalScroll(rememberScrollState()),
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp, softWrap = false,
                )
            }
            if (examples.isEmpty()) Text(tr("Tüm işlemler kapalı."), style = MaterialTheme.typography.bodySmall)
            val list = probe ?: return@Column
            HorizontalDivider()
            OutlinedButton(enabled = running && !busy, onClick = {
                scope.launch {
                    busy = true
                    result = withContext(Dispatchers.IO) {
                        runCatching {
                            val url = "http://127.0.0.1:${config.server.port}${list.path}"
                            val r = ApiProbe.get(url, authorization.takeIf { list.needsAuth })
                            "HTTP ${r.status}\n" + prettyJson(r.body)
                        }.getOrElse { tr("İstek başarısız: {0}", it.message) }
                    }
                    busy = false
                }
            }) { Text(if (busy) tr("Gönderiliyor…") else tr("Dene: {0}", list.title.lowercase())) }
            result?.let {
                Text(it.take(4_000), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
    }
}

private fun prettyJson(body: String): String =
    runCatching { ConfigJson.encodeToString(JsonElement.serializer(), ConfigJson.parseToJsonElement(body)) }.getOrDefault(body)

/**
 * Tipli istemci (SDK): koleksiyon tipleri, CRUD, giriş, canlı abonelik ve dosya yükleme içeren tek dosya; paylaşılır
 * veya kaydedilir. Konfigürasyon değişince yeniden alınmalı.
 */
@Composable
private fun SdkShare(config: ServerConfig) {
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf<SdkGenerator.Lang?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val lang = pending
        pending = null
        if (uri != null && lang != null) {
            runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(SdkGenerator.generate(config, lang).toByteArray()) } }
        }
    }
    Text(tr("İstemci SDK'sı"), style = MaterialTheme.typography.titleSmall)
    Text(
        tr("Uygulamanız için tipli istemci: koleksiyon tipleri, listeleme/filtre, ekleme/güncelleme/silme, dosya yükleme, giriş ve " +
            "canlı abonelik. TypeScript (tarayıcı, Node, React Native), Dart (Flutter) ve Swift (iOS, macOS) bağımlılıksız; Kotlin " +
            "(Android/JVM) kotlinx.serialization ister. Sunucudan da indirilebilir: GET …/_sdk/{ts|kotlin|dart|swift} (yönetici anahtarı)."),
        style = MaterialTheme.typography.bodySmall,
    )
    FlowRow {
        SdkGenerator.Lang.entries.forEach { lang ->
            TextButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, lang.fileName)
                    putExtra(Intent.EXTRA_TEXT, SdkGenerator.generate(config, lang))
                }
                ctx.startActivity(Intent.createChooser(send, lang.fileName))
            }) { Text(tr("{0} paylaş", lang.title)) }
        }
    }
    FlowRow {
        SdkGenerator.Lang.entries.forEach { lang ->
            TextButton(onClick = { pending = lang; save.launch(lang.fileName) }) { Text(tr("{0} kaydet", lang.title)) }
        }
    }
}
