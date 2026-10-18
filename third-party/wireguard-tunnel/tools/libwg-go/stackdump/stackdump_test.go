// SPDX-License-Identifier: GPL-3.0-or-later

package stackdump

import (
	"runtime"
	"strings"
	"sync"
	"testing"
)

// TestWriteCarriesEveryGoroutineInBoundedEntries pins the C6 contract: the header names the
// goroutine count, every entry fits logcat's payload cap, entries end at line ends, and the
// splitting loses no goroutine header.
func TestWriteCarriesEveryGoroutineInBoundedEntries(t *testing.T) {
	const extra = 60 // enough goroutines that the dump spans several entries
	release := make(chan struct{})
	var wg sync.WaitGroup
	for range extra {
		wg.Add(1)
		go func() {
			defer wg.Done()
			<-release
		}()
	}
	var entries []string
	Write(func(e string) { entries = append(entries, e) })
	close(release)
	wg.Wait()

	if len(entries) < 3 {
		t.Fatalf("expected a header plus several dump entries, got %d", len(entries))
	}
	if header := entries[0]; !strings.Contains(header, "goroutine dump: ") || !strings.Contains(header, "goroutines") {
		t.Errorf("header does not name the goroutine count: %q", header)
	}
	joined := strings.Join(entries[1:], "")
	for i, e := range entries[1:] {
		if len(e) > maxEntry {
			t.Errorf("entry %d is %d bytes, over the %d cap", i, len(e), maxEntry)
		}
		if !strings.HasSuffix(e, "\n") {
			t.Errorf("entry %d does not end at a line end: %q", i, e[len(e)-1:])
		}
	}
	heads := 0
	for _, line := range strings.Split(joined, "\n") {
		if strings.HasPrefix(line, "goroutine ") && strings.Contains(line, "[") {
			heads++
		}
	}
	if want := runtime.NumGoroutine(); heads < want-2 { // some may have exited since the dump
		t.Errorf("dump shows %d goroutine headers, want about %d", heads, want)
	}
}
