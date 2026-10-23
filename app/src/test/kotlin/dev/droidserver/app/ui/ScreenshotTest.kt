package dev.droidserver.app.ui

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.droidserver.app.ui.theme.DroidServerTheme
import dev.droidserver.app.ui.theme.ThemeSettings
import dev.droidserver.core.config.AiConfig
import dev.droidserver.core.config.AutomationAction
import dev.droidserver.core.config.AutomationActionType
import dev.droidserver.core.config.AutomationConfig
import dev.droidserver.core.config.AutomationTrigger
import dev.droidserver.core.config.CollectionConfig
import dev.droidserver.core.config.FieldConfig
import dev.droidserver.core.config.FieldType
import dev.droidserver.core.config.FormConfig
import dev.droidserver.core.config.HomeAssistantConfig
import dev.droidserver.core.config.MqttConfig
import dev.droidserver.core.config.MqttMessage
import dev.droidserver.core.config.MqttScene
import dev.droidserver.core.config.SensorConfig
import dev.droidserver.core.config.ServerConfig
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Ekran görüntüleri (tasarım denetimi için; normal test koşusunda atlanır):
 * `./gradlew :app:testDebugUnitTest -Pscreenshots --tests ScreenshotTest` → `build/screenshots` klasörüne PNG.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "tr-rTR-w400dp-h860dp-xxhdpi", application = TestDroidServerApp::class)
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val dir = File(System.getProperty("ds.screenshotDir") ?: "build/screenshots")
    private val app get() = ApplicationProvider.getApplicationContext<TestDroidServerApp>()

    @Before
    fun setUp() {
        assumeTrue("yalnızca -Pscreenshots ile", System.getProperty("ds.screenshots") == "true")
        ThemeSettings.mode = "light"
    }

    @After
    fun tearDown() {
        runCatching { app.graph.engine.stop() }
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(dir, "$name.png").path)
    }

    private fun click(text: String) {
        val node = compose.onAllNodes(hasText(text).or(hasContentDescription(text))).onLast()
        runCatching { node.performScrollTo() }
        node.performClick()
        compose.waitForIdle()
    }

    private fun running(change: (ServerConfig) -> ServerConfig = { it }): ServerConfig {
        val base = ServerConfig.fromJson(app.assets.open("default-config.json").bufferedReader().readText())
        val text = change(base).toJson()
        File(app.graph.dataRoot, "config").apply { mkdirs() }.resolve("server.json").writeText(text)
        val cfg = ServerConfig.fromJson(text).let { it.copy(server = it.server.copy(port = 18080)) }
        app.graph.engine.deploy(cfg)
        return cfg
    }

    private fun render() {
        compose.setContent { DroidServerTheme { AppNavigation(app.graph) } }
        compose.waitForIdle()
    }

    @Test
    fun ilkAcilis() {
        compose.setContent {
            DroidServerTheme {
                val vm: dev.droidserver.app.ui.wizard.WizardViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = dev.droidserver.app.ui.wizard.WizardViewModel.factory(app.graph))
                dev.droidserver.app.ui.status.StatusScreen(app.graph, vm, onStartWizard = {})
            }
        }
        shot("01-karsilama")
    }

    @Test
    fun calisanSunucu() {
        running()
        render()
        shot("02-durum")
        click("Ayarlar")
        shot("03-ayarlar")
    }

    @Test
    fun koyuTema() {
        ThemeSettings.mode = "dark"
        running()
        render()
        shot("04-durum-koyu")
        click("Sunucu")
        shot("05-sunucu-koyu")
    }

    @Test
    fun sunucuVeBilesenler() {
        running()
        render()
        click("Sunucu")
        shot("06-sunucu")
        click("Bileşenler")
        shot("07-bilesenler")
        click("Loglar")
        shot("08-loglar")
    }

    @Test
    fun sihirbazVeAraclar() {
        running()
        render()
        click("Sunucu")
        click("Sihirbazla düzenle")
        shot("09-sihirbaz")
        click("İleri: Veritabanı")
        shot("10-sihirbaz-veritabani")
    }

    @Test
    fun bosEkranlar() {
        running()
        render()
        click("Sunucu")
        click("Uygulamalar (0)")
        shot("11-uygulamalar-bos")
    }

    @Test
    fun ortamBos() {
        running()
        render()
        click("Sunucu")
        click("Ortam ve sırlar (.env)")
        shot("12-ortam-bos")
    }

    @Test
    fun otomasyonlar() {
        running { c ->
            val todos = c.collections.first().name
            c.copy(
                automations = listOf(
                    AutomationConfig(
                        "yeni-is", AutomationTrigger(collection = todos, events = listOf("create")), condition = "done=false",
                        actions = listOf(
                            AutomationAction(AutomationActionType.LOG, body = "Yeni iş: {title}"),
                            AutomationAction(AutomationActionType.UPDATE, values = mapOf("done" to "false")),
                        ),
                    ),
                    AutomationConfig("sabah-ozeti", AutomationTrigger(collection = todos, schedule = "0 8 * * *"), actions = listOf(AutomationAction(AutomationActionType.LOG, body = "{count} açık iş"))),
                ),
            )
        }
        render()
        click("Sunucu")
        click("Otomasyonlar (2)")
        click("Şimdi dene")
        shot("13-otomasyonlar")
        click("yeni-is")
        shot("14-otomasyon-duzenle")
    }

    @Test
    fun formlar() {
        running { c -> c.copy(forms = listOf(FormConfig("iletisim", "notes", "Bize ulaşın", description = "Notunuzu bırakın"))) }
        render()
        click("Sunucu")
        click("Formlar (1)")
        shot("15-formlar")
        click("Bize ulaşın")
        shot("16-form-duzenle")
    }

    @Test
    fun yapayZeka() {
        running { c -> c.copy(ai = AiConfig(enabled = true, apiKey = "sk-ant-ornek", dailyLimit = 20, system = "Kısa ve Türkçe yanıtla.")) }
        render()
        click("Sunucu")
        click("Yapay zekâ")
        shot("17-yapay-zeka")
        click("Voyage AI")
        shot("18-anlamsal-arama")
        click("OpenAI")
        shot("19-saglayici-openai")
    }

    /** Widget'ı açık ve koyu temada 360x150 dp olarak çizer. */
    private fun widgetShots(light: String, darkName: String, views: () -> android.widget.RemoteViews) {
        for ((file, dark) in listOf(light to false, darkName to true)) {
            val ctx = if (!dark) app else app.createConfigurationContext(
                android.content.res.Configuration(app.resources.configuration).apply {
                    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES or (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv())
                },
            )
            val v = views().apply(ctx, android.widget.FrameLayout(ctx))
            val w = (360 * app.resources.displayMetrics.density).toInt()
            val h = (150 * app.resources.displayMetrics.density).toInt()
            v.measure(android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY))
            v.layout(0, 0, w, h)
            val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            v.draw(android.graphics.Canvas(bmp))
            dir.mkdirs()
            File(dir, "$file.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test
    fun widget() {
        running()
        widgetShots("20-widget", "21-widget-koyu") { dev.droidserver.app.service.StatusWidget.views(app) }
    }

    @Test
    fun tasker() {
        running()
        app.graph.automation.enabled = true
        app.graph.automation.packages = setOf("net.dinglisch.android.taskerm")
        render()
        click("Ayarlar")
        click("Anahtarı kopyala")
        shot("22-tasker")
    }

    @Test
    fun agSurucusu() {
        running { c -> c.copy(webdav = dev.droidserver.core.config.WebDavConfig(enabled = true, password = "gizli-parola-1", site = true)) }
        render()
        click("Sunucu")
        click("Ağ sürücüsü")
        shot("23-ag-surucusu")
    }

    @Test
    fun sensorPanosu() {
        running { c ->
            val readings = CollectionConfig("readings", listOf(FieldConfig("temp", FieldType.REAL), FieldConfig("device", FieldType.TEXT)))
            c.copy(
                collections = c.collections + readings,
                mqtt = MqttConfig(enabled = true, password = "cihaz-parolasi", homeAssistant = HomeAssistantConfig(enabled = true)),
                sensors = listOf(SensorConfig("readings", "temp", name = "Salon", unit = "°C", min = 15.0, max = 28.0)),
            )
        }
        render()
        click("Sunucu")
        click("Sensör panosu")
        shot("24-sensor-panosu")
    }

    @Test
    fun harita() {
        val cfg = running { c ->
            c.copy(collections = c.collections + CollectionConfig("places", listOf(FieldConfig("name", FieldType.TEXT), FieldConfig("loc", FieldType.GEO))))
        }
        for ((name, lat, lng) in listOf(Triple("Sultanahmet", 41.0054, 28.9768), Triple("Kadıköy", 40.99, 29.028), Triple("Beşiktaş", 41.0422, 29.0067))) {
            val c = java.net.URI("http://127.0.0.1:18080/api/collections/places/records").toURL().openConnection() as java.net.HttpURLConnection
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Authorization", "Bearer " + cfg.auth.apiKeys.first())
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write("""{"name":"$name","loc":{"lat":$lat,"lng":$lng}}""".toByteArray()) }
            check(c.responseCode == 201) { c.responseCode }
        }
        render()
        click("Sunucu")
        click("Harita")
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("3 konumlu kayıt", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        shot("31-harita")
    }

    @Test
    fun sayfalarVePanolar() {
        val cfg = running { c ->
            val posts = CollectionConfig("posts", listOf(FieldConfig("title", FieldType.TEXT), FieldConfig("status", FieldType.SELECT, options = listOf("taslak", "yayında")), FieldConfig("score", FieldType.INTEGER)))
            c.copy(
                collections = c.collections + posts,
                views = listOf(
                    dev.droidserver.core.config.ViewConfig("durum", "posts", report = dev.droidserver.core.config.ReportConfig(groupBy = "status", sum = listOf("score"))),
                    dev.droidserver.core.config.ViewConfig("toplam", "posts", report = dev.droidserver.core.config.ReportConfig(sum = listOf("score"))),
                    dev.droidserver.core.config.ViewConfig("son", "posts", columns = listOf("title", "score"), sort = "-score"),
                ),
                pages = listOf(dev.droidserver.core.config.PageConfig("blog", "posts", "Blog", filter = "status=yayında")),
                dashboards = listOf(dev.droidserver.core.config.DashboardConfig("ozet", "Özet", listOf(
                    dev.droidserver.core.config.WidgetConfig(dev.droidserver.core.config.WidgetType.NUMBER, "toplam", title = "Toplam puan", metric = "sum_score"),
                    dev.droidserver.core.config.WidgetConfig(dev.droidserver.core.config.WidgetType.CHART, "durum"),
                    dev.droidserver.core.config.WidgetConfig(dev.droidserver.core.config.WidgetType.TABLE, "son", limit = 3),
                ), shareToken = "paylasim-anahtari-123456")),
            )
        }
        for ((t, st, sc) in listOf(Triple("Elma", "yayında", 5), Triple("Armut", "taslak", 3), Triple("Kiraz", "yayında", 8))) {
            val c = java.net.URI("http://127.0.0.1:18080/api/collections/posts/records").toURL().openConnection() as java.net.HttpURLConnection
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Authorization", "Bearer " + cfg.auth.apiKeys.first())
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write("""{"title":"$t","status":"$st","score":$sc}""".toByteArray()) }
            check(c.responseCode == 201) { c.responseCode }
        }
        render()
        click("Sunucu")
        click("Panolar (1)")
        click("Özet")
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Toplam puan")).fetchSemanticsNodes().isNotEmpty() }
        shot("32-pano")
        click("Geri")
        click("Geri")
        click("Sayfalar (1)")
        shot("33-sayfalar")
    }

    @Test
    fun paketler() {
        running { c ->
            c.copy(
                collections = c.collections + CollectionConfig("entries", listOf(FieldConfig("text", FieldType.TEXT))),
                packages = listOf(dev.droidserver.core.packages.InstalledPackage("defter", "1.2.0", "Defter", "Örnek Ltd", "ab:cd:ef:01:23:45:67:89:ab:cd:ef:01:23:45:67:89", collections = listOf("entries"))),
            )
        }
        render()
        click("Sunucu")
        click("Paketler (1)")
        shot("34-paketler")
    }

    @Test
    fun odemeler() {
        running { c ->
            c.copy(
                auth = c.auth.copy(users = c.auth.users.copy(enabled = true, roles = listOf(dev.droidserver.core.config.RoleConfig("pro")))),
                access = c.access.copy(
                    mode = dev.droidserver.core.config.AccessMode.CLOUDFLARE,
                    cloudflare = c.access.cloudflare.copy(tunnel = dev.droidserver.core.config.CloudflareTunnel.NAMED, hostname = "magaza.example.com"),
                ),
                payments = dev.droidserver.core.config.PaymentsConfig(
                    providers = listOf(
                        dev.droidserver.core.config.PaymentProviderConfig(dev.droidserver.core.config.PaymentProvider.STRIPE, "sk_test_abc", webhookSecret = "whsec_abc"),
                        dev.droidserver.core.config.PaymentProviderConfig(dev.droidserver.core.config.PaymentProvider.IYZICO, "sandbox-gizli", apiKey = "sandbox-api", sandbox = true),
                    ),
                    publicUrl = "https://magaza.example.com",
                    plans = listOf(dev.droidserver.core.config.PaymentPlan("pro", "Pro (aylık)", dev.droidserver.core.config.PaymentProvider.STRIPE, "price_123", "pro")),
                ),
            )
        }
        render()
        click("Sunucu")
        click("Ödemeler")
        shot("36-odemeler")
    }

    @Test
    fun yerelModeller() {
        compose.setContent { DroidServerTheme { dev.droidserver.app.ui.config.LocalModelsScreen(app.graph, onBack = {}) } }
        shot("35-yerel-modeller")
    }

    @Test
    fun sahneWidgeti() {
        running { c ->
            c.copy(mqtt = MqttConfig(enabled = true, password = "cihaz-parolasi", scenes = listOf(
                MqttScene("Akşam", listOf(MqttMessage("ev/salon/lamba", "on"))), MqttScene("Sabah", listOf(MqttMessage("ev/perde", "acik"))),
                MqttScene("Dışarıdayım", listOf(MqttMessage("ev/hepsi", "off"))),
            )))
        }
        widgetShots("25-sahne-widget", "26-sahne-widget-koyu") { dev.droidserver.app.service.SceneWidget.views(app) }
    }

    @Test
    fun rehber() {
        compose.setContent { DroidServerTheme { dev.droidserver.app.ui.help.OnboardingScreen(onDone = {}) } }
        shot("27-rehber")
        repeat(3) { click("İleri") }
        shot("28-rehber-baslangic")
    }

    @Test
    fun yardim() {
        running()
        render()
        click("Yardım")
        shot("29-yardim")
        compose.onNodeWithText("Yardımda ara").performTextInput("MQTT")
        click("MQTT (IoT)")
        shot("30-yardim-bolum")
    }
}
