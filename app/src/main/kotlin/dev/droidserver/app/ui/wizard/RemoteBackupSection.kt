package dev.droidserver.app.ui.wizard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import dev.droidserver.app.ui.common.ErrorText
import dev.droidserver.app.ui.common.RadioRow
import dev.droidserver.app.ui.common.WarningText
import dev.droidserver.app.ui.common.copySecret
import dev.droidserver.core.backup.RemoteBackups
import dev.droidserver.core.config.RemoteKind
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Bulut yedeği: WebDAV veya S3 uyumlu depoya şifreli yükleme. */
@Composable
internal fun RemoteBackupSection(vm: WizardViewModel, draft: ServerConfig) {
    val r = draft.backup.remote
    SwitchRow(
        tr("Bulut yedeği"),
        tr("Yedekler telefonda parolayla şifrelenip (AES-256) Nextcloud gibi bir WebDAV sunucusuna veya Backblaze B2, " +
            "Cloudflare R2, MinIO gibi S3 uyumlu bir depoya yüklenir; depo içeriği göremez. Telefon kaybolsa da başka " +
            "telefona geri yüklenebilir (Sunucu → Yedekler)."),
        r != null,
    ) { vm.setRemoteBackup(it) }
    if (r == null) return
    val path = "backup.remote"
    RemoteKind.entries.forEach { k -> RadioRow(selected = r.kind == k, title = k.title) { vm.updateRemoteBackup { it.copy(kind = k) } } }
    val s3 = r.kind == RemoteKind.S3
    OutlinedTextField(
        value = r.url, onValueChange = { v -> vm.updateRemoteBackup { it.copy(url = v.trim()) } },
        label = { Text(if (s3) tr("Uç adresi") else tr("Yedek klasörünün adresi")) },
        placeholder = { Text(if (s3) "https://s3.eu-central-003.backblazeb2.com" else "https://bulut.ornek.com/remote.php/dav/files/ali/DroidServer") },
        supportingText = { Text(vm.errorAt("$path.url") ?: if (s3) tr("Sağlayıcının S3 uç adresi") else tr("Nextcloud: Dosyalar → Ayarlar → WebDAV adresi + klasör adı")) },
        isError = vm.errorAt("$path.url") != null, singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    if (s3) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = r.bucket.orEmpty(), onValueChange = { v -> vm.updateRemoteBackup { it.copy(bucket = v.trim().ifEmpty { null }) } },
                label = { Text(tr("Kova")) }, isError = vm.errorAt("$path.bucket") != null, singleLine = true, modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = r.region.orEmpty(), onValueChange = { v -> vm.updateRemoteBackup { it.copy(region = v.trim().ifEmpty { null }) } },
                label = { Text(tr("Bölge")) }, placeholder = { Text("us-east-1") }, singleLine = true, modifier = Modifier.weight(1f),
            )
        }
        ErrorText(vm.errorAt("$path.bucket"))
        OutlinedTextField(
            value = r.prefix, onValueChange = { v -> vm.updateRemoteBackup { it.copy(prefix = v.trim()) } },
            label = { Text(tr("Önek (klasör)")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
    }
    OutlinedTextField(
        value = r.username, onValueChange = { v -> vm.updateRemoteBackup { it.copy(username = v.trim()) } },
        label = { Text(if (s3) tr("Erişim anahtarı kimliği (key ID)") else tr("Kullanıcı adı")) },
        isError = vm.errorAt("$path.username") != null, supportingText = vm.errorAt("$path.username")?.let { { Text(it) } },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    // Parola ekran durumuna yazılmasın diye rememberSaveable kullanılmaz.
    var password by remember { mutableStateOf("") }
    OutlinedTextField(
        value = password, onValueChange = { password = it },
        label = { Text((if (s3) tr("Gizli anahtar") else tr("Parola (uygulama parolası)")) + if (r.password.isNotBlank()) tr(" — kayıtlı; değiştirmek için yazın") else "") },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        isError = vm.errorAt("$path.password") != null,
        supportingText = { Text(vm.errorAt("$path.password") ?: tr("Şifreli depoda saklanır, konfigürasyon dosyasına yazılmaz.")) },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    if (password.isNotBlank()) TextButton(onClick = { vm.setRemotePassword(password); password = "" }) { Text(tr("Kaydet")) }

    PassphraseEditor(vm, r.passphrase)

    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = r.uploadManual, onCheckedChange = { v -> vm.updateRemoteBackup { it.copy(uploadManual = v) } })
        Text(tr("Elle alınan yedekleri de yükle (zamanlanmışlar her zaman)"))
    }
    var keepText by remember { mutableStateOf(r.keep.toString()) }
    OutlinedTextField(
        value = keepText,
        onValueChange = { v -> keepText = v.filter(Char::isDigit).take(4); keepText.toIntOrNull()?.let { k -> vm.updateRemoteBackup { it.copy(keep = k) } } },
        label = { Text(tr("Depoda tutulacak yedek sayısı")) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        isError = vm.errorAt("$path.keep") != null, supportingText = vm.errorAt("$path.keep")?.let { { Text(it) } },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
    )

    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    TextButton(enabled = !testing, onClick = {
        val cfg = vm.resolvedRemoteBackup() ?: return@TextButton
        testing = true
        result = null
        scope.launch {
            result = withContext(Dispatchers.IO) { runCatching { RemoteBackups(cfg).test() } }
                .fold({ true to it }, { false to (it.message ?: it.toString()) })
            testing = false
        }
    }) { Text(if (testing) tr("Deneniyor…") else tr("Bağlantıyı dene")) }
    result?.let { (ok, text) -> if (ok) Text(text, style = MaterialTheme.typography.bodySmall) else ErrorText(text) }
}

/** Şifreleme parolası: üretilen parola gösterilir/kopyalanır veya kendi parolanız yazılır. */
@Composable
private fun PassphraseEditor(vm: WizardViewModel, ref: String) {
    val ctx = LocalContext.current
    val current = vm.secretValue(ref)
    var shown by remember { mutableStateOf(false) }
    Text(tr("Şifreleme parolası"), style = MaterialTheme.typography.bodyLarge)
    WarningText(
        tr("Bu parolayı telefon dışında güvenli bir yere (parola yöneticisi, kağıt) not edin. Başka telefona geri yüklerken " +
            "gerekir; kaybolursa buluttaki yedekler hiçbir şekilde açılamaz."),
    )
    if (current != null) {
        Text(if (shown) current else "•".repeat(current.length.coerceAtMost(29)), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
        Row {
            TextButton(onClick = { shown = !shown }) { Text(if (shown) tr("Gizle") else tr("Göster")) }
            TextButton(onClick = { copySecret(ctx, tr("şifreleme parolası"), current) }) { Text(tr("Kopyala")) }
        }
    }
    var own by remember { mutableStateOf("") }
    OutlinedTextField(
        value = own, onValueChange = { own = it },
        label = { Text(tr("Kendi parolanız (isteğe bağlı, en az 12 karakter)")) },
        supportingText = { Text(vm.errorAt("backup.remote.passphrase") ?: tr("Başka telefondaki yedeği açmak için oradaki parolayı yazın.")) },
        isError = vm.errorAt("backup.remote.passphrase") != null,
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    if (own.length >= 12) TextButton(onClick = { vm.setRemotePassphrase(own); own = ""; shown = false }) { Text(tr("Bu parolayı kullan")) }
}
