package dev.vaayil.feature.settings

import android.Manifest
import android.app.Application
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * C1/C2's platform verdict, per API level. The cascade is permission-first — turning
 * Location on cannot help while a grant is missing (B2) — so approximate-only precedes
 * the Location switch and a missing background grant precedes it too. The switch itself
 * applies from API 27 (AOSP 8.1 hides the SSID with Location off; 8.0 does not), and a
 * fresh call is what the ON_RESUME recompute reads, so one test pins both directions.
 */
@RunWith(AndroidJUnit4::class)
class LocationPermissionTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    /**
     * The device's Location switch as each API level exposes it: the location mode that
     * API 28+ reads, plus the gps/network providers that `LocationManagerCompat` falls
     * back to below 28.
     */
    private fun setLocationSwitch(on: Boolean) {
        val manager = context.getSystemService(LocationManager::class.java)
        shadowOf(manager).setLocationEnabled(on)
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, on)
        shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, on)
    }

    private fun grant(vararg permissions: String) = shadowOf(context).grantPermissions(*permissions)

    private fun grantPrecise() = grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    private fun grantPreciseAndBackground() = grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_BACKGROUND_LOCATION,
    )

    @Test
    @Config(sdk = [26])
    fun `api 26 ignores the Location switch`() {
        grantPrecise()
        setLocationSwitch(false)
        assertEquals(PermissionState.GRANTED, permissionStateFor(context))
    }

    @Test
    @Config(sdk = [27, 28])
    fun `api 27 and 28 read Location off when the switch is off`() {
        grantPrecise()
        setLocationSwitch(true)
        assertEquals(PermissionState.GRANTED, permissionStateFor(context))
        setLocationSwitch(false)
        assertEquals(PermissionState.LOCATION_OFF, permissionStateFor(context))
    }

    @Test
    @Config(sdk = [29, 31, 35])
    fun resumeReflectsLocationSwitch() {
        grantPreciseAndBackground()
        setLocationSwitch(true)
        assertEquals(PermissionState.GRANTED, permissionStateFor(context))
        setLocationSwitch(false)
        assertEquals(PermissionState.LOCATION_OFF, permissionStateFor(context))
        setLocationSwitch(true)
        assertEquals(PermissionState.GRANTED, permissionStateFor(context))
    }

    @Test
    @Config(sdk = [29, 31, 35])
    fun `api 29+ pauses on the missing grant before the switch`() {
        grantPrecise()
        setLocationSwitch(true)
        assertEquals(PermissionState.FOREGROUND_ONLY, permissionStateFor(context))
        setLocationSwitch(false)
        // The missing "Allow all the time" still leads: turning Location on cannot help yet.
        assertEquals(PermissionState.FOREGROUND_ONLY, permissionStateFor(context))
        grant(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        assertEquals(PermissionState.LOCATION_OFF, permissionStateFor(context))
    }

    @Test
    @Config(sdk = [31])
    fun approximateBeforeLocationOff() {
        grant(Manifest.permission.ACCESS_COARSE_LOCATION)
        setLocationSwitch(false)
        assertEquals(PermissionState.APPROXIMATE_ONLY, permissionStateFor(context))
        grantPreciseAndBackground()
        assertEquals(PermissionState.LOCATION_OFF, permissionStateFor(context))
    }

    @Test
    @Config(sdk = [31])
    fun `no location grant reads denied even with the switch on`() {
        shadowOf(context).denyPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        setLocationSwitch(true)
        assertEquals(PermissionState.DENIED, permissionStateFor(context))
    }
}
