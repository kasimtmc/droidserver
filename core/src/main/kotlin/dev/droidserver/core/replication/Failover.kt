package dev.droidserver.core.replication

import dev.droidserver.core.config.ReplicaSource
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.i18n.tr
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Devralmanın süreleri (testler kısaltır). */
data class FailoverTiming(
    /** Ana sunucuya ulaşılamayan süre bu kadarla çarpılır (1: saniye). */
    val afterScaleMs: Long = 1_000,
    /** Devralan sunucu eski ana sunucuyu bu aralıkla dener. */
    val fenceIntervalMs: Long = 15_000,
    /** Geri almadan önce kopyanın güncel kalması gereken süre. */
    val caughtUpMs: Long = 60_000,
    /** Rolünü devretmek için duran ana sunucu, devir gelmezse bu süre sonra yeniden yazılabilir olur. */
    val stepDownMs: Long = 120_000,
    /** Açılışta bilinen kopyaların sorulması (her biri). */
    val probeTimeoutMs: Int = 3_000,
)

/**
 * Konfigürasyonu kalıcı olarak değiştirip yeniden deploy eden taraf (app: şifreli depo, denetim kaydı, servis).
 * [event] denetim kaydına, [backupFirst]: önce mevcut verinin yedeği alınır. Yeniden deploy çağıranın iş parçacığında
 * yapılmamalı (kopya iş parçacığından çağrılır).
 */
fun interface FailoverHost {
    fun change(event: String, detail: String, backupFirst: Boolean, edit: (ServerConfig) -> ServerConfig)
}

/**
 * Sunucu modülünün devralma bağlantısı: kalıcı [instanceId], kopyaların adresleri ([replicaSeen]) ve "devredildin"
 * bildirimi ([fenced]: bu sunucu [url]'deki yeni ana sunucunun kopyası olmalı; anahtar isteği yapanınki).
 */
class FailoverPort(
    val instanceId: String,
    val timing: FailoverTiming = FailoverTiming(),
    val replicaSeen: (String) -> Unit = {},
    val fenced: (url: String, key: String) -> Unit = { _, _ -> },
) {
    /** Devredildi (yeniden deploy edilene kadar salt okunur). */
    @Volatile
    var isFenced: Boolean = false
}

/** Devralma ve geri alma için konfigürasyon dönüşümleri ve yardımcılar. */
object Failover {
    /** Kopya ana sunucu olur: kopyalanabilir kalır (öbürü ona bağlanabilsin), eski ana sunucu hatırlanır. */
    fun promote(config: ServerConfig, primaryId: String? = null): ServerConfig {
        val src = config.replication.replicaOf ?: return config
        return config.copy(
            replication = config.replication.copy(
                replicaOf = null, source = true,
                formerPrimary = ReplicaSource(src.url, src.key, id = primaryId ?: src.id),
            ),
        )
    }

    /** Ana sunucu devredildi: [url]'deki yeni ana sunucunun kopyası olur (veriler baştan oradan alınır). */
    fun demote(config: ServerConfig, url: String, key: String): ServerConfig = config.copy(
        replication = config.replication.copy(
            source = false, formerPrimary = null,
            replicaOf = ReplicaSource(url.trimEnd('/'), key, intervalSec = config.replication.replicaOf?.intervalSec ?: 5, demoted = true),
        ),
    )

    /** Profil klasöründe `instance-id`: sunucunun kalıcı kimliği (devralanın eski ana sunucuyu tanıması için). */
    fun instanceId(dataDir: File?): String {
        val file = dataDir?.let { File(it, "instance-id") } ?: return UUID.randomUUID().toString()
        runCatching { file.readText().trim().takeIf { it.length in 8..64 } }.getOrNull()?.let { return it }
        val id = UUID.randomUUID().toString()
        runCatching { file.parentFile?.mkdirs(); file.writeText(id) }
        return id
    }

    /** Bir sunucunun rolü (`/_replication/role`; kimlik doğrulamasız, yalnızca kimlikler). Ulaşılamazsa null. */
    fun role(url: String, timeoutMs: Int): Role? = runCatching {
        val conn = (URI(url.trimEnd('/') + "/_replication/role").toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = false
        }
        try {
            if (conn.responseCode != 200) return@runCatching null
            val o = Json.parseToJsonElement(conn.inputStream.use { it.readBytes().decodeToString() }).jsonObject
            Role(o.getValue("role").jsonPrimitive.content, o["id"]?.jsonPrimitive?.content, o["formerPrimaryId"]?.jsonPrimitive?.content)
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    data class Role(val role: String, val id: String?, val formerPrimaryId: String?)

    /** Yönetici isteği (fence, step-down); yanıt gövdesi. 2xx değilse IOException. */
    fun post(url: String, path: String, key: String, body: String, timeoutMs: Int = 10_000, headers: Map<String, String> = emptyMap()): JsonObject {
        val conn = (URI(url.trimEnd('/') + path).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = false
            doOutput = true
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Content-Type", "application/json")
            headers.forEach(::setRequestProperty)
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
            if (code !in 200..299) {
                val msg = runCatching { Json.parseToJsonElement(text).jsonObject["message"]?.jsonPrimitive?.content }.getOrNull()
                throw IOException(tr("Sunucu {0}", code) + (msg?.let { ": $it" } ?: ""))
            }
            return runCatching { Json.parseToJsonElement(text).jsonObject }.getOrDefault(JsonObject(emptyMap()))
        } finally {
            conn.disconnect()
        }
    }

    /** Tanık adresine (herhangi bir HTTP yanıtı yeter) ulaşılabiliyor mu. */
    fun reachable(url: String, timeoutMs: Int = 5_000): Boolean = runCatching {
        val conn = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = false
            requestMethod = "HEAD"
        }
        try {
            conn.responseCode > 0
        } finally {
            conn.disconnect()
        }
    }.getOrDefault(false)
}

/**
 * Ana sunucuya bağlanan kopyaların adresleri (`replicas.json`): açılışta "beni devralan var mı" diye sorulur. Adres,
 * kopyanın IP'si ile bildirdiği port ve API yolundan kurulur.
 */
class ReplicaRegistry(private val file: File?) {
    @Serializable
    data class Entry(val url: String, val lastSeen: String)

    private val entries = LinkedHashMap<String, Instant>()
    private var lastWrite = Instant.EPOCH

    init {
        runCatching {
            file?.takeIf { it.isFile }?.let { f ->
                Json.decodeFromString(ListSerializer(Entry.serializer()), f.readText()).forEach { entries[it.url] = Instant.parse(it.lastSeen) }
            }
        }
    }

    @Synchronized
    fun seen(url: String, now: Instant = Instant.now()) {
        val isNew = url !in entries
        entries[url] = now
        // Her eşitleme turunda değil: yeni adres veya saatte bir.
        if (isNew || Duration.between(lastWrite, now) > Duration.ofHours(1)) save(now)
    }

    /** Son [days] günde görülenler, en yenisi önce. */
    @Synchronized
    fun recent(days: Long = 7, now: Instant = Instant.now()): List<String> =
        entries.filter { Duration.between(it.value, now).toDays() < days }.entries.sortedByDescending { it.value }.map { it.key }

    private fun save(now: Instant) {
        val f = file ?: return
        lastWrite = now
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            val list = entries.entries.sortedByDescending { it.value }.take(10).map { Entry(it.key, it.value.toString()) }
            tmp.writeText(Json.encodeToString(ListSerializer(Entry.serializer()), list))
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }
}
