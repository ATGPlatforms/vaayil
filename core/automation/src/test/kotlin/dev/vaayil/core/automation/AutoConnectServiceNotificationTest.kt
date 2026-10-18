package dev.vaayil.core.automation

import android.Manifest
import android.app.Application
import android.app.Notification
import android.content.Intent
import android.location.LocationManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.data.AutomationRulesStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/**
 * C7/M5: every start command must reach startForeground with the LAST computed copy, never the
 * generic title. B2: the platform's Location switch re-words that same copy within seconds while
 * the app is closed, without a start command.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class AutoConnectServiceNotificationTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var controller: ServiceController<AutoConnectService>

    @Before
    fun seedPausedRules() = runBlocking {
        AutomationRulesStore(app) // open the DataStore in the app context first, as the service expects
        // The store outlives one test (one test below sets the mobile rule), so pin both fields:
        // no background location is granted, making this the paused copy.
        AutomationRulesStore(app).mutate { it.copy(wifiGuardEnabled = true, autoConnectOnMobileData = false) }
    }

    @After
    fun tearDown() {
        if (::controller.isInitialized) controller.destroy()
    }

    /** B2 fixture: grants in place, Location on, the copy refresh free to read real rules. */
    private fun createdWithLocation() {
        shadowOf(app).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        )
        shadowOf(app.getSystemService(LocationManager::class.java)).setLocationEnabled(true)
        controller = Robolectric.buildService(AutoConnectService::class.java).create()
    }

    private fun setLocationEnabled(enabled: Boolean) =
        shadowOf(app.getSystemService(LocationManager::class.java)).setLocationEnabled(enabled)

    private fun foregroundCopy(): Pair<String?, String?> {
        val posted = shadowOf(controller.get()).lastForegroundNotification
        return posted.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() to
            posted.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
    }

    @Test
    fun `a resume start command promotes the paused copy, not the generic title`() {
        controller = Robolectric.buildService(AutoConnectService::class.java).create()
        // onCreate's refreshNotification reads DataStore on IO: drain until it lands.
        awaitCopy("Vaayil auto-connect is paused" to "Allow location all the time so Vaayil can see which Wi-Fi you're on.")
        controller.startCommand(0, 2)
        val posted = shadowOf(controller.get()).lastForegroundNotification
        assertEquals("Vaayil auto-connect is paused", posted.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertEquals(
            "Allow location all the time so Vaayil can see which Wi-Fi you're on.",
            posted.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )
    }

    @Test
    fun `Location changes refresh notification without an app start`() {
        createdWithLocation()

        setLocationEnabled(false)
        app.sendBroadcast(Intent(LocationManager.MODE_CHANGED_ACTION))

        awaitCopy("Vaayil auto-connect is paused" to "Turn on Location so Vaayil can see which Wi-Fi you're on.")
    }

    @Test
    fun `Location changes refresh notification without an app start with mobile data set to connect`() {
        runBlocking { AutomationRulesStore(app).mutate { it.copy(autoConnectOnMobileData = true) } }
        createdWithLocation()

        setLocationEnabled(false)
        app.sendBroadcast(Intent(LocationManager.MODE_CHANGED_ACTION))

        awaitCopy("Vaayil auto-connect is on" to "Wi-Fi rules are paused: turn on Location.")
    }

    /**
     * The copy is computed on the service's scope behind a DataStore read, so a re-worded
     * notification arrives on the main looper some time after the triggering event: poll, then
     * drain once more so a late create-time refresh cannot flip the copy back. The slot is
     * pinned: this copy belongs to the automation service's own foreground notification.
     */
    private fun awaitCopy(expected: Pair<String, String?>, timeoutMs: Long = 5_000) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (foregroundCopy() == expected &&
                shadowOf(controller.get()).lastForegroundNotificationId == AutoConnectService.NOTIFICATION_ID
            ) {
                // A late create-time refresh must not flip the copy back after this check.
                shadowOf(Looper.getMainLooper()).idle()
                if (foregroundCopy() == expected) return
            }
            Thread.sleep(20)
        }
        fail("the notification copy '$expected' was never promoted (was ${foregroundCopy()})")
    }
}
