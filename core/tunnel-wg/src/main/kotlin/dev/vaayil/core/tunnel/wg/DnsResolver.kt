package dev.vaayil.core.tunnel.wg

import android.util.Log
import dev.vaayil.core.dns.BootstrapLookup
import dev.vaayil.core.dns.BootstrapResolver
import dev.vaayil.core.dns.CachingUpstream
import dev.vaayil.core.dns.DatagramPipe
import dev.vaayil.core.dns.DnsAnswerer
import dev.vaayil.core.dns.DnsResponder
import dev.vaayil.core.dns.DnsResponseCache
import dev.vaayil.core.dns.DnsRuleMatcher
import dev.vaayil.core.dns.DnsUpstream
import dev.vaayil.core.dns.FramedDnsTransport
import dev.vaayil.core.dns.PlainTcpUpstream
import dev.vaayil.core.dns.PlainUdpUpstream
import dev.vaayil.core.dns.ReprobingUpstream
import dev.vaayil.core.dns.SecureDnsBootstrapException
import dev.vaayil.core.dns.SplitVia
import dev.vaayil.core.dns.buildSecureUpstream
import dev.vaayil.core.model.DnsMode
import dev.vaayil.core.model.DnsPolicy
import dev.vaayil.core.model.Profile
import dev.vaayil.core.model.RESOLVER_V4
import dev.vaayil.core.model.RESOLVER_V6
import dev.vaayil.core.model.SecureDnsType
import dev.vaayil.core.model.secureInEffect
import java.io.Closeable
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.util.IdentityHashMap
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

private const val TAG = "DnsResolver"

private const val MAX_REOPEN_ATTEMPTS = 3
private const val REOPEN_STEP_MS = 1_000L
private const val STABLE_CHANNEL_MS = 60_000L

/**
 * The DNS outcome for one tunnel start: either the in-tunnel resolver intercepts (the VPN
 * interface's DNS points at the one resolver address, which libwg-go answers over the DNS
 * channel) or the profile's plain DNS passes through untouched.
 */
data class DnsPlan(
    val intercept: Boolean = false,
    val vpnDnsServers: List<String> = emptyList(), // the one resolver address when intercepting
)

/**
 * Pure per-profile decision of whether the in-tunnel resolver must run, and at which address.
 * Secure DNS in effect intercepts; split rules intercept only when the tunnel also has plain
 * servers to forward the non-bypassed traffic to. The resolver address follows the interface
 * after WgConfigMapper's per-family rule (B1): [RESOLVER_V4] when an IPv4 address survives it,
 * else [RESOLVER_V6] when an IPv6 one does, else no interception. So the VPN DNS server never
 * opens an address family the profile does not route (T-1; AOSP Vpn.java makeLinkProperties
 * counts a DNS server like an address when it decides which families get an unreachable route).
 */
object DnsPlanner {
    fun plan(profile: Profile): DnsPlan {
        val dns = profile.dns
        val rulesUsable = dns.rules.isNotEmpty() && profile.interfaceConfig.dnsServers.isNotEmpty()
        if (!dns.secureInEffect && !rulesUsable) return DnsPlan()
        val families = WgConfigMapper.routedAddresses(profile).map(WgConfigMapper::familyOf).toSet()
        val resolver = when {
            WgConfigMapper.Family.V4 in families -> RESOLVER_V4
            WgConfigMapper.Family.V6 in families -> RESOLVER_V6
            else -> return DnsPlan() // no interface address survives the family rule
        }
        return DnsPlan(intercept = true, vpnDnsServers = listOf(resolver))
    }
}

