package com.plyg.droidserver.core

import com.sun.net.httpserver.HttpServer
import com.plyg.droidserver.core.config.AccessConfig
import com.plyg.droidserver.core.config.AccessMode
import com.plyg.droidserver.core.config.DdnsConfig
import com.plyg.droidserver.core.config.DirectAccess as DirectAccessConfig
import com.plyg.droidserver.core.config.DirectNetwork
import com.plyg.droidserver.core.config.HttpConfig
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.net.AddressScope
import com.plyg.droidserver.core.net.DirectAccess
import com.plyg.droidserver.core.net.DirectStatus
import com.plyg.droidserver.core.net.NetInterface
import com.plyg.droidserver.core.net.NetworkProbe
import com.plyg.droidserver.core.net.PortMapper
import com.plyg.droidserver.core.net.Reachability
import com.plyg.droidserver.core.net.TlsIdentity
import com.plyg.droidserver.core.net.TlsProxy
import com.plyg.droidserver.core.net.WanServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Collections
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.TrustManagerFactory
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DirectAccessTest {
    private val closers = mutableListOf<() -> Unit>()

    @AfterTest
    fun cleanup() = closers.reversed().forEach { runCatching(it) }

    private fun freePort() = ServerSocket(0).use { it.localPort }

    /** Gelen istek başlıklarını ve gövdesini yanıt olarak döndüren HTTP sunucusu (Ktor'un yerine). */
    private fun echoBackend(): Int {
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/") { ex ->
            val body = buildString {
                ex.requestHeaders.forEach { (k, v) -> append(k.lowercase()).append('=').append(v.joinToString(",")).append('\n') }
                append("body=").append(ex.requestBody.readBytes().decodeToString())
            }.toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        srv.start()
        closers += { srv.stop(0) }
        return srv.address.port
    }

    /** Yalnızca verilen sertifikaya güvenen istemci (sabitleme). */
    private fun pinnedClient(cert: X509Certificate): SSLContext {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null); setCertificateEntry("s", cert) }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
        return SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }
    }

    private fun https(port: Int, ctx: SSLContext, method: String = "GET", body: String? = null, headers: Map<String, String> = emptyMap()): Pair<Int, String> {
        val c = URI("https://127.0.0.1:$port/x").toURL().openConnection() as HttpsURLConnection
        c.sslSocketFactory = ctx.socketFactory
        c.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true } // IP adresi; güven sabitlenmiş sertifikadan
        c.requestMethod = method
        c.connectTimeout = 5_000
        c.readTimeout = 10_000
        headers.forEach(c::setRequestProperty)
        if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) } }
        val status = c.responseCode
        return status to (if (status < 400) c.inputStream else c.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
    }

    @Test
    fun `kendinden imzali sertifika uretilir, kaydedilir ve yuklenir`() {
        val id = TlsIdentity.generate()
        assertEquals("CN=DroidServer", id.certificate.subjectX500Principal.name)
        assertTrue(id.certificate.notAfter.toInstant().toString().startsWith("2049-12-31"))
        assertTrue(id.fingerprint.matches(Regex("^([0-9A-F][0-9A-F]:)+[0-9A-F][0-9A-F]$")) && id.fingerprint.length == 95, id.fingerprint)

        val file = File(Files.createTempDirectory("ds-tls").toFile(), "tls.p12")
        val loaded = TlsIdentity.loadOrCreate(file, "parola".toCharArray())
        assertEquals(loaded.fingerprint, TlsIdentity.loadOrCreate(file, "parola".toCharArray()).fingerprint, "ikinci açılışta aynı sertifika")
        file.writeText("bozuk")
        assertTrue(TlsIdentity.loadOrCreate(file, "parola".toCharArray()).fingerprint != loaded.fingerprint, "bozuk dosyada yenisi üretilir")
    }

    @Test
    fun `TLS ucu istegi aktarir, gercek adresi ekler, taklit basliklari siler`() {
        val id = TlsIdentity.generate()
        val proxy = TlsProxy(id.sslContext(), InetSocketAddress("127.0.0.1", 0), echoBackend()).start()
        closers += proxy::close
        val ctx = pinnedClient(id.certificate)

        val (status, body) = https(
            proxy.port, ctx, "POST", body = "{\"a\":1}",
            headers = mapOf("X-Forwarded-For" to "6.6.6.6", "CF-Connecting-IP" to "7.7.7.7", "X-Test" to "evet"),
        )
        assertEquals(200, status, body)
        assertTrue(body.contains("x-forwarded-for=127.0.0.1"), body)
        assertFalse(body.contains("6.6.6.6") || body.contains("7.7.7.7"), "istemcinin gönderdiği adres başlıkları silinmeli: $body")
        assertTrue(body.contains("x-forwarded-proto=https") && body.contains("x-test=evet"), body)
        assertTrue(body.contains("body={\"a\":1}"), body)

        // Başka bir sertifikaya güvenen istemci bağlanamaz (sabitleme işe yarıyor).
        assertFailsWith<SSLHandshakeException> { https(proxy.port, pinnedClient(TlsIdentity.generate().certificate)) }
    }

    @Test
    fun `TLS ucu asiri buyuk basligi reddeder`() {
        val id = TlsIdentity.generate()
        val proxy = TlsProxy(id.sslContext(), InetSocketAddress("127.0.0.1", 0), echoBackend()).start()
        closers += proxy::close
        val (status, _) = https(proxy.port, pinnedClient(id.certificate), headers = mapOf("X-Big" to "a".repeat(20_000)))
        assertEquals(431, status)
    }

    /** RFC 6886 sahte NAT-PMP modemi. */
    private fun fakeNatPmp(external: ByteArray, onMap: (internal: Int, external: Int, lifetime: Int) -> Unit = { _, _, _ -> }): Int {
        val s = DatagramSocket(0, InetAddress.getLoopbackAddress())
        closers += s::close
        thread(isDaemon = true) {
            val buf = ByteArray(64)
            while (!s.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                runCatching { s.receive(p) }.onFailure { return@thread }
                val op = buf[1].toInt()
                val reply = if (op == 0) {
                    ByteBuffer.allocate(12).put(0).put(128.toByte()).putShort(0).putInt(1).put(external).array()
                } else {
                    val req = ByteBuffer.wrap(buf, 0, 12)
                    val internal = req.getShort(4).toInt() and 0xFFFF
                    val ext = req.getShort(6).toInt() and 0xFFFF
                    val life = req.getInt(8)
                    onMap(internal, ext, life)
                    ByteBuffer.allocate(16).put(0).put(130.toByte()).putShort(0).putInt(1)
                        .putShort(internal.toShort()).putShort(ext.toShort()).putInt(life).array()
                }
                s.send(DatagramPacket(reply, reply.size, p.socketAddress))
            }
        }
        return s.localPort
    }

    @Test
    fun `NAT-PMP ile yonlendirme acilir ve kaldirilir`() {
        val calls = Collections.synchronizedList(mutableListOf<Triple<Int, Int, Int>>())
        val port = fakeNatPmp(byteArrayOf(203.toByte(), 0, 113, 7)) { i, e, l -> calls += Triple(i, e, l) }
        val mapper = PortMapper(natPmpPort = port, ssdpTarget = InetSocketAddress("127.0.0.1", freePort()), timeoutMs = 1_000)
        val r = mapper.map(InetAddress.getLoopbackAddress(), InetAddress.getByName("192.168.1.20"), 8443)
        val m = assertNotNull(r.mapping, r.errors.joinToString())
        assertEquals(PortMapper.NAT_PMP, m.method)
        assertEquals("203.0.113.7", m.externalIp?.hostAddress)
        assertEquals(8443 to 3600, m.externalPort to m.lifetimeSec)

        mapper.unmap(InetAddress.getLoopbackAddress(), m)
        assertEquals(Triple(8443, 0, 0), calls.last(), "kaldırma: süre 0")
    }

    /** SSDP yanıtı veren ve SOAP çağrılarını kaydeden sahte UPnP modemi. */
    private fun fakeUpnp(onlyPermanent: Boolean, soapCalls: MutableList<String>): Int {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/desc.xml") { ex ->
            val xml = """<?xml version="1.0"?><root><device><serviceList>
                <service><serviceType>urn:schemas-upnp-org:service:Layer3Forwarding:1</serviceType><controlURL>/l3f</controlURL></service>
                <service><serviceType>urn:schemas-upnp-org:service:WANIPConnection:1</serviceType><controlURL>/ctl</controlURL></service>
                </serviceList></device></root>""".toByteArray()
            ex.sendResponseHeaders(200, xml.size.toLong()); ex.responseBody.use { it.write(xml) }
        }
        http.createContext("/ctl") { ex ->
            val action = ex.requestHeaders.getFirst("SOAPAction").substringAfter('#').trim('"')
            val body = ex.requestBody.readBytes().decodeToString()
            soapCalls += "$action ${Regex("<NewLeaseDuration>(\\d+)<").find(body)?.groupValues?.get(1).orEmpty()}".trim()
            val (status, reply) = when {
                action == "AddPortMapping" && onlyPermanent && !body.contains("<NewLeaseDuration>0<") ->
                    500 to "<s:Envelope><s:Body><s:Fault><detail><UPnPError><errorCode>725</errorCode><errorDescription>OnlyPermanentLeasesSupported</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>"
                action == "GetExternalIPAddress" ->
                    200 to "<s:Envelope><s:Body><u:GetExternalIPAddressResponse><NewExternalIPAddress>100.70.1.2</NewExternalIPAddress></u:GetExternalIPAddressResponse></s:Body></s:Envelope>"
                else -> 200 to "<s:Envelope><s:Body/></s:Envelope>"
            }
            val bytes = reply.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
        }
        http.start()
        closers += { http.stop(0) }

        val ssdp = DatagramSocket(0, InetAddress.getLoopbackAddress())
        closers += ssdp::close
        thread(isDaemon = true) {
            val buf = ByteArray(2048)
            while (!ssdp.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                runCatching { ssdp.receive(p) }.onFailure { return@thread }
                val reply = "HTTP/1.1 200 OK\r\nST: x\r\nLOCATION: http://127.0.0.1:${http.address.port}/desc.xml\r\n\r\n".toByteArray()
                ssdp.send(DatagramPacket(reply, reply.size, p.socketAddress))
            }
        }
        return ssdp.localPort
    }

    @Test
    fun `NAT-PMP yoksa UPnP denenir, yalnizca suresiz kabul eden modeme uyum saglanir`() {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val ssdp = fakeUpnp(onlyPermanent = true, soapCalls = calls)
        val mapper = PortMapper(natPmpPort = freePort(), ssdpTarget = InetSocketAddress("127.0.0.1", ssdp), timeoutMs = 1_000)
        val r = mapper.map(InetAddress.getLoopbackAddress(), InetAddress.getByName("192.168.1.20"), 8443)
        val m = assertNotNull(r.mapping, r.errors.joinToString())
        assertEquals(PortMapper.UPNP, m.method)
        assertEquals(0, m.lifetimeSec)
        assertEquals("100.70.1.2", m.externalIp?.hostAddress)
        assertTrue(r.errors.single().startsWith("NAT-PMP"), r.errors.joinToString())
        assertEquals(listOf("AddPortMapping 3600", "AddPortMapping 0", "GetExternalIPAddress"), calls.toList())

        mapper.unmap(null, m)
        assertEquals("DeletePortMapping", calls.last())
    }

    @Test
    fun `modem yanit vermezse yonlendirme yapilamaz, nedenler bildirilir`() {
        val r = PortMapper(natPmpPort = freePort(), ssdpTarget = InetSocketAddress("127.0.0.1", freePort()), timeoutMs = 500)
            .map(InetAddress.getLoopbackAddress(), InetAddress.getByName("192.168.1.20"), 8443)
        assertNull(r.mapping)
        assertEquals(2, r.errors.size, r.errors.joinToString())
    }

    private fun iface(kind: NetInterface.Kind, vararg addrs: String) =
        NetInterface(kind.name.lowercase(), kind, addrs.map(InetAddress::getByName))

    @Test
    fun `ulasilabilirlik ve CGNAT tespiti`() {
        assertEquals(AddressScope.SHARED, AddressScope.of(InetAddress.getByName("100.64.0.1")))
        assertEquals(AddressScope.PRIVATE, AddressScope.of(InetAddress.getByName("192.168.1.5")))
        assertEquals(AddressScope.PUBLIC, AddressScope.of(InetAddress.getByName("2a02:ff0::1")))
        assertEquals(AddressScope.LINK_LOCAL, AddressScope.of(InetAddress.getByName("fe80::1")))
        assertEquals(AddressScope.UNIQUE_LOCAL, AddressScope.of(InetAddress.getByName("fd00::1")))

        val wifi = listOf(iface(NetInterface.Kind.WIFI, "192.168.1.20"))
        val ok = Reachability.assess(wifi, NetInterface.Kind.WIFI, InetAddress.getByName("85.100.1.2"), InetAddress.getByName("85.100.1.2"), true)
        assertEquals(listOf("85.100.1.2"), ok.candidates)
        assertTrue(ok.problems.isEmpty(), ok.problems.joinToString())

        val cgnat = Reachability.assess(wifi, NetInterface.Kind.WIFI, InetAddress.getByName("100.70.1.2"), null, true)
        assertTrue(cgnat.candidates.isEmpty())
        assertTrue(cgnat.problems.single().contains("CGNAT"), cgnat.problems.joinToString())

        val doubleNat = Reachability.assess(wifi, NetInterface.Kind.WIFI, InetAddress.getByName("85.100.1.2"), InetAddress.getByName("88.1.1.1"), true)
        assertTrue(doubleNat.problems.single().contains("ikinci"), doubleNat.problems.joinToString())

        val mobile = listOf(iface(NetInterface.Kind.MOBILE, "100.72.3.4", "2a02:ff0:1::5"))
        val m = Reachability.assess(mobile, NetInterface.Kind.MOBILE, null, null, false)
        assertEquals(listOf("[2a02:ff0:1:0:0:0:0:5]"), m.candidates)
        assertTrue(m.problems.any { it.contains("CGNAT") } && m.problems.any { it.contains("operatöre bağlı") }, m.problems.joinToString())

        // Otomatik yönlendirme kapalı (kullanıcı elle yaptı): dışarıdan görünen adres aday olur.
        val manual = Reachability.assess(wifi, NetInterface.Kind.WIFI, null, InetAddress.getByName("85.100.1.2"), true)
        assertEquals(listOf("85.100.1.2"), manual.candidates)
        // Modemin dış adresi özel ağ: iç içe modem veya CGNAT; aday yok.
        val inner = Reachability.assess(wifi, NetInterface.Kind.WIFI, InetAddress.getByName("10.70.70.89"), InetAddress.getByName("85.100.1.2"), true)
        assertTrue(inner.candidates.isEmpty())
        assertTrue(inner.problems.any { "köprü" in it && "10.70.70.89" in it }, inner.problems.joinToString())

        val noV6 = Reachability.assess(listOf(iface(NetInterface.Kind.MOBILE, "100.72.3.4")), NetInterface.Kind.MOBILE, null, null, false)
        assertTrue(noV6.candidates.isEmpty())
        assertTrue(noV6.problems.any { it.contains("Cloudflare") }, noV6.problems.joinToString())
    }

    @Test
    fun `dogrudan erisim HTTPS ucunu acar, modemde yonlendirir ve DuckDNS'i gunceller`(): Unit = runBlocking {
        val backendPort = echoBackend()
        val natPmp = fakeNatPmp(byteArrayOf(85, 100, 1, 2))
        val wanHits = Collections.synchronizedList(mutableListOf<String>())
        val wan = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/ip") { ex -> val b = "85.100.1.2".toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) } }
            createContext("/update") { ex ->
                wanHits += ex.requestURI.rawQuery
                val b = "OK".toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
            }
            start()
        }
        closers += { wan.stop(0) }
        val base = "http://127.0.0.1:${wan.address.port}"

        val probe = object : NetworkProbe {
            override fun interfaces() = listOf(iface(NetInterface.Kind.WIFI, "192.168.1.20"))
            override fun gateway(): InetAddress = InetAddress.getLoopbackAddress()
        }
        val identity = TlsIdentity.generate()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        closers += { scope.cancel() }
        val direct = DirectAccess(
            probe, { identity }, scope,
            mapper = PortMapper(natPmpPort = natPmp, ssdpTarget = InetSocketAddress("127.0.0.1", freePort()), timeoutMs = 1_000),
            wan = WanServices(ipv4Url = "$base/ip", duckDnsUrl = "$base/update"),
        )
        closers += direct::stop

        val httpsPort = freePort()
        val cfg = ServerConfig(
            server = HttpConfig(port = backendPort),
            access = AccessConfig(AccessMode.DIRECT, direct = DirectAccessConfig(port = httpsPort, ddns = DdnsConfig("evim", "tok"))),
        )
        direct.apply(cfg)
        val status = withTimeout(10_000) { direct.status.first { it.checkedAt != null } }
        assertTrue(status.active)
        assertEquals(identity.fingerprint, status.fingerprint)
        assertEquals(listOf("85.100.1.2"), status.addresses, status.problems.joinToString())
        assertEquals(PortMapper.NAT_PMP, status.mapping?.method)
        assertEquals("evim.duckdns.org", status.ddnsHost)
        assertNull(status.ddnsError)
        // Yalnızca IPv4 var: eski AAAA kaydı temizlenir, A yazılır.
        assertEquals(listOf("domains=evim&token=tok&clear=true", "domains=evim&token=tok&ip=85.100.1.2"), wanHits.toList())
        assertTrue(status.ddnsActive)

        val (code, body) = https(httpsPort, pinnedClient(identity.certificate))
        assertEquals(200, code, body)

        direct.apply(cfg.copy(access = AccessConfig(AccessMode.LOCAL)))
        assertFalse(direct.status.value.active)
        assertFailsWith<java.io.IOException> { https(httpsPort, pinnedClient(identity.certificate)) }
    }

    /** Sahte ipify + DuckDNS; DuckDNS isteklerinin sorgu dizgelerini toplar. */
    private fun fakeWan(observed: String): Pair<WanServices, MutableList<String>> {
        val hits = Collections.synchronizedList(mutableListOf<String>())
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/ip") { ex -> val b = observed.toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) } }
            createContext("/update") { ex ->
                hits += ex.requestURI.rawQuery
                val b = "OK".toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
            }
            start()
        }
        closers += { srv.stop(0) }
        val base = "http://127.0.0.1:${srv.address.port}"
        return WanServices(ipv4Url = "$base/ip", duckDnsUrl = "$base/update") to hits
    }

    private suspend fun directStatus(probe: NetworkProbe, wan: WanServices, network: DirectNetwork, natPmp: Int): DirectStatus {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        closers += { scope.cancel() }
        val identity = TlsIdentity.generate()
        val direct = DirectAccess(
            probe, { identity }, scope,
            mapper = PortMapper(natPmpPort = natPmp, ssdpTarget = InetSocketAddress("127.0.0.1", freePort()), timeoutMs = 1_000),
            wan = wan,
        )
        closers += direct::stop
        direct.apply(
            ServerConfig(
                server = HttpConfig(port = freePort()),
                access = AccessConfig(AccessMode.DIRECT, direct = DirectAccessConfig(network = network, port = freePort(), ddns = DdnsConfig("evim", "tok"))),
            ),
        )
        return withTimeout(10_000) { direct.status.first { it.checkedAt != null } }
    }

    @Test
    fun `CGNAT'ta DuckDNS'e ozel adres yazilmaz, kayit temizlenir`(): Unit = runBlocking {
        val (wan, hits) = fakeWan("85.100.1.2")
        val probe = object : NetworkProbe {
            override fun interfaces() = listOf(iface(NetInterface.Kind.WIFI, "192.168.1.20"))
            override fun gateway(): InetAddress = InetAddress.getLoopbackAddress()
        }
        val status = directStatus(probe, wan, DirectNetwork.WIFI, fakeNatPmp(byteArrayOf(10, 70, 70, 89)))
        assertNotNull(status.mapping, "modemde yönlendirme açıldı ama dış adresi özel")
        assertTrue(status.addresses.isEmpty())
        assertEquals(listOf("domains=evim&token=tok&clear=true"), hits.toList())
        assertFalse(status.ddnsActive)
    }

    @Test
    fun `mobilde yalnizca IPv6 yayinlanir, eski IPv4 kaydi temizlenir`(): Unit = runBlocking {
        val (wan, hits) = fakeWan("85.100.1.2")
        val probe = object : NetworkProbe {
            override fun interfaces() = listOf(iface(NetInterface.Kind.MOBILE, "100.72.3.4", "2a02:ff0:1::5"))
            override fun gateway(): InetAddress? = null
        }
        val status = directStatus(probe, wan, DirectNetwork.MOBILE, freePort())
        assertEquals(listOf("[2a02:ff0:1:0:0:0:0:5]"), status.addresses)
        assertEquals(listOf("domains=evim&token=tok&clear=true", "domains=evim&token=tok&ip=2a02%3Aff0%3A1%3A0%3A0%3A0%3A0%3A5"), hits.toList())
        assertTrue(status.ddnsActive)
    }
}
