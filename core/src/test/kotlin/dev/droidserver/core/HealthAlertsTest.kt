package dev.droidserver.core

import dev.droidserver.core.alerts.Alert
import dev.droidserver.core.alerts.AlertChannel
import dev.droidserver.core.alerts.AlertChannels
import dev.droidserver.core.alerts.AlertKind
import dev.droidserver.core.alerts.EmailChannel
import dev.droidserver.core.alerts.HealthInput
import dev.droidserver.core.alerts.HealthMonitor
import dev.droidserver.core.alerts.TelegramChannel
import dev.droidserver.core.api.droidServerModule
import dev.droidserver.core.config.AlertsConfig
import dev.droidserver.core.config.AuthConfig
import dev.droidserver.core.config.AuthMode
import dev.droidserver.core.config.ConfigJson
import dev.droidserver.core.config.ConfigSecrets
import dev.droidserver.core.config.ConfigValidator
import dev.droidserver.core.config.EmailConfig
import dev.droidserver.core.config.QuietHours
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.config.TelegramConfig
import dev.droidserver.core.db.SqlDriver
import dev.droidserver.core.mail.MailSender
import dev.droidserver.core.metrics.DeviceSnapshot
import dev.droidserver.core.metrics.Metrics
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.request.uri
import io.ktor.server.response.respondText
import io.ktor.server.testing.testApplication
import io.ktor.http.ContentType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.URLDecoder
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Faz 13.3: sağlık uyarıları. */
class HealthAlertsTest {
    private val t0 = Instant.parse("2026-10-14T12:00:00Z")
    private val mb = 1024L * 1024

    private class Recorder : AlertChannel {
        val got = mutableListOf<Alert>()
        override fun send(alert: Alert) { got += alert }
    }

    private fun monitor(cfg: AlertsConfig = AlertsConfig(), now: () -> Instant, vararg channels: Pair<String, AlertChannel>) =
        HealthMonitor(clock = now, zone = { ZoneOffset.UTC }).also { it.configure(cfg, channels.toMap(), "Evim") }

    @Test
    fun `kosullar`() {
        val cfg = AlertsConfig()
        fun kinds(input: HealthInput) = HealthMonitor.conditions(cfg, input, t0).map { it.kind }
        assertEquals(emptyList(), kinds(HealthInput(DeviceSnapshot(freeStorageBytes = 2000 * mb, batteryTempC = 30.0))))
        assertEquals(listOf(AlertKind.DISK), kinds(HealthInput(DeviceSnapshot(freeStorageBytes = 300 * mb))))
        assertEquals(listOf(AlertKind.DISK_FULL), kinds(HealthInput(DeviceSnapshot(freeStorageBytes = 50 * mb))))
        assertEquals(listOf(AlertKind.HEAT), kinds(HealthInput(DeviceSnapshot(batteryTempC = 46.5))))

        val start = t0.toEpochMilli() - t0.toEpochMilli() % 60_000
        fun minute(back: Int, requests: Long, errors: Long) = Metrics.Minute(start - back * 60_000L, requests, errors, null, null)
        assertEquals(listOf(AlertKind.ERRORS), kinds(HealthInput(minutes = listOf(minute(1, 30, 4), minute(0, 10, 4)))), "8/40 = %20")
        assertEquals(emptyList(), kinds(HealthInput(minutes = listOf(minute(0, 10, 10)))), "az istek: oran anlamsız")
        assertEquals(emptyList(), kinds(HealthInput(minutes = listOf(minute(9, 100, 100), minute(0, 20, 1)))), "5 dakikadan eski sayılmaz")

        assertEquals(listOf(AlertKind.CERTIFICATE), kinds(HealthInput(certificateUntil = t0 + Duration.ofDays(5))))
        assertEquals(emptyList(), kinds(HealthInput(certificateUntil = t0 + Duration.ofDays(40))))
        val both = HealthMonitor.conditions(cfg, HealthInput(serverFailure = "port kullanımda", failedComponents = listOf("postgres")), t0)
        assertEquals(listOf(AlertKind.SERVER, AlertKind.COMPONENT), both.map { it.kind })
        assertTrue(both.all { it.critical } && "postgres" == both[1].subject)
    }

