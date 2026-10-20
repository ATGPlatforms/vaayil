package dev.vaayil.app.quick

import android.app.Application
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.pm.PackageManager
import android.service.quicksettings.TileService
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

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

    @Test
    fun `the widget provider is not exported and declares its info`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val info = app.packageManager.getReceiverInfo(
            ComponentName(app, QuickWidgetProvider::class.java),
            PackageManager.GET_META_DATA,
        )
        assertFalse(info.exported)
        assertEquals(R.xml.quick_widget_info, info.metaData.getInt("android.appwidget.provider"))
    }

    @Test
    fun `the widget's click receiver is not exported`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val info = app.packageManager.getReceiverInfo(
            ComponentName(app, QuickWidgetReceiver::class.java),
            0,
        )
        assertFalse(info.exported)
    }

    @Test
    fun `the widget info pins its size and update rules`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val parser = app.resources.getXml(R.xml.quick_widget_info)
        var event = parser.eventType
        while (event != XmlPullParser.START_TAG) event = parser.next()
        val ns = "http://schemas.android.com/apk/res/android"
        assertEquals(
            AppWidgetProviderInfo.RESIZE_HORIZONTAL,
            parser.getAttributeIntValue(ns, "resizeMode", -1),
        )
        assertEquals(
            AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN,
            parser.getAttributeIntValue(ns, "widgetCategory", -1),
        )
        assertEquals(0, parser.getAttributeIntValue(ns, "updatePeriodMillis", -1))
        assertNull(parser.getAttributeValue(ns, "configure"))
    }
}
