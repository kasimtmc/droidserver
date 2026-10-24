package com.plyg.droidserver.core.backup

import com.plyg.droidserver.core.i18n.tr
import com.plyg.droidserver.core.script.CronSchedule
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Yedeğin nedeni; dosya adında durur, eski yedekler silinirken yalnızca zamanlanmışlar sayılır. */
enum class BackupKind(val key: String, val title: String) {
    SCHEDULED("otomatik", tr("Zamanlanmış")),
    MANUAL("elle", tr("Elle")),
    BEFORE_RESTORE("geri-yukleme-oncesi", tr("Geri yükleme öncesi")),
    BEFORE_DEMOTE("devir-oncesi", tr("Kopyaya dönmeden önce")),
}

data class BackupInfo(val file: File, val kind: BackupKind, val size: Long, val createdAt: LocalDateTime) {
    val name: String get() = file.name
}

/** Uygulama klasöründeki yedekler: `droidserver-<tarih>-<neden>.zip`. */
class BackupStore(val dir: File) {
    fun newFile(kind: BackupKind, now: LocalDateTime = LocalDateTime.now()): File {
        dir.mkdirs()
        val base = "droidserver-${now.format(STAMP)}-${kind.key}"
        // Aynı saniyedeki yedeklerin sırası: boşalan bir ad yeniden kullanılmaz (yoksa yeni yedek "eski" sayılırdı).
        val used = dir.listFiles().orEmpty().mapNotNull { f ->
            when {
                f.name == "$base.zip" -> 0
                f.name.startsWith("$base-") && f.name.endsWith(".zip") -> f.name.removePrefix("$base-").removeSuffix(".zip").toIntOrNull()
                else -> null
            }
        }
        val next = used.maxOrNull()?.plus(1) ?: 0
        return File(dir, if (next == 0) "$base.zip" else "$base-$next.zip")
    }

    fun list(): List<BackupInfo> = dir.listFiles { f -> f.isFile && NAME.matches(f.name) }.orEmpty().mapNotNull { f ->
        val m = NAME.matchEntire(f.name) ?: return@mapNotNull null
        val kind = BackupKind.entries.firstOrNull { it.key == m.groupValues[2] } ?: return@mapNotNull null
        Triple(BackupInfo(f, kind, f.length(), LocalDateTime.parse(m.groupValues[1], STAMP)), m.groupValues[3].removePrefix("-").toIntOrNull() ?: 0, f)
    }.sortedWith(compareByDescending<Triple<BackupInfo, Int, File>> { it.first.createdAt }.thenByDescending { it.third.lastModified() }.thenByDescending { it.second }).map { it.first }

    fun find(name: String): File? = list().firstOrNull { it.name == name }?.file

    /** Zamanlanmış yedeklerden en yeni [keep] tanesi kalır; silinenleri döndürür. */
    fun prune(keep: Int): List<File> = list().filter { it.kind == BackupKind.SCHEDULED }.drop(keep).map { it.file }.onEach { it.delete() }

    private companion object {
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        val NAME = Regex("^droidserver-(\\d{8}-\\d{6})-([a-z-]+?)(-\\d+)?\\.zip$")
    }
}

/** Zamanlanmış yedeğin zamanı geldi mi? Aynı dakikada iki kez tetiklemez. */
class BackupSchedule(expression: String) {
    private val cron = CronSchedule.parse(expression)
    private var last: LocalDateTime? = null

    @Synchronized
    fun due(now: LocalDateTime): Boolean {
        val minute = now.truncatedTo(ChronoUnit.MINUTES)
        if (minute == last || !cron.matches(minute)) return false
        last = minute
        return true
    }
}
