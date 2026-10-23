package dev.droidserver.core.auth

import dev.droidserver.core.api.ApiException
import dev.droidserver.core.i18n.tr
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import java.math.BigInteger
import java.net.URI
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.RSAPublicKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * WebAuthn (passkey) doğrulaması, bağımlılıksız: kayıtta `attestationObject` (CBOR) çözülür ve açık anahtar alınır
 * (attestation "none" istenir, sertifika zinciri denetlenmez); girişte imza `authenticatorData || SHA-256(clientDataJSON)`
 * üzerinde doğrulanır. Algoritmalar ES256 (-7) ve RS256 (-257); Ed25519 Android'de API 33 öncesinde olmadığı için yok.
 * Açık anahtar X.509 (SPKI) olarak saklanır.
 */
object WebAuthn {
    const val ES256 = -7L
    const val RS256 = -257L
    val ALGORITHMS = listOf(ES256, RS256)

    class Registration(val credentialId: ByteArray, val publicKey: ByteArray, val algorithm: Long, val signCount: Long, val userVerified: Boolean)

    class Assertion(val signCount: Long, val userVerified: Boolean)

    fun challenge(): String = b64url(ByteArray(32).also(SecureRandom()::nextBytes))

    /** `clientDataJSON`'daki challenge (bekleyen isteği bulmak için); okunamazsa null. */
    fun challengeOf(clientDataJson: ByteArray): String? =
        runCatching { (Json.parseToJsonElement(clientDataJson.decodeToString()).jsonObject["challenge"] as? JsonPrimitive)?.content }.getOrNull()

    fun b64url(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

    fun fromB64url(s: String?): ByteArray = try {
        Base64.getUrlDecoder().decode(s.orEmpty().trimEnd('=').replace('+', '-').replace('/', '_'))
    } catch (e: IllegalArgumentException) {
        throw ApiException(400, tr("Passkey verisi bozuk (base64url bekleniyor)"))
    }

    /**
     * Tarayıcının bildirdiği köken RP kimliğine uyuyor mu: `https://<rpId>[:port]` veya alt alan adı. `localhost` geliştirme
     * için `http` ile de kabul edilir (tarayıcılar onu güvenli sayar).
     */
    fun originAllowed(origin: String, rpId: String): Boolean {
        val uri = runCatching { URI(origin) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        val id = rpId.lowercase()
        val hostOk = host == id || host.endsWith(".$id")
        return hostOk && (uri.scheme == "https" || (uri.scheme == "http" && id == "localhost"))
    }

    fun verifyRegistration(clientDataJson: ByteArray, attestationObject: ByteArray, challenge: String, rpId: String): Registration {
        checkClientData(clientDataJson, "webauthn.create", challenge, rpId)
        val att = cbor(attestationObject) as? Map<*, *> ?: fail()
        val authData = att["authData"] as? ByteArray ?: fail()
        val flags = checkAuthData(authData, rpId)
        if (flags and 0x40 == 0) fail()
        var i = 37 + 16 // rpIdHash, bayraklar, sayaç, AAGUID
        if (authData.size < i + 2) fail()
        val idLen = ((authData[i].toInt() and 0xFF) shl 8) or (authData[i + 1].toInt() and 0xFF)
        i += 2
        if (authData.size < i + idLen) fail()
        val credentialId = authData.copyOfRange(i, i + idLen)
        i += idLen
        val reader = Cbor.Reader(authData, i)
        val cose = reader.read() as? Map<*, *> ?: fail()
        val alg = cose[3L] as? Long ?: fail()
        val key = when (alg) {
            ES256 -> {
                if (cose[1L] != 2L || cose[-1L] != 1L) fail()
                val x = cose[-2L] as? ByteArray ?: fail()
                val y = cose[-3L] as? ByteArray ?: fail()
                KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(BigInteger(1, x), BigInteger(1, y)), p256))
            }
            RS256 -> {
                if (cose[1L] != 3L) fail()
                val n = cose[-1L] as? ByteArray ?: fail()
                val e = cose[-2L] as? ByteArray ?: fail()
                KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(BigInteger(1, n), BigInteger(1, e)))
            }
            else -> throw ApiException(400, tr("Bu passkey algoritması desteklenmiyor ({0}); ES256 veya RS256 kullanın", alg))
        }
        return Registration(credentialId, key.encoded, alg, counter(authData), flags and 0x04 != 0)
    }

    /** [storedCount] > 0 iken sayaç ilerlemediyse anahtar kopyalanmış olabilir: giriş reddedilir. */
    fun verifyAssertion(
        clientDataJson: ByteArray, authenticatorData: ByteArray, signature: ByteArray, challenge: String, rpId: String,
        publicKey: ByteArray, algorithm: Long, storedCount: Long,
    ): Assertion {
        checkClientData(clientDataJson, "webauthn.get", challenge, rpId)
        val flags = checkAuthData(authenticatorData, rpId)
        val ok = runCatching {
            Signature.getInstance(if (algorithm == RS256) "SHA256withRSA" else "SHA256withECDSA").run {
                initVerify(decodeKey(publicKey, algorithm))
                update(authenticatorData)
                update(sha256(clientDataJson))
                verify(signature)
            }
        }.getOrDefault(false)
        if (!ok) throw ApiException(401, tr("Passkey imzası doğrulanamadı"))
        val count = counter(authenticatorData)
        if ((storedCount > 0 || count > 0) && count <= storedCount) {
            throw ApiException(401, tr("Passkey sayacı geriledi; anahtar kopyalanmış olabilir. Bu anahtarı silip yeniden ekleyin."))
        }
        return Assertion(count, flags and 0x04 != 0)
    }

    private fun checkClientData(raw: ByteArray, type: String, challenge: String, rpId: String) {
        val data = runCatching { Json.parseToJsonElement(raw.decodeToString()).jsonObject }.getOrNull() ?: fail()
        fun s(k: String) = (data[k] as? JsonPrimitive)?.content
        if (s("type") != type) fail()
        if (!MessageDigest.isEqual(fromB64url(s("challenge")), fromB64url(challenge))) throw ApiException(401, tr("Passkey isteğinin süresi doldu veya geçersiz; yeniden deneyin"))
        val origin = s("origin").orEmpty()
        if (!originAllowed(origin, rpId)) throw ApiException(401, tr("Passkey bu adres için değil ({0}); sunucunun alan adı {1}", origin, rpId))
        if ((data["crossOrigin"] as? JsonPrimitive)?.booleanOrNull == true) fail()
    }

    /** RP kimliği özeti ve "kullanıcı var" bayrağı; bayrakları döndürür. */
    private fun checkAuthData(authData: ByteArray, rpId: String): Int {
        if (authData.size < 37) fail()
        if (!MessageDigest.isEqual(authData.copyOfRange(0, 32), sha256(rpId.lowercase().toByteArray()))) {
            throw ApiException(401, tr("Passkey başka bir alan adı için oluşturulmuş"))
        }
        val flags = authData[32].toInt() and 0xFF
        if (flags and 0x01 == 0) fail()
        return flags
    }

    private fun counter(authData: ByteArray): Long =
        (0 until 4).fold(0L) { acc, k -> (acc shl 8) or (authData[33 + k].toLong() and 0xFF) }

    private fun decodeKey(spki: ByteArray, algorithm: Long): PublicKey =
        KeyFactory.getInstance(if (algorithm == RS256) "RSA" else "EC").generatePublic(X509EncodedKeySpec(spki))

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)

    private fun cbor(b: ByteArray): Any? = try {
        Cbor.Reader(b).read()
    } catch (e: RuntimeException) {
        fail()
    }

    private fun fail(): Nothing = throw ApiException(400, tr("Passkey yanıtı geçersiz"))

    /** P-256 eğrisinin parametreleri (Android'de AlgorithmParameters'a güvenilmez; bir anahtar üretilip alınır). */
    private val p256: ECParameterSpec by lazy {
        (KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().public as ECPublicKey).params
    }

    /** İstemcinin istediği tarayıcı ayarı: `navigator.credentials.create({ publicKey })` için ortak alanlar. */
    fun pubKeyCredParams(): List<JsonObject> = ALGORITHMS.map { JsonObject(mapOf("type" to JsonPrimitive("public-key"), "alg" to JsonPrimitive(it))) }
}

