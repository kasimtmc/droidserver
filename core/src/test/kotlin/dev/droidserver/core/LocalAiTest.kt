package dev.droidserver.core

import dev.droidserver.core.ai.AiChat
import dev.droidserver.core.ai.AiReply
import dev.droidserver.core.ai.Embedder
import dev.droidserver.core.ai.LocalAi
import dev.droidserver.core.api.RecordService
import dev.droidserver.core.api.WriteGate
import dev.droidserver.core.api.droidServerModule
import dev.droidserver.core.automation.AutomationEngine
import dev.droidserver.core.automation.AutomationOutputs
import dev.droidserver.core.config.AiConfig
import dev.droidserver.core.config.AiProvider
import dev.droidserver.core.config.AuthConfig
import dev.droidserver.core.config.AuthMode
import dev.droidserver.core.config.AutomationAction
import dev.droidserver.core.config.AutomationActionType
import dev.droidserver.core.config.AutomationConfig
import dev.droidserver.core.config.AutomationTrigger
import dev.droidserver.core.config.CollectionConfig
import dev.droidserver.core.config.ConfigValidator
import dev.droidserver.core.config.EmbeddingProvider
import dev.droidserver.core.config.EmbeddingsConfig
import dev.droidserver.core.config.FieldConfig
import dev.droidserver.core.config.FieldType
import dev.droidserver.core.config.SemanticIndexConfig
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.db.SchemaMigrator
import dev.droidserver.core.db.SqlDriver
import dev.droidserver.core.realtime.RecordEvent
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Faz 18.7: yerel sağlayıcı (uygulamanın LocalAi'si), yerel gömme ve otomasyonlarda YZ adımı. */
class LocalAiTest {
    private val key = "yonetici-anahtari-1234567890"
    private val notes = CollectionConfig(
        "notes",
        listOf(
            FieldConfig("body", FieldType.TEXT, required = true),
            FieldConfig("summary", FieldType.TEXT, maxLength = 20),
            FieldConfig("priority", FieldType.SELECT, options = listOf("düşük", "yüksek")),
            FieldConfig("labels", FieldType.MULTISELECT, options = listOf("ev", "iş", "acil")),
        ),
    )
    private val ai = AiConfig(
        enabled = true, provider = AiProvider.LOCAL, model = "qwen2.5-0.5b-instruct-q4_k_m",
        embeddings = EmbeddingsConfig(EmbeddingProvider.LOCAL, model = "all-minilm-l6-v2-q8_0"),
        semantic = listOf(SemanticIndexConfig("notes", listOf("body"))),
    )
    private val config = ServerConfig(auth = AuthConfig(AuthMode.API_KEY, apiKeys = listOf(key)), collections = listOf(notes), ai = ai)
    private lateinit var db: SqlDriver

    @Before
    fun open() {
        db = TestDb.create(Dialect.SQLITE).open()
        SchemaMigrator(db).migrate(config.schemaCollections())
    }

    @After
    fun close() = db.close()

    private class FakeLocal : LocalAi {
        val asked = mutableListOf<String>()
        override fun chat(model: String, config: AiConfig): AiChat = AiChat { _, messages ->
            asked += model
            AiReply("yerel: " + messages.last().content, "end_turn", model, 5, 3)
        }
        override fun embedder(model: String): Embedder = Embedder { texts, _ ->
            asked += model
            texts.map { t -> FloatArray(2) { i -> if (i == 0) (if ("araba" in t || "taşıt" in t) 1f else 0f) else 0.1f } }
        }
    }

    @Test
    fun `yerel saglayici ve gomme uygulamadan gelir`() = testApplication {
        val local = FakeLocal()
        application { droidServerModule(config, db, localAi = local) }
        val r = client.post("/api/ai/chat") { bearerAuth(key); contentType(ContentType.Application.Json); setBody("""{"messages":[{"role":"user","content":"merhaba"}]}""") }
        assertEquals(200, r.status.value, r.bodyAsText())
        assertTrue("yerel: merhaba" in r.bodyAsText())
        client.post("/api/collections/notes/records") { bearerAuth(key); contentType(ContentType.Application.Json); setBody("""{"body":"araba lastiği"}""") }
        client.post("/api/collections/notes/records") { bearerAuth(key); contentType(ContentType.Application.Json); setBody("""{"body":"elma"}""") }
        val found = Json.parseToJsonElement(client.get("/api/ai/search/notes?q=taşıt") { bearerAuth(key) }.bodyAsText()).jsonObject
        assertEquals("araba lastiği", found.getValue("items").jsonArray.first().jsonObject.getValue("body").jsonPrimitive.content)
        assertTrue("qwen2.5-0.5b-instruct-q4_k_m" in local.asked && "all-minilm-l6-v2-q8_0" in local.asked)
    }

    @Test
    fun `yerel ortam yoksa 503`() = testApplication {
        application { droidServerModule(config.copy(ai = ai.copy(embeddings = null, semantic = emptyList())), db) }
        val r = client.post("/api/ai/chat") { bearerAuth(key); contentType(ContentType.Application.Json); setBody("""{"messages":[{"role":"user","content":"merhaba"}]}""") }
        assertEquals(503, r.status.value, r.bodyAsText())
    }

    @Test
    fun `otomasyonda yz adimi ve dogrulama`() {
        val services = mapOf("notes" to RecordService(db, notes, collections = config.schemaCollections()))
        val replies = ArrayDeque(listOf("Kısa bir özet metni ve devamı uzun", "Yüksek.", "acil, iş"))
        val prompts = mutableListOf<Pair<String, String>>()
        fun rule(field: String, prompt: String) = AutomationConfig(
            "yz-$field", AutomationTrigger(collection = "notes", events = listOf("create")),
            actions = listOf(AutomationAction(AutomationActionType.AI, body = prompt, field = field)),
        )
        val engine = AutomationEngine(
            listOf(rule("summary", "Özetle: {body}"), rule("priority", "Öncelik? {body}"), rule("labels", "Etiketler? {body}")),
            config.collections, services, WriteGate(),
            AutomationOutputs(ai = { system, prompt -> prompts += system to prompt; replies.removeFirst() }),
            changed = { _, _, _, _ -> }, synchronous = true,
        )
        val rec = services.getValue("notes").create(JsonObject(mapOf("body" to JsonPrimitive("Sunucu çöktü, hemen bak"))))
        engine.onRecord("notes", RecordEvent.Action.CREATE, rec)
        val after = services.getValue("notes").get((rec["id"] as JsonPrimitive).content.toLong())
        assertEquals("Kısa bir özet metni ", after.getValue("summary").jsonPrimitive.content, "uzunluk sınırına kırpılır")
        assertEquals("yüksek", after.getValue("priority").jsonPrimitive.content, "seçeneklerden biri (harf ve nokta yok sayılır)")
        assertEquals(listOf("iş", "acil"), after.getValue("labels").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("Özetle: Sunucu çöktü, hemen bak", prompts.first().second)
        assertTrue("düşük, yüksek" in prompts[1].first, prompts[1].first)
        assertTrue(engine.runs().all { it.ok }, engine.runs().toString())

        // Doğrulama: model kimliği, yerel gömme modeli, YZ adımının alanı ve tetikleyicisi.
        val bad = config.copy(
            ai = ai.copy(model = "Kötü Model", embeddings = EmbeddingsConfig(EmbeddingProvider.LOCAL)),
            automations = listOf(
                AutomationConfig("a", AutomationTrigger(collection = "notes"), actions = listOf(AutomationAction(AutomationActionType.AI, body = "x", field = "yok"))),
                AutomationConfig("b", AutomationTrigger(schedule = "0 3 * * *"), actions = listOf(AutomationAction(AutomationActionType.AI, body = "x", field = "summary"))),
            ),
        )
        val errors = ConfigValidator.validateDetailed(bad).map { it.path }
        for (p in listOf("ai.model", "ai.embeddings.model", "automations[0].actions[0].field", "automations[1].actions[0]")) assertTrue(p in errors, "$p yok: $errors")
        assertTrue(ConfigValidator.validateDetailed(config).none { it.path.startsWith("ai") }, "yerel sağlayıcıda anahtar gerekmez")
    }
}
