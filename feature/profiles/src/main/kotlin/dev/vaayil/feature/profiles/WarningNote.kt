package dev.vaayil.feature.profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.vaayil.core.ui.VellumColors
import dev.vaayil.core.ui.vellumSeam

/**
 * A profile warning (spec U9) as a free-standing warning block, in the editor hostname hint's
 * treatment: `warning` fill, a 1dp `warning-mark` seam at the 10dp card radius, `bodySmall` text
 * in `on-warning`. Advice only: nothing it says blocks an action.
 */
@Composable
internal fun WarningNote(text: String, modifier: Modifier = Modifier) {
    WarningNote(AnnotatedString(text), modifier)
}

/**
 * The same block for a body that carries styled spans — T-8's Private DNS note sets the host
 * in monospace inside its one sentence, so TalkBack still reads the note as a single line.
 */
@Composable
internal fun WarningNote(text: AnnotatedString, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(VellumColors.Warning, shape)
            .vellumSeam(shape, VellumColors.WarningMark)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = VellumColors.OnWarning)
    }
}