    @Test
    fun `tekrar etmez, hatirlatir, duzelince bildirir`() {
        var now = t0
        val rec = Recorder()
        val m = monitor(AlertsConfig(repeatHours = 6), { now }, "kayıt" to rec)
        val hot = HealthInput(DeviceSnapshot(batteryTempC = 47.0))
        assertEquals(1, m.check(hot).size)
        now += Duration.ofMinutes(30)
        assertEquals(0, m.check(hot).size, "aynı uyarı tekrar etmez")
        assertEquals(1, m.active().size)
        now += Duration.ofHours(6)
        assertEquals(1, m.check(hot).size, "hatırlatma")
        now += Duration.ofMinutes(1)
        val ok = m.check(HealthInput(DeviceSnapshot(batteryTempC = 35.0))).single()
        assertTrue(ok.resolved && "düzeldi" in ok.title)
        assertEquals(0, m.active().size)
        assertEquals(3, rec.got.size)
        assertEquals(listOf(true, false, false), m.recent().map { it.resolved }, "en yeni önce")

        // Disk azaldı -> doldu: ağırlaşma beklemeden; doldu -> düzeldi.
        assertEquals(AlertKind.DISK, m.check(HealthInput(DeviceSnapshot(freeStorageBytes = 300 * mb))).single().kind)
        now += Duration.ofMinutes(1)
        assertEquals(AlertKind.DISK_FULL, m.check(HealthInput(DeviceSnapshot(freeStorageBytes = 20 * mb))).single().kind)
        now += Duration.ofMinutes(1)
        assertTrue(m.check(HealthInput(DeviceSnapshot(freeStorageBytes = 900 * mb))).single().resolved)
    }

    @Test
    fun `sessiz saatler`() {
        assertTrue(HealthMonitor.inQuiet(QuietHours("23:00", "07:00"), LocalTime.of(2, 0)))
        assertTrue(HealthMonitor.inQuiet(QuietHours("23:00", "07:00"), LocalTime.of(23, 0)))
        assertFalse(HealthMonitor.inQuiet(QuietHours("23:00", "07:00"), LocalTime.of(7, 0)))
        assertTrue(HealthMonitor.inQuiet(QuietHours("13:00", "14:00"), LocalTime.of(13, 30)))
        assertFalse(HealthMonitor.inQuiet(QuietHours("13:00", "13:00"), LocalTime.of(13, 0)))

        var now = Instant.parse("2026-10-14T23:30:00Z")
        val rec = Recorder()
        val m = monitor(AlertsConfig(quietHours = QuietHours("23:00", "07:00"), repeatHours = 12), { now }, "kayıt" to rec)
        val hot = HealthInput(DeviceSnapshot(batteryTempC = 50.0))
        assertEquals(0, m.check(hot).size, "kritik olmayan bekler")
        assertNull(m.event(AlertKind.BACKUP, "yüklenemedi"), "kritik olmayan olay sessizde gitmez")
        assertEquals(AlertKind.SERVER, m.check(HealthInput(DeviceSnapshot(batteryTempC = 50.0), serverFailure = "x")).single().kind, "kritik gider")
        now = Instant.parse("2026-10-15T07:00:30Z")
        assertEquals(listOf(AlertKind.HEAT), m.check(HealthInput(DeviceSnapshot(batteryTempC = 50.0), serverFailure = "x")).map { it.kind }, "sessiz saat bitince")
    }

