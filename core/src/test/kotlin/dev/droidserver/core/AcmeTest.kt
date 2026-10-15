package dev.droidserver.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.droidserver.core.config.AccessConfig
import dev.droidserver.core.config.AccessMode
import dev.droidserver.core.config.DdnsConfig
import dev.droidserver.core.config.DirectAccess as DirectAccessConfig
import dev.droidserver.core.config.HttpConfig
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.net.AcmeClient
import dev.droidserver.core.net.AcmeException
import dev.droidserver.core.net.CertificateManager
import dev.droidserver.core.net.Der
import dev.droidserver.core.net.DirectAccess
import dev.droidserver.core.net.NetInterface
import dev.droidserver.core.net.NetworkProbe
import dev.droidserver.core.net.PortMapper
import dev.droidserver.core.net.TlsIdentity
import dev.droidserver.core.net.WanServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigInteger
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.URLDecoder
import java.nio.file.Files
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Base64
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Let's Encrypt akışı sahte bir ACME sunucusuna (RFC 8555) ve sahte DuckDNS'e karşı: JWS imzaları, nonce, hesap,
 * sipariş, DNS-01 TXT değeri, CSR ve zincir gerçekten doğrulanır.
 */
class AcmeTest {
    private val closers = mutableListOf<() -> Unit>()

    @AfterTest
    fun cleanup() = closers.reversed().forEach { runCatching(it) }

    private val host = "evim.duckdns.org"
    private val ca = caIdentity("Sahte CA")

    /** basicConstraints CA:true uzantılı kendinden imzalı kök (istemcilerin güven çapası olabilmesi için). */
    private fun caIdentity(name: String): TlsIdentity {
        val pair = java.security.KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val now = ZonedDateTime.now(ZoneOffset.UTC).withNano(0)
        val alg = Der.seq(Der.oid(Der.ECDSA_SHA256))
        val basicConstraints = Der.seq(Der.oid("2.5.29.19"), Der.tlv(0x01, byteArrayOf(-1)), Der.octets(Der.seq(Der.tlv(0x01, byteArrayOf(-1)))))
        val tbs = Der.seq(
            Der.explicit(0, Der.int(BigInteger.valueOf(2))), Der.int(BigInteger.valueOf(7)), alg, Der.name(name),
            Der.seq(Der.time(now.minusDays(1)), Der.time(now.plusYears(1))), Der.name(name), pair.public.encoded,
            Der.explicit(3, Der.seq(basicConstraints)),
        )
        val der = Der.seq(tbs, alg, Der.bits(Der.sign(pair.private, tbs)))
        val cert = java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as java.security.cert.X509Certificate
        return TlsIdentity(cert, pair.private)
    }

    /** Sahte DuckDNS: TXT kaydını tutar, istekleri kaydeder. */
    private inner class FakeDuckDns {
        @Volatile var txt: String? = null
        val hits: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val url: String

        init {
            val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            srv.createContext("/update") { ex ->
                val q = query(ex.requestURI.rawQuery)
                hits += ex.requestURI.rawQuery
                if (q.containsKey("txt")) txt = if (q["clear"] == "true") null else q["txt"]
                reply(ex, 200, "OK")
            }
            srv.createContext("/ip") { ex -> reply(ex, 200, "85.100.1.2") }
            srv.start()
            closers += { srv.stop(0) }
            url = "http://127.0.0.1:${srv.address.port}"
        }

        fun wan() = WanServices(ipv4Url = "$url/ip", duckDnsUrl = "$url/update", dohUrl = "$url/yok")
    }

