package com.plyg.droidserver.core.mqtt

import com.plyg.droidserver.core.i18n.tr
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Küçük bir MQTT 3.1.1 sunucusu (3.1 istemcileri de): kullanıcı adı ve parola zorunlu; yayın QoS 0, 1, 2 alınır,
 * abonelere QoS 0 ile iletilir; `+` ve `#` jokerleri; oturum saklama ve vasiyet yoktur.
 * Her bağlantı kendi iş parçacığında. Gelen her yayın [onPublish]'e gider (koleksiyona yazma); [publish] sunucudan
 * abonelere gönderir (kayıt olayları, uygulamadan komut).
 *
 * Yalnızca yerel ağ: [fromInternet] true dönen adresten gelen bağlantı hemen kapatılır. [tls] verilirse [tlsPort]'ta
 * TLS dinleyicisi de açılır (bağlam her bağlantıda istenir; sertifika yenilenince yeni bağlantılar yenisini alır);
 * [plain] false ise düz port açılmaz.
 * Tutulan (retain) mesajlar: konu başına son mesaj saklanır, yeni aboneliğe RETAIN bayrağıyla gönderilir; boş içerik
 * siler. [retainedFile] verilirse deploy ve yeniden başlatmalar arasında kalır (en çok 5 sn'de bir yazılır).
 */
class MqttBroker(
    private val port: Int,
    private val username: String,
    private val password: String,
    private val host: String = "0.0.0.0",
    private val maxClients: Int = 50,
    /** Gelen yayın: konu ve içerik; hata fırlatırsa sayılır ve loglanır (bağlantı sürer). */
    private val onPublish: (topic: String, payload: ByteArray, client: String) -> Unit = { _, _, _ -> },
    /** Hatalı kimlik: true dönerse adres engellendi (güvenlik duvarı). */
    private val authFailed: (ip: String) -> Boolean = { false },
    /** Engellenmiş adres mi (bağlantı hemen kapanır). */
    private val banned: (ip: String) -> Boolean = { false },
    private val log: (String) -> Unit = {},
    /** İnternetten gelen bağlantı mı (MQTT yalnızca yerel ağda; Tailscale ve aynı IPv6 ağı yerel sayılır). */
    private val fromInternet: (InetAddress) -> Boolean = { false },
    private val retainedFile: File? = null,
    /** Cihaz başına kimlik: kullanıcı adı → parola. [password] boşsa yalnızca bunlar bağlanabilir. */
    private val devices: Map<String, String> = emptyMap(),
    /** Düz (şifresiz) [port] açılsın mı. */
    private val plain: Boolean = true,
    private val tlsPort: Int? = null,
    /** TLS bağlamı (yerel kökle imzalı sertifika); null ise TLS dinleyicisi yok. */
    private val tls: (() -> SSLContext)? = null,
) {
    /** Bağlı istemci (durum ekranı); [user] bağlandığı kullanıcı adı (ortak veya cihaz). */
    data class ClientInfo(val id: String, val address: String, val connectedAt: Instant, val subscriptions: List<String>, val user: String? = null, val tls: Boolean = false)

    /** Son mesajlar: zaman, konu, boyut, sonuç (kayıt kimliği veya hata). */
    data class Message(val at: Instant, val topic: String, val bytes: Int, val result: String)

    val received = AtomicLong()
    val delivered = AtomicLong()
    val failed = AtomicLong()
    private val recent = CopyOnWriteArrayList<Message>()

    private class Session(val socket: Socket, val id: String, val address: String, val connectedAt: Instant, val user: String?) {
        val tls: Boolean get() = socket is SSLSocket
        val subs = CopyOnWriteArrayList<String>()
        val out: OutputStream = socket.getOutputStream()
        fun send(bytes: ByteArray) = synchronized(out) { out.write(bytes); out.flush() }
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private val servers = CopyOnWriteArrayList<ServerSocket>()
    private val lastHandshakeLog = AtomicLong()
    @Volatile private var running = false
    private val lastRejectLog = AtomicLong()

    private val retained = ConcurrentHashMap<String, ByteArray>()
    @Volatile private var retainedDirty = false
    @Volatile private var retainedFullLogged = false

    /** Tutulan mesaj sayısı. */
    fun retainedCount(): Int = retained.size

    fun retainedTopics(): Set<String> = retained.keys.toSet()

    fun clients(): List<ClientInfo> = sessions.values.map { ClientInfo(it.id, it.address, it.connectedAt, it.subs.toList(), it.user, it.tls) }.sortedBy { it.connectedAt }
    fun recent(): List<Message> = recent.toList().asReversed()

    fun note(topic: String, bytes: Int, result: String) {
        recent += Message(Instant.now(), topic, bytes, result)
        while (recent.size > 50) recent.removeAt(0)
    }

    /** Dinlemeye başlar; port kullanılıyorsa IOException (o zaman hiçbiri açık kalmaz). */
    fun start() {
        loadRetained()
        val listeners = listOfNotNull(port.takeIf { plain }?.let { it to null }, tls?.let { t -> (tlsPort ?: 8883) to t })
        running = true
        for ((p, context) in listeners) {
            val s = ServerSocket()
            s.reuseAddress = true
            try {
                s.bind(InetSocketAddress(InetAddress.getByName(host), p))
            } catch (e: IOException) {
                runCatching { s.close() }
                running = false
                servers.forEach { runCatching { it.close() } }
                servers.clear()
                throw e
            }
            servers += s
            accept(s, context)
        }
        if (retainedFile != null) {
            Thread({
                while (running) {
                    try { Thread.sleep(5_000) } catch (_: InterruptedException) { break }
                    saveRetained()
                }
            }, "ds-mqtt-retain").apply { isDaemon = true; start() }
        }
    }

    private fun accept(s: ServerSocket, context: (() -> SSLContext)?) {
        Thread({
            while (!s.isClosed) {
                val socket = try {
                    s.accept()
                } catch (_: IOException) {
                    break
                }
                val ip = socket.inetAddress.hostAddress.substringBefore('%')
                if (fromInternet(socket.inetAddress)) {
                    val now = System.currentTimeMillis()
                    if (now - lastRejectLog.getAndSet(now) > 60_000) log(tr("MQTT: internetten gelen bağlantı reddedildi ({0}); MQTT yalnızca yerel ağda çalışır", ip))
                    runCatching { socket.close() }
                    continue
                }
                if (banned(ip) || sessions.size >= maxClients) {
                    runCatching { socket.close() }
                    continue
                }
                Thread({
                    val conn = if (context == null) socket else handshake(socket, ip, context)
                    if (conn != null) serve(conn, ip)
                }, "ds-mqtt-client").apply { isDaemon = true; start() }
            }
        }, if (context == null) "ds-mqtt" else "ds-mqtt-tls").apply { isDaemon = true; start() }
    }

    /** TLS el sıkışması (10 sn); başarısızsa (cihaz köke güvenmiyor) bağlantı kapanır, dakikada bir loglanır. */
    private fun handshake(socket: Socket, ip: String, context: () -> SSLContext): Socket? = try {
        socket.tcpNoDelay = true
        socket.soTimeout = 10_000
        (context().socketFactory.createSocket(socket, ip, socket.port, true) as SSLSocket).apply {
            useClientMode = false
            startHandshake()
        }
    } catch (e: Exception) {
        val now = System.currentTimeMillis()
        if (now - lastHandshakeLog.getAndSet(now) > 60_000) log(tr("MQTT TLS: {0} ile el sıkışma başarısız ({1}); cihaz yerel köke güveniyor mu?", ip, e.message ?: e.javaClass.simpleName))
        runCatching { socket.close() }
        null
    }

    fun stop() {
        running = false
        saveRetained()
        servers.forEach { runCatching { it.close() } }
        servers.clear()
        sessions.values.forEach { runCatching { it.socket.close() } }
        sessions.clear()
    }

    /**
     * Sunucudan yayın: konuya uyan abonelere QoS 0. Kaç istemciye gittiği. [retain]: konunun son mesajı olarak saklanır
     * (boş içerik saklananı siler); var olan abonelere yine RETAIN bayraksız gider (MQTT 3.1.1).
     */
    fun publish(topic: String, payload: ByteArray, retain: Boolean = false): Int {
        if (retain) retain(topic, payload)
        val packet = packet(0x30, encodeString(topic) + payload)
        var n = 0
        for (s in sessions.values) {
            if (s.subs.none { matches(it, topic) }) continue
            if (runCatching { s.send(packet) }.isSuccess) n++
        }
        delivered.addAndGet(n.toLong())
        return n
    }

    // ---- bağlantı ----

    private fun serve(socket: Socket, ip: String) {
        var session: Session? = null
        try {
            socket.soTimeout = 10_000 // CONNECT için
            socket.tcpNoDelay = true
            val input = DataInputStream(socket.getInputStream().buffered())
            val (type, flags, body) = read(input)
            if (type != CONNECT) return
            val connect = parseConnect(body) ?: run {
                socket.getOutputStream().write(byteArrayOf(0x20, 2, 0, 1)) // desteklenmeyen sürüm
                return
            }
            val shared = password.isNotEmpty() && sameSecret(connect.username, username) && sameSecret(connect.password, password)
            val device = connect.username?.let(devices::get)?.let { sameSecret(connect.password, it) } == true
            if (!shared && !device) {
                socket.getOutputStream().write(byteArrayOf(0x20, 2, 0, 4)) // hatalı kullanıcı/parola
                if (authFailed(ip)) log(tr("MQTT: {0} engellendi (art arda hatalı giriş)", ip))
                return
            }
            if (flags != 0) return
            val id = connect.clientId.ifEmpty { "anon-" + System.nanoTime().toString(36) }
            // Aynı kimlikle yeni bağlantı eskisini kapatır (MQTT kuralı).
            sessions.remove(id)?.let { runCatching { it.socket.close() } }
            val s = Session(socket, id, ip, Instant.now(), connect.username)
            session = s
            sessions[id] = s
            socket.soTimeout = if (connect.keepAlive > 0) connect.keepAlive * 1500 else 0
            s.send(byteArrayOf(0x20, 2, 0, 0))
            val pendingQos2 = HashSet<Int>()
            while (running) {
                val (t, f, b) = read(input)
                when (t) {
                    PUBLISH -> handlePublish(s, f, b, pendingQos2)
                    PUBREL -> {
                        val pid = u16(b, 0)
                        pendingQos2.remove(pid)
                        s.send(byteArrayOf(0x70, 2, (pid shr 8).toByte(), pid.toByte()))
                    }
                    SUBSCRIBE -> {
                        val pid = u16(b, 0)
                        var i = 2
                        val granted = ByteArrayOutputStream()
                        val added = mutableListOf<String>()
                        while (i < b.size) {
                            val len = u16(b, i)
                            val filter = String(b, i + 2, len, Charsets.UTF_8)
                            i += 2 + len + 1
                            if (validFilter(filter) && s.subs.size < MAX_SUBSCRIPTIONS) {
                                if (filter !in s.subs) s.subs += filter
                                added += filter
                                granted.write(0)
                            } else {
                                granted.write(0x80)
                            }
                        }
                        s.send(packet(0x90, byteArrayOf((pid shr 8).toByte(), pid.toByte()) + granted.toByteArray()))
                        // Yeni aboneliğe uyan tutulan mesajlar RETAIN bayrağıyla.
                        for ((topic, payload) in retained) {
                            if (added.any { matches(it, topic) }) s.send(packet(0x31, encodeString(topic) + payload))
                        }
                    }
                    UNSUBSCRIBE -> {
                        val pid = u16(b, 0)
                        var i = 2
                        while (i < b.size) {
                            val len = u16(b, i)
                            s.subs.remove(String(b, i + 2, len, Charsets.UTF_8))
                            i += 2 + len
                        }
                        s.send(byteArrayOf(0xB0.toByte(), 2, (pid shr 8).toByte(), pid.toByte()))
                    }
                    PINGREQ -> s.send(byteArrayOf(0xD0.toByte(), 0))
                    DISCONNECT -> return
                    else -> return // istemci sunucuya bunları göndermez
                }
            }
        } catch (_: EOFException) {
        } catch (_: SocketTimeoutException) {
        } catch (_: SocketException) {
        } catch (e: IOException) {
            log("MQTT: ${e.message}")
        } finally {
            session?.let { sessions.remove(it.id, it) }
            runCatching { socket.close() }
        }
    }

    private fun handlePublish(s: Session, flags: Int, b: ByteArray, pendingQos2: MutableSet<Int>) {
        val qos = (flags shr 1) and 3
        val retain = flags and 1 != 0
        val len = u16(b, 0)
        val topic = String(b, 2, len, Charsets.UTF_8)
        var i = 2 + len
        val pid = if (qos > 0) u16(b, i).also { i += 2 } else 0
        val payload = b.copyOfRange(i, b.size)
        if (qos == 2 && pid in pendingQos2) {
            s.send(byteArrayOf(0x50, 2, (pid shr 8).toByte(), pid.toByte())) // yineleme: yeniden işlenmez
            return
        }
        if (topic.isEmpty() || '+' in topic || '#' in topic) throw IOException(tr("Yayın konusunda joker olamaz"))
        received.incrementAndGet()
        try {
            onPublish(topic, payload, s.id)
        } catch (e: Exception) {
            failed.incrementAndGet()
            note(topic, payload.size, tr("hata: {0}", e.message ?: e.javaClass.simpleName))
        }
        publish(topic, payload, retain)
        when (qos) {
            1 -> s.send(byteArrayOf(0x40, 2, (pid shr 8).toByte(), pid.toByte()))
            2 -> {
                pendingQos2 += pid
                s.send(byteArrayOf(0x50, 2, (pid shr 8).toByte(), pid.toByte()))
            }
        }
    }

    private fun retain(topic: String, payload: ByteArray) {
        if (payload.isEmpty()) {
            if (retained.remove(topic) != null) retainedDirty = true
            return
        }
        val total = retained.values.sumOf { it.size } - (retained[topic]?.size ?: 0) + payload.size
        if ((!retained.containsKey(topic) && retained.size >= MAX_RETAINED) || total > MAX_RETAINED_BYTES) {
            if (!retainedFullLogged) log(tr("MQTT: tutulan mesaj sınırı doldu ({0} konu); yeni konular saklanmıyor", MAX_RETAINED))
            retainedFullLogged = true
            return
        }
        retained[topic] = payload
        retainedDirty = true
    }

    private fun loadRetained() {
        val f = retainedFile?.takeIf { it.isFile } ?: return
        runCatching {
            Json.parseToJsonElement(f.readText()).jsonObject.forEach { (topic, v) -> retained[topic] = Base64.getDecoder().decode(v.jsonPrimitive.content) }
        }.onFailure { log(tr("MQTT: tutulan mesajlar okunamadı: {0}", it.message)) }
    }

    @Synchronized
    private fun saveRetained() {
        val f = retainedFile ?: return
        if (!retainedDirty) return
        retainedDirty = false
        runCatching {
            val text = JsonObject(retained.mapValues { JsonPrimitive(Base64.getEncoder().encodeToString(it.value)) }).toString()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }.onFailure { retainedDirty = true; log(tr("MQTT: tutulan mesajlar yazılamadı: {0}", it.message)) }
    }

    private data class Connect(val clientId: String, val username: String?, val password: String?, val keepAlive: Int)

    /** CONNECT gövdesi; desteklenmeyen protokolde null. */
    private fun parseConnect(b: ByteArray): Connect? {
        var i = 0
        fun str(): String { val n = u16(b, i); val v = String(b, i + 2, n, Charsets.UTF_8); i += 2 + n; return v }
        val name = str()
        val level = b[i++].toInt() and 0xFF
        if (!((name == "MQTT" && level == 4) || (name == "MQIsdp" && level == 3))) return null
        val flags = b[i++].toInt() and 0xFF
        val keepAlive = u16(b, i).also { i += 2 }
        val clientId = str()
        if (flags and 0x04 != 0) { str(); str() } // vasiyet konusu ve mesajı: desteklenmez, atlanır
        val user = if (flags and 0x80 != 0) str() else null
        val pass = if (flags and 0x40 != 0) str() else null
        return Connect(clientId, user, pass, keepAlive)
    }

    private fun sameSecret(given: String?, expected: String): Boolean =
        given != null && MessageDigest.isEqual(given.toByteArray(), expected.toByteArray())

    private fun read(input: DataInputStream): Triple<Int, Int, ByteArray> {
        val first = input.readUnsignedByte()
        var length = 0
        var multiplier = 1
        for (n in 0 until 4) {
            val d = input.readUnsignedByte()
            length += (d and 0x7F) * multiplier
            if (d and 0x80 == 0) break
            multiplier *= 128
            if (n == 3) throw IOException(tr("Bozuk MQTT paketi"))
        }
        if (length > MAX_PACKET) throw IOException(tr("MQTT paketi çok büyük ({0} bayt)", length))
        val body = ByteArray(length)
        input.readFully(body)
        return Triple(first shr 4, first and 0x0F, body)
    }

    companion object {
        private const val CONNECT = 1
        private const val PUBLISH = 3
        private const val PUBREL = 6
        private const val SUBSCRIBE = 8
        private const val UNSUBSCRIBE = 10
        private const val PINGREQ = 12
        private const val DISCONNECT = 14
        const val MAX_PACKET = 256 * 1024
        const val MAX_RETAINED = 1000
        private const val MAX_RETAINED_BYTES = 4 * 1024 * 1024
        private const val MAX_SUBSCRIPTIONS = 100

        private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

        private fun encodeString(s: String): ByteArray {
            val bytes = s.toByteArray(Charsets.UTF_8)
            return byteArrayOf((bytes.size shr 8).toByte(), bytes.size.toByte()) + bytes
        }

        /** Sabit başlık (tür + bayraklar) ve kalan uzunlukla paket. */
        fun packet(header: Int, body: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(header)
            var n = body.size
            do {
                var d = n % 128
                n /= 128
                if (n > 0) d = d or 0x80
                out.write(d)
            } while (n > 0)
            out.write(body)
            return out.toByteArray()
        }

        /** Abonelik süzgeci geçerli mi: `#` yalnızca sonda ve tek başına bölüm, `+` tek başına bölüm. */
        fun validFilter(filter: String): Boolean {
            if (filter.isEmpty() || filter.length > 256) return false
            val parts = filter.split('/')
            return parts.withIndex().all { (i, p) ->
                when {
                    p == "#" -> i == parts.lastIndex
                    p == "+" -> true
                    else -> '#' !in p && '+' !in p
                }
            }
        }

        /** Konu süzgece uyuyor mu (`$` ile başlayan konular jokerle eşleşmez). */
        fun matches(filter: String, topic: String): Boolean {
            if (topic.startsWith("$") && (filter.startsWith("+") || filter.startsWith("#"))) return false
            val f = filter.split('/')
            val t = topic.split('/')
            for (i in f.indices) {
                if (f[i] == "#") return true
                if (i >= t.size) return false
                if (f[i] != "+" && f[i] != t[i]) return false
            }
            return f.size == t.size
        }

        /** Süzgecin `+` ve `#` bölümlerine düşen konu parçaları (örn. `sensors/+/temp` ← `sensors/salon/temp` → [salon]). */
        fun captures(filter: String, topic: String): List<String> {
            val f = filter.split('/')
            val t = topic.split('/')
            return f.indices.flatMap { i ->
                when (f[i]) {
                    "+" -> listOfNotNull(t.getOrNull(i))
                    "#" -> listOf(t.drop(i).joinToString("/"))
                    else -> emptyList()
                }
            }
        }
    }
}
