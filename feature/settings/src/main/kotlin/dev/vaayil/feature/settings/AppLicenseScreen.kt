package dev.vaayil.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.vaayil.core.ui.VellumColors

/**
 * The block model for the bundled licence text: centred titles, numbered sections,
 * lettered items, the indented notice templates and running paragraphs.
 */
internal sealed interface LicenseBlock {
    data class Title(val lines: List<String>) : LicenseBlock
    data class Section(val text: String) : LicenseBlock
    data class Item(val text: String) : LicenseBlock
    data class Template(val lines: List<String>) : LicenseBlock
    data class Paragraph(val text: String) : LicenseBlock
}

private val SECTION_LINE = Regex("""\d{1,2}\. .+\.""")
private val ITEM_LINE = Regex("""^[a-z]\) """)

/**
 * Splits the GPL text into blocks by line shape: a block is a run of non-blank lines
 * (a line is blank when it is empty after [String.trimEnd]), each line trailing-trimmed.
 * The rules run in order — centred titles (indent ≥ 10), numbered sections (one line,
 * indent 2), lettered items and indented templates (indent ≥ 4), everything else a
 * paragraph. Lines are joined back with single spaces, so no word is lost or reordered.
 */
internal fun parseLicenseText(text: String): List<LicenseBlock> {
    val blocks = mutableListOf<LicenseBlock>()
    val run = mutableListOf<String>()
    fun flush() {
        if (run.isNotEmpty()) {
            blocks += classify(run)
            run.clear()
        }
    }
    text.lines().forEach { raw ->
        val line = raw.trimEnd()
        if (line.isEmpty()) flush() else run += line
    }
    flush()
    return blocks
}

private fun classify(lines: List<String>): LicenseBlock {
    val indents = lines.map { line -> line.takeWhile { it == ' ' }.length }
    val first = lines.first().trim()
    return when {
        (indents.all { it >= 10 }) ->
            LicenseBlock.Title(lines.map { it.trim() })
        (lines.size == 1 && indents.first() == 2 && SECTION_LINE.matches(first)) ->
            LicenseBlock.Section(first)
        (indents.all { it >= 4 } && ITEM_LINE.containsMatchIn(first)) ->
            LicenseBlock.Item(lines.joinToString(" ") { it.trim() })
        (indents.all { it >= 4 }) ->
            LicenseBlock.Template(lines.map { it.substring(4) })
        else ->
            LicenseBlock.Paragraph(lines.joinToString(" ") { it.trim() })
    }
}

/**
 * Vaayil's own licence, the GNU GPL version 3, bundled so the app carries a copy of it (GPL-3.0
 * section 4). Settings → About's View licence opens it.
 */
@Composable
fun AppLicenseScreen() {
    val context = LocalContext.current
    val blocks = remember {
        parseLicenseText(
            context.resources.openRawResource(R.raw.gpl_3_0).use { it.readBytes().decodeToString() },
        )
    }
    Column(
        modifier = Modifier
            .testTag("app-license-column")
            // Same readable-measure cap as the privacy policy: 65ch of the 16sp body ≈ 520dp.
            .widthIn(max = 520.dp)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        blocks.forEachIndexed { index, block ->
            when (block) {
                is LicenseBlock.Title -> if (index == 0) {
                    Text(
                        block.lines.first(),
                        style = MaterialTheme.typography.headlineLarge,
                        modifier = Modifier.semantics { heading() },
                    )
                    block.lines.drop(1).forEach { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodyLarge,
                            color = VellumColors.OnSurfaceVariant,
                            modifier = Modifier.semantics { heading() },
                        )
                    }
                } else {
                    block.lines.forEachIndexed { lineIndex, line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = (if (lineIndex == 0) Modifier.padding(top = 8.dp) else Modifier)
                                .semantics { heading() },
                        )
                    }
                }

                is LicenseBlock.Section ->
                    Text(
                        block.text,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .semantics { heading() },
                    )

                is LicenseBlock.Paragraph ->
                    Text(
                        block.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = VellumColors.OnSurface,
                    )

                is LicenseBlock.Item ->
                    Text(
                        block.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = VellumColors.OnSurface,
                        modifier = Modifier.padding(start = 16.dp),
                    )

                is LicenseBlock.Template ->
                    Text(
                        block.lines.joinToString("\n"),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 16.dp),
                    )
            }
        }
    }
}
