// SPDX-License-Identifier: GPL-3.0-or-later

package dnsintercept

import (
	"encoding/binary"
	"net/netip"
	"testing"
)

const (
	testResolvers = "10.111.222.3,fdcb:b743:7f0b::3"
	resolverV4    = "10.111.222.3"
	resolverV6    = "fdcb:b743:7f0b::3"
	clientV4      = "10.0.0.2"
	clientV6      = "fd00::2"
)

// Test packets are built here by hand, independently of the production builders. Their own checksums are left
// zero: the filter never checks them.

func ipv4Packet(src, dst string, proto byte, body []byte) []byte {
	p := make([]byte, 20+len(body))
	p[0] = 0x45
	binary.BigEndian.PutUint16(p[2:], uint16(len(p)))
	p[8] = 64
	p[9] = proto
	s, d := netip.MustParseAddr(src).As4(), netip.MustParseAddr(dst).As4()
	copy(p[12:], s[:])
	copy(p[16:], d[:])
	copy(p[20:], body)
	return p
}

func ipv6Packet(src, dst string, next byte, body []byte) []byte {
	p := make([]byte, 40+len(body))
	p[0] = 0x60
	binary.BigEndian.PutUint16(p[4:], uint16(len(body)))
	p[6] = next
	p[7] = 64
	s, d := netip.MustParseAddr(src).As16(), netip.MustParseAddr(dst).As16()
	copy(p[8:], s[:])
	copy(p[24:], d[:])
	copy(p[40:], body)
	return p
}

func udpBody(srcPort, dstPort uint16, payload []byte) []byte {
	b := make([]byte, 8+len(payload))
	binary.BigEndian.PutUint16(b[0:], srcPort)
	binary.BigEndian.PutUint16(b[2:], dstPort)
	binary.BigEndian.PutUint16(b[4:], uint16(len(b)))
	copy(b[8:], payload)
	return b
}

func tcpBody(srcPort, dstPort uint16, seq, ack uint32, flags byte, payload []byte) []byte {
	b := make([]byte, 20+len(payload))
	binary.BigEndian.PutUint16(b[0:], srcPort)
	binary.BigEndian.PutUint16(b[2:], dstPort)
	binary.BigEndian.PutUint32(b[4:], seq)
	binary.BigEndian.PutUint32(b[8:], ack)
	b[12] = 5 << 4
	b[13] = flags
	binary.BigEndian.PutUint16(b[14:], 65535)
	copy(b[20:], payload)
	return b
}

// dnsQuery is a minimal 29-byte DNS query for example.com A, enough for the 12-byte floor.
func dnsQuery(id uint16) []byte {
	q := []byte{0, 0, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0,
		7, 'e', 'x', 'a', 'm', 'p', 'l', 'e', 3, 'c', 'o', 'm', 0, 0, 1, 0, 1}
	binary.BigEndian.PutUint16(q[0:], id)
	return q
}

func v4Query(port uint16, id uint16) []byte {
	return ipv4Packet(clientV4, resolverV4, protoUDP, udpBody(port, 53, dnsQuery(id)))
}

func v6Query(port uint16, id uint16) []byte {
	return ipv6Packet(clientV6, resolverV6, protoUDP, udpBody(port, 53, dnsQuery(id)))
}

// onesSum is the independent checksum verifier: it sums every 16-bit word of the given parts, concatenated, with a
// 64-bit accumulator and folds at the end. A correct header or segment, checksum field included, sums to 0xffff.
func onesSum(parts ...[]byte) uint16 {
	var all []byte
	for _, p := range parts {
		all = append(all, p...)
	}
	if len(all)%2 == 1 {
		all = append(all, 0)
	}
	var acc uint64
	for i := 0; i < len(all); i += 2 {
		acc += uint64(binary.BigEndian.Uint16(all[i:]))
	}
	for acc>>16 != 0 {
		acc = acc&0xffff + acc>>16
	}
	return uint16(acc)
}

// verifyChecksums checks the IPv4 header checksum (if any) and the transport checksum of an IP packet.
func verifyChecksums(t *testing.T, pkt []byte) {
	t.Helper()
	var pseudo, body []byte
	switch pkt[0] >> 4 {
	case 4:
		ihl := int(pkt[0]&0x0f) * 4
		if got := onesSum(pkt[:ihl]); got != 0xffff {
			t.Fatalf("IPv4 header checksum invalid: sum %#04x", got)
		}
		body = pkt[ihl:binary.BigEndian.Uint16(pkt[2:])]
		pseudo = append(append(append([]byte{}, pkt[12:16]...), pkt[16:20]...), 0, pkt[9], byte(len(body)>>8), byte(len(body)))
	case 6:
		body = pkt[40 : 40+int(binary.BigEndian.Uint16(pkt[4:]))]
		l := uint32(len(body))
		pseudo = append(append(append([]byte{}, pkt[8:24]...), pkt[24:40]...),
			byte(l>>24), byte(l>>16), byte(l>>8), byte(l), 0, 0, 0, pkt[6])
	default:
		t.Fatalf("not an IP packet: %x", pkt)
	}
	if got := onesSum(pseudo, body); got != 0xffff {
		t.Fatalf("transport checksum invalid: sum %#04x", got)
	}
}

func srcOf(pkt []byte) netip.Addr {
	if pkt[0]>>4 == 4 {
		return netip.AddrFrom4([4]byte(pkt[12:16]))
	}
	return netip.AddrFrom16([16]byte(pkt[8:24]))
}

func dstOf(pkt []byte) netip.Addr {
	if pkt[0]>>4 == 4 {
		return netip.AddrFrom4([4]byte(pkt[16:20]))
	}
	return netip.AddrFrom16([16]byte(pkt[24:40]))
}

// splitReply returns a reply's addresses and its UDP segment, checking the IP header's protocol and hop limit.
func splitReply(t *testing.T, pkt []byte) (src, dst netip.Addr, udp []byte) {
	t.Helper()
	if pkt[0]>>4 == 4 {
		if pkt[9] != protoUDP || pkt[8] != 64 {
			t.Fatalf("ipv4 header %x", pkt[:20])
		}
		return srcOf(pkt), dstOf(pkt), pkt[20:]
	}
	if pkt[6] != protoUDP || pkt[7] != 64 {
		t.Fatalf("ipv6 header %x", pkt[:40])
	}
	return srcOf(pkt), dstOf(pkt), pkt[40:]
}
