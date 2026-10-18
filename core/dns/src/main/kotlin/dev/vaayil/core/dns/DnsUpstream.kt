package dev.vaayil.core.dns

import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.ConnectionSpec
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.minidns.dnsmessage.DnsMessage
import org.minidns.dnsmessage.Question

/** An upstream resolver the in-tunnel DNS server can forward queries to. */
interface DnsUpstream {
    /** Returns the response wire message, or null on upstream failure. */
    suspend fun query(queryWire: ByteArray): ByteArray?
    /**
     * Releases this upstream. A1: closure is final and immediate — after it, no query of this upstream creates a
     * socket, dials, writes, dispatches an HTTP call or rebuilds, in-flight attempts included, and an attempt still
     * blocked in a receive ends when its resource closes.
     */
    fun close()
}

/** RFC 1035 2-byte length-prefixed message framing, shared by plain TCP and DoT.
    [deadlineNanos] (System.nanoTime origin) bounds the TOTAL read: a server drip-feeding one
    byte per soTimeout window used to stretch a single attempt to minutes (D2). null = legacy
    per-read budget (DoT's 4 s). */
internal fun readFramedTcpMessage(input: InputStream, deadlineNanos: Long? = null): ByteArray? {
    val hi = input.read()
    val lo = input.read()
    if (hi < 0 || lo < 0) return null
    val len = (hi shl 8) or lo
    val resp = ByteArray(len)
    var read = 0
    while (read < len) {
        if (deadlineNanos != null && System.nanoTime() > deadlineNanos) return null
        val n = input.read(resp, read, len - read)
        if (n < 0) return null
        read += n
    }
    return resp
}

internal fun writeFramedTcpMessage(out: java.io.OutputStream, wire: ByteArray, beforeWrite: () -> Unit = {}) {
    beforeWrite()
    out.write(byteArrayOf(((wire.size shr 8) and 0xFF).toByte(), wire.size.toByte()))
    beforeWrite()
    out.write(wire)
    beforeWrite()
    out.flush()
}

private fun isTruncated(msg: DnsMessage): Boolean = msg.truncated

/**
 * A1: one upstream's closure. The flag is irreversible, and the live set is what a close reaches: every resource
 * (socket, HTTP call) is registered the moment it exists and dropped when its attempt ends, so a close also ends an
 * attempt that is between its own steps, and a resource whose creation lost the race with the close is refused —
 * never used — instead of being published. The monitor guards this bookkeeping alone, never I/O: [register] reports
 * the race and the caller closes its own resource, so no socket close ever runs under the lock.
 */
private class UpstreamClosure<T : Any>(private val closeResource: (T) -> Unit) {
    private val monitor = Any()

    @Volatile
    private var closed = false
    private val live = mutableSetOf<T>()

    val isClosed: Boolean get() = closed

    /** Marks the upstream closed and releases everything still registered. Idempotent, and final. */
    fun close() {
        val registered = synchronized(monitor) {
            closed = true
            live.toList().also { live.clear() }
        }
        registered.forEach { runCatching { closeResource(it) } }
    }

    /** Registers [resource]; false when the upstream is already closed — then the caller closes that resource itself. */
    fun register(resource: T): Boolean = synchronized(monitor) {
        if (closed) false else { live += resource; true }
    }

    fun unregister(resource: T) = synchronized(monitor) { live -= resource }
}

/**
 * A1: whether this attempt may no longer touch the network — its answer's Job was cancelled (the session was staged,
 * deactivated or revoked) or the upstream closed. Checked immediately before every socket creation, dial, TLS step,
 * framed write, DoH dispatch and rebuild.
 */
private fun stopped(context: CoroutineContext, closure: UpstreamClosure<*>): Boolean =
    !context.isActive || closure.isClosed

/** A1: what a guarded network step throws when its [stopped] check fails. Caught by the step's own attempt. */
private class UpstreamStoppedException : IOException("the upstream stopped before this attempt reached the network")

