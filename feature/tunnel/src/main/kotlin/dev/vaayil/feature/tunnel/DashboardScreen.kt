package dev.vaayil.feature.tunnel

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.North
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.South
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.vaayil.core.model.Profile
import dev.vaayil.core.model.dnsSummary
import dev.vaayil.core.tunnel.api.ConnectionPhase
import dev.vaayil.core.tunnel.api.HandshakeState
import dev.vaayil.core.tunnel.api.TunnelError
import dev.vaayil.core.tunnel.api.uiLabel
import dev.vaayil.core.ui.CappedFontScale
import dev.vaayil.core.ui.Films
import dev.vaayil.core.ui.LocalFilms
import dev.vaayil.core.ui.LocalReducedMotion
import dev.vaayil.core.ui.MachineValueText
import dev.vaayil.core.ui.MicroBadge
import dev.vaayil.core.ui.PillTone
import dev.vaayil.core.ui.Sheet
import dev.vaayil.core.ui.StatusPill
import dev.vaayil.core.ui.VellumButton
import dev.vaayil.core.ui.VellumColors
import dev.vaayil.core.ui.VellumDropdownMenu
import dev.vaayil.core.ui.VellumDropdownMenuItem
import dev.vaayil.core.ui.VellumEyebrow
import dev.vaayil.core.ui.VellumTelemetryNumStyle

