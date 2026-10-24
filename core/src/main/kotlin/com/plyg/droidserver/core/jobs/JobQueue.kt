package com.plyg.droidserver.core.jobs

import com.plyg.droidserver.core.api.ApiException
import com.plyg.droidserver.core.config.FieldType
import com.plyg.droidserver.core.config.SystemCollections
import com.plyg.droidserver.core.db.SqlDriver
import com.plyg.droidserver.core.db.quoteIdent
import com.plyg.droidserver.core.i18n.tr
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Duration
import java.time.Instant

/** Kuyruktaki bir iş. */
data class Job(
    val id: Long,
    val name: String,
    val data: JsonElement,
    val status: String,
    val attempts: Int,
    val maxAttempts: Int,
    val runAt: Instant,
    val key: String?,
    val result: JsonElement?,
    val error: String?,
    val createdAt: Instant?,
    val startedAt: Instant?,
    val finishedAt: Instant?,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(id))
        put("name", JsonPrimitive(name))
        put("data", data)
        put("status", JsonPrimitive(status))
        put("attempts", JsonPrimitive(attempts))
        put("maxAttempts", JsonPrimitive(maxAttempts))
        put("runAt", JsonPrimitive(runAt.toString()))
        key?.let { put("key", JsonPrimitive(it)) }
        result?.let { put("result", it) }
        error?.let { put("error", JsonPrimitive(it)) }
        createdAt?.let { put("createdAt", JsonPrimitive(it.toString())) }
        startedAt?.let { put("startedAt", JsonPrimitive(it.toString())) }
        finishedAt?.let { put("finishedAt", JsonPrimitive(it.toString())) }
    }

    companion object {
        const val QUEUED = "queued"
        const val RUNNING = "running"
        const val DONE = "done"
        const val FAILED = "failed"
        const val CANCELLED = "cancelled"
        val STATUSES = listOf(QUEUED, RUNNING, DONE, FAILED, CANCELLED)
    }
}

/**
 * Kalıcı iş kuyruğu (`_jobs` tablosu; sunucu veya telefon yeniden başlasa da işler kaybolmaz). Bir işi yalnızca bir
 * çalıştırıcı alır (`queued` -> `running` koşullu güncelleme). Başarısız iş üstel beklemeyle (30 sn, 1 dk, 2 dk, …,
 * en çok 1 saat) yeniden denenir; deneme hakkı bitince `failed`.
 */
class JobQueue(private val db: SqlDriver, private val clock: () -> Instant = Instant::now) {
    private val t = quoteIdent(SystemCollections.JOBS)
    private val d = db.dialect

    private fun at(i: Instant) = d.encode(FieldType.DATETIME, i)
    private fun json(e: JsonElement?) = e?.takeIf { it !is JsonNull }?.let { d.encode(FieldType.JSON, it.toString()) }
    private fun cmp(expr: String) = d.comparable(FieldType.DATETIME, expr)

    /**
     * İş ekler, kimliğini döndürür. [key] doluysa aynı anahtarlı bitmemiş (bekleyen/çalışan) iş varsa yenisi
     * eklenmez, onun kimliği döner.
     */
    fun enqueue(name: String, data: JsonElement = JsonObject(emptyMap()), delay: Duration = Duration.ZERO, maxAttempts: Int = 3, key: String? = null): Long =
        db.transaction {
            key?.let { k ->
                db.query("SELECT \"id\" FROM $t WHERE \"key\" = ? AND \"status\" IN (?, ?) ORDER BY \"id\" LIMIT 1", listOf(k, Job.QUEUED, Job.RUNNING))
                    .firstOrNull()?.let { return@transaction (it["id"] as Number).toLong() }
            }
            val now = clock()
            db.insert(
                d.insertReturningId(SystemCollections.JOBS, listOf("name", "data", "status", "attempts", "max_attempts", "run_at", "key", "created_at", "updated_at")),
                listOf(name, json(data), Job.QUEUED, 0, maxAttempts.coerceIn(1, MAX_ATTEMPTS), at(now + delay), key, at(now), at(now)),
            )
        }

