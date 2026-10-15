package dev.droidserver.core

import dev.droidserver.core.api.droidServerModule
import dev.droidserver.core.auth.Caller
import dev.droidserver.core.config.AuthConfig
import dev.droidserver.core.config.AuthMode
import dev.droidserver.core.config.ChannelConfig
import dev.droidserver.core.config.ConfigValidator
import dev.droidserver.core.config.RealtimeConfig
import dev.droidserver.core.config.RoleConfig
import dev.droidserver.core.config.Rule
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.config.UsersConfig
import dev.droidserver.core.db.SchemaMigrator
import dev.droidserver.core.realtime.ChannelHub
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** WebSocket kanalları: katılma/gönderme kuralları, çevrimiçi listesi, geçmiş, hız sınırı. */
class ChannelsTest {
    private val key = "yonetici-anahtari-1234567890"
    private val config = ServerConfig(
        auth = AuthConfig(AuthMode.API_KEY, apiKeys = listOf(key), users = UsersConfig(enabled = true, roles = listOf(RoleConfig("editor")))),
        realtime = RealtimeConfig(
            enabled = true,
            channels = listOf(
                ChannelConfig("lobby", join = Rule.PUBLIC, publish = Rule.AUTH, history = 2),
                ChannelConfig("room:*", join = Rule.AUTH, publish = Rule.AUTH, presence = true),
                ChannelConfig("announce", join = Rule.PUBLIC, publish = Rule.ADMIN, roles = listOf("editor"), presence = false),
            ),
        ),
    )

    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject
    private suspend fun DefaultClientWebSocketSession.next(): JsonObject = withTimeout(5_000) { json((incoming.receive() as Frame.Text).readText()) }
    private suspend fun DefaultClientWebSocketSession.say(text: String) = send(Frame.Text(text))
    private fun JsonObject.str(k: String) = getValue(k).jsonPrimitive.content

    @Test
    fun `kanallar uctan uca`() = testApplication {
        val db = TestDb.create(Dialect.SQLITE).open()
        SchemaMigrator(db).migrate(config.schemaCollections())
        application { droidServerModule(config, db) }
        val signup = client.post("/api/auth/signup") { contentType(ContentType.Application.Json); setBody("""{"email":"a@example.com","password":"parola-123"}""") }
        val auth = json(signup.bodyAsText())
        val alice = auth.str("token")
        val aliceId = auth.getValue("user").jsonObject.str("id")
        val ws = createClient { install(WebSockets) }

        ws.webSocket("/api/realtime/ws") {
            // Misafir: lobiye katılır ama gönderemez; odaya katılamaz.
            say("""{"type":"join","channel":"lobby"}""")
            val joined = next()
            assertEquals("joined", joined.str("type"))
            val guest = joined.getValue("me").jsonObject.str("id")
            assertTrue(guest.startsWith("g:"))
            say("""{"type":"send","channel":"lobby","data":{"text":"x"}}""")
            assertEquals("error", next().str("type"))
            say("""{"type":"join","channel":"room:1"}""")
            assertTrue("yetkiniz yok" in next().str("message"))
            say("""{"type":"join","channel":"yok"}""")
            assertTrue("kanalı yok" in next().str("message"))
            say("bozuk")
            assertEquals("error", next().str("type"))

            ws.webSocket("/api/realtime/ws?token=$alice") {
                say("""{"type":"join","channel":"lobby"}""")
                val aj = next()
                assertEquals(setOf(guest, "u:$aliceId"), aj.getValue("members").jsonArray.map { it.jsonObject.str("id") }.toSet())
                say("""{"type":"send","channel":"lobby","data":{"text":"selam"}}""")
                val mine = next()
                assertEquals("message", mine.str("type"))
                assertEquals("selam", mine.getValue("data").jsonObject.str("text"))
                say("""{"type":"join","channel":"room:42"}""")
                assertEquals("joined", next().str("type"))
                say("""{"type":"send","channel":"announce","data":1}""")
                assertTrue("katılın" in next().str("message"), "katılmadan gönderilemez")
                say("""{"type":"ping"}""")
                assertEquals("pong", next().str("type"))
            }

            // Misafirin gördükleri: Alice'in gelişi, iletisi ve ayrılışı.
            val presence = next()
            assertEquals(listOf("presence", "join", "u:$aliceId"), listOf(presence.str("type"), presence.str("event"), presence.getValue("member").jsonObject.str("id")))
            val message = next()
            assertEquals("u:$aliceId", message.getValue("from").jsonObject.str("id"))
            assertEquals("leave", next().str("event"))
        }

        // Yeni katılan son 2 iletiyi alır; yönetici duyuru gönderebilir.
        ws.webSocket("/api/realtime/ws?token=$key") {
            say("""{"type":"join","channel":"lobby"}""")
            assertEquals(listOf("selam"), next().getValue("history").jsonArray.map { it.jsonObject.getValue("data").jsonObject.str("text") })
            say("""{"type":"join","channel":"announce"}""")
            assertTrue("members" !in next(), "presence kapalı kanalda liste yok")
            say("""{"type":"send","channel":"announce","data":"bakım 22:00"}""")
            assertEquals("bakım 22:00", next().str("data"))
            // Hız sınırı: saniyede en çok 20 ileti.
            repeat(ChannelHub.RATE + 5) { say("""{"type":"send","channel":"lobby","data":$it}""") }
            val replies = List(ChannelHub.RATE + 5) { next() }
            assertTrue(replies.any { it.str("type") == "error" && "Çok hızlı" in it.str("message") })
        }
        db.close()
    }

    @Test
    fun `sunucudan yayin, kaliplar ve dogrulama`() {
        val hub = ChannelHub(config.realtime.channels)
        assertEquals("room:*", hub.spec("room:abc")?.name)
        assertEquals(null, hub.spec("rooms"))
        val c = hub.connect(Caller.Anonymous)!!
        hub.handle(c, """{"type":"join","channel":"announce"}""")
        c.outgoing.tryReceive().getOrThrow()
        assertTrue(hub.publish("announce", JsonPrimitive("sunucudan")))
        assertTrue("sunucudan" in c.outgoing.tryReceive().getOrThrow())
        assertEquals(false, hub.publish("yok", JsonPrimitive(1)))
        hub.disconnect(c)
        assertEquals(0, hub.connectionCount)

        val errors = { ch: ChannelConfig -> ConfigValidator.validateDetailed(config.copy(realtime = config.realtime.copy(channels = listOf(ch)))).map { it.path }.toSet() }
        assertEquals(emptySet(), errors(ChannelConfig("chat:*")))
        assertEquals(setOf("realtime.channels[0].name"), errors(ChannelConfig("a*b")))
        assertEquals(setOf("realtime.channels[0].name"), errors(ChannelConfig("boşluk var")))
        assertEquals(setOf("realtime.channels[0].join"), errors(ChannelConfig("x", join = Rule.OWNER)))
        assertEquals(setOf("realtime.channels[0].roles"), errors(ChannelConfig("x", roles = listOf("yazar"))))
        assertEquals(setOf("realtime.channels[0].history"), errors(ChannelConfig("x", history = 1000)))
    }
}
