package dev.vaayil.core.automation

import android.Manifest
import android.app.Application
import android.location.LocationManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.model.AutoConnectRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The guard's notification must claim only what the rules can actually do right now (F2, F28, B2). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class GuardNotificationCopyTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test fun `guard with background location reads on`() =
        assertEquals(
            "Vaayil auto-connect is on" to null,
            guardNotificationCopy(AutoConnectRules(wifiGuardEnabled = true), backgroundLocation = true, preciseLocation = true, locationEnabled = true),
        )

    @Test fun `mobile data alone reads on without location`() =
        assertEquals(
            "Vaayil auto-connect is on" to null,
            guardNotificationCopy(AutoConnectRules(autoConnectOnMobileData = true), backgroundLocation = false, preciseLocation = false, locationEnabled = false),
        )

    @Test fun `guard without background location reads paused`() =
        assertEquals(
            "Vaayil auto-connect is paused" to "Allow location all the time so Vaayil can see which Wi-Fi you're on.",
            guardNotificationCopy(AutoConnectRules(wifiGuardEnabled = true), backgroundLocation = false, preciseLocation = false, locationEnabled = false),
        )

    @Test fun `guard paused while mobile data still runs says which part is paused`() =
        assertEquals(
            "Vaayil auto-connect is on" to "Wi-Fi rules are paused: allow location all the time.",
            guardNotificationCopy(AutoConnectRules(wifiGuardEnabled = true, autoConnectOnMobileData = true), backgroundLocation = false, preciseLocation = false, locationEnabled = false),
        )

    @Test
    fun `copy prioritizes background then precise then Location`() {
        val guard = AutoConnectRules(wifiGuardEnabled = true)
        val guardWithMobile = AutoConnectRules(wifiGuardEnabled = true, autoConnectOnMobileData = true)

        // Background location missing: nothing else can change that row.
        assertEquals(
            "Vaayil auto-connect is on" to "Wi-Fi rules are paused: allow location all the time.",
            guardNotificationCopy(guardWithMobile, backgroundLocation = false, preciseLocation = false, locationEnabled = false),
        )
        assertEquals(
            "Vaayil auto-connect is paused" to "Allow location all the time so Vaayil can see which Wi-Fi you're on.",
            guardNotificationCopy(guard, backgroundLocation = false, preciseLocation = false, locationEnabled = false),
        )

        // Then precise location: Approximate with the background grant must win over the Location
        // switch, which is also off in this row.
        assertEquals(
            "Vaayil auto-connect is on" to "Wi-Fi rules are paused: turn on precise location.",
            guardNotificationCopy(guardWithMobile, backgroundLocation = true, preciseLocation = false, locationEnabled = false),
        )
        assertEquals(
            "Vaayil auto-connect is paused" to "Turn on precise location so Vaayil can see which Wi-Fi you're on.",
            guardNotificationCopy(guard, backgroundLocation = true, preciseLocation = false, locationEnabled = false),
        )

        // Only then the Location switch.
        assertEquals(
            "Vaayil auto-connect is on" to "Wi-Fi rules are paused: turn on Location.",
            guardNotificationCopy(guardWithMobile, backgroundLocation = true, preciseLocation = true, locationEnabled = false),
        )
        assertEquals(
            "Vaayil auto-connect is paused" to "Turn on Location so Vaayil can see which Wi-Fi you're on.",
            guardNotificationCopy(guard, backgroundLocation = true, preciseLocation = true, locationEnabled = false),
        )

        // Everything granted and Location on: nothing to report, mobile rule or not.
        assertEquals(
            "Vaayil auto-connect is on" to null,
            guardNotificationCopy(guard, backgroundLocation = true, preciseLocation = true, locationEnabled = true),
        )
        assertEquals(
            "Vaayil auto-connect is on" to null,
            guardNotificationCopy(guardWithMobile, backgroundLocation = true, preciseLocation = true, locationEnabled = true),
        )

        // Without the guard the copy never mentions the paused Wi-Fi rules, whatever the state is
        // (mobile-only and everything-off are both in this group).
        assertEquals(
            "Vaayil auto-connect is on" to null,
            guardNotificationCopy(AutoConnectRules(autoConnectOnMobileData = true), backgroundLocation = false, preciseLocation = false, locationEnabled = false),
        )
        assertEquals(
            "Vaayil auto-connect is on" to null,
            guardNotificationCopy(AutoConnectRules(), backgroundLocation = false, preciseLocation = false, locationEnabled = false),
        )
    }

    @Test
    @Config(sdk = [31, 35])
    fun `approximate background is distinguishable`() {
        shadowOf(context).grantPermissions(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        )
        shadowOf(context).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)

        val state = guardLocationState(context)

        assertTrue(state.backgroundLocation) // Approximate + background is still a background grant
        assertFalse(state.preciseLocation)
        assertFalse(hasBackgroundLocation(context))
    }

    @Test
    @Config(sdk = [26, 27, 28, 30, 35])
    fun `Location switch applies from 27`() {
        val manager = shadowOf(context.getSystemService(LocationManager::class.java))

        manager.setLocationEnabled(false)
        if (Build.VERSION.SDK_INT >= 27) {
            assertFalse(guardLocationState(context).locationEnabled)
        } else {
            assertTrue(guardLocationState(context).locationEnabled) // API 26 cannot hide the name
        }

        manager.setLocationEnabled(true)
        assertTrue(guardLocationState(context).locationEnabled)
    }

    @Test fun `the ssid reaches the rules with background location`() =
        assertEquals("HomeWifi", ssidForRules("HomeWifi", true))

    @Test fun `the ssid is gated without background location`() =
        assertNull(ssidForRules("HomeWifi", false))
}
