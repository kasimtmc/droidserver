package dev.droidserver.app.ui.wizard

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.core.net.toUri
import dev.droidserver.app.AppGraph
import dev.droidserver.app.data.NetUtil
import dev.droidserver.app.ui.common.ChoiceCard
import dev.droidserver.app.ui.common.ErrorText
import dev.droidserver.app.ui.common.RadioRow
import dev.droidserver.app.ui.common.SectionCard
import dev.droidserver.app.ui.common.WarningText
import dev.droidserver.core.config.AccessMode
import dev.droidserver.core.config.AuthMode
import dev.droidserver.core.config.CloudflareTunnel
import dev.droidserver.core.config.ConfigSummary
import dev.droidserver.core.config.DirectNetwork
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.config.accessToken
import dev.droidserver.core.i18n.tr
import dev.droidserver.core.net.AddressScope
import dev.droidserver.core.net.NetInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun AccessStep(g: AppGraph, vm: WizardViewModel, draft: ServerConfig, installedIds: Set<String>, onInstalled: () -> Unit) {
    val mode = AccessMode.of(draft)
    val port = draft.server.port
    StepColumn {
        StepIntro(tr("Sunucuya nereden erişilecek? Seçiminize göre gerekenleri uygulama yapar; yapamadıklarını adım adım anlatır."))
        ChoiceCard(
            mode == AccessMode.LOCAL, AccessMode.LOCAL.title,
            tr("Aynı Wi-Fi'daki cihazlar http://<telefon-ip>:{0} ile erişir. Ek kurulum gerekmez; internetten erişilemez.", port),
            onClick = { vm.setAccess(AccessMode.LOCAL) },
        )
        ChoiceCard(
            mode == AccessMode.DIRECT, AccessMode.DIRECT.title,
            tr("Hiçbir hesap veya ek uygulama olmadan, telefonun internet adresiyle HTTPS. Wi-Fi'de modemde port açılır " +
                "(uygulama otomatik dener); mobil veride operatörün IPv6 desteğine bağlıdır."),
            onClick = { vm.setAccess(AccessMode.DIRECT) },
        )
        ChoiceCard(
            mode == AccessMode.CLOUDFLARE, AccessMode.CLOUDFLARE.title,
            tr("Her ağda çalışır (CGNAT, mobil veri dahil); modemde port açmak gerekmez, HTTPS Cloudflare'de. " +
                "Hesapsız hızlı tünel veya kendi alan adınız."),
            onClick = { vm.setAccess(AccessMode.CLOUDFLARE) },
            badge = tr("en kolay"),
        )
        ChoiceCard(
            mode == AccessMode.TAILSCALE, AccessMode.TAILSCALE.title,
            tr("Sunucu internete açılmaz; yalnızca Tailscale'e giriş yaptığınız kendi cihazlarınız erişir. Tailscale uygulaması gerekir."),
            onClick = { vm.setAccess(AccessMode.TAILSCALE) },
            badge = tr("en güvenli"),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("Yerel ağda görünür ol (mDNS)"))
                Text(
                    tr("Aynı ağdaki uygulamalar sunucuyu adres yazmadan bulur (_droidserver._tcp). Yalnızca ad, port ve API yolu " +
                        "duyurulur; anahtar duyurulmaz."),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = draft.access.discovery, onCheckedChange = vm::setDiscovery)
        }

        when (mode) {
            AccessMode.LOCAL -> LocalSection(port)
            AccessMode.DIRECT -> DirectSection(vm, draft)
            AccessMode.CLOUDFLARE -> CloudflareSection(g, vm, draft, installedIds, onInstalled)
            AccessMode.TAILSCALE -> TailscaleSection(g, port)
        }

        NoAuthConsent(vm, draft)
        ConfigSummary.securityWarnings(draft).forEach { WarningText(it) }
    }
}

@Composable
private fun LocalSection(port: Int) {
    SectionCard(tr("Nasıl erişilir")) {
        val ips = NetUtil.localIpv4()
        Text(
            if (ips.isEmpty()) tr("Telefon şu an bir Wi-Fi ağına bağlı değil.") else ips.joinToString("\n") { "http://$it:$port" },
            fontFamily = FontFamily.Monospace,
        )
        Steps(
            tr("Erişecek cihaz aynı Wi-Fi ağında olmalı."),
            tr("Telefonun yerel adresi modem her yeniden bağlandığında değişebilir; modemde telefona sabit IP (DHCP rezervasyonu) verin."),
        )
        WarningText(tr("Misafir ağları ve bazı kurumsal ağlar cihazların birbirini görmesini engeller (istemci yalıtımı)."))
    }
}

