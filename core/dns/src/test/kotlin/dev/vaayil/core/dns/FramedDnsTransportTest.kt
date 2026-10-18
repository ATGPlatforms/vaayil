package dev.vaayil.core.dns

import java.io.IOException
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertArrayEquals
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

/** A scripted [DatagramPipe]: frames the test delivers come out of receive(); frames the transport sends are kept. */
private class FakePipe : DatagramPipe {
    private val incoming = LinkedBlockingQueue<ByteArray>()
    val sent = LinkedBlockingQueue<ByteArray>()
    val receiveCalls = AtomicInteger()
    val sendCalls = AtomicInteger()
    @Volatile var failSends = false
    @Volatile var closed = false

    fun deliver(frame: ByteArray) = incoming.put(frame)
    fun endOfFile() = incoming.put(EOF)
    fun breakReceive() = incoming.put(BROKEN)

    override fun receive(buffer: ByteArray): Int {
        receiveCalls.incrementAndGet()
        val frame = incoming.take()
        if (frame === EOF) return -1
        if (frame === BROKEN) throw IOException("receive failed")
        frame.copyInto(buffer)
        return frame.size
    }

    override fun send(buffer: ByteArray, length: Int) {
        sendCalls.incrementAndGet()
        if (failSends) throw IOException("send failed")
        sent.put(buffer.copyOf(length))
    }

    override fun close() {
        closed = true
    }

    /** The next frame the transport sent, waiting up to [ms]; null when none came. */
    fun nextSent(ms: Long = 5_000): ByteArray? = sent.poll(ms, TimeUnit.MILLISECONDS)

    private companion object {
        val EOF = ByteArray(0)
        val BROKEN = ByteArray(0)
    }
}

/** An answerer with a scripted source rule, answer Job and reply; counts the answers it was asked for. */
private class ScriptedAnswerer(
    private val allowed: (InetAddress) -> Boolean = { true },
    private val job: Job? = null,
    private val reply: suspend (call: Int, query: ByteArray) -> ByteArray? = { _, query -> answerTo(query) },
) : DnsAnswerer {
    val calls = AtomicInteger()
    override val answerJob: Job? get() = job
    override fun acceptsSource(source: InetAddress): Boolean = allowed(source)
    override suspend fun answer(query: ByteArray): ByteArray? = reply(calls.incrementAndGet(), query)
}

/**
 * A transport over [pipe] on its own thread; [ended] opens when run() returns. [failures] collects every exception an
 * answer coroutine let escape: on Android an escaped exception kills the app (kotlinx.coroutines'
 * propagateExceptionFinalResort hands it to the thread's uncaught-exception handler, which by default ends in AOSP
 * RuntimeInit.java's KillApplicationHandler.uncaughtException, and that kills the process), so a test expects none.
 */
private class Served(pipe: DatagramPipe, current: () -> DnsAnswerer?, maxInFlight: Int = 64) {
    val failures = ConcurrentLinkedQueue<Throwable>()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> failures += e })
    val ended = CountDownLatch(1)

    init {
        thread(isDaemon = true, name = "framed-dns-test") {
            FramedDnsTransport(pipe, scope, current, maxInFlight).run()
            ended.countDown()
        }
    }

    fun stop() = scope.cancel()
}

private fun query(id: Int, name: String = "example.com"): ByteArray = DnsMessage.builder()
    .setId(id)
    .setQuestion(Question(DnsName.from(name), Record.TYPE.A))
    .build()
    .toArray()

/** A NOERROR answer to [query] with [records] A records. */
private fun answerTo(query: ByteArray, records: Int = 1): ByteArray {
    val parsed = DnsMessage(query)
    val builder = parsed.asBuilder().setQrFlag(true)
    repeat(records) { i ->
        builder.addAnswer(
            Record(parsed.question.name, Record.TYPE.A, Record.CLASS.IN, 60, A(192, 0, 2 + i / 256, i % 256), false),
        )
    }
    return builder.build().toArray()
}

