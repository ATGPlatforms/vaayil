package dev.vaayil.core.dns

import java.io.DataInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.minidns.dnsmessage.DnsMessage
import org.minidns.dnsname.DnsName
import org.minidns.dnsmessage.Question
import org.minidns.record.A
import org.minidns.record.Record

/** test helper: tiny UDP "DNS" server echoing a fixed NOERROR A-answer wire message per query */
class FakeUdpDns(
    private val answer: ByteArray,
    private val port: Int,
    private val mirrorTxid: Boolean = true, // false = reply with the answer's own (wrong) txid
) {
    val exchanges = AtomicInteger(0)
    private val socket = DatagramSocket(port, InetAddress.getByName("127.0.0.1"))
    private val thread = Thread {
        val buf = ByteArray(1500)
        try {
            while (!socket.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                socket.receive(p)
                val resp = answer.copyOf()
                if (mirrorTxid) { resp[0] = buf[0]; resp[1] = buf[1] } // mirror the transaction id
                exchanges.incrementAndGet()
                socket.send(DatagramPacket(resp, resp.size, p.address, p.port))
            }
        } catch (_: IOException) {
            // socket closed — exit
        }
    }.apply { isDaemon = true; start() }
    fun close() { socket.close(); thread.interrupt() }
}

/** test helper: tiny TCP "DNS" server answering 2-byte-framed queries with a fixed A-answer */
class FakeTcpDns(private val answer: ByteArray, private val port: Int) {
    val exchanges = AtomicInteger(0)
    private val server = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
    private val thread = Thread {
        while (!server.isClosed) {
            val conn = runCatching { server.accept() }.getOrNull() ?: break
            Thread {
                try {
                    conn.use { c ->
                        val input = DataInputStream(c.getInputStream())
                        while (true) {
                            val query = ByteArray(input.readUnsignedShort())
                            input.readFully(query)
                            exchanges.incrementAndGet()
                            val resp = answer.copyOf()
                            resp[0] = query[0]; resp[1] = query[1] // mirror the transaction id
                            c.getOutputStream().apply {
                                write(byteArrayOf(((resp.size shr 8) and 0xFF).toByte(), resp.size.toByte()))
                                write(resp); flush()
                            }
                        }
                    }
                } catch (_: IOException) {
                    // connection closed — done
                }
            }.apply { isDaemon = true; start() }
        }
    }.apply { isDaemon = true; start() }
    fun close() { server.close() }
}

/** test helper: TCP "DNS" server that answers correctly but drip-feeds the response body one
    byte per [dripMillis] — the shape that used to stretch PlainTcpUpstream's per-read budget (D2). */
class FakeDrippingTcpDns(
    private val answer: ByteArray,
    private val port: Int,
    private val dripMillis: Long = 150,
) {
    val exchanges = AtomicInteger(0)
    private val server = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
    private val thread = Thread {
        while (!server.isClosed) {
            val conn = runCatching { server.accept() }.getOrNull() ?: break
            Thread {
                try {
                    conn.use { c ->
                        val input = DataInputStream(c.getInputStream())
                        while (true) {
                            val query = ByteArray(input.readUnsignedShort())
                            input.readFully(query)
                            exchanges.incrementAndGet()
                            val resp = answer.copyOf()
                            resp[0] = query[0]; resp[1] = query[1]
                            val out = c.getOutputStream()
                            out.write(byteArrayOf(((resp.size shr 8) and 0xFF).toByte(), resp.size.toByte()))
                            out.flush()
                            for (b in resp) {
                                out.write(b.toInt()); out.flush()
                                Thread.sleep(dripMillis)
                            }
                        }
                    }
                } catch (_: IOException) {
                    // connection closed — done
                }
            }.apply { isDaemon = true; start() }
        }
    }.apply { isDaemon = true; start() }
    fun close() { server.close() }
}

/** test helper: TLS "DoT" server that completes the handshake, reads one framed query and never answers. */
class FakeSilentTlsDns(handshake: okhttp3.tls.HandshakeCertificates, port: Int) {
    private val server = handshake.sslContext().serverSocketFactory
        .createServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
    private val thread = Thread {
        while (!server.isClosed) {
            val conn = runCatching { server.accept() }.getOrNull() ?: break
            Thread {
                runCatching {
                    val input = DataInputStream(conn.getInputStream())
                    val query = ByteArray(input.readUnsignedShort())
                    input.readFully(query)
                    Thread.sleep(10_000) // a blackholed upstream: the query is swallowed
                }
                runCatching { conn.close() }
            }.apply { isDaemon = true; start() }
        }
    }.apply { isDaemon = true; start() }
    fun close() { server.close() }
}

