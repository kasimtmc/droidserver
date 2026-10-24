package com.plyg.droidserver.core.discovery

import com.plyg.droidserver.core.i18n.tr
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/** Yerel ağda bulunan sunucu: mDNS örnek adı, adresleri, port ve TXT öznitelikleri (`path`, `https`, `v`). */
data class FoundServer(
    val name: String,
    val host: String?,
    val port: Int,
    val addresses: List<InetAddress>,
    val attributes: Map<String, String>,
) {
    /** API adresi, örn. `http://192.168.1.20:8080/api` (IPv4 tercih edilir). */
    fun baseUrl(): String? {
        val a = addresses.firstOrNull { it.address.size == 4 } ?: addresses.firstOrNull() ?: return null
        val host = a.hostAddress.substringBefore('%').let { if (':' in it) "[$it]" else it }
        return "http://$host:$port" + (attributes["path"] ?: "/api")
    }
}

/**
 * Yerel ağda `_droidserver._tcp` sunucularını bulur (mDNS/DNS-SD, RFC 6762/6763). Sorgu geçici bir porttan
 * gönderilir ("legacy unicast"): yanıtlar doğrudan bu sokete gelir, 5353 portunu dinlemek veya çoklu yayın grubuna
 * katılmak gerekmez. Android'de uygulamanın kendisi `NsdManager` da kullanabilir.
 */
object MdnsDiscovery {
    const val SERVICE = "_droidserver._tcp.local"
    val GROUP: InetSocketAddress = InetSocketAddress(InetAddress.getByName("224.0.0.251"), 5353)

    /** [timeoutMs] boyunca yanıt toplar; [target] testlerde tek bir yanıtlayıcı. */
    fun discover(timeoutMs: Long = 2_000, service: String = SERVICE, target: InetSocketAddress = GROUP): List<FoundServer> {
        val records = mutableListOf<Record>()
        val sources = HashMap<String, InetAddress>()
        DatagramSocket().use { socket ->
            val query = query(service)
            socket.send(DatagramPacket(query, query.size, target))
            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(9000)
            while (true) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) break
                socket.soTimeout = left.toInt().coerceAtLeast(1)
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    break
                }
                val parsed = runCatching { parse(packet.data.copyOf(packet.length)) }.getOrNull() ?: continue
                records += parsed
                parsed.filter { it.type == PTR }.forEach { sources[it.data as String] = packet.address }
            }
        }
        return assemble(records, service, sources)
    }

    /** PTR sorgusu (kimlik 0, tek soru). */
    fun query(service: String = SERVICE): ByteArray = ByteArrayOutputStream().apply {
        write(byteArrayOf(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        writeName(service)
        write(byteArrayOf(0, PTR.toByte(), 0, 1))
    }.toByteArray()

    /** Yanıttaki kaynak kayıtları (cevap, yetki ve ek bölümler). */
    fun parse(msg: ByteArray): List<Record> {
        val r = Reader(msg)
        r.pos = 4
        val questions = r.u16()
        val total = r.u16() + r.u16() + r.u16()
        repeat(questions) { r.name(); r.pos += 4 }
        return List(total) {
            val name = r.name()
            val type = r.u16()
            r.pos += 2 // sınıf (önbellek temizleme biti dahil)
            r.pos += 4 // TTL
            val len = r.u16()
            val start = r.pos
            val data: Any? = when (type) {
                PTR -> r.name()
                SRV -> { r.pos += 4; val port = r.u16(); port to r.name() }
                TXT -> buildMap {
                    while (r.pos < start + len) {
                        val n = msg[r.pos++].toInt() and 0xff
                        val s = String(msg, r.pos, n, Charsets.UTF_8).also { r.pos += n }
                        if (s.isNotEmpty()) put(s.substringBefore('='), s.substringAfter('=', ""))
                    }
                }
                A, AAAA -> InetAddress.getByAddress(msg.copyOfRange(start, start + len))
                else -> null
            }
            r.pos = start + len
            Record(name.lowercase(), type, data)
        }
    }

    private fun assemble(records: List<Record>, service: String, sources: Map<String, InetAddress>): List<FoundServer> {
        val instances = records.filter { it.type == PTR && it.name == service.lowercase() }.map { it.data as String }.distinct()
        return instances.mapNotNull { instance ->
            val key = instance.lowercase()
            @Suppress("UNCHECKED_CAST")
            val srv = records.firstOrNull { it.type == SRV && it.name == key }?.data as Pair<Int, String>? ?: return@mapNotNull null
            @Suppress("UNCHECKED_CAST")
            val txt = records.firstOrNull { it.type == TXT && it.name == key }?.data as Map<String, String>? ?: emptyMap()
            val addresses = records.filter { (it.type == A || it.type == AAAA) && it.name == srv.second.lowercase() }.map { it.data as InetAddress }
                .ifEmpty { listOfNotNull(sources[instance]) }
            FoundServer(instance.removeSuffix("." + service).removeSuffix(service), srv.second, srv.first, addresses.distinct(), txt)
        }
    }

    data class Record(val name: String, val type: Int, val data: Any?)

    private class Reader(val msg: ByteArray) {
        var pos = 0
        fun u16(): Int = ((msg[pos].toInt() and 0xff) shl 8 or (msg[pos + 1].toInt() and 0xff)).also { pos += 2 }

        /** Sıkıştırılmış adlar dahil; döngüye karşı en çok 64 atlama. */
        fun name(): String {
            val labels = mutableListOf<String>()
            var p = pos
            var jumped = false
            var hops = 0
            while (true) {
                val len = msg[p].toInt() and 0xff
                when {
                    len == 0 -> { p++; break }
                    len and 0xc0 == 0xc0 -> {
                        if (++hops > 64) error(tr("mDNS: ad döngüsü"))
                        val target = ((len and 0x3f) shl 8) or (msg[p + 1].toInt() and 0xff)
                        if (!jumped) pos = p + 2
                        jumped = true
                        p = target
                    }
                    else -> { labels += String(msg, p + 1, len, Charsets.UTF_8); p += len + 1 }
                }
            }
            if (!jumped) pos = p
            return labels.joinToString(".")
        }
    }

    private fun ByteArrayOutputStream.writeName(name: String) {
        name.split('.').filter { it.isNotEmpty() }.forEach { label ->
            val b = label.toByteArray(Charsets.UTF_8)
            write(b.size)
            write(b)
        }
        write(0)
    }

    const val A = 1
    const val PTR = 12
    const val TXT = 16
    const val AAAA = 28
    const val SRV = 33
}
