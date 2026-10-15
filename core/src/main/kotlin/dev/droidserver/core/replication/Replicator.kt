package dev.droidserver.core.replication

import dev.droidserver.core.config.ConfigJson
import dev.droidserver.core.config.FieldType
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.db.SqlDriver
import dev.droidserver.core.db.quoteIdent
import dev.droidserver.core.files.FileStore
import dev.droidserver.core.i18n.tr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant

/** Kopyanın durumu (uygulamadaki kart ve panel). */
data class ReplicaStatus(
    val phase: Phase = Phase.CONNECTING,
    /** Uygulanan son sıra ve anadaki son sıra. */
    val appliedSeq: Long = 0,
    val latestSeq: Long = 0,
    val lastContact: Instant? = null,
    val lastApplied: Instant? = null,
    val error: String? = null,
    val progress: String? = null,
    /** Otomatik devralma açıksa: ana sunucuya ulaşılamazsa devralınacak an; ulaşılıyorsa null. */
    val takeoverAt: Instant? = null,
    /** Devralma veya geri alma neden yapılmadı / ne zaman yapılacak. */
    val failoverNote: String? = null,
    /** Ana sunucunun kimliği (elle devralmada eski ana sunucuyu tanımak için). */
    val primaryId: String? = null,
) {
    enum class Phase(val title: String) {
        CONNECTING(tr("Bağlanıyor")), INITIAL(tr("İlk kopyalama")), SYNCING(tr("Eşit")), WAITING_CONFIG(tr("Ana sunucunun konfigürasyonu değişti, kopya yeniden kuruluyor")),
        ERROR(tr("Hata")), STOPPED(tr("Durdu")), PROMOTING(tr("Ana sunucu oluyor")),
    }

    /** Anadan geride kalan değişiklik sayısı. */
    val behind: Long get() = (latestSeq - appliedSeq).coerceAtLeast(0)
}

/** Kopyanın kaldığı yer (profil klasöründe `replica-state.json`); ana adres değişince sıfırlanır. */
@Serializable
private data class ReplicaState(val primary: String, val seq: Long = 0, val initialDone: Boolean = false)

/**
 * Ana sunucudan sürekli çeker ve bu veritabanına uygular (kopya salt okunur çalışır; yazmayı yalnızca bu sınıf
 * yapar). İlk kurulumda tablolar sayfa sayfa kopyalanır, sonra `_replication/changes` ile artımlı. Ana sunucunun
 * konfigürasyonu değişirse (özet farklı) [onPrimaryConfig] çağrılır ve uygulama yeniden kurulana kadar beklenir.
 */