/**
 * The Vellum dashboard (spec §3.5): status pill, the 112dp hero window disc on a
 * container-3 seat running to the 20dp gutter, the session-totals ledger, and the
 * facts ledger card. Only real data renders — session totals instead of invented
 * rates, "—" where a fact is unset (empty slots stay quiet).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DashboardScreen(
    ui: HomeUiState,
    selected: Profile?,
    onSelectProfile: (String) -> Unit,
    onToggle: () -> Unit,
    onOpenProfiles: () -> Unit,
    scrollState: ScrollState = rememberScrollState(),
) {
    val phase = ui.status.phase
    val face = discFace(ui.status, selected?.id)
    val tunnelUp = phase == ConnectionPhase.CONNECTED // the global pill: some tunnel is up
    // G4 (spec §4.4.4): a live tunnel claims nothing until its own handshake. The pill says what
    // is happening, and the selected profile's disc keeps the connecting presentation.
    val hs = ui.handshakeState
    val waiting = tunnelUp && hs == HandshakeState.WAITING
    val verified = tunnelUp && hs == HandshakeState.VERIFIED
    // PENDING's presentation also covers a CONNECTED status whose state is not yet known: fail
    // closed — never sage before this tunnel's own handshake (G4).
    val pending = tunnelUp && !waiting && !verified
    val connected = face == DiscFace.ACTIVE && verified // the disc: the SELECTED profile is up and verified
    val switching = face == DiscFace.SWITCH
    val connecting = face == DiscFace.BUSY || (face == DiscFace.ACTIVE && !verified)
    // Tapping the busy disc on the profile that is coming up cancels it (toggle treats a
    // connecting profile as live). Only CONNECTING, or a tunnel still pending its handshake,
    // turns the tap into a cancel: a disc that is DISCONNECTING is disabled, so it must not offer one.
    val cancelling = connecting && (phase == ConnectionPhase.CONNECTING || pending || waiting) &&
        ui.status.profileId == selected?.id
    val failed = face == DiscFace.FAILED
    val noProfiles = ui.profiles.isEmpty()
    val liveName = ui.profiles.firstOrNull { it.id == ui.status.profileId }?.name

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // When the pill and the badge do not fit side by side (large font scales, narrow
        // screens), the badge drops below the pill instead of being squeezed off the row (F29).
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            StatusPill(
                label = when {
                    waiting -> "Waiting for handshake"
                    pending -> ConnectionPhase.CONNECTING.uiLabel
                    tunnelUp -> "Protected & Encrypted"
                    // F17b's rule lives in discFace; the pill asks its verdict (T2).
                    else -> pillPhase(face, phase).uiLabel
                },
                tone = when {
                    waiting -> PillTone.WARNING
                    pending -> PillTone.WARNING
                    tunnelUp -> PillTone.ACTIVE
                    failed -> PillTone.ERROR
                    connecting -> PillTone.WARNING
                    else -> PillTone.IDLE
                },
            )
            MicroBadge("WIREGUARD")
        }

        if (noProfiles) {
            Sheet(Films.One, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Text("Welcome to Vaayil", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Add your first tunnel to get started — import a .conf file, scan a QR code, " +
                        "or enter the details manually.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                VellumButton(
                    "Go to Profiles",
                    onOpenProfiles,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            return@Column
        }

        // Hero seat: container-3 paper running to the 20dp gutter (spec §3.4.3 "full-bleed
        // to the 20dp gutter"; DEVICE-FIX 2026-09-21 — the old edge-to-edge seat cut 14dp
        // corner notches into the physical screen edge, review finding 12).
        // Pressing the disc resolves the seat tone one ladder step deeper (§3.4.3
        // "Pressed: seat +1 film (c3→c4)"): a plain state-driven color swap to
        // ladder[4] — the clickable Sheet's pressedFill precedent — drawn behind the
        // seat content; no LocalFilms change, so R1's nesting cap is not implicated.
        val heroInteraction = remember { MutableInteractionSource() }
        val seatPressed by heroInteraction.collectIsPressedAsState()
        Sheet(
            films = Films.Three,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            shape = RoundedCornerShape(14.dp),
            contentPadding = 0.dp,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (seatPressed) VellumColors.ladder[4] else Color.Transparent)
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    HeroDisc(
                        interaction = heroInteraction,
                        connected = connected,
                        failed = failed,
                        busy = connecting,
                        cancelling = cancelling,
                        enabled = phase != ConnectionPhase.DISCONNECTING,
                        switchTo = if (switching) (selected?.name ?: "this profile") else null,
                        sageWindow = discWearsSage(face, verified),
                        onToggle = onToggle,
                    )
                    ProfilePill(
                        profile = selected,
                        profiles = ui.profiles,
                        onSelect = onSelectProfile,
                    )
                    Text(
                        when {
                            connected -> "Tap the disc to disconnect"
                            cancelling -> "Tap the disc to cancel connecting"
                            switching -> "Tap the disc to switch to ${selected?.name ?: "this profile"}. " +
                                "${liveName ?: "The current tunnel"} stays on until then."
                            else -> "Tap the disc to connect"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    if (failed) {
                        Text(
                            errorMessage(ui.status.error),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp),
                        )
                    }
                    DashboardNotice(ui)
                }
            }
        }

        if (connected) {
            Sheet(Films.One, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                VellumEyebrow("SESSION TOTALS")
                TelemetryRow(Icons.Rounded.South, "DOWN", formatBytes(ui.stats.rxBytes))
                LedgerDivider()
                TelemetryRow(Icons.Rounded.North, "UP", formatBytes(ui.stats.txBytes))
                LedgerDivider()
                TelemetryRow(Icons.Rounded.Sync, "HANDSHAKE", formatHandshakeAge(ui.stats.handshakeAt))
            }
        }

        // Facts ledger: config truths, shown whether or not the tunnel is up. One card,
        // full-width values (device review 2026-09-21, C5 — the half-width tile grid
        // truncated the endpoint and DNS list; the duplicate "Last handshake" row is
        // gone, HANDSHAKE already lives in SESSION TOTALS).
        Sheet(Films.One, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            FactRow("ENDPOINT") { selected?.peer?.endpoint }
            FactRow("ADDRESS") { selected?.interfaceConfig?.addresses?.firstOrNull() }
            FactRow("DNS") { selected?.dnsSummary(allServers = true) }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * The 112dp hero window disc (spec §3.4.3, was 140dp). Disconnected it is the lightest
 * element on screen — a canvas window to the bare board, 1dp seam ring, glyph in
 * on-surface-variant. Connected (including SWITCH, which wears connected styling with
 * busy semantics) the window fills primary, the glyph turns canvas, and a static 1dp
 * sage-mark ring sits at 132dp. The caller decides that through [sageWindow]
 * ([discWearsSage]): sage appears only at VERIFIED, so PENDING and WAITING — a live
 * tunnel still waiting for its first handshake — keep the canvas window (G4). FAILED
 * wears a 2dp error-mark ring at the same radius.
 * The fill transition is the `fill` motion token — 260ms, an instant swap under
 * reduced motion. Press feedback is tonal only, no ripple: the caller resolves the
 * SEAT's background one ladder step deeper while this disc's [interaction] is pressed
 * (spec §3.4.3 "Pressed: seat +1 film (c3→c4)") — a plain state-driven color swap via
 * the ladder index, the clickable Sheet's `pressedFill` precedent; no `LocalFilms`
 * depth change, so the nesting cap (spec §3.3.1 R1) is not implicated.
 */
