package dev.droidserver.app.ui.wizard

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.droidserver.app.ui.common.ChoiceCard
import dev.droidserver.app.ui.common.ErrorText
import dev.droidserver.app.ui.common.WarningText
import dev.droidserver.app.ui.common.copySecret
import dev.droidserver.app.ui.common.mask
import dev.droidserver.core.config.AccessMode
import dev.droidserver.core.config.AuthMode
import dev.droidserver.core.config.ConfigDefaults
import dev.droidserver.core.config.ConfigSummary
import dev.droidserver.core.config.CorsMode
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.config.accessToken
import dev.droidserver.core.i18n.tr

@Composable
internal fun SecurityStep(vm: WizardViewModel, draft: ServerConfig) {
    val auth = draft.auth
    val active by vm.active.collectAsState()
    StepColumn {
        Text(tr("Kimlik doğrulama"), style = MaterialTheme.typography.titleMedium)
        ChoiceCard(
            auth.mode == AuthMode.NONE, tr("Yok"),
            tr("Herkes okuyup yazabilir. Yalnızca güvendiğiniz bir ağda, deneme amaçlı."),
            onClick = { vm.setAuthMode(AuthMode.NONE) },
        )
        ChoiceCard(
            auth.mode == AuthMode.API_KEY, tr("API anahtarı"),
            tr("Tek bir uygulama, betik veya ev otomasyonu için en basit yol. İstekte: Authorization: Bearer <anahtar>"),
            onClick = { vm.setAuthMode(AuthMode.API_KEY) },
        )
        ChoiceCard(
            auth.mode == AuthMode.JWT, "JWT",
            tr("Birden çok istemci ve süresi dolan token'lar için. Token'ları bu uygulama imzalar (Durum > İstemci erişimi)."),
            onClick = { vm.setAuthMode(AuthMode.JWT) },
        )
        ErrorText(vm.errorAt("auth.mode"))
        if (auth.users.enabled) {
            Text(
                tr("Kullanıcı hesapları açık: buradaki anahtar/sır yönetici erişimidir (tüm kuralları aşar). Uygulamanızın " +
                    "kullanıcıları e-posta ve parolayla giriş yapıp kendi token'larını alır."),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        when (auth.mode) {
            AuthMode.API_KEY -> {
                auth.apiKeys.forEachIndexed { i, key ->
                    val value = vm.secretValue(key)
                    SecretRow(
                        label = tr("Anahtar {0}", i + 1),
                        value = value,
                        error = vm.errorAt("auth.apiKeys[$i]"),
                        onRegenerate = { vm.regenerateApiKey(i) },
                        onRemove = if (auth.apiKeys.size > 1) ({ vm.removeApiKey(i) }) else null,
                        warning = PLACEHOLDER_WARNING.takeIf { value == ConfigDefaults.PLACEHOLDER_API_KEY },
                    )
                }
                ErrorText(vm.errorAt("auth.apiKeys"))
                TextButton(onClick = vm::addApiKey) { Text(tr("Anahtar ekle")) }
            }
            AuthMode.JWT -> SecretRow(
                label = tr("İmza sırrı (HS256)"),
                value = auth.jwtSecret?.let(vm::secretValue),
                error = vm.errorAt("auth.jwtSecret"),
                onRegenerate = vm::regenerateJwtSecret,
            )
            AuthMode.NONE -> ConfigSummary.securityWarnings(draft).forEach { WarningText(it) }
        }
        if (active?.let { it.auth != auth } == true) {
            Text(
                tr("Değişiklik deploy edilince eski anahtar/sır geçersiz olur; istemcileri güncelleyin."),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (auth.mode != AuthMode.NONE) {
            HorizontalDivider()
            KeysSection(vm, draft)
        }

        HorizontalDivider()
        FirewallSection(vm, draft)

        HorizontalDivider()
        ScriptsSection(vm, draft)

        HorizontalDivider()
        WebhooksSection(vm, draft)

        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("Web yönetim paneli"), style = MaterialTheme.typography.titleMedium)
                Text(
                    tr("Bilgisayarın tarayıcısından /_admin adresiyle kayıtları, kullanıcıları, metrikleri, logları, yedekleri ve " +
                        "konfigürasyonu yönetin. Giriş, telefonda (Durum ekranı) gösterilen tek kullanımlık kodla."),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = draft.adminPanel.enabled, onCheckedChange = { on -> vm.updateAdminPanel { it.copy(enabled = on) } })
        }
        if (draft.adminPanel.enabled) PanelTotpSection(vm, draft)

        HorizontalDivider()
        Text(tr("CORS (tarayıcıdan erişim)"), style = MaterialTheme.typography.titleMedium)
        Text(
            tr("Bir web sayfasındaki JavaScript bu sunucuya istek atacaksa açın; mobil uygulama ve curl için gerekmez."),
            style = MaterialTheme.typography.bodySmall,
        )
        val cors = draft.server.cors
        val corsMode = CorsMode.of(cors)
        val listedHosts = cors.allowedHosts.filter { it != "*" }
        CorsMode.entries.forEach { m ->
            Row(
                Modifier.fillMaxWidth().selectable(selected = corsMode == m, role = Role.RadioButton) { vm.setCors(m, listedHosts) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = corsMode == m, onClick = null)
                Text(m.title, Modifier.padding(start = 8.dp))
            }
        }
        if (corsMode == CorsMode.LIST) {
            var hostsText by rememberSaveable { mutableStateOf(listedHosts.joinToString(", ")) }
            val hostsError = vm.errorAt("server.cors.allowedHosts")
            OutlinedTextField(
                value = hostsText,
                onValueChange = { v -> hostsText = v; vm.setCors(CorsMode.LIST, v.split(',', '\n', ' ')) },
                label = { Text(tr("İzinli hostlar (virgülle)")) }, modifier = Modifier.fillMaxWidth(),
                isError = hostsError != null,
                supportingText = { Text(hostsError ?: tr("Örn. example.com, app.example.com")) },
            )
        }
    }
}

/** Script'ler: açık/kapalı, süre sınırı ve `ctx.http`'nin istek atabileceği adresler. */
@Composable
private fun ScriptsSection(vm: WizardViewModel, draft: ServerConfig) {
    val sc = draft.scripts
    Text(tr("Script'ler (JavaScript)"), style = MaterialTheme.typography.titleMedium)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            tr("Kendi uçlarınız, kayıt eklenirken/değişirken çalışan kurallar ve zamanlanmış görevler. Script'ler Sunucu → " +
                "Script'ler ekranında yazılır, deploy'da denetlenir."),
            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
        )
        Switch(checked = sc.enabled, onCheckedChange = { on -> vm.updateScripts { it.copy(enabled = on) } })
    }
    if (!sc.enabled) return
    var timeoutText by rememberSaveable { mutableStateOf(sc.timeoutMs.toString()) }
    OutlinedTextField(
        value = timeoutText,
        onValueChange = { v -> timeoutText = v.filter(Char::isDigit).take(5); timeoutText.toIntOrNull()?.let { t -> vm.updateScripts { it.copy(timeoutMs = t) } } },
        label = { Text(tr("Süre sınırı (ms)")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        isError = vm.errorAt("scripts.timeoutMs") != null,
        supportingText = { Text(vm.errorAt("scripts.timeoutMs") ?: tr("Bir uç veya olay bu süreyi aşarsa durdurulur (sonsuz döngüye karşı).")) },
    )
    var hostsText by rememberSaveable { mutableStateOf(sc.allowedHosts.joinToString(", ")) }
    val hostErrors = sc.allowedHosts.indices.mapNotNull { vm.errorAt("scripts.allowedHosts[$it]") }
    OutlinedTextField(
        value = hostsText,
        onValueChange = { v ->
            hostsText = v
            vm.updateScripts { it.copy(allowedHosts = v.split(',', ' ', '\n').map(String::trim).filter(String::isNotEmpty)) }
        },
        label = { Text(tr("İstek atılabilecek adresler (ctx.http)")) }, modifier = Modifier.fillMaxWidth(),
        isError = hostErrors.isNotEmpty(),
        supportingText = { Text(hostErrors.firstOrNull() ?: tr("Virgülle: api.example.com, *.example.com. Boşsa script'ler dışarıya istek atamaz.")) },
    )
    NumberField(
        tr("Aynı anda çalışan arka plan işi"), sc.jobs.concurrency, vm.errorAt("scripts.jobs.concurrency"),
        tr("jobAdd ile tanımlanan işler; telefonu yormamak için 1-4 önerilir."),
    ) { n -> vm.updateScripts { it.copy(jobs = it.jobs.copy(concurrency = n)) } }
    NumberField(tr("Biten işleri sakla (gün)"), sc.jobs.keepDays, vm.errorAt("scripts.jobs.keepDays"), tr("Sonra kuyruktan silinir.")) { n ->
        vm.updateScripts { it.copy(jobs = it.jobs.copy(keepDays = n)) }
    }
    Text(
        tr("Script'ler Java sınıflarına ve dosyalara erişemez; kayıtlara yönetici yetkisiyle ctx.db üzerinden erişir."),
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
internal fun SecretRow(
    label: String,
    value: String?,
    error: String?,
    onRegenerate: () -> Unit,
    onRemove: (() -> Unit)? = null,
    warning: String? = null,
) {
    val ctx = LocalContext.current
    var visible by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(
                when {
                    value == null -> tr("(depoda bulunamadı)")
                    visible -> value
                    else -> mask(value)
                },
                fontFamily = FontFamily.Monospace,
            )
            Row {
                TextButton(onClick = { visible = !visible }, enabled = value != null) { Text(if (visible) tr("Gizle") else tr("Göster")) }
                TextButton(onClick = { value?.let { copySecret(ctx, label, it); copied = true } }, enabled = value != null) {
                    Text(if (copied) tr("Kopyalandı") else tr("Kopyala"))
                }
                TextButton(onClick = { onRegenerate(); copied = false }) { Text(tr("Yeniden üret")) }
                onRemove?.let { TextButton(onClick = it) { Text(tr("Sil")) } }
            }
            warning?.let { WarningText(it) }
            ErrorText(error)
        }
    }
}
