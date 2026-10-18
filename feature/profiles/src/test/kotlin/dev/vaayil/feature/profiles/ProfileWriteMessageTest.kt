package dev.vaayil.feature.profiles

import dev.vaayil.core.data.AtRestUnavailableException
import dev.vaayil.core.data.InvalidProfileException
import kotlin.test.Test
import kotlin.test.assertEquals

/** R6: every failed profile write reaches the UI through one mapper, never as exception text. */
class ProfileWriteMessageTest {
    @Test
    fun `an at-rest outage gets the Keystore sentence`() {
        assertEquals(
            "Profiles can't be read or saved while the Android Keystore is unavailable. Try again in a moment.",
            profileWriteMessage(AtRestUnavailableException(), fallback = "Couldn't save the profile."),
        )
    }

    @Test
    fun `a rejected profile names its first error`() {
        assertEquals(
            "Profile is invalid: DNS server \"corp.example.com\" must be an IP address.",
            profileWriteMessage(
                InvalidProfileException(listOf("DNS server \"corp.example.com\" must be an IP address.", "Second.")),
                fallback = "Couldn't save the profile.",
            ),
        )
    }

    @Test
    fun `anything else gets the fallback, never the exception text`() {
        assertEquals(
            "Couldn't restore \"Home\".",
            profileWriteMessage(
                IllegalStateException("SQLITE_FULL: database or disk is full"),
                fallback = "Couldn't restore \"Home\".",
            ),
        )
    }
}
