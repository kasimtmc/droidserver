package dev.droidserver.app.ui.status

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import dev.droidserver.app.AppGraph
import dev.droidserver.app.ui.common.SectionCard
import dev.droidserver.app.ui.common.WarningText
import dev.droidserver.app.ui.common.copyToClipboard
import dev.droidserver.app.ui.wizard.tailnetAddresses
import dev.droidserver.core.config.AccessMode
import dev.droidserver.core.config.CloudflareTunnel
import dev.droidserver.core.config.QuickTunnel
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.i18n.tr
import dev.droidserver.core.process.ProcessSupervisor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Sunucu çalışırken internetten erişim adresleri ve sorunlar (erişim moduna göre). */
@Composable
internal fun AccessStatusCard(g: AppGraph, config: ServerConfig) {
    when (config.access.mode) {
        AccessMode.LOCAL -> {}
        AccessMode.DIRECT -> DirectStatusCard(g, config)
        AccessMode.CLOUDFLARE -> CloudflareStatusCard(g, config)
        AccessMode.TAILSCALE -> TailscaleStatusCard(g, config)
    }
}

@Composable
private fun DirectStatusCard(g: AppGraph, config: ServerConfig) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by g.direct.status.collectAsState()
    SectionCard(tr("İnternetten erişim (HTTPS)")) {
        s.error?.let { WarningText(it) }
        if (!s.active) {
            Text(tr("HTTPS ucu çalışmıyor."), style = MaterialTheme.typography.bodySmall)
            return@SectionCard
        }
        val urls = buildList {
            s.ddnsHost?.takeIf { s.ddnsActive }?.let { add("https://$it:${s.httpsPort}") }
            s.addresses.forEach { add("https://$it:${s.httpsPort}") }
        }
        when {
            s.checking && s.checkedAt == null -> Text(tr("Ağ kontrol ediliyor…"), style = MaterialTheme.typography.bodySmall)
            urls.isEmpty() -> Text(tr("İnternetten ulaşılabilecek bir adres bulunamadı."), style = MaterialTheme.typography.bodySmall)
            else -> urls.forEach { Text(it, fontFamily = FontFamily.Monospace) }
        }
        s.mapping?.let { m ->
            Text(
                tr("Modemde {0} ile port açık: {1} → {2}", m.method, m.externalPort, m.internalPort) +
                    if (m.lifetimeSec > 0) tr(" (sunucu çalıştıkça yenilenir)") else "",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        s.mappingErrors.takeIf { s.mapping == null && it.isNotEmpty() }?.let {
            Text(tr("Otomatik port açma: {0}", it.joinToString("; ")), style = MaterialTheme.typography.bodySmall)
        }
        s.problems.forEach { WarningText(it) }
        s.ddnsError?.let { WarningText("DuckDNS: $it") }
        if (s.ddnsHost != null && !s.ddnsActive && s.ddnsError == null && s.checkedAt != null) {
            Text(tr("{0}: ulaşılabilir bir adres olmadığı için şu an hiçbir yere yönlendirilmiyor.", s.ddnsHost), style = MaterialTheme.typography.bodySmall)
        }

        if (s.certificateWorking) Text(tr("Let's Encrypt sertifikası alınıyor…"), style = MaterialTheme.typography.bodySmall)
        s.certificateError?.let {
            WarningText(tr("Let's Encrypt sertifikası alınamadı: {0}. Kendinden imzalı sertifikayla devam ediliyor; birkaç saat sonra yeniden denenecek.", it))
        }
        val pin = s.publicKeyPin
        val trusted = s.certificateUntil != null
        val example = if (trusted) {
            s.ddnsHost?.let { "curl https://$it:${s.httpsPort}${config.server.basePath}/health" }
        } else {
            urls.firstOrNull()?.let { u -> "curl --pinnedpubkey \"sha256//$pin\" -k $u${config.server.basePath}/health" }
        }
        if (trusted) {
            Text(
                tr("Sertifika: Let's Encrypt, {0} tarihine kadar geçerli (kendiliğinden yenilenir). " +
                    "Tarayıcılar {1} adına güvenir; IP adresiyle bağlanılırsa uyarı verir.", s.certificateUntil.toString().take(10), s.ddnsHost),
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            s.fingerprint?.let { Text(tr("Sertifika parmak izi (SHA-256):\n{0}", it), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        }
        example?.let { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        if (!trusted) {
            Text(
                tr("-k sertifikanın bir kuruluşça imzalanmadığını kabul eder; --pinnedpubkey yalnızca bu sunucunun anahtarını kabul ettirir " +
                    "(araya giren biri olursa bağlantı reddedilir)."),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row {
            urls.firstOrNull()?.let { u -> TextButton(onClick = { copyToClipboard(ctx, "adres", u) }) { Text(tr("Adresi kopyala")) } }
            pin?.let { p -> TextButton(onClick = { copyToClipboard(ctx, "pin", "sha256//$p") }) { Text(tr("Pin'i kopyala")) } }
            example?.let { e -> TextButton(onClick = { copyToClipboard(ctx, "curl", e) }) { Text("curl") } }
        }
        TextButton(enabled = !s.checking, onClick = { scope.launch { withContext(Dispatchers.IO) { g.direct.recheck() } } }) {
            Text(if (s.checking) tr("Kontrol ediliyor…") else tr("Yeniden kontrol et"))
        }
        s.checkedAt?.let { Text(tr("Son kontrol: {0}", it), style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun CloudflareStatusCard(g: AppGraph, config: ServerConfig) {
    val ctx = LocalContext.current
    val id = AccessMode.CLOUDFLARE.componentId!!
    val statuses by g.supervisor.statuses.collectAsState()
    val phase = statuses[id]?.phase
    var quickUrl by remember { mutableStateOf<String?>(null) }
    val quick = config.access.cloudflare.tunnel == CloudflareTunnel.QUICK
    if (quick) {
        // Adres yalnızca cloudflared'ın günlüğünde yazar; ekran açıkken izlenir.
        LaunchedEffect(Unit) {
            while (true) {
                quickUrl = QuickTunnel.urlIn(g.supervisor.logs(id))
                delay(2_000)
            }
        }
    }
    SectionCard("Cloudflare Tunnel") {
        Text(
            when (phase) {
                ProcessSupervisor.Phase.RUNNING -> tr("● cloudflared çalışıyor")
                ProcessSupervisor.Phase.STARTING -> tr("cloudflared başlıyor…")
                ProcessSupervisor.Phase.BACKOFF -> tr("cloudflared yeniden başlatılıyor (Loglar'a bakın)")
                ProcessSupervisor.Phase.FAILED, ProcessSupervisor.Phase.EXITED -> tr("✕ cloudflared durdu (Loglar'a bakın)")
                ProcessSupervisor.Phase.STOPPED, null -> tr("cloudflared çalışmıyor (bileşen kurulu mu?)")
            },
            style = MaterialTheme.typography.bodySmall,
        )
        val url = if (quick) quickUrl else config.access.cloudflare.hostname?.let { "https://$it" }
        if (url != null) {
            Text(url + config.server.basePath, fontFamily = FontFamily.Monospace)
            TextButton(onClick = { copyToClipboard(ctx, "adres", url) }) { Text(tr("Adresi kopyala")) }
        } else if (quick) {
            Text(tr("Adres bekleniyor… (tünel kurulunca burada görünür)"), style = MaterialTheme.typography.bodySmall)
        } else {
            Text(tr("Adres Cloudflare panelinde tünele eklediğiniz alan adıdır."), style = MaterialTheme.typography.bodySmall)
        }
        if (quick) WarningText(tr("Hızlı tünel adresi her başlatmada değişir; istemcilerdeki adresi güncelleyin."))
    }
}

@Composable
private fun TailscaleStatusCard(g: AppGraph, config: ServerConfig) {
    var ips by remember { mutableStateOf(tailnetAddresses(g)) }
    SectionCard(tr("Tailscale")) {
        if (ips.isEmpty()) {
            WarningText(tr("Tailscale bağlı değil: Tailscale uygulamasını açıp bağlanın."))
        } else {
            ips.forEach { Text("http://$it:${config.server.port}", fontFamily = FontFamily.Monospace) }
            Text(tr("Yalnızca Tailscale'e giriş yaptığınız cihazlardan erişilir."), style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { ips = tailnetAddresses(g) }) { Text(tr("Yeniden kontrol et")) }
    }
}
