package dev.droidserver.app.ui

import android.security.keystore.KeyGenParameterSpec
import dev.droidserver.app.DroidServerApp
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** Robolectric'te olmayan AndroidKeyStore'un bellek içi karşılığı (yalnızca ekran görüntüsü testleri için). */
object FakeAndroidKeyStore {
    val keys = mutableMapOf<String, Key>()

    class Store : KeyStoreSpi() {
        override fun engineGetKey(alias: String, password: CharArray?): Key? = keys[alias]
        override fun engineGetCertificateChain(alias: String?): Array<Certificate>? = null
        override fun engineGetCertificate(alias: String?): Certificate? = null
        override fun engineGetCreationDate(alias: String?): Date = Date()
        override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<out Certificate>?) { keys[alias] = key }
        override fun engineSetKeyEntry(alias: String?, key: ByteArray?, chain: Array<out Certificate>?) = Unit
        override fun engineSetCertificateEntry(alias: String?, cert: Certificate?) = Unit
        override fun engineDeleteEntry(alias: String) { keys.remove(alias) }
        override fun engineAliases(): Enumeration<String> = Collections.enumeration(keys.keys)
        override fun engineContainsAlias(alias: String) = alias in keys
        override fun engineSize() = keys.size
        override fun engineIsKeyEntry(alias: String) = alias in keys
        override fun engineIsCertificateEntry(alias: String?) = false
        override fun engineGetCertificateAlias(cert: Certificate?): String? = null
        override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit
        override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
    }

    class AesGenerator : KeyGeneratorSpi() {
        private var alias = "default"
        override fun engineInit(random: SecureRandom?) = Unit
        override fun engineInit(params: AlgorithmParameterSpec?, random: SecureRandom?) {
            alias = (params as? KeyGenParameterSpec)?.keystoreAlias ?: alias
        }
        override fun engineInit(keysize: Int, random: SecureRandom?) = Unit
        override fun engineGenerateKey(): SecretKey =
            SecretKeySpec(ByteArray(32).also(SecureRandom()::nextBytes), "AES").also { keys[alias] = it }
    }

    fun install() {
        Security.removeProvider("AndroidKeyStore")
        Security.addProvider(object : Provider("AndroidKeyStore", 1.0, "test") {
            init {
                put("KeyStore.AndroidKeyStore", Store::class.java.name)
                put("KeyGenerator.AES", AesGenerator::class.java.name)
            }
        })
    }
}

/** Ekran görüntüsü testlerinin uygulaması: keystore'u kurup gerçek uygulamayı başlatır. */
class TestDroidServerApp : DroidServerApp() {
    override fun onCreate() {
        FakeAndroidKeyStore.install()
        super.onCreate()
    }
}
