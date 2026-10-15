package dev.droidserver.app.ui.wizard

import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.Arrangement
import dev.droidserver.core.i18n.tr
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import dev.droidserver.core.config.SmtpSecurity
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.droidserver.app.ui.common.ErrorText
import dev.droidserver.core.config.AuthMode
import dev.droidserver.core.config.FieldConfig
import dev.droidserver.core.config.FieldType
import dev.droidserver.core.config.ServerConfig

/** Son kullanıcı hesapları: açık/kapalı, kayıt, ek alanlar (örn. ad) ve hangilerinin başkalarına görüneceği. */
@Composable
internal fun UsersStep(vm: WizardViewModel, draft: ServerConfig) {
    val users = draft.auth.users
    StepColumn {
        StepIntro(
            tr("Uygulamanızı kullananlar e-posta ve parolayla hesap açıp giriş yapabilir. Koleksiyonlarda kimin neyi " +
                "yapabileceğini (örn. herkes okur, yalnızca sahibi düzenler) bir sonraki adımda seçersiniz."),
        )
        SwitchRow(tr("Kullanıcı hesapları"), tr("Kayıt, giriş ve kullanıcıya özel kayıtlar."), users.enabled) { vm.setUsersEnabled(it) }
        if (!users.enabled) {
            Text(
                tr("Kapalıyken sunucuya yalnızca yönetici kimliğiyle (Güvenlik adımı) veya herkese açık bıraktığınız " +
                    "koleksiyonlara erişilir. Tek bir uygulama ya da kendi betikleriniz için bu yeterlidir."),
                style = MaterialTheme.typography.bodySmall,
            )
            HorizontalDivider()
            EmailSection(vm, draft)
            return@StepColumn
        }
        if (draft.auth.mode != AuthMode.NONE) {
            Text(
                tr("Yönetici erişimi: {0} (Güvenlik adımı). " +
                    "Yönetici kullanıcıları buradan veya Sunucu → Kullanıcılar ekranından yönetir.", if (draft.auth.mode == AuthMode.API_KEY) tr("API anahtarı") else "JWT"),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        SwitchRow(
            tr("Herkes kayıt olabilir"),
            if (users.allowSignup) tr("Kayıt ucu açık: /auth/signup") else tr("Hesapları yalnızca siz açarsınız (Sunucu → Kullanıcılar)."),
            users.allowSignup,
        ) { vm.setAllowSignup(it) }

        var lengthText by rememberSaveable { mutableStateOf(users.minPasswordLength.toString()) }
        val lengthError = vm.errorAt("auth.users.minPasswordLength")
        OutlinedTextField(
            value = lengthText,
            onValueChange = { v -> lengthText = v.filter(Char::isDigit).take(3); lengthText.toIntOrNull()?.let(vm::setMinPasswordLength) },
            label = { Text(tr("En kısa parola")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = lengthError != null,
            supportingText = { Text(lengthError ?: tr("karakter (6-128)")) },
        )

        Text(tr("Ek alanlar"), style = MaterialTheme.typography.titleMedium)
        Text(
            tr("E-posta, parola, rol ve doğrulandı bilgisi hazırdır. Ad, telefon gibi bilgiler için alan ekleyin; kayıt olurken " +
                "gönderilir. \"Herkese görünür\" alanlar başkalarına da gösterilir (örn. yazının yazar adı); diğerlerini yalnızca " +
                "kullanıcının kendisi ve yönetici görür."),
            style = MaterialTheme.typography.bodySmall,
        )
        users.extraFields.forEachIndexed { i, f -> UserFieldCard(vm, i, f, f.name in users.publicFields) }
        OutlinedButton(onClick = vm::addUserField) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(tr("Alan ekle"))
        }

        Text(tr("Uçlar"), style = MaterialTheme.typography.titleMedium)
        val base = draft.server.basePath.trimEnd('/') + "/auth"
        listOfNotNull(
            tr("POST {0}/signup", base).takeIf { users.allowSignup },
            tr("POST {0}/login  → token + refreshToken", base),
            tr("POST {0}/refresh", base),
            tr("POST {0}/logout", base),
            "GET  $base/me",
        ).forEach { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        Text(
            tr("Erişim token'ı {0} dakika, yenileme token'ı {1} gün geçerlidir; " +
                "yenileme token'ı her kullanımda değişir. Parolalar PBKDF2 ile özetlenir, düz metin saklanmaz.", users.accessTokenMinutes, users.refreshTokenDays),
            style = MaterialTheme.typography.bodySmall,
        )

        HorizontalDivider()
        RolesSection(vm, draft)
        HorizontalDivider()
        TeamsSection(vm, draft)

        HorizontalDivider()
        TwoFactorSection(vm, draft)

        HorizontalDivider()
        QuotaSection(vm, draft)

        val expert by vm.expert.collectAsState()
        if (expert) {
            HorizontalDivider()
            UsersExpertSection(vm, draft)
        }

        HorizontalDivider()
        OAuthSection(vm, draft)

        HorizontalDivider()
        PushSection(vm, draft)

        HorizontalDivider()
        EmailSection(vm, draft)
    }
}

/** SMTP ile e-posta: hesap doğrulama ve parola sıfırlama e-postaları, script'lerden ctx.mail. */
@Composable
private fun EmailSection(vm: WizardViewModel, draft: ServerConfig) {
    val email = draft.email
    val users = draft.auth.users
    SwitchRow(
        tr("E-posta gönderimi"),
        if (users.enabled) tr("Hesap doğrulama ve \"parolamı unuttum\" e-postaları; script'lerden ctx.mail.") else tr("Script'lerden ctx.mail.send ile e-posta."),
        email.enabled,
    ) { on -> vm.updateEmail { it.copy(enabled = on) } }
    if (!email.enabled) return

    Text(tr("Hazır ayar"), style = MaterialTheme.typography.labelLarge)
    var note by rememberSaveable { mutableStateOf<String?>(null) }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SmtpPreset.entries.forEach { p ->
            FilterChip(selected = email.host == p.host, onClick = { vm.applySmtpPreset(p); note = p.note }, label = { Text(p.title) })
        }
    }
    (note ?: SmtpPreset.entries.firstOrNull { it.host == email.host }?.note)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = email.host, onValueChange = { v -> vm.updateEmail { it.copy(host = v.trim()) } },
            label = { Text(tr("SMTP sunucusu")) }, singleLine = true, modifier = Modifier.weight(2f),
            isError = vm.errorAt("email.host") != null,
        )
        OutlinedTextField(
            value = email.port.toString(),
            onValueChange = { v -> v.filter(Char::isDigit).take(5).toIntOrNull()?.let { n -> vm.updateEmail { it.copy(port = n) } } },
            label = { Text(tr("Port")) }, singleLine = true, modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
    ErrorText(vm.errorAt("email.host") ?: vm.errorAt("email.port"))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SmtpSecurity.entries.forEach { sec ->
            FilterChip(selected = email.security == sec, onClick = { vm.updateEmail { it.copy(security = sec) } }, label = { Text(sec.title) })
        }
    }
    ErrorText(vm.errorAt("email.security"))
    OutlinedTextField(
        value = email.from, onValueChange = { v -> vm.updateEmail { it.copy(from = v.trim()) } },
        label = { Text(tr("Gönderen adresi")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        isError = vm.errorAt("email.from") != null,
        supportingText = { Text(vm.errorAt("email.from") ?: tr("Örn. evim@gmail.com")) },
    )
    OutlinedTextField(
        value = email.username.orEmpty(), onValueChange = { v -> vm.updateEmail { it.copy(username = v.trim().ifEmpty { null }) } },
        label = { Text(tr("Kullanıcı adı")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        supportingText = { Text(tr("Çoğunlukla e-posta adresinizin kendisi")) },
    )
    // Parola ekran durumuna yazılmasın diye rememberSaveable kullanılmaz.
    var password by remember { mutableStateOf("") }
    val stored = email.password != null
    OutlinedTextField(
        value = password, onValueChange = { password = it },
        label = { Text(if (stored) tr("Parola (kayıtlı; değiştirmek için yazın)") else tr("Parola / uygulama şifresi")) },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        isError = vm.errorAt("email.password") != null,
        supportingText = { Text(vm.errorAt("email.password") ?: tr("Şifreli depoda saklanır, konfigürasyon dosyasına yazılmaz.")) },
    )
    if (password.isNotBlank()) {
        TextButton(onClick = { vm.setEmailPassword(password); password = "" }) { Text(tr("Parolayı kaydet")) }
    }
    OutlinedTextField(
        value = email.fromName.orEmpty(), onValueChange = { v -> vm.updateEmail { it.copy(fromName = v.ifBlank { null }) } },
        label = { Text(tr("Gönderen adı (isteğe bağlı)")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        supportingText = { Text(tr("Boşsa sunucu adı: {0}", draft.name)) },
    )
    OutlinedTextField(
        value = email.publicUrl.orEmpty(), onValueChange = { v -> vm.updateEmail { it.copy(publicUrl = v.trim().ifEmpty { null }) } },
        label = { Text(tr("Bağlantılardaki adres (isteğe bağlı)")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        isError = vm.errorAt("email.publicUrl") != null,
        supportingText = {
            Text(
                vm.errorAt("email.publicUrl")
                    ?: tr("Sunucunuzun internetten adresi; e-postadaki bağlantılar buraya gelir (/_auth/verify, /_auth/reset). Boşsa e-postada kod gönderilir."),
            )
        },
    )
    vm.suggestedPublicUrl()?.takeIf { it != email.publicUrl }?.let { url ->
        TextButton(onClick = { vm.updateEmail { it.copy(publicUrl = url) } }) { Text(tr("Erişim ayarından kullan: {0}", url)) }
    }

    if (users.enabled) {
        SwitchRow(tr("Kayıtta doğrulama e-postası"), tr("Yeni hesaba e-posta adresini doğrulama bağlantısı gönderilir."), users.sendVerification) { vm.setSendVerification(it) }
        SwitchRow(
            tr("Doğrulanmadan giriş yok"),
            tr("Açıkken e-postasını doğrulamamış kullanıcı giriş yapamaz; kayıtta oturum verilmez."),
            users.requireVerified,
        ) { vm.setRequireVerified(it) }
        ErrorText(vm.errorAt("auth.users.requireVerified"))
    }

    // Deneme e-postası taslaktaki ayarlarla, kaydetmeden gönderilir.
    val scope = rememberCoroutineScope()
    var to by rememberSaveable { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = to, onValueChange = { to = it }, label = { Text(tr("Deneme alıcısı")) }, singleLine = true,
            modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        )
        OutlinedButton(enabled = !sending && to.isNotBlank(), onClick = {
            sending = true
            result = null
            scope.launch {
                val error = withContext(Dispatchers.IO) { vm.sendTestEmail(to) }
                result = error?.let { tr("Gönderilemedi: {0}", it) } ?: tr("Gönderildi; gelen kutusunu (ve gereksiz klasörünü) kontrol edin.")
                sending = false
            }
        }) { Text(if (sending) tr("Gönderiliyor…") else tr("Deneme gönder")) }
    }
    result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun UserFieldCard(vm: WizardViewModel, index: Int, field: FieldConfig, public: Boolean) {
    val path = "auth.users.extraFields[$index]"
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val nameError = vm.errorAt("$path.name")
                OutlinedTextField(
                    value = field.name, onValueChange = { v -> vm.updateUserField(index) { it.copy(name = v) } },
                    label = { Text(tr("Alan adı")) }, singleLine = true, modifier = Modifier.weight(1f),
                    isError = nameError != null,
                )
                IconButton(onClick = { vm.removeUserField(index) }) { Icon(Icons.Default.Delete, contentDescription = tr("Alanı sil")) }
            }
            ErrorText(vm.errorAt("$path.name"))
            TypePicker(field.type, FieldType.entries - FieldType.RELATION) { vm.setUserFieldType(index, it) }
            ErrorText(vm.errorAt("$path.type"))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = field.required, onCheckedChange = { v -> vm.updateUserField(index) { it.copy(required = v) } })
                Text(tr("Zorunlu"))
                Spacer(Modifier.width(12.dp))
                Checkbox(checked = field.unique, onCheckedChange = { v -> vm.updateUserField(index) { it.copy(unique = v) } })
                Text(tr("Benzersiz"))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = public, onCheckedChange = { vm.setUserFieldPublic(index, it) })
                Text(tr("Herkese görünür"))
            }
            val others = vm.draft.collectAsState().value.auth.users.extraFields
                .filter { it.name != field.name && it.type != FieldType.FILE && it.type != FieldType.JSON }.map { it.name }
            FieldRulesEditor("users/$index", field, path, vm::errorAt, others) { change -> vm.updateUserField(index, change) }
            if (field.computed == null) DefaultValueEditor("users/$index", field) { v -> vm.updateUserField(index) { it.copy(default = v) } }
            ErrorText(vm.errorAt("$path.default"))
        }
    }
}
