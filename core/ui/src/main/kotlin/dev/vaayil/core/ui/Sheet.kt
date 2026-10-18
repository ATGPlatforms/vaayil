package dev.vaayil.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The `sheet-lift` elevation token (DESIGN.md, device review 2026-09-21 C6):
 * `elevation 6dp, ambient #3A301C, spot #3A301C @ 60%`. Android does NOT render
 * these alphas literally: `Modifier.shadow` multiplies them by the theme's
 * `ambientShadowAlpha` (~0.039) and `spotShadowAlpha` (~0.19), so full-alpha
 * ambient lands at ~3.9% and the 0x99 spot at ~11% effective strength — the halo
 * the review measured as "visible, ΔRGB ≥ 8" on the 4a. The first port used the
 * CSS token's web alphas (0x0F/0x14, 6%/8%), which the same multiplier crushed to
 * ~0.2%/1.5% — a shadow that measures 2–3 RGB units, i.e. invisible. Shadow
 * casting is reserved for lifted surfaces — `LiftedSheet`, the `PillSegmented`
 * selected segment, and a focused `SecretTextField` (spec §3.3.1, §3.4.3, §3.5;
 * the Split Tunnel search pill left the list in C6 — it is a filled field, not a
 * lift) — and every one of them routes through [sheetLift], so the token has
 * exactly one definition.
 */
private val SheetLiftAmbient = Color(0xFF3A301C)
private val SheetLiftSpot = Color(0x993A301C)

/** The one `sheet-lift` elevation; `VellumDropdownMenu` matches it for its popup surface. */
internal val SheetLiftElevation = 6.dp

/**
 * The one `sheet-lift` shadow pass; permitted call sites are pinned in the file
 * header (public because the Split Tunnel search pill composes it from
 * `:feature:profiles` — feature modules never re-derive the shadow themselves).
 */
fun Modifier.sheetLift(shape: Shape): Modifier = shadow(
    elevation = SheetLiftElevation,
    shape = shape,
    ambientColor = SheetLiftAmbient,
    spotColor = SheetLiftSpot,
    clip = false,
)

/**
 * Pressed fill for the clickable `Sheet`: one film deeper (spec §3.4.3 "+1 film
 * pressed"). `depth` is required <= 3 by `Sheet` and the ladder runs to index 5,
 * so `depth + 1` is always in range — no clamp needed. The swap is instant (no
 * animation), so reduced motion keeps it unchanged (spec §3.4.2 preserves press
 * feedback); the tone step is the SOLE press feedback — no ripple — and the
 * seam and stroke-3 boundary keep drawing unchanged during press.
 */
internal fun pressedFill(depth: Int): Color = VellumColors.ladder[depth + 1]

/**
 * Resolves the stroke radius for a node of the given size. A node under 2dp
 * would invert the coerce range (half the size is below the 1dp stroke) and
 * crash the draw pass; degrade to a full-stroke radius instead of throwing.
 */
internal fun strokeRadius(unclamped: Float, strokePx: Float, widthPx: Float, heightPx: Float): Float =
    unclamped.coerceIn(strokePx, maxOf(strokePx, minOf(widthPx, heightPx) / 2f))

/**
 * A Vellum sheet: `films` more paper films laid over whatever depth the ambient
 * stack already carries. The *cumulative* depth is capped at three (spec §3.3.1)
 * and re-provided to the content, so a 1-film card inside a 3-film well fails
 * fast instead of silently bottoming out. The fill comes from `VellumColors.ladder`,
 * the cut-paper seam (closed 1dp `Seam` perimeter) is optional.
 */