/** test helper: TLS "DoT" server that completes the handshake, reads one framed query, then answers
    with the correct length prefix followed by the body ONE byte per [dripMillis] — the shape that
    reaches the body loop of readFramedTcpMessage (the prefix arrives at once, each gap stays inside
    the socket timeout) and so is bounded only by the total deadline (D2). */
class FakeDrippingTlsDns(
    handshake: okhttp3.tls.HandshakeCertificates,
    port: Int,
    private val body: ByteArray,
    private val dripMillis: Long = 50,
) {
    val exchanges = AtomicInteger(0)
    private val server = handshake.sslContext().serverSocketFactory
        .createServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
    private val thread = Thread {
        while (!server.isClosed) {
            val conn = runCatching { server.accept() }.getOrNull() ?: break
            Thread {
                try {
                    conn.use { c ->
                        val input = DataInputStream(c.getInputStream())
                        val query = ByteArray(input.readUnsignedShort())
                        input.readFully(query)
                        exchanges.incrementAndGet()
                        val out = c.getOutputStream()
                        out.write(byteArrayOf(((body.size shr 8) and 0xFF).toByte(), body.size.toByte()))
                        out.flush()
                        for (b in body) {
                            out.write(b.toInt()); out.flush()
                            Thread.sleep(dripMillis)
                        }
                    }
                } catch (_: IOException) {
                    // client gave up mid-drip — done
                }
            }.apply { isDaemon = true; start() }
        }
    }.apply { isDaemon = true; start() }
    fun close() { server.close() }
}

/** test helper: TLS "DoT" server on an ephemeral loopback port that answers every framed query
    with [answer] and the query's transaction id mirrored — the reply link A3's positive control needs. */
private class FakeAnsweringTlsDns(
    handshake: okhttp3.tls.HandshakeCertificates,
    private val answer: ByteArray,
) {
    private val server = handshake.sslContext().serverSocketFactory.createServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress("127.0.0.1", 0))
    }
    val port: Int = server.localPort
    private val thread = Thread {
        while (!server.isClosed) {
            val conn = runCatching { server.accept() }.getOrNull() ?: break
            Thread {
                try {
                    conn.use { c ->
                        val input = DataInputStream(c.getInputStream())
                        while (true) {
                            val query = ByteArray(input.readUnsignedShort())
                            input.readFully(query)
                            val resp = answer.copyOf()
                            resp[0] = query[0]; resp[1] = query[1] // mirror the transaction id
                            c.getOutputStream().apply {
                                write(byteArrayOf(((resp.size shr 8) and 0xFF).toByte(), resp.size.toByte()))
                                write(resp); flush()
                            }
                        }
                    }
                } catch (_: IOException) {
                    // the handshake failed or the connection closed — done
                }
            }.apply { isDaemon = true; start() }
        }
    }.apply { isDaemon = true; start() }
    fun close() { server.close() }
}

/** test helper counting upstream: answer bytes or null; records close for ownership checks. */
private class CountingUpstream(private var answer: ByteArray?) : DnsUpstream {
    val hits = AtomicInteger(0)
    var closed = false
    override suspend fun query(queryWire: ByteArray): ByteArray? {
        hits.incrementAndGet()
        return answer?.copyOf()?.also { it[0] = queryWire[0]; it[1] = queryWire[1] }
    }
    override fun close() { closed = true }
}

class UpstreamTest {

    private fun buildQuery(name: String, id: Int = 0): ByteArray = DnsMessage.builder()
        .setId(id)
        .setQuestion(Question(DnsName.from(name), Record.TYPE.A))
        .build()
        .toArray()

