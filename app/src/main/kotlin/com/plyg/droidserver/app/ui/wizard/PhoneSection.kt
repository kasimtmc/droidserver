package com.plyg.droidserver.app.ui.wizard

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.plyg.droidserver.app.BuildConfig
import com.plyg.droidserver.app.graph
import com.plyg.droidserver.app.ui.common.ErrorText
import com.plyg.droidserver.app.ui.common.RadioRow
import com.plyg.droidserver.app.ui.common.WarningText
import com.plyg.droidserver.core.config.ConfigSecrets
import com.plyg.droidserver.core.config.PhoneConfig
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.config.SmsProviderConfig
import com.plyg.droidserver.core.config.SmsProviderType
import com.plyg.droidserver.core.config.SmsSim
import com.plyg.droidserver.core.i18n.tr

/** SMS, konum ve cihaz durumu: her biri ayrı açılır, açılınca Android izni istenir. */
@Composable
internal fun PhoneSection(vm: WizardViewModel, draft: ServerConfig) {
    val ctx = LocalContext.current
    val phone = draft.phone
    fun set(change: (PhoneConfig) -> PhoneConfig) = vm.update { it.copy(phone = change(it.phone)) }
    // İzin sonucu gelince ekran yeniden çizilsin diye sayaç.
    var asked by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { asked++ }
    fun granted(p: String) = asked >= 0 && ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    val smsPermission = arrayOf(Manifest.permission.SEND_SMS)
    val locationPermission = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    Text(tr("Telefon özellikleri"), style = MaterialTheme.typography.titleMedium)
    Text(
        tr("Script'lerden (ctx.sms.send, ctx.device.location(), ctx.device.status()) ve yönetici API'sinden ({0}/_phone/…) " +
            "telefonun kendisini kullanın. Her biri ayrı açılır ve Android izni ister; kapalıyken hiçbiri çalışmaz.", draft.server.basePath.trimEnd('/')),
        style = MaterialTheme.typography.bodySmall,
    )

    // SMS yolları (PLAN 19.5): sağlayıcı (otomatik), SIM'den doğrudan (yalnızca GitHub sürümü, SEND_SMS) ya da her SMS'te
    // telefonun sahibinin onayıyla (bildirim → SMS uygulaması).
    Toggle(tr("SMS gönderme"), tr("Doğrulama kodu, sipariş bildirimi gibi; operatör ya da sağlayıcı ücretine tabidir."), phone.sms) { on ->
        // Play sürümünde doğrudan SIM yok: açılınca yol seçilene kadar SIM kapalı (onaylı kip açık bilgilendirme ister).
        set { it.copy(sms = on, smsSim = if (on && !BuildConfig.SIM_SMS && it.smsSim == SmsSim.DIRECT) SmsSim.OFF else it.smsSim) }
        if (on && BuildConfig.SIM_SMS && phone.smsSim == SmsSim.DIRECT && !granted(Manifest.permission.SEND_SMS)) launcher.launch(smsPermission)
    }
    if (phone.sms) {
        var disclosure by remember { mutableStateOf(false) }
        val settings = ctx.graph.settings
        Text(tr("SIM'den gönderim"), style = MaterialTheme.typography.labelLarge)
        if (BuildConfig.SIM_SMS) RadioRow(phone.smsSim == SmsSim.DIRECT, tr("Doğrudan (otomatik)"), tr("SMS izniyle, onay beklemeden.")) {
            set { it.copy(smsSim = SmsSim.DIRECT) }
            if (!granted(Manifest.permission.SEND_SMS)) launcher.launch(smsPermission)
        }
        RadioRow(phone.smsSim == SmsSim.CONFIRM, tr("Her SMS'te onayımla"), tr("Bildirimdeki \"SMS'i gönder\" düğmesine dokununca SMS uygulamanız numara ve metinle açılır.")) {
            if (settings.smsConfirmDisclosure) set { it.copy(smsSim = SmsSim.CONFIRM) } else disclosure = true
        }
        RadioRow(phone.smsSim == SmsSim.OFF, tr("Kapalı"), tr("Yalnızca SMS sağlayıcısı kullanılır.")) { set { it.copy(smsSim = SmsSim.OFF) } }
        ErrorText(vm.errorAt("phone.smsSim"))
        when (phone.smsSim) {
            SmsSim.DIRECT -> if (!BuildConfig.SIM_SMS) {
                WarningText(tr("Bu sürümde SMS, SIM kartından doğrudan gönderilemez; onaylı gönderimi ya da bir sağlayıcıyı seçin."))
            } else if (!granted(Manifest.permission.SEND_SMS)) {
                PermissionWarning(tr("SMS izni verilmedi.")) { launcher.launch(smsPermission) }
            }
            SmsSim.CONFIRM -> {
                WarningText(tr("Her SMS'te telefonun başında onay gerekir: bildirimdeki \"SMS'i gönder\" düğmesine dokunun, SMS uygulamanız açılır. Script'lerde ctx.sms.send hemen {status: \"awaiting_user\"} döner."))
                if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
                    PermissionWarning(tr("Bildirim izni verilmedi; onay bekleyen SMS gösterilemez.")) { launcher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) }
                }
            }
            SmsSim.OFF -> Unit
        }
        if (disclosure) {
            SmsDisclosure(onAccept = {
                settings.smsConfirmDisclosure = true
                disclosure = false
                set { it.copy(smsSim = SmsSim.CONFIRM) }
                if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) launcher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }, onDismiss = { disclosure = false })
        }

        SmsProviderFields(vm, phone.smsProvider) { p -> set { it.copy(smsProvider = p) } }

        var limit by rememberSaveable { mutableStateOf(phone.smsDailyLimit.toString()) }
        OutlinedTextField(
            value = limit,
            onValueChange = { v -> limit = v.filter(Char::isDigit).take(3); limit.toIntOrNull()?.let { n -> set { it.copy(smsDailyLimit = n) } } },
            label = { Text(tr("Günlük en çok SMS")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = vm.errorAt("phone.smsDailyLimit") != null,
            supportingText = { Text(vm.errorAt("phone.smsDailyLimit") ?: tr("Kötüye kullanım ve fatura sürprizine karşı; aşılınca 429.")) },
        )
        var prefixes by rememberSaveable { mutableStateOf(phone.smsPrefixes.joinToString(", ")) }
        OutlinedTextField(
            value = prefixes,
            onValueChange = { v -> prefixes = v; set { it.copy(smsPrefixes = v.split(',', ' ').map(String::trim).filter(String::isNotEmpty)) } },
            label = { Text(tr("İzinli ülke kodları")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            isError = vm.errorAt("phone.smsPrefixes") != null,
            supportingText = { Text(vm.errorAt("phone.smsPrefixes") ?: tr("Yalnızca bunlarla başlayan numaralara gönderilir (örn. +90); yurt dışı ücretli SMS'e karşı.")) },
        )
    }

    val locationNote = if (ctx.applicationInfo.targetSdkVersion >= 29) " " + tr("Bu sürümde yalnızca DroidServer ekrandayken.") else ""
    Toggle(tr("Konum"), tr("Telefonun son bilinen konumu (GPS'i ayrıca çalıştırmaz).") + locationNote, phone.location) { on ->
        set { it.copy(location = on) }
        if (on && locationPermission.none(::granted)) launcher.launch(locationPermission)
    }
    if (phone.location && locationPermission.none(::granted)) PermissionWarning(tr("Konum izni verilmedi.")) { launcher.launch(locationPermission) }

    Toggle(tr("Cihaz durumu"), tr("Pil, sıcaklık, şarj, boş depolama ve bellek."), phone.status) { on -> set { it.copy(status = on) } }
    ErrorText(vm.errorAt("phone"))
}

@Composable
private fun Toggle(title: String, description: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = on, onCheckedChange = onChange)
    }
}