class PlainUdpUpstream(
    private val servers: List<InetAddress>, private val port: Int = 53,
    private val protect: ((DatagramSocket) -> Boolean)? = null, // null = socket rides the tunnel
    /** Test seam (A1): production creates a fresh [DatagramSocket] per server attempt. */
    private val socketFactory: () -> DatagramSocket = { DatagramSocket() },
) : DnsUpstream {
    /** A1: closure is final — after it, no attempt creates a socket, sends or receives again. */
    private val closure = UpstreamClosure<DatagramSocket> { it.close() }

    override suspend fun query(queryWire: ByteArray): ByteArray? = query(queryWire, null)

    /**
     * [deadlineNanos] (System.nanoTime origin) bounds the WHOLE pass: every receive gets
     * min(400 ms, remaining), and an exhausted budget returns null without another send. The
     * caller's coroutine timeout cannot interrupt a blocking receive, so the budget must bound
     * the receive itself (sweep-2 minor). null = per-receive 400 ms, as before.
     */
    suspend fun query(queryWire: ByteArray, deadlineNanos: Long?): ByteArray? = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        for (server in servers) {
            // A1: a closed upstream — or an answer whose Job was cancelled — stops the pass before the next socket.
            if (stopped(context, closure)) return@withContext null
            val result = runCatching {
                socketFactory().use { sock ->
                    // A1: a socket that lost the race with close() is used for nothing; `use` closes it here.
                    if (!closure.register(sock)) return@use null
                    try {
                        val budgetMs = deadlineNanos?.let { (it - System.nanoTime()) / 1_000_000L }
                        if (budgetMs != null && budgetMs <= 0) return@use null
                        sock.soTimeout = if (budgetMs == null) 400 else minOf(400, budgetMs).toInt().coerceAtLeast(1)
                        if (stopped(context, closure)) return@use null
                        if (protect?.invoke(sock) == false) return@use null // never send un-protected
                        if (stopped(context, closure)) return@use null // a close during the callback must not send
                        sock.connect(InetSocketAddress(server, port))       // kernel filters the peer
                        sock.send(DatagramPacket(queryWire, queryWire.size, server, port))
                        val buf = ByteArray(65535)
                        val p = DatagramPacket(buf, buf.size)
                        sock.receive(p)
                        val resp = p.data.copyOf(p.length)
                        if (resp.size < 2 || resp[0] != queryWire[0] || resp[1] != queryWire[1]) {
                            return@use null // wrong/short txid: not an answer to our query
                        }
                        resp
                    } finally {
                        closure.unregister(sock)
                    }
                }
            }
            result.getOrNull()?.let { return@withContext it }
        }
        null
    }

    override fun close() = closure.close()
}

class PlainTcpUpstream(
    private val servers: List<InetAddress>, private val port: Int = 53,
    private val protect: ((Socket) -> Boolean)? = null, // null = socket rides the tunnel
    private val attemptBudgetMillis: Int = 400, // TOTAL per attempt (connect + all reads, D2)
    /** Test seam (A1): production creates a fresh [Socket] per server attempt. */
    private val socketFactory: () -> Socket = { Socket() },
) : DnsUpstream {
    /** A1: closure is final — after it, no attempt creates a socket, dials or writes again. */
    private val closure = UpstreamClosure<Socket> { it.close() }

    override suspend fun query(queryWire: ByteArray): ByteArray? = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        for (server in servers) {
            // A1: a closed upstream — or an answer whose Job was cancelled — stops the pass before the next socket.
            if (stopped(context, closure)) return@withContext null
            val result = runCatching {
                socketFactory().use { sock ->
                    // A1: a socket that lost the race with close() is used for nothing; `use` closes it here.
                    if (!closure.register(sock)) return@use null
                    try {
                        val deadline = System.nanoTime() + attemptBudgetMillis * 1_000_000L
                        sock.soTimeout = attemptBudgetMillis
                        if (stopped(context, closure)) return@use null
                        if (protect?.invoke(sock) == false) return@use null // never connect un-protected
                        if (stopped(context, closure)) return@use null // a close during the callback must not dial
                        val connectBudget = ((deadline - System.nanoTime()) / 1_000_000L).toInt()
                        if (connectBudget <= 0) return@use null
                        sock.connect(InetSocketAddress(server, port), connectBudget)
                        val readBudget = ((deadline - System.nanoTime()) / 1_000_000L).toInt()
                        if (readBudget <= 0) return@use null
                        sock.soTimeout = readBudget
                        writeFramedTcpMessage(sock.getOutputStream(), queryWire) {
                            // A1: the prefix, the body and the flush are each guarded: a close between them sends no more.
                            if (stopped(context, closure)) throw UpstreamStoppedException()
                        }
                        readFramedTcpMessage(
                            sock.getInputStream(),
                            deadlineNanos = deadline,
                        )
                    } finally {
                        closure.unregister(sock)
                    }
                }
            }
            result.getOrNull()?.let { return@withContext it }
        }
        null
    }

    override fun close() = closure.close()
}

