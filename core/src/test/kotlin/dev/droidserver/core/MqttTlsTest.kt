package dev.droidserver.core

import dev.droidserver.core.api.droidServerModule
import dev.droidserver.core.config.AuthConfig
import dev.droidserver.core.config.AuthMode
import dev.droidserver.core.config.ConfigValidator
import dev.droidserver.core.config.HttpConfig
import dev.droidserver.core.config.MqttConfig
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.mqtt.MqttBroker
import dev.droidserver.core.net.LocalCa
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.DataInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.file.Files
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** MQTT TLS (17.5): yerel kök, IP adresli sunucu sertifikası, TLS dinleyicisi ve düz portun kapatılması. */
class MqttTlsTest {
    private val dir: File = Files.createTempDirectory("ds-ca").toFile().apply { deleteOnExit() }
    private val password = "tls-parolasi".toCharArray()
    private val lan = InetAddress.getByName("192.168.1.20")
    private val loopback = InetAddress.getByName("127.0.0.1")

    private fun freePort() = ServerSocket(0).use { it.localPort }

    /** Yalnızca yerel köke güvenen, adı/IP'yi de denetleyen (HTTPS kuralı) istemci bağlamı. */
    private fun trusting(pem: String): SSLContext {
        val root = CertificateFactory.getInstance("X.509").generateCertificate(pem.byteInputStream()) as X509Certificate
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null); setCertificateEntry("root", root) }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
        return SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }
    }

    /** TLS üzerinden MQTT CONNECT; CONNACK dönüş kodu. */
    private fun connect(context: SSLContext, port: Int, user: String, pass: String): Int {
        val socket = context.socketFactory.createSocket("127.0.0.1", port) as SSLSocket
        socket.use { s ->
            s.sslParameters = s.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            s.soTimeout = 5_000
            fun str(v: String) = v.toByteArray().let { byteArrayOf((it.size shr 8).toByte(), it.size.toByte()) + it }
            s.outputStream.write(MqttBroker.packet(0x10, str("MQTT") + byteArrayOf(4, 0xC2.toByte(), 0, 60) + str("tls-cihaz") + str(user) + str(pass)))
            val input = DataInputStream(s.inputStream)
            assertEquals(0x20, input.readUnsignedByte())
            input.readUnsignedByte()
            input.readUnsignedByte()
            return input.readUnsignedByte()
        }
    }

    @Test
    fun `kok ve ip adresli sunucu sertifikasi`() {
        var ips = listOf(lan, loopback)
        var now = Instant.parse("2026-10-23T10:00:00Z")
        val ca = LocalCa(dir, password, clock = { now }, addresses = { ips })
        val root = ca.root.certificate
        assertTrue(root.basicConstraints >= 0, "kök CA olmalı")
        assertTrue(root.keyUsage[5], "kök sertifika imzalar (keyCertSign)")
        assertTrue(ca.rootPem.startsWith("-----BEGIN CERTIFICATE-----\n"))

        val first = ca.server()
        val leaf = first.certificate
        leaf.verify(root.publicKey)
        assertEquals(-1, leaf.basicConstraints, "sunucu sertifikası CA değil")
        assertEquals(listOf("1.3.6.1.5.5.7.3.1"), leaf.extendedKeyUsage)
        val sans = leaf.subjectAlternativeNames.map { it[1] as String }.toSet()
        assertEquals(setOf("localhost", "192.168.1.20", "127.0.0.1"), sans)
        assertEquals(root.subjectX500Principal, leaf.issuerX500Principal)
        assertEquals(2, first.chain.size)
        // Anahtar kimlikleri (Python 3.13'ün sıkı X.509 denetimi ister).
        assertTrue(root.getExtensionValue("2.5.29.14") != null && leaf.getExtensionValue("2.5.29.35") != null && leaf.getExtensionValue("2.5.29.14") != null)

        // Aynı adresler: aynı sertifika; yeni örnek de dosyadan aynısını okur.
        assertEquals(leaf, ca.server().certificate)
        assertEquals(leaf, LocalCa(dir, password, clock = { now }, addresses = { ips }).server().certificate)
        assertEquals(root, LocalCa(dir, password, clock = { now }, addresses = { ips }).root.certificate)

        // Adres değişince aynı anahtarla yeniden imzalanır.
        ips = listOf(InetAddress.getByName("10.0.0.5"))
        val moved = ca.server()
        assertNotEquals(leaf, moved.certificate)
        assertEquals(leaf.publicKey, moved.certificate.publicKey)
        assertTrue("10.0.0.5" in moved.certificate.subjectAlternativeNames.map { it[1] })

        // Bitişine 30 günden az kalınca yenilenir.
        now = now.plus(Duration.ofDays(380))
        val renewed = ca.server()
        assertNotEquals(moved.certificate, renewed.certificate)
        assertTrue(renewed.certificate.notAfter.toInstant().isAfter(now.plus(Duration.ofDays(300))))
    }

    @Test
    fun `tls dinleyicisi ve duz portun kapatilmasi`() {
        val ca = LocalCa(dir, password, addresses = { listOf(loopback) })
        val plainPort = freePort()
        val tlsPort = freePort()
        val httpPort = freePort()
        val key = "yonetici-anahtari-1234567890"
        val config = ServerConfig(
            server = HttpConfig(port = httpPort, host = "127.0.0.1"),
            auth = AuthConfig(AuthMode.API_KEY, apiKeys = listOf(key)),
            mqtt = MqttConfig(enabled = true, port = plainPort, plain = false, tls = true, tlsPort = tlsPort, username = "device", password = "cihaz-parolasi"),
        )
        assertTrue(ConfigValidator.validateDetailed(config).none { it.path.startsWith("mqtt") })
        val db = TestDb.create(Dialect.SQLITE).open()
        val server = embeddedServer(CIO, port = httpPort, host = "127.0.0.1") { droidServerModule(config, db, mqttCa = ca) }.start(wait = false)
        try {
            val trusted = trusting(ca.rootPem)
            assertEquals(0, connect(trusted, tlsPort, "device", "cihaz-parolasi"), "köke güvenen istemci IP adını da doğrular")
            assertEquals(4, connect(trusted, tlsPort, "device", "yanlis"), "TLS'te de parola denetlenir")
            // Başka bir köke güvenen istemci el sıkışamaz.
            val other = LocalCa(Files.createTempDirectory("ds-ca2").toFile(), password, addresses = { listOf(loopback) })
            assertFailsWith<SSLHandshakeException> { connect(trusting(other.rootPem), tlsPort, "device", "cihaz-parolasi") }
            assertFailsWith<java.net.ConnectException>("düz port kapalı") { Socket("127.0.0.1", plainPort).close() }

            fun get(path: String): Pair<Int, String> {
                val c = URI("http://127.0.0.1:$httpPort/api$path").toURL().openConnection() as HttpURLConnection
                c.setRequestProperty("Authorization", "Bearer $key")
                val code = c.responseCode
                return code to ((if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty())
            }
            val (code, pem) = get("/_mqtt/ca.pem")
            assertEquals(200, code)
            assertEquals(ca.rootPem, pem)
            val status = Json.parseToJsonElement(get("/_mqtt").second).jsonObject
            assertEquals("true", status.getValue("tls").jsonPrimitive.content)
            assertEquals("false", status.getValue("plain").jsonPrimitive.content)
            assertEquals(ca.root.fingerprint, status.getValue("caFingerprint").jsonPrimitive.content)
            assertTrue(status.getValue("clients").jsonArray.isEmpty())
        } finally {
            server.stop(100, 1000)
            db.close()
        }

        val errors = ConfigValidator.validateDetailed(config.copy(mqtt = config.mqtt.copy(tls = false))).map { it.path }
        assertTrue("mqtt.plain" in errors, "düz port ancak TLS açıkken kapatılır: $errors")
        val clash = ConfigValidator.validateDetailed(config.copy(mqtt = config.mqtt.copy(plain = true, tlsPort = plainPort))).map { it.path }
        assertTrue("mqtt.tlsPort" in clash, clash.toString())
    }
}