@Composable
private fun PermissionWarning(text: String, onRequest: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { WarningText(text) }
        TextButton(onClick = onRequest) { Text(tr("İzin ver")) }
    }
}

/** SMS sağlayıcısı (Netgsm, İleti Merkezi, Twilio): seçim ve hesap bilgileri; gizli anahtar boş bırakılırsa kayıtlı olan kalır. */
@Composable
private fun SmsProviderFields(vm: WizardViewModel, provider: SmsProviderConfig?, onChange: (SmsProviderConfig?) -> Unit) {
    Text(tr("SMS sağlayıcısı"), style = MaterialTheme.typography.labelLarge)
    Text(tr("Otomatik gönderim, operatör üzerinden; önce bu denenir, olmazsa SIM yolu."), style = MaterialTheme.typography.bodySmall)
    RadioRow(provider == null, tr("Yok"), tr("Yalnızca SIM.")) { onChange(null) }
    SmsProviderType.entries.forEach { type ->
        RadioRow(provider?.type == type, type.title, providerHint(type)) {
            onChange(provider?.copy(type = type) ?: SmsProviderConfig(type, "", "", ""))
        }
    }
    val p = provider ?: return
    val (userLabel, secretLabel, senderLabel) = when (p.type) {
        SmsProviderType.NETGSM -> Triple(tr("Kullanıcı kodu"), tr("API parolası"), tr("Onaylı başlık"))
        SmsProviderType.ILETIMERKEZI -> Triple(tr("API anahtarı"), tr("API hash"), tr("Onaylı başlık"))
        SmsProviderType.TWILIO -> Triple("Account SID", "Auth Token", tr("Gönderen numara ya da Messaging Service SID"))
    }
    OutlinedTextField(
        value = p.user, onValueChange = { onChange(p.copy(user = it.trim())) }, label = { Text(userLabel) }, singleLine = true,
        modifier = Modifier.fillMaxWidth(), isError = vm.errorAt("phone.smsProvider.user") != null,
        supportingText = vm.errorAt("phone.smsProvider.user")?.let { e -> { Text(e) } },
    )
    var secret by remember(p.type) { mutableStateOf("") }
    val saved = ConfigSecrets.isRef(p.secret)
    OutlinedTextField(
        value = if (saved) secret else p.secret,
        onValueChange = { v -> secret = v.trim(); onChange(p.copy(secret = if (saved && secret.isEmpty()) p.secret else secret)) },
        label = { Text(secretLabel) }, singleLine = true, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation(),
        isError = vm.errorAt("phone.smsProvider.secret") != null,
        supportingText = { Text(vm.errorAt("phone.smsProvider.secret") ?: if (saved) tr("Şifreli depoda kayıtlı; değiştirmek için yazın") else tr("Kaydedince şifreli depoya taşınır")) },
    )
    OutlinedTextField(
        value = p.sender, onValueChange = { onChange(p.copy(sender = it.trim())) }, label = { Text(senderLabel) }, singleLine = true,
        modifier = Modifier.fillMaxWidth(), isError = vm.errorAt("phone.smsProvider.sender") != null,
        supportingText = vm.errorAt("phone.smsProvider.sender")?.let { e -> { Text(e) } },
    )
}

