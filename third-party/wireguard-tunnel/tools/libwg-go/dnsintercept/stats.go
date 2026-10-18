// SPDX-License-Identifier: GPL-3.0-or-later

package dnsintercept

import (
	"fmt"
	"runtime"
)

// RuntimeStats returns one line of numbers for the F31 measurement: goroutines, heap in use, heap released.
// It carries no addresses and no config.
func RuntimeStats() string {
	var m runtime.MemStats
	runtime.ReadMemStats(&m)
	return fmt.Sprintf("goroutines=%d heapInuse=%d heapReleased=%d", runtime.NumGoroutine(), m.HeapInuse, m.HeapReleased)
}
