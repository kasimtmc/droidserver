package com.plyg.droidserver.core

import com.plyg.droidserver.core.api.FormGuard
import com.plyg.droidserver.core.api.RecordService
import com.plyg.droidserver.core.api.WriteGate
import com.plyg.droidserver.core.api.droidServerModule
import com.plyg.droidserver.core.api.formPages
import com.plyg.droidserver.core.config.CollectionConfig
import com.plyg.droidserver.core.config.ConfigValidator
import com.plyg.droidserver.core.config.FieldConfig
import com.plyg.droidserver.core.config.FieldType
import com.plyg.droidserver.core.config.FormConfig
import com.plyg.droidserver.core.config.FormField
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.config.TextFormat
import com.plyg.droidserver.core.db.SchemaMigrator
import com.plyg.droidserver.core.db.SqlDriver
import com.plyg.droidserver.core.realtime.RecordEvent
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Herkese açık formlar: sayfa, kayıt ekleme, spam koruması, hatalar ve doğrulama. */
class FormsTest {
    private val leads = CollectionConfig(
        "leads",
        listOf(
            FieldConfig("name", FieldType.TEXT, required = true, maxLength = 80),
            FieldConfig("email", FieldType.TEXT, required = true, format = TextFormat.EMAIL),
            FieldConfig("topic", FieldType.SELECT, options = listOf("Satış", "Destek")),
            FieldConfig("tags", FieldType.MULTISELECT, options = listOf("a", "b", "c")),
            FieldConfig("count", FieldType.INTEGER, min = 1.0, max = 10.0),
            FieldConfig("consent", FieldType.BOOLEAN, default = JsonPrimitive(false)),
            FieldConfig("note", FieldType.TEXT),
            FieldConfig("score", FieldType.INTEGER, computed = "count * 2"),
        ),
    )
    private val form = FormConfig(
        "iletisim", "leads", "Bize ulaşın", description = "En kısa sürede dönüyoruz.",
        fields = listOf(
            FormField("name", label = "Adınız"), FormField("email", label = "E-posta"), FormField("topic"), FormField("tags"),
            FormField("count"), FormField("consent", label = "İzin veriyorum"), FormField("note", multiline = true, help = "İsteğe bağlı"),
        ),
        successMessage = "Aldık, teşekkürler!", maxPerTenMinutes = 3,
    )
    private val config = ServerConfig(collections = listOf(leads), forms = listOf(form))
    private lateinit var db: SqlDriver
    private lateinit var svc: RecordService
    private val events = mutableListOf<String>()

    @BeforeTest
    fun open() {
        db = TestDb.create(Dialect.SQLITE).open()
        SchemaMigrator(db).migrate(config.schemaCollections())
        svc = RecordService(db, leads, collections = config.schemaCollections())
    }

    @AfterTest
    fun close() = db.close()

    private fun token(page: String) = Regex("name=\"_t\" value=\"([^\"]+)\"").find(page)!!.groupValues[1]