    @Test
    fun `olaylar ve kanallar`() {
        var now = t0
        val rec = Recorder()
        val broken = AlertChannel { throw IOException("SMTP kapalı") }
        val logs = mutableListOf<String>()
        val m = HealthMonitor(clock = { now }, log = { logs += it }).also { it.configure(AlertsConfig(repeatHours = 2), mapOf("bozuk" to broken, "kayıt" to rec), "Evim") }
        assertNotNull(m.event(AlertKind.BACKUP, "buluta yüklenemedi", "remote"), "bir kanal başarısız olsa da diğeri")
        assertTrue(logs.any { "bozuk" in it && "SMTP kapalı" in it })
        assertNull(m.event(AlertKind.BACKUP, "buluta yüklenemedi", "remote"), "aynı olay tekrar etmez")
        assertNotNull(m.event(AlertKind.BACKUP, "klasöre kopyalanamadı", "folder"), "başka konu")
        now += Duration.ofHours(2)
        assertNotNull(m.event(AlertKind.BACKUP, "buluta yüklenemedi", "remote"))
        assertNotNull(m.event(AlertKind.TEST, "deneme"))
        assertNotNull(m.event(AlertKind.TEST, "deneme"), "deneme her zaman")

        val none = HealthMonitor().also { it.configure(AlertsConfig(), emptyMap(), "Evim") }
        assertNull(none.event(AlertKind.TEST, "deneme"), "kanal yok")
        val off = monitor(AlertsConfig(enabled = false), { now }, "kayıt" to rec)
        assertEquals(0, off.check(HealthInput(DeviceSnapshot(batteryTempC = 60.0))).size)
        assertNull(off.event(AlertKind.CRASH, "çöktü"))

        val mails = mutableListOf<Triple<String, String, String>>()
        val sender = MailSender { to, subject, text, _ -> mails += Triple(to, subject, text) }
        EmailChannel(sender, listOf("a@example.com", "b@example.com"), "Evim").send(Alert(AlertKind.DISK, "", "Depolama dolmak üzere", "Boş alan 300 MB", t0.toString()))
        assertEquals(listOf("a@example.com", "b@example.com"), mails.map { it.first })
        assertEquals("Evim: Depolama dolmak üzere", mails[0].second)

        val cfg = ServerConfig(
            email = EmailConfig(enabled = true, host = "smtp.example.com", from = "evim@example.com"),
            alerts = AlertsConfig(email = listOf("a@example.com"), telegram = TelegramConfig("123:abc", "42"), notification = false),
        )
        assertEquals(setOf("e-posta", "Telegram"), AlertChannels.from(cfg, rec, sender).keys)
        assertEquals(setOf("bildirim"), AlertChannels.from(ServerConfig(alerts = AlertsConfig(email = listOf("a@example.com"))), rec).keys, "SMTP kapalıyken e-posta yok")
    }

