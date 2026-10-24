package com.plyg.droidserver.core.security

import com.plyg.droidserver.core.i18n.tr
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Şifreli alanların (bkz. `FieldConfig.encrypted`) değerleri: AES-256-GCM, her değerde rastgele 12 baytlık IV.
 * Veritabanındaki biçim `enc:v1:<anahtar kimliği>:<base64url(iv + şifreli metin + etiket)>`; anahtar kimliği
 * anahtarın SHA-256'sının ilk 8 onaltılık hanesidir (yanlış anahtarla açılmaya çalışıldığını anlamak için).
 * Anahtar telefonun sır deposunda durur (`database.fieldKey` = `secret:field_key`); yedeklere girmez.
 */
class FieldCipher(key: ByteArray) {
    init {
        require(key.size == KEY_BYTES) { tr("Alan şifreleme anahtarı 32 bayt olmalı") }
    }

    private val spec = SecretKeySpec(key, "AES")

    val keyId: String = MessageDigest.getInstance("SHA-256").digest(key).joinToString("") { "%02x".format(it) }.take(8)

    fun encrypt(plain: String): String {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, spec, GCMParameterSpec(TAG_BITS, iv)) }
        return "$PREFIX$keyId:" + B64.encodeToString(iv + cipher.doFinal(plain.toByteArray()))
    }

    /** Şifreli değeri açar; başka anahtarla şifrelenmiş veya bozulmuşsa [FieldCipherException]. */
    fun decrypt(value: String): String {
        val rest = value.removePrefix(PREFIX)
        val id = rest.substringBefore(':')
        if (id != keyId) throw FieldCipherException(tr("Değer başka bir anahtarla şifrelenmiş ({0}; bu sunucunun anahtarı {1})", id, keyId))
        val bytes = runCatching { B64D.decode(rest.substringAfter(':')) }.getOrNull()?.takeIf { it.size > IV_BYTES }
            ?: throw FieldCipherException(tr("Şifreli değer bozuk"))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, spec, GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
        }
        return try {
            cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES).decodeToString()
        } catch (_: AEADBadTagException) {
            throw FieldCipherException(tr("Şifreli değer bozuk"))
        }
    }

    companion object {
        const val PREFIX = "enc:v1:"
        private const val KEY_BYTES = 32
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private val random = SecureRandom()
        private val B64 = Base64.getUrlEncoder().withoutPadding()
        private val B64D = Base64.getUrlDecoder()

        fun isEncrypted(value: String) = value.startsWith(PREFIX)

        /** Yeni rastgele anahtar (base64, konfigürasyonda sır olarak saklanır). */
        fun generateKey(): String = Base64.getEncoder().encodeToString(ByteArray(KEY_BYTES).also(random::nextBytes))

        /** Base64 anahtardan; geçersizse [IllegalArgumentException]. */
        fun of(key: String): FieldCipher {
            val bytes = runCatching { Base64.getDecoder().decode(key.trim()) }.getOrNull()
                ?: throw IllegalArgumentException(tr("Alan şifreleme anahtarı base64 olmalı"))
            return FieldCipher(bytes)
        }
    }
}

class FieldCipherException(message: String) : RuntimeException(message)
