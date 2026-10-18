// SPDX-License-Identifier: GPL-3.0-or-later

package dnsintercept

import (
	"bytes"
	"testing"
)

func TestReadConsumesOnlyPacketsForAResolver(t *testing.T) {
	resetState(t)
	peer := openChannel(t)
	f := newFakeTun()
	dev := Wrap(f)

	other := ipv4Packet(clientV4, "192.0.2.10", protoUDP, udpBody(5000, 53, dnsQuery(1)))
	other6 := ipv6Packet(clientV6, "2001:db8::10", protoTCP, tcpBody(4000, 443, 1, 0, tcpFlagSYN, nil))
	query := v4Query(40000, 2)
	syn := ipv4Packet(clientV4, resolverV4, protoTCP, tcpBody(41000, 53, 100, 0, tcpFlagSYN, nil))
	icmp := ipv6Packet(clientV6, resolverV6, 58, []byte{128, 0, 0, 0, 0, 1, 0, 1})

	n, sizes, bufs := readThrough(t, dev, f, other, query, other6, syn, icmp)

	if n != 5 {
		t.Fatalf("n = %d, want the inner count 5", n)
	}
	want := []int{len(other), 0, len(other6), 0, 0}
	for i := range want {
		if sizes[i] != want[i] {
			t.Fatalf("sizes = %v, want %v", sizes, want)
		}
	}
	if !bytes.Equal(bufs[0][headroom:headroom+sizes[0]], other) || !bytes.Equal(bufs[2][headroom:headroom+sizes[2]], other6) {
		t.Fatal("a pass-through packet moved or changed")
	}
	frame := readFrame(t, peer)
	if !bytes.Equal(frame[seqLen+5:], dnsQuery(2)) {
		t.Fatalf("diverted frame %x", frame)
	}
	rst := f.waitWrite(t)
	if rst[9] != protoTCP || rst[33]&tcpFlagRST == 0 {
		t.Fatalf("expected a reset, got %x", rst)
	}
	if got := f.writtenCount(); got != 1 {
		t.Fatalf("%d packets written, want only the reset", got)
	}
}

func TestReadWithoutAnOpenChannelPassesEverything(t *testing.T) {
	resetState(t)
	f := newFakeTun()
	dev := Wrap(f)
	query := v4Query(40000, 3)
	n, sizes, _ := readThrough(t, dev, f, query)
	if n != 1 || sizes[0] != len(query) {
		t.Fatalf("n = %d sizes = %v", n, sizes)
	}
}

func TestWriteDropsPacketsFromOrToAResolver(t *testing.T) {
	resetState(t)
	openChannel(t)
	f := newFakeTun()
	dev := Wrap(f)

	keep1 := ipv4Packet("192.0.2.10", clientV4, protoUDP, udpBody(53, 5000, dnsQuery(1)))
	spoof := ipv4Packet(resolverV4, clientV4, protoUDP, udpBody(53, 5000, dnsQuery(1)))
	keep2 := ipv6Packet("2001:db8::10", clientV6, protoUDP, udpBody(53, 5000, dnsQuery(2)))
	probe := ipv6Packet(clientV6, resolverV6, protoUDP, udpBody(5000, 53, dnsQuery(3)))

	batch := [][]byte{withHeadroom(keep1), withHeadroom(spoof), withHeadroom(keep2), withHeadroom(probe)}
	if _, err := dev.Write(batch, headroom); err != nil {
		t.Fatalf("Write: %v", err)
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	if len(f.written) != 2 || !bytes.Equal(f.written[0], keep1) || !bytes.Equal(f.written[1], keep2) {
		t.Fatalf("written %d packets, want keep1 then keep2", len(f.written))
	}
}

func TestWriteHandsACleanBatchThroughUncopied(t *testing.T) {
	resetState(t)
	openChannel(t)
	f := newFakeTun()
	dev := Wrap(f)
	batch := [][]byte{
		withHeadroom(ipv4Packet("192.0.2.10", clientV4, protoUDP, udpBody(53, 5000, dnsQuery(1)))),
		withHeadroom(ipv6Packet("2001:db8::10", clientV6, protoUDP, udpBody(53, 5000, dnsQuery(2)))),
	}
	if _, err := dev.Write(batch, headroom); err != nil {
		t.Fatalf("Write: %v", err)
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	if len(f.lastBatch) != 2 || &f.lastBatch[0] != &batch[0] {
		t.Fatal("a clean batch was copied instead of handed through")
	}
}

func TestWriteOfOnlyResolverPacketsWritesNothing(t *testing.T) {
	resetState(t)
	openChannel(t)
	f := newFakeTun()
	dev := Wrap(f)
	spoof := withHeadroom(ipv4Packet(resolverV4, clientV4, protoUDP, udpBody(53, 5000, dnsQuery(1))))
	if n, err := dev.Write([][]byte{spoof}, headroom); n != 0 || err != nil {
		t.Fatalf("Write = %d, %v", n, err)
	}
	if got := f.writtenCount(); got != 0 {
		t.Fatalf("%d packets written", got)
	}
}

func TestAResetGetsNoReply(t *testing.T) {
	resetState(t)
	openChannel(t)
	f := newFakeTun()
	dev := Wrap(f)
	rst := ipv4Packet(clientV4, resolverV4, protoTCP, tcpBody(41000, 53, 5, 0, tcpFlagRST, nil))
	_, sizes, _ := readThrough(t, dev, f, rst)
	if sizes[0] != 0 {
		t.Fatal("a reset to the resolver was passed on")
	}
	if got := f.writtenCount(); got != 0 {
		t.Fatalf("%d packets written in reply to a reset", got)
	}
}

func withHeadroom(pkt []byte) []byte {
	return append(make([]byte, headroom), pkt...)
}
