// SPDX-License-Identifier: GPL-3.0-or-later

// Package stackdump formats a full goroutine dump into logcat-sized entries (sweep 2, C6).
//
// The old SIGUSR2 handler in api-android.go copied runtime.Stack into one page and logged it as a
// single __android_log_write call, so a dump of hundreds of goroutines showed one or two of them.
// This package grows the buffer until the whole dump fits (up to maxDump), writes a header naming
// the goroutine count, and hands the sink entries of at most maxEntry bytes, each split at a line
// end, in dump order.
package stackdump

import (
	"fmt"
	"runtime"
	"strings"
)

// maxEntry stays under __android_log_write's ~4 KiB payload budget (system/liblog writes one
// entry per call). maxDump caps the buffer growth: 4 MiB is thousands of goroutines.
const (
	maxEntry = 4000
	maxDump  = 4 << 20
)

// Write captures the goroutine dump and hands sink the header line, then the dump, entry by entry.
func Write(sink func(entry string)) {
	n := runtime.NumGoroutine()
	buf := make([]byte, 1<<12)
	for {
		written := runtime.Stack(buf, true)
		if written < len(buf) || len(buf) >= maxDump {
			buf = buf[:written]
			break
		}
		buf = make([]byte, len(buf)*2)
	}
	sink(fmt.Sprintf("goroutine dump: %d goroutines, %d bytes", n, len(buf)))
	rest := string(buf)
	for len(rest) > 0 {
		entry := rest
		if len(entry) > maxEntry {
			entry = entry[:maxEntry]
			if i := strings.LastIndexByte(entry, '\n'); i >= 0 {
				entry = entry[:i+1]
			}
		}
		sink(entry)
		rest = rest[len(entry):]
	}
}
