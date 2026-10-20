package dev.vaayil.feature.profiles

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.vaayil.core.model.SplitTunnelMode
import dev.vaayil.core.ui.CappedFontScale
import dev.vaayil.core.ui.PillSegmented
import dev.vaayil.core.ui.TabHeader
import dev.vaayil.core.ui.VellumColors
import dev.vaayil.core.ui.VellumQuietAction
import dev.vaayil.core.ui.vellumSeam
import dev.vaayil.core.ui.vellumSwitchColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Split Tunnel tab (Vellum): the Exclusive/Inclusive segment flips the mode and
 * the app list toggles per-app routing with Route All / Bypass All quick actions. The rules
 * are app-wide — they apply to whichever profile is connected, not to one config.
 *
 * The whole tab is ONE `LazyColumn` (device review 2026-09-21, C8): the header items
 * (title, intro, the Allow local network row, mode segment, hints, search, counts, filter)
 * scroll away with the content, and app rows sit directly on the bare canvas with their 60dp-inset
 * hairlines — there is no list card, so nothing clips at a card edge and the list
 * owns the whole screen once scrolled. [imePadding] keeps the search field and its
 * results above the IME under edge-to-edge (the Task-1 gap, orchestrator ruling R7c);
 * [listState] is hoisted so the top bar can derive its scrolled hairline (R7b).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SplitTunnelScreen(
    state: SplitTunnelUiState,
    onMode: (SplitTunnelMode) -> Unit,
    onToggle: (String) -> Unit,
    onQuery: (String) -> Unit,
    onShowSystem: (Boolean) -> Unit,
    onRouteAll: () -> Unit,
    onBypassAll: () -> Unit,
    onAllowLocalNetwork: (Boolean) -> Unit,
    listState: LazyListState = rememberLazyListState(),
    iconLoader: AppIconLoader = rememberDefaultAppIconLoader(),
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
    ) {
        item(key = "header") {
            TabHeader(
                title = "Split Tunnel",
                intro = "Choose which apps travel through the encrypted tunnel. The rules apply to the " +
                    "active connection — every profile uses the same split tunneling.",
            )
        }
        item(key = "local-network") {
            AllowLocalNetworkRow(
                checked = state.allowLocalNetwork,
                onChecked = onAllowLocalNetwork,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
        item(key = "mode") {
            Column {
                PillSegmented(
                    options = listOf("Exclusive", "Inclusive"),
                    selectedIndex = if (state.mode == SplitTunnelMode.EXCLUDE) 0 else 1,
                    onSelect = { index -> onMode(if (index == 0) SplitTunnelMode.EXCLUDE else SplitTunnelMode.INCLUDE) },
                )
                Text(
                    when {
                        state.mode != SplitTunnelMode.EXCLUDE ->
                            "Only the apps you select travel through the tunnel; everything else stays outside."
                        state.allowLocalNetwork ->
                            "Everything travels through the tunnel except the apps you select and your local network."
                        else ->
                            "Everything travels through the encrypted tunnel except the apps you select."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = VellumColors.OnSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    "Saving restarts the tunnel; traffic can leave it for a moment.",
                    style = MaterialTheme.typography.bodySmall,
                    color = VellumColors.OnSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        if (state.includeListEmpty) {
            // A refusal to save, not a failure — amber surface, never the error pair (spec §3.5).
            item(key = "inclusive-warning") {
                InclusiveWarningCard(modifier = Modifier.padding(top = 8.dp))
            }
        }
        item(key = "search") {
            SearchPill(
                value = state.query,
                onValueChange = onQuery,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
        }
        item(key = "counts") {
            // The count keeps its line, and the two actions wrap onto the next one when they no
            // longer fit beside it (large font scales, F29), instead of squeezing the count.
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${state.selectedPackages.size} app${if (state.selectedPackages.size == 1) "" else "s"} selected",
                    style = MaterialTheme.typography.labelLarge,
                    color = VellumColors.OnSurfaceVariant,
                    // The old row's 8dp minimum gap before the actions.
                    modifier = Modifier.padding(end = 8.dp),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VellumQuietAction("Route All", onRouteAll)
                    VellumQuietAction("Bypass All", onBypassAll)
                }
            }
        }
        item(key = "system-filter") {
            SystemAppsRow(
                selected = state.showSystem,
                onToggle = onShowSystem,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (state.apps.isEmpty()) {
            item(key = "empty") {
                Text(
                    if (state.loading) "Loading apps…" else "No apps match.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = VellumColors.OnSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            itemsIndexed(
                state.apps,
                key = { _, app -> app.packageName },
            ) { index, app ->
                AppRow(
                    app = app,
                    checked = app.packageName in state.selectedPackages,
                    onToggle = { onToggle(app.packageName) },
                    showDivider = index > 0,
                    iconLoader = iconLoader,
                )
            }
        }
    }
}

/**
 * The Inclusive-mode blocker (spec §3.5): an amber card — the mode refused to save
 * because no app is selected. A refusal to save, not a failure — amber surface,
 * never the error pair.
 */
@Composable
private fun InclusiveWarningCard(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(VellumColors.Warning, shape)
            .vellumSeam(shape, VellumColors.WarningMark)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            "Inclusive mode needs at least one app. Until you pick one, the previous rules stay in effect.",
            style = MaterialTheme.typography.bodySmall,
            color = VellumColors.OnWarning,
        )
    }
}

/** The search field's label: its placeholder shows it, and its semantics carry it (F27). */
private const val SEARCH_LABEL = "Search installed apps"

/**
 * The search field (spec §3.5, device review 2026-09-21 C6): a FILLED FIELD, not a
 * lift — `container-2` #F1ECE1 fill, closed seam, radius 999, 48dp target, NO
 * shadow (its old `sheet-lift` painted the shadow over the fill and was invisible
 * anyway). Focus is the field-lift pattern minus the shadow: the fill flips to
 * canvas and the existing 2dp `primary` ring draws.
 */
@Composable
private fun SearchPill(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(if (focused) VellumColors.Canvas else VellumColors.Container2, shape)
            .vellumSeam(shape)
            .drawBehind {
                if (!focused) return@drawBehind
                val stroke = 2.dp.toPx()
                val radius = minOf(size.width, size.height) / 2f
                drawRoundRect(
                    color = VellumColors.Primary,
                    topLeft = Offset(-stroke / 2f, -stroke / 2f),
                    size = Size(size.width + stroke, size.height + stroke),
                    cornerRadius = CornerRadius(radius + stroke / 2f),
                    style = Stroke(width = stroke, cap = StrokeCap.Butt),
                )
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            Icons.Rounded.Search,
            contentDescription = null,
            tint = VellumColors.OnSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Box(modifier = Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    "$SEARCH_LABEL…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = VellumColors.OnSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = VellumColors.OnSurface),
                cursorBrush = SolidColor(VellumColors.Primary),
                interactionSource = interactionSource,
                // F27: the placeholder is a separate Text and goes away once the user types, so
                // name the editable node with the label.
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = SEARCH_LABEL },
            )
        }
    }
}

/**
 * The "Show system apps" filter (device review 2026-09-21, C8): a plain switch row —
 * the whole row is the toggle target (`Role.Switch`, no ripple) and the M3 switch
 * on the right wears `vellumSwitchColors`. It replaces the old chip, which read as
 * a static tag when off (no box, no check).
 */
@Composable
private fun SystemAppsRow(selected: Boolean, onToggle: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(
                value = selected,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Switch,
                onValueChange = onToggle,
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Show system apps",
            style = MaterialTheme.typography.bodyMedium,
            color = VellumColors.OnSurface,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = selected, onCheckedChange = null, colors = vellumSwitchColors())
    }
}

/**
 * The app-wide local-network routing switch, above the mode segment: the whole row is the
 * toggle target (one focus stop, like Settings' switch rows) and the label and its
 * supporting lines sit beside the M3 switch.
 */
@Composable
private fun AllowLocalNetworkRow(
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(
                value = checked,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Switch,
                onValueChange = onChecked,
            )
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Allow local network",
                style = MaterialTheme.typography.bodyMedium,
                color = VellumColors.OnSurface,
            )
            Text(
                "Printers, cast devices and other devices on your local network stay reachable.",
                style = MaterialTheme.typography.bodySmall,
                color = VellumColors.OnSurfaceVariant,
            )
            Text(
                "Connections to them skip the tunnel, on public Wi-Fi too.",
                style = MaterialTheme.typography.bodySmall,
                color = VellumColors.OnSurfaceVariant,
            )
            Text(
                "With Block connections without VPN on, Android still blocks them.",
                style = MaterialTheme.typography.bodySmall,
                color = VellumColors.OnSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = vellumSwitchColors(),
            // M3 Switch applies the minimum touch size only when it carries the callback;
            // restate it so handing taps to the row keeps the row's height unchanged.
            modifier = Modifier.minimumInteractiveComponentSize(),
        )
    }
}

