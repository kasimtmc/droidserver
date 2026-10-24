package com.plyg.droidserver.core

import com.plyg.droidserver.core.auth.WebAuthn
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * Testlerde tarayıcı + passkey doğrulayıcısı taklidi: `navigator.credentials.create/get` yanıtlarını (base64url
 * alanlarıyla) üretir. [rpId] anahtarın bağlandığı alan adı, [origin] tarayıcının bildirdiği köken.
 */
class TestAuthenticator(val keys: KeyPair, val alg: Long, val rpId: String, val origin: String) {
    val id = ByteArray(16).also(java.security.SecureRandom()::nextBytes)
    val credentialId: String get() = WebAuthn.b64url(id)
    var count = 0L

    class Created(val clientData: ByteArray, val attestationObject: ByteArray)
    class Asserted(val clientData: ByteArray, val authenticatorData: ByteArray, val signature: ByteArray)

    fun create(challenge: String, rp: String = rpId, origin: String = this.origin, fmt: String = "none"): Created {
        val attested = ByteArray(16) + byteArrayOf(0, id.size.toByte()) + id + coseKey()
        val att = Cbor.map("fmt" to fmt, "attStmt" to Cbor.Raw(byteArrayOf(0xA0.toByte())), "authData" to authData(rp, 0x45, 0, attested))
        return Created(clientData("webauthn.create", challenge, origin), att)
    }

    fun get(challenge: String, tamper: Boolean = false): Asserted {
        count++
        val data = authData(rpId, 0x05, count)
        val client = clientData("webauthn.get", challenge, origin)
        val sig = Signature.getInstance(if (alg == WebAuthn.RS256) "SHA256withRSA" else "SHA256withECDSA").run {
            initSign(keys.private); update(data); update(sha256(client)); sign()
        }
        if (tamper) sig[sig.size - 1] = (sig.last() + 1).toByte()
        return Asserted(client, data, sig)
    }

    /** Sunucuya gönderilen gövde (kayıt). */
    fun registrationJson(challenge: String, name: String): String {
        val c = create(challenge)
        return """{"name":"$name","credential":{"id":"$credentialId","type":"public-key","response":{"clientDataJSON":"${WebAuthn.b64url(c.clientData)}","attestationObject":"${WebAuthn.b64url(c.attestationObject)}"}}}"""
    }

    /** Sunucuya gönderilen gövde (giriş). */
    fun assertionJson(challenge: String): String {
        val a = get(challenge)
        return """{"credential":{"id":"$credentialId","type":"public-key","response":{"clientDataJSON":"${WebAuthn.b64url(a.clientData)}","authenticatorData":"${WebAuthn.b64url(a.authenticatorData)}","signature":"${WebAuthn.b64url(a.signature)}"}}}"""
    }

    private fun clientData(type: String, challenge: String, origin: String) =
        """{"type":"$type","challenge":"$challenge","origin":"$origin","crossOrigin":false}""".toByteArray()

    private fun authData(rp: String, flags: Int, count: Long, attested: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(sha256(rp.toByteArray()))
        out.write(flags)
        for (k in 3 downTo 0) out.write(((count shr (8 * k)) and 0xFF).toInt())
        out.write(attested)
        return out.toByteArray()
    }

    private fun coseKey(): ByteArray = when (val pub = keys.public) {
        is ECPublicKey -> Cbor.map(1 to 2, 3 to -7, -1 to 1, -2 to unsigned(pub.w.affineX, 32), -3 to unsigned(pub.w.affineY, 32))
        is RSAPublicKey -> Cbor.map(1 to 3, 3 to -257, -1 to pub.modulus.toByteArray().dropWhile { it == 0.toByte() }.toByteArray(), -2 to pub.publicExponent.toByteArray())
        else -> error("anahtar")
    }

    companion object {
        fun ec(rpId: String, origin: String) =
            TestAuthenticator(KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair(), WebAuthn.ES256, rpId, origin)

        fun rsa(rpId: String, origin: String) =
            TestAuthenticator(KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair(), WebAuthn.RS256, rpId, origin)

        fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

        private fun unsigned(n: BigInteger, size: Int): ByteArray {
            val b = n.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
            return ByteArray(size - b.size) + b
        }
    }

    /** WebAuthn'in kullandığı kadar CBOR kodlayıcı. */
    private object Cbor {
        class Raw(val bytes: ByteArray)

        fun map(vararg entries: Pair<Any, Any>): ByteArray = ByteArrayOutputStream().apply {
            head(5, entries.size.toLong())
            entries.forEach { (k, v) -> write(enc(k)); write(enc(v)) }
        }.toByteArray()

        private fun enc(v: Any): ByteArray = when (v) {
            is Raw -> v.bytes
            is ByteArray -> ByteArrayOutputStream().apply { head(2, v.size.toLong()); write(v) }.toByteArray()
            is String -> v.toByteArray().let { b -> ByteArrayOutputStream().apply { head(3, b.size.toLong()); write(b) }.toByteArray() }
            is Int -> ByteArrayOutputStream().apply { if (v >= 0) head(0, v.toLong()) else head(1, -1L - v) }.toByteArray()
            else -> error("tür")
        }

        private fun ByteArrayOutputStream.head(major: Int, n: Long) {
            when {
                n < 24 -> write((major shl 5) or n.toInt())
                n < 256 -> { write((major shl 5) or 24); write(n.toInt()) }
                else -> { write((major shl 5) or 25); write((n shr 8).toInt()); write((n and 0xFF).toInt()) }
            }
        }
    }
}
