package dev.droidserver.app.ui.status

import android.os.storage.StorageManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.droidserver.app.AppGraph
import dev.droidserver.app.ui.common.SectionCard
import dev.droidserver.app.ui.common.WarningText
import dev.droidserver.app.ui.config.formatBytes
import dev.droidserver.core.config.DbEngine
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.files.StorageUsage
import dev.droidserver.core.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Depolama kullanımı: veritabanı, yüklenen dosyalar, statik site ve telefonda kalan boş alan. */
@Composable
internal fun StorageCard(g: AppGraph, config: ServerConfig) {
    val ctx = LocalContext.current
    var lines by remember { mutableStateOf<List<String>?>(null) }
    var free by remember { mutableLongStateOf(Long.MAX_VALUE) }
    LaunchedEffect(config) {
        withContext(Dispatchers.IO) {
            lines = buildList {
                when (config.database.engine) {
                    DbEngine.SQLITE -> {
                        val db = g.databaseFile(config.database.file)
                        val size = listOf("", "-wal", "-journal").sumOf { File(db.path + it).length() }
                        add(tr("Veritabanı (SQLite): {0}", formatBytes(size)))
                    }
                    DbEngine.POSTGRES -> {
                        val data = File(ctx.filesDir, "components/data/postgres")
                        if (data.exists()) add(tr("Veritabanı (PostgreSQL): {0}", formatBytes(StorageUsage.of(data).bytes)))
                    }
                }
                val files = StorageUsage.of(g.engine.fileStore?.root)
                add(tr("Yüklenen dosyalar: {0} dosya, {1}", files.files, formatBytes(files.bytes)))
                if (config.static.enabled) {
                    val site = StorageUsage.of(g.engine.siteDir)
                    add(tr("Statik site: {0} dosya, {1}", site.files, formatBytes(site.bytes)))
                }
            }
            // Silinebilir önbellekler de hesaba katılır (sistem gerekince boşaltır).
            val sm = ctx.getSystemService(StorageManager::class.java)
            free = runCatching { sm.getAllocatableBytes(sm.getUuidForPath(ctx.filesDir)) }.getOrDefault(ctx.filesDir.usableSpace)
        }
    }
    SectionCard(tr("Depolama")) {
        lines?.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text(tr("Telefonda boş: {0}", formatBytes(free.coerceAtMost(Long.MAX_VALUE / 2))), style = MaterialTheme.typography.bodySmall)
        if (free < 500L * 1024 * 1024) WarningText(tr("Boş alan azaldı; yüklemeler ve veritabanı yazmaları başarısız olabilir."))
    }
}