/** seq (u32) · addrLen (u8) · the client's address · the DNS message, as libwg-go writes a query frame. */
private fun queryFrame(seq: Int, client: String, dns: ByteArray): ByteArray {
    val address = InetAddress.getByName(client).address
    return ByteBuffer.allocate(4 + 1 + address.size + dns.size)
        .putInt(seq).put(address.size.toByte()).put(address).put(dns)
        .array()
}

private fun ByteArray.seq(): Int = ByteBuffer.wrap(this).int
private fun ByteArray.dns(): ByteArray = copyOfRange(4, size)

private fun waitUntil(what: String, condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (!condition()) {
        if (System.nanoTime() > deadline) throw AssertionError("timed out waiting until $what")
        Thread.sleep(5)
    }
}

class FramedDnsTransportTest {

    @Test
    fun `an answer frame carries the query's sequence number for ipv4 and ipv6 clients`() {
        val pipe = FakePipe()
        val served = Served(pipe, { ScriptedAnswerer() })
        try {
            val v4 = query(0x0101)
            val v6 = query(0x0202, "example.org")
            pipe.deliver(queryFrame(7, "10.0.0.2", v4))
            pipe.deliver(queryFrame(-2, "fd00::2", v6)) // 0xfffffffe: all 32 bits come back unchanged
            val replies = listOf(pipe.nextSent()!!, pipe.nextSent()!!).associateBy { it.seq() }
            assertArrayEquals(answerTo(v4), replies.getValue(7).dns())
            assertArrayEquals(answerTo(v6), replies.getValue(-2).dns())
        } finally {
            pipe.endOfFile()
            served.stop()
        }
    }

    @Test
    fun `malformed frames are dropped and the loop keeps reading`() {
        val pipe = FakePipe()
        val answerer = ScriptedAnswerer()
        val served = Served(pipe, { answerer })
        try {
            pipe.deliver(byteArrayOf(0, 0, 0, 1)) // no address length
            pipe.deliver(byteArrayOf(0, 0, 0, 2, 5, 10, 0, 0, 2, 9) + query(2)) // address length 5
            pipe.deliver(byteArrayOf(0, 0, 0, 3, 4, 10, 0, 0)) // address cut short
            pipe.deliver(queryFrame(4, "10.0.0.2", query(4)).copyOf(4 + 1 + 4 + 11)) // DNS header cut short
            pipe.deliver(queryFrame(5, "10.0.0.2", query(5)))
            val reply = pipe.nextSent()!!
            assertEquals(5, reply.seq())
            assertArrayEquals(answerTo(query(5)), reply.dns())
            assertEquals(1, answerer.calls.get())
            assertNull(pipe.nextSent(200))
        } finally {
            pipe.endOfFile()
            served.stop()
        }
    }

    @Test
    fun `a query from a source the answerer refuses is never answered`() {
        val pipe = FakePipe()
        val answerer = ScriptedAnswerer(allowed = { it == InetAddress.getByName("10.0.0.2") })
        val served = Served(pipe, { answerer })
        try {
            pipe.deliver(queryFrame(1, "10.0.0.9", query(1)))
            pipe.deliver(queryFrame(2, "10.0.0.2", query(2)))
            assertEquals(2, pipe.nextSent()!!.seq())
            assertEquals(1, answerer.calls.get())
            assertNull(pipe.nextSent(200))
        } finally {
            pipe.endOfFile()
            served.stop()
        }
    }

    @Test
    fun `a query that arrives with no answerer current is dropped`() {
        val pipe = FakePipe()
        val answerer = ScriptedAnswerer()
        val lookups = AtomicInteger()
        val served = Served(pipe, { if (lookups.incrementAndGet() == 1) null else answerer })
        try {
            pipe.deliver(queryFrame(1, "10.0.0.2", query(1)))
            pipe.deliver(queryFrame(2, "10.0.0.2", query(2)))
            assertEquals(2, pipe.nextSent()!!.seq())
            assertEquals(1, answerer.calls.get())
            assertNull(pipe.nextSent(200))
        } finally {
            pipe.endOfFile()
            served.stop()
        }
    }

