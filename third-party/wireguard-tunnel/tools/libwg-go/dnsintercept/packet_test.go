// SPDX-License-Identifier: GPL-3.0-or-later

package dnsintercept

import (
	"bytes"
	"encoding/binary"
	"net/netip"
	"testing"
)

func testSet() resolverSet {
	return newResolverSet([]netip.Addr{netip.MustParseAddr(resolverV4), netip.MustParseAddr(resolverV6)})
}

func TestClassifyPassesPacketsForOtherAddresses(t *testing.T) {
	set := testSet()
	for name, pkt := range map[string][]byte{
		"ipv4 dns to another server": ipv4Packet(clientV4, "1.1.1.1", protoUDP, udpBody(5000, 53, dnsQuery(1))),
		"ipv6 dns to another server": ipv6Packet(clientV6, "2606:4700::1111", protoUDP, udpBody(5000, 53, dnsQuery(1))),
		"ipv4 tcp elsewhere":         ipv4Packet(clientV4, "192.0.2.1", protoTCP, tcpBody(4000, 443, 1, 0, tcpFlagSYN, nil)),
		"ipv4 too short to address":  ipv4Packet(clientV4, resolverV4, protoUDP, nil)[:19],
		"ipv6 too short to address":  ipv6Packet(clientV6, resolverV6, protoUDP, nil)[:39],
		"not ip":                     {0x00, 1, 2, 3},
		"empty":                      {},
	} {
		if v, _ := classify(pkt, &set); v != pass {
			t.Errorf("%s: verdict %d, want pass", name, v)
		}
	}
}

func TestClassifyDivertsDnsQueriesToAResolver(t *testing.T) {
	set := testSet()
	for name, tc := range map[string]struct {
		pkt          []byte
		client, peer string
	}{
		"ipv4": {v4Query(40000, 7), clientV4, resolverV4},
		"ipv6": {v6Query(40000, 7), clientV6, resolverV6},
	} {
		v, seg := classify(tc.pkt, &set)
		if v != divert {
			t.Fatalf("%s: verdict %d, want divert", name, v)
		}
		if seg.src != netip.MustParseAddr(tc.client) || seg.dst != netip.MustParseAddr(tc.peer) {
			t.Errorf("%s: addresses %v -> %v", name, seg.src, seg.dst)
		}
		if seg.srcPort != 40000 || seg.dstPort != 53 {
			t.Errorf("%s: ports %d -> %d", name, seg.srcPort, seg.dstPort)
		}
		if !bytes.Equal(seg.payload, dnsQuery(7)) {
			t.Errorf("%s: payload %x", name, seg.payload)
		}
	}
}

func TestClassifyFindsTheUdpHeaderPastIpv4Options(t *testing.T) {
	set := testSet()
	plain := v4Query(40000, 9)
	withOptions := make([]byte, 0, len(plain)+4)
	withOptions = append(withOptions, plain[:20]...)
	withOptions = append(withOptions, 1, 1, 1, 0) // four bytes of NOP options and an end-of-list
	withOptions = append(withOptions, plain[20:]...)
	withOptions[0] = 0x46
	binary.BigEndian.PutUint16(withOptions[2:], uint16(len(withOptions)))
	v, seg := classify(withOptions, &set)
	if v != divert || !bytes.Equal(seg.payload, dnsQuery(9)) || seg.srcPort != 40000 {
		t.Fatalf("verdict %d, payload %x, port %d", v, seg.payload, seg.srcPort)
	}
}

