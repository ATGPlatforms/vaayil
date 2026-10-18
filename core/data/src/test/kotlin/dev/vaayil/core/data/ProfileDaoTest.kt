package dev.vaayil.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.model.DnsMode
import dev.vaayil.core.model.DnsSplitRule
import dev.vaayil.core.model.SecureDnsType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class ProfileDaoTest {
    private lateinit var db: VaayilDatabase

    @Before fun setUp() {
        db = buildInMemoryDatabase(ApplicationProvider.getApplicationContext<Context>())
    }
    @After fun tearDown() { db.close() }

    private fun entity(id: String, name: String) = ProfileEntity(
        id = id, name = name, privateKey = "k", addresses = listOf("10.0.0.2/32"),
        dnsServers = listOf("1.1.1.1"), mtu = null, listenPort = null,
        peerPublicKey = "K", presharedKey = null, endpoint = "h:51820",
        allowedIps = listOf("0.0.0.0/0"), keepalive = 25,
        createdAt = 1L, updatedAt = 1L,
    )

    @Test fun `upsert and read round-trips lists and sets`() = runTest {
        db.profileDao().upsert(entity("p1", "Home"))
        val loaded = db.profileDao().getById("p1")!!
        assertEquals(listOf("10.0.0.2/32"), loaded.addresses)
    }

    @Test fun `delete removes row`() = runTest {
        db.profileDao().upsert(entity("p1", "Home"))
        db.profileDao().delete("p1")
        assertNull(db.profileDao().getById("p1"))
    }

    @Test fun `observeAll orders by updatedAt desc`() = runTest {
        db.profileDao().upsert(entity("old", "Old"))
        db.profileDao().upsert(entity("new", "New").copy(updatedAt = 99L))
        assertEquals(listOf("new", "old"), db.profileDao().observeAll().first().map { it.id })
    }

    private fun profileEntity(id: String) = ProfileEntity(
        id = id, name = "Home", privateKey = KEY44,
        addresses = listOf("10.0.0.2/32"), dnsServers = listOf("1.1.1.1"),
        mtu = 1420, listenPort = null, peerPublicKey = KEY44,
        presharedKey = null, endpoint = "vpn.example.com:51820",
        allowedIps = listOf("0.0.0.0/0"), keepalive = 25,
        createdAt = 1L, updatedAt = 1L,
    )

    @Test
    fun `dns policy columns round-trip`() = runTest {
        val dao = db.profileDao()
        val e = profileEntity("p1").copy(
            dnsMode = "SECURE", dnsSecureType = "DOH",
            dnsSecureEndpoint = "https://9.9.9.9/dns-query",
            dnsRules = listOf(DnsSplitRule("internal.corp", bypass = true)),
        )
        dao.upsert(e)
        val loaded = dao.getById("p1")!!
        assertEquals("SECURE", loaded.dnsMode)
        assertEquals("DOH", loaded.dnsSecureType)
        assertEquals("https://9.9.9.9/dns-query", loaded.dnsSecureEndpoint)
        assertEquals(listOf(DnsSplitRule("internal.corp", bypass = true)), loaded.dnsRules)
        val model = loaded.toModel()
        assertEquals(DnsMode.SECURE, model.dns.mode)
        assertEquals(SecureDnsType.DOH, model.dns.secureType)
        assertEquals(listOf(DnsSplitRule("internal.corp", bypass = true)), model.dns.rules)
    }

    @Test
    fun `secure_delete is on for every connection`() {
        db.openHelper.readableDatabase.query("PRAGMA secure_delete").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
    }
}

private const val KEY44 = "eCyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy0="