/**
 * One transparent retry for a pooled session whose peer went stale (RFC 7766 §3.2 spirit):
 * after an idle period the first exchange on the old connection fails, so the retry happens
 * inside the same call instead of costing the user's query (D4). Exactly one retry — a
 * genuinely dead upstream fails after two attempts, not a loop.
 */
internal suspend fun <S> withStaleSessionRetry(
    acquire: () -> S,                     // session or pooled resource; throwing counts as failure
    exchange: suspend (S) -> ByteArray?,  // null = IO-class failure on this session
    retire: (S) -> Unit,                  // close/discard the dead session before retrying
): ByteArray? {
    val first = runCatching { acquire() }.getOrNull() ?: return null
    exchange(first)?.let { return it }
    retire(first)
    val second = runCatching { acquire() }.getOrNull() ?: return null
    return exchange(second)
}

class DotUpstream(
    private val host: String, private val port: Int,
    /** D1 bootstrap pin: dial these resolved addresses in order while keeping [host] as the
        TLS peer name (SNI + RFC 8310 verification). Empty = dial [host] itself (IP literals). */
    private val dialAddresses: List<String> = emptyList(),
    /** Dial budget per attempt, sliced equally across the pinned addresses ([connectBudgetMs] /
        targets, floored at 500 ms, so many targets can exceed it) — and the stale-session retry
        dials once more inside the same query, so a query can spend it twice. */
    private val connectBudgetMs: Int = 3_000,
    /** Budget for ONE write+read exchange on the session: a drip-feeding or silent server dies
        here (D2). Per exchange, not per query — the stale-session retry runs a second one, so a
        silent endpoint costs 2 x this plus dialling before the query returns null. Measured 1.63 s
        against the 500 ms test budget (3.3 x) while retire() paid a socket timeout to close a
        session whose peer never acknowledges; the abortive close brought it back to 2 x + dials. */
    private val exchangeBudgetMs: Long = 3_500,
    /** Tests inject a locally trusted factory; production uses the platform default (system CAs). */
    private val sslFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
    /** Test seam (A1): production creates a fresh [Socket] per dial attempt. */
    private val socketFactory: () -> Socket = { Socket() },
) : DnsUpstream {
    private val mutex = Mutex()
    private var session: SSLSocket? = null

    /** A1: closure is final — every raw and TLS socket is registered while it exists, so close() reaches them all. */
    private val closure = UpstreamClosure<Socket> { socket ->
        // The peer may never acknowledge (that is why the session is being retired), and its graceful close waits
        // out the socket timeout before giving up; a 1 ms read timeout bounds that shutdown handshake exactly as
        // the existing stale-session retirement does.
        runCatching { socket.soTimeout = 1 }
        socket.close()
    }

    private fun connect(context: CoroutineContext): SSLSocket {
        val targets = if (dialAddresses.isEmpty()) listOf(host) else dialAddresses
        val perTarget = maxOf(500, connectBudgetMs / targets.size)
        var last: Exception? = null
        for (target in targets) { // D4: a dead first pinned address is not fatal, and cannot eat the whole budget
            if (stopped(context, closure)) throw UpstreamStoppedException()
            val raw = socketFactory()
            if (!closure.register(raw)) {
                runCatching { raw.close() } // a close won the race with this dial: never use the socket
                throw UpstreamStoppedException()
            }
            try {
                raw.soTimeout = perTarget
                raw.connect(InetSocketAddress(target, port), perTarget)
                if (stopped(context, closure)) throw UpstreamStoppedException()
                // Rides the tunnel like every other app socket (ruling 2026-09-10): the resolver
                // provider sees the VPN exit address and the local network sees nothing.
                val tls = sslFactory.createSocket(raw, host, port, true) as SSLSocket
                if (!closure.register(tls)) {
                    runCatching { tls.close() } // a close won the race with the TLS wrap: never publish it
                    throw UpstreamStoppedException()
                }
                closure.unregister(raw) // the TLS socket owns it from here (autoClose)
                try {
                    val params = tls.sslParameters
                    params.endpointIdentificationAlgorithm = "HTTPS" // RFC 8310 hostname verification
                    tls.sslParameters = params
                    if (stopped(context, closure)) throw UpstreamStoppedException()
                    tls.startHandshake()
                    return tls
                } catch (e: IOException) {
                    runCatching { tls.close() }
                    throw e
                }
            } catch (e: UpstreamStoppedException) {
                runCatching { raw.close() }
                throw e // stopped: the next target must not be dialled either
            } catch (e: Exception) {
                runCatching { raw.close() } // never leak the raw fd on a failed dial
                last = e
            } finally {
                closure.unregister(raw) // registered only until its TLS socket (or its failure) took over
            }
        }
        throw last ?: IOException("no dial address for $host")
    }

    /** Session-or-connect with the pre-existing one reconnect on connect failure. */
    private fun acquire(context: CoroutineContext): SSLSocket {
        if (stopped(context, closure)) throw UpstreamStoppedException()
        val existing = session?.takeIf { !it.isClosed }
        if (existing != null) return existing
        return try {
            publish(connect(context), context)
        } catch (e: UpstreamStoppedException) {
            throw e // a stopped answer or a closed upstream never dials again
        } catch (e: IOException) {
            if (stopped(context, closure)) throw UpstreamStoppedException()
            session?.close()
            session = null
            publish(connect(context), context)
        }
    }

    /** A1: a fresh connection a close (or a cancelled answer) reached first is closed here, never published. */
    private fun publish(fresh: SSLSocket, context: CoroutineContext): SSLSocket {
        if (stopped(context, closure)) {
            runCatching { fresh.close() }
            throw UpstreamStoppedException()
        }
        session = fresh
        return fresh
    }

    override suspend fun query(queryWire: ByteArray): ByteArray? = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        runCatching {
            mutex.withLock {
                withStaleSessionRetry(
                    acquire = { acquire(context) },
                    // null exchange = dead/EOF session; retire() closes it and the retry
                    // reconnects once inside the same call (D4).
                    exchange = { tls ->
                        runCatching {
                            if (stopped(context, closure)) throw UpstreamStoppedException()
                            tls.soTimeout = exchangeBudgetMs.toInt()
                            writeFramedTcpMessage(tls.outputStream, queryWire) {
                                if (stopped(context, closure)) throw UpstreamStoppedException()
                            }
                            readFramedTcpMessage(tls.inputStream, deadlineNanos = System.nanoTime() + exchangeBudgetMs * 1_000_000)
                        }.getOrNull()
                    },
                    retire = { dead ->
                        // The peer never acknowledged (that is why the session is being retired), and
                        // its graceful close waits out the socket timeout before giving up — while
                        // this call holds the mutex, so every query queued behind it times out too.
                        // A 1 ms read timeout bounds that shutdown handshake: measured 1.63 s ->
                        // 1.18 s on the silent-server fixture (linger 0 alone changed nothing).
                        runCatching { dead.soTimeout = 1 }
                        runCatching { dead.close() }
                        closure.unregister(dead)
                        if (session === dead) session = null
                    },
                )
            }
        }.getOrNull()
    }

    override fun close() {
        closure.close() // A1: closed first, then every registered socket — the session and any in-flight dial
        session = null
    }
}