/**
 * Owns the in-tunnel resolver: builds one [ResolverSession] per intercepting profile (the classify
 * recipe: bypass rules → protected plain upstream over the selected underlying network; secure
 * queries → DoH/DoT; plain tunnel queries → the profile's own servers riding the tunnel, each
 * channel wrapped in the response cache), keeps the session that answers ([current]) and admits answering only
 * through [answererNow], and owns the DNS channel from libwg-go ([ensureChannel]). No socket is bound: libwg-go
 * diverts every query to a resolver address over the channel, and a [FramedDnsTransport] hands it to
 * [answererNow] (B1).
 *
 * Protect semantics (ruling 2026-09-10): only the bootstrap lookup and the split-DNS bypass
 * channel escape the tunnel through VpnService.protect. Secure (DoT/DoH) and plain tunnel-path
 * sockets follow the VPN routing like any app socket, so the resolver provider sees the VPN
 * exit and the local network sees nothing. Bypass plain degrades to unprotected only as a
 * last resort (logged) when no callback is live. UDP and TCP need separate callbacks because
 * VpnService.protect has disjoint overloads (protect(DatagramSocket) vs protect(Socket)).
 *
 * S10: [underlying] selects ONE underlying network ([selectUnderlying]). Only its DNS servers are
 * asked, and every protected socket is also bound to it ([protectAndBindDatagram],
 * [protectAndBindStream]), so a query leaves on the network whose resolvers it asks. Each build
 * and each lookup selects once; every re-probe selects again.
 *
 * Session lifecycle (B1 §6.5), driven by [WireGuardEngine] under its start/stop mutex: [build]
 * the new session, [stage] it — nothing answers while the stage is pending (A1) — then [commit]
 * after a successful setState (it answers from then on, and the previous one is retired) or
 * [rollback] when the previous tunnel survived a failed one (the previous one answers again on
 * a fresh Job). [deactivate] retires everything. A retired session's upstreams are closed; a
 * quiesced one keeps them open for the rollback. [stageIfUnchanged] makes a stage conditional on no
 * [deactivate] having happened since the start captured [deactivationEpoch] (A1: a revoke during the
 * build must not be followed by a late stage).
 *
 * D1 bootstrap: a hostname secure endpoint is resolved ONCE at build — through the underlying
 * network's system resolvers over a protected socket, never the in-tunnel resolver — and the
 * address is pinned for the session. The secure channel sits in the same re-arm wrapper as
 * bypass, so a network switch re-resolves it. Bootstrap failures are loud
 * ([SecureDnsBootstrapException] → the build fails, and so does the start); there is no fallback.
 */
