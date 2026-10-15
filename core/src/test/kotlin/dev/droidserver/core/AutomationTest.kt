package dev.droidserver.core

import dev.droidserver.core.api.RecordService
import dev.droidserver.core.api.droidServerModule
import dev.droidserver.core.api.WriteGate
import dev.droidserver.core.automation.AutomationEngine
import dev.droidserver.core.automation.AutomationOutputs
import dev.droidserver.core.config.AutomationAction
import dev.droidserver.core.config.AutomationActionType
import dev.droidserver.core.config.AutomationConfig
import dev.droidserver.core.config.AutomationTrigger
import dev.droidserver.core.config.AuthConfig
import dev.droidserver.core.config.AuthMode
import dev.droidserver.core.config.CollectionConfig
import dev.droidserver.core.config.ConfigValidator
import dev.droidserver.core.config.EmailConfig
import dev.droidserver.core.config.FieldConfig
import dev.droidserver.core.config.FieldType
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.config.WebhookConfig
import dev.droidserver.core.db.SchemaMigrator
import dev.droidserver.core.db.SqlDriver
import dev.droidserver.core.realtime.RecordEvent
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import java.time.Instant
import java.time.LocalDateTime
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Otomasyon kuralları: kayıt olayı ve zamanlama tetikleyicileri, eylemler, döngü koruması, doğrulama. */
class AutomationTest {
    private val now = Instant.parse("2026-10-15T08:00:00Z")
    private val orders = CollectionConfig(
        "orders",
        listOf(
            FieldConfig("item", FieldType.TEXT),
            FieldConfig("email", FieldType.TEXT),
            FieldConfig("status", FieldType.TEXT),
            FieldConfig("qty", FieldType.INTEGER),
            FieldConfig("seen", FieldType.BOOLEAN),
            FieldConfig("touched", FieldType.TEXT),
        ),
    )
    private val events = CollectionConfig("events", listOf(FieldConfig("text", FieldType.TEXT), FieldConfig("order", FieldType.INTEGER)))
    private val base = ServerConfig(collections = listOf(orders, events))
    private lateinit var db: SqlDriver
    private lateinit var services: Map<String, RecordService>
    private val mails = mutableListOf<Triple<String, String, String>>()
    private val hooks = mutableListOf<Pair<String, String>>()
    private val changes = mutableListOf<String>()

    @Before
    fun open() {
        db = TestDb.create(Dialect.SQLITE).open()
        SchemaMigrator(db).migrate(base.schemaCollections())
        services = base.collections.associate { it.name to RecordService(db, it, clock = { now }, collections = base.schemaCollections()) }
    }

    @After
    fun close() = db.close()

    private fun engine(vararg rules: AutomationConfig): AutomationEngine {
        lateinit var engine: AutomationEngine
        engine = AutomationEngine(
            rules.toList(), base.collections, services, WriteGate(),
            AutomationOutputs(
                mail = { to, subject, text -> mails += Triple(to, subject, text) },
                webhook = { name, event, _, _ -> hooks += name to event },
            ),
            changed = { c, a, r, depth ->
                changes += "$c.${a.wire}#${r["id"]}@$depth"
                engine.onRecord(c, a, r, depth)
            },
            clock = { now },
            synchronous = true,
        )
        return engine
    }

    private fun create(engine: AutomationEngine, vararg pairs: Pair<String, Any?>): JsonObject {
        val record = services.getValue("orders").create(JsonObject(pairs.associate { (k, v) ->
            k to when (v) {
                is Number -> JsonPrimitive(v)
                is Boolean -> JsonPrimitive(v)
                else -> JsonPrimitive(v as String?)
            }
        }))
        engine.onRecord("orders", RecordEvent.Action.CREATE, record)
        return record
    }

    private fun order(id: Any?) = services.getValue("orders").get(id.toString().toLong())