/**
 * Self-healing wrapper for channels built from a network snapshot (D3): when the wrapped
 * upstream fails — e.g. the Wi-Fi→cellular switch left BYPASS-rule queries targeting dead
 * resolvers — [rebuild] is asked for a fresh upstream built from a new probe, the dead one
 * is closed, and the query is retried once against it. A rebuild failure (no networks, or
 * probe returned nothing) keeps the old upstream and returns null: fail-safe SERVFAIL, no leak.
 */
class ReprobingUpstream(
    private var inner: DnsUpstream,
    private val rebuild: suspend () -> DnsUpstream?,
) : DnsUpstream {
    private val mutex = Mutex()

    /** A1: closure is final; whichever channel is current is registered, so close() reaches it after a swap too. */
    private val closure = UpstreamClosure<DnsUpstream> { runCatching { it.close() } }

    init {
        closure.register(inner)
    }

    /** The currently-wrapped upstream (tests and owners use this for routing/close). */
    val current: DnsUpstream get() = inner

    override suspend fun query(queryWire: ByteArray): ByteArray? {
        val context = currentCoroutineContext()
        // A1: a closed reprobe — or an answer whose Job was cancelled — asks no channel and rebuilds nothing.
        if (stopped(context, closure)) return null
        inner.query(queryWire)?.let { return it }
        if (stopped(context, closure)) return null
        return mutex.withLock {
            // Double-check under the lock: a concurrent failure may have rebuilt already.
            if (stopped(context, closure)) return@withLock null
            inner.query(queryWire)?.let { return@withLock it }
            if (stopped(context, closure)) return@withLock null
            val fresh = runCatching { rebuild() }.getOrNull() ?: return@withLock null
            // A1: a rebuild whose epoch or upstream ended first closes its late result instead of publishing it.
            if (stopped(context, closure)) {
                runCatching { fresh.close() }
                return@withLock null
            }
            // A1: the registration has to win too — a close landing between the check above and this line would
            // otherwise leave the fresh channel untracked (never closed) while this wrapper installs and queries it.
            if (!closure.register(fresh)) {
                runCatching { fresh.close() }
                return@withLock null
            }
            val dead = inner
            inner = fresh
            closure.unregister(dead)
            runCatching { dead.close() }
            fresh.query(queryWire)
        }
    }

    override fun close() {
        closure.close()
    }
}

