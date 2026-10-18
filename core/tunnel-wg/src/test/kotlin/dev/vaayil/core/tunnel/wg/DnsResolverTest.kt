package dev.vaayil.core.tunnel.wg

import dev.vaayil.core.dns.CachingUpstream
import dev.vaayil.core.dns.DatagramPipe
import dev.vaayil.core.dns.DnsResponder
import dev.vaayil.core.dns.DnsResponseCache
import dev.vaayil.core.dns.PlainUdpUpstream
import dev.vaayil.core.dns.SecureDnsBootstrapException
import dev.vaayil.core.model.DnsMode
import dev.vaayil.core.model.DnsPolicy
import dev.vaayil.core.model.DnsSplitRule
import dev.vaayil.core.model.InterfaceConfig
import dev.vaayil.core.model.PeerConfig
import dev.vaayil.core.model.Profile
import dev.vaayil.core.model.RESOLVER_V4
import dev.vaayil.core.model.RESOLVER_V6
import dev.vaayil.core.model.SecureDnsType
import dev.vaayil.core.model.SplitTunnelMode
import dev.vaayil.core.model.SplitTunnelPolicy
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import org.minidns.dnsmessage.DnsMessage
import org.minidns.dnsmessage.Question
import org.minidns.dnsname.DnsName
import org.minidns.record.A
import org.minidns.record.AAAA
import org.minidns.record.Record

private fun validProfile(
    dnsServers: List<String> = listOf("1.1.1.1", "9.9.9.9"),
): Profile = Profile(
    id = "p1",
    name = "Test",
    interfaceConfig = InterfaceConfig(
        privateKey = "A".repeat(43) + "=",
        addresses = listOf("10.0.0.2/32", "fd00::2/128"),
        dnsServers = dnsServers,
        mtu = null,
        listenPort = null,
    ),
    peer = PeerConfig(
        publicKey = "E".repeat(43) + "=",
        presharedKey = null,
        endpoint = "vpn.example.com:51820",
        allowedIps = listOf("0.0.0.0/0", "::/0"),
        persistentKeepalive = 25,
    ),
)

/** Canned-answer upstream; mirrors the query transaction id on its own copy (never mutates the fixture). */
private class FakeUpstream(private val answer: ByteArray) : dev.vaayil.core.dns.DnsUpstream {
    var hits = 0
    val lastIdMirror: Byte get() = answer[0]
    override suspend fun query(queryWire: ByteArray): ByteArray? {
        hits++
        return answer.copyOf().also { it[0] = queryWire[0]; it[1] = queryWire[1] }
    }
    override fun close() = Unit
}

private fun buildQuery(name: String, id: Int = 0x1234): ByteArray = DnsMessage.builder()
    .setId(id)
    .setQuestion(Question(DnsName.from(name), Record.TYPE.A))
    .build()
    .toArray()

private fun buildAAnswer(name: String, id: Int = 0x1234): ByteArray {
    val response = DnsMessage.builder()
        .setId(id)
        .setQuestion(Question(DnsName.from(name), Record.TYPE.A))
        .build()
        .asBuilder()
        .setQrFlag(true)
    response.addAnswer(
        Record(DnsName.from(name), Record.TYPE.A, Record.CLASS.IN, 60, A("93.184.216.34"), false),
    )
    return response.build().toArray()
}

private object NullUpstream : dev.vaayil.core.dns.DnsUpstream {
    override suspend fun query(queryWire: ByteArray): ByteArray? = null
    override fun close() = Unit
}

/**
 * An upstream that counts the queries it was entered for and the closes its owner performed; each answer parks on
 * [gate] first (null: answers at once), so a test can hold one query inside the upstream while the resolver
 * lifecycle moves on. Closes are counted on the caller's thread only.
 */
private class GatedUpstream(
    private val answer: ByteArray = buildAAnswer("example.com"),
    private val gate: CompletableDeferred<Unit>? = null,
) : dev.vaayil.core.dns.DnsUpstream {
    var closes = 0
    val entered = AtomicInteger()

    override suspend fun query(queryWire: ByteArray): ByteArray? {
        entered.incrementAndGet()
        gate?.await()
        return answer.copyOf().also { it[0] = queryWire[0]; it[1] = queryWire[1] }
    }

    override fun close() {
        closes++
    }
}

private fun buildTruncatedAnswer(name: String, id: Int = 0x1234): ByteArray {
    val response = DnsMessage.builder()
        .setId(id)
        .setQuestion(Question(DnsName.from(name), Record.TYPE.A))
        .build()
        .asBuilder()
        .setQrFlag(true)
        .setTruncated(true)
    response.addAnswer(Record(DnsName.from(name), Record.TYPE.A, Record.CLASS.IN, 60, A("93.184.216.34"), false))
    return response.build().toArray()
}

/**
 * Answers [query] on Dispatchers.IO, so the responder's 4 s budget runs on real time. Inside runTest a bare
 * answer() would see the test's virtual clock jump the budget the moment an upstream blocks on real I/O.
 */
private suspend fun ResolverSession.answerInRealTime(query: ByteArray): ByteArray? =
    withContext(Dispatchers.IO) { answer(query) }

/** A loopback port free in BOTH families (mirrors UpstreamTest's helper; fixed ports
    collided across concurrent Gradle test runs, ledger D5). */
private fun freeDualFamilyPort(): Int {
    while (true) {
        val probe = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port = probe.localPort
        probe.close()
        val udp = try {
            DatagramSocket(port, InetAddress.getByName("127.0.0.1"))
        } catch (_: SocketException) {
            continue
        }
        udp.close()
        return port
    }
}

/** Bounded poll for a condition another thread reaches (the DNS channel's reader is a plain thread). */
private fun waitUntil(what: String, condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (!condition()) {
        if (System.nanoTime() > deadline) throw AssertionError("timed out waiting until $what")
        Thread.sleep(5)
    }
}

/** seq (u32) · addrLen (u8) · the client's address · the DNS message, as libwg-go writes a query frame. */
private fun queryFrame(seq: Int, client: String, dns: ByteArray): ByteArray {
    val address = InetAddress.getByName(client).address
    return ByteBuffer.allocate(4 + 1 + address.size + dns.size)
        .putInt(seq).put(address.size.toByte()).put(address).put(dns)
        .array()
}

/** UDP "system resolver" fixture for the D1 bootstrap: parses the question and answers per
    type (null = NOERROR with no answers), mirroring the transaction id. Ephemeral port. */
