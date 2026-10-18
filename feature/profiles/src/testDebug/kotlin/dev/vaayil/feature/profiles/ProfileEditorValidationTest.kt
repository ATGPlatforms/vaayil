package dev.vaayil.feature.profiles

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
 * The editor says why Save is off (F14): reserved-address errors reach the Addresses
 * field, split-DNS drafts are validated before they can be added, an untouched invalid
 * profile still names its blockers under Save, and Secure DNS comes on as DoH (F15).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class ProfileEditorValidationTest {
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

    @Test
    fun `a reserved address shows its message under addresses`() {
        val current = androidx.compose.runtime.mutableStateOf(profile())
        setContent(current)
        compose.onNodeWithText("10.5.0.2/32").performScrollTo().performClick()
        compose.onNodeWithText("10.5.0.2/32").performTextReplacement("10.111.222.3/32")
        compose.onNodeWithText("Interface address 10.111.222.3 is reserved for Vaayil's DNS resolver.")
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `an invalid split-dns draft cannot be added`() {
        val current = androidx.compose.runtime.mutableStateOf(profile())
        setContent(current)
        // The Domain draft field starts empty and its label lives above the well, so the
        // editable node is the only composed empty text field.
        val domain = compose.onNode(hasSetTextAction() and hasText("")).performScrollTo()
        domain.performClick()
        domain.performTextInput("not a domain!")
        compose.onNodeWithText("Split-DNS domain \"not a domain!\" is not a valid hostname.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Add rule").performScrollTo().assert(isNotEnabled())
        compose.runOnIdle {
            assertEquals(emptyList<DnsSplitRule>(), current.value.dns.rules)
        }
    }

    @Test
    fun `a duplicate split-dns draft cannot be added`() {
        val current = androidx.compose.runtime.mutableStateOf(
            profile().copy(dns = DnsPolicy(rules = listOf(DnsSplitRule("corp.example", bypass = true)))),
        )
        setContent(current)
        val domain = compose.onNode(hasSetTextAction() and hasText("")).performScrollTo()
        domain.performClick()
        domain.performTextInput("CORP.example")
        compose.onNodeWithText("Duplicate split-DNS domain \"CORP.example\".")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Add rule").performScrollTo().assert(isNotEnabled())
        compose.runOnIdle {
            assertEquals(listOf("corp.example"), current.value.dns.rules.map { it.domain })
        }
    }

    @Test
    fun `a blank profile lists what save needs`() {
        val current = androidx.compose.runtime.mutableStateOf(NewProfileFactory.blank())
        setContent(current)
        compose.onNodeWithText("To save, fix: Profile name, Private key, Endpoint, Server public key, Allowed IPs")
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a split-dns rule flips between resolvers`() {
        val current = androidx.compose.runtime.mutableStateOf(
            profile().copy(
                interfaceConfig = InterfaceConfig("k", listOf("10.5.0.2/32"), listOf("10.0.0.1"), 1420, null),
                dns = DnsPolicy(rules = listOf(DnsSplitRule("corp.example", bypass = true))),
            ),
        )
        setContent(current)
        compose.onNodeWithText("Bypass resolver").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(false, current.value.dns.rules.single().bypass)
        }
        compose.onNodeWithText("Tunnel resolver").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the resolver flip names its action for the tapped rule only`() {
        val current = androidx.compose.runtime.mutableStateOf(
            profile().copy(
                interfaceConfig = InterfaceConfig("k", listOf("10.5.0.2/32"), listOf("10.0.0.1"), 1420, null),
                dns = DnsPolicy(
                    rules = listOf(DnsSplitRule("a.corp", bypass = true), DnsSplitRule("b.corp", bypass = false)),
                ),
            ),
        )
        setContent(current)
        // F9/F20: TalkBack must name the domain the action touches, not just the state.
        compose.onNodeWithText("Bypass resolver").performScrollTo()
        val clickLabel = compose.onNodeWithText("Bypass resolver")
            .fetchSemanticsNode().config[SemanticsActions.OnClick]?.label
        assertEquals("Use the tunnel resolver for a.corp", clickLabel)
        compose.onNodeWithText("Bypass resolver").performClick()
        compose.runOnIdle {
            assertEquals(false, current.value.dns.rules.first { it.domain == "a.corp" }.bypass)
            // Only the tapped rule flips.
            assertEquals(false, current.value.dns.rules.first { it.domain == "b.corp" }.bypass)
        }
    }

    @Test
    fun `turning secure dns on selects doh`() {
        val current = androidx.compose.runtime.mutableStateOf(profile())
        setContent(current)
        // R4: the pill option, not the "Secure DNS" heading above it.
        compose.onNode(hasText("Secure DNS") and isSelectable()).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(DnsMode.SECURE, current.value.dns.mode)
            assertEquals(SecureDnsType.DOH, current.value.dns.secureType)
        }
    }
}
