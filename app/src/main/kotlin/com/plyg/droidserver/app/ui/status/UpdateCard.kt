package com.plyg.droidserver.app.ui.status

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.plyg.droidserver.app.AppGraph
import com.plyg.droidserver.app.Settings
import com.plyg.droidserver.app.data.StoreServices
import com.plyg.droidserver.app.data.StoreUpdate
import com.plyg.droidserver.app.data.UpdateState
import com.plyg.droidserver.app.ui.common.ErrorText
import com.plyg.droidserver.app.ui.common.SectionCard
import com.plyg.droidserver.app.ui.common.WarningText
import com.plyg.droidserver.app.ui.config.formatBytes
import com.plyg.droidserver.core.i18n.tr
import com.plyg.droidserver.core.server.EngineState
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Uygulama güncellemesi: denetle, notları göster, indir ve kur; otomatik denetim ve ön sürüm seçenekleri. */
@Composable
internal fun UpdateCard(g: AppGraph) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by g.updater.state.collectAsState()
    var auto by remember { mutableStateOf(g.settings.autoUpdateCheck) }
    var pre by remember { mutableStateOf(g.settings.includePrerelease) }
    // İzin ekranından dönünce yeniden okunur.
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumed++
        onPauseOrDispose {}
    }
    val canInstall = remember(resumed) { g.updater.canInstall() }

    SectionCard(tr("Uygulama güncellemesi")) {
        Text(tr("Kurulu sürüm: {0}", g.updater.currentVersion), style = MaterialTheme.typography.bodySmall)
        when (val s = state) {
            UpdateState.Idle -> {}
            UpdateState.Checking -> Text(tr("Denetleniyor…"), style = MaterialTheme.typography.bodySmall)
            is UpdateState.UpToDate -> Text(
                tr("Güncel (son denetim: {0})", DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(s.checkedAt))),
                style = MaterialTheme.typography.bodySmall,
            )
            is UpdateState.Available -> {
                val r = s.release
                Text(
                    tr("Yeni sürüm: {0}", r.version) + (if (r.prerelease) tr(" (ön sürüm)") else "") + " · ${formatBytes(r.apkSize)}",
                    style = MaterialTheme.typography.titleSmall,
                )
                if (r.notes.isNotBlank()) {
                    Text(r.notes.trim(), style = MaterialTheme.typography.bodySmall, maxLines = 12, overflow = TextOverflow.Ellipsis)
                }
                if (!canInstall) {
                    WarningText(tr("Kurulum için bu uygulamaya \"bilinmeyen uygulamaları yükleme\" izni verilmeli (yalnızca bu uygulama için)."))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (canInstall) {
                        Button(onClick = { scope.launch { g.updater.downloadAndInstall(r) } }) { Text(tr("İndir ve kur")) }
                    } else {
                        Button(onClick = { ctx.startActivity(g.updater.installPermissionIntent()) }) { Text(tr("İzin ver")) }
                    }
                    r.pageUrl?.let { url ->
                        OutlinedButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }) { Text(tr("Sürüm sayfası")) }
                    }
                }
                Text(
                    tr("Kurulum sırasında uygulama ve sunucu kısa süre kapanır; sunucu çalışıyorsa yeni sürüm açılınca kendiliğinden başlar. " +
                        "Veriler ve ayarlar korunur."),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            is UpdateState.Downloading -> {
                Text(tr("{0} indiriliyor… {1} / {2}", s.release.version, formatBytes(s.done), formatBytes(s.total)), style = MaterialTheme.typography.bodySmall)
                if (s.total > 0) LinearProgressIndicator(progress = { s.done.toFloat() / s.total }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            is UpdateState.Installing -> Text(tr("Android'in kurulum onayı bekleniyor…"), style = MaterialTheme.typography.bodySmall)
            is UpdateState.Failed -> {
                ErrorText(s.message)
                s.release?.pageUrl?.let { url ->
                    TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }) { Text(tr("Sürüm sayfası")) }
                }
            }
        }
        val busy = state is UpdateState.Checking || state is UpdateState.Downloading || state is UpdateState.Installing
        TextButton(enabled = !busy, onClick = { scope.launch { g.updater.check() } }) { Text(tr("Şimdi denetle")) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Günde bir kez otomatik denetle"), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = auto, onCheckedChange = { auto = it; g.settings.autoUpdateCheck = it })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Ön sürümleri de öner (beta)"), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = pre, onCheckedChange = { pre = it; g.settings.includePrerelease = it })
        }
        Text(
            tr("Sürümler github.com/{0} adresinden alınır. İndirilen dosyanın özeti ve imza sertifikası kurulu " +
                "uygulamayla karşılaştırılır; her kurulumu Android ayrıca size onaylatır.", Settings.UPDATE_REPO),
            style = MaterialTheme.typography.bodySmall,
        )
    }

    // Uygulama açıldığında, günlük denetim zamanı geldiyse (bildirim göstermeden; kart zaten görünüyor).
    LaunchedEffect(Unit) { if (g.updater.isDue()) g.updater.check() }
}

/**
 * Play sürümünde Play'den gelen güncelleme (esnek): yeni sürüm varsa indirme, inince yeniden başlatıp kurma. Güncelleme
 * yoksa ya da uygulama Play'den kurulmamışsa görünmez.
 */
@Composable
internal fun StoreUpdateCard(g: AppGraph, store: StoreServices) {
    val state by store.update.collectAsState()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {}
    if (state == StoreUpdate.None) return
    SectionCard(tr("Uygulama güncellemesi")) {
        when (val s = state) {
            StoreUpdate.None -> {}
            is StoreUpdate.Available -> {
                Text(tr("Play'de yeni sürüm var."), style = MaterialTheme.typography.titleSmall)
                Text(tr("Arka planda iner; sunucu inerken çalışmaya devam eder."), style = MaterialTheme.typography.bodySmall)
                Button(onClick = { store.startUpdate(launcher) }) { Text(tr("İndir")) }
            }
            is StoreUpdate.Downloading -> {
                Text(tr("İndiriliyor… %{0}", s.percent), style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(progress = { s.percent / 100f }, modifier = Modifier.fillMaxWidth())
            }
            StoreUpdate.Downloaded -> {
                Text(tr("Güncelleme indi."), style = MaterialTheme.typography.titleSmall)
                Text(tr("Kurmak için uygulama yeniden başlar; sunucu kısa süre durur."), style = MaterialTheme.typography.bodySmall)
                Button(onClick = {
                    // Kurulumdan sonra (MY_PACKAGE_REPLACED) çalışan sunucu yeniden başlatılır; bkz. BootReceiver.
                    g.settings.restartAfterUpdate = g.engine.state.value is EngineState.Running
                    store.completeUpdate()
                }) { Text(tr("Yeniden başlat ve kur")) }
            }
        }
    }
}
