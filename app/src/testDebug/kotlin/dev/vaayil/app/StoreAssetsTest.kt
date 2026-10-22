package dev.vaayil.app

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.model.AutoConnectRules
import dev.vaayil.core.model.DnsMode
import dev.vaayil.core.model.DnsPolicy
import dev.vaayil.core.model.DnsSplitRule
import dev.vaayil.core.model.InterfaceConfig
import dev.vaayil.core.model.PeerConfig
import dev.vaayil.core.model.Profile
import dev.vaayil.core.model.SecureDnsType
import dev.vaayil.core.model.SplitTunnelMode
import dev.vaayil.core.model.SplitTunnelSelections
import dev.vaayil.core.model.warnings
import dev.vaayil.core.tunnel.api.ConnectionPhase
import dev.vaayil.core.tunnel.api.HandshakeState
import dev.vaayil.core.tunnel.api.TunnelStats
import dev.vaayil.core.tunnel.api.TunnelStatus
import dev.vaayil.core.ui.LocalReducedMotion
import dev.vaayil.core.ui.VaayilLogoMark
import dev.vaayil.core.ui.VellumColors
import dev.vaayil.feature.profiles.AppIconLoader
import dev.vaayil.feature.profiles.AppInfo
import dev.vaayil.feature.profiles.EditorState
import dev.vaayil.feature.profiles.ProfileEditorScreen
import dev.vaayil.feature.profiles.ProfileListScreen
import dev.vaayil.feature.profiles.ProfileListUiState
import dev.vaayil.feature.profiles.SplitTunnelScreen
import dev.vaayil.feature.profiles.SplitTunnelUiState
import dev.vaayil.feature.settings.AutomationUiState
import dev.vaayil.feature.settings.BackupScreen
import dev.vaayil.feature.settings.BackupViewModel
import dev.vaayil.feature.settings.PermissionState
import dev.vaayil.feature.settings.SettingsFlags
import dev.vaayil.feature.settings.SettingsScreen
import dev.vaayil.feature.tunnel.DashboardScreen
import dev.vaayil.feature.tunnel.HomeUiState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The store images, rendered from the real app UI with demo data only (spec §4.6) and written
 * into the fastlane layout (`fastlane/metadata/android/en-US/`), the one image set F-Droid
 * and Play both read. Gated: the class runs only when `-PstoreAssets` is passed (the
 * `vaayil.storeAssets` system property wired in `app/build.gradle.kts`), so `build` and CI
 * never write files. The PNGs are not Roborazzi fixtures.
 *
 * The scrolling phone shots assert the top-edge rule (spec §8.1) on the captured pixels
 * before writing — nothing partial may sit directly under the top bar.
 *
 * Every value on screen is neutral demo data: TEST-NET endpoints, documentation/ULA tunnel
 * addresses, fixed dummy keys, a dns.example DoH endpoint, made-up split-tunnel apps with
 * generated icons, totals-only telemetry, no prices and no trial countdown.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StoreAssetsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun gate() {
        // Spec §4.6: build and CI never write files; the images are not Roborazzi fixtures.
        Assume.assumeTrue(System.getProperty("vaayil.storeAssets") == "true")
    }

    // Resolved lazily, after the @Before gate: from a working directory outside a checkout this
    // class must still SKIP (gate off), never error while the test instance is constructed.
    // Gradle runs unit tests with the app module as the working directory; walk up to the repo
    // root (settings.gradle.kts) so the output path is absolute and stable either way.
    private val repoRoot: File by lazy {
        generateSequence(File(checkNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("StoreAssetsTest needs a repo checkout above ${System.getProperty("user.dir")}")
    }

    private val imagesDir: File get() = File(repoRoot, "fastlane/metadata/android/en-US/images")

    /**
     * The canonical file for a capture name. Names stay stable internally — `phone-N`,
     * `tablet7-N`, `tablet10-N`, `feature-graphic` and `icon` — and an unknown name fails
     * here instead of writing to a fallback directory.
     */
    private fun imageFile(name: String): File {
        fun numbered(prefix: String, dir: String): File {
            val n = name.removePrefix(prefix)
            require(n.isNotEmpty() && n.all(Char::isDigit)) { "unknown store asset name: $name" }
            return File(imagesDir, "$dir/$n.png")
        }
        return when {
            name == "icon" -> File(imagesDir, "icon.png")
            name == "feature-graphic" -> File(imagesDir, "featureGraphic.png")
            name.startsWith("phone-") -> numbered("phone-", "phoneScreenshots")
            name.startsWith("tablet7-") -> numbered("tablet7-", "sevenInchScreenshots")
            name.startsWith("tablet10-") -> numbered("tablet10-", "tenInchScreenshots")
            else -> error("unknown store asset name: $name")
        }
    }

    /**
     * Screenshots and the feature graphic are 24-bit (no alpha); the icon keeps its alpha.
     * The output's parent directory is created at the write, so a skipped class (gate off)
     * leaves no directory behind.
     */
    private fun writePng(bitmap: Bitmap, file: File, opaque: Boolean) {
        file.parentFile?.mkdirs()
        val out = if (opaque && bitmap.hasAlpha()) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false).also { it.setHasAlpha(false) }
        } else bitmap
        FileOutputStream(file).use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /**
     * Renders [content] at the qualifier's window size, asserts the exact pixel size and
     * writes the PNG. [await] runs after the first idle: the async-loading screens wait
     * there before the capture. [topBand] additionally asserts the top-edge rule (spec
     * §8.1) on the 6px band directly below the top bar before anything is written.
     */
    private fun capture(
        name: String,
        widthDp: Int,
        heightDp: Int,
        dpi: Int,
        opaque: Boolean = true,
        topBand: Boolean = false,
        await: () -> Unit = {},
        content: @Composable () -> Unit,
    ) {
        compose.setContent {
            VaayilTheme.Content {
                // Deterministic captures: the status dot holds still.
                CompositionLocalProvider(LocalReducedMotion provides true) { content() }
            }
        }
        compose.waitForIdle()
        await()
        // captureToImage() waits for a window redraw that Robolectric never performs; draw the
        // decor view straight into the bitmap instead, the same path Roborazzi captures take.
        val view = compose.activity.window.decorView
        val shot = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(shot))
        if (topBand) assertTopBand(shot, topBandPx())
        assertEquals("width $name", widthDp * dpi / 160, shot.width)
        assertEquals("height $name", heightDp * dpi / 160, shot.height)
        writePng(shot, imageFile(name), opaque)
    }

    /** The content viewport's top edge — the top bar's bottom edge — in captured px. */
    private fun topBandPx(): Int =
        compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes()
            .maxByOrNull { rootRect(it).height }
            ?.let { rootRect(it).top.roundToInt() }
            ?: error("no scrollable content to measure the top bar's edge")

    /**
     * The top-edge rule for the scrolling phone shots (spec §8.1): the first element under the
     * top bar must be whole, so the 6px band directly below the bar may hold only board, film
     * and seam pixels — or antialiased blends of them (rounded card corners and segment
     * tracks) and shadow tints, which composite as blends towards the sheet-lift ink. Every
     * ink colour (on-surface, on-surface-variant, sage, slate, lavender) is far from every
     * allowed blend, so a glyph, icon or switch cut by the bar always fails.
     */
    private fun assertTopBand(shot: Bitmap, bandTop: Int) {
        val allowed = listOf(
            VellumColors.Canvas, VellumColors.Container1, VellumColors.Container2,
            VellumColors.Container3, VellumColors.Container4, VellumColors.Container5,
            VellumColors.Seam, VellumColors.Hairline,
            Color(0xFF3A301C), // SheetLiftAmbient: `sheet-lift` tints composite towards it
        ).map(::channelsOf)
        for (y in bandTop until bandTop + 6) {
            for (x in 0 until shot.width) {
                val argb = shot.getPixel(x, y)
                val pixel = intArrayOf((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)
                val clean = allowed.indices.any { i ->
                    (i until allowed.size).any { j -> nearBlend(pixel, allowed[i], allowed[j], BAND_TOLERANCE) }
                }
                assertTrue(
                    "top band pixel ($x, $y) = ${pixel.contentToString()} is not board, film, seam or a blend of them",
                    clean,
                )
            }
        }
    }

    /** The colour's sRGB channels as 0..255 ints. */
    private fun channelsOf(color: Color) = intArrayOf(
        (color.red * 255f + 0.5f).toInt(),
        (color.green * 255f + 0.5f).toInt(),
        (color.blue * 255f + 0.5f).toInt(),
    )

    /** True when [pixel] is within [tolerance] per channel of a point on the segment a–b. */
    private fun nearBlend(pixel: IntArray, a: IntArray, b: IntArray, tolerance: Int): Boolean {
        val ab = FloatArray(3) { (b[it] - a[it]).toFloat() }
        val len2 = ab[0] * ab[0] + ab[1] * ab[1] + ab[2] * ab[2]
        val t = if (len2 == 0f) 0f else (
            (pixel[0] - a[0]) * ab[0] + (pixel[1] - a[1]) * ab[1] + (pixel[2] - a[2]) * ab[2]
        ) / len2
        val s = t.coerceIn(0f, 1f)
        return (0..2).all { abs(pixel[it] - (a[it] + s * ab[it])) <= tolerance + 0.5f }
    }

    /**
     * The tab chrome exactly as `RootNav` composes it: `TabContainer` capping the column at
     * 720dp, the Vellum top bar above the screen, the bottom bar below. No device frames.
     */
    @Composable
    private fun InChrome(route: String, content: @Composable () -> Unit) {
        Column(Modifier.fillMaxSize().background(VellumColors.Canvas)) {
            Box(Modifier.weight(1f)) {
                TabContainer {
                    Column(Modifier.fillMaxSize()) {
                        VellumTopBar(onOpenSettings = {}, scrolled = false)
                        Box(Modifier.weight(1f)) { content() }
                    }
                }
            }
            VellumBottomBar(currentRoute = route) {}
        }
    }

    // --- Demo data (spec §4.6: neutral names, TEST-NET/example endpoints, dummy keys). ---

    private val home = Profile(
        id = "home",
        name = "Home server",
        interfaceConfig = InterfaceConfig(
            privateKey = INTERFACE_KEY,
            addresses = listOf("10.0.0.2/32", "fdcb:b743:7f0b::2/128"),
            dnsServers = listOf("192.0.2.53"),
            mtu = 1420,
            listenPort = null,
        ),
        peer = PeerConfig(
            publicKey = PEER_KEY,
            presharedKey = null,
            endpoint = "192.0.2.10:51820",
            allowedIps = listOf("0.0.0.0/0", "::/0"),
            persistentKeepalive = 25,
        ),
    )
    private val travel = home.copy(
        id = "travel",
        name = "Travel",
        peer = home.peer.copy(endpoint = "198.51.100.7:51820"),
    )
    private val office = home.copy(
        id = "office",
        name = "Office",
        peer = home.peer.copy(endpoint = "203.0.113.24:51820"),
    )

    private val editorProfile = home.copy(
        dns = DnsPolicy(
            mode = DnsMode.SECURE,
            secureType = SecureDnsType.DOH,
            secureEndpoint = "https://dns.example/dns-query",
            rules = listOf(
                DnsSplitRule("example.com", bypass = true),
                DnsSplitRule("example.org", bypass = true),
            ),
        ),
    )

    private val splitApps = listOf(
        AppInfo("com.example.notes", "Notes"),
        AppInfo("com.example.maps", "Maps"),
        AppInfo("com.example.mail", "Mail"),
        AppInfo("com.example.podcasts", "Podcasts"),
        AppInfo("com.example.cal", "Calendar"),
        AppInfo("com.example.radio", "Radio"),
    )
    private val splitTunnelUi = SplitTunnelUiState(
        mode = SplitTunnelMode.EXCLUDE,
        selectedPackages = setOf("com.example.notes", "com.example.maps"),
        apps = splitApps,
    )

    /** Plausible session totals and handshake age only — never rates (spec §4.6). */
    private fun connectedUi() = HomeUiState(
        status = TunnelStatus(
            profileId = home.id,
            phase = ConnectionPhase.CONNECTED,
            error = null,
            generation = 1,
        ),
        stats = TunnelStats(
            handshakeAt = System.currentTimeMillis() - 3_000,
            rxBytes = 812_345_678,
            txBytes = 94_217_728,
            generation = 1,
        ),
        activeProfile = home,
        profiles = listOf(home, travel, office),
        handshakeState = HandshakeState.VERIFIED,
    )

    // --- Generated split-tunnel icons (the G3 seam): no real app icons ever render. ---

    private val demoIconLoader = AppIconLoader { packageName, sizePx -> demoIcon(packageName, sizePx) }

    /**
     * Fill + letter-ink pairs for the generated tiles, all semantically neutral: a decorative app
     * icon must never claim a state. Excluded on purpose:
     * - Primary/PrimaryContainer/SageMark/Pastel1 — sage marks verified/active states only;
     * - Warning/WarningMark and Error/ErrorMark/M3Error — state markers;
     * - FieldActive/FieldIdle/IdleDot — connection-state surfaces; Disabled — means disabled;
     * - Canvas/Container1..5 — the warm paper films (hue 85) read as a pale sage cast at tile
     *   size, so they stay out too, and Hairline/Seam are decorative rules, not fills.
     * What is left is neutral by documentation:
     * - Slate: the transport-data accent (a data domain, never a state);
     * - Lavender: the DNS/advanced accent (a data domain, never a state);
     * - Stroke3: the neutral boundary ink, a warm grey;
     * - Pastel2/Pastel3: the avatar pastels (blue and lavender), decorative by documentation —
     *   the one pair skipped, Pastel1, is the sage-tinted pastel.
     * White reads on the three dark fills; the pastels carry their documented inks.
     */
    private val iconPalette = listOf(
        VellumColors.Slate to Color.White,
        VellumColors.Lavender to Color.White,
        VellumColors.Stroke3 to Color.White,
        VellumColors.Pastel2 to VellumColors.OnPastel2,
        VellumColors.Pastel3 to VellumColors.OnPastel3,
    )

    /** A plain coloured rounded square with the app's initial; colour from the name's hash. */
    private fun demoIcon(packageName: String, sizePx: Int): Bitmap {
        val (fill, ink) = iconPalette[Math.floorMod(packageName.hashCode(), iconPalette.size)]
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = fill.toArgb()
        val radius = sizePx * 0.24f
        canvas.drawRoundRect(RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat()), radius, radius, paint)
        paint.color = ink.toArgb()
        paint.textSize = sizePx * 0.5f
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        val metrics = paint.fontMetrics
        val baseline = sizePx / 2f - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(packageName.substringAfterLast('.').take(1).uppercase(), sizePx / 2f, baseline, paint)
        return bitmap
    }

    // --- Screen content, mirroring the real routes' chrome. ---

    @Composable
    private fun DashboardContent(ui: HomeUiState) {
        InChrome("dashboard") {
            DashboardScreen(
                ui = ui,
                selected = home,
                onSelectProfile = {},
                onToggle = {},
                onOpenProfiles = {},
            )
        }
    }

    @Composable
    private fun ProfilesContent() {
        InChrome("profiles") {
            ProfileListScreen(
                ui = ProfileListUiState(
                    items = listOf(home, travel, office),
                    activeId = home.id,
                    phase = ConnectionPhase.CONNECTED,
                    handshakeState = HandshakeState.VERIFIED,
                ),
                onOpenEditor = {},
                onOpenImport = {},
                onScanQr = {},
                onDelete = {},
                onDuplicate = {},
                onToggleConnect = {},
                onShareQr = {},
                onExport = {},
                onDeleteUnreadable = {},
            )
        }
    }

    @Composable
    private fun EditorContent() {
        PushScreen(onBack = {}) {
            ProfileEditorScreen(state = EditorState(editorProfile, emptyList()), onField = {}, onSave = {})
        }
    }

    @Composable
    private fun SplitTunnelContent() {
        InChrome("split-tunnel") {
            SplitTunnelScreen(
                state = splitTunnelUi,
                onMode = {},
                onToggle = {},
                onQuery = {},
                onShowSystem = {},
                onRouteAll = {},
                onBypassAll = {},
                onAllowLocalNetwork = {},
                iconLoader = demoIconLoader,
            )
        }
    }

    @Composable
    private fun SettingsContent() {
        PushScreen(onBack = {}) {
            SettingsScreen(
                ui = SettingsFlags(),
                automation = AutomationUiState(
                    rules = AutoConnectRules(wifiGuardEnabled = true, trustedSsids = setOf("Home")),
                    permissionState = PermissionState.GRANTED,
                ),
                appVersion = "1.1.0",
                onOpenKillSwitchGuide = {},
                onOpenVpnSettings = {},
                onOpenPrivacyPolicy = {},
                onOpenLicenses = {},
                onOpenSourceCode = {},
                onOpenAppLicense = {},
                onOpenLocationSettings = {},
                onWifiGuard = {},
                onAutoConnectOnMobileData = {},
                onDisconnectOnTrusted = {},
                onConnectOnBoot = {},
                onAddTrustedSsid = {},
                onRemoveTrustedSsid = {},
                onExternalControl = {},
                onRegenerateExternalToken = {},
            )
        }
    }

    @Composable
    private fun BackupContent() {
        val vm = remember {
            BackupViewModel(
                profiles = MutableStateFlow(listOf(home, travel, office)),
                storedCount = MutableStateFlow(3),
                existingIds = { emptySet() },
                saveProfiles = {},
                selections = MutableStateFlow(SplitTunnelSelections.INITIAL),
                saveRules = {},
            )
        }
        InChrome("backup") { BackupScreen(vm = vm, onRestored = {}) }
    }

    // --- Scroll anchors (store-image wave 2). The top-edge rule needs an exact position, so
    // each scrolling phone shot drives the same ScrollBy action `performScrollTo` uses with a
    // measured delta, instead of that helper's minimal bring-into-view.

    /** The vertical scrollable ancestor of [matcher]'s node — the viewport that clips it. */
    private fun scrollableAncestorOf(matcher: SemanticsMatcher): SemanticsNode {
        var node: SemanticsNode? = compose.onNode(matcher).fetchSemanticsNode()
        while (node != null && !node.config.contains(SemanticsActions.ScrollBy)) node = node.parent
        return requireNotNull(node) { "no scrollable ancestor for ${matcher.description}" }
    }

    /**
     * The node's rect in root coordinates. `SemanticsNode.boundsInRoot` clips: a node fully
     * scrolled out of its viewport comes back empty, and every anchor here is measured before
     * its scroll, so the position/size pair is the honest source.
     */
    private fun rootRect(node: SemanticsNode) = Rect(
        node.positionInRoot.x,
        node.positionInRoot.y,
        node.positionInRoot.x + node.size.width,
        node.positionInRoot.y + node.size.height,
    )

    /** Drives that scrollable's exact ScrollBy action; positive [dy] scrolls the content up. */
    private fun scrollBy(scrollable: SemanticsNode, dy: Float) {
        if (dy == 0f) return
        val scrollables = compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes()
        val index = scrollables.indexOfFirst { it.id == scrollable.id }
        check(index >= 0) { "scrollable ${scrollable.id} is not matched by hasScrollAction()" }
        compose.onAllNodes(hasScrollAction())[index]
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, dy) }
        compose.waitForIdle()
    }

    /**
     * phone-3: the DNS card's top edge right under the top bar, with the example.com rule row
     * fully visible. The card is no semantics node, so its top edge is measured off the [Peer]
     * card's last child — the two scope chips; `warnings()` is empty for the demo profile, so
     * nothing follows them — plus the sheet's 20dp bottom padding and the 16dp card gap. The
     * re-measure after the scroll proves the request did not clamp at either end.
     */
    private fun scrollToDnsCardTop() {
        // The card-top derivation below reads the [Peer] card's bottom off its last child, so
        // it holds only while no warning note follows the chips for the demo profile.
        assertTrue("phone-3: the demo profile must produce no warning notes", editorProfile.warnings().isEmpty())
        val scrollable = scrollableAncestorOf(hasText("example.com"))
        val cardTop = {
            listOf("Route everything", "Split subnets")
                .maxOf { rootRect(compose.onNodeWithText(it).fetchSemanticsNode()).bottom } +
                with(compose.density) { (20.dp + 16.dp).toPx() }
        }
        val viewportTop = rootRect(scrollable).top
        scrollBy(scrollable, cardTop() - viewportTop)
        assertEquals(
            "phone-3: the DNS card's top edge sits at the viewport top",
            viewportTop, cardTop(), 1f,
        )
        val viewport = rootRect(scrollable)
        // phone-3 frames the example.com rule — the domain and the remove icon a person sees.
        // The remove control's 40dp IconButton touch box may run past the fold; its visible
        // 24dp icon (measured unmerged) stays whole. The longer split-DNS hint from #11 moved
        // the row down, and the resolver action below it is a 48dp target, so the rest of the
        // rule block ends below the viewport bottom: the card top owns the bar (the top-edge
        // rule) and a bottom-edge cut is the one the shot may carry.
        listOf(
            compose.onNodeWithText("example.com"),
            compose.onNodeWithContentDescription("Remove example.com", useUnmergedTree = true),
        ).forEach { node ->
            val bounds = rootRect(node.fetchSemanticsNode())
            assertTrue(
                "phone-3: the example.com rule row $bounds is not fully inside $viewport",
                bounds.top >= viewport.top && bounds.bottom <= viewport.bottom,
            )
        }
    }

    /**
     * phone-5: the AUTOMATION section header's top edge right under the top bar, with nothing
     * of the Protection card above it showing. `VellumEyebrow` uppercases its text, so the
     * semantics string is "AUTOMATION".
     */
    private fun scrollToAutomationHeader() {
        val scrollable = scrollableAncestorOf(hasText("AUTOMATION"))
        val header = { rootRect(compose.onNodeWithText("AUTOMATION").fetchSemanticsNode()) }
        val viewportTop = rootRect(scrollable).top
        scrollBy(scrollable, header().top - viewportTop)
        assertEquals("phone-5: the AUTOMATION header sits at the viewport top", viewportTop, header().top, 1f)
        assertTrue(
            "phone-5: nothing of the Protection card shows",
            rootRect(compose.onNodeWithText("System VPN settings").fetchSemanticsNode()).bottom <= viewportTop,
        )
    }

    /**
     * phone-4: the Exclusive/Inclusive mode selector first under the top bar, with the two
     * selected app rows (Notes, Maps) fully visible. The list is keyed, so ScrollToIndex puts
     * the "mode" item's top edge at the viewport top; the segment sits 4dp inside the track
     * (PillSegmented's inner padding).
     */
    private fun scrollToModeSelector() {
        compose.onNode(hasScrollAction()).performScrollToKey("mode")
        compose.waitForIdle()
        val scrollable = scrollableAncestorOf(hasText("Exclusive"))
        val viewportTop = rootRect(scrollable).top
        assertEquals(
            "phone-4: the mode selector sits at the viewport top",
            viewportTop + with(compose.density) { 4.dp.toPx() },
            rootRect(compose.onNodeWithText("Exclusive").fetchSemanticsNode()).top,
            1f,
        )
        val viewport = rootRect(scrollable)
        listOf("Notes", "Maps").forEach { label ->
            val bounds = rootRect(compose.onNodeWithText(label).fetchSemanticsNode())
            assertTrue(
                "phone-4: $label is not fully visible ($bounds in $viewport)",
                bounds.top >= viewport.top && bounds.bottom <= viewport.bottom,
            )
        }
    }

    /**
     * Tablet settings shots: unchanged by the top-edge wave — the minimal bring-into-view on
     * the Automation rows, exactly as before; only the phone shot needed the exact anchor.
     */
    private fun scrollToAutomation() {
        compose.onNodeWithText("Connect on boot").performScrollTo()
        compose.waitForIdle()
    }

    /**
     * G3: the icons load off the main thread; every composed row holds its icon before the
     * capture. The unmerged tree is required — the row is one merged `toggleable` node, so
     * the icon's tag is invisible to a merged-tree query.
     */
    private fun awaitDemoIcons() {
        compose.waitUntil(10_000) {
            splitApps.all { app ->
                val rowShown = compose.onAllNodesWithText(app.packageName).fetchSemanticsNodes().isNotEmpty()
                !rowShown || compose
                    .onAllNodesWithTag("app-icon-${app.packageName}", useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
        }
        compose.waitForIdle()
    }

    // --- Phone shots: 360×640dp @ 3.0 (1080×1920 px). ---

    @Test
    @Config(qualifiers = "w360dp-h640dp-480dpi")
    fun phoneDashboard() = capture("phone-1", 360, 640, 480) { DashboardContent(connectedUi()) }

    @Test
    @Config(qualifiers = "w360dp-h640dp-480dpi")
    fun phoneProfiles() = capture("phone-2", 360, 640, 480) { ProfilesContent() }

    @Test
    @Config(qualifiers = "w360dp-h640dp-480dpi")
    fun phoneEditorDns() = capture("phone-3", 360, 640, 480, topBand = true, await = { scrollToDnsCardTop() }) { EditorContent() }

    @Test
    @Config(qualifiers = "w360dp-h640dp-480dpi")
    fun phoneSplitTunnel() = capture("phone-4", 360, 640, 480, topBand = true, await = { scrollToModeSelector(); awaitDemoIcons() }) { SplitTunnelContent() }

    @Test
    @Config(qualifiers = "w360dp-h640dp-480dpi")
    fun phoneSettings() = capture("phone-5", 360, 640, 480, topBand = true, await = { scrollToAutomationHeader() }) { SettingsContent() }

    @Test
    @Config(qualifiers = "w360dp-h640dp-480dpi")
    fun phoneBackup() = capture("phone-6", 360, 640, 480) { BackupContent() }

    // --- Tablet shots: 612×1088dp and 864×1536dp @ 2.0; the 864dp shots show the G1 caps. ---

    @Test
    @Config(qualifiers = "w612dp-h1088dp-320dpi")
    fun tablet7Dashboard() = capture("tablet7-1", 612, 1088, 320) { DashboardContent(connectedUi()) }

    @Test
    @Config(qualifiers = "w612dp-h1088dp-320dpi")
    fun tablet7Profiles() = capture("tablet7-2", 612, 1088, 320) { ProfilesContent() }

    @Test
    @Config(qualifiers = "w612dp-h1088dp-320dpi")
    fun tablet7SplitTunnel() = capture("tablet7-3", 612, 1088, 320, await = { awaitDemoIcons() }) { SplitTunnelContent() }

    @Test
    @Config(qualifiers = "w612dp-h1088dp-320dpi")
    fun tablet7Settings() = capture("tablet7-4", 612, 1088, 320, await = { scrollToAutomation() }) { SettingsContent() }

    @Test
    @Config(qualifiers = "w864dp-h1536dp-320dpi")
    fun tablet10Dashboard() = capture("tablet10-1", 864, 1536, 320) { DashboardContent(connectedUi()) }

    @Test
    @Config(qualifiers = "w864dp-h1536dp-320dpi")
    fun tablet10Profiles() = capture("tablet10-2", 864, 1536, 320) { ProfilesContent() }

    @Test
    @Config(qualifiers = "w864dp-h1536dp-320dpi")
    fun tablet10SplitTunnel() = capture("tablet10-3", 864, 1536, 320, await = { awaitDemoIcons() }) { SplitTunnelContent() }

    @Test
    @Config(qualifiers = "w864dp-h1536dp-320dpi")
    fun tablet10Settings() = capture("tablet10-4", 864, 1536, 320, await = { scrollToAutomation() }) { SettingsContent() }

    // --- Feature graphic: 512×250dp @ 2.0 (1024×500 px), no chrome, no "WireGuard". ---

    @Test
    @Config(qualifiers = "w512dp-h250dp-320dpi")
    fun featureGraphic() = capture("feature-graphic", 512, 250, 320) { FeatureGraphic() }

    @Composable
    private fun FeatureGraphic() {
        Column(
            Modifier.fillMaxSize().background(VellumColors.Canvas),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            VaayilLogoMark(
                modifier = Modifier.size(96.dp),
                // The top bar's mark colours (VellumTopBar): sage on the container-2 notch.
                container = VellumColors.Primary,
                glyph = VellumColors.Primary,
                notch = VellumColors.Container2,
            )
            Spacer(Modifier.height(20.dp))
            Text("Vaayil", style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.SemiBold))
            Text(
                "Bring your own tunnel. No accounts, no analytics.",
                style = MaterialTheme.typography.bodyLarge,
                color = VellumColors.OnSurfaceVariant,
            )
        }
    }

    // --- Icon: the adaptive layers composited, cropped to the 72dp launchers show (512 px). ---

    @Test
    fun icon512() {
        val icon = renderIcon(ApplicationProvider.getApplicationContext())
        assertEquals("width icon-512", 512, icon.width)
        assertEquals("height icon-512", 512, icon.height)
    }

    private fun renderIcon(context: Context): Bitmap {
        val size = 512
        val layer = size * 108 / 72          // 768: the full adaptive layer size at this scale
        val offset = (layer - size) / 2      // 128: centre-crop to what launchers show
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.translate(-offset.toFloat(), -offset.toFloat())
        val bg = ColorDrawable(context.getColor(R.color.ic_launcher_background))
        bg.setBounds(0, 0, layer, layer)
        bg.draw(canvas)
        val fg = context.getDrawable(R.drawable.ic_launcher_foreground)!!
        fg.setBounds(0, 0, layer, layer)
        fg.draw(canvas)
        writePng(bitmap, imageFile("icon"), opaque = false) // no mask, no shadow — the store draws its own
        return bitmap
    }

    private companion object {
        /** Fixed dummy keys (spec §4.6): 32-byte base64, valid shape, not real material. */
        const val PEER_KEY = "0J9OwLZ0PqfZD8kRtWy6cA1iJHUuXCbFw2coQVN5R2k="
        const val INTERFACE_KEY = "1J9OwLZ0PqfZD8kRtWy6cA1iJHUuXCbFw2coQVN5R2k="

        /**
         * The top-band check's per-channel tolerance: rendering rounds each blend pixel to
         * 8-bit and AA can land a unit off the ideal line; ink is 90+ units away.
         */
        const val BAND_TOLERANCE = 3
    }
}
