package dev.vaayil.app

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.model.DnsMode
import dev.vaayil.core.model.DnsPolicy
import dev.vaayil.core.model.InterfaceConfig
import dev.vaayil.core.model.PeerConfig
import dev.vaayil.core.model.Profile
import dev.vaayil.core.model.SecureDnsType
import dev.vaayil.core.tunnel.api.ConnectionPhase
import dev.vaayil.core.tunnel.api.HandshakeState
import dev.vaayil.core.ui.LocalReducedMotion
import dev.vaayil.feature.profiles.ProfileListScreen
import dev.vaayil.feature.profiles.ProfileListUiState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The DNS coverage notes (#15) fit the two lines the warning block allows in a 360 dp saved row
 * and in the hero card. They run inside the app's own [VaayilTheme], so `bodySmall` is the
 * production 13sp Schibsted mapping (DESIGN.md): a feature test would need a copy of it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfileWarningFitTest {
    @get:Rule val compose = createComposeRule()

    private val dnsServerOutside = "DNS lookups will bypass this tunnel. Its DNS server isn't in Allowed IPs."
    private val dnsServersOutside = "DNS lookups will bypass this tunnel. Its DNS servers aren't in Allowed IPs."
    private val firstDnsServerOutside =
        "DNS lookups will bypass this tunnel. Its first DNS server isn't in Allowed IPs."
    private val laterDnsServerOutside =
        "DNS lookups may bypass this tunnel. One DNS server isn't in Allowed IPs."
    private val laterDnsServersOutside =
        "DNS lookups may bypass this tunnel. Some DNS servers aren't in Allowed IPs."
    private val secureServerOutside = "Secure DNS will bypass this tunnel. Its server isn't in Allowed IPs."
    private val secureHostnameUncovered =
        "Secure DNS will bypass this tunnel unless its server is in Allowed IPs."

    private fun profile(
        id: String,
        name: String,
        address: String,
        dnsServers: List<String> = listOf("1.1.1.1"),
        allowedIps: List<String> = listOf("0.0.0.0/0"),
        dns: DnsPolicy = DnsPolicy(),
    ) = Profile(
        id = id, name = name,
        interfaceConfig = InterfaceConfig(
            privateKey = "k",
            addresses = listOf(address), dnsServers = dnsServers,
            mtu = 1420, listenPort = null,
        ),
        peer = PeerConfig(
            publicKey = "K",
            presharedKey = null, endpoint = "vpn.example.com:51820",
            allowedIps = allowedIps, persistentKeepalive = 25,
        ),
        dns = dns,
    )

    private fun show(ui: () -> ProfileListUiState) {
        compose.setContent {
            VaayilTheme.Content {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    ProfileListScreen(
                        ui = ui(),
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
        }
        compose.waitForIdle()
    }

    private fun coverageFixtures(): List<Pair<Profile, String>> {
        val allowedIps = listOf("198.51.100.0/24")
        fun secure(endpoint: String) =
            DnsPolicy(mode = DnsMode.SECURE, secureType = SecureDnsType.DOH, secureEndpoint = endpoint)
        return listOf(
            profile(
                "one", "One", "198.51.100.2/24",
                dnsServers = listOf("192.0.2.53"), allowedIps = allowedIps,
            ) to dnsServerOutside,
            profile(
                "many", "Many", "198.51.100.2/24",
                dnsServers = listOf("192.0.2.53", "192.0.2.54"), allowedIps = allowedIps,
            ) to dnsServersOutside,
            profile(
                "first", "First", "198.51.100.2/24",
                dnsServers = listOf("192.0.2.53", "198.51.100.53"), allowedIps = allowedIps,
            ) to firstDnsServerOutside,
            profile(
                "later", "Later", "198.51.100.2/24",
                dnsServers = listOf("198.51.100.53", "192.0.2.53"), allowedIps = allowedIps,
            ) to laterDnsServerOutside,
            profile(
                "later-many", "Later many", "198.51.100.2/24",
                dnsServers = listOf("198.51.100.53", "192.0.2.53", "192.0.2.54"), allowedIps = allowedIps,
            ) to laterDnsServersOutside,
            profile(
                "secure", "Secure", "198.51.100.2/24",
                dnsServers = emptyList(), allowedIps = allowedIps,
                dns = secure("https://192.0.2.1/dns-query"),
            ) to secureServerOutside,
            profile(
                "hostname", "Hostname", "198.51.100.2/24",
                dnsServers = emptyList(), allowedIps = allowedIps,
                dns = secure("https://dns.example/dns-query"),
            ) to secureHostnameUncovered,
        )
    }

    private fun lineCountOf(text: String): Int {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single().lineCount
    }

    @Test
    fun `every DNS coverage note fits two lines in a saved row at 360dp`() {
        val items = mutableStateOf(emptyList<Profile>())
        show { ProfileListUiState(items = items.value) }
        coverageFixtures().chunked(3).forEach { batch ->
            compose.runOnIdle { items.value = batch.map { it.first } }
            batch.forEach { (_, text) ->
                assertTrue(
                    "the saved-row note wrapped past two lines: $text",
                    lineCountOf(text) <= 2,
                )
            }
        }
    }

    @Test
    fun `every DNS coverage note fits two lines in the hero at 360dp`() {
        val fixtures = coverageFixtures()
        val activeId = mutableStateOf(fixtures.first().first.id)
        show {
            ProfileListUiState(
                items = fixtures.map { it.first },
                activeId = activeId.value,
                phase = ConnectionPhase.CONNECTED,
                handshakeState = HandshakeState.VERIFIED,
            )
        }
        fixtures.forEach { (profile, text) ->
            compose.runOnIdle { activeId.value = profile.id }
            assertTrue(
                "the hero note wrapped past two lines: $text",
                lineCountOf(text) <= 2,
            )
        }
    }
}
