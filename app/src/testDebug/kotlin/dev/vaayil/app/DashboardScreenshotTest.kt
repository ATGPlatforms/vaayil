package dev.vaayil.app

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
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
import dev.vaayil.core.tunnel.api.TunnelStatus
import dev.vaayil.core.tunnel.api.TunnelStats
import dev.vaayil.core.ui.LocalReducedMotion
import dev.vaayil.feature.tunnel.DashboardScreen
import dev.vaayil.feature.tunnel.HomeUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config

/**
 * Vellum dashboard fixtures: the empty welcome state and the populated facts grid.
 * Throughput only renders while connected — the phase matrix owns that face.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DashboardScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun capture(fileName: String, ui: HomeUiState, selected: Profile?) {
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
        compose.onRoot().captureRoboImage("src/testDebug/snapshots/$fileName.png")
    }

    private val home = Profile(
        id = "p1", name = "Home relay",
        interfaceConfig = InterfaceConfig("k", listOf("10.0.0.2/32"), listOf("1.1.1.1"), 1420, null),
        peer = PeerConfig("K", null, "vpn.example.net:51820", listOf("0.0.0.0/0"), 25),
    )

    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun phoneEmpty() = capture("dashboard-empty-w360dp", HomeUiState(), null)

    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun phoneIdle() = capture(
        "dashboard-idle-w360dp",
        HomeUiState(
            status = TunnelStatus.idle,
            stats = TunnelStats.empty,
            profiles = listOf(home),
        ),
        home,
    )

    @Test
    @Config(qualifiers = "w600dp-h960dp")
    fun tabletIdle() = capture(
        "dashboard-idle-w600dp",
        HomeUiState(
            status = TunnelStatus.idle,
            stats = TunnelStats.empty,
            profiles = listOf(home),
        ),
        home,
    )

    @Test
    @Config(qualifiers = "w393dp-h851dp-440dpi")
    fun phoneConnected440() = capture(
        // Device review C11: fixtures at true phone density (Pixel 8a-class window),
        // where the 1dp seams, 11sp eyebrows and 21sp telemetry digits render as they
        // do on hardware. Connected so the facts ledger and the filled disc render.
        "dashboard-connected-d440",
        HomeUiState(
            status = TunnelStatus(profileId = home.id, phase = ConnectionPhase.CONNECTED, error = null),
            stats = TunnelStats(
                handshakeAt = System.currentTimeMillis() - 14_000,
                rxBytes = 1_523_000_000,
                txBytes = 403_000_000,
            ),
            activeProfile = home,
            profiles = listOf(home),
            handshakeState = HandshakeState.VERIFIED,
        ),
        home,
    )

    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun phoneConnectedPrivateDns() = capture(
        // T-8 (spec §3.2): the warning card sits under the hero while Android's Private DNS
        // names a provider and the VPN network lists a DNS server.
        "dashboard-private-dns-w360dp",
        HomeUiState(
            status = TunnelStatus(profileId = home.id, phase = ConnectionPhase.CONNECTED, error = null),
            activeProfile = home,
            profiles = listOf(home),
            handshakeState = HandshakeState.VERIFIED,
            privateDnsHost = "dns.google",
            vpnHasDnsServer = true,
        ),
        home,
    )
}
