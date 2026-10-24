package com.plyg.droidserver.core.auth

import com.plyg.droidserver.core.i18n.tr
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.util.Base64

/**
 * Zamana dayalı tek kullanımlık parola (RFC 6238; HMAC-SHA1, 30 sn, 6 hane): Google Authenticator, Aegis, 1Password
 * vb. ile uyumlu. Saat kaymasına karşı bir önceki ve sonraki adım da kabul edilir.
 */
object Totp {
    const val DIGITS = 6
    const val PERIOD = 30L
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /** 160 bit rastgele gizli anahtar (Base32). */
    fun newSecret(random: SecureRandom = SecureRandom()): String = base32(ByteArray(20).also(random::nextBytes))

    fun step(now: Instant): Long = Math.floorDiv(now.epochSecond, PERIOD)

    fun code(secret: String, step: Long): String {
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(unbase32(secret), "HmacSHA1")) }
        val h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array())
        val o = h.last().toInt() and 0x0f
        val bin = ((h[o].toInt() and 0x7f) shl 24) or ((h[o + 1].toInt() and 0xff) shl 16) or
            ((h[o + 2].toInt() and 0xff) shl 8) or (h[o + 3].toInt() and 0xff)
        return (bin % 1_000_000).toString().padStart(DIGITS, '0')
    }

    /**
     * Kod eşleşen adım; yoksa null. [after]'dan büyük olmayan adımlar reddedilir (aynı kod ikinci kez kullanılamaz).
     */
    fun match(secret: String, code: String, now: Instant, after: Long? = null): Long? {
        val c = code.filter { !it.isWhitespace() }
        if (c.length != DIGITS || !c.all(Char::isDigit)) return null
        val s = step(now)
        return (s - 1..s + 1).firstOrNull { (after == null || it > after) && constantEquals(code(secret, it), c) }
    }

    /** Doğrulayıcı uygulamaya ekleme adresi (QR veya doğrudan açma). */
    fun uri(secret: String, account: String, issuer: String): String {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        return "otpauth://totp/${enc(issuer)}:${enc(account)}?secret=$secret&issuer=${enc(issuer)}&algorithm=SHA1&digits=$DIGITS&period=$PERIOD"
    }

    fun base32(bytes: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                sb.append(ALPHABET[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) sb.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return sb.toString()
    }

    fun unbase32(text: String): ByteArray {
        val clean = text.uppercase().filter { it != '=' && !it.isWhitespace() }
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (ch in clean) {
            val v = ALPHABET.indexOf(ch)
            require(v >= 0) { tr("Base32 değil: {0}", ch) }
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                out.write((buffer shr (bits - 8)) and 0xff)
                bits -= 8
            }
        }
        return out.toByteArray()
    }

    private fun constantEquals(a: String, b: String) = java.security.MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
}

/** Küçük sırların (TOTP anahtarı) veritabanında şifreli saklanması: AES-256-GCM, rastgele 96 bit IV. */
class SecretBox(key: ByteArray) {
    private val spec = SecretKeySpec(java.security.MessageDigest.getInstance("SHA-256").digest(key), "AES")
    private val random = SecureRandom()

    fun seal(plain: String): String {
        val iv = ByteArray(12).also(random::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, spec, GCMParameterSpec(128, iv)) }
        return Base64.getEncoder().encodeToString(iv + c.doFinal(plain.toByteArray()))
    }

    fun open(sealed: String): String {
        val all = Base64.getDecoder().decode(sealed)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, spec, GCMParameterSpec(128, all, 0, 12)) }
        return String(c.doFinal(all, 12, all.size - 12))
    }
}
