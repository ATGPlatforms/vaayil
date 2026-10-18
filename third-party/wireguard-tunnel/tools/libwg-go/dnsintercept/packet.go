// SPDX-License-Identifier: GPL-3.0-or-later

// Package dnsintercept is Vaayil's DNS intercept inside libwg-go. It wraps the tun device that wgTurnOn hands to
// wireguard-go (api-android.go, wgTurnOn), catches packets addressed to the in-tunnel resolver addresses and carries
// DNS queries to the Kotlin resolver over a process-wide socket pair. Nothing addressed to a resolver address ever
// reaches the peer.
package dnsintercept

import (
	"encoding/binary"
	"net/netip"
)

const (
	// headroom is the space left in front of every packet this package writes into the tun device. It equals
	// wireguard-go's MessageTransportHeaderSize (device/noise-protocol.go, MessageTransportHeaderSize), the offset
	// wireguard-go itself writes with (device/receive.go, (*Peer).RoutineSequentialReceiver), and it covers the
	// 10-byte virtio header a vnet-hdr tun would need (tun/offload_linux.go, virtioNetHdrLen).
	headroom = 16

	protoTCP = 6
	protoUDP = 17

	dnsPort      = 53
	dnsHeaderLen = 12

	ipv4HeaderLen = 20
	ipv6HeaderLen = 40
	udpHeaderLen  = 8
	tcpHeaderLen  = 20

	replyHopLimit = 64

	tcpFlagFIN = 0x01
	tcpFlagSYN = 0x02
	tcpFlagRST = 0x04
	tcpFlagACK = 0x10
)

// verdict is what the outbound filter does with one packet.
type verdict int

const (
	pass   verdict = iota // not addressed to a resolver address: hand it to WireGuard untouched
	divert                // UDP to port 53 with a DNS payload: send it to the channel
	reset                 // TCP without RST: answer with a reset
	drop                  // addressed to a resolver address, anything else
)

// segment is what classify extracts from a packet it diverts or resets.
type segment struct {
	src, dst         netip.Addr
	srcPort, dstPort uint16
	payload          []byte // the UDP payload; aliases the packet buffer
	seq, ack         uint32 // TCP only
	flags            byte   // TCP only
	payloadLen       int    // TCP only: the segment's data length
}

// resolverSet holds the resolver addresses, split by family for byte comparison on the hot path.
type resolverSet struct {
	v4 [][4]byte
	v6 [][16]byte
}

func newResolverSet(addrs []netip.Addr) resolverSet {
	var s resolverSet
	for _, a := range addrs {
		if a.Is4() {
			s.v4 = append(s.v4, a.As4())
		} else {
			s.v6 = append(s.v6, a.As16())
		}
	}
	return s
}

func (s *resolverSet) has4(b []byte) bool {
	for _, r := range s.v4 {
		if [4]byte(b) == r {
			return true
		}
	}
	return false
}

func (s *resolverSet) has16(b []byte) bool {
	for _, r := range s.v6 {
		if [16]byte(b) == r {
			return true
		}
	}
	return false
}

// touches reports whether pkt has a resolver address as its source or destination. The inbound filter uses it.
func (s *resolverSet) touches(pkt []byte) bool {
	if len(pkt) == 0 {
		return false
	}
	switch pkt[0] >> 4 {
	case 4:
		return len(pkt) >= ipv4HeaderLen && (s.has4(pkt[12:16]) || s.has4(pkt[16:20]))
	case 6:
		return len(pkt) >= ipv6HeaderLen && (s.has16(pkt[8:24]) || s.has16(pkt[24:40]))
	}
	return false
}

// classify applies the outbound table to one packet. Only a packet whose destination is a resolver address gets
// anything other than pass; such a packet is never passed, whatever its shape.
func classify(pkt []byte, s *resolverSet) (verdict, segment) {
	if len(pkt) == 0 {
		return pass, segment{}
	}
	switch pkt[0] >> 4 {
	case 4:
		if len(pkt) < ipv4HeaderLen || !s.has4(pkt[16:20]) {
			return pass, segment{}
		}
		return classify4(pkt)
	case 6:
		if len(pkt) < ipv6HeaderLen || !s.has16(pkt[24:40]) {
			return pass, segment{}
		}
		return classify6(pkt)
	}
	return pass, segment{}
}

