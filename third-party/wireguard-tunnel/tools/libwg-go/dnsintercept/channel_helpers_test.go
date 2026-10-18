// SPDX-License-Identifier: GPL-3.0-or-later

package dnsintercept

import (
	"encoding/binary"
	"os"
	"sync"
	"testing"
	"time"

	"golang.org/x/sys/unix"
	"golang.zx2c4.com/wireguard/tun"
)

// resetState returns the process-wide state to "never opened" and registers the same reset as cleanup, so every
// test starts and ends clean. Tests in this package must not run in parallel.
func resetState(t *testing.T) {
	t.Helper()
	wipe := func() {
		if ch := live.Swap(nil); ch != nil {
			ch.file.Close()
		}
		current.Store(nil)
		pendingMu.Lock()
		pending = make(map[uint32]pendingQuery)
		pendingMu.Unlock()
	}
	wipe()
	t.Cleanup(wipe)
}

func pendingCount() int {
	pendingMu.Lock()
	defer pendingMu.Unlock()
	return len(pending)
}

func expirePending() {
	pendingMu.Lock()
	defer pendingMu.Unlock()
	for seq, q := range pending {
		q.deadline = time.Time{}
		pending[seq] = q
	}
}

// openChannel opens the channel and returns Kotlin's end as a non-blocking file, so reads can time out
// (os/file_unix.go NewFile registers a non-blocking descriptor with the poller, which internal/poll's
// setDeadlineImpl requires).
func openChannel(t *testing.T) *os.File {
	t.Helper()
	fd, err := Open(testResolvers)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	if err := unix.SetNonblock(fd, true); err != nil {
		t.Fatalf("SetNonblock: %v", err)
	}
	peer := os.NewFile(uintptr(fd), "kotlin-end")
	t.Cleanup(func() { peer.Close() })
	return peer
}

func readFrame(t *testing.T, peer *os.File) []byte {
	t.Helper()
	buf := make([]byte, 70000)
	if err := peer.SetReadDeadline(time.Now().Add(2 * time.Second)); err != nil {
		t.Fatalf("SetReadDeadline: %v", err)
	}
	n, err := peer.Read(buf)
	if err != nil {
		t.Fatalf("reading a query frame: %v", err)
	}
	return buf[:n]
}

func writeAnswer(t *testing.T, peer *os.File, seq uint32, dns []byte) {
	t.Helper()
	frame := binary.BigEndian.AppendUint32(nil, seq)
	if _, err := peer.Write(append(frame, dns...)); err != nil {
		t.Fatalf("writing an answer frame: %v", err)
	}
}

// fakeTun stands in for wireguard-go's NativeTun. Write serializes on a mutex, as NativeTun's writeOpMu does
// (tun/tun_linux.go NativeTun.Write).
type fakeTun struct {
	reads     chan [][]byte
	mu        sync.Mutex
	written   [][]byte
	lastBatch [][]byte
	writes    chan []byte
	closed    bool
	events    chan tun.Event
}

func newFakeTun() *fakeTun {
	return &fakeTun{
		reads:  make(chan [][]byte, 16),
		writes: make(chan []byte, 8192),
		events: make(chan tun.Event),
	}
}

func (f *fakeTun) Read(bufs [][]byte, sizes []int, offset int) (int, error) {
	batch, ok := <-f.reads
	if !ok {
		return 0, os.ErrClosed
	}
	for i, p := range batch {
		sizes[i] = copy(bufs[i][offset:], p)
	}
	return len(batch), nil
}

func (f *fakeTun) Write(bufs [][]byte, offset int) (int, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.closed {
		return 0, os.ErrClosed
	}
	f.lastBatch = bufs
	for _, b := range bufs {
		p := append([]byte(nil), b[offset:]...)
		f.written = append(f.written, p)
		select {
		case f.writes <- p:
		default:
		}
	}
	return len(bufs), nil
}

func (f *fakeTun) Close() error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.closed = true
	return nil
}

func (f *fakeTun) File() *os.File           { return nil }
func (f *fakeTun) MTU() (int, error)        { return 1280, nil }
func (f *fakeTun) Name() (string, error)    { return "tun0", nil }
func (f *fakeTun) Events() <-chan tun.Event { return f.events }
func (f *fakeTun) BatchSize() int           { return 1 }

func (f *fakeTun) writtenCount() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return len(f.written)
}

func (f *fakeTun) waitWrite(t *testing.T) []byte {
	t.Helper()
	select {
	case p := <-f.writes:
		return p
	case <-time.After(2 * time.Second):
		t.Fatal("no packet was written to the inner device")
		return nil
	}
}

// readThrough hands one batch to dev.Read and returns the count, sizes and buffers it produced.
func readThrough(t *testing.T, dev *Device, f *fakeTun, batch ...[]byte) (int, []int, [][]byte) {
	t.Helper()
	bufs := make([][]byte, len(batch))
	for i := range bufs {
		bufs[i] = make([]byte, headroom+65535)
	}
	sizes := make([]int, len(batch))
	f.reads <- batch
	n, err := dev.Read(bufs, sizes, headroom)
	if err != nil {
		t.Fatalf("Read: %v", err)
	}
	return n, sizes, bufs
}
