package dev.vaayil.core.dns

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull

/**
 * DNS over sockets: answers UDP datagrams and RFC 1035 2-byte-framed TCP queries on [bindAddress]:[port]. Since B1
 * production binds no socket (libwg-go hands queries over the DNS channel to [FramedDnsTransport]); these socket
 * transports remain the JVM test harness for the answer path and the upstreams, on loopback.
 *
 * Every query is answered by a [DnsResponder] built from [classify], [tcpFor] and [isAllowedSource]: one question
 * goes upstream under a 4 s budget, SERVFAIL on upstream failure, REFUSED for zero or several questions, and a TC=1
 * UDP answer is re-fetched over TCP.
 *
 * [start]/[stop] are idempotent and safe from any thread; stop() closes the sockets and every
 * live TCP connection, then cancels the child scope so no per-query work outlives the server.
 * Per-query work runs fire-and-forget on Dispatchers.IO, bounded by the 4 s budget and, for
 * datagrams, by the [maxConcurrentAnswers] flood cap; TCP messages carry a total-arrival
 * budget and connections a lifetime cap, both independent of client chattiness (D2).
 */
class LocalDnsServer(
    private val scope: CoroutineScope,
    private val bindAddress: InetAddress,
    private val classify: (qname: String) -> DnsUpstream,
    private val port: Int = 53,
    private val tcpFor: (DnsUpstream) -> DnsUpstream? = { null },
    private val maxConcurrentAnswers: Int = 64, // flood cap on per-datagram answer jobs (D6)
    /** D6/T-9: only these sources are answered; production passes the VPN interface's own
        addresses plus loopback. Datagrams/connections from anywhere else are dropped
        before any work. */
    private val isAllowedSource: (InetAddress) -> Boolean = { true },
    private val maxConcurrentTcpConnections: Int = 8, // D2: over-cap connections close immediately
    private val tcpMessageBudgetMs: Long = 10_000, // D2: TOTAL time one framed message may take to arrive
    private val tcpConnectionLifetimeMs: Long = 120_000, // D2: per-connection cap, however chatty the client
) {
    private var udp: DatagramSocket? = null
    private var tcp: ServerSocket? = null
    private var acceptJob: Job? = null
    private var udpJob: Job? = null
    private val closed = AtomicBoolean(false)
    private val answerPermits = Semaphore(maxConcurrentAnswers)
    private val tcpPermits = Semaphore(maxConcurrentTcpConnections)
    private val connections = ConcurrentHashMap.newKeySet<Socket>()
    /** Child scope cancelled in stop(): per-answer jobs and TCP handlers die with the server (D9). */
    private var lifecycle: CoroutineScope? = null
    /** Answers every query either socket transport receives (spec §6.4: the same answer() as the DNS channel). */
    private val responder = DnsResponder(classify, tcpFor, isAllowedSource)

    @Synchronized
    fun start() {
        if (udp != null) return
        closed.set(false)
        // SO_REUSEADDR: restart re-binds the same port immediately after close, and a
        // just-closed accepted connection can leave the port occupied — without the flag
        // that rebind throws BindException. On slow 2-core runners even the flag plus an
        // immediate rebind raced, so binds are retried with a short backoff; a genuine
        // conflict (another live server on the port) still fails loud within ~0.5 s.
        var lastError: IOException? = null
        for (attempt in 1..5) {
            try {
                val (udpSocket, tcpSocket) = createSockets()
                udp = udpSocket
                tcp = tcpSocket
                // Child scope: stop() cancels it (D9), and the loops' per-work launches land on
                // it, so nothing outlives the server.
                val serverScope = CoroutineScope(SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.IO)
                lifecycle = serverScope
                udpJob = serverScope.launch { udpLoop(serverScope) }
                acceptJob = serverScope.launch { tcpLoop(serverScope) }
                return
            } catch (e: IOException) {
                lastError = e
                if (attempt < 5) Thread.sleep(100)
            }
        }
        closed.set(true) // never started: a stale flag would make stop() close foreign sockets
        throw IOException("resolver bind on $bindAddress:$port failed after 5 attempts (udp+tcp)", lastError)
    }

    /** Binds both family sockets; on any failure every opened fd is closed before throwing. */
    private fun createSockets(): Pair<DatagramSocket, ServerSocket> {
        val udpSocket = DatagramSocket(null)
        try {
            udpSocket.reuseAddress = true // TIME_WAIT-safe rebind on restart
            udpSocket.soTimeout = 1_000
            udpSocket.bind(InetSocketAddress(bindAddress, port))
        } catch (e: IOException) {
            runCatching { udpSocket.close() } // constructor allocated the fd; bind failed
            throw e
        }
        val tcpSocket = ServerSocket()
        try {
            tcpSocket.reuseAddress = true // TIME_WAIT-safe rebind on restart
            tcpSocket.soTimeout = 1_000
            tcpSocket.bind(InetSocketAddress(bindAddress, port), 50)
        } catch (e: IOException) {
            runCatching { tcpSocket.close() } // same: fd exists even though bind failed
            runCatching { udpSocket.close() } // keep the pair atomic across retries
            throw e
        }
        return udpSocket to tcpSocket
    }

    @Synchronized
    fun stop() {
        closed.set(true)
        runCatching { udp?.close() }
        runCatching { tcp?.close() }
        udp = null
        tcp = null
        connections.forEach { runCatching { it.close() } } // live TCP handlers must not outlive stop() (D2)
        connections.clear()
        udpJob?.cancel()
        acceptJob?.cancel()
        // Bounded join: the 1 s soTimeout ticks the loops at most ~1 s after the sockets
        // close, so a restart never re-binds a port a parked loop still holds (CI flake D5).
        runBlocking {
            withTimeoutOrNull(2_000) {
                udpJob?.join()
                acceptJob?.join()
            }
        }
        udpJob = null
        acceptJob = null
        lifecycle?.cancel() // answer jobs and TCP handlers die with the server (D9, scope part)
        lifecycle = null
    }

    private suspend fun udpLoop(workers: CoroutineScope) {
        val sock = udp ?: return
        val buf = ByteArray(65535)
        while (!closed.get()) {
            val p = DatagramPacket(buf, buf.size)
            // receive blocks; stop() closes the socket to break the loop
            try {
                sock.receive(p)
            } catch (e: SocketTimeoutException) {
                continue // idle tick: re-check closed, keep receiving
            } catch (e: IOException) {
                if (closed.get() || sock.isClosed) return else throw e
            }
            if (!isAllowedSource(p.address)) continue // foreign source: dropped before any work (D6, T-9)
            val query = p.data.copyOf(p.length)
            // Flood cap (D6): each answer job holds memory for the reply and an IO thread
            // slice for up to the 4 s budget; a query flood used to launch unbounded jobs.
            // Over the cap the datagram is dropped unserved — UDP clients retransmit, and
            // the lifetime-bounded exposure stays bounded.
            if (!answerPermits.tryAcquire()) continue
            // Sibling of the loop, not its child: stop() joins the loops only, so an answer stuck
            // in a blocking upstream call cannot hold the main thread; lifecycle.cancel() reaps it.
            workers.launch(Dispatchers.IO) {
                try {
                    val reply = runCatching { answer(query) }.getOrNull()
                    if (reply != null) {
                        runCatching { sock.send(DatagramPacket(reply, reply.size, p.address, p.port)) }
                    }
                } finally {
                    answerPermits.release()
                }
            }
            // fire-and-forget: the socket keeps receiving; replies race independently
        }
    }

    private suspend fun tcpLoop(workers: CoroutineScope) {
        val server = tcp ?: return
        while (!closed.get()) {
            // accept blocks; stop() closes the socket to break the loop; the 1 s soTimeout
            // bounds the wait either way
            val conn = try {
                server.accept()
            } catch (e: SocketTimeoutException) {
                continue // idle tick: re-check closed, keep accepting
            } catch (e: IOException) {
                if (closed.get() || server.isClosed) return else throw e
            }
            if (!isAllowedSource(conn.inetAddress)) {
                runCatching { conn.close() } // foreign source: closed before any work (D6, T-9)
                continue
            }
            if (!tcpPermits.tryAcquire()) {
                runCatching { conn.close() } // over the connection cap (D2): closed immediately
                continue
            }
            connections += conn // so stop() can close live handlers (D2)
            workers.launch(Dispatchers.IO) {
                try {
                    handleTcpConnection(conn)
                } finally {
                    connections -= conn
                    tcpPermits.release()
                }
            }
        }
    }

    private suspend fun handleTcpConnection(conn: Socket) {
        try {
            val connectionDeadline = System.nanoTime() + tcpConnectionLifetimeMs * 1_000_000L
            conn.use { c ->
                val input = c.getInputStream()
                val output = c.getOutputStream()
                while (System.nanoTime() < connectionDeadline) { // per-connection lifetime cap (D2)
                    // Total-message deadline: a drip-feeding client used to restart the
                    // per-read timeout on every byte and pin an IO thread indefinitely (D2).
                    val deadline = System.nanoTime() + tcpMessageBudgetMs * 1_000_000L
                    // The read timeout is capped to the same budget: readFramedTcpMessage only
                    // checks the deadline BETWEEN reads, so a client that stalls mid-message
                    // would otherwise block until the listener's idle timeout instead (D2).
                    c.soTimeout = tcpMessageBudgetMs.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
                    val query = readFramedTcpMessage(input, deadlineNanos = deadline) ?: return
                    val reply = runCatching { answer(query) }.getOrNull() ?: return
                    writeFramedTcpMessage(output, reply)
                }
            }
        } catch (_: IOException) {
            // client went away — done
        }
    }

    /** Wire query → wire response, answered by the shared [DnsResponder]. */
    internal suspend fun answer(query: ByteArray): ByteArray? = responder.answer(query)
}
