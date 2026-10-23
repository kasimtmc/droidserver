
// ---- Çevrimdışı eşitleme (koleksiyonda `sync` açıkken) ----

/** Çevrimdışı deponun saklama yeri: anahtar → JSON metni. SQLite gibi başka bir depo bu arayüzle takılabilir. */
interface OfflineStore {
    fun read(key: String): String?
    fun write(key: String, value: String)
}

/** Bellekte (uygulama kapanınca silinir; denemeler için). */
class MemoryOfflineStore : OfflineStore {
    private val values = java.util.concurrent.ConcurrentHashMap<String, String>()
    override fun read(key: String): String? = values[key]
    override fun write(key: String, value: String) { values[key] = value }
}

/** Klasörde anahtar başına bir dosya (Android'de `context.filesDir`). Yazma önce geçici dosyaya, sonra yerine. */
class FileOfflineStore(private val dir: File) : OfflineStore {
    private fun file(key: String) = File(dir, key.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json")

    override fun read(key: String): String? = file(key).takeIf { it.isFile }?.readText()

    override fun write(key: String, value: String) {
        dir.mkdirs()
        val target = file(key)
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeText(value)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }
}

/**
 * Eşitleme çakışması. [status]: `merged` (son yazan kazanır: bazı alanlarda sunucunun değeri kaldı) veya `conflict`
 * (elle çözme kipinde değişiklik uygulanmadı ya da silinen kayıt sunucuda daha yeni). [local]: gönderilen alanlar,
 * [server]: kaydın sunucudaki (artık yerelde de olan) hali. Elle çözmek için kaydı yeniden `update` edin.
 */
data class SyncConflictInfo(val id: Long, val status: String, val local: JsonObject, val server: JsonObject?)

/**
 * Bir koleksiyonun çevrimdışı kopyası. Okuma ve yazma yereldir; yazmalar kayıt başına birleştirilerek bekler ve
 * [sync] ile gönderilir, sonra sunucudaki değişiklikler çekilir. Çevrimdışı oluşturulan kaydın kimliği eşitlenene kadar
 * negatiftir. Dosya alanları yalnızca adıyla gelir (dosya indirilmez, çevrimdışıyken yüklenmez). Çevrimdışı oluşturulan
 * kayda başka koleksiyondan ilişki, kayıt eşitlendikten sonra kurulmalı. Durum her değişiklikte depoya yazılır.
 */
class OfflineCollection<T, I> internal constructor(
    private val client: DroidServerBase,
    val name: String,
    private val path: String,
    private val idField: String,
    private val serializer: KSerializer<T>,
    private val input: KSerializer<I>,
    private val store: OfflineStore,
    private val key: String,
) {
    /** Çakışma bildirimi (bkz. [SyncConflictInfo]). */
    var onConflict: ((SyncConflictInfo) -> Unit)? = null

    /** Sunucunun kabul etmediği (doğrulama, kural) ve kuyruktan çıkarılan değişiklik. */
    var onRejected: ((id: Long, error: DroidServerException) -> Unit)? = null

    private class Op(val op: String, var id: Long, val base: Long, val fields: LinkedHashMap<String, JsonElement>, val times: LinkedHashMap<String, Long>, var time: Long)

    private val lock = Any()
    private var cursor = 0L
    private var epoch: String? = null
    private var nextTemp = -1L
    private val records = LinkedHashMap<Long, Pair<Long, JsonObject>>()
    private val pending = mutableListOf<Op>()

    init {
        store.read(key)?.let { text ->
            val o = DroidServerJson.parseToJsonElement(text).jsonObject
            cursor = o["cursor"]?.jsonPrimitive?.content?.toLong() ?: 0
            epoch = (o["epoch"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            nextTemp = o["nextTemp"]?.jsonPrimitive?.content?.toLong() ?: -1
            (o["records"] as? JsonObject)?.forEach { (id, v) ->
                val r = v.jsonObject
                records[id.toLong()] = r["v"]!!.jsonPrimitive.content.toLong() to r["r"]!!.jsonObject
            }
            (o["pending"] as? JsonArray)?.forEach { e ->
                val p = e.jsonObject
                pending += Op(
                    p["op"]!!.jsonPrimitive.content, p["id"]!!.jsonPrimitive.content.toLong(), p["base"]!!.jsonPrimitive.content.toLong(),
                    LinkedHashMap(p["fields"]!!.jsonObject), LinkedHashMap(p["times"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content.toLong() }),
                    p["time"]!!.jsonPrimitive.content.toLong(),
                )
            }
        }
    }

    private fun save() {
        val o = buildJsonObject {
            put("cursor", JsonPrimitive(cursor))
            epoch?.let { put("epoch", JsonPrimitive(it)) }
            put("nextTemp", JsonPrimitive(nextTemp))
            put("records", JsonObject(records.entries.associate { (id, v) -> id.toString() to buildJsonObject { put("v", JsonPrimitive(v.first)); put("r", v.second) } }))
            put("pending", JsonArray(pending.map(::wire)))
        }
        store.write(key, o.toString())
    }

    private fun wire(op: Op) = buildJsonObject {
        put("op", JsonPrimitive(op.op))
        put("id", JsonPrimitive(op.id))
        if (op.op == "create") put("tempId", JsonPrimitive(op.id))
        put("base", JsonPrimitive(op.base))
        put("fields", JsonObject(op.fields))
        put("times", JsonObject(op.times.mapValues { JsonPrimitive(it.value) }))
        put("time", JsonPrimitive(op.time))
    }

    private fun decode(o: JsonObject): T = DroidServerJson.decodeFromJsonElement(serializer, o)

    private fun encode(data: I): JsonObject = DroidServerJson.encodeToJsonElement(input, data).jsonObject

    /** Yereldeki kayıtlar (bekleyen değişiklikler dahil). */
    fun all(): List<T> = synchronized(lock) { records.values.map { decode(it.second) } }

    fun get(id: Long): T? = synchronized(lock) { records[id]?.let { decode(it.second) } }

    /** Gönderilmeyi bekleyen değişiklik sayısı. */
    val pendingCount: Int get() = synchronized(lock) { pending.size }

    fun create(data: I): T = synchronized(lock) {
        val fields = encode(data)
        val id = nextTemp--
        val record = JsonObject(fields + (idField to JsonPrimitive(id)))
        records[id] = 0L to record
        val now = System.currentTimeMillis()
        pending += Op("create", id, 0, LinkedHashMap(fields), LinkedHashMap(fields.keys.associateWith { now }), now)
        save()
        decode(record)
    }

    fun update(id: Long, data: I): T = synchronized(lock) {
        val (version, record) = records[id] ?: throw DroidServerException(404, "Kayıt yerelde yok: " + id)
        val fields = encode(data)
        val now = System.currentTimeMillis()
        val merged = JsonObject(record + fields)
        records[id] = version to merged
        val op = pending.firstOrNull { it.id == id }
        if (op == null) {
            pending += Op("update", id, version, LinkedHashMap(fields), LinkedHashMap(fields.keys.associateWith { now }), now)
        } else {
            op.fields.putAll(fields)
            fields.keys.forEach { op.times[it] = now }
            op.time = now
        }
        save()
        decode(merged)
    }

    fun delete(id: Long): Unit = synchronized(lock) {
        val (version, _) = records.remove(id) ?: return
        val op = pending.firstOrNull { it.id == id }
        op?.let(pending::remove)
        // Henüz gönderilmemiş kayıt: sunucuya hiç gitmez.
        if (op?.op != "create") pending += Op("delete", id, op?.base ?: version, LinkedHashMap(), LinkedHashMap(), System.currentTimeMillis())
        save()
    }

    /**
     * Bekleyen değişiklikleri gönderir, sonra sunucudaki değişiklikleri çeker. Ağ hatasında [IOException] (bekleyenler
     * kalır). Bloklayıcıdır; aynı anda bir eşitleme çalışır.
     */
    fun sync(): Unit = synchronized(lock) {
        push()
        pull()
    }

    private fun push() {
        while (pending.isNotEmpty()) {
            val batch = pending.take(100)
            val body = buildJsonObject {
                put("now", JsonPrimitive(System.currentTimeMillis()))
                put("changes", JsonArray(batch.map(::wire)))
            }
            val results = client.request("POST", path, json = body)!!.jsonObject["results"]!!.jsonArray
            for ((op, r) in batch.zip(results)) {
                val o = r.jsonObject
                val status = o["status"]!!.jsonPrimitive.content
                val id = (o["id"] as? JsonPrimitive)?.content?.toLongOrNull()
                val version = (o["version"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0
                val server = o["record"] as? JsonObject
                pending.remove(op)
                when (status) {
                    "ok", "merged", "conflict" -> {
                        if (op.op == "create") records.remove(op.id)
                        if (server != null && id != null) records[id] = version to server else if (id != null) records.remove(id)
                        if (status != "ok") onConflict?.invoke(SyncConflictInfo(id ?: op.id, status, JsonObject(op.fields), server))
                    }
                    "gone" -> records.remove(op.id)
                    else -> {
                        val code = (o["code"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
                        val details = (o["details"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
                        val error = DroidServerException(code, (o["message"] as? JsonPrimitive)?.content ?: "HTTP " + code, details)
                        if (code >= 500) {
                            // Sunucu tarafı geçici hata: sonra yeniden denenir.
                            pending.add(0, op)
                            save()
                            throw error
                        }
                        onRejected?.invoke(op.id, error)
                    }
                }
            }
            save()
        }
    }

    private fun pull() {
        do {
            val query = buildMap {
                put("since", cursor.toString())
                put("limit", "500")
                epoch?.let { put("epoch", it) }
            }
            val r = client.request("GET", path, query)!!.jsonObject
            if (r["reset"]?.jsonPrimitive?.content == "true") {
                // Sunucudaki iz yenilendi: yerel kopya baştan kurulur (gönderilmemiş yeni kayıtlar kalır).
                records.keys.filter { it > 0 }.forEach(records::remove)
                cursor = 0
            }
            epoch = r["epoch"]?.jsonPrimitive?.content
            for (c in r["changes"]!!.jsonArray) {
                val o = c.jsonObject
                val id = o["id"]!!.jsonPrimitive.content.toLong()
                if (pending.any { it.id == id }) continue // yerel değişiklik bekliyor
                if (o["action"]!!.jsonPrimitive.content == "upsert") {
                    records[id] = o["version"]!!.jsonPrimitive.content.toLong() to o["record"]!!.jsonObject
                } else {
                    records.remove(id)
                }
            }
            cursor = r["next"]!!.jsonPrimitive.content.toLong()
            save()
        } while (r["more"]?.jsonPrimitive?.content == "true")
    }

    /**
     * Canlı abonelikle: sunucuda değişiklik olunca arka planda [sync] çalışır (art arda olaylar birleşir).
     * [Closeable.close] durdurur.
     */
    fun live(onError: (Exception) -> Unit = {}): Closeable {
        val busy = AtomicBoolean(false)
        val again = AtomicBoolean(false)
        fun run() {
            if (!busy.compareAndSet(false, true)) { again.set(true); return }
            Thread {
                try {
                    do {
                        again.set(false)
                        try { sync() } catch (e: Exception) { onError(e) }
                    } while (again.get())
                } finally {
                    busy.set(false)
                }
            }.apply { isDaemon = true }.start()
        }
        run()
        // Abonelik kurulunca (ve her yeniden bağlanmada) bir kez daha: ilk eşitlemeyle bağlantı arasındaki değişiklik kaçmasın.
        return client.subscribe(listOf(name), onError, onReady = { run() }) { run() }
    }
}
