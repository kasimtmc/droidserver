package dev.droidserver.core

import dev.droidserver.core.api.droidServerModule
import dev.droidserver.core.config.AuthConfig
import dev.droidserver.core.config.AuthMode
import dev.droidserver.core.config.ChartKind
import dev.droidserver.core.config.CollectionConfig
import dev.droidserver.core.config.ConfigValidator
import dev.droidserver.core.config.DashboardConfig
import dev.droidserver.core.config.FieldConfig
import dev.droidserver.core.config.FieldType
import dev.droidserver.core.config.PageConfig
import dev.droidserver.core.config.PageLayout
import dev.droidserver.core.config.ReportConfig
import dev.droidserver.core.config.Rule
import dev.droidserver.core.config.Rules
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.config.SystemCollections
import dev.droidserver.core.config.UsersConfig
import dev.droidserver.core.config.ViewConfig
import dev.droidserver.core.config.WidgetConfig
import dev.droidserver.core.config.WidgetType
import dev.droidserver.core.db.SchemaMigrator
import dev.droidserver.core.db.SqlDriver
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.parameters
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Faz 18.6: kodsuz sayfalar (liste, detay, arama, sıralama, giriş) ve panolar. */
class PagesTest {
    private val key = "yonetici-anahtari-1234567890"
    private val posts = CollectionConfig(
        "posts",
        listOf(
            FieldConfig("title", FieldType.TEXT, required = true),
            FieldConfig("body", FieldType.TEXT),
            FieldConfig("status", FieldType.SELECT, options = listOf("taslak", "yayında")),
            FieldConfig("score", FieldType.INTEGER),
            FieldConfig("secret", FieldType.TEXT, visibility = dev.droidserver.core.config.FieldVisibility.ADMIN),
        ),
        rules = Rules(Rule.PUBLIC, Rule.PUBLIC, Rule.ADMIN, Rule.ADMIN, Rule.ADMIN),
        search = listOf("title", "body"),
    )
    private val notes = CollectionConfig(
        "notes",
        listOf(FieldConfig("text", FieldType.TEXT, required = true), FieldConfig("owner", FieldType.RELATION, collection = SystemCollections.USERS)),
        rules = Rules(Rule.OWNER, Rule.OWNER, Rule.AUTH, Rule.OWNER, Rule.OWNER),
        ownerField = "owner",
    )
    private val config = ServerConfig(
        auth = AuthConfig(AuthMode.API_KEY, apiKeys = listOf(key), users = UsersConfig(enabled = true, sendVerification = false)),
        collections = listOf(posts, notes),
        views = listOf(
            ViewConfig("by-status", "posts", report = ReportConfig(groupBy = "status", sum = listOf("score"))),
            ViewConfig("total", "posts", report = ReportConfig(sum = listOf("score"))),
            ViewConfig("latest", "posts", columns = listOf("title", "score"), sort = "-score"),
        ),
        pages = listOf(
            PageConfig("blog", "posts", "Blog", description = "Yazılar", fields = listOf("title", "score"), filter = "status=yayında", sort = "title", perPage = 2),
            PageConfig("tablo", "posts", "Tablo", layout = PageLayout.TABLE, detail = false),
            PageConfig("notlarim", "notes", "Notlarım", login = true, accent = "#aa3300"),
        ),
        dashboards = listOf(
            DashboardConfig(
                "ozet", "Özet",
                widgets = listOf(
                    WidgetConfig(WidgetType.NUMBER, "total", title = "Toplam puan", metric = "sum_score"),
                    WidgetConfig(WidgetType.CHART, "by-status", chart = ChartKind.BAR),
                    WidgetConfig(WidgetType.TABLE, "latest", limit = 2, width = 2),
                ),
                shareToken = "paylasim-anahtari-123456",
            ),
        ),
    )
    private lateinit var db: SqlDriver

    @Before
    fun open() {
        db = TestDb.create(Dialect.SQLITE).open()
        SchemaMigrator(db).migrate(config.schemaCollections())
    }

    @After
    fun close() = db.close()