    @Test
    fun `telegram botu`() {
        val calls = mutableListOf<Pair<String, String>>()
        val server = embeddedServer(CIO, port = 0) {
            intercept(ApplicationCallPipeline.Call) {
                val path = context.request.uri
                calls += path to URLDecoder.decode(context.receiveText(), "UTF-8")
                when {
                    path.startsWith("/botBOZUK/") -> context.respondText("""{"ok":false,"error_code":401,"description":"Unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
                    path.endsWith("/getUpdates") && path.startsWith("/botBOS/") -> context.respondText("""{"ok":true,"result":[]}""", ContentType.Application.Json)
                    path.endsWith("/getUpdates") -> context.respondText(
                        """{"ok":true,"result":[{"update_id":1,"message":{"chat":{"id":111,"first_name":"Eski"}}},{"update_id":2,"message":{"chat":{"id":987654321,"first_name":"Kasım","type":"private"},"text":"/start"}}]}""",
                        ContentType.Application.Json,
                    )
                    else -> context.respondText("""{"ok":true,"result":{}}""", ContentType.Application.Json)
                }
            }
        }.start(false)
        try {
            val base = "http://127.0.0.1:${runBlocking { server.engine.resolvedConnectors().first().port }}"
            assertEquals("987654321" to "Kasım", TelegramChannel.discoverChat("123:abc", base), "en son mesaj atan sohbet")
            assertNull(TelegramChannel.discoverChat("BOS", base))
            val e = assertFailsWith<IOException> { TelegramChannel.discoverChat("BOZUK", base) }
            assertEquals("Telegram 401: Unauthorized", e.message, "token mesajda yok")

            TelegramChannel("123:abc", "987654321", "Evim", base).send(Alert(AlertKind.SERVER, "", "Sunucu çalışmıyor", "port kullanımda", t0.toString(), critical = true))
            val (path, body) = calls.last()
            assertEquals("/bot123:abc/sendMessage", path)
            assertTrue("chat_id=987654321" in body && "🚨 Evim: Sunucu çalışmıyor\nport kullanımda" in body, body)
        } finally {
            server.stop(100, 1000)
        }
    }

    @Test
    fun `konfigurasyon, sir ve dogrulama`() {
        val cfg = ServerConfig(alerts = AlertsConfig(telegram = TelegramConfig("123:abc", "42")))
        val stored = mutableMapOf<String, String>()
        val secured = ConfigSecrets.secureText(cfg.toJson()) { kind, v -> "${kind}_1".also { stored[it] = v } }!!
        assertTrue("123:abc" !in secured && "secret:telegram_token_1" in secured, secured)
        assertEquals("123:abc", ConfigSecrets.resolve(ServerConfig.fromJson(secured), stored::get).alerts.telegram?.botToken)
        assertTrue("alerts" !in ServerConfig().toJson(), "varsayılan dosyaya yazılmaz")

        val bad = ServerConfig(
            alerts = AlertsConfig(
                email = listOf("x"), telegram = TelegramConfig("", "abc def"), diskFreeMb = 10, errorRatePercent = 0,
                certificateDays = 90, batteryTempC = 99, repeatHours = 0, quietHours = QuietHours("25:00", "7"),
            ),
        )
        val paths = ConfigValidator.validateDetailed(bad).map { it.path }.filter { it.startsWith("alerts") }.toSet()
        assertEquals(
            setOf(
                "alerts.email[0]", "alerts.email", "alerts.telegram.botToken", "alerts.telegram.chatId", "alerts.diskFreeMb",
                "alerts.errorRatePercent", "alerts.certificateDays", "alerts.batteryTempC", "alerts.repeatHours",
                "alerts.quietHours.from", "alerts.quietHours.to",
            ),
            paths,
        )
        assertTrue(ConfigValidator.validateDetailed(ServerConfig(alerts = AlertsConfig(telegram = TelegramConfig("1:a", "@evim_kanal")))).none { it.path.startsWith("alerts") })
    }

    @Test
    fun `yonetici uclari`() = testApplication {
        val key = "yonetici-anahtari-1234567890"
        val db: SqlDriver = TestDb.create(Dialect.SQLITE).open()
        val rec = Recorder()
        val health = HealthMonitor().also { it.configure(AlertsConfig(), mapOf("kayıt" to rec), "Evim") }
        health.check(HealthInput(DeviceSnapshot(batteryTempC = 50.0)))
        application { droidServerModule(ServerConfig(auth = AuthConfig(AuthMode.API_KEY, apiKeys = listOf(key))), db, health = health) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/_alerts").status)
        val r = Json.parseToJsonElement(client.get("/api/_alerts") { bearerAuth(key) }.bodyAsText()).jsonObject
        assertEquals("HEAT", r.getValue("active").jsonArray.single().jsonObject.getValue("kind").jsonPrimitive.content)
        val test = client.post("/api/_alerts/test") { bearerAuth(key) }
        assertEquals(HttpStatusCode.OK, test.status)
        assertEquals(AlertKind.TEST, rec.got.last().kind)
        assertEquals(2, ConfigJson.parseToJsonElement(client.get("/api/_alerts") { bearerAuth(key) }.bodyAsText()).jsonObject.getValue("recent").jsonArray.size)
        health.configure(AlertsConfig(), emptyMap(), "Evim")
        assertEquals(HttpStatusCode.BadGateway, client.post("/api/_alerts/test") { bearerAuth(key) }.status)
        db.close()
    }
}
