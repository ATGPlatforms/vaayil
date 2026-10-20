package dev.vaayil.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.graphics.Bitmap
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.journeyapps.barcodescanner.ScanContract
import com.google.zxing.BarcodeFormat
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import dev.vaayil.core.config.WgConfigSerializer
import dev.vaayil.core.config.toWgQuickConfig
import dev.vaayil.core.model.AutoConnectRules
import dev.vaayil.core.model.Profile
import dev.vaayil.core.model.needsGuardService
import dev.vaayil.core.model.validate
import dev.vaayil.core.tunnel.api.isLive
import dev.vaayil.core.tunnel.wg.ConnectionRegistry
import dev.vaayil.core.tunnel.wg.VpnTunnelService
import dev.vaayil.core.ui.ScrimDialog
import dev.vaayil.core.ui.VellumQuietAction
import dev.vaayil.core.ui.SecureWindow
import dev.vaayil.core.ui.VellumColors
import dev.vaayil.feature.profiles.ImportOutcome
import dev.vaayil.feature.profiles.ImportScreen
import dev.vaayil.feature.profiles.ImportViewModel
import dev.vaayil.feature.profiles.InstalledAppsSource
import dev.vaayil.feature.profiles.PackageManagerAppIconLoader
import dev.vaayil.feature.profiles.ProfileListScreen
import dev.vaayil.feature.profiles.ProfileListViewModel
import dev.vaayil.feature.profiles.SplitTunnelScreen
import dev.vaayil.feature.profiles.SplitTunnelViewModel
import dev.vaayil.feature.profiles.profileWriteMessage
import dev.vaayil.feature.settings.AppLicenseScreen
import dev.vaayil.feature.settings.BackupScreen
import dev.vaayil.feature.settings.BackupViewModel
import dev.vaayil.feature.settings.KillSwitchGuideScreen
import dev.vaayil.feature.settings.LocationDisclosureDialog
import dev.vaayil.feature.settings.OnboardingScreen
import dev.vaayil.feature.settings.PermissionState
import dev.vaayil.feature.settings.PrivacyPolicyScreen
import dev.vaayil.feature.settings.SettingsScreen
import dev.vaayil.feature.settings.SettingsViewModel
import dev.vaayil.feature.settings.VAAYIL_SOURCE_URL
import dev.vaayil.feature.settings.permissionStateFor
import dev.vaayil.feature.tunnel.DashboardScreen
import dev.vaayil.feature.tunnel.HomeViewModel
import dev.vaayil.feature.tunnel.dashboardTarget
import dev.vaayil.feature.tunnel.rememberDashboardPick
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val TAB_ROUTES = setOf("dashboard", "profiles", "split-tunnel", "backup")

@Composable
fun RootNav(container: AppContainer) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    SwitchFailureSnackbar(snackbar)
    val permissionGate = rememberManualConnectionGate(container, snackbar)
    val nav = rememberNavController()
    // Gate the graph on the flags' first emission: the start destination must be decided from
    // persisted onboarding state before NavHost composes, and never recomputed afterwards.
    val flags = container.settingsFlags.collectAsStateWithLifecycle(initialValue = null).value
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route?.substringBefore('?')
    // U7: drafts end with the task, not with a recreation. M5: every resume re-arms the guard
    // while a rule is on, so a permission granted outside Vaayil takes effect on return.
    DropDraftsOnFinish { container.dropDrafts() }
    GuardServiceResumeEffect(
        needsGuard = { container.automationRules.snapshot().needsGuardService },
        start = container::startAutoConnectService,
    )
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (currentRoute in TAB_ROUTES) {
                VellumBottomBar(currentRoute) { route -> nav.navigateTab(route) }
            }
        },
    ) { padding ->
        val startOnboarding = flags?.let { !it.onboardingDone }
        if (startOnboarding == null) {
            Box(Modifier.fillMaxSize().padding(padding)) // first DataStore read still in flight
        } else {
            RootNavGraph(container, nav, permissionGate, snackbar, startOnboarding, Modifier.padding(padding))
        }
    }
}

