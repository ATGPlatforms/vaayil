package dev.vaayil.app

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Intent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.data.DataGraph
import dev.vaayil.core.tunnel.api.ConnectionPhase
import dev.vaayil.core.tunnel.api.TunnelStatus
import dev.vaayil.core.tunnel.wg.ConnectionRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * C7/C8: a manual connect's consent chain. The VPN consent dialog comes first — a denial ends
 * in a snackbar and never reaches the notification ask — and the one-time notification ask
 * follows a granted consent, never stacking two system dialogs. The ask is claimed in the flags
 * store (shared with onboarding), so denials, recreation and later connects never ask twice; a
 * denial of the notification itself still connects, and the live profile's tap is a disconnect
 * that prompts nothing.
 *
 * The gates are driven directly through a recording [ActivityResultRegistry], the
 * [LocationPermissionRequestTest] pattern, so the test sees exactly what a device would answer.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class ManualConnectPermissionTest {
    @get:Rule
    val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    /** Every launch as the registry received it; its contract names the event. */
    private class Launch(val requestCode: Int, val contract: ActivityResultContract<*, *>, val input: Any?)

    /** Records every launch and answers it on demand, like the platform would. */
    private class RecordingRegistry(private val events: MutableList<String>) : ActivityResultRegistry() {
        val launches = mutableListOf<Launch>()

        override fun <I : Any?, O : Any?> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            launches += Launch(requestCode, contract, input)
            events += if (contract is ActivityResultContracts.StartActivityForResult) "vpn" else "notifications"
        }

        fun answer(index: Int, result: Any?) = dispatchResult(launches[index].requestCode, result)
    }

    private val events = mutableListOf<String>()
    private val registry = RecordingRegistry(events)
    private val snackbar = SnackbarHostState()
    private lateinit var container: AppContainer
    private lateinit var tap: (String?) -> Unit
    private lateinit var askNotifications: () -> Unit

    /** The platform's answer to [android.net.VpnService.prepare]: null once consent is held. */
    private var vpnConsentGranted = false
    private val consentIntent = Intent("dev.vaayil.test.VPN_CONSENT")

    /** Bumping it recreates the gate composition, dropping every remembered in-flight marker. */
    private val generation = mutableStateOf(0)

    @Before
    fun setUp() {
        runBlocking {
            DataGraph.reset()
            // R2: the flags delegate is process-wide, so a fresh file is never guaranteed.
            app.settingsDataStore.edit { it.clear() }
            container = AppContainer(app, { _ -> }, { _ -> })
        }
        vpnConsentGranted = false
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = registry
        }
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                key(generation.value) {
                    val context = LocalContext.current
                    // What OnboardingRoute's card does today; the manual gate claims the same store.
                    askNotifications =
                        rememberNotificationPermissionGate(context, container::claimNotificationPermissionRequest)
                            .let { gate -> { gate {} } }
                    val gate = rememberManualConnectionGate(
                        container,
                        snackbar,
                        prepareVpn = { if (vpnConsentGranted) null else consentIntent },
                    )
                    tap = { id -> gate(id) { events += "connect" } }
                }
            }
        }
        compose.waitForIdle()
    }

    @After
    fun resetRegistry() {
        ConnectionRegistry.status.value = TunnelStatus.idle
    }

    @Test
    fun skippedOnboardingAsksAfterVpnResult() {
        compose.runOnIdle { tap("p1") }
        assertEquals(listOf("vpn"), events)

        // The user consents: only then does the notification ask follow — never two dialogs at once.
        vpnConsentGranted = true
        compose.runOnIdle { registry.answer(0, ActivityResult(Activity.RESULT_OK, null)) }
        compose.waitUntil(TIMEOUT_MS) { events.size == 2 }
        assertEquals(listOf("vpn", "notifications"), events)

        // Denial still connects: the notification is not a blocker.
        compose.runOnIdle { registry.answer(1, false) }
        compose.waitUntil(TIMEOUT_MS) { events.size == 3 }
        assertEquals(listOf("vpn", "notifications", "connect"), events)
    }

    @Test
    fun vpnDenialDoesNotAskNotifications() {
        compose.runOnIdle { tap("p1") }
        assertEquals(listOf("vpn"), events)

        compose.runOnIdle { registry.answer(0, ActivityResult(Activity.RESULT_CANCELED, null)) }
        compose.waitUntil(TIMEOUT_MS) { snackbar.currentSnackbarData != null }

        // No notification ask and no connect: the chain stopped where the consent did.
        assertEquals(listOf("vpn"), events)
        assertEquals(1, registry.launches.size)
        val shown = snackbar.currentSnackbarData!!.visuals
        assertEquals(
            "Vaayil didn't get VPN permission. If another app is set as Always-on VPN, turn that off first.",
            shown.message,
        )
        assertEquals(SnackbarDuration.Long, shown.duration)

        // The prompt resolved: the next tap asks again instead of being swallowed.
        compose.runOnIdle { tap("p1") }
        assertEquals(listOf("vpn", "vpn"), events)
    }

    @Test
    fun askedFlagSurvivesRecreation() {
        compose.runOnIdle { tap("p1") }
        vpnConsentGranted = true
        compose.runOnIdle { registry.answer(0, ActivityResult(Activity.RESULT_OK, null)) }
        compose.waitUntil(TIMEOUT_MS) { events.size == 2 }
        compose.runOnIdle { registry.answer(1, false) }
        compose.waitUntil(TIMEOUT_MS) { events.size == 3 }
        assertEquals(listOf("vpn", "notifications", "connect"), events)

        // Recreation drops every remembered marker; only the flags store remembers the ask.
        compose.runOnIdle { generation.value++ }
        compose.runOnIdle { tap("p1") }
        compose.waitUntil(TIMEOUT_MS) { events.size == 4 }
        assertEquals(listOf("vpn", "notifications", "connect", "connect"), events)
        assertEquals(2, registry.launches.size)
    }

    @Test
    fun onboardingAndManualShareTheClaim() {
        // Onboarding's card claims the one request for the process's lifetime.
        compose.runOnIdle { askNotifications() }
        compose.waitUntil(TIMEOUT_MS) { events.size == 1 }
        assertEquals(listOf("notifications"), events)
        compose.runOnIdle { registry.answer(0, false) }

        // The first manual connect finds the claim spent: it connects without asking again.
        compose.runOnIdle { tap("p1") }
        assertEquals(listOf("notifications", "vpn"), events)
        vpnConsentGranted = true
        compose.runOnIdle { registry.answer(1, ActivityResult(Activity.RESULT_OK, null)) }
        compose.waitUntil(TIMEOUT_MS) { events.size == 3 }
        assertEquals(listOf("notifications", "vpn", "connect"), events)
        assertEquals(2, registry.launches.size)
    }

    @Test
    fun alreadyGrantedSkipsTheAsk() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        compose.runOnIdle { tap("p1") }
        vpnConsentGranted = true
        compose.runOnIdle { registry.answer(0, ActivityResult(Activity.RESULT_OK, null)) }

        // Already granted: straight to the connect, and the one-time claim stays unspent.
        assertEquals(listOf("vpn", "connect"), events)
        assertEquals(1, registry.launches.size)
    }

    /**
     * The API-33 gate, on the one sandbox that can see it: below API 33 there is no
     * POST_NOTIFICATIONS runtime permission, so the connect runs straight through — and the
     * ask is still unspent for the environment that must never place it.
     */
    @Test
    @Config(sdk = [32])
    fun api32SkipsTheAsk() {
        compose.runOnIdle { tap("p1") }
        assertEquals(listOf("vpn"), events)
        vpnConsentGranted = true
        compose.runOnIdle { registry.answer(0, ActivityResult(Activity.RESULT_OK, null)) }

        assertEquals(listOf("vpn", "connect"), events)
        assertEquals(1, registry.launches.size)
        runBlocking { assertTrue(container.claimNotificationPermissionRequest()) }
    }

    @Test
    fun doubleTapDoesNotStackRequests() {
        compose.runOnIdle { tap("p1"); tap("p1") }
        assertEquals(listOf("vpn"), events)

        vpnConsentGranted = true
        compose.runOnIdle { registry.answer(0, ActivityResult(Activity.RESULT_OK, null)) }
        compose.waitUntil(TIMEOUT_MS) { events.size == 2 }

        // Taps while the notification dialog is up are ignored too.
        compose.runOnIdle { tap("p1"); tap("p1") }
        compose.runOnIdle { registry.answer(1, false) }
        compose.waitUntil(TIMEOUT_MS) { events.size == 3 }
        assertEquals(listOf("vpn", "notifications", "connect"), events)
        assertEquals(2, registry.launches.size)
    }

    @Test
    fun disconnectNeverAsks() {
        // The live profile's tap is a disconnect: no prompt, at once.
        ConnectionRegistry.status.value =
            TunnelStatus(profileId = "p1", phase = ConnectionPhase.CONNECTED, error = null)
        compose.runOnIdle { tap("p1") }
        assertEquals(listOf("connect"), events)

        // A CONNECTING profile's tap cancels it; the same bypass.
        ConnectionRegistry.status.value =
            TunnelStatus(profileId = "p1", phase = ConnectionPhase.CONNECTING, error = null)
        compose.runOnIdle { tap("p1") }
        assertEquals(listOf("connect", "connect"), events)

        // Another profile is a connect, so it prompts — and the live one still disconnects at once.
        ConnectionRegistry.status.value =
            TunnelStatus(profileId = "p1", phase = ConnectionPhase.CONNECTED, error = null)
        compose.runOnIdle { tap("p2") }
        assertEquals(listOf("connect", "connect", "vpn"), events)
        compose.runOnIdle { tap("p1") }
        assertEquals(listOf("connect", "connect", "vpn", "connect"), events)
        assertEquals(0, registry.launches.count { it.contract is ActivityResultContracts.RequestPermission })
    }

    @Test
    fun nullTargetPromptsNothing() {
        compose.runOnIdle { tap(null) }
        assertTrue(events.isEmpty())
        assertEquals(0, registry.launches.size)
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