/**
 * App row (spec §3.4.3): 68dp (the 48dp switch envelope plus 10dp vertical padding;
 * 64dp is only the heightIn floor), 36dp icon, app name, package in mono 13/400
 * `on-surface-variant`, sage-tracked switch. Rows sit directly on the bare canvas
 * (device review 2026-09-21, C8 — the old list card sliced scrolled rows at its
 * edges), as keyed lazy items of the tab's one `LazyColumn`; [showDivider] draws
 * this row's leading 1dp `Hairline` rule (every row but the first — dividers only
 * sit BETWEEN rows), inset 60dp per spec. The tap toggles; feedback is the switch
 * itself (no ripple).
 */
@Composable
private fun AppRow(
    app: AppInfo,
    checked: Boolean,
    onToggle: () -> Unit,
    showDivider: Boolean,
    iconLoader: AppIconLoader,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .drawBehind {
                if (!showDivider) return@drawBehind
                val stroke = 1.dp.toPx()
                drawLine(
                    color = VellumColors.Hairline,
                    start = Offset(60.dp.toPx(), stroke / 2f),
                    end = Offset(size.width, stroke / 2f),
                    strokeWidth = stroke,
                )
            }
            .toggleable(
                value = checked,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Switch,
                onValueChange = { onToggle() },
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppIcon(packageName = app.packageName, label = app.label, loader = iconLoader)
        Column(modifier = Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                app.packageName,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = VellumColors.OnSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = vellumSwitchColors(),
            // M3 Switch applies minimumInteractiveComponentSize only when it carries
            // the callback; restate it here so handing taps to the row keeps the
            // row's 68dp height unchanged (F9) — the 48dp switch envelope plus the
            // row's vertical padding; 64dp is only the heightIn floor.
            modifier = Modifier.minimumInteractiveComponentSize(),
        )
    }
}

