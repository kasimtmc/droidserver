package dev.droidserver.app.ui.config

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.droidserver.app.AppGraph
import dev.droidserver.app.ui.common.ScreenScaffold
import dev.droidserver.core.audit.AuditEntry
import dev.droidserver.core.audit.AuditLog
import dev.droidserver.core.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** İşlemlerin Türkçe adları (bilinmeyen işlem olduğu gibi gösterilir). */
internal val AUDIT_ACTIONS = mapOf(
    "server.start" to tr("Sunucu başlatıldı"),
    "deploy" to tr("Konfigürasyon değişti"),
    "config.save" to tr("Panelden konfigürasyon kaydedildi"),
    "panel.login" to tr("Panel girişi"),
    "panel.login.failed" to tr("Başarısız panel girişi"),
    "user.create" to tr("Kullanıcı açıldı"),
    "user.update" to tr("Kullanıcı güncellendi"),
    "user.delete" to tr("Kullanıcı silindi"),
    "record.revert" to tr("Kayıt eski sürüme döndü"),
    "record.restore" to tr("Kayıt çöp kutusundan geri yüklendi"),
    "record.purge" to tr("Kayıt kalıcı silindi"),
    "data.import" to tr("İçe aktarma"),
    "backup.create" to tr("Yedek alındı"),
    "backup.restore" to tr("Yedekten geri yüklendi"),
    "firewall.ban" to tr("Adres engellendi"),
    "firewall.unban" to tr("Adres engeli kaldırıldı"),
    "job.enqueue" to tr("İş eklendi"),
    "job.retry" to tr("İş yeniden çalıştırıldı"),
    "job.cancel" to tr("İş iptal edildi"),
    "replica.promote" to tr("Kopya devraldı (ana sunucu oldu)"),
    "replica.failover" to tr("Kopya otomatik devraldı (ana sunucuya ulaşılamadı)"),
    "replica.failback" to tr("Ana rol geri alındı"),
    "replica.demoted" to tr("Bu sunucu devredildi, kopyaya döndü"),
    "replica.fenced" to tr("Eski ana sunucu kopyaya çevrildi"),
    "phone.sms" to tr("SMS gönderildi"),
)

/** Denetim kaydı: kim, ne zaman, nereden ne yaptı; arama ve CSV paylaşımı. */
@Composable
fun AuditScreen(g: AppGraph, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<AuditEntry>>(emptyList()) }
    LaunchedEffect(query) { entries = withContext(Dispatchers.IO) { g.engine.audit.list(500, query = query.takeIf { it.isNotBlank() }) } }
    val time = remember { DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(ZoneId.systemDefault()) }

    ScreenScaffold(tr("Denetim kaydı"), onBack = onBack) { modifier ->
        LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(
                    tr("Yönetici işlemleri: konfigürasyon değişiklikleri, kullanıcı yönetimi, geri yükleme ve kalıcı silme, panel girişleri. " +
                        "Kayıt telefonda durur; geri yükleme veya veritabanı taşıma onu silmez."),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, label = { Text(tr("Ara (işlem, kişi, ayrıntı, adres)")) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Row {
                    TextButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/csv"
                            putExtra(Intent.EXTRA_SUBJECT, "denetim.csv")
                            putExtra(Intent.EXTRA_TEXT, AuditLog.csv(entries))
                        }
                        ctx.startActivity(Intent.createChooser(send, "denetim.csv"))
                    }) { Text(tr("CSV paylaş")) }
                }
                if (entries.isEmpty()) Text(tr("Kayıt yok."), style = MaterialTheme.typography.bodySmall)
            }
            items(entries) { e ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(AUDIT_ACTIONS[e.action] ?: e.action, style = MaterialTheme.typography.titleSmall)
                        Text(
                            listOfNotNull(runCatching { time.format(Instant.parse(e.at)) }.getOrDefault(e.at), e.actor, e.ip).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        e.target?.let { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                        e.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
}
