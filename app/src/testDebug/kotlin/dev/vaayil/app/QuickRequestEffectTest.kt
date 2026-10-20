package dev.vaayil.app

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.app.quick.QuickRequests
import dev.vaayil.core.tunnel.api.HandshakeState
import dev.vaayil.core.tunnel.api.TunnelStatus
import dev.vaayil.core.tunnel.wg.ConnectionRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicLong

/**
 * The root's effect only services a posted request while the screen is started, reads the route
 * the user is on at that moment, and consumes the slot exactly once: a recreation must not replay
 * a request that already ran.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h800dp")
class QuickRequestEffectTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val clock = AtomicLong(0)
    private val requests = QuickRequests { clock.get() }
    private val handled = mutableListOf<Pair<String, String?>>()
    private lateinit var nav: NavHostController

    @Before
    fun drainStartedServices() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        while (shadowOf(app).nextStartedService != null) Unit
    }

    @After
    fun resetRegistry() {
        ConnectionRegistry.status.value = TunnelStatus.idle
        ConnectionRegistry.handshake.value = HandshakeState.NONE
    }

    @Composable
    private fun graph() {
        nav = rememberNavController()
        NavHost(navController = nav, startDestination = "dashboard") {
            composable("dashboard") { Text("Dashboard") }
            composable("profiles") { Text("Profiles") }
            composable("editor") { Text("Editor") }
        }
        QuickRequestEffect(requests, nav) { id, route -> handled += id to route }
    }

    private fun show() {
        compose.setContent { graph() }
    }

    /** Bounded wait for the effect's collection to run on the main thread. */
    private fun awaitHandled(count: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (handled.size < count && System.currentTimeMillis() < deadline) {
            compose.waitForIdle()
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertEquals("handled requests", count, handled.size)
    }

    @Test
    fun `a posted request is handled once with the current route`() {
        show()
        compose.runOnIdle { nav.navigate("profiles") }
        compose.waitForIdle()

        requests.post("p1")
        awaitHandled(1)

        compose.runOnIdle {
            assertEquals(listOf("p1" to "profiles"), handled)
            assertNull(requests.pending.value)
        }
    }

    @Test
    fun `a request waits until the activity is started`() {
        show()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)

        requests.post("p1")
        compose.waitForIdle()
        shadowOf(Looper.getMainLooper()).idle()
        compose.runOnIdle { assertTrue(handled.isEmpty()) }

        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        awaitHandled(1)
        compose.runOnIdle { assertEquals(listOf("p1" to "dashboard"), handled) }
    }

    @Test
    fun `an expired request is dropped`() {
        show()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        requests.post("p1")
        clock.set(31_000)

        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        val deadline = System.currentTimeMillis() + 5_000
        while (requests.pending.value != null && System.currentTimeMillis() < deadline) {
            compose.waitForIdle()
            shadowOf(Looper.getMainLooper()).idle()
        }

        compose.runOnIdle {
            assertTrue(handled.isEmpty())
            assertNull(requests.pending.value)
        }
    }

    @Test
    fun `a handled request does not run again after the composition is restored`() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { graph() }

        requests.post("p1")
        awaitHandled(1)

        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        shadowOf(Looper.getMainLooper()).idle()

        compose.runOnIdle { assertEquals(1, handled.size) }
    }
}
