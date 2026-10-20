# AGENTS.md — working rules for AI agents on Vaayil

Vaayil is a privacy-first **WireGuard VPN client for Android** (phone + tablet; TV later).
It is free software under **GPL-3.0-or-later**, with **all features available free,
without a time limit or in-app purchases**, and it is published on F-Droid, Google Play
and GitHub Releases as each goes live. All connections still use the single
`EngineProvider.connectById` funnel; the domain core stays platform-free for fast JVM
tests and clean seams. Profiles arrive only by .conf import, QR scan, or manual entry.
The UI is the **Vellum** design system: light-only, Schibsted Grotesk, bottom-nav tabs
(Dashboard / Profiles / Split Tunnel / Backup), hero connect disc, passphrase-encrypted
.vylconf backup tab (format v5, `BackupCrypto`).

Comments and these rules cite finding IDs (such as C4 or F32) and design-spec sections (such as spec §3.7) from the maintainer's private records. The text around each tag carries the reasoning; you don't need the records to work on the code.

The maintainer's private docs live in the companion repo `vaayil-dev`, checked out next to this one at `../vaayil-dev`. Write specs, plans and issue records there, never here; read its `STATUS.md` first when it is present.

## Hard rules (violations have caused rework — do not test them)

- **Toolchain is user-local.** There is no system java. Every gradle invocation needs:
  `export JAVA_HOME=$HOME/.local/opt/jdk-17.0.20.1+1 ANDROID_HOME=$HOME/.local/opt/android-sdk`
  and `export PATH=$JAVA_HOME/bin:$PATH`; on any other machine, point
  `JAVA_HOME` and `ANDROID_HOME` at your own JDK 17 and SDK instead.
  Gradle 8.13 wrapper; JDK 17 toolchain; minSdk 26,
  compile/targetSdk 36. The vendored `:third-party:wireguard-tunnel` also needs NDK 27.0.12077973
  and CMake 3.22.1 in the SDK, plus `make` and a host `cc` (its `goTest` runs `go test -race`).
  Its first build downloads Go 1.24.3 into the Gradle user home, sha256-checked (Makefile
  `GO_HASH_*`), and the first native build takes several minutes.
- **Worktrees:** feature work happens in `.worktrees/<branch>` via `git worktree add`. Always
  use ABSOLUTE paths under the worktree — five separate agents have dirtied the main checkout
  with relative-path edits. Verify `git -C <main checkout> status --short` is empty
  before pushing.
- **Control plane:** all tunnel connect/disconnect goes through `VpnTunnelService` intents
  (`ACTION_CONNECT`/`ACTION_DISCONNECT` + `EXTRA_PROFILE_ID`). Nothing outside
  `VpnTunnelService`/`EngineProvider` touches `WireGuardEngine`. Background senders
  (connect-on-boot, the guard service, Tasker) issue those intents only through
  `BackgroundTunnelCommands` (`:core:automation`, B1): a CONNECT starts the service only with VPN
  consent (`VpnService.prepare() == null`), and without it posts "Vaayil couldn't connect" /
  "VPN permission is off. Open Vaayil and connect once to allow it." in the tunnel
  notification's slot (42, the "VPN connection" channel); a DISCONNECT first bars the crash resume
  (`CrashResumeGate.suppress()`, revoke included), clears ACTIVE (LAST kept) and joins a resume the service already
  accepted, then starts the service only while a tunnel is live (`EngineProvider.isTunnelLive()`). On API 34+ a
  `VpnTunnelService` start without consent crashes the
  process (its `systemExempted` type needs the VPN app-op, TUN-1); with consent, a VPN app is
  exempt from Android 12+'s background FGS-start limits (`REASON_OP_ACTIVATE_VPN`, B6).
- **Module purity:** `:core:model`, `:core:config`, `:core:tunnel-api`, `:core:dns`
  must not import `android.*`/`androidx.*`. The reviewed dependency graph is in the
  Module map section; respect it when adding imports between modules.
