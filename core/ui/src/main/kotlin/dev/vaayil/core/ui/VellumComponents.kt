package dev.vaayil.core.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Vellum components (DESIGN.md): the breathing dot, state pill, micro badge,
 * segmented control, sparkline, brand mark, and the form-well/switch colour
 * mappings. M3-mapped color values come from MaterialTheme so :app's Theme.kt
 * stays the single token mapping; fixed tones come from `VellumColors`
 * (`Vellum.kt` — the theme is light-only, so statics are exact).
 */

/**
 * Breathing 7dp dot; motion is state feedback only (DESIGN.md §6). The breath is
 * opacity only — 2400ms 1.0→0.6 — and reduced motion holds it static at full
 * opacity (spec §3.4.2). The breath is sampled every 100ms (about 10Hz), not every
 * frame, so an idle dashboard draws about 10 frames a second (G2). The animated
 * value is read in the draw phase (`graphicsLayer`), so the breath redraws this
 * dot's layer without recomposing it. Callers pass the state's dot token:
 * `SageMark` active, `IdleDot` idle, `WarningMark` warning, `ErrorMark` error.
 * [onComposed] is a test-only composition probe.
 */
@Composable
fun BreathingDot(color: Color, modifier: Modifier = Modifier, onComposed: (() -> Unit)? = null) {
    onComposed?.invoke()
    val breath = remember { mutableFloatStateOf(1f) }
    val reducedMotion = LocalReducedMotion.current
    LaunchedEffect(reducedMotion) {
        if (reducedMotion) {
            // Reduced motion: static at full opacity, and no loop runs.
            breath.floatValue = 1f
            return@LaunchedEffect
        }
        val start = withInfiniteAnimationFrameMillis { it }
        while (true) {
            delay(BREATH_STEP_MS)
            withInfiniteAnimationFrameMillis { now -> breath.floatValue = breathAlpha(now - start) }
        }
    }
    Box(
        modifier
            .size(7.dp)
            // G2: the State read lives inside the draw lambda, so the breath redraws
            // without recomposing (and the window redraws only this layer).
            .graphicsLayer { alpha = breath.value }
            .background(color, RoundedCornerShape(50)),
    )
}

/** The breath's step, so an idle screen redraws the dot about ten times a second (G2). */
private const val BREATH_STEP_MS = 100L

private const val BREATH_PERIOD_MS = 2400L

/**
 * The dot's opacity [elapsedMs] into an unbounded breath: a 2400ms fall from 1.0 to
 * 0.6, then the same curve reversed back up (`tween`'s default easing is
 * `FastOutSlowInEasing`, and repeat-reverse plays it backwards). Pure, so tests pin
 * the curve the old `rememberInfiniteTransition` produced.
 */
internal fun breathAlpha(elapsedMs: Long): Float {
    val leg = elapsedMs % (BREATH_PERIOD_MS * 2)
    val fraction = if (leg < BREATH_PERIOD_MS) {
        leg / BREATH_PERIOD_MS.toFloat()
    } else {
        (BREATH_PERIOD_MS * 2 - leg) / BREATH_PERIOD_MS.toFloat()
    }
    return 1f - 0.4f * FastOutSlowInEasing.transform(fraction)
}

enum class PillTone { ACTIVE, IDLE, WARNING, ERROR }

/**
 * State pill with breathing dot (spec §3.4.3); non-interactive, no states. 32dp tall at
 * normal text sizes; at large font scales the label wraps and the pill grows instead of
 * cutting it off (F29).
 */
