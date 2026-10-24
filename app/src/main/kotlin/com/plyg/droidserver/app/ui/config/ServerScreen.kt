package com.plyg.droidserver.app.ui.config

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Api
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material.icons.filled.DynamicForm
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material.icons.filled.Javascript
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Web
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SwitchAccount
import androidx.compose.material.icons.filled.Webhook
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.plyg.droidserver.app.AppGraph
import com.plyg.droidserver.app.ui.Routes
import com.plyg.droidserver.app.ui.common.ConfigUpgradeCard
import com.plyg.droidserver.app.ui.common.EmptyState
import com.plyg.droidserver.app.ui.common.ListRow
import com.plyg.droidserver.app.ui.common.Pill
import com.plyg.droidserver.app.ui.common.RowGroup
import com.plyg.droidserver.app.ui.common.ScreenScaffold
import com.plyg.droidserver.app.ui.common.SectionHeader
import com.plyg.droidserver.app.ui.common.SoftCard
import com.plyg.droidserver.app.ui.common.SummaryTable
import com.plyg.droidserver.app.ui.wizard.WizardViewModel
import com.plyg.droidserver.core.config.FieldType
import com.plyg.droidserver.core.config.AccessMode
import com.plyg.droidserver.core.config.AuthMode
import com.plyg.droidserver.core.config.ConfigSummary
import com.plyg.droidserver.core.config.DbEngine
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.i18n.tr

/** Sunucu ekranındaki bir araç: grup, simge, başlık, kısa açıklama ve açılınca yapılacak iş. */
private data class Tool(val group: String, val icon: ImageVector, val title: String, val subtitle: String, val open: () -> Unit)

/**
 * Etkin konfigürasyonun özeti ve araçlar. Araçlar gruplara ayrılır (Tasarım, Veri, Geliştirici, Güvenlik ve işletim);
 * üstteki arama başlık ve açıklamada arar. Ayrıntılı özet katlanabilir.
 */