- **Dependencies:** Apache-2.0 / MIT / BSD-class only (spec §16). Current set: minidns-core,
  material-icons-extended (BOM-pinned), Room, DataStore, OkHttp, ZXing, wireguard-android's
  `tunnel` module (vendored as `:third-party:wireguard-tunnel` at tag 1.0.20260102; its
  upstream code and Vaayil's patches to it stay Apache-2.0, as do its androidx.annotation,
  androidx.collection and compile-only jsr305; Vaayil's own files in that module are
  GPL-3.0-or-later),
  AboutLibraries 12.2.4
  (notices screen, Apache-2.0), Schibsted Grotesk (OFL, bundled in `core/ui/src/main/res/font`,
  notice in `docs/fonts-schibsted-grotesk.md`). minidns-core is consumed under its Apache-2.0
  option (election recorded here, B-7). Vaayil builds `libwg-go.so` (wireguard-go, MIT) from
  source; it is the only native from wireguard-android that ships, next to androidx's
  `libandroidx.graphics.path.so` and `libdatastore_shared_counter.so`. The Go standard library
  and `golang.org/x/crypto`, `x/net` and `x/sys` compiled into it (BSD-3-Clause) have their own
  notice (`app/config/libraries/go.json`). The vendored build never produces wireguard-tools'
  GPL-2.0 binaries (`libwg.so`, `libwg-quick.so`; `wg-quick/android.c` is GPL-2.0 only), and the
  packaging excludes in `app/build.gradle.kts` stay as a guard. The notices (C10): AboutLibraries'
  detected `androidx.datastore:datastore-preferences-external-protobuf` entry is excluded
  (`exclusionPatterns` in `app/build.gradle.kts`) and replaced by a manual definition in
  `app/config/libraries/`, because the plugin's merge appends licences. OkHttp's bundled Public
  Suffix List data (`okhttp3/internal/publicsuffix/publicsuffixes.gz`, MPL-2.0, from
  publicsuffix.org) ships in the APK with its own notice, and the WireGuard entries carry
  "Copyright © WireGuard LLC". A dependency bump must keep `AboutLibrariesContentTest` green.
- **Commits:** conventional commits (`feat:`, `fix:`, `test:`, `chore:`, `docs:`). Every
  touched module must pass `./gradlew :module:build` before its commit.
- **DCO:** every commit carries its author's `Signed-off-by:` (`git commit -s`),
  and agent-made commits keep the AI co-author trailer too.
- **Private material:** never copy the maintainer's private docs or their content
  into this repo: specs, plans, issue records, test and device records, network
  details.

## Module map

```
core/model        pure domain: Profile, DnsPolicy, AutoConnectRules, local-network
                  routes, validation                                              (pure JVM)
core/config       wg-quick parse/serialize                                        (pure JVM)
core/tunnel-api   TunnelEngine seam + status/stats/error types, handshakeState    (pure JVM)
core/dns          in-tunnel resolver: rule matcher, upstreams (plain/DoT/DoH),
                  TTL cache, DnsResponder (the answer path), FramedDnsTransport
                  (the DNS channel's framing); LocalDnsServer is the JVM tests'
                  socket harness since B1                                         (pure JVM)
core/data         Room + DataStore: ProfileRepository, ActiveProfileStore,
                  AutomationRulesStore, BackupCodec
core/tunnel-wg    WireGuardEngine over the vendored GoBackend, VpnTunnelService
                  seam, DnsResolverController, ResolverChannel, StatsSampler      (Android lib)
core/automation   SsidWatcher, AutoConnectService (specialUse FGS), BootReceiver,
                  AutomationReceiver (Tasker)                                     (Android lib)
core/ui           Vellum primitives shared by all features: Sheet, StatusPill,
                  MicroBadge, vellumFieldColors, Sparkline,
                  VaayilLogoMark                                                (Android lib)
feature/*         Compose UI: profiles (profiles tab w/ connect switches + QR share +
                  conf export, split-tunnel tab, editor w/ keygen, import),
                  tunnel (dashboard hero + telemetry), settings (automation,
                  backup/restore, onboarding, kill-switch guide)
app               single APK: MainActivity, RootNav (bottom-nav tabs + pushed routes),
                  VellumTopBar/VellumBottomBar, QR dialog, AppContainer, theme, screenshots
third-party/wireguard-tunnel
                  wireguard-android's tunnel module, vendored at 1.0.20260102:
                  GoBackend (+ resolver host route, handle reset, VpnRoutes
                  hand-over), DnsIntercept,
                  libwg-go.so built from source with the dnsintercept filter      (Android lib)
```

Reviewed dependency edges, taken from every module's `build.gradle.kts`
(`implementation` unless noted):

```
core/config, core/dns (api), core/tunnel-api -> core/model
core/data -> core/model, core/config
core/tunnel-wg -> core/model, core/data, core/tunnel-api, core/dns, third-party/wireguard-tunnel
core/automation -> core/model, core/data, core/tunnel-api, core/tunnel-wg
core/ui, third-party/wireguard-tunnel -> no Vaayil module
feature/profiles -> core/model, core/ui, core/tunnel-api, core/data, third-party/wireguard-tunnel (its `com.wireguard.crypto` package only)
feature/tunnel -> core/model, core/ui, core/tunnel-api, core/data
feature/settings -> core/model, core/ui, core/tunnel-api, core/data
app -> core/model, core/ui, core/config, core/tunnel-api, core/data, core/automation, core/tunnel-wg, third-party/wireguard-tunnel, feature/profiles, feature/tunnel, feature/settings
```

