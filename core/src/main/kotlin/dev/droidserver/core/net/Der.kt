package dev.droidserver.core.net

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * En küçük DER kodlayıcı: kendinden imzalı sertifika ve Let's Encrypt için sertifika isteği (CSR) üretmeye yetecek kadar.
 * Android'de BouncyCastle'ın tam sürümü olmadığı ve JDK'nın sertifika üreticisi dahili olduğu için elle yazıldı.
 */
internal object Der {
    const val ECDSA_SHA256 = "1.2.840.10045.4.3.2"
    const val COMMON_NAME = "2.5.4.3"
    const val SUBJECT_ALT_NAME = "2.5.29.17"
    const val EXTENSION_REQUEST = "1.2.840.113549.1.9.14"

    fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val n = content.size
        when {
            n < 0x80 -> out.write(n)
            n < 0x100 -> { out.write(0x81); out.write(n) }
            n < 0x10000 -> { out.write(0x82); out.write(n shr 8); out.write(n and 0xFF) }
            else -> { out.write(0x83); out.write(n shr 16); out.write((n shr 8) and 0xFF); out.write(n and 0xFF) }
        }
        out.write(content)
        return out.toByteArray()
    }

    private fun concat(parts: Array<out ByteArray>) = parts.fold(ByteArray(0)) { acc, p -> acc + p }

    fun seq(vararg parts: ByteArray) = tlv(0x30, concat(parts))
    fun set(vararg parts: ByteArray) = tlv(0x31, concat(parts))
    fun int(v: BigInteger) = tlv(0x02, v.toByteArray())
    fun utf8(s: String) = tlv(0x0C, s.toByteArray(Charsets.UTF_8))
    fun octets(b: ByteArray) = tlv(0x04, b)
    fun bits(b: ByteArray) = tlv(0x03, byteArrayOf(0) + b)

    /** Bağlama özgü, yapılandırılmış etiket: `[n]`. */
    fun explicit(n: Int, vararg parts: ByteArray) = tlv(0xA0 + n, concat(parts))

    fun oid(dotted: String): ByteArray {
        val arcs = dotted.split('.').map { it.toLong() }
        val out = ByteArrayOutputStream()
        out.write((arcs[0] * 40 + arcs[1]).toInt())
        for (arc in arcs.drop(2)) {
            val bytes = mutableListOf<Int>()
            var v = arc
            do { bytes.add(0, (v and 0x7F).toInt()); v = v shr 7 } while (v > 0)
            bytes.forEachIndexed { i, b -> out.write(if (i < bytes.lastIndex) b or 0x80 else b) }
        }
        return tlv(0x06, out.toByteArray())
    }

    /** 2050 öncesi UTCTime, sonrası GeneralizedTime (RFC 5280). */
    fun time(t: ZonedDateTime): ByteArray =
        if (t.year < 2050) tlv(0x17, t.format(DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'")).toByteArray())
        else tlv(0x18, t.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss'Z'")).toByteArray())

    fun name(commonName: String) = seq(set(seq(oid(COMMON_NAME), utf8(commonName))))

    /** subjectAltName uzantısının değeri: tek bir DNS adı (`[2] IA5String`). */
    private fun dnsNames(vararg names: String) = seq(*names.map { tlv(0x82, it.toByteArray(Charsets.US_ASCII)) }.toTypedArray())

    fun sign(key: PrivateKey, data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(key)
        update(data)
        sign()
    }

    /**
     * [issuerKey] ile imzalı X.509 v3 sertifikası (EC P-256 / SHA-256). [dnsName] verilirse subjectAltName uzantısı eklenir.
     * Kendinden imzalı sertifika için konu ve yayıncı aynıdır.
     */
    fun certificate(
        subject: String,
        subjectKey: PublicKey,
        issuer: String,
        issuerKey: PrivateKey,
        notBefore: ZonedDateTime,
        notAfter: ZonedDateTime,
        dnsName: String? = null,
    ): X509Certificate {
        val algorithm = seq(oid(ECDSA_SHA256))
        val extensions = dnsName?.let { explicit(3, seq(seq(oid(SUBJECT_ALT_NAME), octets(dnsNames(it))))) } ?: ByteArray(0)
        val tbs = seq(
            explicit(0, int(BigInteger.valueOf(2))), // v3
            int(BigInteger(64, SecureRandom()).add(BigInteger.ONE)),
            algorithm,
            name(issuer),
            seq(time(notBefore.withZoneSameInstant(ZoneOffset.UTC).withNano(0)), time(notAfter.withZoneSameInstant(ZoneOffset.UTC).withNano(0))),
            name(subject),
            subjectKey.encoded, // SubjectPublicKeyInfo zaten DER
            extensions,
        )
        val der = seq(tbs, algorithm, bits(sign(issuerKey, tbs)))
        return CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
    }

    /** PKCS#10 sertifika isteği: konu `CN=<host>`, subjectAltName `<host>`, anahtarın kendisiyle imzalı. */
    fun csr(host: String, publicKey: PublicKey, privateKey: PrivateKey): ByteArray {
        val extensions = seq(seq(oid(SUBJECT_ALT_NAME), octets(dnsNames(host))))
        val info = seq(
            int(BigInteger.ZERO),
            name(host),
            publicKey.encoded,
            explicit(0, seq(oid(EXTENSION_REQUEST), set(extensions))),
        )
        return seq(info, seq(oid(ECDSA_SHA256)), bits(sign(privateKey, info)))
    }
}
