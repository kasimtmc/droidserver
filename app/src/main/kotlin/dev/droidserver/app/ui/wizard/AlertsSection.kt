package dev.droidserver.app.ui.wizard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.droidserver.app.ui.common.ErrorText
import dev.droidserver.core.alerts.Alert
import dev.droidserver.core.alerts.AlertKind
import dev.droidserver.core.alerts.TelegramChannel
import dev.droidserver.core.config.QuietHours
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

/** Sağlık uyarıları: kanallar (bildirim, e-posta, Telegram), eşikler ve sessiz saatler. */
@Composable
internal fun AlertsSection(vm: WizardViewModel, draft: ServerConfig) {
    val a = draft.alerts
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(tr("Sağlık uyarıları"), style = MaterialTheme.typography.titleMedium)
            Text(
                tr("Disk dolmak üzereyken, sunucu hataları artınca, sertifika bitmek üzereyken, pil ısınınca, yedek alınamayınca " +
                    "veya sunucu/uygulama çökünce haber verir. Aynı uyarı {0} saatte bir hatırlatılır, düzelince bildirilir.", a.repeatHours),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(checked = a.enabled, onCheckedChange = { on -> vm.updateAlerts { it.copy(enabled = on) } })
    }
    if (!a.enabled) return

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(tr("Telefon bildirimi"), Modifier.weight(1f))
        Switch(checked = a.notification, onCheckedChange = { on -> vm.updateAlerts { it.copy(notification = on) } })
    }

    var emails by rememberSaveable { mutableStateOf(a.email.joinToString(", ")) }
    OutlinedTextField(
        value = emails,
        onValueChange = { v -> emails = v; vm.updateAlerts { it.copy(email = v.split(',', ' ', '\n').map(String::trim).filter(String::isNotEmpty)) } },
        label = { Text(tr("E-posta alıcıları (virgülle)")) }, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        isError = (vm.errorAt("alerts.email") ?: a.email.indices.firstNotNullOfOrNull { vm.errorAt("alerts.email[$it]") }) != null,
        supportingText = {
            Text(
                vm.errorAt("alerts.email") ?: a.email.indices.firstNotNullOfOrNull { vm.errorAt("alerts.email[$it]") }
                    ?: if (draft.email.enabled) tr("Sunucunun e-posta (SMTP) ayarlarıyla gönderilir.") else tr("Önce Kullanıcılar adımında e-posta (SMTP) ayarlarını açın."),
            )
        },
    )

    TelegramEditor(vm, draft)

    Text(tr("Eşikler"), style = MaterialTheme.typography.labelLarge)
    NumberField(tr("Boş depolama altına inince (MB)"), a.diskFreeMb, vm.errorAt("alerts.diskFreeMb"), tr("100 MB'ın altı her zaman kritik sayılır.")) { n -> vm.updateAlerts { it.copy(diskFreeMb = n) } }
    NumberField(tr("Sunucu hata oranı (%)"), a.errorRatePercent, vm.errorAt("alerts.errorRatePercent"), tr("Son 5 dakikada en az 20 istek varken 5xx yüzdesi.")) { n -> vm.updateAlerts { it.copy(errorRatePercent = n) } }
    NumberField(tr("Sertifikanın bitmesine (gün)"), a.certificateDays, vm.errorAt("alerts.certificateDays"), tr("Yalnızca Let's Encrypt sertifikası; normalde 30 gün kala kendiliğinden yenilenir.")) { n -> vm.updateAlerts { it.copy(certificateDays = n) } }
    NumberField(tr("Pil sıcaklığı (°C)"), a.batteryTempC, vm.errorAt("alerts.batteryTempC"), tr("Uzun süre sıcak kalan pil şişebilir.")) { n -> vm.updateAlerts { it.copy(batteryTempC = n) } }
    NumberField(tr("Hatırlatma aralığı (saat)"), a.repeatHours, vm.errorAt("alerts.repeatHours"), tr("Süren bir sorun bu aralıkla yeniden bildirilir.")) { n -> vm.updateAlerts { it.copy(repeatHours = n) } }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(tr("Sessiz saatler"))
            Text(tr("Bu aralıkta yalnızca kritik uyarılar (sunucu durdu, disk doldu, çökme) gider."), style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = a.quietHours != null, onCheckedChange = { on -> vm.updateAlerts { it.copy(quietHours = if (on) QuietHours() else null) } })
    }
    a.quietHours?.let { q ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = q.from, onValueChange = { v -> vm.updateAlerts { it.copy(quietHours = q.copy(from = v.take(5))) } },
                label = { Text(tr("Başlangıç")) }, singleLine = true, modifier = Modifier.weight(1f),
                isError = vm.errorAt("alerts.quietHours.from") != null,
            )
            OutlinedTextField(
                value = q.to, onValueChange = { v -> vm.updateAlerts { it.copy(quietHours = q.copy(to = v.take(5))) } },
                label = { Text(tr("Bitiş")) }, singleLine = true, modifier = Modifier.weight(1f),
                isError = vm.errorAt("alerts.quietHours.to") != null,
            )
        }
        ErrorText(vm.errorAt("alerts.quietHours.from") ?: vm.errorAt("alerts.quietHours.to"))
    }
}