@Composable
private fun DirectSection(vm: WizardViewModel, draft: ServerConfig) {
    val d = draft.access.direct
    SectionCard(tr("Telefon internete nasıl bağlı?")) {
        RadioRow(d.network == DirectNetwork.WIFI, tr("Wi-Fi (ev / ofis modemi)"), tr("Modemde port yönlendirme gerekir; uygulama UPnP / NAT-PMP ile otomatik açmayı dener.")) {
            vm.setDirectNetwork(DirectNetwork.WIFI)
        }
        RadioRow(d.network == DirectNetwork.MOBILE, tr("Mobil veri"), tr("Yalnızca operatör genel IPv6 adresi veriyor ve gelen bağlantıları engellemiyorsa çalışır.")) {
            vm.setDirectNetwork(DirectNetwork.MOBILE)
        }

        var portText by rememberSaveable(d.port) { mutableStateOf(d.port.toString()) }
        val portError = vm.errorAt("access.direct.port")
        OutlinedTextField(
            value = portText,
            onValueChange = { v -> portText = v.filter(Char::isDigit).take(5); portText.toIntOrNull()?.let(vm::setDirectPort) },
            label = { Text(tr("HTTPS portu")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = portError != null,
            supportingText = { Text(portError ?: tr("İnternetten https://<adres>:{0} ile erişilir. Sunucu portu ({1}) yalnızca yerel ağa açık kalır.", d.port, draft.server.port)) },
        )
    }

    if (d.network == DirectNetwork.WIFI) {
        SectionCard(tr("Wi-Fi: modemde port yönlendirme")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("Otomatik aç (UPnP / NAT-PMP)"))
                    Text(
                        tr("Sunucu çalıştıkça yönlendirme yenilenir, durunca kaldırılır. Modemde UPnP kapalıysa elle yapın."),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = d.portMapping, onCheckedChange = vm::setPortMapping)
            }
            Text(tr("Elle yapmak gerekirse:"), style = MaterialTheme.typography.labelLarge)
            Steps(
                tr("Tarayıcıda modem arayüzünü açın (genellikle http://192.168.1.1 veya http://192.168.0.1; modemin altındaki etikette yazar)."),
                tr("\"Port yönlendirme\" / \"NAT\" / \"Sanal sunucu\" bölümünde TCP {0} → telefonun yerel adresi ({1}), port {2} kuralı ekleyin.", d.port, NetUtil.localIpv4().firstOrNull() ?: tr("Wi-Fi adresi"), d.port),
                tr("Telefonun yerel adresi değişmesin: modemde \"DHCP rezervasyonu / sabit IP\" ile bu telefona adres ayırın."),
                tr("Telefonda Wi-Fi ayarları > bu ağ > Gizlilik: \"Cihaz MAC adresini kullan\" seçin; rastgele MAC her bağlantıda farklı cihaz gibi görünür ve rezervasyon tutmaz."),
            )
            WarningText(
                tr("Operatörünüz internet bağlantısını başka abonelerle paylaştırıyorsa (CGNAT) modemde port açmak işe yaramaz. " +
                    "Uygulama bunu sunucu başlayınca algılar ve Durum ekranında söyler; çözüm: operatörden \"gerçek/sabit IP\" istemek, IPv6 veya Cloudflare Tunnel."),
            )
            WarningText(tr("Aynı Wi-Fi'dan dış adresle denerseniz bazı modemler bağlantıyı geri döndürmez (hairpin NAT). Dışarıdan denemek için mobil veriye geçin."))
            WarningText(tr("Modemde UPnP açık olması, ağdaki başka uygulamaların da port açabilmesi demektir; işiniz bitince elle yönlendirmeyi tercih edebilirsiniz."))
        }
    } else {
        SectionCard(tr("Mobil veri")) {
            Steps(
                tr("Mobil veride IPv4 adresi neredeyse her zaman operatörde paylaşımlıdır (CGNAT); internetten yalnızca IPv6 ile ulaşılabilir."),
                tr("Ayarlar > Mobil ağ > Erişim noktası adları (APN) > APN protokolü: \"IPv4/IPv6\" olmalı."),
                tr("Erişecek cihazın da IPv6 bağlantısı olmalı (çoğu ev interneti ve mobil hat destekler)."),
                tr("Telefonun IPv6 adresi her bağlantıda değişir; aşağıdaki DuckDNS ile sabit bir ad kullanın."),
            )
            WarningText(tr("Bazı operatörler dışarıdan gelen bağlantıları engeller; o zaman doğrudan erişim mümkün değildir, Cloudflare Tunnel kullanın."))
            WarningText(tr("Her istek ve yanıt mobil veri kotanızdan düşer."))
        }
    }

    DdnsCard(vm, draft)

    SectionCard(tr("Güvenlik")) {
        Steps(
            tr("Bağlantı HTTPS ile şifrelenir. Sertifikayı uygulama üretir (kendinden imzalı): tarayıcı bir kez uyarı gösterir."),
            tr("Uygulamalarınızda sertifikayı sabitleyin (pinning); parmak izi ve örnek curl komutu sunucu başlayınca Durum ekranında görünür."),
            tr("İnternetten gelen istekler istemci başına sınırlanır; art arda hatalı anahtar deneyen adres geçici olarak engellenir."),
        )
    }
}

