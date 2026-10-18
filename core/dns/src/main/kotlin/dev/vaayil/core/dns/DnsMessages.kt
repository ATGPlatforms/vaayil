package dev.vaayil.core.dns

import org.minidns.dnsmessage.DnsMessage
import org.minidns.dnsmessage.Question

/**
 * True when this message is a RESPONSE whose single question is exactly [question]: the one
 * acceptance test shared by the bootstrap (D4), the cache (D7) and the listener. Matching the
 * transaction id alone let an off-path spoofer pick answers.
 */
internal fun DnsMessage.answers(question: Question): Boolean {
    val rq = questions.singleOrNull() ?: return false
    return qr && rq.name == question.name && rq.type == question.type && rq.clazz == question.clazz
}
