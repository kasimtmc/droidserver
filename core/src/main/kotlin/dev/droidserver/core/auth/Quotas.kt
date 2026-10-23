package dev.droidserver.core.auth

import dev.droidserver.core.api.ApiException
import dev.droidserver.core.config.CollectionConfig
import dev.droidserver.core.config.FieldType
import dev.droidserver.core.config.QuotaConfig
import dev.droidserver.core.config.SystemCollections
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.db.SqlDriver
import dev.droidserver.core.db.quoteIdent
import dev.droidserver.core.files.FileStore
import dev.droidserver.core.i18n.tr
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Kullanıcı başına günlük (UTC) istek sayacı. Bellekte tutulur; sunucu yeniden kurulsa da (ServerEngine saklar) sürer,
 * uygulama kapanınca sıfırlanır.
 */
class DailyCounter(private val clock: () -> Instant = Instant::now) {
    private class Day(val day: Long) { val count = AtomicInteger() }
    private val days = ConcurrentHashMap<Long, Day>()

    private fun today() = Math.floorDiv(clock().epochSecond, 86_400L)

    /** Bugünkü sayıyı bir artırır ve döndürür. */
    fun hit(user: Long): Int {
        val d = today()
        return days.compute(user) { _, old -> if (old?.day == d) old else Day(d) }!!.count.incrementAndGet()
    }

    fun count(user: Long): Int = days[user]?.takeIf { it.day == today() }?.count?.get() ?: 0

    /** Sayaçların sıfırlanacağı an (yarın 00:00 UTC). */
    fun resetsAt(): Instant = Instant.ofEpochSecond((today() + 1) * 86_400L)
}

/**
 * Kotaların uygulanması ve kullanım özeti (`GET /auth/me/usage`, uygulamadaki kullanıcı kartı). Yalnızca yönetici
 * olmayan kullanıcılar sınırlanır. Kayıt ve depolama sınırı yazmadan hemen önce sayılarak denetlenir (aynı anda gelen
 * isteklerde küçük bir aşım olabilir).
 */
class QuotaService(
    private val db: SqlDriver,
    private val config: ServerConfig,
    private val files: FileStore?,
    private val counter: DailyCounter,
) {
    private val quotas = config.auth.users.quotas

    /** Abonelik planlarının rollere bağladığı kotalar (genel kotanın yerine). */
    private val roleQuotas = config.payments.plans.mapNotNull { p -> p.quotas?.let { p.role to it } }.toMap()
    val active: Boolean = config.auth.users.enabled && (quotas.isSet || roleQuotas.values.any { it.isSet })

    /** Kullanıcının kotası: rolüne bağlı plan kotası, yoksa genel kota. */
    private fun quotasFor(user: Long, role: String? = null): QuotaConfig {
        if (roleQuotas.isEmpty()) return quotas
        val r = role ?: db.query("SELECT \"role\" FROM ${quoteIdent(SystemCollections.USERS)} WHERE \"id\" = ?", listOf(user)).firstOrNull()?.get("role")?.toString()
        return r?.let(roleQuotas::get) ?: quotas
    }

    fun recordLimit(c: CollectionConfig, q: QuotaConfig = quotas): Int? = c.ownerField?.let { q.collections[c.name] ?: q.records }

    fun records(c: CollectionConfig, user: Long): Long {
        val owner = c.ownerField ?: return 0
        return (db.query("SELECT COUNT(*) AS c FROM ${quoteIdent(c.name)} WHERE ${quoteIdent(owner)} = ?", listOf(user)).first()["c"] as Number).toLong()
    }

    /** Yeni kayıttan önce: sınır dolduysa 403. */
    fun checkRecords(c: CollectionConfig, user: Long) {
        if (!active) return
        val limit = recordLimit(c, quotasFor(user)) ?: return
        if (records(c, user) >= limit) {
            throw ApiException(403, tr("Kota doldu: '{0}' koleksiyonunda en çok {1} kaydınız olabilir", c.name, limit), mapOf("quota" to "records", "limit" to "$limit"))
        }
    }

    /** Sahibi olduğu kayıtların dosyaları (bayt; küçük resimler hariç). */
    fun storageBytes(user: Long): Long {
        val store = files ?: return 0
        return config.collections.filter { c -> c.ownerField != null && c.fields.any { it.type == FieldType.FILE } }.sumOf { c ->
            db.query("SELECT \"id\" FROM ${quoteIdent(c.name)} WHERE ${quoteIdent(c.ownerField!!)} = ?", listOf(user)).sumOf { r ->
                val dir = store.recordDir(c.name, (r["id"] as Number).toLong())
                dir.listFiles(File::isFile)?.sumOf(File::length) ?: 0L
            }
        }
    }

    /** Dosya yüklemeden önce: [adding] bayt eklenince sınır aşılıyorsa 413. */
    fun checkStorage(user: Long, adding: Long) {
        if (!active || adding <= 0) return
        val limit = quotasFor(user).storageMb ?: return
        val used = storageBytes(user)
        if (used + adding > limit * MB) {
            throw ApiException(
                413, tr("Depolama kotası doldu: en çok {0} MB (kullanılan {1} MB, yüklenen {2} MB)", limit, mb(used), mb(adding)),
                mapOf("quota" to "storage", "limit" to "${limit * MB}"),
            )
        }
    }

    /** İstek başına bir kez (kullanıcı token'ı çözülünce): günlük sınır aşıldıysa 429. */
    fun hit(user: Long, role: String? = null) {
        if (!active) return
        val limit = quotasFor(user, role).dailyRequests ?: return
        if (counter.hit(user) > limit) {
            throw ApiException(429, tr("Günlük istek kotası doldu ({0}); {1} itibarıyla sıfırlanır", limit, counter.resetsAt()), mapOf("quota" to "requests", "limit" to "$limit"))
        }
    }

    /** `{records:{koleksiyon:{used, limit}}, storage:{usedBytes, limitBytes}, requests:{today, limit, resetsAt}}`; sınırsızda limit null. */
    fun usage(user: Long): JsonObject = buildJsonObject {
        val quotas = quotasFor(user)
        put("records", buildJsonObject {
            for (c in config.collections.filter { it.ownerField != null }) {
                put(c.name, buildJsonObject {
                    put("used", JsonPrimitive(records(c, user)))
                    put("limit", recordLimit(c, quotas)?.let(::JsonPrimitive) ?: JsonNull)
                })
            }
        })
        put("storage", buildJsonObject {
            put("usedBytes", JsonPrimitive(storageBytes(user)))
            put("limitBytes", quotas.storageMb?.let { JsonPrimitive(it * MB) } ?: JsonNull)
        })
        put("requests", buildJsonObject {
            put("today", JsonPrimitive(counter.count(user)))
            put("limit", quotas.dailyRequests?.let(::JsonPrimitive) ?: JsonNull)
            put("resetsAt", JsonPrimitive(counter.resetsAt().toString()))
        })
    }

    private fun mb(bytes: Long) = "%.1f".format(java.util.Locale.ROOT, bytes / MB.toDouble())

    companion object {
        const val MB = 1024L * 1024L
    }
}
