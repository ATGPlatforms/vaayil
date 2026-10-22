package dev.vaayil.feature.profiles

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle

/**
 * T-8 (spec §3.3): the editor's Private DNS note. Android's Private DNS is set to [host], so
 * apps' lookups go to that provider instead of this profile's DNS servers, Secure DNS and
 * split-DNS rules — which is why the settings on this sheet change nothing while it is set.
 * The host is one monospace span (The Data Speaks Mono Rule) inside the single sentence, so
 * TalkBack reads the whole note at once. Advice only: no action, and nothing it says blocks
 * Save.
 */
internal fun privateDnsEditorNote(host: String): AnnotatedString = buildAnnotatedString {
    append("Android's Private DNS is set to ")
    withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(host) }
    append(". While it is, apps' DNS lookups skip this profile's DNS servers, Secure DNS and split-DNS rules.")
}
