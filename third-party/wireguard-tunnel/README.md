<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# wireguard-tunnel (vendored)

WireGuard's Android tunnel library, vendored into Vaayil as `:third-party:wireguard-tunnel`. It replaces the Maven
artifact `com.wireguard.android:tunnel`; Vaayil builds its native backend, `libwg-go.so`, from source.

- Upstream: https://git.zx2c4.com/wireguard-android (mirror: https://github.com/WireGuard/wireguard-android),
  directory `tunnel/`.
- Tag `1.0.20260102`, commit `09b75c2bd37f749e2a8c85876394854113c74be7`.
- Licence: mixed. Upstream's code and the patches to it are Apache-2.0, see `LICENSE` (upstream's `COPYING`); Vaayil's
  own files are GPL-3.0-or-later. The Licensing section below says which is which. `libwg-go.so` also links
  wireguard-go (MIT) and the Go standard library, runtime and `golang.org/x` modules (BSD-3-Clause), per each one's
  `LICENSE` file. The app's notices screen lists all of them.

## Kept from upstream

- The Java packages, under their original names, so the JNI symbols and the app's R8 keep rules still match:
  `com.wireguard.android.backend` (Backend, BackendException, GoBackend, Statistics, Tunnel),
  `com.wireguard.android.util.SharedLibraryLoader`, `com.wireguard.config.*`, `com.wireguard.crypto.*` and
  `com.wireguard.util.NonNullForAll`.
- Upstream's `ConfigTest` and `BadConfigExceptionTest`, with their resources.
- `src/main/AndroidManifest.xml`, unchanged.
- `tools/CMakeLists.txt` and `tools/libwg-go/` (api-android.go, jni.c, Makefile, go.mod, go.sum,
  goruntime-boottime-over-monotonic.diff).

Dropped: the root-only backend (`WgQuickBackend`, `RootShell`, `ToolsInstaller`; `RootShell` runs `su`), the GPL-2.0
`wireguard-tools` (its `COPYING`) and the `elf-cleaner` submodules, and `ndk-compat`, which only the wireguard-tools
targets used (upstream `tools/CMakeLists.txt`: only `libwg-quick.so` and `libwg.so` compile `ndk-compat/compat.c`).
This build never produces `libwg.so` or `libwg-quick.so`.

The commit that added this directory holds the kept files byte-for-byte. Every Vaayil change came later, so
`git log -- third-party/wireguard-tunnel` and a diff against that first commit show exactly what Vaayil changed.

## Modified upstream files

Each keeps its SPDX header and carries one `Modified by Vaayil:` line in it.

