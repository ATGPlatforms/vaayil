package dev.vaayil.feature.profiles

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.font.FontFamily
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.model.InterfaceConfig
import dev.vaayil.core.model.PeerConfig
import dev.vaayil.core.model.Profile
import dev.vaayil.core.model.validate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * T-8 (spec §3.3): the editor's Private DNS note sits directly under the DNS sheet's header,
 * names the host in monospace inside one sentence, and renders only while a host is known.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h2000dp")
class ProfileEditorPrivateDnsTest {
    @get:Rule val compose = createComposeRule()

    private fun profile() = Profile(
        id = "p1",
        name = "Test",
        interfaceConfig = InterfaceConfig(
            privateKey = "eCyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy0=",
            addresses = listOf("10.0.0.2/32"), dnsServers = listOf("1.1.1.1"),
            mtu = 1420, listenPort = null,
        ),
        peer = PeerConfig(
            publicKey = "KyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyU=",
            presharedKey = null, endpoint = "198.51.100.7:51820",
            allowedIps = listOf("0.0.0.0/0"), persistentKeepalive = 25,
        ),
    )

    private fun setContent(privateDnsHost: String?) {
        val current = mutableStateOf(profile())
        compose.setContent {
            MaterialTheme {
                ProfileEditorScreen(
                    state = EditorState(current.value, current.value.validate()),
                    onField = { f -> current.value = f(current.value) },
                    onSave = {},
                    privateDnsHost = privateDnsHost,
                )
            }
        }
    }

    @Test
    fun `the note names the host between the DNS header and Secure DNS`() {
        setContent(privateDnsHost = "dns.google")
        compose.onNodeWithText(NOTE).assertIsDisplayed()
        val header = compose.onNodeWithText("DNS").fetchSemanticsNode().boundsInRoot
        val note = compose.onNodeWithText(NOTE).fetchSemanticsNode().boundsInRoot
        assertTrue("note starts at ${note.top}, the DNS header ends at ${header.bottom}", note.top >= header.bottom)
        val secureTops = compose.onAllNodesWithText("Secure DNS").fetchSemanticsNodes()
            .map { it.boundsInRoot.top }
        assertTrue("note ends at ${note.bottom}, Secure DNS starts at ${secureTops.min()}", note.bottom <= secureTops.min())
    }

    @Test
    fun `the host renders in monospace`() {
        setContent(privateDnsHost = "dns.google")
        val text = compose.onNodeWithText(NOTE).fetchSemanticsNode().config[SemanticsProperties.Text].single()
        val mono = text.spanStyles.filter { it.item.fontFamily == FontFamily.Monospace }
        assertEquals(1, mono.size)
        assertEquals("dns.google", text.text.substring(mono.single().start, mono.single().end))
    }

    @Test
    fun `no note without a host`() {
        setContent(privateDnsHost = null)
        compose.onNodeWithText("DNS").assertExists() // positive control: the DNS sheet is on screen
        compose.onNodeWithText("Private DNS", substring = true).assertDoesNotExist()
    }

    private companion object {
        const val NOTE = "Android's Private DNS is set to dns.google. While it is, apps' DNS lookups skip this " +
            "profile's DNS servers, Secure DNS and split-DNS rules."
    }
}