@Composable
fun Sheet(
    films: Films,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(10.dp),
    seam: Boolean = true,
    contentPadding: Dp = 20.dp,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(12.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val depth = LocalFilms.current.count + films.count
    val film = Films.of(depth)
    CompositionLocalProvider(LocalFilms provides film) {
        Column(
            modifier = modifier
                .background(VellumColors.ladder[depth], shape)
                .then(if (seam) Modifier.vellumSeam(shape) else Modifier)
                .padding(contentPadding),
            verticalArrangement = verticalArrangement,
            content = content,
        )
    }
}

/**
 * The clickable `Sheet` variant: a control, so it wears the 1dp `Stroke3`
 * boundary on all four sides at rest (a full perimeter, like the seam since
 * the 2026-09-21 device-fix amendment) — drawn via `drawBehind`; `Modifier.border` is banned (spec §3.3.2).
 * The boundary is control affordance, so it draws ONLY when `enabled` — a
 * disabled control never advertises itself ("tap me" ink on a control that
 * does nothing breaks Honest Controls; device review 2026-09-21 C7).
 * Press feedback is the tone step alone (`pressedFill`, `indication = null`):
 * spec §3.4.2's press token is "+1 film for 90ms", not an overlay glow, and a
 * ripple on top of the film stack would contradict the depth story. Nothing
 * scales or slides. A disabled control never steps its tone (`clickable`
 * ignores presses), so callers get the plain rest fill. Content centers
 * vertically when a caller's `heightIn(min = …)` exceeds the content — the
 * review measured "realworld1" sitting 4.7dp high under the old hard
 * top-alignment (C7); `verticalArrangement` is exposed for parity with the
 * resting overload. [boundary] = false opts out of the stroke entirely, for the
 * clickable sheet whose identity a text label carries instead of a perimeter —
 * the BackupAction route (device review 2026-09-21, C10: "one edge style on the
 * page"; spec R5's text-label affordance).
 */
@Composable
fun Sheet(
    onClick: () -> Unit,
    films: Films,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: RoundedCornerShape = RoundedCornerShape(10.dp),
    seam: Boolean = true,
    boundary: Boolean = true,
    contentPadding: Dp = 20.dp,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    content: @Composable ColumnScope.() -> Unit,
) {
    val depth = LocalFilms.current.count + films.count
    val film = Films.of(depth)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    CompositionLocalProvider(LocalFilms provides film) {
        Column(
            modifier = modifier
                .background(
                    if (pressed) pressedFill(depth) else VellumColors.ladder[depth],
                    shape,
                )
                .then(if (seam) Modifier.vellumSeam(shape) else Modifier)
                .then(if (enabled && boundary) Modifier.vellumBoundary(shape, VellumColors.Stroke3) else Modifier)
                .clip(shape)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick,
                )
                .padding(contentPadding),
            verticalArrangement = verticalArrangement,
            content = content,
        )
    }
}

/**
 * The lifted sheet: the one *container* surface that rises off the board. It
 * resets the film stack to `Films.Board`, paints `Canvas` (depth 0), always
 * draws the seam, and applies the `sheet-lift` shadow.
 */
@Composable
fun LiftedSheet(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(10.dp),
    contentPadding: Dp = 20.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    CompositionLocalProvider(LocalFilms provides Films.Board) {
        Column(
            modifier = modifier
                .sheetLift(shape)
                .background(VellumColors.Canvas, shape)
                .vellumSeam(shape)
                .padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/**
 * The Vellum cut-paper seam (spec §3.3.2, amended 2026-09-21 by the device
 * review): ONE closed perimeter — a single 1dp `drawRoundRect` stroke, inset
 * 0.5dp, corner radius r−0.5dp, `StrokeCap.Butt`, via `drawBehind` — sharing
 * `vellumBoundary`'s geometry. The device review retired the old top+left-only
 * stroke: on real panels it read as an open bracket (the eye completes the
 * shape and finds two sides missing). Every call site inherits the closed
 * edge; because the stroke is inset half its width, abutting sheets still
 * never double-draw to 2dp.
 */
fun Modifier.vellumSeam(
    shape: RoundedCornerShape,
    color: Color = VellumColors.Seam,
): Modifier = vellumBoundary(shape, color)

/**
 * The one closed-perimeter 1dp stroke (spec §3.3.2): inset 0.5dp, corner
 * radius r−0.5dp, `StrokeCap.Butt`, via `drawBehind`. `vellumBoundary` wears
 * it in `Stroke3` as the control affordance; `vellumSeam` shares the exact
 * geometry in the decorative seam colour.
 */
private fun Modifier.vellumBoundary(
    shape: RoundedCornerShape,
    color: Color,
): Modifier = drawBehind {
    val stroke = 1.dp.toPx()
    val radius = strokeRadius(
        unclamped = shape.topStart.toPx(size, this),
        strokePx = stroke,
        widthPx = size.width,
        heightPx = size.height,
    )
    drawRoundRect(
        color = color,
        topLeft = Offset(stroke / 2f, stroke / 2f),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(radius - stroke / 2f),
        style = Stroke(width = stroke, cap = StrokeCap.Butt),
    )
}
