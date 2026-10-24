package com.plyg.droidserver.core

import com.plyg.droidserver.core.api.droidServerModule
import com.plyg.droidserver.core.config.AuthConfig
import com.plyg.droidserver.core.config.AuthMode
import com.plyg.droidserver.core.config.EmailConfig
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.config.UsersConfig
import com.plyg.droidserver.core.db.SchemaMigrator
import com.plyg.droidserver.core.mail.MailSender
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLDecoder
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Faz 17.13: kullanıcılara toplu e-posta, abonelikten çıkma ve iptal. */
class BroadcastTest {
    private val key = "yonetici-anahtari-1234567890"
    private val config = ServerConfig(
        name = "Evim",
        auth = AuthConfig(AuthMode.API_KEY, apiKeys = listOf(key), users = UsersConfig(enabled = true)),
        email = EmailConfig(enabled = true, host = "smtp.example.com", from = "evim@example.com", publicUrl = "https://evim.example"),
    )

    private data class Mail(val to: String, val subject: String, val text: String, val html: String?)

    @Test
    fun `suzgec, gonderim, abonelikten cikma ve iptal`() = testApplication {
        val db = TestDb.create(Dialect.SQLITE).open()
        SchemaMigrator(db).migrate(config.schemaCollections())
        val now = "2026-10-23T00:00:00Z"
        for ((email, verified, role) in listOf(
            Triple("ali@example.com", true, "user"), Triple("ayse@example.com", true, "admin"), Triple("can@example.com", false, "user"),
            Triple("deniz@example.com", true, "user"), Triple("ece@example.com", true, "user"),
        )) {
            db.execute(
                "INSERT INTO \"_users\" (\"email\", \"password_hash\", \"verified\", \"role\", \"created_at\", \"updated_at\") VALUES (?, 'x', ?, ?, ?, ?)",
                listOf(email, if (verified) 1 else 0, role, now, now),
            )
        }
        val mails = Collections.synchronizedList(mutableListOf<Mail>())
        application { droidServerModule(config, db, mailSender = MailSender { to, subject, text, html -> mails += Mail(to, subject, text, html) }) }
        fun json(r: String) = Json.parseToJsonElement(r).jsonObject
        suspend fun HttpClient.broadcast(body: String, auth: Boolean = true): HttpResponse = post("/api/_broadcast") {
            if (auth) bearerAuth(key)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        suspend fun recipients(extra: String = "") =
            json(client.broadcast("""{"subject":"Duyuru","text":"Merhaba","dryRun":true$extra}""").bodyAsText()).getValue("recipients").jsonPrimitive.content.toInt()
        suspend fun job(id: String) = json(client.get("/api/_jobs?name=_broadcast") { bearerAuth(key) }.bodyAsText())
            .getValue("items").jsonArray.map { it.jsonObject }.first { it.getValue("id").jsonPrimitive.content == id }
        suspend fun waitFor(id: String, status: String): JsonObject {
            repeat(100) {
                val j = job(id)
                if (j.getValue("status").jsonPrimitive.content == status) return j
                Thread.sleep(100)
            }
            error("iş $status olmadı: ${job(id)}")
        }

        // Süzgeç: varsayılan yalnızca doğrulanmış adresler.
        assertEquals(4, recipients())
        assertEquals(5, recipients(""","verifiedOnly":false"""))
        assertEquals(1, recipients(""","role":"admin""""))
        assertEquals(2, recipients(""","where":"email[in]=ali@example.com,deniz@example.com""""))

        // Hatalı istekler.
        assertEquals(HttpStatusCode.Unauthorized, client.broadcast("""{"subject":"a","text":"b"}""", auth = false).status)
        assertEquals(HttpStatusCode.BadRequest, client.broadcast("""{"text":"b"}""").status)
        assertEquals(HttpStatusCode.BadRequest, client.broadcast("""{"channel":"push","subject":"a","text":"b"}""").status, "push kapalı")
        assertEquals(HttpStatusCode.BadRequest, client.broadcast("""{"subject":"a","text":"b","where":"password_hash[like]=a"}""").status)
        assertEquals(HttpStatusCode.BadRequest, client.broadcast("""{"subject":"a","text":"b","where":"yok=1&x[gt]=2"}""").status)

        // Gönderim: hızlı (dakikada 600), abonelikten çıkma bağlantısıyla.
        val sent = json(client.broadcast("""{"subject":"Duyuru","text":"Merhaba","html":"<b>Merhaba</b>","perMinute":600}""").bodyAsText())
        assertEquals("4", sent.getValue("recipients").jsonPrimitive.content)
        val done = waitFor(sent.getValue("job").jsonPrimitive.content, "done")
        assertEquals("4", done.getValue("result").jsonObject.getValue("sent").jsonPrimitive.content)
        assertEquals(listOf("ali@example.com", "ayse@example.com", "deniz@example.com", "ece@example.com"), mails.map { it.to })
        assertTrue(mails.all { it.subject == "Duyuru" && "Abonelikten çık" in it.html.orEmpty() })
        val link = Regex("https://evim.example(/_auth/unsubscribe\\S+)").find(mails.first().text)!!.groupValues[1]

        // Abonelikten çıkma: GET yalnızca onay sayfası, POST çıkarır; belirteç adrese bağlı.
        assertEquals(HttpStatusCode.OK, client.get(link).status)
        assertEquals(4, recipients(), "GET çıkarmaz (e-posta tarayıcıları bağlantıyı önceden açabilir)")
        val q = URI("https://x$link").rawQuery.split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        assertEquals(HttpStatusCode.BadRequest, client.submitForm("/_auth/unsubscribe", parameters { append("email", "ayse@example.com"); append("t", q.getValue("t")) }).status)
        assertEquals(HttpStatusCode.OK, client.submitForm("/_auth/unsubscribe", parameters { append("email", q.getValue("email")); append("t", q.getValue("t")) }).status)
        assertEquals(3, recipients())

        // İptal: dakikada 1 kişi; ilk gönderimden sonra iptal edilince durur.
        mails.clear()
        val slow = json(client.broadcast("""{"subject":"Yavaş","text":"…","perMinute":1}""").bodyAsText()).getValue("job").jsonPrimitive.content
        repeat(50) { if (mails.isEmpty()) Thread.sleep(100) }
        assertEquals(1, mails.size)
        assertEquals(HttpStatusCode.OK, client.post("/api/_jobs/$slow/cancel") { bearerAuth(key) }.status)
        Thread.sleep(1_500)
        assertEquals(1, mails.size, "iptalden sonra gönderim yok")
        assertEquals("cancelled", job(slow).getValue("status").jsonPrimitive.content)
        db.close()
    }
}
