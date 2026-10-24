package com.plyg.droidserver.core

import com.plyg.droidserver.core.ai.AiChat
import com.plyg.droidserver.core.ai.AiHost
import com.plyg.droidserver.core.ai.AiMessage
import com.plyg.droidserver.core.ai.AiReply
import com.plyg.droidserver.core.ai.ClaudeChat
import com.plyg.droidserver.core.ai.GeminiChat
import com.plyg.droidserver.core.ai.OpenAiChat
import com.plyg.droidserver.core.api.ApiException
import com.plyg.droidserver.core.ai.Embedder
import com.plyg.droidserver.core.ai.SemanticIndex
import com.plyg.droidserver.core.api.RecordService
import com.plyg.droidserver.core.api.droidServerModule
import com.plyg.droidserver.core.config.AiAccess
import com.plyg.droidserver.core.config.AiConfig
import com.plyg.droidserver.core.config.AiEffort
import com.plyg.droidserver.core.config.AiModel
import com.plyg.droidserver.core.config.AiProvider
import com.plyg.droidserver.core.config.AuthConfig
import com.plyg.droidserver.core.config.AuthMode
import com.plyg.droidserver.core.config.CollectionConfig
import com.plyg.droidserver.core.config.ConfigSecrets
import com.plyg.droidserver.core.config.ConfigValidator
import com.plyg.droidserver.core.config.EmbeddingProvider
import com.plyg.droidserver.core.config.EmbeddingsConfig
import com.plyg.droidserver.core.config.FieldConfig
import com.plyg.droidserver.core.config.FieldType
import com.plyg.droidserver.core.config.FieldVisibility
import com.plyg.droidserver.core.config.Rule
import com.plyg.droidserver.core.config.Rules
import com.plyg.droidserver.core.config.SemanticIndexConfig
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.config.UsersConfig
import com.plyg.droidserver.core.db.SchemaMigrator
import com.plyg.droidserver.core.db.SqlDriver
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** YZ: Claude vekili, günlük sınır, erişim, kayıtlara soru ve anlamsal arama (sahte sağlayıcılarla). */
class AiTest {
    private val key = "yonetici-anahtari-1234567890"
    private val notes = CollectionConfig(
        "notes",
        listOf(
            FieldConfig("title", FieldType.TEXT, required = true),
            FieldConfig("body", FieldType.TEXT),
            FieldConfig("tag", FieldType.SELECT, options = listOf("ev", "iş")),
            FieldConfig("secret", FieldType.TEXT, visibility = FieldVisibility.ADMIN),
        ),
        // Giriş yapan herkes okur; gizli alanı yalnızca yönetici görür.
        rules = Rules(list = Rule.AUTH, view = Rule.AUTH),
    )
    private val ai = AiConfig(
        enabled = true, apiKey = "sk-ant-deneme", dailyLimit = 3, system = "Kısa yanıtla.",
        embeddings = EmbeddingsConfig(EmbeddingProvider.OPENAI, url = "http://127.0.0.1:1/v1"),
        semantic = listOf(SemanticIndexConfig("notes", listOf("title", "body", "tag"))),
    )
    private val config = ServerConfig(
        auth = AuthConfig(AuthMode.API_KEY, apiKeys = listOf(key), users = UsersConfig(enabled = true)),
        collections = listOf(notes),
        ai = ai,
    )
    private lateinit var db: SqlDriver

    /** Gönderilen istekleri saklar; yanıt son iletinin uzunluğunu söyler. */
    private val requests = mutableListOf<Pair<String, List<AiMessage>>>()
    private var refuse = false
    private val chat = AiChat { system, messages ->
        requests += system to messages
        if (refuse) AiReply("", "refusal", "claude-opus-5-5", 10, 0)
        else AiReply("yanıt: " + messages.last().content.takeLast(20), "end_turn", "claude-opus-5-5", 120, 30)
    }

