package dev.vaayil.core.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * The one Vellum text field (spec §3.4.3): a container-3 well with the closed
 * 1dp `seam` perimeter (device-fix amendment — the old top-edge-only line read
 * as a faint band with a stray rule) and the label rendered ABOVE the well in
 * `titleSmall` (device review 2026-09-21, C4 — sentence-case labels never wear
 * the uppercase eyebrow style) — not
 * an M3 floating label. Focus lifts the well out of the stack (canvas + shared
 * `sheet-lift` + 2dp `primary` focus ring); an invalid field wears the 2dp
 * `error-mark` ring with its copy in `m3-error` (error outranks focus, matching
 * M3); a disabled one drops to container-2 with `disabled` ink. This is the
 * single field mechanism — `SecretTextField` is this plus the password
 * transformation and reveal toggle, and screens must not re-derive the well
 * colours by applying `vellumFieldColors` to a bare `TextField`.
 *
 * [modifier] applies to the well itself (so `onFocusChanged` sees real focus);
 * the label and supporting text hang outside it. Because the label is not the
 * well's own text, the well's semantics carry it as the content description, so
 * TalkBack names the field by its visible label (F27).
 */
@Composable
fun VellumField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    supportingText: String? = null,
    mono: Boolean = true,
    singleLine: Boolean = true,
    minLines: Int = 1,
    readOnly: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    trailing: (@Composable () -> Unit)? = null,
) {
    val wellShape = RoundedCornerShape(8.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            color = VellumColors.OnSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        TextField(
            value = value,
            onValueChange = onValueChange,
            label = null, // the label lives above the well, never floating inside it
            isError = isError,
            singleLine = singleLine,
            minLines = minLines,
            readOnly = readOnly,
            keyboardOptions = keyboardOptions,
            textStyle = monoTextStyle(mono),
            visualTransformation = visualTransformation,
            trailingIcon = trailing?.let { slot -> { slot() } },
            colors = vellumFieldColors(),
            shape = wellShape,
            interactionSource = interactionSource,
            modifier = modifier
                .fillMaxWidth()
                // F27: the label above is a separate Text, so name the editable node with it.
                .semantics { contentDescription = label }
                .then(if (focused) Modifier.sheetLift(wellShape) else Modifier)
                .drawWithContent {
                    drawContent()
                    // The field paints its own fill internally, so the seam and the
                    // ring draw with content — over the fill, never over the glyphs
                    // (both hug the edges). The seam is the closed 1dp perimeter of
                    // `vellumBoundary`'s geometry (spec §3.3.2 amendment): it cannot
                    // reuse the `vellumSeam` modifier because that draws BEHIND this
                    // node, i.e. under the field's internal fill.
                    val seam = 1.dp.toPx()
                    val radius = strokeRadius(
                        unclamped = wellShape.topStart.toPx(size, this),
                        strokePx = seam,
                        widthPx = size.width,
                        heightPx = size.height,
                    )
                    drawRoundRect(
                        color = VellumColors.Seam,
                        topLeft = Offset(seam / 2f, seam / 2f),
                        size = Size(size.width - seam, size.height - seam),
                        cornerRadius = CornerRadius(radius - seam / 2f),
                        style = Stroke(width = seam, cap = StrokeCap.Butt),
                    )
                    val ring = when {
                        isError -> VellumColors.ErrorMark
                        focused -> VellumColors.Primary
                        else -> return@drawWithContent
                    }
                    val stroke = 2.dp.toPx()
                    drawRoundRect(
                        color = ring,
                        topLeft = Offset(-stroke / 2f, -stroke / 2f),
                        size = Size(size.width + stroke, size.height + stroke),
                        cornerRadius = CornerRadius(radius + stroke / 2f),
                        style = Stroke(width = stroke, cap = StrokeCap.Butt),
                    )
                },
        )
        if (supportingText != null) {
            // Rendered outside the well so the focus/error ring hugs the well
            // itself, not the well-plus-error block.
            Text(
                supportingText,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) VellumColors.M3Error else VellumColors.OnSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp),
            )
        }
    }
}
