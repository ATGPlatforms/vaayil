// SPDX-License-Identifier: GPL-3.0-or-later

package dnsintercept

import (
	"os"
	"sync/atomic"

	"golang.zx2c4.com/wireguard/tun"
)

// Device is the filter around the tun device wireguard-go reads and writes.
type Device struct {
	inner  tun.Device
	closed atomic.Bool
}

// current is the device answers go to. Wrap sets it; Close clears it if it still points at the closing device.
var current atomic.Pointer[Device]

var _ tun.Device = (*Device)(nil)

// Wrap puts the filter around inner and makes the result the current device. wgTurnOn calls it on every start,
// GoBackend's own restore included (GoBackend.java setState re-ups the old tunnel after a failed switch).
func Wrap(inner tun.Device) *Device {
	d := &Device{inner: inner}
	current.Store(d)
	return d
}

// Read reads from the inner device, then consumes every packet addressed to a resolver address by setting its size
// to 0. It never reorders bufs and returns the inner count unchanged; wireguard-go skips a zero size
// (device/send.go RoutineReadFromTUN).
func (d *Device) Read(bufs [][]byte, sizes []int, offset int) (int, error) {
	n, err := d.inner.Read(bufs, sizes, offset)
	ch := live.Load()
	if ch == nil {
		return n, err
	}
	for i := 0; i < n; i++ {
		if sizes[i] <= 0 {
			continue
		}
		v, seg := classify(bufs[i][offset:offset+sizes[i]], &ch.set)
		switch v {
		case pass:
			continue
		case divert:
			ch.divert(d, seg)
		case reset:
			d.inject(buildReset(seg))
		}
		sizes[i] = 0
	}
	return n, err
}

// Write drops every packet from the peer with a resolver source or destination and writes the rest. With none to
// drop it hands bufs to the inner device as they are.
func (d *Device) Write(bufs [][]byte, offset int) (int, error) {
	ch := live.Load()
	if ch == nil {
		return d.inner.Write(bufs, offset)
	}
	for i := range bufs {
		if ch.set.touches(bufs[i][offset:]) {
			return d.writeKept(ch, bufs, offset, i)
		}
	}
	return d.inner.Write(bufs, offset)
}

func (d *Device) writeKept(ch *channel, bufs [][]byte, offset, first int) (int, error) {
	kept := make([][]byte, 0, len(bufs)-1)
	kept = append(kept, bufs[:first]...)
	for _, b := range bufs[first+1:] {
		if !ch.set.touches(b[offset:]) {
			kept = append(kept, b)
		}
	}
	if len(kept) == 0 {
		return 0, nil
	}
	return d.inner.Write(kept, offset)
}

// inject writes one packet built with headroom straight into the inner device. It never goes through d.Write,
// whose inbound filter would drop a packet from a resolver address.
func (d *Device) inject(pkt []byte) {
	if d.closed.Load() {
		return
	}
	_, _ = d.inner.Write([][]byte{pkt}, headroom)
}

func (d *Device) Close() error {
	if d.closed.CompareAndSwap(false, true) {
		current.CompareAndSwap(d, nil)
		forget(d)
	}
	return d.inner.Close()
}

func (d *Device) File() *os.File           { return d.inner.File() }
func (d *Device) MTU() (int, error)        { return d.inner.MTU() }
func (d *Device) Name() (string, error)    { return d.inner.Name() }
func (d *Device) Events() <-chan tun.Event { return d.inner.Events() }
func (d *Device) BatchSize() int           { return d.inner.BatchSize() }
