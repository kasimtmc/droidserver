package dev.droidserver.core

import dev.droidserver.core.api.droidServerModule
import dev.droidserver.core.auth.SmsGuard
import dev.droidserver.core.config.AuthConfig
import dev.droidserver.core.config.AuthMode
import dev.droidserver.core.config.ConfigValidator
import dev.droidserver.core.config.FieldConfig
import dev.droidserver.core.config.FieldType
import dev.droidserver.core.config.PhoneConfig
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.config.UsersConfig
import dev.droidserver.core.db.SchemaMigrator
import dev.droidserver.core.db.SqlDriver
import dev.droidserver.core.phone.PhoneHost
import dev.droidserver.core.phone.PhoneLocation
import dev.droidserver.core.phone.PhoneService
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SMS ile giriş: numara biçimi, kod gönderme ve giriş, sınırlar, kapalıyken uç yok, doğrulama. */
class SmsLoginTest {
    private class FakePhone : PhoneHost {
        val sent = CopyOnWriteArrayList<Pair<String, String>>()
        override fun sendSms(to: String, text: String) { sent += to to text }
        override fun location(): PhoneLocation? = null
    }

    private val users = UsersConfig(
        enabled = true,
        extraFields = listOf(FieldConfig("phone", FieldType.TEXT, unique = true)),
        smsLogin = true,
    )
    private val config = ServerConfig(
        name = "Dükkan",
        auth = AuthConfig(AuthMode.API_KEY, apiKeys = listOf("yonetici-anahtari-1234567890"), users = users),
        phone = PhoneConfig(sms = true, smsDailyLimit = 20, smsPrefixes = listOf("+90")),
    )
    private lateinit var db: SqlDriver

    @BeforeTest
    fun open() {
        db = TestDb.create(Dialect.SQLITE).open()
        SchemaMigrator(db).migrate(config.schemaCollections())
    }

    @AfterTest
    fun close() = db.close()

    private suspend fun HttpClient.send(path: String, body: String): HttpResponse =
        post(path) { contentType(ContentType.Application.Json); setBody(body) }

    private suspend fun HttpResponse.obj() = Json.parseToJsonElement(bodyAsText()).jsonObject

    @Test
    fun `dogrulama`() {
        assertEquals(emptyList(), ConfigValidator.validate(config))
        fun errors(c: ServerConfig) = ConfigValidator.validate(c).joinToString("\n")
        assertTrue("SMS gönderme" in errors(config.copy(phone = PhoneConfig())))
        assertTrue("telefon alanı ekleyin" in errors(config.copy(auth = config.auth.copy(users = users.copy(extraFields = emptyList())))))
        assertTrue("benzersiz" in errors(config.copy(auth = config.auth.copy(users = users.copy(extraFields = listOf(FieldConfig("phone", FieldType.TEXT)))))))
        assertTrue("metin" in errors(config.copy(auth = config.auth.copy(users = users.copy(extraFields = listOf(FieldConfig("phone", FieldType.INTEGER, unique = true)))))))
        assertEquals("+905551234567", SmsGuard.normalize("+90 (555) 123-45-67"))
        assertNull(SmsGuard.normalize("05551234567"))
    }