    /** Anlamı kaba taklit eden gömme: kelimeler kavram gruplarına sayılır (eş anlamlılar aynı boyuta düşer). */
    private var embedCalls = 0
    private val groups = listOf(
        setOf("elma", "armut", "meyve", "yemek", "kahvaltı", "market"),
        setOf("araba", "otomobil", "taşıt", "lastik", "servis", "benzin"),
        setOf("kedi", "köpek", "hayvan", "veteriner", "mama"),
    )
    private val embedder = Embedder { texts, _ ->
        embedCalls++
        texts.map { t ->
            val words = t.lowercase().split(Regex("[^\\p{L}]+"))
            FloatArray(groups.size + 1) { i -> if (i < groups.size) words.count { it in groups[i] }.toFloat() else 0.1f }
        }
    }

    @BeforeTest
    fun open() {
        db = TestDb.create(Dialect.SQLITE).open()
        SchemaMigrator(db).migrate(config.schemaCollections())
    }

    @AfterTest
    fun close() = db.close()

    private suspend fun HttpClient.send(path: String, token: String?, body: String? = null): HttpResponse =
        if (body == null) get(path) { token?.let { bearerAuth(it) } }
        else post(path) { token?.let { bearerAuth(it) }; contentType(ContentType.Application.Json); setBody(body) }

    private suspend fun HttpResponse.obj() = Json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun HttpClient.signup(email: String): String {
        val r = send("/api/auth/signup", null, """{"email":"$email","password":"parola-123"}""")
        assertEquals(HttpStatusCode.Created, r.status, r.bodyAsText())
        return r.obj().getValue("token").jsonPrimitive.content
    }

    @Test
    fun `dogrulama ve sirlar`() {
        assertEquals(emptyList(), ConfigValidator.validate(config))
        val bad = config.copy(
            ai = ai.copy(
                apiKey = "", model = "gpt-4", dailyLimit = -1, embeddings = EmbeddingsConfig(EmbeddingProvider.VOYAGE),
                semantic = listOf(SemanticIndexConfig("notes", listOf("secret", "yok")), SemanticIndexConfig("notes", emptyList()), SemanticIndexConfig("bilinmeyen", listOf("x"))),
            ),
        )
        val errors = ConfigValidator.validate(bad).joinToString("\n")
        listOf("API anahtarı girin", "claude-", "Günlük sınır", "Voyage AI anahtarı", "görünürlüğü kısıtlı", "'yok' alanı yok", "iki kez", "en az bir alan", "'bilinmeyen' koleksiyonu yok")
            .forEach { assertTrue(it in errors, "$it bekleniyordu:\n$errors") }
        assertTrue("gömme servisini" in ConfigValidator.validate(config.copy(ai = ai.copy(embeddings = null))).joinToString())

        // Anahtarlar kaydedilirken şifreli depoya taşınır, deploy'da çözülür.
        val stored = mutableMapOf<String, String>()
        val text = config.copy(ai = ai.copy(embeddings = ai.embeddings!!.copy(apiKey = "voyage-gizli"))).toJson()
        val secured = ConfigSecrets.secureText(text) { kind, v -> kind.also { stored[it] = v } }!!
        assertFalse("sk-ant-deneme" in secured || "voyage-gizli" in secured, secured)
        assertEquals(mapOf("ai_api_key" to "sk-ant-deneme", "embeddings_key" to "voyage-gizli"), stored.filterKeys { it != "api_key" })
        val resolved = ConfigSecrets.resolve(ServerConfig.fromJson(secured)) { stored[it] }
        assertEquals("sk-ant-deneme", resolved.ai.apiKey)
        assertEquals("voyage-gizli", resolved.ai.embeddings!!.apiKey)
    }