@Composable
private fun DdnsCard(vm: WizardViewModel, draft: ServerConfig) {
    val ddns = draft.access.direct.ddns
    SectionCard(tr("Sabit adres (isteğe bağlı, DuckDNS)")) {
        Text(
            tr("İnternet adresiniz zamanla değişir. Ücretsiz DuckDNS ile evim.duckdns.org gibi bir ad alın; uygulama adres değiştikçe günceller."),
            style = MaterialTheme.typography.bodySmall,
        )
        if (ddns != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("✓ {0}.duckdns.org", ddns.domain), Modifier.weight(1f), fontFamily = FontFamily.Monospace)
                TextButton(onClick = vm::clearDdns) { Text(tr("Kaldır")) }
            }
            ErrorText(vm.errorAt("access.direct.ddns.domain") ?: vm.errorAt("access.direct.ddns.token"))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Let's Encrypt sertifikası al"), Modifier.weight(1f))
                Switch(checked = ddns.certificate, onCheckedChange = vm::setDdnsCertificate)
            }
            Text(
                tr("Açıkken {0}.duckdns.org için herkesçe tanınan bir sertifika alınır: tarayıcılar uyarı vermez, " +
                    "istemcilerde -k veya sabitleme gerekmez. Doğrulama DuckDNS üzerinden yapılır (80/443 portları gerekmez), " +
                    "sertifika süresi dolmadan kendiliğinden yenilenir; alınamazsa kendinden imzalı sertifikayla devam edilir. " +
                    "Açarak Let's Encrypt kullanım koşullarını (letsencrypt.org/repository) kabul etmiş olursunuz.", ddns.domain),
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Steps(tr("duckdns.org'a girin, bir ad ekleyin ve sayfadaki token'ı kopyalayın."))
            var domain by rememberSaveable { mutableStateOf("") }
            // Token ekran durumuna (saved state) yazılmasın diye rememberSaveable kullanılmaz.
            var token by remember { mutableStateOf("") }
            OutlinedTextField(
                value = domain, onValueChange = { domain = it }, label = { Text(tr("Ad (örn. evim)")) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = token, onValueChange = { token = it }, label = { Text(tr("DuckDNS token'ı")) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            Button(onClick = { vm.setDdns(domain, token); token = "" }, enabled = domain.isNotBlank() && token.isNotBlank()) {
                Text(tr("Kaydet"))
            }
        }
    }
}

@Composable
private fun CloudflareSection(g: AppGraph, vm: WizardViewModel, draft: ServerConfig, installedIds: Set<String>, onInstalled: () -> Unit) {
    val cf = draft.access.cloudflare
    val id = AccessMode.CLOUDFLARE.componentId!!
    SectionCard(tr("cloudflared bileşeni")) {
        if (id in installedIds) {
            Text(tr("✓ Kurulu; deploy sırasında başlatılır."), style = MaterialTheme.typography.bodySmall)
        } else {
            InstallComponentButton(g, id, tr("cloudflared bileşenini kur (~10 MB)"), onInstalled)
        }
    }
    SectionCard(tr("Tünel türü")) {
        RadioRow(
            cf.tunnel == CloudflareTunnel.QUICK, tr("Hızlı tünel (hesap gerekmez)"),
            tr("Hemen çalışır: https://<rastgele>.trycloudflare.com adresi verilir, Durum ekranında görünür. " +
                "Adres her başlatmada değişir; deneme ve geçici paylaşım içindir."),
        ) { vm.setCloudflareTunnel(CloudflareTunnel.QUICK) }
        RadioRow(
            cf.tunnel == CloudflareTunnel.NAMED, tr("Kendi alan adınızla"),
            tr("Kalıcı adres (örn. https://api.alanadiniz.com). Ücretsiz Cloudflare hesabı ve Cloudflare'e eklenmiş bir alan adı gerekir."),
        ) { vm.setCloudflareTunnel(CloudflareTunnel.NAMED) }

        if (cf.tunnel == CloudflareTunnel.QUICK) {
            WarningText(tr("Hızlı tünelde Cloudflare'in sınırları geçerlidir (eşzamanlı istek sınırı, garanti yok); kalıcı kullanım için kendi alan adınızı bağlayın."))
        } else {
            Steps(
                tr("dash.cloudflare.com > Zero Trust > Networks > Tunnels > \"Create a tunnel\" > Cloudflared."),
                tr("Tünele ad verin; gösterilen kurulum komutundaki uzun token'ı (eyJ… ile başlar) kopyalayın."),
                tr("\"Public hostname\" ekleyin: alan adınızı seçin, Service: HTTP, URL: localhost:{0}.", draft.server.port),
                tr("Token'ı aşağıya yapıştırın; adresi de yazarsanız Durum ekranında gösterilir."),
            )
            val tokenError = vm.errorAt(ACCESS_TOKEN_PATH)
            if (draft.accessToken(AccessMode.CLOUDFLARE) != null && tokenError == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("✓ Token kayıtlı (şifreli depoda)"), Modifier.weight(1f))
                    TextButton(onClick = vm::clearAccessToken) { Text(tr("Değiştir")) }
                }
            } else {
                var tokenText by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = tokenText, onValueChange = { tokenText = it },
                    label = { Text(tr("Tünel token'ı")) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = tokenError != null,
                    supportingText = tokenError?.let { { Text(it) } },
                )
                Button(onClick = { vm.setAccessToken(tokenText); tokenText = "" }, enabled = tokenText.isNotBlank()) {
                    Text(tr("Token'ı kaydet"))
                }
            }
            var host by rememberSaveable { mutableStateOf(cf.hostname.orEmpty()) }
            val hostError = vm.errorAt("access.cloudflare.hostname")
            OutlinedTextField(
                value = host, onValueChange = { host = it; vm.setCloudflareHostname(it) },
                label = { Text(tr("Adres (isteğe bağlı)")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                isError = hostError != null, supportingText = { Text(hostError ?: tr("Örn. api.alanadiniz.com")) },
            )
        }
    }
    SectionCard(tr("Bilmeniz gerekenler")) {
        Steps(
            tr("Trafik Cloudflare üzerinden geçer; HTTPS Cloudflare'de sonlanır (Cloudflare istekleri görebilir)."),
            tr("Telefon uykuya geçerse tünel kopabilir; Durum ekranından pil optimizasyonu muafiyetini verin."),
            tr("Sunucu portu ({0}) internete açılmaz; tünel telefonun içinden bağlanır.", draft.server.port),
        )
    }
}

@Composable
private fun TailscaleSection(g: AppGraph, port: Int) {
    val ctx = LocalContext.current
    val installed = remember { ctx.packageManager.getLaunchIntentForPackage(AccessMode.TAILSCALE_PACKAGE) != null }
    var tailnetIps by remember { mutableStateOf(tailnetAddresses(g)) }
    SectionCard(tr("Tailscale uygulaması")) {
        if (installed) {
            Text(tr("✓ Tailscale kurulu."), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { openTailscale(ctx) }) { Text(tr("Tailscale'i aç")) }
        } else {
            Text(tr("Tailscale bu telefonda kurulu değil."), style = MaterialTheme.typography.bodySmall)
            Button(onClick = { openStore(ctx) }) { Text(tr("Play Store'dan kur")) }
        }
        Steps(
            tr("Tailscale'i açın ve bir hesapla (Google, Microsoft, GitHub…) giriş yapın; VPN iznini onaylayın."),
            tr("Erişecek bilgisayar veya telefona da Tailscale'i kurup aynı hesapla giriş yapın."),
            tr("O cihazdan http://<bu telefonun Tailscale adresi>:{0} ile erişin. Bağlantı Tailscale tarafından şifrelenir.", port),
        )
        if (tailnetIps.isNotEmpty()) {
            Text(tr("Bu telefonun Tailscale adresi:"), style = MaterialTheme.typography.labelLarge)
            tailnetIps.forEach { Text("http://$it:$port", fontFamily = FontFamily.Monospace) }
        } else {
            Text(tr("Tailscale bağlı görünmüyor."), style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { tailnetIps = tailnetAddresses(g) }) { Text(tr("Yeniden kontrol et")) }
        WarningText(tr("Android aynı anda tek VPN'e izin verir: başka bir VPN açılırsa Tailscale kapanır ve erişim kesilir."))
        WarningText(tr("Tailscale açık kalmalı; telefon uykudayken de bağlı kalması için Tailscale'i pil optimizasyonundan muaf tutun."))
    }
}

/** Telefonun Tailscale adresleri (100.64.0.0/10 veya fd7a:115c:a1e0::/48). */
internal fun tailnetAddresses(g: AppGraph): List<String> = runCatching {
    g.network.interfaces().filter { it.kind == NetInterface.Kind.VPN || it.name.startsWith("tun") }.flatMap { it.addresses }
        .filter { a -> AddressScope.of(a) == AddressScope.SHARED || a.hostAddress.orEmpty().startsWith("fd7a:115c:a1e0") }
        .map { a -> a.hostAddress.orEmpty().substringBefore('%').let { if (':' in it) "[$it]" else it } }
}.getOrDefault(emptyList())

private fun openTailscale(ctx: Context) {
    ctx.packageManager.getLaunchIntentForPackage(AccessMode.TAILSCALE_PACKAGE)?.let(ctx::startActivity)
}

private fun openStore(ctx: Context) {
    val pkg = AccessMode.TAILSCALE_PACKAGE
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, "market://details?id=$pkg".toUri()))
    } catch (_: ActivityNotFoundException) {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=$pkg".toUri()))
    }
}

