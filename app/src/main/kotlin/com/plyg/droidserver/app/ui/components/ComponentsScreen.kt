package com.plyg.droidserver.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.ui.text.style.TextOverflow
import com.plyg.droidserver.app.ui.common.EmptyState
import com.plyg.droidserver.app.ui.common.Pill
import com.plyg.droidserver.app.ui.common.SoftCard
import com.plyg.droidserver.app.ui.theme.LocalStatusColors
import com.plyg.droidserver.core.process.ProcessSupervisor
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.plyg.droidserver.app.AppGraph
import com.plyg.droidserver.app.ui.common.ErrorText
import com.plyg.droidserver.app.ui.common.ScreenScaffold
import com.plyg.droidserver.app.ui.common.saveConfigChange
import com.plyg.droidserver.app.ui.wizard.WizardViewModel
import com.plyg.droidserver.core.components.ComponentPackage
import com.plyg.droidserver.core.config.AccessMode
import com.plyg.droidserver.core.config.ComponentConfig
import com.plyg.droidserver.core.config.ConfigDefaults
import com.plyg.droidserver.core.config.ConfigSecrets
import com.plyg.droidserver.core.config.EnvFile
import com.plyg.droidserver.core.config.PostgresDefaults
import com.plyg.droidserver.core.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Bileşen kataloğu: depodaki paketler (sha256 doğrulamalı) kurulur, güncellenir, kaldırılır. Servisler (Redis, Caddy …)
 * buradan tek dokunuşla çalıştırılır (konfigürasyona eklenip deploy edilir); çalışma ortamları (Node.js, Python, Java)
 * yalnızca kurulur, Uygulamalar ekranındaki uygulamalar kullanır. Postgres ve tünel sihirbazdan yönetilir.
 */
