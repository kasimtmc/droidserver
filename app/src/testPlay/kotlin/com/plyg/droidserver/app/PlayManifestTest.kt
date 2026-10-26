package com.plyg.droidserver.app

import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.plyg.droidserver.app.data.componentModules
import com.plyg.droidserver.app.data.storeServices
import com.plyg.droidserver.app.ui.TestDroidServerApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Play sürümünün birleştirilmiş manifesti (PLAN Faz 19): Play'in kısıtlı izinleri hiç istenmez. Bir kütüphane bu izinleri
 * geri getirirse test düşer; Play incelemesi bu izinlerle uygulamayı reddeder.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TestDroidServerApp::class)
class PlayManifestTest {
    private val app = ApplicationProvider.getApplicationContext<TestDroidServerApp>()

    @Suppress("DEPRECATION")
    private fun permissions(): Set<String> =
        app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()

    @Test
    fun `kisitli izinler yok`() {
        val p = permissions()
        for (forbidden in listOf("android.permission.SEND_SMS", "android.permission.REQUEST_INSTALL_PACKAGES", "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS")) {
            assertFalse("$forbidden Play sürümünde olmamalı: $p", forbidden in p)
        }
        assertTrue("android.permission.POST_NOTIFICATIONS" in p)
    }

    @Test
    fun `guncel hedef ve kanal ozellikleri`() {
        assertEquals(36, app.applicationInfo.targetSdkVersion)
        assertFalse(BuildConfig.SELF_UPDATE)
        assertFalse(BuildConfig.SIM_SMS)
        assertNotNull("bileşenler Play modülleriyle gelir", componentModules(app))
        assertNotNull("uygulama içi güncelleme ve değerlendirme Play'den", storeServices(app))
    }
}