`:app` does not declare `:core:dns` directly; it reaches it only through
`:core:tunnel-wg`.

## Verification

- Full gate (what CI runs): `./gradlew build` then `./gradlew :app:verifyRoborazziDebug`
  (the second step is CI-exclusive — `build` alone never compares screenshots). `build`
  includes `:third-party:wireguard-tunnel:goTest` (go vet and go test -race on libwg-go's
  `dnsintercept` package).
- Focused: `./gradlew :module:test` (JVM modules) / `:module:testDebugUnitTest` (Android
  modules; the bare `test` filter task does not exist there).
- Socket tests bind ephemeral dual-family loopback ports — never add fixed ports (this flaked
  CI; D5). DNS servers under test must set `SO_REUSEADDR` and bind
  unbound sockets.
- TDD is the norm: failing test first (RED), minimal implementation (GREEN), evidence quoted.
- When CI can't run (CI-1), the evidence for a merge is both gate steps run locally on the
  merged tree; once CI can run again, rerun it on the pushed head.

## Architecture invariants worth knowing before editing

- `VpnService.protect()` is UID-scoped (AOSP `Vpn.protect` checks caller UID == VPN owner):
  bootstrap and bypass sockets are protected via the app's own `VpnTunnelService` instance
  through `EngineProvider.protectSocket`/`protectDatagramSocket`. Bootstrap and bypass
  sockets must be protected; tunnel-path and secure-DNS sockets must not be.
- Two reserved resolver addresses (B1): `10.111.222.3` and the ULA `fdcb:b743:7f0b::3`
  (`RESOLVER_V4`/`RESOLVER_V6`, `Model.kt`). Neither is ever an interface address, and validation
  rejects both as tunnel addresses and as DNS servers. An intercepting profile's only VPN DNS
  server is the resolver of a family its interface keeps (IPv4 first), and the vendored GoBackend
  adds a host route for it into the tun. libwg-go's `dnsintercept` filter diverts UDP port 53 for
  either address over a SOCK_SEQPACKET socket pair (`DnsIntercept.open`) to `ResolverChannel`,
  `FramedDnsTransport` and `DnsResponder`. It resets TCP to them and drops everything else to or
  from them, the peer's packets included. Nothing binds port 53, and only `EngineProvider`'s
  lambdas call `DnsIntercept`. Every start, plain ones included, ensures the process-wide channel
  before `setState`, because `wgTurnOn` refuses to start in a process that never opened one. A
  dead channel, while a session is current, is reopened in place by the controller through the opener it
  kept (C3): the first reopen runs at once, the second after 1 s and the third after 2 s; a reopen that
  throws, or a new channel that dies again, counts as one attempt; the count resets at every start and
  after a channel stayed up 60 s, and a channel that a newer one replaced is never reopened. After three
  failed attempts the session carries a `DnsNotAnswering` notice until the next start or a disconnect,
  queries staying dropped (fail closed).
- A resolver session does no tunnel-path network I/O once it may no longer answer (A1). Each
  session owns the Job its answers run under (`FramedDnsTransport` launches into it). `stage()`
  quiesces every session, so nothing answers, cached and bypass queries included, until `commit()`
  or `rollback()`; `deactivate()` cancels and closes them all. An upstream's `close()` is final: no
  socket, send, dial, reconnect or rebuild after it. `VpnTunnelService.onRevoke` calls the
  mutex-free `WireGuardEngine.deactivateResolver()` before it posts the teardown, and the
  controller's epoch ticket stops a start that was building its session from staging it after a
  revoke. Two windows remain open (A1): the gap between the VPN network going away and
  `onRevoke` arriving, and `GoBackend.onDestroy`, which runs `wgTurnOff` just
  before its DOWN callback deactivates the resolver. DoH never uses a proxy: its client is
  built with `Proxy.NO_PROXY` (A2).
- A CONNECTED status may carry a notice (`TunnelStatus.notice`, `TunnelNotice` in `core/tunnel-api`):
  `SettingsNotApplied` — C2's refused same-profile restart, the dashboard note "New settings aren't
  applied yet. Reconnect to apply them." until the next connect or a disconnect — and `DnsNotAnswering`
  — C3's give-up, "DNS isn't answering. Reconnect to fix it.". A notice never rides `error`, and the
  dashboard shows it under the disc's hint line while CONNECTED (U4).
