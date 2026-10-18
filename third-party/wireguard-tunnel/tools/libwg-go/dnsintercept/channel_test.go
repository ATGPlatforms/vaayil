// SPDX-License-Identifier: GPL-3.0-or-later

package dnsintercept

import (
	"bytes"
	"encoding/binary"
	"errors"
	"io"
	"sync"
	"testing"
	"time"
)

func TestReadyOnlyAfterAChannelWasOpened(t *testing.T) {
	resetState(t)
	if Ready() {
		t.Fatal("Ready before any Open")
	}
	openChannel(t)
	if !Ready() {
		t.Fatal("not Ready after Open")
	}
}

func TestOpenRejectsABadResolverList(t *testing.T) {
	resetState(t)
	for _, list := range []string{"", "resolver.example", "10.111.222.3,", "fe80::1%wlan0"} {
		if fd, err := Open(list); err == nil {
			t.Fatalf("Open(%q) = %d, want an error", list, fd)
		}
	}
	if Ready() {
		t.Fatal("a rejected Open made the channel ready")
	}
}

func TestQueryFramesCarrySeqFamilyClientAndMessage(t *testing.T) {
	resetState(t)
	peer := openChannel(t)
	f := newFakeTun()
	dev := Wrap(f)

	readThrough(t, dev, f, v4Query(40000, 0x0a0b))
	frame := readFrame(t, peer)
	if frame[seqLen] != 4 || !bytes.Equal(frame[seqLen+1:seqLen+5], []byte{10, 0, 0, 2}) ||
		!bytes.Equal(frame[seqLen+5:], dnsQuery(0x0a0b)) {
		t.Fatalf("ipv4 frame %x", frame)
	}
	first := binary.BigEndian.Uint32(frame)

	readThrough(t, dev, f, v6Query(40001, 0x0c0d))
	frame = readFrame(t, peer)
	client := []byte{0xfd, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2}
	if frame[seqLen] != 16 || !bytes.Equal(frame[seqLen+1:seqLen+17], client) ||
		!bytes.Equal(frame[seqLen+17:], dnsQuery(0x0c0d)) {
		t.Fatalf("ipv6 frame %x", frame)
	}
	if binary.BigEndian.Uint32(frame) == first {
		t.Fatal("two queries share a sequence number")
	}
}

func TestAnAnswerFrameBecomesAReplyToTheClient(t *testing.T) {
	for _, tc := range []struct {
		name  string
		query []byte
	}{
		{"ipv4", v4Query(40000, 0x1111)},
		{"ipv6", v6Query(40000, 0x1111)},
	} {
		t.Run(tc.name, func(t *testing.T) {
			resetState(t)
			peer := openChannel(t)
			f := newFakeTun()
			dev := Wrap(f)
			readThrough(t, dev, f, tc.query)
			seq := binary.BigEndian.Uint32(readFrame(t, peer))
			answer := append(dnsQuery(0x1111), 0xc0, 0x0c)
			answer[2] |= 0x80
			writeAnswer(t, peer, seq, answer)

			reply := f.waitWrite(t)
			verifyChecksums(t, reply)
			src, dst, udp := splitReply(t, reply)
			if src != dstOf(tc.query) || dst != srcOf(tc.query) {
				t.Fatalf("reply %v -> %v, want resolver -> client", src, dst)
			}
			if binary.BigEndian.Uint16(udp[0:]) != 53 || binary.BigEndian.Uint16(udp[2:]) != 40000 ||
				!bytes.Equal(udp[8:], answer) {
				t.Fatalf("reply udp %x", udp)
			}
		})
	}
}