    @Test
    fun `herkese acik liste ve detay`() = testApplication {
        application { droidServerModule(config, db) }
        listOf("Elma" to 3, "Armut" to 5, "Kiraz" to 1, "Gizli taslak" to 9).forEachIndexed { i, (t, s) ->
            client.post("/api/collections/posts/records") {
                bearerAuth(key); contentType(ContentType.Application.Json)
                setBody("""{"title":"$t","body":"<b>kalın</b> satır\nikinci","status":"${if (i == 3) "taslak" else "yayında"}","score":$s,"secret":"çok gizli"}""")
            }
        }
        val list = client.get("/pages/blog")
        assertEquals(200, list.status.value)
        val html = list.bodyAsText()
        assertTrue("<title>Blog" in html && "Yazılar" in html, html)
        // Sabit süzgeç ve sıralama (title), sayfa başına 2.
        assertTrue("Armut" in html && "Elma" in html && "Kiraz" !in html && "Gizli taslak" !in html, html)
        assertTrue("Sayfa 1 / 2" in html && "page=2" in html)
        assertTrue("default-src 'none'" in list.headers["Content-Security-Policy"].orEmpty())
        assertTrue("<script" !in html)
        assertTrue("Kiraz" in client.get("/pages/blog?page=2").bodyAsText())
        // Ziyaretçi sıralaması yalnızca listedeki alanlarla; arama.
        assertTrue(client.get("/pages/blog?sort=-score").bodyAsText().let { it.indexOf("Armut") < it.indexOf("Elma") })
        assertTrue(client.get("/pages/blog?sort=secret").bodyAsText().let { it.indexOf("Armut") < it.indexOf("Elma") }, "geçersiz sıralama yok sayılır")
        val found = client.get("/pages/blog?q=kiraz").bodyAsText()
        assertTrue("Kiraz" in found && "Elma" !in found)

        // Detay: gizli alan yok, metin kaçışlı ve satır sonları korunur.
        val detail = client.get("/pages/blog/1").bodyAsText()
        assertTrue("Elma" in detail && "&lt;b&gt;kalın&lt;/b&gt; satır<br>ikinci" in detail, detail)
        assertFalse("çok gizli" in detail || "secret" in detail)
        assertEquals(404, client.get("/pages/blog/999").status.value)
        // Tablo düzeni; detay kapalı.
        val table = client.get("/pages/tablo").bodyAsText()
        assertTrue("<table>" in table && "/pages/tablo/1" !in table, table)
        assertEquals(404, client.get("/pages/tablo/1").status.value)
    }

    @Test
    fun `giris gereken sayfa ve oturum`() = testApplication {
        application { droidServerModule(config, db) }
        val browser = createClient { install(HttpCookies); followRedirects = false }
        val ali = Json.parseToJsonElement(client.post("/api/auth/signup") { contentType(ContentType.Application.Json); setBody("""{"email":"ali@example.com","password":"parola-1234"}""") }.bodyAsText()).jsonObject["token"]!!.jsonPrimitive.content
        val ayse = Json.parseToJsonElement(client.post("/api/auth/signup") { contentType(ContentType.Application.Json); setBody("""{"email":"ayse@example.com","password":"parola-1234"}""") }.bodyAsText()).jsonObject["token"]!!.jsonPrimitive.content
        client.post("/api/collections/notes/records") { bearerAuth(ali); contentType(ContentType.Application.Json); setBody("""{"text":"Ali'nin notu"}""") }
        client.post("/api/collections/notes/records") { bearerAuth(ayse); contentType(ContentType.Application.Json); setBody("""{"text":"Ayşe'nin notu"}""") }

        val gate = browser.get("/pages/notlarim")
        assertEquals(302, gate.status.value)
        assertEquals("/pages/_login?next=%2Fpages%2Fnotlarim", gate.headers["Location"])
        assertTrue("<form class=\"login\"" in browser.get("/pages/_login?next=/pages/notlarim").bodyAsText())
        val bad = browser.submitForm("/pages/_login", parameters { append("email", "ali@example.com"); append("password", "yanlis-parola"); append("next", "/pages/notlarim") })
        assertEquals(401, bad.status.value)
        val ok = browser.submitForm("/pages/_login", parameters { append("email", "ali@example.com"); append("password", "parola-1234"); append("next", "/pages/notlarim") })
        assertEquals(302, ok.status.value)
        assertEquals("/pages/notlarim", ok.headers["Location"])
        assertTrue(ok.headers.getAll("Set-Cookie").orEmpty().all { "HttpOnly" in it && "Path=/pages" in it && "SameSite=Lax" in it })
        val mine = browser.get("/pages/notlarim").bodyAsText()
        assertTrue("Ali'nin notu".replace("'", "&#39;") in mine || "Ali'nin notu" in mine, mine)
        assertFalse("Ayşe" in mine, "kural (sahip) uygulanır")
        assertTrue("--accent:#aa3300" in mine && "Çıkış yap" in mine)
        // Dışarıya yönlendirme yok.
        val evil = browser.submitForm("/pages/_login", parameters { append("email", "ali@example.com"); append("password", "parola-1234"); append("next", "//kotu.example.com") })
        assertEquals("/", evil.headers["Location"])
        // Çıkış.
        browser.submitForm("/pages/_logout", parameters { append("next", "/pages/notlarim") })
        assertEquals(302, browser.get("/pages/notlarim").status.value)
    }

