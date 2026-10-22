package dev.vaayil.app

import android.app.Application
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.data.DataGraph
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * T-8 (spec §3.2): the dashboard card's "Got it" is remembered in the flags store, so the card
 * stays away for that hostname across restarts, and a later "Got it" replaces the hostname it
 * hid. The comparison itself lives in the feature's `privateDnsCardHost`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class PrivateDnsDismissalTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var container: AppContainer

    @Before
    fun setUp() {
        runBlocking {
            DataGraph.reset()
            // R2: the flags delegate is process-wide, so a fresh file is never guaranteed.
            app.settingsDataStore.edit { it.clear() }
            container = AppContainer(app, { _ -> }, { _ -> })
        }
    }

    @Test
    fun `no hidden host reads null`() = runBlocking {
        assertNull(container.privateDnsDismissedHost.first())
    }

    @Test
    fun `a hidden host is stored and read back`() = runBlocking {
        container.dismissPrivateDnsCard("dns.google")

        assertEquals("dns.google", container.privateDnsDismissedHost.first())
    }

    @Test
    fun `a later Got it replaces the earlier host`() = runBlocking {
        container.dismissPrivateDnsCard("dns.google")
        container.dismissPrivateDnsCard("one.one.one.one")

        assertEquals("one.one.one.one", container.privateDnsDismissedHost.first())
    }
}