private fun providerHint(type: SmsProviderType) = when (type) {
    SmsProviderType.NETGSM -> tr("Türkiye; kullanıcı kodu, API parolası ve onaylı başlık.")
    SmsProviderType.ILETIMERKEZI -> tr("Türkiye; panelden API anahtarı ve hash, onaylı başlık.")
    SmsProviderType.TWILIO -> tr("Uluslararası; Account SID, Auth Token ve gönderen numara.")
}

/**
 * Onaylı SMS'in açık bilgilendirmesi (Play'in "prominent disclosure" kuralı): tam sayfa; kabul edilmeden kip açılmaz.
 */
@Composable
private fun SmsDisclosure(onAccept: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(tr("SMS'i onayınızla gönderme"), style = MaterialTheme.typography.headlineSmall)
                Text(tr("Bu uygulama yerel bir sunucudur. Sunucuya gelen doğrulama ve giriş istekleri, sizin onayınızla telefonunuzun SMS uygulamasından SMS göndermenizi isteyebilir."))
                Text(tr("Kullanılan veriler"), style = MaterialTheme.typography.titleSmall)
                Text(tr("Alıcının telefon numarası ve SMS metni (örneğin giriş kodu): yalnızca SMS'i göndermeniz için bildirimde gösterilir ve SMS uygulamanıza aktarılır. Telefonda kalır, başka yere gönderilmez; denetim kaydında numaranın son dört hanesi gizlenir, metin yazılmaz."))
                Text(tr("Nasıl çalışır"), style = MaterialTheme.typography.titleSmall)
                Text(tr("Her istek için bir bildirim çıkar. \"SMS'i gönder\" düğmesine dokunursanız SMS uygulamanız numara ve metinle açılır; SMS'i siz gönderirsiniz. Atla ile istek düşer. Bu kipte uygulama SMS'leri kendiliğinden göndermez."))
                Text(tr("Nasıl kapatılır"), style = MaterialTheme.typography.titleSmall)
                Text(tr("Sihirbaz → Genel → Telefon özellikleri'nde SIM'den gönderimi kapatın ya da SMS göndermeyi kapatın."))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onAccept) { Text(tr("Kabul ediyorum")) }
                    TextButton(onClick = onDismiss) { Text(tr("Vazgeç")) }
                }
            }
        }
    }
}
