package dev.droidserver.core

import dev.droidserver.core.admin.AdminSessions
import dev.droidserver.core.api.droidServerModule
import dev.droidserver.core.auth.Totp
import dev.droidserver.core.config.AdminPanelConfig
import dev.droidserver.core.config.AuthConfig
import dev.droidserver.core.config.AuthMode
import dev.droidserver.core.config.CollectionConfig
import dev.droidserver.core.config.ConfigValidator
import dev.droidserver.core.config.EmailConfig
import dev.droidserver.core.config.FieldConfig
import dev.droidserver.core.config.FieldType
import dev.droidserver.core.config.Rule
import dev.droidserver.core.config.Rules
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.config.TwoFactorConfig
import dev.droidserver.core.config.UsersConfig
import dev.droidserver.core.db.SchemaMigrator
import dev.droidserver.core.db.SqlDriver
import dev.droidserver.core.mail.MailSender
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.time.Instant
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Faz 12.4: iki adımlı doğrulama (TOTP, yedek kodlar), e-postayla kodla giriş ve panel doğrulayıcı kodu. */
@RunWith(Parameterized::class)
class TwoFactorTest(private val dialect: Dialect) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun dialects() = Dialect.entries.toList()
    }

    private val key = "yonetici-anahtari-1234567890"
    private val notes = CollectionConfig("notes", listOf(FieldConfig("text", FieldType.TEXT)), rules = Rules(Rule.AUTH, Rule.AUTH, Rule.AUTH, Rule.AUTH, Rule.AUTH))
    private fun config(required: Boolean = false) = ServerConfig(
        name = "Evim",
        auth = AuthConfig(
            AuthMode.API_KEY, apiKeys = listOf(key),
            users = UsersConfig(enabled = true, sendVerification = false, twoFactor = TwoFactorConfig(enabled = true, required = required), emailLogin = true, emailLoginUrl = "https://uygulama.test/giris"),
        ),
        email = EmailConfig(enabled = true, host = "smtp.example.com", from = "evim@example.com"),
        collections = listOf(notes),
    )
    private val outbox: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
    private val sender = MailSender { to, _, text, _ -> outbox += to to text }
    private lateinit var db: SqlDriver

    @Before
    fun open() {
        db = TestDb.create(dialect).open()
        SchemaMigrator(db).migrate(config().schemaCollections())
    }

    @After
    fun close() = db.close()

    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject
    private fun JsonObject.str(k: String) = getValue(k).jsonPrimitive.content
    private suspend fun HttpClient.send(path: String, body: String, token: String? = null): HttpResponse = post(path) {
        token?.let { bearerAuth(it) }; contentType(ContentType.Application.Json); setBody(body)
    }
    private suspend fun HttpClient.ok(path: String, body: String, token: String? = null, status: HttpStatusCode = HttpStatusCode.OK): JsonObject {
        val r = send(path, body, token)
        assertEquals(status, r.status, "$path: ${r.bodyAsText()}")
        return if (status == HttpStatusCode.NoContent) JsonObject(emptyMap()) else json(r.bodyAsText())
    }
    private fun now(offset: Long = 0) = Totp.code(secret, Totp.step(Instant.now()) + offset)
    private var secret = ""

    @Test
    fun `dogrulayici kodu, yedek kodlar ve e-postayla giris`() = testApplication {
        application { droidServerModule(config(), db, mailSender = sender) }
        assertTrue(ConfigValidator.validate(config()).isEmpty(), ConfigValidator.validate(config()).toString())
        val token = client.ok("/api/auth/signup", """{"email":"ali@example.com","password":"parola-1234"}""", status = HttpStatusCode.Created).str("token")
        assertEquals("false", json(client.get("/api/auth/2fa") { bearerAuth(token) }.bodyAsText()).str("enabled"))

        // Kurulum: anahtar, yanlış kod reddedilir, doğru kodla açılır; yedek kodlar bir kez.
        val setup = client.ok("/api/auth/2fa/setup", "{}", token)
        secret = setup.str("secret")
        assertTrue(setup.str("uri").startsWith("otpauth://totp/Evim:ali%40example.com?secret=$secret"), setup.str("uri"))
        assertEquals(HttpStatusCode.BadRequest, client.send("/api/auth/2fa/enable", """{"code":"000000"}""", token).status)
        val codes = client.ok("/api/auth/2fa/enable", """{"code":"${now()}"}""", token).getValue("recoveryCodes").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(10, codes.size)
        assertTrue(codes.all { Regex("^[A-Z2-9]{5}-[A-Z2-9]{5}$").matches(it) }, codes.toString())
        assertEquals(HttpStatusCode.Conflict, client.send("/api/auth/2fa/setup", "{}", token).status)

        // Giriş artık iki adımlı: parola doğruysa challenge; kod veya yedek kodla token.
        fun login() = """{"email":"ali@example.com","password":"parola-1234"}"""
        var step1 = client.ok("/api/auth/login", login())
        assertEquals("true", step1.str("twoFactorRequired"))
        assertTrue("token" !in step1)
        assertEquals(HttpStatusCode.Unauthorized, client.send("/api/auth/2fa/verify", """{"challenge":"${step1.str("challenge")}","code":"123456"}""").status)
        assertEquals(HttpStatusCode.Unauthorized, client.send("/api/auth/2fa/verify", """{"challenge":"bozuk","code":"${now(1)}"}""").status)
        val tokens = client.ok("/api/auth/2fa/verify", """{"challenge":"${step1.str("challenge")}","code":"${now(1)}"}""")
        assertEquals("ali@example.com", tokens.getValue("user").jsonObject.str("email"))
        step1 = client.ok("/api/auth/login", login())
        assertEquals(HttpStatusCode.Unauthorized, client.send("/api/auth/2fa/verify", """{"challenge":"${step1.str("challenge")}","code":"${now(1)}"}""").status, "aynı kod ikinci kez geçmez")
        client.ok("/api/auth/2fa/verify", """{"challenge":"${step1.str("challenge")}","code":"${codes[0].lowercase()}"}""")
        assertEquals(HttpStatusCode.Unauthorized, client.send("/api/auth/2fa/verify", """{"challenge":"${step1.str("challenge")}","code":"${codes[0]}"}""").status, "yedek kod tek kullanımlık")
        assertEquals("9", json(client.get("/api/auth/2fa") { bearerAuth(token) }.bodyAsText()).str("recoveryCodesLeft"))

        // E-postayla kodla giriş: kod e-postada (ve istemci bağlantısında); 2FA açık olduğu için yine ikinci adım.
        assertEquals(HttpStatusCode.Accepted, client.send("/api/auth/request-login-code", """{"email":"ali@example.com"}""").status)
        assertEquals(HttpStatusCode.Accepted, client.send("/api/auth/request-login-code", """{"email":"yok@example.com"}""").status)
        val until = System.currentTimeMillis() + 5_000
        while (outbox.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(20)
        val (to, text) = outbox.single()
        assertEquals("ali@example.com", to)
        val code = Regex("giriş kodunuz: (\\d{8})").find(text)!!.groupValues[1]
        assertTrue("https://uygulama.test/giris?email=ali%40example.com&code=$code" in text, text)
        assertEquals(HttpStatusCode.Unauthorized, client.send("/api/auth/login-code", """{"email":"ali@example.com","code":"00000000"}""").status)
        val byCode = client.ok("/api/auth/login-code", """{"email":"ali@example.com","code":"$code"}""")
        assertEquals("true", byCode.str("twoFactorRequired"))
        assertEquals(HttpStatusCode.Unauthorized, client.send("/api/auth/login-code", """{"email":"ali@example.com","code":"$code"}""").status, "kod tek kullanımlık")

        // Yedek kodları yenileme ve kapatma (yedek kodla); yönetici de sıfırlayabilir.
        val fresh = client.ok("/api/auth/2fa/recovery-codes", """{"code":"${codes[1]}"}""", token).getValue("recoveryCodes").jsonArray
        assertEquals(10, fresh.size)
        assertEquals(HttpStatusCode.BadRequest, client.send("/api/auth/2fa/disable", """{"code":"${codes[2]}"}""", token).status, "eski yedek kodlar geçersiz")
        client.ok("/api/auth/2fa/disable", """{"code":"${fresh[0].jsonPrimitive.content}"}""", token, HttpStatusCode.NoContent)
        assertTrue("token" in client.ok("/api/auth/login", login()))

        client.ok("/api/auth/2fa/setup", "{}", token).also { secret = it.str("secret") }
        client.ok("/api/auth/2fa/enable", """{"code":"${now()}"}""", token)
        val users = json(client.get("/api/auth/users") { bearerAuth(key) }.bodyAsText()).getValue("items").jsonArray
        val ali = users.single().jsonObject
        assertEquals("true", ali.str("twoFactor"))
        val reset = client.patch("/api/auth/users/${ali.str("id")}") { bearerAuth(key); contentType(ContentType.Application.Json); setBody("""{"twoFactor":false}""") }
        assertEquals(HttpStatusCode.OK, reset.status, reset.bodyAsText())
        assertTrue("token" in client.ok("/api/auth/login", login()))
    }

    @Test
    fun `zorunlu modda kurmayan kullanici yalnizca hesap uclarini kullanir`() = testApplication {
        application { droidServerModule(config(required = true), db, mailSender = sender) }
        val signup = client.ok("/api/auth/signup", """{"email":"ayse@example.com","password":"parola-1234"}""", status = HttpStatusCode.Created)
        assertEquals("true", signup.str("twoFactorSetupRequired"))
        val token = signup.str("token")
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/collections/notes/records") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/auth/me") { bearerAuth(token) }.status)
        secret = client.ok("/api/auth/2fa/setup", "{}", token).str("secret")
        val codes = client.ok("/api/auth/2fa/enable", """{"code":"${now()}"}""", token).getValue("recoveryCodes").jsonArray
        assertEquals(HttpStatusCode.OK, client.get("/api/collections/notes/records") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.send("/api/auth/2fa/disable", """{"code":"${codes[0].jsonPrimitive.content}"}""", token).status)
    }

    @Test
    fun `panel girisi dogrulayici kodu ister, ayar dogrulamasi`() = testApplication {
        val panelSecret = Totp.newSecret()
        val sessions = AdminSessions()
        application { droidServerModule(config().copy(adminPanel = AdminPanelConfig(enabled = true, totpSecret = panelSecret)), db, adminSessions = sessions) }
        assertEquals("true", json(client.get("/_admin/api/login").bodyAsText()).str("totp"))
        var code = sessions.newCode().code
        assertEquals(HttpStatusCode.Unauthorized, client.send("/_admin/api/pair", """{"code":"$code","totp":"000000"}""").status)
        code = sessions.newCode().code
        assertEquals(HttpStatusCode.OK, client.send("/_admin/api/pair", """{"code":"$code","totp":"${Totp.code(panelSecret, Totp.step(Instant.now()))}"}""").status)

        // RFC 6238 ek B test vektörü (SHA-1, 8 hanenin son 6'sı).
        val rfc = Totp.base32("12345678901234567890".toByteArray())
        assertEquals("287082", Totp.code(rfc, 59 / 30))
        assertEquals("005924", Totp.code(rfc, 1234567890L / 30))

        fun errors(c: ServerConfig) = ConfigValidator.validate(c)
        val users = config().auth.users
        assertTrue(errors(config().copy(email = EmailConfig())).any { "E-postayla giriş" in it })
        assertTrue(errors(config().copy(auth = config().auth.copy(users = users.copy(twoFactor = TwoFactorConfig(required = true))))).any { "Zorunlu" in it })
        assertTrue(errors(config().copy(adminPanel = AdminPanelConfig(enabled = true, totpSecret = "kısa"))).any { "Base32" in it })
        assertEquals(config(), ServerConfig.fromJson(config().toJson()))
    }
}
