package dev.droidserver.core.backup

import dev.droidserver.core.i18n.tr
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Yedeklerin parolayla şifrelenmesi (bulut yedeği). Biçim:
 *
 * - başlık: `DSBK`, sürüm (1), PBKDF2 tekrar sayısı (int), tuz (16), nonce öneki (8)
 * - parçalar: uzunluk (int) + AES-256-GCM şifreli 1 MiB'lık parça; nonce = önek + parça sırası; ek veri = başlık +
 *   "son parça mı" baytı. Böylece parça çıkarma, sıra değiştirme ve kesilme fark edilir; dosya belleğe alınmaz.
 *
 * Anahtar PBKDF2-HMAC-SHA256 ile parolada türetilir; parola yanlışsa ilk parçada hata verir.
 */
object BackupCrypto {
    private val MAGIC = byteArrayOf('D'.code.toByte(), 'S'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte())
    private const val VERSION = 1
    private const val ITERATIONS = 200_000
    private const val CHUNK = 1 shl 20
    private const val HEADER = 4 + 1 + 4 + 16 + 8

    class WrongPassphraseException : RuntimeException(tr("Şifreleme parolası yanlış veya yedek dosyası bozuk"))

    fun encrypt(input: InputStream, output: OutputStream, passphrase: String, random: SecureRandom = SecureRandom()) {
        val salt = ByteArray(16).also(random::nextBytes)
        val prefix = ByteArray(8).also(random::nextBytes)
        val header = java.io.ByteArrayOutputStream().also { b ->
            DataOutputStream(b).apply { write(MAGIC); writeByte(VERSION); writeInt(ITERATIONS); write(salt); write(prefix) }
        }.toByteArray()
        val key = derive(passphrase, salt, ITERATIONS)
        val out = DataOutputStream(output)
        out.write(header)
        var current = readChunk(input)
        var index = 0
        while (true) {
            val next = if (current.size == CHUNK) readChunk(input) else ByteArray(0)
            val last = next.isEmpty()
            val sealed = cipher(Cipher.ENCRYPT_MODE, key, prefix, index, header, last).doFinal(current)
            out.writeInt(sealed.size)
            out.write(sealed)
            if (last) break
            current = next
            index++
        }
        out.flush()
    }

    fun decrypt(input: InputStream, output: OutputStream, passphrase: String) {
        val inp = DataInputStream(input)
        val header = ByteArray(HEADER)
        try {
            inp.readFully(header)
        } catch (_: EOFException) {
            throw IllegalArgumentException(tr("DroidServer şifreli yedeği değil"))
        }
        require(header.copyOfRange(0, 4).contentEquals(MAGIC)) { tr("DroidServer şifreli yedeği değil") }
        require(header[4].toInt() == VERSION) { tr("Bu yedek sürümü desteklenmiyor ({0})", header[4]) }
        val iterations = java.nio.ByteBuffer.wrap(header, 5, 4).int
        require(iterations in 10_000..10_000_000) { tr("Yedek başlığı bozuk") }
        val salt = header.copyOfRange(9, 25)
        val prefix = header.copyOfRange(25, 33)
        val key = derive(passphrase, salt, iterations)
        var index = 0
        while (true) {
            val size = try { inp.readInt() } catch (_: EOFException) { throw IllegalArgumentException(tr("Yedek dosyası eksik (son parça yok)")) }
            require(size in 16..CHUNK + 16) { tr("Yedek dosyası bozuk") }
            val sealed = ByteArray(size).also(inp::readFully)
            // Son parça mı bilinmiyor: önce "son değil" ile, olmazsa "son" ile çözülür (ek veri farklı).
            val plain = runCatching { cipher(Cipher.DECRYPT_MODE, key, prefix, index, header, false).doFinal(sealed) }.getOrNull()
            if (plain != null) {
                output.write(plain)
                index++
                continue
            }
            val final = try {
                cipher(Cipher.DECRYPT_MODE, key, prefix, index, header, true).doFinal(sealed)
            } catch (_: GeneralSecurityException) {
                throw WrongPassphraseException()
            }
            output.write(final)
            require(inp.read() == -1) { tr("Yedek dosyasının sonunda fazladan veri var") }
            output.flush()
            return
        }
    }

    private fun readChunk(input: InputStream): ByteArray {
        val buf = ByteArray(CHUNK)
        var n = 0
        while (n < CHUNK) {
            val r = input.read(buf, n, CHUNK - n)
            if (r < 0) break
            n += r
        }
        return if (n == CHUNK) buf else buf.copyOf(n)
    }

    private fun derive(passphrase: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, 256)
        return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
    }

    private fun cipher(mode: Int, key: SecretKeySpec, prefix: ByteArray, index: Int, header: ByteArray, last: Boolean): Cipher {
        val nonce = prefix + java.nio.ByteBuffer.allocate(4).putInt(index).array()
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(128, nonce))
            updateAAD(header + byteArrayOf(if (last) 1 else 0))
        }
    }
}