/** WebAuthn için yeterli küçük CBOR çözücü: tamsayı, bayt ve metin dizisi, dizi, eşlem, true/false/null. */
internal object Cbor {
    class Reader(private val b: ByteArray, private var pos: Int = 0) {
        fun read(): Any? {
            val ib = byte()
            val major = ib shr 5
            val info = ib and 0x1F
            return when (major) {
                0 -> length(info)
                1 -> -1 - length(info)
                2 -> bytes(length(info))
                3 -> bytes(length(info)).decodeToString()
                4 -> List(length(info).toInt()) { read() }
                5 -> {
                    val n = length(info).toInt()
                    LinkedHashMap<Any?, Any?>().apply { repeat(n) { put(read(), read()) } }
                }
                7 -> when (info) {
                    20 -> false
                    21 -> true
                    22, 23 -> null
                    else -> throw IllegalArgumentException("CBOR: desteklenmeyen değer")
                }
                else -> throw IllegalArgumentException("CBOR: desteklenmeyen tür $major")
            }
        }

        private fun byte(): Int = b[pos++].toInt() and 0xFF

        private fun length(info: Int): Long = when {
            info < 24 -> info.toLong()
            info in 24..27 -> (0 until (1 shl (info - 24))).fold(0L) { acc, _ -> (acc shl 8) or byte().toLong() }
            else -> throw IllegalArgumentException("CBOR: belirsiz uzunluk desteklenmez")
        }

        private fun bytes(n: Long): ByteArray {
            require(n >= 0 && pos + n <= b.size) { "CBOR: kısa veri" }
            return b.copyOfRange(pos, pos + n.toInt()).also { pos += n.toInt() }
        }
    }
}