    @Test
    fun `sohbet vekili - erisim, gunluk sinir, ret`() = testApplication {
        val host = AiHost()
        application { droidServerModule(config, db, aiHost = host, aiChat = chat, embedder = embedder) }
        val user = client.signup("a@example.com")

        assertEquals(HttpStatusCode.Unauthorized, client.send("/api/ai/chat", null, """{"prompt":"merhaba"}""").status)
        assertEquals(HttpStatusCode.BadRequest, client.send("/api/ai/chat", user, """{"messages":[{"role":"system","content":"x"}]}""").status)
        assertEquals(HttpStatusCode.BadRequest, client.send("/api/ai/chat", user, """{"messages":[{"role":"user","content":"a"},{"role":"assistant","content":"b"}]}""").status)

        val r = client.send("/api/ai/chat", user, """{"messages":[{"role":"user","content":"selam"},{"role":"assistant","content":"merhaba"},{"role":"user","content":"nasılsın"}]}""")
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        val body = r.obj()
        assertEquals("yanıt: nasılsın", body.getValue("text").jsonPrimitive.content)
        assertEquals("2", body.getValue("remaining").jsonPrimitive.content)
        assertEquals("Kısa yanıtla.", requests.last().first)
        assertEquals(listOf("user", "assistant", "user"), requests.last().second.map { it.role })

        client.send("/api/ai/chat", user, """{"prompt":"iki"}""")
        client.send("/api/ai/chat", user, """{"prompt":"üç"}""")
        val limited = client.send("/api/ai/chat", user, """{"prompt":"dört"}""")
        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
        // Başka kullanıcının hakkı ayrı; yönetici sınırsız.
        assertEquals(HttpStatusCode.OK, client.send("/api/ai/chat", client.signup("b@example.com"), """{"prompt":"x"}""").status)
        repeat(4) { assertEquals(HttpStatusCode.OK, client.send("/api/ai/chat", key, """{"prompt":"y"}""").status) }

        refuse = true
        val refused = client.send("/api/ai/chat", key, """{"prompt":"z"}""").obj()
        assertEquals("true", refused.getValue("refused").jsonPrimitive.content)
        assertEquals("refusal", refused.getValue("stopReason").jsonPrimitive.content)

        val status = client.send("/api/_ai", key).obj()
        assertEquals(9, status.getValue("today").jsonObject.getValue("requests").jsonPrimitive.content.toInt())
        assertEquals(8 * 120 + 10, status.getValue("today").jsonObject.getValue("inputTokens").jsonPrimitive.content.toInt())
        assertEquals(HttpStatusCode.Forbidden, client.send("/api/_ai", user).status)
    }

    @Test
    fun `yalnizca yonetici ve kapaliyken uc yok`() = testApplication {
        application { droidServerModule(config.copy(ai = ai.copy(access = AiAccess.ADMIN, semantic = emptyList())), db, aiChat = chat) }
        assertEquals(HttpStatusCode.Forbidden, client.send("/api/ai/chat", client.signup("a@example.com"), """{"prompt":"x"}""").status)
        assertEquals(HttpStatusCode.OK, client.send("/api/ai/chat", key, """{"prompt":"x"}""").status)
        // Dizin yokken anlamsal arama 400; soru-cevap en yeni kayıtlarla çalışır.
        assertEquals(HttpStatusCode.BadRequest, client.send("/api/ai/search/notes?q=araba", key).status)
    }

    @Test
    fun `kapali`() = testApplication {
        application { droidServerModule(config.copy(ai = AiConfig()), db, aiChat = chat) }
        assertEquals(HttpStatusCode.NotFound, client.send("/api/ai/chat", key, """{"prompt":"x"}""").status)
    }