- Sage needs a verified handshake from THIS tunnel (G4).
  - `TunnelStatus` and `TunnelStats` carry the start's `generation`.
  - `handshakeState` (`:core:tunnel-api`) reads PENDING, the amber "Connecting" presentation, until a handshake of the
    same generation arrives. It reads WAITING after U3's 10 s plus its settle, and VERIFIED once the handshake comes.
  - A state not yet known reads PENDING: it fails closed.
  - It runs once for the process, in `ConnectionRegistry.bridge` (`ConnectionRegistry.handshake`, J13), and every
    surface reads that one result: the dashboard, the Profiles tab and the tunnel notification. While CONNECTED, the
    notification reads "Vaayil is connecting" until VERIFIED, "Vaayil is connected" at VERIFIED and "Vaayil is
    waiting for a handshake" at WAITING.
  - The sampler polls every second until the first handshake, for at most 30 s.
- Hostname DoH/DoT endpoints are bootstrap-resolved through the underlying network's resolver at
  tunnel start and pinned; a failed query on the secure channel (a network switch is the usual
  cause) rebuilds it, which re-runs the lookup (`ReprobingUpstream`, wrapped around the secure
  upstream in `DnsResolver.kt`). The editor's hint says so (C9): "A hostname DNS server is looked up
  through your current network's resolver when the tunnel comes up, and again after a network change
  or a failure." Secure DNS rides the tunnel; only bootstrap and bypass sockets are protected.
- "The underlying network" is ONE network (K2, S10): `selectUnderlying` (`:core:tunnel-wg`
  `UnderlyingNetwork.kt`) picks among non-VPN networks with INTERNET, FOREGROUND and
  NOT_RESTRICTED, VALIDATED first, then Ethernet > Wi-Fi > cellular. Only its DNS servers are
  used, and every bootstrap, bypass and switch pre-check socket is protected AND bound to it with
  `Network.bindSocket`; a failed bind skips that attempt. Background and restricted networks are
  excluded because binding to them needs permissions Vaayil does not hold (EACCES, per AOSP
  source; no check on a device has confirmed it).