/** Tab navigation pops to the Dashboard root; state is kept per tab (mockup tab model). */
internal fun NavHostController.navigateTab(route: String) {
    navigate(route) {
        popUpTo("dashboard") { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * Pushes a full-screen route, leaving the top of the stack alone: a second activation that
 * arrives before the push lands (or while the outgoing entry's exit transition still runs)
 * cannot stack the same destination twice (C3).
 */
internal fun NavHostController.navigatePush(route: String) {
    navigate(route) { launchSingleTop = true }
}

/**
 * Pops the current destination only while one sits below it. A Back activation repeated from an
 * entry that is already leaving lands here a second time; the start destination is the floor and
 * is never popped, so no sequence of activations can empty the NavHost (C3).
 */
internal fun NavHostController.popIfNotRoot(): Boolean =
    previousBackStackEntry != null && popBackStack()

@Composable
private fun RootNavGraph(
    container: AppContainer,
    nav: NavHostController,
    gate: (profileId: String?, action: () -> Unit) -> Unit,
    snackbar: SnackbarHostState,
    startOnboarding: Boolean,
    modifier: Modifier = Modifier,
) {
    val startDestination = remember { if (startOnboarding) "onboarding" else "dashboard" }
    // Outlives every destination: a notice launched as an entry leaves must not end with it (M3).
    val noticeScope = rememberCoroutineScope()
    NavHost(navController = nav, startDestination = startDestination, modifier = modifier) {
        composable("dashboard") { DashboardTab(container, nav, gate) }
        composable("profiles") { ProfilesTab(container, nav, gate, snackbar) }
        composable("split-tunnel") { SplitTunnelTab(container, nav) }
        composable("backup") { BackupTab(container, nav) }
        composable(
            route = EDITOR_ROUTE,
            arguments = listOf(
                navArgument("profileId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("draftId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            EditorRoute(
                container,
                nav,
                entry.arguments?.getString("profileId"),
                entry.arguments?.getString("draftId"),
                entry.id,
                snackbar,
                noticeScope,
            )
        }
        composable("import") { ImportRoute(container, nav, snackbar) }
        composable("settings") { SettingsRoute(container, nav) }
        composable("killswitch") {
            val guideContext = LocalContext.current
            PushScreen(onBack = { nav.popIfNotRoot() }) {
                KillSwitchGuideScreen(
                    onOpenVpnSettings = { guideContext.openSystemScreen(Intent(Settings.ACTION_VPN_SETTINGS)) },
                )
            }
        }
        composable("privacy") {
            PushScreen(onBack = { nav.popIfNotRoot() }) {
                // Long-form text rides the 720dp tab container (spec §3.5).
                TabContainer {
                    PrivacyPolicyScreen()
                }
            }
        }
        composable("licenses") {
            PushScreen(onBack = { nav.popIfNotRoot() }) {
                TabContainer {
                    LicensesScreen()
                }
            }
        }
        composable("app-license") {
            PushScreen(onBack = { nav.popIfNotRoot() }) {
                TabContainer {
                    AppLicenseScreen()
                }
            }
        }
        composable("onboarding") { OnboardingRoute(container, nav) }
    }
}

/** Standard chrome for pushed full-screen routes: a quiet back header above the screen. */
@Composable
internal fun PushScreen(onBack: () -> Unit, content: @Composable () -> Unit) {
    // C3: the arrow re-checks this entry's lifecycle at every activation, so a header left on
    // screen by a pop that is still transitioning ignores the activations that follow.
    val guardedBack = dropUnlessResumed(block = onBack)
    Column(modifier = Modifier.fillMaxSize()) {
        IconButton(onClick = guardedBack, modifier = Modifier.padding(start = 8.dp, top = 8.dp)) {
            Icon(
                Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Back",
                modifier = Modifier.size(22.dp),
            )
        }
        content()
    }
}

@Composable
private fun DashboardTab(
    container: AppContainer,
    nav: NavHostController,
    gate: (profileId: String?, action: () -> Unit) -> Unit,
) {
    val homeVm: HomeViewModel = viewModel {
        HomeViewModel(
            status = ConnectionRegistry.status,
            stats = ConnectionRegistry.stats,
            active = container.activeProfile.activeId,
            profiles = container.profiles.observeProfiles(),
            controller = container.tunnelController,
            lastConnected = container.activeProfile.lastConnectedId,
            handshakeState = container.handshakeState,
        )
    }
    val home by homeVm.ui.collectAsStateWithLifecycle()
    // The disc acts on the profile chosen in the pill until another profile goes live (J14);
    // else the live one, then the last-connected one.
    val pick = rememberDashboardPick(home.activeProfile?.id)
    val selected = dashboardTarget(home.profiles, pick.pickedId, home.activeProfile?.id, home.lastConnectedId)
    TabContainer {
        Column(modifier = Modifier.fillMaxSize()) {
            // The scroll state is hoisted so the top bar can derive its scrolled
            // hairline (device review 2026-09-21, C5 finding 13). The boolean is a
            // derivedStateOf read (R7a): reading scroll.value directly recomposed
            // this whole tab once per scroll frame.
            val dashboardScroll = rememberScrollState()
            val dashboardScrolled by remember { derivedStateOf { dashboardScroll.value > 0 } }
            VellumTopBar(
                onOpenSettings = { nav.navigatePush("settings") },
                scrolled = dashboardScrolled,
            )
            DashboardScreen(
                ui = home,
                selected = selected,
                onSelectProfile = pick.pick,
                onToggle = { gate(selected?.id) { homeVm.toggle(selected?.id) } },
                onOpenProfiles = { nav.navigateTab("profiles") },
                scrollState = dashboardScroll,
            )
        }
    }
}

@Composable
private fun ProfilesTab(
    container: AppContainer,
    nav: NavHostController,
    gate: (profileId: String?, action: () -> Unit) -> Unit,
    snackbar: SnackbarHostState,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listVm: ProfileListViewModel = viewModel {
        ProfileListViewModel(
            profiles = container.profiles.observeProfiles(),
            health = container.profiles.observeHealth(), // S-3: at-rest posture + unreadable rows
            status = ConnectionRegistry.status,
            handshakeState = container.handshakeState,
            activeId = container.activeProfile.activeId,
            onSelectActive = { id -> container.tunnelController.toggle(id) },
            onDelete = { id -> container.profiles.delete(id) },
            onDeleteUnreadable = { container.profiles.deleteUnreadable() },
            onDuplicate = { id -> container.duplicateProfile(id) },
            onDuplicateFailed = { message -> snackbar.showSnackbar(message) },
        )
    }
    val list by listVm.ui.collectAsStateWithLifecycle()

    // Add-flow: scan QR straight from this tab; manual entry pushes the blank editor.
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        scope.launch {
            handleQrScanResult(result, snackbar) { text ->
                // In-memory draft: the review editor decides, nothing is saved up front (F18).
                scope.launch {
                    val name = "QR " + java.text.SimpleDateFormat("MMM d HH:mm", java.util.Locale.US)
                        .format(java.util.Date())
                    runCatching { container.parseImport(text, name) }.fold(
                        onSuccess = { profile -> nav.navigatePush("editor?draftId=${Uri.encode(profile.id)}") },
                        onFailure = {
                            snackbar.showSnackbar("That doesn't look like a valid WireGuard config.")
                        },
                    )
                }
            }
        }
    }

    // Share QR: serialize the profile and render its QR code in a dialog.
    var shareQrProfile by remember { mutableStateOf<Profile?>(null) }
    shareQrProfile?.let { profile ->
        ShareQrDialog(profile = profile, onDismiss = { shareQrProfile = null })
    }

    // Export .conf via SAF.
    val exportTarget = remember { mutableStateOf<Profile?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val profile = exportTarget.value
        if (uri != null && profile != null) {
            scope.launch {
                // A row written before the current rules can hold a line break: refuse cleanly
                // instead of crashing the composition (the QR path already guards the same call).
                val text = runCatching { WgConfigSerializer.serialize(profile.toWgQuickConfig()) }.getOrNull()
                if (text == null) {
                    exportTarget.value = null
                    snackbar.showSnackbar("This profile can't be exported.")
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use {
                            it.write(text.toByteArray(Charsets.UTF_8))
                        }
                    }
                }
                exportTarget.value = null
            }
        }
    }

    // R7b: the tab's list state is hoisted here so the top-bar hairline reacts to
    // this tab's scrolling too — the derivedStateOf read keeps the tab from
    // recomposing per scroll frame (R7a).
    val listState = rememberLazyListState()
    val listScrolled by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 }
    }

    TabContainer {
        Column(modifier = Modifier.fillMaxSize()) {
            VellumTopBar(onOpenSettings = { nav.navigatePush("settings") }, scrolled = listScrolled)
            ProfileListScreen(
                ui = list,
                listState = listState,
                onOpenEditor = { id ->
                    nav.navigatePush(if (id == null) "editor" else "editor?profileId=${Uri.encode(id)}")
                },
                onOpenImport = { nav.navigatePush("import") },
                onScanQr = { scanLauncher.launch(wireGuardScanOptions()) },
                onDelete = { id ->
                    // Deleting the connected profile brings the tunnel down first: a live tunnel
                    // behind a deleted row is a refused reconnect waiting to happen (T-2). Only a
                    // live phase counts: a FAILED status also carries the id but has no tunnel.
                    val live = ConnectionRegistry.status.value
                    if (live.profileId == id && live.phase.isLive) {
                        context.startService(
                            Intent(context, VpnTunnelService::class.java)
                                .setAction(VpnTunnelService.ACTION_DISCONNECT),
                        )
                    }
                    val victim = list.items.firstOrNull { it.id == id }
                    listVm.delete(id)
                    if (victim != null) {
                        scope.launch {
                            showDeletedSnackbar(snackbar, victim) { restored ->
                                container.profiles.save(restored)
                            }
                        }
                    }
                },
                onDuplicate = { id -> listVm.duplicate(id) },
                onToggleConnect = { id -> gate(id) { listVm.toggleActive(id) } },
                onShareQr = { id -> list.items.firstOrNull { it.id == id }?.let { shareQrProfile = it } },
                onExport = { id ->
                    list.items.firstOrNull { it.id == id }?.let {
                        exportTarget.value = it
                        exportLauncher.launch("${it.name.ifBlank { "profile" }}.conf")
                    }
                },
                onDeleteUnreadable = { listVm.deleteUnreadable() },
            )
        }
    }
}

/**
 * The delete snackbar (U1, sweep 2 / N2): Long with a close button, so it times out and a
 * second delete's Undo is never queued behind an indefinite first. Undo is offered only for
 * a row the current rules still accept — a legacy row would be gone with an error snackbar.
 */
internal suspend fun showDeletedSnackbar(
    snackbar: SnackbarHostState,
    victim: Profile,
    restore: suspend (Profile) -> Unit,
) {
    if (victim.validate().isNotEmpty()) {
        snackbar.showSnackbar(
            "Deleted \"${victim.name}\"",
            duration = SnackbarDuration.Long,
            withDismissAction = true,
        )
        return
    }
    val result = snackbar.showSnackbar(
        "Deleted \"${victim.name}\"",
        actionLabel = "Undo",
        duration = SnackbarDuration.Long,
        withDismissAction = true,
    )
    if (result == SnackbarResult.ActionPerformed) {
        runCatching { restore(victim) }.onFailure {
            snackbar.showSnackbar(
                profileWriteMessage(it, fallback = "Couldn't restore \"${victim.name}\"."),
                duration = SnackbarDuration.Long,
                withDismissAction = true,
            )
        }
    }
}

@Composable
internal fun ShareQrDialog(profile: Profile, onDismiss: () -> Unit) {
    val configText = remember(profile.id) {
        runCatching { WgConfigSerializer.serialize(profile.toWgQuickConfig()) }.getOrElse { "" }
    }
    ScrimDialog(onDismissRequest = onDismiss) {
        SecureWindow() // the QR encodes the private key: blank screenshots/recents (S-5)
        Text(
            "Share \"${profile.name}\"",
            style = MaterialTheme.typography.headlineLarge,
            color = VellumColors.OnSurface,
        )
        if (configText.isEmpty()) {
            Text("This profile can't be serialized.", color = VellumColors.OnSurfaceVariant)
        } else {
            // C5: a config past QR capacity (2,953 bytes in byte mode at level L) makes the
            // encoder throw; the export advice replaces the code instead of crashing the
            // composition.
            val bitmap = remember(configText) {
                try {
                    qrBitmap(configText)
                } catch (_: WriterException) {
                    null
                }
            }
            if (bitmap == null) {
                Text(
                    "This profile is too large for a QR code. Use Export .conf instead.",
                    color = VellumColors.OnSurfaceVariant,
                )
            } else {
                // Pure-white mat with a 20dp quiet zone around the code (DESIGN.md §3).
                Box(modifier = Modifier.fillMaxWidth().background(Color.White)) {
                    Image(
                        painter = BitmapPainter(bitmap.asImageBitmap()),
                        contentDescription = "QR code for ${profile.name}",
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f).padding(20.dp),
                    )
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            VellumQuietAction("Done", onDismiss)
        }
    }
}

/**
 * QR ink, pinned: sage `#3E5A42` — 7.65:1 on the pure-white mat, the reviewed
 * darkest-sage choice (R4). Light modules stay pure white; pinned by `QrInkTest`.
 */
internal val QR_INK: Int = 0xFF3E5A42.toInt()

/** Decode text into a QR bitmap (zxing core), dark ink on white for scanner contrast. */
private fun qrBitmap(text: String, size: Int = 640): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            pixels[y * size + x] = if (matrix[x, y]) QR_INK else 0xFFFFFFFF.toInt()
        }
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

@Composable
private fun SplitTunnelTab(container: AppContainer, nav: NavHostController) {
    val context = LocalContext.current
    val vm: SplitTunnelViewModel = viewModel {
        SplitTunnelViewModel(
            selections = container.splitTunnelRules.selections,
            // Spec §11 + C4: saving while a tunnel is up restarts it so the new split-tunnel
            // policy applies immediately, and a save during CONNECTING applies as it settles
            // (saveSplitTunnelRules over the app-scoped coordinator).
            save = { saveSplitTunnelRules(container.splitTunnelRules, it, container.settingsRestarts) },
        )
    }
    val appsSource = remember { InstalledAppsSource(context) }
    val iconLoader = remember { PackageManagerAppIconLoader(context) }
    val scope = rememberCoroutineScope()
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(appsSource) { vm.load(withContext(Dispatchers.IO) { appsSource.load(false) }) }
    // R7b: the one-LazyColumn tab's scroll state is hoisted for the top-bar hairline,
    // read through derivedStateOf so scrolling doesn't recompose the tab per frame.
    val listState = rememberLazyListState()
    val listScrolled by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 }
    }
    TabContainer {
        Column(modifier = Modifier.fillMaxSize()) {
            VellumTopBar(onOpenSettings = { nav.navigatePush("settings") }, scrolled = listScrolled)
            SplitTunnelScreen(
                state = state,
                listState = listState,
                iconLoader = iconLoader,
                onMode = vm::setMode,
                onToggle = vm::togglePackage,
                onQuery = vm::setQuery,
                onShowSystem = { show ->
                    vm.setShowSystem(show)
                    scope.launch { vm.load(withContext(Dispatchers.IO) { appsSource.load(show) }) }
                },
                onRouteAll = vm::routeAll,
                onBypassAll = vm::bypassAll,
                onAllowLocalNetwork = vm::setAllowLocalNetwork,
            )
        }
    }
}

