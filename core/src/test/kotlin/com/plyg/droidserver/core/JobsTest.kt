package com.plyg.droidserver.core

import com.plyg.droidserver.core.api.WriteGate
import com.plyg.droidserver.core.api.droidServerModule
import com.plyg.droidserver.core.audit.AuditLog
import com.plyg.droidserver.core.config.AuthConfig
import com.plyg.droidserver.core.config.AuthMode
import com.plyg.droidserver.core.config.CollectionConfig
import com.plyg.droidserver.core.config.ConfigValidator
import com.plyg.droidserver.core.config.FieldConfig
import com.plyg.droidserver.core.config.FieldType
import com.plyg.droidserver.core.config.JobsConfig
import com.plyg.droidserver.core.config.Rule
import com.plyg.droidserver.core.config.Rules
import com.plyg.droidserver.core.config.ScriptsConfig
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.db.SchemaMigrator
import com.plyg.droidserver.core.db.SqlDriver
import com.plyg.droidserver.core.jobs.Job
import com.plyg.droidserver.core.jobs.JobQueue
import com.plyg.droidserver.core.jobs.JobRunner
import com.plyg.droidserver.core.script.ScriptLoadException
import com.plyg.droidserver.core.script.ScriptRuntime
import com.plyg.droidserver.core.script.ScriptSource
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Faz 13.4: arka plan iş kuyruğu, iki lehçede. */
@RunWith(Parameterized::class)
class JobsTest(private val dialect: Dialect) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun dialects() = Dialect.entries.toList()
    }

    private val key = "yonetici-anahtari-1234567890"
    private val orders = CollectionConfig(
        "orders", listOf(FieldConfig("total", FieldType.REAL), FieldConfig("invoice", FieldType.TEXT)),
        rules = Rules(Rule.PUBLIC, Rule.PUBLIC, Rule.PUBLIC, Rule.PUBLIC, Rule.PUBLIC),
    )
    private val scripts = ScriptsConfig(enabled = true, timeoutMs = 500, cronTimeoutSec = 2, jobs = JobsConfig(concurrency = 2))
    private val config = ServerConfig(auth = AuthConfig(AuthMode.API_KEY, apiKeys = listOf(key)), collections = listOf(orders), scripts = scripts)
    private lateinit var db: SqlDriver
    private var now = Instant.parse("2026-10-14T12:00:00Z")

    @Before
    fun open() {
        db = TestDb.create(dialect).open()
        SchemaMigrator(db).migrate(config.schemaCollections())
    }

    @After
    fun close() = db.close()

    private fun load(js: String) = ScriptRuntime.load(listOf(ScriptSource("jobs.js", js)), scripts, setOf("orders"))

    @Test
    fun `kuyruk - ekle, al, bitir, yeniden dene, iptal, temizle`() {
        val q = JobQueue(db) { now }
        val a = q.enqueue("fatura", buildJsonObject { put("order", JsonPrimitive(7)) }, maxAttempts = 2)
        val later = q.enqueue("fatura", delay = Duration.ofMinutes(5))
        val daily = q.enqueue("rapor", key = "gunluk")
        assertEquals(daily, q.enqueue("rapor", key = "gunluk"), "aynı anahtarlı bitmemiş iş tekrar eklenmez")

        assertEquals(emptyList(), q.claim(setOf("yok"), 5))
        val first = q.claim(setOf("fatura"), 5)
        assertEquals(listOf(a), first.map { it.id }, "gecikmeli iş henüz değil")
        assertEquals(Job.RUNNING, q.get(a).status)
        assertEquals(1, first.single().attempts)
        assertEquals(emptyList(), q.claim(setOf("fatura"), 5), "çalışan iş ikinci kez alınmaz")

        assertEquals(Job.QUEUED, q.fail(first.single(), "ağ hatası"))
        assertEquals(now + JobQueue.backoff(1), q.get(a).runAt, "üstel bekleme")
        now += Duration.ofSeconds(31)
        val second = q.claim(setOf("fatura"), 5).single { it.id == a }
        assertEquals(2, second.attempts)
        assertEquals(Job.FAILED, q.fail(second, "yine"), "deneme hakkı bitti")
        assertEquals("yine", q.get(a).error)

        val retried = q.retry(a)
        assertEquals(Job.QUEUED to 0, retried.status to retried.attempts)
        assertEquals(409, assertFailsWith<com.plyg.droidserver.core.api.ApiException> { q.retry(a) }.status)
        val third = q.claim(setOf("fatura"), 1).single()
        q.complete(third.id, JsonPrimitive("FTR-1"))
        assertEquals(JsonPrimitive("FTR-1"), q.get(a).result)
        assertEquals(Job.CANCELLED, q.cancel(later).status)
        assertEquals(409, assertFailsWith<com.plyg.droidserver.core.api.ApiException> { q.cancel(a) }.status)

        val r = q.claim(setOf("rapor"), 1).single()
        assertEquals(1, q.recover(), "yarım kalan iş açılışta kuyruğa döner")
        assertEquals(Job.QUEUED, q.get(r.id).status)
        assertEquals(mapOf(Job.DONE to 1L, Job.CANCELLED to 1L, Job.QUEUED to 1L), q.counts())
        val (items, total) = q.list(status = Job.DONE)
        assertEquals(1L, total)
        assertEquals("fatura", items.single().name)

        now += Duration.ofDays(8)
        assertEquals(2, q.purge(7), "biten ve iptal edilen")
        assertEquals(mapOf(Job.QUEUED to 1L), q.counts())
        q.enqueue("fatura")
        assertEquals(listOf("rapor"), q.claim(setOf("fatura", "rapor"), 5, skip = setOf("fatura")).map { it.name }, "sınırı dolan tür atlanır")
    }

    @Test
    fun `script isleri - sonuc, yeniden deneme, kalici hata, ctx jobs`() {
        val runtime = load(
            """
            var calls = 0;
            jobAdd("fatura", (data, ctx, job) => {
              const o = ctx.db.get("orders", data.order);
              ctx.db.update("orders", o.id, { invoice: "FTR-" + o.id });
              return { invoice: "FTR-" + o.id, attempt: job.attempt };
            });
            jobAdd("dalgali", (data, ctx, job) => { if (job.attempt < 2) throw new Error("geçici"); return "tamam"; }, { retries: 3 });
            jobAdd("gecersiz", () => fail(422, "veri bozuk"), { retries: 5 });
            jobAdd("yavas", () => { while (true) {} }, { timeoutSec: 1, retries: 0 });
            routerAdd("POST", "/siparis", (req, ctx) => {
              const o = ctx.db.create("orders", { total: req.body.total });
              return { id: o.id, job: ctx.jobs.enqueue("fatura", { order: o.id }, { key: "fatura-" + o.id }) };
            });
            routerAdd("POST", "/yok", (req, ctx) => ctx.jobs.enqueue("bilinmeyen", {}));
            """.trimIndent(),
        )
        assertEquals(setOf("fatura", "dalgali", "gecersiz", "yavas"), runtime.jobTypes.keys)
        assertEquals(4, runtime.jobTypes.getValue("dalgali").attempts)

        testApplication {
            application { droidServerModule(config, db, scripts = runtime) }
            val created = Json.parseToJsonElement(client.post("/api/siparis") { contentType(ContentType.Application.Json); setBody("""{"total":12.5}""") }.bodyAsText()).jsonObject
            assertEquals(HttpStatusCode.BadRequest, client.post("/api/yok").status)
            val jobId = created.getValue("job").jsonPrimitive.content.toDouble().toLong()
            val orderId = created.getValue("id").jsonPrimitive.content

            // Çalıştırıcı kendiliğinden (eklenince uyanır).
            waitFor { JobQueue(db).get(jobId).status == Job.DONE }
            val done = JobQueue(db).get(jobId)
            assertEquals("FTR-$orderId", (done.result as JsonObject).getValue("invoice").jsonPrimitive.content)
            assertEquals("FTR-$orderId", Json.parseToJsonElement(client.get("/api/collections/orders/records/$orderId").bodyAsText()).jsonObject.getValue("invoice").jsonPrimitive.content)

            // Yönetici uçları.
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/_jobs").status)
            val list = Json.parseToJsonElement(client.get("/api/_jobs?status=done") { bearerAuth(key) }.bodyAsText()).jsonObject
            assertEquals(1, list.getValue("items").jsonArray.size)
            assertEquals(4, list.getValue("types").jsonArray.size)
            assertEquals("1", list.getValue("counts").jsonObject.getValue("done").jsonPrimitive.content)

            suspend fun enqueue(body: String) = client.post("/api/_jobs") { bearerAuth(key); contentType(ContentType.Application.Json); setBody(body) }
            assertEquals(HttpStatusCode.BadRequest, enqueue("""{"name":"bilinmeyen"}""").status)
            val bad = Json.parseToJsonElement(enqueue("""{"name":"gecersiz","data":{"x":1}}""").bodyAsText()).jsonObject.getValue("id").jsonPrimitive.content.toLong()
            waitFor { JobQueue(db).get(bad).status == Job.FAILED }
            assertEquals(1, JobQueue(db).get(bad).attempts, "4xx yeniden denenmez")
            assertEquals("veri bozuk", JobQueue(db).get(bad).error)

            val slow = Json.parseToJsonElement(enqueue("""{"name":"yavas"}""").bodyAsText()).jsonObject.getValue("id").jsonPrimitive.content.toLong()
            waitFor { JobQueue(db).get(slow).status == Job.FAILED }
            assertTrue("süre" in JobQueue(db).get(slow).error.orEmpty(), JobQueue(db).get(slow).error)

            assertEquals(HttpStatusCode.OK, client.post("/api/_jobs/$bad/retry") { bearerAuth(key) }.status)
            assertEquals(HttpStatusCode.Conflict, client.post("/api/_jobs/$done/retry".replace("$done", done.id.toString())) { bearerAuth(key) }.status)
            assertEquals(HttpStatusCode.NotFound, client.post("/api/_jobs/99999/cancel") { bearerAuth(key) }.status)
        }
    }

    @Test
    fun `calistirici - yeniden deneme ve eszamanlilik`() {
        val runtime = load(
            """
            jobAdd("dalgali", (data, ctx, job) => { if (job.attempt < 3) throw new Error("geçici " + job.attempt); return job.attempt; }, { retries: 3 });
            jobAdd("tekli", () => 1, { concurrency: 1 });
            """.trimIndent(),
        )
        val q = JobQueue(db) { now }
        val logs = mutableListOf<String>()
        val runner = JobRunner(q, runtime, JobsConfig(concurrency = 2), WriteGate(), { logs += it })
        val id = q.enqueue("dalgali", maxAttempts = 4)
        assertEquals(1, runner.tick(), "çalıştırıcı yokken aynı iş parçacığında")
        assertEquals(Job.QUEUED, q.get(id).status)
        assertTrue(q.get(id).error.orEmpty().startsWith("Error: geçici 1"), q.get(id).error)
        assertEquals(0, runner.tick(), "bekleme süresi dolmadı")
        now += Duration.ofMinutes(1)
        runner.tick()
        now += Duration.ofMinutes(2)
        runner.tick()
        assertEquals(Job.DONE to JsonPrimitive(3), q.get(id).status to q.get(id).result)

        // Yazmalar kapalıyken (veri taşıma) iş alınmaz.
        val gate = WriteGate()
        val paused = JobRunner(q, runtime, JobsConfig(), gate)
        val t = q.enqueue("tekli")
        gate.pause()
        assertEquals(0, paused.tick())
        gate.resume()
        assertEquals(1, paused.tick())
        assertEquals(Job.DONE, q.get(t).status)
    }

    @Test
    fun `tanim hatalari ve dogrulama`() {
        val e = assertFailsWith<ScriptLoadException> {
            load(
                """
                jobAdd("kötü ad", () => 1);
                jobAdd("a", () => 1); jobAdd("a", () => 1);
                jobAdd("b", 5);
                jobAdd("c", () => 1, { retries: 50, timeoutSec: 0, concurrency: 99 });
                routerAdd("GET", "/_jobs/x", () => 1);
                """.trimIndent(),
            )
        }
        assertEquals(5, e.errors.size, e.errors.toString())
        val paths = ConfigValidator.validateDetailed(config.copy(scripts = scripts.copy(jobs = JobsConfig(concurrency = 0, keepDays = 0)))).map { it.path }
        assertEquals(listOf("scripts.jobs.concurrency", "scripts.jobs.keepDays"), paths.filter { it.startsWith("scripts.jobs") })
        assertTrue("_jobs" in config.schemaCollections().map { it.name })
        assertTrue("_jobs" !in config.copy(scripts = ScriptsConfig()).schemaCollections().map { it.name })
        assertNull(AuditLog(null).list(1).firstOrNull())
    }

    private fun waitFor(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > end) throw AssertionError("zaman aşımı")
            Thread.sleep(50)
        }
    }
}