    @Test
    fun `anlamsal arama ve kayitlara soru`() = testApplication {
        // Sunucu açılmadan önce var olan kayıtlar açılışta dizinlenir.
        val svc = RecordService(db, notes, collections = config.schemaCollections())
        val car = svc.create(JsonObject(mapOf("title" to JsonPrimitive("Kırmızı otomobil"), "body" to JsonPrimitive("lastik değişecek, servis randevusu"), "tag" to JsonPrimitive("iş"))))
        svc.create(JsonObject(mapOf("title" to JsonPrimitive("Alışveriş"), "body" to JsonPrimitive("elma armut market"), "tag" to JsonPrimitive("ev"), "secret" to JsonPrimitive("kasa şifresi"))))
        val host = AiHost()
        application { droidServerModule(config, db, aiHost = host, aiChat = chat, embedder = embedder) }
        val user = client.signup("a@example.com")
        client.send("/api/collections/notes/records", key, """{"title":"Veteriner","body":"kedi aşısı ve mama","tag":"ev"}""")

        assertEquals(null, host.index!!.lastError)
        assertEquals(listOf(3), host.index!!.status().map { it.indexed })
        // Kelime geçmese de anlamca yakın olan önce gelir.
        val found = client.send("/api/ai/search/notes?q=taşıt", user).obj().getValue("items").jsonArray.map { it.jsonObject }
        assertEquals("Kırmızı otomobil", found.first().getValue("title").jsonPrimitive.content)
        assertTrue(found.first().getValue("_score").jsonPrimitive.content.toDouble() > 0.9)
        // Görünürlüğü kısıtlı alan yanıtta yok; filtreler uygulanır.
        assertTrue(found.none { "secret" in it })
        val filtered = client.send("/api/ai/search/notes?q=taşıt&tag=ev&limit=1", user).obj().getValue("items").jsonArray
        assertEquals(1, filtered.size)
        assertEquals("ev", filtered.single().jsonObject.getValue("tag").jsonPrimitive.content)
        assertEquals(HttpStatusCode.Unauthorized, client.send("/api/ai/search/notes?q=taşıt", null).status)
        assertEquals(HttpStatusCode.BadRequest, client.send("/api/ai/search/notes", user).status)

        // Değişmeyen kayıt yeniden gömülmez; değişen ve silinen dizine yansır.
        val calls = embedCalls
        client.send("/api/collections/notes/records/${car.getValue("id")}", key)
        assertEquals(calls, embedCalls)
        val patched = client.patch("/api/collections/notes/records/${car.getValue("id")}") {
            bearerAuth(key); contentType(ContentType.Application.Json); setBody("""{"body":"köpek maması"}""")
        }
        assertEquals(HttpStatusCode.OK, patched.status, patched.bodyAsText())
        assertEquals(calls + 1, embedCalls)
        val animals = client.send("/api/ai/search/notes?q=hayvan", user).obj().getValue("items").jsonArray.map { it.jsonObject.getValue("title").jsonPrimitive.content }
        assertEquals(listOf("Veteriner", "Kırmızı otomobil", "Alışveriş"), animals)

        // Soru: anlamca en yakın kayıtlar <records> içinde, kaynak kimlikleriyle; gizli alan YZ'ye de gitmez.
        val asked = client.send("/api/ai/ask/notes?limit=2", user, """{"question":"Meyve için ne almalıyım?","limit":1}""").obj()
        assertEquals(1, asked.getValue("sources").jsonArray.size)
        val prompt = requests.last().second.single().content
        assertTrue("<records collection=\"notes\">" in prompt && "Alışveriş" in prompt && "Meyve için ne almalıyım?" in prompt, prompt)
        assertFalse("kasa şifresi" in prompt, prompt)
        assertTrue("not instructions" in requests.last().first && requests.last().first.startsWith("Kısa yanıtla."))

        // Sıralama istenirse dizin kullanılmaz: en yeniler.
        client.send("/api/ai/ask/notes?sort=-id", key, """{"question":"son not?","limit":1}""")
        assertTrue("Veteriner" in requests.last().second.single().content)

        val del = client.delete("/api/collections/notes/records/${car.getValue("id")}") { bearerAuth(key) }
        assertEquals(HttpStatusCode.NoContent, del.status, del.bodyAsText())
        assertEquals(listOf(2), host.index!!.status().map { it.indexed })
        assertEquals(2L, db.query("SELECT COUNT(*) AS n FROM ${SemanticIndex.TABLE}").single()["n"].let { (it as Number).toLong() })
    }