    @Test
    fun `pano verisi ve paylasim`() = testApplication {
        application { droidServerModule(config, db) }
        listOf("a" to "taslak" to 2, "b" to "yayında" to 5, "c" to "yayında" to 7).forEach { (ts, s) ->
            client.post("/api/collections/posts/records") { bearerAuth(key); contentType(ContentType.Application.Json); setBody("""{"title":"${ts.first}","status":"${ts.second}","score":$s}""") }
        }
        val data = Json.parseToJsonElement(client.get("/api/_dashboards/ozet") { bearerAuth(key) }.bodyAsText()).jsonObject
        val widgets = data.getValue("widgets").jsonArray.map { it.jsonObject }
        assertEquals("14", widgets[0].getValue("value").jsonPrimitive.content.removeSuffix(".0"))
        assertEquals("Toplam puan", widgets[0].getValue("title").jsonPrimitive.content)
        val points = widgets[1].getValue("points").jsonArray.map { it.jsonObject.getValue("label").jsonPrimitive.content to it.jsonObject.getValue("value").jsonPrimitive.content }
        assertEquals(listOf("taslak" to "1.0", "yayında" to "2.0"), points)
        assertEquals(listOf("c", "b"), widgets[2].getValue("rows").jsonArray.map { it.jsonArray[0].jsonPrimitive.content })
        assertEquals(401, client.get("/api/_dashboards/ozet").status.value)

        val share = client.get("/dashboards/ozet?token=paylasim-anahtari-123456")
        assertEquals(200, share.status.value)
        val html = share.bodyAsText()
        assertTrue("Toplam puan" in html && "<svg" in html && "<table>" in html && "<script" !in html, html)
        assertEquals(404, client.get("/dashboards/ozet?token=yanlis").status.value)
        assertEquals(404, client.get("/dashboards/ozet").status.value)
    }

    @Test
    fun `sayfa ve pano dogrulamasi`() {
        val bad = config.copy(
            auth = config.auth.copy(users = UsersConfig()),
            pages = listOf(PageConfig("Kötü Ad", "yok", ""), PageConfig("x", "posts", "X", fields = listOf("nope"), accent = "red", login = true, perPage = 0)),
            dashboards = listOf(DashboardConfig("d", "D", listOf(WidgetConfig(WidgetType.CHART, "total"), WidgetConfig(WidgetType.NUMBER, "latest"), WidgetConfig(WidgetType.MAP, "latest", field = "title"), WidgetConfig(WidgetType.TABLE, "yok")), shareToken = "kisa")),
        )
        val errors = ConfigValidator.validateDetailed(bad).map { it.path }
        for (p in listOf("pages[0].name", "pages[0].title", "pages[0].collection", "pages[1].fields", "pages[1].accent", "pages[1].login", "pages[1].perPage",
            "dashboards[0].shareToken", "dashboards[0].widgets[0].view", "dashboards[0].widgets[1].view", "dashboards[0].widgets[2].field", "dashboards[0].widgets[3].view")) {
            assertTrue(p in errors, "$p yok: $errors")
        }
    }
}
