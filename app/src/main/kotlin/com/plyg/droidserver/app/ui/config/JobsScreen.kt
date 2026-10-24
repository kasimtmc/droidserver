package com.plyg.droidserver.app.ui.config

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import com.plyg.droidserver.app.ui.common.EmptyState
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
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.plyg.droidserver.app.AppGraph
import com.plyg.droidserver.app.ui.common.ScreenScaffold
import com.plyg.droidserver.core.audit.AuditLog
import com.plyg.droidserver.core.i18n.tr
import com.plyg.droidserver.core.jobs.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Durumların Türkçe adları. */
internal val JOB_STATUS = mapOf(
    Job.QUEUED to tr("Bekliyor"), Job.RUNNING to tr("Çalışıyor"), Job.DONE to tr("Bitti"), Job.FAILED to tr("Başarısız"), Job.CANCELLED to tr("İptal edildi"),
)

/** Arka plan işleri: duruma göre liste, sonuç/hata, yeniden çalıştırma ve iptal. */
@Composable
fun JobsScreen(g: AppGraph, onBack: () -> Unit) {
    var status by rememberSaveable { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var jobs by remember { mutableStateOf<List<Job>>(emptyList()) }
    var counts by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var unavailable by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(status, tick) {
        withContext(Dispatchers.IO) {
            val q = g.engine.jobs()
            unavailable = q == null
            if (q != null) runCatching {
                jobs = q.list(status, perPage = 100).first
                counts = q.counts()
            }.onFailure { message = it.message }
        }
    }
    LaunchedEffect(Unit) { while (true) { delay(5_000); tick++ } }
    val time = remember { DateTimeFormatter.ofPattern("dd.MM HH:mm:ss").withZone(ZoneId.systemDefault()) }

    fun act(label: String, block: (com.plyg.droidserver.core.jobs.JobQueue) -> Job) = scope.launch {
        message = withContext(Dispatchers.IO) {
            val q = g.engine.jobs() ?: return@withContext tr("Sunucu çalışmıyor")
            runCatching { block(q) }.fold({ g.engine.audit.record(AuditLog.APP, label, "${it.name}#${it.id}"); null }, { it.message })
        }
        tick++
    }

    ScreenScaffold(tr("Arka plan işleri"), onBack = onBack) { modifier ->
        LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(
                    tr("Script'lerin jobAdd ile tanımlayıp ctx.jobs.enqueue ile kuyruğa eklediği işler. Başarısız iş beklemeyle " +
                        "yeniden denenir; telefon yeniden başlasa da kuyruk kaybolmaz."),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (unavailable) Text(tr("Sunucu çalışmıyor veya script'ler kapalı."), style = MaterialTheme.typography.bodySmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = status == null, onClick = { status = null }, label = { Text(tr("Tümü ({0})", counts.values.sum())) })
                    JOB_STATUS.forEach { (s, title) ->
                        FilterChip(selected = status == s, onClick = { status = s }, label = { Text("$title (${counts[s] ?: 0})") })
                    }
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (!unavailable && jobs.isEmpty()) EmptyState(
                    Icons.Default.Schedule, tr("İş yok"),
                    tr("Script'lerde jobAdd ile tanımlanan ve ctx.jobs.enqueue ile kuyruğa eklenen işler burada görünür."),
                )
            }
            items(jobs, key = { it.id }) { j ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${j.name} #${j.id} · ${JOB_STATUS[j.status] ?: j.status}", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    listOfNotNull(
                                        tr("deneme {0}/{1}", j.attempts, j.maxAttempts),
                                        j.createdAt?.let { tr("eklendi {0}", time.format(it)) },
                                        j.runAt.takeIf { j.status == Job.QUEUED }?.let { tr("çalışacak {0}", time.format(it)) },
                                        j.finishedAt?.let { tr("bitti {0}", time.format(it)) },
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            when (j.status) {
                                Job.FAILED, Job.CANCELLED -> TextButton(onClick = { act("job.retry") { it.retry(j.id) } }) { Text(tr("Yeniden")) }
                                Job.QUEUED, Job.RUNNING -> TextButton(onClick = { act("job.cancel") { it.cancel(j.id) } }) { Text(tr("İptal")) }
                            }
                        }
                        Text(j.data.toString().take(300), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        j.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        j.result?.takeIf { j.status == Job.DONE }?.let { Text(tr("Sonuç: ") + it.toString().take(300), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
}
