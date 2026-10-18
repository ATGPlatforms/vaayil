package dev.vaayil.app

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.vaayil.core.model.InterfaceConfig
import dev.vaayil.core.model.PeerConfig
import dev.vaayil.core.model.Profile
import dev.vaayil.core.model.SplitTunnelMode
import dev.vaayil.core.model.SplitTunnelPolicy
import dev.vaayil.core.tunnel.api.ConnectionPhase
import dev.vaayil.core.tunnel.api.HandshakeState
import dev.vaayil.core.tunnel.api.TunnelError
import dev.vaayil.core.tunnel.api.TunnelStatus
import dev.vaayil.core.tunnel.api.TunnelStats
import dev.vaayil.core.ui.LocalReducedMotion
import dev.vaayil.feature.tunnel.DashboardScreen
import dev.vaayil.feature.tunnel.HomeUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config

/**
 * The hero disc's six signal states in Vellum: idle window, sage active, connecting,
 * failed, switch — another profile is live, so the selected one offers a connect that
 * replaces it — and waiting, a tunnel up with no handshake yet that claims no protection
 * (F21). The dashboard fixtures only exercise idle, so the protected/connecting/failed
 * faces had no rendered reference until this matrix.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DashboardPhaseScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val profile = Profile(
        id = "p1", name = "Home relay",
        interfaceConfig = InterfaceConfig("k", listOf("10.0.0.2/32"), listOf("1.1.1.1"), 1420, null),
        peer = PeerConfig("K", null, "vpn.example.net:51820", listOf("0.0.0.0/0"), 25),
    )

    private fun render(ui: HomeUiState, selected: Profile = profile) {
        compose.setContent {
            VaayilTheme.Content {
                // Deterministic captures: no breathing dot/fill animation mid-phase.
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    DashboardScreen(
                        ui = ui,
                        selected = selected,
                        onSelectProfile = {},
                        onToggle = {},
                        onOpenProfiles = {},
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun capture(fileName: String, ui: HomeUiState, selected: Profile = profile) {
        render(ui, selected)
        compose.onRoot().captureRoboImage("src/testDebug/snapshots/$fileName.png")
    }

    private val connectingUi = HomeUiState(
        TunnelStatus(profileId = profile.id, phase = ConnectionPhase.CONNECTING, error = null),
        TunnelStats.empty,
        activeProfile = profile,
        profiles = listOf(profile),
    )

    @Test
    fun idle() = capture(
        "dashboard-phase-idle",
        HomeUiState(TunnelStatus.idle, TunnelStats.empty, activeProfile = null, profiles = listOf(profile)),
    )

    @Test
    fun connected() = capture(
        "dashboard-phase-connected",
        HomeUiState(
            TunnelStatus(profileId = profile.id, phase = ConnectionPhase.CONNECTED, error = null),
            TunnelStats(handshakeAt = System.currentTimeMillis() - 14_000, rxBytes = 1_523_000_000, txBytes = 403_000_000),
            activeProfile = profile,
            profiles = listOf(profile),
            handshakeState = HandshakeState.VERIFIED,
        ),
    )

    @Test
    fun connecting() = capture("dashboard-phase-connecting", connectingUi)

    @Test
    fun `a connect in flight on the selected profile offers to cancel, not to connect`() {
        // Tapping the busy disc runs the toggle, whose live guard makes that a cancel: the
        // label must not promise a connect the tap will not perform.
        render(connectingUi)
        compose.onNodeWithContentDescription("Cancel connecting").assertIsDisplayed()
        compose.onNodeWithText("Tap the disc to cancel connecting").assertIsDisplayed()
    }

    @Test
    fun `a busy disc still offers Connect when another profile is coming up`() {
        render(connectingUi, selected = other)
        compose.onNodeWithContentDescription("Connect").assertIsDisplayed()
        compose.onNodeWithText("Tap the disc to connect").assertIsDisplayed()
    }

    @Test
    fun failed() = capture(
        "dashboard-phase-failed",
        HomeUiState(
            TunnelStatus(profileId = profile.id, phase = ConnectionPhase.FAILED, error = dev.vaayil.core.tunnel.api.TunnelError.HandshakeTimeout),
            TunnelStats.empty,
            activeProfile = profile,
            profiles = listOf(profile),
        ),
    )

    @Test
    fun permissionOff() {
        // C8: a FAILED start whose cause is the missing VPN consent reads as the state it is
        // (another app holding Always-on VPN, a policy, a cancelled dialog), not a refusal.
        capture(
            "dashboard-phase-permission-off",
            HomeUiState(
                TunnelStatus(profileId = profile.id, phase = ConnectionPhase.FAILED, error = TunnelError.PermissionDenied),
                TunnelStats.empty,
                activeProfile = profile,
                profiles = listOf(profile),
            ),
        )
        compose.onNodeWithText("VPN permission is off").assertIsDisplayed()
        compose.onAllNodesWithText("VPN permission denied").assertCountEquals(0)
    }

    private val other = profile.copy(id = "p2", name = "Office relay")

    @Test
    fun switch() = capture(
        "dashboard-phase-switch",
        HomeUiState(
            TunnelStatus(profileId = profile.id, phase = ConnectionPhase.CONNECTED, error = null),
            TunnelStats(handshakeAt = System.currentTimeMillis() - 14_000, rxBytes = 1_523_000_000, txBytes = 403_000_000),
            activeProfile = profile,
            profiles = listOf(profile, other),
            handshakeState = HandshakeState.VERIFIED,
        ),
        selected = other,
    )

    private fun failedUi(failedId: String) = HomeUiState(
        TunnelStatus(profileId = failedId, phase = ConnectionPhase.FAILED, error = TunnelError.HandshakeTimeout),
        TunnelStats.empty,
        activeProfile = null,
        profiles = listOf(profile, other),
    )

    @Test
    fun `a failure on another profile leaves the selected profile idle`() {
        // F17b: p1 failed, then the user picked p2. The disc, the pill and the error text
        // all belong to p1's attempt, so p2 shows its plain idle presentation.
        render(failedUi(failedId = profile.id), selected = other)
        compose.onNodeWithText("Disconnected").assertIsDisplayed()
        compose.onAllNodesWithText("Failed").assertCountEquals(0)
        compose.onAllNodesWithText("Handshake timed out").assertCountEquals(0)
        compose.onNodeWithContentDescription("Connect").assertIsDisplayed()
        compose.onNodeWithText("Tap the disc to connect").assertIsDisplayed()
    }

    @Test
    fun `a failure on the selected profile shows the failed pill and its error`() {
        render(failedUi(failedId = profile.id), selected = profile)
        compose.onNodeWithText("Failed").assertIsDisplayed()
        compose.onNodeWithText("Handshake timed out").assertIsDisplayed()
    }

    private val waitingUi = HomeUiState(
        TunnelStatus(profileId = profile.id, phase = ConnectionPhase.CONNECTED, error = null),
        TunnelStats.empty,
        activeProfile = profile,
        profiles = listOf(profile),
        handshakeState = HandshakeState.WAITING,
    )

    @Test
    fun waiting() {
        capture("dashboard-phase-waiting", waitingUi)
        // U2: the endpoint keeps its port through the 320dp wrap.
        assertEquals(1, compose.layoutOf(":51820").lineCount)
    }

    @Test
    fun `a tunnel still waiting for its handshake claims no protection`() {
        // F21: CONNECTED with no handshake after 10 s. Nothing is verified, so there is no sage
        // claim: the pill says what is happening and the disc keeps the connecting presentation.
        render(waitingUi)
        compose.onNodeWithText("Waiting for handshake").assertIsDisplayed()
        compose.onAllNodesWithText("Protected & Encrypted").assertCountEquals(0)
        compose.onAllNodesWithText("ACTIVE").assertCountEquals(0)
        compose.onAllNodesWithText("SESSION TOTALS").assertCountEquals(0)
        compose.onNodeWithContentDescription("Cancel connecting").assertIsDisplayed()
        compose.onNodeWithText("Tap the disc to cancel connecting").assertIsDisplayed()
    }

    @Test
    fun `a switch offered while the live tunnel waits still says it is waiting`() {
        render(waitingUi.copy(profiles = listOf(profile, other)), selected = other)
        compose.onNodeWithText("Waiting for handshake").assertIsDisplayed()
        compose.onNodeWithContentDescription("Switch to Office relay").assertIsDisplayed()
    }
}
