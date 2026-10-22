package dev.vaayil.app

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * T-8 (spec §3.2): "Open network settings" tries the network settings screen first and falls
 * back to the Settings root on a build that lacks it; a build with neither leaves the app where
 * it is instead of crashing.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class SystemScreenTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val opened = mutableListOf<String?>()

    /** Records every start and refuses the actions in [missing], like an OEM build without them. */
    private fun context(missing: Set<String> = emptySet()): Context = object : ContextWrapper(app) {
        override fun startActivity(intent: Intent) {
            if (intent.action in missing) throw ActivityNotFoundException(intent.action)
            opened += intent.action
        }
    }

    @Test
    fun `the first screen that opens wins`() {
        context().openNetworkSettings()

        assertEquals(listOf(Settings.ACTION_WIRELESS_SETTINGS), opened)
    }

    @Test
    fun `a missing network screen falls back to Settings`() {
        context(missing = setOf(Settings.ACTION_WIRELESS_SETTINGS)).openNetworkSettings()

        assertEquals(listOf(Settings.ACTION_SETTINGS), opened)
    }

    @Test
    fun `nothing opens when neither screen exists`() {
        context(missing = setOf(Settings.ACTION_WIRELESS_SETTINGS, Settings.ACTION_SETTINGS))
            .openNetworkSettings()

        assertEquals(emptyList<String>(), opened)
    }
}
