package dev.droidserver.app.ui.logs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.droidserver.app.AppGraph
import dev.droidserver.app.ui.common.EmptyState
import dev.droidserver.app.ui.common.ScreenScaffold
import dev.droidserver.app.ui.common.copyToClipboard
import dev.droidserver.app.ui.theme.LocalStatusColors
import dev.droidserver.core.i18n.tr

/** Satırın önemi: hata/uyarı satırları renkle ayrılır, süzgeç onlara göre çalışır. */
private enum class Level { ERROR, WARN, INFO }

private val ERROR_WORDS = listOf("hata", "başarısız", "başlatılamadı", "error", "failed", "exception", "çöktü", "geçmedi", "bulunamadı", "reddedildi")
private val WARN_WORDS = listOf("uyarı", "warning", "yeniden denenecek", "dönülüyor", "atlandı", "eksik")

private fun levelOf(line: String): Level {
    val l = line.lowercase()
    return when {
        ERROR_WORDS.any { it in l } -> Level.ERROR
        WARN_WORDS.any { it in l } -> Level.WARN
        else -> Level.INFO
    }
}

/** Uygulama ve sunucu logları: arama, yalnızca sorunlar süzgeci, saat ve iletinin ayrı gösterimi, panoya kopyalama. */
@Composable
fun LogsScreen(g: AppGraph) {
    val ctx = LocalContext.current
    val lines by g.logs.lines.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var problemsOnly by rememberSaveable { mutableStateOf(false) }
    val shown = remember(lines, query, problemsOnly) {
        val q = query.trim().lowercase()
        lines.filter { (q.isEmpty() || q in it.lowercase()) && (!problemsOnly || levelOf(it) != Level.INFO) }
    }
    ScreenScaffold(
        tr("Loglar"),
        subtitle = tr("{0} satır", lines.size),
        actions = {
            IconButton(onClick = { copyToClipboard(ctx, "log", shown.joinToString("\n")) }, enabled = shown.isNotEmpty()) {
                Icon(Icons.Default.ContentCopy, contentDescription = tr("Logları kopyala"))
            }
        },
    ) { modifier ->
        Column(modifier.fillMaxSize()) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text(tr("Loglarda ara (örn. postgres, app:api)")) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    shape = MaterialTheme.shapes.extraLarge,
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !problemsOnly, onClick = { problemsOnly = false }, label = { Text(tr("Tümü")) })
                    FilterChip(
                        selected = problemsOnly, onClick = { problemsOnly = true },
                        label = { Text(tr("Sorunlar ({0})", lines.count { levelOf(it) != Level.INFO })) },
                    )
                }
            }
            if (shown.isEmpty()) {
                EmptyState(
                    Icons.Default.Terminal,
                    if (lines.isEmpty()) tr("Henüz log yok") else tr("Eşleşen satır yok"),
                    if (lines.isEmpty()) tr("Sunucu başlatılınca, deploy edilince ve bileşenler çalışınca olanlar burada görünür.") else tr("Aramayı veya süzgeci değiştirin."),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize(), reverseLayout = true, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                    items(shown.asReversed()) { LogLine(it) }
                }
            }
        }
    }
}

@Composable
private fun LogLine(line: String) {
    val time = line.substringBefore("  ", "")
    val message = if (time.isEmpty()) line else line.substringAfter("  ")
    val color: Color = when (levelOf(line)) {
        Level.ERROR -> MaterialTheme.colorScheme.error
        Level.WARN -> LocalStatusColors.current.warning
        Level.INFO -> MaterialTheme.colorScheme.onSurface
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text(time, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Text(message, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp, color = color, modifier = Modifier.weight(1f))
    }
}
