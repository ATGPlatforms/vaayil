package dev.vaayil.app

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class ManifestPermissionsTest {
    @Test
    fun `the merged manifest has no billing permission`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val requested = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toList()
        assertFalse("The free app must not request Play Billing", "com.android.vending.BILLING" in requested)
    }

    @Test
    fun `the merged manifest requests both location permissions for the wi-fi guard`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val requested = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toList()
        assertTrue("ACCESS_FINE_LOCATION must be requested", android.Manifest.permission.ACCESS_FINE_LOCATION in requested)
        assertTrue(
            "ACCESS_BACKGROUND_LOCATION must be requested for the background guard",
            android.Manifest.permission.ACCESS_BACKGROUND_LOCATION in requested,
        )
    }
}
