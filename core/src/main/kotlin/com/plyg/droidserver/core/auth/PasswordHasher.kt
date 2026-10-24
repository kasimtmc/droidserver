package com.plyg.droidserver.core.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Parola özeti: PBKDF2-HMAC-SHA256 (JDK'da ve Android'de yerleşik), rastgele 16 baytlık tuz.
 * Saklanan biçim `pbkdf2$<tur>$<tuz>$<özet>` (base64): tur sayısı özetle birlikte durduğu için ileride artırılabilir,
 * önek de algoritmanın değiştirilebilmesi için. Karşılaştırma sabit zamanlıdır.
 */
class PasswordHasher(val iterations: Int = DEFAULT_ITERATIONS) {
    private val random = SecureRandom()

    /**
     * Var olmayan e-postayla girişte de bir özet hesaplanır; yanıt süresi hesabın var olup olmadığını belli etmesin.
     */
    private val dummy by lazy { hash("droidserver-dummy-password") }

    fun hash(password: String): String {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        return listOf(PREFIX, iterations.toString(), b64(salt), b64(derive(password, salt, iterations))).joinToString("$")
    }

    fun verify(password: String, stored: String): Boolean {
        val parts = stored.split('$')
        if (parts.size != 4 || parts[0] != PREFIX) return false
        val rounds = parts[1].toIntOrNull()?.takeIf { it in 1..MAX_ITERATIONS } ?: return false
        val salt = runCatching { Base64.getDecoder().decode(parts[2]) }.getOrNull() ?: return false
        val expected = runCatching { Base64.getDecoder().decode(parts[3]) }.getOrNull() ?: return false
        return MessageDigest.isEqual(derive(password, salt, rounds, expected.size * 8), expected)
    }

    /** Hesap yokken [verify] ile aynı sürede false döner. */
    fun verifyDummy(password: String): Boolean {
        verify(password, dummy)
        return false
    }

    private fun derive(password: String, salt: ByteArray, rounds: Int, bits: Int = HASH_BYTES * 8): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, rounds, bits)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun b64(bytes: ByteArray) = Base64.getEncoder().withoutPadding().encodeToString(bytes)

    companion object {
        /** OWASP'ın PBKDF2-HMAC-SHA256 önerisi. */
        const val DEFAULT_ITERATIONS = 310_000
        private const val MAX_ITERATIONS = 10_000_000
        private const val PREFIX = "pbkdf2"
        private const val SALT_BYTES = 16
        private const val HASH_BYTES = 32
    }
}