@Composable
private fun BackupTab(container: AppContainer, nav: NavHostController) {
    val vm: BackupViewModel = viewModel {
        BackupViewModel(
            profiles = container.profiles.observeProfiles(),
            storedCount = container.profiles.observeStoredCount(),
            existingIds = { container.profiles.storedIds() },
            saveProfiles = { container.profiles.saveAll(it) },
            selections = container.splitTunnelRules.selections,
            saveRules = { saveSplitTunnelRules(container.splitTunnelRules, it, container.settingsRestarts) },
        )
    }
    // R7b: the scroll state is hoisted for the top-bar hairline, read through
    // derivedStateOf so scrolling doesn't recompose the tab per frame.
    val backupScroll = rememberScrollState()
    val backupScrolled by remember { derivedStateOf { backupScroll.value > 0 } }
    TabContainer {
        Column(modifier = Modifier.fillMaxSize()) {
            VellumTopBar(onOpenSettings = { nav.navigatePush("settings") }, scrolled = backupScrolled)
            BackupScreen(vm = vm, onRestored = {}, scrollState = backupScroll)
        }
    }
}

@Composable
private fun EditorRoute(
    container: AppContainer,
    nav: NavHostController,
    profileId: String?,
    draftId: String?,
    entryId: String,
    snackbar: SnackbarHostState,
    noticeScope: CoroutineScope,
) {
    // F19: an import that left search domains out says so once, as its review opens.
    ImportNoticeSnackbar(draftId, container.importNotices, snackbar)
    // F30: the view model, the entry's kept draft and Back live in EditorEntry; this route
    // supplies the stores and the save path.
    EditorEntry(
        nav = nav,
        entryId = entryId,
        profileId = profileId,
        draftId = draftId,
        drafts = container.editorDrafts,
        pendingDrafts = container.pendingDrafts,
        importNotices = container.importNotices,
        snackbar = snackbar,
        noticeScope = noticeScope,
        get = { id ->
            if (draftId != null) container.pendingDrafts[draftId] else id?.let { container.profiles.get(it) }
        },
        saveFn = { profile ->
            saveEditedProfile(
                profile,
                { id -> container.profiles.get(id) },
                { p -> container.profiles.save(p) },
                container.pendingDrafts,
                container.importNotices,
            ) { before, after ->
                restartIfConnectedProfileChanged(before, after, container.settingsRestarts)
            }
        },
    )
}

