package com.plyg.droidserver.core

import com.plyg.droidserver.core.api.droidServerModule
import com.plyg.droidserver.core.config.AuthConfig
import com.plyg.droidserver.core.config.AuthMode
import com.plyg.droidserver.core.config.CollectionConfig
import com.plyg.droidserver.core.config.FieldConfig
import com.plyg.droidserver.core.config.FieldType
import com.plyg.droidserver.core.config.Rule
import com.plyg.droidserver.core.config.Rules
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.db.SchemaMigrator
import com.plyg.droidserver.core.db.SqlDriver
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Toplu işlem (`/api/batch`) ve özetler (`/aggregate`), iki lehçede. */
@RunWith(Parameterized::class)
class BatchAggregateTest(private val dialect: Dialect) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun dialects() = Dialect.entries.toList()
    }

    private val key = "yonetici-anahtari-1234567890"
    private val orders = CollectionConfig(
        "orders",
        listOf(
            FieldConfig("item", FieldType.TEXT, required = true),
            FieldConfig("qty", FieldType.INTEGER),
            FieldConfig("price", FieldType.REAL),
            FieldConfig("paid", FieldType.BOOLEAN),
            FieldConfig("at", FieldType.DATETIME),
        ),
        rules = Rules(list = Rule.PUBLIC),
        search = listOf("item"),
    )
    private val photos = CollectionConfig("photos", listOf(FieldConfig("img", FieldType.FILE)))
    private val config = ServerConfig(auth = AuthConfig(AuthMode.API_KEY, apiKeys = listOf(key)), collections = listOf(orders, photos))
    private lateinit var db: SqlDriver

    @Before
    fun open() {
        db = TestDb.create(dialect).open()
        SchemaMigrator(db).migrate(config.collections)
    }

    @After
    fun close() = db.close()

    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject
    private suspend fun HttpClient.batch(body: String, token: String? = key): HttpResponse = post("/api/batch") {
        token?.let { bearerAuth(it) }
        contentType(ContentType.Application.Json)
        setBody(body)
    }
    private suspend fun HttpClient.count(): Int =
        json(get("/api/collections/orders/records") { bearerAuth(key) }.bodyAsText()).getValue("totalItems").jsonPrimitive.content.toInt()

    @Test
    fun `toplu islem hepsi ya da hicbiri`() = testApplication {
        application { droidServerModule(config, db) }
        val ok = client.batch(
            """{"requests":[
              {"method":"POST","collection":"orders","body":{"item":"çay","qty":2}},
              {"method":"POST","collection":"orders","body":{"item":"kahve","qty":1}},
              {"method":"PATCH","collection":"orders","id":1,"body":{"qty":5}},
              {"method":"DELETE","collection":"orders","id":2}
            ]}""",
        )
        assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
        val results = json(ok.bodyAsText()).getValue("results").jsonArray.map { it.jsonObject }
        assertEquals(listOf("201", "201", "200", "204"), results.map { it.getValue("status").jsonPrimitive.content })
        assertEquals("5", results[2].getValue("body").jsonObject.getValue("qty").jsonPrimitive.content)
        assertEquals(1, client.count())

        // İkinci istek geçersiz: ilki de geri alınır.
        val bad = client.batch("""{"requests":[{"method":"POST","collection":"orders","body":{"item":"süt"}},{"method":"POST","collection":"orders","body":{"qty":1}}]}""")
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        val err = json(bad.bodyAsText())
        assertTrue(err.getValue("message").jsonPrimitive.content.startsWith("İstek 2:"), err.toString())
        assertEquals("1", err.getValue("details").jsonObject.getValue("index").jsonPrimitive.content)
        assertEquals(1, client.count(), "hiçbiri yazılmadı")

        // Olmayan kayıt, kurallar, dosya alanı, sınırlar.
        assertEquals(HttpStatusCode.NotFound, client.batch("""{"requests":[{"method":"DELETE","collection":"orders","id":99}]}""").status)
        assertEquals(HttpStatusCode.Unauthorized, client.batch("""{"requests":[{"method":"POST","collection":"orders","body":{"item":"x"}}]}""", token = null).status)
        assertTrue("dosya alanı" in client.batch("""{"requests":[{"method":"POST","collection":"photos","body":{}}]}""").bodyAsText())
        assertEquals(HttpStatusCode.BadRequest, client.batch("""{"requests":[]}""").status)
        assertEquals(HttpStatusCode.BadRequest, client.batch("""{"requests":[{"method":"GET","collection":"orders"}]}""").status)
    }

    @Test
    fun `ozetler - toplam, ortalama, gruplama, filtre`() = testApplication {
        application { droidServerModule(config, db) }
        val rows = listOf(
            """{"item":"Çay","qty":2,"price":10.0,"paid":true,"at":"2026-10-01T09:00:00Z"}""",
            """{"item":"Kahve","qty":1,"price":30.0,"paid":false,"at":"2026-10-01T18:00:00Z"}""",
            """{"item":"Çay","qty":3,"price":10.0,"paid":true,"at":"2026-10-02T09:00:00Z"}""",
            """{"item":"Su","qty":6,"price":5.0,"at":"2026-11-03T09:00:00Z"}""",
        )
        client.batch("""{"requests":[${rows.joinToString(",") { """{"method":"POST","collection":"orders","body":$it}""" }}]}""")
        suspend fun agg(q: String, token: String? = null): JsonObject {
            val r = client.get("/api/collections/orders/aggregate?$q") { token?.let { bearerAuth(it) } }
            assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
            return json(r.bodyAsText())
        }

        val all = agg("count&sum=qty,price&avg=price&min=at&max=qty")
        assertEquals("4", all.getValue("count").jsonPrimitive.content)
        assertEquals("12", all.getValue("sum_qty").jsonPrimitive.content)
        assertEquals(55.0, all.getValue("sum_price").jsonPrimitive.content.toDouble())
        assertEquals(13.75, all.getValue("avg_price").jsonPrimitive.content.toDouble())
        assertEquals("2026-10-01T09:00:00Z", all.getValue("min_at").jsonPrimitive.content)
        assertEquals("6", all.getValue("max_qty").jsonPrimitive.content)

        val byItem = agg("sum=qty&groupBy=item").getValue("groups").jsonArray.map { it.jsonObject }
        assertEquals(listOf("Kahve", "Su", "Çay").sorted(), byItem.map { it.getValue("key").jsonPrimitive.content }.sorted())
        assertEquals("5", byItem.first { it.getValue("key").jsonPrimitive.content == "Çay" }.getValue("sum_qty").jsonPrimitive.content)

        val byDay = agg("groupBy=at:day&sum=qty").getValue("groups").jsonArray.map { it.jsonObject }
        assertEquals(listOf("2026-10-01", "2026-10-02", "2026-11-03"), byDay.map { it.getValue("key").jsonPrimitive.content })
        assertEquals("3", byDay[0].getValue("sum_qty").jsonPrimitive.content)
        assertEquals(listOf("2026-10", "2026-11"), agg("groupBy=at:month").getValue("groups").jsonArray.map { it.jsonObject.getValue("key").jsonPrimitive.content })

        val byPaid = agg("groupBy=paid").getValue("groups").jsonArray.associate { it.jsonObject.getValue("key").toString() to it.jsonObject.getValue("count").jsonPrimitive.content }
        assertEquals("2", byPaid["true"])
        assertEquals("1", byPaid["null"], "boş değerler ayrı grup")

        // Filtre ve arama özetle birlikte; listeleme kuralı uygulanır.
        assertEquals("2", agg("paid=true&count").getValue("count").jsonPrimitive.content)
        assertEquals("2", agg("q=cay").getValue("count").jsonPrimitive.content)
        assertEquals("1", agg("qty[gte]=5").getValue("count").jsonPrimitive.content)

        assertEquals(HttpStatusCode.BadRequest, client.get("/api/collections/orders/aggregate?sum=item").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/collections/orders/aggregate?groupBy=at").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/collections/orders/aggregate?avg=yok").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/collections/photos/aggregate").status)
    }
}
