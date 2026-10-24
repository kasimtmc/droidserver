package com.plyg.droidserver.core

import com.plyg.droidserver.core.alerts.TelegramActions
import com.plyg.droidserver.core.alerts.TelegramBot
import com.plyg.droidserver.core.config.AlertsConfig
import com.plyg.droidserver.core.config.ConfigValidator
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.config.TelegramConfig
import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.request.uri
import io.ktor.server.response.respondText
import kotlinx.coroutines.runBlocking
import java.net.URLDecoder
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Faz 17.12: Telegram'dan yönetim komutları. */
class TelegramBotTest {
    private val admin = "987654321"

    private class Recorder : TelegramActions {
        val calls = mutableListOf<String>()
        override fun status() = "durum-metni".also { calls += "status" }
        override fun backup() = "yedek alındı".also { calls += "backup" }
        override fun logs(count: Int) = (1..count).map { "satır $it" }.also { calls += "logs:$count" }
        override fun start() = "başlıyor".also { calls += "start" }
        override fun stop() = "durdu".also { calls += "stop" }
    }

    @Test
    fun `komutlar yalnizca yonetici sohbetinden, durdurma onayla`() {
        val updates = ConcurrentLinkedQueue<String>()
        val sent = ConcurrentLinkedQueue<Pair<String, String>>()
        val server = embeddedServer(CIO, port = 0) {
            intercept(ApplicationCallPipeline.Call) {
                val method = context.request.uri.substringAfterLast('/')
                val body = URLDecoder.decode(context.receiveText(), "UTF-8")
                if (method == "getUpdates") {
                    context.respondText("""{"ok":true,"result":[${updates.poll() ?: ""}]}""", ContentType.Application.Json)
                } else {
                    sent += method to body
                    context.respondText("""{"ok":true,"result":true}""", ContentType.Application.Json)
                }
            }
        }.start(false)
        try {
            val base = "http://127.0.0.1:${runBlocking { server.engine.resolvedConnectors().first().port }}"
            var now = 1_800_000_000_000L
            val actions = Recorder()
            val logs = mutableListOf<String>()
            val bot = TelegramBot("123:abc", admin, actions, { logs += it }, base) { now }
            var id = 0
            fun message(text: String, chat: String = admin, ageSec: Long = 0) =
                """{"update_id":${++id},"message":{"message_id":$id,"date":${now / 1000 - ageSec},"chat":{"id":$chat},"text":"$text"}}"""
            fun texts() = sent.filter { it.first == "sendMessage" }.map { it.second }.also { sent.clear() }

            updates += message("/durum") + "," + message("/loglar@benim_bot 3") + "," + message("/yedek")
            assertEquals(3, bot.poll(0))
            assertEquals(listOf("status", "logs:3", "backup"), actions.calls)
            val out = texts()
            assertTrue(out[0].contains("chat_id=$admin") && out[0].contains("text=durum-metni"), out[0])
            assertTrue("satır 1\nsatır 2\nsatır 3" in out[1], out[1])
            assertTrue("yedek alındı" in out.last())

            // Başka sohbet ve eski komut yanıtsız; bilinmeyen komut yardım.
            actions.calls.clear()
            updates += message("/durdur", chat = "111") + "," + message("/baslat", ageSec = 600) + "," + message("/neyse")
            bot.poll(0)
            assertEquals(emptyList(), actions.calls)
            assertTrue(logs.any { "111" in it })
            assertTrue("/durum" in texts().single(), "yardım")

            // /durdur onay düğmesiyle; onaysız durmaz.
            updates += message("/durdur")
            bot.poll(0)
            assertEquals(emptyList(), actions.calls)
            val ask = texts().single()
            val nonce = Regex("\"callback_data\":\"durdur:([a-z0-9]+)\"").find(ask)!!.groupValues[1]
            fun press(data: String, chat: String = admin) =
                """{"update_id":${++id},"callback_query":{"id":"q$id","data":"$data","message":{"message_id":7,"chat":{"id":$chat}}}}"""
            updates += press("durdur:$nonce", chat = "111")
            bot.poll(0)
            assertEquals(emptyList(), actions.calls, "başka sohbetteki düğme")
            updates += press("durdur:yanlis")
            bot.poll(0)
            assertEquals(emptyList(), actions.calls, "eski/yanlış belirteç")
            updates += message("/durdur")
            bot.poll(0)
            val second = Regex("durdur:([a-z0-9]+)").find(texts().single())!!.groupValues[1]
            now += 10_000
            sent.clear()
            updates += press("durdur:$second")
            bot.poll(0)
            assertEquals(listOf("stop"), actions.calls)
            assertTrue(sent.any { it.first == "editMessageText" && "text=durdu" in it.second }, sent.toString())
            assertTrue(sent.any { it.first == "answerCallbackQuery" })
        } finally {
            server.stop(100, 1000)
        }
    }

    @Test
    fun `yonetici sohbeti dogrulamasi`() {
        fun errors(admin: String?) = ConfigValidator.validate(ServerConfig(alerts = AlertsConfig(telegram = TelegramConfig("123:abc", "42", admin))))
        assertTrue(errors("-100123").isEmpty())
        assertTrue(errors(null).isEmpty())
        assertTrue(errors("@kanal").any { "Yönetici sohbetinin" in it }, errors("@kanal").toString())
    }
}
