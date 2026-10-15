package dev.droidserver.core

import dev.droidserver.core.api.droidServerModule
import dev.droidserver.core.config.AuthConfig
import dev.droidserver.core.config.AuthMode
import dev.droidserver.core.config.CollectionConfig
import dev.droidserver.core.config.FieldConfig
import dev.droidserver.core.config.FieldType
import dev.droidserver.core.config.OnDelete
import dev.droidserver.core.config.RealtimeConfig
import dev.droidserver.core.config.Rule
import dev.droidserver.core.config.Rules
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.config.SystemCollections
import dev.droidserver.core.config.UsersConfig
import dev.droidserver.core.db.SchemaMigrator
import dev.droidserver.core.db.SqlDriver
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Canlı abonelik gerçek bir CIO sunucusunda (SSE akışı test motorunda tamponlanır). */
class RealtimeTest {
    private val key = "yonetici-anahtari-1234567890"
    private val todos = CollectionConfig(
        "todos", listOf(FieldConfig("title", FieldType.TEXT)),
        rules = Rules(list = Rule.PUBLIC, view = Rule.PUBLIC, create = Rule.PUBLIC, update = Rule.PUBLIC, delete = Rule.PUBLIC),
    )
    private val notes = CollectionConfig(
        "notes",
        listOf(
            FieldConfig("body", FieldType.TEXT),
            FieldConfig("author", FieldType.RELATION, collection = SystemCollections.USERS, required = true, onDelete = OnDelete.CASCADE),
        ),
        rules = Rules(list = Rule.OWNER, view = Rule.OWNER, create = Rule.AUTH, update = Rule.OWNER, delete = Rule.OWNER),
        ownerField = "author",
    )
    private val secret = CollectionConfig("secret", listOf(FieldConfig("x", FieldType.TEXT)))

    private fun config(realtime: RealtimeConfig = RealtimeConfig(enabled = true)) = ServerConfig(
        auth = AuthConfig(AuthMode.API_KEY, apiKeys = listOf(key), users = UsersConfig(enabled = true)),
        collections = listOf(todos, notes, secret),
        realtime = realtime,
    )

    private lateinit var db: SqlDriver
    private var server: EmbeddedServer<*, *>? = null
    private var port = 0
    private val streams = mutableListOf<HttpURLConnection>()
    private val http = HttpClient.newHttpClient()

    @BeforeTest
    fun open() {
        db = TestDb.create(Dialect.SQLITE).open()
        SchemaMigrator(db).migrate(config().schemaCollections())
    }

    @AfterTest
    fun close() {
        streams.forEach { runCatching { it.disconnect() } }
        server?.stop(100, 500)
        db.close()
    }

    private fun start(cfg: ServerConfig = config()) {
        port = ServerSocket(0).use { it.localPort }
        server = embeddedServer(CIO, port = port, host = "127.0.0.1") { droidServerModule(cfg, db) }.start(wait = false)
    }

    private fun request(method: String, path: String, token: String?, body: String? = null): Pair<Int, String> {
        val b = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
            .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
            .header("Content-Type", "application/json")
        token?.let { b.header("Authorization", "Bearer $it") }
        val r = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
        return r.statusCode() to r.body()
    }

    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject

    private fun signup(email: String): Pair<String, Long> {
        val (code, body) = request("POST", "/api/auth/signup", null, """{"email":"$email","password":"parola-123"}""")
        assertEquals(201, code, body)
        val j = json(body)
        return j.getValue("token").jsonPrimitive.content to j.getValue("user").jsonObject.getValue("id").jsonPrimitive.content.toLong()
    }

