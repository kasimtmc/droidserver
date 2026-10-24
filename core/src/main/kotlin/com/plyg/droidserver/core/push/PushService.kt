package com.plyg.droidserver.core.push

import com.plyg.droidserver.core.api.ApiException
import com.plyg.droidserver.core.config.FieldType
import com.plyg.droidserver.core.config.PushConfig
import com.plyg.droidserver.core.config.SystemCollections
import com.plyg.droidserver.core.db.SqlDriver
import com.plyg.droidserver.core.db.quoteIdent
import com.plyg.droidserver.core.i18n.tr
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.net.URI
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Push abonelikleri (`_push_subscriptions`) ve gönderim. Abonelik adresi yalnızca bilinen itme servislerinden biri
 * olabilir ([allowEndpoint]): kullanıcının verdiği adrese sunucu istek attığı için iç ağdaki adreslere istek
 * yaptırılması (SSRF) engellenir. Gönderim arka planda; servis aboneliğin geçersiz olduğunu söylerse (404/410) silinir.
 */
class PushService(
    private val db: SqlDriver,
    private val config: PushConfig,
    private val sender: WebPushSender,
    private val log: (String) -> Unit = {},
    private val allowEndpoint: (URI) -> Boolean = ::knownPushService,
    private val clock: () -> Instant = Instant::now,
) : AutoCloseable {
    private val table = quoteIdent(SystemCollections.PUSH_SUBSCRIPTIONS)
    private val dialect = db.dialect
    private val executor = Executors.newFixedThreadPool(2) { r -> Thread(r, "ds-push").apply { isDaemon = true } }

    val publicKey: String get() = config.publicKey

    fun subscribe(user: Long, body: JsonObject) {
        val endpoint = (body["endpoint"] as? JsonPrimitive)?.content ?: throw ApiException(400, tr("endpoint gerekli (PushSubscription.toJSON())"))
        val keys = body["keys"] as? JsonObject
        val p256dh = (keys?.get("p256dh") as? JsonPrimitive)?.content
        val auth = (keys?.get("auth") as? JsonPrimitive)?.content
        if (p256dh == null || auth == null) throw ApiException(400, tr("keys.p256dh ve keys.auth gerekli"))
        val uri = runCatching { URI(endpoint) }.getOrNull()
        if (uri == null || uri.host == null || !allowEndpoint(uri)) {
            throw ApiException(400, tr("Abonelik adresi bilinen bir itme servisi olmalı (Chrome, Firefox, Safari, Edge)"))
        }
        val keyOk = runCatching { Vapid.unb64(p256dh).size == 65 && Vapid.unb64(auth).size == 16 }.getOrDefault(false)
        if (!keyOk) throw ApiException(400, tr("Abonelik anahtarları geçersiz"))
        val now = dialect.encode(FieldType.DATETIME, clock())
        val userValue = dialect.encode(FieldType.RELATION, user)
        db.transaction {
            // Aynı tarayıcı yeniden abone olursa (veya başka kullanıcıyla) kayıt güncellenir.
            db.execute("DELETE FROM $table WHERE \"endpoint\" = ?", listOf(endpoint))
            db.insert(
                dialect.insertReturningId(SystemCollections.PUSH_SUBSCRIPTIONS, listOf("endpoint", "p256dh", "auth", "user", "created_at", "updated_at")),
                listOf(endpoint, p256dh, auth, userValue, now, now),
            )
            // Kullanıcı başına en çok 20 cihaz: en eskiler silinir.
            val ids = db.query("SELECT \"id\" FROM $table WHERE \"user\" = ? ORDER BY \"id\" DESC", listOf(userValue)).map { (it["id"] as Number).toLong() }
            ids.drop(MAX_PER_USER).forEach { db.execute("DELETE FROM $table WHERE \"id\" = ?", listOf(it)) }
        }
    }

    fun unsubscribe(user: Long, endpoint: String?) {
        if (endpoint == null) throw ApiException(400, tr("endpoint gerekli"))
        db.execute("DELETE FROM $table WHERE \"endpoint\" = ? AND \"user\" = ?", listOf(endpoint, dialect.encode(FieldType.RELATION, user)))
    }

    fun count(user: Long): Int =
        (db.query("SELECT COUNT(*) AS c FROM $table WHERE \"user\" = ?", listOf(dialect.encode(FieldType.RELATION, user))).first()["c"] as Number).toInt()

    /** Bildirim gövdesi: tarayıcıdaki service worker'ın `push` olayında `event.data.json()`. */
    fun message(title: String, body: String?, url: String?, data: JsonElement?): ByteArray = buildJsonObject {
        put("title", JsonPrimitive(title))
        body?.takeIf { it.isNotEmpty() }?.let { put("body", JsonPrimitive(it)) }
        url?.takeIf { it.isNotEmpty() }?.let { put("url", JsonPrimitive(it)) }
        data?.let { put("data", it) }
    }.toString().toByteArray().also {
        if (it.size > WebPushSender.MAX_PAYLOAD) throw ApiException(400, tr("Bildirim çok uzun (en çok {0} bayt)", WebPushSender.MAX_PAYLOAD))
    }

    /** Kullanıcının tüm cihazlarına arka planda gönderir; abonelik sayısını döndürür. */
    fun sendToUser(user: Long, payload: ByteArray): Int {
        val subs = db.query("SELECT \"endpoint\", \"p256dh\", \"auth\" FROM $table WHERE \"user\" = ?", listOf(dialect.encode(FieldType.RELATION, user)))
            .map { PushSubscription(it["endpoint"] as String, it["p256dh"] as String, it["auth"] as String) }
        subs.forEach { sub -> executor.execute { deliver(sub, payload) } }
        return subs.size
    }

    private fun deliver(sub: PushSubscription, payload: ByteArray) {
        val code = try {
            sender.send(sub, payload)
        } catch (e: Exception) {
            log("Push gönderilemedi (${URI(sub.endpoint).host}): ${e.message}")
            return
        }
        when {
            code == 404 || code == 410 -> runCatching { db.execute("DELETE FROM $table WHERE \"endpoint\" = ?", listOf(sub.endpoint)) }
            code !in 200..299 -> log("Push servisi reddetti (${URI(sub.endpoint).host}): HTTP $code")
        }
    }

    /** Bekleyen gönderimlerin bitmesini bekler (testler ve kapanış). */
    fun drain(timeoutMs: Long = 5_000) {
        executor.shutdown()
        executor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)
    }

    override fun close() {
        executor.shutdownNow()
    }

    companion object {
        private const val MAX_PER_USER = 20

        /** Tarayıcıların itme servisleri. */
        private val SERVICES = listOf("fcm.googleapis.com", "push.services.mozilla.com", "push.apple.com", "notify.windows.com", "push.microsoft.com")

        fun knownPushService(uri: URI): Boolean {
            if (uri.scheme != "https") return false
            val host = uri.host?.lowercase() ?: return false
            return SERVICES.any { host == it || host.endsWith(".$it") }
        }

        /** `{alan}` yer tutucularını kaydın değerleriyle doldurur (bilinmeyen alan boş). */
        fun fill(template: String, record: JsonObject): String = Regex("\\{([A-Za-z_][A-Za-z0-9_]*)\\}").replace(template) { m ->
            (record[m.groupValues[1]] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: ""
        }
    }
}
