package dev.vaayil.app

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import dev.vaayil.feature.profiles.AppInfo

/**
 * G3 capture determinism (final review): a null loader still hops Dispatchers.IO →
 * Dispatchers.Main before a row's initial renders, and waitForIdle does not track a
 * coroutine parked on the IO pool. Wait until every composed app row shows its initial —
 * counted in the unmerged tree, where the initial is its own Text node — so a capture or a
 * measurement never races the empty 36dp box. The StoreAssetsTest.awaitDemoIcons precedent,
 * for the fixtures that inject a null loader.
 */
internal fun ComposeTestRule.awaitVisibleAppInitials(apps: List<AppInfo>) {
    val initials = apps.map { it.label.take(1).uppercase() }.toSet()
    waitUntil(10_000) {
        val visibleRows = apps.count { app ->
            onAllNodesWithText(app.packageName).fetchSemanticsNodes().isNotEmpty()
        }
        val shownInitials = initials.sumOf { initial ->
            onAllNodesWithText(initial, useUnmergedTree = true).fetchSemanticsNodes().size
        }
        visibleRows > 0 && shownInitials >= visibleRows
    }
    waitForIdle()
}
