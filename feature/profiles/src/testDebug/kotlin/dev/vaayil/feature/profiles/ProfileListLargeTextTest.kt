package dev.vaayil.feature.profiles

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.model.InterfaceConfig
import dev.vaayil.core.model.PeerConfig
import dev.vaayil.core.model.Profile
import dev.vaayil.core.tunnel.api.ConnectionPhase
import dev.vaayil.core.tunnel.api.HandshakeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * U3/U2 (sweep 2): the Profiles tab at the narrowest phone width. A long hero name wraps
 * at its word edges and never starves beside the ACTIVE pill (C1: the pill rides beside a
 * name that fits and drops under one that cannot); a long saved name wraps to two lines
 * before it ellipsizes; and the hero endpoint renders as machine-value segments that break
 * at the value's own points — a port stays whole.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfileListLargeTextTest {
    @get:Rule val compose = createComposeRule()

    private fun profile(id: String, name: String, endpoint: String) = Profile(
        id, name,
        InterfaceConfig("k", listOf("10.0.0.2/32"), listOf("1.1.1.1"), null, null),
        PeerConfig("K", null, endpoint, listOf("0.0.0.0/0"), 25),
    )

    private fun screen(ui: ProfileListUiState, fontScale: Float = 1f) {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalDensity provides Density(LocalDensity.current.density, fontScale),
                ) {
                    ProfileListScreen(
                        ui = ui,
                        onOpenEditor = {},
                        onOpenImport = {},
                        onScanQr = {},
                        onDelete = {},
                        onDuplicate = {},
                        onToggleConnect = {},
                        onShareQr = {},
                        onExport = {},
                        onDeleteUnreadable = {},
                    )
                }
            }
        }
    }

    private fun textLayoutOf(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    /** Every line but the last ends right after a space: no break inside a word (TabHeaderTest). */
    private fun assertBreaksAtWordEdges(layout: TextLayoutResult, name: String) {
        for (line in 0 until layout.lineCount - 1) {
            val end = layout.getLineEnd(line, visibleEnd = false) - 1
            assertTrue("line ${line + 1} broke inside a word", name[end] == ' ')
        }
    }

    private val longName = "Home relay through the Frankfurt exit node on the fibre line"

    @Test
    fun `a long hero name wraps at word edges and keeps the pill`() {
        screen(
            ProfileListUiState(
                items = listOf(profile("a", longName, "vpn.example.net:51820"), profile("b", "Office", "198.51.100.7:51820")),
                activeId = "a",
                phase = ConnectionPhase.CONNECTED,
                handshakeState = HandshakeState.VERIFIED,
            ),
        )
        // C1: the name is measured against the whole line, so it wraps at its spaces and
        // never starves beside the pill; the pill rides beside a name that fits and drops
        // under one that does not — it never floats above the name.
        val layout = textLayoutOf(longName)
        assertEquals("the hero name did not wrap to two lines", 2, layout.lineCount)
        assertBreaksAtWordEdges(layout, longName)
        val pill = compose.onNodeWithText("ACTIVE").fetchSemanticsNode().boundsInRoot
        val name = compose.onNodeWithText(longName).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "the pill floated above the hero name (pill.top=${pill.top}, name.top=${name.top})",
            pill.top >= name.top,
        )
    }

    // 393dp (the phoneList440 width): since J6 the hero's header strip carries the
    // connect switch, so beside it the text column is
    // 393 − 40 (screen) − 40 (Sheet) − 24 (strip padding) − 40 (avatar) − 28 (the two
    // 14dp gaps) − 52 (switch) = 169px. "Home relay" (111px) + the 8dp gap + the 93px
    // pill no longer fits there — the C1 rule correctly drops the pill — so this test
    // pins the "rides beside" half of it with the shorter "Home".
    @Test
    @Config(qualifiers = "w393dp-h800dp")
    fun `a short hero name keeps the pill beside it`() {
        screen(
            ProfileListUiState(
                items = listOf(profile("a", "Home", "vpn.example.net:51820"), profile("b", "Office", "198.51.100.7:51820")),
                activeId = "a",
                phase = ConnectionPhase.CONNECTED,
                handshakeState = HandshakeState.VERIFIED,
            ),
        )
        // The pill still rides beside a name that fits: it shares the name's line (its top
        // sits above the name's bottom edge).
        val pill = compose.onNodeWithText("ACTIVE").fetchSemanticsNode().boundsInRoot
        val name = compose.onNodeWithText("Home").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "the pill dropped below a name that fits beside it (pill.top=${pill.top}, name.bottom=${name.bottom})",
            pill.top < name.bottom,
        )
    }

    @Test
    fun `a long hero name is not starved at font scale 2`() {
        screen(
            ProfileListUiState(
                items = listOf(profile("a", longName, "vpn.example.net:51820"), profile("b", "Office", "198.51.100.7:51820")),
                activeId = "a",
                phase = ConnectionPhase.CONNECTED,
                handshakeState = HandshakeState.VERIFIED,
            ),
            fontScale = 2f,
        )
        // C1: the old Row-with-weight starved the name to "Ho…" here. The FlowRow measures
        // it against the whole line, so its first line holds the whole first word.
        val layout = textLayoutOf(longName)
        assertEquals("the hero name did not wrap to two lines", 2, layout.lineCount)
        assertBreaksAtWordEdges(layout, longName)
        assertTrue(
            "the first line starves the name (ends at ${layout.getLineEnd(0, visibleEnd = false)})",
            layout.getLineEnd(0, visibleEnd = false) >= longName.indexOf(' ') + 1,
        )
    }

    @Test
    fun `a long saved name wraps to two lines before ellipsizing`() {
        screen(
            ProfileListUiState(
                items = listOf(profile("a", "Alpha", "vpn.example.net:51820"), profile("b", longName, "198.51.100.7:51820")),
            ),
        )
        val layout = textLayoutOf(longName)
        assertEquals("the saved name did not wrap to two lines", 2, layout.lineCount)
        assertTrue("the second line is not cut with an ellipsis", layout.isLineEllipsized(1))
    }

    // Device check 2026-10-03 (Pixel 4a, 332dp, font scale 2.0): beside avatar, switch and
    // menu the weighted text column kept ~76dp, so "vt-u2long" and "realworld1" broke
    // mid-word and the port split from its colon. The row measures first now: when the
    // name's longest word or the endpoint's widest machine segment cannot fit beside the
    // controls, the text takes the row and the controls drop to their own end-aligned line
    // under it — TabHeader's action rule (U3).
    @Test
    @Config(qualifiers = "w332dp-h800dp")
    fun `a saved row stacks its controls when the text cannot fit beside them`() {
        val name = "vt-u2long realworld1"
        screen(
            ProfileListUiState(items = listOf(profile("a", name, "vpn.example.net:51820"))),
            fontScale = 2f,
        )
        val layout = textLayoutOf(name)
        assertTrue("the saved name did not wrap (${layout.lineCount} lines)", layout.lineCount >= 2)
        assertBreaksAtWordEdges(layout, name)
        assertEquals("the port split from its colon", 1, textLayoutOf(":51820").lineCount)
        val dns = compose.onNodeWithText("DNS 1.1.1.1 • MTU auto", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val switch = compose.onNodeWithContentDescription(name).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "the controls still shared the line (switch.top=${switch.top}, dns.bottom=${dns.bottom})",
            switch.top >= dns.bottom,
        )
        // The switch is still the labelled control, and the dropped controls are END-aligned:
        // the menu's 40dp container — M3's IconButton inside its 48dp touch target — ends 4dp
        // inside the row's content edge (the root's width minus the pinned paddings: 20dp
        // screen + 4dp sheet + 16dp row; Robolectric density 1).
        val menu = compose.onNodeWithContentDescription("More options for $name")
            .fetchSemanticsNode()
        val rowRight = compose.onRoot().fetchSemanticsNode().size.width - 40f
        assertTrue(
            "the dropped controls are not end-aligned (menu.right=${menu.boundsInRoot.right}, " +
                "rowRight=$rowRight)",
            kotlin.math.abs(menu.boundsInRoot.right - (rowRight - 4f)) < 2f,
        )
    }

    // Ruling 1 (conductor, 2026-10-03): the trigger's second half. This row is the crampiest
    // shape — the endpoint's widest segment (":51820", ~96px) squeezes into the ~104px
    // beside-space, so the per-piece rule does not fire, but the endpoint's whole width
    // (~280px) needs more than two lines beside the controls. The row stacks anyway.
    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun `a row whose endpoint needs more than two lines beside the controls stacks`() {
        val name = "Office"
        screen(
            ProfileListUiState(items = listOf(profile("a", name, "198.51.100.7:51820"))),
            fontScale = 2f,
        )
        val dns = compose.onNodeWithText("DNS 1.1.1.1 • MTU auto", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val switch = compose.onNodeWithContentDescription(name).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "the controls still shared the line (switch.top=${switch.top}, dns.bottom=${dns.bottom})",
            switch.top >= dns.bottom,
        )
    }

    // Important 1 (review wave 1): the menu's popup slot sits outside the controls cluster, so
    // opening it must not move the row's controls (composed inside the cluster, its 0-size slot
    // added the row's 12dp gap a second time while open).
    @Test
    @Config(qualifiers = "w332dp-h800dp")
    fun `opening the overflow menu does not move the row's controls`() {
        val name = "vt-u2long realworld1"
        screen(
            ProfileListUiState(items = listOf(profile("a", name, "vpn.example.net:51820"))),
            fontScale = 2f,
        )
        val menu = compose.onNodeWithContentDescription("More options for $name")
        val before = menu.fetchSemanticsNode().boundsInRoot
        menu.performClick()
        compose.waitForIdle()
        val after = compose.onNodeWithContentDescription("More options for $name")
            .fetchSemanticsNode().boundsInRoot
        assertEquals("opening the menu moved the controls", before, after)
    }

    @Test
    fun `a saved row keeps its controls beside the text when it fits`() {
        // The 1.0 layout is unchanged (w360dp, font scale 1): the longest word and the widest
        // machine segment both fit beside the controls, so the switch shares the text's row.
        val name = "vt-u2long realworld1"
        screen(ProfileListUiState(items = listOf(profile("a", name, "vpn.example.net:51820"))))
        val dns = compose.onNodeWithText("DNS 1.1.1.1 • MTU auto", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val switch = compose.onNodeWithContentDescription(name).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "the controls dropped below a row that fits (switch.top=${switch.top}, dns.bottom=${dns.bottom})",
            switch.top < dns.bottom,
        )
    }

    @Test
    fun `the hero endpoint wraps at machine break points`() {
        screen(
            ProfileListUiState(
                items = listOf(profile("a", longName, "vpn.example.net:51820")),
                activeId = "a",
                phase = ConnectionPhase.CONNECTED,
                handshakeState = HandshakeState.VERIFIED,
            ),
        )
        // The endpoint renders as machine-value segments; the port segment is whole.
        assertEquals(1, textLayoutOf(":51820").lineCount)
    }

    // J6: the hero's switch follows the saved row's large-text rule. At font scale 2 the
    // long name's words cannot fit beside the switch (the beside-space is the strip's
    // content width minus the 40dp avatar, the two 14dp gaps and the 52dp switch), so the
    // switch drops to its own end-aligned line under the header text.
    @Test
    fun `the hero switch drops under the header text when the name cannot fit beside it`() {
        screen(
            ProfileListUiState(
                items = listOf(profile("a", longName, "vpn.example.net:51820")),
                activeId = "a",
                phase = ConnectionPhase.CONNECTED,
                handshakeState = HandshakeState.VERIFIED,
            ),
            fontScale = 2f,
        )
        val port = compose.onNodeWithText(":51820", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val switch = compose.onNodeWithContentDescription(longName).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "the switch did not drop under the header text (switch.top=${switch.top}, port.bottom=${port.bottom})",
            switch.top >= port.bottom,
        )
        // END-aligned: the switch's right edge sits on the strip's content edge — the
        // root's width minus the pinned paddings (20dp screen + 20dp Sheet + 12dp strip;
        // Robolectric density 1); M3's Switch is 52dp wide, so its touch target adds no
        // horizontal padding.
        val stripRight = compose.onRoot().fetchSemanticsNode().size.width - 52f
        assertTrue(
            "the dropped switch is not end-aligned (switch.right=${switch.right}, stripRight=$stripRight)",
            kotlin.math.abs(switch.right - stripRight) < 2f,
        )
    }

    // J6's fit branch: "Home" and the endpoint's widest machine segment fit beside the
    // switch at font scale 1, so the switch shares the header line like a saved row's
    // controls.
    @Test
    fun `the hero keeps its switch beside the text when it fits`() {
        screen(
            ProfileListUiState(
                items = listOf(profile("a", "Home", "10.0.0.1:51820")),
                activeId = "a",
                phase = ConnectionPhase.CONNECTED,
                handshakeState = HandshakeState.VERIFIED,
            ),
        )
        val name = compose.onNodeWithText("Home").fetchSemanticsNode().boundsInRoot
        val switch = compose.onNodeWithContentDescription("Home").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "the switch dropped below a name that fits beside it (switch.top=${switch.top}, name.bottom=${name.bottom})",
            switch.top < name.bottom,
        )
        assertTrue(
            "the switch is not beside the name (switch.left=${switch.left}, name.right=${name.right})",
            switch.left > name.right,
        )
    }

    // The hero card was not device-checked: this pins it at the device's width. Since J6
    // the hero carries the row's connect switch: at this width and font scale the name's
    // words cannot fit beside it, so the switch drops under the text and the name keeps
    // the full column.
    @Test
    @Config(qualifiers = "w332dp-h800dp")
    fun `the hero keeps whole words at 332dp scale 2`() {
        screen(
            ProfileListUiState(
                items = listOf(profile("a", longName, "vpn.example.net:51820")),
                activeId = "a",
                phase = ConnectionPhase.CONNECTED,
                handshakeState = HandshakeState.VERIFIED,
            ),
            fontScale = 2f,
        )
        val layout = textLayoutOf(longName)
        assertEquals("the hero name did not wrap to two lines", 2, layout.lineCount)
        assertBreaksAtWordEdges(layout, longName)
        assertEquals("the port split from its colon", 1, textLayoutOf(":51820").lineCount)
    }
}
