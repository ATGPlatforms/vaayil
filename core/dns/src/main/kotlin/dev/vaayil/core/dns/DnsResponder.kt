package dev.vaayil.core.dns

import java.net.InetAddress
import kotlinx.coroutines.withTimeoutOrNull
import org.minidns.dnsmessage.DnsMessage
import org.minidns.dnsmessage.Question

/**
 * The resolver's answer path, shared by every transport: the DNS channel ([FramedDnsTransport]) in production and
 * [LocalDnsServer]'s sockets in the JVM tests.
 *
 * Per query: exactly one question is forwarded to [classify]'s upstream under a 4 s budget; upstream failure
 * synthesizes SERVFAIL and zero-or-multiple questions are REFUSED, both mirroring the query transaction id. If a UDP
 * answer carries the TC bit, the same upstream is retried over TCP via [tcpFor] (the controller maps plain/DoT
 * upstreams to their TCP-capable instances); without a TCP path the truncated UDP answer is served as-is.
 * [isAllowedSource] is the D6/T-9 source rule each transport applies before asking for an answer.
 */
class DnsResponder(
    private val classify: (qname: String) -> DnsUpstream,
    private val tcpFor: (DnsUpstream) -> DnsUpstream? = { null },
    private val isAllowedSource: (InetAddress) -> Boolean = { true },
) : DnsAnswerer {
    override fun acceptsSource(source: InetAddress): Boolean = isAllowedSource(source)

    /** Wire query → wire response. SERVFAIL on upstream failure, REFUSED for malformed queries. */
    override suspend fun answer(query: ByteArray): ByteArray? {
        val parsed = runCatching { DnsMessage(query) }.getOrNull()
            ?: return errorReply(query, DnsMessage.RESPONSE_CODE.REFUSED)
        val question = parsed.questions.singleOrNull()
            ?: return errorReply(query, DnsMessage.RESPONSE_CODE.REFUSED)
        val upstream = classify(question.name.ace)
        val udpAnswer = withTimeoutOrNull(4_000) { upstream.query(query) }
            ?.takeIf { it.answers(question) } // a reply that is not an answer to THIS query is never forwarded
            ?: return errorReply(query, DnsMessage.RESPONSE_CODE.SERVER_FAIL)
        val truncated = udpAnswer.size > 2 && (udpAnswer[2].toInt() and 0x02) != 0
        if (!truncated) return udpAnswer
        // TC set: retry the same upstream over TCP; keep the UDP answer if no TCP path exists.
        return tcpFor(upstream)
            ?.let { tcp -> runCatching { withTimeoutOrNull(4_000) { tcp.query(query) } }.getOrNull() }
            ?.takeIf { it.answers(question) }
            ?: udpAnswer
    }

    private fun ByteArray.answers(question: Question): Boolean =
        runCatching { DnsMessage(this) }.getOrNull()?.answers(question) == true

    private fun errorReply(query: ByteArray, code: DnsMessage.RESPONSE_CODE): ByteArray? =
        runCatching {
            val q = DnsMessage(query)
            DnsMessage.builder()
                .setId(q.id)
                .setQuestions(q.questions)
                .setQrFlag(true)
                .setResponseCode(code)
                .build()
                .toArray()
        }.getOrNull()
}