func TestClassifyDropsEverythingElseToAResolver(t *testing.T) {
	set := testSet()
	mutate := func(p []byte, f func([]byte)) []byte { f(p); return p }
	cases := map[string][]byte{
		"udp to another port":       ipv4Packet(clientV4, resolverV4, protoUDP, udpBody(5000, 853, dnsQuery(1))),
		"dns payload under 12":      ipv4Packet(clientV4, resolverV4, protoUDP, udpBody(5000, 53, make([]byte, 11))),
		"icmp":                      ipv4Packet(clientV4, resolverV4, 1, []byte{8, 0, 0, 0, 0, 1, 0, 1}),
		"tcp with rst":              ipv4Packet(clientV4, resolverV4, protoTCP, tcpBody(4000, 53, 1, 0, tcpFlagRST, nil)),
		"tcp rst and ack":           ipv6Packet(clientV6, resolverV6, protoTCP, tcpBody(4000, 53, 1, 9, tcpFlagRST|tcpFlagACK, nil)),
		"more fragments":            mutate(v4Query(5000, 1), func(p []byte) { p[6] = 0x20 }),
		"fragment offset":           mutate(v4Query(5000, 1), func(p []byte) { p[7] = 0x01 }),
		"ihl under five":            mutate(v4Query(5000, 1), func(p []byte) { p[0] = 0x44 }),
		"ihl past the packet":       mutate(ipv4Packet(clientV4, resolverV4, protoUDP, nil), func(p []byte) { p[0] = 0x4f }),
		"total length past the end": mutate(v4Query(5000, 1), func(p []byte) { binary.BigEndian.PutUint16(p[2:], uint16(len(p)+1)) }),
		"total length under ihl":    mutate(v4Query(5000, 1), func(p []byte) { binary.BigEndian.PutUint16(p[2:], 19) }),
		"udp length past the body":  mutate(v4Query(5000, 1), func(p []byte) { binary.BigEndian.PutUint16(p[24:], uint16(len(p)-20+1)) }),
		"udp length under eight":    mutate(v4Query(5000, 1), func(p []byte) { binary.BigEndian.PutUint16(p[24:], 7) }),
		"udp header cut short":      ipv4Packet(clientV4, resolverV4, protoUDP, []byte{0, 53, 0, 53}),
		"tcp header cut short":      ipv4Packet(clientV4, resolverV4, protoTCP, make([]byte, 19)),
		"tcp data offset under 5":   mutate(ipv4Packet(clientV4, resolverV4, protoTCP, tcpBody(4000, 53, 1, 0, tcpFlagSYN, nil)), func(p []byte) { p[32] = 4 << 4 }),
		"tcp data offset past end":  mutate(ipv4Packet(clientV4, resolverV4, protoTCP, tcpBody(4000, 53, 1, 0, tcpFlagSYN, nil)), func(p []byte) { p[32] = 6 << 4 }),
		"ipv6 hop-by-hop header":    ipv6Packet(clientV6, resolverV6, 0, append([]byte{17, 0, 1, 4, 0, 0, 0, 0}, udpBody(5000, 53, dnsQuery(1))...)),
		"ipv6 routing header":       ipv6Packet(clientV6, resolverV6, 43, make([]byte, 40)),
		"ipv6 fragment header":      ipv6Packet(clientV6, resolverV6, 44, append([]byte{17, 0, 0, 1, 0, 0, 0, 1}, udpBody(5000, 53, dnsQuery(1))...)),
		"ipv6 destination options":  ipv6Packet(clientV6, resolverV6, 60, make([]byte, 40)),
		"icmpv6":                    ipv6Packet(clientV6, resolverV6, 58, []byte{128, 0, 0, 0, 0, 1, 0, 1}),
		"ipv6 payload past the end": mutate(v6Query(5000, 1), func(p []byte) { binary.BigEndian.PutUint16(p[4:], uint16(len(p)-40+1)) }),
		"ipv6 udp to another port":  ipv6Packet(clientV6, resolverV6, protoUDP, udpBody(5000, 5353, dnsQuery(1))),
	}
	for name, pkt := range cases {
		if v, _ := classify(pkt, &set); v != drop {
			t.Errorf("%s: verdict %d, want drop", name, v)
		}
	}
}

