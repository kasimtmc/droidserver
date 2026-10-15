package dev.droidserver.core

import dev.droidserver.core.config.AccessMode
import dev.droidserver.core.diagnostics.DiagnosticInput
import dev.droidserver.core.diagnostics.Diagnostics
import dev.droidserver.core.diagnostics.Fix
import dev.droidserver.core.diagnostics.Level
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Sorun giderici kuralları: girdilerden bulgular, önce sorunlar. */
class DiagnosticsTest {
    private val now = Instant.parse("2026-10-14T12:00:00Z")
    private val healthy = DiagnosticInput(
        hasConfig = true, running = true, localHealth = true, localAddresses = listOf("192.168.1.20"), wifi = true, internet = true,
        batteryOptimized = false, notificationsAllowed = true, freeStorageBytes = 8L shl 30, now = now,
    )

    @Test
    fun `saglikli sunucuda sorun yok`() {
        val f = Diagnostics.run(healthy)
        assertTrue(f.none { it.level == Level.PROBLEM || it.level == Level.WARNING }, f.toString())
        assertEquals("http://192.168.1.20:8080", f.first { it.id == "lan" }.detail)
        assertEquals(Fix.OPEN_ACCESS, f.first { it.id == "access" }.fix, "yalnızca yerel ağ: erişimi açma önerisi")
    }

    @Test
    fun `sorunlar once ve dogru cozumle`() {
        val f = Diagnostics.run(
            healthy.copy(
                running = false, internet = false, batteryOptimized = true, notificationsAllowed = false, freeStorageBytes = 100L shl 20,
                failedComponents = listOf("cloudflared"), access = AccessMode.CLOUDFLARE, publicUrl = "https://x.trycloudflare.com", publicHealth = false,
            ),
        )
        assertEquals(Level.PROBLEM, f.first().level)
        val byId = f.groupBy { it.id }
        assertEquals(Fix.START_SERVER, byId.getValue("server").single().fix)
        assertEquals(Fix.BATTERY_SETTINGS, byId.getValue("battery").single().fix)
        assertEquals(Fix.NOTIFICATION_SETTINGS, byId.getValue("notifications").single().fix)
        assertEquals(Level.PROBLEM, byId.getValue("storage").single().level)
        assertEquals(Fix.OPEN_COMPONENTS, byId.getValue("access").single().fix)
        assertEquals(Level.WARNING, byId.getValue("internet").single().level)
        val levels = f.map { it.level.ordinal }
        assertEquals(levels.sortedBy { listOf(3, 2, 1, 0)[it] }, levels, "PROBLEM, WARNING, INFO, OK sırası")
    }

    @Test
    fun `konfigurasyon, yanit vermeyen sunucu ve sertifika`() {
        assertEquals(listOf(Fix.OPEN_WIZARD), Diagnostics.run(DiagnosticInput(hasConfig = false, running = false)).map { it.fix })
        val f = Diagnostics.run(
            healthy.copy(
                configErrors = listOf("port hatalı"), localHealth = false, access = AccessMode.DIRECT,
                directProblems = listOf("Modem yönlendirmesi yapılamadı"), certificateUntil = now.plusSeconds(5 * 86_400L), publicUrl = "https://evim.duckdns.org:8443",
            ),
        )
        val byId = f.groupBy { it.id }
        assertEquals(Level.PROBLEM, byId.getValue("config").single().level)
        assertTrue("8080" in byId.getValue("server").single().detail)
        assertTrue(byId.getValue("access").any { it.detail == "Modem yönlendirmesi yapılamadı" })
        assertEquals(Level.WARNING, byId.getValue("certificate").single().level)
        assertEquals(Level.PROBLEM, Diagnostics.run(healthy.copy(access = AccessMode.DIRECT, certificateUntil = now.minusSeconds(60))).first { it.id == "certificate" }.level)
    }
}