    @Test
    fun `kod gonderilir ve giris yapilir`() = testApplication {
        val fake = FakePhone()
        val guard = SmsGuard(perNumberSeconds = 60, perNumberDaily = 5, perClientHourly = 4)
        application { droidServerModule(config, db, phone = PhoneService(config.phone, fake), smsGuard = guard) }

        // Kayıtta numara uluslararası biçime getirilir; geçersiz biçim reddedilir.
        val bad = client.send("/api/auth/signup", """{"email":"a@example.com","password":"parola-123","phone":"0555 123 45 67"}""")
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        assertTrue("phone" in bad.obj().getValue("details").jsonObject)
        val ok = client.send("/api/auth/signup", """{"email":"a@example.com","password":"parola-123","phone":"+90 555 123 45 67"}""")
        assertEquals(HttpStatusCode.Created, ok.status, ok.bodyAsText())
        assertEquals("+905551234567", ok.obj().getValue("user").jsonObject.getValue("phone").jsonPrimitive.content)

        // Kod iste: yanıt hemen döner, SMS arka planda gider.
        val req = client.send("/api/auth/request-sms-code", """{"phone":"+905551234567"}""")
        assertEquals(HttpStatusCode.Accepted, req.status)
        repeat(50) { if (fake.sent.isEmpty()) delay(20) }
        val (to, text) = fake.sent.single()
        assertEquals("+905551234567", to)
        assertTrue(text.startsWith("Dükkan giriş kodunuz: "), text)
        val code = Regex("\\b(\\d{6})\\b").find(text)!!.groupValues[1]

        assertEquals(HttpStatusCode.Unauthorized, client.send("/api/auth/sms-code", """{"phone":"+905551234567","code":"000000"}""").status)
        val login = client.send("/api/auth/sms-code", """{"phone":"+90 555 123 45 67","code":"$code"}""")
        assertEquals(HttpStatusCode.OK, login.status, login.bodyAsText())
        assertTrue(login.obj().getValue("token").jsonPrimitive.content.isNotEmpty())
        // Kod tek kullanımlık.
        assertEquals(HttpStatusCode.Unauthorized, client.send("/api/auth/sms-code", """{"phone":"+905551234567","code":"$code"}""").status)

        // Aynı numaraya 60 saniye içinde ikinci kod yok.
        assertEquals(HttpStatusCode.TooManyRequests, client.send("/api/auth/request-sms-code", """{"phone":"+905551234567"}""").status)
        // Kayıtlı olmayan numara: aynı yanıt, SMS gitmez.
        assertEquals(HttpStatusCode.Accepted, client.send("/api/auth/request-sms-code", """{"phone":"+905559999999"}""").status)
        delay(200)
        assertEquals(1, fake.sent.size)
        // İzinli olmayan ülke kodu ve bozuk biçim.
        assertEquals(HttpStatusCode.BadRequest, client.send("/api/auth/request-sms-code", """{"phone":"+15551234567"}""").status)
        assertEquals(HttpStatusCode.BadRequest, client.send("/api/auth/request-sms-code", """{"phone":"5551234567"}""").status)
        // İstemci başına saatlik sınır (4): iki başarılı istek daha, sonra 429.
        assertEquals(HttpStatusCode.Accepted, client.send("/api/auth/request-sms-code", """{"phone":"+905550000001"}""").status)
        assertEquals(HttpStatusCode.Accepted, client.send("/api/auth/request-sms-code", """{"phone":"+905550000002"}""").status)
        assertEquals(HttpStatusCode.TooManyRequests, client.send("/api/auth/request-sms-code", """{"phone":"+905550000003"}""").status)
    }

    @Test
    fun `sinir sayaci`() {
        var now = Instant.parse("2026-10-15T10:00:00Z")
        val g = SmsGuard(perNumberSeconds = 60, perNumberDaily = 2, perClientHourly = 100, clock = { now })
        g.check("+905551112233", "a")
        now = now.plusSeconds(61)
        g.check("+905551112233", "a")
        now = now.plusSeconds(61)
        val e = runCatching { g.check("+905551112233", "a") }.exceptionOrNull()
        assertTrue(e?.message.orEmpty().contains("bugünkü"), e?.message)
        now = now.plusSeconds(86_400)
        g.check("+905551112233", "a")
    }

    @Test
    fun `kapaliyken uc yok`() = testApplication {
        val off = config.copy(auth = config.auth.copy(users = users.copy(smsLogin = false)))
        application { droidServerModule(off, db, phone = PhoneService(off.phone, FakePhone())) }
        assertEquals(HttpStatusCode.NotFound, client.send("/api/auth/request-sms-code", """{"phone":"+905551234567"}""").status)
    }
}
