package com.plyg.droidserver.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Play sürümünün deneme süresi: başlangıç seçimi, durum ve kalan gün. */
class TrialPolicyTest {
    private val day = TrialPolicy.DAY_MS
    private val t0 = 1_800_000_000_000L

    @Test
    fun `baslangic iki kaydin erkeni, yoksa simdi`() {
        assertEquals(t0, TrialPolicy.start(null, null, t0))
        assertEquals(t0 - 3 * day, TrialPolicy.start(t0, t0 - 3 * day, t0), "yeniden kurulumda Block Store'daki eski kayıt geçer")
        assertEquals(t0 - day, TrialPolicy.start(t0 - day, null, t0))
    }

    @Test
    fun `yedi gun deneme, sonra satin alma gerekir`() {
        assertEquals(License.Trial(t0 + 7 * day), TrialPolicy.license(t0, t0, owned = false))
        assertEquals(License.Trial(t0 + 7 * day), TrialPolicy.license(t0, t0 + 7 * day - 1, owned = false))
        assertEquals(License.Required("₺99,99"), TrialPolicy.license(t0, t0 + 7 * day, owned = false, price = "₺99,99"))
        assertEquals(License.Required(pending = true), TrialPolicy.license(t0, t0 + 30 * day, owned = false, pending = true))
        assertEquals(License.Owned, TrialPolicy.license(t0, t0 + 30 * day, owned = true))
        assertEquals(License.Owned, TrialPolicy.license(t0, t0, owned = true), "denemede satın alınınca")
    }

    @Test
    fun `kalan gun yukari yuvarlanir`() {
        val trial = License.Trial(t0 + 7 * day)
        assertEquals(7, TrialPolicy.daysLeft(trial, t0))
        assertEquals(7, TrialPolicy.daysLeft(trial, t0 + 1))
        assertEquals(1, TrialPolicy.daysLeft(trial, t0 + 7 * day - 1))
        assertEquals(0, TrialPolicy.daysLeft(trial, t0 + 8 * day))
    }

    private fun assertEquals(expected: Any?, actual: Any?, message: String) = org.junit.Assert.assertEquals(message, expected, actual)
}
