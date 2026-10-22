package dev.vaayil.core.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** How loud the button affirms. */
enum class VellumButtonRole { PRIMARY, SECONDARY }

/**
 * The Vellum button (spec §3.4.3): 52dp minimum target, 10dp radius, no ripple.
 * Primary: `primary` fill with a canvas label — pressed deepens by an 8% ink
 * overlay, never a scale. Secondary: container-2 with the cut seam and an
 * `on-surface` label — pressed steps one film deeper. Disabled: container-3
 * fill with `disabled` ink, both roles. [labelColor] overrides the label ink
 * for destructive confirmations (the restore replace-confirm wears `error-mark`
 * on the secondary construction — never a red fill, spec §3.5 Backup).
 */
@Composable
fun VellumButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    role: VellumButtonRole = VellumButtonRole.PRIMARY,
    leadingIcon: ImageVector? = null,
    labelColor: Color = Color.Unspecified,
) {
    val shape = RoundedCornerShape(10.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val fill = when {
        !enabled -> VellumColors.Container3
        role == VellumButtonRole.PRIMARY ->
            if (pressed) {
                VellumColors.OnSurface.copy(alpha = 0.08f).compositeOver(VellumColors.Primary)
            } else {
                VellumColors.Primary
            }
        // Secondary rests on container-2 (ladder[2]); pressed is +1 film.
        pressed -> pressedFill(2)
        else -> VellumColors.Container2
    }
    val contentColor = when {
        !enabled -> VellumColors.Disabled
        labelColor != Color.Unspecified -> labelColor
        role == VellumButtonRole.PRIMARY -> VellumColors.Canvas // #FBFAF7, per spec
        else -> VellumColors.OnSurface
    }
    Row(
        modifier = modifier
            .heightIn(min = 52.dp)
            .background(fill, shape)
            .then(if (role == VellumButtonRole.SECONDARY) Modifier.vellumSeam(shape) else Modifier)
            .clip(shape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
                role = Role.Button,
            )
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        leadingIcon?.let {
            Icon(it, contentDescription = null, tint = contentColor, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        // No line cap: at large font scales a long label wraps instead of being cut off (F29).
        // It stays start-aligned, because centring moves some one-line labels by a pixel.
        Text(label, style = MaterialTheme.typography.labelLarge, color = contentColor)
    }
}

/**
 * The ONE quiet-action underline (spec §3.4.3): a text-width 1dp stroke along the
 * text node's bottom edge, drawn via `drawBehind` so it can't be covered. Public
 * because feature modules compose it (`BackupAction`'s non-interactive quiet
 * label, device review 2026-09-21 C10) and there must be a single underline
 * implementation, as with the seam. [color] lets pressed states darken it:
 * `VellumQuietAction` passes stroke-3 under the finger.
 */
fun Modifier.vellumQuietUnderline(color: Color = VellumColors.Seam): Modifier = drawBehind {
    val stroke = 1.dp.toPx()
    val y = size.height - stroke / 2f
    drawLine(
        color = color,
        start = Offset(0f, y),
        end = Offset(size.width, y),
        strokeWidth = stroke,
    )
}

/**
 * The quiet action (spec §3.4.3): an `on-surface-variant` label over a 1dp seam
 * underline, 48dp minimum target, no container. Pressed steps the underline to
 * `stroke-3` — the boundary becomes momentarily load-bearing under the finger.
 * [onClickLabel] names the action for screen readers when the visible label alone
 * does not say what it does.
 * It reports the button role, as [VellumButton] does, so screen readers
 * announce it as a button (#17).
 */
@Composable
fun VellumQuietAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    mono: Boolean = false,
    onClickLabel: String? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClickLabel = onClickLabel,
                onClick = onClick,
                role = Role.Button,
            )
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        icon?.let {
            Icon(it, contentDescription = null, tint = VellumColors.OnSurfaceVariant, modifier = Modifier.size(16.dp))
        }
        Text(
            label,
            style = if (mono) monoTextStyle() else MaterialTheme.typography.labelLarge,
            color = VellumColors.OnSurfaceVariant,
            // U3 (sweep 2, PF4): a long quiet-action label wraps within the space left of
            // the icon instead of clipping sideways at large font scales; short labels keep
            // their natural width (weight fill=false).
            modifier = Modifier
                .weight(1f, fill = false)
                .vellumQuietUnderline(if (pressed) VellumColors.Stroke3 else VellumColors.Seam),
        )
    }
}

/**
 * The dropdown menu as a lifted surface (spec §3.4.3 "Profile pill + dropdown"):
 * canvas fill, 10dp radius, the sheet-lift elevation, and a 1dp decorative seam.
 * A popup never abuts another sheet, so the seam rides the full perimeter via
 * the menu's own border slot — decorative only, never a load-bearing boundary.
 */
@Composable
fun VellumDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        containerColor = VellumColors.Canvas,
        tonalElevation = 0.dp,
        shadowElevation = SheetLiftElevation,
        border = BorderStroke(1.dp, VellumColors.Seam),
        content = content,
    )
}

/**
 * A menu row inside [VellumDropdownMenu]. `selected` rows carry the primary
 * check (spec: "selected row carries a `primary` check").
 */
@Composable
fun VellumDropdownMenuItem(
    text: String,
    onClick: () -> Unit,
    selected: Boolean = false,
    leadingIcon: ImageVector? = null,
) {
    DropdownMenuItem(
        text = { Text(text, color = VellumColors.OnSurface) },
        leadingIcon = when {
            selected -> {
                {
                    Icon(Icons.Rounded.Check, contentDescription = null, tint = VellumColors.Primary)
                }
            }
            leadingIcon != null -> {
                {
                    Icon(leadingIcon, contentDescription = null, tint = VellumColors.OnSurfaceVariant)
                }
            }
            else -> null
        },
        onClick = onClick,
    )
}