/**
 * The row's icon: the app's 36dp launcher icon rendered bare — no `container-2`
 * tile behind it (device-fix amendment: a rounded square inside the row sheet
 * was a shape inside a shape), drawn straight on the canvas. G3: the load runs
 * off the main thread, so the empty 36dp box holds the row height until the
 * loader answers; a gone package keeps the label's initial exactly as before,
 * and captures inject a loader so a half-loaded list is never recorded.
 * Decorative, so `contentDescription = null`; the adjacent app name
 * identifies the row.
 */
@Composable
internal fun AppIcon(packageName: String, label: String, loader: AppIconLoader) {
    val sizePx = with(LocalDensity.current) { 36.dp.roundToPx() }
    val icon by produceState(LoadedIcon(bitmap = null, done = false), packageName, loader, sizePx) {
        val loaded = withContext(Dispatchers.IO) { loader.load(packageName, sizePx) }
        // The write must land on the platform main thread: under the test clock the producer's
        // dispatcher is not thread-confined, and a recomposition running on the completing IO
        // thread would touch the view hierarchy from it.
        withContext(Dispatchers.Main) { value = LoadedIcon(loaded, done = true) }
    }
    Box(
        modifier = Modifier.size(36.dp),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = icon.bitmap
        when {
            bitmap != null -> Image(
                painter = BitmapPainter(bitmap.asImageBitmap()),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(36.dp).testTag("app-icon-$packageName"),
            )
            icon.done -> {
                // The initial stands in for a gone package's icon, exactly as before (G3
                // keeps this), and icons do not grow with the font scale: the 36dp box
                // cannot hold a larger letter (F29).
                CappedFontScale(1f) {
                    Text(
                        label.firstOrNull()?.uppercase() ?: "?",
                        style = MaterialTheme.typography.titleMedium,
                        color = VellumColors.OnSurface,
                        maxLines = 1,
                    )
                }
            }
            // else: loading — the empty 36dp box holds the row height; no shimmer (DESIGN.md).
        }
    }
}

/** The tab's default loader: the package-manager lookup, cached process-wide (G3). */
@Composable
internal fun rememberDefaultAppIconLoader(): AppIconLoader {
    val context = LocalContext.current
    return remember { PackageManagerAppIconLoader(context) }
}

private data class LoadedIcon(val bitmap: Bitmap?, val done: Boolean)