class DnsResolverController(
    private val scope: CoroutineScope,
    private val protect: () -> ((Socket) -> Boolean)?,
    private val protectDatagram: () -> ((DatagramSocket) -> Boolean)?,
    private val underlying: () -> UnderlyingRoute, // S10: the one selected underlying network
    private val ok: OkHttpClient,
    /** UDP port of the underlying resolvers for bootstrap resolution (53 in production;
        tests point it at a local DNS fixture). */
    private val bootstrapPort: Int = 53,
    private val secureUpstream: suspend (DnsPolicy) -> DnsUpstream = { policy ->
        val type = policy.secureType ?: error("secure upstream requested without a type")
        val endpoint = policy.secureEndpoint
            ?: throw SecureDnsBootstrapException("secure DNS endpoint must not be blank")
        buildSecureUpstream(
            type = type,
            endpoint = endpoint,
            ok = ok,
            // D1 bootstrap: resolve the hostname through the UNDERLYING network's system
            // resolvers (fresh probe, protected UDP socket) — never through the in-tunnel
            // resolver, which would loop the hostname query back to the upstream being
            // established. Empty/throwing fails loud; no public-resolver fallback.
            bootstrapResolve = { hostname ->
                val route = underlying()
                if (route.dnsServers.isEmpty()) {
                    throw SecureDnsBootstrapException(
                        "no underlying DNS servers available to bootstrap \"$hostname\"",
                    )
                }
                val protectFn = protectDatagram() ?: throw SecureDnsBootstrapException(
                    "bootstrap resolution of \"$hostname\" requires a live VpnService protect callback",
                )
                BootstrapResolver(route.dnsServers, protectAndBindDatagram(protectFn, route), bootstrapPort).resolve(hostname)
            },
        )
    },
    /** [bypass] non-null: a split-DNS bypass leg, protected AND bound to that route (S10). Null: rides the tunnel. */
    private val plainUpstream: (servers: List<String>, bypass: UnderlyingRoute?) -> DnsUpstream = { servers, bypass ->
        val parsed = servers.map { InetAddress.getByName(it) }
        val protectFn = if (bypass != null) protectDatagram() else null
        if (bypass != null && protectFn != null) {
            PlainUdpUpstream(parsed, protect = protectAndBindDatagram(protectFn, bypass))
        } else {
            if (bypass != null) {
                Log.w(TAG, "no VpnService protect callback; bypass dns degrades to unprotected (rides the tunnel)")
            }
            PlainUdpUpstream(parsed)
        }
    },
    /** Tests inject a canned TCP leg; production (null) uses [plainTcpUpstream]. */
    private val tcpUpstream: ((servers: List<String>, bypass: UnderlyingRoute?) -> DnsUpstream)? = null,
    /** C3: clock for the channel's stable-uptime reset; tests may inject a fake. */
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * The session that is current — the one that answers once nothing is staged; the staged candidate while a stage
     * is pending. Null when no tunnel intercepts. [answererNow] is what admits answering.
     */
    @Volatile
    var current: ResolverSession? = null
        private set

    /** The previous session, kept aside between [stage] and [commit] or [rollback]. */
    private var standby: ResolverSession? = null
    private var staged = false

    /** A1: moved by every [deactivate]; a start that captured an older one cannot [stageIfUnchanged]. */
    private var epoch = 0L

    /**
     * The session the DNS channel may answer with right now: [current] while no stage is pending, null otherwise.
     * A staged session must not answer at all (A1), because a switch or a restart runs while no tunnel may be up.
     */
    @Synchronized
    internal fun answererNow(): ResolverSession? = if (staged) null else current

    /** The live DNS channel; null before the first [ensureChannel] and after the channel died. */
    private var channel: DatagramPipe? = null

    /** C3: the opener [ensureChannel] last used, kept so a dead channel can be reopened in place. */
    private var channelOpener: ((List<InetAddress>) -> DatagramPipe)? = null

    /** C3: reopen attempts since the last start or 60 s-stable channel; MAX_REOPEN_ATTEMPTS stops trying. */
    private var reopenAttempts = 0
    private var channelOpenedAtMs = 0L

    /** C3: the engine marks the live session DnsNotAnswering when the channel gives up reopening. */
    @Volatile var onDnsNotAnswering: (() -> Unit)? = null

    /**
     * Builds the resolver session for [profile]: its upstreams, the D1 bootstrap of a hostname secure endpoint, the
     * classify recipe and the D6/T-9 source rule. Binds nothing and changes no state: the engine [stage]s the
     * result. On failure every upstream built so far is closed and the exception propagates.
     */
    suspend fun build(profile: Profile): ResolverSession {
        val built = mutableListOf<DnsUpstream>()
        try {
            val dns = profile.dns
            val matcher = DnsRuleMatcher(dns.rules)
            val cache = DnsResponseCache()

            // S10: one selection per build; the bypass legs ask only its servers and bind to it.
            val route = underlying()
            val underlyingServers = route.dnsServers.mapNotNull { it.hostAddress }
            // Primary-constructor defaults cannot reference [plainTcpUpstream], so the TCP
            // factory is resolved here: null = production, use the private factory.
            val buildTcp = tcpUpstream ?: { servers: List<String>, bypass: UnderlyingRoute? ->
                plainTcpUpstream(servers, bypass)
            }
            // D3: the snapshot is probed once here; the wrapper re-probes and swaps the plain
            // layer on first failure so a Wi-Fi→cellular switch mid-tunnel stops serving
            // SERVFAIL from dead resolvers. The re-probe selects the network again (S10). The
            // cache survives the swap (same instance).
            val bypassUdp = ReprobingUpstream(
                CachingUpstream(plainUpstream(underlyingServers, route), cache),
            ) {
                val fresh = underlying()
                val servers = fresh.dnsServers.mapNotNull { it.hostAddress }
                if (servers.isEmpty()) null else CachingUpstream(plainUpstream(servers, fresh), cache)
            }
            // D11: the TCP retry leg re-arms like the UDP leg — a Wi-Fi→cellular switch must
            // not leave truncated answers pointing at dead resolvers until a tunnel restart.
            val bypassTcp = ReprobingUpstream(buildTcp(underlyingServers, route)) {
                val fresh = underlying()
                val servers = fresh.dnsServers.mapNotNull { it.hostAddress }
                if (servers.isEmpty()) null else buildTcp(servers, fresh)
            }
            built += bypassUdp
            built += bypassTcp

            val secureRaw: DnsUpstream?
            val secureUdp: DnsUpstream?
            if (dns.mode == DnsMode.SECURE) {
                // D1: the secure channel lives in the same re-arm wrapper as the bypass
                // layer — on first failure (e.g. a network switch killed the pinned dial)
                // the rebuild re-runs buildSecureUpstream, which re-resolves the hostname
                // endpoint through a FRESH underlying-DNS probe and re-pins the session.
                secureRaw = ReprobingUpstream(secureUpstream(dns)) { secureUpstream(dns) }
                secureUdp = CachingUpstream(secureRaw, cache)
                built += secureUdp
            } else {
                secureRaw = null
                secureUdp = null
            }

            val tunnelUdp: DnsUpstream?
            val tunnelTcp: DnsUpstream?
            if (dns.mode == DnsMode.TUNNEL) {
                val servers = profile.interfaceConfig.dnsServers
                tunnelUdp = CachingUpstream(plainUpstream(servers, null), cache)
                tunnelTcp = plainTcpUpstream(servers, bypass = null)
                built += tunnelUdp
                built += tunnelTcp
            } else {
                tunnelUdp = null
                tunnelTcp = null
            }

            // Truncated UDP answers retry over the matching channel's TCP-capable upstream:
            // plain → PlainTcpUpstream, DoT → the DoT session itself (TLS is TCP), DoH → none.
            val routes = IdentityHashMap<DnsUpstream, DnsUpstream?>()
            routes[bypassUdp] = bypassTcp
            secureUdp?.let { routes[it] = if (dns.secureType == SecureDnsType.DOT) secureRaw else null }
            tunnelUdp?.let { routes[it] = tunnelTcp }

            val classify: (String) -> DnsUpstream = { qname ->
                when (matcher.classify(qname)) {
                    SplitVia.BYPASS -> bypassUdp
                    SplitVia.TUNNEL -> if (dns.mode == DnsMode.SECURE) secureUdp!! else tunnelUdp!!
                }
            }
            // D6, T-9: only the VPN interface's own addresses plus loopback may query the
            // resolver — a query from anywhere else is dropped before any work. libwg-go
            // reports each query's source address in its frame.
            val ownAddresses = buildSet {
                add(InetAddress.getLoopbackAddress())
                profile.interfaceConfig.addresses.forEach { cidr ->
                    runCatching { add(InetAddress.getByName(cidr.substringBefore('/'))) }
                }
            }
            return ResolverSession(
                profileId = profile.id,
                responder = DnsResponder(
                    classify = classify,
                    tcpFor = { udp -> routes[udp] },
                    isAllowedSource = { source -> source.isLoopbackAddress || source in ownAddresses },
                ),
                owned = built.toList(),
            )
        } catch (e: Exception) {
            built.forEach { runCatching { it.close() } } // never leak half-built channels
            throw e
        }
    }

    /**
     * Makes [next] the session that will answer (null: none, for a plain profile) and keeps the previous one aside.
     * While the stage is pending nothing answers at all (A1): every session involved has its answers cancelled and
     * [answererNow] admits none of them, so a switch or restart sends and dials nothing while no tunnel may be up.
     * [commit] or [rollback] ends the stage. A stage that was neither committed nor rolled back is committed first.
     */
    @Synchronized
    fun stage(next: ResolverSession?) {
        if (staged) commit()
        listOfNotNull(current, standby, next).distinct().forEach { it.quiesce() }
        standby = current
        current = next
        staged = true
    }

    /**
     * A1: [stage] behind the ticket a start captured from [deactivationEpoch] before its suspending work. A
     * revoke that deactivated the resolver while the session was still building makes that ticket stale: false
     * comes back and nothing is staged, so the start goes on without a resolver instead of resurrecting one the
     * revoke retired. The check and the stage share one critical section, so nothing slips in between.
     */
    @Synchronized
    internal fun stageIfUnchanged(next: ResolverSession?, expectedEpoch: Long): Boolean {
        if (epoch != expectedEpoch) return false
        stage(next)
        return true
    }

    /** A1: the ticket [stageIfUnchanged] checks; [deactivate] moves it. Captured once per start. */
    @Synchronized
    internal fun deactivationEpoch(): Long = epoch

    /**
     * The start succeeded: [current] answers from now on and the session kept aside is retired. A no-op when
     * nothing is staged.
     */
    @Synchronized
    fun commit() {
        if (!staged) return
        val previous = standby
        standby = null
        staged = false
        previous?.close()
        current?.activate(scope)
    }

    /**
     * The previous tunnel survived a failed start: its session answers again, on a fresh Job, and the staged one is
     * retired. A no-op when nothing is staged.
     */
    @Synchronized
    fun rollback() {
        if (!staged) return
        val rejected = current
        current = standby
        standby = null
        staged = false
        rejected?.close()
        current?.activate(scope)
    }

    /**
     * No tunnel is left: cancels every session's answers and retires every session, and moves the deactivation
     * ticket so no start that captured an older one can stage a session afterwards (A1). Idempotent.
     */
    @Synchronized
    fun deactivate() {
        val retired = listOfNotNull(current, standby)
        retired.forEach { it.quiesce() } // the answers stop first, so no query starts from a retiring session
        retired.forEach { it.close() }
        current = null
        standby = null
        staged = false
        epoch++ // A1: the ticket a start captured is stale from here on
    }

    /**
     * Opens the DNS channel unless one is live, through [open] (production: libwg-go's DnsIntercept.open). libwg-go
     * refuses to start a tunnel until a channel was opened, so the engine calls this on every start. The channel
     * always carries both resolver addresses, whichever one the profile uses, so GoBackend routes whichever config it
     * applies: its own, or the previous one it restores after a failed UP (GoBackend.setState), because
     * setStateInternal adds a host route for every DNS server that is a resolver address of the channel opened last
     * (DnsIntercept.resolverRoutePrefix). Queries arriving on the channel go to the session that [answererNow]
     * admits at that moment — none while a stage is pending. The reader thread releases the channel when it ends
     * (end-of-file or a pipe error), so the next start opens a fresh one. A channel that dies while a session is
     * current is reopened in place (C3), up to three attempts with a growing backoff; after that the session is
     * marked DNS-not-answering until the next start.
     * Throws what [open] throws.
     */
    @Synchronized
    fun ensureChannel(open: (List<InetAddress>) -> DatagramPipe) {
        channelOpener = open
        reopenAttempts = 0 // every start resets the burst (C3)
        if (channel != null) return
        openChannelLocked()
    }

    /** Caller holds the controller monitor. */
    private fun openChannelLocked() {
        val opener = channelOpener ?: return
        val pipe = opener(listOf(RESOLVER_V4, RESOLVER_V6).map { InetAddress.getByName(it) })
        channel = pipe
        channelOpenedAtMs = nowMs()
        thread(isDaemon = true, name = "vaayil-dns-channel") { readChannel(pipe) }
    }

    private fun readChannel(pipe: DatagramPipe) {
        try {
            FramedDnsTransport(pipe, scope, { answererNow() }).run()
        } catch (e: Exception) {
            Log.w(TAG, "DNS channel reader failed (${e.javaClass.simpleName})")
        } finally {
            val scheduleReopen = synchronized(this@DnsResolverController) {
                if (channel === pipe) {
                    channel = null
                    // A channel up for >= 60 s proved stable: a later death starts a fresh burst (C3).
                    if (nowMs() - channelOpenedAtMs >= STABLE_CHANNEL_MS) reopenAttempts = 0
                    current != null
                } else {
                    false // a newer channel replaced this pipe: never reopened (C3)
                }
            }
            if (scheduleReopen) {
                Log.i(TAG, "DNS channel closed; reopening it in place")
                scope.launch { reopenLoop() }
            } else {
                Log.i(TAG, "DNS channel closed; the next start opens a new one")
            }
            runCatching { pipe.close() }
        }
    }

    /**
     * C3: reopens the channel after the live one died. The first reopen runs at once, the second after
     * 1 s and the third after 2 s; a reopen that throws or a channel that dies again counts as one
     * attempt. Stops when a newer channel is live, no session is current, or three attempts failed —
     * then the session is marked DNS-not-answering until the next start. libwg-go's Open replaces the
     * live channel and closes the old Go end, so the tunnel's resolver routes never change.
     */
    private suspend fun reopenLoop() {
        while (true) {
            val pre = synchronized(this@DnsResolverController) {
                when {
                    channel != null -> ReopenDecision.SUPERSEDED
                    current == null -> ReopenDecision.QUIET
                    reopenAttempts >= MAX_REOPEN_ATTEMPTS -> ReopenDecision.GAVE_UP
                    else -> ReopenDecision.TRY
                }
            }
            when (pre) {
                ReopenDecision.SUPERSEDED, ReopenDecision.QUIET -> return
                ReopenDecision.GAVE_UP -> {
                    onDnsNotAnswering?.invoke()
                    return
                }
                ReopenDecision.TRY -> Unit
            }
            delay(REOPEN_STEP_MS * reopenAttempts)
            val opened = synchronized(this@DnsResolverController) {
                if (channel != null || current == null) return@synchronized true // someone else won; stand down
                reopenAttempts++
                try {
                    openChannelLocked()
                    true
                } catch (e: Exception) {
                    Log.w(TAG, "DNS channel reopen failed (${e.javaClass.simpleName})")
                    false
                }
            }
            if (opened) return // the new channel's own reader continues the story
            // a failed attempt loops to the pre-checks, which give up once three have failed
        }
    }

    private enum class ReopenDecision { TRY, SUPERSEDED, QUIET, GAVE_UP }

    /**
     * F33: why [hostname] does not resolve on the underlying network, or null when it does. Same path as the
     * D1 bootstrap: the selected underlying network's system resolvers, over sockets protected out of the tunnel and bound to it,
     * never the tunnel itself. [WireGuardEngine] asks before a switch touches the live tunnel, which may be
     * the broken one the user is leaving. A family that runs out of time adds nothing (K1, inside
     * [BootstrapResolver]), so a host is refused only when no family resolves it. Never throws except for a
     * real cancellation, and the reasons carry no config text.
     */
    internal suspend fun underlyingLookupProblem(hostname: String): String? = try {
        val route = underlying()
        val protectFn = protectDatagram()
        when {
            route.dnsServers.isEmpty() -> "no underlying DNS servers"
            protectFn == null -> "no VpnService protect callback"
            else -> when (
                BootstrapResolver(route.dnsServers, protectAndBindDatagram(protectFn, route), bootstrapPort)
                    .resolveOutcome(hostname)
            ) {
                is BootstrapLookup.Resolved -> null
                BootstrapLookup.NoResponse -> "no answer from the underlying resolvers"
                BootstrapLookup.NameUnknown -> "the underlying resolvers say the name does not exist"
                BootstrapLookup.EmptyAnswer -> "no address in the answer"
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        "lookup failed (${e.javaClass.simpleName})"
    }

    /** TCP counterpart for the truncated-UDP retry; mirrors the UDP factory's protect-and-bind semantics. */
    private fun plainTcpUpstream(servers: List<String>, bypass: UnderlyingRoute?): DnsUpstream {
        val parsed = servers.map { InetAddress.getByName(it) }
        val protectFn = if (bypass != null) protect() else null
        return if (bypass != null && protectFn != null) {
            PlainTcpUpstream(parsed, protect = protectAndBindStream(protectFn, bypass))
        } else {
            if (bypass != null) {
                Log.w(TAG, "no VpnService protect callback; bypass dns (tcp) degrades to unprotected")
            }
            PlainTcpUpstream(parsed)
        }
    }
}

/**
 * One profile's resolver: answers queries ([DnsAnswerer]) and owns the upstreams it built, which [close] releases.
 * Built by [DnsResolverController.build]; [profileId] names the profile it was built for.
 *
 * A1: every answer runs under [answerJob]. It starts cancelled — a built session answers nothing until the
 * controller commits or rolls back to it ([activate]) — and [quiesce] cancels it while the owned upstreams stay
 * open, so a rollback can answer again. A session answers again only on a fresh Job: a cancelled one is never
 * revived, and a closed session activates no more.
 */
class ResolverSession internal constructor(
    val profileId: String,
    private val responder: DnsResponder,
    private val owned: List<DnsUpstream>,
) : DnsAnswerer by responder, Closeable {

    /** The session's answer epoch: cancelled, quiesced or closed with the session; [activate] replaces it. */
    @Volatile
    private var job: Job = SupervisorJob().apply { cancel() }

    @Volatile
    private var closed = false

    override val answerJob: Job get() = job

    /**
     * The controller committed or rolled back to this session: answers launched from now on run under a fresh Job,
     * a child of [parent]'s, so cancelling the controller's scope stops them too. A closed session stays closed.
     */
    @Synchronized
    internal fun activate(parent: CoroutineScope) {
        if (closed) return
        job = SupervisorJob(parent.coroutineContext[Job])
    }

    /** Cancels the answers waiting to start and the ones in flight; the owned upstreams stay open (a rollback). */
    @Synchronized
    internal fun quiesce() {
        job.cancel()
    }

    /** Permanently closed: cancels the answers first, then closes every owned upstream. Idempotent. */
    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        job.cancel()
        owned.forEach { runCatching { it.close() } }
    }
}
