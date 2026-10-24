package com.plyg.droidserver.app.ui.wizard

import com.plyg.droidserver.core.api.EndpointGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.plyg.droidserver.app.AppGraph
import com.plyg.droidserver.app.service.ServerService
import com.plyg.droidserver.app.ui.common.ChoiceCard
import com.plyg.droidserver.app.ui.common.ErrorText
import com.plyg.droidserver.app.ui.common.RadioRow
import com.plyg.droidserver.app.ui.common.SectionCard
import com.plyg.droidserver.core.config.DbEngine
import com.plyg.droidserver.core.config.PostgresDefaults
import com.plyg.droidserver.core.config.PostgresUrl
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.i18n.tr
import com.plyg.droidserver.core.server.DeployException
import com.plyg.droidserver.core.server.DeployOrchestrator.DuringMove
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Postgres bileşeninin parola sıfırlama işi (bkz. docs/components.example.json). */
internal const val SET_PASSWORD_TASK = "set-password"

@Composable
internal fun GeneralStep(vm: WizardViewModel, draft: ServerConfig) {
    StepColumn {
        StepIntro(tr("Sunucunuzun adı, hangi porttan yayın yapacağı ve API adreslerinin hangi yolla başlayacağı."))

        val nameError = vm.errorAt("name")
        OutlinedTextField(
            value = draft.name, onValueChange = vm::setName,
            label = { Text(tr("Sunucu adı")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            isError = nameError != null, supportingText = nameError?.let { { Text(it) } },
        )

        // Metin alanı yerel tutulur: yazarken geçici olarak boş/eksik sayı olabilir.
        var portText by rememberSaveable { mutableStateOf(draft.server.port.toString()) }
        LaunchedEffect(draft.server.port) {
            if (portText.toIntOrNull()?.let { it != draft.server.port } == true) portText = draft.server.port.toString()
        }
        val portError = if (portText.isEmpty()) tr("Port gerekli") else vm.errorAt("server.port")
        OutlinedTextField(
            value = portText,
            onValueChange = { v -> portText = v.filter(Char::isDigit).take(5); portText.toIntOrNull()?.let(vm::setPort) },
            label = { Text(tr("Port")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = portError != null,
            supportingText = { Text(portError ?: tr("1024-65535 arası; varsayılan 8080. Root olmadan alt portlar açılamaz.")) },
        )

        val pathError = vm.errorAt("server.basePath")
        OutlinedTextField(
            value = draft.server.basePath, onValueChange = vm::setBasePath,
            label = { Text(tr("API yolu")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            isError = pathError != null,
            supportingText = { Text(pathError ?: tr("Örn. /api → http://<cihaz>:{0}{1}/health", draft.server.port, draft.server.basePath)) },
        )

        val site = draft.static
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("Statik site"), style = MaterialTheme.typography.bodyLarge)
                Text(
                    tr("HTML, CSS, JavaScript ve görsellerden oluşan bir siteyi de yayınlayın. Dosyaları kaydettikten sonra " +
                        "Sunucu ekranından zip olarak yüklersiniz."),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = site.enabled, onCheckedChange = { on -> vm.updateStatic { it.copy(enabled = on) } })
        }
        if (site.enabled) {
            val staticError = vm.errorAt("static.path")
            OutlinedTextField(
                value = site.path, onValueChange = { v -> vm.updateStatic { it.copy(path = v.trim()) } },
                label = { Text(tr("Site yolu")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                isError = staticError != null,
                supportingText = {
                    Text(staticError ?: tr("/ → http://<cihaz>:{0}/ ; API yolu ({1}) her zaman API'ye gider", draft.server.port, draft.server.basePath))
                },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = site.spaFallback, onCheckedChange = { on -> vm.updateStatic { it.copy(spaFallback = on) } })
                Column {
                    Text(tr("Bulunamayan sayfalarda index.html"))
                    Text(
                        tr("React, Vue gibi tek sayfalık uygulamaların kendi yönlendirmesi için açık bırakın."),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("Canlı abonelik"), style = MaterialTheme.typography.bodyLarge)
                Text(
                    tr("İstemciler kayıt eklenince, değişince veya silinince anında haber alır (sohbet, canlı pano, bildirim). " +
                        "{0}/realtime adresinden; koleksiyon kuralları her olayda uygulanır.", draft.server.basePath),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = draft.realtime.enabled, onCheckedChange = { on -> vm.updateRealtime { it.copy(enabled = on) } })
        }
        if (draft.realtime.enabled) {
            val rtError = vm.errorAt("realtime.maxConnections")
            OutlinedTextField(
                value = draft.realtime.maxConnections.toString(),
                onValueChange = { v -> v.filter(Char::isDigit).take(4).toIntOrNull()?.let { n -> vm.updateRealtime { it.copy(maxConnections = n) } } },
                label = { Text(tr("En çok eşzamanlı canlı bağlantı")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                isError = rtError != null,
                supportingText = { Text(rtError ?: tr("Her açık sekme veya uygulama bir bağlantıdır.")) },
            )
            ChannelsSection(vm, draft)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("GraphQL"), style = MaterialTheme.typography.bodyLarge)
                Text(
                    tr("REST'in yanında {0}/graphql ucu: istemci tek istekte istediği alanları ve ilişkili kayıtları alır. Kurallar REST'le aynıdır; şema {0}/graphql/schema adresinde.", draft.server.basePath),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = draft.api.graphql, onCheckedChange = { on -> vm.update { it.copy(api = it.api.copy(graphql = on)) } })
        }

        HorizontalDivider()
        AlertsSection(vm, draft)

        HorizontalDivider()
        PhoneSection(vm, draft)

        val expert by vm.expert.collectAsState()
        if (expert) {
            HorizontalDivider()
            ExpertHeader(tr("Sunucu uçları"), tr("Sağlık, toplu işlem, canlı abonelik, dosyalar, OpenAPI, yönetici ve web push uçları."))
            EndpointsEditor(vm, draft, listOf(EndpointGroup.SERVER, EndpointGroup.ADMIN, EndpointGroup.PUSH))
            HorizontalDivider()
            LimitsEditor(vm, draft)
        }
    }
}

@Composable
internal fun DatabaseStep(
    g: AppGraph,
    vm: WizardViewModel,
    draft: ServerConfig,
    postgresInstalled: Boolean,
    onInstalled: () -> Unit,
) {
    val engine = draft.database.engine
    val moveData by vm.moveData.collectAsState()
    StepColumn {
        StepIntro(tr("Kayıtların saklanacağı veritabanı."))
        ChoiceCard(
            selected = engine == DbEngine.SQLITE,
            title = "SQLite",
            description = tr("Uygulamanın içinde çalışır, ek kurulum gerektirmez. Çoğu kullanım için önerilir."),
            onClick = { vm.setSqliteFile(draft.database.file) },
        )
        if (engine == DbEngine.SQLITE) {
            val fileError = vm.errorAt("database.file")
            OutlinedTextField(
                value = draft.database.file, onValueChange = vm::setSqliteFile,
                label = { Text(tr("Veritabanı dosyası")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                isError = fileError != null,
                supportingText = { Text(fileError ?: tr("Dosya adı değişirse yeni bir veritabanı açılır.")) },
            )
        }

        ChoiceCard(
            selected = engine == DbEngine.POSTGRES,
            title = "PostgreSQL",
            description = tr("Ayrı süreç olarak çalışan tam veritabanı; eşzamanlı yazma ve büyük veri için. " +
                "Yalnızca 127.0.0.1'i dinler; dışarıdan erişim her zaman HTTP API üzerinden."),
            onClick = vm::selectPostgres,
        )
        if (engine == DbEngine.POSTGRES) PostgresSettings(g, vm, draft, postgresInstalled, onInstalled)

        if (vm.databaseChanged) MoveDataChoice(vm, moveData)

        BackupSettings(vm, draft)
    }
}

/** Zamanlanmış yedek: açık/kapalı, ne zaman (hazır seçenekler veya cron), kaç yedek tutulacağı. */
@Composable
private fun BackupSettings(vm: WizardViewModel, draft: ServerConfig) {
    val b = draft.backup
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(tr("Zamanlanmış yedek"), style = MaterialTheme.typography.bodyLarge)
            Text(
                tr("Veritabanı, yüklenen dosyalar, script'ler ve site düzenli olarak tek bir zip'e yedeklenir (Sunucu → Yedekler; " +
                    "oradan yedeklerin kopyalanacağı bir klasör de seçebilirsiniz)."),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(checked = b.enabled, onCheckedChange = { on -> vm.updateBackup { it.copy(enabled = on) } })
    }
    if (b.enabled) BackupScheduleSettings(vm, b)
    RemoteBackupSection(vm, draft)
    HorizontalDivider()
    ReplicationSection(vm, draft)
    FieldKeySection(vm, draft)
}

@Composable
private fun BackupScheduleSettings(vm: WizardViewModel, b: com.plyg.droidserver.core.config.BackupConfig) {
    BACKUP_PRESETS.forEach { (title, cron) ->
        RadioRow(selected = b.schedule == cron, title = title) { vm.updateBackup { it.copy(schedule = cron) } }
    }
    var cronText by rememberSaveable { mutableStateOf(b.schedule) }
    OutlinedTextField(
        value = if (BACKUP_PRESETS.any { it.second == b.schedule }) b.schedule else cronText,
        onValueChange = { v -> cronText = v; vm.updateBackup { it.copy(schedule = v.trim()) } },
        label = { Text(tr("Zaman (cron: dakika saat gün ay haftanın-günü)")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        isError = vm.errorAt("backup.schedule") != null,
        supportingText = { Text(vm.errorAt("backup.schedule") ?: tr("Telefonun saatiyle; örn. 30 2 * * * her gece 02:30")) },
    )
    var keepText by rememberSaveable { mutableStateOf(b.keep.toString()) }
    OutlinedTextField(
        value = keepText,
        onValueChange = { v -> keepText = v.filter(Char::isDigit).take(3); keepText.toIntOrNull()?.let { k -> vm.updateBackup { it.copy(keep = k) } } },
        label = { Text(tr("Saklanacak yedek sayısı")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        isError = vm.errorAt("backup.keep") != null,
        supportingText = { Text(vm.errorAt("backup.keep") ?: tr("Eskiler silinir; elle alınan yedekler sayılmaz.")) },
    )
}

private val BACKUP_PRESETS = listOf(
    tr("Her gece 04:00") to "0 4 * * *",
    tr("6 saatte bir") to "0 */6 * * *",
    tr("Her pazar 04:00") to "0 4 * * 0",
)

/** Veritabanı değişiyor: mevcut kayıtlar taşınsın mı, taşınırken sunucu ve dolu hedef için ne yapılsın? */
@Composable
private fun MoveDataChoice(vm: WizardViewModel, moveData: Boolean?) {
    val options by vm.moveOptions.collectAsState()
    SectionCard(tr("Mevcut kayıtlar")) {
        RadioRow(
            moveData == true, tr("Yeni veritabanına taşı"),
            tr("Tablolar kimlikleriyle kopyalanır, satır sayıları karşılaştırılır. Sorun olursa eski veritabanıyla devam edilir; " +
                "eski veriler silinmez."),
        ) { vm.setMoveData(true) }
        RadioRow(moveData == false, tr("Taşıma"), tr("Yeni veritabanı olduğu gibi kullanılır; eski veriler eski veritabanında kalır.")) {
            vm.setMoveData(false)
        }
        ErrorText(vm.errorAt(MOVE_DATA_PATH))
        if (moveData == true) {
            Text(tr("Taşıma sürerken sunucu"), style = MaterialTheme.typography.labelLarge)
            RadioRow(options.duringMove == DuringMove.STOP, tr("Durur"), tr("Taşıma bitene kadar API yanıt vermez.")) {
                vm.setMoveOptions { it.copy(duringMove = DuringMove.STOP) }
            }
            RadioRow(
                options.duringMove == DuringMove.READ_ONLY, tr("Salt okunur çalışır"),
                tr("Okumalar sürer; yazma istekleri 503 alır (Retry-After), istemciler sonra yeniden dener."),
            ) { vm.setMoveOptions { it.copy(duringMove = DuringMove.READ_ONLY) } }
            Text(tr("Yeni veritabanında aynı koleksiyonda kayıt varsa"), style = MaterialTheme.typography.labelLarge)
            RadioRow(!options.replaceExisting, tr("Taşımayı durdur"), tr("Hiçbir şey değişmez; sonra karar verirsiniz.")) {
                vm.setMoveOptions { it.copy(replaceExisting = false) }
            }
            RadioRow(
                options.replaceExisting, tr("Oradaki kayıtları sil, buradakileri yaz"),
                tr("Yeni veritabanındaki o koleksiyonların kayıtları silinir (geri alınamaz)."),
            ) { vm.setMoveOptions { it.copy(replaceExisting = true) } }
        }
    }
}

@Composable
private fun PostgresSettings(
    g: AppGraph,
    vm: WizardViewModel,
    draft: ServerConfig,
    installed: Boolean,
    onInstalled: () -> Unit,
) {
    val db = draft.database
    val usesComponent = draft.components.any { it.id == PostgresDefaults.COMPONENT_ID && it.enabled }
    fun readSetupDone() = runCatching { g.components.installed()[PostgresDefaults.COMPONENT_ID]?.setupDone == true }.getOrDefault(false)
    fun readResetTask() = runCatching { g.components.task(PostgresDefaults.COMPONENT_ID, SET_PASSWORD_TASK) != null }.getOrDefault(false)
    var setupDone by remember(installed) { mutableStateOf(readSetupDone()) }
    var resetTask by remember(installed) { mutableStateOf(readResetTask()) }
    fun refresh() {
        setupDone = readSetupDone()
        resetTask = readResetTask()
    }

    if (usesComponent) {
        if (installed) {
            Text(tr("✓ PostgreSQL bileşeni kurulu; deploy sırasında başlatılır."), style = MaterialTheme.typography.bodySmall)
        } else {
            Text(
                tr("PostgreSQL bileşeni kurulu değil. Bileşen deposu adresi Bileşenler sekmesinde girilir."),
                style = MaterialTheme.typography.bodySmall,
            )
            InstallComponentButton(g, PostgresDefaults.COMPONENT_ID, tr("PostgreSQL bileşenini kur"), onInstalled)
        }
    }

    // Bağlantı ayarları: varsayılanlar bileşen içindir; kendi sunucunuzu kullanmak için değiştirilebilir.
    val urlError = vm.errorAt("database.url")
    OutlinedTextField(
        value = db.url.orEmpty(), onValueChange = { vm.setPostgresConnection(it, db.user.orEmpty()) },
        label = { Text(tr("Bağlantı adresi (JDBC)")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        isError = urlError != null, supportingText = urlError?.let { { Text(it) } },
    )
    val nameError = vm.errorAt("database.name")
    OutlinedTextField(
        value = PostgresUrl.databaseName(db.url.orEmpty()).orEmpty(), onValueChange = vm::setPostgresDatabaseName,
        label = { Text(tr("Veritabanı adı")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        isError = nameError != null,
        supportingText = { Text(nameError ?: tr("Adresin son kısmı. Aynı Postgres'te her sunucu kendi veritabanını kullanabilir.")) },
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = db.createIfMissing, onCheckedChange = vm::setCreateIfMissing)
        Text(tr("Veritabanı yoksa oluştur"), Modifier.weight(1f))
    }
    val userError = vm.errorAt("database.user")
    OutlinedTextField(
        value = db.user.orEmpty(), onValueChange = { vm.setPostgresConnection(db.url.orEmpty(), it) },
        label = { Text(tr("Kullanıcı")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        isError = userError != null, supportingText = userError?.let { { Text(it) } },
    )
    PostgresPassword(vm, usesComponent, resetAvailable = usesComponent && setupDone && resetTask)
    if (usesComponent && setupDone) {
        DataReset(g) { refresh() }
    }
}

@Composable
private fun PostgresPassword(vm: WizardViewModel, usesComponent: Boolean, resetAvailable: Boolean) {
    var entering by remember { mutableStateOf(false) }
    val method by vm.passwordMethod.collectAsState()
    SecretRow(
        label = tr("Parola"),
        value = vm.postgresPasswordValue(),
        error = vm.errorAt("database.password"),
        onRegenerate = vm::regeneratePostgresPassword,
    )
    TextButton(onClick = { entering = true }) { Text(tr("Kendi parolamı gir")) }
    if (usesComponent) {
        Text(
            tr("Bileşen ilk kurulurken bu parolayla oluşturulur; şifreli depoda saklanır."),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (vm.postgresPasswordChanged) {
        Text(tr("Parola değişti. Kaydederken:"), style = MaterialTheme.typography.labelLarge)
        RadioRow(
            method == PasswordMethod.CHANGE, tr("Postgres'teki parolayı değiştir"),
            tr("Eski parolayla bağlanılıp yenisine çevrilir; sunucu çalışmaya devam eder."),
        ) { vm.setPasswordMethod(PasswordMethod.CHANGE) }
        RadioRow(
            method == PasswordMethod.RESET, tr("Parolayı sıfırla (eski parola geçmiyorsa)"),
            if (resetAvailable) tr("Postgres kısa süre durur, parola tek kullanıcı modunda değiştirilir.") else tr("Yalnızca kurulu PostgreSQL bileşeniyle."),
            enabled = resetAvailable,
        ) { vm.setPasswordMethod(PasswordMethod.RESET) }
    }
    if (entering) OwnPasswordDialog(vm) { entering = false }
}

@Composable
private fun OwnPasswordDialog(vm: WizardViewModel, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Kendi parolanız")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it; error = null },
                    label = { Text(tr("Parola")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = error != null, supportingText = error?.let { { Text(it) } },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = visible, onCheckedChange = { visible = it })
                    Text(tr("Göster"))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { error = vm.setPostgresPassword(text); if (error == null) onDismiss() }) { Text(tr("Kullan")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Vazgeç")) } },
    )
}

/** PostgreSQL bileşeninin verisini siler (geri alınamaz); sonraki başlatmada boş veritabanı kurulur. */
@Composable
private fun DataReset(g: AppGraph, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    TextButton(onClick = { confirming = true }, enabled = !busy) {
        Text(if (busy) tr("Siliniyor…") else tr("PostgreSQL verisini sil…"), color = MaterialTheme.colorScheme.error)
    }
    ErrorText(error)
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(tr("PostgreSQL verisi silinsin mi?")) },
            text = {
                Text(
                    tr("Tüm Postgres veritabanları, tabloları ve kayıtları kalıcı olarak silinir. Sonraki başlatmada kaydedilmiş " +
                        "konfigürasyondaki parolayla boş bir veritabanı kurulur. Geri alınamaz."),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    scope.launch {
                        busy = true
                        error = withContext(Dispatchers.IO) {
                            runCatching { g.deployLock.withLock { g.orchestrator.resetComponentData(PostgresDefaults.COMPONENT_ID) } }
                                .exceptionOrNull()?.let { (it as? DeployException)?.errors?.joinToString() ?: it.message }
                        }
                        busy = false
                        if (error == null) ServerService.deploy(ctx)
                        onDone()
                    }
                }) { Text(tr("Sil"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(tr("Vazgeç")) } },
        )
    }
}