- Phase-1 fail-closed semantics: disconnect and revoke clear the ACTIVE profile record (before the
  engine stop and again after it); `lastConnectedProfileId` is the only pointer that survives a
  disconnect (used by auto-connect/boot). Neither pointer outlives its profile (B3): every automation
  target lookup runs `ActiveProfileStore.reconcileProfilePointers(storedIds)`, which clears a pointer
  no stored row carries (deleted, or re-imported under a new id) while a row that merely can't be
  decrypted keeps its pointer; `resolveAutomationTarget` then picks ACTIVE, else last-connected,
  else the newest readable profile (the dashboard disc's order). ACTIVE carries the boot it was written in (`Settings.Global.BOOT_COUNT`). At
  every process start `EngineProvider.init` resumes a record from THIS boot exactly once — the
  previous process died with that tunnel up (F32) — clearing it before it sends `ACTION_CONNECT`, so
  only a fresh STARTED re-arms it. A record from an earlier boot, or an unstamped one, is cleared
  without a resume: reconnecting after a reboot is connect-on-boot's decision alone. The resume
  carries `EXTRA_RESUME` and yields: `VpnTunnelService` drops it when the process already handled
  any other start, it is skipped while another VPN network is up (`prepare()` would take that VPN
  over) and, checked after that, while Vaayil holds no VPN consent (A5; logged, the record staying
  consumed), and a second resume of the same profile within 60 s in the same boot is cleared instead
  (loop-breaker; any switch or Disconnect resets it). The resume also yields to a DISCONNECT or
  revoke at its last check before the engine (`CrashResumeGate`, which replaced `explicitStartSeen`):
  a barred resume is REFUSED there, and a disconnect joins a resume already past that check and stops
  it as it settles. Connect-on-boot runs once per device boot: every BOOT_COMPLETED stamps
  `Settings.Global.BOOT_COUNT` first (`ActiveProfileStore.claimBoot()`, key `boot_connect_handled_boot`),
  and `BootReceiver` connects only for a NEW_BOOT or UNKNOWN claim — an unknown boot number or a
  throwing claim connects as before — while `MY_PACKAGE_REPLACED` never claims.
- Split-DNS "bypass" is resolver-selection only, not IP routing (Android fixes routes at
  establishment). Editor copy must keep saying so.
- Lockdown blocks apps' connections, not their DNS lookups (J10): with Always-on VPN and "Block
  connections without VPN" on, Android still sends getaddrinfo lookups to the underlying network's DNS server while
  Vaayil's tunnel is down (after a Disconnect, in a switch's or a restart's gap) and, with the tunnel up, for apps
  excluded in Split Tunnel. User-facing copy says "connections", never "traffic" or "leak-free", and the kill-switch
  guide names the DNS exception (`KillSwitchGuideScreen`, Settings' kill-switch row). A profile whose VPN network
  would carry no DNS server sends every app's lookups outside the tunnel while connected (J12): no DNS server in a
  family its Allowed IPs route, no Secure DNS in effect and no usable split-DNS rules (`DnsPlanner`'s rule).
  `Profile.warnings()` then says "DNS lookups will bypass this tunnel. Add a DNS server to keep them in." when the
  profile reads as a tunnel; it never blocks Save, and Vaayil does not fail such a profile closed.
- The Wi-Fi guard needs background location ("Allow all the time") — SSID access is redacted
  without it, and without it the rules see no SSID at all (`ssidForRules`), so they are paused.
  The disclosure (`LocationDisclosureDialog`, "Wi-Fi network access") is Vaayil's own copy written
  to Play's prominent-disclosure requirements: it says what is read (the network's name), why
  (trusted versus untrusted Wi-Fi), that the guard runs in the background and so needs "Allow all
  the time", and that the name stays on the device. Play publishes no text to copy, so it is not
  verbatim. Its Continue requests
  FINE and COARSE together, then background location only once FINE is held
  (`:app` `LocationPermissionRequest.kt`, C1). `PermissionState` (`:feature:settings`
  `LocationPermission.kt`) calls COARSE
  alone APPROXIMATE_ONLY: the Settings card says "Vaayil needs precise location to see the Wi-Fi
  name. Turn on Use precise location for Vaayil." with "Open location settings", which opens
  Vaayil's app details. Every grant held but the Location switch off is LOCATION_OFF, API 27+ only
  (C2): "Location is off, so the Wi-Fi rules are paused. Turn it on so Vaayil can see which Wi-Fi
  you're on." with "Open Location settings", which opens `ACTION_LOCATION_SOURCE_SETTINGS`. The
  card recomputes both on resume. With the Location
  switch off, API 27+ hides the SSID even with every grant (API 31+ sanitises `transportInfo`;
  8.1's `WifiStateMachine.syncRequestConnectionInfo` gates it on `canAccessScanResults`, which
  needs Location on; 8.0 redacts only the MAC), so the Wi-Fi rules pause and notification 43 names
  the first missing piece: background location, precise location, then Location on (B2; API 26's
  copy is unchanged). While it runs, `AutoConnectService` holds Vaayil's only runtime-registered
  receiver: NOT_EXPORTED, for `LocationManager.MODE_CHANGED_ACTION` (`ContextCompat.registerReceiver`,
  `AutoConnectService.kt:235`), unregistered once at teardown. A change re-registers the Wi-Fi
  watcher, so turning Location on re-evaluates the current network at once. Below API 33,
  NOT_EXPORTED relies on the generated `dev.vaayil.android.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`
  in the merged manifest. A network is trusted only when its SSID is
  on the list AND it is SECURED (S4, `AutoConnectRules.decide` with `WifiSecurity`): OPEN (no
  password, OWE, WEP) and UNKNOWN never count, so the tunnel stays up. The type comes from
  `WifiInfo.getCurrentSecurityType()` on API 31+ and from the connected BSSID's scan result below
  that (remembered per BSSID; needs `ACCESS_WIFI_STATE`). Every app resume sends
  `AutoConnectService` its start command while a guard rule is on (`GuardServiceResumeEffect`),
  and the service re-checks the background-location grant on each start (M5). `DataGraph` (core/data) is
  the only constructor of the database, at-rest crypto and repository — AppContainer and
  every receiver go through it; a second Room instance is a bug.
- `WireGuardKeyring` (`:feature:profiles`) uses the vendored tunnel module's pure-Java crypto
  package (`com.wireguard.crypto`) for Curve25519 key generation and public-key derivation. That
  is NOT engine access — the control-plane rule above still applies to tunnel start/stop. Don't
  "fix" it into :core.
- Split-tunnel rules are app-wide, not per profile: one `SplitTunnelPolicy` lives in
  `:core:data`'s `SplitTunnelRulesStore` and applies to whichever profile is connected;
  the engine resolves it fresh at every start via the `EngineProvider.init` lambda. Saves
  debounce 500 ms in the Split Tunnel VM and route through the :app `saveSplitTunnelRules`
  hook, which restarts the tunnel when one is up so policy applies immediately (spec §11).
  That save, and a profile edit (`restartIfConnectedProfileChanged`), go through
  `SettingsRestartCoordinator` on `AppContainer`'s app-lifetime scope (C4): a save while the
  profile is CONNECTED restarts at once; one while it is CONNECTING leaves one pending request
  (saves coalesce), which restarts once if that start settles CONNECTED for the same profile and
  is consumed without a restart by FAILED, DISCONNECTED, DISCONNECTING or another profile.
- Telemetry is totals-only (`TunnelStats` = rx/tx bytes + handshake time). The dashboard
  shows session totals honestly — never invent rates, latency, or "fastest" badges.
- `EngineProvider.connectById` returns a `StartOutcome`: the outcome of its own start, captured
  under the engine lock (`WireGuardEngine.startWithOutcome`, A6), never a re-read of the shared
  status that a queued start could overwrite. Only STARTED updates the active and last-connected
  records. A failed switch keeps the previous tunnel and its pointers. A switch away
  from a live tunnel first resolves the target's endpoint hostname on the underlying network (the
  D1 bootstrap path) and is refused before the backend is touched when it does not resolve (F33);
  IP literals and fresh connects skip that lookup. `VpnTunnelService` never keeps a start request
  of its own: every connect start returns START_NOT_STICKY and calls `stopSelf(startId)` once it
  settles, whatever the outcome. A live tunnel is then held by the system's VPN binding alone (plus
  `ServiceHold` during `setState`), so a process death leaves no start request behind and takes its
  notification with it (F32). With nothing live the instance stops, and it never tears the tunnel
  down.
