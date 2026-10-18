package dev.vaayil.core.dns

import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.minidns.dnsmessage.DnsMessage
import org.minidns.dnsmessage.Question
import org.minidns.dnsname.DnsName
import org.minidns.record.A
import org.minidns.record.Record

/** Canned-answer upstream; mirrors the query transaction id on its own copy (never mutates the fixture). */
private class EchoUpstream(private val answer: ByteArray) : DnsUpstream {
    override suspend fun query(queryWire: ByteArray): ByteArray? =
        answer.copyOf().also { it[0] = queryWire[0]; it[1] = queryWire[1] }

    override fun close() = Unit
}

private object NullUpstream : DnsUpstream {
    override suspend fun query(queryWire: ByteArray): ByteArray? = null
    override fun close() = Unit
}

/** udp side answers truncated, tcp side answers fully; both mirror the txid on copies. */
private class TruncatedThenTcpUpstream(udpAnswer: ByteArray, tcpAnswer: ByteArray) {
    val udp: DnsUpstream = EchoUpstream(udpAnswer)
    val tcp: DnsUpstream = EchoUpstream(tcpAnswer)
}

/** Sends one datagram, awaits one reply (2 s timeout). */
private object UdpClient {
    fun ask(host: String, port: Int, query: ByteArray, timeoutMs: Int = 2_000): ByteArray =
        DatagramSocket().use { s ->
            s.soTimeout = timeoutMs
            s.send(DatagramPacket(query, query.size, InetAddress.getByName(host), port))
            val buf = ByteArray(65535)
            val p = DatagramPacket(buf, buf.size)
            s.receive(p)
            buf.copyOf(p.length)
        }
}

/** Opens one TCP connection, sends one 2-byte-framed query, reads one framed reply. */
private object TcpClient {
    fun ask(host: String, port: Int, query: ByteArray, timeoutMs: Int = 2_000): ByteArray =
        Socket(host, port).use { s ->
            s.soTimeout = timeoutMs
            s.getOutputStream().apply {
                write(byteArrayOf(((query.size shr 8) and 0xFF).toByte(), query.size.toByte()))
                write(query)
                flush()
            }
            val input = DataInputStream(s.getInputStream())
            val resp = ByteArray(input.readUnsignedShort())
            input.readFully(resp)
            resp
        }
}

private fun buildQuery(name: String, id: Int = 0x1234): ByteArray = DnsMessage.builder()
    .setId(id)
    .setQuestion(Question(DnsName.from(name), Record.TYPE.A))
    .build()
    .toArray()

private fun buildAnswer(name: String, id: Int = 0x1234, truncated: Boolean = false): ByteArray {
    val response = DnsMessage.builder()
        .setId(id)
        .setQuestion(Question(DnsName.from(name), Record.TYPE.A))
        .build()
        .asBuilder()
        .setQrFlag(true)
        .setTruncated(truncated)
    response.addAnswer(
        Record(DnsName.from(name), Record.TYPE.A, Record.CLASS.IN, 60, A("93.184.216.34"), false),
    )
    return response.build().toArray()
}

private val NOERROR_ANSWER = buildAnswer("example.com")
private val TC_ANSWER = buildAnswer("big.example", truncated = true)
private val TCP_ANSWER = buildAnswer("big.example")

class LocalDnsServerTest {

    @Test
    fun `udp query reaches the classified upstream and answer returns`() = runTest {
        val upstream = EchoUpstream(NOERROR_ANSWER)
        val port = freeLoopbackPort()
        val server = LocalDnsServer(
            this, InetAddress.getByName("127.0.0.1"),
            classify = { upstream }, port = port,
        )
        server.start()
        try {
            val query = buildQuery("example.com")
            val resp = UdpClient.ask("127.0.0.1", port, query)
            // UDP path is unframed: the reply is exactly the upstream answer, txid mirrored.
            assertEquals(NOERROR_ANSWER.size, resp.size)
            assertEquals(query[0], resp[0]); assertEquals(query[1], resp[1])
        } finally {
            server.stop() // failure path must close the sockets, or runTest waits on the loops
        }
    }

