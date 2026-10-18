package dev.vaayil.feature.profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.vaayil.core.model.Profile
import dev.vaayil.core.model.dnsSummary
import dev.vaayil.core.model.warnings
import dev.vaayil.core.tunnel.api.ConnectionPhase
import dev.vaayil.core.tunnel.api.HandshakeState
import dev.vaayil.core.tunnel.api.isLive
import dev.vaayil.core.ui.Films
import dev.vaayil.core.ui.LocalReducedMotion
import dev.vaayil.core.ui.MachineValueText
import dev.vaayil.core.ui.PillTone
import dev.vaayil.core.ui.Sheet
import dev.vaayil.core.ui.StatusPill
import dev.vaayil.core.ui.VellumButton
import dev.vaayil.core.ui.VellumColors
import dev.vaayil.core.ui.VellumDropdownMenu
import dev.vaayil.core.ui.VellumDropdownMenuItem
import dev.vaayil.core.ui.VellumEyebrow
import dev.vaayil.core.ui.VellumQuietAction
import dev.vaayil.core.ui.TabHeader
import dev.vaayil.core.ui.machineValueSegments
import dev.vaayil.core.ui.monoTextStyle
import dev.vaayil.core.ui.vellumSeam
import dev.vaayil.core.ui.vellumSwitchColors

/**
 * The Profiles tab (Vellum, spec §3.5): header with the Add control, warning cards for a
 * degraded at-rest posture, the active profile's two-film hero sheet, and the saved rows
 * grouped in one one-film sheet with inset hairline dividers. The connect switch on each
 * row connects that profile (mockup: "tap switch to connect"); the overflow menu manages.
 * The active hero keeps the row's connect switch while its profile is active (J6).
 */
