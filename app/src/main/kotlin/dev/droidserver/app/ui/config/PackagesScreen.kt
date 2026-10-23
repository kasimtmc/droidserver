package dev.droidserver.app.ui.config

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.droidserver.app.AppGraph
import dev.droidserver.app.ui.common.EmptyState
import dev.droidserver.app.ui.common.ErrorText
import dev.droidserver.app.ui.common.Pill
import dev.droidserver.app.ui.common.ScreenScaffold
import dev.droidserver.app.ui.common.SectionCard
import dev.droidserver.app.ui.common.SoftCard
import dev.droidserver.app.ui.wizard.WizardViewModel
import dev.droidserver.core.apps.GitFetcher
import dev.droidserver.core.config.ConfigSecrets
import dev.droidserver.core.config.GitProvider
import dev.droidserver.core.config.GitSource
import dev.droidserver.core.i18n.tr
import dev.droidserver.core.packages.InstalledPackage
import dev.droidserver.core.packages.PackageFile
import dev.droidserver.core.packages.PackagePlan
import dev.droidserver.core.packages.PackageSignature
import dev.droidserver.core.packages.Packages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Eklenti ve şablon paketleri: kurulu olanlar, dosyadan / adresten / Git deposundan ekleme, kurulum planı. */
@Composable
fun PackagesScreen(g: AppGraph, wizard: WizardViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val active by wizard.active.collectAsState()
    val installed = active?.packages.orEmpty()
    var url by remember { mutableStateOf("") }
    var repo by remember { mutableStateOf("") }
    var branch by remember { mutableStateOf("main") }
    var loaded by remember { mutableStateOf<Pair<PackageFile, PackagePlan>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<InstalledPackage?>(null) }

    fun load(read: () -> PackageFile) = scope.launch {
        busy = true; error = null; message = null; loaded = null
        try {
            loaded = withContext(Dispatchers.IO) {
                val pkg = read()
                val cfg = ConfigSecrets.resolve(g.configRepo.load(), g.secrets::get)
                pkg to Packages.plan(cfg, pkg, g.engine.scriptsDir?.list()?.toSet().orEmpty())
            }
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        } finally {
            busy = false
        }
    }

    fun fetch(source: GitSource) = load {
        val fetched = GitFetcher(maxBytes = Packages.MAX_BYTES).download(source, ctx.cacheDir)
        try {
            fetched.file.inputStream().use(Packages::read)
        } finally {
            fetched.file.delete()
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) load { (ctx.contentResolver.openInputStream(uri) ?: error(tr("Dosya açılamadı"))).use(Packages::read) }
    }

    ScreenScaffold(tr("Paketler"), subtitle = tr("{0} kurulu", installed.size), onBack = onBack) { modifier ->
        Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (installed.isEmpty() && loaded == null) {
                EmptyState(
                    Icons.Default.Extension, tr("Kurulu paket yok"),
                    tr("Paket, hazır bir uygulamanın koleksiyonlarını, görünümlerini, otomasyonlarını, formlarını, sayfalarını, script'lerini ve örnek verisini tek dosyada getirir. Kurmadan önce ne değişeceği gösterilir."),
                )
            }
            installed.forEach { p ->
                SoftCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Pill(p.version)
                    }
                    Text(listOfNotNull(p.name, p.publisher, if (p.key != null) tr("imzalı") else tr("imzasız")).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    p.key?.let { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall) }
                    val parts = listOf(p.collections to tr("koleksiyon"), p.views to tr("görünüm"), p.automations to tr("otomasyon"), p.forms to tr("form"), p.pages to tr("sayfa"), p.dashboards to tr("pano"), p.scripts to "script")
                    Text(parts.filter { it.first.isNotEmpty() }.joinToString(", ") { "${it.first.size} ${it.second}" }, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { removing = p }) { Text(tr("Kaldır")) }
                }
            }
            SectionCard(tr("Paket ekle")) {
                OutlinedButton(enabled = !busy, onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream")) }, modifier = Modifier.fillMaxWidth()) { Text(tr("Dosyadan (.zip)")) }
                OutlinedTextField(url, { url = it.trim() }, label = { Text(tr("Adres")) }, placeholder = { Text("https://example.com/paket.zip") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedButton(enabled = !busy && url.startsWith("http"), onClick = { fetch(GitSource(GitProvider.URL, url)) }, modifier = Modifier.fillMaxWidth()) { Text(tr("Adresten getir")) }
                OutlinedTextField(repo, { repo = it.trim() }, label = { Text(tr("GitHub deposu")) }, placeholder = { Text("sahip/depo") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(branch, { branch = it.trim() }, label = { Text(tr("Dal")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedButton(enabled = !busy && '/' in repo, onClick = { fetch(GitSource(GitProvider.GITHUB, repo, branch = branch.ifBlank { "main" })) }, modifier = Modifier.fillMaxWidth()) { Text(tr("Depodan getir")) }
                if (busy) Text(tr("Paket okunuyor…"), style = MaterialTheme.typography.bodySmall)
                error?.let { ErrorText(it) }
            }
            loaded?.let { (pkg, plan) -> PlanCard(plan) { scope.launch {
                busy = true
                try {
                    message = withContext(Dispatchers.IO) { g.engine.adminHost?.installPackage(pkg) ?: tr("Sunucu hazır değil") }
                    loaded = null
                } catch (e: Exception) {
                    error = e.message
                } finally {
                    busy = false
                }
            } } }
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        }
    }

    removing?.let { p ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(tr("{0} kaldırılsın mı?", p.title)) },
            text = { Text(tr("Paketin getirdiği tanımlar ve script'ler kaldırılır; koleksiyonların verileri veritabanında kalır.")) },
            confirmButton = { TextButton(onClick = {
                removing = null
                scope.launch {
                    message = try {
                        withContext(Dispatchers.IO) { g.engine.adminHost?.removePackage(p.name) }
                    } catch (e: Exception) {
                        e.message
                    }
                }
            }) { Text(tr("Kaldır")) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(tr("Vazgeç")) } },
        )
    }
}

/** Kurulum planı: imza, eklenecekler, güncellenecekler, kaldırılacaklar, çakışma ve hatalar. */
@Composable
private fun PlanCard(plan: PackagePlan, onInstall: () -> Unit) {
    val m = plan.manifest
    SectionCard("${m.title} ${m.version}") {
        m.description?.let { Text(it) }
        val sig = when (val s = plan.signature) {
            PackageSignature.Unsigned -> tr("İmzasız")
            is PackageSignature.Signed -> tr("İmzalı: {0} ({1})", m.publisher ?: tr("yayımcı belirtilmemiş"), s.fingerprint)
            is PackageSignature.Invalid -> tr("İmza geçersiz: {0}", s.reason)
        }
        Text(sig, style = MaterialTheme.typography.bodySmall, color = if (plan.signature is PackageSignature.Signed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        plan.previous?.let { Text(tr("Kurulu sürüm: {0}", it.version), style = MaterialTheme.typography.bodySmall) }
        @Composable
        fun group(title: String, items: List<String>, error: Boolean = false) {
            if (items.isEmpty()) return
            Text(title, style = MaterialTheme.typography.labelLarge)
            items.forEach { if (error) ErrorText(it) else Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }
        group(tr("Eklenecek"), plan.adds)
        group(tr("Güncellenecek"), plan.updates)
        group(tr("Kaldırılacak"), plan.removes)
        group(tr("Değişiklikler"), plan.changes)
        group(tr("Çakışmalar"), plan.conflicts, error = true)
        group(tr("Hatalar"), plan.errors, error = true)
        Button(enabled = !plan.blocked, onClick = onInstall, modifier = Modifier.fillMaxWidth()) { Text(if (plan.previous != null) tr("Güncelle ve deploy et") else tr("Kur ve deploy et")) }
    }
}
