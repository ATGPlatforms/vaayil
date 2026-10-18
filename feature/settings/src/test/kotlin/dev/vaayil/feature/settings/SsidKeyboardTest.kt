package dev.vaayil.feature.settings

import androidx.compose.ui.text.input.KeyboardCapitalization
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * F16: trusted SSIDs match exactly, so the add-field keyboard must not autocorrect or
 * auto-capitalise. No keyboardType is set, so SSIDs can be non-ASCII.
 */
class SsidKeyboardTest {
    @Test fun `ssid field keeps text as typed`() {
        assertEquals(false, SsidKeyboard.autoCorrectEnabled)
        assertEquals(KeyboardCapitalization.None, SsidKeyboard.capitalization)
    }
}
