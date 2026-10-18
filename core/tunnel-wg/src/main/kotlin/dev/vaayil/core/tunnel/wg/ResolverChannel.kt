package dev.vaayil.core.tunnel.wg

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import dev.vaayil.core.dns.DatagramPipe
import java.io.IOException

/**
 * Vaayil's end of libwg-go's DNS channel (B1): a SOCK_SEQPACKET socket from `DnsIntercept.open`, so every read
 * returns one whole frame and every write sends one (Linux net/unix/af_unix.c unix_seqpacket_recvmsg and
 * unix_seqpacket_sendmsg keep record boundaries). One reader thread calls [receive] (the controller's
 * [dev.vaayil.core.dns.FramedDnsTransport]); answers are written from the answer coroutines, one at a time.
 */
internal class ResolverChannel(private val pfd: ParcelFileDescriptor) : DatagramPipe {
    private val writeLock = Any()

    override fun receive(buffer: ByteArray): Int {
        val n = try {
            Os.read(pfd.fileDescriptor, buffer, 0, buffer.size)
        } catch (e: ErrnoException) {
            throw IOException("DNS channel read failed", e)
        }
        // 0: libwg-go closed its end, or replaced the channel (af_unix.c unix_release_sock shuts this end down).
        return if (n == 0) -1 else n
    }

    override fun send(buffer: ByteArray, length: Int) {
        synchronized(writeLock) {
            try {
                Os.write(pfd.fileDescriptor, buffer, 0, length)
            } catch (e: ErrnoException) {
                // EPIPE once libwg-go's end is gone (af_unix.c unix_dgram_sendmsg); one ECONNRESET may come first
                // when that end still held an unread answer (unix_release_sock).
                throw IOException("DNS channel write failed", e)
            }
        }
    }

    override fun close() {
        // Under the write lock: a write reads the fd number before its write(2) (libcore libcore_io_Linux.cpp
        // IO_FAILURE_RETRY), so a close in between could let that write reach a reused descriptor.
        synchronized(writeLock) { runCatching { pfd.close() } }
    }
}
