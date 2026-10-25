package com.plyg.droidserver.core

import com.plyg.droidserver.core.api.ApiException
import com.plyg.droidserver.core.api.droidServerModule
import com.plyg.droidserver.core.audit.AuditLog
import com.plyg.droidserver.core.config.AuthConfig
import com.plyg.droidserver.core.config.AuthMode
import com.plyg.droidserver.core.config.ConfigValidator
import com.plyg.droidserver.core.config.ConfigJson
import com.plyg.droidserver.core.config.ConfigSecrets
import com.plyg.droidserver.core.config.PhoneConfig
import com.plyg.droidserver.core.config.SmsProviderConfig
import com.plyg.droidserver.core.config.SmsProviderType
import com.plyg.droidserver.core.config.SmsSim
import com.plyg.droidserver.core.config.ScriptsConfig
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.db.SchemaMigrator
import com.plyg.droidserver.core.metrics.DeviceSnapshot
import com.plyg.droidserver.core.phone.PhoneException
import com.plyg.droidserver.core.phone.PhoneHost
import com.plyg.droidserver.core.phone.PhoneLocation
import com.plyg.droidserver.core.phone.PhoneService
import com.plyg.droidserver.core.phone.SmsProviders
import com.plyg.droidserver.core.script.ScriptRuntime
import com.plyg.droidserver.core.script.ScriptSource
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Telefon özellikleri: SMS sınırları ve önekleri, konum, durum; API yalnızca yönetici, script'ler. */
class PhoneTest {
    private class FakePhone : PhoneHost {
        val sent = mutableListOf<Pair<String, String>>()
        val queued = mutableListOf<String>()
        var fail = false
        override fun sendSms(to: String, text: String) {
            if (fail) throw PhoneException("izin yok")
            sent += to to text
        }
        override fun location() = PhoneLocation(41.0, 29.0, 12f, Instant.parse("2026-10-14T10:00:00Z"), "network")
        override fun queueSms(to: String, text: String, ttlSeconds: Long, replace: Boolean) {
            queued += "$to $text $ttlSeconds $replace"
        }
    }

    private val config = PhoneConfig(sms = true, smsDailyLimit = 2, smsPrefixes = listOf("+90"), location = true, status = true)

    @Test
    fun `sms sinirlari ve sayac`() {
        val dir = Files.createTempDirectory("ds-phone").toFile()
        val file = java.io.File(dir, "phone-sms.json")
        val audit = AuditLog(java.io.File(dir, "audit.jsonl"))
        var day = LocalDate.parse("2026-10-14")
        val phone = FakePhone()
        val svc = PhoneService(config, phone, { DeviceSnapshot(batteryPercent = 80) }, file, audit) { day }

        fun status(block: () -> Unit) = assertFailsWith<ApiException> { block() }.status
        assertEquals(400, status { svc.sendSms("05551234567", "kod", "admin") }, "E.164 değil")
        assertEquals(403, status { svc.sendSms("+15551234567", "kod", "admin") }, "izinli önek değil")
        assertEquals(400, status { svc.sendSms("+905551234567", "x".repeat(PhoneService.MAX_TEXT + 1), "admin") })
        assertEquals(1, svc.sendSms("+90 555 123 45 67", "Kodunuz 1234", "admin").getValue("today").jsonPrimitive.content.toInt())
        svc.sendSms("+905551234568", "ikinci", "script")
        assertEquals(429, status { svc.sendSms("+905551234569", "üçüncü", "admin") })
        assertEquals(listOf("+905551234567", "+905551234568"), phone.sent.map { it.first })

        // Sayaç dosyada: yeniden kurulan servis de sınırı bilir; ertesi gün sıfırlanır.
        val again = PhoneService(config, phone, null, file, audit) { day }
        assertEquals(429, status { again.sendSms("+905551234569", "x", "admin") })
        day = day.plusDays(1)
        assertEquals("1", again.sendSms("+905551234569", "x", "admin").getValue("today").jsonPrimitive.content)

        // Denetim kaydında numara maskeli, metin yok.
        val entries = audit.list(limit = 10).map { it.target + " " + it.detail }
        assertTrue(entries.all { "****" in it && "Kodunuz" !in it }, entries.toString())

        phone.fail = true
        assertEquals(503, status { again.sendSms("+905551234500", "x", "admin") })
        assertEquals(404, status { PhoneService(PhoneConfig(), phone).sendSms("+905551234567", "x", "admin") })
        assertEquals(404, status { PhoneService(PhoneConfig(), phone).location() })
        assertEquals("41.0", svc.location().getValue("latitude").jsonPrimitive.content)
        assertEquals("80", svc.status().getValue("batteryPercent").jsonPrimitive.content)
    }