    /**
     * Zamanı gelmiş bekleyen işlerden [limit] tanesini alır ve `running` yapar (eski önce). [names]: çalıştırılabilen
     * türler (script'te tanımlı olanlar); [skip] o an sınırına ulaşmış türler.
     */
    fun claim(names: Set<String>, limit: Int, skip: Set<String> = emptySet()): List<Job> {
        val wanted = names - skip
        if (wanted.isEmpty() || limit <= 0) return emptyList()
        val now = clock()
        val marks = wanted.joinToString(",") { "?" }
        val due = db.query(
            "SELECT * FROM $t WHERE \"status\" = ? AND \"name\" IN ($marks) AND ${cmp("\"run_at\"")} <= ${cmp("?")} ORDER BY \"run_at\", \"id\" LIMIT ?",
            listOf(Job.QUEUED) + wanted + listOf(at(now), limit),
        )
        return due.mapNotNull { row ->
            val id = (row["id"] as Number).toLong()
            val attempts = (row["attempts"] as Number).toInt() + 1
            val n = db.execute(
                "UPDATE $t SET \"status\" = ?, \"attempts\" = ?, \"started_at\" = ?, \"updated_at\" = ? WHERE \"id\" = ? AND \"status\" = ?",
                listOf(Job.RUNNING, attempts, at(now), at(now), id, Job.QUEUED),
            )
            if (n == 1) job(row).copy(status = Job.RUNNING, attempts = attempts, startedAt = now) else null
        }
    }

    /** Bitti; iş bu arada iptal edildiyse dokunulmaz. */
    fun complete(id: Long, result: JsonElement?) {
        val now = clock()
        db.execute(
            "UPDATE $t SET \"status\" = ?, \"result\" = ?, \"error\" = NULL, \"finished_at\" = ?, \"updated_at\" = ? WHERE \"id\" = ? AND \"status\" = ?",
            listOf(Job.DONE, json(result), at(now), at(now), id, Job.RUNNING),
        )
    }

    /** Başarısız: hakkı varsa ve [retry] ise beklemeyle yeniden kuyruğa, yoksa `failed`. Yeni durumu döndürür. */
    fun fail(job: Job, error: String, retry: Boolean = true): String {
        val now = clock()
        val again = retry && job.attempts < job.maxAttempts
        val status = if (again) Job.QUEUED else Job.FAILED
        db.execute(
            "UPDATE $t SET \"status\" = ?, \"error\" = ?, \"run_at\" = ?, \"finished_at\" = ?, \"updated_at\" = ? WHERE \"id\" = ? AND \"status\" = ?",
            listOf(status, error.take(2000), at(if (again) now + backoff(job.attempts) else job.runAt), if (again) null else at(now), at(now), job.id, Job.RUNNING),
        )
        return status
    }

    /** Başarısız veya iptal edilmiş işi baştan (deneme sayısı sıfır) hemen çalışacak şekilde kuyruğa alır. */
    fun retry(id: Long): Job {
        val job = get(id)
        if (job.status != Job.FAILED && job.status != Job.CANCELLED) throw ApiException(409, tr("Yalnızca başarısız veya iptal edilmiş iş yeniden çalıştırılabilir"))
        val now = clock()
        db.execute(
            "UPDATE $t SET \"status\" = ?, \"attempts\" = 0, \"run_at\" = ?, \"error\" = NULL, \"finished_at\" = NULL, \"updated_at\" = ? WHERE \"id\" = ?",
            listOf(Job.QUEUED, at(now), at(now), id),
        )
        return get(id)
    }

    /** Bekleyen veya çalışan işi iptal eder (çalışan işin sonucu yok sayılır). */
    fun cancel(id: Long): Job {
        val job = get(id)
        if (job.status != Job.QUEUED && job.status != Job.RUNNING) throw ApiException(409, tr("İş zaten bitmiş ({0})", job.status))
        val now = clock()
        db.execute(
            "UPDATE $t SET \"status\" = ?, \"finished_at\" = ?, \"updated_at\" = ? WHERE \"id\" = ? AND \"status\" IN (?, ?)",
            listOf(Job.CANCELLED, at(now), at(now), id, Job.QUEUED, Job.RUNNING),
        )
        return get(id)
    }

    fun get(id: Long): Job = db.query("SELECT * FROM $t WHERE \"id\" = ?", listOf(id)).firstOrNull()?.let(::job)
        ?: throw ApiException(404, tr("İş bulunamadı"))

    /** Yeniden eskiye; [status]/[name] süzgeci. */
    fun list(status: String? = null, name: String? = null, page: Int = 1, perPage: Int = 50): Pair<List<Job>, Long> {
        if (status != null && status !in Job.STATUSES) throw ApiException(400, tr("Durum {0} olmalı", Job.STATUSES.joinToString("/")))
        val where = listOfNotNull(status?.let { "\"status\" = ?" }, name?.let { "\"name\" = ?" }).takeIf { it.isNotEmpty() }
            ?.joinToString(" AND ", prefix = " WHERE ").orEmpty()
        val args = listOfNotNull(status, name)
        val total = (db.query("SELECT COUNT(*) AS n FROM $t$where", args).first()["n"] as Number).toLong()
        val size = perPage.coerceIn(1, 200)
        val rows = db.query("SELECT * FROM $t$where ORDER BY \"id\" DESC LIMIT ? OFFSET ?", args + listOf(size, (page.coerceAtLeast(1) - 1) * size))
        return rows.map(::job) to total
    }

    /** Durum başına iş sayısı. */
    fun counts(): Map<String, Long> =
        db.query("SELECT \"status\", COUNT(*) AS n FROM $t GROUP BY \"status\"").associate { it["status"].toString() to (it["n"] as Number).toLong() }

    /**
     * Açılışta: önceki çalıştırmada yarım kalan işler (uygulama kapandı) yeniden kuyruğa. [only]/[except]: yalnızca o
     * türdeki / o tür dışındaki işler (her çalıştırıcı kendi işlerini alır; başkasının süren işine dokunmaz).
     */
    fun recover(only: String? = null, except: String? = null): Int {
        val now = clock()
        val name = when {
            only != null -> " AND \"name\" = ?"
            except != null -> " AND \"name\" <> ?"
            else -> ""
        }
        return db.execute("UPDATE $t SET \"status\" = ?, \"updated_at\" = ? WHERE \"status\" = ?$name", listOfNotNull(Job.QUEUED, at(now), Job.RUNNING, only ?: except))
    }

    /** Süren işin ara sonucu (ilerleme); iş bu arada iptal edildiyse false. */
    fun progress(id: Long, result: JsonElement): Boolean {
        val now = clock()
        return db.execute(
            "UPDATE $t SET \"result\" = ?, \"updated_at\" = ? WHERE \"id\" = ? AND \"status\" = ?",
            listOf(json(result), at(now), id, Job.RUNNING),
        ) == 1
    }

    /** [keepDays] günden eski biten, başarısız ve iptal edilmiş işleri siler. */
    fun purge(keepDays: Int): Int = db.execute(
        "DELETE FROM $t WHERE \"status\" IN (?, ?, ?) AND ${cmp("\"updated_at\"")} < ${cmp("?")}",
        listOf(Job.DONE, Job.FAILED, Job.CANCELLED, at(clock() - Duration.ofDays(keepDays.toLong()))),
    )

    private fun job(row: Map<String, Any?>): Job {
        fun time(c: String) = d.decode(FieldType.DATETIME, row[c]) as? Instant
        fun parsed(c: String) = (d.decode(FieldType.JSON, row[c]) as String?)?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }
        return Job(
            id = (row["id"] as Number).toLong(),
            name = row["name"].toString(),
            data = parsed("data") ?: JsonNull,
            status = row["status"].toString(),
            attempts = (row["attempts"] as Number).toInt(),
            maxAttempts = (row["max_attempts"] as Number).toInt(),
            runAt = time("run_at") ?: Instant.EPOCH,
            key = row["key"]?.toString(),
            result = parsed("result"),
            error = row["error"]?.toString(),
            createdAt = time("created_at"),
            startedAt = time("started_at"),
            finishedAt = time("finished_at"),
        )
    }

    companion object {
        const val MAX_ATTEMPTS = 20

        /** n. başarısız denemeden sonra bekleme: 30 sn × 2^(n-1), en çok 1 saat. */
        fun backoff(attempt: Int): Duration = Duration.ofSeconds((30L shl (attempt - 1).coerceIn(0, 7)).coerceAtMost(3600))
    }
}
