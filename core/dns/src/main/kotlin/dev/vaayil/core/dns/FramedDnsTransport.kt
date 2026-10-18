package dev.vaayil.core.dns

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import org.minidns.dnsmessage.DnsMessage

/**
 * The largest DNS answer the channel carries: the stub resolver's receive buffer on Android 9 and later (MAXPACKET in
 * bionic 9.0 libc/dns/net/getaddrinfo.c, netd 10 resolv/getaddrinfo.cpp, DnsResolver resolv_private.h). One cap on
 * every API level: on Android 8.x, whose bionic took 64 KiB (getaddrinfo.c MAXPACKET), an answer between 8 and 64
 * KiB comes back TC=1, and its TCP retry is reset ([truncatedReply]).
 */
const val MAX_CHANNEL_ANSWER = 8_192

/**
 * A frame-preserving duplex pipe: every [receive] returns one whole frame and every [send] writes one. Production
 * wraps Vaayil's end of libwg-go's DNS channel (a SOCK_SEQPACKET socket, which keeps record boundaries: Linux
 * net/unix/af_unix.c unix_seqpacket_sendmsg and unix_seqpacket_recvmsg); tests use a fake. The interface keeps
 * `:core:dns` free of android.*.
 */
interface DatagramPipe : Closeable {
    /**
     * Blocks until the next frame arrives, copies it into [buffer] and returns its length; returns -1 at end-of-file.
     * Throws [IOException] when the pipe fails.
     */
    fun receive(buffer: ByteArray): Int

    /** Writes the first [length] bytes of [buffer] as one frame. Throws [IOException] when the pipe fails. */
    fun send(buffer: ByteArray, length: Int)
}

/** What answers one query: the D6/T-9 source rule and the answer itself. [DnsResponder] is the production one. */
interface DnsAnswerer {
    /** Whether a query from [source] may be answered at all. */
    fun acceptsSource(source: InetAddress): Boolean

    /** The wire answer to [query], or null when there is nothing to send back. */
    suspend fun answer(query: ByteArray): ByteArray?

    /**
     * The Job this answerer's answers run under, null when the transport's own scope owns them. Cancelling it stops
     * the answers that have not started and the ones in flight, which is how a retired or staged resolver session
     * stops answering at once (A1). [DnsResponder] has no owner.
     */
    val answerJob: Job? get() = null
}

/**
 * Serves DNS over libwg-go's DNS channel (spec §5.3, §6.4). A query frame is `seq: u32 · addrLen: u8 (4 or 16) ·
 * the client's address · the DNS message`; an answer frame is `seq: u32 · the DNS message`. Integers are big-endian.
 *
 * [run] blocks its thread, reading frames until end-of-file or a pipe error. For each query frame it:
 * 1. parses the header and drops a malformed frame;
 * 2. takes the answerer that is [current] when the frame arrives, and drops the query when there is none;
 * 3. drops the query unless that answerer accepts the frame's client address (D6/T-9);
 * 4. answers on [scope] — under the answerer's [DnsAnswerer.answerJob] when it has one, so its owner can cancel an
 *    answer that has not started — with at most [maxInFlight] queries in flight, dropping a query over the cap;
 * 5. turns an answer over [MAX_CHANNEL_ANSWER] bytes into a truncated reply: the header and question with TC=1;
 * 6. sends the answer frame, or nothing when there is no answer. A failed send loses that answer only.
 *
 * The caller owns [pipe] and closes it after [run] returns.
 */
class FramedDnsTransport(
    private val pipe: DatagramPipe,
    private val scope: CoroutineScope,
    private val current: () -> DnsAnswerer?,
    maxInFlight: Int = 64,
) {
    private val permits = Semaphore(maxInFlight)

    fun run() {
        val buffer = ByteArray(FRAME_BUFFER_BYTES)
        while (true) {
            val length = try {
                pipe.receive(buffer)
            } catch (e: IOException) {
                return // the pipe failed: the channel is dead
            }
            if (length < 0) return // end-of-file: libwg-go closed or replaced its end (af_unix.c unix_release_sock)
            val frame = QueryFrame.parse(buffer, length) ?: continue
            val answerer = current() ?: continue
            if (!answerer.acceptsSource(frame.client)) continue
            if (!permits.tryAcquire()) continue
            // The permit comes back when the answer's Job completes, a cancellation before it ever started included.
            val job = answerer.answerJob
            val answer = if (job == null) {
                scope.launch(Dispatchers.IO) { sendAnswer(frame, answerer) }
            } else {
                scope.launch(job + Dispatchers.IO) { sendAnswer(frame, answerer) }
            }
            answer.invokeOnCompletion { permits.release() }
        }
    }

    /**
     * [answerer]'s answer to [frame]: one frame at most, nothing when there is no answer, and nothing after the
     * answer's Job was cancelled.
     */
    private suspend fun sendAnswer(frame: QueryFrame, answerer: DnsAnswerer) {
        val answer = runCatching { answerer.answer(frame.query) }.getOrNull() ?: return
        val dns = if (answer.size > MAX_CHANNEL_ANSWER) truncatedReply(answer) ?: return else answer
        val reply = frame.seq + dns
        runCatching { pipe.send(reply, reply.size) }
    }

    private class QueryFrame(val seq: ByteArray, val client: InetAddress, val query: ByteArray) {
        companion object {
            fun parse(buffer: ByteArray, length: Int): QueryFrame? {
                if (length < SEQ_BYTES + 1) return null
                val addressBytes = buffer[SEQ_BYTES].toInt() and 0xff
                if (addressBytes != 4 && addressBytes != 16) return null
                val dnsStart = SEQ_BYTES + 1 + addressBytes
                if (length < dnsStart + DNS_HEADER_BYTES) return null
                return QueryFrame(
                    seq = buffer.copyOfRange(0, SEQ_BYTES),
                    client = InetAddress.getByAddress(buffer.copyOfRange(SEQ_BYTES + 1, dnsStart)),
                    query = buffer.copyOfRange(dnsStart, length),
                )
            }
        }
    }

    private companion object {
        const val SEQ_BYTES = 4
        const val DNS_HEADER_BYTES = 12
        const val FRAME_BUFFER_BYTES = 65_536
    }
}

/**
 * The header and question of [answer] with TC set and no records (RFC 1035 §4.1.1): what the stub resolver gets
 * instead of a datagram its receive buffer ([MAX_CHANNEL_ANSWER]) would cut short. Its TCP retry (on TC=1, send_dg
 * sets v_circuit and res_nsend asks the same server over TCP: bionic libc/dns/resolv/res_send.c, DnsResolver
 * res_send.cpp) is reset, because DNS over TCP to the resolver is out of scope (B1 spec §11). Null when [answer]
 * does not parse.
 */
internal fun truncatedReply(answer: ByteArray): ByteArray? = runCatching {
    DnsMessage(answer).asBuilder()
        .setTruncated(true)
        .setAnswers(emptyList())
        .setNameserverRecords(emptyList())
        .setAdditionalResourceRecords(emptyList())
        .build()
        .toArray()
}.getOrNull()