- A FAILED connect to profile B while profile A's tunnel stays live is shown, never silent (K3):
  `VpnTunnelService` reports it on `ConnectionRegistry.switchFailures` (process-scope, buffered,
  never replayed), and `:app`'s `SwitchFailureSnackbar` says "Couldn't switch to {B}. Still
  connected to {A}." (F33's lookup refusal adds ": its server name didn't resolve"). REFUSED and a
  fresh connect's FAILED (the dashboard shows it) get no snackbar. A same-profile restart whose new
  settings can't apply (C2) does: "Couldn't apply the new settings to {profile}: {reason}. It's still
  running the previous ones." — reasons "its DNS server name didn't resolve", "the DNS resolver
  timed out", "its DNS settings could not start" — and the session carries the dashboard note
  "New settings aren't applied yet. Reconnect to apply them." until the next connect or a
  disconnect. These two, the delete/undo/restore snackbars (U1), the VPN-consent snackbar (C8) and
  the camera-denial snackbar (C6) use `SnackbarDuration.Long`. Other short notices stay Short,
  among them the import and QR-parse failures (c6; the pair is a recorded inconsistency, kept Short).
- The tunnel status outlives service instances (F7): the engine→registry bridge
  (`ConnectionRegistry.bridge`) starts once in `EngineProvider.init` and lives for the process,
  and `WireGuardEngine.start` holds a `BIND_AUTO_CREATE` binding to `VpnTunnelService`
  (`ServiceHold`) across every GoBackend `setState`, because GoBackend `stopSelf()`s the service
  on every tunnel-DOWN, including the DOWN inside a switch. Neither moves back onto a service
  instance; a dying instance asks the engine to `reconcile()`. On every re-vendor of the tunnel module
  (`third-party/wireguard-tunnel/README.md`, Re-vendoring), re-check `ServiceHold`'s assumptions and
  that the system's VPN binding still targets `VpnTunnelService` (F32's start-request release relies
  on that binding holding a live tunnel).
- The automation foreground service runs while EITHER rule is on (`AutoConnectRules.needsGuardService`):
  boot, app update (`MY_PACKAGE_REPLACED`), cold start and both Settings toggles go through that
  predicate.
- An inclusive split-tunnel policy with no installed app is never applied: the VM refuses to save
  it and `SplitTunnelResolver` returns no restriction (everything tunnels).
