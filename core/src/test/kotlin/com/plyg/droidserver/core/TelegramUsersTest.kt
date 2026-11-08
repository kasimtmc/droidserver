package com.plyg.droidserver.core

import com.plyg.droidserver.core.api.WriteGate
import com.plyg.droidserver.core.api.droidServerModule
import com.plyg.droidserver.core.auth.TelegramAccounts
import com.plyg.droidserver.core.auth.TelegramUserBot
import com.plyg.droidserver.core.auth.UserService
import com.plyg.droidserver.core.config.AlertsConfig
import com.plyg.droidserver.core.config.AuthConfig
import com.plyg.droidserver.core.config.AuthMode
import com.plyg.droidserver.core.config.ConfigValidator
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.config.TelegramConfig
import com.plyg.droidserver.core.config.UserTelegramConfig
import com.plyg.droidserver.core.config.UsersConfig
import com.plyg.droidserver.core.db.SchemaMigrator
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.request.uri
import io.ktor.server.response.respondText
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLDecoder
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Faz 20.1-20.2: kullanıcıların Telegram hesaplarını botla bağlaması ve Telegram ile giriş. */
class TelegramUsersTest {
    private val token = "123456789:" + "A".repeat(35)
    private val config = ServerConfig(
        auth = AuthConfig(AuthMode.API_KEY, apiKeys = listOf("yonetici-anahtari-1234567890"), users = UsersConfig(enabled = true, sendVerification = false, telegram = UserTelegramConfig(token))),
    )

    /** Sahte Bot API: `getMe`, sıradaki güncellemeler, gönderilen mesajlar; "403" sohbetine mesaj engellenmiş sayılır. */
    private class FakeTelegram : AutoCloseable {
        val updates = ConcurrentLinkedQueue<String>()
        val sent = ConcurrentLinkedQueue<Pair<String, String>>()
        private var id = 0
        val server: EmbeddedServer<*, *> = embeddedServer(CIO, port = 0) {
            intercept(ApplicationCallPipeline.Call) {
                val method = context.request.uri.substringAfterLast('/')
                val body = URLDecoder.decode(context.receiveText(), "UTF-8")
                when (method) {
                    "getMe" -> context.respondText("""{"ok":true,"result":{"id":1,"is_bot":true,"username":"ds_test_bot"}}""", ContentType.Application.Json)
                    "getUpdates" -> {
                        // Bekleyenlerin hepsi (Telegram gibi); yoksa kısa bekleme.
                        val pending = generateSequence { updates.poll() }.toList()
                        if (pending.isEmpty()) delay(50)
                        context.respondText("""{"ok":true,"result":[${pending.joinToString(",")}]}""", ContentType.Application.Json)
                    }
                    else -> {
                        sent += method to body
                        if ("chat_id=403&" in body) {
                            context.respondText("""{"ok":false,"error_code":403,"description":"Forbidden: bot was blocked by the user"}""", ContentType.Application.Json, io.ktor.http.HttpStatusCode.Forbidden)
                        } else {
                            context.respondText("""{"ok":true,"result":true}""", ContentType.Application.Json)
                        }
                    }
                }
            }
        }.start(false)
        val base = "http://127.0.0.1:${runBlocking { server.engine.resolvedConnectors().first().port }}"

        fun message(text: String, chat: String, type: String = "private", from: String = "ayse_tg") =
            """{"update_id":${++id},"message":{"message_id":$id,"date":1,"chat":{"id":$chat,"type":"$type"},"from":{"id":$chat,"username":"$from"},"text":"$text"}}"""

        /** Sohbete gönderilen mesaj metinleri; okunanlar listeden çıkar. */
        fun texts(chat: String): List<String> {
            val mine = sent.filter { it.first == "sendMessage" && "chat_id=$chat&" in it.second }
            sent.removeAll(mine.toSet())
            return mine.map { it.second.substringAfter("text=").substringBefore("&disable") }
        }

        override fun close() = server.stop(0, 0)
    }

