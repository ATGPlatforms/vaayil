package dev.vaayil.app.quick

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertTrue

/*
 * View-side sibling of the app's `TextClipping` (F29, spec U6). A TextView's `Layout` is
 * measured inside the view's own box, so a clipped line is not always visible from sizes
 * alone: `getLineWidth` still reports the full line, and a `maxLines` cut silently drops
 * the rest of the text. These checks read the layout the visible TextView produced.
 */

/**
 * Fails listing every visible [TextView] under [root] that can't show its text:
 *  - more lines laid out than [TextView.getMaxLines] allows;
 *  - a line that isn't ellipsized wider than the view's content box;
 *  - the layout plus the compound paddings taller than the view;
 *  - a view whose bounds, offset to [root], fall outside it.
 * [where] names the rendered state in the failure message. Ellipsized lines are cut on
 * purpose, so the width check skips them.
 */
internal fun assertNoTextViewClipped(root: View, where: String) {
    val problems = mutableListOf<String>()
    fun walk(view: View) {
        if (view is TextView && isVisibleIn(view, root)) {
            problems += clipProblems(view, root)
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) walk(view.getChildAt(index))
        }
    }
    walk(root)
    assertTrue("Clipped text $where:\n" + problems.joinToString("\n"), problems.isEmpty())
}

private fun clipProblems(text: TextView, root: View): List<String> {
    val layout = text.layout ?: return emptyList()
    val label = "\"${text.text.take(48)}\""
    val problems = mutableListOf<String>()

    if (layout.lineCount > text.maxLines) {
        problems += "$label: ${layout.lineCount} lines laid out, maxLines is ${text.maxLines}"
    }

    val contentWidth = text.width - text.compoundPaddingLeft - text.compoundPaddingRight
    for (line in 0 until layout.lineCount) {
        if (layout.getEllipsisCount(line) == 0 && layout.getLineWidth(line) > contentWidth + 0.5f) {
            problems += "$label: line $line is ${layout.getLineWidth(line)}px wide, content box is $contentWidth px"
        }
    }

    val neededHeight = layout.height + text.compoundPaddingTop + text.compoundPaddingBottom
    if (neededHeight > text.height) {
        problems += "$label: cut at the bottom (text $neededHeight px tall, view ${text.height} px)"
    }

    val (x, y) = offsetInRoot(text, root)
    if (x < 0 || y < 0 || x + text.width > root.width || y + text.height > root.height) {
        problems += "$label: off the widget (bounds ${x},${y} ${text.width}x${text.height}, root ${root.width}x${root.height})"
    }
    return problems
}

private fun isVisibleIn(view: View, root: View): Boolean {
    var current: View? = view
    while (current != null) {
        if (current.visibility != View.VISIBLE) return false
        if (current === root) return true
        current = current.parent as? View
    }
    return false
}

private fun offsetInRoot(view: View, root: View): Pair<Int, Int> {
    var x = 0
    var y = 0
    var current: View = view
    while (current !== root) {
        x += current.left
        y += current.top
        current = current.parent as? View ?: return x to y
    }
    return x to y
}
