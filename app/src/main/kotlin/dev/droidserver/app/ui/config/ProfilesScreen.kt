package dev.droidserver.app.ui.config

import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.droidserver.app.AppGraph
import dev.droidserver.app.DroidServerApp
import dev.droidserver.app.data.ConfigRepository
import dev.droidserver.app.data.PendingTemplate
import dev.droidserver.app.data.Profile
import dev.droidserver.app.data.TemplateRequest
import dev.droidserver.app.service.ServerService
import dev.droidserver.app.ui.MainActivity
import dev.droidserver.app.ui.common.ErrorText
import dev.droidserver.app.ui.common.ScreenScaffold
import dev.droidserver.core.i18n.tr
import dev.droidserver.core.server.EngineState
import dev.droidserver.core.templates.AppTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Profilin özeti (listede): sunucu adı ve portu; henüz kurulmadıysa null. */
private data class ProfileInfo(val profile: Profile, val summary: String?)

/**
 * Sunucu profilleri: aynı telefonda bağımsız sunucular (ayrı konfigürasyon, veritabanı, dosyalar). Aynı anda biri
 * çalışır; geçişte çalışan sunucu durdurulur, seçilen profil çalışıyorsa onun sunucusu başlatılır.
 */
@Composable
fun ProfilesScreen(g: AppGraph, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as DroidServerApp
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var infos by remember { mutableStateOf<List<ProfileInfo>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Profile?>(null) }
    var deleting by remember { mutableStateOf<Profile?>(null) }

    LaunchedEffect(refresh) {
        infos = withContext(Dispatchers.IO) {
            app.profiles.list().map { p ->
                val cfg = runCatching { ConfigRepository(ctx, g.secrets, app.profiles.root(p.id)).loadActiveOrNull() }.getOrNull()
                ProfileInfo(p, cfg?.let { tr("{0} · port {1} · {2} koleksiyon", it.name, it.server.port, it.collections.size) })
            }
        }
    }

    fun switchTo(p: Profile) = scope.launch {
        busy = true
        error = null
        runCatching {
            val wasRunning = g.engine.state.value !is EngineState.Stopped
            if (wasRunning) {
                ServerService.stop(ctx)
                withTimeoutOrNull(20_000) { g.engine.state.first { it is EngineState.Stopped } }
            }
            withContext(Dispatchers.IO) { app.switchProfile(p.id) }
            if (wasRunning && app.graph.configRepo.hasConfig()) ServerService.start(ctx)
            // Ekranlar (ve sihirbazın taslağı) yeni profille baştan kurulsun.
            (ctx as? Activity)?.let { a ->
                a.startActivity(Intent(a, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
                a.finish()
            }
        }.onFailure { error = it.message ?: it.toString() }
        busy = false
    }

    ScreenScaffold(tr("Sunucu profilleri"), onBack = onBack) { modifier ->
        LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text(
                    tr("Her profil ayrı bir sunucudur: kendi konfigürasyonu, portu, veritabanı, dosyaları, script'leri ve yedekleri. " +
                        "Aynı anda biri çalışır; geçince çalışan sunucu durur. Yüklü bileşenler (PostgreSQL, cloudflared) ortaktır: " +
                        "PostgreSQL kullanan profillere farklı veritabanı adı verin."),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            items(infos, key = { it.profile.id }) { info ->
                val p = info.profile
                val active = p.id == g.profile.id
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(p.name + if (active) tr(" (etkin)") else "", style = MaterialTheme.typography.titleSmall)
                        Text(info.summary ?: tr("Henüz kurulmadı (geçince sihirbaz açılır)"), style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!active) Button(enabled = !busy, onClick = { switchTo(p) }) { Text(tr("Geç")) }
                            if (!p.isDefault) {
                                TextButton(enabled = !busy, onClick = { renaming = p }) { Text(tr("Yeniden adlandır")) }
                                if (!active) TextButton(enabled = !busy, onClick = { deleting = p }) { Text(tr("Sil")) }
                            }
                        }
                    }
                }
            }
            item {
                OutlinedButton(enabled = !busy, onClick = { creating = true }) { Text(tr("Yeni profil")) }
                ErrorText(error)
            }
        }
    }

    if (creating || renaming != null) {
        var name by remember { mutableStateOf(renaming?.name.orEmpty()) }
        var template by remember { mutableStateOf<AppTemplate?>(null) }
        AlertDialog(
            onDismissRequest = { creating = false; renaming = null },
            title = { Text(if (creating) tr("Yeni profil") else tr("Yeniden adlandır")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, label = { Text(tr("Ad (örn. Test, Müşteri A)")) }, singleLine = true)
                    if (creating) {
                        Text(tr("Başlangıç"), style = MaterialTheme.typography.labelLarge)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(selected = template == null, onClick = { template = null }, label = { Text(tr("Boş")) })
                            AppTemplate.entries.forEach { t ->
                                FilterChip(selected = template == t, onClick = { template = t; if (name.isBlank()) name = t.title }, label = { Text(t.title) })
                            }
                        }
                        template?.let {
                            Text(
                                it.description + tr(" Profile geçip sihirbazı açınca koleksiyonlar eklenir; deploy'dan sonra örnek veri ve web sitesi kurulur."),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(enabled = name.isNotBlank(), onClick = {
                    val target = renaming
                    if (target != null) app.profiles.rename(target.id, name)
                    else app.profiles.create(name).also { p ->
                        template?.let { t -> PendingTemplate(app.profiles.root(p.id)).set(TemplateRequest(t.id, applyDraft = true)) }
                    }
                    creating = false
                    renaming = null
                    refresh++
                }) { Text(tr("Kaydet")) }
            },
            dismissButton = { TextButton(onClick = { creating = false; renaming = null }) { Text(tr("Vazgeç")) } },
        )
    }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(tr("\"{0}\" silinsin mi?", p.name)) },
            text = { Text(tr("Profilin konfigürasyonu, SQLite veritabanı, yüklenen dosyaları, script'leri ve yedekleri kalıcı olarak silinir. Önce yedek alıp dışa aktarabilirsiniz.")) },
            confirmButton = {
                Button(onClick = {
                    deleting = null
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { app.profiles.delete(p.id) } }.onFailure { error = it.message }
                        refresh++
                    }
                }) { Text(tr("Sil")) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(tr("Vazgeç")) } },
        )
    }
}
