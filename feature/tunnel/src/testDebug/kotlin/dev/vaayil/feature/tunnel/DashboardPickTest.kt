package dev.vaayil.feature.tunnel

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class DashboardPickTest {
    @get:Rule val compose = createComposeRule()

    private val liveId = mutableStateOf<String?>("a")
    private val compositions = mutableListOf<Pair<String?, String?>>()
    private var currentPick: DashboardPick? = null

    @Composable
    private fun Host() {
        val currentLiveId = liveId.value
        val pick = rememberDashboardPick(currentLiveId)
        SideEffect {
            compositions += currentLiveId to pick.pickedId
            currentPick = pick
        }
    }

    private fun show() {
        compose.setContent { Host() }
        compose.waitForIdle()
    }

    private fun pick(id: String?) {
        compose.runOnIdle { currentPick!!.pick(id) }
        compose.waitForIdle()
    }

    private fun setLive(id: String?) {
        compose.runOnIdle { liveId.value = id }
        compose.waitForIdle()
    }

    @Test
    fun `the pick gives way when another profile goes live, from that composition on`() {
        show()
        pick("b")
        compositions.clear()

        setLive("c")

        val afterChange = compositions.filter { it.first == "c" }
        assertTrue("no composition recorded for the new live profile", afterChange.isNotEmpty())
        assertTrue("a stale pick was rendered: $afterChange", afterChange.all { it.second == null })
        assertNull(currentPick?.pickedId)
    }

    @Test
    fun `a disconnect and a switch to the pick keep it`() {
        show()
        pick("b")

        setLive(null)
        assertEquals("b", currentPick?.pickedId)
        setLive("b")
        assertEquals("b", currentPick?.pickedId)
    }

    @Test
    fun `a dropped pick stays dropped when the earlier profile comes back`() {
        show()
        pick("b")

        setLive("c")
        assertNull(currentPick?.pickedId)
        setLive("a")
        assertNull(currentPick?.pickedId)
    }

    @Test
    fun `the pick survives recreation`() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Host() }
        compose.waitForIdle()
        pick("b")

        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()

        assertEquals("b", currentPick?.pickedId)
    }
}