@Composable
private fun ImportRoute(
    container: AppContainer,
    nav: NavHostController,
    snackbar: SnackbarHostState,
) {
    // A1 boundary: :app owns :core:config, so parse exceptions map to the feature's
    // domain-level ImportOutcome here — :feature:profiles never sees the parser type.
    // The parse lands an in-memory draft (F18): nothing is saved until the editor saves.
    val vm: ImportViewModel = viewModel {
        ImportViewModel { text, name ->
            runCatching { container.parseImport(text, name) }.fold(
                onSuccess = { ImportOutcome.Success(it) },
                onFailure = { ImportOutcome.Failure(importFailureMessage(it)) },
            )
        }
    }
    val scope = rememberCoroutineScope()
    // The review replaces the Import screen, so Back from it lands where the import started (U7).
    val openEditor: (String) -> Unit = { id -> nav.openImportReview(id) }
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        scope.launch {
            handleQrScanResult(result, snackbar) { text ->
                // Timestamped names: every QR import used to land as "Scanned", piling up duplicates.
                val name = "QR " + java.text.SimpleDateFormat("MMM d HH:mm", java.util.Locale.US)
                    .format(java.util.Date())
                vm.importText(text, name) { outcome ->
                    when (outcome) {
                        is ImportOutcome.Success -> openEditor(outcome.profile.id)
                        is ImportOutcome.Failure -> scope.launch {
                            snackbar.showSnackbar(outcome.message ?: "That doesn't look like a valid WireGuard config.")
                        }
                    }
                }
            }
        }
    }
    PushScreen(onBack = { nav.popIfNotRoot() }) {
        ImportScreen(
            vm = vm,
            onImported = openEditor,
            onOpenManual = { nav.navigatePush("editor") },
            onScanQr = { scanLauncher.launch(wireGuardScanOptions()) },
        )
    }
}