    @Test
    fun `sms yollari - saglayici, sim, onayli kuyruk`() {
        val provider = SmsProviderConfig(SmsProviderType.TWILIO, "AC1", "token", "+15550001111")
        val calls = mutableListOf<String>()
        var providerFails = false
        fun svc(p: PhoneConfig, phone: PhoneHost? = FakePhone()) = PhoneService(p, phone, provider = { _, to, _ ->
            calls += to
            if (providerFails) throw PhoneException("Twilio hatası (500): x")
        })
        fun via(r: kotlinx.serialization.json.JsonObject) = r.getValue("via").jsonPrimitive.content

        // Sağlayıcı varsa önce o; SIM'e dokunulmaz, telefon olmadan da çalışır.
        val phone = FakePhone()
        assertEquals("provider", via(svc(config.copy(smsProvider = provider), phone).sendSms("+905551234567", "kod", "admin")))
        assertEquals(listOf("+905551234567"), calls)
        assertTrue(phone.sent.isEmpty())
        assertEquals("provider", via(svc(config.copy(smsProvider = provider), null).sendSms("+905551234567", "kod", "admin")))

        // Sağlayıcı başarısızsa SIM (doğrudan); SIM kapalıysa hata.
        providerFails = true
        assertEquals("sim", via(svc(config.copy(smsProvider = provider), phone).sendSms("+905551234567", "kod", "admin")))
        assertEquals(1, phone.sent.size)
        val off = assertFailsWith<ApiException> { svc(config.copy(smsProvider = provider, smsSim = SmsSim.OFF)).sendSms("+905551234567", "kod", "admin") }
        assertEquals(503, off.status)
        assertTrue("Twilio" in off.message.orEmpty(), off.message)

        // Onaylı SIM: kuyruğa girer, hemen döner; sınırdan düşer.
        val confirm = config.copy(smsSim = SmsSim.CONFIRM)
        val queued = FakePhone()
        val s = svc(confirm, queued)
        val r = s.sendSms("+905551234567", "Kod: 1", "auth.sms", ttlSeconds = 600, replace = true)
        assertEquals("awaiting_user", r.getValue("status").jsonPrimitive.content)
        assertEquals("false", r.getValue("sent").jsonPrimitive.content)
        assertEquals(listOf("+905551234567 Kod: 1 600 true"), queued.queued)
        s.sendSms("+905551234568", "x", "script")
        assertEquals("+905551234568 x ${PhoneService.QUEUE_TTL_SECONDS} false", queued.queued.last())
        assertEquals(429, assertFailsWith<ApiException> { s.sendSms("+905551234569", "y", "script") }.status)
    }

