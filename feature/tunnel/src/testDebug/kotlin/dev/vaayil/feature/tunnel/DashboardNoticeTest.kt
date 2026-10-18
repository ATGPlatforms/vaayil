package dev.vaayil.feature.tunnel

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.tunnel.api.ConnectionPhase
import dev.vaayil.core.tunnel.api.TunnelNotice
import dev.vaayil.core.tunnel.api.TunnelStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** U4: the dashboard note for a live session's notices; renders nothing otherwise. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class DashboardNoticeTest {
    @get:Rule val compose = createComposeRule()

    private fun state(phase: ConnectionPhase, notice: TunnelNotice?) =
        HomeUiState(status = TunnelStatus(phase, "a", null, notice))

    @Test
    fun `settings-not-applied shows its note while connected`() {
        compose.setContent { DashboardNotice(state(ConnectionPhase.CONNECTED, TunnelNotice.SettingsNotApplied)) }
        compose.onNodeWithText("New settings aren't applied yet. Reconnect to apply them.").assertIsDisplayed()
    }

    @Test
    fun `dns-not-answering shows its note while connected`() {
        compose.setContent { DashboardNotice(state(ConnectionPhase.CONNECTED, TunnelNotice.DnsNotAnswering)) }
        compose.onNodeWithText("DNS isn't answering. Reconnect to fix it.").assertIsDisplayed()
    }

    @Test
    fun `no note without a notice or outside connected`() {
        // One composition for both cases: the rule refuses a second setContent per test.
        compose.setContent {
            Column {
                DashboardNotice(state(ConnectionPhase.CONNECTED, null))
                DashboardNotice(state(ConnectionPhase.FAILED, TunnelNotice.SettingsNotApplied))
            }
        }
        compose.onNodeWithText("New settings aren't applied yet. Reconnect to apply them.").assertDoesNotExist()
        compose.onNodeWithText("DNS isn't answering. Reconnect to fix it.").assertDoesNotExist()
    }
}
