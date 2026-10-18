package dev.vaayil.core.tunnel.wg

import dev.vaayil.core.dns.DatagramPipe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A scripted end of the DNS channel (B1): frames the test delivers come out of receive(), frames the transport sends
 * back are kept. It never touches libwg-go, so JVM tests never load the native library.
 */
internal class FakePipe : DatagramPipe {
    private val incoming = LinkedBlockingQueue<ByteArray>()
    private val sent = LinkedBlockingQueue<ByteArray>()
    private val closed = CountDownLatch(1)

    /** Queues one query frame for the reader. */
    fun deliver(frame: ByteArray) = incoming.put(frame)

    /** Makes the reader see end-of-file, as when libwg-go's end goes away (af_unix.c unix_release_sock). */
    fun endOfFile() = incoming.put(EOF)

    /** The next frame sent back, waiting up to [timeoutMs] (5 s by default); null when none came. */
    fun nextSent(timeoutMs: Long = 5_000): ByteArray? = sent.poll(timeoutMs, TimeUnit.MILLISECONDS)

    /** Waits up to 5 s for the owner to close this end. */
    fun awaitClosed() {
        check(closed.await(5, TimeUnit.SECONDS)) { "the channel was never closed" }
    }

    override fun receive(buffer: ByteArray): Int {
        val frame = incoming.take()
        if (frame === EOF) return -1
        frame.copyInto(buffer)
        return frame.size
    }

    override fun send(buffer: ByteArray, length: Int) {
        sent.put(buffer.copyOf(length))
    }

    override fun close() = closed.countDown()

    private companion object {
        val EOF = ByteArray(0)
    }
}
