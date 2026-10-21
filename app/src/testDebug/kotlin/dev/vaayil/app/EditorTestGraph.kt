package dev.vaayil.app

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import dev.vaayil.core.model.InterfaceConfig
import dev.vaayil.core.model.PeerConfig
import dev.vaayil.core.model.Profile
import dev.vaayil.feature.profiles.EditorDraft

/** A valid stored profile for the editor tests. */
internal val savedHome = Profile(
    "p1",
    "Home",
    InterfaceConfig("eCyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy0=", listOf("10.0.0.2/32"), listOf("1.1.1.1"), null, null),
    PeerConfig("KyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyU=", null, "vpn.example.com:51820", listOf("0.0.0.0/0"), null),
)

/**
 * The editor's slice of `RootNavGraph`: stand-ins for the tabs, onboarding and the Import
 * screen, and the real [EditorEntry] under the real route, reading [savedHome] and the given
 * import drafts. [startDestination] picks the tab, or onboarding, the graph starts on. Saving
 * does nothing. Notices show in a snackbar host, as in the app.
 */
@Composable
internal fun EditorTestGraph(
    nav: NavHostController,
    drafts: MutableMap<String, EditorDraft>,
    pendingDrafts: MutableMap<String, Profile>,
    importNotices: MutableMap<String, String>,
    startDestination: String = "dashboard",
) {
    val snackbar = remember { SnackbarHostState() }
    val noticeScope = rememberCoroutineScope()
    Box {
        NavHost(navController = nav, startDestination = startDestination) {
            composable("dashboard") { Text("Dashboard tab") }
            composable("onboarding") { Text("Onboarding") }
            composable("profiles") { Text("Profiles tab") }
            composable("import") { Text("Import screen") }
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
                val draftId = entry.arguments?.getString("draftId")
                EditorEntry(
                    nav = nav,
                    entryId = entry.id,
                    profileId = entry.arguments?.getString("profileId"),
                    draftId = draftId,
                    drafts = drafts,
                    pendingDrafts = pendingDrafts,
                    importNotices = importNotices,
                    snackbar = snackbar,
                    noticeScope = noticeScope,
                    get = { id -> if (draftId != null) pendingDrafts[draftId] else savedHome.takeIf { it.id == id } },
                    saveFn = {},
                )
            }
        }
        SnackbarHost(snackbar)
    }
}
