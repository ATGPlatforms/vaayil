/* SPDX-License-Identifier: Apache-2.0
 *
 * Copyright © 2017-2022 Jason A. Donenfeld <Jason@zx2c4.com>. All Rights Reserved.
 * Modified by Vaayil: DNS intercept (dnsintercept wrapper, wgOpenDnsChannel, wgRuntimeStats), SIGUSR2 logs the full goroutine dump via stackdump in logcat-sized entries, UAPI listener removed, IpcSet error path closes only the device, a garbage collection and FreeOSMemory after turn-off.
 */

package main

// #cgo LDFLAGS: -llog
// #include <android/log.h>
import "C"

import (
	"fmt"
	"math"
	"os"
	"os/signal"
	"runtime"
	"runtime/debug"
	"strings"
	"unsafe"

	"golang.org/x/sys/unix"
	"golang.zx2c4.com/wireguard/android/dnsintercept"
	"golang.zx2c4.com/wireguard/android/stackdump"
	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun"
)

type AndroidLogger struct {
	level C.int
	tag   *C.char
}

func cstring(s string) *C.char {
	b, err := unix.BytePtrFromString(s)
	if err != nil {
		b := [1]C.char{}
		return &b[0]
	}
	return (*C.char)(unsafe.Pointer(b))
}

func (l AndroidLogger) Printf(format string, args ...interface{}) {
	C.__android_log_write(l.level, l.tag, cstring(fmt.Sprintf(format, args...)))
}

type TunnelHandle struct {
	device *device.Device
}

// errNoDnsChannel is wgTurnOn's result when no DNS channel was ever opened in this process. GoBackend reports any
// negative result as GO_ACTIVATION_ERROR_CODE (GoBackend.java setStateInternal); this value marks the cause.
const errNoDnsChannel = -2

var tunnelHandles map[int32]TunnelHandle

func init() {
	tunnelHandles = make(map[int32]TunnelHandle)
	signals := make(chan os.Signal)
	signal.Notify(signals, unix.SIGUSR2)
	go func() {
		for range signals {
			// C6 (sweep 2): the full dump, grown to fit, in logcat-sized entries, under the same tag.
			stackdump.Write(func(entry string) {
				C.__android_log_write(C.ANDROID_LOG_ERROR, cstring("WireGuard/GoBackend/Stacktrace"), cstring(entry))
			})
		}
	}()
}

//export wgTurnOn
func wgTurnOn(interfaceName string, tunFd int32, settings string) int32 {
	tag := cstring("WireGuard/GoBackend/" + interfaceName)
	logger := &device.Logger{
		Verbosef: AndroidLogger{level: C.ANDROID_LOG_DEBUG, tag: tag}.Printf,
		Errorf:   AndroidLogger{level: C.ANDROID_LOG_ERROR, tag: tag}.Printf,
	}

	if !dnsintercept.Ready() {
		unix.Close(int(tunFd))
		logger.Errorf("No DNS channel open")
		return errNoDnsChannel
	}

	tun, name, err := tun.CreateUnmonitoredTUNFromFD(int(tunFd))
	if err != nil {
		unix.Close(int(tunFd))
		logger.Errorf("CreateUnmonitoredTUNFromFD: %v", err)
		return -1
	}

	logger.Verbosef("Attaching to interface %v", name)
	device := device.NewDevice(dnsintercept.Wrap(tun), conn.NewStdNetBind(), logger)

	err = device.IpcSet(settings)
	if err != nil {
		device.Close()
		logger.Errorf("IpcSet: %v", err)
		return -1
	}
	device.DisableSomeRoamingForBrokenMobileSemantics()

	err = device.Up()
	if err != nil {
		logger.Errorf("Unable to bring up device: %v", err)
		device.Close()
		return -1
	}
	logger.Verbosef("Device started")

	var i int32
	for i = 0; i < math.MaxInt32; i++ {
		if _, exists := tunnelHandles[i]; !exists {
			break
		}
	}
	if i == math.MaxInt32 {
		logger.Errorf("Unable to find empty handle")
		device.Close()
		return -1
	}
	tunnelHandles[i] = TunnelHandle{device: device}
	return i
}

//export wgTurnOff
func wgTurnOff(tunnelHandle int32) {
	handle, ok := tunnelHandles[tunnelHandle]
	if !ok {
		return
	}
	delete(tunnelHandles, tunnelHandle)
	handle.device.Close()
	// The closed device's pooled message buffers are 65,535 bytes each (wireguard-go device/pools.go
	// PopulatePools, sizes in device/queueconstants_android.go), so each takes 64 KiB of heap. They
	// outlive one garbage collection in sync.Pool's victim cache (sync/pool.go poolCleanup), and
	// FreeOSMemory runs only one (runtime/mheap.go runtime_debug_freeOSMemory). This collection moves
	// them to the victim cache, so FreeOSMemory's own collection frees them and its scavenge returns
	// their pages.
	runtime.GC()
	debug.FreeOSMemory()
}

//export wgGetSocketV4
func wgGetSocketV4(tunnelHandle int32) int32 {
	handle, ok := tunnelHandles[tunnelHandle]
	if !ok {
		return -1
	}
	bind, _ := handle.device.Bind().(conn.PeekLookAtSocketFd)
	if bind == nil {
		return -1
	}
	fd, err := bind.PeekLookAtSocketFd4()
	if err != nil {
		return -1
	}
	return int32(fd)
}

//export wgGetSocketV6
func wgGetSocketV6(tunnelHandle int32) int32 {
	handle, ok := tunnelHandles[tunnelHandle]
	if !ok {
		return -1
	}
	bind, _ := handle.device.Bind().(conn.PeekLookAtSocketFd)
	if bind == nil {
		return -1
	}
	fd, err := bind.PeekLookAtSocketFd6()
	if err != nil {
		return -1
	}
	return int32(fd)
}

//export wgGetConfig
func wgGetConfig(tunnelHandle int32) *C.char {
	handle, ok := tunnelHandles[tunnelHandle]
	if !ok {
		return nil
	}
	settings, err := handle.device.IpcGet()
	if err != nil {
		return nil
	}
	return C.CString(settings)
}

//export wgVersion
func wgVersion() *C.char {
	info, ok := debug.ReadBuildInfo()
	if !ok {
		return C.CString("unknown")
	}
	for _, dep := range info.Deps {
		if dep.Path == "golang.zx2c4.com/wireguard" {
			parts := strings.Split(dep.Version, "-")
			if len(parts) == 3 && len(parts[2]) == 12 {
				return C.CString(parts[2][:7])
			}
			return C.CString(dep.Version)
		}
	}
	return C.CString("unknown")
}

//export wgOpenDnsChannel
func wgOpenDnsChannel(resolvers string) int32 {
	fd, err := dnsintercept.Open(resolvers)
	if err != nil {
		AndroidLogger{level: C.ANDROID_LOG_ERROR, tag: cstring("WireGuard/GoBackend/DnsIntercept")}.Printf("wgOpenDnsChannel: %v", err)
		return -1
	}
	return int32(fd)
}

//export wgRuntimeStats
func wgRuntimeStats() *C.char {
	return C.CString(dnsintercept.RuntimeStats())
}

func main() {}
