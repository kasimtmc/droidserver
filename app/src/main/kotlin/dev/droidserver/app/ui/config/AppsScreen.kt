package dev.droidserver.app.ui.config

import androidx.compose.material.icons.filled.RocketLaunch
import dev.droidserver.app.ui.common.TipCard
import dev.droidserver.app.ui.common.EmptyState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.droidserver.app.AppGraph
import dev.droidserver.app.service.ServerService
import dev.droidserver.app.ui.common.RadioRow
import dev.droidserver.app.ui.common.ErrorText
import dev.droidserver.app.ui.common.ScreenScaffold
import dev.droidserver.app.ui.common.SectionCard
import dev.droidserver.app.ui.common.WarningText
import dev.droidserver.app.ui.common.copySecret
import dev.droidserver.app.ui.common.saveConfigChange
import dev.droidserver.app.ui.wizard.WizardViewModel
import dev.droidserver.core.apps.AppRelease
import dev.droidserver.core.config.AppAccess
import dev.droidserver.core.config.AppConfig
import dev.droidserver.core.config.CommandLine
import dev.droidserver.core.config.ConfigDefaults
import dev.droidserver.core.config.ConfigSecrets
import dev.droidserver.core.config.EnvFile
import dev.droidserver.core.config.GitProvider
import dev.droidserver.core.config.GitSource
import dev.droidserver.core.config.RestartPolicy
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Kendi uygulamalarınız (Node.js, Python, Java, ikili): liste ve durum, zip yükleme, Git'ten çekme, sürümler arasında
 * dönme, düzenleme. Uygulama `127.0.0.1:<port>`'ta çalışır ve sunucunun yolu altında yayınlanır.
 */