@Composable
fun StatusPill(label: String, tone: PillTone, modifier: Modifier = Modifier) {
    val container: Color
    val content: Color
    val dot: Color
    when (tone) {
        PillTone.ACTIVE -> {
            container = VellumColors.FieldActive; content = VellumColors.OnFieldActive; dot = VellumColors.SageMark
        }
        PillTone.IDLE -> {
            container = VellumColors.FieldIdle; content = VellumColors.OnSurfaceVariant; dot = VellumColors.IdleDot
        }
        PillTone.WARNING -> {
            container = VellumColors.Warning; content = VellumColors.OnWarning; dot = VellumColors.WarningMark
        }
        PillTone.ERROR -> {
            // on-error on error = 7.60:1 — the old white-on-clay was 4.1:1.
            container = VellumColors.Error; content = VellumColors.OnError; dot = VellumColors.ErrorMark
        }
    }
    Surface(
        // A floor, not a fixed height: Surface hands the 32dp minimum to the row, so normal
        // text renders exactly as before, and a wrapped label grows the pill.
        modifier = modifier.heightIn(min = 32.dp),
        shape = RoundedCornerShape(50),
        color = container,
        contentColor = content,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BreathingDot(dot)
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * The eyebrow (device review 2026-09-21, C4): uppercase technical micro-copy —
 * ledger labels (DOWN, DNS), section headers, badges — in the themed 11sp/700/
 * +0.115em label slot. The eyebrow style is for uppercase ONLY: this composable
 * uppercases its input, so a mixed-case string can never render in that style
 * (mixed-case labels belong in `titleSmall`). Single line by default — eyebrows are
 * micro-copy, never body text. [wrap] lets a long title (the warning cards') wrap
 * at large font scales instead of being cut off (F29).
 */
@Composable
fun VellumEyebrow(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = VellumColors.OnSurfaceVariant,
    wrap: Boolean = false,
) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = if (wrap) Int.MAX_VALUE else 1,
        softWrap = wrap,
        modifier = modifier,
    )
}

/**
 * Uppercase technical micro-copy badge (WIREGUARD, cipher tags) on container-2,
 * 6dp radius, 1dp seam, in the themed 11/700 label slot (spec §3.4.3). Fill and
 * seam draw in this chain — a `Surface` paints its fill internally and would
 * cover an outer seam.
 */
@Composable
fun MicroBadge(text: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = modifier
            .background(VellumColors.Container2, shape)
            .vellumSeam(shape)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        VellumEyebrow(text)
    }
}

/** Section header: functional icon, bold title, optional uppercase badge. */
@Composable
fun SectionHeader(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
    badge: String? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        badge?.let { MicroBadge(it) }
    }
}

/**
 * Vellum form-well colours (spec §3.4.3): the well rests on container-3, lifts
 * to canvas on focus, drops to container-2 when disabled. Every M3 indicator is
 * transparent — the well's boundary is the closed 1dp seam perimeter plus the
 * 2dp focus/error ring drawn by the field that applies these colours (e.g.
 * `SecretTextField`); inline errors speak m3-error. Features stop carrying
 * private copies of this mapping.
 */
@Composable
fun vellumFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = VellumColors.Canvas,
    unfocusedContainerColor = VellumColors.Container3,
    errorContainerColor = VellumColors.Container3,
    disabledContainerColor = VellumColors.Container2,
    focusedTextColor = VellumColors.OnSurface,
    unfocusedTextColor = VellumColors.OnSurface,
    disabledTextColor = VellumColors.Disabled,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
    errorIndicatorColor = Color.Transparent,
    errorSupportingTextColor = VellumColors.M3Error,
)

/**
 * Machine data wears the mono face (The Data Speaks Mono Rule). The mono face
 * pins at 14sp/20sp with zero tracking (device review 2026-09-21, C4): at 15sp
 * mono glyphs matched the Schibsted cap height but ran 25% wider; at 14sp the
 * digits sit just under the caps and endpoints fit their columns.
 */
@Composable
fun monoTextStyle(mono: Boolean = true) =
    if (mono) {
        MaterialTheme.typography.bodyMedium.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.sp,
        )
    } else {
        MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Default)
    }

/**
 * Vellum switch colours (spec §3.2 / §3.4.3): affirming toggles wear sage with
 * a canvas thumb; the off track is container-4 outlined in stroke-3 — the off
 * state is identifiable by the stroke, not the fill — under a stroke-3 thumb
 * (device review 2026-09-21, C9: the old canvas thumb vanished against the
 * track at 1.28:1; stroke-3 is 3.54:1 — the familiar M3 off-knob).
 */