/** The automation foreground service hosts both watchers: run it while either rule is on, stop it only when both are off. */
private fun syncGuardService(container: AppContainer, rules: AutoConnectRules) {
    if (rules.needsGuardService) container.startAutoConnectService() else container.stopAutoConnectService()
}

/** System settings screens are implicit targets; an OEM build without one must not crash the app. */
private fun Context.openSystemScreen(intent: Intent) {
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // Nothing on this device handles the target — no such settings screen or browser;
        // the app stays up where the user left it.
    }
}

@Composable
private fun SettingsRoute(container: AppContainer, nav: NavHostController) {
    val context = LocalContext.current
    val vm: SettingsViewModel = viewModel {
        SettingsViewModel(
            container.settingsFlags,
            container.automationRules.rules,
            { key, value -> container.setFlag(key, value) },
            { transform -> container.automationRules.mutate(transform) },
        )
    }
    val automationUi by vm.automationUi.collectAsStateWithLifecycle()
    // Location permission: Android hides the SSID without it, so the guard evaluates blind.
    // The guard needs "Allow all the time": fine alone pauses it off-screen (§3.4). C1's
    // launcher asks for precise and approximate in one system dialog, then for background.
    val requestLocationPermission = rememberLocationPermissionRequest { vm.onPermissionResult(it) }
    // Recompute on entry AND on return: launching a settings screen stops this route
    // without disposing it, so a one-shot effect would leave a stale card on screen after
    // the user grants "Allow all the time" or turns Location on there.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        // The platform verdict, in both directions: a downgrade made in system settings must show.
        vm.onPermissionEntry(permissionStateFor(context))
    }
    var showLocationDisclosure by rememberSaveable { mutableStateOf(false) }
    val flags by vm.ui.collectAsStateWithLifecycle()
    PushScreen(onBack = { nav.popIfNotRoot() }) {
        SettingsScreen(
            ui = flags,
            automation = automationUi,
            appVersion = remember {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
            },
            onOpenKillSwitchGuide = { nav.navigatePush("killswitch") },
            onOpenVpnSettings = { context.openSystemScreen(Intent(Settings.ACTION_VPN_SETTINGS)) },
            onOpenPrivacyPolicy = { nav.navigatePush("privacy") },
            onOpenLicenses = { nav.navigatePush("licenses") },
            onOpenSourceCode = {
                context.openSystemScreen(Intent(Intent.ACTION_VIEW, Uri.parse(VAAYIL_SOURCE_URL)))
            },
            onOpenAppLicense = { nav.navigatePush("app-license") },
            onAutoConnectOnMobileData = { enabled ->
                vm.setAutoConnectOnMobileData(enabled)
                syncGuardService(container, automationUi.rules.copy(autoConnectOnMobileData = enabled))
            },
            onWifiGuard = { enabled ->
                vm.setWifiGuard(enabled)
                if (enabled) {
                    // Prominent disclosure before any system prompt; the rule stays on in
                    // every case — the guard just evaluates blind until access is complete.
                    when (val state = permissionStateFor(context)) {
                        // GRANTED needs nothing; LOCATION_OFF is the platform switch, not a
                        // permission, so there is no system prompt to show (C2).
                        PermissionState.GRANTED, PermissionState.LOCATION_OFF -> vm.onPermissionResult(state)
                        else -> showLocationDisclosure = true
                    }
                }
                syncGuardService(container, automationUi.rules.copy(wifiGuardEnabled = enabled))
            },
            onOpenLocationSettings = {
                // C2: the Location switch is fixed in the system's Location settings; every
                // permission gap is fixed on the app's settings page (C1).
                val destination = if (permissionStateFor(context) == PermissionState.LOCATION_OFF) {
                    Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                } else {
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    )
                }
                context.openSystemScreen(destination)
            },
            onDisconnectOnTrusted = vm::setDisconnectOnTrusted,
            onConnectOnBoot = vm::setConnectOnBoot,
            onAddTrustedSsid = vm::addTrustedSsid,
            onRemoveTrustedSsid = vm::removeTrustedSsid,
            onExternalControl = { enabled ->
                // Two gates, one toggle: the DataStore flag gates broadcasts inside the
                // receiver, the component state gates delivery at the system level.
                vm.setExternalControl(enabled)
                setExternalControlEnabled(context, enabled)
            },
            onRegenerateExternalToken = vm::regenerateExternalToken,
        )
    }
    if (showLocationDisclosure) {
        LocationDisclosureDialog(
            onContinue = {
                showLocationDisclosure = false
                requestLocationPermission()
            },
            onNotNow = { showLocationDisclosure = false },
        )
    }
}