    @Test
    fun `baglama, yeniden kullanim, ayrilma ve engelleme`() {
        val db = TestDb.create(Dialect.SQLITE).open()
        SchemaMigrator(db).migrate(config.schemaCollections())
        var now = Instant.parse("2026-11-08T10:00:00Z")
        val users = UserService(db, config)
        val id = users.create(Json.parseToJsonElement("""{"email":"ayse@example.com","password":"parola-12345"}""").jsonObject)
            .getValue("id").jsonPrimitive.content.toLong()
        val audit = mutableListOf<String>()
        FakeTelegram().use { tg ->
            val accounts = TelegramAccounts(db) { now }
            val bot = TelegramUserBot(token, accounts, WriteGate(), "Dükkan", { "ayse@example.com" }, {}, { a, u -> audit += "$a:$u" }, tg.base)
            bot.identify()
            val url = bot.linkUrl(id)
            assertTrue(url.startsWith("https://t.me/ds_test_bot?start="), url)
            val code = url.substringAfter("start=")
            assertTrue(Regex("^[A-Za-z0-9_-]{1,64}$").matches(code), "Telegram'ın kabul ettiği biçim: $code")

            // Başlat: sohbet hesaba bağlanır, bot e-postayla yanıt verir.
            tg.updates += tg.message("/start $code", "555")
            bot.poll(0)
            assertTrue("ayse@example.com" in tg.texts("555").single())
            assertEquals(buildJsonObject { put("linked", JsonPrimitive(true)); put("username", JsonPrimitive("ayse_tg")) }, accounts.status(id))
            assertEquals(listOf("user.telegram.link:$id"), audit)

            // Aynı kod ikinci kez geçmez; grup sohbeti yanıtsız kalır; kodsuz Başlat bilgi verir.
            tg.updates += tg.message("/start $code", "556")
            tg.updates += tg.message("/start ${bot.linkUrl(id).substringAfter("start=")}", "-100", type = "group")
            tg.updates += tg.message("/start", "557")
            bot.poll(0)
            assertTrue("süresi dolmuş" in tg.texts("556").single())
            assertEquals("555", accounts.chatOf(id))
            assertTrue("Dükkan" in tg.texts("557").single())
            assertTrue(tg.texts("-100").isEmpty())

            // Mesaj; yönetici listesinde görünür.
            assertTrue(bot.send(id, "merhaba"))
            assertEquals(listOf("merhaba"), tg.texts("555"))
            assertEquals(true, users.list(1, 10).items.single()["telegram"]?.jsonPrimitive?.boolean)

            // /ayril bağı kaldırır; sonra mesaj gitmez.
            tg.updates += tg.message("/ayril", "555")
            bot.poll(0)
            assertTrue("kaldırıldı" in tg.texts("555").single())
            assertNull(accounts.chatOf(id))
            assertFalse(bot.send(id, "yok"))

            // Süresi dolan kod geçmez.
            val late = bot.linkUrl(id).substringAfter("start=")
            now = now.plus(TelegramAccounts.LINK_TTL).plus(Duration.ofSeconds(1))
            tg.updates += tg.message("/start $late", "555")
            bot.poll(0)
            assertNull(accounts.chatOf(id))

            // Botu engelleyen kullanıcı: mesaj gitmez, bağ düşer.
            tg.updates += tg.message("/start ${bot.linkUrl(id).substringAfter("start=")}", "403")
            bot.poll(0)
            assertEquals("403", accounts.chatOf(id))
            assertFalse(bot.send(id, "engelli"))
            assertNull(accounts.chatOf(id))
            assertTrue("user.telegram.blocked:$id" in audit)

            // Yönetici bağı kaldırır (yalnızca false).
            tg.updates += tg.message("/start ${bot.linkUrl(id).substringAfter("start=")}", "555")
            bot.poll(0)
            users.adminUpdate(id, Json.parseToJsonElement("""{"telegram":false}""").jsonObject)
            assertNull(accounts.chatOf(id))
            assertEquals(false, users.list(1, 10).items.single()["telegram"]?.jsonPrimitive?.boolean)
        }
    }

    @Test
    fun `telegram ile giris`() = FakeTelegram().use { tg ->
        val login = config.copy(auth = config.auth.copy(users = config.auth.users.copy(telegram = UserTelegramConfig(token, login = true))))
        testApplication {
            val db = TestDb.create(Dialect.SQLITE).open()
            SchemaMigrator(db).migrate(login.schemaCollections())
            application { droidServerModule(login, db, telegramApi = tg.base) }
            suspend fun post(path: String, body: String = "{}", token: String? = null) =
                client.post(path) { token?.let(::bearerAuth); contentType(ContentType.Application.Json); setBody(body) }
            val key = "yonetici-anahtari-1234567890"
            for (who in listOf("ayse", "mehmet")) {
                assertEquals(HttpStatusCode.Created, post("/api/auth/users", """{"email":"$who@example.com","password":"parola-12345"}""", key).status)
            }
            val user = Json.parseToJsonElement(post("/api/auth/login", """{"email":"ayse@example.com","password":"parola-12345"}""").bodyAsText())
                .jsonObject.getValue("token").jsonPrimitive.content

            // Ayşe Telegram'ı bağlar.
            var url: String? = null
            var tries = 0
            while (url == null && tries++ < 100) {
                val r = post("/api/auth/telegram/link", token = user)
                if (r.status == HttpStatusCode.OK) url = Json.parseToJsonElement(r.bodyAsText()).jsonObject.getValue("url").jsonPrimitive.content else delay(50)
            }
            tg.updates += tg.message("/start ${url!!.substringAfter("start=")}", "555")
            tries = 0
            while (tg.texts("555").isEmpty() && tries++ < 100) delay(50)

            // Telegram'ı bağlı olmayan ve olmayan hesap: aynı yanıt, kod gitmez. Geçersiz adres 400.
            assertEquals(HttpStatusCode.Accepted, post("/api/auth/request-telegram-code", """{"email":"mehmet@example.com"}""").status)
            assertEquals(HttpStatusCode.Accepted, post("/api/auth/request-telegram-code", """{"email":"kimse@example.com"}""").status)
            assertEquals(HttpStatusCode.BadRequest, post("/api/auth/request-telegram-code", """{"email":"yok"}""").status)

            // Kod Ayşe'nin sohbetine gelir (adres büyük harfle yazılsa da).
            assertEquals(HttpStatusCode.Accepted, post("/api/auth/request-telegram-code", """{"email":"Ayse@Example.com"}""").status)
            var text: String? = null
            tries = 0
            while (text == null && tries++ < 100) text = tg.texts("555").firstOrNull() ?: run { delay(50); null }
            val code = Regex("\\b(\\d{6})\\b").find(text!!)!!.groupValues[1]
            assertTrue(tg.sent.none { it.first == "sendMessage" }, "başka sohbete kod gitmedi")

            // Yanlış kod 401; doğru kod token çifti; aynı kod ikinci kez geçmez.
            assertEquals(HttpStatusCode.Unauthorized, post("/api/auth/telegram-code", """{"email":"ayse@example.com","code":"000000"}""").status)
            val ok = post("/api/auth/telegram-code", """{"email":"ayse@example.com","code":"$code"}""")
            assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
            assertTrue("refreshToken" in Json.parseToJsonElement(ok.bodyAsText()).jsonObject)
            assertEquals(HttpStatusCode.Unauthorized, post("/api/auth/telegram-code", """{"email":"ayse@example.com","code":"$code"}""").status)

            // Hesap başına dakikada bir kod.
            assertEquals(HttpStatusCode.TooManyRequests, post("/api/auth/request-telegram-code", """{"email":"ayse@example.com"}""").status)
        }
    }