    @Test
    fun `form gosterilir, gonderim kayit ekler, hatalar alanin altinda`() = testApplication {
        var now = 1_000_000L
        val guard = FormGuard(minFillMs = 2_000, clock = { now })
        val redirecting = form.copy(name = "basvuru", redirect = "https://example.com/tesekkurler", fields = listOf(FormField("name"), FormField("email")))
        application {
            routing { formPages("sunucum", listOf(form, redirecting), mapOf("leads" to leads), mapOf("leads" to svc), WriteGate(), guard) { c, a, r -> events += "$c.${a.wire}#${r["id"]}" } }
        }
        val page = client.get("/forms/iletisim").bodyAsText()
        assertTrue("<h1>Bize ulaşın</h1>" in page && "En kısa sürede dönüyoruz." in page, page)
        assertTrue("type=\"email\"" in page && "<textarea" in page && "<option>Satış</option>" in page && "maxlength=\"80\"" in page, page)
        assertTrue("name=\"f.score\"" !in page, "hesaplanan alan formda olmaz")
        assertTrue("name=\"website\"" in page, "tuzak alanı")
        val t = token(page)

        // Çok hızlı gönderim reddedilir, değerler korunur.
        val fast = client.submitForm("/forms/iletisim", parameters { append("_t", t); append("f.name", "Ayşe"); append("f.email", "a@example.com") })
        assertEquals(HttpStatusCode.BadRequest, fast.status)
        assertTrue("çok hızlı" in fast.bodyAsText() && "value=\"Ayşe\"" in fast.bodyAsText())

        now += 5_000
        val ok = client.submitForm("/forms/iletisim", parameters {
            append("_t", t); append("f.name", "Ayşe"); append("f.email", "a@example.com"); append("f.topic", "Destek")
            append("f.tags", "a"); append("f.tags", "c"); append("f.count", "3"); append("f.consent", "true"); append("f.note", "Merhaba")
        })
        assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
        assertTrue("Aldık, teşekkürler!" in ok.bodyAsText())
        val saved = svc.list(1, 10, null, emptyList()).items.single()
        assertEquals("Ayşe", saved["name"]!!.jsonPrimitive.content)
        assertEquals("[\"a\",\"c\"]", saved["tags"].toString())
        assertEquals("3", saved["count"].toString())
        assertEquals("true", saved["consent"].toString())
        assertEquals(listOf("leads.create#${saved["id"]}"), events)

        // Sunucu doğrulaması: alan hatası ilgili alanın altında, girilen değerler korunur.
        val bad = client.submitForm("/forms/iletisim", parameters { append("_t", t); append("f.name", "Ali"); append("f.email", "yanlis"); append("f.count", "99") })
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        val badPage = bad.bodyAsText()
        assertTrue("class=\"e\"" in badPage && "value=\"Ali\"" in badPage, badPage)
        assertEquals(1, svc.list(1, 10, null, emptyList()).totalItems)

        // Tuzak alanı dolu: başarılı görünür ama kaydedilmez.
        val bot = client.submitForm("/forms/iletisim", parameters { append("_t", t); append("website", "http://spam"); append("f.name", "Bot"); append("f.email", "b@example.com") })
        assertEquals(HttpStatusCode.OK, bot.status)
        assertEquals(1, svc.list(1, 10, null, emptyList()).totalItems)

        // Sahte veya süresi dolmuş imza.
        assertEquals(HttpStatusCode.BadRequest, client.submitForm("/forms/iletisim", parameters { append("_t", "1.abc"); append("f.name", "X"); append("f.email", "x@example.com") }).status)
        now += 3 * 3_600_000
        assertTrue("süresi doldu" in client.submitForm("/forms/iletisim", parameters { append("_t", t); append("f.name", "X"); append("f.email", "x@example.com") }).bodyAsText())

        // Yönlendirmeli form ve adres başına sınır (3 / 10 dk; yukarıdaki hatalı denemeler de sayılır).
        val t2 = token(client.get("/forms/basvuru").bodyAsText())
        now += 5_000
        val redirected = client.config { followRedirects = false }.submitForm("/forms/basvuru", parameters { append("_t", t2); append("f.name", "Can"); append("f.email", "c@example.com") })
        assertEquals(HttpStatusCode.Found, redirected.status)
        assertEquals("https://example.com/tesekkurler", redirected.headers[HttpHeaders.Location])
        repeat(2) { client.submitForm("/forms/basvuru", parameters { append("_t", t2); append("f.name", "Can"); append("f.email", "c@example.com") }) }
        assertEquals(HttpStatusCode.TooManyRequests, client.submitForm("/forms/basvuru", parameters { append("_t", t2); append("f.name", "Can"); append("f.email", "c@example.com") }).status)
        assertEquals(HttpStatusCode.NotFound, client.get("/forms/yok").status)
    }

    @Test
    fun `sunucuya bagli, kapali form yayinlanmaz`() = testApplication {
        val cfg = config.copy(forms = listOf(form, form.copy(name = "kapali", enabled = false)))
        assertEquals(emptyList(), ConfigValidator.validate(cfg))
        application { droidServerModule(cfg, db) }
        val res = client.get("/forms/iletisim")
        assertEquals(HttpStatusCode.OK, res.status)
        assertTrue(res.headers["Content-Security-Policy"]!!.contains("frame-ancestors 'none'"))
        assertEquals(HttpStatusCode.NotFound, client.get("/forms/kapali").status)
    }

    @Test
    fun `dogrulama`() {
        fun errors(vararg forms: FormConfig) = ConfigValidator.validate(config.copy(forms = forms.toList()))
        assertEquals(emptyList(), errors(form))
        assertEquals(emptyList(), errors(form.copy(fields = emptyList())), "boşsa tüm yazılabilir alanlar")
        assertTrue(errors(form, form).any { "iki kez" in it })
        assertTrue(errors(form.copy(name = "Büyük Harf")).isNotEmpty())
        assertTrue(errors(form.copy(collection = "yok")).any { "'yok'" in it })
        assertTrue(errors(form.copy(fields = listOf(FormField("name")))).any { "zorunlu 'email'" in it })
        assertTrue(errors(form.copy(fields = form.fields + FormField("score"))).any { "hesaplanan" in it })
        assertTrue(errors(form.copy(fields = form.fields + FormField("yok"))).any { "'yok' alanı yok" in it })
        assertTrue(errors(form.copy(redirect = "javascript:alert(1)")).any { "yönlendirme" in it })
        assertFalse(errors(form.copy(redirect = "/tesekkurler")).any { "yönlendirme" in it })
        assertTrue(errors(form.copy(title = " ")).any { "başlığı boş" in it })
        val sameBase = config.copy(server = config.server.copy(basePath = "/forms"), forms = listOf(form))
        assertTrue(ConfigValidator.validate(sameBase).any { "/forms" in it })
        assertEquals(config.copy(forms = listOf(form)), ServerConfig.fromJson(config.copy(forms = listOf(form)).toJson()))
    }
}
