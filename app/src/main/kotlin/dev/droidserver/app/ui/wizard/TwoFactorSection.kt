package dev.droidserver.app.ui.wizard

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.core.net.toUri
import dev.droidserver.app.ui.common.ErrorText
import dev.droidserver.app.ui.common.copySecret
import dev.droidserver.core.auth.Totp
import dev.droidserver.core.config.FieldConfig
import dev.droidserver.core.config.FieldType
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.i18n.tr

/** Kullanıcılar adımı: iki adımlı doğrulama, e-postayla ve SMS ile kodla giriş. */
@Composable
internal fun TwoFactorSection(vm: WizardViewModel, draft: ServerConfig) {
    val users = draft.auth.users
    Text(tr("Giriş güvenliği"), style = MaterialTheme.typography.titleMedium)
    SwitchRow(
        tr("İki adımlı doğrulama"),
        tr("Kullanıcılar hesaplarına doğrulayıcı uygulama (Google Authenticator, Aegis…) ekleyebilir; girişte parolaya ek olarak " +
            "6 haneli kod istenir. Telefonunu kaybeden için 10 yedek kod verilir; siz de Kullanıcılar ekranından sıfırlayabilirsiniz."),
        users.twoFactor.enabled,
    ) { on -> vm.updateUsersSettings { it.copy(twoFactor = it.twoFactor.copy(enabled = on, required = it.twoFactor.required && on)) } }
    if (users.twoFactor.enabled) {
        SwitchRow(
            tr("Herkes için zorunlu"),
            tr("Kurmamış kullanıcı giriş yapar ama kurana kadar yalnızca hesap işlemlerini yapabilir; kurulduktan sonra kapatamaz."),
            users.twoFactor.required,
        ) { on -> vm.updateUsersSettings { it.copy(twoFactor = it.twoFactor.copy(required = on)) } }
        ErrorText(vm.errorAt("auth.users.twoFactor.required"))
    }
    SwitchRow(
        tr("E-postayla kodla giriş"),
        tr("Parolasız giriş: kullanıcı e-postasına gelen 8 haneli kodla girer (10 dakika, tek kullanımlık). E-posta ayarı gerekir."),
        users.emailLogin,
    ) { on -> vm.updateUsersSettings { it.copy(emailLogin = on) } }
    ErrorText(vm.errorAt("auth.users.emailLogin"))
    if (users.emailLogin) {
        var url by remember { mutableStateOf(users.emailLoginUrl.orEmpty()) }
        OutlinedTextField(
            value = url,
            onValueChange = { v -> url = v; vm.updateUsersSettings { it.copy(emailLoginUrl = v.trim().ifEmpty { null }) } },
            label = { Text(tr("Giriş sayfanız (isteğe bağlı)")) },
            placeholder = { Text("https://uygulamam.com/giris") },
            supportingText = { Text(vm.errorAt("auth.users.emailLoginUrl") ?: tr("Verilirse e-postaya ?email=…&code=… eklenmiş bağlantı da konur.")) },
            isError = vm.errorAt("auth.users.emailLoginUrl") != null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    SmsLoginSection(vm, draft)
}

/** SMS ile kodla giriş: telefon alanı ve Telefon özellikleri → SMS. */
@Composable
private fun SmsLoginSection(vm: WizardViewModel, draft: ServerConfig) {
    val users = draft.auth.users
    SwitchRow(
        tr("SMS ile giriş"),
        tr("Parolasız giriş: kullanıcının telefonuna 6 haneli kod gider (10 dakika, tek kullanımlık). SMS bu telefonun kendi SIM'inden gönderilir; operatör ücreti size yansır."),
        users.smsLogin,
    ) { on ->
        vm.update { c ->
            val u = c.auth.users
            // Açılınca telefon alanı yoksa eklenir (metin, benzersiz) ve SMS gönderme açılır.
            val fields = if (on && u.extraFields.none { it.name == u.phoneField }) u.extraFields + FieldConfig(u.phoneField, FieldType.TEXT, unique = true) else u.extraFields
            c.copy(
                auth = c.auth.copy(users = u.copy(smsLogin = on, extraFields = fields)),
                phone = if (on) c.phone.copy(sms = true) else c.phone,
            )
        }
    }
    ErrorText(vm.errorAt("auth.users.smsLogin") ?: vm.errorAt("auth.users.phoneField"))
    if (users.smsLogin) {
        Text(
            tr("Numaralar \"{0}\" alanında uluslararası biçimde (+905551234567) saklanır. Günlük SMS sınırı ve izinli ülke kodları Genel → Telefon özellikleri'nde; ayrıca numara başına dakikada bir, günde en çok 5 kod gönderilir.", users.phoneField),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Güvenlik adımı: web paneli girişinde eşleştirme koduna ek olarak doğrulayıcı uygulama kodu. */
@Composable
internal fun PanelTotpSection(vm: WizardViewModel, draft: ServerConfig) {
    val ctx = LocalContext.current
    val ref = draft.adminPanel.totpSecret
    SwitchRow(
        tr("Panelde doğrulayıcı kodu da iste"),
        tr("Panele girişte telefondaki eşleştirme koduna ek olarak doğrulayıcı uygulamadaki 6 haneli kod istenir (kod başka bir " +
            "cihazdaki uygulamada olabilir)."),
        ref != null,
    ) { vm.setPanelTotp(it) }
    ErrorText(vm.errorAt("adminPanel.totpSecret"))
    val secret = ref?.let(vm::secretValue) ?: return
    val uri = Totp.uri(secret, "panel", draft.name.ifBlank { "DroidServer" })
    Text(tr("Anahtar: {0}", secret.chunked(4).joinToString(" ")), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    Row {
        TextButton(onClick = {
            try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, uri.toUri()))
            } catch (_: ActivityNotFoundException) {
                copySecret(ctx, tr("doğrulayıcı anahtarı"), secret)
            }
        }) { Text(tr("Doğrulayıcı uygulamaya ekle")) }
        TextButton(onClick = { copySecret(ctx, tr("doğrulayıcı anahtarı"), secret) }) { Text(tr("Anahtarı kopyala")) }
    }
    Text(
        tr("Uygulama bu telefonda yoksa anahtarı başka cihazdaki doğrulayıcıya elle girin (zaman tabanlı, 6 hane, 30 sn). " +
            "Kaydedip sunucuyu güncelleyince geçerli olur."),
        style = MaterialTheme.typography.bodySmall,
    )
}

/**
 * Güvenlik adımı: passkey ile parolasız giriş (kullanıcı hesapları ve web paneli). WebAuthn bir alan adına bağlanır:
 * DuckDNS adı veya adlandırılmış tünel önerilir; hızlı tünelin adresi her başlatmada değiştiği için uygun değil.
 */
@Composable
internal fun PasskeySection(vm: WizardViewModel, draft: ServerConfig) {
    val passkeys = draft.auth.passkeys
    val suggested = draft.access.direct.ddns?.domain?.takeIf { it.isNotBlank() }?.let { "$it.duckdns.org" }
        ?: draft.access.cloudflare.hostname?.takeIf { it.isNotBlank() }
    SwitchRow(
        tr("Passkey ile giriş"),
        tr("Parola yerine telefonun veya bilgisayarın kilidiyle (parmak izi, yüz, PIN) giriş: kullanıcılar hesaplarına, siz web paneline passkey eklersiniz. " +
            "HTTPS ve gerçek bir alan adı gerekir (DuckDNS veya adlandırılmış tünel); yerel IP adresiyle çalışmaz."),
        passkeys.enabled,
    ) { on ->
        vm.update { c -> c.copy(auth = c.auth.copy(passkeys = c.auth.passkeys.copy(enabled = on, rpId = c.auth.passkeys.rpId.ifBlank { suggested.orEmpty() }))) }
    }
    ErrorText(vm.errorAt("auth.passkeys.enabled"))
    if (passkeys.enabled) {
        var rpId by remember { mutableStateOf(passkeys.rpId) }
        OutlinedTextField(
            value = rpId,
            onValueChange = { v -> rpId = v; vm.update { c -> c.copy(auth = c.auth.copy(passkeys = c.auth.passkeys.copy(rpId = v.trim().lowercase()))) } },
            label = { Text(tr("Alan adı")) },
            placeholder = { Text(suggested ?: "ev.duckdns.org") },
            supportingText = {
                Text(vm.errorAt("auth.passkeys.rpId") ?: tr("Sunucuyu tarayıcıda açtığınız ad, https:// ve port olmadan. Ad değişirse eklenen passkey'ler çalışmaz."))
            },
            isError = vm.errorAt("auth.passkeys.rpId") != null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (suggested == null) {
            Text(
                tr("Henüz alan adınız yok: Erişim adımında DuckDNS (doğrudan erişim) veya adlandırılmış Cloudflare tüneli kurun."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