class Replicator(
    private val db: SqlDriver,
    private val config: ServerConfig,
    private val files: FileStore? = null,
    private val stateFile: File? = null,
    private val log: (String) -> Unit = {},
    private val onPrimaryConfig: ((ServerConfig, String) -> Unit)? = null,
    private val status: MutableStateFlow<ReplicaStatus?> = MutableStateFlow(null),
    /** Devralma ve geri alma (bkz. [FailoverConfig]); yoksa yalnızca elle devralınır. */
    private val failoverHost: FailoverHost? = null,
    private val timing: FailoverTiming = FailoverTiming(),
    /** Telefonun bir ağa bağlı olup olmadığı (app); bağlı değilse kopan bu sunucudur, devralınmaz. */
    private val networkUp: () -> Boolean = { true },
) {
    private val source = config.replication.replicaOf ?: throw IllegalArgumentException(tr("Bu sunucu kopya değil"))
    private val base = source.url.trimEnd('/')
    private val tables = ChangeLog.tables(config)
    private val byName = tables.associateBy { it.name }
    private val columns = HashMap<String, Set<String>>()
    private var state = readState()
    private var notifiedHash: String? = null
    @Volatile private var stopped = false
    private var thread: Thread? = null
    private val failover = config.replication.failover
    /** Ana sunucudan son HTTP yanıtı (hata kodu dahil: sunucu ayakta). */
    @Volatile private var lastResponse = Instant.now()
    /** Ana sunucunun kimliği (`X-Instance-Id`). */
    @Volatile private var primaryId: String? = source.id
    private var caughtUpSince: Instant? = null

    @Synchronized
    fun start() {
        if (thread != null) return
        status.value = ReplicaStatus(appliedSeq = state.seq)
        thread = Thread({
            var failures = 0
            while (!stopped) {
                val more = try {
                    syncOnce().also { failures = 0 }
                } catch (e: Exception) {
                    if (stopped) break
                    failures++
                    status.update { (it ?: ReplicaStatus()).copy(phase = ReplicaStatus.Phase.ERROR, error = e.message ?: e.javaClass.simpleName) }
                    if (failures == 1 || failures % 20 == 0) log("Kopya: ${e.message}")
                    false
                }
                if (!stopped) runCatching { checkFailover() }.onFailure { log("Kopya: devralma denetimi: ${it.message}") }
                if (!more && !stopped) sleep(if (failures > 0) minOf(failoverWait(), 2_000L shl minOf(failures, 5)) else source.intervalSec * 1000L)
            }
        }, "ds-replica").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        stopped = true
        thread?.interrupt()
        // Süren bir yazma işlemi bitsin (deploy hemen ardından şemayı değiştirir); kendi iş parçacığından çağrılırsa beklemez.
        thread?.takeIf { it !== Thread.currentThread() }?.join(15_000)
        thread = null
        status.update { it?.copy(phase = ReplicaStatus.Phase.STOPPED) }
    }

    /** Baştan eşitle (bir sonraki turda tablolar yeniden kopyalanır). */
    fun resync() {
        state = state.copy(initialDone = false)
        writeState()
    }

    /** Tek tur: gerekiyorsa ilk kopyalama, sonra bir parti değişiklik. Hemen devam edilecek iş varsa true. */
    fun syncOnce(): Boolean {
        val (batch, hash) = get("/_replication/changes", "after=${state.seq}&limit=$BATCH")
        val latest = batch.getValue("latest").jsonPrimitive.long
        status.update { (it ?: ReplicaStatus()).copy(lastContact = Instant.now(), latestSeq = latest, error = null) }
        if (hash != null && hash != source.configHash && onPrimaryConfig != null) {
            if (notifiedHash != hash) {
                notifiedHash = hash
                val (cfg, _) = get("/_replication/config", "")
                log("Kopya: ana sunucunun konfigürasyonu değişti; kopya yeniden kuruluyor")
                onPrimaryConfig.invoke(ConfigJson.decodeFromJsonElement(ServerConfig.serializer(), cfg.getValue("config")), hash)
            }
            status.update { it?.copy(phase = ReplicaStatus.Phase.WAITING_CONFIG) }
            return false
        }
        if (!state.initialDone || batch["reset"]?.jsonPrimitive?.content == "true") {
            initial(latest)
            return true
        }
        val changes = batch.getValue("changes").jsonArray.map { it.jsonObject }
        apply(changes)
        state = state.copy(seq = batch.getValue("next").jsonPrimitive.long)
        writeState()
        status.update {
            (it ?: ReplicaStatus()).copy(
                phase = ReplicaStatus.Phase.SYNCING, appliedSeq = state.seq, latestSeq = maxOf(latest, state.seq),
                lastApplied = if (changes.isNotEmpty()) Instant.now() else it?.lastApplied ?: Instant.now(), progress = null,
            )
        }
        return changes.size >= BATCH || state.seq < latest
    }

    /** Hata sonrası bekleme: otomatik devralma açıksa devralma anını kaçırmayacak kadar kısa. */
    private fun failoverWait(): Long = if (failover.auto && failoverHost != null) minOf(60_000L, failover.afterSec * timing.afterScaleMs / 4).coerceAtLeast(100) else 60_000L

    /**
     * Otomatik devralma: ilk kopyalama bitmiş, ana sunucudan [FailoverConfig.afterSec] boyunca hiç yanıt yok, bu
     * telefon ağda ve tanık (varsa) ulaşılabilir. Geri alma: bu sunucu devredilmiş eski ana sunucuysa ve kopya bir süredir
     * güncelse, ana sunucuya yazmayı durdurtup son değişikliği alır ve ana rolünü kayıpsız geri alır.
     */
    private fun checkFailover() {
        val host = failoverHost ?: return
        val now = Instant.now()
        if (failover.auto && state.initialDone) {
            val at = lastResponse.plusMillis(failover.afterSec * timing.afterScaleMs)
            status.update { it?.copy(takeoverAt = at) }
            if (!now.isBefore(at)) {
                val blocked = when {
                    !networkUp() -> tr("Bu telefon ağa bağlı değil; devralınmadı")
                    failover.witness != null && !Failover.reachable(failover.witness) -> tr("Tanık adresine ({0}) ulaşılamıyor; kopan bu telefon olabilir, devralınmadı", failover.witness)
                    else -> null
                }
                if (blocked != null) {
                    if (status.value?.failoverNote != blocked) log("Kopya: $blocked")
                    status.update { it?.copy(failoverNote = blocked) }
                    return
                }
                promote(host, "replica.failover", tr("Ana sunucuya ({0}) {1} sn ulaşılamadı; bu sunucu devraldı", source.url, failover.afterSec))
                return
            }
        }
        if (failover.failback && source.demoted) {
            val s = status.value
            if (s == null || s.phase != ReplicaStatus.Phase.SYNCING || s.behind > 0) {
                caughtUpSince = null
                return
            }
            val since = caughtUpSince ?: now.also { caughtUpSince = it }
            if (Duration.between(since, now).toMillis() >= timing.caughtUpMs) failback(host)
        }
    }

    /** Ana sunucuya yazmayı durdurtur, son değişikliğe kadar eşitler ve devralır; yetişemezse ana sunucu sürer. */
    private fun failback(host: FailoverHost) {
        log("Kopya: ana rol geri alınıyor; ${source.url} yazmaları durduruyor")
        val latest = try {
            Failover.post(base, "/_replication/step-down", source.key, "{}").getValue("latest").jsonPrimitive.long
        } catch (e: Exception) {
            caughtUpSince = null
            throw e
        }
        val deadline = Instant.now().plusMillis(timing.caughtUpMs.coerceAtMost(30_000))
        while (!stopped && state.seq < latest && Instant.now().isBefore(deadline)) {
            if (!syncOnce()) sleep(200)
        }
        if (state.seq >= latest && !stopped) {
            promote(host, "replica.failback", tr("Ana rol geri alındı (önceki ana sunucu: {0}); veri kaybı yok", source.url))
        } else {
            caughtUpSince = null
            runCatching { Failover.post(base, "/_replication/step-up", source.key, "{}") }
            log("Kopya: ana rol geri alınamadı (son değişikliğe yetişilemedi); daha sonra yeniden denenecek")
        }
    }

    private fun promote(host: FailoverHost, event: String, detail: String) {
        stopped = true
        status.update { (it ?: ReplicaStatus()).copy(phase = ReplicaStatus.Phase.PROMOTING, failoverNote = detail) }
        log("Kopya: $detail")
        val id = primaryId
        host.change(event, detail, false) { Failover.promote(it, id) }
    }

    /** Tüm tabloları kopyalar (anada olmayan yerel satırlar silinir); sonra [latest]'ten devam. */
    private fun initial(latest: Long) {
        log("Kopya: ilk kopyalama başladı (${tables.size} tablo)")
        val seen = HashMap<String, MutableSet<Long>>()
        for (c in tables) {
            var after = 0L
            var count = 0
            val ids = seen.getOrPut(c.name) { HashSet() }
            while (true) {
                if (stopped) return
                status.update { (it ?: ReplicaStatus()).copy(phase = ReplicaStatus.Phase.INITIAL, progress = tr("{0}: {1} kayıt", c.name, count)) }
                val (page, _) = get("/_replication/snapshot", "table=${enc(c.name)}&after=$after&limit=$BATCH")
                val rows = page.getValue("rows").jsonArray.map { it.jsonObject }
                db.transaction { rows.forEach { upsert(c.name, it) } }
                rows.forEach { r -> val id = r.getValue("id").jsonPrimitive.long; ids += id; syncFiles(c.name, id, r) }
                count += rows.size
                after = rows.lastOrNull()?.getValue("id")?.jsonPrimitive?.long ?: after
                if (page.getValue("done").jsonPrimitive.content == "true") break
            }
            resetSequence(c.name)
        }
        // Anada olmayanlar (yeniden eşitlemede): önce bağlı tablolar.
        for (c in tables.asReversed()) {
            val local = db.query("SELECT \"id\" FROM ${quoteIdent(c.name)}").map { (it["id"] as Number).toLong() }
            val gone = local.filter { it !in seen[c.name].orEmpty() }
            gone.chunked(500).forEach { chunk -> db.execute("DELETE FROM ${quoteIdent(c.name)} WHERE \"id\" IN (${chunk.joinToString(",") { "?" }})", chunk) }
            gone.forEach { files?.deleteRecord(c.name, it) }
        }
        state = state.copy(seq = latest, initialDone = true)
        writeState()
        log("Kopya: ilk kopyalama bitti")
    }

    private fun apply(changes: List<JsonObject>) {
        if (changes.isEmpty()) return
        changes.map { it.getValue("table").jsonPrimitive.content }.firstOrNull { it !in byName }?.let {
            throw IOException(tr("'{0}' tablosu bu kopyada yok; ana sunucunun konfigürasyonuyla yeniden kurulmalı", it))
        }
        db.transaction {
            for (ch in changes) {
                val table = ch.getValue("table").jsonPrimitive.content
                val id = ch.getValue("id").jsonPrimitive.long
                if (ch.getValue("action").jsonPrimitive.content == "delete") {
                    db.execute("DELETE FROM ${quoteIdent(table)} WHERE \"id\" = ?", listOf(id))
                } else {
                    upsert(table, ch.getValue("row").jsonObject)
                }
            }
        }
        for (ch in changes) {
            val table = ch.getValue("table").jsonPrimitive.content
            val id = ch.getValue("id").jsonPrimitive.long
            if (ch.getValue("action").jsonPrimitive.content == "delete") files?.deleteRecord(table, id)
            else syncFiles(table, id, ch.getValue("row").jsonObject)
        }
        changes.map { it.getValue("table").jsonPrimitive.content }.distinct().forEach(::resetSequence)
    }

    /** Varsa günceller, yoksa aynı kimlikle ekler (yabancı anahtar zincirleri tetiklenmesin diye silip eklemez). */
    private fun upsert(table: String, row: JsonObject) {
        val c = byName.getValue(table)
        val types = RowCodec.types(c)
        val local = columns.getOrPut(table) { db.dialect.listColumns(db, table).keys }
        val id = row.getValue("id").jsonPrimitive.long
        val cols = row.keys.filter { it in local && it != "id" }
        val values = cols.map { RowCodec.decode(db.dialect, types[it], row.getValue(it)) }
        val updated = if (cols.isEmpty()) 0 else db.execute(
            "UPDATE ${quoteIdent(table)} SET ${cols.joinToString(", ") { "${quoteIdent(it)} = ?" }} WHERE \"id\" = ?", values + id,
        )
        if (updated == 0 && db.query("SELECT 1 AS x FROM ${quoteIdent(table)} WHERE \"id\" = ?", listOf(id)).isEmpty()) {
            db.execute(db.dialect.insertWithIdSql(table, listOf("id") + cols), listOf<Any?>(id) + values)
        }
    }

    /** Kaydın dosya alanlarındaki dosyalar: eksikler indirilir, artık kullanılmayanlar silinir. */
    private fun syncFiles(table: String, id: Long, row: JsonObject) {
        val store = files ?: return
        val fileFields = byName[table]?.fields?.filter { it.type == FieldType.FILE }.orEmpty()
        if (fileFields.isEmpty()) return
        val names = fileFields.flatMap { f ->
            val v = row[f.name] as? JsonPrimitive ?: return@flatMap emptyList()
            val text = v.content
            if (f.isMultiple) runCatching { Json.parseToJsonElement(text).jsonArray.map { it.jsonPrimitive.content } }.getOrDefault(emptyList()) else listOf(text)
        }.filter { it.isNotBlank() }.toSet()
        for (name in names) {
            val target = store.file(table, id, name) ?: continue
            if (target.isFile) continue
            target.parentFile.mkdirs()
            val tmp = store.newTemp()
            download("/files/${enc(table)}/$id/${enc(name)}", tmp)
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        store.recordDir(table, id).listFiles { f -> f.isFile && f.name !in names }?.forEach { store.delete(table, id, it.name) }
    }

    private fun resetSequence(table: String) {
        db.dialect.resetIdSequenceSql(table)?.let { runCatching { db.query(it) } }
    }

    private fun get(path: String, query: String): Pair<JsonObject, String?> {
        val conn = open(path + if (query.isEmpty()) "" else "?$query")
        try {
            val code = conn.responseCode
            lastResponse = Instant.now()
            conn.getHeaderField("X-Instance-Id")?.takeIf { it != primaryId }?.let { id -> primaryId = id; status.update { it?.copy(primaryId = id) } }
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
            if (code !in 200..299) {
                val msg = runCatching { Json.parseToJsonElement(text).jsonObject["message"]?.jsonPrimitive?.content }.getOrNull()
                throw IOException(tr("Ana sunucu {0}", code) + (msg?.let { ": $it" } ?: ""))
            }
            return Json.parseToJsonElement(text).jsonObject to conn.getHeaderField("X-Config-Hash")
        } finally {
            conn.disconnect()
        }
    }

    private fun download(path: String, target: File) {
        val conn = open(path)
        try {
            val code = conn.responseCode
            lastResponse = Instant.now()
            if (code != 200) throw IOException(tr("Dosya indirilemedi ({0}): {1}", path, code))
            conn.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(path: String): HttpURLConnection = (URI(base + path).toURL().openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 60_000
        instanceFollowRedirects = false
        setRequestProperty("Authorization", "Bearer ${source.key}")
        setRequestProperty("Accept-Encoding", "identity")
        // Ana sunucu açılışta "beni devralan var mı" diye bu adresi sorar (IP bağlantıdan).
        setRequestProperty("X-Replica-Port", config.server.port.toString())
        setRequestProperty("X-Replica-Path", config.server.basePath)
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }

    private fun readState(): ReplicaState {
        val saved = runCatching { stateFile?.takeIf { it.isFile }?.let { Json.decodeFromString(ReplicaState.serializer(), it.readText()) } }.getOrNull()
        return saved?.takeIf { it.primary == base } ?: ReplicaState(base)
    }

    private fun writeState() {
        val f = stateFile ?: return
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(Json.encodeToString(ReplicaState.serializer(), state))
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        const val BATCH = 500

        /** Kurulum: ana sunucunun konfigürasyonu ve özeti (sihirbazda "Bağlan ve kopyala"). */
        fun fetchPrimary(url: String, key: String): Pair<ServerConfig, String> {
            val conn = (URI(url.trimEnd('/') + "/_replication/config").toURL().openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 30_000
                setRequestProperty("Authorization", "Bearer $key")
                setRequestProperty("Accept-Encoding", "identity")
            }
            try {
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
                if (code !in 200..299) {
                    val msg = runCatching { Json.parseToJsonElement(text).jsonObject["message"]?.jsonPrimitive?.content }.getOrNull()
                    throw IOException(
                        when (code) {
                            401, 403 -> tr("Anahtar kabul edilmedi (ana sunucunun yönetici anahtarı gerekli)")
                            404 -> msg ?: tr("Ana sunucu kopyalanabilir değil")
                            else -> tr("Ana sunucu {0}", code) + (msg?.let { ": $it" } ?: "")
                        },
                    )
                }
                val body = Json.parseToJsonElement(text).jsonObject
                return ConfigJson.decodeFromJsonElement(ServerConfig.serializer(), body.getValue("config")) to body.getValue("hash").jsonPrimitive.content
            } finally {
                conn.disconnect()
            }
        }
    }
}
