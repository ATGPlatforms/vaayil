package dev.vaayil.core.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Component geometry pinned by spec §3.4.3: `PillSegmented` fixes today's
 * content-driven track (≈48dp on device) at exactly 52dp, and `StatusPill`
 * grows 28dp → 32dp. Colors live in the token pins (`VellumTokensTest`) and
 * the draw phase is not observable under Robolectric, so these assert the
 * layout contracts screens rely on. Note the track height must be *pinned*,
 * not merely capped: under Robolectric the emulated font inflates today's
 * content-driven track to 64dp, so a >=-assert cannot discriminate. The class runs
 * under NATIVE graphics so text wraps and measures with real font metrics: the
 * large-text tests (F29) depend on it, and the pill's height is content-driven.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComponentsTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun pillSegmentedTrackIs52dp() {
        var trackHeightPx = 0
        var density = 1f
        compose.setContent {
            density = LocalDensity.current.density
            PillSegmented(
                options = listOf("Exclusive", "Inclusive"),
                selectedIndex = 0,
                onSelect = {},
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { trackHeightPx = it.size.height },
            )
        }
        compose.waitForIdle()
        val trackHeightDp = trackHeightPx / density
        assertEquals(
            "PillSegmented track must be pinned at 52dp per spec §3.4.3",
            52f,
            trackHeightDp,
            0.1f,
        )
    }

    @Test
    fun statusPillIs32dpTall() {
        var pillHeightPx = 0
        var density = 1f
        compose.setContent {
            density = LocalDensity.current.density
            StatusPill(
                label = "ACTIVE",
                tone = PillTone.ACTIVE,
                modifier = Modifier.onGloballyPositioned { pillHeightPx = it.size.height },
            )
        }
        compose.waitForIdle()
        val pillHeightDp = pillHeightPx / density
        assertEquals(32f, pillHeightDp, 0.1f)
    }

    @Test
    fun vellumFieldRendersLabelOutsideWellAndShowsErrorCopy() {
        // Spec §3.4.3: the label sits ABOVE the well (not an M3 floating label), so it
        // must exist as text without being part of the editable node; the error copy
        // renders as supporting text under the well.
        compose.setContent {
            VellumField(
                value = "10.0.0.2/32",
                onValueChange = {},
                label = "Addresses (comma-separated CIDR)",
                isError = true,
                supportingText = "Invalid address",
            )
        }
        compose.onNodeWithText("Addresses (comma-separated CIDR)").assertExists()
        compose.onNodeWithText("Invalid address").assertExists()
    }

    @Test
    fun vellumFieldPushesTypedText() {
        var captured: String? = null
        compose.setContent {
            VellumField(
                value = "",
                onValueChange = { captured = it },
                label = "Endpoint (host:port)",
            )
        }
        compose.onNode(hasSetTextAction()).performTextInput("vpn.example.net:51820")
        compose.runOnIdle { assertEquals("vpn.example.net:51820", captured) }
    }

    @Test
    fun secretTextFieldTogglesMask() {
        compose.setContent {
            var visible by remember { mutableStateOf(false) }
            SecretTextField(
                label = "Private key",
                value = "secret-material",
                onValueChange = {},
                visible = visible,
                onToggleVisible = { visible = !visible },
            )
        }
        compose.onNodeWithContentDescription("Show Private key").performClick()
        compose.onNodeWithContentDescription("Hide Private key").assertExists()
    }

    /** Renders [content] as if the system font scale were [scale] (Compose's non-linear curve applies). */
    @Composable
    private fun AtFontScale(scale: Float, content: @Composable () -> Unit) {
        CompositionLocalProvider(
            LocalDensity provides Density(LocalDensity.current.density, scale),
            content = content,
        )
    }

    /** The laid-out paragraph of the one unmerged text node rendering [text]. */
    private fun textLayoutOf(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    @Test
    fun statusPillGrowsWhenItsLabelWrapsAtLargeText() {
        // F29: at font scale 2.0 the fixed 32dp pill cut "Protected & Encrypted" off. The
        // label now wraps and the pill grows with it.
        var pillHeightPx = 0
        var density = 1f
        compose.setContent {
            density = LocalDensity.current.density
            AtFontScale(2f) {
                StatusPill(
                    label = "Protected & Encrypted",
                    tone = PillTone.ACTIVE,
                    modifier = Modifier.width(200.dp).onGloballyPositioned { pillHeightPx = it.size.height },
                )
            }
        }
        compose.waitForIdle()
        assertTrue("label stayed on one line", textLayoutOf("Protected & Encrypted").lineCount >= 2)
        assertTrue("pill stayed ${pillHeightPx / density}dp tall", pillHeightPx / density > 40f)
    }

    @Test
    fun buttonLabelWrapsInsteadOfClippingAtLargeText() {
        compose.setContent {
            AtFontScale(2f) {
                VellumButton("Import a .conf file", onClick = {}, modifier = Modifier.width(200.dp))
            }
        }
        compose.waitForIdle()
        assertTrue("label stayed on one line", textLayoutOf("Import a .conf file").lineCount >= 2)
    }

    @Test
    fun quietActionLabelWrapsInsteadOfClippingAtLargeText() {
        // U3 (sweep 2, PF4): "I've done this — get started" clipped sideways at 2.0 because
        // the label was softWrap=false, maxLines=1.
        compose.setContent {
            AtFontScale(2f) {
                VellumQuietAction(
                    "I've done this — get started",
                    onClick = {},
                    modifier = Modifier.width(220.dp),
                )
            }
        }
        compose.waitForIdle()
        assertTrue("label stayed on one line", textLayoutOf("I've done this — get started").lineCount >= 2)
    }

    @Test
    fun quietActionReportsTheButtonRole() {
        // #17: without a role, screen readers read every quiet action as a plain view, never as a button.
        compose.setContent { VellumQuietAction("Got it", onClick = {}) }
        compose.onNodeWithText("Got it")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
    }

    @Test
    fun eyebrowStaysOnOneLineByDefault() {
        compose.setContent { Box(Modifier.width(120.dp)) { VellumEyebrow("Encryption unavailable") } }
        compose.waitForIdle()
        val layout = textLayoutOf("ENCRYPTION UNAVAILABLE")
        assertEquals(1, layout.lineCount)
        assertFalse(layout.layoutInput.softWrap)
    }

    @Test
    fun eyebrowWrapsWhenTheCallerOptsIn() {
        // F29: the warning cards' titles wrap at large text instead of being cut off.
        compose.setContent { Box(Modifier.width(120.dp)) { VellumEyebrow("Encryption unavailable", wrap = true) } }
        compose.waitForIdle()
        val lines = textLayoutOf("ENCRYPTION UNAVAILABLE").lineCount
        assertTrue("the eyebrow stayed on one line", lines >= 2)
    }

    @Test
    fun vellumFieldCarriesItsLabelForAccessibility() {
        // F27: the label is a separate Text above the well, so TalkBack never announced it on
        // the field. The editable node itself carries the label.
        compose.setContent {
            VellumField(value = "vpn.example.net:51820", onValueChange = {}, label = "Endpoint (host:port)")
        }
        compose.onNode(hasSetTextAction() and hasContentDescription("Endpoint (host:port)")).assertExists()
    }

    @Test
    fun secretTextFieldCarriesItsLabelForAccessibility() {
        compose.setContent {
            SecretTextField(
                label = "Private key",
                value = "secret-material",
                onValueChange = {},
                visible = false,
                onToggleVisible = {},
            )
        }
        compose.onNode(hasSetTextAction() and hasContentDescription("Private key")).assertExists()
        compose.onNodeWithContentDescription("Show Private key").assertExists()
    }

    /**
     * The host node's centre pixel as drawn right now. `captureToImage` cannot be used under
     * Robolectric here: its `forceRedraw` waits for a window draw that never comes while the
     * test clock is paused (compose 1.8.2; upstream skips that wait on Robolectric in later
     * versions), so the compose root is drawn the way Roborazzi does instead.
     */
    private fun hostCentrePixel(): Int {
        val host = compose.onNodeWithTag("host").fetchSemanticsNode()
        val view = (host.root as ViewRootForTest).view
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val bounds = host.boundsInRoot
        return bitmap.getPixel(
            ((bounds.left + bounds.right) / 2f).toInt(),
            ((bounds.top + bounds.bottom) / 2f).toInt(),
        )
    }

    @Test
    fun `the breathing dot composes once across a full breath`() {
        // G2 (spec §4.2 of the launch-prep design): the breath lives in the draw phase, so
        // two full 2400ms cycles must not recompose the dot's body.
        var compositions = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                BreathingDot(Color.Black, Modifier.testTag("dot"), onComposed = { compositions++ })
            }
        }
        compose.waitForIdle() // frame 0
        compose.mainClock.advanceTimeBy(2400 * 2 + 100)
        assertEquals("the dot must not recompose per frame", 1, compositions)
    }

    @Test
    fun `the drawn alpha differs between frames 1200ms apart`() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                // The dot sits centred so the host's centre pixel is a pixel the dot paints.
                Box(
                    Modifier.fillMaxWidth().height(40.dp).background(Color.White).testTag("host"),
                    contentAlignment = Alignment.Center,
                ) {
                    BreathingDot(Color.Black)
                }
            }
        }
        compose.waitForIdle()
        val t0 = hostCentrePixel()
        compose.mainClock.advanceTimeBy(1200)
        val t1 = hostCentrePixel()
        assertTrue(
            "alpha must breathe: center pixel 0x${Integer.toHexString(t0)} vs 0x${Integer.toHexString(t1)}",
            kotlin.math.abs((t0 and 0xFF) - (t1 and 0xFF)) > 30,
        )
    }

    @Test
    fun `under reduced motion the drawn alpha is fully opaque at every frame`() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    Box(
                        Modifier.fillMaxWidth().height(40.dp).background(Color.White).testTag("host"),
                        contentAlignment = Alignment.Center,
                    ) {
                        BreathingDot(Color.Black)
                    }
                }
            }
        }
        compose.waitForIdle()
        val t0 = hostCentrePixel()
        compose.mainClock.advanceTimeBy(1200)
        val t1 = hostCentrePixel()
        assertEquals(t0, t1)
        // and the static dot is fully opaque: pure black on white
        assertEquals(0xFF000000.toInt(), t1)
    }

    @Test
    fun `the breath updates about ten times a second, not every frame`() {
        // G2: the breath is sampled every 100ms, so 60 frames (960ms of 16ms frames)
        // change the drawn pixel about 8 times, not every frame.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                Box(
                    Modifier.fillMaxWidth().height(40.dp).background(Color.White).testTag("host"),
                    contentAlignment = Alignment.Center,
                ) {
                    BreathingDot(Color.Black)
                }
            }
        }
        compose.waitForIdle()
        var previous = hostCentrePixel()
        var changes = 0
        repeat(60) {
            compose.mainClock.advanceTimeByFrame()
            val pixel = hostCentrePixel()
            if (pixel != previous) changes++
            previous = pixel
        }
        assertTrue(
            "expected about 8 alpha changes in 960ms, got $changes",
            changes in 5..11,
        )
    }

    @Test
    fun `breathAlpha reproduces the old one-to-0p6 reverse curve`() {
        assertEquals(1f, breathAlpha(0))
        assertEquals(0.6f, breathAlpha(2400), 1e-4f)
        assertEquals(1f, breathAlpha(4800), 1e-4f)
        assertEquals(breathAlpha(1200), breathAlpha(3600), 1e-4f)
        assertEquals(
            1f - 0.4f * FastOutSlowInEasing.transform(0.5f),
            breathAlpha(1200),
            1e-4f,
        )
    }

    @Test
    fun `reduced motion mid-breath returns the dot to fully opaque`() {
        compose.mainClock.autoAdvance = false
        val reduced = mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalReducedMotion provides reduced.value) {
                    Box(
                        Modifier.fillMaxWidth().height(40.dp).background(Color.White).testTag("host"),
                        contentAlignment = Alignment.Center,
                    ) {
                        BreathingDot(Color.Black)
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1200)
        compose.runOnIdle { reduced.value = true }
        // Under the paused clock the flip's recomposition resumes on a delivered frame;
        // pump frames until the effect restart has applied (normally the first one).
        compose.waitUntil(5_000) {
            compose.mainClock.advanceTimeByFrame()
            hostCentrePixel() == 0xFF000000.toInt()
        }
        assertEquals(0xFF000000.toInt(), hostCentrePixel())
    }
}
