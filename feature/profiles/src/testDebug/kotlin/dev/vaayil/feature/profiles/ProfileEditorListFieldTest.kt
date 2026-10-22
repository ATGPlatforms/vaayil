package dev.vaayil.feature.profiles

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.model.DnsMode
import dev.vaayil.core.model.DnsPolicy
import dev.vaayil.core.model.DnsSplitRule
import dev.vaayil.core.model.InterfaceConfig
import dev.vaayil.core.model.PeerConfig
import dev.vaayil.core.model.Profile
import dev.vaayil.core.model.SecureDnsType
import dev.vaayil.core.model.validate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * List fields must keep exactly what the user typed (F11): a typed "," or " " used to vanish
 * because the field re-derived its text from the parsed model on every keystroke. The draft is
 * only replaced when the model changes from elsewhere (a scope chip, a load).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class ProfileEditorListFieldTest {
    @get:Rule val compose = createComposeRule()

    private fun profile() = Profile(
        id = "p1",
        name = "Test",
        interfaceConfig = InterfaceConfig("k", listOf("10.5.0.2/32"), listOf("1.1.1.1"), 1420, null),
        peer = PeerConfig("pub", null, "host:51820", listOf("0.0.0.0/0"), null),
    )

    private fun setContent(current: androidx.compose.runtime.MutableState<Profile>) {
        compose.setContent {
            MaterialTheme {
                ProfileEditorScreen(
                    state = EditorState(current.value, current.value.validate()),
                    onField = { f -> current.value = f(current.value) },
                    onSave = {},
                )
            }
        }
    }

    /** Focuses the field by its initial text, clears it, and returns the focused editable
     *  node — the only stable handle once the field's text has been cleared. */
    private fun focusedFieldAfterClearing(initialText: String) =
        compose.onNodeWithText(initialText).performScrollTo().performClick().also {
            compose.onNodeWithText(initialText).performTextClearance()
        }.let { compose.onNode(hasSetTextAction() and isFocused()) }

    @Test
    fun `typing a second address keeps the separator and yields two entries`() {
        val current = androidx.compose.runtime.mutableStateOf(profile())
        setContent(current)
        val field = focusedFieldAfterClearing("10.5.0.2/32")
        field.performTextInput("10.5.0.2/32")
        field.performTextInput(",")
        field.performTextInput(" fd00::2/128")
        compose.onNodeWithText("10.5.0.2/32, fd00::2/128").performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf("10.5.0.2/32", "fd00::2/128"), current.value.interfaceConfig.addresses)
        }
    }

    @Test
    fun `a trailing comma stays while the next entry is typed`() {
        val current = androidx.compose.runtime.mutableStateOf(profile())
        setContent(current)
        val field = focusedFieldAfterClearing("10.5.0.2/32")
        field.performTextInput("10.5.0.2/32,")
        compose.onNodeWithText("10.5.0.2/32,").performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf("10.5.0.2/32"), current.value.interfaceConfig.addresses)
        }
    }

    @Test
    fun `a scope chip replaces the allowed-ips draft`() {
        val current = androidx.compose.runtime.mutableStateOf(profile())
        setContent(current)
        val field = focusedFieldAfterClearing("0.0.0.0/0")
        field.performTextInput("192.168.1.0/24")
        compose.onNodeWithText("Route everything").performScrollTo().performClick()
        compose.onNodeWithText("0.0.0.0/0, ::/0").performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf("0.0.0.0/0", "::/0"), current.value.peer.allowedIps)
        }
    }

    @Test
    fun `a whole line pasted at once keeps its text and both entries`() {
        val current = androidx.compose.runtime.mutableStateOf(profile())
        setContent(current)
        val field = focusedFieldAfterClearing("10.5.0.2/32")
        field.performTextInput("10.0.0.2/32, fd00::2/128")
        compose.onNodeWithText("10.0.0.2/32, fd00::2/128").performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf("10.0.0.2/32", "fd00::2/128"), current.value.interfaceConfig.addresses)
        }
    }

    @Test
    fun `a comma typed then deleted leaves one entry`() {
        val current = androidx.compose.runtime.mutableStateOf(profile())
        setContent(current)
        val field = focusedFieldAfterClearing("10.5.0.2/32")
        field.performTextInput("10.5.0.2/32,")
        compose.onNodeWithText("10.5.0.2/32,").performTextReplacement("10.5.0.2/32")
        compose.onNodeWithText("10.5.0.2/32").performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf("10.5.0.2/32"), current.value.interfaceConfig.addresses)
        }
    }

    @Test
    fun `dns servers are editable as a list`() {
        val current = androidx.compose.runtime.mutableStateOf(profile())
        setContent(current)
        val field = focusedFieldAfterClearing("1.1.1.1")
        field.performTextInput("1.1.1.1, 9.9.9.9")
        compose.onNodeWithText("1.1.1.1, 9.9.9.9").performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf("1.1.1.1", "9.9.9.9"), current.value.interfaceConfig.dnsServers)
        }
    }

    @Test
    fun `an invalid dns server shows the validator message`() {
        val current = androidx.compose.runtime.mutableStateOf(profile())
        setContent(current)
        val field = focusedFieldAfterClearing("1.1.1.1")
        field.performTextInput("dns.example")
        // Errors surface only on touched fields: move focus away so the DNS field shows them.
        compose.onNodeWithText("10.5.0.2/32").performScrollTo().performClick()
        compose.onNodeWithText("DNS server \"dns.example\" must be an IP address.")
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `split-dns rules without a dns server say they are ignored`() {
        val current = androidx.compose.runtime.mutableStateOf(
            profile().copy(
                interfaceConfig = profile().interfaceConfig.copy(dnsServers = emptyList()),
                dns = DnsPolicy(rules = listOf(DnsSplitRule("corp.example", bypass = true))),
            ),
        )
        setContent(current)
        compose.onNodeWithText("Split-DNS rules take effect only when this profile has a DNS server.")
            .performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            current.value = current.value.copy(
                interfaceConfig = current.value.interfaceConfig.copy(dnsServers = listOf("10.0.0.1")),
            )
        }
        compose.onAllNodesWithText("Split-DNS rules take effect only when this profile has a DNS server.")
            .assertCountEquals(0)
    }

    @Test
    fun `split-dns rules under secure dns need no plain server`() {
        val current = androidx.compose.runtime.mutableStateOf(
            profile().copy(
                interfaceConfig = profile().interfaceConfig.copy(dnsServers = emptyList()),
                dns = DnsPolicy(
                    mode = DnsMode.SECURE,
                    secureType = SecureDnsType.DOH,
                    secureEndpoint = "https://9.9.9.9/dns-query",
                    rules = listOf(DnsSplitRule("corp.example", bypass = true)),
                ),
            ),
        )
        setContent(current)
        compose.onAllNodesWithText("Split-DNS rules take effect only when this profile has a DNS server.")
            .assertCountEquals(0)
    }
}
