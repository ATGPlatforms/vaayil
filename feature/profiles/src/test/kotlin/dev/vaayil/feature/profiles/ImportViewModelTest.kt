package dev.vaayil.feature.profiles

import dev.vaayil.core.model.InterfaceConfig
import dev.vaayil.core.model.PeerConfig
import dev.vaayil.core.model.Profile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ImportViewModelTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val conf = """
        [Interface]
        PrivateKey = eCyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy0=
        Address = 10.0.0.2/32

        [Peer]
        PublicKey = KyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyU=
        AllowedIPs = 0.0.0.0/0
        Endpoint = vpn.example.com:51820
    """.trimIndent()

    private fun stub(id: String) = Profile(id, "s",
        InterfaceConfig("k", listOf("10.0.0.2/32"), emptyList(), null, null),
        PeerConfig("K", null, "h:51820", listOf("0.0.0.0/0"), null),
    )
    @Test
    fun `valid text imports with the caller's name`() = runTest(UnconfinedTestDispatcher()) {
        var imported: String? = null
        val vm = ImportViewModel { _, name -> imported = name; ImportOutcome.Success(stub("x")) }
        val outcome = CompletableDeferred<ImportOutcome>()
        vm.importText(conf, "QR name") { outcome.complete(it) }
        // The parse runs on Dispatchers.Default, so the callback lands off the test's stack.
        assertTrue(outcome.await() is ImportOutcome.Success)
        assertEquals("QR name", imported)
    }

    @Test
    fun `mapped failure carries the boundary's message`() = runTest(UnconfinedTestDispatcher()) {
        val vm = ImportViewModel { _, _ -> ImportOutcome.Failure("Line 2: no Address") }
        val outcome = CompletableDeferred<ImportOutcome>()
        vm.importText("garbage", "x") { outcome.complete(it) }
        assertEquals("Line 2: no Address", (outcome.await() as ImportOutcome.Failure).message)
    }

    @Test
    fun `unmapped throw degrades to a generic failure not a crash`() = runTest(UnconfinedTestDispatcher()) {
        val vm = ImportViewModel { _, _ -> throw IllegalStateException("parser exploded") }
        val outcome = CompletableDeferred<ImportOutcome>()
        vm.importText("garbage", "x") { outcome.complete(it) }
        val failure = outcome.await()
        assertTrue(failure is ImportOutcome.Failure)
        assertEquals(null, (failure as ImportOutcome.Failure).message)
    }
}
