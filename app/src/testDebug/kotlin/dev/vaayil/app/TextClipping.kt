package dev.vaayil.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/*
 * Text-fit checks for Robolectric compose tests (F29, spec U6). `TextLayoutResult.size` is
 * clamped to the node's constraints, so comparing it with the node's box never sees a clip.
 * These checks read the paragraph the text actually produced. Never use `hasVisualOverflow`
 * or `didExceedMaxLines` here: the semantics layout re-runs each paragraph at its parent's
 * full width, so every short single-line text would report an overflow.
 */

/** One laid-out text node and the layout its text produced. */
class LaidOutText(val node: SemanticsNode, val layout: TextLayoutResult) {
    val text: String get() = layout.layoutInput.text.text
}

/** Renders [content] as if the system font scale were [scale], on Compose's non-linear curve. */
@Composable
fun AtFontScale(scale: Float, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalDensity provides Density(LocalDensity.current.density, scale),
        content = content,
    )
}

/** Every laid-out text in [root]'s unmerged tree (merged groups drop leaves). */
fun SemanticsNodeInteractionsProvider.allTextLayouts(
    root: SemanticsMatcher = isRoot(),
): List<LaidOutText> {
    val found = mutableListOf<LaidOutText>()
    fun walk(node: SemanticsNode) {
        val action = node.config.getOrElseNullable(SemanticsActions.GetTextLayoutResult) { null }?.action
        if (action != null) {
            val results = mutableListOf<TextLayoutResult>()
            if (action(results) && results.isNotEmpty()) found += LaidOutText(node, results.first())
        }
        node.children.forEach { walk(it) }
    }
    walk(onNode(root, useUnmergedTree = true).fetchSemanticsNode())
    return found
}

/**
 * One line per clipped text node, empty when every text fits:
 *  - cut at the bottom: the paragraph is taller than the node;
 *  - cut at the side: a text that may not wrap is wider than its node;
 *  - cut after its last line: the visible lines end before the text does (a `maxLines` cut);
 *  - off the screen: the node starts before or ends after [root]'s horizontal edges, both measured in the window.
 * Ellipsized text is cut on purpose, so the side and last-line checks skip it.
 */
fun SemanticsNodeInteractionsProvider.textClipProblems(
    root: SemanticsMatcher = isRoot(),
): List<String> {
    val rootNode = onNode(root, useUnmergedTree = true).fetchSemanticsNode()
    val rootLeft = rootNode.positionInRoot.x
    val rootRight = rootLeft + rootNode.size.width
    return allTextLayouts(root).mapNotNull { t ->
        val layout = t.layout
        val input = layout.layoutInput
        val paragraph = layout.multiParagraph
        val ellipsized = input.overflow == TextOverflow.Ellipsis
        val textWidth = paragraph.intrinsics.maxIntrinsicWidth
        val shownChars = if (layout.lineCount == 0) 0 else layout.getLineEnd(layout.lineCount - 1)
        val left = t.node.positionInRoot.x
        val right = left + t.node.size.width
        val problem = when {
            paragraph.height > layout.size.height + 0.5f ->
                "cut at the bottom (text ${paragraph.height}px tall, node ${layout.size.height}px)"
            !ellipsized && !input.softWrap && textWidth > layout.size.width + 0.5f ->
                "cut at the side (text ${textWidth}px wide, node ${layout.size.width}px)"
            !ellipsized && shownChars < t.text.length ->
                "cut after line ${layout.lineCount} (shows $shownChars of ${t.text.length} characters)"
            left < rootLeft - 0.5f || right > rootRight + 0.5f ->
                "off the screen (x $left..${right}px, root $rootLeft..${rootRight}px)"
            else -> null
        }
        problem?.let { "\"${t.text.take(48)}\": $it" }
    }
}

/** Fails listing every clipped text node in [root]; [where] names the screen state. */
fun SemanticsNodeInteractionsProvider.assertNoTextClipped(
    where: String,
    root: SemanticsMatcher = isRoot(),
) {
    val problems = textClipProblems(root)
    assertTrue("Clipped text $where:\n" + problems.joinToString("\n"), problems.isEmpty())
}

/** The layout of the one text node rendering exactly [text]. */
fun SemanticsNodeInteractionsProvider.layoutOf(text: String): TextLayoutResult {
    val matches = allTextLayouts().filter { it.text == text }
    assertEquals("text nodes rendering \"${text.take(48)}\"", 1, matches.size)
    return matches.single().layout
}

/** [text] renders on at most [maxLines] lines. */
fun SemanticsNodeInteractionsProvider.assertLinesAtMost(text: String, maxLines: Int) {
    val lines = layoutOf(text).lineCount
    assertTrue("\"${text.take(48)}\" wrapped to $lines lines (budget $maxLines)", lines <= maxLines)
}

/** [text] renders on one line, cut with an ellipsis (a long name at large text, F29). */
fun SemanticsNodeInteractionsProvider.assertEllipsizedOnOneLine(text: String) {
    val layout = layoutOf(text)
    assertEquals("\"${text.take(48)}\" lines", 1, layout.lineCount)
    assertTrue("\"${text.take(48)}\" is not cut with an ellipsis", layout.isLineEllipsized(0))
}