    private fun buildResponse(
        name: String,
        id: Int = 0,
        ttl: Long = 3600,
        code: DnsMessage.RESPONSE_CODE = DnsMessage.RESPONSE_CODE.NO_ERROR,
        truncated: Boolean = false,
        withAnswer: Boolean = true,
        qr: Boolean = true,
    ): ByteArray {
        val builder = DnsMessage.builder()
            .setId(id)
            .setQuestion(Question(DnsName.from(name), Record.TYPE.A))
            .build()
            .asBuilder()
            .setQrFlag(qr)
            .setResponseCode(code)
            .setTruncated(truncated)
        if (withAnswer) {
            builder.addAnswer(
                Record(DnsName.from(name), Record.TYPE.A, Record.CLASS.IN, ttl,
                    A("93.184.216.34"), false),
            )
        }
        return builder.build().toArray()
    }

    // (1) plain UDP

    @Test
    fun `plain udp upstream answers and mirrors the transaction id`() = runBlocking {
        val answer = buildResponse("example.com", id = 0x1C2C)
        val port = freeLoopbackPort()
        val fake = FakeUdpDns(answer, port = port)
        val upstream = PlainUdpUpstream(listOf(InetAddress.getByName("127.0.0.1")), port)
        val query = buildQuery("example.com", id = 0x1C2C)
        val resp = upstream.query(query)!!
        assertEquals(query[0], resp[0]); assertEquals(query[1], resp[1])
        assertEquals(Record.TYPE.A, DnsMessage(resp).questions.first().type)
        upstream.close(); fake.close()
    }

    @Test
    fun `plain udp returns truncated answers as-is`() = runBlocking {
        val answer = buildResponse("tc.example", id = 7, truncated = true)
        val port = freeLoopbackPort()
        val fake = FakeUdpDns(answer, port = port)
        val upstream = PlainUdpUpstream(listOf(InetAddress.getByName("127.0.0.1")), port)
        val query = buildQuery("tc.example", id = 7)
        val resp = upstream.query(query)
        assertNotNull(resp)
        assertTrue(DnsMessage(resp!!).truncated)
        upstream.close(); fake.close()
    }

    // (2) response cache

    @Test
    fun `caching upstream serves the second query from cache`() = runBlocking {
        val port = freeLoopbackPort()
        val fake = FakeUdpDns(buildResponse("cached.example", id = 9), port = port)
        val inner = PlainUdpUpstream(listOf(InetAddress.getByName("127.0.0.1")), port)
        val cached = CachingUpstream(inner, DnsResponseCache())
        val query = buildQuery("cached.example", id = 9)
        assertNotNull(cached.query(query))
        fake.close()                                    // upstream gone…
        val resp = cached.query(query)                  // …cache answers anyway
        assertNotNull(resp)
        assertEquals(query[0], resp!![0]); assertEquals(query[1], resp[1])
        cached.close(); inner.close()
    }

    @Test
    fun `caching upstream rewrites the served copy transaction id`() = runBlocking {
        val port = freeLoopbackPort()
        val fake = FakeUdpDns(buildResponse("txid.example", id = 9), port = port)
        val inner = PlainUdpUpstream(listOf(InetAddress.getByName("127.0.0.1")), port)
        val cached = CachingUpstream(inner, DnsResponseCache())
        assertNotNull(cached.query(buildQuery("txid.example", id = 9)))   // populates the cache
        fake.close(); inner.close()     // upstream gone → only the cache can answer
        val second = cached.query(buildQuery("txid.example", id = 0xBEEF))
        // Regression: the cached reply carried the FIRST query's id (9); a stub
        // resolver drops mismatched-id replies, so the served copy must be patched
        // to the second query's id.
        assertNotNull(second)
        val servedId = ((second!![0].toInt() and 0xFF) shl 8) or (second[1].toInt() and 0xFF)
        assertEquals(0xBEEF, servedId)
        cached.close()
    }

    @Test
    fun `caching upstream never serves expired entries`() = runBlocking {
        val port = freeLoopbackPort()
        val fake = FakeUdpDns(buildResponse("expiring.example", id = 11, ttl = 1), port = port)
        val inner = PlainUdpUpstream(listOf(InetAddress.getByName("127.0.0.1")), port)
        val cached = CachingUpstream(inner, DnsResponseCache())
        val query = buildQuery("expiring.example", id = 11)
        assertNotNull(cached.query(query))
        delay(1100)                                     // TTL=1s elapses
        fake.close()                                    // inner dead → only the cache could answer
        assertNull(cached.query(query))
        cached.close(); inner.close()
    }

