package com.plyg.droidserver.core.push

import com.plyg.droidserver.core.i18n.tr
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URI
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Tarayıcının `PushSubscription`'ı: itme servisinin adresi ve şifreleme anahtarları (base64url). */
data class PushSubscription(val endpoint: String, val p256dh: String, val auth: String)

/** P-256 yardımcıları ve VAPID anahtar çifti. */
object Vapid {
    private val random = SecureRandom()

    fun keyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()

    /** Yeni anahtar çifti: açık anahtar (sıkıştırılmamış nokta) ve özel anahtar (PKCS#8), ikisi de base64url. */
    fun generate(): Pair<String, String> {
        val kp = keyPair()
        return b64(uncompressed(kp.public as ECPublicKey)) to b64(kp.private.encoded)
    }

    fun privateKey(pkcs8: String): ECPrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(unb64(pkcs8))) as ECPrivateKey

    /** 65 bayt: 0x04 || X || Y. */
    fun uncompressed(key: ECPublicKey): ByteArray = byteArrayOf(4) + fixed(key.w.affineX) + fixed(key.w.affineY)

    /** Sıkıştırılmamış noktadan açık anahtar; [params] eğrinin parametreleri (aynı eğride bir anahtardan). */
    fun publicKey(point: ByteArray, params: ECParameterSpec): ECPublicKey {
        require(point.size == 65 && point[0] == 4.toByte()) { tr("geçersiz P-256 açık anahtarı") }
        val p = ECPoint(BigInteger(1, point.copyOfRange(1, 33)), BigInteger(1, point.copyOfRange(33, 65)))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(p, params)) as ECPublicKey
    }

    fun ecdh(private: PrivateKey, public: PublicKey): ByteArray =
        KeyAgreement.getInstance("ECDH").run { init(private); doPhase(public, true); generateSecret() }

    private fun fixed(v: BigInteger): ByteArray {
        val raw = v.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        return ByteArray(32 - raw.size) + raw
    }

    fun b64(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

    /** base64url (dolgulu veya dolgusuz) ve standart base64 kabul edilir. */
    fun unb64(s: String): ByteArray = Base64.getUrlDecoder().decode(s.trim().replace('+', '-').replace('/', '_').trimEnd('='))
}

/**
 * Web push gönderici: içerik RFC 8291 (`aes128gcm`) ile tarayıcının anahtarına şifrelenir, istek RFC 8292 VAPID
 * JWT'siyle (ES256) imzalanır. İtme servisinin yanıt kodunu döndürür (404/410: abonelik artık geçersiz).
 */
class WebPushSender(
    private val publicKey: String,
    privateKey: String,
    private val subject: String,
    private val clock: () -> Instant = Instant::now,
    private val timeoutMs: Int = 10_000,
) {
    private val signingKey = Vapid.privateKey(privateKey)
    private val random = SecureRandom()

    fun send(sub: PushSubscription, payload: ByteArray, ttlSeconds: Int = 86_400): Int {
        val body = encrypt(payload, Vapid.unb64(sub.p256dh), Vapid.unb64(sub.auth))
        val uri = URI(sub.endpoint)
        val c = uri.toURL().openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.instanceFollowRedirects = false
            c.doOutput = true
            c.setRequestProperty("TTL", ttlSeconds.toString())
            c.setRequestProperty("Urgency", "normal")
            c.setRequestProperty("Content-Encoding", "aes128gcm")
            c.setRequestProperty("Content-Type", "application/octet-stream")
            c.setRequestProperty("Authorization", "vapid t=${vapidJwt(uri)}, k=$publicKey")
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            (if (code < 400) c.inputStream else c.errorStream)?.use { it.skip(64 * 1024) }
            return code
        } finally {
            c.disconnect()
        }
    }

    /** `aud` itme servisinin kökeni, en çok 12 saat geçerli. */
    internal fun vapidJwt(endpoint: URI): String {
        val origin = endpoint.scheme + "://" + endpoint.host + (if (endpoint.port != -1) ":${endpoint.port}" else "")
        val header = Vapid.b64("""{"typ":"JWT","alg":"ES256"}""".toByteArray())
        val exp = clock().epochSecond + 12 * 3600
        val claims = Vapid.b64("""{"aud":"$origin","exp":$exp,"sub":"${subject.replace("\"", "")}"}""".toByteArray())
        val der = Signature.getInstance("SHA256withECDSA").run { initSign(signingKey); update("$header.$claims".toByteArray()); sign() }
        return "$header.$claims.${Vapid.b64(p1363(der))}"
    }

    /** RFC 8291: tek kayıt, `rs` 4096, dolgu sınırlayıcısı 0x02. */
    internal fun encrypt(plaintext: ByteArray, uaPublic: ByteArray, authSecret: ByteArray, salt: ByteArray? = null, ephemeral: KeyPair? = null): ByteArray {
        require(plaintext.size <= MAX_PAYLOAD) { tr("Bildirim en çok {0} bayt olabilir", MAX_PAYLOAD) }
        require(authSecret.size == 16) { tr("geçersiz auth anahtarı") }
        val asKeys = ephemeral ?: Vapid.keyPair()
        val asPublic = Vapid.uncompressed(asKeys.public as ECPublicKey)
        val ua = Vapid.publicKey(uaPublic, (asKeys.public as ECPublicKey).params)
        val shared = Vapid.ecdh(asKeys.private, ua)
        val s = salt ?: ByteArray(16).also(random::nextBytes)

        val prkKey = hmac(authSecret, shared)
        val ikm = hmac(prkKey, "WebPush: info".toByteArray() + 0 + uaPublic + asPublic + 1)
        val prk = hmac(s, ikm)
        val cek = hmac(prk, "Content-Encoding: aes128gcm".toByteArray() + 0 + 1).copyOf(16)
        val nonce = hmac(prk, "Content-Encoding: nonce".toByteArray() + 0 + 1).copyOf(12)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, nonce)) }
        val encrypted = cipher.doFinal(plaintext + 2)
        return ByteBuffer.allocate(16 + 4 + 1 + asPublic.size + encrypted.size)
            .put(s).putInt(RECORD_SIZE).put(asPublic.size.toByte()).put(asPublic).put(encrypted).array()
    }

    private operator fun ByteArray.plus(b: Int): ByteArray = this + byteArrayOf(b.toByte())

    companion object {
        /** 4096 baytlık kayıttan başlık ve etiket düşülünce güvenle kalan. */
        const val MAX_PAYLOAD = 3_800
        private const val RECORD_SIZE = 4096

        fun hmac(key: ByteArray, data: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }

        /** DER ECDSA imzası → r||s (64 bayt). */
        internal fun p1363(der: ByteArray): ByteArray {
            var i = 2
            if (der[1].toInt() and 0x80 != 0) i += der[1].toInt() and 0x7F
            fun next(): ByteArray {
                check(der[i].toInt() == 0x02) { tr("Beklenmeyen imza biçimi") }
                val len = der[i + 1].toInt()
                val raw = der.copyOfRange(i + 2, i + 2 + len).dropWhile { it == 0.toByte() }.toByteArray()
                i += 2 + len
                return ByteArray(32 - raw.size) + raw
            }
            return next() + next()
        }
    }
}