@Composable
fun ServerScreen(
    g: AppGraph,
    vm: WizardViewModel,
    onWizard: () -> Unit,
    onJson: () -> Unit,
    onHistory: () -> Unit,
    onApi: () -> Unit,
    onUsers: () -> Unit,
    onScripts: () -> Unit,
    onBackups: () -> Unit,
    onWebhooks: () -> Unit,
    onTransfer: () -> Unit,
    onProfiles: () -> Unit,
    /** Diğer ekranlar (rota adıyla; bkz. Routes). */
    onOpen: (String) -> Unit,
) {
    val active by vm.active.collectAsState()
    val draft by vm.draft.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var details by rememberSaveable { mutableStateOf(false) }

    val design = tr("Tasarım")
    val data = tr("Veri")
    val dev = tr("Geliştirici")
    val ops = tr("Güvenlik ve işletim")
    val cfg = active
    val tools = buildList {
        add(Tool(design, Icons.Default.Code, tr("Gelişmiş (JSON)"), tr("Konfigürasyonu metin olarak düzenle"), onJson))
        add(Tool(design, Icons.Default.History, tr("Geçmiş"), tr("Önceki konfigürasyonlar; karşılaştır ve geri dön"), onHistory))
        add(Tool(design, Icons.Default.SwitchAccount, tr("Profiller ({0})", g.profile.name), tr("Aynı telefonda birden çok sunucu"), onProfiles))
        if (cfg?.auth?.users?.enabled == true) add(Tool(data, Icons.Default.People, tr("Kullanıcılar"), tr("Hesaplar, parola sıfırlama, roller"), onUsers))
        if (cfg?.auth?.users?.let { it.enabled && it.teams.enabled } == true) add(Tool(data, Icons.Default.Groups, tr("Takımlar"), tr("Takımlar ve üyeleri"), { onOpen(Routes.TEAMS) }))
        add(Tool(data, Icons.Default.Backup, tr("Yedekler"), tr("Yedek al, geri yükle, bulut ve klasör kopyası"), onBackups))
        if (cfg?.collections?.isNotEmpty() == true) add(Tool(data, Icons.Default.ImportExport, tr("İçe / dışa aktarma"), tr("CSV (Excel) ve JSON"), onTransfer))
        if (cfg != null) add(Tool(data, Icons.Default.AutoMode, tr("Otomasyonlar ({0})", cfg.automations.size), tr("Kodsuz kurallar: e-posta, bildirim, kayıt güncelleme"), { onOpen(Routes.AUTOMATIONS) }))
        if (cfg != null) add(Tool(data, Icons.Default.DynamicForm, tr("Formlar ({0})", cfg.forms.size), tr("Herkese açık iletişim, başvuru, anket formları"), { onOpen(Routes.FORMS) }))
        if (cfg?.collections?.isNotEmpty() == true) add(Tool(data, Icons.Default.Web, tr("Sayfalar ({0})", cfg.pages.size), tr("Kodsuz liste ve detay sayfaları"), { onOpen(Routes.PAGES) }))
        if (cfg != null) add(Tool(data, Icons.Default.Dashboard, tr("Panolar ({0})", cfg.dashboards.size), tr("Görünümlerden sayı, grafik, tablo, harita"), { onOpen(Routes.DASHBOARDS) }))
        if (cfg != null) add(Tool(data, Icons.Default.Extension, tr("Paketler ({0})", cfg.packages.size), tr("Hazır uygulama ve eklenti paketleri: dosyadan, adresten, Git'ten"), { onOpen(Routes.PACKAGES) }))
        if (cfg != null) add(Tool(data, Icons.Default.Payments, tr("Ödemeler"), cfg.payments.providers.filter { it.enabled }.joinToString { it.provider.title }.ifEmpty { tr("Stripe, iyzico, Paddle: ödeme bağlantısı, abonelik, makbuz") }, { onOpen(Routes.PAYMENTS) }))
        if (cfg != null) add(Tool(data, Icons.Default.AutoAwesome, tr("Yapay zekâ"), if (cfg.ai.enabled) tr("{0} açık · sohbet, kayıtlara soru, anlamsal arama", cfg.ai.provider.title) else tr("Claude, OpenAI veya Gemini: sohbet, kayıtlara soru, anlamsal arama"), { onOpen(Routes.AI) }))
        if (cfg != null) add(Tool(data, Icons.Default.FolderShared, tr("Ağ sürücüsü"), if (cfg.webdav.enabled) tr("Açık · bilgisayarda klasör olarak bağla (WebDAV)") else tr("Telefondaki klasörleri bilgisayarda sürücü gibi aç (WebDAV)"), { onOpen(Routes.DRIVE) }))
        if (cfg != null) add(Tool(data, Icons.Default.Sensors, tr("MQTT (IoT)"), if (cfg.mqtt.enabled) tr("Açık · port {0}", cfg.mqtt.port) else tr("Sensörler ve ESP32: mesajları kayda yaz, komut gönder"), { onOpen(Routes.MQTT) }))
        if (cfg != null) add(Tool(data, Icons.Default.ShowChart, tr("Sensör panosu"), if (cfg.sensors.isEmpty()) tr("Sayısal alanların grafiği, eşik uyarısı, Home Assistant") else tr("{0} sensör", cfg.sensors.size), { onOpen(Routes.SENSORS) }))
        if (cfg?.collections?.any { c -> c.fields.any { it.type == FieldType.GEO } } == true) add(Tool(data, Icons.Default.Map, tr("Harita"), tr("Konumlu kayıtlar haritada"), { onOpen(Routes.MAP) }))
        if (cfg != null) add(Tool(data, Icons.Default.Api, tr("API gezgini"), tr("Uçları dene, örnek kodlar"), onApi))
        add(Tool(dev, Icons.Default.Terminal, tr("Komut satırı (CLI)"), tr("Bilgisayardan kayıtlar, loglar, deploy, yedek"), { onOpen(Routes.CLI) }))
        add(Tool(dev, Icons.Default.RocketLaunch, tr("Uygulamalar ({0})", cfg?.apps?.size ?: 0), tr("Kendi Node.js, Python, Java sunucunuz"), { onOpen(Routes.APPS) }))
        add(Tool(dev, Icons.Default.Key, tr("Ortam ve sırlar (.env)"), tr("Ortak ortam değişkenleri, şifreli sırlar"), { onOpen(Routes.ENV) }))
        add(Tool(dev, Icons.Default.Javascript, tr("Script'ler"), tr("Özel uçlar, kayıt olayları, zamanlanmış görevler"), onScripts))
        if (cfg?.scripts?.enabled == true) add(Tool(dev, Icons.Default.Schedule, tr("Arka plan işleri"), tr("Kuyruktaki işler, yeniden deneme"), { onOpen(Routes.JOBS) }))
        if (cfg?.webhooks?.isNotEmpty() == true) add(Tool(dev, Icons.Default.Webhook, tr("Webhook'lar"), tr("Teslimler ve deneme"), onWebhooks))
        add(Tool(ops, Icons.Default.Shield, tr("Güvenlik duvarı"), tr("Engellenen adresler, izin listesi"), { onOpen(Routes.FIREWALL) }))
        add(Tool(ops, Icons.Default.NotificationsActive, tr("Sağlık uyarıları"), tr("Etkin uyarılar, Telegram ve e-posta"), { onOpen(Routes.ALERTS) }))
        add(Tool(ops, Icons.Default.Policy, tr("Denetim kaydı"), tr("Kim, ne zaman, neyi değiştirdi"), { onOpen(Routes.AUDIT) }))
    }
    val q = query.trim().lowercase()
    val shown = if (q.isEmpty()) tools else tools.filter { q in it.title.lowercase() || q in it.subtitle.lowercase() }

    ScreenScaffold(tr("Sunucu"), subtitle = cfg?.name) { modifier ->
        Column(
            modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (draft != active) {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(tr("Kaydedilmemiş değişiklikler"), style = MaterialTheme.typography.titleMedium)
                        Text(tr("Sihirbazda veya JSON'da yapılan değişiklikler henüz deploy edilmedi."), style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onWizard) { Text(tr("Devam et")) }
                            if (active != null) TextButton(onClick = vm::discard) { Text(tr("Değişiklikleri at")) }
                        }
                    }
                }
            }

            ConfigUpgradeCard(g, vm, dismissible = false)

            if (cfg == null) {
                EmptyState(
                    Icons.Default.AutoFixHigh, tr("Henüz sunucu tasarlanmadı"),
                    tr("Sihirbaz port, veritabanı, koleksiyonlar ve güvenlik ayarlarını adım adım sorar."),
                    action = tr("Sihirbazı başlat"), onAction = onWizard,
                )
            } else {
                OverviewCard(cfg, details, onToggle = { details = !details }, onWizard = onWizard)
            }

            active?.takeIf { it.static.enabled }?.let { SiteCard(g, it) }

            OutlinedTextField(
                query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text(tr("Araç ara (örn. yedek, script, kullanıcı)")) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                shape = MaterialTheme.shapes.extraLarge,
            )
            if (shown.isEmpty()) Text(tr("'{0}' ile eşleşen araç yok.", query), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            shown.groupBy { it.group }.forEach { (group, items) ->
                SectionHeader(group)
                RowGroup {
                    items.forEach { t -> ListRow(t.icon, t.title, subtitle = t.subtitle, onClick = t.open) }
                }
            }
        }
    }
}

