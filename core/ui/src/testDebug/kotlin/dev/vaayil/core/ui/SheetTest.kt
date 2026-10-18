package dev.vaayil.core.ui

import android.app.Application
import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Film-stack mechanics enforcement (spec §3.3.3): the *cumulative* nesting cap of
 * three films across `Sheet` compositions, and `LiftedSheet` resetting the count
 * for its children. (`filmsRejectsFour` lives in `VellumTokensTest`.)
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class SheetTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun nestedSheetsRejectFourthFilm() {
        val thrown = runCatching {
            compose.setContent {
                Sheet(Films.One) {
                    Sheet(Films.One) {
                        Sheet(Films.One) {
                            Sheet(Films.One) {}
                        }
                    }
                }
            }
        }.exceptionOrNull()

        checkNotNull(thrown) { "a fourth nested film must fail composition" }
        var cause: Throwable = thrown
        while (cause !is IllegalArgumentException && cause.cause != null) cause = cause.cause!!
        check(cause is IllegalArgumentException) { "expected the 3-film cap failure, got: $thrown" }
        assertEquals("Vellum caps nesting at 3 films (got 4)", cause.message)
    }

    @Test
    fun liftedSheetResetsDepth() {
        compose.setContent {
            // The wrapper Sheet raises the ambient depth to 1; LiftedSheet must
            // reset it to Board, or the inner Sheet(Three) computes 1 + 3 = 4 and
            // fails the cap. Without the reset this composition throws.
            Sheet(Films.One) {
                LiftedSheet {
                    Sheet(Films.Three) {}
                }
            }
        }
        compose.onRoot().assertExists()
    }

    @Test
    fun strokeRadiusDegradesOnSubStrokeNodes() {
        // A node under 2dp would invert the seam/boundary radius clamp (half the
        // size is below the 1dp stroke) and crash the draw pass; it must degrade
        // to the stroke radius instead. (The draw phase itself does not surface
        // under Robolectric ui-test, so this is pinned as pure math.)
        assertEquals(1f, strokeRadius(unclamped = 10f, strokePx = 1f, widthPx = 1f, heightPx = 1f), 0f)
        assertEquals(6f, strokeRadius(unclamped = 10f, strokePx = 1f, widthPx = 12f, heightPx = 12f), 0f)
        assertEquals(10f, strokeRadius(unclamped = 10f, strokePx = 1f, widthPx = 20f, heightPx = 100f), 0f)
    }

    @Test
    fun pressedResolvesOneFilmDeeper() {
        // Spec §3.4.3: "clickable variant: +1 film pressed" — the tone step IS the
        // feedback. Pinned per depth across the whole legal domain (0..3, the cap).
        assertEquals(VellumColors.Container1, pressedFill(0))
        assertEquals(VellumColors.Container2, pressedFill(1))
        assertEquals(VellumColors.Container3, pressedFill(2))
        assertEquals(VellumColors.Container4, pressedFill(3))
    }

    @Test
    fun clickableSheetCentersContentWhenMinHeightExceedsIt() {
        // Device review 2026-09-21 C7 / finding 5: "realworld1" sat 4.7dp high because
        // the clickable Sheet's Column was hard-top-aligned while the pill wore
        // heightIn(min = 48.dp). The probe (20dp Box, contentPadding 0dp) in a 48dp
        // sheet must land within 0.5dp of the sheet's vertical centre — the review's
        // own acceptance for the fix.
        compose.setContent {
            Sheet(
                onClick = {},
                films = Films.Board,
                seam = false,
                contentPadding = 0.dp,
                modifier = Modifier.width(120.dp).heightIn(min = 48.dp).testTag("sheet"),
            ) {
                Box(Modifier.size(20.dp).testTag("probe"))
            }
        }
        val density = compose.density.density
        val sheet = compose.onNodeWithTag("sheet").fetchSemanticsNode()
        // The clickable modifier merges descendants, so the probe's tag only shows
        // in the unmerged tree.
        val probe = compose.onNodeWithTag("probe", useUnmergedTree = true).fetchSemanticsNode()
        val sheetCenter = sheet.positionInRoot.y + sheet.size.height / 2f
        val probeCenter = probe.positionInRoot.y + probe.size.height / 2f
        val tolerance = 0.5f * density
        assert(
            abs(sheetCenter - probeCenter) <= tolerance,
        ) {
            "probe centre ${probeCenter - sheetCenter}px off the sheet centre (tolerance ${tolerance}px)"
        }
    }

    /** The `vellumBoundary` stroke is a `drawBehind` modifier; on the composed node
     *  it surfaces as a `DrawBehindElement` (the element class — its node implements
     *  `DrawModifier`, the element does not, so an `is DrawModifier` probe sees
     *  nothing). Matched by qualified name because the class is not public; seam is
     *  off in the probe compositions, so this element can only be the boundary. */
    private fun boundaryDrawCount(tag: String): Int =
        compose.onNodeWithTag(tag).fetchSemanticsNode().layoutInfo.getModifierInfo()
            .count { it.modifier::class.qualifiedName == "androidx.compose.ui.draw.DrawBehindElement" }

    @Test
    fun clickableSheetDrawsBoundaryOnlyWhenEnabled() {
        // Device review 2026-09-21 C7 / finding 6: the stroke-3 control outline drew
        // even when the control was disabled — a "tap me" edge on a control that does
        // nothing. Honest Controls: the boundary is control affordance, so it draws
        // IFF the Sheet is enabled. Two identical compositions, differing only in
        // `enabled` (seam off — the only drawBehind in either chain is the boundary).
        compose.setContent {
            Sheet(
                onClick = {},
                films = Films.Board,
                seam = false,
                enabled = false,
                contentPadding = 0.dp,
                modifier = Modifier.width(120.dp).heightIn(min = 48.dp).testTag("disabled"),
            ) {
                Box(Modifier.size(20.dp))
            }
            Sheet(
                onClick = {},
                films = Films.Board,
                seam = false,
                contentPadding = 0.dp,
                modifier = Modifier.width(120.dp).heightIn(min = 48.dp).testTag("enabled"),
            ) {
                Box(Modifier.size(20.dp))
            }
        }
        assertEquals(
            "a disabled clickable Sheet must not draw the stroke-3 boundary",
            0,
            boundaryDrawCount("disabled"),
        )
        assertEquals(
            "an enabled clickable Sheet draws exactly one boundary stroke",
            1,
            boundaryDrawCount("enabled"),
        )
    }

    @Test
    fun clickableSheetBoundaryParamSuppressesStroke() {
        // Device review 2026-09-21 C10: BackupAction's sheet carries NO stroke — the
        // page keeps one edge style (the seam) and a sage text label names the
        // control instead (spec R5's text-label route). `boundary = false` must
        // remove the only drawBehind in the chain (seam off), exactly like a
        // disabled sheet — while the sheet stays enabled and clickable.
        compose.setContent {
            Sheet(
                onClick = {},
                films = Films.Board,
                seam = false,
                boundary = false,
                contentPadding = 0.dp,
                modifier = Modifier.width(120.dp).heightIn(min = 48.dp).testTag("unbounded"),
            ) {
                Box(Modifier.size(20.dp))
            }
        }
        assertEquals(
            "boundary = false must suppress the stroke-3 perimeter on an enabled sheet",
            0,
            boundaryDrawCount("unbounded"),
        )
    }
}
