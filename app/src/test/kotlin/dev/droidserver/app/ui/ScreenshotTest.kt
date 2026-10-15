package dev.droidserver.app.ui

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.droidserver.app.ui.theme.DroidServerTheme
import dev.droidserver.app.ui.theme.ThemeSettings
import dev.droidserver.core.config.AiConfig
import dev.droidserver.core.config.AutomationAction
import dev.droidserver.core.config.AutomationActionType
import dev.droidserver.core.config.AutomationConfig
import dev.droidserver.core.config.AutomationTrigger
import dev.droidserver.core.config.FormConfig
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

    @Test
    fun widget() {
        running()
        for ((name, dark) in listOf("20-widget" to false, "21-widget-koyu" to true)) {
            val ctx = if (!dark) app else app.createConfigurationContext(
                android.content.res.Configuration(app.resources.configuration).apply {
                    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES or (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv())
                },
            )
            val v = dev.droidserver.app.service.StatusWidget.views(app).apply(ctx, android.widget.FrameLayout(ctx))
            val w = (360 * app.resources.displayMetrics.density).toInt()
            val h = (150 * app.resources.displayMetrics.density).toInt()
            v.measure(android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY))
            v.layout(0, 0, w, h)
            val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            v.draw(android.graphics.Canvas(bmp))
            dir.mkdirs()
            File(dir, "$name.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
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
}
