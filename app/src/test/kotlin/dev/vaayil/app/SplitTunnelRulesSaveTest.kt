package dev.vaayil.app

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.data.SplitTunnelRulesStore
import dev.vaayil.core.model.SplitTunnelMode
import dev.vaayil.core.model.SplitTunnelSelections
import dev.vaayil.core.tunnel.api.ConnectionPhase
import dev.vaayil.core.tunnel.api.TunnelError
import dev.vaayil.core.tunnel.api.TunnelStatus
import dev.vaayil.core.tunnel.wg.ConnectionRegistry
import dev.vaayil.core.tunnel.wg.VpnTunnelService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Spec §11 + C4: saving split-tunnel rules applies them to the live tunnel through the service —
 * at once while CONNECTED, and once a CONNECTING start settles CONNECTED for that profile; with
 * nothing live, or holding another profile, it must not start anything.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class SplitTunnelRulesSaveTest {
    private lateinit var context: Context
    private lateinit var store: SplitTunnelRulesStore

    // The coordinator's own scope, as in the app: the restart is never run on the save's caller.
    // Unconfined settles a status emission inline, so these tests need no checkpoint.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = SplitTunnelRulesStore(context)
        // Robolectric shares the application between test methods: drop intents an earlier test
        // started so no stale service intent leaks into these assertions.
        while (shadowOf(context as Application).nextStartedService != null) Unit
    }

    @After
    fun tearDown() {
        scope.cancel() // a pending request must not outlive its test and fire into the next one
    }

    /** The production restart callback (C4); the assertions below check the intent it builds. */
    private fun restarts() =
        SettingsRestartCoordinator(scope, ConnectionRegistry.status) { id -> restartTunnel(context, id) }

    @Test
    fun `saving rules while connected fires a reconnect intent for the connected profile`() = runBlocking {
        ConnectionRegistry.status.value = TunnelStatus(ConnectionPhase.CONNECTED, "p1", null)
        // Robolectric shares the DataStore file across test methods: pin the prior policy so the
        // save below is unambiguously a change to the active list, not a no-op replay of it.
        store.setSelections(SplitTunnelSelections(SplitTunnelMode.INCLUDE))
        val rules = SplitTunnelSelections(SplitTunnelMode.INCLUDE, included = setOf("com.a"))
        saveSplitTunnelRules(store, rules, restarts())
        val intent = shadowOf(context as Application).nextStartedService
        assertEquals(VpnTunnelService::class.java.name, intent.component?.className)
        assertEquals(VpnTunnelService.ACTION_CONNECT, intent.action)
        assertEquals("p1", intent.getStringExtra(VpnTunnelService.EXTRA_PROFILE_ID))
        assertEquals(rules, store.snapshotSelections())
    }

    @Test
    fun `saving rules while connecting restarts the profile once it is connected`() = runBlocking {
        ConnectionRegistry.status.value = TunnelStatus(ConnectionPhase.CONNECTING, "p1", null)
        store.setSelections(SplitTunnelSelections(SplitTunnelMode.INCLUDE))
        val rules = SplitTunnelSelections(SplitTunnelMode.INCLUDE, included = setOf("com.a"))
        saveSplitTunnelRules(store, rules, restarts())
        assertNull(shadowOf(context as Application).nextStartedService) // still connecting: nothing yet
        assertEquals(rules, store.snapshotSelections()) // the saved values are in before the restart
        ConnectionRegistry.status.value = TunnelStatus(ConnectionPhase.CONNECTED, "p1", null)
        val intent = shadowOf(context as Application).nextStartedService
        assertEquals(VpnTunnelService::class.java.name, intent.component?.className)
        assertEquals(VpnTunnelService.ACTION_CONNECT, intent.action)
        assertEquals("p1", intent.getStringExtra(VpnTunnelService.EXTRA_PROFILE_ID))
    }

    @Test
    fun `saving rules while disconnected does not restart anything`() = runBlocking {
        ConnectionRegistry.status.value = TunnelStatus.idle
        saveSplitTunnelRules(
            store,
            SplitTunnelSelections(SplitTunnelMode.EXCLUDE),
            restarts(),
        )
        assertNull(shadowOf(context as Application).nextStartedService)
    }

    @Test
    fun `saving rules after a failed connect does not start a tunnel`() = runBlocking {
        ConnectionRegistry.status.value = TunnelStatus(ConnectionPhase.FAILED, "p1", TunnelError.HandshakeTimeout)
        saveSplitTunnelRules(
            store,
            SplitTunnelSelections(SplitTunnelMode.INCLUDE, included = setOf("com.a")),
            restarts(),
        )
        assertNull(shadowOf(context as Application).nextStartedService)
    }

    @Test
    fun `saving an unchanged active policy while connected does not restart`() = runBlocking {
        ConnectionRegistry.status.value = TunnelStatus(ConnectionPhase.CONNECTED, "p1", null)
        val rules = SplitTunnelSelections(SplitTunnelMode.EXCLUDE, excluded = setOf("com.a"))
        store.setSelections(rules)
        // Only the INACTIVE mode's list changed: the live tunnel would run the same policy.
        saveSplitTunnelRules(store, rules.copy(included = setOf("com.zzz")), restarts())
        assertNull(shadowOf(context as Application).nextStartedService)
        assertEquals(setOf("com.zzz"), store.snapshotSelections().included)
    }

    @Test
    fun `an inactive-list edit while connecting restarts nothing`() = runBlocking {
        ConnectionRegistry.status.value = TunnelStatus(ConnectionPhase.CONNECTING, "p1", null)
        val rules = SplitTunnelSelections(SplitTunnelMode.EXCLUDE, excluded = setOf("com.a"))
        store.setSelections(rules)
        saveSplitTunnelRules(store, rules.copy(included = setOf("com.zzz")), restarts())
        // The inactive list is saved, but the start that settles CONNECTED runs the same policy.
        assertEquals(setOf("com.zzz"), store.snapshotSelections().included)
        ConnectionRegistry.status.value = TunnelStatus(ConnectionPhase.CONNECTED, "p1", null)
        assertNull(shadowOf(context as Application).nextStartedService)
    }
}
