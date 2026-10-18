package dev.vaayil.feature.settings

import android.content.ClipData
import android.os.PersistableBundle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import dev.vaayil.core.ui.Films
import dev.vaayil.core.ui.ScrimDialog
import dev.vaayil.core.ui.SecureWindow
import dev.vaayil.core.ui.Sheet
import dev.vaayil.core.ui.VellumButton
import dev.vaayil.core.ui.VellumButtonRole
import dev.vaayil.core.ui.VellumColors
import dev.vaayil.core.ui.VellumEyebrow
import dev.vaayil.core.ui.VellumField
import dev.vaayil.core.ui.VellumQuietAction
import dev.vaayil.core.ui.monoTextStyle
import dev.vaayil.core.ui.vellumSeam
import dev.vaayil.core.ui.vellumSwitchColors
import kotlinx.coroutines.launch

/** ClipDescription.EXTRA_IS_SENSITIVE (API 33): a plain extra that older releases ignore. */
private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

// Trusted SSIDs match exactly, so autocorrect or auto-capitalisation would make them never
// match (F16). The keyboard type stays the default text one: SSIDs can be non-ASCII.
internal val SsidKeyboard = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
)

@Composable
fun SettingsScreen(
    ui: SettingsFlags,
    automation: AutomationUiState,
    appVersion: String,
    onOpenKillSwitchGuide: () -> Unit,
    onOpenVpnSettings: () -> Unit,
    onOpenPrivacyPolicy: () -> Unit,
    onOpenLicenses: () -> Unit,
    onOpenSourceCode: () -> Unit,
    onOpenAppLicense: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onWifiGuard: (Boolean) -> Unit,
    onAutoConnectOnMobileData: (Boolean) -> Unit,
    onDisconnectOnTrusted: (Boolean) -> Unit,
    onConnectOnBoot: (Boolean) -> Unit,
    onAddTrustedSsid: (String) -> Unit,
    onRemoveTrustedSsid: (String) -> Unit,
    onExternalControl: (Boolean) -> Unit,
    onRegenerateExternalToken: () -> Unit,
) {
    // Capped centered column: the phone list must not stretch into 840dp scan lines (P2).
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .testTag("settings-column")
                .widthIn(max = 600.dp)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Settings",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            SectionHeader("Protection")
            Sheet(
                films = Films.One,
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                SettingsRow(
                    icon = { Icon(Icons.Rounded.Lock, contentDescription = null, tint = VellumColors.OnSurfaceVariant) },
                    title = "Always-on VPN (kill switch)",
                    supporting = "Block connections if the tunnel drops",
                    onClick = onOpenKillSwitchGuide,
                )
                RowDivider(inset = 36.dp)
                SettingsRow(
                    icon = { Icon(Icons.Rounded.Settings, contentDescription = null, tint = VellumColors.OnSurfaceVariant) },
                    title = "System VPN settings",
                    supporting = "Always-on and block-connections live in Android's settings",
                    onClick = onOpenVpnSettings,
                )
            }
            SectionHeader("Automation")
            Sheet(
                films = Films.One,
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                SwitchRow(
                    title = "Auto-connect on untrusted Wi-Fi",
                    supporting = "Connects your active profile whenever this device joins a network outside your trusted list",
                    checked = automation.rules.wifiGuardEnabled,
                    onChecked = onWifiGuard,
                )
                if (automation.rules.wifiGuardEnabled) {
                    if (automation.permissionState == PermissionState.GRANTED) {
                        Text(
                            "Active while Vaayil runs in the background.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    if (automation.permissionState == PermissionState.DENIED) {
                        WarningNote(
                            body = "Location permission is off, so Android hides the current Wi-Fi name. " +
                                "Auto-connect stays paused until you grant it — nothing else changes.",
                            action = { VellumQuietAction("Open location settings", onOpenLocationSettings) },
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    if (automation.permissionState == PermissionState.APPROXIMATE_ONLY) {
                        WarningNote(
                            body = "Vaayil needs precise location to see the Wi-Fi name. Turn on Use precise location for Vaayil.",
                            action = { VellumQuietAction("Open location settings", onOpenLocationSettings) },
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    if (automation.permissionState == PermissionState.FOREGROUND_ONLY) {
                        WarningNote(
                            body = "Location is allowed only while Vaayil is open, so Android hides the " +
                                "Wi-Fi name in the background. Choose 'Allow all the time' to keep " +
                                "the guard running.",
                            action = { VellumQuietAction("Open location settings", onOpenLocationSettings) },
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    if (automation.permissionState == PermissionState.LOCATION_OFF) {
                        WarningNote(
                            body = "Location is off, so the Wi-Fi rules are paused. Turn it on so Vaayil can see which Wi-Fi you're on.",
                            action = { VellumQuietAction("Open Location settings", onOpenLocationSettings) },
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    TrustedSsidEditor(
                        ssids = automation.rules.trustedSsids,
                        onAdd = onAddTrustedSsid,
                        onRemove = onRemoveTrustedSsid,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                RowDivider(inset = 0.dp)
                SwitchRow(
                    title = "Auto-connect on mobile data",
                    supporting = "Connects your active profile whenever the device is on mobile data",
                    checked = automation.rules.autoConnectOnMobileData,
                    onChecked = onAutoConnectOnMobileData,
                )
                RowDivider(inset = 0.dp)
                SwitchRow(
                    title = "Disconnect on trusted networks",
                    supporting = "Turns the tunnel off when you join a trusted network",
                    checked = automation.rules.disconnectOnTrusted,
                    onChecked = onDisconnectOnTrusted,
                )
                RowDivider(inset = 0.dp)
                SwitchRow(
                    title = "Connect on boot",
                    supporting = "Restores your active profile after the device restarts — independent of the system Always-on setting",
                    checked = automation.rules.connectOnBoot,
                    onChecked = onConnectOnBoot,
                )
                RowDivider(inset = 0.dp)
                SwitchRow(
                    title = "Allow external control",
                    supporting = "Tasker, adb and other apps can connect or disconnect with the token below",
                    checked = automation.rules.externalControlEnabled,
                    onChecked = onExternalControl,
                )
                if (automation.rules.externalControlEnabled) {
                    ExternalTokenBlock(
                        token = automation.rules.externalControlToken.orEmpty(),
                        onRegenerate = onRegenerateExternalToken,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
            SectionHeader("About")
            Sheet(
                films = Films.One,
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                SettingsRow(
                    icon = { Icon(Icons.Rounded.Article, contentDescription = null, tint = VellumColors.OnSurfaceVariant) },
                    title = "Privacy policy",
                    supporting = "What Vaayil does with data on your device",
                    onClick = onOpenPrivacyPolicy,
                )
                RowDivider(inset = 36.dp)
                SettingsRow(
                    icon = { Icon(Icons.Rounded.MenuBook, contentDescription = null, tint = VellumColors.OnSurfaceVariant) },
                    title = "Open-source licences",
                    supporting = "Third-party notices for everything Vaayil ships",
                    onClick = onOpenLicenses,
                )
                RowDivider(inset = 36.dp)
                SourceCodeNotice(
                    onOpenSourceCode = onOpenSourceCode,
                    onOpenAppLicense = onOpenAppLicense,
                )
                RowDivider(inset = 36.dp)
                Text(
                    "Vaayil $appVersion",
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .alpha(0.7f)
                        .fillMaxWidth()
                        .padding(top = 12.dp, bottom = 4.dp),
                )
            }
            Spacer(Modifier.padding(bottom = 16.dp))
        }
    }
}

/** Eyebrow section header sitting on the bare board (spec §3.5 Settings) — `VellumEyebrow` uppercases. */
@Composable
private fun SectionHeader(title: String) {
    VellumEyebrow(title)
}

/** 1dp hairline rule between a sheet's rows, inset to the row's text column. */
@Composable
private fun RowDivider(inset: Dp) {
    Box(
        modifier = Modifier
            .padding(start = inset)
            .fillMaxWidth()
            .height(1.dp)
            .background(VellumColors.Hairline),
    )
}

/** A pushed row: icon, title + supporting copy, chevron; the tone step is the only press feedback. */
@Composable
internal fun SettingsRow(
    icon: @Composable () -> Unit,
    title: String,
    supporting: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = VellumColors.OnSurfaceVariant,
        )
    }
}

/**
 * The Wi-Fi guard's permission-state notes (spec §3.5 Settings): the mandated
 * `warning` surface — 1dp `warning-mark` seam, body in `on-warning`. The copy is
 * not the Play disclosure; it is the per-state explanation, kept verbatim.
 */
@Composable
private fun WarningNote(
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(VellumColors.Warning, shape)
            .vellumSeam(shape, VellumColors.WarningMark)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(body, style = MaterialTheme.typography.bodySmall, color = VellumColors.OnWarning)
        action?.invoke()
    }
}

@Composable
private fun SwitchRow(
    title: String,
    supporting: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            // One focus stop per setting: the row carries the label and the switch state (F9).
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
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = vellumSwitchColors(),
            // M3 Switch applies minimumInteractiveComponentSize only when it carries
            // the callback; restate it here so handing taps to the row keeps the
            // row's height unchanged (F9).
            modifier = Modifier.minimumInteractiveComponentSize(),
        )
    }
}

/**
 * Trusted networks (spec §3.5 Settings): seamed `container-2` chips for the saved
 * SSIDs and a [VellumField] well to add one. Shown only while the Wi-Fi guard is on.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrustedSsidEditor(
    ssids: Set<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // U3: the in-progress draft survives rotation.
    var draft by rememberSaveable { mutableStateOf("") }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Trusted networks",
            style = MaterialTheme.typography.titleSmall,
            color = VellumColors.OnSurfaceVariant,
        )
        // S4: a trusted name is not enough; the network must ask for a password (or a sign-in).
        Text(
            "Open Wi-Fi networks (no password) never count as trusted.",
            style = MaterialTheme.typography.bodySmall,
            color = VellumColors.OnSurfaceVariant,
        )
        if (ssids.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ssids.sorted().forEach { ssid -> SsidChip(ssid, onRemove) }
            }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // weight sits on the direct Row child — VellumField applies its modifier
            // to the inner well, where a nested weight would mis-measure the field.
            Box(modifier = Modifier.weight(1f)) {
                VellumField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = "Add trusted network name",
                    keyboardOptions = SsidKeyboard,
                )
            }
            VellumButton(
                label = "Add",
                onClick = { onAdd(draft); draft = "" },
                enabled = draft.isNotBlank(),
            )
        }
    }
}

/** One saved SSID: `container-2` chip with the cut seam and a quiet remove affordance. */
@Composable
private fun SsidChip(ssid: String, onRemove: (String) -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier = Modifier
            .background(VellumColors.Container2, shape)
            .vellumSeam(shape)
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            ssid,
            style = monoTextStyle(),
            color = VellumColors.OnSurface,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        IconButton(onClick = { onRemove(ssid) }, modifier = Modifier.size(48.dp)) {
            Icon(
                Icons.Rounded.Delete,
                contentDescription = "Remove $ssid",
                tint = VellumColors.OnSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * The external-control credential (spec §3.5 Settings): the token is machine data,
 * so it wears mono inside a `Films.Two` well; `Copy token` / `New token` are quiet
 * actions. The clip is marked sensitive and the window secure (S5).
 */
@Composable
private fun ExternalTokenBlock(
    token: String,
    onRegenerate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SecureWindow() // the token is the only credential gating third-party control (S5)
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var confirmNew by rememberSaveable { mutableStateOf(false) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Sheet(films = Films.Two, modifier = Modifier.fillMaxWidth(), contentPadding = 12.dp) {
            Text(token, style = monoTextStyle(), color = VellumColors.OnSurface)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VellumQuietAction("Copy token", onClick = {
                val clip = ClipData.newPlainText("Vaayil token", token).apply {
                    // No clipboard preview on Android 13+, and the platform treats the clip as a secret.
                    description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
                }
                scope.launch { clipboard.setClipEntry(ClipEntry(clip)) }
            })
            VellumQuietAction("New token", onClick = { confirmNew = true })
        }
        Text(
            "Broadcasts without this token are ignored. A new token invalidates automations that use the old one.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (confirmNew) {
        ScrimDialog(onDismissRequest = { confirmNew = false }) {
            Text("Replace the token?", style = MaterialTheme.typography.headlineLarge, color = VellumColors.OnSurface)
            Text(
                "Automations that use the current token stop working until you give them the new one.",
                style = MaterialTheme.typography.bodyMedium,
                color = VellumColors.OnSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VellumQuietAction("Cancel", { confirmNew = false })
                Spacer(Modifier.width(8.dp))
                // Destructive, but never a red fill: the secondary construction with the label in error-mark.
                VellumButton(
                    "Replace token",
                    { confirmNew = false; onRegenerate() },
                    role = VellumButtonRole.SECONDARY,
                    labelColor = VellumColors.ErrorMark,
                )
            }
        }
    }
}
