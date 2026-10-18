// SPDX-License-Identifier: GPL-3.0-or-later

package dnsintercept

import (
	"encoding/binary"
	"errors"
	"fmt"
	"net/netip"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"golang.org/x/sys/unix"
)

const (
	// maxPending caps the queries waiting for an answer; a query over the cap is dropped.
	maxPending = 1024
	// pendingTTL is how long a query waits for its answer.
	pendingTTL = 10 * time.Second
	// maxAnswerLen is the largest DNS answer Kotlin may send back: the stub resolver's receive buffer on Android 9
	// and later (MAXPACKET in bionic 9.0 libc/dns/net/getaddrinfo.c, netd 10 resolv/getaddrinfo.cpp, DnsResolver
	// resolv_private.h). One cap on every API level: on Android 8.x, whose bionic took 64 KiB (getaddrinfo.c
	// MAXPACKET), an answer between 8 and 64 KiB comes back TC=1, and its TCP retry is reset.
	maxAnswerLen = 8192
	// seqLen is the size of the sequence number that starts every frame.
	seqLen = 4
)

// channel is one socket pair: Go keeps file, Kotlin owns the other end. It also carries the resolver set given to
// the Open that created it.
type channel struct {
	file *os.File
	raw  syscall.RawConn
	set  resolverSet
}

// pendingQuery is a diverted query waiting for its answer.
type pendingQuery struct {
	dev      *Device
	client   netip.Addr
	port     uint16
	resolver netip.Addr
	deadline time.Time
}

var (
	// live is the current channel. It is nil until the first Open and never nil again.
	live atomic.Pointer[channel]

	pendingMu sync.Mutex
	pending   = make(map[uint32]pendingQuery)
	nextSeq   uint32 // wraps; guarded by pendingMu
)

// Ready reports whether a channel was ever opened in this process. wgTurnOn refuses to start without one: the
// filter would not know which addresses to catch.
func Ready() bool {
	return live.Load() != nil
}

// Open creates the process-wide channel, or replaces it, and returns the fd of Kotlin's end, which the caller now
// owns. resolvers is a comma-separated list of IP address literals. Replacing closes Go's end of the old pair, so
// the old Kotlin reader sees end-of-file (net/unix/af_unix.c unix_release_sock; one ECONNRESET first if Go's end
// still held an unread answer) and the old answer goroutine exits (internal/poll fd_unix.go FD.Close wakes its Read).
func Open(resolvers string) (int, error) {
	addrs, err := parseResolvers(strings.Clone(resolvers))
	if err != nil {
		return -1, err
	}
	fds, err := unix.Socketpair(unix.AF_UNIX, unix.SOCK_SEQPACKET|unix.SOCK_CLOEXEC, 0)
	if err != nil {
		return -1, fmt.Errorf("socketpair: %w", err)
	}
	if err := unix.SetNonblock(fds[0], true); err != nil {
		unix.Close(fds[0])
		unix.Close(fds[1])
		return -1, fmt.Errorf("set nonblock: %w", err)
	}
	file := os.NewFile(uintptr(fds[0]), "dnsintercept")
	raw, err := file.SyscallConn()
	if err != nil {
		file.Close()
		unix.Close(fds[1])
		return -1, err
	}
	ch := &channel{file: file, raw: raw, set: newResolverSet(addrs)}
	if old := live.Swap(ch); old != nil {
		old.file.Close()
	}
	go ch.serveAnswers()
	return fds[1], nil
}

func parseResolvers(list string) ([]netip.Addr, error) {
	var addrs []netip.Addr
	for _, field := range strings.Split(list, ",") {
		addr, err := netip.ParseAddr(strings.TrimSpace(field))
		if err != nil {
			return nil, err
		}
		if addr.Zone() != "" {
			return nil, fmt.Errorf("resolver address %q has a zone", field)
		}
		addrs = append(addrs, addr)
	}
	if len(addrs) == 0 {
		return nil, errors.New("no resolver addresses")
	}
	return addrs, nil
}

// divert records the query and sends its frame without blocking. A full table, a full socket buffer or a dead
// channel drops the query (net/unix/af_unix.c unix_dgram_sendmsg fails a MSG_DONTWAIT send with EAGAIN or EPIPE).
func (ch *channel) divert(dev *Device, q segment) {
	seq, ok := remember(pendingQuery{
		dev:      dev,
		client:   q.src,
		port:     q.srcPort,
		resolver: q.dst,
		deadline: time.Now().Add(pendingTTL),
	})
	if !ok {
		return
	}
	frame := make([]byte, 0, seqLen+1+16+len(q.payload))
	frame = binary.BigEndian.AppendUint32(frame, seq)
	if q.src.Is4() {
		a := q.src.As4()
		frame = append(frame, 4)
		frame = append(frame, a[:]...)
	} else {
		a := q.src.As16()
		frame = append(frame, 16)
		frame = append(frame, a[:]...)
	}
	frame = append(frame, q.payload...)
	if !ch.send(frame) {
		take(seq)
	}
}

func (ch *channel) send(frame []byte) bool {
	var sendErr error
	err := ch.raw.Write(func(fd uintptr) bool {
		sendErr = unix.Sendto(int(fd), frame, unix.MSG_DONTWAIT|unix.MSG_NOSIGNAL, nil)
		return true // never wait for the socket to drain (internal/poll fd_unix.go FD.RawWrite)
	})
	return err == nil && sendErr == nil
}

// serveAnswers reads answer frames until the channel closes and injects each answer as a UDP reply.
func (ch *channel) serveAnswers() {
	// one spare byte detects a frame that is too long: a read returns at most len(buf) bytes of a record and
	// discards the rest (net/unix/af_unix.c __unix_dgram_recvmsg)
	buf := make([]byte, seqLen+maxAnswerLen+1)
	for {
		n, err := ch.file.Read(buf)
		if err != nil {
			// end-of-file: Kotlin closed its end (one ECONNRESET first if it held unread frames: net/unix/af_unix.c
			// unix_release_sock); or Open closed ours
			return
		}
		if n < seqLen+dnsHeaderLen || n > seqLen+maxAnswerLen {
			continue
		}
		q, ok := take(binary.BigEndian.Uint32(buf[:seqLen]))
		if !ok || time.Now().After(q.deadline) || current.Load() != q.dev {
			continue
		}
		q.dev.inject(buildReply(q.resolver, q.client, q.port, buf[seqLen:n]))
	}
}

func remember(q pendingQuery) (uint32, bool) {
	pendingMu.Lock()
	defer pendingMu.Unlock()
	if len(pending) >= maxPending {
		now := time.Now()
		for seq, old := range pending {
			if now.After(old.deadline) {
				delete(pending, seq)
			}
		}
		if len(pending) >= maxPending {
			return 0, false
		}
	}
	nextSeq++
	pending[nextSeq] = q
	return nextSeq, true
}

func take(seq uint32) (pendingQuery, bool) {
	pendingMu.Lock()
	defer pendingMu.Unlock()
	q, ok := pending[seq]
	if ok {
		delete(pending, seq)
	}
	return q, ok
}

// forget drops every query dev diverted, so a closed device is not kept alive by the table.
func forget(dev *Device) {
	pendingMu.Lock()
	defer pendingMu.Unlock()
	for seq, q := range pending {
		if q.dev == dev {
			delete(pending, seq)
		}
	}
}
