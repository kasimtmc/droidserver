package dev.droidserver.core.api

import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.i18n.tr
import java.time.Instant

/**
 * Saklama süresi dolan kayıtları siler (saatte bir, bakım döngüsünde). Silme normal silme yolundan geçer: ilişkilerin
 * `onDelete` davranışı, dosyalar, geçmiş ve çöp kutusu uygulanır; webhook ve canlı abonelik olayı üretmez. Bağlı kaydı
 * olduğu için (`restrict`) silinemeyen kayıt atlanır. Bir çalışmada koleksiyon başına en çok [BATCH] kayıt.
 */
object Retention {
    const val BATCH = 500
    const val ACTOR = "system:retention"

    /** Koleksiyon adından silinen kayıt sayısı (silinenler olanlar). */
    fun run(config: ServerConfig, services: Map<String, RecordService>, now: Instant = Instant.now(), log: (String) -> Unit = {}): Map<String, Int> =
        buildMap {
            for (c in config.collections) {
                if (c.retention == null) continue
                val svc = services[c.name] ?: continue
                var deleted = 0
                var skipped = 0
                for (id in svc.expiredIds(now, BATCH)) {
                    try {
                        svc.delete(id, Scope(actor = ACTOR))
                        deleted++
                    } catch (e: ApiException) {
                        // Silinmiş (cascade ile) veya bağlı kaydı olduğu için silinemeyen.
                        if (e.status != 404) skipped++
                    }
                }
                if (skipped > 0) log(tr("Saklama süresi: '{0}' koleksiyonunda {1} kayıt bağlı kayıtları olduğu için silinemedi", c.name, skipped))
                if (deleted > 0) put(c.name, deleted)
            }
        }
}
