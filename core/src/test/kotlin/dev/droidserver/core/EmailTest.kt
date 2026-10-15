package dev.droidserver.core

import dev.droidserver.core.api.droidServerModule
import dev.droidserver.core.config.AuthConfig
import dev.droidserver.core.config.AuthMode
import dev.droidserver.core.config.ConfigValidator
import dev.droidserver.core.config.EmailConfig
import dev.droidserver.core.config.ScriptsConfig
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.config.SmtpSecurity
import dev.droidserver.core.config.UsersConfig
import dev.droidserver.core.db.SchemaMigrator
import dev.droidserver.core.db.SqlDriver
import dev.droidserver.core.mail.MailSender
import dev.droidserver.core.mail.SmtpClient
import dev.droidserver.core.mail.SmtpException
import dev.droidserver.core.net.TlsIdentity
import dev.droidserver.core.script.ScriptRuntime
import dev.droidserver.core.script.ScriptSource
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.parameters
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.KeyStore
import java.util.Base64
import java.util.Collections
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EmailTest {
    private val key = "yonetici-anahtari-1234567890"

    // ---- SMTP istemcisi, sahte sunucuya karşı ----

    /** STARTTLS / SSL ve AUTH destekleyen sahte SMTP sunucusu; gelen iletileri ve komutları kaydeder. */
    private inner class FakeSmtp(private val implicitTls: Boolean, private val password: String = "uygulama-sifresi") {
        val identity = TlsIdentity.generate("localhost")
        val commands: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val messages: MutableList<String> = Collections.synchronizedList(mutableListOf())
        private val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        val port get() = server.localPort
        private val serverSsl = identity.sslContext().socketFactory

        init {
            closers += server::close
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val s = runCatching { server.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) { runCatching { session(s) } }
                }
            }
        }

        private fun tls(s: Socket): Socket = (serverSsl.createSocket(s, null, s.port, true) as SSLSocket).apply {
            useClientMode = false
            startHandshake()
        }

        private fun session(plain: Socket) {
            var s = if (implicitTls) tls(plain) else plain
            var r = BufferedReader(InputStreamReader(s.getInputStream()))
            var w = s.getOutputStream()
            fun say(line: String) { w.write("$line\r\n".toByteArray()); w.flush() }
            say("220 sahte ESMTP")
            var secure = implicitTls
            while (true) {
                val line = r.readLine() ?: return
                commands += if (line.startsWith("AUTH PLAIN ")) "AUTH PLAIN ***" else line
                when {
                    line.startsWith("EHLO") -> {
                        say("250-sahte")
                        if (!secure) say("250-STARTTLS")
                        say("250 AUTH PLAIN LOGIN")
                    }
                    line == "STARTTLS" -> {
                        say("220 hazır")
                        s = tls(s); secure = true
                        r = BufferedReader(InputStreamReader(s.getInputStream())); w = s.getOutputStream()
                    }
                    line.startsWith("AUTH PLAIN ") -> {
                        val parts = String(Base64.getDecoder().decode(line.removePrefix("AUTH PLAIN "))).split('\u0000')
                        if (secure && parts.getOrNull(2) == password) say("235 tamam") else say("535 5.7.8 kimlik reddedildi")
                    }
                    line.startsWith("MAIL FROM") || line.startsWith("RCPT TO") -> say("250 tamam")
                    line == "DATA" -> {
                        say("354 gönder")
                        val body = StringBuilder()
                        while (true) {
                            val l = r.readLine() ?: return
                            if (l == ".") break
                            body.append(if (l.startsWith("..")) l.drop(1) else l).append("\n")
                        }
                        messages += body.toString()
                        say("250 kuyrukta")
                    }
                    line == "QUIT" -> { say("221 güle güle"); s.close(); return }
                    else -> say("500 bilinmeyen")
                }
            }
        }

        /** İstemci: bu sunucunun sertifikasına güvenir. */
        fun clientSsl(): SSLSocketFactory {
            val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null); setCertificateEntry("s", identity.certificate) }
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
            return SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }.socketFactory
        }
    }

    private val closers = mutableListOf<() -> Unit>()

    @AfterTest
    fun cleanup() = closers.reversed().forEach { runCatching(it) }

    private fun smtpConfig(port: Int, security: SmtpSecurity) = EmailConfig(
        enabled = true, host = "localhost", port = port, security = security, username = "evim@example.com",
        from = "evim@example.com", fromName = "Evim Sunucusu",
    )

    private fun decodeBody(message: String): String {
        val b64 = message.substringAfter("Content-Transfer-Encoding: base64\n\n").substringBefore("\n\n").replace("\n", "")
        return String(Base64.getDecoder().decode(b64), Charsets.UTF_8)
    }

    @Test
    fun `STARTTLS ve SSL ile gonderir, Turkce konu ve govde, nokta kacisi`() {
        for (security in listOf(SmtpSecurity.STARTTLS, SmtpSecurity.SSL)) {
            val smtp = FakeSmtp(implicitTls = security == SmtpSecurity.SSL)
            val client = SmtpClient(smtpConfig(smtp.port, security), "uygulama-sifresi", ssl = smtp.clientSsl(), hostnameVerifier = { _, _ -> true })
            client.send("ali@example.com", "Şifre sıfırlama", "Merhaba Ayşe,\n.nokta ile başlayan satır\nçğıöşü")
            val msg = smtp.messages.single()
            assertTrue("Subject: =?UTF-8?B?" in msg, msg)
            assertTrue("From: Evim Sunucusu <evim@example.com>" in msg, msg)
            assertEquals("Merhaba Ayşe,\n.nokta ile başlayan satır\nçğıöşü", decodeBody(msg).replace("\r\n", "\n"))
            assertTrue("AUTH PLAIN ***" in smtp.commands, smtp.commands.toString())
            if (security == SmtpSecurity.STARTTLS) assertEquals("STARTTLS", smtp.commands[1])
            assertTrue(smtp.commands.indexOf("AUTH PLAIN ***") > smtp.commands.indexOf("STARTTLS"), "parola şifreli bağlantıda")
        }
    }

    @Test
    fun `yanlis parola, guvenilmeyen sertifika, baslik enjeksiyonu`() {
        val smtp = FakeSmtp(implicitTls = false)
        val wrong = SmtpClient(smtpConfig(smtp.port, SmtpSecurity.STARTTLS), "yanlis", ssl = smtp.clientSsl(), hostnameVerifier = { _, _ -> true })
        val e = assertFailsWith<SmtpException> { wrong.send("a@example.com", "x", "y") }
        assertTrue("uygulama şifresi" in e.message.orEmpty(), e.message)

        // Varsayılan güven deposu sahte sunucunun sertifikasını tanımaz.
        assertFailsWith<javax.net.ssl.SSLException> { SmtpClient(smtpConfig(smtp.port, SmtpSecurity.STARTTLS), "uygulama-sifresi").send("a@example.com", "x", "y") }
        val byIp = SmtpClient(smtpConfig(smtp.port, SmtpSecurity.STARTTLS).copy(host = "127.0.0.1"), "uygulama-sifresi", ssl = smtp.clientSsl())
        assertFailsWith<javax.net.ssl.SSLException>("sertifika 127.0.0.1 adına değil") { byIp.send("a@example.com", "x", "y") }
        val ok = SmtpClient(smtpConfig(smtp.port, SmtpSecurity.STARTTLS), "uygulama-sifresi", ssl = smtp.clientSsl())
        ok.send("a@example.com", "ad doğrulaması geçer", "y")
        assertFailsWith<IllegalArgumentException> { ok.send("a@example.com", "x\r\nBcc: kotu@example.com", "y") }
        assertFailsWith<IllegalArgumentException> { ok.send("a@example.com>\r\nRCPT TO:<b@example.com", "x", "y") }
    }

    // ---- doğrulama ve parola sıfırlama akışı ----

    private data class Sent(val to: String, val subject: String, val text: String)

    private val outbox: MutableList<Sent> = Collections.synchronizedList(mutableListOf())
    private val sender = MailSender { to, subject, text, _ -> outbox += Sent(to, subject, text) }
    private lateinit var db: SqlDriver

    private fun config(requireVerified: Boolean = false, publicUrl: String? = "https://ornek.test:8443") = ServerConfig(
        name = "Evim",
        auth = AuthConfig(AuthMode.API_KEY, apiKeys = listOf(key), users = UsersConfig(enabled = true, requireVerified = requireVerified)),
        email = EmailConfig(enabled = true, host = "smtp.example.com", from = "evim@example.com", publicUrl = publicUrl),
    )

    @BeforeTest
    fun open() {
        db = TestDb.create(Dialect.SQLITE).open()
        SchemaMigrator(db).migrate(config().schemaCollections())
    }

    @AfterTest
    fun close() = db.close()

    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject
    private fun JsonObject.str(k: String) = getValue(k).jsonPrimitive.content

    private suspend fun HttpClient.postJson(path: String, body: String, token: String? = null): HttpResponse = post(path) {
        token?.let { bearerAuth(it) }
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    /** Arka planda gönderilen e-postayı bekler. */
    private fun awaitMail(count: Int): Sent {
        val until = System.currentTimeMillis() + 5_000
        while (outbox.size < count && System.currentTimeMillis() < until) Thread.sleep(20)
        assertTrue(outbox.size >= count, "e-posta gelmedi")
        return outbox[count - 1]
    }

    private fun linkToken(text: String, path: String): String {
        val url = text.lines().first { it.startsWith("https://ornek.test:8443$path?token=") }
        return URLDecoder.decode(url.substringAfter("token="), "UTF-8")
    }

    @Test
    fun `kayit dogrulama e-postasi ve sunucu sayfasi`() = testApplication {
        application { droidServerModule(config(), db, mailSender = sender) }
        val r = client.postJson("/api/auth/signup", """{"email":"Ayse@Example.com","password":"parola-123"}""")
        assertEquals(HttpStatusCode.Created, r.status)
        val token = json(r.bodyAsText()).str("token")
        val mail = awaitMail(1)
        assertEquals("ayse@example.com", mail.to)
        assertTrue("Evim" in mail.subject)

        val page = client.get("/_auth/verify?token=" + java.net.URLEncoder.encode(linkToken(mail.text, "/_auth/verify"), "UTF-8"))
        assertEquals(HttpStatusCode.OK, page.status)
        assertTrue("doğrulandı" in page.bodyAsText())
        assertTrue("frame-ancestors 'none'" in page.headers["Content-Security-Policy"].orEmpty())
        assertEquals("true", json(client.get("/api/auth/me") { bearerAuth(token) }.bodyAsText()).str("verified"))

        // Doğrulanmış hesaba yeniden doğrulama e-postası gitmez; bozuk belirteç anlaşılır hata.
        assertEquals(HttpStatusCode.Accepted, client.post("/api/auth/request-verification") { bearerAuth(token) }.status)
        Thread.sleep(200)
        assertEquals(1, outbox.size)
        assertEquals(HttpStatusCode.BadRequest, client.get("/_auth/verify?token=bozuk").status)
    }

    @Test
    fun `dogrulanmamis giris engellenir, kodla dogrulanir`() = testApplication {
        application { droidServerModule(config(requireVerified = true, publicUrl = null), db, mailSender = sender) }
        client.postJson("/api/auth/signup", """{"email":"b@example.com","password":"parola-123"}""")
        val login = """{"email":"b@example.com","password":"parola-123"}"""
        assertEquals(HttpStatusCode.Forbidden, client.postJson("/api/auth/login", login).status)
        assertEquals(HttpStatusCode.Unauthorized, client.postJson("/api/auth/login", """{"email":"b@example.com","password":"yanlis-123"}""").status, "yanlış parolada doğrulama durumu sızmaz")

        val code = awaitMail(1).text.lines().first { it.startsWith("Doğrulama kodu: ") }.removePrefix("Doğrulama kodu: ")
        assertEquals(HttpStatusCode.OK, client.postJson("/api/auth/confirm-verification", """{"token":"$code"}""").status)
        assertEquals(HttpStatusCode.OK, client.postJson("/api/auth/login", login).status)
    }

    @Test
    fun `parola sifirlama - hesap sizdirmaz, tek kullanimlik, amaca bagli`() = testApplication {
        application { droidServerModule(config(), db, mailSender = sender) }
        val signup = json(client.postJson("/api/auth/signup", """{"email":"c@example.com","password":"eski-parola-1"}""").bodyAsText())
        val verifyMail = awaitMail(1)

        val unknown = client.postJson("/api/auth/request-reset", """{"email":"yok@example.com"}""")
        val known = client.postJson("/api/auth/request-reset", """{"email":"C@example.com"}""")
        assertEquals(HttpStatusCode.Accepted, unknown.status)
        assertEquals(unknown.bodyAsText(), known.bodyAsText(), "hesap olsa da olmasa da aynı yanıt")
        val mail = awaitMail(2)
        assertTrue("parola sıfırlama" in mail.subject)
        val reset = linkToken(mail.text, "/_auth/reset")
        Thread.sleep(200)
        assertEquals(2, outbox.size, "olmayan hesaba e-posta gitmez")
        client.postJson("/api/auth/request-reset", """{"email":"c@example.com"}""")
        Thread.sleep(200)
        assertEquals(2, outbox.size, "aynı adrese dakikada bir")

        // Yanlış amaçlı belirteçler reddedilir.
        val verifyToken = linkToken(verifyMail.text, "/_auth/verify")
        assertEquals(HttpStatusCode.BadRequest, client.postJson("/api/auth/confirm-reset", """{"token":"$verifyToken","password":"yeni-parola-1"}""").status)
        assertEquals(HttpStatusCode.BadRequest, client.postJson("/api/auth/confirm-reset", """{"token":"${signup.str("token")}","password":"yeni-parola-1"}""").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/auth/me") { bearerAuth(reset) }.status, "sıfırlama belirteci erişim token'ı değil")

        val form = client.get("/_auth/reset?token=" + java.net.URLEncoder.encode(reset, "UTF-8")).bodyAsText()
        assertTrue("name=\"token\"" in form && "<script" !in form)
        val mismatch = client.submitForm("/_auth/reset", parameters { append("token", reset); append("password", "yeni-parola-1"); append("repeat", "baska") })
        assertEquals(HttpStatusCode.BadRequest, mismatch.status)
        val done = client.submitForm("/_auth/reset", parameters { append("token", reset); append("password", "yeni-parola-1"); append("repeat", "yeni-parola-1") })
        assertEquals(HttpStatusCode.OK, done.status, done.bodyAsText())

        assertEquals(HttpStatusCode.Unauthorized, client.postJson("/api/auth/login", """{"email":"c@example.com","password":"eski-parola-1"}""").status)
        assertEquals(HttpStatusCode.OK, client.postJson("/api/auth/login", """{"email":"c@example.com","password":"yeni-parola-1"}""").status)
        assertEquals(HttpStatusCode.Unauthorized, client.postJson("/api/auth/refresh", """{"refreshToken":"${signup.str("refreshToken")}"}""").status, "eski oturumlar kapandı")
        assertEquals(HttpStatusCode.BadRequest, client.postJson("/api/auth/confirm-reset", """{"token":"$reset","password":"ucuncu-parola"}""").status, "tek kullanımlık")
    }

    @Test
    fun `script ctx mail ve dogrulama kurallari`() = testApplication {
        val runtime = ScriptRuntime.load(
            listOf(ScriptSource("m.js", """routerAdd("POST", "/bildir", (req, ctx) => { ctx.mail.send("d@example.com", "Sipariş", "Yeni sipariş: " + req.body.no); return { ok: true } }, "admin")""")),
            ScriptsConfig(enabled = true), emptySet(),
        )
        application { droidServerModule(config().copy(scripts = ScriptsConfig(enabled = true)), db, scripts = runtime, mailSender = sender) }
        assertEquals(HttpStatusCode.OK, client.postJson("/api/bildir", """{"no":42}""", key).status)
        assertEquals(Sent("d@example.com", "Sipariş", "Yeni sipariş: 42"), outbox.single())
        assertEquals(HttpStatusCode.TooManyRequests, client.postJson("/api/bildir", """{"no":43}""", key).status, "aynı adrese dakikada bir")

        fun errors(c: ServerConfig) = ConfigValidator.validate(c)
        assertTrue(errors(config().copy(email = EmailConfig())).isEmpty())
        assertTrue(errors(config(requireVerified = true).copy(email = EmailConfig())).any { "doğrulamasını zorunlu" in it })
        assertTrue(errors(config().copy(email = config().email.copy(from = "adres-degil"))).any { "Gönderen adresi" in it })
        assertTrue(errors(config().copy(email = config().email.copy(username = "u", password = null))).any { "SMTP parolası" in it })
        assertTrue(errors(config().copy(email = config().email.copy(publicUrl = "ornek.test"))).any { "http://" in it })
        assertNotNull(config().email.publicUrl)
    }
}
