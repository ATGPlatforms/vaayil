package dev.vaayil.core.dns

import dev.vaayil.core.model.SecureDnsType
import dev.vaayil.core.model.dohEndpointProblem
import dev.vaayil.core.model.isIpLiteral
import dev.vaayil.core.model.parseDotEndpoint
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URI
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.minidns.dnsmessage.DnsMessage
import org.minidns.dnsmessage.Question
import org.minidns.dnsname.DnsName
import org.minidns.record.A
import org.minidns.record.AAAA
import org.minidns.record.Record

/**
 * D1 bootstrap failure: a hostname secure-DNS endpoint could not be resolved through the
 * UNDERLYING network's system resolvers before the in-tunnel resolver was established.
 * Surfaces from resolver activation so tunnel start fails loud — falling back to resolving
 * through the active in-tunnel resolver is the bootstrap loop this exception exists to break.
 */
class SecureDnsBootstrapException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** F33: why a bootstrap lookup returned what it did. */
sealed interface BootstrapLookup {
    data class Resolved(val addresses: List<String>) : BootstrapLookup
    /** No resolver answered within the budget (silent). */
    data object NoResponse : BootstrapLookup
    /** A resolver answered NXDOMAIN. */
    data object NameUnknown : BootstrapLookup
    /** A response arrived but carried no usable address records. */
    data object EmptyAnswer : BootstrapLookup
}

/**
 * The D1 bootstrap resolver: plain UDP DNS (A + AAAA in parallel) against [servers] — the
 * underlying network's system resolvers, reached over sockets protected by [protect], i.e.
 * WITHOUT the tunnel. Returns the union of the families that answered within their budgets
 * (A first). A family that times out adds nothing (K1), so the result is empty only when no
 * family answers usable records. No caching and no fallback here: the caller resolves ONCE at
 * resolver establishment, caches the mapping for the resolver session, and re-resolves via the
 * re-arm path on network switch (D1 ruling — never the public-resolver kind of fallback).
 */
class BootstrapResolver(
    private val servers: List<InetAddress>,
    private val protect: (DatagramSocket) -> Boolean,
    private val port: Int = 53,
    private val timeoutMs: Int = 2_000,
) {
    suspend fun resolve(hostname: String): List<String> =
        (resolveOutcome(hostname) as? BootstrapLookup.Resolved)?.addresses ?: emptyList()

    /** F33: [resolve] with its cause, so callers can name why a host did not resolve. */
    suspend fun resolveOutcome(hostname: String): BootstrapLookup {
        if (servers.isEmpty()) return BootstrapLookup.NoResponse
        return coroutineScope {
            val a = async { ask(hostname, Record.TYPE.A) }
            val aaaa = async { ask(hostname, Record.TYPE.AAAA) }
            merge(a.await(), aaaa.await())
        }
    }

    /** The merge rule: any family resolving wins, else NameUnknown beats EmptyAnswer beats NoResponse. */
    private fun merge(a: BootstrapLookup, aaaa: BootstrapLookup): BootstrapLookup {
        val resolved = listOf(a, aaaa).filterIsInstance<BootstrapLookup.Resolved>()
        if (resolved.isNotEmpty()) {
            return BootstrapLookup.Resolved(resolved.flatMap { it.addresses }.distinct())
        }
        val causes = listOf(a, aaaa)
        return when {
            BootstrapLookup.NameUnknown in causes -> BootstrapLookup.NameUnknown
            BootstrapLookup.EmptyAnswer in causes -> BootstrapLookup.EmptyAnswer
            else -> BootstrapLookup.NoResponse
        }
    }

    private suspend fun ask(hostname: String, type: Record.TYPE): BootstrapLookup =
        withContext(Dispatchers.IO) {
            val question = Question(DnsName.from(hostname), type)
            val queryMessage = DnsMessage.builder()
                .setId(random.nextInt(0x10000)) // per-query random txid: off-path guessing needs port AND id (D4)
                .setQuestion(question)
                // J2: the underlying resolver is recursive; an RD=0 question gets REFUSED.
                .setRecursionDesired(true)
                .build()
            val query = queryMessage.toArray()
            // K1: a family that runs out of budget yields NoResponse, never an exception that would
            // discard the other family's answer. The deadline bounds the UDP receives themselves —
            // a coroutine timeout cannot interrupt a blocking receive (sweep-2 minor).
            val deadline = System.nanoTime() + timeoutMs * 1_000_000L
            // One transparent retry: a single lost datagram on a cold radio must not abort the whole connect.
            val wire = PlainUdpUpstream(servers, port, protect).query(query, deadline)
                ?: PlainUdpUpstream(servers, port, protect).query(query, deadline)
                ?: return@withContext BootstrapLookup.NoResponse
            val msg = runCatching { DnsMessage(wire) }.getOrNull()
                ?: return@withContext BootstrapLookup.NoResponse
            // D4: a reply counts only if it is a RESPONSE answering OUR question.
            if (!msg.answers(question)) return@withContext BootstrapLookup.NoResponse
            if (msg.responseCode == DnsMessage.RESPONSE_CODE.NX_DOMAIN) {
                return@withContext BootstrapLookup.NameUnknown
            }
            val addresses = when (type) {
                Record.TYPE.A -> msg.filterAnswerSectionBy(A::class.java)
                    .mapNotNull { it.payloadData.inetAddress.hostAddress }
                Record.TYPE.AAAA -> msg.filterAnswerSectionBy(AAAA::class.java)
                    .mapNotNull { it.payloadData.inetAddress.hostAddress }
                else -> emptyList()
            }
            if (addresses.isEmpty()) BootstrapLookup.EmptyAnswer
            else BootstrapLookup.Resolved(addresses)
        }

    private companion object {
        private val random = SecureRandom()
    }
}