    /** SSE olaylarını (boş satırla biten bloklar) arka planda kuyruğa toplayan bağlantı. */
    private inner class Stream(path: String, token: String? = null, lastEventId: String? = null) {
        val status: Int
        val events = LinkedBlockingQueue<String>()

        init {
            val c = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
            token?.let { c.setRequestProperty("Authorization", "Bearer $it") }
            lastEventId?.let { c.setRequestProperty("Last-Event-ID", it) }
            c.readTimeout = 10_000
            streams += c
            status = c.responseCode
            if (status == 200) {
                val reader = c.inputStream.bufferedReader()
                thread(isDaemon = true) {
                    val block = StringBuilder()
                    runCatching {
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) {
                                if (block.isNotEmpty() && !block.startsWith(":") && !block.startsWith("retry")) events += block.toString().trim()
                                block.clear()
                            } else {
                                block.append(line).append('\n')
                            }
                        }
                    }
                }
            }
        }

        fun next(): String = assertNotNull(events.poll(5, TimeUnit.SECONDS), "olay gelmedi")
        fun none() = assertNull(events.poll(700, TimeUnit.MILLISECONDS), "beklenmeyen olay")

        /** Sıradaki kayıt olayı: (kimlik, veri). */
        fun record(): Pair<String, JsonObject> {
            val e = next()
            val id = e.lines().first { it.startsWith("id: ") }.removePrefix("id: ")
            return id to json(e.lines().first { it.startsWith("data: ") }.removePrefix("data: "))
        }
    }

    private fun JsonObject.str(k: String) = getValue(k).jsonPrimitive.content

    @Test
    fun `olusturma, guncelleme, silme yayinlanir`() {
        start()
        val s = Stream("/api/realtime?collections=todos", key)
        assertEquals(200, s.status)
        assertTrue(s.next().startsWith("event: ready"))

        val (_, created) = request("POST", "/api/collections/todos/records", null, """{"title":"süt al"}""")
        val id = json(created).str("id")
        val (_, c) = s.record()
        assertEquals("create", c.str("action"))
        assertEquals("todos", c.str("collection"))
        assertEquals("süt al", c.getValue("record").jsonObject.str("title"))

        request("PATCH", "/api/collections/todos/records/$id", null, """{"title":"ekmek al"}""")
        assertEquals("ekmek al", s.record().second.getValue("record").jsonObject.str("title"))

        request("DELETE", "/api/collections/todos/records/$id", null)
        val (_, d) = s.record()
        assertEquals("delete", d.str("action"))
        assertEquals(setOf("id"), d.getValue("record").jsonObject.keys, "silmede yalnızca kimlik")

        // Abone olunmayan koleksiyon gelmez.
        request("POST", "/api/collections/secret/records", key, """{"x":"y"}""")
        s.none()
    }

    @Test
    fun `kurallar her olayda uygulanir, token sorgu parametresiyle de verilir`() {
        start()
        val (a, _) = signup("a@example.com")
        val (b, _) = signup("b@example.com")
        assertEquals(401, Stream("/api/realtime?collections=notes").status, "anonim, owner kuralı")
        assertEquals(403, Stream("/api/realtime?collections=secret&token=$a").status, "yalnızca yönetici koleksiyonu")
        assertEquals(404, Stream("/api/realtime?collections=yok", key).status)
        assertEquals(400, Stream("/api/realtime", key).status)

        val sa = Stream("/api/realtime?collections=notes&token=$a") // EventSource gibi: başlık yok
        assertEquals(200, sa.status)
        sa.next() // ready
        val admin = Stream("/api/realtime?collections=notes", key)
        admin.next()

        request("POST", "/api/collections/notes/records", b, """{"body":"b'nin notu"}""")
        assertEquals("b'nin notu", admin.record().second.getValue("record").jsonObject.str("body"), "yönetici hepsini görür")
        sa.none()
        request("POST", "/api/collections/notes/records", a, """{"body":"a'nın notu"}""")
        assertEquals("a'nın notu", sa.record().second.getValue("record").jsonObject.str("body"))
    }

    @Test
    fun `yeniden baglanan istemci kacirdiklarini alir, eski kimlikte reset`() {
        start()
        val s = Stream("/api/realtime?collections=todos", key)
        s.next()
        request("POST", "/api/collections/todos/records", null, """{"title":"bir"}""")
        val (lastId, _) = s.record()
        streams.last().disconnect()

        request("POST", "/api/collections/todos/records", null, """{"title":"iki"}""")
        request("POST", "/api/collections/todos/records", null, """{"title":"üç"}""")
        val again = Stream("/api/realtime?collections=todos", key, lastEventId = lastId)
        assertEquals("iki", again.record().second.getValue("record").jsonObject.str("title"))
        assertEquals("üç", again.record().second.getValue("record").jsonObject.str("title"))
        assertTrue(again.next().startsWith("event: ready"))

        val stale = Stream("/api/realtime?collections=todos", key, lastEventId = "eski-5")
        assertTrue(stale.next().startsWith("event: reset"), "başka sunucu örneğinin kimliği")
    }

    @Test
    fun `kapaliyken yok, baglanti siniri`() {
        start(config(RealtimeConfig(enabled = true, maxConnections = 1)))
        val first = Stream("/api/realtime?collections=todos", key)
        assertEquals(200, first.status)
        assertEquals(503, Stream("/api/realtime?collections=todos", key).status)
        server?.stop(100, 500)
        start(config(RealtimeConfig()))
        assertEquals(404, Stream("/api/realtime?collections=todos", key).status)
    }
}