func TestResetFollowsRfc9293(t *testing.T) {
	set := testSet()
	cases := []struct {
		name     string
		seq, ack uint32
		flags    byte
		payload  int
		wantSeq  uint32
		wantAck  uint32
		wantCtl  byte
	}{
		{"syn", 1000, 0, tcpFlagSYN, 0, 0, 1001, tcpFlagRST | tcpFlagACK},
		{"syn with payload", 1000, 0, tcpFlagSYN, 10, 0, 1011, tcpFlagRST | tcpFlagACK},
		{"fin", 7, 0, tcpFlagFIN, 3, 0, 11, tcpFlagRST | tcpFlagACK},
		{"syn at the wrap", 0xffffffff, 0, tcpFlagSYN, 0, 0, 0, tcpFlagRST | tcpFlagACK},
		{"ack", 1, 5000, tcpFlagACK, 0, 5000, 0, tcpFlagRST},
		{"ack with payload", 1, 6000, tcpFlagACK, 20, 6000, 0, tcpFlagRST},
		{"fin and ack", 1, 7000, tcpFlagFIN | tcpFlagACK, 0, 7000, 0, tcpFlagRST},
	}
	for _, family := range []string{"ipv4", "ipv6"} {
		for _, tc := range cases {
			body := tcpBody(41000, 853, tc.seq, tc.ack, tc.flags, make([]byte, tc.payload))
			pkt := ipv4Packet(clientV4, resolverV4, protoTCP, body)
			if family == "ipv6" {
				pkt = ipv6Packet(clientV6, resolverV6, protoTCP, body)
			}
			v, seg := classify(pkt, &set)
			if v != reset {
				t.Fatalf("%s %s: verdict %d, want reset", family, tc.name, v)
			}
			rst := buildReset(seg)[headroom:]
			verifyChecksums(t, rst)
			var tcp []byte
			if family == "ipv4" {
				if len(rst) != 40 || rst[8] != 64 || rst[9] != protoTCP {
					t.Fatalf("%s: ipv4 header %x", tc.name, rst[:20])
				}
				if !bytes.Equal(rst[12:16], pkt[16:20]) || !bytes.Equal(rst[16:20], pkt[12:16]) {
					t.Fatalf("%s: addresses not swapped", tc.name)
				}
				tcp = rst[20:]
			} else {
				if len(rst) != 60 || rst[7] != 64 || rst[6] != protoTCP {
					t.Fatalf("%s: ipv6 header %x", tc.name, rst[:40])
				}
				if !bytes.Equal(rst[8:24], pkt[24:40]) || !bytes.Equal(rst[24:40], pkt[8:24]) {
					t.Fatalf("%s: addresses not swapped", tc.name)
				}
				tcp = rst[40:]
			}
			if binary.BigEndian.Uint16(tcp[0:]) != 853 || binary.BigEndian.Uint16(tcp[2:]) != 41000 {
				t.Errorf("%s %s: ports not swapped", family, tc.name)
			}
			if got := binary.BigEndian.Uint32(tcp[4:]); got != tc.wantSeq {
				t.Errorf("%s %s: seq %d, want %d", family, tc.name, got, tc.wantSeq)
			}
			if got := binary.BigEndian.Uint32(tcp[8:]); got != tc.wantAck {
				t.Errorf("%s %s: ack %d, want %d", family, tc.name, got, tc.wantAck)
			}
			if tcp[13] != tc.wantCtl || tcp[12] != 5<<4 {
				t.Errorf("%s %s: flags %#02x offset %#02x", family, tc.name, tcp[13], tcp[12])
			}
		}
	}
}

