package dev.droidserver.core.realtime

import dev.droidserver.core.auth.Caller
import dev.droidserver.core.config.ChannelConfig
import dev.droidserver.core.config.Rule
import dev.droidserver.core.i18n.tr
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong

/**
 * WebSocket kanalları ([ChannelConfig]): katılma ve gönderme kurala bağlı, çevrimiçi listesi (presence) ve son
 * mesajlar. Her bağlantının giden kuyruğu sınırlıdır; okuyamayacak kadar yavaş kalan bağlantı kapatılır (yazanı
 * bekletmez). Bağlantı başına saniyede en çok [RATE] mesaj, mesaj en çok [MAX_MESSAGE] karakter.
 */
class ChannelHub(private val channels: List<ChannelConfig>, private val maxConnections: Int = 200) {
    /** Kanalda görünen kimlik: kullanıcı `u:<id>`, diğerleri bağlantıya özgü `g:<n>`. */
    data class Member(val id: String, val user: Long?, val role: String?) {
        fun toJson() = buildJsonObject {
            put("id", JsonPrimitive(id))
            put("user", user?.let(::JsonPrimitive) ?: JsonNull)
            role?.let { put("role", JsonPrimitive(it)) }
        }
    }

    class ChannelException(message: String) : RuntimeException(message)

    inner class Connection internal constructor(val caller: Caller, val member: Member) {
        /** Giden metin çerçeveleri; WebSocket oturumu okur. */
        val outgoing = Channel<String>(QUEUE)
        internal val joined = CopyOnWriteArraySet<String>()
        private var windowStart = 0L
        private var windowCount = 0

        @Volatile var overflowed = false
            internal set

        internal fun deliver(message: JsonObject) {
            if (outgoing.trySend(message.toString()).isFailure && !outgoing.isClosedForSend) {
                overflowed = true
                outgoing.close()
            }
        }

        internal fun allowSend(now: Long): Boolean {
            if (now - windowStart >= 1_000) { windowStart = now; windowCount = 0 }
            return ++windowCount <= RATE
        }
    }

    private val rooms = ConcurrentHashMap<String, CopyOnWriteArraySet<Connection>>()
    private val history = ConcurrentHashMap<String, ArrayDeque<JsonObject>>()
    private val connections = CopyOnWriteArraySet<Connection>()
    private val guests = AtomicLong()

    val connectionCount: Int get() = connections.size

    /** Yeni bağlantı; sınır doluysa null. */
    fun connect(caller: Caller): Connection? {
        if (connections.size >= maxConnections) return null
        val user = caller as? Caller.User
        val member = if (user != null) Member("u:${user.id}", user.id, user.role) else Member("g:${guests.incrementAndGet()}", null, null)
        return Connection(caller, member).also { connections += it }
    }

    fun disconnect(c: Connection) {
        connections -= c
        c.joined.toList().forEach { leave(c, it) }
        c.outgoing.close()
    }

    /** Kanalın tanımı: tam ad önce, sonra `*` ile biten en uzun kalıp. */
    fun spec(channel: String): ChannelConfig? =
        channels.firstOrNull { it.name == channel }
            ?: channels.filter { it.name.endsWith("*") && channel.startsWith(it.name.dropLast(1)) }.maxByOrNull { it.name.length }

