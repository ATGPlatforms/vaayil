package dev.vaayil.app

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.automation.AutomationReceiver
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class ExternalControlComponentTest {
    @Test
    fun `toggle enables and disables the receiver component`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val component = ComponentName(context, AutomationReceiver::class.java)
        setExternalControlEnabled(context, true)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, context.packageManager.getComponentEnabledSetting(component))
        setExternalControlEnabled(context, false)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, context.packageManager.getComponentEnabledSetting(component))
    }
}
