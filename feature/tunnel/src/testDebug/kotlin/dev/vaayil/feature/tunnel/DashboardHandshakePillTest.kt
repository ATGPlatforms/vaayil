package dev.vaayil.feature.tunnel

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.model.InterfaceConfig
import dev.vaayil.core.model.PeerConfig
import dev.vaayil.core.model.Profile
import dev.vaayil.core.tunnel.api.ConnectionPhase
import dev.vaayil.core.tunnel.api.HandshakeState
import dev.vaayil.core.tunnel.api.TunnelStatus
import dev.vaayil.core.ui.LocalReducedMotion
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** G4 (spec §4.4.5 test 7): the dashboard's PENDING/WAITING presentations never claim protection,
 *  and VERIFIED renders exactly as connected does today. Sage is unit-pinned by [DiscFaceTest]. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class DashboardHandshakePillTest {
    @get:Rule val compose = createComposeRule()

    private val profile = Profile(
        id = "p1", name = "Home relay",
        interfaceConfig = InterfaceConfig("k", listOf("10.0.0.2/32"), listOf("1.1.1.1"), 1420, null),
        peer = PeerConfig("K", null, "vpn.example.net:51820", listOf("0.0.0.0/0"), 25),
    )

    private fun show(state: HandshakeState, phase: ConnectionPhase = ConnectionPhase.CONNECTED) {
        val ui = HomeUiState(
            status = TunnelStatus(phase, "p1"),
            handshakeState = state,
            profiles = listOf(profile),
        )
        compose.setContent {
            MaterialTheme {
                // Deterministic captures: no breathing dot/fill animation mid-phase.
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    DashboardScreen(
                        ui = ui,
                        selected = profile,
                        onSelectProfile = {},
                        onToggle = {},
                        onOpenProfiles = {},
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `PENDING shows Connecting and never the verified presentation`() {
        show(HandshakeState.PENDING)
        compose.onNodeWithText("Connecting").assertIsDisplayed()
        compose.onNodeWithText("Protected & Encrypted").assertDoesNotExist()
        compose.onNodeWithText("Tap the disc to cancel connecting").assertIsDisplayed() // connecting presentation, tap cancels
    }

    @Test
    fun `WAITING shows Waiting for handshake and cancels on tap`() {
        show(HandshakeState.WAITING)
        compose.onNodeWithText("Waiting for handshake").assertIsDisplayed()
        compose.onNodeWithText("Tap the disc to cancel connecting").assertIsDisplayed()
    }

    @Test
    fun `VERIFIED renders exactly as connected does today`() {
        show(HandshakeState.VERIFIED)
        compose.onNodeWithText("Protected & Encrypted").assertIsDisplayed()
        compose.onNodeWithText("Tap the disc to disconnect").assertIsDisplayed()
    }
}