    @Test
    fun `kayit olayinda kosula uyan kural eylemleri sirayla calistirir`() {
        val engine = engine(
            AutomationConfig(
                "yeni-siparis",
                AutomationTrigger(collection = "orders", events = listOf("create")),
                condition = "status=new",
                actions = listOf(
                    AutomationAction(AutomationActionType.EMAIL, to = "{email}", subject = "Sipariş #{id}: {item}", body = "{qty} adet, kural {rule}"),
                    AutomationAction(AutomationActionType.UPDATE, values = mapOf("seen" to "evet", "touched" to "{now}")),
                    AutomationAction(AutomationActionType.CREATE, collection = "events", values = mapOf("text" to "{item} alındı", "order" to "{id}")),
                    AutomationAction(AutomationActionType.WEBHOOK, webhook = "erp"),
                ),
            ),
        )
        val skipped = create(engine, "item" to "kalem", "email" to "a@example.com", "status" to "draft", "qty" to 1)
        assertTrue(mails.isEmpty(), "koşula uymayan kayıt")
        val o = create(engine, "item" to "defter", "email" to "b@example.com", "status" to "new", "qty" to 3)

        assertEquals(listOf(Triple("b@example.com", "Sipariş #${o["id"]}: defter", "3 adet, kural yeni-siparis")), mails)
        val updated = order(o["id"])
        assertEquals("true", updated["seen"].toString())
        assertEquals(now.toString(), updated["touched"]!!.jsonPrimitive.content)
        assertEquals("false", order(skipped["id"])["seen"].toString().let { if (it == "null") "false" else it })
        val created = services.getValue("events").list(1, 10, null, emptyList()).items.single()
        assertEquals("defter alındı", created["text"]!!.jsonPrimitive.content)
        assertEquals(o["id"].toString(), created["order"].toString())
        assertEquals(listOf("erp" to "automation.yeni-siparis"), hooks)
        val run = engine.runs().single()
        assertTrue(run.ok, run.message)
        assertEquals("orders.create", run.trigger)
        // Kuralın yazmaları da olay üretir (bir derinlik artarak).
        assertTrue(changes.any { it.startsWith("orders.update") && it.endsWith("@1") }, changes.joinToString())
        assertTrue(changes.any { it.startsWith("events.create") && it.endsWith("@1") }, changes.joinToString())
    }

    @Test
    fun `kendini tetikleyen kural derinlik sinirinda durur, degismeyen deger yazilmaz`() {
        val engine = engine(
            AutomationConfig(
                "dongu", AutomationTrigger(collection = "orders", events = listOf("create", "update")),
                actions = listOf(AutomationAction(AutomationActionType.UPDATE, values = mapOf("qty" to "{id}{qty}"))),
            ),
            AutomationConfig(
                "sabit", AutomationTrigger(collection = "orders", events = listOf("update")),
                actions = listOf(AutomationAction(AutomationActionType.UPDATE, values = mapOf("status" to "ok"))),
            ),
        )
        create(engine, "item" to "x", "qty" to 1)
        val updates = changes.count { it.startsWith("orders.update") }
        assertTrue(updates in 1..AutomationEngine.MAX_DEPTH * 2, changes.joinToString())
        assertTrue(changes.none { it.endsWith("@${AutomationEngine.MAX_DEPTH + 1}") }, changes.joinToString())
        // "sabit" ikinci kez yazmaz: değer zaten "ok".
        assertTrue(engine.runs().any { it.rule == "sabit" && "değişiklik yok" in it.message }, engine.runs().joinToString { it.message })
    }