@Composable
private fun HeroDisc(
    interaction: MutableInteractionSource,
    connected: Boolean,
    failed: Boolean,
    busy: Boolean,
    cancelling: Boolean,
    enabled: Boolean,
    switchTo: String?,
    sageWindow: Boolean,
    onToggle: () -> Unit,
) {
    val reducedMotion = LocalReducedMotion.current
    val fill by animateColorAsState(
        targetValue = if (sageWindow) VellumColors.Primary else VellumColors.Canvas,
        animationSpec = if (reducedMotion) snap() else tween(durationMillis = 260),
        label = "hero-fill",
    )
    val glyph = when {
        !enabled -> VellumColors.Disabled // DISCONNECTING: glyph disabled, no ring
        sageWindow -> VellumColors.Canvas
        else -> VellumColors.OnSurfaceVariant
    }
    Box(
        modifier = Modifier
            .size(132.dp)
            .drawBehind {
                // Static state ring at 132dp — never animated (spec §3.4.3 hero row).
                when {
                    sageWindow -> drawCircle(
                        VellumColors.SageMark,
                        radius = size.minDimension / 2f - 0.5.dp.toPx(),
                        style = Stroke(width = 1.dp.toPx()),
                    )
                    failed -> drawCircle(
                        VellumColors.ErrorMark,
                        radius = size.minDimension / 2f - 1.dp.toPx(),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(112.dp)
                .clip(CircleShape)
                .background(fill, CircleShape)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = enabled,
                    onClick = onToggle,
                )
                .drawBehind {
                    if (!sageWindow && enabled) {
                        drawCircle(
                            VellumColors.Seam,
                            radius = size.minDimension / 2f - 0.5.dp.toPx(),
                            style = Stroke(width = 1.dp.toPx()),
                        )
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Rounded.PowerSettingsNew,
                    contentDescription = when {
                        connected -> "Disconnect"
                        cancelling -> "Cancel connecting"
                        switchTo != null -> "Switch to $switchTo"
                        else -> "Connect"
                    },
                    modifier = Modifier.size(36.dp),
                    tint = glyph,
                )
                Spacer(Modifier.height(6.dp))
                // One line, never broken mid-word (F29: "CONNE / CT" at font scale 2.0). The
                // window cannot grow, and above 1.15 "CONNECT" would cross its round edge.
                CappedFontScale(1.15f) {
                    Text(
                        when {
                            busy -> "…"
                            connected -> "ACTIVE"
                            switchTo != null -> "SWITCH"
                            else -> "CONNECT"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = glyph,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

/**
 * The selected-profile control (spec §3.4.3, device review 2026-09-21 C7). With two
 * or more profiles it is the pill: canvas fill, radius 999, 1dp stroke-3 boundary,
 * 48dp minimum, titleMedium, ArrowDropDown — the film stack RESETS around it
 * (LiftedSheet's mechanism) because it is a lighter-than-surround window like the
 * disc; the boundary draws because the control is enabled. With exactly one
 * profile (or none) it is HONEST: a plain `titleMedium` name on the hero seat — no
 * container, no boundary, not clickable, because there is no menu to open. The
 * dropdown is the lifted-surface menu; the selected row carries the primary check.
 * The name stays on one line and ends in an ellipsis when it does not fit (F29).
 */
@Composable
private fun ProfilePill(
    profile: Profile?,
    profiles: List<Profile>,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    if (profiles.size <= 1) {
        Text(
            profile?.name ?: "No profile selected",
            style = MaterialTheme.typography.titleMedium,
            color = VellumColors.OnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
        )
    } else {
        CompositionLocalProvider(LocalFilms provides Films.Board) {
            Sheet(
                onClick = { open = true },
                films = Films.Board,
                shape = RoundedCornerShape(50),
                seam = false,
                contentPadding = 0.dp,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        profile?.name ?: "No profile selected",
                        style = MaterialTheme.typography.titleMedium,
                        color = VellumColors.OnSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // Measured after the arrow, so a long name shortens instead of pushing
                        // the arrow out of the pill (F29).
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Icon(
                        Icons.Rounded.ArrowDropDown,
                        contentDescription = "Choose profile",
                        tint = VellumColors.OnSurfaceVariant,
                    )
                }
            }
        }
    }
    VellumDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        profiles.forEach { candidate ->
            VellumDropdownMenuItem(
                text = candidate.name,
                selected = candidate.id == profile?.id,
                onClick = { open = false; onSelect(candidate.id) },
            )
        }
    }
}

/**
 * 1dp hairline rule between a sheet's rows, inset 28dp (spec §3.5 SESSION TOTALS,
 * device review 2026-09-21 C5) — the settings tab's RowDivider pattern.
 */
@Composable
private fun LedgerDivider() {
    Box(
        modifier = Modifier
            .padding(start = 28.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(VellumColors.Hairline),
    )
}

/**
 * One ledger row (spec §3.4.3 ThroughputRow): 18dp slate icon, eyebrow label, and the
 * 21/600 tnum value at the row's right edge. Empty values render "—" in disabled. The old
 * 1dp baseline rule under the value column is gone (device review 2026-09-21, C4). The
 * value's lines right-align like the facts ledger's values (T5, sweep 2 — the old FlowRow
 * left a wrapped value ragged-left). I2 (final review): the value shares the line only
 * while it whole fits beside the label — measured up front, not left to the layout — and
 * otherwise drops to its own full-width, right-aligned line, so a narrow column (density
 * 520 at font scale 2.0 ≈ 332dp) never breaks "14s ago" mid-token.
 */
@Composable
private fun TelemetryRow(icon: ImageVector, label: String, value: String?) {
    val empty = value.isNullOrBlank()
    val text = if (empty) "—" else value.orEmpty()
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        // The eyebrow renders [label].uppercase() in the label slot; the row's own icon and
        // gap sit left of it (18dp + 10dp).
        val labelWidth = with(density) {
            18.dp.toPx() + 10.dp.toPx() +
                measurer.measure(label.uppercase(), MaterialTheme.typography.labelSmall).size.width
        }
        val valueWidth = measurer.measure(text, VellumTelemetryNumStyle).size.width
        val fitsBeside = labelWidth + with(density) { 16.dp.toPx() } + valueWidth <=
            with(density) { maxWidth.toPx() }
        if (fitsBeside) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, contentDescription = null, tint = VellumColors.Slate, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    VellumEyebrow(label)
                }
                Spacer(Modifier.width(16.dp))
                Text(
                    text,
                    style = VellumTelemetryNumStyle,
                    color = if (empty) VellumColors.Disabled else Color.Unspecified,
                    textAlign = TextAlign.Right,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, contentDescription = null, tint = VellumColors.Slate, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    VellumEyebrow(label)
                }
                Text(
                    text,
                    style = VellumTelemetryNumStyle,
                    color = if (empty) VellumColors.Disabled else Color.Unspecified,
                    textAlign = TextAlign.Right,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                )
            }
        }
    }
}

/**
 * One facts-ledger row (spec §3.5, device review 2026-09-21 C5): eyebrow label left,
 * 14sp mono value right — so the endpoint keeps its port and the DNS list keeps its
 * second resolver (the half-width tiles truncated both). Label and value share the line
 * while they fit; when they do not, the value moves under the label and gets the whole
 * row (I1a, final review), its lines start-aligned so the value reads down one column
 * (device check 2026-10-03) — the same rule as the Profiles hero facts — so the value
 * wraps at the machine break points (`MachineValueText`, U2) onto as many lines as it
 * needs instead of being cut off at large font scales and on narrow screens (F29). An
 * unset fact renders "—" in disabled.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FactRow(label: String, value: () -> String?) {
    val v = value()
    val empty = v.isNullOrBlank()
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        VellumEyebrow(label)
        MachineValueText(
            value = if (empty) "—" else v.orEmpty(),
            color = if (empty) VellumColors.Disabled else Color.Unspecified,
        )
    }
}

private fun errorMessage(error: TunnelError?): String = when (error) {
    is TunnelError.ParseError -> error.message
    TunnelError.PermissionDenied -> "VPN permission is off"
    is TunnelError.DnsFailure -> "Could not resolve ${error.endpoint}"
    is TunnelError.DnsResolverFailure -> "The DNS resolver could not start: ${error.detail}"
    TunnelError.HandshakeTimeout -> "Handshake timed out"
    is TunnelError.BackendError -> error.message
    null -> "Connection failed"
}