- `tools/CMakeLists.txt`: builds only `libwg-go.so`; the wireguard-tools and elf-cleaner targets are removed, and so
  is `ANDROID_PACKAGE_NAME`, which only fed the UAPI socket directory (its one use was the Makefile's
  `-X …ipc.socketDirectory` link flag, read by wireguard-go's `ipc/uapi_unix.go` `UAPIOpen`).
- `tools/libwg-go/Makefile`: rebuilds `libwg-go.so` when a Go or C source changes (upstream rebuilt only when
  `go.mod` changed, per its `$(DESTDIR)/libwg-go.so` rule); adds the host-side `test` target (`go vet` and
  `go test -race` for `dnsintercept`, with the host GOOS, GOARCH and C compiler instead of the Android ones); drops
  the `ipc.socketDirectory` link flag; links `libwg-go.so` with GODEBUG `madvdontneed=1` appended to the module's
  default GODEBUG, so freed heap pages go back to the kernel with `MADV_DONTNEED` instead of Android's `MADV_FREE` (the
  rule's comment gives the sources); the libwg-go.so rule also lists the Makefile itself, so a flag or target change
  rebuilds; the test target also covers stackdump.
- `tools/libwg-go/api-android.go`: wraps the tun device with `dnsintercept.Wrap` before `device.NewDevice`;
  `wgTurnOn` returns -2 when no DNS channel was ever opened in the process; the UAPI listener is removed (Vaayil
  reads statistics through `wgGetConfig`, in `GoBackend.java` `getStatistics`); a failed `IpcSet` closes only the
  device, never the descriptor the device owns (wireguard-go's `tun/tun_linux.go` `CreateUnmonitoredTUNFromFD`
  wraps it in the tun's file, which `device.Close` closes through `NativeTun.Close`); `wgTurnOff` runs `runtime.GC()`
  and then `debug.FreeOSMemory()` (`runtime/debug/garbage.go` `FreeOSMemory`: a garbage collection, then as much memory
  as possible back to the OS); the first collection moves the closed device's pooled buffers into `sync.Pool`'s victim
  cache (`sync/pool.go` `poolCleanup`), so the second frees them; new exports `wgOpenDnsChannel` and `wgRuntimeStats`;
  the SIGUSR2 handler logs the full goroutine dump (grown to fit, entries of at most 4,000 bytes split at line ends,
  a header naming the goroutine count) through the new stackdump package.
- `tools/libwg-go/jni.c`: JNI bindings for `dev.vaayil.wgtunnel.DnsIntercept`.
- `src/main/java/com/wireguard/android/backend/GoBackend.java`: a /32 or /128 route to a DNS server that is a
  resolver address of the DNS channel. Resolver addresses are routed into the tunnel, never interface addresses. A
  negative `wgTurnOn` result resets the tunnel handle to -1 before the throw. In `setStateInternal`, the "Tunnel
  already up" guard treats only -1 as no tunnel, and the throw comes before `currentTunnel` is set, so a kept -2
  would turn every later start into a silent no-op.

## Vaayil-owned files

- `build.gradle.kts`: the Android library build (namespace `com.wireguard.android.tunnel`, minSdk 26, compileSdk 36,
  Java 17, NDK 27.0.12077973, CMake 3.22.1, the `libwg-go.so` target only, flexible page sizes for 16 KB devices,
  which the NDK's `build/cmake/flags.cmake` turns into `-z max-page-size=16384` on arm64-v8a and x86_64),
  the `goTest` task, which `check` depends on, and `consumer-rules.pro` for the app's R8.
- `consumer-rules.pro`: keeps `DnsIntercept` and its native methods under the names `jni.c` binds.
- `lint-baseline.xml`: four lint findings in upstream code (NewApi twice, ObsoleteSdkInt,
  UnsafeDynamicallyLoadedCode). Lint fails on anything new.
- `src/main/java/dev/vaayil/wgtunnel/DnsIntercept.java`: the Java surface of the DNS intercept (`open`,
  `isResolver`, `resolverRoutePrefix`, `runtimeStats`), with `src/test/java/dev/vaayil/wgtunnel/DnsInterceptTest.java`.
- `tools/libwg-go/dnsintercept/`: Vaayil's DNS intercept: a tun wrapper that sends DNS queries for the resolver
  addresses over a socket pair to the app and injects the answers, resets TCP to those addresses and drops every
  other packet to or from them; plus `RuntimeStats`. With its host tests, plus host benchmarks for the
  filter's per-packet cost (an ordinary passing packet and a diverted DNS query).
- `tools/libwg-go/stackdump/`: the goroutine-dump formatter the SIGUSR2 handler uses, with its host tests.
- `README.md`: this file.

## Licensing

This module holds two licences:

- **Apache-2.0** covers upstream's code and every file upstream wrote, as vendored in the commit that added this
  directory: the `com.wireguard.*` Java packages, the upstream tests and their resources, `src/main/AndroidManifest.xml`,
  `go.mod`, `go.sum`, `goruntime-boottime-over-monotonic.diff` and upstream's `LICENSE` (also left byte-for-byte). It
  also covers the five files Vaayil patched: `GoBackend.java`, `tools/CMakeLists.txt`, `tools/libwg-go/Makefile`,
  `api-android.go` and `jni.c`. Each of those keeps its Apache-2.0 SPDX header and says what Vaayil changed. The
  patches are deliberately Apache-2.0 so they can be offered upstream and re-applied at the next re-vendor.
- **GPL-3.0-or-later** covers everything Vaayil added: `build.gradle.kts`, `consumer-rules.pro`, the
  `dev.vaayil.wgtunnel` Java files and their test, `tools/libwg-go/dnsintercept/` (code, tests and benchmarks),
  `tools/libwg-go/stackdump/` and this README. Their first line carries `SPDX-License-Identifier: GPL-3.0-or-later`.

`lint-baseline.xml` is Vaayil's too, but it is generated by lint: AGP rewrites it on a baseline update, dropping any
comment, so it carries no SPDX header. It is `GPL-3.0-or-later` like the rest of Vaayil's files here.

A `libwg-go.so` built from this module is a work as a whole under **GPL-3.0-or-later** — it contains Vaayil's
GPL-3.0-or-later code — and the Apache-2.0 parts stay Apache-2.0 inside it. The combined APK follows the app's
GPL-3.0-or-later licence; the notices screen names the components.

## Building

`./gradlew :third-party:wireguard-tunnel:assembleRelease` runs CMake, which runs `tools/libwg-go/Makefile` with the
NDK's `make` (`tools/CMakeLists.txt`, target `libwg-go.so`), once per ABI (arm64-v8a, armeabi-v7a, x86, x86_64: no
`abiFilters` is set, and these are the NDK's default ABIs in its `meta/abis.json`). The Makefile downloads Go 1.24.3
once, checks its sha256, caches it in `$GRADLE_USER_HOME/caches/golang` (rule
`$(GRADLE_USER_HOME)/caches/golang/$(GO_TARBALL)`), applies `goruntime-boottime-over-monotonic.diff` to the Go
runtime (rule `$(BUILDDIR)/go-$(GO_VERSION)/.prepared`), and builds `libwg-go.so`. Go modules come from the module
proxy and are verified against `go.sum` (Go 1.24.3's `go.env` sets `GOPROXY=https://proxy.golang.org,direct`;
`cmd/go/internal/modfetch` `checkModSum`).

`./gradlew :third-party:wireguard-tunnel:goTest` runs `make test` with `BUILDDIR` under `build/go-test`; `check`, and
so `./gradlew build`, depends on it.

Needed on the host: make, curl, flock, tar, patch, sha256sum, a C compiler, and the NDK and CMake:
`sdkmanager --install "ndk;27.0.12077973" "cmake;3.22.1"`.

`libwg-go.so` refuses to start a tunnel until the app has opened the DNS channel with `DnsIntercept.open`.

## Re-vendoring

Go and wireguard-go are bumped only through a re-vendor of a newer upstream tag:

1. Diff the new upstream tag against `1.0.20260102`, for the kept files.
2. Re-apply every change listed under "Modified upstream files".
3. Re-check what `dnsintercept` assumes of wireguard-go: the `tun.Device` interface it implements (`tun/tun.go`),
   that the read loop skips a packet whose size is below 1 (`device/send.go` `RoutineReadFromTUN`), and that reads
   and writes use offset 16 (`MessageTransportHeaderSize` for reads in `RoutineReadFromTUN`,
   `MessageTransportOffsetContent` for writes in `device/receive.go` `RoutineSequentialReceiver`; both in
   `device/noise-protocol.go`).
4. Re-run the Go and JVM tests: `./gradlew :third-party:wireguard-tunnel:build`.
5. Re-check the GODEBUG link flag (F31) on a new Go: `strings -a` on a built `libwg-go.so` must still find
   `madvdontneed=1`. The linker skips a `-X` whose variable does not exist (`cmd/link/internal/ld/data.go`
   `addstrdata`), so a Go that renames `runtime.godebugDefault` drops the setting without an error. The flag is
   needed only while the runtime still turns `madvdontneed` on for GOOS=linux alone (`runtime/runtime1.go`
   `parsedebugvars`).
6. Re-check the service-lifetime assumptions (F7/F32): GoBackend still `stopSelf()`s its service on every
   tunnel-DOWN (`GoBackend.java` `setStateInternal`), and the system's VPN binding still targets `VpnTunnelService`
   (AOSP `Vpn.java` `establish` binds the class `VpnService.Builder` recorded from `VpnService.this.getClass()`).
7. Repeat the device checks in `DEVICE-CHECKS.md`.
