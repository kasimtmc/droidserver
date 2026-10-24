package com.plyg.droidserver.core.ai

import com.plyg.droidserver.core.api.RecordService
import com.plyg.droidserver.core.api.Scope
import com.plyg.droidserver.core.api.WriteGate
import com.plyg.droidserver.core.config.SemanticIndexConfig
import com.plyg.droidserver.core.db.SqlDriver
import com.plyg.droidserver.core.i18n.tr
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/** Bir koleksiyonun dizin durumu (uygulama ve `/_ai`). */
data class SemanticStatus(val collection: String, val indexed: Int, val pending: Int)

/**
 * Anlamsal arama dizini: seçilen metin alanları birleştirilip [Embedder] ile vektöre çevrilir ve `_ds_embeddings` tablosunda
 * saklanır (yedeğe ve veri taşımaya girmez; eksikse açılışta yeniden kurulur). Arama bellekteki vektörlerle
 * kosinüs benzerliğidir; telefon ölçeğinde (on binlerce kayıt) milisaniyeler sürer.
 *
 * Kayıt olayları kuyruğa alınır ve tek bir arka plan iş parçacığında, toplu isteklerle dizinlenir. Açılışta metni
 * değişmiş veya dizinde olmayan kayıtlar tamamlanır (metnin özeti tutulur; model değişirse hepsi yenilenir). Gömme
 * servisine ulaşılamazsa bekleyenler bir dakika sonra yeniden denenir.
 */