    @Test
    fun `dogrulama`() {
        fun errors(c: ServerConfig) = ConfigValidator.validate(c).joinToString()
        assertTrue(errors(config).isEmpty(), errors(config))
        val off = config.copy(auth = config.auth.copy(users = config.auth.users.copy(enabled = false)))
        assertTrue("kullanıcı hesapları" in errors(off), errors(off))
        val bad = config.copy(auth = config.auth.copy(users = config.auth.users.copy(telegram = UserTelegramConfig("abc"))))
        assertTrue("@BotFather" in errors(bad), errors(bad))
        val same = config.copy(alerts = AlertsConfig(telegram = TelegramConfig(token, "123456789")))
        assertTrue("ayrı olmalı" in errors(same), errors(same))
        assertTrue(ConfigValidator.validate(config.copy(auth = config.auth.copy(users = config.auth.users.copy(telegram = UserTelegramConfig("secret:user_telegram_token"))))).isEmpty())
    }

    @Test
    fun `http uclari`() = FakeTelegram().use { tg ->
        testApplication {
            val db = TestDb.create(Dialect.SQLITE).open()
            SchemaMigrator(db).migrate(config.schemaCollections())
            application { droidServerModule(config, db, telegramApi = tg.base) }
            suspend fun post(path: String, body: String = "{}", token: String? = null) =
                client.post(path) { token?.let(::bearerAuth); contentType(ContentType.Application.Json); setBody(body) }
            suspend fun status(token: String) = Json.parseToJsonElement(client.get("/api/auth/telegram") { bearerAuth(token) }.bodyAsText()).jsonObject

            assertEquals(HttpStatusCode.Created, post("/api/auth/users", """{"email":"ayse@example.com","password":"parola-12345"}""", "yonetici-anahtari-1234567890").status)
            val user = Json.parseToJsonElement(post("/api/auth/login", """{"email":"ayse@example.com","password":"parola-12345"}""").bodyAsText())
                .jsonObject.getValue("token").jsonPrimitive.content
            assertEquals(HttpStatusCode.Unauthorized, post("/api/auth/telegram/link").status)

            // Bot arka planda adını alana kadar 503.
            var link: JsonObject? = null
            var tries = 0
            while (link == null && tries++ < 100) {
                val r = post("/api/auth/telegram/link", token = user)
                if (r.status == HttpStatusCode.OK) link = Json.parseToJsonElement(r.bodyAsText()).jsonObject else delay(50)
            }
            val url = link!!.getValue("url").jsonPrimitive.content
            assertEquals(600, link!!.getValue("expiresIn").jsonPrimitive.content.toInt())
            assertEquals(false, status(user).getValue("linked").jsonPrimitive.boolean)

            tg.updates += tg.message("/start ${url.substringAfter("start=")}", "555")
            repeat(100) { if (!status(user).getValue("linked").jsonPrimitive.boolean) delay(50) }
            assertEquals("ayse_tg", status(user).getValue("username").jsonPrimitive.content)

            assertEquals(HttpStatusCode.NoContent, client.delete("/api/auth/telegram") { bearerAuth(user) }.status)
            assertEquals(false, status(user).getValue("linked").jsonPrimitive.boolean)
        }
    }
}
