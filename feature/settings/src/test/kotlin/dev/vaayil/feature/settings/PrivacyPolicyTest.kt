package dev.vaayil.feature.settings

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class PrivacyPolicyTest {
    private fun policyText(): String {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return context.resources.openRawResource(R.raw.privacy_policy).use { it.readBytes().decodeToString() }
    }

    @Test
    fun `hosted copy is byte-identical to the in-app resource`() {
        // Unit tests run from the module dir (feature/settings), so the repo root is two up.
        val hosted = File("../../docs/privacy-policy.md")
        assertTrue("docs/privacy-policy.md must exist at the repo root", hosted.isFile)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val inApp = context.resources.openRawResource(R.raw.privacy_policy).use { it.readBytes() }
        assertArrayEquals(hosted.readBytes(), inApp)
    }

    @Test
    fun `the renderer parses the shipped policy`() {
        val blocks = parsePolicyMarkdown(policyText())
        assertEquals(PolicyBlock.Heading(1, "Vaayil privacy policy"), blocks.first())
        assertTrue(blocks.any { it == PolicyBlock.Heading(2, "Location") })
        val items = blocks.filterIsInstance<PolicyBlock.Bullets>().flatMap { it.items }
        assertTrue(items.any {
            it.startsWith("VPN profiles you import or type in:") &&
                it.contains("Private and preshared keys are encrypted at rest")
        })
        assertTrue(items.any {
            it.startsWith("Automation rules:") && it.contains("external-control token")
        })
        assertTrue(items.any {
            it.startsWith("Your VPN endpoint:") && it.contains("WireGuard server configured in the profile")
        })
        assertTrue(items.any {
            it.startsWith("The DNS resolvers configured in the profile:") &&
                it.contains("Encrypted DNS travels inside the tunnel.")
        })
    }

    @Test
    fun `renderer splits headings bullets and paragraphs`() {
        val blocks = parsePolicyMarkdown("# T\n\nPara one.\n\n- a\n- b\n\n## S\n\nPara two.\n")
        assertEquals(
            listOf(
                PolicyBlock.Heading(1, "T"),
                PolicyBlock.Paragraph("Para one."),
                PolicyBlock.Bullets(listOf("a", "b")),
                PolicyBlock.Heading(2, "S"),
                PolicyBlock.Paragraph("Para two."),
            ),
            blocks,
        )
    }

    @Test
    fun `policy identifies the source project without requiring Play`() {
        assertTrue(policyText().contains("developed and published by ATG Platforms Ltd as the open-source project at https://github.com/ATGPlatforms/vaayil."))
        assertTrue(policyText().contains("https://github.com/ATGPlatforms/vaayil/issues"))
        assertTrue(policyText().contains("For privacy questions, email ATG Platforms Ltd at hello@atgplatforms.com."))
        assertTrue(policyText().contains("Last updated: 2026-10-24"))
        assertFalse(policyText().contains("developed and published by the developer named on its Google Play listing"))
        assertFalse(policyText().contains("Use the developer contact address on Vaayil's Google Play listing."))
    }
}
