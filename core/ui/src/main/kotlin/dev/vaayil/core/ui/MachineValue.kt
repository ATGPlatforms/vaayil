package dev.vaayil.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * A machine value's line-break opportunities (U2, sweep 2): after '.' and '/', and before
 * ':' — the PREFERRED break points, so a port normally stays with its colon and a host
 * normally breaks between labels. Breaks the string into segments a line may wrap BETWEEN;
 * the concatenation is always the value. The preferences are not guarantees: a single
 * segment wider than the space it gets still wraps mid-token as a last resort — never worse
 * than an unbroken `Text` — and a separator can then lead a line (I1b, final review).
 */
fun machineValueSegments(value: String): List<String> {
    if (value.isEmpty()) return listOf(value)
    val segments = mutableListOf<String>()
    val current = StringBuilder()
    for (c in value) {
        if (c == ':' && current.isNotEmpty()) {
            segments += current.toString()
            current.clear()
        }
        current.append(c)
        if (c == '.' || c == '/') {
            segments += current.toString()
            current.clear()
        }
    }
    if (current.isNotEmpty()) segments += current.toString()
    return segments
}

/**
 * Machine data in the mono face, wrapping at [machineValueSegments]' opportunities (U2) —
 * the preferred points: a segment wider than the space it gets still wraps mid-token as a
 * last resort, never worse than an unbroken `Text`. Display-only: the FlowRow merges its
 * segment texts into one semantics node whose ordered text and content description are the
 * plain value — TalkBack reads the value, not fragments, and nothing is inserted into the
 * string. [lineAlignment] places each wrapped line (defaults to [Alignment.Start]; the
 * ledger values keep it — device check 2026-10-03).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MachineValueText(
    value: String,
    modifier: Modifier = Modifier,
    style: TextStyle = monoTextStyle(),
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
    lineAlignment: Alignment.Horizontal = Alignment.Start,
) {
    FlowRow(
        modifier = modifier.semantics(mergeDescendants = true) {
            // TalkBack announces the content description of a merged node in place of its
            // children's texts: one utterance, the plain value (I3, final review).
            contentDescription = value
        },
        horizontalArrangement = Arrangement.spacedBy(0.dp, lineAlignment),
    ) {
        machineValueSegments(value).forEach { segment ->
            Text(segment, style = style, color = color, textAlign = textAlign)
        }
    }
}
