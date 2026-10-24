package com.plyg.droidserver.app.ui.wizard

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.plyg.droidserver.app.ui.common.ErrorText
import com.plyg.droidserver.app.ui.common.WarningText
import com.plyg.droidserver.core.config.PhoneConfig
import com.plyg.droidserver.core.config.ServerConfig
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

    Toggle(tr("SMS gönderme"), tr("Doğrulama kodu, sipariş bildirimi gibi; operatör ücretine tabidir."), phone.sms) { on ->
        set { it.copy(sms = on) }
        if (on && !granted(Manifest.permission.SEND_SMS)) launcher.launch(smsPermission)
    }
    if (phone.sms) {
        if (!granted(Manifest.permission.SEND_SMS)) PermissionWarning(tr("SMS izni verilmedi.")) { launcher.launch(smsPermission) }
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

    Toggle(tr("Konum"), tr("Telefonun son bilinen konumu (GPS'i ayrıca çalıştırmaz)."), phone.location) { on ->
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
