package com.plyg.droidserver.core.net

import com.plyg.droidserver.core.i18n.tr
import java.io.File
import java.security.KeyPair
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant

/**
 * Doğrudan erişimin Let's Encrypt sertifikası. Doğrulama DNS-01 ile DuckDNS'in TXT kaydı üzerinden yapılır: 80/443
 * portlarının açık olması gerekmez, sunucu hangi portta olursa olsun tarayıcılar sertifikaya güvenir.
 *
 * Sertifika HTTPS ucunun mevcut anahtarıyla istenir; böylece açık anahtar sabitlemesi (pin) yenilemelerde de değişmez.
 * Sertifika ve ACME hesap anahtarı [dir] altında, kendinden imzalı kimlikle aynı parolalı PKCS12 dosyalarında durur.
 * Bitişine [RENEW_BEFORE]'dan az kalınca yenilenir (Let's Encrypt sertifikaları 90 gün geçerli).
 */
class CertificateManager(
    private val dir: File,
    private val password: CharArray,
    private val wan: WanServices = WanServices(),
    private val directoryUrl: String = LETS_ENCRYPT,
    private val clock: () -> Instant = Instant::now,
    private val log: (String) -> Unit = {},
    /** TXT kaydının yayılmasını bekler (testlerde beklemesiz). */
    private val awaitTxt: (name: String, value: String) -> Unit = { name, value -> wan.awaitTxt(name, value) },
    private val client: (directory: String, account: KeyPair) -> AcmeClient = { d, a -> AcmeClient(d, a) },
) {
    private val certFile = File(dir, "letsencrypt.p12")
    private val accountFile = File(dir, "acme-account.p12")

    @Volatile private var cached: TlsIdentity? = null

    /** [host] için geçerli (süresi dolmamış) Let's Encrypt kimliği; yoksa null. */
    @Synchronized
    fun current(host: String): TlsIdentity? {
        val id = cached ?: runCatching { if (certFile.exists()) TlsIdentity.load(certFile, password) else null }
            .onFailure { log("Let's Encrypt sertifikası okunamadı: ${it.message}") }.getOrNull()?.also { cached = it }
        return id?.takeIf { covers(it.certificate, host) && it.certificate.notAfter.toInstant().isAfter(clock()) }
    }

    /** Sertifika yok, başka ada ait ya da bitişine [RENEW_BEFORE]'dan az kaldıysa. */
    fun needsRenewal(host: String): Boolean {
        val until = current(host)?.certificate?.notAfter?.toInstant() ?: return true
        return Duration.between(clock(), until) < RENEW_BEFORE
    }

    /**
     * [host] (`<domain>.duckdns.org`) için [key]'in anahtarıyla sertifika alır ve kaydeder. Engelleyicidir (doğrulama
     * bir iki dakika sürebilir); ağ veya doğrulama hatasında istisna atar, mevcut sertifika yerinde kalır.
     */
    fun obtain(host: String, domain: String, token: String, key: TlsIdentity): TlsIdentity {
        val account = TlsIdentity.loadOrCreate(accountFile, password)
        val acme = client(directoryUrl, KeyPair(account.certificate.publicKey, account.privateKey))
        log("Let's Encrypt: $host için sertifika isteniyor (DNS doğrulaması, 1-2 dakika sürebilir)")
        val pem = acme.obtain(
            host,
            Der.csr(host, key.certificate.publicKey, key.privateKey),
            publishTxt = { value ->
                wan.setDuckDnsTxt(domain, token, value)
                awaitTxt("_acme-challenge.$host", value)
            },
            clearTxt = { wan.clearDuckDnsTxt(domain, token) },
        )
        val id = TlsIdentity.fromPem(pem, key.privateKey)
        if (id.certificate.publicKey != key.certificate.publicKey) throw AcmeException(tr("Gelen sertifika bu anahtara ait değil"))
        if (!covers(id.certificate, host)) throw AcmeException(tr("Gelen sertifika {0} adını içermiyor", host))
        id.save(certFile, password)
        synchronized(this) { cached = id }
        log("Let's Encrypt sertifikası alındı: $host, ${id.certificate.notAfter.toInstant()} tarihine kadar geçerli")
        return id
    }

    companion object {
        const val LETS_ENCRYPT = "https://acme-v02.api.letsencrypt.org/directory"
        const val LETS_ENCRYPT_STAGING = "https://acme-staging-v02.api.letsencrypt.org/directory"
        val RENEW_BEFORE: Duration = Duration.ofDays(30)

        fun covers(cert: X509Certificate, host: String): Boolean =
            cert.subjectAlternativeNames.orEmpty().any { it[0] == 2 && (it[1] as? String).equals(host, ignoreCase = true) }
    }
}
