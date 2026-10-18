// SPDX-License-Identifier: GPL-3.0-or-later

package dnsintercept

import (
	"regexp"
	"testing"
)

func TestRuntimeStatsIsOneLineOfNumbers(t *testing.T) {
	line := RuntimeStats()
	if !regexp.MustCompile(`^goroutines=\d+ heapInuse=\d+ heapReleased=\d+$`).MatchString(line) {
		t.Fatalf("RuntimeStats() = %q", line)
	}
}