@Composable
fun ProfileListScreen(
    ui: ProfileListUiState,
    onOpenEditor: (String?) -> Unit,
    onOpenImport: () -> Unit,
    onScanQr: () -> Unit,
    onDelete: (String) -> Unit,
    onDuplicate: (String) -> Unit,
    onToggleConnect: (String) -> Unit,
    onShareQr: (String) -> Unit,
    onExport: (String) -> Unit,
    onDeleteUnreadable: () -> Unit,
    listState: LazyListState = rememberLazyListState(),
) {
    // F26: a newly active profile's hero is inserted above the saved rows, and the list keeps
    // its first visible row in place, so the hero would land out of sight. Scroll up to it,
    // instantly under reduced motion. The last hero seen survives tab switches and
    // recreation, so returning to the tab keeps the user's place unless the hero changed.
    val reducedMotion = LocalReducedMotion.current
    val heroId = ui.activeId?.takeIf { id -> ui.items.any { it.id == id } }
    var seenHeroId by rememberSaveable { mutableStateOf(heroId) }
    LaunchedEffect(heroId) {
        // A recreated activity's new view model starts with no profiles: keep the restored
        // hero, and with it the restored scroll position, until they arrive.
        if (ui.items.isEmpty()) return@LaunchedEffect
        if (heroId == null) {
            // No hero: the next one to appear is new to the user, so it scrolls again.
            seenHeroId = null
            return@LaunchedEffect
        }
        if (heroId != seenHeroId) {
            if (reducedMotion) listState.scrollToItem(0) else listState.animateScrollToItem(0)
            // Marked seen only if the scroll landed (T7, sweep 2): the guard is defensive —
            // a finger-preempted animateScrollToItem cancels this effect before the write
            // either way; the landed check covers an animation that settles without
            // reaching the top. Either way the next change or re-entry scrolls again
            // instead of stranding the hero out of sight.
            if (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0) {
                seenHeroId = heroId
            }
        }
    }
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        // Shared TabHeader (device review 2026-09-21, X1): the title sits at the
        // tab-grid offset and the Add control aligns to its first baseline — the old
        // header centred the title on the 52dp button, 10.5dp below the other tabs.
        // [listState] is hoisted so the top bar can derive its scrolled hairline (R7b).
        TabHeader(
            title = "VPN Profiles",
            intro = "${ui.items.size} tunnel${if (ui.items.size == 1) "" else "s"} configured" +
                if (ui.activeId != null && ui.phase == ConnectionPhase.CONNECTED) " • 1 active" else "",
            action = {
                AddProfileButton(
                    onImport = onOpenImport,
                    onScanQr = onScanQr,
                    onManual = { onOpenEditor(null) },
                )
            },
        )
        // Degraded at-rest posture (S-3): warn in place. Spacing lives at the call
        // site — TabHeader's baked 16dp governs the header→content gap, so the first
        // follower contributes 0; a second card sits 12dp under the first; the list
        // below restores 16dp (the pre-TabHeader contentPadding) after any cards.
        if (!ui.health.atRestActive) {
            AtRestWarningCard(writesRefused = ui.health.writesRefused)
        }
        if (ui.health.unreadableIds.isNotEmpty()) {
            UnreadableProfilesCard(
                count = ui.health.unreadableIds.size,
                onRemove = onDeleteUnreadable,
                modifier = if (!ui.health.atRestActive) Modifier.padding(top = 12.dp) else Modifier,
            )
        }
        if (ui.items.isEmpty()) {
            // Crossed state (T7 re-review): a warning card above the empty branch must
            // keep the same 16dp seam the list branch restores — never butt the welcome
            // sheet at 0dp.
            EmptyProfiles(
                onOpenImport = onOpenImport,
                onScanQr = onScanQr,
                onManual = { onOpenEditor(null) },
                modifier = Modifier.padding(top = if (!ui.health.atRestActive || ui.health.unreadableIds.isNotEmpty()) 16.dp else 0.dp),
            )
        } else {
            val cardAboveList = !ui.health.atRestActive || ui.health.unreadableIds.isNotEmpty()
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = if (cardAboveList) 16.dp else 0.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val active = ui.items.firstOrNull { it.id == ui.activeId }
                if (active != null) {
                    item(key = "active-hero") {
                        ActiveHeroCard(
                            profile = active,
                            phase = ui.phase,
                            handshakeState = ui.handshakeState,
                            onToggle = { onToggleConnect(active.id) },
                            onShareQr = { onShareQr(active.id) },
                            onExport = { onExport(active.id) },
                            onEdit = { onOpenEditor(active.id) },
                        )
                    }
                }
                val saved = ui.items.filterNot { it.id == ui.activeId }
                if (saved.isNotEmpty()) {
                    item(key = "saved-group") {
                        SavedProfilesGroup(
                            saved = saved,
                            activeId = ui.activeId,
                            live = ui.phase.isLive,
                            onToggle = onToggleConnect,
                            onEdit = onOpenEditor,
                            onDuplicate = onDuplicate,
                            onDelete = onDelete,
                            onShareQr = onShareQr,
                            onExport = onExport,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AddProfileButton(onImport: () -> Unit, onScanQr: () -> Unit, onManual: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        VellumButton(label = "Add Profile", onClick = { open = true }, leadingIcon = Icons.Rounded.Add)
        VellumDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            VellumDropdownMenuItem(
                text = "Import a .conf file",
                leadingIcon = Icons.Rounded.SaveAlt,
                onClick = { open = false; onImport() },
            )
            VellumDropdownMenuItem(
                text = "Scan a QR code",
                leadingIcon = Icons.Rounded.QrCode2,
                onClick = { open = false; onScanQr() },
            )
            VellumDropdownMenuItem(
                text = "Type in the details",
                leadingIcon = Icons.Rounded.Edit,
                onClick = { open = false; onManual() },
            )
        }
    }
}

/**
 * The active profile's plate (spec §3.5): two films, a field-active header strip, mono facts.
 * The hero is this profile's row while it is active, so its header strip keeps the row's
 * connect switch (J6).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActiveHeroCard(
    profile: Profile,
    phase: ConnectionPhase,
    handshakeState: HandshakeState = HandshakeState.NONE,
    onToggle: () -> Unit,
    onShareQr: () -> Unit,
    onExport: () -> Unit,
    onEdit: () -> Unit,
) {
    // Sage ACTIVE claims a verified tunnel (F21/G4): until this tunnel's own handshake the hero
    // shows what it shows while connecting, which is no pill.
    val connected = phase == ConnectionPhase.CONNECTED && handshakeState == HandshakeState.VERIFIED
    val stripShape = RoundedCornerShape(8.dp)
    val measurer = rememberTextMeasurer()
    Sheet(films = Films.Two, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(VellumColors.FieldActive, stripShape)
                .padding(12.dp),
        ) {
            BoxWithConstraints {
                val nameStyle = MaterialTheme.typography.titleLarge
                val monoStyle = monoTextStyle()
                // What the header text keeps beside the switch: the avatar (40dp), the strip's
                // two 14dp gaps and the switch's 52dp, each rounding to px on its own — the
                // saved row's beside-space arithmetic with no overflow menu.
                val besideSwitch = with(LocalDensity.current) {
                    maxWidth.roundToPx() -
                        (40.dp.roundToPx() + 14.dp.roundToPx() + 14.dp.roundToPx() + 52.dp.roundToPx())
                }
                if (controlsStackUnderText(
                        measurer, nameStyle, monoStyle, profile.name, profile.peer.endpoint,
                        besideSwitch,
                    )
                ) {
                    Column {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AvatarSquircle(id = profile.id, icon = Icons.Rounded.VpnKey)
                            HeroHeaderText(profile, connected, Modifier.weight(1f))
                        }
                        HeroConnectSwitch(
                            profile, phase.isLive, onToggle,
                            modifier = Modifier.align(Alignment.End).padding(top = 2.dp),
                        )
                    }
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AvatarSquircle(id = profile.id, icon = Icons.Rounded.VpnKey)
                        HeroHeaderText(profile, connected, Modifier.weight(1f))
                        HeroConnectSwitch(profile, phase.isLive, onToggle)
                    }
                }
            }
        }
        // T-1: the hero is this profile's row while it is active, so its warning sits here,
        // under the header strip and before the facts.
        profile.warnings().forEach { WarningNote(it) }
        // One label-left, value-right fact ledger (device review 2026-09-21, C10/X1-4:
        // the old two-style mix — label-over-value grid + label/value rows — and the
        // duplicate "Endpoint host" row, which repeated the header's endpoint, are gone).
        HeroFactRow("DNS", profile.dnsSummary(allServers = false) ?: "tunnel")
        HeroFactRow("MTU", profile.interfaceConfig.mtu?.toString() ?: "auto")
        if (profile.peer.publicKey.length > 18) {
            HeroFactRow("Server public key", "${profile.peer.publicKey.take(12)}…${profile.peer.publicKey.takeLast(6)}")
        }
        // Equal gaps (device review 2026-09-21, profiles finding 2): the old
        // weight(1f) cells pooled whitespace after short labels — 19.6dp vs 38.9dp.
        // The review's 24dp figure was measured at the 4a's 393dp; the w360dp
        // fixture floor clips "Edit" there, so the equal gap pins at 16dp.
        // At large font scales the actions wrap onto a second line instead of being cut off (F29).
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            VellumQuietAction("Share QR", onShareQr, icon = Icons.Rounded.QrCode2)
            VellumQuietAction("Export", onExport, icon = Icons.Rounded.SaveAlt)
            VellumQuietAction("Edit", onEdit, icon = Icons.Rounded.Edit)
        }
    }
}

/** The hero header's text column: name (wrapping at word edges, C1) + ACTIVE pill, mono endpoint. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeroHeaderText(profile: Profile, connected: Boolean, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        // U3 (sweep 2): the name is measured against the whole line first, so it
        // wraps at its spaces — two lines before it ellipsizes — and never starves
        // beside the pill (the Row-with-weight shape rendered "Ho…" at font scale
        // 2.0, final review C1). The pill rides beside a name that fits on the
        // line and drops under it when it cannot.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                profile.name,
                style = MaterialTheme.typography.titleLarge,
                color = VellumColors.OnFieldActive,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (connected) StatusPill("ACTIVE", PillTone.ACTIVE)
        }
        MachineValueText(
            value = profile.peer.endpoint,
            color = VellumColors.OnFieldActive,
        )
    }
}

/**
 * The hero's connect switch (J6): the saved row's switch — the same
 * `vellumSwitchColors()` control, labelled with the profile name for screen readers
 * (F9). On while the tunnel is live (connecting or connected), so a tap cancels a
 * connecting tunnel or turns the active one off.
 */
@Composable
private fun HeroConnectSwitch(
    profile: Profile,
    live: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Switch(
        checked = live,
        onCheckedChange = { onToggle() },
        colors = vellumSwitchColors(),
        modifier = modifier.semantics { contentDescription = profile.name },
    )
}

/**
 * U3's large-text rule (sweep 2), shared by the saved row and the hero's header strip
 * (J6): the trailing controls drop under the text when the name's longest word or the
 * endpoint's widest machine-value segment cannot fit beside them, or when the endpoint's
 * whole width would need more than two lines beside them (ruling 1, conductor,
 * 2026-10-03 — keeps barely-fitting rows, a port that squeaks into the column, from
 * wrapping per-segment into an unreadable one; a long IPv6 endpoint may stack at 1.0,
 * accepted — the same text-first rule). [besideSpacePx] is the width the text keeps
 * beside the controls, computed by the caller from its own trailing controls, every term
 * rounded to px on its own.
 */
private fun controlsStackUnderText(
    measurer: TextMeasurer,
    nameStyle: TextStyle,
    monoStyle: TextStyle,
    name: String,
    endpoint: String,
    besideSpacePx: Int,
): Boolean {
    val longestWord = name.split(' ').maxOf { measurer.measure(it, nameStyle).size.width }
    val widestSegment = machineValueSegments(endpoint)
        .maxOf { measurer.measure(it, monoStyle).size.width }
    val endpointFullWidth = measurer.measure(endpoint, monoStyle).size.width
    return maxOf(longestWord, widestSegment) > besideSpacePx || endpointFullWidth > 2 * besideSpacePx
}

/**
 * One fact row (spec §3.5, unified with the dashboard's facts ledger): sentence-case
 * `on-surface-variant` label, mono value — machine data wears mono, and the label is
 * information, not colour code (X3). Side by side while they fit; when they do not, the
 * value moves under the label instead of ellipsizing (U3, sweep 2), wrapping at the machine
 * break points (U2).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeroFactRow(label: String, value: String) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = VellumColors.OnSurfaceVariant)
        MachineValueText(
            value = value,
            color = VellumColors.OnSurface,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

/**
 * Saved rows grouped in one one-film sheet (spec §3.5), hairline dividers inset 60dp —
 * past the avatar column, so rows read as one ledger.
 */
@Composable
private fun SavedProfilesGroup(
    saved: List<Profile>,
    activeId: String?,
    live: Boolean,
    onToggle: (String) -> Unit,
    onEdit: (String) -> Unit,
    onDuplicate: (String) -> Unit,
    onDelete: (String) -> Unit,
    onShareQr: (String) -> Unit,
    onExport: (String) -> Unit,
) {
    Sheet(films = Films.One, modifier = Modifier.fillMaxWidth(), contentPadding = 4.dp, verticalArrangement = Arrangement.spacedBy(0.dp)) {
        saved.forEachIndexed { index, profile ->
            if (index > 0) {
                Box(
                    modifier = Modifier
                        .padding(start = 60.dp)
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(VellumColors.Hairline),
                )
            }
            SavedProfileRow(
                profile = profile,
                connected = profile.id == activeId && live,
                onToggle = { onToggle(profile.id) },
                onEdit = { onEdit(profile.id) },
                onDuplicate = { onDuplicate(profile.id) },
                onDelete = { onDelete(profile.id) },
                onShareQr = { onShareQr(profile.id) },
                onExport = { onExport(profile.id) },
            )
            // T-1: the row's own warning, beneath it and aligned with the row's content box
            // (the sheet's 4dp padding plus the row's 16dp), not the 68dp name column — the
            // name-column inset starved the copy to three lines at 360dp (T10, sweep 2).
            profile.warnings().forEach {
                WarningNote(it, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp))
            }
        }
    }
}

/**
 * 40dp pastel squircle avatar (spec §3.4.3): glyph in the paired on-colour over the
 * pastel, fill only — no seam (sub-48dp decorative tiles drop the stroke, device-fix
 * amendment) — non-interactive — `contentDescription = null`; the
 * adjacent profile name identifies the row.
 */
@Composable
private fun AvatarSquircle(id: String, icon: ImageVector, modifier: Modifier = Modifier) {
    val (container, content) = remember(id) { avatarFor(id) }
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .size(40.dp)
            .background(container, shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(22.dp))
    }
}

/**
 * Stable pastel per profile id so avatars don't reshuffle on recomposition.
 *
 * R3 (spec §3.8): the avatar's seam is NOT load-bearing (1.32–1.39:1 vs the pastels —
 * paper tones can't carry a boundary). Identification rides the glyph (8.97–9.83:1)
 * and the adjacent profile name. The avatar must stay non-interactive with
 * `contentDescription = null`; if it ever becomes tappable it MUST first gain a
 * 1.5dp `#6F6658` stroke (4.22–4.46:1 vs the pastels) as its boundary.
 */
private fun avatarFor(id: String): Pair<Color, Color> = when (kotlin.math.abs(id.hashCode()) % 3) {
    0 -> VellumColors.Pastel1 to VellumColors.OnPastel1
    1 -> VellumColors.Pastel2 to VellumColors.OnPastel2
    else -> VellumColors.Pastel3 to VellumColors.OnPastel3
}

/**
 * Saved row: avatar, name + endpoint (mono), connect switch (tap to connect), overflow menu.
 * Connecting a second profile replaces the active one (TunnelController.toggle semantics).
 *
 * Device check 2026-10-03 (Pixel 4a, 332dp, font scale 2.0): beside the controls the
 * weighted text column kept ~76dp, so names and endpoints broke mid-word and the port
 * split from its colon. When the name's longest word or the endpoint's widest unbreakable
 * segment cannot fit beside the trailing controls — or the endpoint's whole width would
 * need more than two lines beside them (ruling 1, 2026-10-03) — the text takes the row's
 * full width and the switch + overflow menu drop to their own end-aligned line under it —
 * TabHeader's action rule (U3). While everything fits, the row renders exactly its old
 * single-line shape. The measurement is the TelemetryRow precedent: measure first, no
 * layout hacks.
 */
@Composable
private fun SavedProfileRow(
    profile: Profile,
    connected: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onShareQr: () -> Unit,
    onExport: () -> Unit,
) {
    val measurer = rememberTextMeasurer()
    var menuOpen by remember { mutableStateOf(false) }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        val density = LocalDensity.current
        val nameStyle = MaterialTheme.typography.titleMedium
        val monoStyle = monoTextStyle()
        // What the text keeps beside the controls: the avatar (40dp) and its 12dp gap, the
        // switch's 52dp and the menu's 48dp, each with their own 12dp gap. The measured
        // widths are px, and every term rounds alone — the rounding the layout itself
        // applies — so at a fractional density a piece is never judged to fit beside the
        // controls and then wrapped anyway.
        val textBesideControls = with(density) {
            maxWidth.roundToPx() -
                (40.dp.roundToPx() + 3 * 12.dp.roundToPx() + 52.dp.roundToPx() + 48.dp.roundToPx())
        }
        val controlsBelowText = controlsStackUnderText(
            measurer, nameStyle, monoStyle, profile.name, profile.peer.endpoint, textBesideControls,
        )
        if (controlsBelowText) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AvatarSquircle(id = profile.id, icon = Icons.Rounded.VpnKey)
                    SavedRowText(profile, Modifier.weight(1f))
                }
                SavedRowControls(
                    profile, connected, onToggle, onEdit, onDuplicate, onDelete,
                    onShareQr, onExport,
                    onOpenMenu = { menuOpen = true },
                    modifier = Modifier.align(Alignment.End).padding(top = 2.dp),
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AvatarSquircle(id = profile.id, icon = Icons.Rounded.VpnKey)
                SavedRowText(profile, Modifier.weight(1f))
                SavedRowControls(
                    profile, connected, onToggle, onEdit, onDuplicate, onDelete,
                    onShareQr, onExport,
                    onOpenMenu = { menuOpen = true },
                )
            }
        }
        // The menu's popup anchors to its parent layout — this full-width box, whose
        // modifier chain is the old row's — so it stays a direct child here, after the
        // branches: composing it inside the 112dp controls cluster anchored the popup to
        // that cluster instead (and its 0-size slot added a spacedBy gap while open).
        VellumDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            VellumDropdownMenuItem(text = "Edit", onClick = { menuOpen = false; onEdit() })
            VellumDropdownMenuItem(
                text = "Share QR",
                leadingIcon = Icons.Rounded.QrCode2,
                onClick = { menuOpen = false; onShareQr() },
            )
            VellumDropdownMenuItem(
                text = "Export .conf",
                leadingIcon = Icons.Rounded.SaveAlt,
                onClick = { menuOpen = false; onExport() },
            )
            VellumDropdownMenuItem(
                text = "Duplicate",
                leadingIcon = Icons.Rounded.ContentCopy,
                onClick = { menuOpen = false; onDuplicate() },
            )
            DropdownMenuItem(
                text = { Text("Delete", color = VellumColors.M3Error) },
                leadingIcon = {
                    Icon(
                        Icons.Rounded.Delete,
                        contentDescription = null,
                        tint = VellumColors.M3Error,
                        modifier = Modifier.size(18.dp),
                    )
                },
                onClick = { menuOpen = false; onDelete() },
            )
        }
    }
}

