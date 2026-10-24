package com.plyg.droidserver.core

import com.sun.net.httpserver.HttpServer
import com.plyg.droidserver.core.api.droidServerModule
import com.plyg.droidserver.core.config.AuthConfig
import com.plyg.droidserver.core.config.AuthMode
import com.plyg.droidserver.core.config.CollectionConfig
import com.plyg.droidserver.core.config.ConfigSecrets
import com.plyg.droidserver.core.config.ConfigValidator
import com.plyg.droidserver.core.config.FieldConfig
import com.plyg.droidserver.core.config.FieldType
import com.plyg.droidserver.core.config.OnDelete
import com.plyg.droidserver.core.config.PushConfig
import com.plyg.droidserver.core.config.PushRule
import com.plyg.droidserver.core.config.Rule
import com.plyg.droidserver.core.config.Rules
import com.plyg.droidserver.core.config.ScriptsConfig
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.config.SystemCollections
import com.plyg.droidserver.core.config.UsersConfig
import com.plyg.droidserver.core.db.SchemaMigrator
import com.plyg.droidserver.core.db.SqlDriver
import com.plyg.droidserver.core.push.PushService
import com.plyg.droidserver.core.push.Vapid
import com.plyg.droidserver.core.push.WebPushSender
import com.plyg.droidserver.core.script.ScriptRuntime
import com.plyg.droidserver.core.script.ScriptSource
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.net.URI
import java.nio.ByteBuffer
import java.security.KeyPair
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.util.Collections
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Web push: RFC 8291 şifreleme (tarayıcı tarafında çözülerek), VAPID imzası, abonelik ve gönderim. */
class PushTest {
    private val key = "yonetici-anahtari-1234567890"
    private val vapid = Vapid.generate()
    private val orders = CollectionConfig(
        "orders",
        listOf(
            FieldConfig("item", FieldType.TEXT),
            FieldConfig("owner", FieldType.RELATION, collection = SystemCollections.USERS, onDelete = OnDelete.CASCADE),
        ),
        rules = Rules(Rule.OWNER, Rule.OWNER, Rule.AUTH, Rule.OWNER, Rule.OWNER),
        ownerField = "owner",
    )
    private val config = ServerConfig(
        auth = AuthConfig(AuthMode.API_KEY, apiKeys = listOf(key), users = UsersConfig(enabled = true)),
        collections = listOf(orders),
        push = PushConfig(
            enabled = true, subject = "mailto:ben@example.com", publicKey = vapid.first, privateKey = vapid.second,
            notify = listOf(PushRule("orders", listOf("update"), "Sipariş {item}", "Durum değişti", "/siparis/{id}")),
        ),
        scripts = ScriptsConfig(enabled = true),
    )
    private lateinit var db: SqlDriver
    private lateinit var service: HttpServer
    private lateinit var base: String

    private data class Hit(val path: String, val headers: Map<String, String>, val body: ByteArray)

    private val hits: MutableList<Hit> = Collections.synchronizedList(mutableListOf())
    private var status = 201

    /** Tarayıcı: kendi anahtar çifti ve auth sırrı. */
    private val browser: KeyPair = Vapid.keyPair()
    private val authSecret = ByteArray(16) { it.toByte() }