func TestAnswersForUnknownExpiredOrOversizedFramesAreDropped(t *testing.T) {
	resetState(t)
	peer := openChannel(t)
	f := newFakeTun()
	dev := Wrap(f)
	answer := dnsQuery(0x2222)

	readThrough(t, dev, f, v4Query(40000, 1))
	expired := binary.BigEndian.Uint32(readFrame(t, peer))
	expirePending()

	readThrough(t, dev, f, v4Query(40001, 2))
	oversized := binary.BigEndian.Uint32(readFrame(t, peer))

	readThrough(t, dev, f, v4Query(40002, 3))
	sentinel := binary.BigEndian.Uint32(readFrame(t, peer))

	writeAnswer(t, peer, sentinel+1000, answer)                   // never issued
	writeAnswer(t, peer, expired, answer)                         // past its deadline
	writeAnswer(t, peer, oversized, make([]byte, maxAnswerLen+1)) // one byte over the cap
	writeAnswer(t, peer, sentinel, make([]byte, maxAnswerLen))    // exactly the cap: accepted

	reply := f.waitWrite(t)
	if port := binary.BigEndian.Uint16(reply[22:]); port != 40002 || len(reply) != 20+8+maxAnswerLen {
		t.Fatalf("first reply went to port %d with %d bytes, want the sentinel", port, len(reply))
	}
	if got := f.writtenCount(); got != 1 {
		t.Fatalf("%d replies written, want only the sentinel", got)
	}
}

func TestAnAnswerForADeviceThatIsNoLongerCurrentIsDropped(t *testing.T) {
	resetState(t)
	peer := openChannel(t)
	oldTun := newFakeTun()
	old := Wrap(oldTun)
	readThrough(t, old, oldTun, v4Query(40000, 1))
	stale := binary.BigEndian.Uint32(readFrame(t, peer))

	newTun := newFakeTun()
	fresh := Wrap(newTun) // a restore (GoBackend.java setState) or a new start makes another device current
	readThrough(t, fresh, newTun, v4Query(40001, 2))
	sentinel := binary.BigEndian.Uint32(readFrame(t, peer))

	writeAnswer(t, peer, stale, dnsQuery(1))
	writeAnswer(t, peer, sentinel, dnsQuery(2))
	reply := newTun.waitWrite(t)
	if port := binary.BigEndian.Uint16(reply[22:]); port != 40001 {
		t.Fatalf("reply went to port %d", port)
	}
	if oldTun.writtenCount() != 0 || newTun.writtenCount() != 1 {
		t.Fatalf("old wrote %d, new wrote %d", oldTun.writtenCount(), newTun.writtenCount())
	}
}

func TestClosingADeviceForgetsItsQueriesAndUnregistersIt(t *testing.T) {
	resetState(t)
	peer := openChannel(t)
	f := newFakeTun()
	dev := Wrap(f)
	readThrough(t, dev, f, v4Query(40000, 1))
	readFrame(t, peer)
	if pendingCount() != 1 {
		t.Fatalf("pending = %d", pendingCount())
	}
	if err := dev.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}
	if pendingCount() != 0 || current.Load() != nil {
		t.Fatalf("after Close: pending %d, current %p", pendingCount(), current.Load())
	}
	other := Wrap(newFakeTun())
	dev.Close() // a second close of the old device must not unregister the new one
	if current.Load() != other {
		t.Fatal("a stale Close unregistered the current device")
	}
}

func TestTheTableCapDropsNewQueriesUntilOldOnesExpire(t *testing.T) {
	resetState(t)
	peer := openChannel(t)
	f := newFakeTun()
	dev := Wrap(f)
	for i := 0; i < maxPending; i++ { // fill the table without sending, so no socket buffer is involved
		if _, ok := remember(pendingQuery{dev: dev, deadline: time.Now().Add(pendingTTL)}); !ok {
			t.Fatalf("remember refused entry %d under the cap", i)
		}
	}

	_, sizes, _ := readThrough(t, dev, f, v4Query(9999, 1))
	if sizes[0] != 0 {
		t.Fatal("a query over the cap reached WireGuard")
	}
	if pendingCount() != maxPending {
		t.Fatalf("over the cap: pending %d, want %d", pendingCount(), maxPending)
	}
	peer.SetReadDeadline(time.Now().Add(100 * time.Millisecond))
	if n, err := peer.Read(make([]byte, 70000)); err == nil {
		t.Fatalf("a query over the cap was framed (%d bytes)", n)
	}

	expirePending()
	readThrough(t, dev, f, v4Query(9999, 2))
	if frame := readFrame(t, peer); !bytes.Equal(frame[seqLen+5:], dnsQuery(2)) {
		t.Fatalf("frame after expiry %x", frame)
	}
	if pendingCount() != 1 {
		t.Fatalf("after expiry: pending %d, want 1", pendingCount())
	}
}

