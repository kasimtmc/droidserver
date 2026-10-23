package dev.droidserver.core.net

import dev.droidserver.core.i18n.tr
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Base64
import javax.net.ssl.SSLContext

/**
 * Yerel ağ için sertifika yetkilisi (MQTT TLS). Telefon bir kez kök sertifika üretir ([root]); cihazlara bu kök bir kez
 * yüklenir ([rootPem]). Sunucu sertifikası kökle imzalanır ve telefonun yerel IP adreslerini içerir; adresler değişince
 * ya da bitişine [RENEW_BEFORE] kalınca aynı anahtarla yeniden imzalanır, cihazlarda ayar gerekmez.
 *
 * Kök ve sunucu anahtarı [dir] altında parolalı PKCS12 dosyalarında (`mqtt-ca.p12`, `mqtt-server.p12`).
 */
class LocalCa(
    private val dir: File,
    private val password: CharArray,
    private val clock: () -> Instant = Instant::now,
    /** Telefonun yerel adresleri (testlerde sabit). */
    private val addresses: () -> List<InetAddress> = ::localAddresses,
    private val log: (String) -> Unit = {},
) {
    private val rootFile = File(dir, "mqtt-ca.p12")
    private val serverFile = File(dir, "mqtt-server.p12")

    /** Kök: ilk kullanımda üretilir, 2049'a kadar geçerli. */
    val root: TlsIdentity by lazy {
        runCatching { if (rootFile.exists()) TlsIdentity.load(rootFile, password) else null }
            .onFailure { log(tr("MQTT kök sertifikası okunamadı ({0}); yenisi üretiliyor", it.message ?: "")) }.getOrNull()
            ?: createRoot().also { it.save(rootFile, password); log(tr("MQTT kök sertifikası üretildi: {0}", it.fingerprint)) }
    }

    /** Cihazlara yüklenecek kök sertifika (PEM). */
    val rootPem: String
        get() = "-----BEGIN CERTIFICATE-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(root.certificate.encoded) + "\n-----END CERTIFICATE-----\n"

    @Volatile private var cached: Pair<Set<InetAddress>, TlsIdentity>? = null

    /** Güncel sunucu kimliği: adresler değiştiyse ya da süresi doluyorsa yeniden imzalanır. */
    @Synchronized
    fun server(): TlsIdentity {
        val ips = addresses().toSet()
        cached?.let { (a, id) -> if (a == ips && fresh(id)) return id }
        val existing = runCatching { if (serverFile.exists()) TlsIdentity.load(serverFile, password) else null }.getOrNull()
        val id = existing?.takeIf { fresh(it) && covers(it, ips) && it.chain.last().publicKey == root.certificate.publicKey }
            ?: issue(ips, existing).also { it.save(serverFile, password) }
        cached = ips to id
        return id
    }

    /** Her yeni bağlantıda çağrılır; kimlik değişmediyse aynı bağlam. */
    @Synchronized
    fun sslContext(): SSLContext {
        val id = server()
        context?.takeIf { it.first === id }?.let { return it.second }
        return id.sslContext().also { context = id to it }
    }

    @Volatile private var context: Pair<TlsIdentity, SSLContext>? = null

    private fun fresh(id: TlsIdentity) = Duration.between(clock(), id.certificate.notAfter.toInstant()) > RENEW_BEFORE

    private fun covers(id: TlsIdentity, ips: Set<InetAddress>): Boolean {
        val names = id.certificate.subjectAlternativeNames.orEmpty().filter { it[0] == 7 }.map { InetAddress.getByName(it[1] as String) }.toSet()
        return names == ips
    }

    private fun createRoot(): TlsIdentity {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val now = ZonedDateTime.ofInstant(clock(), ZoneOffset.UTC).withNano(0)
        val cert = Der.certificate(
            ROOT_NAME, pair.public, ROOT_NAME, pair.private, now.minusDays(1), ZonedDateTime.of(2049, 12, 31, 23, 59, 59, 0, ZoneOffset.UTC),
            extra = listOf(Der.basicConstraints(ca = true), Der.keyUsage(ca = true), Der.subjectKeyId(pair.public)),
        )
        return TlsIdentity(cert, pair.private)
    }

    /** Sunucu sertifikası: önceki anahtar korunur (yoksa yenisi), kökle imzalı, [VALIDITY] geçerli. */
    private fun issue(ips: Set<InetAddress>, previous: TlsIdentity?): TlsIdentity {
        val key = previous?.let { java.security.KeyPair(it.certificate.publicKey, it.privateKey) }
            ?: KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val now = ZonedDateTime.ofInstant(clock(), ZoneOffset.UTC).withNano(0)
        val sorted = ips.sortedBy { it.hostAddress }
        val cert = Der.certificate(
            SERVER_NAME, key.public, ROOT_NAME, root.privateKey, now.minusDays(1), now.plus(VALIDITY),
            extra = listOf(
                Der.basicConstraints(ca = false), Der.keyUsage(ca = false), Der.serverAuth(),
                Der.subjectKeyId(key.public), Der.authorityKeyId(root.certificate.publicKey),
                Der.altNames(listOf("localhost"), sorted),
            ),
        )
        cert.verify(root.certificate.publicKey)
        log(tr("MQTT sunucu sertifikası imzalandı: {0}", sorted.joinToString { it.hostAddress.substringBefore('%') }))
        return TlsIdentity(listOf(cert, root.certificate), key.private)
    }

    companion object {
        const val ROOT_NAME = "DroidServer yerel kök"
        const val SERVER_NAME = "DroidServer MQTT"

        /** Apple'ın özel köklerle de uyguladığı en uzun süre 825 gün; bir yıl yeter, kendiliğinden yenilenir. */
        val VALIDITY: Duration = Duration.ofDays(397)
        val RENEW_BEFORE: Duration = Duration.ofDays(30)

        /** Yerel ağ adresleri: geri döngü ve bağlantı yerel (fe80) dışındaki IPv4 ve IPv6 adresleri. */
        fun localAddresses(): List<InetAddress> = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filter { a -> !a.isLoopbackAddress && !a.isLinkLocalAddress && (a is Inet4Address || a is Inet6Address) }
                .map { InetAddress.getByAddress(it.address) } // IPv6 kapsam kimliği (%wlan0) atılır
                .distinct()
        }.getOrDefault(emptyList())
    }
}