    @Test
    fun `zamanlanmis kural kosula uyan kayitlari gunceller ve sayiyla e-posta gonderir`() {
        val engine = engine(
            AutomationConfig(
                "gunluk", AutomationTrigger(collection = "orders", schedule = "0 8 * * *"), condition = "status=open",
                actions = listOf(
                    AutomationAction(AutomationActionType.UPDATE, values = mapOf("status" to "late")),
                    AutomationAction(AutomationActionType.EMAIL, to = "admin@example.com", subject = "{count} geciken sipariş", body = "{now}"),
                ),
            ),
        )
        repeat(3) { create(engine, "item" to "a$it", "status" to "open") }
        create(engine, "item" to "b", "status" to "done")

        assertEquals(emptyList(), engine.tick(LocalDateTime.of(2026, 10, 15, 7, 59)))
        assertEquals(listOf("gunluk"), engine.tick(LocalDateTime.of(2026, 10, 15, 8, 0, 30)))
        val statuses = services.getValue("orders").list(1, 10, null, emptyList()).items.map { it["status"]!!.jsonPrimitive.content }
        assertEquals(listOf("late", "late", "late", "done"), statuses)
        assertEquals("3 geciken sipariş", mails.single().second)
        assertTrue(engine.runs().first().message.contains("3"), engine.runs().first().message)
    }

    @Test
    fun `simdi dene son kayitla calisir, hata gecmise yazilir`() {
        val engine = engine(
            AutomationConfig(
                "bildir", AutomationTrigger(collection = "orders"), condition = "status=new",
                actions = listOf(AutomationAction(AutomationActionType.EMAIL, to = "{email}", subject = "Yeni: {item}")),
            ),
        )
        assertTrue(!engine.runNow("bildir").ok, "kayıt yokken")
        services.getValue("orders").create(buildJsonObject { put("item", JsonPrimitive("ilk")); put("status", JsonPrimitive("new")); put("email", JsonPrimitive("x@example.com")) })
        services.getValue("orders").create(buildJsonObject { put("item", JsonPrimitive("son")); put("status", JsonPrimitive("new")) })
        val run = engine.runNow("bildir")
        assertTrue(!run.ok && "Alıcı" in run.message, run.message) // son kaydın e-postası yok
        assertEquals(2, engine.runs().size)
    }

    @Test
    fun `dogrulama`() {
        fun errors(vararg rules: AutomationConfig, email: Boolean = true) = ConfigValidator.validate(
            base.copy(
                automations = rules.toList(),
                email = if (email) EmailConfig(enabled = true, host = "smtp.example.com", from = "a@example.com") else EmailConfig(),
                webhooks = listOf(WebhookConfig("erp", "https://example.com/hook")),
            ),
        )
        val ok = AutomationConfig("ok", AutomationTrigger(collection = "orders"), actions = listOf(AutomationAction(AutomationActionType.LOG, body = "x")))
        assertEquals(emptyList(), errors(ok))
        assertTrue(errors(ok, ok).any { "iki kez" in it })
        assertTrue(errors(ok.copy(name = "boşluk var")).isNotEmpty())
        assertTrue(errors(ok.copy(trigger = AutomationTrigger(collection = "yok"))).any { "'yok'" in it })
        assertTrue(errors(ok.copy(trigger = AutomationTrigger())).any { "koleksiyon seçin" in it })
        assertTrue(errors(ok.copy(trigger = AutomationTrigger(collection = "orders", schedule = "her gün"))).any { "zamanlama" in it })
        assertTrue(errors(ok.copy(condition = "durum=x")).any { "bilinmeyen alan" in it })
        assertTrue(errors(ok.copy(actions = emptyList())).any { "en az bir eylem" in it })
        val mail = ok.copy(actions = listOf(AutomationAction(AutomationActionType.EMAIL, to = "{email}", subject = "s")))
        assertEquals(emptyList(), errors(mail))
        assertTrue(errors(mail, email = false).any { "e-posta gönderimini açın" in it })
        val update = ok.copy(actions = listOf(AutomationAction(AutomationActionType.UPDATE, values = mapOf("yok" to "1"))))
        assertTrue(errors(update).any { "'yok' alanı yok" in it })
        assertTrue(errors(ok.copy(actions = listOf(AutomationAction(AutomationActionType.WEBHOOK, webhook = "baska")))).any { "webhook seçin" in it })
        assertTrue(errors(ok.copy(actions = listOf(AutomationAction(AutomationActionType.CREATE, collection = "yok", values = mapOf("a" to "b"))))).any { "koleksiyonu seçin" in it })
        val push = ok.copy(trigger = AutomationTrigger(collection = "orders", schedule = "0 8 * * *"), actions = listOf(AutomationAction(AutomationActionType.PUSH, to = "owner", subject = "s")))
        assertTrue(errors(push).any { "kullanıcı hesaplarını ve web push'u açın" in it })

        // Dosyaya yazılıp okununca aynı kalır.
        val config = base.copy(automations = listOf(mail.copy(condition = "status=new")))
        assertEquals(config, ServerConfig.fromJson(config.toJson()))
    }

