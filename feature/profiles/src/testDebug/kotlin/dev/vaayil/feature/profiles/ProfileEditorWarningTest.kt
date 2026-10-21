package dev.vaayil.feature.profiles

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.model.DnsMode
import dev.vaayil.core.model.DnsPolicy
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
 * T-1 (spec U9): addresses in no family the Allowed IPs route get a warning at the end of the
 * [Peer] sheet. It is advice, not an error, so Save stays enabled.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class ProfileEditorWarningTest {
    @get:Rule val compose = createComposeRule()

    private val mismatch = "This profile's addresses don't match its Allowed IPs, so it won't carry connections."
    private val dnsBypass = "DNS lookups will bypass this tunnel. Add a DNS server to keep them in."

    private fun profile(addresses: List<String>, dnsServers: List<String> = listOf("1.1.1.1")) = Profile(
        id = "p1",
        name = "Test",
        interfaceConfig = InterfaceConfig(
            privateKey = "eCyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy0=",
            addresses = addresses, dnsServers = dnsServers,
            mtu = 1420, listenPort = null,
        ),
        peer = PeerConfig(
            publicKey = "KyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyU=",
            presharedKey = null, endpoint = "198.51.100.7:51820",
            allowedIps = listOf("0.0.0.0/0"), persistentKeepalive = 25,
        ),
    )

    private fun setContent(current: MutableState<Profile>, onSave: () -> Unit = {}) {
        compose.setContent {
            MaterialTheme {
                ProfileEditorScreen(
                    state = EditorState(current.value, current.value.validate()),
                    onField = { f -> current.value = f(current.value) },
                    onSave = onSave,
                )
            }
        }
    }

    @Test
    fun `mismatched families warn and save stays enabled`() {
        var saves = 0
        setContent(mutableStateOf(profile(listOf("fd00::2/128"))), onSave = { saves++ })
        compose.onNodeWithText(mismatch).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Save Profile").performScrollTo().assert(isEnabled()).performClick()
        compose.runOnIdle { assertEquals(1, saves) }
    }

    @Test
    fun `routing the address family clears the warning`() {
        setContent(mutableStateOf(profile(listOf("fd00::2/128"))))
        compose.onNodeWithText(mismatch).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Route everything").performScrollTo().performClick()
        compose.onNodeWithText(mismatch).assertDoesNotExist()
    }

    @Test
    fun `matching families show no warning`() {
        setContent(mutableStateOf(profile(listOf("10.0.0.2/32"))))
        compose.onNodeWithText(mismatch).assertDoesNotExist()
    }

    private val hostnameHint =
        "A hostname DNS server is looked up through your current network's resolver when the tunnel comes up, " +
            "and again after a network change or a failure."

    private fun secureDoh(endpoint: String = "https://cloudflare-dns.com/dns-query") = DnsPolicy(
        mode = DnsMode.SECURE,
        secureType = SecureDnsType.DOH,
        secureEndpoint = endpoint,
    )

    @Test
    fun `a hostname secure endpoint shows the lookup hint`() {
        setContent(mutableStateOf(profile(listOf("10.0.0.2/32")).copy(dns = secureDoh())))
        compose.onNodeWithText(hostnameHint).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `an IP-literal secure endpoint shows no lookup hint`() {
        setContent(mutableStateOf(profile(listOf("10.0.0.2/32")).copy(dns = secureDoh("https://1.1.1.1/dns-query"))))
        compose.onNodeWithText(hostnameHint).assertDoesNotExist()
    }

    @Test
    fun `a full tunnel with no DNS server shows the DNS note until a server is added`() {
        val current = mutableStateOf(profile(listOf("10.0.0.2/32"), dnsServers = emptyList()))
        setContent(current)
        compose.onNodeWithText(dnsBypass).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Save Profile").performScrollTo().assert(isEnabled())

        compose.runOnIdle {
            current.value = current.value.copy(
                interfaceConfig = current.value.interfaceConfig.copy(dnsServers = listOf("10.0.0.1")),
            )
        }
        compose.onAllNodesWithText(dnsBypass).assertCountEquals(0)
    }
}
