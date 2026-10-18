package dev.vaayil.feature.settings

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class AppLicenseTest {
    private fun licenseText(): String {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return context.resources.openRawResource(R.raw.gpl_3_0).use { it.readBytes().decodeToString() }
    }

    /** The parser's rendering ignores line breaks, so the words must survive in order. */
    private fun words(s: String): List<String> = s.split(Regex("\\s+")).filter { it.isNotEmpty() }

    @Test
    fun `bundled copy is byte-identical to LICENSE`() {
        // Unit tests run from the module dir (feature/settings), so the repo root is two up.
        val license = File("../../LICENSE")
        assertTrue("LICENSE must exist at the repo root", license.isFile)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bundled = context.resources.openRawResource(R.raw.gpl_3_0).use { it.readBytes() }
        assertArrayEquals(license.readBytes(), bundled)
    }

    @Test
    fun `the parser keeps every word in order`() {
        val text = licenseText()
        val rendered = parseLicenseText(text).flatMap { block ->
            when (block) {
                is LicenseBlock.Title -> block.lines
                is LicenseBlock.Template -> block.lines
                is LicenseBlock.Section -> listOf(block.text)
                is LicenseBlock.Item -> listOf(block.text)
                is LicenseBlock.Paragraph -> listOf(block.text)
            }.flatMap(::words)
        }
        assertEquals(words(text), rendered)
    }

    @Test
    fun `the parser finds the licence's structure`() {
        val blocks = parseLicenseText(licenseText())

        val titles = blocks.filterIsInstance<LicenseBlock.Title>()
        assertEquals(
            listOf(
                LicenseBlock.Title(listOf("GNU GENERAL PUBLIC LICENSE", "Version 3, 29 June 2007")),
                LicenseBlock.Title(listOf("Preamble")),
                LicenseBlock.Title(listOf("TERMS AND CONDITIONS")),
                LicenseBlock.Title(listOf("END OF TERMS AND CONDITIONS")),
                LicenseBlock.Title(listOf("How to Apply These Terms to Your New Programs")),
            ),
            titles,
        )
        assertEquals(LicenseBlock.Title(listOf("GNU GENERAL PUBLIC LICENSE", "Version 3, 29 June 2007")), blocks.first())

        val sections = blocks.filterIsInstance<LicenseBlock.Section>()
        assertEquals(18, sections.size)
        assertEquals("0. Definitions.", sections.first().text)
        assertEquals("17. Interpretation of Sections 15 and 16.", sections.last().text)

        val items = blocks.filterIsInstance<LicenseBlock.Item>()
        assertEquals(15, items.size)
        assertTrue(
            "the section 5(b) item must survive the re-wrap",
            items.any {
                it.text == "a) The work must carry prominent notices stating that you modified it, and giving a relevant date."
            },
        )

        val templates = blocks.filterIsInstance<LicenseBlock.Template>()
        assertEquals(5, templates.size)
        assertEquals(
            LicenseBlock.Template(
                listOf(
                    "<one line to give the program's name and a brief idea of what it does.>",
                    "Copyright (C) <year>  <name of author>",
                ),
            ),
            templates.first(),
        )

        val paragraphs = blocks.filterIsInstance<LicenseBlock.Paragraph>()
        assertEquals(79, paragraphs.size)
        assertTrue(paragraphs.last().text.endsWith("<https://www.gnu.org/licenses/why-not-lgpl.html>."))
    }

    @Test
    fun `the parser's rules on a small sample`() {
        val blocks = parseLicenseText(
            "            T\n\n  0. Head.\n\nOne\ntwo.\n\n    a) x\n    y\n\n    keep  this\n    line\n",
        )
        assertEquals(
            listOf(
                LicenseBlock.Title(listOf("T")),
                LicenseBlock.Section("0. Head."),
                LicenseBlock.Paragraph("One two."),
                LicenseBlock.Item("a) x y"),
                LicenseBlock.Template(listOf("keep  this", "line")),
            ),
            blocks,
        )
    }
}