    @Test
    fun `sms saglayicilari - istek bicimi ve hata`() {
        val requests = mutableListOf<Triple<String, String?, String>>()
        var reply = 200 to ""
        val srv = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/") { ex ->
            requests += Triple(ex.requestURI.path, ex.requestHeaders.getFirst("Authorization"), ex.requestBody.readBytes().decodeToString())
            val body = reply.second.toByteArray()
            ex.sendResponseHeaders(reply.first, body.size.toLong()); ex.responseBody.use { it.write(body) }
        }
        srv.start()
        val base = "http://127.0.0.1:${srv.address.port}"
        fun basic(u: String, p: String) = "Basic " + java.util.Base64.getEncoder().encodeToString("$u:$p".toByteArray())
        try {
            reply = 200 to """{"code":"00","jobid":"123"}"""
            SmsProviders.send(SmsProviderConfig(SmsProviderType.NETGSM, "8501234567", "parola", "FIRMA", base), "+905551234567", "Kod: 1")
            val (path, auth, body) = requests.last()
            assertEquals("/sms/rest/v2/send", path)
            assertEquals(basic("8501234567", "parola"), auth)
            val json = Json.parseToJsonElement(body).jsonObject
            assertEquals("FIRMA", json.getValue("msgheader").jsonPrimitive.content)
            assertTrue("\"no\":\"905551234567\"" in body, body)

            reply = 200 to """{"code":"30","description":"Geçersiz kullanıcı"}"""
            val e = assertFailsWith<PhoneException> { SmsProviders.send(SmsProviderConfig(SmsProviderType.NETGSM, "u", "p", "F", base), "+905551234567", "x") }
            assertTrue("Netgsm" in e.message.orEmpty() && "30" in e.message.orEmpty(), e.message)

            reply = 200 to """{"response":{"status":{"code":200,"message":"İşlem başarılı"},"order":{"id":"1"}}}"""
            SmsProviders.send(SmsProviderConfig(SmsProviderType.ILETIMERKEZI, "anahtar", "hash", "BASLIK", base), "+905551234567", "Kod: 2")
            val im = requests.last()
            assertEquals("/v1/send-sms/json", im.first)
            val order = Json.parseToJsonElement(im.third).jsonObject.getValue("request").jsonObject
            assertEquals("hash", order.getValue("authentication").jsonObject.getValue("hash").jsonPrimitive.content)
            assertTrue("\"receipents\":{\"number\":[\"905551234567\"]}" in im.third, im.third)
            assertTrue("\"iys\":\"0\"" in im.third, im.third)

            reply = 201 to """{"sid":"SM1"}"""
            SmsProviders.send(SmsProviderConfig(SmsProviderType.TWILIO, "AC123", "tok", "MG456", base), "+905551234567", "Kod: 3 & ğ")
            val tw = requests.last()
            assertEquals("/2010-04-01/Accounts/AC123/Messages.json", tw.first)
            assertEquals(basic("AC123", "tok"), tw.second)
            assertTrue("MessagingServiceSid=MG456" in tw.third && "To=%2B905551234567" in tw.third, tw.third)

            reply = 400 to """{"code":21211,"message":"Invalid 'To' Phone Number"}"""
            val t = assertFailsWith<PhoneException> { SmsProviders.send(SmsProviderConfig(SmsProviderType.TWILIO, "AC1", "t", "+1555", base), "+905551234567", "x") }
            assertTrue("Invalid 'To'" in t.message.orEmpty(), t.message)
        } finally {
            srv.stop(0)
        }
    }

    @Test
    fun `saglayici sirri sifreli depoya tasinir ve cozulur`() {
        val json = ConfigJson.parseToJsonElement(
            """{"version":23,"phone":{"sms":true,"smsProvider":{"type":"netgsm","user":"u","secret":"cok-gizli","sender":"F"}}}""",
        ).jsonObject
        val stored = mutableMapOf<String, String>()
        val out = ConfigSecrets.externalize(json) { kind, v -> "$kind" + "_1".also { stored["${kind}_1"] = v } }
        val ref = out.getValue("phone").jsonObject.getValue("smsProvider").jsonObject.getValue("secret").jsonPrimitive.content
        assertEquals("secret:sms_secret_1", ref)
        val cfg = ConfigJson.decodeFromJsonElement(ServerConfig.serializer(), out)
        assertEquals("cok-gizli", ConfigSecrets.resolve(cfg) { stored[it] }.phone.smsProvider?.secret)
    }

    @Test
    fun `api yalnizca yonetici ve script`() = testApplication {
        val key = "yonetici-anahtari-1234567890"
        val cfg = ServerConfig(auth = AuthConfig(AuthMode.API_KEY, apiKeys = listOf(key)), phone = config, scripts = ScriptsConfig(enabled = true))
        val db = TestDb.create(Dialect.SQLITE).open()
        SchemaMigrator(db).migrate(cfg.schemaCollections())
        val phone = FakePhone()
        val scripts = ScriptRuntime.load(
            listOf(ScriptSource("main.js", """routerAdd("POST", "/otp", (req, ctx) => ctx.sms.send(req.body.to, "Kod: 4321"), "admin")""")),
            cfg.scripts, emptySet(),
        )
        application { droidServerModule(cfg, db, scripts = scripts, phone = PhoneService(cfg.phone, phone)) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/_phone/location").status)
        val loc = client.get("/api/_phone/location") { bearerAuth(key) }
        assertEquals("29.0", Json.parseToJsonElement(loc.bodyAsText()).jsonObject.getValue("longitude").jsonPrimitive.content)
        val sms = client.post("/api/_phone/sms") { bearerAuth(key); contentType(ContentType.Application.Json); setBody("""{"to":"+905550000001","text":"merhaba"}""") }
        assertEquals(HttpStatusCode.OK, sms.status, sms.bodyAsText())
        val viaScript = client.post("/api/otp") { bearerAuth(key); contentType(ContentType.Application.Json); setBody("""{"to":"+905550000002"}""") }
        assertEquals(HttpStatusCode.OK, viaScript.status, viaScript.bodyAsText())
        assertEquals(listOf("+905550000001" to "merhaba", "+905550000002" to "Kod: 4321"), phone.sent)
        val limited = client.post("/api/otp") { bearerAuth(key); contentType(ContentType.Application.Json); setBody("""{"to":"+905550000003"}""") }
        assertEquals(HttpStatusCode.TooManyRequests, limited.status, "günlük sınır script'te de geçerli")
        db.close()

        val errors = { p: PhoneConfig -> ConfigValidator.validateDetailed(cfg.copy(phone = p)).map { it.path }.toSet() }
        assertEquals(emptySet(), errors(config))
        assertEquals(setOf("phone.smsDailyLimit"), errors(config.copy(smsDailyLimit = 0)))
        assertEquals(setOf("phone.smsPrefixes"), errors(config.copy(smsPrefixes = emptyList())))
        assertEquals(setOf("phone.smsPrefixes"), errors(config.copy(smsPrefixes = listOf("90"))))
        assertEquals(setOf("phone.smsSim"), errors(config.copy(smsSim = SmsSim.OFF)))
        assertEquals(
            setOf("phone.smsProvider.user", "phone.smsProvider.secret", "phone.smsProvider.sender"),
            errors(config.copy(smsProvider = SmsProviderConfig(SmsProviderType.NETGSM, "", "", ""))),
        )
    }
}