class DohUpstream(ok: OkHttpClient, private val url: String, pinnedDns: okhttp3.Dns? = null) : DnsUpstream { // RFC 8484 POST application/dns-message
    private val pinnedHost = url.toHttpUrlOrNull()?.host
    internal val client = ok.newBuilder()
        .callTimeout(4, TimeUnit.SECONDS)
        .proxy(Proxy.NO_PROXY) // A2: direct connection — the payload client must never consult the platform's proxy selector
        .followRedirects(false) // D8: a redirect must not re-POST the query (with its names) elsewhere
        .followSslRedirects(false)
        .connectionSpecs(listOf(ConnectionSpec.RESTRICTED_TLS)) // D8: TLS only, modern suites
        .connectionPool(ConnectionPool(2, 1, TimeUnit.MINUTES)) // own pool: close() evicts only this upstream's connections, never a replacement's (D8)
        .apply {
            if (pinnedDns != null && pinnedHost != null) {
                dns(HostnamePinnedDns(pinnedHost, pinnedDns))
            }
        }
        .build()

    /** A1: closure is final; this upstream's in-flight calls are registered so a close cancels them, and only them. */
    private val closure = UpstreamClosure<Call> { it.cancel() }

    override suspend fun query(queryWire: ByteArray): ByteArray? = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        // A1: a closed upstream dispatches nothing — no connection, no HTTP call.
        if (stopped(context, closure)) return@withContext null
        val call = client.newCall(
            Request.Builder()
                .url(url)
                .header("Accept", "application/dns-message")
                .post(queryWire.toRequestBody("application/dns-message".toMediaType()))
                .build(),
        )
        // A1: a call that lost the race with close() is never executed; close() cancelled it already.
        if (!closure.register(call)) return@withContext null
        try {
            runCatching {
                executeCall(call).use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    val body = resp.body ?: return@withContext null
                    val type = body.contentType() ?: return@withContext null
                    // OkHttp lower-cases type and subtype; media types are case-insensitive (RFC 9110 §8.3.1, D8).
                    if (type.type != "application" || type.subtype != "dns-message") return@withContext null
                    val declared = body.contentLength()
                    if (declared > MAX_BODY_BYTES) return@withContext null
                    val source = body.source()
                    if (declared < 0) {
                        // Unknown length: pull at most cap+1 bytes (or EOF) and refuse anything longer (D8).
                        source.request(MAX_BODY_BYTES + 1)
                        if (source.buffer.size > MAX_BODY_BYTES) return@withContext null
                    }
                    source.readByteArray()
                }
            }.getOrNull()
        } finally {
            closure.unregister(call)
        }
    }

    /**
     * A1: [call]'s blocking execute stays on this thread (the caller is on [Dispatchers.IO]), inside a bridge that
     * lets this answer's cancellation reach it: the continuation is armed with [Call.cancel] BEFORE execute, so a
     * staged, deactivated or revoked answer stops its own HTTP call at once — and only its own, because the OkHttp
     * dispatcher and its connection pool are shared. The upstream itself stays open: a later query runs from a fresh
     * Job. A continuation that a cancellation already completed ignores the late resume.
     */
    private suspend fun executeCall(call: Call): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { call.cancel() }
        try {
            cont.resume(call.execute())
        } catch (e: IOException) {
            cont.resumeWithException(e)
        }
    }

    override fun close() {
        closure.close() // A1: closed first, then this upstream's in-flight calls are cancelled
        client.connectionPool.evictAll() // D8: pooled TLS connections must not outlive the tunnel session
    }

    /** D8: the bootstrap pin applies ONLY to the endpoint's own hostname; any other host
        (there is none in practice — redirects are refused) falls back to system resolution. */
    private class HostnamePinnedDns(
        private val pinnedHost: String,
        private val pinned: okhttp3.Dns,
    ) : okhttp3.Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            if (hostname.equals(pinnedHost, ignoreCase = true)) pinned.lookup(hostname)
            else Dns.SYSTEM.lookup(hostname)
    }

    private companion object {
        const val MAX_BODY_BYTES = 65_535L // RFC 8484 over UDP truncation limit
    }
}

