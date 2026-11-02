package dev.vaayil.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The prominent disclosure's copy (Y1), pinned word for word: Play's review of the store
 * listing turns on this text naming every background-location feature in Play's recommended
 * form, so any wording change must be deliberate.
 */
class LocationDisclosureCopyTest {
    @Test
    fun `the title is verbatim`() {
        assertEquals("Location access", LOCATION_DISCLOSURE_TITLE)
    }

    @Test
    fun `the body on API 31 and newer is verbatim`() {
        assertEquals(BODY_WITHOUT_NEARBY_NETWORKS, locationDisclosureText(false))
    }

    @Test
    fun `the body on API 30 and older is verbatim`() {
        assertEquals(BODY_WITH_NEARBY_NETWORKS, locationDisclosureText(true))
    }

    @Test
    fun `both bodies name the features, the setting and the on-device handling`() {
        for (body in listOf(BODY_WITHOUT_NEARBY_NETWORKS, BODY_WITH_NEARBY_NETWORKS)) {
            assertTrue(body.contains("location data"))
            assertTrue(body.contains("even when the app is closed or not in use"))
            assertTrue(body.contains("Auto-connect on untrusted Wi-Fi"))
            assertTrue(body.contains("Disconnect on trusted networks"))
            assertTrue(body.contains("'Allow all the time'"))
            assertTrue(body.contains("It never stores your location data or transmits it anywhere"))
        }
    }

    @Test
    fun `neither body says collects or traffic`() {
        for (body in listOf(BODY_WITHOUT_NEARBY_NETWORKS, BODY_WITH_NEARBY_NETWORKS)) {
            assertFalse(body.contains("collects"))
            assertFalse(body.contains("traffic"))
        }
    }

    private companion object {
        const val BODY_WITHOUT_NEARBY_NETWORKS =
            "Vaayil accesses location data to enable Auto-connect on untrusted Wi-Fi and Disconnect on trusted networks, even when the app is closed or not in use. Android counts the name of the Wi-Fi network you're on as location data, so these rules need location set to 'Allow all the time'. Vaayil checks that name against your trusted list on this device. It never stores your location data or transmits it anywhere, and keeps no history of networks."
        const val BODY_WITH_NEARBY_NETWORKS =
            "Vaayil accesses location data to enable Auto-connect on untrusted Wi-Fi and Disconnect on trusted networks, even when the app is closed or not in use. Android counts the name of the Wi-Fi network you're on as location data, so these rules need location set to 'Allow all the time'. Vaayil checks that name against your trusted list on this device. To tell whether the network has a password, it also reads the list of nearby Wi-Fi networks. It never stores your location data or transmits it anywhere, and keeps no history of networks."
    }
}