@Composable
fun vellumSwitchColors() = SwitchDefaults.colors(
    checkedTrackColor = VellumColors.Primary,
    checkedThumbColor = VellumColors.Canvas,
    checkedBorderColor = Color.Transparent,
    uncheckedTrackColor = VellumColors.Container4,
    uncheckedThumbColor = VellumColors.Stroke3,
    uncheckedBorderColor = VellumColors.Stroke3,
)

/**
 * Two-option pill segmenter (Exclusive / Inclusive mode switches), spec §3.4.3:
 * a 52dp container-4 track (radius 999) with the selected option as a lifted
 * canvas pill — `sheet-lift` + seam + `on-surface`, the paper physically rising
 * out of the track. Unselected options sit transparent in `on-surface-variant`
 * and step one film deeper when pressed; press feedback is tonal only
 * (`indication = null`, the Task-3 ripple ruling). The track is a
 * `selectableGroup` and each segment carries `Role.RadioButton` + `selected`
 * semantics so screen readers announce the current option.
 */
@Composable
fun PillSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val trackShape = RoundedCornerShape(50)
    val segmentShape = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .selectableGroup()
            .fillMaxWidth()
            .height(52.dp)
            .background(VellumColors.Container4, trackShape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            val interactionSource = remember { MutableInteractionSource() }
            val pressed by interactionSource.collectIsPressedAsState()
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .then(if (selected) Modifier.sheetLift(segmentShape) else Modifier)
                    .background(
                        when {
                            selected -> VellumColors.Canvas
                            // +1 film pressed: the container-4 track deepens to container-5.
                            pressed -> VellumColors.Container5
                            else -> Color.Transparent
                        },
                        segmentShape,
                    )
                    .then(if (selected) Modifier.vellumSeam(segmentShape) else Modifier)
                    .clip(segmentShape)
                    .selectable(
                        selected = selected,
                        role = Role.RadioButton,
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = { onSelect(index) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    option,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) VellumColors.OnSurface else VellumColors.OnSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

/**
 * Rounded sparkline for telemetry history; flat line when no samples yet. Real data only —
 * callers pass an empty list rather than fabricating a shape.
 */
@Composable
fun Sparkline(
    samples: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        if (samples.size < 2) return@Canvas
        val max = (samples.maxOrNull() ?: return@Canvas).coerceAtLeast(1e-6f)
        val min = samples.minOrNull() ?: 0f
        val range = (max - min).coerceAtLeast(1e-6f)
        val step = size.width / (samples.size - 1)
        var previous: Offset? = null
        samples.forEachIndexed { index, sample ->
            val x = step * index
            val y = size.height - ((sample - min) / range) * size.height
            val current = Offset(x, y)
            previous?.let { last ->
                drawLine(color, last, current, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            }
            previous = current
        }
    }
}

/**
 * The brand mark: an interlocking V with a gateway notch (DESIGN.md §5 / product logo).
 * Drawn, not rasterized, so it scales cleanly and follows the sage palette.
 */
@Composable
fun VaayilLogoMark(
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.primaryContainer,
    glyph: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    notch: Color = MaterialTheme.colorScheme.surfaceContainerLowest,
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.22f
        // Left arm of the V
        drawLine(
            container,
            Offset(w * 0.16f, h * 0.14f),
            Offset(w * 0.5f, h * 0.9f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        // Right arm, drawn darker where it overlaps to suggest interlocking
        drawLine(
            container.copy(alpha = 0.82f),
            Offset(w * 0.84f, h * 0.14f),
            Offset(w * 0.5f, h * 0.9f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        // Gateway notch at the V's vertex
        drawCircle(notch, radius = w * 0.09f, center = Offset(w * 0.5f, h * 0.46f))
    }
}