/** LRU cache of upstream responses, TTL-honoring (0..86400 s clamp). */
class DnsResponseCache(maxEntries: Int = 256) {
    private class Cached(val wire: ByteArray, val expiresAtEpochMs: Long)

    private val mutex = Mutex()
    private val map = object : LinkedHashMap<String, Cached>(16, 1f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>): Boolean =
            size > maxEntries
    }

    /** Returns the cached wire response, or null when absent/expired (expired entries are dropped). */
    suspend fun get(key: String): ByteArray? = mutex.withLock {
        val hit = map[key] ?: return@withLock null
        if (hit.expiresAtEpochMs <= System.currentTimeMillis()) {
            map.remove(key)
            null
        } else {
            hit.wire.copyOf() // defensive copy: callers (txid mirroring) must not race on the cached bytes
        }
    }

    suspend fun put(key: String, wire: ByteArray, ttlSeconds: Long) {
        mutex.withLock {
            map[key] = Cached(wire, System.currentTimeMillis() + ttlSeconds * 1000)
        }
    }
}

/** Decorator serving repeat queries from [cache]; stores only what the upstream answered for the
    querying question (D7), and only NOERROR with >=1 answer, never truncated. */
class CachingUpstream(
    private val inner: DnsUpstream,
    private val cache: DnsResponseCache,
) : DnsUpstream {
    override suspend fun query(queryWire: ByteArray): ByteArray? {
        val query = runCatching { DnsMessage(queryWire) }.getOrNull()?.questions?.singleOrNull()
            ?: return inner.query(queryWire)
        val key = cacheKey(query)
        // The cached wire bytes carry the FIRST query's txid; stub resolvers drop
        // replies whose id mismatches the query. get() returns a defensive copy,
        // so patching the served bytes is safe.
        cache.get(key)?.let { c -> return c.also { c[0] = queryWire[0]; c[1] = queryWire[1] } }
        val resp = inner.query(queryWire) ?: return null
        val msg = runCatching { DnsMessage(resp) }.getOrNull() ?: return resp
        // D7: store only what the upstream actually answered for OUR question — a spoofed
        // or mismatched reply must not be replayed to every app on the device.
        val answeredTheQuery = msg.answers(query)
        if (answeredTheQuery && msg.responseCode == DnsMessage.RESPONSE_CODE.NO_ERROR &&
            msg.answerSection.isNotEmpty() && !isTruncated(msg)
        ) {
            val ttlSeconds = msg.answerSection.minOf { it.ttl }.coerceIn(0, 86_400)
            cache.put(key, resp, ttlSeconds)
        }
        return resp
    }
    override fun close() = inner.close()
}

private fun cacheKey(q: Question): String =
    "${q.name}".lowercase() + "|${q.type}|${q.clazz}" // class in the key (D7)