func classify4(pkt []byte) (verdict, segment) {
	ihl := int(pkt[0]&0x0f) * 4
	total := int(binary.BigEndian.Uint16(pkt[2:4]))
	if ihl < ipv4HeaderLen || total < ihl || total > len(pkt) {
		return drop, segment{}
	}
	if binary.BigEndian.Uint16(pkt[6:8])&0x3fff != 0 { // more-fragments flag or a fragment offset
		return drop, segment{}
	}
	seg := segment{
		src: netip.AddrFrom4([4]byte(pkt[12:16])),
		dst: netip.AddrFrom4([4]byte(pkt[16:20])),
	}
	return transport(pkt[9], pkt[ihl:total], seg)
}

func classify6(pkt []byte) (verdict, segment) {
	end := ipv6HeaderLen + int(binary.BigEndian.Uint16(pkt[4:6]))
	if end > len(pkt) {
		return drop, segment{}
	}
	seg := segment{
		src: netip.AddrFrom16([16]byte(pkt[8:24])),
		dst: netip.AddrFrom16([16]byte(pkt[24:40])),
	}
	// Extension headers fall through to drop: next header must name UDP or TCP directly.
	return transport(pkt[6], pkt[ipv6HeaderLen:end], seg)
}

func transport(proto byte, body []byte, seg segment) (verdict, segment) {
	switch proto {
	case protoUDP:
		if len(body) < udpHeaderLen {
			return drop, segment{}
		}
		length := int(binary.BigEndian.Uint16(body[4:6]))
		if length < udpHeaderLen || length > len(body) {
			return drop, segment{}
		}
		seg.srcPort = binary.BigEndian.Uint16(body[0:2])
		seg.dstPort = binary.BigEndian.Uint16(body[2:4])
		seg.payload = body[udpHeaderLen:length]
		if seg.dstPort != dnsPort || len(seg.payload) < dnsHeaderLen {
			return drop, segment{}
		}
		return divert, seg
	case protoTCP:
		if len(body) < tcpHeaderLen {
			return drop, segment{}
		}
		dataOffset := int(body[12]>>4) * 4
		if dataOffset < tcpHeaderLen || dataOffset > len(body) {
			return drop, segment{}
		}
		seg.flags = body[13]
		if seg.flags&tcpFlagRST != 0 {
			return drop, segment{}
		}
		seg.srcPort = binary.BigEndian.Uint16(body[0:2])
		seg.dstPort = binary.BigEndian.Uint16(body[2:4])
		seg.seq = binary.BigEndian.Uint32(body[4:8])
		seg.ack = binary.BigEndian.Uint32(body[8:12])
		seg.payloadLen = len(body) - dataOffset
		return reset, seg
	}
	return drop, segment{}
}

// newPacket allocates headroom plus an IP header plus transportLen bytes, writes the IP header for src→dst with a
// hop limit of 64, and returns the whole buffer and the transport part to fill in. src and dst share a family.
func newPacket(src, dst netip.Addr, proto byte, transportLen int) (buf, body []byte) {
	if src.Is4() {
		buf = make([]byte, headroom+ipv4HeaderLen+transportLen)
		ip := buf[headroom : headroom+ipv4HeaderLen]
		ip[0] = 0x45
		binary.BigEndian.PutUint16(ip[2:4], uint16(ipv4HeaderLen+transportLen))
		ip[8] = replyHopLimit
		ip[9] = proto
		s, d := src.As4(), dst.As4()
		copy(ip[12:16], s[:])
		copy(ip[16:20], d[:])
		binary.BigEndian.PutUint16(ip[10:12], ^fold(sum16(ip, 0)))
		return buf, buf[headroom+ipv4HeaderLen:]
	}
	buf = make([]byte, headroom+ipv6HeaderLen+transportLen)
	ip := buf[headroom : headroom+ipv6HeaderLen]
	ip[0] = 0x60
	binary.BigEndian.PutUint16(ip[4:6], uint16(transportLen))
	ip[6] = proto
	ip[7] = replyHopLimit
	s, d := src.As16(), dst.As16()
	copy(ip[8:24], s[:])
	copy(ip[24:40], d[:])
	return buf, buf[headroom+ipv6HeaderLen:]
}