func TestReplyHeadersAndChecksums(t *testing.T) {
	for _, tc := range []struct{ resolver, client string }{
		{resolverV4, clientV4},
		{resolverV6, clientV6},
	} {
		dns := append(dnsQuery(0x1234), 0xab) // odd length exercises the checksum padding
		pkt := buildReply(netip.MustParseAddr(tc.resolver), netip.MustParseAddr(tc.client), 40001, dns)
		if !bytes.Equal(pkt[:headroom], make([]byte, headroom)) {
			t.Fatalf("headroom not zero")
		}
		ip := pkt[headroom:]
		verifyChecksums(t, ip)
		udp := ip[20:]
		if ip[0]>>4 == 4 {
			if ip[8] != 64 || ip[9] != protoUDP || int(binary.BigEndian.Uint16(ip[2:])) != len(ip) {
				t.Fatalf("ipv4 header %x", ip[:20])
			}
		} else {
			udp = ip[40:]
			if ip[7] != 64 || ip[6] != protoUDP || int(binary.BigEndian.Uint16(ip[4:])) != len(udp) {
				t.Fatalf("ipv6 header %x", ip[:40])
			}
		}
		if binary.BigEndian.Uint16(udp[0:]) != 53 || binary.BigEndian.Uint16(udp[2:]) != 40001 ||
			int(binary.BigEndian.Uint16(udp[4:])) != len(udp) || !bytes.Equal(udp[8:], dns) {
			t.Fatalf("udp %x", udp)
		}
	}
}

func TestUdpChecksumOfZeroIsSentAsAllOnes(t *testing.T) {
	resolver, client := netip.MustParseAddr(resolverV6), netip.MustParseAddr(clientV6)
	dns := append(dnsQuery(1), 0, 0, 0) // 32 bytes, so the last two sit in one aligned 16-bit word
	probe := buildReply(resolver, client, 40002, dns)[headroom+40:]
	binary.BigEndian.PutUint16(probe[6:], 0)
	partial := fold(sum16(probe, 0) + sum16(resolver.AsSlice(), 0) + sum16(client.AsSlice(), 0) +
		uint32(len(probe)) + protoUDP)
	binary.BigEndian.PutUint16(dns[len(dns)-2:], 0xffff-partial) // makes the computed checksum exactly zero
	udp := buildReply(resolver, client, 40002, dns)[headroom+40:]
	if got := binary.BigEndian.Uint16(udp[6:]); got != 0xffff {
		t.Fatalf("checksum %#04x, want 0xffff", got)
	}
}

func TestTouchesSeesAResolverAsSourceOrDestination(t *testing.T) {
	set := testSet()
	for name, tc := range map[string]struct {
		pkt  []byte
		want bool
	}{
		"ipv4 from a resolver":  {ipv4Packet(resolverV4, clientV4, protoUDP, udpBody(53, 40000, dnsQuery(1))), true},
		"ipv4 to a resolver":    {ipv4Packet("192.0.2.10", resolverV4, protoUDP, udpBody(40000, 53, dnsQuery(1))), true},
		"ipv6 from a resolver":  {ipv6Packet(resolverV6, clientV6, protoUDP, udpBody(53, 40000, dnsQuery(1))), true},
		"ipv6 to a resolver":    {ipv6Packet("2001:db8::10", resolverV6, protoTCP, tcpBody(4000, 53, 1, 0, tcpFlagSYN, nil)), true},
		"ipv4 elsewhere":        {ipv4Packet("192.0.2.10", clientV4, protoUDP, udpBody(443, 5000, nil)), false},
		"ipv6 elsewhere":        {ipv6Packet("2001:db8::10", clientV6, protoUDP, udpBody(443, 5000, nil)), false},
		"ipv4 header cut short": {ipv4Packet(resolverV4, clientV4, protoUDP, nil)[:19], false},
		"ipv6 header cut short": {ipv6Packet(resolverV6, clientV6, protoUDP, nil)[:39], false},
		"not ip":                {[]byte{0x00, 1, 2, 3}, false},
		"empty":                 {[]byte{}, false},
	} {
		if got := set.touches(tc.pkt); got != tc.want {
			t.Errorf("%s: touches = %v, want %v", name, got, tc.want)
		}
	}
}
