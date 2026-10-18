package dev.vaayil.app

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.model.InterfaceConfig
import dev.vaayil.core.model.PeerConfig
import dev.vaayil.core.model.Profile
import dev.vaayil.core.tunnel.api.ConnectionPhase
import dev.vaayil.core.tunnel.api.HandshakeState
import dev.vaayil.core.tunnel.api.TunnelStats
import dev.vaayil.core.tunnel.api.TunnelStatus
import dev.vaayil.core.ui.LocalReducedMotion
import dev.vaayil.feature.tunnel.DashboardScreen
import dev.vaayil.feature.tunnel.HomeUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The dashboard's machine values at font scale 2.0 on the narrowest phone width, where
 * both ledgers reflow:
 *  - I2: a telemetry value shares its line only while it whole fits beside the label;
 *    otherwise it takes its own full-width, right-aligned line — never a mid-token break,
 *    and always flush with the other values;
 *  - U2: the endpoint wraps only at `machineValueSegments`' break points (after `.`/`/`,
 *    before `:`), so the port segment stays whole and never splits mid-token;
 *  - I1a: the endpoint fact stacks under its label when they cannot share the line, and the
 *    value then wraps against the full row — every segment fits whole.
 * The window is 2000dp tall (LargeTextScreenshotTest's reason) so the facts card is on
 * screen: `boundsInRoot` reads zero for a node below the viewport, and this test compares
 * node bounds. Semantics-only, so no golden is recorded; `LocalReducedMotion` is pinned
 * per the fixture convention.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h2000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DashboardReflowTest {
    @get:Rule val compose = createComposeRule()

    private fun profile(id: String, name: String, endpoint: String) = Profile(
        id = id, name = name,
        interfaceConfig = InterfaceConfig("k", listOf("10.0.0.2/32"), listOf("1.1.1.1"), 1420, null),
        peer = PeerConfig("K", null, endpoint, listOf("0.0.0.0/0"), 25),
    )

    @Test
    fun `the handshake value stays whole and flush right when it cannot share the line`() {
        showConnectedAt2()
        // I2: the value shares the line only while it whole fits beside the label; here it
        // cannot (HANDSHAKE + the value overrun the row), so the value takes its own
        // full-width line — unbroken and flush right with the DOWN value.
        val layout = compose.layoutOf("13m ago") // TextClipping.kt helper
        assertEquals("the handshake value broke mid-token", 1, layout.lineCount)
        val label = compose.onNodeWithText("HANDSHAKE", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val down = compose.onNodeWithText("1.4 GiB", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val value = compose.onNodeWithText("13m ago", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "the value still shared the line (value.left=${value.left}, label.left=${label.left})",
            value.left < label.left,
        )
        assertTrue(
            "the value's own line is not flush with the DOWN value's right edge",
            kotlin.math.abs(value.right - down.right) < 2f,
        )
    }

    @Test
    @Config(qualifiers = "w320dp-h2000dp")
    fun `a narrow column never breaks a telemetry value mid-token`() {
        showConnectedAt2()
        // I2: however narrow the column, the value stays one unbroken line.
        assertEquals("the handshake value broke mid-token", 1, compose.layoutOf("13m ago").lineCount)
    }

    @Test
    fun `the endpoint fact stacks under its label at font scale 2`() {
        showConnectedAt2()
        // I1a: label and value cannot share the line at 2.0, so the value moves under the
        // label and gets the whole row — every machine segment then fits whole (the old
        // weight(1f) column broke "example." mid-token, leaving a lone dot on a line).
        val label = compose.onNodeWithText("ENDPOINT", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val value = compose.onNodeWithText("vpn.").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "the endpoint value did not stack under its label (value.top=${value.top}, label.bottom=${label.bottom})",
            value.top >= label.bottom - 1f,
        )
        listOf("vpn.", "example.", "net", ":51820").forEach {
            assertEquals("\"$it\" broke mid-segment", 1, compose.layoutOf(it).lineCount)
        }
    }

    @Test
    fun `the endpoint keeps its port through the wrap`() {
        showConnectedAt2()
        // U2: segments exist and none broke mid-token; the port segment is whole.
        assertEquals(1, compose.layoutOf(":51820").lineCount)
        // Cross-node line tops are paragraph-local, so compare the nodes' shared positions.
        val host = compose.onNodeWithText("vpn.", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val port = compose.onNodeWithText(":51820", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue("the port segment did not wrap below the host segment", host.top < port.top)
    }

    @Test
    fun `stacked fact values align start`() {
        showConnectedAt2()
        // Device check 2026-10-03: a stacked ENDPOINT value ran ragged-left (each line pushed
        // right) while ADDRESS read start-aligned. Every stacked value now begins at the row's
        // start column: the ENDPOINT's first segment, its wrapped port line and the ADDRESS
        // value share one left edge. (Line starts are compared as node bounds — a
        // TextLayoutResult's line tops/lefts are paragraph-local.)
        val address = compose.onNodeWithText("10.", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        listOf("vpn.", ":51820").forEach { segment ->
            val node = compose.onNodeWithText(segment, useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            assertTrue(
                "\"$segment\" does not start at the ADDRESS value's column " +
                    "(left=${node.left}, address.left=${address.left})",
                kotlin.math.abs(node.left - address.left) < 2f,
            )
        }
    }

    /** The class's one state: a connected dashboard at font scale 2.0. */
    private fun showConnectedAt2() {
        val home = profile("p1", "Home relay", "vpn.example.net:51820")
        compose.setContent {
            VaayilTheme.Content {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    AtFontScale(2f) {
                        DashboardScreen(
                            ui = HomeUiState(
                                status = TunnelStatus(profileId = "p1", phase = ConnectionPhase.CONNECTED, error = null),
                                stats = TunnelStats(
                                    handshakeAt = System.currentTimeMillis() - 780_000, // "13m ago"
                                    rxBytes = 1_523_000_000, // "1.4 GiB"
                                    txBytes = 403_000,
                                ),
                                activeProfile = home,
                                profiles = listOf(home),
                                handshakeState = HandshakeState.VERIFIED,
                            ),
                            selected = home,
                            onSelectProfile = {},
                            onToggle = {},
                            onOpenProfiles = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }
}