    @Test
    fun `caching upstream never caches servfail or empty answers`() = runBlocking {
        val query = buildQuery("fail.example", id = 13)

        val servfailPort = freeLoopbackPort()
        val servfailFake = FakeUdpDns(
            buildResponse("fail.example", id = 13, code = DnsMessage.RESPONSE_CODE.SERVER_FAIL),
            port = servfailPort,
        )
        val servfailInner = PlainUdpUpstream(listOf(InetAddress.getByName("127.0.0.1")), servfailPort)
        val servfailCached = CachingUpstream(servfailInner, DnsResponseCache())
        assertNotNull(servfailCached.query(query))      // SERVFAIL passed through…
        servfailFake.close()                            // …but not cached
        assertNull(servfailCached.query(query))
        servfailCached.close(); servfailInner.close()

        val emptyPort = freeLoopbackPort()
        val emptyFake = FakeUdpDns(
            buildResponse("fail.example", id = 13, withAnswer = false),
            port = emptyPort,
        )
        val emptyInner = PlainUdpUpstream(listOf(InetAddress.getByName("127.0.0.1")), emptyPort)
        val emptyCached = CachingUpstream(emptyInner, DnsResponseCache())
        assertNotNull(emptyCached.query(query))         // empty NOERROR passed through…
        emptyFake.close()                               // …but not cached
        assertNull(emptyCached.query(query))
        emptyCached.close(); emptyInner.close()
    }

    @Test
    fun `caching upstream never caches truncated responses`() = runBlocking {
        val port = freeLoopbackPort()
        val fake = FakeUdpDns(
            buildResponse("tcnc.example", id = 15, truncated = true),
            port = port,
        )
        val inner = PlainUdpUpstream(listOf(InetAddress.getByName("127.0.0.1")), port)
        val cached = CachingUpstream(inner, DnsResponseCache())
        val query = buildQuery("tcnc.example", id = 15)
        assertNotNull(cached.query(query))              // truncated passed through…
        fake.close()                                    // …but not cached
        assertNull(cached.query(query))
        cached.close(); inner.close()
    }

    // (3) DoH

    @Test
    fun `doh upstream posts dns-message to the well-known path`() = runBlocking {
        val answer = buildResponse("doh.example", id = 0x2A2A)
        val handshake = tlsHandshake("localhost")
        MockWebServer().use { server ->
            server.useHttps(handshake.sslSocketFactory(), false)
            server.enqueue(
                MockResponse()
                    .setBody(Buffer().write(answer))
                    .addHeader("Content-Type", "application/dns-message"),
            )
            server.start()
            val upstream = DohUpstream(tlsClient(handshake), "https://localhost:${server.port}/dns-query")
            val query = buildQuery("doh.example", id = 0x2A2A)
            val resp = upstream.query(query)!!
            assertEquals(query[0], resp[0]); assertEquals(query[1], resp[1])
            val req = server.takeRequest()
            assertEquals("POST", req.method)
            assertEquals("/dns-query", req.path)
            assertEquals("application/dns-message", req.getHeader("Content-Type"))
            assertEquals("application/dns-message", req.getHeader("Accept"))
            upstream.close()
        }
    }