- The local-network switch is app-wide, as `SplitTunnelSelections.allowLocalNetwork`, stored with
  the split-tunnel rules and in backups (additive, still version 4). When it is on,
  `WgConfigMapper.vpnRoutes` (`localNetworkRoutes` and `LOCAL_NETWORK_RANGES` in `:core:model`)
  hands GoBackend, through `dev.vaayil.wgtunnel.VpnRoutes`, the AllowedIPs with the local ranges
  cut out of every entry that covers them; an entry inside a local range stays routed. It keeps
  each interface subnet (for a subnet wider than a local range, the local ranges inside it), and a
  host route for each DNS server (the profile's, plus the secure server's literal or pinned
  addresses), wherever a trimmed entry covered them; GoBackend still adds the resolver host routes.
  The AllowedIPs, the peer config and the exported `.conf` never change. Both family rules read the
  AllowedIPs, so an IPv4-only profile keeps IPv6 blocked.
  The routes are fixed when the tunnel comes up: after a network change, a secure server
  re-resolved to a different local address skips the tunnel, still encrypted, until the next
  connect. A flip saves even while an empty Inclusive list holds the app rules back, and it
  restarts the tunnel like any rule change. Under Always-on with Block connections without VPN,
  Android blocks the connections that skip the tunnel (API 34 and 36 checked).
- Every Profile id is validated (`[A-Za-z0-9_-]{1,64}`) and Uri-encoded into routes; restore
  normalises legacy fields and confirms once before writing in one transaction. Export writes only
  the profiles it can read and says how many (C9, `BackupViewModel.reportExport`): "Encrypted
  snapshot written: N profiles." or "Wrote N of M profiles. K couldn't be read and were left out."
  (singular forms in the code).
- Navigation (C3): every push goes through `navigatePush` (`launchSingleTop`; single-top matches
  the destination, not its arguments; two quick pushes can reuse one entry, c1).
  Back and up on pushed routes act only while their entry is
  RESUMED (`dropUnlessResumed`) and pop through `popIfNotRoot`, which never pops the start
  destination; the editor's save completion leaves through `dropUnlessStarted`.
- The dashboard's pick (F23, J14): the disc acts on the profile picked in the pill, kept in the tab's saved state
  (`rememberDashboardPick`, `:feature:tunnel`). When the ACTIVE profile changes to one that isn't the pick (a connect
  from the Profiles tab, the notification, automation or boot), the pick is dropped and the dashboard shows the live
  profile; a disconnect, a failed switch, a restart of the same profile and a switch to the pick keep it
  (`pickAfterLiveChange`).
- The manual-connect gate (C7, C8): `rememberManualConnectionGate` (`:app` `Permission.kt`) runs VPN
  consent first, then, on API 33+, the one notification ask, then the connect. The ask is claimed
  in one `vaayil_flags` edit (`AppContainer.claimNotificationPermissionRequest`, key
  `notificationsAsked`) before its dialog launches, so onboarding and the gate ask once in total,
  two system dialogs never stack, and a skipped onboarding still asks; a notification denial still
  connects. A tap on the live profile (the disconnect or cancel) runs at once, taps while a consent
  dialog is up are ignored, and a null target does nothing. A refused consent shows "Vaayil didn't
  get VPN permission. If another app is set as Always-on VPN, turn that off first." (Long); the
  dashboard's label is "VPN permission is off".
- QR (C5, C6): Share QR keeps its secure window (FLAG_SECURE) and shows "This profile is too large
  for a QR code. Use Export .conf instead." when the config is past QR capacity, instead of
  crashing. Both scan sites use `wireGuardScanOptions()` (`:app` `QrScan.kt`), which turns ZXing's
  missing-permission dialog off, and `handleQrScanResult` turns a missing camera permission into
  "Vaayil needs camera access to scan a QR code." (Long).
- Editor drafts live in memory only (F30): `AppContainer.editorDrafts`, keyed by the editor's
  back-stack entry id and written on every edit, survives activity recreation; import drafts
  wait in `pendingDrafts`. Drafts hold private keys, so they NEVER go into a saved-state Bundle;
  a process death loses them by design (an orphaned import review says so, M3), and all three
  maps are dropped when the activity finishes for good.
- The main window is out of autofill (L1): `MainActivity` sets `IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS`
  on its decor view (`Window.excludeFromAutofill`), so an automatic autofill request sees none of its views and
  no service offers to save the editor's keys as a password. Compose's root opts itself in
  (`IMPORTANT_FOR_AUTOFILL_YES`); an ancestor's exclusion overrides that. Dialogs are their own windows and stay
  fillable (the backup passphrase); a fill the user asks for by hand still reaches the service.