    /** RFC 8555'in burada kullanılan kısmını uygulayan sahte ACME sunucusu. */
    private inner class FakeAcme(private val dns: FakeDuckDns, private val badNonceOnce: Boolean = false) {
        val base: String
        private val nonces = Collections.synchronizedSet(mutableSetOf<String>())
        private val accounts = mutableMapOf<String, PublicKey>()
        private var accountJwk: JsonObject? = null
        @Volatile var authStatus = "pending"
        @Volatile var orderStatus = "pending"
        @Volatile var certPem: String? = null
        private var orderPolls = 0
        private var sentBadNonce = false
        val token = "tok-" + System.nanoTime()
        val requests = AtomicInteger()

        init {
            val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            base = "http://127.0.0.1:${srv.address.port}"
            srv.createContext("/") { ex -> runCatching { handle(ex) }.onFailure { reply(ex, 500, problem("serverInternal", it.toString())) } }
            srv.start()
            closers += { srv.stop(0) }
        }

        private fun newNonce() = "n" + System.nanoTime().also { nonces += "n$it" }

        private fun handle(ex: HttpExchange) {
            val path = ex.requestURI.path
            ex.responseHeaders.add("Replay-Nonce", newNonce())
            when {
                path == "/dir" -> reply(ex, 200, """{"newNonce":"$base/nonce","newAccount":"$base/acct","newOrder":"$base/order"}""")
                path == "/nonce" -> { ex.sendResponseHeaders(200, -1); ex.close() }
                ex.requestMethod == "POST" -> post(ex, path)
                else -> reply(ex, 405, problem("malformed", "yalnızca POST"))
            }
        }

        private fun post(ex: HttpExchange, path: String) {
            requests.incrementAndGet()
            assertEquals("application/jose+json", ex.requestHeaders.getFirst("Content-Type"))
            val jws = Json.parseToJsonElement(ex.requestBody.readBytes().decodeToString()).jsonObject
            val protectedB64 = jws.getValue("protected").jsonPrimitive.content
            val payloadB64 = jws.getValue("payload").jsonPrimitive.content
            val header = Json.parseToJsonElement(Base64.getUrlDecoder().decode(protectedB64).decodeToString()).jsonObject
            assertEquals("ES256", header.getValue("alg").jsonPrimitive.content)
            assertEquals("$base$path", header.getValue("url").jsonPrimitive.content, "imzalı url istekle aynı olmalı")

            val nonce = header.getValue("nonce").jsonPrimitive.content
            if (!nonces.remove(nonce) || (badNonceOnce && !sentBadNonce)) {
                sentBadNonce = true
                return reply(ex, 400, problem("badNonce", "eski nonce"))
            }
            val key = header["jwk"]?.jsonObject?.let(::ecKey)
                ?: accounts[header.getValue("kid").jsonPrimitive.content]
                ?: return reply(ex, 401, problem("accountDoesNotExist", "hesap yok"))
            val ok = Signature.getInstance("SHA256withECDSAinP1363Format").run {
                initVerify(key)
                update("$protectedB64.$payloadB64".toByteArray())
                verify(Base64.getUrlDecoder().decode(jws.getValue("signature").jsonPrimitive.content))
            }
            if (!ok) return reply(ex, 401, problem("unauthorized", "imza geçersiz"))
            val payload = if (payloadB64.isEmpty()) null else Json.parseToJsonElement(Base64.getUrlDecoder().decode(payloadB64).decodeToString()).jsonObject

            when (path) {
                "/acct" -> {
                    assertEquals("true", payload?.get("termsOfServiceAgreed")?.jsonPrimitive?.content)
                    accountJwk = header.getValue("jwk").jsonObject
                    accounts["$base/acct/1"] = key
                    ex.responseHeaders.add("Location", "$base/acct/1")
                    reply(ex, 201, """{"status":"valid"}""")
                }
                "/order" -> {
                    val id = payload!!.getValue("identifiers").jsonArray.single().jsonObject
                    assertEquals(host, id.getValue("value").jsonPrimitive.content)
                    ex.responseHeaders.add("Location", "$base/order/1")
                    reply(ex, 201, order())
                }
                "/order/1" -> {
                    assertNull(payload, "POST-as-GET")
                    if (orderStatus == "processing" && ++orderPolls >= 2) orderStatus = "valid"
                    ex.responseHeaders.add("Retry-After", "1")
                    reply(ex, 200, order())
                }
                "/authz/1" -> reply(ex, 200, """{"status":"$authStatus","identifier":{"type":"dns","value":"$host"},"challenges":[
                    {"type":"http-01","url":"$base/chall/0","token":"$token","status":"pending"},
                    {"type":"dns-01","url":"$base/chall/1","token":"$token","status":"$authStatus"
                     ${if (authStatus == "invalid") ""","error":{"detail":"TXT kaydı yanlış"}""" else ""}}]}""")
                "/chall/1" -> {
                    assertEquals(JsonObject(emptyMap()), payload)
                    authStatus = if (dns.txt == expectedTxt()) "valid" else "invalid"
                    reply(ex, 200, """{"type":"dns-01","status":"processing"}""")
                }
                "/finalize/1" -> {
                    assertEquals("valid", authStatus)
                    val csr = Base64.getUrlDecoder().decode(payload!!.getValue("csr").jsonPrimitive.content)
                    certPem = issue(csr)
                    orderStatus = "processing"
                    reply(ex, 200, order())
                }
                "/cert/1" -> {
                    assertEquals("application/pem-certificate-chain", ex.requestHeaders.getFirst("Accept"))
                    reply(ex, 200, certPem!!)
                }
                else -> reply(ex, 404, problem("malformed", "yok: $path"))
            }
        }

        private fun order() = """{"status":"$orderStatus","authorizations":["$base/authz/1"],"finalize":"$base/finalize/1"
            ${if (orderStatus == "valid") ""","certificate":"$base/cert/1"""" else ""}}"""

        /** Beklenen TXT değeri, hesabın JWK'sından bağımsız hesaplanır (RFC 7638 + RFC 8555 8.4). */
        fun expectedTxt(): String {
            val j = accountJwk!!
            val canonical = """{"crv":"${j.str("crv")}","kty":"${j.str("kty")}","x":"${j.str("x")}","y":"${j.str("y")}"}"""
            val thumb = b64u(sha(canonical.toByteArray()))
            return b64u(sha("$token.$thumb".toByteArray()))
        }

        /** CSR'ı ayrıştırır, imzasını doğrular ve sahte CA ile 90 günlük sertifika verir. */
        private fun issue(csr: ByteArray): String {
            val root = DerReader(csr).node(0)
            val (info, _, sig) = root.children()
            val (_, subject, spki, attrs) = info.children()
            val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki.bytes()))
            val signed = Signature.getInstance("SHA256withECDSA").run {
                initVerify(publicKey)
                update(info.bytes())
                verify(sig.content().copyOfRange(1, sig.content().size))
            }
            assertTrue(signed, "CSR kendi anahtarıyla imzalı olmalı")
            assertTrue(String(subject.bytes(), Charsets.ISO_8859_1).contains(host), "konu CN=$host")
            assertTrue(String(attrs.bytes(), Charsets.ISO_8859_1).contains(host), "subjectAltName $host")
            val now = ZonedDateTime.now(ZoneOffset.UTC)
            val cert = Der.certificate(host, publicKey, "Sahte CA", ca.privateKey, now.minusMinutes(1), now.plusDays(90), dnsName = host)
            return pem(cert.encoded) + pem(ca.certificate.encoded)
        }
    }

    private fun JsonObject.str(k: String) = getValue(k).jsonPrimitive.content
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
    private fun b64u(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    private fun pem(der: ByteArray) = "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) + "\n-----END CERTIFICATE-----\n"

    private fun ecKey(jwk: JsonObject): PublicKey {
        val params = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)
        val point = ECPoint(BigInteger(1, Base64.getUrlDecoder().decode(jwk.str("x"))), BigInteger(1, Base64.getUrlDecoder().decode(jwk.str("y"))))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, params))
    }

    private fun query(raw: String?) = raw.orEmpty().split('&').filter { it.isNotEmpty() }
        .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") }

    private fun reply(ex: HttpExchange, code: Int, body: String) {
        val b = body.toByteArray()
        ex.responseHeaders.add("Content-Type", if (code >= 400) "application/problem+json" else "application/json")
        ex.sendResponseHeaders(code, b.size.toLong())
        ex.responseBody.use { it.write(b) }
    }

    private fun problem(type: String, detail: String) = """{"type":"urn:ietf:params:acme:error:$type","detail":"$detail"}"""

    /** Testte CSR ayrıştırmak için en küçük DER okuyucu. */
    private class DerReader(val b: ByteArray) {
        inner class Node(val start: Int, val contentStart: Int, val end: Int) {
            fun bytes() = b.copyOfRange(start, end)
            fun content() = b.copyOfRange(contentStart, end)
            fun children(): List<Node> = buildList {
                var i = contentStart
                while (i < end) node(i).also { add(it); i = it.end }
            }
        }

        fun node(at: Int): Node {
            var i = at + 1
            var len = b[i++].toInt() and 0xFF
            if (len and 0x80 != 0) {
                val n = len and 0x7F
                len = 0
                repeat(n) { len = (len shl 8) or (b[i++].toInt() and 0xFF) }
            }
            return Node(at, i, i + len)
        }
    }

    private fun manager(dir: java.io.File, dns: FakeDuckDns, acme: FakeAcme, clock: () -> Instant = Instant::now) = CertificateManager(
        dir, "parola".toCharArray(), dns.wan(), "${acme.base}/dir", clock,
        awaitTxt = { name, value -> assertEquals("_acme-challenge.$host", name); assertEquals(value, dns.txt) },
        client = { d, a -> AcmeClient(d, a, sleep = {}) },
    )

    @Test
    fun `DNS dogrulamasiyla sertifika alinir, kaydedilir ve yenileme zamani bilinir`() {
        val dns = FakeDuckDns()
        val acme = FakeAcme(dns, badNonceOnce = true)
        val dir = Files.createTempDirectory("ds-acme").toFile()
        val key = TlsIdentity.generate()

        val id = manager(dir, dns, acme).obtain(host, "evim", "tok", key)
        assertEquals(2, id.chain.size, "sunucu sertifikası + ara sertifika")
        assertTrue(CertificateManager.covers(id.certificate, host))
        assertEquals(key.publicKeyPin, id.publicKeyPin, "aynı anahtar: sabitleme değişmez")
        assertFalse(id.selfSigned)
        assertNull(dns.txt, "TXT kaydı iş bitince temizlenir")
        assertTrue(dns.hits.any { it.startsWith("domains=evim&token=tok&txt=") && "clear" !in it })
        assertEquals("domains=evim&token=tok&txt=&clear=true", dns.hits.last())

        // Yeniden açılışta dosyadan okunur; 90 günlük sertifika 30 gün kalana dek yenilenmez.
        val reopened = manager(dir, dns, acme)
        assertEquals(id.fingerprint, reopened.current(host)?.fingerprint)
        assertFalse(reopened.needsRenewal(host))
        assertNull(reopened.current("baska.duckdns.org"))
        assertTrue(reopened.needsRenewal("baska.duckdns.org"))
        assertTrue(manager(dir, dns, acme) { Instant.now() + Duration.ofDays(61) }.needsRenewal(host))
        assertNull(manager(dir, dns, acme) { Instant.now() + Duration.ofDays(91) }.current(host), "süresi dolan kullanılmaz")
    }

    @Test
    fun `dogrulama basarisizsa anlasilir hata ve TXT temizlenir`() {
        val dns = FakeDuckDns()
        val acme = FakeAcme(dns)
        val dir = Files.createTempDirectory("ds-acme").toFile()
        // TXT yazıldı ama yayılmadan önce başka bir değerle ezildi (ör. eski kayıt): doğrulama başarısız olmalı.
        val m = CertificateManager(
            dir, "parola".toCharArray(), dns.wan(), "${acme.base}/dir",
            awaitTxt = { _, _ -> dns.txt = "yanlis" },
            client = { d, a -> AcmeClient(d, a, sleep = {}) },
        )
        val e = assertFailsWith<AcmeException> { m.obtain(host, "evim", "tok", TlsIdentity.generate()) }
        assertTrue("TXT kaydı yanlış" in e.message.orEmpty(), e.message)
        assertTrue(dns.hits.last().endsWith("&clear=true"))
        assertNull(m.current(host))
    }

    @Test
    fun `dogrudan erisim Lets Encrypt sertifikasiyla acilir`() = runBlocking {
        val dns = FakeDuckDns()
        val acme = FakeAcme(dns)
        val self = TlsIdentity.generate()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        closers += { scope.cancel() }
        val probe = object : NetworkProbe {
            override fun interfaces() = listOf(NetInterface("wlan0", NetInterface.Kind.WIFI, listOf(InetAddress.getByName("192.168.1.20"))))
            override fun gateway(): InetAddress = InetAddress.getLoopbackAddress()
        }
        val direct = DirectAccess(
            probe, { self }, scope,
            mapper = PortMapper(natPmpPort = freePort(), ssdpTarget = InetSocketAddress("127.0.0.1", freePort()), timeoutMs = 300),
            wan = dns.wan(),
            certificates = manager(Files.createTempDirectory("ds-acme").toFile(), dns, acme),
        )
        closers += direct::stop
        val backend = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex -> reply(ex, 200, "merhaba") }
            start()
        }
        closers += { backend.stop(0) }

        val httpsPort = freePort()
        direct.apply(
            ServerConfig(
                server = HttpConfig(port = backend.address.port),
                access = AccessConfig(AccessMode.DIRECT, direct = DirectAccessConfig(port = httpsPort, ddns = DdnsConfig("evim", "tok", certificate = true))),
            ),
        )
        val status = withTimeout(15_000) { direct.status.first { it.certificateUntil != null } }
        assertNull(status.certificateError)
        assertEquals(self.publicKeyPin, status.publicKeyPin)

        // CA'ya güvenen istemci, ad doğrulamasıyla bağlanır (tarayıcının yaptığı gibi; burada ad 127.0.0.1'e çözülemediği için elle).
        val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null); setCertificateEntry("ca", ca.certificate) }
        val ctx = SSLContext.getInstance("TLS").apply {
            init(null, TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }.trustManagers, null)
        }
        val c = URI("https://127.0.0.1:$httpsPort/").toURL().openConnection() as HttpsURLConnection
        c.sslSocketFactory = ctx.socketFactory
        c.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, session ->
            CertificateManager.covers(session.peerCertificates[0] as java.security.cert.X509Certificate, host)
        }
        assertEquals(200, c.responseCode)
        assertEquals(2, assertNotNull(c.serverCertificates).size, "zincir ara sertifikayla birlikte gönderilir")
        assertEquals("merhaba", c.inputStream.use { it.readBytes().decodeToString() })
        c.disconnect()
        val before = acme.requests.get()
        direct.recheck()
        withTimeout(10_000) { direct.status.first { it.checking } ; direct.status.first { !it.checking } }
        assertEquals(before, acme.requests.get(), "geçerli sertifika yeniden istenmez")
    }

    private fun freePort() = ServerSocket(0).use { it.localPort }
}
