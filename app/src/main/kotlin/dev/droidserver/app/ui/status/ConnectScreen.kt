package dev.droidserver.app.ui.status

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.droidserver.app.AppGraph
import dev.droidserver.app.data.NetUtil
import dev.droidserver.app.ui.common.ScreenScaffold
import dev.droidserver.app.ui.common.WarningText
import dev.droidserver.app.ui.common.copySecret
import dev.droidserver.core.api.issueJwt
import dev.droidserver.core.config.AuthMode
import dev.droidserver.core.config.ConfigSecrets
import dev.droidserver.core.discovery.ConnectLink
import dev.droidserver.core.discovery.QrCode
import dev.droidserver.core.i18n.tr
import kotlinx.coroutines.delay
import java.time.Instant

private enum class ShareKind(val title: String) { ADDRESS(tr("Yalnızca adres")), ACCESS(tr("Adres + erişim")), PANEL(tr("Panele giriş")) }

/** Kısa ömürlü JWT süreleri (saat). */
private val TOKEN_HOURS = listOf(1 to tr("1 saat"), 24 to tr("1 gün"), 24 * 7 to tr("7 gün"))

/**
 * Bağlantıyı QR ile paylaşma: istemci uygulamaya adres (ve isteğe bağlı erişim), başka bir cihazın tarayıcısına
 * panel girişi (tek kullanımlık eşleştirme kodu adresin `#pair=` parçasında; sunucuya gitmez).
 */
@Composable
fun ConnectScreen(g: AppGraph, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val config = remember { runCatching { g.configRepo.loadActiveOrNull() }.getOrNull() }
    val direct by g.direct.status.collectAsState()
    val bases = remember(direct) {
        val port = config?.server?.port ?: 8080
        buildList {
            NetUtil.localIpv4().forEach { add("http://$it:$port") }
            direct.ddnsHost?.takeIf { direct.ddnsActive }?.let { add("https://$it:${direct.httpsPort}") }
            direct.addresses.forEach { add("https://$it:${direct.httpsPort}") }
        }.ifEmpty { listOf("http://127.0.0.1:$port") }
    }
    var base by rememberSaveable { mutableStateOf(bases.first()) }
    var custom by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(ShareKind.ADDRESS) }
    var hours by rememberSaveable { mutableIntStateOf(24) }
    var pairing by remember { mutableStateOf<Pair<String, Long>?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(pairing) { while (pairing != null) { now = System.currentTimeMillis(); if (now > pairing!!.second) pairing = null; delay(1_000) } }

    val root = custom.trim().trimEnd('/').takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: base
    val api = root + (config?.server?.basePath ?: "/api")
    val (text, note) = remember(kind, root, hours, pairing, config) {
        when {
            config == null -> null to tr("Henüz konfigürasyon yok.")
            kind == ShareKind.ADDRESS -> ConnectLink(api, config.name).toUri() to tr("Adres ve sunucu adı; erişim bilgisi içermez.")
            kind == ShareKind.ACCESS -> runCatching {
                val cfg = ConfigSecrets.resolve(config, g.secrets::get)
                when (cfg.auth.mode) {
                    AuthMode.JWT -> {
                        val exp = Instant.now().plusSeconds(hours * 3600L)
                        ConnectLink(api, cfg.name, issueJwt(cfg, "qr-davet", hours * 3600L), exp).toUri() to
                            tr("Yönetici yetkili token, {0} sonra geçersiz olur. Yalnızca güvendiğiniz cihazla paylaşın.", TOKEN_HOURS.first { it.first == hours }.second)
                    }
                    AuthMode.API_KEY -> cfg.auth.apiKeys.firstOrNull()?.let { ConnectLink(api, cfg.name, it).toUri() to
                        tr("Ana API anahtarı süresizdir ve tam yetkilidir: QR'ın fotoğrafı anahtarın kendisidir. Sınırlı erişim için Sihirbaz > Güvenlik'te adlandırılmış anahtar oluşturun.") } ?: (null to tr("API anahtarı yok."))
                    AuthMode.NONE -> ConnectLink(api, cfg.name).toUri() to tr("Kimlik doğrulama kapalı; adres yeterli.")
                }
            }.getOrElse { null to tr("Hata: {0}", it.message) }
            !config.adminPanel.enabled -> null to tr("Web paneli kapalı (Sihirbaz > Güvenlik).")
            pairing == null -> null to tr("Kod oluşturun; QR'ı tarayan cihazın tarayıcısı panele kendiliğinden girer.")
            else -> "$root/_admin/#pair=${pairing!!.first}" to tr("Tek kullanımlık kod, 5 dakika geçerli.")
        }
    }

    ScreenScaffold(tr("Bağlan (QR)"), onBack = onBack) { modifier ->
        Column(
            modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ShareKind.entries.forEach { k -> FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k.title) }) }
            }
            Text(tr("Adres"), style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                bases.forEach { b -> FilterChip(selected = base == b && custom.isBlank(), onClick = { base = b; custom = "" }, label = { Text(b) }) }
            }
            OutlinedTextField(
                value = custom, onValueChange = { custom = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text(tr("Başka adres (tünel, alan adı)")) }, placeholder = { Text("https://ornek.trycloudflare.com") },
            )
            if (kind == ShareKind.ACCESS && config?.auth?.mode == AuthMode.JWT) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TOKEN_HOURS.forEach { (h, title) -> FilterChip(selected = hours == h, onClick = { hours = h }, label = { Text(title) }) }
                }
            }
            if (kind == ShareKind.PANEL && config?.adminPanel?.enabled == true) {
                OutlinedButton(onClick = { g.engine.adminSessions.newCode().let { pairing = it.code to it.expiresAtMs } }) {
                    Text(if (pairing == null) tr("Eşleştirme kodu oluştur") else tr("Yeni kod ({0} sn kaldı)", (pairing!!.second - now) / 1000))
                }
            }
            text?.let { t ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Qr(t, Modifier.widthIn(max = 320.dp).fillMaxWidth()) }
                Text(t, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { copySecret(ctx, tr("bağlantı"), t) }) { Text(tr("Kopyala")) }
                    OutlinedButton(onClick = {
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t), tr("Bağlantı")))
                    }) { Text(tr("Paylaş")) }
                }
            }
            note?.let { if (kind == ShareKind.ACCESS && config?.auth?.mode == AuthMode.API_KEY) WarningText(it) else Text(it, style = MaterialTheme.typography.bodySmall) }
            Text(
                tr("İstemci uygulamada: DroidServerClient.fromLink(metin) / DroidServer.fromLink(metin). Aynı ağdaki uygulamalar " +
                    "sunucuyu QR olmadan da bulabilir (Sihirbaz > Erişim > Yerel ağda görünür ol)."),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** QR kodu (beyaz zeminde siyah; 4 modül kenar boşluğu). */
@Composable
private fun Qr(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) { runCatching { QrCode.matrix(text) }.getOrNull() } ?: return
    Canvas(modifier.aspectRatio(1f).background(Color.White)) {
        val cells = matrix.size + 8
        val cell = size.width / cells
        matrix.forEachIndexed { y, row ->
            row.forEachIndexed { x, dark ->
                if (dark) drawRect(Color.Black, Offset((x + 4) * cell, (y + 4) * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}