class SemanticIndex(
    private val db: SqlDriver,
    indexes: List<SemanticIndexConfig>,
    private val services: Map<String, RecordService>,
    private val embedder: Embedder,
    /** Özete katılır: model değişince vektörler yenilenir. */
    private val model: String,
    private val writes: WriteGate,
    private val log: (String) -> Unit = {},
    /** Testlerde: olaylar çağıran iş parçacığında hemen işlenir. */
    private val synchronous: Boolean = false,
) : Closeable {
    private val fields: Map<String, List<String>> = indexes.associate { it.collection to it.fields }
    private val vectors = ConcurrentHashMap<String, ConcurrentHashMap<Long, FloatArray>>()
    private val pending = ConcurrentHashMap<String, MutableSet<Long>>()
    private val worker: ScheduledExecutorService? = if (synchronous) null else Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "semantic-index").apply { isDaemon = true }
    }

    @Volatile
    var lastError: String? = null
        private set

    fun indexes(collection: String) = collection in fields

    /** Tabloyu kurar, kayıtlı vektörleri belleğe alır ve eksikleri arka planda tamamlar. */
    fun start() {
        db.execute(
            "CREATE TABLE IF NOT EXISTS $TABLE (\"collection\" TEXT NOT NULL, \"record\" BIGINT NOT NULL, \"hash\" TEXT NOT NULL, " +
                "\"vector\" TEXT NOT NULL, PRIMARY KEY (\"collection\", \"record\"))",
        )
        // Artık dizinlenmeyen koleksiyonların vektörleri silinir.
        val known = fields.keys.toList()
        if (known.isEmpty()) db.execute("DELETE FROM $TABLE")
        else db.execute("DELETE FROM $TABLE WHERE \"collection\" NOT IN (${known.joinToString { "?" }})", known)
        submit { backfill() }
    }

    /** Kayıt olayı: silinen dizinden çıkar, eklenen/güncellenen kuyruğa girer. */
    fun onRecord(collection: String, deleted: Boolean, record: JsonObject) {
        if (collection !in fields) return
        val id = (record["id"] as? JsonPrimitive)?.longOrNull ?: return
        if (deleted) {
            vectors[collection]?.remove(id)
            submit { runCatching { writes.write { db.execute("DELETE FROM $TABLE WHERE \"collection\" = ? AND \"record\" = ?", listOf(collection, id)) } } }
            return
        }
        pending.computeIfAbsent(collection) { ConcurrentHashMap.newKeySet() }.add(id)
        submit { flush() }
    }

    /**
     * [query]'ye anlamca en yakın kayıtlar (kimlik ve benzerlik, büyükten küçüğe); kurallar çağıran tarafından uygulanır.
     * Gömme servisine bir istek yapar.
     */
    fun search(collection: String, query: String, limit: Int): List<Pair<Long, Float>> {
        val all = vectors[collection] ?: return emptyList()
        if (all.isEmpty() || query.isBlank()) return emptyList()
        val q = normalize(embedder.embed(listOf(query.take(MAX_TEXT)), query = true).single())
        return all.entries.asSequence()
            .filter { it.value.size == q.size }
            .map { it.key to dot(q, it.value) }
            .sortedByDescending { it.second }
            .take(limit)
            .toList()
    }

    fun status(): List<SemanticStatus> = fields.keys.map { c -> SemanticStatus(c, vectors[c]?.size ?: 0, pending[c]?.size ?: 0) }

    /** Tüm vektörleri siler ve baştan kurar (yönetici). */
    fun rebuild() = submit {
        runCatching { writes.write { db.execute("DELETE FROM $TABLE") } }
        vectors.clear()
        backfill()
    }

    override fun close() {
        worker?.shutdownNow()
    }

    private fun submit(task: () -> Unit) {
        val safe = { runCatching(task).onFailure { fail(it) }; Unit }
        val w = worker
        if (w == null) safe() else runCatching { w.execute(safe) }
    }

    private fun fail(e: Throwable) {
        val msg = e.message ?: e.toString()
        if (msg != lastError) log(tr("Anlamsal dizin: {0}", msg))
        lastError = msg
        // Bekleyenler bir dakika sonra yeniden denenir.
        val w = worker
        if (w != null && pending.values.any { it.isNotEmpty() }) runCatching { w.schedule({ submit { flush() } }, 60, TimeUnit.SECONDS) }
    }

    private fun backfill() {
        for ((collection, names) in fields) {
            val svc = services[collection] ?: continue
            val stored = db.query("SELECT \"record\", \"hash\", \"vector\" FROM $TABLE WHERE \"collection\" = ?", listOf(collection))
                .associate { (it["record"] as Number).toLong() to (it["hash"] as String to it["vector"] as String) }
            val map = vectors.computeIfAbsent(collection) { ConcurrentHashMap() }
            val seen = HashSet<Long>()
            val stale = mutableListOf<Long>()
            var last = 0L
            while (true) {
                val page = svc.list(1, PAGE, "id", listOf("id[gt]" to last.toString()), fields = listOf("id") + names, scope = Scope.ADMIN).items
                if (page.isEmpty()) break
                for (r in page) {
                    val id = (r["id"] as JsonPrimitive).longOrNull ?: continue
                    last = id
                    seen += id
                    val text = text(collection, r)
                    val old = stored[id]
                    when {
                        text.isEmpty() -> if (old != null) remove(collection, id)
                        old != null && old.first == hash(text) -> map[id] = decode(old.second)
                        else -> stale += id
                    }
                }
                if (page.size < PAGE) break
            }
            for (id in stored.keys - seen) remove(collection, id)
            if (stale.isNotEmpty()) pending.computeIfAbsent(collection) { ConcurrentHashMap.newKeySet() }.addAll(stale)
        }
        flush()
        lastError = null
    }

    private fun flush() {
        for ((collection, ids) in pending) {
            val svc = services[collection] ?: continue
            val names = fields.getValue(collection)
            while (ids.isNotEmpty()) {
                val batch = ids.take(BATCH)
                val records = svc.list(1, BATCH, null, listOf("id[in]" to batch.joinToString(",")), fields = listOf("id") + names, scope = Scope.ADMIN).items
                    .associateBy { (it["id"] as JsonPrimitive).longOrNull }
                val texts = batch.mapNotNull { id -> records[id]?.let { id to text(collection, it) } }
                for (id in batch.filter { records[it] == null }) remove(collection, id)
                for ((id, _) in texts.filter { it.second.isEmpty() }) remove(collection, id)
                val work = texts.filter { it.second.isNotEmpty() }
                if (work.isNotEmpty()) {
                    val embedded = embedder.embed(work.map { it.second }, query = false)
                    writes.write {
                        db.transaction {
                            work.forEachIndexed { i, (id, text) ->
                                val v = normalize(embedded[i])
                                db.execute("DELETE FROM $TABLE WHERE \"collection\" = ? AND \"record\" = ?", listOf(collection, id))
                                db.execute(
                                    "INSERT INTO $TABLE (\"collection\", \"record\", \"hash\", \"vector\") VALUES (?, ?, ?, ?)",
                                    listOf(collection, id, hash(text), encode(v)),
                                )
                                vectors.computeIfAbsent(collection) { ConcurrentHashMap() }[id] = v
                            }
                        }
                    }
                }
                ids.removeAll(batch.toSet())
            }
        }
        lastError = null
    }

    private fun remove(collection: String, id: Long) {
        vectors[collection]?.remove(id)
        writes.write { db.execute("DELETE FROM $TABLE WHERE \"collection\" = ? AND \"record\" = ?", listOf(collection, id)) }
    }

    /** Dizinlenen metin: "alan: değer" satırları (boş alanlar atlanır). */
    internal fun text(collection: String, record: JsonObject): String {
        return fields.getValue(collection).mapNotNull { name ->
            val value = plain(record[name]).trim()
            if (value.isEmpty()) null else "$name: $value"
        }.joinToString("\n").take(MAX_TEXT)
    }

    private fun plain(e: JsonElement?): String = when (e) {
        null, JsonNull -> ""
        is JsonPrimitive -> e.content
        is JsonArray -> e.joinToString(", ") { plain(it) }
        is JsonObject -> e.toString()
    }

    private fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest("$model\n$text".toByteArray()).joinToString("") { "%02x".format(it) }.take(32)

    companion object {
        const val TABLE = "\"_ds_embeddings\""
        private const val PAGE = 500
        private const val BATCH = 32
        private const val MAX_TEXT = 8000

        fun normalize(v: FloatArray): FloatArray {
            var sum = 0.0
            for (x in v) sum += x * x
            val n = sqrt(sum).toFloat().takeIf { it > 0f } ?: return v
            return FloatArray(v.size) { v[it] / n }
        }

        fun dot(a: FloatArray, b: FloatArray): Float {
            var s = 0f
            for (i in a.indices) s += a[i] * b[i]
            return s
        }

        internal fun encode(v: FloatArray): String {
            val buf = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            v.forEach { buf.putFloat(it) }
            return Base64.getEncoder().encodeToString(buf.array())
        }

        internal fun decode(s: String): FloatArray {
            val buf = ByteBuffer.wrap(Base64.getDecoder().decode(s)).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(buf.remaining() / 4) { buf.getFloat() }
        }
    }
}
