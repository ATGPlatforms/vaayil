package dev.vaayil.core.dns

import dev.vaayil.core.model.SecureDnsType
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.minidns.dnsmessage.DnsMessage
import org.minidns.dnsmessage.Question
import org.minidns.dnsname.DnsName
import org.minidns.record.A
import org.minidns.record.AAAA
import org.minidns.record.Record

/**
 * UDP "system resolver" fixture that PARSES the question and answers per type: A queries
 * get the configured IPv4, AAAA queries the configured IPv6 (null = NOERROR, no answers).
 * Mirrors UpstreamTest's FakeUdpDns pattern: ephemeral dual-family port, mirrors the txid.
 */
private class TypeAwareUdpDns(
    private val port: Int,
    private val a: String? = "127.0.0.1",
    private val aaaa: String? = null,
    /** K1: AAAA queries get no reply at all, so the AAAA attempt can only time out. */
    private val dropAaaa: Boolean = false,
    /** F33: answer every query NXDOMAIN instead of any records. */
    private val nxDomain: Boolean = false,
    /** J2: an RD-sensitive recursive resolver — an RD=0 query gets QR=1 REFUSED, no records. */
    private val requireRecursion: Boolean = false,
) {
    val exchanges = AtomicInteger(0)

    /** Every parsed inbound query, for flag assertions; the test clears it before the real run. */
    val received = ConcurrentLinkedQueue<DnsMessage>()
    private val socket = DatagramSocket(null).apply {
        reuseAddress = true
        bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), this@TypeAwareUdpDns.port))
    }
    private val thread = Thread {
        val buf = ByteArray(1500)
        try {
            while (!socket.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                socket.receive(p)
                val query = DnsMessage(buf.copyOf(p.length))
                received.add(query)
                val q = query.question
                if (dropAaaa && q.type == Record.TYPE.AAAA) continue // black-holed: never answered
                val builder = DnsMessage.builder()
                    .setId(query.id)
                    .setQuestion(Question(q.name, q.type))
                    .setQrFlag(true)
                if (requireRecursion && !query.recursionDesired) {
                    builder.setResponseCode(DnsMessage.RESPONSE_CODE.REFUSED)
                } else if (nxDomain) {
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
        thread.join(1_000)
    }
}

/** UDP fixture that swallows the FIRST query of each type and answers the second (one lost datagram). */
private class DropFirstUdpDns(port: Int, private val a: String = "127.0.0.1") {
    val exchanges = AtomicInteger(0)
    private val seen = mutableSetOf<Record.TYPE>()
    private val socket = DatagramSocket(null).apply {
        reuseAddress = true
        bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
    }
    private val thread = Thread {
        val buf = ByteArray(1500)
        try {
            while (!socket.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                socket.receive(p)
                val query = DnsMessage(buf.copyOf(p.length))
                val q = query.question
                exchanges.incrementAndGet()
                if (seen.add(q.type)) continue // first of this type: lost on the wire
                val builder = DnsMessage.builder().setId(query.id).setQuestion(Question(q.name, q.type)).setQrFlag(true)
                if (q.type == Record.TYPE.A) builder.addAnswer(Record(q.name, Record.TYPE.A, Record.CLASS.IN, 60, A(a), false))
                val resp = builder.build().toArray()
                socket.send(DatagramPacket(resp, resp.size, p.address, p.port))
            }
        } catch (_: IOException) {
        }
    }.apply { isDaemon = true; start() }
    fun close() { socket.close(); thread.interrupt() }
}

/** TCP server that accepts and immediately closes: proves a connection was DIALED to it
    (the pinned address) while never completing TLS — cheap, fast, no trust store needed. */
private class AcceptCloseServer(port: Int) {
    val accepted = AtomicInteger(0)
    private val server = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
    private val thread = Thread {
        while (!server.isClosed) {
            val conn = runCatching { server.accept() }.getOrNull() ?: break
            accepted.incrementAndGet()
            runCatching { conn.close() }
        }
    }.apply { isDaemon = true; start() }
    fun close() {
        server.close()
    }
}

/** Serves [queries] exchanges: mirrors the txid, answers with a poisoned A record and a
    question nobody asked — an off-path spoofer's reply (D4). */
private class MismatchedReplyUdpDns(port: Int, private val queries: Int) {
    private val server = DatagramSocket(null).apply {
        reuseAddress = true
        bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
    }
    val seenIds = mutableListOf<Int>()
    private val thread = Thread {
        val buf = ByteArray(65535)
        try {
            repeat(queries) {
                val p = DatagramPacket(buf, buf.size)
                server.receive(p)
                val q = buf.copyOf(p.length)
                seenIds += ((q[0].toInt() and 0xFF) shl 8) or (q[1].toInt() and 0xFF)
                val asked = DnsMessage(q)
                val reply = DnsMessage.builder()
                    .setId(asked.id)
                    .setQuestion(Question(DnsName.from("attacker.example"), asked.question.type))
                    .build()
                    .asBuilder()
                    .setQrFlag(true)
                    // Without question validation this answer would become the bootstrap pin.
                    .addAnswer(
                        Record(
                            DnsName.from("attacker.example"), Record.TYPE.A, Record.CLASS.IN, 60,
                            A("192.0.2.99"), false,
                        ),
                    )
                    .build()
                    .toArray()
                server.send(DatagramPacket(reply, reply.size, p.address, p.port))
            }
        } catch (_: IOException) {
        }
    }

    fun start() { thread.start() }
    fun close() { server.close() }
}

private fun buildAQuery(name: String): ByteArray = DnsMessage.builder()
    .setId(0x1234)
    .setQuestion(Question(DnsName.from(name), Record.TYPE.A))
    .build()
    .toArray()

class BootstrapTest {

    // ---- BootstrapResolver: plain UDP A/AAAA over protected sockets (the D1 bootstrap path)

    @Test
    fun `bootstrap resolver answers A and AAAA from the underlying fixture`() = runBlocking {
        val port = freeLoopbackPort()
        val fixture = TypeAwareUdpDns(port, a = "127.0.0.1", aaaa = "::1")
        try {
            val resolved = BootstrapResolver(
                servers = listOf(InetAddress.getByName("127.0.0.1")),
                protect = { true },
                port = port,
            ).resolve("bootstrap.test")
            assertEquals(listOf("127.0.0.1", "0:0:0:0:0:0:0:1"), resolved)
        } finally {
            fixture.close()
        }
    }

    /** J2: recursive resolvers refuse RD=0 questions — the bootstrap query must ask for recursion. */
    @Test
    fun `bootstrap requests recursion for both address families`() = runBlocking {
        val port = freeLoopbackPort()
        val fixture = TypeAwareUdpDns(
            port,
            a = "192.0.2.10",
            aaaa = "2001:db8::10",
            requireRecursion = true,
        )
        try {
            // RD=0 control: an RD-sensitive recursive resolver refuses it, no records.
            val control = DnsMessage.builder()
                .setId(0x4242)
                .setQuestion(Question(DnsName.from("recursion.test"), Record.TYPE.A))
                .build()
                .toArray()
            val controlReply = DatagramSocket().use { s ->
                s.soTimeout = 2_000
                s.send(DatagramPacket(control, control.size, InetAddress.getByName("127.0.0.1"), port))
                val buf = ByteArray(1500)
                val p = DatagramPacket(buf, buf.size)
                s.receive(p)
                buf.copyOf(p.length)
            }
            assertEquals(DnsMessage.RESPONSE_CODE.REFUSED, DnsMessage(controlReply).responseCode)

            fixture.received.clear()
            val outcome = BootstrapResolver(
                servers = listOf(InetAddress.getByName("127.0.0.1")),
                protect = { true },
                port = port,
            ).resolveOutcome("recursion.test")
            assertEquals(
                BootstrapLookup.Resolved(listOf("192.0.2.10", "2001:db8:0:0:0:0:0:10")),
                outcome,
            )
            val received = fixture.received.toList()
            assertEquals(setOf(Record.TYPE.A, Record.TYPE.AAAA), received.map { it.question.type }.toSet())
            assertTrue(received.all { it.recursionDesired })
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `bootstrap resolver prefers the reachable family when the other answers empty`() = runBlocking {
        val port = freeLoopbackPort()
        val fixture = TypeAwareUdpDns(port, a = "127.0.0.1", aaaa = null)
        try {
            val resolved = BootstrapResolver(
                servers = listOf(InetAddress.getByName("127.0.0.1")),
                protect = { true },
                port = port,
            ).resolve("bootstrap.test")
            assertEquals(listOf("127.0.0.1"), resolved)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `bootstrap resolver protects every query socket`() = runBlocking {
        val port = freeLoopbackPort()
        val fixture = TypeAwareUdpDns(port)
        val protectedDatagrams = AtomicInteger(0)
        try {
            BootstrapResolver(
                servers = listOf(InetAddress.getByName("127.0.0.1")),
                protect = { protectedDatagrams.incrementAndGet(); true },
                port = port,
            ).resolve("bootstrap.test")
            assertEquals(2, protectedDatagrams.get()) // one A + one AAAA query, each on a protected socket
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `bootstrap resolver returns empty when nothing answers`() = runBlocking {
        val deadPort = freeLoopbackPort() // nothing listens here
        val resolved = BootstrapResolver(
            servers = listOf(InetAddress.getByName("127.0.0.1")),
            protect = { true },
            port = deadPort,
            timeoutMs = 600,
        ).resolve("bootstrap.test")
        assertTrue(resolved.isEmpty())
    }

    @Test
    fun `a family that never answers adds nothing instead of failing the lookup`() = runBlocking {
        // K1: the resolver answers A but black-holes AAAA. The AAAA attempt and its retry (2 x 400 ms)
        // outlast the 600 ms budget; the A answer must survive instead of the timeout failing both.
        val port = freeLoopbackPort()
        val fixture = TypeAwareUdpDns(port, a = "127.0.0.1", dropAaaa = true)
        try {
            val resolved = BootstrapResolver(
                servers = listOf(InetAddress.getByName("127.0.0.1")),
                protect = { true },
                port = port,
                timeoutMs = 600,
            ).resolve("bootstrap.test")
            assertEquals(listOf("127.0.0.1"), resolved)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `a lookup that no family answers is empty and the secure upstream fails loud`() = runBlocking {
        // Bound but silent: no reply and no ICMP, so both families time out. Empty is the only
        // outcome, and buildSecureUpstream turns it into the existing loud D1 error.
        val silent = DatagramSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        }
        try {
            val resolver = BootstrapResolver(
                servers = listOf(InetAddress.getByName("127.0.0.1")),
                protect = { true },
                port = silent.localPort,
                timeoutMs = 600,
            )
            assertTrue(resolver.resolve("silent.test").isEmpty())
            val err = runCatching {
                buildSecureUpstream(
                    type = SecureDnsType.DOT,
                    endpoint = "dot.example.test:853",
                    ok = OkHttpClient(),
                    bootstrapResolve = { resolver.resolve(it) },
                )
            }.exceptionOrNull()
            assertTrue("got $err", err is SecureDnsBootstrapException)
        } finally {
            silent.close()
        }
    }

    @Test
    fun `bootstrap resolver short-circuits without queries when there are no servers`() = runBlocking {
        var protectedDatagrams = 0
        val resolved = BootstrapResolver(
            servers = emptyList(),
            protect = { protectedDatagrams++; true },
        ).resolve("bootstrap.test")
        assertTrue(resolved.isEmpty())
        assertEquals(0, protectedDatagrams)
    }

    // ---- resolveOutcome (F33): resolve with its cause — a silent resolver, an NXDOMAIN answer and
    // an empty answer stay apart so the switch pre-check can name why it refused.

    @Test
    fun `resolveOutcome reports the resolved addresses when a family answers`() = runBlocking {
        val port = freeLoopbackPort()
        val fixture = TypeAwareUdpDns(port, a = "127.0.0.1", aaaa = null)
        try {
            val outcome = BootstrapResolver(
                servers = listOf(InetAddress.getByName("127.0.0.1")),
                protect = { true },
                port = port,
            ).resolveOutcome("bootstrap.test")
            assertEquals(BootstrapLookup.Resolved(listOf("127.0.0.1")), outcome)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `resolveOutcome reports an empty answer when a response carries no records`() = runBlocking {
        val port = freeLoopbackPort()
        val fixture = TypeAwareUdpDns(port, a = null, aaaa = null)
        try {
            val outcome = BootstrapResolver(
                servers = listOf(InetAddress.getByName("127.0.0.1")),
                protect = { true },
                port = port,
            ).resolveOutcome("empty.test")
            assertEquals(BootstrapLookup.EmptyAnswer, outcome)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `resolveOutcome reports an unknown name when a resolver answers NXDOMAIN`() = runBlocking {
        val port = freeLoopbackPort()
        val fixture = TypeAwareUdpDns(port, nxDomain = true)
        try {
            val outcome = BootstrapResolver(
                servers = listOf(InetAddress.getByName("127.0.0.1")),
                protect = { true },
                port = port,
            ).resolveOutcome("gone.test")
            assertEquals(BootstrapLookup.NameUnknown, outcome)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `resolveOutcome reports no response when no family answers`() = runBlocking {
        // Bound but silent: no reply and no ICMP, so both families time out without an answer.
        val silent = DatagramSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        }
        try {
            val outcome = BootstrapResolver(
                servers = listOf(InetAddress.getByName("127.0.0.1")),
                protect = { true },
                port = silent.localPort,
                timeoutMs = 600,
            ).resolveOutcome("silent.test")
            assertEquals(BootstrapLookup.NoResponse, outcome)
        } finally {
            silent.close()
        }
    }

    @Test
    fun `resolveOutcome keeps NXDOMAIN over a silent family`() = runBlocking {
        // The merge rule: NameUnknown beats NoResponse. A answers NXDOMAIN while AAAA is black-holed.
        val port = freeLoopbackPort()
        val fixture = TypeAwareUdpDns(port, nxDomain = true, dropAaaa = true)
        try {
            val outcome = BootstrapResolver(
                servers = listOf(InetAddress.getByName("127.0.0.1")),
                protect = { true },
                port = port,
            ).resolveOutcome("gone.test")
            assertEquals(BootstrapLookup.NameUnknown, outcome)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `resolveOutcome keeps an empty answer over a silent family`() = runBlocking {
        // The merge rule: EmptyAnswer beats NoResponse. A answers NOERROR with no records while AAAA is black-holed.
        val port = freeLoopbackPort()
        val fixture = TypeAwareUdpDns(port, a = null, dropAaaa = true)
        try {
            val outcome = BootstrapResolver(
                servers = listOf(InetAddress.getByName("127.0.0.1")),
                protect = { true },
                port = port,
            ).resolveOutcome("empty.test")
            assertEquals(BootstrapLookup.EmptyAnswer, outcome)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `a bootstrap reply that answers a different question is ignored`() = runBlocking {
        val port = freeLoopbackPort()
        val fixture = MismatchedReplyUdpDns(port, queries = 2).apply { start() }
        try {
            val resolved = BootstrapResolver(
                listOf(InetAddress.getByName("127.0.0.1")),
                protect = { true }, port = port, timeoutMs = 500,
            ).resolve("real.example")
            assertTrue("a spoofed reply must yield no addresses", resolved.isEmpty())
            assertEquals(2, fixture.seenIds.size) // both A and AAAA were asked and poisoned
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `bootstrap transaction ids are random per query`() = runBlocking {
        val port = freeLoopbackPort()
        val fixture = MismatchedReplyUdpDns(port, queries = 8).apply { start() }
        try {
            val resolver = BootstrapResolver(
                listOf(InetAddress.getByName("127.0.0.1")),
                protect = { true }, port = port, timeoutMs = 500,
            )
            repeat(4) { resolver.resolve("id.example") }
            assertEquals(8, fixture.seenIds.size)
            // The old code sent id 0x0000 on every query; 8 SecureRandom ids are all zero
            // with probability 2^-128.
            assertTrue(fixture.seenIds.any { it != 0 })
        } finally {
            fixture.close()
        }
    }

    // ---- DotUpstream dial pin: connect to the bootstrap-resolved address, hostname stays the
    // TLS peer name (SNI/verification) — observable here as the dial target; the authority
    // half of the contract is observed end-to-end in the DohUpstream test below.

    @Test
    fun `DotUpstream dials the pinned address instead of the hostname`() = runBlocking {
        val port = freeLoopbackPort()
        val server = AcceptCloseServer(port)
        try {
            val upstream = DotUpstream(
                host = "dot.example.test",
                port = port,
                dialAddresses = listOf("127.0.0.1"),
            )
            // Both stale-retry attempts dial the pinned address and die on the immediate close.
            assertNull(upstream.query(buildAQuery("x.example")))
            assertEquals(2, server.accepted.get())
        } finally {
            server.close()
        }
    }

    @Test
    fun `DotUpstream without a pin still dials the hostname form`() = runBlocking {
        // "127.0.0.1" as the host IS the literal dial target — the no-pin legacy path.
        val port = freeLoopbackPort()
        val server = AcceptCloseServer(port)
        try {
            val upstream = DotUpstream("127.0.0.1", port)
            assertNull(upstream.query(buildAQuery("x.example")))
            assertEquals(2, server.accepted.get())
        } finally {
            server.close()
        }
    }

    @Test
    fun `DotUpstream walks the pinned list when the first address is dead`() = runBlocking {
        val livePort = freeLoopbackPort()
        val live = AcceptCloseServer(livePort)
        try {
            val upstream = DotUpstream(
                "dot.example.test", livePort,
                dialAddresses = listOf("127.0.0.2", "127.0.0.1"), // nothing listens on 127.0.0.2: refused, next wins
            )
            assertNull(upstream.query(buildAQuery("x.example"))) // TLS never completes against AcceptCloseServer
            assertTrue(live.accepted.get() >= 1) // the second pinned address WAS dialed (D4)
        } finally {
            live.close()
        }
    }

    // ---- DohUpstream dial pin: connection via pinned addresses, URL authority stays the hostname

    @Test
    fun `DohUpstream dials the pinned address but keeps the hostname authority`() = runBlocking {
        val answer = buildAQuery("pin.example") // arbitrary wire bytes; DoH POST returns the body verbatim
        val handshake = tlsHandshake("dohtest.example")
        MockWebServer().use { server ->
            server.useHttps(handshake.sslSocketFactory(), false)
            server.enqueue(
                MockResponse().setHeader("Content-Type", "application/dns-message").setBody(Buffer().write(answer)),
            )
            server.start()
            val upstream = DohUpstream(
                tlsClient(handshake),
                "https://dohtest.example:${server.port}/dns-query",
                pinnedDns = object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> =
                        listOf(InetAddress.getByName("127.0.0.1"))
                },
            )
            assertEquals(answer.toList(), upstream.query(buildAQuery("x.example"))!!.toList())
            val recorded = server.takeRequest()
            // Over TLS OkHttp negotiates ALPN h2, where the authority rides the :authority
            // header instead of Host — either way it must stay the hostname, not the pinned IP.
            assertEquals(
                "dohtest.example:${server.port}",
                recorded.getHeader("Host") ?: recorded.getHeader(":authority"),
            )
            assertEquals("/dns-query", recorded.path)
        }
    }

    // ---- buildSecureUpstream: the D1 seam — hostname endpoints bootstrap-resolve ONCE via
    // the injected lambda, IP literals pass through untouched, failures are loud.

    @Test
    fun `buildSecureUpstream resolves a hostname DoT endpoint via the lambda and dials the IP`() = runBlocking {
        val port = freeLoopbackPort()
        val server = AcceptCloseServer(port)
        val asked = mutableListOf<String>()
        try {
            val upstream = buildSecureUpstream(
                type = SecureDnsType.DOT,
                endpoint = "dot.example.test:$port",
                ok = OkHttpClient(),
                bootstrapResolve = { asked.add(it); listOf("127.0.0.1") },
            )
            assertNull(upstream.query(buildAQuery("x.example")))
            assertEquals(listOf("dot.example.test"), asked)
            assertEquals(2, server.accepted.get())
        } finally {
            server.close()
        }
    }

    @Test
    fun `buildSecureUpstream fails loud on empty bootstrap resolution`() = runBlocking {
        val err = runCatching {
            buildSecureUpstream(
                type = SecureDnsType.DOT,
                endpoint = "dot.example.test:853",
                ok = OkHttpClient(),
                bootstrapResolve = { emptyList() },
            )
        }.exceptionOrNull()
        assertTrue(err is SecureDnsBootstrapException)
    }

    @Test
    fun `buildSecureUpstream fails loud with cause when bootstrap resolution throws`() = runBlocking {
        val err = runCatching {
            buildSecureUpstream(
                type = SecureDnsType.DOT,
                endpoint = "dot.example.test:853",
                ok = OkHttpClient(),
                bootstrapResolve = { throw IllegalStateException("boom") },
            )
        }.exceptionOrNull()
        assertTrue(err is SecureDnsBootstrapException)
        assertEquals("boom", err!!.cause?.message)
    }

    @Test
    fun `buildSecureUpstream passes IPv4 literal DoT endpoints through without resolving`() = runBlocking {
        val port = freeLoopbackPort()
        val server = AcceptCloseServer(port)
        try {
            val upstream = buildSecureUpstream(
                type = SecureDnsType.DOT,
                endpoint = "127.0.0.1:$port",
                ok = OkHttpClient(),
                bootstrapResolve = { throw AssertionError("must not resolve IP literals") },
            )
            assertNull(upstream.query(buildAQuery("x.example")))
            assertEquals(2, server.accepted.get())
        } finally {
            server.close()
        }
    }

    @Test
    fun `buildSecureUpstream passes bracketed IPv6 literal DoT endpoints through without resolving`() = runBlocking {
        val upstream = buildSecureUpstream(
            type = SecureDnsType.DOT,
            endpoint = "[::1]:853",
            ok = OkHttpClient(),
            bootstrapResolve = { throw AssertionError("must not resolve IP literals") },
        )
        upstream.close() // constructed lazily: no dial, no resolution, no extra query
    }

    @Test
    fun `buildSecureUpstream resolves a hostname DoH endpoint and keeps its authority`() = runBlocking {
        val handshake = tlsHandshake("dohtest.example")
        val answer = buildAQuery("pin.example") // arbitrary wire bytes; DoH POST returns the body verbatim
        MockWebServer().use { server ->
            server.useHttps(handshake.sslSocketFactory(), false)
            server.enqueue(
                MockResponse().setHeader("Content-Type", "application/dns-message").setBody(Buffer().write(answer)),
            )
            server.start()
            val asked = mutableListOf<String>()
            val upstream = buildSecureUpstream(
                type = SecureDnsType.DOH,
                endpoint = "https://dohtest.example:${server.port}/dns-query",
                ok = tlsClient(handshake),
                bootstrapResolve = { asked.add(it); listOf("127.0.0.1") },
            )
            assertEquals(answer.toList(), upstream.query(buildAQuery("x.example"))!!.toList())
            assertEquals(listOf("dohtest.example"), asked)
            val recorded = server.takeRequest()
            // Over TLS OkHttp negotiates ALPN h2, where the authority rides the :authority
            // header instead of Host — either way it must stay the hostname, not the pinned IP.
            assertEquals(
                "dohtest.example:${server.port}",
                recorded.getHeader("Host") ?: recorded.getHeader(":authority"),
            )
        }
    }

    @Test
    fun `buildSecureUpstream passes IP literal DoH endpoints through without resolving`() = runBlocking {
        val upstream = buildSecureUpstream(
            type = SecureDnsType.DOH,
            endpoint = "https://9.9.9.9/dns-query",
            ok = OkHttpClient(),
            bootstrapResolve = { throw AssertionError("must not resolve IP literals") },
        )
        upstream.close() // construction only — never dial 9.9.9.9 from a test
    }

    @Test
    fun `buildSecureUpstream passes bracketed IPv6 literal DoH endpoints through without resolving`() = runBlocking {
        val upstream = buildSecureUpstream(
            type = SecureDnsType.DOH,
            endpoint = "https://[2001:db8::1]/dns-query",
            ok = OkHttpClient(),
            bootstrapResolve = { throw AssertionError("must not resolve IP literals") },
        )
        upstream.close() // construction only — never dial a documentation-prefix address
    }

    @Test
    fun `a DoT server reports the address it dials`() = runBlocking {
        val upstream = buildSecureUpstream(
            type = SecureDnsType.DOT,
            endpoint = "192.0.2.53:853",
            ok = OkHttpClient(),
        ) { error("no lookup for a literal") }
        try {
            assertEquals(listOf("192.0.2.53"), upstream.endpointAddresses)
        } finally {
            upstream.close()
        }
    }

    @Test
    fun `a DoT hostname reports its pinned addresses`() = runBlocking {
        val upstream = buildSecureUpstream(
            type = SecureDnsType.DOT,
            endpoint = "dns.example",
            ok = OkHttpClient(),
            bootstrapResolve = { listOf("192.0.2.53", "2001:db8::53") },
        )
        try {
            assertEquals(listOf("192.0.2.53", "2001:db8::53"), upstream.endpointAddresses)
        } finally {
            upstream.close()
        }
    }

    @Test
    fun `a DoH literal reports its address without brackets`() = runBlocking {
        val upstream = buildSecureUpstream(
            type = SecureDnsType.DOH,
            endpoint = "https://[2001:db8::53]/dns-query",
            ok = OkHttpClient(),
        ) { error("no lookup for a literal") }
        try {
            assertEquals(listOf("2001:db8::53"), upstream.endpointAddresses)
        } finally {
            upstream.close()
        }
    }

    @Test
    fun `a DoH hostname reports its pinned addresses`() = runBlocking {
        val upstream = buildSecureUpstream(
            type = SecureDnsType.DOH,
            endpoint = "https://dns.example/dns-query",
            ok = OkHttpClient(),
            bootstrapResolve = { listOf("192.0.2.53", "2001:db8::53") },
        )
        try {
            assertEquals(listOf("192.0.2.53", "2001:db8::53"), upstream.endpointAddresses)
        } finally {
            upstream.close()
        }
    }

    @Test
    fun `buildSecureUpstream rejects blank endpoints loudly`() = runBlocking {
        val err = runCatching {
            buildSecureUpstream(
                type = SecureDnsType.DOT,
                endpoint = " ",
                ok = OkHttpClient(),
                bootstrapResolve = { listOf("127.0.0.1") },
            )
        }.exceptionOrNull()
        assertTrue(err is SecureDnsBootstrapException)
    }

    @Test
    fun `buildSecureUpstream rejects invalid DoT endpoints loudly`() = runBlocking {
        val err = runCatching {
            buildSecureUpstream(
                type = SecureDnsType.DOT,
                endpoint = "host:99999",
                ok = OkHttpClient(),
                bootstrapResolve = { listOf("127.0.0.1") },
            )
        }.exceptionOrNull()
        assertTrue(err is SecureDnsBootstrapException)
    }

    @Test
    fun `buildSecureUpstream rejects invalid DoH endpoints loudly`() = runBlocking {
        val err = runCatching {
            buildSecureUpstream(
                type = SecureDnsType.DOH,
                endpoint = "not a url",
                ok = OkHttpClient(),
                bootstrapResolve = { listOf("127.0.0.1") },
            )
        }.exceptionOrNull()
        assertTrue(err is SecureDnsBootstrapException)
    }

    @Test
    fun `doh upstream refuses a non-https endpoint at runtime`() = runBlocking {
        val e = assertThrows(SecureDnsBootstrapException::class.java) {
            runBlocking {
                buildSecureUpstream(SecureDnsType.DOH, "http://dns.example/dns-query", OkHttpClient()) { listOf("192.0.2.1") }
            }
        }
        assertTrue(e.message!!.contains("https"))
    }

    @Test
    fun `secure upstreams are built without any socket protection`() = runBlocking {
        val dot = buildSecureUpstream(SecureDnsType.DOT, "9.9.9.9", OkHttpClient()) { emptyList() }
        assertTrue(dot is DotUpstream)
        val doh = buildSecureUpstream(SecureDnsType.DOH, "https://1.1.1.1/dns-query", OkHttpClient()) { emptyList() }
        assertTrue(doh is DohUpstream)
    }

    @Test
    fun `bootstrap resolver retries once after a lost datagram`() = runBlocking {
        val port = freeLoopbackPort()
        val fixture = DropFirstUdpDns(port)
        try {
            val resolved = BootstrapResolver(listOf(InetAddress.getByName("127.0.0.1")), { true }, port).resolve("retry.test")
            assertEquals(listOf("127.0.0.1"), resolved)
            assertEquals(4, fixture.exchanges.get()) // A and AAAA, each asked twice
        } finally {
            fixture.close()
        }
    }

}