    @Test
    fun `Claude istegi - model, efor, geri dusus ve yanit`() {
        val seen = mutableListOf<Pair<Map<String, String>, JsonObject>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/messages") { ex ->
            seen += ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") } to
                Json.parseToJsonElement(ex.requestBody.readBytes().decodeToString()).jsonObject
            val body = """{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5",
                "content":[{"type":"thinking","thinking":"","signature":"x"},{"type":"text","text":"Merhaba!"}],
                "stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":12,"output_tokens":5}}""".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}"
            ClaudeChat("sk-ant-deneme", ai.copy(effort = AiEffort.LOW, maxTokens = 2000), url).use { c ->
                val r = c.complete("Kısa yanıtla.", listOf(AiMessage("user", "selam"), AiMessage("assistant", "merhaba"), AiMessage("user", "nasılsın")))
                assertEquals(AiReply("Merhaba!", "end_turn", "claude-opus-5-5", 12, 5), r)
            }
            ClaudeChat("k", ai.copy(model = AiModel.HAIKU.id), url).use { it.complete("", listOf(AiMessage("user", "x"))) }
        } finally {
            server.stop(0)
        }
        val (headers, body) = seen.first()
        assertEquals("sk-ant-deneme", headers["x-api-key"])
        assertTrue(ClaudeChat.FALLBACK_BETA in headers["anthropic-beta"].orEmpty(), headers.toString())
        assertEquals("claude-opus-5-5", body.getValue("model").jsonPrimitive.content)
        assertEquals("2000", body.getValue("max_tokens").toString())
        assertEquals("\"default\"", body.getValue("fallbacks").toString())
        assertEquals("\"low\"", body.getValue("output_config").jsonObject.getValue("effort").toString())
        assertEquals(listOf("user", "assistant", "user"), body.getValue("messages").jsonArray.map { it.jsonObject.getValue("role").jsonPrimitive.content })
        assertTrue("Kısa yanıtla." in body.getValue("system").toString())
        // Haiku'da sunucu tarafı geri düşüş yok: parametre ve başlık gönderilmez.
        val (haikuHeaders, haiku) = seen[1]
        assertFalse("fallbacks" in haiku || "system" in haiku, haiku.toString())
        assertFalse(ClaudeChat.FALLBACK_BETA in haikuHeaders["anthropic-beta"].orEmpty())
    }

    /** Yerel sahte sağlayıcı: gelen istekleri saklar, sırayla verilen yanıtları döndürür. */
    private class FakeProvider(vararg responses: Pair<Int, String>) : AutoCloseable {
        val seen = mutableListOf<Triple<String, Map<String, String>, JsonObject>>()
        private val queue = ArrayDeque(responses.toList())
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                seen += Triple(
                    ex.requestURI.path, ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") },
                    Json.parseToJsonElement(ex.requestBody.readBytes().decodeToString()).jsonObject,
                )
                val (status, body) = queue.removeFirst()
                val bytes = body.toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(status, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }
        val url = "http://127.0.0.1:${server.address.port}"
        override fun close() = server.stop(0)
    }

    private val chatTurns = listOf(AiMessage("user", "selam"), AiMessage("assistant", "merhaba"), AiMessage("user", "nasılsın"))

    @Test
    fun `OpenAI istegi - resmi uc, ret ve uyumlu servis`() {
        val openai = ai.copy(provider = AiProvider.OPENAI, apiKey = "sk-openai", model = "gpt-6-astra", effort = AiEffort.HIGH, maxTokens = 1500)
        FakeProvider(
            200 to """{"model":"gpt-6-astra-2026","choices":[{"message":{"role":"assistant","content":"İyiyim!","refusal":null},"finish_reason":"stop"}],
                "usage":{"prompt_tokens":20,"completion_tokens":7}}""",
            200 to """{"model":"gpt-6-astra","choices":[{"message":{"role":"assistant","content":null,"refusal":"Yardımcı olamam."},"finish_reason":"stop"}],"usage":{}}""",
            401 to """{"error":{"message":"Incorrect API key provided","type":"invalid_request_error"}}""",
        ).use { f ->
            val chat = OpenAiChat(openai, f.url + "/v1")
            assertEquals(AiReply("İyiyim!", "end_turn", "gpt-6-astra-2026", 20, 7), chat.complete("Kısa yanıtla.", chatTurns))
            val refused = chat.complete("", listOf(AiMessage("user", "x")))
            assertTrue(refused.refused)
            assertEquals("Yardımcı olamam.", refused.text)
            val e = assertFailsWith<ApiException> { chat.complete("", listOf(AiMessage("user", "x"))) }
            assertEquals(502, e.status)
            assertTrue("Incorrect API key" in e.message!! && "sk-openai" !in e.message!!, e.message)

            val (path, headers, body) = f.seen.first()
            assertEquals("/v1/chat/completions", path)
            assertEquals("Bearer sk-openai", headers["authorization"])
            assertEquals("gpt-6-astra", body.getValue("model").jsonPrimitive.content)
            assertEquals("1500", body.getValue("max_completion_tokens").toString())
            assertEquals("high", body.getValue("reasoning_effort").jsonPrimitive.content)
            assertEquals(listOf("developer", "user", "assistant", "user"), body.getValue("messages").jsonArray.map { it.jsonObject.getValue("role").jsonPrimitive.content })
            assertFalse("system" in f.seen[1].third.getValue("messages").toString(), "boş sistem talimatı gönderilmez")
        }
        // OpenAI uyumlu yerel servis (Ollama): anahtarsız, ortak parametreler.
        FakeProvider(200 to """{"choices":[{"message":{"content":"tamam"},"finish_reason":"length"}]}""").use { f ->
            val local = openai.copy(apiKey = "", url = f.url + "/v1", model = "llama3.2:3b")
            assertEquals(emptyList(), ConfigValidator.validate(config.copy(ai = local)))
            val r = AiChat.of(local).complete("Kısa yanıtla.", listOf(AiMessage("user", "x")))
            assertEquals(AiReply("tamam", "max_tokens", "llama3.2:3b", 0, 0), r)
            val (_, headers, body) = f.seen.single()
            assertFalse("authorization" in headers)
            assertEquals("system", body.getValue("messages").jsonArray.first().jsonObject.getValue("role").jsonPrimitive.content)
            assertTrue("max_tokens" in body && "reasoning_effort" !in body && "max_completion_tokens" !in body, body.toString())
        }
    }

    @Test
    fun `Gemini istegi - dusunme duzeyi, dusunce parcalari ve ret`() {
        val gemini = ai.copy(provider = AiProvider.GEMINI, apiKey = "AIza-gizli", effort = AiEffort.LOW, maxTokens = 3000)
        assertEquals(emptyList(), ConfigValidator.validate(config.copy(ai = gemini)))
        FakeProvider(
            200 to """{"candidates":[{"content":{"role":"model","parts":[{"text":"düşünüyorum","thought":true},{"text":"İyi"},{"text":"yim!"}]},"finishReason":"STOP"}],
                "usageMetadata":{"promptTokenCount":15,"candidatesTokenCount":4,"thoughtsTokenCount":6},"modelVersion":"gemini-3.8-flash"}""",
            200 to """{"candidates":[{"content":{"parts":[]},"finishReason":"SAFETY"}],"usageMetadata":{"promptTokenCount":3}}""",
            200 to """{"promptFeedback":{"blockReason":"PROHIBITED_CONTENT"},"usageMetadata":{"promptTokenCount":3}}""",
        ).use { f ->
            val chat = GeminiChat(gemini, f.url + "/v1beta")
            assertEquals(AiReply("İyiyim!", "end_turn", "gemini-3.8-flash", 15, 10), chat.complete("Kısa yanıtla.", chatTurns))
            assertTrue(chat.complete("", listOf(AiMessage("user", "x"))).refused)
            assertTrue(chat.complete("", listOf(AiMessage("user", "x"))).refused)

            val (path, headers, body) = f.seen.first()
            assertEquals("/v1beta/models/gemini-3.8-flash:generateContent", path)
            assertEquals("AIza-gizli", headers["x-goog-api-key"])
            assertTrue("AIza" !in path)
            assertEquals(listOf("user", "model", "user"), body.getValue("contents").jsonArray.map { it.jsonObject.getValue("role").jsonPrimitive.content })
            assertTrue("Kısa yanıtla." in body.getValue("systemInstruction").toString())
            val gen = body.getValue("generationConfig").jsonObject
            assertEquals("3000", gen.getValue("maxOutputTokens").toString())
            assertEquals("low", gen.getValue("thinkingConfig").jsonObject.getValue("thinkingLevel").jsonPrimitive.content)
        }
        // Model öneki ve adres kuralları.
        val errors = ConfigValidator.validate(config.copy(ai = gemini.copy(model = "gpt-6-luna", url = "http://x"))).joinToString()
        assertTrue("gemini-" in errors && "yalnızca OpenAI" in errors, errors)
    }

    @Test
    fun `vektor kodlama`() {
        val v = SemanticIndex.normalize(floatArrayOf(3f, 4f))
        assertEquals(1f, SemanticIndex.dot(v, v), 1e-6f)
        assertTrue(SemanticIndex.decode(SemanticIndex.encode(v)).contentEquals(v))
    }
}