@Composable
fun AppsScreen(g: AppGraph, wizard: WizardViewModel, onBack: () -> Unit, onLogs: () -> Unit, onComponents: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val active by wizard.active.collectAsState()
    val statuses by g.supervisor.statuses.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<AppConfig?>(null) }
    var creating by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var releasesOf by remember { mutableStateOf<String?>(null) }
    var uploadFor by remember { mutableStateOf<String?>(null) }
    val apps = active?.apps.orEmpty()

    fun work(label: String, block: suspend () -> String?) = scope.launch {
        busy = label
        message = runCatching { block() }.fold({ it }, { tr("Hata: {0}", it.message) })
        busy = null
        refresh++
    }

    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val name = uploadFor
        uploadFor = null
        if (uri != null && name != null) work(tr("Yükleniyor…")) {
            val r = g.apps.upload(name, uri, deploy = true)
            tr("{0}: yeni sürüm {1} ({2} KB) yüklendi; deploy başladı, sağlık denetimi sonucu Loglar'da", name, r.id, r.sizeBytes / 1024)
        }
    }

    val edit = editing
    if (edit != null || creating) {
        AppEditor(g, wizard, active ?: ServerConfig(), edit, onDone = { editing = null; creating = false; refresh++ }, onComponents = onComponents)
        return
    }

    ScreenScaffold(
        tr("Uygulamalar"),
        onBack = onBack,
        actions = { IconButton(onClick = { creating = true }) { Icon(Icons.Default.Add, contentDescription = tr("Uygulama ekle")) } },
    ) { modifier ->
        LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                if (apps.isEmpty()) {
                    EmptyState(
                        Icons.Default.RocketLaunch, tr("Henüz uygulama yok"), tr("Kendi yazdığınız sunucuyu (Node.js, Python, Java veya derlenmiş bir program) telefonda çalıştırın: kod zip olarak yüklenir veya Git'ten çekilir, uygulama PORT ortam değişkenindeki portu dinler ve sunucunun yolu altında (örn. /apps/api) HTTPS, tünel ve güvenlik duvarıyla birlikte yayınlanır. Yeni sürüm sağlık denetiminden geçmezse önceki sürüm çalışmaya devam eder."),
                        action = tr("Uygulama ekle"), onAction = { creating = true },
                    )
                    TipCard(g, "apps-runtime", tr("Önce Bileşenler'den çalışma ortamını (örn. Node.js) kurun."), action = tr("Bileşenlere git"), onAction = onComponents)
                } else {
                    TipCard(g, "apps-intro", tr("Kendi yazdığınız sunucuyu (Node.js, Python, Java veya derlenmiş bir program) telefonda çalıştırın: kod zip olarak yüklenir veya Git'ten çekilir, uygulama PORT ortam değişkenindeki portu dinler ve sunucunun yolu altında (örn. /apps/api) HTTPS, tünel ve güvenlik duvarıyla birlikte yayınlanır. Yeni sürüm sağlık denetiminden geçmezse önceki sürüm çalışmaya devam eder."))
                }
                busy?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            items(apps, key = { it.name }) { app ->
                val st = remember(app.name, refresh, statuses) { g.apps.manager.state(app.name) }
                val releases = remember(app.name, refresh, statuses) { g.apps.manager.releases(app.name) }
                val phase = statuses[app.processId]?.phase
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(app.name + if (!app.enabled) tr(" (kapalı)") else "", style = MaterialTheme.typography.titleMedium)
                        Text("${app.publicPath} → 127.0.0.1:${app.port}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        Text(
                            listOfNotNull(
                                app.runtime?.let { tr("Ortam: {0}", it) },
                                tr("Durum: {0}", phase?.name ?: tr("çalışmıyor")),
                                st.current?.let { cur -> tr("Sürüm: {0}", releases.firstOrNull { it.id == cur }?.commit?.take(8) ?: cur) },
                                st.pending?.let { tr("Bekleyen: {0} (deploy'da devreye girer)", it) },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        st.failed.entries.lastOrNull()?.let { (id, why) -> ErrorText(tr("Son başarısız sürüm {0}: {1}", id, why)) }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(enabled = busy == null, onClick = { uploadFor = app.name; uploadLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }) {
                                Text(tr("Zip yükle"))
                            }
                            if (app.git != null) {
                                OutlinedButton(enabled = busy == null, onClick = {
                                    work(tr("Git'ten çekiliyor…")) {
                                        val r = g.apps.pull(app.name)
                                        if (r == null) tr("{0}: değişiklik yok (son commit zaten yüklü)", app.name)
                                        else { ServerService.deploy(ctx); tr("{0}: {1} indirildi; deploy başladı", app.name, r.commit?.take(12) ?: r.id) }
                                    }
                                }) { Text(tr("Git'ten çek")) }
                            }
                            OutlinedButton(enabled = releases.isNotEmpty(), onClick = { releasesOf = app.name }) { Text(tr("Sürümler ({0})", releases.size)) }
                            OutlinedButton(onClick = { editing = app }) { Text(tr("Düzenle")) }
                            TextButton(onClick = onLogs) { Text(tr("Loglar")) }
                        }
                    }
                }
            }
        }
    }

    releasesOf?.let { name ->
        val app = apps.firstOrNull { it.name == name }
        val list = remember(name, refresh) { g.apps.manager.releases(name) }
        val st = remember(name, refresh) { g.apps.manager.state(name) }
        AlertDialog(
            onDismissRequest = { releasesOf = null },
            title = { Text(tr("{0} sürümleri", name)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(tr("Eski bir sürüme dönmek için seçin; deploy'da devreye girer. En çok {0} sürüm saklanır.", app?.keepReleases ?: 3), style = MaterialTheme.typography.bodySmall)
                    list.forEach { r -> ReleaseRow(r, current = r.id == st.current, pending = r.id == st.pending, failed = st.failed[r.id]) {
                        releasesOf = null
                        work(tr("Geri dönülüyor…")) {
                            withContext(Dispatchers.IO) { g.apps.manager.activate(name, r.id) }
                            ServerService.deploy(ctx)
                            tr("{0}: {1} sürümüne dönülüyor; deploy başladı", name, r.id)
                        }
                    } }
                }
            },
            confirmButton = { TextButton(onClick = { releasesOf = null }) { Text(tr("Kapat")) } },
        )
    }
}