    /** Gelen istemci iletisini işler (`join`, `leave`, `send`, `ping`); yanıtı bağlantıya yazar. */
    fun handle(c: Connection, text: String) {
        val msg = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(text) as JsonObject }.getOrNull()
            ?: return c.deliver(error(null, tr("İleti JSON nesnesi olmalı")))
        val type = (msg["type"] as? JsonPrimitive)?.content
        val channel = (msg["channel"] as? JsonPrimitive)?.content
        try {
            when (type) {
                "ping" -> c.deliver(buildJsonObject { put("type", JsonPrimitive("pong")) })
                "join" -> c.deliver(join(c, requireName(channel)))
                "leave" -> { leave(c, requireName(channel)); c.deliver(buildJsonObject { put("type", JsonPrimitive("left")); put("channel", JsonPrimitive(channel)) }) }
                "send" -> {
                    if (text.length > MAX_MESSAGE) throw ChannelException(tr("İleti en çok {0} karakter olabilir", MAX_MESSAGE))
                    if (!c.allowSend(System.currentTimeMillis())) throw ChannelException(tr("Çok hızlı: saniyede en çok {0} ileti", RATE))
                    send(c, requireName(channel), msg["data"] ?: JsonNull)
                }
                else -> throw ChannelException(tr("Bilinmeyen ileti türü: {0} (join, leave, send, ping)", type ?: "-"))
            }
        } catch (e: ChannelException) {
            c.deliver(error(channel, e.message ?: ""))
        }
    }

    private fun requireName(channel: String?): String {
        if (channel == null || !NAME.matches(channel)) throw ChannelException(tr("Kanal adı geçersiz: harf, rakam ve _ : . - (en çok 64)"))
        return channel
    }

    private fun join(c: Connection, channel: String): JsonObject {
        val spec = spec(channel) ?: throw ChannelException(tr("'{0}' kanalı yok", channel))
        if (!allowed(spec.join, spec.roles, c.caller)) throw ChannelException(tr("'{0}' kanalına katılma yetkiniz yok", channel))
        val room = rooms.getOrPut(channel) { CopyOnWriteArraySet() }
        val first = room.none { it.member.id == c.member.id }
        room += c
        c.joined += channel
        if (spec.presence && first) broadcast(channel, presence(channel, "join", c.member), except = c)
        return buildJsonObject {
            put("type", JsonPrimitive("joined"))
            put("channel", JsonPrimitive(channel))
            put("me", c.member.toJson())
            if (spec.presence) put("members", JsonArray(room.map { it.member }.distinctBy { it.id }.map { it.toJson() }))
            put("history", JsonArray(history[channel]?.let { synchronized(it) { it.toList() } }.orEmpty()))
        }
    }

    private fun leave(c: Connection, channel: String) {
        if (!c.joined.remove(channel)) return
        val room = rooms[channel] ?: return
        room -= c
        if (spec(channel)?.presence == true && room.none { it.member.id == c.member.id }) broadcast(channel, presence(channel, "leave", c.member))
        if (room.isEmpty()) rooms.remove(channel, room)
    }

    private fun send(c: Connection, channel: String, data: JsonElement) {
        val spec = spec(channel) ?: throw ChannelException(tr("'{0}' kanalı yok", channel))
        if (channel !in c.joined) throw ChannelException(tr("Önce kanala katılın: {0}", channel))
        if (!allowed(spec.publish, spec.roles, c.caller)) throw ChannelException(tr("'{0}' kanalına gönderme yetkiniz yok", channel))
        deliverMessage(spec, channel, data, c.member)
    }

    /** Sunucudan (script, kayıt olayı) kanala ileti; katılan yoksa yalnızca geçmişe yazılır. */
    fun publish(channel: String, data: JsonElement): Boolean {
        val spec = spec(channel) ?: return false
        deliverMessage(spec, channel, data, null)
        return true
    }

    private fun deliverMessage(spec: ChannelConfig, channel: String, data: JsonElement, from: Member?) {
        val message = buildJsonObject {
            put("type", JsonPrimitive("message"))
            put("channel", JsonPrimitive(channel))
            put("from", from?.toJson() ?: JsonNull)
            put("data", data)
            put("at", JsonPrimitive(Instant.now().toString()))
        }
        if (spec.history > 0) {
            val h = history.getOrPut(channel) { ArrayDeque() }
            synchronized(h) {
                h.addLast(message)
                while (h.size > spec.history) h.removeFirst()
            }
        }
        broadcast(channel, message)
    }

    private fun broadcast(channel: String, message: JsonObject, except: Connection? = null) {
        rooms[channel]?.forEach { if (it !== except) it.deliver(message) }
    }

    private fun presence(channel: String, event: String, member: Member) = buildJsonObject {
        put("type", JsonPrimitive("presence"))
        put("channel", JsonPrimitive(channel))
        put("event", JsonPrimitive(event))
        put("member", member.toJson())
    }

    private fun error(channel: String?, message: String) = buildJsonObject {
        put("type", JsonPrimitive("error"))
        channel?.let { put("channel", JsonPrimitive(it)) }
        put("message", JsonPrimitive(message))
    }

    private fun allowed(rule: Rule, roles: List<String>, caller: Caller): Boolean = when {
        caller.isAdmin -> true
        (caller as? Caller.User)?.restricted == true -> false
        rule == Rule.PUBLIC -> true
        rule == Rule.AUTH -> caller !is Caller.Anonymous
        else -> (caller as? Caller.User)?.role?.let { it in roles } == true
    }

    companion object {
        const val RATE = 20
        const val MAX_MESSAGE = 16_000
        private const val QUEUE = 256
        val NAME = Regex("^[A-Za-z0-9_:.\\-]{1,64}$")
    }
}
