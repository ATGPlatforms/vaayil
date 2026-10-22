package dev.vaayil.feature.profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.vaayil.core.model.DnsMode
import dev.vaayil.core.model.DnsSplitRule
import dev.vaayil.core.model.Profile
import dev.vaayil.core.model.SecureDnsType
import dev.vaayil.core.model.isIpLiteral
import dev.vaayil.core.model.parseDotEndpoint
import dev.vaayil.core.model.secureInEffect
import dev.vaayil.core.model.warnings
import dev.vaayil.core.ui.CappedFontScale
import dev.vaayil.core.ui.Films
import dev.vaayil.core.ui.PillSegmented
import dev.vaayil.core.ui.SecretTextField
import dev.vaayil.core.ui.SectionHeader
import dev.vaayil.core.ui.SecureWindow
import dev.vaayil.core.ui.Sheet
import dev.vaayil.core.ui.VellumButton
import dev.vaayil.core.ui.VellumButtonRole
import dev.vaayil.core.ui.VellumColors
import dev.vaayil.core.ui.VellumField
import dev.vaayil.core.ui.VellumQuietAction
import dev.vaayil.core.ui.monoTextStyle
import dev.vaayil.core.ui.vellumSeam

/**
 * The "Split subnets" scope chip's preset: all three RFC 1918 private ranges —
 * the old preset omitted 172.16.0.0/12 (F20a).
 */
private val PRIVATE_SUBNETS = listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")

/**
 * Sectioned editor in Vellum dress (spec §3.5): [Interface] / [Peer] / DNS / Advanced
 * sheets with sage/slate/lavender section tints, fields as two-film wells with labels
 * above (the shared [VellumField]), DNS policy as stacked pill segments, and the
 * hostname lookup hint as a warning block. While Android's Private DNS is set to a host,
 * the DNS sheet carries a note naming it (T-8). Field errors surface per field,
 * only once touched.
 */