private class BootstrapUdpDns(
    private val port: Int,
    private val a: String? = "127.0.0.1",
    private val aaaa: String? = null,
    /** K1: AAAA queries get no reply at all, so the AAAA attempt can only time out. */
    private val dropAaaa: Boolean = false,
    /** F33: answer every query NXDOMAIN instead of any records. */
    private val nxDomain: Boolean = false,
) {
    val exchanges = AtomicInteger(0)
    private val socket = DatagramSocket(null).apply {
        reuseAddress = true
        bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), this@BootstrapUdpDns.port))
    }
    private val thread = Thread {
        val buf = ByteArray(1500)
        try {
            while (!socket.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                socket.receive(p)
                val query = DnsMessage(buf.copyOf(p.length))
                val q = query.question
                if (dropAaaa && q.type == Record.TYPE.AAAA) continue // black-holed: never answered
                val builder = DnsMessage.builder()
                    .setId(query.id)
                    .setQuestion(Question(q.name, q.type))
                    .setQrFlag(true)
                if (nxDomain) {
                    builder.setResponseCode(DnsMessage.RESPONSE_CODE.NX_DOMAIN)
                } else when (q.type) {
                    Record.TYPE.A -> a?.let {
                        builder.addAnswer(Record(q.name, Record.TYPE.A, Record.CLASS.IN, 60, A(it), false))
                    }
                    Record.TYPE.AAAA -> aaaa?.let {
                        builder.addAnswer(Record(q.name, Record.TYPE.AAAA, Record.CLASS.IN, 60, AAAA(it), false))
                    }
                    else -> Unit
                }
                exchanges.incrementAndGet()
                val resp = builder.build().toArray()
                socket.send(DatagramPacket(resp, resp.size, p.address, p.port))
            }
        } catch (_: IOException) {
            // socket closed — exit
        }
    }.apply { isDaemon = true; start() }
    fun close() {
        socket.close()
        thread.interrupt()
    }
}

class DnsResolverTest {

    private fun <T> record(steps: MutableMap<T, MutableList<String>>, socket: T, step: String) {
        steps.getOrPut(socket) { java.util.Collections.synchronizedList(mutableListOf()) }.add(step)
    }

    @Test
    fun `plan intercepts for secure dns at the ipv4 resolver on a dual-stack profile`() {
        val p = validProfile().copy(
            dns = DnsPolicy(
                mode = DnsMode.SECURE, secureType = SecureDnsType.DOH,
                secureEndpoint = "https://9.9.9.9/dns-query",
            ),
        )
        assertEquals(DnsPlan(intercept = true, vpnDnsServers = listOf(RESOLVER_V4)), DnsPlanner.plan(p))
    }

    @Test
    fun `plan intercepts tunnel mode only when rules exist and plain servers exist`() {
        val p = validProfile().copy(dns = DnsPolicy(rules = listOf(DnsSplitRule("x.corp", true))))
        assertTrue(DnsPlanner.plan(p).intercept)
        val noServers = p.copy(interfaceConfig = p.interfaceConfig.copy(dnsServers = emptyList()))
        assertFalse(DnsPlanner.plan(noServers).intercept)
    }

    @Test
    fun `plan passes through when the policy is plain or secure dns is not in effect`() {
        val plain = validProfile()
        assertEquals(DnsPlan(), DnsPlanner.plan(plain))
        val blankEndpoint = plain.copy(
            dns = DnsPolicy(mode = DnsMode.SECURE, secureType = SecureDnsType.DOT, secureEndpoint = "  "),
        )
        assertEquals(DnsPlan(), DnsPlanner.plan(blankEndpoint))
    }

    @Test
    fun `plan picks the ipv6 resolver for a profile that routes only ipv6`() {
        val secure = DnsPolicy(mode = DnsMode.SECURE, secureType = SecureDnsType.DOT, secureEndpoint = "9.9.9.9")
        val base = validProfile()
        val ipv6Only = base.copy(
            interfaceConfig = base.interfaceConfig.copy(addresses = listOf("fd00::2/128")),
            peer = base.peer.copy(allowedIps = listOf("::/0")),
            dns = secure,
        )
        assertEquals(DnsPlan(intercept = true, vpnDnsServers = listOf(RESOLVER_V6)), DnsPlanner.plan(ipv6Only))
        // Both families' addresses but only IPv6 routed: the IPv4 address does not survive the family rule.
        val routesOnlyIpv6 = base.copy(peer = base.peer.copy(allowedIps = listOf("::/0")), dns = secure)
        assertEquals(listOf(RESOLVER_V6), DnsPlanner.plan(routesOnlyIpv6).vpnDnsServers)
    }

    @Test
    fun `plan does not intercept when no interface address survives the family rule`() {
        val secure = DnsPolicy(mode = DnsMode.SECURE, secureType = SecureDnsType.DOT, secureEndpoint = "9.9.9.9")
        val base = validProfile()
        val noAddresses = base.copy(interfaceConfig = base.interfaceConfig.copy(addresses = emptyList()), dns = secure)
        assertEquals(DnsPlan(), DnsPlanner.plan(noAddresses))
        // Only an IPv4 address, only IPv6 routed: the zero-address profile of known-issue T-1.
        val mismatched = base.copy(
            interfaceConfig = base.interfaceConfig.copy(addresses = listOf("10.0.0.2/32")),
            peer = base.peer.copy(allowedIps = listOf("::/0")),
            dns = secure,
        )
        assertEquals(DnsPlan(), DnsPlanner.plan(mismatched))
    }

    @Test
    fun `build routes bypass rules to the protected plain upstream`() = runTest {
        var protectedCount = 0
        var udpProtectedCount = 0
        val bypass = FakeUpstream(buildAAnswer("nas.corp.lan"))
        val secure = FakeUpstream(buildAAnswer("secure.example"))
        val controller = DnsResolverController(
            scope = backgroundScope,
            protect = { { protectedCount++; true } },
            protectDatagram = { { udpProtectedCount++; true } },
            underlying = { UnderlyingRoute(listOf(InetAddress.getByName("127.0.0.1"))) },
            ok = OkHttpClient(),
            secureUpstream = { secure },
            plainUpstream = { _, _ -> bypass },
        )
        val p = validProfile().copy(
            dns = DnsPolicy(
                mode = DnsMode.SECURE, secureType = SecureDnsType.DOH,
                secureEndpoint = "https://9.9.9.9/dns-query",
                rules = listOf(DnsSplitRule("corp.lan", bypass = true)),
            ),
        )
        val session = controller.build(p)
        val resp = session.answerInRealTime(buildQuery("nas.corp.lan"))
        assertEquals(bypass.lastIdMirror, resp!![0]) // bypass upstream answered, not secure
        assertEquals(1, bypass.hits)
        assertEquals(0, secure.hits)
        session.close()
    }

    // ---- D1 bootstrap: hostname secure endpoints resolve ONCE at establishment via the
    // underlying resolvers over protected sockets; IP literals skip it; failures are loud.

    private fun secureDotProfile(endpoint: String, rules: List<DnsSplitRule> = emptyList()) =
        validProfile().copy(
            dns = DnsPolicy(
                mode = DnsMode.SECURE,
                secureType = SecureDnsType.DOT,
                secureEndpoint = endpoint,
                rules = rules,
            ),
        )

