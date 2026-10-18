package dev.vaayil.core.dns

import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import okhttp3.OkHttpClient

/** A loopback port free in BOTH families (fake servers bind UDP or TCP per test).
    Fixed ports collided across concurrent Gradle test runs (ledger D5). */
internal fun freeLoopbackPort(): Int {
    while (true) {
        val probe = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port = probe.localPort
        probe.close()
        val udp = try {
            DatagramSocket(port, InetAddress.getByName("127.0.0.1"))
        } catch (_: java.net.SocketException) {
            continue
        }
        udp.close()
        return port
    }
}

/** Locally-trusted TLS for MockWebServer and fake DoT servers (okhttp-tls): SAN [hostname], trusted by the paired client. */
internal fun tlsHandshake(hostname: String): okhttp3.tls.HandshakeCertificates {
    val held = okhttp3.tls.HeldCertificate.Builder().addSubjectAlternativeName(hostname).build()
    return okhttp3.tls.HandshakeCertificates.Builder().heldCertificate(held).addTrustedCertificate(held.certificate).build()
}

internal fun tlsClient(handshake: okhttp3.tls.HandshakeCertificates): OkHttpClient =
    OkHttpClient.Builder().sslSocketFactory(handshake.sslSocketFactory(), handshake.trustManager).build()
