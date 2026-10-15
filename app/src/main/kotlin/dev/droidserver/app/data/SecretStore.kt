package dev.droidserver.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import dev.droidserver.core.config.SecretSink
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Konfigürasyondaki sırları (API anahtarları, JWT sırrı) Android Keystore'daki bir AES-GCM anahtarıyla
 * şifreleyip SharedPreferences'ta saklar. server.json'da yalnızca `"secret:<ad>"` referansı durur;
 * referanslar deploy anında [get] ile çözülür. Anahtar cihazdan dışarı çıkarılamaz.
 */
class SecretStore(context: Context) : SecretSink {
    private val prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)
    private val random = SecureRandom()

    @Synchronized
    fun get(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        // Keystore anahtarı kaybolmuşsa çözülemez; deploy "sır bulunamadı" der, kullanıcı yeniden girer.
        return runCatching { decrypt(stored) }.getOrNull()
    }

    /** commit: referansı server.json'a yazılmadan önce sır diskte olmalı. */
    @Synchronized
    fun put(name: String, value: String) {
        prefs.edit(commit = true) { putString(name, encrypt(value)) }
    }

    /** Değeri saklar; aynı değer aynı türde zaten varsa (örn. geçmişteki kopyalar) onun adını döndürür. */
    @Synchronized
    override fun store(kind: String, value: String): String {
        prefs.all.keys.firstOrNull { it.startsWith("${kind}_") && get(it) == value }?.let { return it }
        var name: String
        do {
            name = "${kind}_" + ByteArray(4).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        } while (prefs.contains(name))
        put(name, value)
        return name
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ct = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return b64(cipher.iv) + ":" + b64(ct)
    }

    private fun decrypt(stored: String): String {
        val (iv, ct) = stored.split(':', limit = 2).map { Base64.decode(it, Base64.NO_WRAP) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        return cipher.doFinal(ct).toString(Charsets.UTF_8)
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "droidserver-secrets"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