    @Test
    fun `build bootstraps a hostname DoT endpoint over protected sockets to the underlying resolver`() = runTest {
        val fixturePort = freeDualFamilyPort()
        val fixture = BootstrapUdpDns(fixturePort)
        val udpProtectCalls = AtomicInteger(0)
        val controller = DnsResolverController(
            scope = backgroundScope,
            protect = { { true } },
            protectDatagram = { { udpProtectCalls.incrementAndGet(); true } },
            underlying = { UnderlyingRoute(listOf(InetAddress.getByName("127.0.0.1"))) },
            ok = OkHttpClient(),
            bootstrapPort = fixturePort,
        )
        try {
            controller.build(secureDotProfile("bootstrap.test:853")).close()
            assertEquals(2, fixture.exchanges.get()) // resolved ONCE at establishment: one A + one AAAA
            assertEquals(2, udpProtectCalls.get()) // both bootstrap sockets went through protect(DatagramSocket)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `build fails loud when the underlying network exposes no DNS servers`() = runTest {
        val controller = DnsResolverController(
            scope = backgroundScope,
            protect = { { true } },
            protectDatagram = { { true } },
            underlying = { UnderlyingRoute.NONE },
            ok = OkHttpClient(),
        )
        assertFailsWith<SecureDnsBootstrapException> {
            controller.build(secureDotProfile("bootstrap.test:853"))
        }
    }

    @Test
    fun `build fails loud when the bootstrap resolver returns no addresses`() = runTest {
        val fixturePort = freeDualFamilyPort()
        val fixture = BootstrapUdpDns(fixturePort, a = null, aaaa = null)
        try {
            val controller = DnsResolverController(
                scope = backgroundScope,
                protect = { { true } },
                protectDatagram = { { true } },
                underlying = { UnderlyingRoute(listOf(InetAddress.getByName("127.0.0.1"))) },
                ok = OkHttpClient(),
                bootstrapPort = fixturePort,
            )
            assertFailsWith<SecureDnsBootstrapException> {
                controller.build(secureDotProfile("bootstrap.test:853"))
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `secure dns builds without any protect callbacks when the endpoint is an IP literal`() = runTest {
        val controller = DnsResolverController(
            scope = backgroundScope,
            protect = { null },
            protectDatagram = { null },
            underlying = { UnderlyingRoute(listOf(InetAddress.getByName("127.0.0.1"))) },
            ok = OkHttpClient(),
        )
        // Ruling 2026-09-10: the DoT socket rides the tunnel, so no protect callback is
        // consulted and the build must not demand one.
        controller.build(secureDotProfile("9.9.9.9:853")).close()
    }

    @Test
    fun `a hostname bootstrap still requires the protect datagram callback`() = runTest {
        val controller = DnsResolverController(
            scope = backgroundScope,
            protect = { { true } },
            protectDatagram = { null },
            underlying = { UnderlyingRoute(listOf(InetAddress.getByName("127.0.0.1"))) },
            ok = OkHttpClient(),
        )
        assertFailsWith<SecureDnsBootstrapException> {
            controller.build(secureDotProfile("bootstrap.test:853"))
        }
    }

    /** F33: a controller used only for the underlying-network lookup and the session lifecycle. It never builds. */
    private fun lookupController(
        scope: CoroutineScope,
        servers: List<InetAddress>,
        protectDatagram: () -> ((DatagramSocket) -> Boolean)?,
        bootstrapPort: Int,
    ) = DnsResolverController(
        scope = scope,
        protect = { { true } },
        protectDatagram = protectDatagram,
        underlying = { UnderlyingRoute(servers) },
        ok = OkHttpClient(),
        bootstrapPort = bootstrapPort,
    )

    @Test
    fun `the underlying lookup resolves a hostname over protected sockets to the underlying resolver`() = runTest {
        val fixturePort = freeDualFamilyPort()
        val fixture = BootstrapUdpDns(fixturePort)
        val udpProtectCalls = AtomicInteger(0)
        try {
            val controller = lookupController(
                backgroundScope,
                servers = listOf(InetAddress.getByName("127.0.0.1")),
                protectDatagram = { { udpProtectCalls.incrementAndGet(); true } },
                bootstrapPort = fixturePort,
            )
            assertNull(controller.underlyingLookupProblem("switch.test"))
            assertEquals(2, fixture.exchanges.get()) // one A + one AAAA
            assertEquals(2, udpProtectCalls.get()) // both sockets were protected out of the tunnel
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `the underlying lookup tells an NXDOMAIN answer from a silent resolver`() = runTest {
        val fixturePort = freeDualFamilyPort()
        val fixture = BootstrapUdpDns(fixturePort, nxDomain = true)
        try {
            val controller = lookupController(
                backgroundScope,
                servers = listOf(InetAddress.getByName("127.0.0.1")),
                protectDatagram = { { true } },
                bootstrapPort = fixturePort,
            )
            assertEquals("the underlying resolvers say the name does not exist", controller.underlyingLookupProblem("gone.test"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `the underlying lookup reports a hostname with no address`() = runTest {
        val fixturePort = freeDualFamilyPort()
        val fixture = BootstrapUdpDns(fixturePort, a = null, aaaa = null)
        try {
            val controller = lookupController(
                backgroundScope,
                servers = listOf(InetAddress.getByName("127.0.0.1")),
                protectDatagram = { { true } },
                bootstrapPort = fixturePort,
            )
            assertEquals("no address in the answer", controller.underlyingLookupProblem("nx.invalid"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `the underlying lookup reports a missing server list or protect callback instead of throwing`() = runTest {
        val closedPort = freeDualFamilyPort() // nothing listens: no query may leave without both inputs
        val noServers = lookupController(backgroundScope, emptyList(), { { true } }, closedPort)
        assertNotNull(noServers.underlyingLookupProblem("vpn.example.com"))
        val noProtect = lookupController(backgroundScope, listOf(InetAddress.getByName("127.0.0.1")), { null }, closedPort)
        assertNotNull(noProtect.underlyingLookupProblem("vpn.example.com"))
    }

    @Test
    fun `an underlying lookup that runs out of time reports it instead of throwing`() = runTest {
        // Bound but silent: no reply and no ICMP, so every 400 ms socket attempt times out. Three server
        // entries make one pass 1.2 s, so BootstrapResolver's 2 s budget runs out during its retry. Both
        // families then add nothing (K1): the lookup reports the silent-resolver reason and never throws.
        val silent = DatagramSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        }
        try {
            val controller = lookupController(
                backgroundScope,
                servers = List(3) { InetAddress.getByName("127.0.0.1") },
                protectDatagram = { { true } },
                bootstrapPort = silent.localPort,
            )
            var thrown: Throwable? = null
            val problem = try {
                controller.underlyingLookupProblem("slow.test")
            } catch (t: Throwable) {
                thrown = t
                null
            }
            assertNull(thrown, "the lookup must report, not throw: $thrown")
            assertEquals("no answer from the underlying resolvers", problem)
        } finally {
            silent.close()
        }
    }

    @Test
    fun `an underlying lookup whose resolver drops AAAA still resolves the A answer`() = runTest {
        // K1 (F33 false refusal): three server entries make one AAAA pass 1.2 s, so the pass and its retry
        // outlast BootstrapResolver's 2 s budget. The A answer must still count: the switch pre-check may
        // refuse only a host that no family resolves.
        val fixturePort = freeDualFamilyPort()
        val fixture = BootstrapUdpDns(fixturePort, dropAaaa = true)
        try {
            val controller = lookupController(
                backgroundScope,
                servers = List(3) { InetAddress.getByName("127.0.0.1") },
                protectDatagram = { { true } },
                bootstrapPort = fixturePort,
            )
            assertNull(controller.underlyingLookupProblem("switch.test"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `an underlying lookup of a malformed hostname reports it instead of throwing`() = runTest {
        // minidns rejects a 64-octet label before any socket opens; the generic catch is what keeps that
        // exception from escaping start() and skipping TunnelControl.connect's settle callback.
        val controller = lookupController(
            backgroundScope,
            servers = listOf(InetAddress.getByName("127.0.0.1")),
            protectDatagram = { { true } },
            bootstrapPort = freeDualFamilyPort(),
        )
        val problem = controller.underlyingLookupProblem("a".repeat(64) + ".example.com")
        assertTrue(problem != null && problem.startsWith("lookup failed ("))
    }

    @Test
    fun `ip literal secure endpoints skip the bootstrap resolution entirely`() = runTest {
        val underlyingDnsCalls = AtomicInteger(0)
        val controller = DnsResolverController(
            scope = backgroundScope,
            protect = { { true } },
            protectDatagram = { { true } },
            underlying = {
                underlyingDnsCalls.incrementAndGet()
                UnderlyingRoute(listOf(InetAddress.getByName("127.0.0.1"))) // only the D3 bypass snapshot probes this
            },
            ok = OkHttpClient(),
        )
        controller.build(secureDotProfile("9.9.9.9:853")).close()
        assertEquals(1, underlyingDnsCalls.get()) // bypass snapshot only — no bootstrap probe for a literal
        val doh = validProfile().copy(
            dns = DnsPolicy(
                mode = DnsMode.SECURE,
                secureType = SecureDnsType.DOH,
                secureEndpoint = "https://9.9.9.9/dns-query",
            ),
        )
        controller.build(doh).close()
        assertEquals(2, underlyingDnsCalls.get()) // one bypass snapshot per build, never a bootstrap probe
    }

    @Test
    fun `a failed secure query re-resolves through the re-arm path`() = runTest {
        val fixturePort = freeDualFamilyPort()
        val fixture = BootstrapUdpDns(fixturePort) // answers A=127.0.0.1; nothing listens on :853
        val bypass = FakeUpstream(buildAAnswer("nas.corp.lan"))
        val controller = DnsResolverController(
            scope = backgroundScope,
            protect = { { true } },
            protectDatagram = { { true } },
            underlying = { UnderlyingRoute(listOf(InetAddress.getByName("127.0.0.1"))) },
            ok = OkHttpClient(),
            bootstrapPort = fixturePort,
            plainUpstream = { _, _ -> bypass },
        )
        var session: ResolverSession? = null
        try {
            session = controller.build(
                secureDotProfile("bootstrap.test:853", rules = listOf(DnsSplitRule("corp.lan", bypass = true))),
            )
            assertEquals(2, fixture.exchanges.get()) // establishment resolution
            // A secure query dials 127.0.0.1:853 (refused — the network "switched") → the
            // re-arm path rebuilds the secure channel, which re-runs the bootstrap resolution.
            val resp = session.answerInRealTime(buildQuery("secure.example"))
            assertEquals(4, fixture.exchanges.get()) // re-resolved exactly once more, no loop
            assertTrue(resp != null && (resp[3].toInt() and 0x0F) == 2) // SERVFAIL, never resolved in-tunnel
            // The resolver stays alive for the other channels after the rebuild.
            session.answerInRealTime(buildQuery("nas.corp.lan"))
            assertEquals(1, bypass.hits)
        } finally {
            session?.close()
            fixture.close()
        }
    }

    @Test
    fun `the bypass tcp leg rebuilds after a probe change`() = runTest {
        val truncated = buildTruncatedAnswer("big.example")
        val full = buildAAnswer("big.example")
        var tcpGeneration = 0
        val controller = DnsResolverController(
            scope = backgroundScope,
            protect = { { true } },
            protectDatagram = { { true } },
            underlying = { UnderlyingRoute(listOf(InetAddress.getByName("127.0.0.1"))) },
            ok = OkHttpClient(),
            plainUpstream = { _, _ -> FakeUpstream(truncated) }, // the UDP leg always truncates
            tcpUpstream = { _, _ ->
                if (tcpGeneration++ == 0) NullUpstream else FakeUpstream(full) // dead snapshot leg, fresh rebuilt leg
            },
        )
        val p = validProfile().copy(
            dns = DnsPolicy(mode = DnsMode.TUNNEL, rules = listOf(DnsSplitRule("big.example", bypass = true))),
        )
        val session = controller.build(p)
        try {
            val resp = session.answerInRealTime(buildQuery("big.example"))
            assertNotNull(resp)
            assertFalse(DnsMessage(resp!!).truncated) // the REBUILT tcp leg answered, not the truncated UDP fallback (D11)
        } finally {
            session.close()
        }
    }

    @Test
    fun `the bypass udp leg rebuilds on the re-probed network`() = runTest {
        val first = UnderlyingRoute(listOf(InetAddress.getByName("127.0.0.1")))
        val second = UnderlyingRoute(listOf(InetAddress.getByName("127.0.0.1")))
        val selections = AtomicInteger(0)
        val builds = java.util.Collections.synchronizedList(mutableListOf<UnderlyingRoute?>())
        val controller = DnsResolverController(
            scope = backgroundScope,
            protect = { { true } },
            protectDatagram = { { true } },
            underlying = { if (selections.getAndIncrement() == 0) first else second },
            ok = OkHttpClient(),
            plainUpstream = { _, route ->
                if (route == null) {
                    NullUpstream // the tunnel leg rides the tunnel: no route to pin
                } else {
                    builds += route
                    if (builds.size == 1) NullUpstream else FakeUpstream(buildAAnswer("big.example"))
                }
            },
            tcpUpstream = { _, _ -> NullUpstream }, // the tcp leg never answers
        )
        val p = validProfile().copy(
            dns = DnsPolicy(mode = DnsMode.TUNNEL, rules = listOf(DnsSplitRule("big.example", bypass = true))),
        )
        val session = controller.build(p)
        try {
            // The dead first UDP leg triggers the re-probe; the rebuilt leg answers on the FRESH route.
            val resp = session.answerInRealTime(buildQuery("big.example"))
            assertNotNull(resp)
            assertFalse(DnsMessage(resp!!).truncated)
            assertSame(second, builds[1]) // the rebuilt UDP leg was built with the re-probed network's route
        } finally {
            session.close()
        }
    }

    // ---- S10: one selected underlying network; protected sockets are also bound to it.

    @Test
    fun `bootstrap sockets are protected and then bound to the selected network`() = runTest {
        val fixturePort = freeDualFamilyPort()
        val fixture = BootstrapUdpDns(fixturePort)
        val steps = java.util.concurrent.ConcurrentHashMap<DatagramSocket, MutableList<String>>()
        try {
            val controller = DnsResolverController(
                scope = backgroundScope,
                protect = { { true } },
                protectDatagram = { { s -> record(steps, s, "protect"); true } },
                underlying = {
                    UnderlyingRoute(
                        listOf(InetAddress.getByName("127.0.0.1")),
                        bindDatagram = { s -> record(steps, s, "bind"); true },
                    )
                },
                ok = OkHttpClient(),
                bootstrapPort = fixturePort,
            )
            assertNull(controller.underlyingLookupProblem("switch.test"))
            assertEquals(2, steps.size) // one socket per family
            steps.values.forEach { assertEquals(listOf("protect", "bind"), it.toList()) }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `a socket whose bind fails sends nothing and the lookup moves on`() = runTest {
        // The first two binds fail. Each family still gets its answer from a later socket (the next
        // server entry or the retry), and no socket whose bind failed ever reached the resolver.
        val fixturePort = freeDualFamilyPort()
        val fixture = BootstrapUdpDns(fixturePort)
        val binds = AtomicInteger(0)
        try {
            val controller = DnsResolverController(
                scope = backgroundScope,
                protect = { { true } },
                protectDatagram = { { true } },
                underlying = {
                    UnderlyingRoute(
                        List(2) { InetAddress.getByName("127.0.0.1") },
                        bindDatagram = { binds.incrementAndGet() > 2 },
                    )
                },
                ok = OkHttpClient(),
                bootstrapPort = fixturePort,
            )
            assertNull(controller.underlyingLookupProblem("switch.test"))
            assertEquals(4, binds.get()) // two failed, then one success per family
            assertEquals(2, fixture.exchanges.get()) // only the bound sockets sent a query
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `bypass legs get the selected route and tunnel legs ride the tunnel`() = runTest {
        val route = UnderlyingRoute(listOf(InetAddress.getByName("127.0.0.1")))
        val udpLegs = mutableListOf<Pair<List<String>, UnderlyingRoute?>>()
        val tcpLegs = mutableListOf<Pair<List<String>, UnderlyingRoute?>>()
        val controller = DnsResolverController(
            scope = backgroundScope,
            protect = { { true } },
            protectDatagram = { { true } },
            underlying = { route },
            ok = OkHttpClient(),
            plainUpstream = { servers, bypass -> udpLegs += servers to bypass; NullUpstream },
            tcpUpstream = { servers, bypass -> tcpLegs += servers to bypass; NullUpstream },
        )
        val p = validProfile().copy(
            dns = DnsPolicy(mode = DnsMode.TUNNEL, rules = listOf(DnsSplitRule("corp.lan", bypass = true))),
        )
        controller.build(p).close()
        // Bypass UDP gets the route; the profile's own servers ride the tunnel with none.
        assertEquals(listOf(listOf("127.0.0.1") to route, listOf("1.1.1.1", "9.9.9.9") to null), udpLegs)
        // Only the bypass TCP leg goes through the injected factory, with the route.
        assertEquals(listOf<Pair<List<String>, UnderlyingRoute?>>(listOf("127.0.0.1") to route), tcpLegs)
    }

    @Test
    fun `the bypass re-probe selects the underlying network again`() = runTest {
        val truncated = buildTruncatedAnswer("big.example")
        val full = buildAAnswer("big.example")
        val first = UnderlyingRoute(listOf(InetAddress.getByName("127.0.0.1")))
        val second = UnderlyingRoute(listOf(InetAddress.getByName("127.0.0.2")))
        val selections = AtomicInteger(0)
        val tcpRoutes = java.util.Collections.synchronizedList(mutableListOf<UnderlyingRoute?>())
        val controller = DnsResolverController(
            scope = backgroundScope,
            protect = { { true } },
            protectDatagram = { { true } },
            underlying = { if (selections.getAndIncrement() == 0) first else second },
            ok = OkHttpClient(),
            plainUpstream = { _, _ -> FakeUpstream(truncated) }, // the UDP leg always truncates
            tcpUpstream = { _, route ->
                tcpRoutes += route
                if (tcpRoutes.size == 1) NullUpstream else FakeUpstream(full) // dead first leg; the rebuilt leg answers
            },
        )
        val p = validProfile().copy(
            dns = DnsPolicy(mode = DnsMode.TUNNEL, rules = listOf(DnsSplitRule("big.example", bypass = true))),
        )
        val session = controller.build(p)
        try {
            assertNotNull(session.answerInRealTime(buildQuery("big.example")))
            assertEquals(listOf(first, second), tcpRoutes.toList()) // the rebuilt leg rides the fresh selection
            assertEquals(2, selections.get()) // one at the build, one at the re-probe
        } finally {
            session.close()
        }
    }

    @Test
    fun `the tunnel-start bootstrap protects then binds each socket to the selected network`() = runTest {
        val fixturePort = freeDualFamilyPort()
        val fixture = BootstrapUdpDns(fixturePort)
        val steps = java.util.concurrent.ConcurrentHashMap<DatagramSocket, MutableList<String>>()
        val controller = DnsResolverController(
            scope = backgroundScope,
            protect = { { true } },
            protectDatagram = { { s -> record(steps, s, "protect"); true } },
            underlying = {
                UnderlyingRoute(
                    listOf(InetAddress.getByName("127.0.0.1")),
                    bindDatagram = { s -> record(steps, s, "bind"); true },
                )
            },
            ok = OkHttpClient(),
            bootstrapPort = fixturePort,
        )
        try {
            controller.build(secureDotProfile("bootstrap.test:853")).close()
            assertEquals(2, steps.size) // one bootstrap socket per family, all on the selected route
            steps.values.forEach { assertEquals(listOf("protect", "bind"), it.toList()) }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `the default bypass udp leg protects then binds its socket to the selected network`() = runTest {
        val steps = java.util.concurrent.ConcurrentHashMap<DatagramSocket, MutableList<String>>()
        val controller = DnsResolverController(
            scope = backgroundScope,
            protect = { { true } },
            protectDatagram = { { s -> record(steps, s, "protect"); true } },
            underlying = {
                UnderlyingRoute(
                    listOf(InetAddress.getByName("127.0.0.1")),
                    bindDatagram = { s -> record(steps, s, "bind"); false }, // refused bind: the attempt is skipped, nothing is sent
                )
            },
            ok = OkHttpClient(),
        )
        val p = validProfile().copy(
            dns = DnsPolicy(mode = DnsMode.TUNNEL, rules = listOf(DnsSplitRule("corp.lan", bypass = true))),
        )
        val session = controller.build(p)
        try {
            session.answerInRealTime(buildQuery("nas.corp.lan")) // drives the bypass query; the answer value is irrelevant here
            assertTrue(steps.isNotEmpty())
            steps.values.forEach { assertEquals(listOf("protect", "bind"), it.toList()) }
        } finally {
            session.close()
        }
    }

    @Test
    fun `the default bypass tcp leg protects then binds its socket to the selected network`() = runTest {
        val steps = java.util.concurrent.ConcurrentHashMap<Socket, MutableList<String>>()
        val controller = DnsResolverController(
            scope = backgroundScope,
            protect = { { s -> record(steps, s, "protect"); true } },
            protectDatagram = { { true } },
            underlying = {
                UnderlyingRoute(
                    listOf(InetAddress.getByName("127.0.0.1")),
                    bindStream = { s -> record(steps, s, "bind"); true },
                )
            },
            ok = OkHttpClient(),
            // UDP truncates so the truncated-UDP retry runs the production plainTcpUpstream (tcpUpstream stays null).
            plainUpstream = { _, _ -> FakeUpstream(buildTruncatedAnswer("big.example")) },
        )
        val p = validProfile().copy(
            dns = DnsPolicy(mode = DnsMode.TUNNEL, rules = listOf(DnsSplitRule("big.example", bypass = true))),
        )
        val session = controller.build(p)
        try {
            session.answerInRealTime(buildQuery("big.example")) // the tcp connect to 127.0.0.1:53 fails; the wiring is what we pin
            assertTrue(steps.isNotEmpty())
            steps.values.forEach { assertEquals(listOf("protect", "bind"), it.toList()) }
        } finally {
            session.close()
        }
    }

    @Test
    fun `a session accepts queries only from the interface's own addresses and loopback`() = runTest {
        val controller = DnsResolverController(
            scope = backgroundScope,
            protect = { null },
            protectDatagram = { null },
            underlying = { UnderlyingRoute(listOf(InetAddress.getByName("127.0.0.1"))) },
            ok = OkHttpClient(),
            secureUpstream = { FakeUpstream(buildAAnswer("secure.example")) },
        )
        val session = controller.build(secureDotProfile("9.9.9.9:853"))
        try {
            assertTrue(session.acceptsSource(InetAddress.getByName("10.0.0.2")))
            assertTrue(session.acceptsSource(InetAddress.getByName("fd00::2")))
            assertTrue(session.acceptsSource(InetAddress.getLoopbackAddress()))
            assertFalse(session.acceptsSource(InetAddress.getByName("10.0.0.3")))
            // Neither resolver address is an interface address any more (B1), so neither is a client.
            assertFalse(session.acceptsSource(InetAddress.getByName(RESOLVER_V4)))
            assertFalse(session.acceptsSource(InetAddress.getByName(RESOLVER_V6)))
        } finally {
            session.close()
        }
    }

    /** An upstream that only counts how often its owner closed it. */
    private class CloseCounter : dev.vaayil.core.dns.DnsUpstream {
        var closes = 0
        override suspend fun query(queryWire: ByteArray): ByteArray? = null
        override fun close() {
            closes++
        }
    }

    private fun sessionOf(id: String, upstream: dev.vaayil.core.dns.DnsUpstream) =
        ResolverSession(id, DnsResponder({ upstream }), listOf(upstream))

    @Test
    fun `stage, commit, rollback and deactivate decide which session answers and which retire`() = runTest {
        val controller = lookupController(backgroundScope, emptyList(), { null }, bootstrapPort = 53)
        val a = CloseCounter()
        val b = CloseCounter()
        val c = CloseCounter()
        val sessionA = sessionOf("a", a)
        val sessionB = sessionOf("b", b)
        val sessionC = sessionOf("c", c)

        controller.stage(sessionA)
        controller.commit()
        assertSame(sessionA, controller.current)

        controller.stage(sessionB) // a switch: nothing answers while the stage is pending, A waits for the outcome
        assertSame(sessionB, controller.current)
        assertEquals(0, a.closes)
        controller.rollback() // the switch failed and A's tunnel survived
        assertSame(sessionA, controller.current)
        assertEquals(1, b.closes)
        assertEquals(0, a.closes)

        controller.stage(sessionC)
        controller.commit() // the switch succeeded
        assertSame(sessionC, controller.current)
        assertEquals(1, a.closes)

        controller.stage(null) // a switch to a plain profile: nothing answers
        assertNull(controller.current)
        controller.deactivate() // a stop in the middle of it: everything retires
        assertNull(controller.current)
        controller.rollback() // nothing is staged any more: a no-op
        assertNull(controller.current)
        assertEquals(listOf(1, 1, 1), listOf(a.closes, b.closes, c.closes)) // each session retired exactly once
    }

    @Test
    fun `stage silences both sessions including cached and bypass queries`() = runTest {
        val controller = lookupController(backgroundScope, emptyList(), { null }, bootstrapPort = 53)
        val pipe = FakePipe()
        controller.ensureChannel { pipe }
        val tunnel = FakeUpstream(buildAAnswer("example.com"))
        val bypass = FakeUpstream(buildAAnswer("nas.corp.lan"))
        val cached = CachingUpstream(tunnel, DnsResponseCache())
        val sessionA = ResolverSession(
            "a",
            DnsResponder({ name -> if (name == "nas.corp.lan") bypass else cached }),
            listOf(cached, bypass),
        )
        controller.stage(sessionA)
        controller.commit()
        // Control: A answers on the channel, and a repeat query is served from the cache (one upstream hit).
        pipe.deliver(queryFrame(1, "10.0.0.2", buildQuery("example.com", id = 1)))
        assertNotNull(pipe.nextSent())
        pipe.deliver(queryFrame(2, "10.0.0.2", buildQuery("example.com", id = 2)))
        assertNotNull(pipe.nextSent())
        assertEquals(1, tunnel.hits)

        // A pending stage answers nothing: neither the repeat the cache would serve nor the bypass leg may send a
        // frame or touch an upstream.
        controller.stage(sessionOf("b", FakeUpstream(buildAAnswer("example.com"))))
        assertNull(controller.answererNow())
        pipe.deliver(queryFrame(3, "10.0.0.2", buildQuery("example.com", id = 3)))
        pipe.deliver(queryFrame(4, "10.0.0.2", buildQuery("nas.corp.lan", id = 4)))
        assertNull(pipe.nextSent(200))
        assertEquals(1, tunnel.hits)
        assertEquals(0, bypass.hits)

        controller.deactivate()
        pipe.endOfFile()
        pipe.awaitClosed()
    }

    @Test
    fun `rollback answers with a fresh job and does not revive the old query`() = runTest {
        val controller = lookupController(backgroundScope, emptyList(), { null }, bootstrapPort = 53)
        val pipe = FakePipe()
        controller.ensureChannel { pipe }
        val gate = CompletableDeferred<Unit>()
        val a = GatedUpstream(gate = gate)
        val b = GatedUpstream()
        val sessionA = sessionOf("a", a)
        val sessionB = sessionOf("b", b)

        controller.stage(sessionA)
        controller.commit() // A answers from here on
        val oldJob = sessionA.answerJob

        // One query is parked inside A's upstream when the switch starts.
        pipe.deliver(queryFrame(1, "10.0.0.2", buildQuery("example.com", id = 1)))
        waitUntil("A entered its upstream") { a.entered.get() == 1 }

        controller.stage(sessionB) // the stage quiesces both sessions' answers
        assertTrue(oldJob.isCancelled)
        assertNull(controller.answererNow())
        controller.rollback() // the switch failed: A answers again on a fresh Job, B retires
        val restoredJob = sessionA.answerJob
        assertNotSame(oldJob, restoredJob)
        assertFalse(restoredJob.isCancelled)
        assertEquals(0, a.closes)
        assertEquals(1, b.closes)

        gate.complete(Unit) // the parked query is let go: its epoch is gone and it must never answer
        assertNull(pipe.nextSent(200))

        // A fresh query is answered by the restored session.
        pipe.deliver(queryFrame(2, "10.0.0.2", buildQuery("example.com", id = 2)))
        val reply = pipe.nextSent()
        assertNotNull(reply)
        assertEquals(listOf<Byte>(0, 0, 0, 2), reply.take(4))
        assertEquals(2, DnsMessage(reply.copyOfRange(4, reply.size)).id)
        assertEquals(2, a.entered.get())

        controller.deactivate()
        assertEquals(1, a.closes)
        pipe.endOfFile()
        pipe.awaitClosed()
    }

    @Test
    fun `commit and deactivate retire sessions once`() = runTest {
        val controller = lookupController(backgroundScope, emptyList(), { null }, bootstrapPort = 53)
        val a = CloseCounter()
        val b = CloseCounter()
        val c = CloseCounter()
        val d = CloseCounter()
        val sessionA = sessionOf("a", a)
        val sessionB = sessionOf("b", b)
        val sessionC = sessionOf("c", c)
        val sessionD = sessionOf("d", d)

        controller.stage(sessionA)
        controller.commit() // A answers from here on
        assertFalse(sessionA.answerJob.isCancelled)

        controller.stage(sessionB)
        controller.commit() // the switch succeeded: A retires exactly once
        assertEquals(1, a.closes)
        assertEquals(0, b.closes)
        assertTrue(sessionA.answerJob.isCancelled)
        assertFalse(sessionB.answerJob.isCancelled)

        controller.stage(null) // a switch to a plain profile: nothing is staged to answer
        assertNull(controller.current)
        assertTrue(sessionB.answerJob.isCancelled)
        controller.rollback() // the switch failed: B answers again on a fresh Job
        assertSame(sessionB, controller.current)
        assertFalse(sessionB.answerJob.isCancelled)
        assertEquals(0, b.closes)

        controller.stage(sessionC)
        controller.stage(sessionD) // two consecutive stages: B retires as the pending stage commits, D is staged
        assertEquals(1, b.closes)
        assertEquals(0, c.closes)
        assertTrue(sessionC.answerJob.isCancelled)

        controller.deactivate() // cancels and retires the staged candidate and the standby, each exactly once
        assertEquals(listOf(1, 1, 1, 1), listOf(a.closes, b.closes, c.closes, d.closes))
        assertNull(controller.current)
        assertTrue(sessionC.answerJob.isCancelled)
        assertTrue(sessionD.answerJob.isCancelled)

        controller.commit() // a late commit and a late rollback: nothing is staged, nothing revives
        assertNull(controller.current)
        controller.rollback()
        assertNull(controller.current)
        assertEquals(listOf(1, 1, 1, 1), listOf(a.closes, b.closes, c.closes, d.closes))
    }

    @Test
    fun `the dns channel opens once, carries both resolver addresses and reopens after it dies`() = runTest {
        val controller = lookupController(backgroundScope, emptyList(), { null }, bootstrapPort = 53)
        val pipes = mutableListOf<FakePipe>()
        val asked = mutableListOf<List<InetAddress>>()
        val open: (List<InetAddress>) -> DatagramPipe = { addresses -> asked += addresses; FakePipe().also { pipes += it } }
        controller.ensureChannel(open)
        controller.ensureChannel(open) // live: not opened again
        assertEquals(1, pipes.size)
        assertEquals(listOf(InetAddress.getByName(RESOLVER_V4), InetAddress.getByName(RESOLVER_V6)), asked.single())

        pipes[0].endOfFile()
        pipes[0].awaitClosed() // the reader saw end-of-file, released the channel and closed its end
        controller.ensureChannel(open)
        assertEquals(2, pipes.size)
        pipes[1].endOfFile()
        pipes[1].awaitClosed()
    }

    @Test
    fun `a query on the dns channel is answered by the current session`() = runTest {
        val upstream = FakeUpstream(buildAAnswer("example.com", id = 0x0707))
        val controller = lookupController(backgroundScope, emptyList(), { null }, bootstrapPort = 53)
        val pipe = FakePipe()
        controller.ensureChannel { pipe }
        controller.stage(sessionOf("a", upstream))
        controller.commit()
        // seq 42 · an IPv4 client (10.0.0.2) · the query, as libwg-go frames it
        pipe.deliver(byteArrayOf(0, 0, 0, 42, 4, 10, 0, 0, 2) + buildQuery("example.com", id = 0x0707))
        val reply = pipe.nextSent()
        assertNotNull(reply)
        assertEquals(listOf<Byte>(0, 0, 0, 42), reply.take(4))
        assertEquals(0x0707, DnsMessage(reply.copyOfRange(4, reply.size)).id)
        assertEquals(1, upstream.hits)
        controller.deactivate() // a plain EOF with no session must not schedule a reopen
        pipe.endOfFile()
        pipe.awaitClosed()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a dead dns channel reopens in place while a session is current`() = runTest {
        val controller = lookupController(backgroundScope, emptyList(), { null }, bootstrapPort = 53)
        controller.stage(sessionOf("a", FakeUpstream(buildAAnswer("example.com"))))
        controller.commit()
        var noticed = 0
        controller.onDnsNotAnswering = { noticed++ }
        val pipes = mutableListOf<FakePipe>()
        controller.ensureChannel { FakePipe().also { pipes += it } }
        pipes[0].endOfFile()
        pipes[0].awaitClosed() // the reader released it; the reopen is scheduled
        runCurrent()
        assertEquals(2, pipes.size) // the first reopen runs at once, through the same opener
        assertEquals(0, noticed)
        pipes[1].endOfFile()
        pipes[1].awaitClosed()
        controller.deactivate() // the session ends before the second attempt's 1 s backoff
        advanceTimeBy(2_000); runCurrent()
        assertEquals(2, pipes.size) // no current session: no further reopen
        assertEquals(0, noticed)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `three failed reopens mark the session dns-not-answering and stop trying`() = runTest {
        val controller = lookupController(backgroundScope, emptyList(), { null }, bootstrapPort = 53)
        controller.stage(sessionOf("a", FakeUpstream(buildAAnswer("example.com"))))
        controller.commit()
        var noticed = 0
        controller.onDnsNotAnswering = { noticed++ }
        var opens = 0
        val first = FakePipe()
        controller.ensureChannel { opens++; if (opens == 1) first else throw IOException("gone") }
        first.endOfFile()
        first.awaitClosed()
        advanceTimeBy(3_000); runCurrent() // attempts at 0 s, 1 s and 2 s all throw
        assertEquals(4, opens) // one initial open, three reopens
        assertEquals(1, noticed)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a channel that a newer one replaced is never reopened`() = runTest {
        val controller = lookupController(backgroundScope, emptyList(), { null }, bootstrapPort = 53)
        controller.stage(sessionOf("a", FakeUpstream(buildAAnswer("example.com"))))
        controller.commit()
        val pipes = mutableListOf<FakePipe>()
        val open: (List<InetAddress>) -> DatagramPipe = { FakePipe().also { pipes += it } }
        controller.ensureChannel(open)
        pipes[0].endOfFile()
        pipes[0].awaitClosed() // the reopen is scheduled but has not run
        controller.ensureChannel(open) // a start opened a fresh channel first
        runCurrent() // the scheduled reopen sees a live channel and stands down
        assertEquals(2, pipes.size)
        controller.deactivate()
    }

    // ---- A1: a real plain upstream behind the framed transport --------------------------------

    @Test
    fun `stage and deactivate stop a blocking plain query before its next server`() = runBlocking {
        stopsBlockingPlainQuery { controller ->
            controller.stage(sessionOf("b", FakeUpstream(buildAAnswer("example.com"))))
        }
        stopsBlockingPlainQuery { controller ->
            controller.deactivate()
        }
    }

    @Test
    fun `rollback never resumes the old blocked epoch`() = runBlocking {
        val port = freeDualFamilyPort()
        val server = BlockingUdpDns("127.0.0.1", port, holdFirst = true)
        val pipe = FakePipe()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val controller = lookupController(scope, emptyList(), { null }, bootstrapPort = 53)
            controller.ensureChannel { pipe }
            val upstream = PlainUdpUpstream(listOf(InetAddress.getByName("127.0.0.1")), port)
            val session = ResolverSession("a", DnsResponder({ upstream }), listOf(upstream))
            controller.stage(session)
            controller.commit()

            // One query is parked inside the server's blocking receive when the switch starts.
            pipe.deliver(queryFrame(1, "10.0.0.2", buildQuery("example.com", id = 1)))
            server.awaitQuery()
            controller.stage(sessionOf("b", FakeUpstream(buildAAnswer("example.com"))))
            controller.rollback() // the switch failed: A answers again, on a fresh Job
            assertFalse(session.answerJob.isCancelled)

            server.release() // the parked receive returns: its epoch is gone and it must never answer
            assertNull(pipe.nextSent(200))

            // Only a distinct fresh query may send.
            pipe.deliver(queryFrame(2, "10.0.0.2", buildQuery("example.com", id = 2)))
            val reply = pipe.nextSent()
            assertNotNull(reply)
            assertEquals(listOf<Byte>(0, 0, 0, 2), reply.take(4))
            assertEquals(2, DnsMessage(reply.copyOfRange(4, reply.size)).id)
        } finally {
            scope.cancel()
            server.close()
            pipe.endOfFile()
        }
    }

    /**
     * A1: [action] runs while a real plain upstream sits inside its first server's blocking receive, behind the
     * framed transport. A stage (which only quiesces) and a deactivate (which closes) must both stop the pass before
     * its second server: no new socket, no send there, and no answer frame back.
     */
    private fun stopsBlockingPlainQuery(action: (DnsResolverController) -> Unit) {
        val port = freeDualFamilyPort()
        val first = BlockingUdpDns("127.0.0.1", port, holdFirst = true)
        val second = BlockingUdpDns("127.0.0.2", port, holdFirst = false)
        val pipe = FakePipe()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val controller = lookupController(scope, emptyList(), { null }, bootstrapPort = 53)
            controller.ensureChannel { pipe }
            val creations = AtomicInteger(0)
            val upstream = PlainUdpUpstream(
                listOf(InetAddress.getByName("127.0.0.1"), InetAddress.getByName("127.0.0.2")), port,
                socketFactory = { creations.incrementAndGet(); DatagramSocket() },
            )
            controller.stage(ResolverSession("a", DnsResponder({ upstream }), listOf(upstream)))
            controller.commit()
            pipe.deliver(queryFrame(1, "10.0.0.2", buildQuery("example.com", id = 1)))
            first.awaitQuery()
            assertEquals(1, creations.get())
            action(controller)
            // The pre-fix pass opens its second socket the moment server 1's 400 ms receive window expires.
            assertQuietFor(700, "the stopped pass") { creations.get() == 1 && second.hits.get() == 0 }
            assertNull(pipe.nextSent(200))
        } finally {
            scope.cancel()
            first.close()
            second.close()
            pipe.endOfFile()
        }
    }

    /** A1: bounded negative wait — "nothing happened" has no event to await, so watch [ms] for it. */
    private fun assertQuietFor(ms: Long, what: String, check: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms)
        while (System.nanoTime() < deadline) {
            if (!check()) throw AssertionError("$what reached the network during the wait")
            Thread.sleep(5)
        }
    }

}

/**
 * A real UDP "resolver" on [address]:[port] for the A1 integration cases: it counts the queries it receives and
 * answers each with a txid-mirrored A answer, but holds the FIRST one until [release] when [holdFirst] is set — so
 * the resolver's in-flight attempt can sit in a blocking receive while the test moves the session lifecycle.
 */
private class BlockingUdpDns(address: String, port: Int, private val holdFirst: Boolean) {
    val hits = AtomicInteger(0)
    private val firstArrived = CountDownLatch(1)
    private val firstReleased = CountDownLatch(1)
    private val socket = DatagramSocket(null).apply {
        reuseAddress = true
        bind(InetSocketAddress(InetAddress.getByName(address), port))
    }

    init {
        Thread {
            val buffer = ByteArray(1500)
            while (!socket.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: IOException) {
                    return@Thread
                }
                val index = hits.incrementAndGet()
                if (holdFirst && index == 1) {
                    firstArrived.countDown()
                    firstReleased.await(10, TimeUnit.SECONDS)
                }
                if (socket.isClosed) return@Thread
                val parsed = DnsMessage(packet.data.copyOf(packet.length))
                val answer = parsed.asBuilder()
                    .setQrFlag(true)
                    .addAnswer(Record(parsed.question.name, Record.TYPE.A, Record.CLASS.IN, 60, A("192.0.2.1"), false))
                    .build()
                    .toArray()
                runCatching { socket.send(DatagramPacket(answer, answer.size, packet.socketAddress)) }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    fun awaitQuery() {
        check(firstArrived.await(5, TimeUnit.SECONDS)) { "the fixture server never saw a query" }
    }

    fun release() = firstReleased.countDown()

    fun close() {
        socket.close()
        firstReleased.countDown()
    }
}
