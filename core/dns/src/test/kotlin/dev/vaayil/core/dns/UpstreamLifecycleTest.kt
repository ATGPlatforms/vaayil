package dev.vaayil.core.dns

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.minidns.dnsmessage.DnsMessage
import org.minidns.dnsmessage.Question
import org.minidns.dnsname.DnsName
import org.minidns.record.A
import org.minidns.record.Record

/**
 * A1: closure is final at every upstream network boundary. Each test drives a real socket fixture (or a recording
 * socket) through the upstream under test, closes it mid-flight, and watches what the fixture sees: a closed
 * upstream never creates a socket, dials, reconnects, rebuilds or dispatches an HTTP call again, and a query whose
 * Job was cancelled cancels its own DoH call without closing the reusable upstream.
 */
class UpstreamLifecycleTest {

    // ---- fixtures -------------------------------------------------------------------------------------------------

    /** A UDP "resolver" on [address]:[port] that counts the queries it receives and answers each with a
        transaction-id-mirrored A answer. With [holdFirst] the first query is answered only after [release]. */
    private class UdpDnsFixture(address: String, port: Int, private val holdFirst: Boolean) {
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
                    val answer = anAnswer(packet.data.copyOf(packet.length))
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

    /** A TCP "resolver" on [address]:[port] that counts accepted connections and holds each one open, unanswering,
        until the client goes away — an attempt that can only end by its own timeout or a close. */
    private class TcpDnsFixture(address: String, port: Int) {
        val hits = AtomicInteger(0)
        private val socket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName(address), port), 50)
        }

        init {
            Thread {
                while (!socket.isClosed) {
                    val conn = runCatching { socket.accept() }.getOrNull() ?: return@Thread
                    hits.incrementAndGet()
                    Thread {
                        try {
                            conn.use { c ->
                                val input = DataInputStream(c.getInputStream())
                                val query = ByteArray(input.readUnsignedShort())
                                input.readFully(query)
                                input.read() // hold the connection until the client retires it: never an answer
                            }
                        } catch (_: IOException) {
                            // client closed — done
                        }
                    }.apply {
                        isDaemon = true
                        start()
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }
        }

        fun close() = socket.close()
    }

    /** A TLS "DoT" resolver: every accepted connection is counted; the first connection reads its query and holds,
        later connections answer at once. */
    private class DotFixture(handshake: HandshakeCertificates, port: Int) {
        val accepts = AtomicInteger(0)
        val firstQueryRead = CountDownLatch(1)
        private val server = handshake.sslContext().serverSocketFactory.createServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 50)
        }

        init {
            Thread {
                while (!server.isClosed) {
                    val conn = runCatching { server.accept() }.getOrNull() ?: return@Thread
                    val index = accepts.incrementAndGet()
                    Thread {
                        try {
                            conn.use { c ->
                                val input = DataInputStream(c.getInputStream())
                                while (true) {
                                    val query = ByteArray(input.readUnsignedShort())
                                    input.readFully(query)
                                    if (index == 1) {
                                        firstQueryRead.countDown()
                                        input.read() // swallow the first connection's exchanges
                                        return@use
                                    }
                                    val answer = anAnswer(query)
                                    val out = c.getOutputStream()
                                    out.write(byteArrayOf(((answer.size shr 8) and 0xFF).toByte(), answer.size.toByte()))
                                    out.write(answer)
                                    out.flush()
                                }
                            }
                        } catch (_: IOException) {
                            // client closed — done
                        }
                    }.apply {
                        isDaemon = true
                        start()
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }
        }

        fun close() = server.close()
    }

    /** A socket that records its dials, its writes and its flushes, so a test can close the upstream between the
        framing writes and see exactly how far a send got. */
    private class RecordingSocket(private val afterWrite: (Int) -> Unit = {}) : Socket() {
        val written = ByteArrayOutputStream()
        var dials = 0
            private set
        var flushed = false
            private set
        private val sink = object : OutputStream() {
            override fun write(b: Int) {
                written.write(b)
                afterWrite(written.size())
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                written.write(b, off, len)
                afterWrite(written.size())
            }

            override fun flush() {
                flushed = true
            }
        }

        override fun getOutputStream(): OutputStream = sink

        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun connect(endpoint: SocketAddress?, timeout: Int) {
            dials++
        }

        override fun setSoTimeout(timeout: Int) = Unit
    }

    /** An upstream that counts the queries and the closes it saw; [answer] null = every query fails. */
    private class CountingLink(private val answer: ByteArray? = null) : DnsUpstream {
        val hits = AtomicInteger(0)
        val closes = AtomicInteger(0)

        override suspend fun query(queryWire: ByteArray): ByteArray? {
            hits.incrementAndGet()
            return answer?.copyOf()?.also {
                it[0] = queryWire[0]
                it[1] = queryWire[1]
            }
        }

        override fun close() {
            closes.incrementAndGet()
        }
    }

    // ---- tests ----------------------------------------------------------------------------------------------------

    @Test(timeout = 60_000)
    fun `closed udp never reaches a second server`() = runBlocking {
        val port = freeLoopbackPort()
        val first = UdpDnsFixture("127.0.0.1", port, holdFirst = true)
        val second = UdpDnsFixture("127.0.0.2", port, holdFirst = false)
        try {
            val creations = AtomicInteger(0)
            val upstream = PlainUdpUpstream(
                listOf(InetAddress.getByName("127.0.0.1"), InetAddress.getByName("127.0.0.2")), port,
                socketFactory = { creations.incrementAndGet(); DatagramSocket() },
            )
            val inFlight = async(Dispatchers.IO) { upstream.query(aQuery(1)) }
            first.awaitQuery()
            val creationsAtClose = creations.get()
            upstream.close()
            first.release()
            assertNull(inFlight.await())
            assertEquals(creationsAtClose, creations.get())
            assertEquals(0, second.hits.get())
            assertNull(upstream.query(aQuery(2))) // a fresh query on the closed upstream
            assertEquals(creationsAtClose, creations.get())
            assertEquals(0, second.hits.get())
        } finally {
            first.close()
            second.close()
        }
    }

    @Test(timeout = 60_000)
    fun `closed tcp never reaches a second server`() = runBlocking {
        val port = freeLoopbackPort()
        val first = TcpDnsFixture("127.0.0.1", port)
        val second = TcpDnsFixture("127.0.0.2", port)
        try {
            val creations = AtomicInteger(0)
            val upstream = PlainTcpUpstream(
                listOf(InetAddress.getByName("127.0.0.1"), InetAddress.getByName("127.0.0.2")), port,
                socketFactory = { creations.incrementAndGet(); Socket() },
            )
            val inFlight = async(Dispatchers.IO) { upstream.query(aQuery(1)) }
            waitFor("the first server accepted the connection") { first.hits.get() == 1 }
            val creationsAtClose = creations.get()
            upstream.close()
            assertNull(inFlight.await())
            assertEquals(creationsAtClose, creations.get())
            assertEquals(0, second.hits.get())
            assertNull(upstream.query(aQuery(2)))
            assertEquals(creationsAtClose, creations.get())
            assertEquals(0, second.hits.get())
        } finally {
            first.close()
            second.close()
        }
    }

    @Test(timeout = 60_000)
    fun `closed dot never reconnects or answers a queued query`() = runBlocking {
        val handshake = tlsHandshake("dot.test")
        val port = freeLoopbackPort()
        val fixture = DotFixture(handshake, port)
        try {
            val upstream = DotUpstream(
                "dot.test", port,
                dialAddresses = listOf("127.0.0.1"),
                sslFactory = handshake.sslSocketFactory(),
            )
            val inFlight = async(Dispatchers.IO) { upstream.query(aQuery(7)) }
            assertTrue("the first query reached the server", fixture.firstQueryRead.await(5, TimeUnit.SECONDS))
            val queued = async(Dispatchers.IO) { upstream.query(aQuery(8)) }
            Thread.sleep(100) // let the queued query reach the session mutex behind the in-flight exchange
            upstream.close()
            assertNull(inFlight.await())
            assertNull(queued.await())
            assertEquals(1, fixture.accepts.get()) // one connection: the close refused the stale retry and the queued query
        } finally {
            fixture.close()
        }
    }

    @Test(timeout = 60_000)
    fun `closed reprobe never rebuilds`() = runBlocking {
        val dead = CountingLink()
        val fresh = CountingLink(anAnswer(aQuery(1)))
        var rebuilds = 0
        val upstream = ReprobingUpstream(dead) {
            rebuilds++
            fresh
        }
        upstream.close()
        assertNull(upstream.query(aQuery(1)))
        assertEquals(0, rebuilds)
        assertEquals(0, dead.hits.get())
        assertEquals(0, fresh.hits.get())
        assertEquals(0, fresh.closes.get())
    }

    @Test(timeout = 60_000)
    fun `a late rebuild is closed without being queried`() = runBlocking {
        // (a) the upstream was closed while the rebuild ran.
        val fresh = CountingLink(anAnswer(aQuery(1)))
        val arrived = CountDownLatch(1)
        val released = CountDownLatch(1)
        val nonCooperative: suspend () -> DnsUpstream? = {
            arrived.countDown()
            released.await(10, TimeUnit.SECONDS) // deliberately blocking: the late result still arrives
            fresh
        }
        val closedWhileRebuilding = ReprobingUpstream(CountingLink(), nonCooperative)
        val first = async(Dispatchers.IO) { closedWhileRebuilding.query(aQuery(1)) }
        assertTrue(arrived.await(5, TimeUnit.SECONDS))
        closedWhileRebuilding.close()
        released.countDown()
        assertNull(first.await())
        waitFor("the late result was closed") { fresh.closes.get() == 1 }
        assertEquals(0, fresh.hits.get())

        // (b) the query's Job was cancelled while the rebuild ran.
        val secondFresh = CountingLink(anAnswer(aQuery(2)))
        val secondArrived = CountDownLatch(1)
        val secondReleased = CountDownLatch(1)
        val nonCooperativeAgain: suspend () -> DnsUpstream? = {
            secondArrived.countDown()
            secondReleased.await(10, TimeUnit.SECONDS)
            secondFresh
        }
        val cancelledWhileRebuilding = ReprobingUpstream(CountingLink(), nonCooperativeAgain)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val second = scope.async { cancelledWhileRebuilding.query(aQuery(2)) }
            assertTrue(secondArrived.await(5, TimeUnit.SECONDS))
            second.cancel()
            secondReleased.countDown()
            runCatching { second.await() }
            waitFor("the late result was closed") { secondFresh.closes.get() == 1 }
            assertEquals(0, secondFresh.hits.get())
        } finally {
            scope.cancel()
            cancelledWhileRebuilding.close()
        }
    }

    @Test(timeout = 60_000)
    fun `closed doh creates no connection and leaves a sibling usable`() = runBlocking {
        val handshake = tlsHandshake("localhost")
        MockWebServer().use { server ->
            server.useHttps(handshake.sslSocketFactory(), false)
            server.dispatcher = dnsDispatcher()
            server.start()
            val base = tlsClient(handshake)
            val closed = DohUpstream(base, dnsUrl(server))
            val sibling = DohUpstream(base, dnsUrl(server))
            assertNotNull(closed.query(aQuery(1)))
            assertNotNull(sibling.query(aQuery(1)))
            closed.close()
            val requestsAtClose = server.requestCount
            assertNull(closed.query(aQuery(2))) // the closed upstream dispatches nothing
            assertEquals(requestsAtClose, server.requestCount)
            waitFor("no call of the closed upstream is left") {
                closed.client.dispatcher.queuedCallsCount() == 0 && closed.client.dispatcher.runningCallsCount() == 0
            }
            assertNotNull(sibling.query(aQuery(1))) // a sibling's pool and calls are untouched
        }
    }

    @Test(timeout = 60_000)
    fun `cancelled query cancels doh without closing its upstream`() = runBlocking {
        val handshake = tlsHandshake("localhost")
        MockWebServer().use { server ->
            server.useHttps(handshake.sslSocketFactory(), false)
            val responses = LinkedBlockingQueue<MockResponse>()
            responses += dnsResponse(aQuery(1)).setBodyDelay(5, TimeUnit.SECONDS) // in flight until the query is cancelled
            responses += dnsResponse(aQuery(2))
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    responses.poll() ?: MockResponse().setResponseCode(500)
            }
            server.start()
            val canceled = CountDownLatch(1)
            val base = tlsClient(handshake).newBuilder()
                .eventListener(object : EventListener() {
                    override fun canceled(call: Call) {
                        canceled.countDown()
                    }
                })
                .build()
            val upstream = DohUpstream(base, dnsUrl(server))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val query = scope.async { upstream.query(aQuery(1)) }
                assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)) // the call reached the server
                query.cancel()
                // Well below DohUpstream's own 4 s call timeout, so this can only be the query's cancellation.
                assertTrue("the in-flight call was cancelled", canceled.await(2, TimeUnit.SECONDS))
                assertNull(runCatching { query.await() }.getOrNull())
                waitFor("the cancelled call left the dispatcher") { upstream.client.dispatcher.runningCallsCount() == 0 }
                // Cancellation cancelled the call, not the reusable upstream.
                assertNotNull(upstream.query(aQuery(2)))
            } finally {
                scope.cancel()
            }
        }
    }

    @Test(timeout = 60_000)
    fun `cancelled query cancels doh while its reply body is still arriving`() = runBlocking {
        val handshake = tlsHandshake("localhost")
        MockWebServer().use { server ->
            server.useHttps(handshake.sslSocketFactory(), false)
            val responses = LinkedBlockingQueue<MockResponse>()
            // The headers arrive at once; the body only after 5 s.
            responses += dnsResponse(aQuery(1)).setBodyDelay(5, TimeUnit.SECONDS)
            responses += dnsResponse(aQuery(2))
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    responses.poll() ?: MockResponse().setResponseCode(500)
            }
            server.start()
            val readingBody = CountDownLatch(1)
            val canceled = CountDownLatch(1)
            val base = tlsClient(handshake).newBuilder()
                .eventListener(object : EventListener() {
                    override fun canceled(call: Call) {
                        canceled.countDown()
                    }
                })
                .addNetworkInterceptor { chain ->
                    // Opens the latch once the query has the headers and starts reading the body.
                    val response = chain.proceed(chain.request())
                    val body = response.body!!
                    val source = object : ForwardingSource(body.source()) {
                        override fun read(sink: Buffer, byteCount: Long): Long {
                            readingBody.countDown()
                            return super.read(sink, byteCount)
                        }
                    }.buffer()
                    response.newBuilder().body(source.asResponseBody(body.contentType(), body.contentLength())).build()
                }
                .build()
            val upstream = DohUpstream(base, dnsUrl(server))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val query = scope.async { upstream.query(aQuery(1)) }
                assertTrue("the query is reading the reply body", readingBody.await(5, TimeUnit.SECONDS))
                query.cancel()
                // Well below DohUpstream's own 4 s call timeout, so this can only be the query's cancellation.
                assertTrue("the call reading its body was cancelled", canceled.await(2, TimeUnit.SECONDS))
                assertNull(runCatching { query.await() }.getOrNull())
                waitFor("the cancelled call left the dispatcher") { upstream.client.dispatcher.runningCallsCount() == 0 }
                // Cancellation cancelled the call, not the reusable upstream.
                assertNotNull(upstream.query(aQuery(2)))
            } finally {
                scope.cancel()
            }
        }
    }

    @Test(timeout = 60_000)
    fun `closing inside the udp protection callback sends nothing`() = runBlocking {
        val port = freeLoopbackPort()
        val server = UdpDnsFixture("127.0.0.1", port, holdFirst = false)
        try {
            lateinit var upstream: PlainUdpUpstream
            upstream = PlainUdpUpstream(
                listOf(InetAddress.getByName("127.0.0.1")), port,
                protect = {
                    upstream.close()
                    true
                },
            )
            assertNull(upstream.query(aQuery(1)))
            Thread.sleep(100) // a send would land here
            assertEquals(0, server.hits.get())
        } finally {
            server.close()
        }
    }

    @Test(timeout = 60_000)
    fun `closing inside the tcp protection callback dials nothing`() = runBlocking {
        lateinit var upstream: PlainTcpUpstream
        val socket = RecordingSocket()
        upstream = PlainTcpUpstream(
            listOf(InetAddress.getByName("127.0.0.1")), 53,
            protect = {
                upstream.close()
                true
            },
            socketFactory = { socket },
        )
        assertNull(upstream.query(aQuery(1)))
        assertEquals(0, socket.dials)
        assertEquals(0, socket.written.size())
        assertFalse(socket.flushed)
    }

    @Test(timeout = 60_000)
    fun `closing between tcp framing writes sends no body`() = runBlocking {
        lateinit var upstream: PlainTcpUpstream
        val socket = RecordingSocket(afterWrite = { written -> if (written >= 2) upstream.close() })
        upstream = PlainTcpUpstream(
            listOf(InetAddress.getByName("127.0.0.1")), 53,
            socketFactory = { socket },
        )
        assertNull(upstream.query(aQuery(1)))
        assertEquals(2, socket.written.size()) // the length prefix only: no body, no flush
        assertFalse(socket.flushed)
    }

    @Test(timeout = 60_000)
    fun `closing inside the dot dial refuses the socket it was handed`() = runBlocking {
        val handshake = tlsHandshake("dot.test")
        lateinit var upstream: DotUpstream
        val socket = RecordingSocket()
        upstream = DotUpstream(
            "dot.test", 853,
            dialAddresses = listOf("127.0.0.1"),
            sslFactory = handshake.sslSocketFactory(),
            socketFactory = {
                upstream.close() // the close wins the race with this dial
                socket
            },
        )
        assertNull(upstream.query(aQuery(1)))
        assertEquals(0, socket.dials) // the refused socket never reached the network
        assertEquals(0, socket.written.size())
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    /** A dispatcher that answers every request with an A answer mirroring the query's transaction id. */
    private fun dnsDispatcher(): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = dnsResponse(request.body.readByteArray())
    }

    private fun dnsResponse(query: ByteArray): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/dns-message")
        .setBody(Buffer().write(anAnswer(query)))

    private fun dnsUrl(server: MockWebServer) = "https://localhost:${server.port}/dns-query"

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            if (System.nanoTime() > deadline) throw AssertionError("timed out waiting until $what")
            Thread.sleep(5)
        }
    }
}

private fun aQuery(id: Int, name: String = "lifecycle.example"): ByteArray = DnsMessage.builder()
    .setId(id)
    .setQuestion(Question(DnsName.from(name), Record.TYPE.A))
    .build()
    .toArray()

private fun anAnswer(query: ByteArray): ByteArray {
    val parsed = DnsMessage(query)
    return parsed.asBuilder()
        .setQrFlag(true)
        .addAnswer(Record(parsed.question.name, Record.TYPE.A, Record.CLASS.IN, 60, A("192.0.2.1"), false))
        .build()
        .toArray()
}
