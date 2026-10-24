package com.plyg.droidserver.core

import com.google.zxing.BinaryBitmap
import com.google.zxing.LuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.discovery.ConnectLink
import com.plyg.droidserver.core.discovery.MdnsDiscovery
import com.plyg.droidserver.core.discovery.QrCode
import com.plyg.droidserver.core.sdkclient.ConnectionLink
import com.plyg.droidserver.core.sdkclient.DroidServerClient
import com.plyg.droidserver.core.sdkclient.LocalDiscovery
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Instant
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Faz 13.6: yerel ağ keşfi (mDNS) ve QR bağlantısı. */
class DiscoveryTest {
    @Test
    fun `baglanti metni`() {
        val link = ConnectLink("http://192.168.1.20:8080/api", "Evim & Ofis", "a.b.c", Instant.ofEpochSecond(1_800_000_000))
        val uri = link.toUri()
        assertTrue(uri.startsWith("droidserver://connect?url=http%3A%2F%2F192.168.1.20%3A8080%2Fapi&name=Evim+%26+Ofis"), uri)
        assertEquals(link, ConnectLink.parse(uri))
        assertEquals(ConnectLink("https://evim.example.com/api"), ConnectLink.parse(" https://evim.example.com/api/ "))
        assertNull(ConnectLink.parse("droidserver://connect?url=javascript%3Aalert(1)"))
        assertNull(ConnectLink.parse("merhaba"))

        // Üretilen Kotlin istemcisi aynı biçimi okur: JWT token, diğerleri API anahtarı.
        assertEquals(ConnectionLink("http://192.168.1.20:8080/api", "Evim & Ofis", "a.b.c", 1_800_000_000), ConnectionLink.parse(uri))
        val jwt = DroidServerClient.fromLink(uri)
        assertEquals("a.b.c", jwt.token)
        assertNull(jwt.apiKey)
        val key = DroidServerClient.fromLink(ConnectLink("http://x/api", token = "ds_anahtar").toUri())
        assertEquals("ds_anahtar", key.apiKey)
        assertNull(key.token)
    }

    @Test
    fun `qr kodu geri okunur`() {
        val text = ConnectLink("http://192.168.1.20:8080/api", "Evim", "ds_" + "x".repeat(40)).toUri()
        val m = QrCode.matrix(text)
        assertTrue(m.size in 21..177 && m.all { it.size == m.size })
        val scale = 4
        val side = (m.size + 8) * scale
        val pixels = ByteArray(side * side) { i ->
            val x = (i % side) / scale - 4
            val y = (i / side) / scale - 4
            if (x in m.indices && y in m.indices && m[y][x]) 0 else -1
        }
        val source = object : LuminanceSource(side, side) {
            override fun getRow(y: Int, row: ByteArray?): ByteArray = pixels.copyOfRange(y * side, (y + 1) * side)
            override fun getMatrix(): ByteArray = pixels
        }
        assertEquals(text, QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text)
        val svg = QrCode.svg(text)
        assertTrue(svg.startsWith("<svg") && "<path d=\"M" in svg)
    }

    @Test
    fun `mdns sorgusu ve yanit`() {
        val responder = DatagramSocket(0, InetAddress.getLoopbackAddress())
        val queries = mutableListOf<ByteArray>()
        val t = thread(isDaemon = true) {
            val buf = ByteArray(1500)
            runCatching {
                while (true) {
                    val p = DatagramPacket(buf, buf.size)
                    responder.receive(p)
                    queries += p.data.copyOf(p.length)
                    val reply = response()
                    responder.send(DatagramPacket(reply, reply.size, p.socketAddress))
                    // Gürültü: başka bir hizmetin yanıtı ve bozuk paket yok sayılır.
                    responder.send(DatagramPacket(byteArrayOf(1, 2, 3), 3, p.socketAddress))
                }
            }
        }
        try {
            val target = InetSocketAddress(InetAddress.getLoopbackAddress(), responder.localPort)
            val found = MdnsDiscovery.discover(500, target = target)
            assertEquals(1, found.size, found.toString())
            val s = found.single()
            assertEquals("Evim", s.name)
            assertEquals(8080, s.port)
            assertEquals(mapOf("path" to "/v1", "v" to "1"), s.attributes)
            assertEquals("http://192.168.1.20:8080/v1", s.baseUrl())
            assertTrue(queries.first().contentEquals(MdnsDiscovery.query()), "PTR sorgusu")

            val sdk = LocalDiscovery.find(500, target)
            assertEquals(listOf("http://192.168.1.20:8080/v1"), sdk.map { it.baseUrl })
            assertEquals("Evim", sdk.single().name)
        } finally {
            responder.close()
            t.join(1000)
        }
        assertTrue("\"discovery\"" !in ServerConfig().toJson(), "varsayılan (açık) dosyaya yazılmaz")
    }

    /** `Evim._droidserver._tcp.local` için PTR (cevap), SRV + TXT + A (ek bölüm), ad sıkıştırmasıyla. */
    private fun response(): ByteArray {
        val out = ByteArrayOutputStream()
        fun u16(v: Int) { out.write(v shr 8); out.write(v and 0xff) }
        fun name(vararg labels: String) { labels.forEach { val b = it.toByteArray(); out.write(b.size); out.write(b) }; out.write(0) }
        fun pointer(offset: Int) { out.write(0xc0 or (offset shr 8)); out.write(offset and 0xff) }
        fun rr(type: Int, data: () -> Unit) {
            u16(type); u16(0x8001); u16(0); u16(120)
            val lenAt = out.size()
            u16(0)
            val start = out.size()
            data()
            val bytes = out.toByteArray()
            val len = bytes.size - start
            bytes[lenAt] = (len shr 8).toByte(); bytes[lenAt + 1] = (len and 0xff).toByte()
            out.reset(); out.write(bytes)
        }
        u16(0); u16(0x8400); u16(0); u16(1); u16(0); u16(3)
        val service = out.size()
        name("_droidserver", "_tcp", "local")
        rr(12) {}
        // PTR verisi: "Evim" + hizmet adına işaretçi
        val instance = out.size()
        out.write(4); out.write("Evim".toByteArray()); pointer(service)
        fixLength(out, instance - 2, out.size() - instance)
        // SRV
        pointer(instance)
        rr(33) { u16(0); u16(0); u16(8080); name("telefon", "local") }
        val host = out.size() - "telefon".length - "local".length - 3
        // TXT
        pointer(instance)
        rr(16) { listOf("path=/v1", "v=1").forEach { val b = it.toByteArray(); out.write(b.size); out.write(b) } }
        // A
        pointer(host)
        rr(1) { out.write(byteArrayOf(192.toByte(), 168.toByte(), 1, 20)) }
        return out.toByteArray()
    }

    private fun fixLength(out: ByteArrayOutputStream, at: Int, len: Int) {
        val bytes = out.toByteArray()
        bytes[at] = (len shr 8).toByte(); bytes[at + 1] = (len and 0xff).toByte()
        out.reset(); out.write(bytes)
    }
}
