package dev.vaayil.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.model.InterfaceConfig
import dev.vaayil.core.model.PeerConfig
import dev.vaayil.core.model.Profile
import dev.vaayil.core.tunnel.api.ConnectionPhase
import dev.vaayil.core.tunnel.api.HandshakeState
import dev.vaayil.core.tunnel.api.TunnelStatus
import dev.vaayil.core.tunnel.wg.ConnectionRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A launcher shortcut's request goes through the same manual connect gate a tap inside the app
 * does: it never connects a profile that is already live, never touches one that cannot be read,
 * and gets the dashboard on screen before any consent dialog.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class QuickRequestRoutingTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val p1 = profile("p1", "Home")
    private val p2 = profile("p2", "Work")
    private val profiles = mapOf("p1" to p1, "p2" to p2)

    private var status = TunnelStatus.idle
    private val gateIds = mutableListOf<String?>()
    private val events = mutableListOf<String>()
    private val connected = mutableListOf<Profile>()
    private var lookups = 0
    private var heldAction: (() -> Unit)? = null

    @Before
    fun drainStartedServices() {
        // Robolectric shares the application between test methods: drop intents another test left.
        while (shadowOf(app).nextStartedService != null) Unit
    }

    @After
    fun resetRegistry() {
        ConnectionRegistry.status.value = TunnelStatus.idle
        ConnectionRegistry.handshake.value = HandshakeState.NONE
    }

    private suspend fun route(
        profileId: String = "p1",
        currentRoute: String? = "dashboard",
        resolve: suspend (String) -> Profile? = { profiles[it] },
        holdGate: Boolean = false,
    ): QuickRequestOutcome = routeQuickRequest(
        profileId = profileId,
        currentRoute = currentRoute,
        status = { status },
        lookup = { id ->
            lookups++
            resolve(id)
        },
        showDashboard = { events += "dashboard" },
        gate = { id, action ->
            gateIds += id
            events += "gate"
            if (holdGate) heldAction = action else action()
        },
        connect = { connected += it },
    )

    @Test
    fun `a request during onboarding is dropped`() = runBlocking {
        assertEquals(QuickRequestOutcome.DROPPED, route(currentRoute = "onboarding"))
        assertEquals(0, lookups)
        assertTrue(gateIds.isEmpty())
        assertTrue(connected.isEmpty())
    }

    @Test
    fun `a request for the live profile does nothing`() = runBlocking {
        status = TunnelStatus(ConnectionPhase.CONNECTED, "p1")
        assertEquals(QuickRequestOutcome.ALREADY_LIVE, route())
        assertTrue(gateIds.isEmpty())
        assertTrue(connected.isEmpty())
    }

    @Test
    fun `a request for an unreadable profile is dropped`() = runBlocking {
        assertEquals(QuickRequestOutcome.DROPPED, route(resolve = { null }))
        assertEquals(1, lookups)
        assertTrue(gateIds.isEmpty())
        assertTrue(connected.isEmpty())
    }

    @Test
    fun `a request on another tab shows the dashboard, then gates and connects`() = runBlocking {
        assertEquals(QuickRequestOutcome.GATED, route(currentRoute = "profiles"))
        assertEquals(listOf("dashboard", "gate"), events)
        assertEquals(listOf<String?>("p1"), gateIds)
        assertEquals(listOf(p1), connected)
    }

    @Test
    fun `a request on a pushed route leaves it alone`() = runBlocking {
        assertEquals(QuickRequestOutcome.GATED, route(currentRoute = "editor"))
        assertEquals(listOf("gate"), events)
        assertEquals(listOf(p1), connected)
    }

    @Test
    fun `a request on the dashboard does not navigate`() = runBlocking {
        assertEquals(QuickRequestOutcome.GATED, route(currentRoute = "dashboard"))
        assertEquals(listOf("gate"), events)
        assertEquals(listOf(p1), connected)
    }

    @Test
    fun `a profile that went live during the gate's dialogs is not connected again`() = runBlocking {
        assertEquals(QuickRequestOutcome.GATED, route(holdGate = true))
        assertTrue(connected.isEmpty())

        status = TunnelStatus(ConnectionPhase.CONNECTED, "p1")
        heldAction!!.invoke()
        assertTrue(connected.isEmpty())
    }

    @Test
    fun `a request switches from another live profile`() = runBlocking {
        status = TunnelStatus(ConnectionPhase.CONNECTED, "p2")
        assertEquals(QuickRequestOutcome.GATED, route())
        assertEquals(listOf<String?>("p1"), gateIds)
        assertEquals(listOf(p1), connected)
    }

    private fun profile(id: String, name: String) = Profile(
        id, name,
        InterfaceConfig("k", listOf("10.0.0.2/32"), emptyList(), 1280, null),
        PeerConfig("K", null, "h:51820", listOf("0.0.0.0/0"), 25),
    )
}