// buildReply builds the UDP answer from resolver:53 to client:port carrying dns, with headroom in front.
func buildReply(resolver, client netip.Addr, port uint16, dns []byte) []byte {
	buf, udp := newPacket(resolver, client, protoUDP, udpHeaderLen+len(dns))
	binary.BigEndian.PutUint16(udp[0:2], dnsPort)
	binary.BigEndian.PutUint16(udp[2:4], port)
	binary.BigEndian.PutUint16(udp[4:6], uint16(udpHeaderLen+len(dns)))
	copy(udp[udpHeaderLen:], dns)
	sum := transportChecksum(resolver, client, protoUDP, udp)
	if sum == 0 {
		sum = 0xffff // RFC 768: a computed zero is sent as all ones
	}
	binary.BigEndian.PutUint16(udp[6:8], sum)
	return buf
}

// buildReset builds the RFC 9293 §3.10.7.1 reset for a TCP segment sent to a resolver address, with headroom in
// front. The reset comes from the resolver address and port the segment went to.
func buildReset(seg segment) []byte {
	buf, tcp := newPacket(seg.dst, seg.src, protoTCP, tcpHeaderLen)
	binary.BigEndian.PutUint16(tcp[0:2], seg.dstPort)
	binary.BigEndian.PutUint16(tcp[2:4], seg.srcPort)
	if seg.flags&tcpFlagACK != 0 {
		binary.BigEndian.PutUint32(tcp[4:8], seg.ack) // <SEQ=SEG.ACK><CTL=RST>
		tcp[13] = tcpFlagRST
	} else {
		length := uint32(seg.payloadLen)
		if seg.flags&tcpFlagSYN != 0 {
			length++
		}
		if seg.flags&tcpFlagFIN != 0 {
			length++
		}
		binary.BigEndian.PutUint32(tcp[8:12], seg.seq+length) // <SEQ=0><ACK=SEG.SEQ+SEG.LEN><CTL=RST,ACK>
		tcp[13] = tcpFlagRST | tcpFlagACK
	}
	tcp[12] = (tcpHeaderLen / 4) << 4
	binary.BigEndian.PutUint16(tcp[16:18], transportChecksum(seg.dst, seg.src, protoTCP, tcp))
	return buf
}

// transportChecksum is the UDP or TCP checksum of body (its checksum field zero) over the pseudo-header.
func transportChecksum(src, dst netip.Addr, proto byte, body []byte) uint16 {
	var sum uint32
	if src.Is4() {
		s, d := src.As4(), dst.As4()
		sum = sum16(s[:], sum)
		sum = sum16(d[:], sum)
		sum += uint32(proto) + uint32(len(body))
	} else {
		s, d := src.As16(), dst.As16()
		sum = sum16(s[:], sum)
		sum = sum16(d[:], sum)
		sum += uint32(len(body)>>16) + uint32(len(body)&0xffff) + uint32(proto)
	}
	return ^fold(sum16(body, sum))
}

// sum16 adds b to sum as big-endian 16-bit words (RFC 1071), padding an odd tail with a zero byte.
func sum16(b []byte, sum uint32) uint32 {
	for len(b) >= 2 {
		sum += uint32(b[0])<<8 | uint32(b[1])
		b = b[2:]
	}
	if len(b) == 1 {
		sum += uint32(b[0]) << 8
	}
	return sum
}

func fold(sum uint32) uint16 {
	for sum > 0xffff {
		sum = sum>>16 + sum&0xffff
	}
	return uint16(sum)
}
