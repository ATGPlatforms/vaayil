package dev.vaayil.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.unit.dp
import dev.vaayil.core.ui.VellumColors

/** Minimal markdown model for the shipped policy: `#`/`##` headings, blank-line paragraphs, `- ` bullets. */
internal sealed interface PolicyBlock {
    data class Heading(val level: Int, val text: String) : PolicyBlock
    data class Paragraph(val text: String) : PolicyBlock
    data class Bullets(val items: List<String>) : PolicyBlock
}

internal fun parsePolicyMarkdown(text: String): List<PolicyBlock> {
    val blocks = mutableListOf<PolicyBlock>()
    var bullets = mutableListOf<String>()
    fun flushBullets() {
        if (bullets.isNotEmpty()) {
            blocks += PolicyBlock.Bullets(bullets.toList())
            bullets = mutableListOf()
        }
    }
    text.lines().forEach { raw ->
        val line = raw.trimEnd()
        when {
            line.startsWith("## ") -> { flushBullets(); blocks += PolicyBlock.Heading(2, line.removePrefix("## ")) }
            line.startsWith("# ") -> { flushBullets(); blocks += PolicyBlock.Heading(1, line.removePrefix("# ")) }
            line.startsWith("- ") -> bullets += line.removePrefix("- ")
            line.isEmpty() -> flushBullets()
            else -> { flushBullets(); blocks += PolicyBlock.Paragraph(line) }
        }
    }
    flushBullets()
    return blocks
}

/** The in-app privacy policy (Play policy requires text inside the app): the raw resource rendered as text. */
@Composable
fun PrivacyPolicyScreen() {
    val context = LocalContext.current
    val text = remember {
        context.resources.openRawResource(R.raw.privacy_policy)
            .use { it.readBytes().decodeToString() }
    }
    Column(
        modifier = Modifier
            .testTag("privacy-column")
            // 65ch of the 16sp body ≈ 520dp: the readable-measure cap for long-form
            // text (spec §3.5) — the hosting TabContainer caps at 720dp above it.
            .widthIn(max = 520.dp)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        parsePolicyMarkdown(text).forEach { block ->
            when (block) {
                is PolicyBlock.Heading ->
                    Text(
                        block.text,
                        style = if (block.level == 1) MaterialTheme.typography.headlineLarge
                        else MaterialTheme.typography.titleLarge,
                        modifier = if (block.level == 1) Modifier else Modifier.padding(top = 8.dp),
                    )
                is PolicyBlock.Paragraph ->
                    Text(
                        block.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = VellumColors.OnSurface,
                    )
                is PolicyBlock.Bullets ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        block.items.forEach { item ->
                            Row {
                                Text("•  ", style = MaterialTheme.typography.bodyLarge)
                                Text(item, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
            }
        }
    }
}
