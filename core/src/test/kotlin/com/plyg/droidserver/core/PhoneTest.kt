package com.plyg.droidserver.core

import com.plyg.droidserver.core.api.ApiException
import com.plyg.droidserver.core.api.droidServerModule
import com.plyg.droidserver.core.audit.AuditLog
import com.plyg.droidserver.core.config.AuthConfig
import com.plyg.droidserver.core.config.AuthMode
import com.plyg.droidserver.core.config.ConfigValidator
import com.plyg.droidserver.core.config.PhoneConfig
import com.plyg.droidserver.core.config.ScriptsConfig
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.db.SchemaMigrator
import com.plyg.droidserver.core.metrics.DeviceSnapshot
import com.plyg.droidserver.core.phone.PhoneException
import com.plyg.droidserver.core.phone.PhoneHost
import com.plyg.droidserver.core.phone.PhoneLocation
import com.plyg.droidserver.core.phone.PhoneService
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
        var fail = false
        override fun sendSms(to: String, text: String) {
            if (fail) throw PhoneException("izin yok")
            sent += to to text
        }
        override fun location() = PhoneLocation(41.0, 29.0, 12f, Instant.parse("2026-10-14T10:00:00Z"), "network")
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
    }
}