    @Test
    fun `api uzerinden tetiklenir ve uclar kurallari gosterir`() = testApplication {
        val key = "yonetici-anahtari-1234567890"
        val config = base.copy(
            auth = AuthConfig(AuthMode.API_KEY, apiKeys = listOf(key)),
            automations = listOf(
                AutomationConfig(
                    "isaretle", AutomationTrigger(collection = "orders"), condition = "qty[gte]=10",
                    actions = listOf(AutomationAction(AutomationActionType.UPDATE, values = mapOf("status" to "toptan"))),
                ),
                AutomationConfig("gece", AutomationTrigger(schedule = "0 3 * * *"), actions = listOf(AutomationAction(AutomationActionType.LOG, body = "{count} {rule}"))),
            ),
        )
        assertEquals(emptyList(), ConfigValidator.validate(config))
        val logs = Collections.synchronizedList(mutableListOf<String>())
        application { droidServerModule(config, db, log = { logs += it }) }
        suspend fun json(text: String) = Json.parseToJsonElement(text).jsonObject

        val created = json(client.post("/api/collections/orders/records") {
            bearerAuth(key); contentType(ContentType.Application.Json); setBody("""{"item":"kalem","qty":12}""")
        }.bodyAsText())
        val id = created.getValue("id").jsonPrimitive.content
        val until = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < until && order(id)["status"]?.jsonPrimitive?.contentOrNull != "toptan") Thread.sleep(20)
        assertEquals("toptan", order(id)["status"]!!.jsonPrimitive.content)

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/_automations").status)
        val run = json(client.post("/api/_automations/gece/run") { bearerAuth(key) }.bodyAsText())
        assertEquals(true, run.getValue("ok").jsonPrimitive.content.toBoolean(), run.toString())
        assertTrue(logs.any { "0 gece" in it }, logs.joinToString())
        assertEquals(HttpStatusCode.NotFound, client.post("/api/_automations/yok/run") { bearerAuth(key) }.status)
        val listed = json(client.get("/api/_automations") { bearerAuth(key) }.bodyAsText())
        assertEquals(listOf("isaretle", "gece"), listed.getValue("rules").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content })
        assertEquals(setOf("orders.create", "test"), listed.getValue("runs").jsonArray.map { it.jsonObject.getValue("trigger").jsonPrimitive.content }.toSet())
    }

    @Test
    fun `deger tiplere cevrilir`() {
        assertEquals(JsonPrimitive(5L), AutomationEngine.typed(FieldType.INTEGER, "5"))
        assertEquals(JsonPrimitive(2.5), AutomationEngine.typed(FieldType.REAL, "2,5"))
        assertEquals(JsonPrimitive(true), AutomationEngine.typed(FieldType.BOOLEAN, "evet"))
        assertEquals("[1,2]", AutomationEngine.typed(FieldType.JSON, "[1,2]").toString())
        assertEquals(kotlinx.serialization.json.JsonNull, AutomationEngine.typed(FieldType.TEXT, ""))
    }
}
