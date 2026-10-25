package com.plyg.droidserver.app

import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.plyg.droidserver.app.ui.TestDroidServerApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** GitHub sürümü (PLAN Faz 19): Play sürümü eklenince değişmemeli. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TestDroidServerApp::class)
class FullManifestTest {
    private val app = ApplicationProvider.getApplicationContext<TestDroidServerApp>()

    @Test
    @Suppress("DEPRECATION")
    fun `izinler ve hedef aynen`() {
        val p = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()
        assertTrue("android.permission.SEND_SMS" in p)
        assertTrue("android.permission.REQUEST_INSTALL_PACKAGES" in p)
        assertEquals(28, app.applicationInfo.targetSdkVersion)
        assertTrue(BuildConfig.SELF_UPDATE && BuildConfig.SIM_SMS && BuildConfig.COMPONENT_DOWNLOADS)
    }
}
