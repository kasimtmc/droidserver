package com.plyg.droidserver.core.net

import com.plyg.droidserver.core.i18n.tr
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Base64
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * Doğrudan erişimin HTTPS kimliği: telefonda üretilen EC P-256 anahtarı ve kendinden imzalı sertifika.
 * Herkesçe tanınan bir sertifika IP adresi için alınamaz (veya alan adı + 80/443 portu gerektirir); istemciler
 * sertifikayı [publicKeyPin] ile sabitler, böylece trafik hem şifreli hem de araya giren birine karşı korunur.
 *
 * DuckDNS adı varsa aynı anahtarla Let's Encrypt sertifikası da alınabilir ([CertificateManager]); o zaman zincir
 * Let's Encrypt'in ara sertifikalarını da içerir, sabitleme (aynı anahtar) değişmez.
 *
 * Anahtar uygulamanın özel klasöründe PKCS12 dosyasında, dosya parolası sır deposunda durur (app: Keystore).
 */
class TlsIdentity(val chain: List<X509Certificate>, val privateKey: PrivateKey) {
    constructor(certificate: X509Certificate, key: PrivateKey) : this(listOf(certificate), key)

    init {
        require(chain.isNotEmpty()) { tr("Sertifika zinciri boş") }
    }

    /** Sunucunun kendi sertifikası (zincirin ilki). */
    val certificate: X509Certificate get() = chain.first()

    fun sslContext(): SSLContext {
        // Bellekteki depo; bazı PKCS12 sağlayıcıları boş parolayı kabul etmediği için rastgele bir parola.
        val pass = java.util.UUID.randomUUID().toString().toCharArray()
        val ks = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry(ALIAS, privateKey, pass, chain.toTypedArray())
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, pass) }
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, SecureRandom()) }
    }

    /** Sertifikanın SHA-256 parmak izi, `AB:CD:...` biçiminde (tarayıcıların gösterdiği). */
    val fingerprint: String
        get() = MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString(":") { "%02X".format(it) }

    /** Açık anahtar sabitlemesi; curl için `--pinnedpubkey "sha256//<bu değer>"`. Aynı anahtarla yenilenen sertifikada değişmez. */
    val publicKeyPin: String
        get() = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded))

    /** Sertifikanın kendinden imzalı olup olmadığı (konu = yayıncı). */
    val selfSigned: Boolean get() = chain.size == 1 && certificate.subjectX500Principal == certificate.issuerX500Principal

    fun save(file: File, password: CharArray) {
        val ks = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry(ALIAS, privateKey, password, chain.toTypedArray())
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().use { ks.store(it, password) }
        check(tmp.renameTo(file) || (file.delete() && tmp.renameTo(file))) { tr("Sertifika dosyası yazılamadı: {0}", file) }
    }

    companion object {
        private const val ALIAS = "droidserver"

        fun load(file: File, password: CharArray): TlsIdentity {
            val ks = KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it, password) } }
            val chain = ks.getCertificateChain(ALIAS)?.map { it as X509Certificate } ?: listOf(ks.getCertificate(ALIAS) as X509Certificate)
            return TlsIdentity(chain, ks.getKey(ALIAS, password) as PrivateKey)
        }

        /** Dosya varsa yükler; yoksa veya okunamıyorsa yeni kimlik üretip kaydeder. */
        fun loadOrCreate(file: File, password: CharArray, log: (String) -> Unit = {}): TlsIdentity {
            if (file.exists()) {
                runCatching { return load(file, password) }.onFailure { log("HTTPS sertifikası okunamadı (${it.message}); yenisi üretiliyor") }
            }
            return generate().also { it.save(file, password); log("HTTPS sertifikası üretildi: ${it.fingerprint}") }
        }

        /** Yeni anahtar ve 2049'a kadar geçerli kendinden imzalı sertifika. */
        fun generate(commonName: String = "DroidServer"): TlsIdentity {
            val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            val now = ZonedDateTime.now(ZoneOffset.UTC).withNano(0)
            val cert = Der.certificate(
                commonName, pair.public, commonName, pair.private,
                now.minusDays(1), ZonedDateTime.of(2049, 12, 31, 23, 59, 59, 0, ZoneOffset.UTC),
            )
            cert.verify(pair.public)
            return TlsIdentity(cert, pair.private)
        }

        /** PEM sertifika zinciri (Let's Encrypt'in verdiği biçim) ve özel anahtardan. */
        fun fromPem(pem: String, key: PrivateKey): TlsIdentity {
            val factory = CertificateFactory.getInstance("X.509")
            val chain = PEM.findAll(pem).map { m ->
                val der = Base64.getMimeDecoder().decode(m.groupValues[1])
                factory.generateCertificate(der.inputStream()) as X509Certificate
            }.toList()
            require(chain.isNotEmpty()) { tr("Yanıtta sertifika yok") }
            return TlsIdentity(chain, key)
        }

        private val PEM = Regex("-----BEGIN CERTIFICATE-----([A-Za-z0-9+/=\\s]+)-----END CERTIFICATE-----")
    }
}