    @Test
    fun `doh redirects are not followed`() = runBlocking {
        val handshake = tlsHandshake("localhost")
        MockWebServer().use { server ->
            server.useHttps(handshake.sslSocketFactory(), false)
            server.start()
            // Same-origin target: a cross-host target would die in DNS and leave requestCount
            // at 1 whether or not the redirect was followed — untestable.
            server.enqueue(
                MockResponse()
                    .setResponseCode(307)
                    .setHeader("Location", "https://localhost:${server.port}/dns-query-redirect"),
            )
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/dns-message")
                    .setBody(Buffer().write(buildResponse("doh.example"))),
            )
            val upstream = DohUpstream(tlsClient(handshake), "https://localhost:${server.port}/dns-query")
            assertNull(upstream.query(buildQuery("doh.example")))
            assertEquals(1, server.requestCount) // the redirect target was never contacted
        }
    }

    @Test
    fun `doh bodies over the 64k cap are rejected`() = runBlocking {
        val handshake = tlsHandshake("localhost")
        MockWebServer().use { server ->
            server.useHttps(handshake.sslSocketFactory(), false)
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/dns-message")
                    .setBody(Buffer().write(ByteArray(65_536))),
            )
            server.start()
            val upstream = DohUpstream(tlsClient(handshake), "https://localhost:${server.port}/dns-query")
            assertNull(upstream.query(buildQuery("doh.example")))
        }
    }

    @Test
    fun `doh chunked bodies over the 64k cap are rejected`() = runBlocking {
        val handshake = tlsHandshake("localhost")
        MockWebServer().use { server ->
            server.useHttps(handshake.sslSocketFactory(), false)
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/dns-message")
                    .setChunkedBody(Buffer().write(ByteArray(65_536)), 8192), // no Content-Length to check
            )
            server.start()
            val upstream = DohUpstream(tlsClient(handshake), "https://localhost:${server.port}/dns-query")
            assertNull(upstream.query(buildQuery("doh.example")))
        }
    }

    @Test
    fun `doh responses with the wrong content type are rejected`() = runBlocking {
        val handshake = tlsHandshake("localhost")
        MockWebServer().use { server ->
            server.useHttps(handshake.sslSocketFactory(), false)
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/html")
                    .setBody(Buffer().write(buildResponse("doh.example"))),
            )
            server.start()
            val upstream = DohUpstream(tlsClient(handshake), "https://localhost:${server.port}/dns-query")
            assertNull(upstream.query(buildQuery("doh.example")))
        }
    }

    // (4) timeout

    @Test
    fun `silent server returns null within the per-attempt budget`() = runBlocking {
        val port = freeLoopbackPort()
        val silent = DatagramSocket(port, InetAddress.getByName("127.0.0.1"))
        val upstream = PlainUdpUpstream(listOf(InetAddress.getByName("127.0.0.1")), port)
        val start = System.currentTimeMillis()
        val resp = upstream.query(buildQuery("silent.example", id = 17))
        val elapsed = System.currentTimeMillis() - start
        assertNull(resp)
        assertTrue("query took ${elapsed}ms", elapsed < 3_000)
        upstream.close(); silent.close()
    }

    @Test
    fun `a bounded query stops at its deadline, not at the end of a full pass`() = runTest {
        // Sweep-2 minor: the timeout used to land at the end of a full pass because each receive
        // blocked for its own 400 ms window regardless of the caller's budget.
        val silent = DatagramSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        }
        try {
            val upstream = PlainUdpUpstream(List(3) { InetAddress.getByName("127.0.0.1") }, silent.localPort)
            val deadline = System.nanoTime() + 600_000_000L
            val answer = upstream.query(buildQuery("bounded.example"), deadline)
            assertNull(answer)
            assertTrue(
                "budget overrun: ${(System.nanoTime() - deadline) / 1_000_000} ms past the deadline",
                System.nanoTime() - deadline < 400_000_000L, // at most one straddling receive window
            )
        } finally {
            silent.close()
        }
    }

    // (5) review-fix coverage: txid validation, protect enforcement, plain TCP framing

    @Test
    fun `plain udp rejects a response with a wrong transaction id`() = runBlocking {
        val port = freeLoopbackPort()
        val fake = FakeUdpDns(buildResponse("spoof.example", id = 99), port = port, mirrorTxid = false)
        val upstream = PlainUdpUpstream(listOf(InetAddress.getByName("127.0.0.1")), port)
        assertNull(upstream.query(buildQuery("spoof.example", id = 42)))
        upstream.close(); fake.close()
    }

    @Test
    fun `plain udp protect failure never sends the query`() = runBlocking {
        val port = freeLoopbackPort()
        val fake = FakeUdpDns(buildResponse("noguard.example", id = 21), port = port)
        val upstream = PlainUdpUpstream(
            listOf(InetAddress.getByName("127.0.0.1")), port,
            protect = { false },
        )
        assertNull(upstream.query(buildQuery("noguard.example", id = 21)))
        assertEquals(0, fake.exchanges.get())
        upstream.close(); fake.close()
    }

    @Test
    fun `plain tcp upstream answers and mirrors the transaction id`() = runBlocking {
        val port = freeLoopbackPort()
        val fake = FakeTcpDns(buildResponse("tcp.example", id = 0x0E0E), port = port)
        val upstream = PlainTcpUpstream(listOf(InetAddress.getByName("127.0.0.1")), port)
        val query = buildQuery("tcp.example", id = 0x0E0E)
        val resp = upstream.query(query)!!
        assertEquals(query[0], resp[0]); assertEquals(query[1], resp[1])
        assertEquals(Record.TYPE.A, DnsMessage(resp).questions.first().type)
        assertEquals(1, fake.exchanges.get())
        upstream.close(); fake.close()
    }

    @Test
    fun `plain tcp protect failure never connects`() = runBlocking {
        val port = freeLoopbackPort()
        val fake = FakeTcpDns(buildResponse("tcpng.example", id = 23), port = port)
        val upstream = PlainTcpUpstream(
            listOf(InetAddress.getByName("127.0.0.1")), port,
            protect = { false },
        )
        assertNull(upstream.query(buildQuery("tcpng.example", id = 23)))
        assertEquals(0, fake.exchanges.get())
        upstream.close(); fake.close()
    }

    // (6) D2: total per-attempt deadline on plain TCP

    @Test
    fun `plain tcp drip-fed answer dies at the total attempt budget`() = runBlocking {
        val port = freeLoopbackPort()
        // 10 body bytes at 150 ms each: the OLD per-read budget finished this attempt in
        // ~1.5 s with a full answer; the new total budget must abandon it within ~400 ms.
        val fake = FakeDrippingTcpDns(ByteArray(10), port = port, dripMillis = 150)
        val upstream = PlainTcpUpstream(listOf(InetAddress.getByName("127.0.0.1")), port)
        val start = System.currentTimeMillis()
        val resp = upstream.query(buildQuery("drip.example", id = 31))
        val elapsed = System.currentTimeMillis() - start
        assertNull("drip-fed answer must not complete the attempt", resp)
        assertTrue("query took ${elapsed}ms", elapsed < 800)
        upstream.close(); fake.close()
    }

    // (7) D4: one transparent retry for a stale pooled session

    @Test
    fun `stale session retry reconnects and answers within the same call`() = runBlocking {
        var acquired = 0
        var retired = 0
        val answers = ArrayDeque(listOf<ByteArray?>(null, buildResponse("retry.example", id = 33)))
        val resp = withStaleSessionRetry(
            acquire = { acquired++ },
            exchange = { answers.removeFirstOrNull() },
            retire = { retired++ },
        )
        assertNotNull(resp)
        assertEquals(2, acquired) // old session + fresh one
        assertEquals(1, retired)  // exactly the dead session
    }

    @Test
    fun `stale session retry gives up after exactly one retry`() = runBlocking {
        var acquired = 0
        val resp = withStaleSessionRetry(
            acquire = { acquired++ },
            exchange = { null },
            retire = { },
        )
        assertNull(resp)
        assertEquals(2, acquired) // never a third attempt
    }

    @Test
    fun `stale session retry passes a healthy session through untouched`() = runBlocking {
        var acquired = 0
        var retired = 0
        val answer = buildResponse("fresh.example", id = 35)
        val resp = withStaleSessionRetry(
            acquire = { acquired++ },
            exchange = { answer },
            retire = { retired++ },
        )
        assertEquals(answer, resp)
        assertEquals(1, acquired)
        assertEquals(0, retired)
    }

    // (8) D3: re-probing wrapper for snapshot-built channels

    @Test
    fun `reprobing upstream rebuilds on failure and retries the query once`() = runBlocking {
        val dead = CountingUpstream(null)
        val fresh = CountingUpstream(buildResponse("d3.example", id = 37))
        var rebuilds = 0
        val upstream = ReprobingUpstream(dead) { rebuilds++; fresh }
        val resp = upstream.query(buildQuery("d3.example", id = 37))
        assertNotNull(resp)
        assertEquals(1, rebuilds)
        assertTrue("dead channel must be closed after the swap", dead.closed)
        assertEquals(2, dead.hits.get()) // outer try + recheck under the lock
        assertEquals(1, fresh.hits.get())
        upstream.close()
    }

    @Test
    fun `reprobing upstream keeps the old channel when the rebuild fails`() = runBlocking {
        val dead = CountingUpstream(null)
        var rebuilds = 0
        val upstream = ReprobingUpstream(dead) { rebuilds++; null }
        assertNull(upstream.query(buildQuery("norebuild.example", id = 39)))
        assertEquals(1, rebuilds)
        assertFalse(dead.closed) // still owned — the wrapper never drops its only channel
        assertEquals(2, dead.hits.get()) // outer try + double-check under the lock
        upstream.close()
    }

    @Test
    fun `reprobing upstream never rebuilds while the inner channel answers`() = runBlocking {
        val healthy = CountingUpstream(buildResponse("ok.example", id = 41))
        var rebuilds = 0
        val upstream = ReprobingUpstream(healthy) { rebuilds++; null }
        assertNotNull(upstream.query(buildQuery("ok.example", id = 41)))
        assertEquals(0, rebuilds)
        assertEquals(1, healthy.hits.get())
        upstream.close()
    }

    // (9) D7: the cache serves and stores only what the upstream answered for THIS question

    private class CountingAnswer(private val answer: ByteArray) : DnsUpstream {
        var queries = 0
        override suspend fun query(queryWire: ByteArray): ByteArray? {
            queries++
            return answer.copyOf().also { it[0] = queryWire[0]; it[1] = queryWire[1] }
        }
        override fun close() = Unit
    }

    @Test
    fun `a response answering a different question is never cached`() = runBlocking {
        val inner = CountingAnswer(buildResponse("other.example"))
        val cached = CachingUpstream(inner, DnsResponseCache())
        val q = buildQuery("example.com")
        assertNotNull(cached.query(q))
        assertNotNull(cached.query(q))
        assertEquals(2, inner.queries) // the mismatched response was refused by the cache (D7)
    }

    @Test
    fun `the cache key includes the dns class`() = runBlocking {
        val inner = CountingAnswer(buildResponse("example.com"))
        val cached = CachingUpstream(inner, DnsResponseCache())
        fun q(clazz: Record.CLASS) = DnsMessage.builder()
            .setId(0x1234)
            .setQuestion(Question(DnsName.from("example.com"), Record.TYPE.A, clazz))
            .build()
            .toArray()
        assertNotNull(cached.query(q(Record.CLASS.IN)))
        assertNotNull(cached.query(q(Record.CLASS.CH)))
        assertEquals(2, inner.queries) // same name+type, different class: a different key, second went upstream
    }

    // (10) D2/D8 review fixes: exchange budget, media-type case, pool ownership, response bit

    @Test
    fun `dot exchange dies at the total exchange budget`() = runBlocking {
        val handshake = tlsHandshake("localhost")
        val port = freeLoopbackPort()
        val fake = FakeSilentTlsDns(handshake, port)
        val upstream = DotUpstream("localhost", port, exchangeBudgetMs = 500, sslFactory = handshake.sslSocketFactory())
        val start = System.currentTimeMillis()
        assertNull(upstream.query(buildQuery("silent.dot", id = 41)))
        val elapsed = System.currentTimeMillis() - start
        // 5 s: >2x the 1.63 s measured here before the abortive close (1.18 s after), and comfortably
        // below both failure modes — the 10.6 s measured when the budget never arrives (default 3.5 s
        // socket timeout, retried once) and the fixture's own 10 s sleep an open-ended read would sit
        // through. Deliberately NOT tightened to pin the abortive close: a bound under the pre-fix
        // time (~1.4 s) leaves ~20% headroom, inside this suite's CI wobble band.
        assertTrue("query took ${elapsed}ms", elapsed < 5_000)
        upstream.close(); fake.close()
    }

    @Test
    fun `dot drip-fed answer dies at the total exchange budget`() = runBlocking {
        val handshake = tlsHandshake("localhost")
        val port = freeLoopbackPort()
        // 100 body bytes at 50 ms each ≈ 5 s of drip: 10x the 500 ms budget (so the no-deadline
        // case is unmistakably slower and still delivers the whole body), and each gap is 10x
        // SHORTER than the socket timeout, so only readFramedTcpMessage's deadline can end this.
        val fake = FakeDrippingTlsDns(handshake, port, body = ByteArray(100), dripMillis = 50)
        val upstream = DotUpstream("localhost", port, exchangeBudgetMs = 500, sslFactory = handshake.sslSocketFactory())
        val start = System.currentTimeMillis()
        assertNull("a drip-fed answer must not complete the exchange", upstream.query(buildQuery("drip.dot", id = 43)))
        val elapsed = System.currentTimeMillis() - start
        // Two attempts (the stale-session retry) of 500 ms each plus two handshakes.
        assertTrue("query took ${elapsed}ms", elapsed < 2_500)
        upstream.close(); fake.close()
    }

    @Test
    fun `doh accepts the media type in any case`() = runBlocking {
        val handshake = tlsHandshake("localhost")
        MockWebServer().use { server ->
            server.useHttps(handshake.sslSocketFactory(), false)
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "Application/DNS-Message")
                    .setBody(Buffer().write(buildResponse("doh.example"))),
            )
            server.start()
            val upstream = DohUpstream(tlsClient(handshake), "https://localhost:${server.port}/dns-query")
            assertNotNull(upstream.query(buildQuery("doh.example")))
        }
    }

    @Test
    fun `closing one doh upstream leaves a sibling's pooled connection alone`() = runBlocking {
        val handshake = tlsHandshake("localhost")
        MockWebServer().use { server ->
            server.useHttps(handshake.sslSocketFactory(), false)
            repeat(3) {
                server.enqueue(
                    MockResponse()
                        .setHeader("Content-Type", "application/dns-message")
                        .setBody(Buffer().write(buildResponse("doh.example"))),
                )
            }
            server.start()
            val base = tlsClient(handshake)
            val old = DohUpstream(base, "https://localhost:${server.port}/dns-query")
            val fresh = DohUpstream(base, "https://localhost:${server.port}/dns-query")
            assertNotNull(old.query(buildQuery("doh.example")))
            assertNotNull(fresh.query(buildQuery("doh.example")))
            old.close() // must evict ONLY old's pool
            assertNotNull(fresh.query(buildQuery("doh.example")))
            server.takeRequest(); server.takeRequest() // old's first, fresh's first: each opened its own connection
            // fresh's second request rode its own pooled connection: sequenceNumber counts requests per connection.
            assertEquals(1, server.takeRequest().sequenceNumber)
            fresh.close()
        }
    }

    @Test
    fun `a reply without the response bit is never cached`() = runBlocking {
        val inner = CountingUpstream(buildResponse("noqr.example", id = 51, qr = false))
        val upstream = CachingUpstream(inner, DnsResponseCache())
        upstream.query(buildQuery("noqr.example", id = 51))
        upstream.query(buildQuery("noqr.example", id = 51))
        assertEquals(2, inner.hits.get())
    }

    // (11) A2/A3: direct DoH and verified DoT identity

    @Test
    fun `doh client explicitly disables system proxies`() {
        // A system selector that would route every request through a dead proxy: the DoH client must
        // pin NO_PROXY itself (A2) instead of relying on callers passing a proxyless base client.
        val base = OkHttpClient.Builder()
            .proxySelector(object : ProxySelector() {
                override fun select(uri: URI): List<Proxy> =
                    listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 1)))
                override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) = Unit
            })
            .build()
        val upstream = DohUpstream(base, "https://dns.example/dns-query")
        assertEquals(Proxy.NO_PROXY, upstream.client.proxy)
        upstream.close()
    }

    @Test
    fun `dot refuses a trusted certificate for another hostname`() = runBlocking {
        val handshake = tlsHandshake("other.test") // trusted, but only for a different hostname
        val fake = FakeAnsweringTlsDns(handshake, buildResponse("dot.test", id = 45))
        val upstream = DotUpstream(
            "dot.test", fake.port,
            dialAddresses = listOf("127.0.0.1"),
            sslFactory = handshake.sslSocketFactory(),
        )
        try {
            assertNull(
                "another hostname's certificate must not verify",
                upstream.query(buildQuery("dot.test", id = 45)),
            )
        } finally {
            upstream.close(); fake.close()
        }
    }

    @Test
    fun `dot accepts a trusted certificate for its configured hostname`() = runBlocking {
        // The negative test's positive control: same fixture, matching SAN — a trust or framing
        // failure would fail here instead of silently satisfying the refusal test.
        val handshake = tlsHandshake("dot.test")
        val fake = FakeAnsweringTlsDns(handshake, buildResponse("dot.test", id = 47))
        val upstream = DotUpstream(
            "dot.test", fake.port,
            dialAddresses = listOf("127.0.0.1"),
            sslFactory = handshake.sslSocketFactory(),
        )
        try {
            assertNotNull(
                "a certificate for the configured hostname must verify and be answered",
                upstream.query(buildQuery("dot.test", id = 47)),
            )
        } finally {
            upstream.close(); fake.close()
        }
    }
}