@Composable
private fun OnboardingRoute(container: AppContainer, nav: NavHostController) {
    val context = LocalContext.current
    val vm: SettingsViewModel = viewModel {
        SettingsViewModel(
            container.settingsFlags,
            container.automationRules.rules,
            { key, value -> container.setFlag(key, value) },
            { transform -> container.automationRules.mutate(transform) },
        )
    }
    // C7: POST_NOTIFICATIONS must be requested at runtime on Android 13+, and the last card is
    // the natural moment. The ask shares the root's one-time claim, so whoever gets there
    // first — this card or a later manual connect — asks once in total, and denial degrades
    // gracefully: the tunnel still runs, only the persistent notification is lost.
    val notificationGate = rememberNotificationPermissionGate(context) { container.claimNotificationPermissionRequest() }
    // Battery-optimization state is re-checked on every resume: returning from the
    // system screen is the moment the card should flip to its affirmation.
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    var batteryOptimizationDisabled by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        batteryOptimizationDisabled = powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }
    OnboardingScreen(
        onDone = {
            vm.completeOnboarding()
            nav.navigate("dashboard") {
                popUpTo("onboarding") { inclusive = true }
                launchSingleTop = true
            }
        },
        onOpenBatterySettings = {
            try {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: ActivityNotFoundException) {
                // Rare devices lack the battery settings entry; onboarding still completes.
            }
        },
        batteryOptimizationDisabled = batteryOptimizationDisabled,
        onRequestNotifications = { notificationGate {} },
    )
}
