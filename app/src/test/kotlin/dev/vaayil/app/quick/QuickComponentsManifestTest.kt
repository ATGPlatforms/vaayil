package dev.vaayil.app.quick

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import android.service.quicksettings.TileService
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class QuickComponentsManifestTest {
    @Test
    fun `the shortcut trampoline is not exported`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val info = app.packageManager.getActivityInfo(
            ComponentName(app, QuickShortcutActivity::class.java),
            0,
        )
        assertFalse(info.exported)
    }

    @Test
    fun `the tile is exported only to the tile binder`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val info = app.packageManager.getServiceInfo(
            ComponentName(app, QuickTileService::class.java),
            PackageManager.GET_META_DATA,
        )
        assertTrue(info.exported)
        assertEquals("android.permission.BIND_QUICK_SETTINGS_TILE", info.permission)
        assertTrue(info.metaData.getBoolean(TileService.META_DATA_TOGGLEABLE_TILE))
        assertEquals("Vaayil", info.loadLabel(app.packageManager).toString())
    }
}
