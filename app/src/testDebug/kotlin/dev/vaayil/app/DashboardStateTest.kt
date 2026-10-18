package dev.vaayil.app

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Spec §3.7.1 `connectedDiffersFromIdle`: the idle and connected hero seats differ by
 * ΔL 0.002 in tone in their shared regions — a whole-image diff cannot see that and a
 * reviewer could approve a broken one by eye — so this test samples exact pixels from
 * both captures instead.
 *
 * Sampled points, relative to landmarks located by their pinned token colors:
 *  - The hero seat paper (first `Container3` #ECE5D7 pixel down the x=30 column): sampled
 *    8dp below that edge, 10dp in from the seat's left edge — inside the 14dp corner
 *    radius, clear of the 1dp seam. `Container3` in BOTH states ("hero seat darkens to
 *    container-3", §3.5). DEVICE-FIX 2026-09-21: the seat runs to the 20dp gutter (was
 *    edge-to-edge), so the landmark column sits 10dp inside the gutter.
 *  - The hero disc (first non-seat pixel down the window's centre column — the seam ring
 *    or state ring's top edge): sampled 40dp right and 40dp below that point, which lands
 *    inside the fill while clearing the 36dp glyph and its label. Idle = `Canvas` #FBFAF7
 *    (the window to the bare board); connected = `Primary` #3E5A42. This fill flip is the
 *    strongest discriminator and immune to the ΔL 0.002 problem.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DashboardStateTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val profile = Profile(
        id = "p1", name = "Home relay",
        interfaceConfig = InterfaceConfig("k", listOf("10.0.0.2/32"), listOf("1.1.1.1"), 1420, null),
        peer = PeerConfig("K", null, "vpn.example.net:51820", listOf("0.0.0.0/0"), 25),
    )

    /** Draws the live window the way the screenshot harness renders (NATIVE graphics). */
    private fun drawWindow(): Bitmap {
        val decor = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bitmap))
        return bitmap
    }

    /** The w360dp Robolectric screen is density 1.0 (fixtures are 360×800). */
    private fun Bitmap.pixelAt(x: Int, y: Int): Int {
        val density = RuntimeEnvironment.getApplication().resources.displayMetrics.density
        return getPixel((x * density).toInt(), (y * density).toInt())
    }

    private fun assertNear(actual: Int, expected: Long, what: String) {
        val dr = Math.abs((actual shr 16 and 0xFF) - (expected shr 16 and 0xFF).toInt())
        val dg = Math.abs((actual shr 8 and 0xFF) - (expected shr 8 and 0xFF).toInt())
        val db = Math.abs((actual and 0xFF) - (expected and 0xFF).toInt())
        assertTrue(
            "$what expected #%08X ±8/channel, got #%08X (dR=$dr dG=$dg dB=$db)".format(expected, actual),
            dr <= 8 && dg <= 8 && db <= 8,
        )
    }

    @Test
    fun connectedDiffersFromIdle() {
        val idleUi = HomeUiState(TunnelStatus.idle, TunnelStats.empty, activeProfile = null, profiles = listOf(profile))
        val connectedUi = HomeUiState(
            TunnelStatus(profileId = profile.id, phase = ConnectionPhase.CONNECTED, error = null),
            TunnelStats.empty,
            activeProfile = profile,
            profiles = listOf(profile),
            handshakeState = HandshakeState.VERIFIED,
        )
        // One composition, two states: setContent may run once per test.
        val ui = mutableStateOf(idleUi)
        compose.setContent {
            VaayilTheme.Content {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    DashboardScreen(
                        ui = ui.value,
                        selected = profile,
                        onSelectProfile = {},
                        onToggle = {},
                        onOpenProfiles = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        val idle = drawWindow()
        compose.runOnUiThread { ui.value = connectedUi }
        compose.waitForIdle()
        val connected = drawWindow()

        val seatPaper = 0xFFECE5D7L // Container3
        val canvas = 0xFFFBFAF7L // Canvas — the idle disc window
        val primary = 0xFF3E5A42L // Primary — the connected disc fill

        for (bitmap in listOf(idle, connected)) {
            val state = if (bitmap === idle) "idle" else "connected"

            var seatTop = -1
            for (y in 0 until bitmap.height) {
                if (bitmap.pixelAt(30, y) == seatPaper.toInt()) { seatTop = y; break }
            }
            assertTrue("$state: seat paper #ECE5D7 (container-3) not found along x=30", seatTop >= 0)
            assertNear(bitmap.pixelAt(30, seatTop + 8), seatPaper, "$state seat paper")

            // Disc fill: first non-seat pixel down the centre column is the ring edge.
            val cx = bitmap.width / 2
            var discTop = -1
            for (y in seatTop + 8 until bitmap.height) {
                if (bitmap.pixelAt(cx, y) != seatPaper.toInt()) { discTop = y; break }
            }
            assertTrue("$state: hero disc not found on the centre column below the seat top", discTop >= 0)
            val expectedFill = if (bitmap === idle) canvas else primary
            assertNear(bitmap.pixelAt(cx + 40, discTop + 40), expectedFill, "$state disc fill")
        }
    }

    /**
     * Device review 2026-09-21 (C5 finding 13): the connected dashboard measured 788dp
     * of content in a 617dp window, hiding 171dp — the status pill and most of the disc —
     * at max scroll. The C5 restructure budgets ≤40dp hidden at max scroll, measured
     * against the device-equivalent content window. A `ScrollState`'s `maxValue` IS the
     * hidden amount: content height − viewport height. The h645dp window reproduces the
     * review's post-fix viewport (617dp + the 8dp shorter top bar + the 20dp shorter
     * docked nav bar); Robolectric density is 1.0, so px == dp.
     */
    @Test
    @Config(qualifiers = "w360dp-h645dp")
    fun connectedContentHidesAtMost40dpAtMaxScroll() {
        val connectedUi = HomeUiState(
            TunnelStatus(profileId = profile.id, phase = ConnectionPhase.CONNECTED, error = null),
            TunnelStats(
                handshakeAt = System.currentTimeMillis() - 14_000,
                rxBytes = 1_523_000_000,
                txBytes = 403_000_000,
            ),
            activeProfile = profile,
            profiles = listOf(profile),
            handshakeState = HandshakeState.VERIFIED,
        )
        val scroll = ScrollState(0)
        compose.setContent {
            VaayilTheme.Content {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    DashboardScreen(
                        ui = connectedUi,
                        selected = profile,
                        onSelectProfile = {},
                        onToggle = {},
                        onOpenProfiles = {},
                        scrollState = scroll,
                    )
                }
            }
        }
        compose.waitForIdle()
        assertTrue(
            "Connected dashboard hides ${scroll.maxValue}dp at max scroll (budget 40dp)",
            scroll.maxValue <= 40,
        )
    }
}