    @Test
    fun `upstream failure yields servfail with mirrored id`() = runTest {
        val port = freeLoopbackPort()
        val server = LocalDnsServer(
            this, InetAddress.getByName("127.0.0.1"),
            classify = { NullUpstream }, port = port,
        )
        server.start()
        try {
            val q = buildQuery("dead.example") // id 0x1234
            val resp = UdpClient.ask("127.0.0.1", port, q)
            assertEquals(0x12, resp[0].toInt() and 0xFF)
            assertEquals(DnsMessage.RESPONSE_CODE.SERVER_FAIL, DnsMessage(resp).responseCode)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `truncated udp answer is retried over tcp`() = runTest {
        assertTrue("fixture must carry the TC bit on the wire", (TC_ANSWER[2].toInt() and 0x02) != 0)
        val truncated = TruncatedThenTcpUpstream(TC_ANSWER, TCP_ANSWER)
        val port = freeLoopbackPort()
        val server = LocalDnsServer(
            this, InetAddress.getByName("127.0.0.1"),
            classify = { truncated.udp }, port = port,
            tcpFor = { truncated.tcp },
        )
        server.start()
        try {
            val parsed = DnsMessage(UdpClient.ask("127.0.0.1", port, buildQuery("big.example")))
            assertFalse(parsed.truncated)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `multi question query is refused with mirrored id`() = runTest {
        val port = freeLoopbackPort()
        val server = LocalDnsServer(
            this, InetAddress.getByName("127.0.0.1"),
            classify = { EchoUpstream(NOERROR_ANSWER) }, port = port,
        )
        server.start()
        try {
            val q = DnsMessage.builder()
                .setId(0x1234)
                .addQuestion(Question(DnsName.from("a.example"), Record.TYPE.A))
                .addQuestion(Question(DnsName.from("b.example"), Record.TYPE.A))
                .build()
                .toArray()
            val resp = UdpClient.ask("127.0.0.1", port, q)
            assertEquals(0x12, resp[0].toInt() and 0xFF)
            assertEquals(DnsMessage.RESPONSE_CODE.REFUSED, DnsMessage(resp).responseCode)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `tcp framed query gets a framed answer`() = runTest {
        val upstream = EchoUpstream(buildAnswer("tcp.example")) // the answer must carry the query's own question
        val port = freeLoopbackPort()
        val server = LocalDnsServer(
            this, InetAddress.getByName("127.0.0.1"),
            classify = { upstream }, port = port,
        )
        server.start()
        try {
            val q = buildQuery("tcp.example")
            val resp = TcpClient.ask("127.0.0.1", port, q)
            assertEquals(q[0], resp[0]); assertEquals(q[1], resp[1]) // txid mirrored
            assertTrue(DnsMessage(resp).qr)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `answer flood above the cap is dropped not queued`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val hits = java.util.concurrent.atomic.AtomicInteger(0)
        val blocking = object : DnsUpstream {
            override suspend fun query(queryWire: ByteArray): ByteArray? {
                hits.incrementAndGet()
                gate.await() // hold the single permit while the flood arrives
                return buildAnswer("flood.example").copyOf().also { it[0] = queryWire[0]; it[1] = queryWire[1] }
            }
            override fun close() = Unit
        }
        val port = freeLoopbackPort()
        val server = LocalDnsServer(
            this, InetAddress.getByName("127.0.0.1"),
            classify = { blocking }, port = port,
            maxConcurrentAnswers = 1,
        )
        server.start()
        try {
            val clients = (1..3).map { id ->
                DatagramSocket().apply {
                    soTimeout = 1_000
                    val q = buildQuery("flood.example", id = id)
                    send(DatagramPacket(q, q.size, InetAddress.getByName("127.0.0.1"), port))
                }
            }
            // Generous real-time waits: poll until one job is in flight, then settle so the
            // other two reach the (now empty) permit bucket and are dropped.
            val deadline = System.currentTimeMillis() + 3_000
            while (hits.get() < 1 && System.currentTimeMillis() < deadline) delay(20)
            delay(500)
            assertEquals(
                "exactly one query may be in flight; the flood's remainder is dropped",
                1, hits.get(),
            )
            gate.complete(Unit)
            val replies = clients.count { c ->
                try {
                    val buf = ByteArray(65535)
                    val p = DatagramPacket(buf, buf.size)
                    c.receive(p)
                    true
                } catch (_: java.net.SocketTimeoutException) {
                    false
                }
            }
            assertEquals("only the in-flight query is answered", 1, replies)
            clients.forEach { runCatching { it.close() } }
        } finally {
            gate.complete(Unit)
            server.stop()
        }
    }

    @Test
    fun `stop then start restarts the listener`() = runTest {
        val upstream = EchoUpstream(NOERROR_ANSWER)
        val port = freeLoopbackPort()
        val server = LocalDnsServer(
            this, InetAddress.getByName("127.0.0.1"),
            classify = { upstream }, port = port,
        )
        server.start()
        try {
            val first = UdpClient.ask("127.0.0.1", port, buildQuery("example.com"))
            assertEquals(0x12, first[0].toInt() and 0xFF)
            server.stop()
            server.start() // restart path: fresh sockets, loops re-entered with closed reset
            val second = UdpClient.ask("127.0.0.1", port, buildQuery("example.com"))
            assertEquals(0x12, second[0].toInt() and 0xFF)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `a stalled tcp client is cut at the message deadline`() = runTest {
        val port = freeLoopbackPort()
        val server = LocalDnsServer(
            this, InetAddress.getByName("127.0.0.1"), classify = { EchoUpstream(NOERROR_ANSWER) },
            port = port, tcpMessageBudgetMs = 300,
        )
        server.start()
        try {
            java.net.Socket().use { sock ->
                sock.connect(java.net.InetSocketAddress("127.0.0.1", port), 2_000)
                sock.soTimeout = 2_000
                // Promise 64 framed bytes, deliver 4, then stall: the old code let the
                // 30 s soTimeout restart per byte, pinning an IO thread indefinitely (D2).
                sock.getOutputStream().apply { write(byteArrayOf(0, 64)); write(ByteArray(4)); flush() }
                // EOF must arrive well inside the client's 2 s window: the old code only
                // closed at the 30 s per-read timeout, so the read throws here and fails.
                assertEquals(-1, runCatching { sock.getInputStream().read() }.getOrDefault(-2))
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `a datagram from a source outside the allow-list is dropped unanswered`() = runTest {
        val port = freeLoopbackPort()
        val server = LocalDnsServer(
            this, InetAddress.getByName("127.0.0.1"), classify = { EchoUpstream(NOERROR_ANSWER) },
            port = port, isAllowedSource = { it == InetAddress.getByName("192.0.2.7") }, // TEST-NET-1: no local source matches
        )
        server.start()
        try {
            // UdpClient.ask surfaces a timeout as SocketTimeoutException: no reply IS the assertion.
            assertThrows(java.net.SocketTimeoutException::class.java) {
                UdpClient.ask("127.0.0.1", port, buildQuery("example.com"), timeoutMs = 500)
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `a reply that does not answer the query is served as servfail`() = runTest {
        val port = freeLoopbackPort()
        // The upstream answers a different name: an off-path spoof that got the txid right.
        val server = LocalDnsServer(this, InetAddress.getByName("127.0.0.1"), { EchoUpstream(buildAnswer("attacker.example")) }, port)
        server.start()
        try {
            val resp = DnsMessage(UdpClient.ask("127.0.0.1", port, buildQuery("example.com", id = 0x2222)))
            assertEquals(0x2222, resp.id)
            assertEquals(DnsMessage.RESPONSE_CODE.SERVER_FAIL, resp.responseCode)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `stop returns promptly while an answer is stuck in a blocking upstream call`() = runBlocking {
        val gate = CountDownLatch(1)
        val stuck = object : DnsUpstream {
            override suspend fun query(queryWire: ByteArray): ByteArray? { gate.await(5, TimeUnit.SECONDS); return null }
            override fun close() = Unit
        }
        val port = freeLoopbackPort()
        val server = LocalDnsServer(this, InetAddress.getByName("127.0.0.1"), { stuck }, port)
        server.start()
        val query = buildQuery("slow.example")
        DatagramSocket().use { s -> s.send(DatagramPacket(query, query.size, InetAddress.getByName("127.0.0.1"), port)) }
        delay(200) // the loop has handed the datagram to its (now blocked) answer job
        val started = System.currentTimeMillis()
        server.stop()
        val elapsed = System.currentTimeMillis() - started
        gate.countDown()
        assertTrue("stop took ${elapsed}ms", elapsed < 1_000)
    }
}
