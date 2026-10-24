package com.plyg.droidserver.app.ui.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.plyg.droidserver.app.AppGraph
import com.plyg.droidserver.app.data.NetUtil
import com.plyg.droidserver.app.ui.common.ScreenScaffold
import com.plyg.droidserver.app.ui.common.SectionCard
import com.plyg.droidserver.app.ui.common.WarningText
import com.plyg.droidserver.app.ui.common.copyToClipboard
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.i18n.tr
import com.plyg.droidserver.core.server.EngineState

/** Bilgisayardan komut satırı aracı (`/_cli`): indirme ve bağlanma komutları, komut listesi. */
@Composable
fun CliScreen(g: AppGraph, config: ServerConfig?, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val state by g.engine.state.collectAsState()
    val running = state as? EngineState.Running
    var copied by remember { mutableStateOf<String?>(null) }
    val api = running?.let { "http://${NetUtil.localIpv4().firstOrNull() ?: "127.0.0.1"}:${it.port}${config?.server?.basePath ?: "/api"}" }
    ScreenScaffold(tr("Komut satırı (CLI)"), subtitle = tr("Bilgisayardan yönetim"), onBack = onBack) { modifier ->
        Column(modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                tr("Sunucuyu bilgisayarınızdan komutlarla yönetin: kayıtları listeleyin ve düzenleyin, dışa/içe aktarın, logları izleyin, " +
                    "konfigürasyonu deploy edin, yedek alın. Tek dosyadır, Node.js 18+ ister (Windows, macOS, Linux)."),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (api == null) WarningText(tr("Komutlar için sunucuyu başlatın."))
            api?.let { url ->
                SectionCard(tr("Kurulum")) {
                    listOf(
                        tr("1. İndir") to "curl -o droidserver.mjs $url/_cli",
                        tr("2. Bağlan (yönetici anahtarı sorulur)") to "node droidserver.mjs login $url",
                        tr("3. Dene") to "node droidserver.mjs status",
                    ).forEach { (title, cmd) ->
                        Text(title, style = MaterialTheme.typography.labelLarge)
                        Text(cmd, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { copyToClipboard(ctx, title, cmd); copied = title }) { Text(tr("Kopyala")) }
                    }
                    copied?.let { Text(tr("Kopyalandı: {0}", it), style = MaterialTheme.typography.bodySmall) }
                }
            }
            SectionCard(tr("Komutlar")) {
                Text(
                    tr("list <koleksiyon> [alan=değer] [--sort -id]\nget | create | update | delete\nexport <koleksiyon> [--format json] [-o dosya]\n" +
                        "import <koleksiyon> <dosya>\nlogs -f\nconfig get -o config.json\ndeploy config.json\nbackup create | list | download <ad>\n" +
                        "apps deploy <ad>\nsdk ts -o client.ts\napi GET /_jobs"),
                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    tr("logs, config, deploy ve backup için web paneli açık olmalı (Sihirbaz → Güvenlik). Anahtar bilgisayarda yalnızca sizin " +
                        "okuyabileceğiniz ~/.droidserver/cli.json dosyasında durur; adlandırılmış, iptal edilebilir bir anahtar önerilir. " +
                        "Kullanıcı olarak girmek için: login <adres> --email <e-posta>."),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
