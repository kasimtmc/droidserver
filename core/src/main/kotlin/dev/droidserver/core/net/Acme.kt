package dev.droidserver.core.net

import dev.droidserver.core.i18n.tr
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URI
import java.security.KeyPair
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.util.Base64

class AcmeException(message: String) : IOException(message)

/**
 * RFC 8555 (ACME) istemcisi; Let's Encrypt'ten DNS-01 doğrulamasıyla sertifika almak için gereken kadarı.
 * Hesap anahtarı EC P-256 (ES256); hesap aynı anahtarla her seferinde yeniden "oluşturulur" (sunucu mevcut hesabı döndürür),
 * böylece hesap adresinin ayrıca saklanması gerekmez.
 */
class AcmeClient(
    private val directoryUrl: String,
    private val accountKey: KeyPair,
    private val timeoutMs: Int = 20_000,
    private val pollTimeoutMs: Long = 180_000,
    private val sleep: (Long) -> Unit = Thread::sleep,
) {
    private class Response(val code: Int, val headers: Map<String, String>, val body: String) {
        fun json(): JsonObject = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrElse { JsonObject(emptyMap()) }
        fun header(name: String) = headers[name.lowercase()]
    }

    private var directory: JsonObject? = null
    private var nonce: String? = null
    private var kid: String? = null

    private val jwk: JsonObject by lazy {
        val pub = accountKey.public as ECPublicKey
        buildJsonObject {
            put("crv", JsonPrimitive("P-256"))
            put("kty", JsonPrimitive("EC"))
            put("x", JsonPrimitive(b64u(unsigned(pub.w.affineX))))
            put("y", JsonPrimitive(b64u(unsigned(pub.w.affineY))))
        }
    }

    /** RFC 7638 JWK parmak izi: alanlar sözlük sırasında, boşluksuz. */
    val thumbprint: String by lazy {
        val j = jwk
        val canonical = """{"crv":"P-256","kty":"EC","x":"${j.str("x")}","y":"${j.str("y")}"}"""
        b64u(sha256(canonical.toByteArray()))
    }

    /** DNS-01 için `_acme-challenge` TXT kaydına yazılacak değer. */
    fun txtValue(token: String): String = b64u(sha256("$token.$thumbprint".toByteArray()))

    /**
     * [host] için sertifika alır ve PEM zincirini döndürür. Doğrulama sırasında [publishTxt] TXT kaydını yazar (ve
     * yayılmasını bekler); kayıt iş bitince, başarısız olsa da [clearTxt] ile silinir. [csr] DER biçiminde.
     */
    fun obtain(host: String, csr: ByteArray, publishTxt: (String) -> Unit, clearTxt: () -> Unit): String {
        account()
        val orderRes = post(endpoint("newOrder"), buildJsonObject {
            put("identifiers", buildJsonArray { add(buildJsonObject { put("type", JsonPrimitive("dns")); put("value", JsonPrimitive(host)) }) })
        })
        val orderUrl = orderRes.header("Location") ?: throw AcmeException(tr("Sipariş adresi gelmedi"))
        val order = orderRes.json()
        var published = false
        try {
            for (authUrl in order.getValue("authorizations").jsonArray.map { it.jsonPrimitive.content }) {
                val auth = post(authUrl, null).json()
                if (auth.str("status") == "valid") continue
                val challenge = auth["challenges"]?.jsonArray?.map { it.jsonObject }?.firstOrNull { it.str("type") == "dns-01" }
                    ?: throw AcmeException(tr("Sunucu DNS doğrulaması sunmadı"))
                publishTxt(txtValue(challenge.str("token")!!))
                published = true
                post(challenge.str("url")!!, JsonObject(emptyMap()))
                val done = poll(authUrl) { it.str("status") != "pending" && it.str("status") != "processing" }
                if (done.str("status") != "valid") {
                    val detail = done["challenges"]?.jsonArray?.map { it.jsonObject }
                        ?.firstNotNullOfOrNull { (it["error"] as? JsonObject)?.str("detail") }
                    throw AcmeException(tr("Alan adı doğrulanamadı") + (detail?.let { ": $it" } ?: ""))
                }
            }
        } finally {
            if (published) runCatching(clearTxt)
        }

        post(order.str("finalize")!!, buildJsonObject { put("csr", JsonPrimitive(b64u(csr))) })
        val finished = poll(orderUrl) { it.str("status") != "pending" && it.str("status") != "processing" && it.str("status") != "ready" }
        if (finished.str("status") != "valid") {
            throw AcmeException(tr("Sertifika verilmedi") + ((finished["error"] as? JsonObject)?.str("detail")?.let { ": $it" } ?: ""))
        }
        val certUrl = finished.str("certificate") ?: throw AcmeException(tr("Sertifika adresi gelmedi"))
        return post(certUrl, null, accept = "application/pem-certificate-chain").body
    }

    private fun account() {
        if (kid != null) return
        val res = post(endpoint("newAccount"), buildJsonObject { put("termsOfServiceAgreed", JsonPrimitive(true)) }, useJwk = true)
        kid = res.header("Location") ?: throw AcmeException(tr("Hesap adresi gelmedi"))
    }

    private fun endpoint(name: String): String {
        val dir = directory ?: get(directoryUrl).also { if (it.code != 200) throw AcmeException(tr("ACME dizini alınamadı (HTTP {0})", it.code)) }
            .json().also { directory = it }
        return dir.str(name) ?: throw AcmeException(tr("ACME dizininde {0} yok", name))
    }

    private fun newNonce(): String {
        val url = endpoint("newNonce")
        val c = open(url, "HEAD")
        try {
            c.responseCode
            return c.getHeaderField("Replay-Nonce") ?: throw AcmeException(tr("Nonce alınamadı"))
        } finally {
            c.disconnect()
        }
    }

    /** Durum [done] olana dek (Retry-After'a uyarak) POST-as-GET ile yoklar. */
    private fun poll(url: String, done: (JsonObject) -> Boolean): JsonObject {
        val deadline = System.currentTimeMillis() + pollTimeoutMs
        while (true) {
            val res = post(url, null)
            val obj = res.json()
            if (done(obj)) return obj
            if (System.currentTimeMillis() > deadline) throw AcmeException(tr("Zaman aşımı (durum: {0})", obj.str("status")))
            val wait = res.header("Retry-After")?.toLongOrNull()?.coerceIn(1, 30)?.times(1000) ?: 2_000
            sleep(wait)
        }
    }

    /** JWS ile imzalı POST; [payload] null ise POST-as-GET. Eskimiş nonce hatasında bir kez yeniden dener. */
    private fun post(url: String, payload: JsonElement?, useJwk: Boolean = false, accept: String? = null): Response {
        repeat(3) { attempt ->
            val header = buildJsonObject {
                put("alg", JsonPrimitive("ES256"))
                put("nonce", JsonPrimitive(nonce ?: newNonce()))
                put("url", JsonPrimitive(url))
                if (useJwk) put("jwk", jwk) else put("kid", JsonPrimitive(kid ?: throw AcmeException(tr("Hesap yok"))))
            }
            nonce = null
            val protected = b64u(header.toString().toByteArray())
            val body = if (payload == null) "" else b64u(payload.toString().toByteArray())
            val signature = b64u(p1363(Der.sign(accountKey.private, "$protected.$body".toByteArray())))
            val jws = buildJsonObject {
                put("protected", JsonPrimitive(protected))
                put("payload", JsonPrimitive(body))
                put("signature", JsonPrimitive(signature))
            }.toString()

            val c = open(url, "POST")
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/jose+json")
            accept?.let { c.setRequestProperty("Accept", it) }
            val res = try {
                c.outputStream.use { it.write(jws.toByteArray()) }
                read(c)
            } finally {
                c.disconnect()
            }
            res.header("Replay-Nonce")?.let { nonce = it }
            if (res.code in 200..299) return res
            val problem = res.json()
            if (problem.str("type") == "urn:ietf:params:acme:error:badNonce" && attempt < 2) return@repeat
            throw AcmeException("Let's Encrypt: " + (problem.str("detail") ?: "HTTP ${res.code}"))
        }
        throw AcmeException(tr("Let's Encrypt isteği tekrar tekrar reddedildi"))
    }

    private fun get(url: String): Response {
        val c = open(url, "GET")
        try {
            return read(c)
        } finally {
            c.disconnect()
        }
    }

    private fun open(url: String, method: String): HttpURLConnection =
        (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "DroidServer-ACME")
        }

    private fun read(c: HttpURLConnection): Response {
        val code = c.responseCode
        val stream = if (code < 400) c.inputStream else c.errorStream
        val text = stream?.use { s ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (out.size() < MAX_BODY) {
                val n = s.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
            out.toString(Charsets.UTF_8.name())
        }.orEmpty()
        val headers = c.headerFields.filterKeys { it != null }.mapKeys { it.key.lowercase() }.mapValues { it.value.firstOrNull().orEmpty() }
        return Response(code, headers, text)
    }

    companion object {
        private const val MAX_BODY = 1 shl 20

        fun b64u(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

        private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)

        /** 32 baytlık işaretsiz büyük sayı (koordinat). */
        private fun unsigned(v: BigInteger): ByteArray {
            val raw = v.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
            return ByteArray(32 - raw.size) + raw
        }

        /** DER ECDSA imzası (SEQUENCE { r, s }) → JWS'in istediği r||s (64 bayt). */
        internal fun p1363(der: ByteArray): ByteArray {
            var i = 2
            if (der[1].toInt() and 0x80 != 0) i += der[1].toInt() and 0x7F
            fun next(): BigInteger {
                check(der[i].toInt() == 0x02) { tr("Beklenmeyen imza biçimi") }
                val len = der[i + 1].toInt()
                val v = BigInteger(1, der.copyOfRange(i + 2, i + 2 + len))
                i += 2 + len
                return v
            }
            val r = next()
            val s = next()
            return unsigned(r) + unsigned(s)
        }

        private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    }
}
