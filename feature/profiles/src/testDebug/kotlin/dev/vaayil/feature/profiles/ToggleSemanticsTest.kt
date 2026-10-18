package dev.vaayil.feature.profiles

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.model.InterfaceConfig
import dev.vaayil.core.model.PeerConfig
import dev.vaayil.core.model.Profile
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * F9/F20a: the profiles row switch, split-tunnel app rows and the editor's scope
 * chips each announce their label and state to screen readers; one toggleable focus
 * stop per row; "Split subnets" is a real selected preset covering all three
 * RFC 1918 ranges.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class ToggleSemanticsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `the profile row switch is labelled with the profile name`() {
        val beta = Profile(
            id = "beta",
            name = "Beta",
            interfaceConfig = InterfaceConfig("k", listOf("10.0.0.2/32"), listOf("1.1.1.1"), 1420, null),
            peer = PeerConfig("pub", null, "host:51820", listOf("0.0.0.0/0"), null),
        )
        compose.setContent {
            MaterialTheme {
                ProfileListScreen(
                    ui = ProfileListUiState(items = listOf(beta)),
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
        compose.onNode(isToggleable() and hasContentDescription("Beta"))
            .performScrollTo().assertIsOff()
    }

    @Test
    fun `each split tunnel app row is one toggleable focus stop`() {
        compose.setContent {
            MaterialTheme {
                SplitTunnelScreen(
                    state = SplitTunnelUiState(apps = listOf(AppInfo("com.example.maps", "Maps"))),
                    onMode = {},
                    onToggle = {},
                    onQuery = {},
                    onShowSystem = {},
                    onRouteAll = {},
                    onBypassAll = {},
                )
            }
        }
        compose.onNode(hasText("Maps") and isToggleable())
            .performScrollTo().assertIsOff()
        // The row AND its switch used to be separately clickable — two focus stops. A
        // label-less toggleable Switch (the F9 two-stop regression) must never come back
        // inside the row: one focus stop per app means the only toggleable carries the text.
        compose.onAllNodes(isToggleable() and SemanticsMatcher.keyNotDefined(SemanticsProperties.Text))
            .assertCountEquals(0)
    }

    @Test
    fun `scope chips are selectable and split subnets covers all three RFC 1918 ranges`() {
        val current = androidx.compose.runtime.mutableStateOf(
            Profile(
                id = "p1",
                name = "Test",
                interfaceConfig = InterfaceConfig("k", listOf("10.0.0.2/32"), listOf("1.1.1.1"), 1420, null),
                peer = PeerConfig("pub", null, "host:51820", listOf("0.0.0.0/0", "::/0"), null),
            ),
        )
        compose.setContent {
            MaterialTheme {
                ProfileEditorScreen(
                    state = EditorState(current.value, emptyList()),
                    onField = { f -> current.value = f(current.value) },
                    onSave = {},
                )
            }
        }
        compose.onNode(hasText("Route all traffic") and isSelectable())
            .performScrollTo().assertIsSelected()
        compose.onNode(hasText("Split subnets") and isSelectable()).assertIsNotSelected()
        compose.onNode(hasText("Split subnets") and isSelectable()).performClick()
        assertEquals(
            listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"),
            current.value.peer.allowedIps,
        )
        compose.onNode(hasText("Split subnets") and isSelectable()).assertIsSelected()
    }
}
