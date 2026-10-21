package dev.vaayil.app

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.model.InterfaceConfig
import dev.vaayil.core.model.PeerConfig
import dev.vaayil.core.model.Profile
import dev.vaayil.core.model.SplitTunnelMode
import dev.vaayil.core.tunnel.api.ConnectionPhase
import dev.vaayil.core.tunnel.api.HandshakeState
import dev.vaayil.core.tunnel.api.TunnelStatus
import dev.vaayil.core.tunnel.api.TunnelStats
import dev.vaayil.core.ui.LocalReducedMotion
import dev.vaayil.feature.profiles.AppInfo
import dev.vaayil.feature.profiles.SplitTunnelScreen
import dev.vaayil.feature.profiles.SplitTunnelUiState
import dev.vaayil.feature.tunnel.DashboardScreen
import dev.vaayil.feature.tunnel.HomeUiState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Type reflow guard (spec §3.7 group 5): at the narrowest phone width (w360dp)
 * the refreshed type must fit its slots. Two layers, both artifact-free under
 * Robolectric:
 *  1. No text node is clipped (`TextClipping.kt`: cut at the bottom, cut at the
 *     side or after its last allowed line, or off the screen). The old check
 *     compared `TextLayoutResult.size` with the node's box, but that size is
 *     clamped to the node's constraints, so it never saw a clip (F29).
 *  2. Explicit line budgets: the dashboard hint line stays ≤3 lines (the
 *     committed `dashboard-empty-w360dp` golden renders 3 — spec §3.7's "≤2"
 *     does not match the shipped type at w360dp; pinned here at the shipped
 *     truth so a future type change can only make it worse, never silently) and
 *     the Split Tunnel mode segment labels stay ≤2 lines (they render 1).
 * State fakes are reused from the screenshot harness; semantics-only, so no
 * golden is recorded; `LocalReducedMotion` is pinned per the fixture convention.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TypeReflowGuardTest {
    @get:Rule val compose = createComposeRule()

    private val dashboardHint = "Add your first tunnel to get started — import a .conf file, " +
        "scan a QR code, or enter the details manually."

    @Test
    fun dashboardEmptyTypeReflows() {
        compose.setContent {
            VaayilTheme.Content {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    DashboardScreen(
                        ui = HomeUiState(),
                        selected = null,
                        onSelectProfile = {},
                        onToggle = {},
                        onOpenProfiles = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.assertNoTextClipped("on the empty dashboard at w360dp")
        compose.assertLinesAtMost(dashboardHint, 3)
    }

    @Test
    fun dashboardConnectedTypeReflows() {
        // The C5 facts ledger (device review 2026-09-21): 14sp mono values right-aligned
        // beside labels at their natural width (no fixed column since F29) must hold the
        // endpoint, address and DNS list at w360dp — the half-width tile grid truncated
        // exactly this data.
        val profile = Profile(
            id = "p1", name = "Home relay",
            interfaceConfig = InterfaceConfig("k", listOf("10.0.0.2/32"), listOf("1.1.1.1", "8.8.8.8"), 1420, null),
            peer = PeerConfig("K", null, "vpn.example.net:51820", listOf("0.0.0.0/0"), 25),
        )
        compose.setContent {
            VaayilTheme.Content {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    DashboardScreen(
                        ui = HomeUiState(
                            status = TunnelStatus(
                                profileId = profile.id,
                                phase = ConnectionPhase.CONNECTED,
                                error = null,
                            ),
                            stats = TunnelStats(
                                handshakeAt = System.currentTimeMillis() - 14_000,
                                rxBytes = 1_523_000_000,
                                txBytes = 403_000_000,
                            ),
                            activeProfile = profile,
                            profiles = listOf(profile),
                            handshakeState = HandshakeState.VERIFIED,
                        ),
                        selected = profile,
                        onSelectProfile = {},
                        onToggle = {},
                        onOpenProfiles = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.assertNoTextClipped("on the connected dashboard at w360dp")
        // U2: the value renders as its machine break-point segments (MachineValueText) now,
        // so the fit budget is the segments' own: at w360dp/1.0 every segment stays whole
        // and they share one line — the row the old single Text filled.
        val tops = listOf("vpn.", "example.", "net", ":51820").map { segment ->
            compose.assertLinesAtMost(segment, 1)
            compose.allTextLayouts().single { it.text == segment }.node.positionInRoot.y
        }
        assertTrue("the endpoint wrapped across lines", tops.all { it == tops.first() })
    }

    @Test
    fun splitTunnelTypeReflows() {
        compose.setContent {
            VaayilTheme.Content {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    SplitTunnelScreen(
                        state = SplitTunnelUiState(
                            mode = SplitTunnelMode.EXCLUDE,
                            selectedPackages = setOf("com.example.app1"),
                            apps = (1..6).map { i -> AppInfo("com.example.app$i", "App $i") },
                        ),
                        onMode = {},
                        onToggle = {},
                        onQuery = {},
                        onShowSystem = {},
                        onRouteAll = {},
                        onBypassAll = {},
                        onAllowLocalNetwork = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.assertNoTextClipped("on the Split Tunnel tab at w360dp")
        compose.assertLinesAtMost("Exclusive", 2)
        compose.assertLinesAtMost("Inclusive", 2)
        // The cut explainer (device review 2026-09-21, C8): the mode hint stays ≤2 lines.
        // C9's restart note renders on exactly 2 lines at w360dp — the budget its
        // rewording was allowed for.
        compose.assertLinesAtMost("Everything travels through the encrypted tunnel except the apps you select.", 2)
        compose.assertLinesAtMost("Saving restarts the tunnel; connections can leave it briefly.", 2)
    }

    @Test
    fun splitTunnelLocalNetworkTypeReflows() {
        // The switch row sits above the segment, so its label, each supporting line and the
        // switch-on Exclusive hint share the same w360dp budget as the rest of the tab.
        compose.setContent {
            VaayilTheme.Content {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    SplitTunnelScreen(
                        state = SplitTunnelUiState(
                            mode = SplitTunnelMode.EXCLUDE,
                            selectedPackages = setOf("com.example.app1"),
                            apps = (1..6).map { i -> AppInfo("com.example.app$i", "App $i") },
                            allowLocalNetwork = true,
                        ),
                        onMode = {},
                        onToggle = {},
                        onQuery = {},
                        onShowSystem = {},
                        onRouteAll = {},
                        onBypassAll = {},
                        onAllowLocalNetwork = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.assertNoTextClipped("on the Split Tunnel tab with Allow local network at w360dp")
        compose.assertLinesAtMost("Allow local network", 1)
        compose.assertLinesAtMost(
            "Printers, cast devices and other devices on your local network stay reachable.",
            2,
        )
        compose.assertLinesAtMost("Connections to them skip the tunnel, on public Wi-Fi too.", 2)
        compose.assertLinesAtMost("With Block connections without VPN on, Android still blocks them.", 2)
        compose.assertLinesAtMost(
            "Everything travels through the tunnel except the apps you select and your local network.",
            2,
        )
    }

    @Test
    fun splitTunnelInclusiveHintReflows() {
        // The INCLUDE hint carries the same ≤2-line budget as the EXCLUSIVE one
        // (review fix round 1): both mode states are pinned, not just the default.
        compose.setContent {
            VaayilTheme.Content {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    SplitTunnelScreen(
                        state = SplitTunnelUiState(mode = SplitTunnelMode.INCLUDE),
                        onMode = {},
                        onToggle = {},
                        onQuery = {},
                        onShowSystem = {},
                        onRouteAll = {},
                        onBypassAll = {},
                        onAllowLocalNetwork = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.assertNoTextClipped("on the inclusive Split Tunnel tab at w360dp")
        compose.assertLinesAtMost("Only the apps you select travel through the tunnel; everything else stays outside.", 2)
    }
}
