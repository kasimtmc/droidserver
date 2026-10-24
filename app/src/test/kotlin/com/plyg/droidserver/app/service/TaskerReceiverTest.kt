package com.plyg.droidserver.app.service

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.plyg.droidserver.app.data.AutomationBridge
import com.plyg.droidserver.app.ui.TestDroidServerApp
import com.plyg.droidserver.core.alerts.Alert
import com.plyg.droidserver.core.alerts.AlertKind
import com.plyg.droidserver.core.config.ServerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** Tasker köprüsü: anahtarsız/yanlış anahtarlı komut yok sayılır, doğru anahtarla servis yönetilir; olaylar yalnızca seçilen uygulamalara. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "tr-rTR", application = TestDroidServerApp::class)
class TaskerReceiverTest {
    private val app get() = ApplicationProvider.getApplicationContext<TestDroidServerApp>()

    private fun send(action: String, token: String?) =
        TaskerReceiver().onReceive(app, Intent(action).apply { token?.let { putExtra(AutomationBridge.EXTRA_TOKEN, it) } })

    private fun nextService() = shadowOf(app).nextStartedService

    @Test
    fun `komutlar anahtarla`() {
        val bridge = app.graph.automation
        // Kapalıyken hiçbir şey yapılmaz.
        send(AutomationBridge.ACTION_STOP, "x")
        assertNull(nextService())

        bridge.enabled = true
        val token = bridge.token()
        send(AutomationBridge.ACTION_STOP, "yanlis")
        send(AutomationBridge.ACTION_STOP, null)
        assertNull(nextService())
        assertTrue(app.graph.logs.lines.value.any { "reddedildi" in it })

        send(AutomationBridge.ACTION_STOP, token)
        assertEquals("com.plyg.droidserver.STOP", nextService()?.action)

        // Konfigürasyon yokken başlatma yapılmaz; varken yapılır.
        send(AutomationBridge.ACTION_START, token)
        assertNull(nextService())
        File(app.graph.dataRoot, "config").apply { mkdirs() }.resolve("server.json").writeText(ServerConfig().toJson())
        send(AutomationBridge.ACTION_START, token)
        assertEquals("com.plyg.droidserver.START", nextService()?.action)
        send(AutomationBridge.ACTION_DEPLOY, token)
        assertEquals("com.plyg.droidserver.DEPLOY", nextService()?.action)

        // Yenilenen anahtardan sonra eskisi geçmez.
        val renewed = bridge.regenerate()
        assertFalse(renewed == token)
        send(AutomationBridge.ACTION_STOP, token)
        assertNull(nextService())
    }

    @Test
    fun `olaylar yalnizca secilen uygulamalara`() {
        val bridge = app.graph.automation
        bridge.started("Ev", 8080, "http://192.168.1.20:8080")
        assertTrue(shadowOf(app).broadcastIntents.none { it.action?.startsWith("com.plyg.droidserver.event") == true })

        bridge.enabled = true
        bridge.packages = setOf("net.dinglisch.android.taskerm", "com.arlosoft.macrodroid")
        bridge.started("Ev", 8080, "http://192.168.1.20:8080")
        bridge.alert("Ev", Alert(AlertKind.DISK, "disk", "Disk doluyor", "Boş alan %5", "2026-10-15T10:00:00Z", critical = true))
        val events = shadowOf(app).broadcastIntents.filter { it.action?.startsWith("com.plyg.droidserver.event") == true }
        assertEquals(4, events.size)
        assertEquals(setOf("net.dinglisch.android.taskerm", "com.arlosoft.macrodroid"), events.map { it.`package` }.toSet())
        val started = events.first { it.action == AutomationBridge.EVENT_STARTED }
        assertEquals("http://192.168.1.20:8080", started.getStringExtra("url"))
        assertEquals("8080", started.getStringExtra("port"))
        val alert = events.first { it.action == AutomationBridge.EVENT_ALERT }
        assertEquals("Disk doluyor", alert.getStringExtra("title"))
        assertEquals("true", alert.getStringExtra("critical"))
    }
}
