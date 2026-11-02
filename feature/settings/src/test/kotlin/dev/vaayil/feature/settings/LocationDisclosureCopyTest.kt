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
        assertEquals(
            BODY_WITHOUT_NEARBY_NETWORKS,
            locationDisclosureText(readsNearbyNetworks = false, offersAllowAllTheTime = true),
        )
    }

    @Test
    fun `the body on API 29 and 30 is verbatim`() {
        assertEquals(
            BODY_WITH_NEARBY_NETWORKS,
            locationDisclosureText(readsNearbyNetworks = true, offersAllowAllTheTime = true),
        )
    }

    @Test
    fun `the body on API 28 and older is verbatim`() {
        assertEquals(
            BODY_WITHOUT_ALLOW_ALL_THE_TIME,
            locationDisclosureText(readsNearbyNetworks = true, offersAllowAllTheTime = false),
        )
    }

    @Test
    fun `every body names the features, the setting and the on-device handling`() {
        for (body in listOf(
            BODY_WITHOUT_NEARBY_NETWORKS,
            BODY_WITH_NEARBY_NETWORKS,
            BODY_WITHOUT_ALLOW_ALL_THE_TIME,
        )) {
            assertTrue(body.contains("location data"))
            assertTrue(body.contains("even when the app is closed or not in use"))
            assertTrue(body.contains("Auto-connect on untrusted Wi-Fi"))
            assertTrue(body.contains("Disconnect on trusted networks"))
            assertTrue(body.contains("It never stores your location data or transmits it anywhere"))
        }
        assertTrue(BODY_WITHOUT_NEARBY_NETWORKS.contains("'Allow all the time'"))
        assertTrue(BODY_WITH_NEARBY_NETWORKS.contains("'Allow all the time'"))
        assertFalse(BODY_WITHOUT_ALLOW_ALL_THE_TIME.contains("Allow all the time"))
        assertTrue(BODY_WITHOUT_ALLOW_ALL_THE_TIME.contains("so these rules need location access."))
    }

    @Test
    fun `no body says collects or traffic`() {
        for (body in listOf(
            BODY_WITHOUT_NEARBY_NETWORKS,
            BODY_WITH_NEARBY_NETWORKS,
            BODY_WITHOUT_ALLOW_ALL_THE_TIME,
        )) {
            assertFalse(body.contains("collects"))
            assertFalse(body.contains("traffic"))
        }
    }

    private companion object {
        const val BODY_WITHOUT_NEARBY_NETWORKS =
            "Vaayil accesses location data to enable Auto-connect on untrusted Wi-Fi and Disconnect on trusted networks, even when the app is closed or not in use. Android counts the name of the Wi-Fi network you're on as location data, so these rules need location set to 'Allow all the time'. Vaayil checks that name against your trusted list on this device. It never stores your location data or transmits it anywhere, and keeps no history of networks."
        const val BODY_WITH_NEARBY_NETWORKS =
            "Vaayil accesses location data to enable Auto-connect on untrusted Wi-Fi and Disconnect on trusted networks, even when the app is closed or not in use. Android counts the name of the Wi-Fi network you're on as location data, so these rules need location set to 'Allow all the time'. Vaayil checks that name against your trusted list on this device. To tell whether the network has a password, it also reads the list of nearby Wi-Fi networks. It never stores your location data or transmits it anywhere, and keeps no history of networks."
        const val BODY_WITHOUT_ALLOW_ALL_THE_TIME =
            "Vaayil accesses location data to enable Auto-connect on untrusted Wi-Fi and Disconnect on trusted networks, even when the app is closed or not in use. Android counts the name of the Wi-Fi network you're on as location data, so these rules need location access. Vaayil checks that name against your trusted list on this device. To tell whether the network has a password, it also reads the list of nearby Wi-Fi networks. It never stores your location data or transmits it anywhere, and keeps no history of networks."
    }
}