    @Test
    fun `the answerer current when a query arrives answers it even after another becomes current`() {
        val pipe = FakePipe()
        val gate = CompletableDeferred<Unit>()
        val first = ScriptedAnswerer(reply = { _, query -> gate.await(); answerTo(query) })
        val second = ScriptedAnswerer()
        val current = AtomicReference<DnsAnswerer?>(first)
        val served = Served(pipe, { current.get() })
        try {
            pipe.deliver(queryFrame(1, "10.0.0.2", query(1)))
            waitUntil("the first answerer is answering") { first.calls.get() == 1 }
            current.set(second)
            gate.complete(Unit)
            assertEquals(1, pipe.nextSent()!!.seq())
            assertEquals(0, second.calls.get())
            pipe.deliver(queryFrame(2, "10.0.0.2", query(2)))
            assertEquals(2, pipe.nextSent()!!.seq())
            assertEquals(1, second.calls.get())
            assertEquals(1, first.calls.get())
        } finally {
            gate.complete(Unit)
            pipe.endOfFile()
            served.stop()
        }
    }

    @Test
    fun `queries over the in-flight cap are dropped, not queued`() {
        val pipe = FakePipe()
        val gate = CompletableDeferred<Unit>()
        val answerer = ScriptedAnswerer(reply = { _, query -> gate.await(); answerTo(query) })
        val served = Served(pipe, { answerer }, maxInFlight = 2)
        try {
            (1..3).forEach { seq -> pipe.deliver(queryFrame(seq, "10.0.0.2", query(seq))) }
            waitUntil("two answers are in flight") { answerer.calls.get() == 2 }
            waitUntil("the third frame was read and handled") { pipe.receiveCalls.get() == 4 }
            gate.complete(Unit)
            assertEquals(setOf(1, 2), setOf(pipe.nextSent()!!.seq(), pipe.nextSent()!!.seq()))
            assertNull(pipe.nextSent(300))
            assertEquals(2, answerer.calls.get())
        } finally {
            gate.complete(Unit)
            pipe.endOfFile()
            served.stop()
        }
    }

    @Test
    fun `session cancellation releases even a not-yet-started answer slot`() {
        val pipe = FakePipe()
        val retired = ScriptedAnswerer(job = Job().apply { cancel() })
        val replacement = ScriptedAnswerer()
        val current = AtomicReference<DnsAnswerer?>(retired)
        val served = Served(pipe, { current.get() }, maxInFlight = 1)
        try {
            pipe.deliver(queryFrame(1, "10.0.0.2", query(1)))
            waitUntil("the retired session's frame was consumed") { pipe.receiveCalls.get() == 2 }
            current.set(replacement)
            // The slot the cancelled answer never used must come back on its own. Its cancelled launch unwinds on
            // Dispatchers.IO, so a frame may arrive before it has: keep asking until answered, as the empty-answer
            // case above does. A leaked slot leaves every later query of this channel unanswered.
            var reply: ByteArray? = null
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (reply == null && System.nanoTime() < deadline) {
                pipe.deliver(queryFrame(2, "10.0.0.2", query(2)))
                reply = pipe.nextSent(50)
            }
            assertNotNull("the cancelled answer never released its slot", reply)
            assertEquals(0, retired.calls.get())
            assertEquals(2, reply!!.seq())
            assertTrue(served.failures.isEmpty())
        } finally {
            pipe.endOfFile()
            served.stop()
        }
    }

    @Test
    fun `an answer that fails or comes back empty still frees its slot`() {
        val pipe = FakePipe()
        val answerer = ScriptedAnswerer(
            reply = { call, query ->
                when (call) {
                    1 -> throw IllegalStateException("upstream blew up")
                    2 -> null
                    else -> answerTo(query)
                }
            },
        )
        val served = Served(pipe, { answerer }, maxInFlight = 1)
        try {
            // One slot: a frame that arrives while it is taken is dropped, so keep asking until an answer comes back.
            // A slot that leaked on the failure or the empty answer would leave every later query unanswered.
            var seq = 0
            var reply: ByteArray? = null
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (reply == null && System.nanoTime() < deadline) {
                seq++
                pipe.deliver(queryFrame(seq, "10.0.0.2", query(seq)))
                reply = pipe.nextSent(50)
            }
            assertNotNull("no query was answered after a failed and an empty answer", reply)
            assertTrue(answerer.calls.get() >= 3)
            assertTrue(served.failures.isEmpty())
        } finally {
            pipe.endOfFile()
            served.stop()
        }
    }

