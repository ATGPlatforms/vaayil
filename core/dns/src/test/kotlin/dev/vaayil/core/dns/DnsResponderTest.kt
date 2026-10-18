package dev.vaayil.core.dns

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.minidns.dnsmessage.DnsMessage
import org.minidns.dnsmessage.Question
import org.minidns.dnsname.DnsName
import org.minidns.record.A
import org.minidns.record.Record

/** Records every forwarded query verbatim and replies with [answer] on a txid-mirrored copy. */
private class RecordingUpstream(private val answer: ByteArray) : DnsUpstream {
    val seen = CopyOnWriteArrayList<ByteArray>()

    override suspend fun query(queryWire: ByteArray): ByteArray? {
        seen.add(queryWire.copyOf())
        return answer.copyOf().also { it[0] = queryWire[0]; it[1] = queryWire[1] }
    }

    override fun close() = Unit
}

private fun buildQuery(name: String, recursionDesired: Boolean, checkingDisabled: Boolean): ByteArray =
    DnsMessage.builder()
        .setId(0x1234)
        .setQuestion(Question(DnsName.from(name), Record.TYPE.A))
        .setRecursionDesired(recursionDesired)
        .setCheckingDisabled(checkingDisabled)
        .build()
        .toArray()

private fun buildAnswer(name: String, truncated: Boolean = false): ByteArray {
    val response = DnsMessage.builder()
        .setId(0x1234)
        .setQuestion(Question(DnsName.from(name), Record.TYPE.A))
        .build()
        .asBuilder()
        .setQrFlag(true)
        .setTruncated(truncated)
    response.addAnswer(
        Record(DnsName.from(name), Record.TYPE.A, Record.CLASS.IN, 60, A("192.0.2.53"), false),
    )
    return response.build().toArray()
}

/** J2 controls: DnsResponder forwards the app's query bytes untouched, RD/CD included —
    it is not a second place where Vaayil originates DNS questions. */
class DnsResponderTest {

    @Test
    fun `forwarding preserves the app recursion and checking flags`() = runTest {
        for (recursion in listOf(false, true)) {
            val name = "flags$recursion.example" // distinct questions; the responder keeps no cache
            val query = buildQuery(name, recursionDesired = recursion, checkingDisabled = true)
            val udp = RecordingUpstream(buildAnswer(name))
            val reply = DnsResponder(classify = { udp }).answer(query)
            assertNotNull(reply)
            assertArrayEquals(query, udp.seen.single())
        }
    }

    @Test
    fun `TCP retry preserves the original query flags`() = runTest {
        for (recursion in listOf(false, true)) {
            val name = "tcpflags$recursion.example" // distinct questions; the responder keeps no cache
            val query = buildQuery(name, recursionDesired = recursion, checkingDisabled = true)
            val udp = RecordingUpstream(buildAnswer(name, truncated = true))
            val tcp = RecordingUpstream(buildAnswer(name))
            val reply = DnsResponder(classify = { udp }, tcpFor = { tcp }).answer(query)
            assertNotNull(reply)
            assertArrayEquals(query, udp.seen.single())
            assertArrayEquals(query, tcp.seen.single())
        }
    }
}