/** Konfigürasyonun kısa özeti (etiketler) ve isteğe bağlı ayrıntılı tablo. */
@Composable
private fun OverviewCard(cfg: ServerConfig, expanded: Boolean, onToggle: () -> Unit, onWizard: () -> Unit) {
    SoftCard {
        Text(tr("Etkin konfigürasyon"), style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill(":" + cfg.server.port)
            Pill(if (cfg.database.engine == DbEngine.POSTGRES) "PostgreSQL" else "SQLite")
            Pill(tr("{0} koleksiyon", cfg.collections.size))
            Pill(
                when (cfg.auth.mode) {
                    AuthMode.NONE -> tr("Kimlik doğrulama yok")
                    AuthMode.API_KEY -> tr("API anahtarı")
                    AuthMode.JWT -> "JWT"
                },
            )
            if (cfg.auth.users.enabled) Pill(tr("Kullanıcı hesapları"))
            Pill(if (cfg.access.mode == AccessMode.LOCAL) tr("Yalnızca yerel ağ") else cfg.access.mode.title)
            if (cfg.apps.isNotEmpty()) Pill(tr("{0} uygulama", cfg.apps.size))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onWizard) {
                Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(tr("Sihirbazla düzenle"))
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onToggle) {
                Text(if (expanded) tr("Daha az") else tr("Ayrıntılar"))
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
            }
        }
        AnimatedVisibility(expanded) { SummaryTable(ConfigSummary.lines(cfg)) }
    }
}
