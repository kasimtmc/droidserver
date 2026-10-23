package dev.droidserver.app.ui.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import dev.droidserver.app.data.NetUtil
import dev.droidserver.app.ui.common.ErrorText
import dev.droidserver.app.ui.common.ScreenScaffold
import dev.droidserver.app.ui.common.SectionCard
import dev.droidserver.app.ui.common.TipCard
import dev.droidserver.app.ui.common.copySecret
import dev.droidserver.app.ui.common.copyToClipboard
import dev.droidserver.app.ui.common.saveConfigChange
import dev.droidserver.app.ui.wizard.WizardViewModel
import dev.droidserver.core.config.ConfigSecrets
import dev.droidserver.core.config.WebDavConfig
import dev.droidserver.core.i18n.tr
import dev.droidserver.core.server.EngineState
import kotlinx.coroutines.launch
import java.security.SecureRandom

/** Ağ sürücüsü (WebDAV): aç/kapat, adres ve kimlik, paylaşılan klasörler, bilgisayardan nasıl bağlanılır. */
@Composable
fun DriveScreen(g: AppGraph, wizard: WizardViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val active by wizard.active.collectAsState()
    val state by g.engine.state.collectAsState()
    val initial = active?.webdav ?: WebDavConfig()

    var enabled by remember(initial) { mutableStateOf(initial.enabled) }
    var path by remember(initial) { mutableStateOf(initial.path) }
    var username by remember(initial) { mutableStateOf(initial.username) }
    var password by remember { mutableStateOf("") }
    var readOnly by remember(initial) { mutableStateOf(initial.readOnly) }
    var site by remember(initial) { mutableStateOf(initial.site) }
    var scripts by remember(initial) { mutableStateOf(initial.scripts) }
    var uploads by remember(initial) { mutableStateOf(initial.uploads) }
    var maxMb by remember(initial) { mutableStateOf(initial.maxFileMb.toString()) }
    var errors by remember { mutableStateOf<List<String>>(emptyList()) }
    var copied by remember { mutableStateOf<String?>(null) }

    val running = state as? EngineState.Running
    val address = running?.let { "http://${NetUtil.localIpv4().firstOrNull() ?: "127.0.0.1"}:${it.port}${initial.path}" }
    fun savedPassword(): String? = initial.password.takeIf { it.isNotEmpty() }?.let { if (ConfigSecrets.isRef(it)) g.secrets.get(it.removePrefix(ConfigSecrets.PREFIX)) else it }

    fun save() = scope.launch {
        // İlk açılışta parola yoksa güçlü bir parola üretilir; kaydedilirken şifreli depoya taşınır.
        val pw = password.ifEmpty { initial.password.ifEmpty { if (enabled) strongPassword() else "" } }
        errors = saveConfigChange(ctx, g, wizard, deploy = true, scope = listOf("webdav")) { c ->
            c.copy(
                webdav = WebDavConfig(
                    enabled = enabled, path = path.trim(), username = username.trim(), password = pw, readOnly = readOnly,
                    site = site, scripts = scripts, uploads = uploads, maxFileMb = maxMb.toIntOrNull() ?: 0,
                ),
            )
        }
        if (errors.isEmpty()) password = ""
    }

    ScreenScaffold(tr("Ağ sürücüsü"), subtitle = if (initial.enabled) tr("Açık") else tr("Kapalı"), onBack = onBack) { modifier ->
        Column(modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TipCard(
                g, "drive-intro",
                tr("Telefondaki klasörleri bilgisayarda sürücü gibi açın: dosyaları sürükleyip bırakın, doğrudan düzenleyin. Windows, macOS, Linux ve dosya yöneticisi uygulamalarıyla çalışır (WebDAV)."),
            )
            SectionCard(tr("Sürücü")) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text(tr("Ağ sürücüsü açık"), Modifier.weight(1f)); Switch(enabled, { enabled = it }) }
                if (initial.enabled && address != null) {
                    Text(address, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { copyToClipboard(ctx, tr("adres"), address); copied = address }) { Text(tr("Adresi kopyala")) }
                        savedPassword()?.let { pw -> TextButton(onClick = { copySecret(ctx, tr("WebDAV parolası"), pw) }) { Text(tr("Parolayı kopyala")) } }
                    }
                    copied?.let { Text(tr("Kopyalandı: {0}", it), style = MaterialTheme.typography.bodySmall) }
                }
                OutlinedTextField(username, { username = it.trim() }, singleLine = true, label = { Text(tr("Kullanıcı adı")) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    password, { password = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(), label = { Text(tr("Parola")) },
                    supportingText = {
                        Text(if (initial.password.isNotEmpty() && password.isEmpty()) tr("Kayıtlı parola korunuyor; değiştirmek için yenisini yazın.") else tr("Boş bırakılırsa güçlü bir parola üretilir. Şifreli depoda saklanır."))
                    },
                )
                TextButton(onClick = { password = strongPassword() }) { Text(tr("Parola üret")) }
                OutlinedTextField(path, { path = it.trim() }, singleLine = true, label = { Text(tr("Yol")) }, modifier = Modifier.fillMaxWidth())
            }
            SectionCard(tr("Paylaşılan klasörler")) {
                Text(tr("drive: telefondaki sürücü klasörü her zaman paylaşılır."), style = MaterialTheme.typography.bodyMedium)
                ShareRow(tr("Sürücü yalnızca okunur"), tr("Bilgisayardan dosya eklenemez ve silinemez"), readOnly) { readOnly = it }
                ShareRow(tr("site: statik site dosyaları"), tr("Düzenlenebilir; değişiklik hemen yayında"), site) { site = it }
                ShareRow(tr("scripts: script dosyaları"), tr("Düzenlenebilir; deploy edince geçerli olur"), scripts) { scripts = it }
                ShareRow(tr("uploads: kayıtlara yüklenen dosyalar"), tr("Her zaman yalnızca okunur"), uploads) { uploads = it }
                OutlinedTextField(
                    maxMb, { maxMb = it.filter(Char::isDigit) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), label = { Text(tr("Tek dosya en çok (MB)")) },
                )
                Text(tr("drive klasörü yedeğe girmez."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SectionCard(tr("Bilgisayardan bağlanma")) {
                val a = address ?: "http://<telefon>:8080${initial.path}"
                Text(tr("Windows: Bu bilgisayar → Ağ konumu ekle → {0}", a), style = MaterialTheme.typography.bodySmall)
                Text(
                    tr("Windows adresi kabul etmezse: {0}. WebClient hizmeti çalışıyor olmalı; Windows tek dosyayı varsayılan olarak 50 MB ile sınırlar.",
                        "\\\\" + a.removePrefix("http://").replaceFirst(":", "@").replace("/", "\\")),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(tr("macOS: Finder → Git → Sunucuya Bağlan → {0}", a), style = MaterialTheme.typography.bodySmall)
                Text(tr("Linux (GNOME): Dosyalar → Diğer Konumlar → {0}", a.replaceFirst("http://", "dav://")), style = MaterialTheme.typography.bodySmall)
                Text(
                    tr("Linux (KDE/Dolphin): {0}; ya da Ağ klasörü ekle → WebDAV, Sunucu: yalnızca IP, Kapı: {1}, Klasör: {2}, şifreleme kapalı.",
                        a.replaceFirst("http://", "webdav://"), a.substringAfterLast(":").substringBefore("/"), initial.path),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(tr("Android/iOS: Solid Explorer, CX Dosya Gezgini, Dosyalar uygulaması (WebDAV sunucusu ekle)."), style = MaterialTheme.typography.bodySmall)
                Text(
                    tr("Parola ağda açık gitmez (Digest). İnternetten bağlanacaksanız HTTPS adresini (doğrudan erişim veya tünel) kullanın."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            errors.forEach { ErrorText(it) }
            Button(onClick = { save() }, modifier = Modifier.fillMaxWidth()) { Text(tr("Kaydet ve deploy et")) }
        }
    }
}

@Composable
private fun ShareRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}

internal fun strongPassword(): String {
    val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
    val r = SecureRandom()
    return (1..16).map { alphabet[r.nextInt(alphabet.length)] }.joinToString("")
}