    @Test
    fun `an answer over 8192 bytes goes back truncated to its header and question`() {
        val pipe = FakePipe()
        val big = query(0x4242, "big.example")
        val answerer = ScriptedAnswerer(reply = { _, query -> answerTo(query, records = 400) })
        val served = Served(pipe, { answerer })
        try {
            assertTrue(answerTo(big, records = 400).size > MAX_CHANNEL_ANSWER)
            pipe.deliver(queryFrame(9, "10.0.0.2", big))
            val reply = pipe.nextSent()!!
            assertEquals(9, reply.seq())
            val dns = DnsMessage(reply.dns())
            assertEquals(0x4242, dns.id)
            assertTrue(dns.truncated)
            assertEquals(DnsMessage(big).questions, dns.questions)
            assertTrue(dns.answerSection.isEmpty())
            assertTrue(reply.dns().size <= MAX_CHANNEL_ANSWER)
        } finally {
            pipe.endOfFile()
            served.stop()
        }
    }

    @Test
    fun `an answer of exactly 8192 bytes goes back as it is`() {
        val pipe = FakePipe()
        val exact = ByteArray(MAX_CHANNEL_ANSWER) { (it % 251).toByte() }
        val served = Served(pipe, { ScriptedAnswerer(reply = { _, _ -> exact }) })
        try {
            pipe.deliver(queryFrame(3, "10.0.0.2", query(3)))
            val reply = pipe.nextSent()!!
            assertEquals(3, reply.seq())
            assertArrayEquals(exact, reply.dns())
        } finally {
            pipe.endOfFile()
            served.stop()
        }
    }

    @Test
    fun `a query with no answer sends no frame`() {
        val pipe = FakePipe()
        val answerer = ScriptedAnswerer(reply = { call, query -> if (call == 1) null else answerTo(query) })
        val served = Served(pipe, { answerer })
        try {
            pipe.deliver(queryFrame(1, "10.0.0.2", query(1)))
            waitUntil("the first query was answered with nothing") { answerer.calls.get() == 1 }
            pipe.deliver(queryFrame(2, "10.0.0.2", query(2)))
            assertEquals(2, pipe.nextSent()!!.seq())
            assertNull(pipe.nextSent(200))
        } finally {
            pipe.endOfFile()
            served.stop()
        }
    }

    @Test
    fun `end-of-file ends the loop without closing the pipe`() {
        val pipe = FakePipe()
        val served = Served(pipe, { ScriptedAnswerer() })
        try {
            pipe.endOfFile()
            assertTrue(served.ended.await(5, TimeUnit.SECONDS))
            assertFalse(pipe.closed)
        } finally {
            served.stop()
        }
    }

    @Test
    fun `a receive failure ends the loop without closing the pipe`() {
        val pipe = FakePipe()
        val served = Served(pipe, { ScriptedAnswerer() })
        try {
            pipe.breakReceive()
            assertTrue(served.ended.await(5, TimeUnit.SECONDS))
            assertFalse(pipe.closed)
        } finally {
            served.stop()
        }
    }

    @Test
    fun `a failed send loses that answer only`() {
        val pipe = FakePipe()
        val answerer = ScriptedAnswerer()
        val served = Served(pipe, { answerer })
        try {
            pipe.failSends = true
            pipe.deliver(queryFrame(1, "10.0.0.2", query(1)))
            waitUntil("the first answer's send was tried") { pipe.sendCalls.get() == 1 }
            pipe.failSends = false
            pipe.deliver(queryFrame(2, "10.0.0.2", query(2)))
            assertEquals(2, pipe.nextSent()!!.seq())
            assertEquals(1L, served.ended.count)
            assertTrue(served.failures.isEmpty())
        } finally {
            pipe.endOfFile()
            served.stop()
        }
    }
}