    @BeforeTest
    fun open() {
        db = TestDb.create(Dialect.SQLITE).open()
        SchemaMigrator(db).migrate(config.schemaCollections())
        service = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        service.createContext("/") { ex ->
            hits += Hit(ex.requestURI.path, ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.first() }, ex.requestBody.readBytes())
            ex.sendResponseHeaders(status, -1); ex.close()
        }
        service.start()
        base = "http://127.0.0.1:${service.address.port}"
    }

    @AfterTest
    fun close() {
        service.stop(0)
        db.close()
    }

    /** RFC 8291'i tarayıcı gibi çözer. */
    private fun decrypt(body: ByteArray): String {
        val buf = ByteBuffer.wrap(body)
        val salt = ByteArray(16).also { buf.get(it) }
        assertEquals(4096, buf.int)
        val asPublic = ByteArray(buf.get().toInt()).also { buf.get(it) }
        val cipherText = ByteArray(buf.remaining()).also { buf.get(it) }
        val uaPublic = Vapid.uncompressed(browser.public as ECPublicKey)
        val shared = Vapid.ecdh(browser.private, Vapid.publicKey(asPublic, (browser.public as ECPublicKey).params))
        fun h(k: ByteArray, d: ByteArray) = WebPushSender.hmac(k, d)
        val ikm = h(h(authSecret, shared), "WebPush: info".toByteArray() + byteArrayOf(0) + uaPublic + asPublic + byteArrayOf(1))
        val prk = h(salt, ikm)
        val cek = h(prk, "Content-Encoding: aes128gcm".toByteArray() + byteArrayOf(0, 1)).copyOf(16)
        val nonce = h(prk, "Content-Encoding: nonce".toByteArray() + byteArrayOf(0, 1)).copyOf(12)
        val plain = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, nonce)) }.doFinal(cipherText)
        assertEquals(2.toByte(), plain.last(), "son kaydın dolgu sınırlayıcısı")
        return plain.copyOf(plain.size - 1).decodeToString()
    }

    private fun subscription(path: String = "/abone-1") = """{"endpoint":"$base$path","keys":{"p256dh":"${Vapid.b64(Vapid.uncompressed(browser.public as ECPublicKey))}","auth":"${Vapid.b64(authSecret)}"}}"""

    private suspend fun HttpClient.json(path: String, body: String, token: String?): HttpResponse = post(path) {
        token?.let { bearerAuth(it) }; contentType(ContentType.Application.Json); setBody(body)
    }

    private fun awaitHits(n: Int) {
        val until = System.currentTimeMillis() + 5_000
        while (hits.size < n && System.currentTimeMillis() < until) Thread.sleep(20)
        assertTrue(hits.size >= n, "beklenen $n, gelen ${hits.size}")
    }

    @Test
    fun `sifreleme, vapid, abonelik, gonderim, kurallar ve script`() = testApplication {
        val push = PushService(db, config.push, WebPushSender(vapid.first, vapid.second, config.push.subject), allowEndpoint = { it.host == "127.0.0.1" })
        val runtime = ScriptRuntime.load(
            listOf(ScriptSource("p.js", """routerAdd("POST", "/bildir", (req, ctx) => ({ devices: ctx.push.send(ctx.auth.id, "Merhaba", "script'ten") }), "auth")""")),
            ScriptsConfig(enabled = true), setOf("orders"),
        )
        application { droidServerModule(config, db, scripts = runtime, pushService = push) }
        assertEquals(vapid.first, Json.parseToJsonElement(client.get("/api/push/key").bodyAsText()).jsonObject.getValue("publicKey").jsonPrimitive.content)

        val signup = Json.parseToJsonElement(client.json("/api/auth/signup", """{"email":"a@example.com","password":"parola-123"}""", null).bodyAsText()).jsonObject
        val token = signup.getValue("token").jsonPrimitive.content
        val userId = signup.getValue("user").jsonObject.getValue("id").jsonPrimitive.content
        assertEquals(HttpStatusCode.Unauthorized, client.json("/api/push/subscribe", subscription(), null).status)
        assertEquals(HttpStatusCode.Created, client.json("/api/push/subscribe", subscription(), token).status)

        // Yönetici gönderir: gövde tarayıcının anahtarıyla şifreli, istek VAPID ile imzalı.
        val sent = client.json("/api/push/send", """{"user":$userId,"title":"Sipariş hazır","body":"Kapıdayız","url":"/s/1"}""", key)
        assertEquals("1", Json.parseToJsonElement(sent.bodyAsText()).jsonObject.getValue("devices").jsonPrimitive.content)
        awaitHits(1)
        val hit = hits.single()
        assertEquals("aes128gcm", hit.headers["content-encoding"])
        val message = Json.parseToJsonElement(decrypt(hit.body)).jsonObject
        assertEquals("Sipariş hazır", message.getValue("title").jsonPrimitive.content)
        assertEquals("/s/1", message.getValue("url").jsonPrimitive.content)
        val auth = hit.headers.getValue("authorization")
        assertTrue(auth.startsWith("vapid t=") && auth.endsWith(", k=${vapid.first}"), auth)
        val jwt = auth.removePrefix("vapid t=").substringBefore(",").split(".")
        val claims = Json.parseToJsonElement(String(java.util.Base64.getUrlDecoder().decode(jwt[1]))).jsonObject
        assertEquals(base, claims.getValue("aud").jsonPrimitive.content)
        assertEquals("mailto:ben@example.com", claims.getValue("sub").jsonPrimitive.content)
        val vapidPublic = Vapid.publicKey(Vapid.unb64(vapid.first), (browser.public as ECPublicKey).params)
        val valid = Signature.getInstance("SHA256withECDSAinP1363Format").run {
            initVerify(vapidPublic); update("${jwt[0]}.${jwt[1]}".toByteArray()); verify(java.util.Base64.getUrlDecoder().decode(jwt[2]))
        }
        assertTrue(valid, "itme servisi VAPID imzasını doğrular")

        // Kural: siparişin sahibine güncellemede bildirim; yer tutucular doldurulur.
        val order = Json.parseToJsonElement(client.json("/api/collections/orders/records", """{"item":"çay"}""", token).bodyAsText()).jsonObject
        val id = order.getValue("id").jsonPrimitive.content
        assertEquals(1, hits.size, "oluşturmada kural yok")
        hits.clear()
        val patched = client.request("/api/collections/orders/records/$id") {
            method = io.ktor.http.HttpMethod.Patch; bearerAuth(token); contentType(ContentType.Application.Json); setBody("""{"item":"kahve"}""")
        }
        assertEquals(HttpStatusCode.OK, patched.status)
        awaitHits(1)
        val rule = Json.parseToJsonElement(decrypt(hits.single().body)).jsonObject
        assertEquals("Sipariş kahve", rule.getValue("title").jsonPrimitive.content)
        assertEquals("/siparis/$id", rule.getValue("url").jsonPrimitive.content)

        // Script'ten.
        hits.clear()
        val fromScript = client.json("/api/bildir", "{}", token)
        assertEquals("1", Json.parseToJsonElement(fromScript.bodyAsText()).jsonObject.getValue("devices").jsonPrimitive.content, fromScript.bodyAsText())
        awaitHits(1)
        assertEquals("Merhaba", Json.parseToJsonElement(decrypt(hits.single().body)).jsonObject.getValue("title").jsonPrimitive.content)

        // Servis aboneliğin bittiğini söylerse (410) silinir.
        status = 410
        client.json("/api/push/send", """{"user":$userId,"title":"x"}""", key)
        awaitHits(2)
        Thread.sleep(200)
        assertEquals(0, push.count(userId.toLong()))
    }

    @Test
    fun `RFC 8291 test vektoru`() {
        val params = (browser.public as ECPublicKey).params
        fun pair(priv: String, pub: String) = KeyPair(
            Vapid.publicKey(Vapid.unb64(pub), params),
            java.security.KeyFactory.getInstance("EC").generatePrivate(java.security.spec.ECPrivateKeySpec(java.math.BigInteger(1, Vapid.unb64(priv)), params)),
        )
        val asKeys = pair("yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw", "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8")
        val sender = WebPushSender(vapid.first, vapid.second, "mailto:x@example.com")
        val body = sender.encrypt(
            "When I grow up, I want to be a watermelon".toByteArray(),
            Vapid.unb64("BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"),
            Vapid.unb64("BTBZMqHH6r4Tts7J_aSIgg"),
            salt = Vapid.unb64("DGv6ra1nlYgDCS1FRnbzlw"),
            ephemeral = asKeys,
        )
        assertEquals(
            "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN",
            Vapid.b64(body),
        )
    }

    @Test
    fun `ssrf korumasi, dogrulama ve sir deposu`() {
        assertTrue(PushService.knownPushService(URI("https://fcm.googleapis.com/fcm/send/abc")))
        assertTrue(PushService.knownPushService(URI("https://updates.push.services.mozilla.com/wpush/v2/x")))
        assertTrue(PushService.knownPushService(URI("https://web.push.apple.com/abc")))
        assertFalse(PushService.knownPushService(URI("https://192.168.1.1/admin")))
        assertFalse(PushService.knownPushService(URI("http://fcm.googleapis.com/x")))
        assertFalse(PushService.knownPushService(URI("https://fcm.googleapis.com.kotu.example/x")))

        assertTrue(ConfigValidator.validate(config).isEmpty(), ConfigValidator.validate(config).toString())
        assertTrue(ConfigValidator.validate(config.copy(push = config.push.copy(subject = "ben"))).any { "mailto:" in it })
        assertTrue(ConfigValidator.validate(config.copy(push = config.push.copy(publicKey = "abc"))).any { "VAPID" in it })
        assertTrue(ConfigValidator.validate(config.copy(push = config.push.copy(notify = listOf(PushRule("yok", title = "x"))))).any { "'yok'" in it })
        val noOwner = config.copy(collections = listOf(orders.copy(ownerField = null, rules = Rules())))
        assertTrue(ConfigValidator.validate(noOwner).any { "sahip alanını" in it })

        val stored = mutableMapOf<String, String>()
        val secured = ConfigSecrets.secureText(config.toJson()) { kind, value -> "${kind}_1".also { stored[it] = value } }!!
        assertTrue(vapid.second !in secured && "secret:vapid_private_1" in secured)
        assertEquals(vapid.second, ConfigSecrets.resolve(ServerConfig.fromJson(secured), stored::get).push.privateKey)
        assertTrue(SystemCollections.PUSH_SUBSCRIPTIONS in config.schemaCollections().map { it.name })
    }
}