@Composable
fun ComponentsScreen(g: AppGraph, wizard: WizardViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(g.settings.manifestUrl) }
    var packages by remember { mutableStateOf<List<ComponentPackage>>(emptyList()) }
    var installed by remember { mutableStateOf(g.components.installed()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val statuses by g.supervisor.statuses.collectAsState()
    val active by wizard.active.collectAsState()
    val configured = active?.components.orEmpty().associateBy { it.id }

    fun io(block: () -> Unit) = scope.launch {
        busy = true
        error = null
        withContext(Dispatchers.IO) {
            runCatching(block).onFailure {
                error = it.message
                g.logs.add("Bileşen hatası: ${it.message}")
            }
        }
        installed = g.components.installed()
        busy = false
    }

    fun setService(id: String, change: (ComponentConfig?) -> ComponentConfig?) = scope.launch {
        val errors = saveConfigChange(ctx, g, wizard, deploy = true, scope = listOf("components")) { c ->
            val current = c.components.firstOrNull { it.id == id }
            val next = change(current)
            c.copy(components = if (next == null) c.components.filter { it.id != id } else if (current == null) c.components + next else c.components.map { if (it.id == id) next else it })
        }
        error = errors.firstOrNull()
    }

    /**
     * Servisi sunucuyla birlikte çalıştırır: paketin istediği sırlar (örn. REDIS_PASSWORD) ortak ortamda yoksa üretilip
     * şifreli depoya eklenir; bileşen ortak ortamı kullanır.
     */
    fun enable(id: String, pkg: ComponentPackage?) = scope.launch {
        val shared = active?.env.orEmpty()
        val generated = withContext(Dispatchers.IO) {
            pkg?.secrets.orEmpty().filter { it !in shared }
                .associateWith { ConfigSecrets.ref(g.secrets.store(EnvFile.SECRET_KIND, ConfigDefaults.generateDbPassword())) }
        }
        val errors = saveConfigChange(ctx, g, wizard, deploy = true, scope = listOf("components", "env")) { c ->
            val cur = c.components.firstOrNull { it.id == id }
            val next = (cur ?: ComponentConfig(id, sharedEnv = true)).let { it.copy(enabled = true, sharedEnv = it.sharedEnv || generated.isNotEmpty()) }
            c.copy(
                env = c.env + generated.filterKeys { it !in c.env },
                components = if (cur == null) c.components + next else c.components.map { if (it.id == id) next else it },
            )
        }
        error = errors.firstOrNull()
        if (errors.isEmpty() && generated.isNotEmpty()) {
            notice = tr("{0} üretildi ve şifreli depoya kaydedildi; uygulamalarınız ortak ortamdan okur (Sunucu → Ortam ve sırlar).", generated.keys.joinToString())
        }
    }

    LaunchedEffect(Unit) {
        if (url.isNotBlank() && packages.isEmpty()) {
            runCatching { withContext(Dispatchers.IO) { g.components.fetchManifest(url).packages } }
                .onSuccess { packages = it }
                .onFailure { error = tr("Depo yüklenemedi: {0}", it.message) }
        }
    }

    /** Sihirbazın yönettiği bileşenler (veritabanı, tünel); burada açılıp kapatılmaz. */
    val managed = setOf(PostgresDefaults.COMPONENT_ID, AccessMode.CLOUDFLARE.componentId)

    var filter by remember { mutableStateOf(0) }
    var showRepo by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(packages, error) { if (packages.isNotEmpty() || error != null) loading = false }

    ScreenScaffold(
        tr("Bileşenler"),
        subtitle = tr("{0} kurulu", installed.size),
        actions = {
            IconButton(enabled = !busy && url.isNotBlank(), onClick = { io { packages = g.components.fetchManifest(url).packages } }) {
                Icon(Icons.Default.Refresh, contentDescription = tr("Depoyu yenile"))
            }
            IconButton(onClick = { showRepo = !showRepo }) { Icon(Icons.Default.Tune, contentDescription = tr("Bileşen deposu")) }
        },
    ) { modifier ->
        val all = (packages.map { it.id } + installed.keys).distinct()
            .mapNotNull { id -> (packages.firstOrNull { it.id == id } ?: installed[id]?.pkg)?.let { id to it } }
            .sortedWith(compareBy({ it.second.isRuntime }, { it.first }))
        val shown = all.filter { (id, p) ->
            when (filter) {
                1 -> !p.isRuntime
                2 -> p.isRuntime
                3 -> id in installed
                else -> true
            }
        }
        LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                AnimatedVisibility(showRepo) {
                    SoftCard {
                        OutlinedTextField(
                            value = url, onValueChange = { url = it; g.settings.manifestUrl = it },
                            label = { Text(tr("Bileşen deposu (components.json adresi)")) },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            tr("Paketler indirilince sha256 ile doğrulanır. Servisler (örn. Redis) \"Çalıştır\" ile sunucuyla birlikte başlar; çalışma ortamları (Node.js, Python, Java) Uygulamalar ekranındaki uygulamalar içindir. PostgreSQL sihirbazın Veritabanı, tünel Erişim adımından yönetilir."),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(enabled = !busy && url.isNotBlank(), onClick = { io { packages = g.components.fetchManifest(url).packages } }) { Text(tr("Depoyu yenile")) }
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(tr("Tümü"), tr("Servisler"), tr("Çalışma ortamları"), tr("Kurulu")).forEachIndexed { i, label ->
                        FilterChip(selected = filter == i, onClick = { filter = i }, label = { Text(label) })
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                ErrorText(error)
                notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            if (shown.isEmpty()) item {
                EmptyState(
                    Icons.Default.Extension,
                    when {
                        loading && error == null -> tr("Depo yükleniyor…")
                        all.isEmpty() -> tr("Paket bulunamadı")
                        else -> tr("Bu süzgeçte paket yok")
                    },
                    when {
                        loading && error == null -> tr("Paket listesi bileşen deposundan alınıyor.")
                        all.isEmpty() -> tr("İnternet bağlantısını ve bileşen deposu adresini denetleyin.")
                        else -> tr("Başka bir süzgeç seçin.")
                    },
                    action = if (all.isEmpty() && !loading) tr("Yeniden dene") else null,
                    onAction = { io { packages = g.components.fetchManifest(url).packages } },
                )
            }
            items(shown, key = { it.first }) { (id, pkg) ->
                val inst = installed[id]
                val cfg = configured[id]
                val phase = statuses[id]?.phase
                SoftCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = MaterialTheme.shapes.small, color = if (pkg.isRuntime) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(44.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    if (pkg.isRuntime) Icons.Default.Code else Icons.Default.Storage, contentDescription = null,
                                    tint = if (pkg.isRuntime) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(id, style = MaterialTheme.typography.titleMedium)
                            Text(
                                (if (pkg.isRuntime) tr("Çalışma ortamı") else tr("Servis")) + " · " + (inst?.version ?: pkg.version),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        val status = LocalStatusColors.current
                        when {
                            phase == ProcessSupervisor.Phase.RUNNING -> Pill(tr("Çalışıyor"), status.successContainer, status.onSuccessContainer)
                            phase == ProcessSupervisor.Phase.FAILED -> Pill(tr("Hata"), MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                            inst != null && g.components.updateAvailable(pkg) && packages.any { it.id == id } -> Pill(tr("Güncelleme var"), status.warningContainer, status.onWarningContainer)
                            inst != null -> Pill(tr("Kurulu"))
                        }
                    }
                    pkg.description.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    if (inst != null && !pkg.isRuntime && id !in managed) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tr("Sunucuyla birlikte çalıştır"), Modifier.weight(1f))
                            Switch(cfg?.enabled == true, enabled = !busy, onCheckedChange = { on -> if (on) enable(id, pkg) else setService(id) { null } })
                        }
                        if (cfg?.enabled == true) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(tr("Ortak ortam değişkenlerini ve sırları ver"), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                Switch(cfg.sharedEnv, enabled = !busy, onCheckedChange = { on -> setService(id) { cur -> cur?.copy(sharedEnv = on) } })
                            }
                        }
                    }
                    if (id in managed && inst != null) {
                        Text(tr("Sihirbazdan yönetilir"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (inst == null && !pkg.isRuntime && id !in managed && pkg.builds.isNotEmpty()) {
                            Button(enabled = !busy, onClick = {
                                scope.launch {
                                    busy = true
                                    val ok = withContext(Dispatchers.IO) { runCatching { g.components.install(pkg) }.onFailure { error = it.message }.isSuccess }
                                    installed = g.components.installed()
                                    busy = false
                                    if (ok) enable(id, pkg)
                                }
                            }) { Text(tr("Kur ve çalıştır")) }
                        }
                        if (packages.any { it.id == id } && (inst == null || g.components.updateAvailable(pkg))) {
                            val primary = inst == null && (pkg.isRuntime || id in managed)
                            val onClick = { io { g.supervisor.stop(id); g.components.install(pkg) } }
                            if (primary) Button(enabled = !busy, onClick = { onClick() }) { Text(tr("Kur")) }
                            else OutlinedButton(enabled = !busy, onClick = { onClick() }) { Text(if (inst == null) tr("Yalnızca kur") else tr("Güncelle")) }
                        }
                        if (inst != null) {
                            TextButton(enabled = !busy, onClick = { io { g.supervisor.stop(id); g.components.uninstall(id) } }) { Text(tr("Kaldır (veriyi koru)")) }
                        }
                    }
                }
            }
        }
    }
}
