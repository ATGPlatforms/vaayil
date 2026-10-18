package dev.vaayil.feature.profiles

import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import org.junit.Assert.assertEquals
import org.junit.Test

class EditorKeyboardsTest {
    @Test fun `machine text has no autocorrect and no capitals`() {
        with(EditorKeyboards.machineText) {
            assertEquals(false, autoCorrectEnabled)
            assertEquals(KeyboardCapitalization.None, capitalization)
            assertEquals(KeyboardType.Ascii, keyboardType)
        }
    }
    @Test fun `uri fields use the uri keyboard without autocorrect`() {
        with(EditorKeyboards.uri) {
            assertEquals(false, autoCorrectEnabled)
            assertEquals(KeyboardCapitalization.None, capitalization)
            assertEquals(KeyboardType.Uri, keyboardType)
        }
    }
    @Test fun `numeric fields use the number keyboard`() {
        with(EditorKeyboards.number) {
            assertEquals(false, autoCorrectEnabled)
        }
        assertEquals(KeyboardType.Number, EditorKeyboards.number.keyboardType)
    }
}