@Composable
fun ProfileEditorScreen(
    state: EditorState,
    onField: ((Profile) -> Profile) -> Unit,
    onSave: () -> Unit,
    privateDnsHost: String? = null,
) {
    SecureWindow() // keys are on screen here, revealed or not: blank screenshots/recents (S-5)
    val p = state.profile
    val ic = p.interfaceConfig
    val peer = p.peer
    // U1: numeric drafts that fail to parse block Save — they used to persist as null
    // while the inline "Must be a number" warning stayed up.
    val formatErrors = remember { mutableStateOf(setOf<String>()) }
    val setFormatError: (String, Boolean) -> Unit = { label, has ->
        formatErrors.value = if (has) formatErrors.value + label else formatErrors.value - label
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Edit Profile", style = MaterialTheme.typography.headlineSmall)

        Sheet(films = Films.One, modifier = Modifier.fillMaxWidth()) {
            SectionHeader(Icons.Rounded.Security, "[Interface]", tint = VellumColors.Primary, badge = "WIREGUARD")
            EditorField(
                label = "Profile name",
                value = p.name,
                errors = errorsFor(state.errors, "Profile name"),
                onValueChange = { v -> onField { it.copy(name = v) } },
                mono = false,
            )
            SecretField(
                label = "Private key",
                value = ic.privateKey,
                errors = errorsFor(state.errors, "Private key"),
                onValueChange = { v ->
                    onField { it.copy(interfaceConfig = it.interfaceConfig.copy(privateKey = v)) }
                },
                trailing = {
                    TextButton(
                        onClick = {
                            val fresh = WireGuardKeyring.generatePrivateKey()
                            onField { it.copy(interfaceConfig = it.interfaceConfig.copy(privateKey = fresh)) }
                        },
                    ) {
                        Icon(Icons.Rounded.Autorenew, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Generate")
                    }
                },
            )
            val derived = remember(ic.privateKey) { WireGuardKeyring.publicKeyFor(ic.privateKey) }
            if (derived != null) {
                EditorField(
                    label = "Public key (derived, read-only)",
                    value = derived,
                    errors = emptyList(),
                    onValueChange = {},
                    readOnly = true,
                    keyboardOptions = EditorKeyboards.machineText,
                )
            }
            ListField(
                label = "Addresses (comma-separated CIDR)",
                values = ic.addresses,
                // The singular prefix also matches the plural CIDR message and the
                // reserved-address message ("Interface address 10.111.222.3 is reserved …").
                errors = errorsFor(state.errors, "Interface address"),
                keyboardOptions = EditorKeyboards.machineText,
                onValues = { v -> onField { it.copy(interfaceConfig = it.interfaceConfig.copy(addresses = v)) } },
            )
            ListField(
                label = "DNS servers (comma-separated IPs)",
                values = ic.dnsServers,
                errors = errorsFor(state.errors, "DNS server"),
                keyboardOptions = EditorKeyboards.machineText,
                onValues = { v -> onField { it.copy(interfaceConfig = it.interfaceConfig.copy(dnsServers = v)) } },
            )
        }

        Sheet(films = Films.One, modifier = Modifier.fillMaxWidth()) {
            SectionHeader(Icons.Rounded.Lan, "[Peer]", tint = VellumColors.Slate)
            EditorField(
                label = "Endpoint (host:port)",
                value = peer.endpoint,
                errors = errorsFor(state.errors, "Endpoint"),
                onValueChange = { v -> onField { it.copy(peer = it.peer.copy(endpoint = v)) } },
                keyboardOptions = EditorKeyboards.uri,
            )
            EditorField(
                label = "Server public key",
                value = peer.publicKey,
                errors = errorsFor(state.errors, "Peer public key"),
                onValueChange = { v -> onField { it.copy(peer = it.peer.copy(publicKey = v)) } },
                keyboardOptions = EditorKeyboards.machineText,
            )
            ListField(
                label = "Allowed IPs (comma-separated CIDR)",
                values = peer.allowedIps,
                errors = errorsFor(state.errors, "AllowedIPs"),
                keyboardOptions = EditorKeyboards.machineText,
                onValues = { v -> onField { it.copy(peer = it.peer.copy(allowedIps = v)) } },
            )
            Row(
                modifier = Modifier.selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QuickScopeChip(
                    label = "Route everything",
                    selected = peer.allowedIps.containsAll(listOf("0.0.0.0/0", "::/0")),
                    onClick = {
                        onField { it.copy(peer = it.peer.copy(allowedIps = listOf("0.0.0.0/0", "::/0"))) }
                    },
                    modifier = Modifier.weight(1f),
                )
                QuickScopeChip(
                    label = "Split subnets",
                    selected = peer.allowedIps.toSet() == PRIVATE_SUBNETS.toSet(),
                    onClick = {
                        onField { it.copy(peer = it.peer.copy(allowedIps = PRIVATE_SUBNETS)) }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            // T-1: advice, not an error. It never joins state.errors, so Save stays enabled.
            p.warnings().forEach { WarningNote(it) }
        }

        Sheet(films = Films.One, modifier = Modifier.fillMaxWidth()) {
            SectionHeader(
                Icons.Rounded.Dns,
                "DNS",
                tint = VellumColors.Lavender,
                badge = if (p.dns.mode == DnsMode.SECURE) "PROTECTED" else null,
            )
            // T-8 (spec §3.3): advice about Android's Private DNS — it says why this sheet's
            // DNS settings are skipped. No action, no "Got it".
            privateDnsHost?.let { WarningNote(privateDnsEditorNote(it)) }
            Text("Secure DNS", style = MaterialTheme.typography.titleSmall)
            // DNS policy = two stacked pill segments (spec §3.5): Off/Secure-DNS, then DoH/DoT.
            // F29: the segment labels stay on one line inside the fixed 52dp track, so at
            // large font scales they cap their scale rather than clip in a half-width
            // segment ("Secure DNS" at font scale 2.0 is wider than the 134dp segment).
            CappedFontScale(1.15f) {
                PillSegmented(
                    options = listOf("Off", "Secure DNS"),
                    selectedIndex = if (p.dns.mode == DnsMode.SECURE) 1 else 0,
                    onSelect = { index ->
                        // F15: coming on pins a type immediately — DoH unless previously chosen —
                        // so the selector below never lies about a null secureType.
                        onField {
                            it.copy(
                                dns = it.dns.copy(
                                    mode = if (index == 1) DnsMode.SECURE else DnsMode.TUNNEL,
                                    secureType = if (index == 1) (it.dns.secureType ?: SecureDnsType.DOH) else it.dns.secureType,
                                ),
                            )
                        }
                    },
                )
            }
            if (p.dns.mode == DnsMode.SECURE) {
                CappedFontScale(1.15f) {
                    PillSegmented(
                        options = listOf("DoH", "DoT"),
                        selectedIndex = if (p.dns.secureType == SecureDnsType.DOT) 1 else 0,
                        onSelect = { index ->
                            onField {
                                it.copy(dns = it.dns.copy(secureType = if (index == 1) SecureDnsType.DOT else SecureDnsType.DOH))
                            }
                        },
                    )
                }
                EditorField(
                    label = if (p.dns.secureType == SecureDnsType.DOT) "DoT endpoint (host or host:port)" else "DoH endpoint (https URL)",
                    value = p.dns.secureEndpoint.orEmpty(),
                    errors = errorsFor(state.errors, "DoH endpoint") + errorsFor(state.errors, "DoT endpoint") +
                        errorsFor(state.errors, "Secure DNS"),
                    onValueChange = { v -> onField { it.copy(dns = it.dns.copy(secureEndpoint = v.ifBlank { null })) } },
                    keyboardOptions = EditorKeyboards.uri,
                )
                if (secureEndpointIsHostname(p.dns.secureType, p.dns.secureEndpoint)) {
                    HostnameHint()
                }
                if (p.dns.secureEndpoint.isNullOrBlank()) {
                    PresetRow("Cloudflare", "https://1.1.1.1/dns-query", onField)
                    PresetRow("Quad9", "https://9.9.9.9/dns-query", onField)
                    PresetRow("Google", "https://8.8.8.8/dns-query", onField)
                }
            }

            Text("Split DNS", style = MaterialTheme.typography.titleSmall)
            Text(
                "Domains listed here get DNS answers from your network's default resolver instead of the tunnel " +
                    "(or vice versa: tap a rule's resolver to switch it). Split DNS only chooses which resolver answers a lookup; it never changes how connections are routed.",
                style = MaterialTheme.typography.bodySmall,
                color = VellumColors.OnSurfaceVariant,
            )
            // Mirror of DnsPlanner's intercept condition (secureUsable || rulesUsable): rules
            // run when plain servers exist, or whenever Secure DNS is in effect, so the
            // warning belongs only to the leftover case.
            if (p.dns.rules.isNotEmpty() && ic.dnsServers.isEmpty() && !p.dns.secureInEffect) {
                Text(
                    "Split-DNS rules take effect only when this profile has a DNS server.",
                    style = MaterialTheme.typography.bodySmall,
                    color = VellumColors.OnSurfaceVariant,
                )
            }
            p.dns.rules.forEach { rule ->
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            rule.domain,
                            modifier = Modifier.weight(1f),
                            style = monoTextStyle(),
                            color = VellumColors.OnSurface,
                        )
                        IconButton(onClick = { onField { it.copy(dns = it.dns.copy(rules = it.dns.rules - rule)) } }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Remove ${rule.domain}")
                        }
                    }
                    VellumQuietAction(
                        if (rule.bypass) "Bypass resolver" else "Tunnel resolver",
                        onClickLabel = "Use the ${if (rule.bypass) "tunnel" else "bypass"} resolver for ${rule.domain}",
                        onClick = {
                            onField {
                                it.copy(dns = it.dns.copy(rules = it.dns.rules.map { r ->
                                    if (r == rule) r.copy(bypass = !r.bypass) else r
                                }))
                            }
                        },
                    )
                    // Imported rules can be invalid: the row always carries its own validator
                    // messages, not only while a draft is being typed.
                    val ruleErrors = state.errors.filter {
                        it.startsWith("Split-DNS domain \"${rule.domain}\"") ||
                            it.startsWith("Duplicate split-DNS domain \"${rule.domain}\"")
                    }
                    if (ruleErrors.isNotEmpty()) {
                        Text(
                            ruleErrors.joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            var newDomain by remember { mutableStateOf("") }
            val draftError = splitDnsDraftError(p, newDomain)
            EditorField(
                label = "Domain (e.g. internal.corp)",
                value = newDomain,
                errors = listOfNotNull(draftError.takeIf { newDomain.isNotBlank() }),
                onValueChange = { newDomain = it },
                keyboardOptions = EditorKeyboards.uri,
            )
            VellumButton(
                label = "Add rule",
                enabled = newDomain.isNotBlank() && draftError == null,
                role = VellumButtonRole.SECONDARY,
                leadingIcon = Icons.Rounded.Add,
                onClick = {
                    onField {
                        it.copy(dns = it.dns.copy(rules = it.dns.rules + DnsSplitRule(newDomain.trim(), bypass = true)))
                    }
                    newDomain = ""
                },
            )
        }

        AdvancedSection {
            NumericField(
                label = "MTU",
                value = ic.mtu,
                errors = errorsFor(state.errors, "MTU"),
                onFormatError = { has -> setFormatError("MTU", has) },
                onValueChange = { v -> onField { it.copy(interfaceConfig = it.interfaceConfig.copy(mtu = v)) } },
                keyboardOptions = EditorKeyboards.number,
            )
            NumericField(
                label = "Listen port",
                value = ic.listenPort,
                errors = errorsFor(state.errors, "Listen port"),
                onFormatError = { has -> setFormatError("Listen port", has) },
                onValueChange = { v -> onField { it.copy(interfaceConfig = it.interfaceConfig.copy(listenPort = v)) } },
                keyboardOptions = EditorKeyboards.number,
            )
            SecretField(
                label = "Preshared key (optional)",
                value = peer.presharedKey.orEmpty(),
                errors = errorsFor(state.errors, "Preshared key"),
                onValueChange = { v -> onField { it.copy(peer = it.peer.copy(presharedKey = v.ifBlank { null })) } },
            )
            NumericField(
                label = "Persistent keepalive (seconds)",
                value = peer.persistentKeepalive,
                errors = errorsFor(state.errors, "Persistent keepalive"),
                onFormatError = { has -> setFormatError("Persistent keepalive", has) },
                onValueChange = { v -> onField { it.copy(peer = it.peer.copy(persistentKeepalive = v)) } },
                keyboardOptions = EditorKeyboards.number,
            )
        }

        state.saveError?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        VellumButton(
            label = "Save Profile",
            onClick = onSave,
            enabled = state.errors.isEmpty() && formatErrors.value.isEmpty(),
            modifier = Modifier.fillMaxWidth(),
        )
        // F14: a disabled Save always says why — even for a profile whose invalid fields are
        // untouched (or inside the collapsed Advanced section). Silent while Save is enabled.
        if (state.errors.isNotEmpty() || formatErrors.value.isNotEmpty()) {
            Text(
                "To save, fix: " + saveBlockers(state.errors, formatErrors.value.toList()).joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.padding(bottom = 16.dp))
    }
}

/**
 * The hostname lookup hint (spec §3.5): a warning block with a 1dp
 * warning-mark seam. COPY IS FROZEN — never reword this string.
 */
@Composable
private fun HostnameHint() {
    // Free-standing warning surfaces standardize at the 10dp card radius (§3.5 Editor).
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(VellumColors.Warning, shape)
            .vellumSeam(shape, VellumColors.WarningMark)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            "A hostname DNS server is looked up through your current network's resolver when the tunnel comes up, " +
                "and again after a network change or a failure.",
            style = MaterialTheme.typography.bodySmall,
            color = VellumColors.OnWarning,
        )
    }
}

/** Selected scope chip (mockup's Route All / Split Subnets quick toggles). */
@Composable
private fun QuickScopeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .background(
                if (selected) VellumColors.PrimaryContainer else VellumColors.Container2,
                shape,
            )
            .then(if (selected) Modifier else Modifier.vellumSeam(shape))
            .clip(shape)
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = null,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) VellumColors.OnPrimaryContainer else VellumColors.OnSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/**
 * Collapsed-by-default home for the fields most profiles never need (MTU, ports, keys).
 * Name kept for the numeric-typo screenshot contract ("Advanced" + MTU flow).
 */
@Composable
private fun AdvancedSection(content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Sheet(films = Films.One, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { open = !open },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Rounded.Tune,
                contentDescription = null,
                tint = VellumColors.OnSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Text("Advanced", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(
                imageVector = if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = if (open) "Collapse advanced settings" else "Expand advanced settings",
            )
        }
        if (open) content()
    }
}

@Composable
private fun EditorField(
    label: String,
    value: String,
    errors: List<String>,
    onValueChange: (String) -> Unit,
    mono: Boolean = true,
    readOnly: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    var touched by remember { mutableStateOf(false) }
    val shown = if (touched) errors else emptyList()
    VellumField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        isError = shown.isNotEmpty(),
        supportingText = shown.joinToString("\n").ifEmpty { null },
        mono = mono,
        readOnly = readOnly,
        keyboardOptions = keyboardOptions,
        modifier = Modifier.onFocusChanged { if (it.isFocused) touched = true },
    )
}

@Composable
private fun SecretField(
    label: String,
    value: String,
    errors: List<String>,
    onValueChange: (String) -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    var visible by remember { mutableStateOf(false) }
    var touched by remember { mutableStateOf(false) }
    val shown = if (touched) errors else emptyList()
    SecretTextField(
        label = label,
        value = value,
        onValueChange = onValueChange,
        visible = visible,
        onToggleVisible = { visible = !visible },
        modifier = Modifier.onFocusChanged { if (it.isFocused) touched = true },
        isError = shown.isNotEmpty(),
        supportingText = shown.joinToString("\n").ifEmpty { null },
        trailing = trailing,
    )
}

@Composable
private fun NumericField(
    label: String,
    value: Int?,
    errors: List<String>,
    onValueChange: (Int?) -> Unit,
    onFormatError: (Boolean) -> Unit = {},
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    // Draft keyed on what the field last pushed, not on value: an unparseable suffix ("1420a")
    // stays in the draft without reaching the model, while external profile changes (value
    // differs from lastPushed) still sync into the field.
    var text by remember { mutableStateOf(value?.toString().orEmpty()) }
    var lastPushed by remember { mutableStateOf(value) }
    var touched by remember { mutableStateOf(false) }
    if (value != lastPushed) {
        lastPushed = value
        text = value?.toString().orEmpty()
    }
    val formatError = text.isNotBlank() && text.toIntOrNull() == null
    // Report the draft's parse state while composed and clear it on dispose: a collapsed
    // Advanced section must not leave Save disabled by a field that is no longer on screen.
    DisposableEffect(formatError) {
        onFormatError(formatError)
        onDispose { onFormatError(false) }
    }
    val reportErrors = if (touched) errors else emptyList()
    VellumField(
        value = text,
        onValueChange = { raw ->
            touched = true
            text = raw
            val parsed = raw.toIntOrNull()
            // Only a parseable draft (or a blank one, a legitimate "unset") reaches the model:
            // an unparseable draft used to push null, and the collapse that re-enables Save then
            // persisted that null as the field's value (the draft itself is kept either way).
            if (parsed != null || raw.isBlank()) {
                lastPushed = parsed
                onValueChange(parsed)
            }
        },
        label = label,
        isError = formatError || reportErrors.isNotEmpty(),
        supportingText = when {
            formatError -> "Must be a number"
            reportErrors.isNotEmpty() -> reportErrors.joinToString("\n")
            else -> null
        },
        keyboardOptions = keyboardOptions,
        modifier = Modifier.onFocusChanged { if (it.isFocused) touched = true },
    )
}

/**
 * A comma-separated list that keeps the raw text the user typed (F11): re-joining the parsed list on
 * every keystroke deleted a typed "," or " " before the next entry could follow it. The draft is
 * replaced only when the model changes from elsewhere (a scope chip, a load).
 */
@Composable
private fun ListField(
    label: String,
    values: List<String>,
    errors: List<String>,
    keyboardOptions: KeyboardOptions,
    onValues: (List<String>) -> Unit,
) {
    // Draft keyed on what the field last pushed, mirroring [NumericField]: the raw text stays in
    // the draft (a trailing "," or a space after it survives), while external profile changes
    // (values differ from lastPushed) still sync into the field.
    var text by remember { mutableStateOf(values.joinToString(", ")) }
    var lastPushed by remember { mutableStateOf(values) }
    if (values != lastPushed) {
        text = values.joinToString(", ")
        lastPushed = values
    }
    EditorField(
        label = label,
        value = text,
        errors = errors,
        keyboardOptions = keyboardOptions,
        onValueChange = { raw ->
            text = raw
            val parsed = splitList(raw)
            lastPushed = parsed
            onValues(parsed)
        },
    )
}

@Composable
private fun PresetRow(
    label: String,
    url: String,
    onField: ((Profile) -> Profile) -> Unit,
) {
    // Shown only while the secure endpoint is blank; picking a preset also pins the type to DoH.
    // Presets are IP-literal so the DoH request never needs a hostname lookup — a hostname
    // endpoint here would instead be bootstrap-resolved at tunnel start and re-resolved after a
    // network change or a lookup failure (D1).
    VellumQuietAction(
        label = "$label: $url",
        mono = true,
        onClick = { onField { it.copy(dns = it.dns.copy(secureEndpoint = url, secureType = SecureDnsType.DOH)) } },
    )
}

private fun errorsFor(errors: List<String>, prefix: String): List<String> =
    errors.filter { it.startsWith(prefix) }

private fun splitList(raw: String): List<String> =
    raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }

// A hostname endpoint is bootstrap-resolved at tunnel start and re-resolved after a network change
// or a lookup failure (D1); the hint just says so.
// Host extraction mirrors validate(): URI host for DoH, parseDotEndpoint for DoT.
private fun secureEndpointIsHostname(type: SecureDnsType?, endpoint: String?): Boolean {
    val raw = endpoint?.trim().orEmpty()
    if (raw.isEmpty() || type == null) return false
    val host = when (type) {
        SecureDnsType.DOH ->
            runCatching { java.net.URI(raw).host?.takeIf { it.isNotBlank() } }.getOrNull()
                ?: raw.replaceFirst(Regex("^https://", RegexOption.IGNORE_CASE), "").substringBefore('/').substringBefore(':')
        SecureDnsType.DOT -> parseDotEndpoint(raw)?.first ?: raw
    }
    val bare = host.removePrefix("[").removeSuffix("]")
    return bare.isNotEmpty() && !isIpLiteral(bare)
}