- The at-rest crypto classifies its own failures: only `AtRestCorruptException` means a row is
  unreadable; anything else drops the cached key and reports unavailable. Reading and generating
  the key are separate (`AtRestKeyRecovery`, B4). A Keystore read that throws is an outage, never
  an absence. An absent key with no `enc1:` value stored is generated; with encrypted values
  present (a raw read-only probe of `vaayil.db` that counts an unreadable file as holding some), it
  is replaced only after at least 20 absent reads spanning at least 600 s with no successful read
  between. That evidence lives in `noBackupFilesDir`, spans restarts, and any successful read
  clears it. Meanwhile encryption refuses (`AtRestUnavailableException`), and once refused,
  plaintext stays refused for the process (`plaintextRefused`).
- The three core Preferences stores (`vaayil_settings`, `vaayil_automation`, `vaayil_split_tunnel`)
  reset a corrupt file to defaults through `ReplaceFileCorruptionHandler`, with one WARN, instead of
  crash-looping every start (B5): pointers cleared, automation off, the external-control token
  gone, both split-tunnel lists empty, Allow local network off. `:app`'s `vaayil_flags` does the
  same (C11): onboarding runs again and the one-time notification ask is owed again.
  `AppContainer.init`'s one-time upgrades run in order as named `StartupStep`s through
  `runStartupSteps` (`AppStartup.kt`): `warm`, `rescueLegacySplitTunnelRules`, `reEncryptAtRest`,
  `wipeLegacyFlags`. A failing step logs its name and exception class and start-up continues;
  `CancellationException` is rethrown.

## Design system (normative for UI work)

- `PRODUCT.md` + `DESIGN.md`/`DESIGN.json` (repo root) are the product voice and the visual
  system ("Vellum": a warm paper board — canvas deepened by up to three paper films — with
  sage/slate/lavender ink, cut-paper seams, and one lifted shadow). UI code uses its tokens.
  `Theme.kt` (app module) is the single Compose mapping of DESIGN.md — change the two in the
  same commit, never let them drift. Shared primitives live in `:core:ui` (`Vellum.kt` —
  `VellumColors`, `Schibsted`, `Films`/`LocalFilms`, `LocalReducedMotion` — plus `Sheet.kt`
  and the surviving restyled components in `VellumComponents.kt`).
- **Dynamic color and dark themes are banned** ("The Daylight Is the Brand Rule"): do not
  re-add `dynamicColorScheme` or a dark scheme; the app renders the one light palette on
  every device, day or night. `VaayilTheme.Content(content)` has no dark parameter by
  design — don't pass one or reintroduce `isSystemInDarkTheme`.
- **Sage means sanctuary** ("The Sage Means Sanctuary Rule"): deep sage marks active/verified
  states and primary affirmations — never decoration. Lavender accents DNS/advanced; slate
  governs transport data; errors use clay/M3 error tokens.
- **Machine data wears mono** ("The Data Speaks Mono Rule"): endpoints, keys, IPs, DNS
  servers, stats render in `FontFamily.Monospace`, everywhere; live telemetry numbers use
  `VellumTelemetryNumStyle` (21sp/W600 tabular figures). UI type is Schibsted Grotesk
  (`Schibsted`, weights 400–700, notice in `docs/fonts-schibsted-grotesk.md`); the 11sp
  uppercase label is the sole 700.
- Depth is paper, not shadow ("The Film Rule"): one film = OKLCH ΔL −0.0206 ±0.0008,
  ΔC +0.0050 ±0.0012, hue 85° ±6° (pinned by `VellumTokensTest`); cumulative nesting caps
  at three films; seams are 1dp `#CEC2A9` drawn as ONE CLOSED perimeter (a single
  1dp stroke inset 0.5dp at radius r−0.5dp, all four sides — the retired
  top-and-left-only stroke read as an open bracket on real panels) —
  `Modifier.border` is
  banned in `:core:ui` and every feature module; `sheet-lift` is the only shadow and belongs
  to lifted sheets alone. Icons are functional
  (`Icons.Rounded.*` from material-icons-extended, also in `:feature:tunnel` and `:app`);
  decorative glyphs set `contentDescription = null`.
- Intentional UI changes: re-record fixtures (record task) AND update DESIGN.md tokens in the
  same commit; affected UI changes need a check on a device.

## Process

- Automation integrators: `docs/automation.md` (explicit-component broadcasts only — the
  receiver has no intent-filter by design).
- Bugs and feature requests go to GitHub Issues; security reports go through
  private vulnerability reporting (`SECURITY.md`).

## Known CI sensitivities

- Real-time-budget tests (400 ms DNS timeouts, sampler latches) can wobble on slow runners.
- `verifyRoborazziDebug` compares committed fixtures; regenerate via the record task when UI
  changes intentionally (follow `app/src/testDebug/.../DashboardScreenshotTest.kt` and
  `DashboardPhaseScreenshotTest.kt` conventions).
