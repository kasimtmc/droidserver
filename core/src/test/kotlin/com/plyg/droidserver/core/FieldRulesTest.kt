package com.plyg.droidserver.core

import com.plyg.droidserver.core.api.droidServerModule
import com.plyg.droidserver.core.config.AuthConfig
import com.plyg.droidserver.core.config.AuthMode
import com.plyg.droidserver.core.config.CollectionConfig
import com.plyg.droidserver.core.config.ConfigValidator
import com.plyg.droidserver.core.config.Expression
import com.plyg.droidserver.core.config.FieldConfig
import com.plyg.droidserver.core.config.FieldType
import com.plyg.droidserver.core.config.Rule
import com.plyg.droidserver.core.config.RuleCondition
import com.plyg.droidserver.core.config.Rules
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.config.TextFormat
import com.plyg.droidserver.core.config.TypeConversions
import com.plyg.droidserver.core.db.SchemaMigrator
import com.plyg.droidserver.core.db.SqlDriver
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Faz 12.1: seçim alanları, alan kısıtları ve hesaplanmış alanlar, iki lehçede. */
@RunWith(Parameterized::class)
class FieldRulesTest(private val dialect: Dialect) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun dialects() = Dialect.entries.toList()
    }

    private val key = "yonetici-anahtari-1234567890"
    private val products = CollectionConfig(
        "products",
        listOf(
            FieldConfig("name", FieldType.TEXT, required = true, minLength = 2, maxLength = 20),
            FieldConfig("sku", FieldType.TEXT, pattern = "[A-Z]{3}-[0-9]+"),
            FieldConfig("contact", FieldType.TEXT, format = TextFormat.EMAIL),
            FieldConfig("site", FieldType.TEXT, format = TextFormat.URL),
            FieldConfig("status", FieldType.SELECT, options = listOf("taslak", "yayında", "arşiv"), default = JsonPrimitive("taslak")),
            FieldConfig("tags", FieldType.MULTISELECT, options = listOf("yeni", "indirim", "a_b", "Yeni"), maxLength = 2),
            FieldConfig("price", FieldType.REAL, min = 0.0),
            FieldConfig("qty", FieldType.INTEGER, min = 0.0, max = 1000.0),
            FieldConfig("total", FieldType.REAL, computed = "round(price * qty, 2)"),
            FieldConfig("label", FieldType.TEXT, computed = "upper(name) + ' (' + (status ?? '-') + ')'"),
            FieldConfig("expensive", FieldType.BOOLEAN, computed = "total > 100"),
        ),
        rules = Rules(Rule.PUBLIC, Rule.PUBLIC, Rule.ADMIN, Rule.ADMIN, Rule.ADMIN, conditions = mapOf("list" to RuleCondition("tags=indirim"))),
    )
    private val config = ServerConfig(auth = AuthConfig(AuthMode.API_KEY, apiKeys = listOf(key)), collections = listOf(products))
    private lateinit var db: SqlDriver

    @Before
    fun open() {
        db = TestDb.create(dialect).open()
        SchemaMigrator(db).migrate(config.collections)
    }

    @After
    fun close() = db.close()

    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject
    private suspend fun HttpClient.create(body: String): HttpResponse = post("/api/collections/products/records") {
        bearerAuth(key); contentType(ContentType.Application.Json); setBody(body)
    }
    private suspend fun HttpClient.names(query: String, token: String? = key): List<String> {
        val r = get("/api/collections/products/records?sort=id&$query") { token?.let { bearerAuth(it) } }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return json(r.bodyAsText()).getValue("items").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }
    }
    private suspend fun HttpClient.errors(body: String): Set<String> {
        val r = create(body)
        assertEquals(HttpStatusCode.BadRequest, r.status, r.bodyAsText())
        return json(r.bodyAsText()).getValue("details").jsonObject.keys
    }

    @Test
    fun `secim alanlari, kisitlar ve hesaplanan alanlar`() = testApplication {
        application { droidServerModule(config, db) }
        assertTrue(ConfigValidator.validate(config).isEmpty(), ConfigValidator.validate(config).toString())

        val r = client.create("""{"name":"Kalem","price":12.5,"qty":10,"tags":["yeni","indirim"]}""")
        assertEquals(HttpStatusCode.Created, r.status, r.bodyAsText())
        val p = json(r.bodyAsText())
        assertEquals("taslak", p.getValue("status").jsonPrimitive.content, "varsayılan seçenek")
        assertEquals(JsonArray(listOf(JsonPrimitive("yeni"), JsonPrimitive("indirim"))), p.getValue("tags"))
        assertEquals(125.0, p.getValue("total").jsonPrimitive.content.toDouble())
        assertEquals("KALEM (taslak)", p.getValue("label").jsonPrimitive.content)
        assertEquals("true", p.getValue("expensive").jsonPrimitive.content)
        val id = p.getValue("id").jsonPrimitive.content

        // Güncelleme: hesaplanan alanlar gönderilmeyen alanlarla birlikte yeniden hesaplanır.
        val u = client.patch("/api/collections/products/records/$id") {
            bearerAuth(key); contentType(ContentType.Application.Json); setBody("""{"qty":2,"status":"yayında"}""")
        }
        assertEquals(HttpStatusCode.OK, u.status, u.bodyAsText())
        val updated = json(u.bodyAsText())
        assertEquals(25.0, updated.getValue("total").jsonPrimitive.content.toDouble())
        assertEquals("KALEM (yayında)", updated.getValue("label").jsonPrimitive.content)
        assertEquals("false", updated.getValue("expensive").jsonPrimitive.content)

        // Eksik girdi: sayı işlemi boş verir, ?? yedeği kullanılır.
        val empty = json(client.create("""{"name":"Silgi","status":null}""").bodyAsText())
        assertEquals(JsonNull, empty.getValue("total"))
        assertEquals("SİLGİ (-)", empty.getValue("label").jsonPrimitive.content)
        assertEquals(JsonArray(emptyList()), empty.getValue("tags"))
        client.create("""{"name":"Defter","tags":["Yeni"],"status":"arşiv"}""")
        client.create("""{"name":"Cetvel","tags":["a_b"]}""")

        // Doğrulama hataları alan başına.
        assertEquals(setOf("name"), client.errors("""{"name":"K"}"""))
        assertEquals(setOf("name"), client.errors("""{"name":"çok uzun bir ürün adı burada"}"""))
        assertEquals(setOf("sku"), client.errors("""{"name":"Kalem","sku":"abc-1"}"""))
        assertEquals(setOf("contact", "site"), client.errors("""{"name":"Kalem","contact":"yok","site":"ftp://x"}"""))
        assertEquals(setOf("status"), client.errors("""{"name":"Kalem","status":"silindi"}"""))
        assertEquals(setOf("tags"), client.errors("""{"name":"Kalem","tags":["eski"]}"""))
        assertEquals(setOf("tags"), client.errors("""{"name":"Kalem","tags":["yeni","yeni"]}"""))
        assertEquals(setOf("tags"), client.errors("""{"name":"Kalem","tags":["yeni","indirim","a_b"]}"""))
        assertEquals(setOf("tags"), client.errors("""{"name":"Kalem","tags":"yeni"}"""))
        assertEquals(setOf("price", "qty"), client.errors("""{"name":"Kalem","price":-1,"qty":1001}"""))
        assertEquals(setOf("total"), client.errors("""{"name":"Kalem","total":5}"""))
        assertEquals(HttpStatusCode.Created, client.create("""{"name":"Kalem","sku":"ABC-12","contact":"a@b.co","site":"https://ornek.com/x"}""").status)

        // Filtreler: seçimde eşit/in, çoklu seçimde "içerir" (harf duyarlı, _ joker değil) ve null.
        assertEquals(listOf("Kalem"), client.names("status=yayında"))
        assertEquals(listOf("Kalem", "Defter"), client.names("status[in]=yayında,arşiv"))
        assertEquals(listOf("Kalem"), client.names("tags=yeni"))
        assertEquals(listOf("Defter"), client.names("tags=Yeni"))
        assertEquals(listOf("Cetvel"), client.names("tags=a_b"))
        assertEquals(listOf("Silgi", "Kalem"), client.names("tags[null]=true"))
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/collections/products/records?tags[ne]=yeni") { bearerAuth(key) }.status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/collections/products/records?status[like]=ya") { bearerAuth(key) }.status)
        // Kural koşulu çoklu seçimde: anonim liste yalnızca "indirim" etiketlileri görür.
        assertEquals(listOf("Kalem"), client.names("", token = null))

        // Özet: seçime göre gruplanır.
        val agg = json(client.get("/api/collections/products/aggregate?groupBy=status") { bearerAuth(key) }.bodyAsText())
        val keys = agg.getValue("groups").jsonArray.map { it.jsonObject["key"]?.let { k -> (k as? JsonPrimitive)?.content } }
        assertTrue("yayında" in keys && "arşiv" in keys, agg.toString())

        // OpenAPI şeması seçenekleri ve kısıtları anlatır.
        val doc = client.get("/api/_openapi.json") { bearerAuth(key) }.bodyAsText().replace(Regex("\\s"), "")
        assertTrue("\"enum\"" in doc && "\"readOnly\"" in doc && "\"maxItems\"" in doc && "\"format\":\"email\"" in doc, doc)
    }

    @Test
    fun `konfigurasyon dogrulamasi`() {
        fun errors(vararg fields: FieldConfig) =
            ConfigValidator.validate(config.copy(collections = listOf(CollectionConfig("c", fields.toList()))))
        fun has(msg: String, vararg fields: FieldConfig) = errors(*fields).let { assertTrue(it.any { e -> msg in e }, "'$msg' bekleniyor: $it") }

        has("en az bir seçenek", FieldConfig("s", FieldType.SELECT))
        has("aynı seçenek", FieldConfig("s", FieldType.SELECT, options = listOf("a", "a")))
        has("kullanılamaz", FieldConfig("s", FieldType.MULTISELECT, options = listOf("a\"b")))
        has("yalnızca seçim", FieldConfig("s", FieldType.TEXT, options = listOf("a")))
        has("yalnızca metin", FieldConfig("s", FieldType.INTEGER, format = TextFormat.EMAIL))
        has("geçersiz düzenli ifade", FieldConfig("s", FieldType.TEXT, pattern = "[a-"))
        has("en azdan küçük", FieldConfig("s", FieldType.TEXT, minLength = 5, maxLength = 2))
        has("yalnızca sayı", FieldConfig("s", FieldType.TEXT, min = 1.0))
        has("varsayılan değer", FieldConfig("s", FieldType.SELECT, options = listOf("a"), default = JsonPrimitive("b")))
        has("varsayılan değer", FieldConfig("s", FieldType.INTEGER, max = 3.0, default = JsonPrimitive(5)))
        has("bilinmeyen alan: yok", FieldConfig("s", FieldType.REAL, computed = "yok * 2"))
        has("kendisine", FieldConfig("s", FieldType.REAL, computed = "s + 1"))
        has("ifade hatalı", FieldConfig("s", FieldType.REAL, computed = "1 +"))
        has("zorunlu olamaz", FieldConfig("s", FieldType.REAL, required = true, computed = "1"))
        has("hesaplanmış alan metin", FieldConfig("s", FieldType.DATETIME, computed = "1"))
        assertTrue(errors(FieldConfig("a", FieldType.INTEGER), FieldConfig("s", FieldType.REAL, computed = "a / 2")).isEmpty())
        assertEquals(config, ServerConfig.fromJson(config.toJson()), "dosyaya yazılır ve okunur")
    }

    @Test
    fun `ifade dili ve tip donusumleri`() {
        fun eval(src: String, vararg values: Pair<String, Any?>) = Expression.parse(src).evaluate(values.toMap())
        assertEquals(7.0, eval("1 + 2 * 3"))
        assertEquals(9.0, eval("(1 + 2) * 3"))
        assertEquals("a1", eval("'a' + 1"))
        assertEquals(3.14, eval("round(x, 2)", "x" to 3.14159))
        assertEquals(null, eval("x / 0", "x" to 1L))
        assertEquals("yok", eval("x ?? 'yok'"))
        assertEquals("büyük", eval("if(x > 5, 'büyük', 'küçük')", "x" to 6L))
        assertEquals(true, eval("!(a && b) || c == 'x'", "a" to true, "b" to false))
        assertEquals("İSTANBUL", eval("upper('istanbul')"))
        assertEquals(3.0, eval("len(trim('  abc '))"))
        assertEquals(setOf("a", "b"), Expression.parse("a + min(b, 2)").references)
        assertFailsWith<IllegalArgumentException> { Expression.parse("foo(1)") }
        assertFailsWith<IllegalArgumentException> { Expression.parse("if(1, 2)") }
        assertFailsWith<IllegalArgumentException> { Expression.parse("'kapanmamış") }
        assertFailsWith<IllegalArgumentException> { Expression.parse("(".repeat(60) + "1" + ")".repeat(60)) }

        assertTrue(TypeConversions.canConvert(FieldType.TEXT, FieldType.SELECT))
        assertEquals(listOf("a", "b"), TypeConversions.multiValues(TypeConversions.convert(FieldType.TEXT, FieldType.MULTISELECT, "a, b") as String))
        assertEquals(listOf("x"), TypeConversions.multiValues(TypeConversions.multiText(listOf("x"))))
    }
}
