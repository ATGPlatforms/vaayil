<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# Device checks for the DNS intercept

The `dnsintercept` package and its Kotlin side (`dev.vaayil.wgtunnel.DnsIntercept`, the resolver in
`:core:tunnel-wg`'s `DnsResolver`) run only on a device or emulator: the Go host tests cover the packet filter and
the channel framing, but not the VPN interface, the in-tunnel routing or the app's resolver. These checks cover
that seam. Run them whenever `dnsintercept`, the intercept's JNI surface, the resolver wiring or a re-vendor
changes anything they describe — step 7 of the Re-vendoring list in `README.md` points here.

Everything below uses **disposable scratch profiles**; never edit a profile you use for real work. No check needs
a live WireGuard server: the tunnel carrier is deliberately dead, and only the DNS path is exercised.

## Setup

Build and install the app, with the device on a working network (Wi-Fi or cellular).

Create two scratch profiles in the app's editor. Both have a peer that can never answer (an endpoint in TEST-NET-1,
`192.0.2.1:51820`), because these checks need the tunnel to come up without traffic leaving the device:

- **`intcheck-v4`** — one IPv4 interface address (`10.0.0.2/32`), a plain DNS server (any address), and AllowedIPs
  `0.0.0.0/0, ::/0`.
- **`intcheck-v6`** — one IPv6 interface address (`2001:db8::2/128`, from the reserved documentation prefix), a
  plain DNS server, and AllowedIPs `::/0` only.

The resolver addresses under test are `10.111.222.3` (IPv4) and `fdcb:b743:7f0b::3` (IPv6), the constants
`RESOLVER_V4`/`RESOLVER_V6` in `:core:model`. A profile whose interface keeps an IPv4 address gets
`10.111.222.3` as its only VPN DNS server; an interface with only IPv6 gets `fdcb:b743:7f0b::3`; with both, IPv4
wins. These checks call them **the resolver** below; where a step names one, it means the address the profile
under test got.

Handy on the device: `adb shell` for the commands each item quotes, `dig` or `nslookup` (for example via
termux), `nc` from toybox, `dumpsys meminfo`, and `logcat`. The app logs one runtime-stats line per turn-on and
turn-off: `libwg-go after <connect|disconnect>: goroutines=… heapInuse=… heapReleased=…` under the tag
`WireGuardEngine`.

## Checks

Items 1–4 use `intcheck-v4` unless a step says otherwise; connect the profile before its step and disconnect when
it is done.

1. **Split DNS through the intercept.** The VPN DNS path and the per-domain bypass path must both answer, and each
   must be the one the split-DNS rules say.
   - No bypass rule: `adb shell ping -c1 <name>` where `<name>` exists in public DNS (for example `example.com`)
     fails. The query reaches the resolver (the answer path runs) but the answer can't be fetched through the dead
     peer. This is the tunnel-path control.
   - Add a bypass rule for that name (a rule matches its domain and every subdomain) in the profile's DNS
     settings, reconnect, and repeat: it resolves. The query now goes straight to the underlying network's
     resolver — proxied through the same intercept, which is the point of this pair of checks.
2. **IPv6-only.** Repeat item 1's two steps with `intcheck-v6`. `adb shell ping` and, where available, `ip addr` /
   `ip route show table all` confirm the interface carries only the IPv6 address and that public DNS names resolve
   through `fdcb:b743:7f0b::3`. **Routes follow AllowedIPs:** with `::/0` only, no IPv4 route via
   the tunnel exists (and the user may still have one via the underlying network); a dual-stack profile
   (`0.0.0.0/0, ::/0`) has an IPv4 default route via the tunnel by design. Do not read the presence or absence of
   an IPv4 default route as a result on its own; the DNS family in use is what this check is about.
3. **TCP to the resolver is refused at once.** `adb shell nc -v 10.111.222.3 53` and the same for port `853`, then
   the IPv6 twin `fdcb:b743:7f0b::3` on both ports. Every attempt fails with connection refused
   (`nc` reports "Connection refused" / exit 1), not a timeout — the filter answers a TCP segment to a resolver
   with a reset rather than passing or dropping it. DNS over TCP and DNS over TLS to the resolver address are
   deliberately unsupported.
