// SPDX-License-Identifier: GPL-3.0-or-later

package dnsintercept

import "testing"

// C5 (sweep 2): the filter's per-packet cost. Every tunneled packet pays BenchmarkClassifyPass;
// BenchmarkClassifyDivert is the DNS-query case the filter hands to the channel. The channel's
// socket write is deliberately NOT part of the benchmark — it is I/O, not filter cost.

var (
	sinkVerdict verdict
	sinkSegment segment
)

func benchmarkClassify(b *testing.B, pkt []byte) {
	s := testSet()
	b.ReportAllocs()
	b.ResetTimer()
	for range b.N {
		v, seg := classify(pkt, &s)
		sinkVerdict, sinkSegment = v, seg
	}
}

// BenchmarkClassifyPass: an ordinary UDP packet for another address — the common case, hand-off.
func BenchmarkClassifyPass(b *testing.B) {
	benchmarkClassify(b, ipv4Packet(clientV4, "198.51.100.9", protoUDP, udpBody(51820, 443, dnsQuery(1))))
}

// BenchmarkClassifyDivert: a DNS query for the resolver address — the case the filter diverts.
func BenchmarkClassifyDivert(b *testing.B) {
	benchmarkClassify(b, v4Query(51820, 1))
}