/** The saved row's text column: name (two lines), mono endpoint, DNS•MTU line. */
@Composable
private fun SavedRowText(profile: Profile, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(profile.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        MachineValueText(
            value = profile.peer.endpoint,
            color = VellumColors.OnSurfaceVariant,
        )
        Text(
            "DNS ${profile.dnsSummary(allServers = false) ?: "tunnel"} • " +
                "MTU ${profile.interfaceConfig.mtu ?: "auto"}",
            style = MaterialTheme.typography.bodySmall,
            color = VellumColors.OnSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The row's trailing controls: connect switch (tap to connect) and the overflow-menu button. */
@Composable
private fun SavedRowControls(
    profile: Profile,
    connected: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onShareQr: () -> Unit,
    onExport: () -> Unit,
    onOpenMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The switch and its menu keep the old row's 12dp gap: both branches must render its
    // trailing controls exactly where the single-row layout put them (1.0-unchanged).
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Switch(
            checked = connected,
            onCheckedChange = { onToggle() },
            colors = vellumSwitchColors(),
            // The row keeps its other action (the overflow menu), so the switch —
            // not the whole row — carries the profile name for screen readers (F9).
            modifier = Modifier.semantics { contentDescription = profile.name },
        )
        IconButton(onClick = onOpenMenu) {
            Icon(Icons.Rounded.MoreVert, contentDescription = "More options for ${profile.name}")
        }
    }
}

/** Empty state (spec §3.5): one one-film sheet, headline, body copy, 52dp primary. */
@Composable
private fun EmptyProfiles(
    onOpenImport: () -> Unit,
    onScanQr: () -> Unit,
    onManual: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Default no top padding: TabHeader's baked 16dp is the one header→content gap;
    // the caller adds 16dp only when a warning card sits above (crossed state).
    Sheet(films = Films.One, modifier = modifier.fillMaxWidth()) {
        Text("Welcome to Vaayil", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Bring your own WireGuard tunnel: import a .conf file, scan a QR code from your " +
                "provider, or type the details in.",
            style = MaterialTheme.typography.bodyMedium,
            color = VellumColors.OnSurfaceVariant,
        )
        VellumButton(
            label = "Import a .conf file",
            onClick = onOpenImport,
            leadingIcon = Icons.Rounded.SaveAlt,
            modifier = Modifier.fillMaxWidth(),
        )
        VellumQuietAction("Scan a QR code", onScanQr, icon = Icons.Rounded.QrCode2, modifier = Modifier.fillMaxWidth())
        VellumQuietAction("Type in the details", onManual, icon = Icons.Rounded.Edit, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * Degraded at-rest posture, spec §3.4.3: error surface with a 1dp error-mark
 * seam, uppercase label, body in on-error. The body names the posture (R6): with no
 * key ever held, keys are stored unencrypted (S-3); once a held key is lost, writes
 * are refused until it comes back (F13), so profiles are read-only.
 */
@Composable
private fun AtRestWarningCard(writesRefused: Boolean, modifier: Modifier = Modifier) {
    WarningCard(
        body = if (writesRefused) {
            "The Android Keystore is unavailable right now, so profiles are read-only. Vaayil keeps trying."
        } else {
            "Keys are stored unencrypted on this device because the Android Keystore is unavailable."
        },
        modifier = modifier,
    )
}

/** Rows whose secrets no longer decrypt: removable in place (S-3). */
@Composable
private fun UnreadableProfilesCard(count: Int, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    WarningCard(
        body = "$count profile${if (count == 1) "" else "s"} can't be read because the device's encryption key changed.",
        action = { VellumQuietAction("Remove", onRemove) },
        modifier = modifier,
    )
}

/** The card carries NO top spacing of its own — the caller owns the rhythm (TabHeader's baked 16dp governs). */
@Composable
private fun WarningCard(
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(VellumColors.Error, shape)
            .vellumSeam(shape, VellumColors.ErrorMark)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        VellumEyebrow("Encryption unavailable", color = VellumColors.OnError, wrap = true)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = VellumColors.OnError)
        action?.invoke()
    }
}
