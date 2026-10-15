package dev.droidserver.app.service

import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import dev.droidserver.app.R
import dev.droidserver.app.ui.TestDroidServerApp
import dev.droidserver.core.config.ServerConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** Hızlı ayar ve widget: durum metinleri ve aç/kapat. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "tr-rTR", application = TestDroidServerApp::class)
class QuickControlsTest {
    private val app get() = ApplicationProvider.getApplicationContext<TestDroidServerApp>()

    @After
    fun tearDown() {
        runCatching { app.graph.engine.stop() }
    }

    private fun render(): View = StatusWidget.views(app).apply(app, FrameLayout(app))

    private fun View.text(id: Int) = (findViewById<TextView>(id)).text.toString()

    @Test
    fun `konfigurasyon yokken uygulama acilir, widget kapali gosterir`() {
        assertFalse(QuickControls.toggle(app))
        val v = render()
        assertEquals("Kapalı", v.text(R.id.widget_status))
        assertEquals("Başlatmak için dokunun", v.text(R.id.widget_detail))
        assertEquals("Başlat", v.text(R.id.widget_toggle))
    }

    @Test
    fun `calisirken adres, istek ve durdur`() {
        val text = ServerConfig.fromJson(app.assets.open("default-config.json").bufferedReader().readText()).toJson()
        File(app.graph.dataRoot, "config").apply { mkdirs() }.resolve("server.json").writeText(text)
        val cfg = ServerConfig.fromJson(text).let { it.copy(server = it.server.copy(port = 18081)) }
        app.graph.engine.deploy(cfg)

        val v = render()
        assertEquals("Çalışıyor", v.text(R.id.widget_status))
        assertTrue(v.text(R.id.widget_detail), v.text(R.id.widget_detail).endsWith(":18081"))
        assertTrue(v.text(R.id.widget_stats), v.text(R.id.widget_stats).startsWith("Son 1 saat: "))
        assertEquals("Durdur", v.text(R.id.widget_toggle))

        // Çalışırken dokunmak servise durdurma isteği gönderir.
        assertTrue(QuickControls.toggle(app))
        val started = shadowOf(app).nextStartedService
        assertEquals("dev.droidserver.STOP", started.action)
    }
}
