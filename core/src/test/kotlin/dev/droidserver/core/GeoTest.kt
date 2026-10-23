package dev.droidserver.core

import dev.droidserver.core.api.FilterParser
import dev.droidserver.core.api.RuleConditions
import dev.droidserver.core.api.droidServerModule
import dev.droidserver.core.config.AuthConfig
import dev.droidserver.core.config.AuthMode
import dev.droidserver.core.config.CollectionConfig
import dev.droidserver.core.config.FieldConfig
import dev.droidserver.core.config.FieldType
import dev.droidserver.core.config.GeoPoint
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.config.TypeConversions
import dev.droidserver.core.db.SchemaMigrator
import dev.droidserver.core.db.SqlDriver
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Faz 18.3: konum alanı, `near` ve `box` filtreleri, iki lehçede aynı sonuç. */
@RunWith(Parameterized::class)
class GeoTest(private val dialect: Dialect) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun dialects() = Dialect.entries.toList()
    }

    private val key = "yonetici-anahtari-1234567890"
    private val places = CollectionConfig(
        "places",
        listOf(FieldConfig("name", FieldType.TEXT, required = true), FieldConfig("loc", FieldType.GEO)),
    )
    private val config = ServerConfig(auth = AuthConfig(AuthMode.API_KEY, apiKeys = listOf(key)), collections = listOf(places))
    private lateinit var db: SqlDriver

    @Before
    fun open() {
        db = TestDb.create(dialect).open()
    }

    @After
    fun close() = db.close()

    private suspend fun HttpClient.call(method: String, path: String, body: String? = null): HttpResponse = when (method) {
        "GET" -> get(path) { bearerAuth(key) }
        "PATCH" -> patch(path) { bearerAuth(key); contentType(ContentType.Application.Json); setBody(body ?: "{}") }
        else -> post(path) { bearerAuth(key); contentType(ContentType.Application.Json); setBody(body ?: "{}") }
    }

    private suspend fun HttpClient.names(query: String): List<String> {
        val r = call("GET", "/api/collections/places/records?$query")
        assertEquals(200, r.status.value, r.bodyAsText())
        return Json.parseToJsonElement(r.bodyAsText()).jsonObject.getValue("items").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }
    }

    private suspend fun HttpClient.add(name: String, lat: Double, lng: Double): JsonObject {
        val r = call("POST", "/api/collections/places/records", """{"name":"$name","loc":{"lat":$lat,"lng":$lng}}""")
        assertEquals(201, r.status.value, r.bodyAsText())
        return Json.parseToJsonElement(r.bodyAsText()).jsonObject
    }

    @Test
    fun `konum yazilir, okunur ve yakindan uzaga aranir`() = testApplication {
        SchemaMigrator(db).migrate(config.schemaCollections())
        application { droidServerModule(config, db) }
        val sultanahmet = client.add("Sultanahmet", 41.0054, 28.9768)
        client.add("Kadıköy", 40.99, 29.028)
        client.add("Ankara", 39.9334, 32.8597)
        client.add("Fiji batı", -17.7134, 178.065)
        client.add("Fiji doğu", -17.8, -179.9)
        client.call("POST", "/api/collections/places/records", """{"name":"Konumsuz"}""")

        // API'de nesne, veritabanında metin.
        assertEquals(Json.parseToJsonElement("""{"lat":41.0054,"lng":28.9768}"""), sultanahmet["loc"])
        val id = sultanahmet.getValue("id").jsonPrimitive.content
        assertEquals("41.0054,28.9768", db.query("SELECT \"loc\" FROM \"places\" WHERE \"id\" = ?", listOf(id.toLong())).single()["loc"])

        // Yakındaki: yarıçapın dışındakiler yok, yakından uzağa.
        assertEquals(listOf("Kadıköy", "Sultanahmet"), client.names("loc[near]=40.99,29.028,10"))
        assertEquals(listOf("Sultanahmet", "Kadıköy", "Ankara"), client.names("loc[near]=41.0054,28.9768,400"))
        assertEquals(listOf("Kadıköy", "Sultanahmet"), client.names("loc[near]=40.99,29.028,400&name[ne]=Ankara"))
        // Verilen sıralama yakınlığın önüne geçer.
        assertEquals(listOf("Ankara", "Kadıköy", "Sultanahmet"), client.names("loc[near]=41.0054,28.9768,400&sort=name"))
        // 180. boylamın iki yanı.
        assertEquals(listOf("Fiji batı", "Fiji doğu"), client.names("loc[near]=-17.7,178.5,300"))
        assertEquals(listOf("Fiji doğu", "Fiji batı"), client.names("loc[near]=-17.8,-179.5,300"))

        // Kutu.
        assertEquals(listOf("Sultanahmet", "Kadıköy"), client.names("loc[box]=40.9,28.9,41.1,29.1"))
        assertEquals(listOf("Konumsuz"), client.names("loc[null]=true"))

        // Hatalı kullanım.
        for (q in listOf("loc=41,29", "loc[eq]=41,29", "loc[near]=41,29", "loc[near]=41,29,5000", "loc[near]=95,29,5", "loc[box]=41,29,40,30", "sort=loc", "name[near]=41,29,5")) {
            assertEquals(400, client.call("GET", "/api/collections/places/records?$q").status.value, q)
        }

        // Doğrulama ve boşaltma.
        for (bad in listOf("""{"lat":91,"lng":0}""", """{"lat":"41","lng":29}""", """{"lat":41}""", """"41,29"""", """{"lat":41,"lng":29,"x":1}""")) {
            val r = client.call("POST", "/api/collections/places/records", """{"name":"Hatalı","loc":$bad}""")
            assertEquals(400, r.status.value, bad)
            assertTrue("loc" in r.bodyAsText(), r.bodyAsText())
        }
        val r = client.call("PATCH", "/api/collections/places/records/$id", """{"loc":{"lat":41.0,"lng":29.0,"accuracy":12.5}}""")
        assertEquals(Json.parseToJsonElement("""{"lat":41.0,"lng":29.0,"accuracy":12.5}"""), Json.parseToJsonElement(r.bodyAsText()).jsonObject["loc"])
        val cleared = client.call("PATCH", "/api/collections/places/records/$id", """{"loc":null}""")
        assertEquals(JsonNull, Json.parseToJsonElement(cleared.bodyAsText()).jsonObject["loc"])
    }

    @Test
    fun `yaklasik uzaklik, kural kosulu ve donusum`() {
        val istanbul = GeoPoint(41.0054, 28.9768)
        val ankara = GeoPoint(39.9334, 32.8597)
        val exact = istanbul.distanceKm(ankara)
        assertTrue(abs(exact - 350) < 10, "$exact")
        assertTrue(abs(GeoPoint.approxKm(ankara.lat, ankara.lng, istanbul.lat, istanbul.lng) - exact) / exact < 0.005)

        // Canlı abonelikte kural koşulu kaydın JSON'u üzerinde SQL'le aynı formülle değerlendirilir.
        val near = RuleConditions.parse(places, "loc[near]=41.0054,28.9768,360", null)
        assertEquals(FilterParser.Op.NEAR, near.single().op)
        assertTrue(RuleConditions.matches(near, JsonObject(mapOf("loc" to ankara.json()))))
        assertTrue(!RuleConditions.matches(RuleConditions.parse(places, "loc[near]=41.0054,28.9768,340", null), JsonObject(mapOf("loc" to ankara.json()))))
        assertTrue(RuleConditions.matches(RuleConditions.parse(places, "loc[box]=39,32,40,33", null), JsonObject(mapOf("loc" to ankara.json()))))

        assertEquals("41.5,29", TypeConversions.convert(FieldType.TEXT, FieldType.GEO, " 41.5 , 29 "))
        assertEquals(null, TypeConversions.convert(FieldType.TEXT, FieldType.GEO, "İstanbul"))
        assertEquals("41.5,29", TypeConversions.convert(FieldType.GEO, FieldType.TEXT, "41.5,29"))
    }
}
