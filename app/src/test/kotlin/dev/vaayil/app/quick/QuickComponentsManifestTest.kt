package dev.vaayil.app.quick

import android.app.Application
import android.content.ComponentName
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
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
}
