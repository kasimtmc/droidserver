package com.plyg.droidserver.app.ui.status

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.plyg.droidserver.app.AppGraph
import com.plyg.droidserver.app.data.NetUtil
import com.plyg.droidserver.app.ui.common.SectionCard
import com.plyg.droidserver.app.ui.common.WarningText
import com.plyg.droidserver.app.ui.common.copyToClipboard
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.i18n.tr
import com.plyg.droidserver.core.metrics.Metrics
import kotlinx.coroutines.delay

/** Son 1 saat: dakikada istek (çubuk grafik), hata sayısı, ortalama süre ve pil durumu. */
@Composable
internal fun MetricsCard(g: AppGraph) {
    var series by remember { mutableStateOf<List<Metrics.Minute>>(emptyList()) }
    LaunchedEffect(Unit) {
        while (true) {
            series = g.engine.metrics.series()
            delay(15_000)
        }
    }
    SectionCard(tr("Son 1 saat")) {
        val total = series.sumOf { it.requests }
        val errors = series.sumOf { it.errors }
        val avg = series.mapNotNull { m -> m.avgMs?.let { it * m.requests } }.sum().let { if (total > 0) it / total else null }
        Text(
            tr("{0} istek", total) + (if (errors > 0) tr(", {0} sunucu hatası (5xx)", errors) else "") + (avg?.let { tr(", ortalama {0} ms", "%.0f".format(it)) } ?: ""),
            style = MaterialTheme.typography.bodySmall,
        )
        val bar = MaterialTheme.colorScheme.primary
        val err = MaterialTheme.colorScheme.error
        Canvas(Modifier.fillMaxWidth().height(56.dp)) {
            if (series.isEmpty()) return@Canvas
            val max = maxOf(1L, series.maxOf { it.requests }).toFloat()
            val w = size.width / 60f
            series.takeLast(60).forEachIndexed { i, m ->
                val x = size.width - (series.takeLast(60).size - i) * w
                val h = m.requests / max * size.height
                drawRect(if (m.errors > 0) err else bar, Offset(x + 1f, size.height - h), Size(maxOf(w - 2f, 1f), h))
            }
        }
        series.lastOrNull { it.device != null }?.device?.let { d ->
            Text(
                listOfNotNull(
                    d.batteryPercent?.let { tr("Pil %{0}", it) + if (d.charging == true) tr(" (şarjda)") else "" },
                    d.batteryTempC?.let { "%.1f °C".format(it) },
                    d.memoryUsedBytes?.let { used -> d.memoryTotalBytes?.let { tr("bellek %{0}", used * 100 / it) } },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
            )
            if ((d.batteryTempC ?: 0.0) >= 45.0) WarningText(tr("Telefon ısınmış; serin bir yere koyun, kılıfını çıkarın."))
        }
    }
}

/** Web paneli: adres, tek kullanımlık eşleştirme kodu (5 dk) ve açık oturumları kapatma. */
@Composable
internal fun AdminPanelCard(g: AppGraph, config: ServerConfig) {
    val ctx = LocalContext.current
    var code by remember { mutableStateOf<String?>(null) }
    var expires by remember { mutableLongStateOf(0L) }
    var left by remember { mutableIntStateOf(0) }
    var sessions by remember { mutableIntStateOf(g.engine.adminSessions.activeSessions) }
    LaunchedEffect(code) {
        while (code != null) {
            left = ((expires - System.currentTimeMillis()) / 1000).toInt()
            if (left <= 0) code = null
            sessions = g.engine.adminSessions.activeSessions
            delay(1_000)
        }
    }
    SectionCard(tr("Web paneli")) {
        val host = remember { NetUtil.localIpv4().firstOrNull() ?: "127.0.0.1" }
        val url = "http://$host:${config.server.port}/_admin/"
        Text(url, fontFamily = FontFamily.Monospace)
        Text(
            tr("Bilgisayarın tarayıcısında açın; istenen kodu buradan oluşturun. İnternetten erişimde (HTTPS / tünel) aynı yol geçerlidir."),
            style = MaterialTheme.typography.bodySmall,
        )
        code?.let {
            Text(it.chunked(3).joinToString(" "), style = MaterialTheme.typography.displaySmall, fontFamily = FontFamily.Monospace)
            Text(tr("{0}:{1} içinde, bir kez kullanılabilir", left / 60, "%02d".format(left % 60)), style = MaterialTheme.typography.bodySmall)
        }
        Row {
            TextButton(onClick = {
                val p = g.engine.adminSessions.newCode()
                code = p.code
                expires = p.expiresAtMs
            }) { Text(tr("Eşleştirme kodu oluştur")) }
            TextButton(onClick = { copyToClipboard(ctx, "panel", url) }) { Text(tr("Adresi kopyala")) }
        }
        if (sessions > 0) {
            TextButton(onClick = { g.engine.adminSessions.revokeAll(); sessions = 0 }) { Text(tr("Açık {0} panel oturumunu kapat", sessions)) }
        }
    }
}