func TestAClosedChannelDropsQueriesWithoutBlocking(t *testing.T) {
	resetState(t)
	peer := openChannel(t)
	peer.Close() // Kotlin's reader is gone
	f := newFakeTun()
	dev := Wrap(f)
	done := make(chan []int, 1)
	go func() {
		bufs := [][]byte{make([]byte, headroom+1500)}
		sizes := []int{0}
		f.reads <- [][]byte{v4Query(40000, 1)}
		dev.Read(bufs, sizes, headroom)
		done <- sizes
	}()
	select {
	case sizes := <-done:
		if sizes[0] != 0 {
			t.Fatal("a query reached WireGuard with the channel closed")
		}
	case <-time.After(2 * time.Second):
		t.Fatal("Read blocked on a closed channel")
	}
	if pendingCount() != 0 {
		t.Fatalf("pending %d after a failed send", pendingCount())
	}
}

func TestAChannelThatNeverDrainsDoesNotStallRead(t *testing.T) {
	resetState(t)
	openChannel(t) // never read
	f := newFakeTun()
	dev := Wrap(f)
	start := time.Now()
	for i := 0; i < 5000; i++ {
		_, sizes, _ := readThrough(t, dev, f, v4Query(uint16(i), uint16(i)))
		if sizes[0] != 0 {
			t.Fatalf("query %d reached WireGuard", i)
		}
	}
	if elapsed := time.Since(start); elapsed > 10*time.Second {
		t.Fatalf("5000 reads took %v", elapsed)
	}
	if pendingCount() > maxPending {
		t.Fatalf("pending %d over the cap", pendingCount())
	}
}

func TestOpeningAgainReplacesTheChannel(t *testing.T) {
	resetState(t)
	first := openChannel(t)
	second := openChannel(t)

	buf := make([]byte, 16)
	first.SetReadDeadline(time.Now().Add(2 * time.Second))
	if _, err := first.Read(buf); !errors.Is(err, io.EOF) {
		t.Fatalf("old Kotlin end read %v, want EOF", err)
	}
	f := newFakeTun()
	dev := Wrap(f)
	readThrough(t, dev, f, v4Query(40000, 5))
	if frame := readFrame(t, second); !bytes.Equal(frame[seqLen+5:], dnsQuery(5)) {
		t.Fatalf("frame on the new channel %x", frame)
	}
}

func TestRepliesInjectWhileWriteCarriesTraffic(t *testing.T) {
	resetState(t)
	peer := openChannel(t)
	f := newFakeTun()
	dev := Wrap(f)
	// 100 frames stay far below the socket buffer (net/core/sock.c sock_init_data_uid: sk_sndbuf = wmem_default).
	const queries, batches = 100, 2000

	var wg sync.WaitGroup
	wg.Add(2)
	go func() { // wireguard-go's receive path writing decrypted packets (device/receive.go RoutineSequentialReceiver)
		defer wg.Done()
		pkt := ipv4Packet("192.0.2.10", clientV4, protoUDP, udpBody(443, 5000, make([]byte, 100)))
		for i := 0; i < batches; i++ {
			dev.Write([][]byte{withHeadroom(pkt)}, headroom)
		}
	}()
	go func() { // the Kotlin resolver answering every frame
		defer wg.Done()
		buf := make([]byte, 70000)
		peer.SetReadDeadline(time.Now().Add(10 * time.Second))
		for i := 0; i < queries; i++ {
			n, err := peer.Read(buf)
			if err != nil {
				t.Errorf("reading frame %d: %v", i, err)
				return
			}
			answer := append(binary.BigEndian.AppendUint32(nil, binary.BigEndian.Uint32(buf)), buf[seqLen+5:n]...)
			if _, err := peer.Write(answer); err != nil {
				t.Errorf("answering frame %d: %v", i, err)
				return
			}
		}
	}()
	for i := 0; i < queries; i++ {
		readThrough(t, dev, f, v4Query(uint16(20000+i), uint16(i)))
	}
	wg.Wait()
	waitFor(t, func() bool { return f.writtenCount() == queries+batches })
}

func waitFor(t *testing.T, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for !cond() {
		if time.Now().After(deadline) {
			t.Fatal("condition not met within 5s")
		}
		time.Sleep(5 * time.Millisecond)
	}
}