/**
 * D1 seam: builds the secure (DoH/DoT) upstream for [endpoint]. A HOSTNAME endpoint is
 * resolved ONCE here — via [bootstrapResolve] (the underlying network's system resolver over
 * a protected socket, supplied by DnsResolverController) — and the resolved address is pinned
 * onto the upstream for the session: DoT dials the IP while [DotUpstream] keeps the hostname
 * as TLS peer name (SNI + RFC 8310 verification); DoH dials via a pinned [okhttp3.Dns] while
 * the URL authority/Host header stay the hostname. IP literals pass through untouched;
 * both DoT and DoH sockets follow VPN routing — only the bootstrap lookup is protected.
 * Empty or throwing [bootstrapResolve] results FAIL LOUD with [SecureDnsBootstrapException] —
 * never resolve through the active in-tunnel resolver (that is the loop).
 */
suspend fun buildSecureUpstream(
    type: SecureDnsType,
    endpoint: String,
    ok: OkHttpClient,
    bootstrapResolve: suspend (String) -> List<String>,
): DnsUpstream {
    if (endpoint.isBlank()) throw SecureDnsBootstrapException("secure DNS endpoint must not be blank")
    return when (type) {
        SecureDnsType.DOT -> {
            val (host, port) = parseDotEndpoint(endpoint)
                ?: throw SecureDnsBootstrapException("invalid DoT endpoint \"$endpoint\" (expected host or host:port)")
            if (isIpLiteral(host)) {
                DotUpstream(host, port)
            } else {
                DotUpstream(host, port, dialAddresses = bootstrapPin(host, bootstrapResolve)) // full list: a dead first address is not fatal (D4)
            }
        }
        SecureDnsType.DOH -> {
            dohEndpointProblem(endpoint)?.let {
                throw SecureDnsBootstrapException("invalid DoH endpoint \"$endpoint\": $it (https required)")
            }
            val uri = URI(endpoint)
            // URI.host keeps an IPv6 literal's brackets; strip them so the strict literal test
            // sees the address (the same normalisation dohEndpointProblem applies).
            val host = uri.host!!.removePrefix("[").removeSuffix("]")
            if (isIpLiteral(host)) {
                DohUpstream(ok, endpoint, endpointAddresses = listOf(host))
            } else {
                val ips = bootstrapPin(host, bootstrapResolve)
                DohUpstream(
                    ok,
                    endpoint,
                    pinnedDns = object : okhttp3.Dns {
                        override fun lookup(hostname: String): List<InetAddress> =
                            ips.map(InetAddress::getByName) // literal strings only: no DNS here
                    },
                    endpointAddresses = ips,
                )
            }
        }
    }
}

/** Runs [bootstrapResolve] and converts empty/failure into the loud D1 bootstrap error. */
private suspend fun bootstrapPin(
    host: String,
    bootstrapResolve: suspend (String) -> List<String>,
): List<String> =
    try {
        bootstrapResolve(host).ifEmpty {
            throw SecureDnsBootstrapException(
                "bootstrap resolution of \"$host\" returned no addresses; refusing to " +
                    "resolve through the in-tunnel resolver (that is the D1 loop)",
            )
        }
    } catch (e: SecureDnsBootstrapException) {
        throw e
    } catch (e: Exception) {
        throw SecureDnsBootstrapException("bootstrap resolution of \"$host\" failed", e)
    }