/** İnternete açık modda kimlik doğrulama yoksa: kullanıcı ya güvenlik adımında seçer ya da bilerek onaylar. */
@Composable
private fun NoAuthConsent(vm: WizardViewModel, draft: ServerConfig) {
    if (!draft.access.mode.public || draft.auth.mode != AuthMode.NONE) return
    HorizontalDivider()
    ErrorText(tr("Sunucu internete açılacak ama kimlik doğrulama kapalı: adresi bulan herkes verileri okuyup silebilir."))
    Row {
        Button(onClick = { vm.goTo(WizardStep.SECURITY) }) { Text(tr("Kimlik doğrulama seç (önerilir)")) }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = draft.access.allowNoAuth, onCheckedChange = vm::setAllowNoAuth)
        Text(tr("Riski biliyorum, kimlik doğrulamasız açılsın"), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun Steps(vararg lines: String) {
    Column {
        lines.forEachIndexed { i, s -> Text("${i + 1}. $s", style = MaterialTheme.typography.bodySmall) }
    }
}

/** Bileşeni depodan indirip kurar (Bileşenler sekmesine gitmeden). */
@Composable
internal fun InstallComponentButton(g: AppGraph, id: String, label: String, onInstalled: () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    Button(enabled = !busy, onClick = {
        scope.launch {
            busy = true
            failure = withContext(Dispatchers.IO) {
                runCatching {
                    val url = g.settings.manifestUrl
                    require(url.isNotBlank()) { tr("Önce Bileşenler sekmesinde bileşen deposu adresini girin") }
                    val pkg = g.components.fetchManifest(url).packages.firstOrNull { it.id == id }
                        ?: error(tr("Bileşen deposunda '{0}' paketi yok", id))
                    g.components.install(pkg)
                }.exceptionOrNull()?.message
            }
            busy = false
            onInstalled()
        }
    }) { Text(if (busy) tr("Kuruluyor…") else label) }
    ErrorText(failure)
}
