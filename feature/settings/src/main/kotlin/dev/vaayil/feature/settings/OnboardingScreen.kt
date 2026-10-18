package dev.vaayil.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.vaayil.core.ui.Films
import dev.vaayil.core.ui.Sheet
import dev.vaayil.core.ui.VellumButton
import dev.vaayil.core.ui.VellumColors
import dev.vaayil.core.ui.VellumQuietAction
import kotlinx.coroutines.launch

private val ONBOARDING_PAGES: List<Pair<ImageVector, String>> = listOf(
    Icons.Rounded.Home to "Vaayil connects to VPN servers you already have",
    Icons.Rounded.Info to "Android will ask you to allow VPN connections — this is normal",
    Icons.Rounded.Warning to "Disable battery optimization for Vaayil so the tunnel stays up",
)

/**
 * Onboarding (spec §3.5): each step is a sheet laid over the previous with a 4dp
 * overlap offset — the completed steps stay visible as paper edges peeking out
 * above the current sheet, seams showing. One 52dp primary per step; `Skip` and
 * "I've done this" are quiet actions. The battery step's heading keeps its copy
 * exactly; under it, until optimization is off, one line says how to find Vaayil in
 * the system list the button opens (F26).
 */
@Composable
fun OnboardingScreen(
    onDone: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    batteryOptimizationDisabled: Boolean,
    onRequestNotifications: () -> Unit = {},
) {
    val pagerState = rememberPagerState(pageCount = { ONBOARDING_PAGES.size })
    val scope = rememberCoroutineScope()
    // The last page is the notification/battery card: reaching it is the natural moment to
    // ask for POST_NOTIFICATIONS (Android 13+), so the tunnel notification - the only
    // out-of-app disconnect - can appear once onboarding completes.
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage == ONBOARDING_PAGES.lastIndex) onRequestNotifications()
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
            // A step taller than the window scrolls (large text, F29). The scroll passes the
            // page height down as a minimum height, so a step that fits stays centred.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                StepSheetStack(
                    page = page,
                    isLast = page == ONBOARDING_PAGES.lastIndex,
                    batteryOptimizationDisabled = batteryOptimizationDisabled,
                    onDone = onDone,
                    onOpenBatterySettings = onOpenBatterySettings,
                    onNext = {
                        scope.launch { pagerState.animateScrollToPage(page + 1) }
                    },
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(ONBOARDING_PAGES.size) { index ->
                val active = pagerState.currentPage == index
                Box(
                    modifier = Modifier
                        .padding(4.dp)
                        .size(if (active) 10.dp else 8.dp)
                        .clip(CircleShape)
                        .background(if (active) VellumColors.Primary else VellumColors.Hairline),
                )
            }
        }
    }
}

/**
 * The layered sheets for one page: every completed step stays as a thin strip
 * under the current sheet, each offset 4dp up the stack, so the paper visibly
 * accumulates as the user advances. The bottom padding absorbs the offset so the
 * stack never clips against the pager.
 */
@Composable
private fun StepSheetStack(
    page: Int,
    isLast: Boolean,
    batteryOptimizationDisabled: Boolean,
    onDone: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onNext: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = (page * 4).dp),
    ) {
        repeat(page) { earlier ->
            // Completed step: a paper edge peeking 4dp above the sheet laid over it.
            Sheet(
                films = Films.One,
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(y = (earlier * 4).dp),
                contentPadding = 6.dp,
            ) {}
        }
        Sheet(
            films = Films.One,
            modifier = Modifier
                .fillMaxWidth()
                .offset(y = (page * 4).dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Icon(
                    ONBOARDING_PAGES[page].first,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = VellumColors.Primary,
                )
                Text(
                    ONBOARDING_PAGES[page].second,
                    style = MaterialTheme.typography.displaySmall,
                    textAlign = TextAlign.Center,
                    color = VellumColors.OnSurface,
                )
                if (isLast) {
                    if (batteryOptimizationDisabled) {
                        // Returning from the system screen confirms nothing by itself; show
                        // the exemption in hand so the user knows the change took (P2). The
                        // prompt primary is replaced by the affirmation — "one primary per
                        // step" never meant "always render one".
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = VellumColors.Primary,
                            )
                            Text(
                                "Battery optimization is off — Vaayil can keep a stable " +
                                    "background connection.",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    } else {
                        // The button opens the system's list of apps, not a Vaayil page: the
                        // per-app dialog needs a Play-restricted permission (F26).
                        Text(
                            "Android opens a list of apps. Switch the list to All apps, tap Vaayil, " +
                                "then choose Don't optimize.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = VellumColors.OnSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        VellumButton(
                            label = "Open battery settings",
                            onClick = onOpenBatterySettings,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    VellumQuietAction(
                        "I've done this — get started",
                        onDone,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        VellumButton(label = "Next", onClick = onNext)
                        Spacer(Modifier.width(16.dp))
                        VellumQuietAction("Skip", onDone)
                    }
                }
            }
        }
    }
}