@Composable
private fun ReleaseRow(r: AppRelease, current: Boolean, pending: Boolean, failed: String?, onActivate: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(r.id + (r.commit?.let { " · ${it.take(8)}" } ?: ""), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Text(
                listOfNotNull(
                    if (r.source == "git") "Git" else tr("Yükleme"),
                    "${r.sizeBytes / 1024} KB",
                    tr("çalışan").takeIf { current },
                    tr("bekleyen").takeIf { pending },
                    failed?.let { tr("başarısız") },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = if (failed != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!current && !pending) TextButton(onClick = onActivate) { Text(tr("Bu sürüme dön")) }
    }
}

/** Uygulama ekleme/düzenleme; kaydedince deploy edilir. */
@Composable
private fun AppEditor(g: AppGraph, wizard: WizardViewModel, cfg: ServerConfig, initial: AppConfig?, onDone: () -> Unit, onComponents: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val runtimes = remember { g.components.installed().values.filter { it.pkg.isRuntime }.map { it.id } }
    val usedPorts = cfg.apps.filter { it.name != initial?.name }.map { it.port }.toSet() + cfg.server.port
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var enabled by remember { mutableStateOf(initial?.enabled ?: true) }
    var runtime by remember { mutableStateOf(initial?.runtime ?: runtimes.firstOrNull()) }
    var command by remember { mutableStateOf(initial?.command?.let(CommandLine::join) ?: defaultCommand(runtimes.firstOrNull())) }
    var build by remember { mutableStateOf(initial?.build?.joinToString("\n") { CommandLine.join(it) }.orEmpty()) }
    var port by remember { mutableStateOf((initial?.port ?: (3000..3999).first { it !in usedPorts }).toString()) }
    var path by remember { mutableStateOf(initial?.path.orEmpty()) }
    var stripPath by remember { mutableStateOf(initial?.stripPath ?: true) }
    var access by remember { mutableStateOf(initial?.access ?: AppAccess.PUBLIC) }
    var health by remember { mutableStateOf(initial?.let { it.health.orEmpty() } ?: "/") }
    var startTimeout by remember { mutableStateOf((initial?.startTimeoutSec ?: 60).toString()) }
    var keep by remember { mutableStateOf((initial?.keepReleases ?: 3).toString()) }
    var maxBody by remember { mutableStateOf((initial?.maxBodyMb ?: 10).toString()) }
    var websockets by remember { mutableStateOf(initial?.websockets ?: false) }
    var sharedEnv by remember { mutableStateOf(initial?.sharedEnv ?: true) }
    var restart by remember { mutableStateOf(initial?.restart ?: RestartPolicy.ON_FAILURE) }
    var envText by remember { mutableStateOf(initial?.env?.let { EnvFile.format(it) }.orEmpty()) }
    val git0 = initial?.git
    var provider by remember { mutableStateOf<GitProvider?>(git0?.provider) }
    var repo by remember { mutableStateOf(git0?.repo.orEmpty()) }
    var host by remember { mutableStateOf(git0?.host.orEmpty()) }
    var branch by remember { mutableStateOf(git0?.branch ?: "main") }
    var subdir by remember { mutableStateOf(git0?.subdir.orEmpty()) }
    var token by remember { mutableStateOf("") }
    var hookSecret by remember { mutableStateOf(git0?.webhookSecret) }
    var newHookSecret by remember { mutableStateOf<String?>(null) }
    var errors by remember { mutableStateOf<List<String>>(emptyList()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val envParsed = remember(envText) { EnvFile.parse(envText) }

    fun build(): AppConfig {
        val git = provider?.let { p ->
            GitSource(
                provider = p, repo = repo.trim(), host = host.trim().ifEmpty { null }, branch = branch.trim().ifEmpty { "main" },
                subdir = subdir.trim().trim('/').ifEmpty { null },
                token = token.ifEmpty { git0?.token?.takeIf { git0.provider == p } },
                webhookSecret = hookSecret,
            )
        }
        return AppConfig(
            name = name.trim(), enabled = enabled, runtime = runtime, command = CommandLine.split(command),
            build = build.lines().map(CommandLine::split).filter { it.isNotEmpty() },
            port = port.toIntOrNull() ?: 0, path = path.trim().ifEmpty { null }, stripPath = stripPath, access = access,
            health = health.trim().ifEmpty { null }, startTimeoutSec = startTimeout.toIntOrNull() ?: 0,
            env = envParsed.toMap(), sharedEnv = sharedEnv, restart = restart, keepReleases = keep.toIntOrNull() ?: 0,
            maxBodyMb = maxBody.toIntOrNull() ?: 0, websockets = websockets, git = git,
        )
    }

    fun save() = scope.launch {
        if (envParsed.errors.isNotEmpty()) { errors = envParsed.errors.map { it.toString() }; return@launch }
        val app = build()
        errors = saveConfigChange(ctx, g, wizard, deploy = true, scope = listOf("apps")) { c ->
            c.copy(apps = if (initial == null) c.apps + app else c.apps.map { if (it.name == initial.name) app else it })
        }
        if (errors.isEmpty()) onDone()
    }

    ScreenScaffold(initial?.name ?: tr("Yeni uygulama"), onBack = onDone) { modifier ->
        Column(modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionCard(tr("Genel")) {
                OutlinedTextField(
                    name, { name = it.lowercase().trim() }, label = { Text(tr("Ad")) }, singleLine = true, enabled = initial == null,
                    supportingText = { Text(tr("Küçük harf, rakam ve -; varsayılan yol /apps/{0}", name.ifEmpty { "ad" })) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Etkin"), Modifier.weight(1f)); Switch(enabled, { enabled = it })
                }
            }
            SectionCard(tr("Çalıştırma")) {
                Text(tr("Çalışma ortamı"), style = MaterialTheme.typography.labelLarge)
                ChoiceRow(tr("Yok (koddaki program veya sh)"), runtime == null) { runtime = null }
                runtimes.forEach { rt -> ChoiceRow(rt, runtime == rt) { if (runtime != rt && command == defaultCommand(runtime)) command = defaultCommand(rt); runtime = rt } }
                if (runtimes.isEmpty()) {
                    WarningText(tr("Kurulu çalışma ortamı yok. Node.js, Python veya Java için önce Bileşenler → Katalog'dan kurun."))
                    TextButton(onClick = onComponents) { Text(tr("Bileşenlere git")) }
                }
                OutlinedTextField(
                    command, { command = it }, label = { Text(tr("Başlatma komutu")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    supportingText = { Text(tr("Örn. node server.js · python3 app.py · java -jar app.jar · ./sunucu")) },
                )
                OutlinedTextField(
                    build, { build = it }, label = { Text(tr("Kurulum komutları (isteğe bağlı, satır başına bir)")) }, minLines = 2, modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    supportingText = { Text(tr("Her yeni sürümde bir kez çalışır, örn. npm ci --omit=dev. Telefonda derleme gerektiren paketler kurulamayabilir; bağımlılıkları zip'e dahil etmek en güvenlisi.")) },
                )
                Text(tr("Çökünce"), style = MaterialTheme.typography.labelLarge)
                ChoiceRow(tr("Yeniden başlat (hata ile kapanınca)"), restart == RestartPolicy.ON_FAILURE) { restart = RestartPolicy.ON_FAILURE }
                ChoiceRow(tr("Her zaman yeniden başlat"), restart == RestartPolicy.ALWAYS) { restart = RestartPolicy.ALWAYS }
                ChoiceRow(tr("Yeniden başlatma"), restart == RestartPolicy.NEVER) { restart = RestartPolicy.NEVER }
            }
            SectionCard(tr("Yayın")) {
                OutlinedTextField(
                    port, { port = it.filter(Char::isDigit).take(5) }, label = { Text(tr("Port")) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = { Text(tr("Uygulama bu portu PORT ortam değişkeninden okumalı; yalnızca telefonun içinden (127.0.0.1) erişilir")) },
                )
                OutlinedTextField(
                    path, { path = it.trim() }, label = { Text(tr("Yol")) }, singleLine = true, placeholder = { Text("/apps/${name.ifEmpty { "ad" }}") },
                    supportingText = { Text(tr("Boşsa /apps/{0}. API yolu ({1}) ve /_admin kullanılamaz.", name.ifEmpty { "ad" }, cfg.server.basePath)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Yol önekini uygulamaya iletme (/apps/api/x → /x)"), Modifier.weight(1f)); Switch(stripPath, { stripPath = it })
                }
                Text(tr("Kim erişebilir"), style = MaterialTheme.typography.labelLarge)
                AppAccess.entries.forEach { a -> ChoiceRow(a.title, access == a) { access = a } }
                if (access != AppAccess.PUBLIC) {
                    Text(tr("Kimlik sunucuda doğrulanır; uygulamaya X-DroidServer-User, X-DroidServer-Role ve X-DroidServer-Admin başlıklarıyla iletilir."), style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("WebSocket bağlantılarını da ilet"), Modifier.weight(1f)); Switch(websockets, { websockets = it })
                }
                OutlinedTextField(
                    health, { health = it.trim() }, label = { Text(tr("Sağlık denetimi yolu")) }, singleLine = true,
                    supportingText = { Text(tr("Uygulamanın kendi yolu (örn. /health); 2xx/3xx yanıtı beklenir. Boşsa yalnızca portun açılması beklenir.")) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(startTimeout, { startTimeout = it.filter(Char::isDigit).take(3) }, label = { Text(tr("Başlama süresi (sn)")) }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(maxBody, { maxBody = it.filter(Char::isDigit).take(4) }, label = { Text(tr("Gövde sınırı (MB)")) }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(keep, { keep = it.filter(Char::isDigit).take(2) }, label = { Text(tr("Saklanan sürüm")) }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
            }
            SectionCard(tr("Ortam değişkenleri")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Ortak ortam değişkenlerini ve sırları kullan (Sunucu → Ortam ve sırlar)"), Modifier.weight(1f)); Switch(sharedEnv, { sharedEnv = it })
                }
                OutlinedTextField(
                    envText, { envText = it }, label = { Text(tr("Bu uygulamaya özel (.env biçiminde)")) }, minLines = 3, modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    supportingText = { Text(tr("Buradaki değerler server.json'da düz metin durur; parola ve anahtarları Ortam ve sırlar ekranında tutun. Sunucu ayrıca PORT, HOST, BASE_PATH, DATA_DIR (sürümler arası kalıcı klasör) ve TMPDIR verir.")) },
                )
                envParsed.problems.forEach { if (it.warning) WarningText(it.toString()) else ErrorText(it.toString()) }
            }
            SectionCard(tr("Git'ten deploy (isteğe bağlı)")) {
                ChoiceRow(tr("Yok (zip yükleyerek)"), provider == null) { provider = null }
                GitProvider.entries.forEach { p -> ChoiceRow(p.title, provider == p) { provider = p } }
                provider?.let { p ->
                    OutlinedTextField(
                        repo, { repo = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text(if (p == GitProvider.URL) tr("Zip adresi (https://…)") else tr("Depo (sahip/depo)")) },
                    )
                    if (p == GitProvider.GITLAB || p == GitProvider.GITEA) {
                        OutlinedTextField(host, { host = it.trim() }, singleLine = true, label = { Text(tr("Sunucu adı")) }, placeholder = { Text(if (p == GitProvider.GITLAB) "gitlab.com" else "git.example.com") })
                    }
                    if (p != GitProvider.URL) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(branch, { branch = it.trim() }, singleLine = true, label = { Text(tr("Dal")) }, modifier = Modifier.weight(1f))
                            OutlinedTextField(subdir, { subdir = it.trim() }, singleLine = true, label = { Text(tr("Alt klasör")) }, modifier = Modifier.weight(1f))
                        }
                    }
                    OutlinedTextField(
                        token, { token = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                        label = { Text(tr("Erişim token'ı (gizli depo için)")) },
                        supportingText = {
                            Text(
                                if (git0?.token != null && token.isEmpty()) tr("Kayıtlı token korunuyor; değiştirmek için yenisini yazın.")
                                else tr("Yalnızca okuma izni yeterli (GitHub: ince ayarlı token, Contents: Read). Şifreli depoda saklanır."),
                            )
                        },
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("Push'ta otomatik deploy (webhook)"), Modifier.weight(1f))
                        Switch(hookSecret != null, { on ->
                            if (on) ConfigDefaults.generateWebhookSecret().let { hookSecret = it; newHookSecret = it } else { hookSecret = null; newHookSecret = null }
                        })
                    }
                    if (hookSecret != null) {
                        Text(
                            tr("Deponun webhook ayarına adres olarak <sunucu adresiniz>{0}/_apps/{1}/hook, içerik türü application/json ve aşağıdaki sırrı girin (GitHub: Secret, GitLab: Secret token). Yalnızca \"{2}\" dalına push deploy edilir.", cfg.server.basePath, name.ifEmpty { "ad" }, branch),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        newHookSecret?.let { s ->
                            Text(s, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { copySecret(ctx, tr("Webhook sırrı"), s) }) { Text(tr("Sırrı kopyala")) }
                            Text(tr("Sır kaydedince şifreli depoya taşınır ve bir daha gösterilmez."), style = MaterialTheme.typography.bodySmall)
                        } ?: if (hookSecret?.let(ConfigSecrets::isRef) == true) {
                            TextButton(onClick = { ConfigDefaults.generateWebhookSecret().let { hookSecret = it; newHookSecret = it } }) { Text(tr("Yeni sır üret")) }
                        } else Unit
                    }
                }
            }
            errors.forEach { ErrorText(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = name.isNotBlank() && command.isNotBlank(), onClick = { save() }) { Text(tr("Kaydet ve deploy et")) }
                TextButton(onClick = onDone) { Text(tr("Vazgeç")) }
                if (initial != null) TextButton(onClick = { confirmDelete = true }) { Text(tr("Sil"), color = MaterialTheme.colorScheme.error) }
            }
        }
    }

    if (confirmDelete && initial != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(tr("{0} silinsin mi?", initial.name)) },
            text = { Text(tr("Uygulama durdurulur; tüm sürümleri ve kalıcı veri klasörü (DATA_DIR) silinir. Geri alınamaz.")) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        errors = saveConfigChange(ctx, g, wizard, deploy = true, scope = listOf("apps")) { c -> c.copy(apps = c.apps.filter { it.name != initial.name }) }
                        if (errors.isEmpty()) {
                            withContext(Dispatchers.IO) {
                                g.supervisor.stop(initial.processId)
                                g.apps.manager.delete(initial.name)
                            }
                            onDone()
                        }
                    }
                }) { Text(tr("Sil"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(tr("Vazgeç")) } },
        )
    }
}

@Composable
private fun ChoiceRow(title: String, selected: Boolean, onClick: () -> Unit) = RadioRow(selected, title, onClick = onClick)

private fun defaultCommand(runtime: String?): String = when {
    runtime == null -> "sh start.sh"
    runtime.startsWith("node") -> "node server.js"
    runtime.startsWith("python") -> "python3 app.py"
    runtime.contains("jdk") || runtime.contains("java") -> "java -jar app.jar"
    else -> ""
}