4. **A big UDP answer survives the intercept.** Answers above 1,400 bytes are the size class that catches
   accidental truncation or a too-small receive buffer.
   - On a machine the device can reach directly (a laptop on the same network is fine), run a small UDP DNS
     responder that answers a TXT query for the name `big.test` with one record padded past 1,400 bytes (over
     1,800 is a good size; the channel's cap is 8,192 bytes). Set it as the profile's DNS server and add a bypass
     rule for `big.test`, so the resolver forwards this name through the underlying network, where the responder
     lives.
   - From the device: `adb shell /data/local/tmp/dig @10.111.222.3 big.test TXT`, or the dig build's path if it
     lives elsewhere (add `+bufsize=4096` to advertise EDNS0 explicitly; any client that advertises it works).
     The full padded TXT record must come back, not a truncated answer and not a timeout. The query's
     destination is the resolver address, so it takes the same divert path as a device-resolved query, and the
     answer travels back through the channel: this proves the channel carries answers of that size.
   - Cross-check through the system resolver: while the bypass rule is on, `adb shell ping big.test` answers
     instead of timing out (or `dig big.test` on the device). The device's own name resolution uses exactly the
     same path — the resolver is only told which upstream answers, never where the query came from.
5. **No UAPI listener.** `adb logcat -d | grep -i uapiopen` after a connect is empty. The vendored library has no
   UAPI code (the CMake and Makefile patches removed the wireguard-tools targets and the socket-directory flag
   that fed it), so any `UAPIOpen` line means the native library was built from unpatched sources or the patch
   was dropped in a re-vendor.
6. **Sixteen connect/disconnect cycles.** With a working network so the tunnel settles CONNECTED each time, run
   16 connect/disconnect cycles and record two numbers after every cycle:
   - `adb shell dumpsys meminfo dev.vaayil.android` — the "Unknown" figure;
   - the `libwg-go after disconnect:` runtime-stats line from logcat (or `adb shell logcat -d -s WireGuardEngine`).

   Targets: goroutines return to the idle baseline after each turn-off, and "Unknown" stays within 15 MB of its
   value after the first connect (measured once, then compared against each cycle). A miss is not a failure by
   itself: record the numbers honestly as a residual and look for a trend (a leak grows cycle after cycle, noise
   wobbles).
7. **Hostname DoH through a working tunnel.** Duplicate `intcheck-v4`, keep the dead TEST-NET peer, set its Secure
   DNS to DoH with a **hostname** endpoint (for example `https://dns.quad9.net/dns-query`), add a bypass rule for
   a well-known name's domain (for example `example.com`), connect, and check both paths:
   - the bypass name resolves (`adb shell ping -c1 <name>`);
   - a name with no bypass rule fails.

   Both results are required: the first says the resolver reached its DoH upstream over the tunnel (a hostname
   endpoint is bootstrapped through the underlying network's resolver first, so the lookup ran too), and the
   second that failures stay failures. Then repeat with the profile's DNS set to DoT and a hostname endpoint (for
   example `dns.quad9.net`). Delete the duplicate afterwards.
8. **Disconnect and delete.** Disconnect the scratch profile, delete it, and confirm the app returns to its normal
   state (no tunnel, no notification). Repeat for `intcheck-v6`.

## Allow local network routes

`VpnRoutes` and the GoBackend loop that reads it also run only on a device. Run these items whenever either
changes, and at every re-vendor. Use `intcheck-v4` (AllowedIPs `0.0.0.0/0, ::/0`) with item 1's bypass rule in
place, so that the resolver intercepts. Read the VPN network's routes from `adb shell dumpsys connectivity` (the VPN
network's `LinkProperties`, its `Routes:` list).

1. **Switch off.** With Allow local network off (the Split Tunnel tab), the tunnel's routes are `0.0.0.0/0`,
   `::/0`, the resolver's host route `10.111.222.3/32` and the host route `10.0.0.2/32` that Android adds for the
   interface address.
2. **Switch on.** Turn it on, and the tunnel restarts. No tunnel route covers `10.0.0.0/8`, `172.16.0.0/12`,
   `192.168.0.0/16`, `169.254.0.0/16`, `224.0.0.0/3`, `fc00::/7`, `fe80::/10` or `ff00::/8`, apart from the host
   routes for the interface address `10.0.0.2/32` and the resolver's `10.111.222.3/32`, plus one for the profile's
   DNS server when its address is local. `100.64.0.0/10` is still covered, by `64.0.0.0/2`. The name from item 1's
   bypass rule still resolves. Turn the switch off again, and item 1's routes come back.

## Known limitations

- **Android 8.x (API 26–27) and answers over 8 KiB.** The DNS channel carries at most 8,192 bytes per answer,
  matching the Android 9+ stub resolver's UDP buffer. Android 8.x's bionic accepts up to 64 KiB, so there an
  answer between 8 KiB and 64 KiB comes back truncated (TC=1) and its TCP retry is reset — TCP to the resolver
  address is refused by design (check 3). One 8,192-byte cap on every API level is a deliberate choice. No check
  has covered Android 8.x devices, and check 4 does not substitute for one; treat the range as untested there.
- **DNS over TCP and TLS to the resolver address** are not implemented; check 3 pins that. Secure DNS upstreams
  (DoH/DoT) are a different feature and ride the tunnel to their own endpoints, which check 7 covers.
- **Strict Private DNS** (Android's own DNS-over-TLS setting with a hostname) sends queries around the in-tunnel
  resolver; that is Android's behaviour and these checks do not change it.
- These checks are written so a desktop can drive them over `adb` and the device's screen is only needed to
  create and connect the scratch profiles. They cover the resolver seam only, not the app's UI.