/** Telegram: token (şifreli depoya), sohbeti otomatik bulma, deneme mesajı. */
@Composable
private fun TelegramEditor(vm: WizardViewModel, draft: ServerConfig) {
    val t = draft.alerts.telegram
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(t != null) }
    var status by remember { mutableStateOf<String?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(tr("Telegram"))
            Text(tr("Uyarılar kendi botunuzdan telefonunuza mesaj olarak gelir."), style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = on, onCheckedChange = { v -> on = v; if (!v) vm.updateAlerts { it.copy(telegram = null) } })
    }
    if (!on) return
    Text(
        tr("1) Telegram'da @BotFather'a /newbot yazıp bot oluşturun ve verdiği token'ı aşağıya yapıştırın. 2) Botunuza herhangi " +
            "bir mesaj yazın (örn. /start). 3) \"Sohbeti bul\"a dokunun."),
        style = MaterialTheme.typography.bodySmall,
    )
    // Token ekran durumuna yazılmasın diye rememberSaveable kullanılmaz.
    var token by remember { mutableStateOf("") }
    OutlinedTextField(
        value = token, onValueChange = { token = it.trim() },
        label = { Text(tr("Bot token'ı") + if (t?.botToken?.isNotBlank() == true) tr(" — kayıtlı; değiştirmek için yazın") else "") },
        visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth(),
        isError = vm.errorAt("alerts.telegram.botToken") != null,
        supportingText = { Text(vm.errorAt("alerts.telegram.botToken") ?: tr("Şifreli depoda saklanır, konfigürasyon dosyasına yazılmaz.")) },
    )
    if (token.isNotBlank()) TextButton(onClick = { vm.setTelegramToken(token); token = "" }) { Text(tr("Kaydet")) }
    if (t != null) {
        OutlinedTextField(
            value = t.chatId, onValueChange = { v -> vm.updateAlerts { it.copy(telegram = t.copy(chatId = v.trim())) } },
            label = { Text(tr("Sohbet kimliği")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            isError = vm.errorAt("alerts.telegram.chatId") != null,
            supportingText = { Text(vm.errorAt("alerts.telegram.chatId") ?: tr("Kendiniz için sayı; grup için eksiyle başlar; kanal için @ad.")) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val key = vm.telegramToken() ?: run { status = tr("Önce token'ı kaydedin."); return@OutlinedButton }
                status = tr("Aranıyor…")
                scope.launch {
                    status = runCatching { withContext(Dispatchers.IO) { TelegramChannel.discoverChat(key) } }.fold(
                        { found ->
                            found?.let { (id, name) -> vm.updateAlerts { it.copy(telegram = it.telegram?.copy(chatId = id)) }; tr("Bulundu: {0}", name) }
                                ?: tr("Bota henüz mesaj yazılmamış; Telegram'da botunuza /start yazıp yeniden deneyin.")
                        },
                        { tr("Bulunamadı: {0}", it.message) },
                    )
                }
            }) { Text(tr("Sohbeti bul")) }
            OutlinedButton(enabled = t.chatId.isNotBlank(), onClick = {
                val key = vm.telegramToken() ?: run { status = tr("Önce token'ı kaydedin."); return@OutlinedButton }
                status = tr("Gönderiliyor…")
                scope.launch {
                    val test = Alert(AlertKind.TEST, "", AlertKind.TEST.title, tr("Telegram uyarıları çalışıyor."), Instant.now().toString(), critical = true)
                    status = runCatching { withContext(Dispatchers.IO) { TelegramChannel(key, t.chatId, draft.name).send(test) } }
                        .fold({ tr("Gönderildi; Telegram'ı kontrol edin.") }, { tr("Gönderilemedi: {0}", it.message) })
                }
            }) { Text(tr("Deneme gönder")) }
        }
    }
    status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}
