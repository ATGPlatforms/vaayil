package dev.vaayil.app

import android.Manifest
import android.app.Application
import android.location.LocationManager
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.feature.settings.PermissionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * C1: the disclosure's Continue asks for precise and approximate location in one launch,
 * and continues to background location only when FINE was granted and background is still
 * missing. The launchers run against a recording [ActivityResultRegistry] so the test sees
 * the real payload a device would receive.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class LocationPermissionRequestTest {
    @get:Rule
    val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    /** One launch as the registry received it. */
    private class Launch(val requestCode: Int, val contract: ActivityResultContract<*, *>, val input: Any?)

    /** Records every launch and answers it on demand, like the platform would. */
    private class RecordingRegistry : ActivityResultRegistry() {
        val launches = mutableListOf<Launch>()

        override fun <I : Any?, O : Any?> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            launches += Launch(requestCode, contract, input)
        }

        fun answer(index: Int, result: Any?) = dispatchResult(launches[index].requestCode, result)
    }

    private val registry = RecordingRegistry()
    private val states = mutableListOf<PermissionState>()
    private lateinit var request: () -> Unit

    private fun start() {
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = registry
        }
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                request = rememberLocationPermissionRequest { states += it }
            }
        }
        compose.waitForIdle()
    }

    /** The device Location switch on, as a real device has it (Robolectric's mode defaults off). */
    private fun locationOn() =
        shadowOf(app.getSystemService(LocationManager::class.java)).setLocationEnabled(true)

    private fun grant(vararg permissions: String) = shadowOf(app).grantPermissions(*permissions)

    @Test
    fun foregroundRequestContainsFineAndCoarse() {
        start()
        compose.runOnIdle { request() }
        val launch = registry.launches.single()
        assertEquals(ActivityResultContracts.RequestMultiplePermissions::class.java, launch.contract.javaClass)
        assertEquals(
            setOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            (launch.input as Array<*>).toSet(),
        )
    }

    @Test
    fun fineContinuesToBackgroundOnlyWhenMissing() {
        start()
        locationOn()
        grant(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

        // Background absent (API 29+): the foreground grant continues to the background ask.
        compose.runOnIdle { request() }
        compose.runOnIdle {
            registry.answer(
                0,
                mapOf(
                    Manifest.permission.ACCESS_FINE_LOCATION to true,
                    Manifest.permission.ACCESS_COARSE_LOCATION to true,
                ),
            )
        }
        assertEquals(2, registry.launches.size)
        val background = registry.launches[1]
        assertEquals(ActivityResultContracts.RequestPermission::class.java, background.contract.javaClass)
        assertEquals(Manifest.permission.ACCESS_BACKGROUND_LOCATION, background.input)
        compose.runOnIdle { registry.answer(1, false) }
        assertEquals(listOf(PermissionState.FOREGROUND_ONLY), states)

        // Background already granted: no second ask; the verdict is written at once.
        grant(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        compose.runOnIdle { request() }
        compose.runOnIdle {
            registry.answer(
                2,
                mapOf(
                    Manifest.permission.ACCESS_FINE_LOCATION to true,
                    Manifest.permission.ACCESS_COARSE_LOCATION to true,
                ),
            )
        }
        assertEquals(3, registry.launches.size)
        assertEquals(PermissionState.GRANTED, states.last())

        // COARSE-only ("Approximate"): never a background ask.
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        compose.runOnIdle { request() }
        compose.runOnIdle {
            registry.answer(
                3,
                mapOf(
                    Manifest.permission.ACCESS_FINE_LOCATION to false,
                    Manifest.permission.ACCESS_COARSE_LOCATION to true,
                ),
            )
        }
        assertEquals(4, registry.launches.size)
        assertEquals(PermissionState.APPROXIMATE_ONLY, states.last())
        assertEquals(3, states.size)
    }

    @Test
    fun foregroundDenialWritesTheVerdictWithoutABackgroundAsk() {
        start()
        locationOn()
        shadowOf(app).denyPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        compose.runOnIdle { request() }
        compose.runOnIdle {
            registry.answer(
                0,
                mapOf(
                    Manifest.permission.ACCESS_FINE_LOCATION to false,
                    Manifest.permission.ACCESS_COARSE_LOCATION to false,
                ),
            )
        }
        assertEquals(1, registry.launches.size)
        assertTrue(registry.launches.single().contract is ActivityResultContracts.RequestMultiplePermissions)
        assertEquals(listOf(PermissionState.DENIED), states)
    }
}
