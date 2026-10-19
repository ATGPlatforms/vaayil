package dev.vaayil.core.tunnel.wg

import android.content.Context
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.InetNetwork
import dev.vaayil.core.dns.DatagramPipe
import dev.vaayil.core.dns.SecureDnsBootstrapException
import dev.vaayil.core.model.Profile
import dev.vaayil.core.model.SplitTunnelPolicy
import dev.vaayil.core.tunnel.api.ConnectionPhase
import dev.vaayil.core.tunnel.api.TunnelEngine
import dev.vaayil.core.tunnel.api.TunnelError
import dev.vaayil.core.tunnel.api.TunnelNotice
import dev.vaayil.core.tunnel.api.TunnelStats
import dev.vaayil.core.tunnel.api.TunnelStatus
import dev.vaayil.wgtunnel.VpnRoutes
import java.net.InetAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class WireGuardEngine(
    private val context: Context,
    private val backend: Backend,
    private val scope: CoroutineScope,
    private val installedApps: suspend () -> Set<String>,
    private val profileLookup: suspend (String) -> Profile?,
    /** Global split-tunnel rules; resolved fresh at every start so policy changes apply on reconnect. */
    private val splitTunnelRules: suspend () -> SplitTunnelPolicy,
    private val dns: DnsResolverController,
    /**
     * B1: opens the DNS channel from libwg-go and returns Vaayil's end. Production wraps
     * `DnsIntercept.open`; JVM tests pass a fake, so they never load the native library.
     */
    private val openChannel: (List<InetAddress>) -> DatagramPipe,
    /**
     * Whether Vaayil holds the VPN permission (`VpnService.prepare(context) == null` in production, AOSP
     * VpnService.java prepare). Without it the start skips the resolver and the channel, so the backend's own
     * permission error is what gets reported: GoBackend.setStateInternal throws VPN_NOT_AUTHORIZED on that check.
     */
    private val vpnPermitted: () -> Boolean,
    /** Stats polling interval; tests shorten it to observe the sampler stopping. */
    private val statsIntervalMs: Long = 10_000,
    /** G4 §4.4.3: the first-handshake sampling cadence, used until a sample carries a handshake. */
    private val statsFastIntervalMs: Long = 1_000,
    /** G4 §4.4.3: how long the fast cadence lasts when no sample carries a handshake. */
    private val statsFastWindowMs: Long = 30_000,
    /** F7: keeps VpnTunnelService alive across setState so a switch never lands on GoBackend's own service. */
    private val serviceHold: ServiceHold = ServiceHold.None,
    /**
     * F33: why a switch target's endpoint host does not resolve on the underlying network, or null when it
     * does. Production asks [DnsResolverController.underlyingLookupProblem], the D1 bootstrap path; tests
     * inject a fake.
     */
    private val endpointLookupProblem: suspend (host: String) -> String? = { host -> dns.underlyingLookupProblem(host) },
    /** One line of libwg-go runtime numbers, logged after every turn-on and turn-off (F31); null logs nothing. */
    private val nativeStats: () -> String? = { null },
) : TunnelEngine {

    init {
        // C3: when the DNS channel gives up reopening, the live session must say so on the dashboard.
        dns.onDnsNotAnswering = ::markDnsNotAnswering
    }

    // Serializes start/stop: a disconnect issued during the DNS/establish window
    // suspends until the connect settles, then brings the tunnel down — no lost
    // transitions, no orphaned tunnel handles.
    private val mutex = Mutex()
    @Volatile private var tunnel: Tunnel? = null
    /** Name of the tunnel that is live right now — the profile name it was started with; null when none. */
    val liveTunnelName: String? get() = tunnel?.name
    private var sampler: StatsSampler? = null
    /** G4 §4.4.2: bumped by every start that publishes CONNECTED; its publications and samples carry it. */
    private var generationCounter: Long = 0L
    /** The generation of the tunnel that is live now — what later CONNECTED publications carry. */
    private var connectedGeneration: Long = 0L
    // Profile id of the tunnel currently in [tunnel]; lets a failed switch restore a
    // truthful CONNECTED status for the still-live previous tunnel.
    private var activeProfileId: String? = null

    /**
     * K3: starts that failed while a different profile's tunnel stayed live, keyed by the target id and
     * kept until [takeFailedSwitch] reports them — a queued second connect of another profile must not
     * wipe a record its own settle callback has not read yet (sweep-2 minor). A start of the same target
     * supersedes its record; a failure overwrites its own key.
     */
    private val failedSwitches = java.util.concurrent.ConcurrentHashMap<String, SwitchFailure>()
    /** C2: restarts of the keyed profile that could not apply their settings, kept until reported (take). */
    private val restartRefusals = java.util.concurrent.ConcurrentHashMap<String, RestartRefusal>()
    private val _status = MutableStateFlow(TunnelStatus.idle)
    private val _stats = MutableStateFlow(TunnelStats.empty)
    override val status: StateFlow<TunnelStatus> = _status.asStateFlow()
    override val stats: StateFlow<TunnelStats> = _stats.asStateFlow()

    /**
     * Set by VpnTunnelService. Fired after [GoBackend.setState] with the packages the policy
     * resolved to. The VpnService.Builder application itself happens inside
     * GoBackend.setStateInternal, which maps Interface.getExcludedApplications() to
     * Builder.addDisallowedApplication() and Interface.getIncludedApplications() to
     * Builder.addAllowedApplication(); the resolved lists are written into the Config by
     * [WgConfigMapper.toBackendConfig] before setState. GoBackend is `final`, so a builder
     * hook via subclassing is not available in the pinned library version.
     */
    var onSplitTunnelResolved: ((ResolvedSplitTunnel) -> Unit)? = null

    /** TunnelEngine's entry point; the outcome is discarded here, so callers that report it use [startWithOutcome]. */
    override suspend fun start(profile: Profile) {
        startWithOutcome(profile)
    }

    /**
     * A6: one start, one mutex acquisition, and the outcome is computed from the status THIS start
     * published — before the lock is released, so a start queued behind it (its own CONNECTING or its
     * settle) cannot change what this request reports. [EngineProvider.connectById] consumes it for the
     * active/last-connected writes; [start] ignores it.
     */
    internal suspend fun startWithOutcome(profile: Profile): StartOutcome =
        mutex.withLock {
            // A1: the resolver's deactivation ticket, captured before any suspending work, so a revoke that
            // lands while this start builds its session leaves its stage (and commit or rollback) a no-op.
            val dnsEpoch = dns.deactivationEpoch()
            failedSwitches.remove(profile.id) // K3: a start supersedes only its own profile's record
            restartRefusals.remove(profile.id) // C2: a start supersedes only its own profile's record
            val hold = serviceHold.acquire()
            try {
                // T-5 gate: what `tunnel` was BEFORE setState. It only changes when the new
                // tunnel is published, so `tunnel === previousTunnel` later means "the new
                // tunnel never came up" and the previous one (if any) is what survived.
                val previousTunnel = tunnel
                // F33: a switch away from a live tunnel first resolves the target's endpoint host on the
                // underlying network. GoBackend resolves it only after bringing the live tunnel DOWN, and
                // retries for ~9 s with nothing protecting the traffic. So a host that does not resolve
                // refuses the switch here, before the backend, the resolver or the status is touched.
                // Fresh connects keep GoBackend's own lookup and retries.
                if (previousTunnel != null) {
                    val host = endpointHostname(profile.peer.endpoint)
                    val problem = host?.let { withContext(Dispatchers.IO) { endpointLookupProblem(it) } }
                    if (problem != null) {
                        android.util.Log.w(
                            "WireGuardEngine",
                            "switch refused before touching the live tunnel: $host does not resolve on the underlying network ($problem)",
                        )
                        // Re-checks the live handle: its DOWN callback is ignored while the mutex is held.
                        failWhileTunnelUp(profile, TunnelError.DnsFailure(profile.peer.endpoint), restartReason = "its server name didn't resolve")
                        return@withLock startOutcome(_status.value, profile.id)
                    }
                }
                // Pure and cheap: plan before the blocking establish so the interface config
                // already carries the resolver DNS swap when intercepting.
                val dnsPlan = DnsPlanner.plan(profile)
                // Plan ruling 20: a fresh connect shows CONNECTING from here, as before B1, so the dashboard's
                // tap-to-cancel works during a slow resolver bootstrap. A switch keeps the live profile's CONNECTED
                // until its resolver is staged, as F33's lookup does, and publishes CONNECTING below.
                if (previousTunnel == null) _status.value = TunnelStatus(ConnectionPhase.CONNECTING, profile.id, null)
                // B1 §6.5 steps 2-4, before the backend is touched: build the new resolver, make sure the DNS channel
                // is open (libwg-go refuses to start a tunnel without one) and make the new resolver current, keeping
                // the previous one aside. A failure refuses a switch the way F33's refusal does, with the live
                // tunnel and its resolver untouched, and fails a fresh connect. Without the VPN permission all of
                // it is skipped, so setState reports the permission error rather than a DNS one
                // (GoBackend.setStateInternal: VPN_NOT_AUTHORIZED). Read once: the guard after setState relies on
                // it (Plan ruling 10).
                val permitted = vpnPermitted()
                // The staged resolver's secure endpoint addresses, for the routes the local-network switch trims:
                // empty when nothing intercepts or the VPN permission is missing.
                var secureEndpointAddresses: List<String> = emptyList()
                if (permitted) {
                    val stage = try {
                        stageResolver(profile, dnsPlan, dnsEpoch)
                    } catch (e: CancellationException) {
                        // The caller died during the build, before anything was staged. A fresh connect already
                        // shows CONNECTING, so it ends the way a cancellation during setState does. A switch never
                        // left CONNECTED and propagates untouched, as a cancelled F33 lookup does.
                        if (previousTunnel == null) {
                            withContext(NonCancellable) {
                                failWhileTunnelUp(profile, TunnelError.BackendError("connect cancelled"))
                            }
                        }
                        throw e
                    }
                    val problem = stage.problem
                    if (problem != null) {
                        // The class name only: a bootstrap failure's message names the endpoint host.
                        android.util.Log.w(
                            "WireGuardEngine",
                            if (previousTunnel != null) {
                                "switch refused before touching the live tunnel: the resolver could not start (${problem.javaClass.simpleName})"
                            } else {
                                "connect refused: the resolver could not start (${problem.javaClass.simpleName})"
                            },
                        )
                        // Re-checks the live handle, as F33's refusal does: CONNECTED for a surviving tunnel,
                        // FAILED otherwise.
                        val restartReason = when (problem) {
                            is SecureDnsBootstrapException -> "its DNS server name didn't resolve"
                            is TimeoutCancellationException -> "the DNS resolver timed out"
                            else -> "its DNS settings could not start"
                        }
                        failWhileTunnelUp(
                            profile,
                            TunnelError.DnsResolverFailure(problem.message ?: problem.javaClass.simpleName),
                            restartReason,
                        )
                        return@withLock startOutcome(_status.value, profile.id)
                    }
                    secureEndpointAddresses = stage.secureEndpointAddresses
                }
                if (previousTunnel != null) _status.value = TunnelStatus(ConnectionPhase.CONNECTING, profile.id, null)
                try {
                    val policy = splitTunnelRules()
                    // Endpoint DNS resolution (config building) and GoBackend's blocking
                    // establish()/wgTurnOn path must not run on the main dispatcher.
                    val newTunnel = withContext(Dispatchers.IO) {
                        val installed = installedApps()
                        val resolved = SplitTunnelResolver.resolve(policy, installed, context.packageName)
                        val config: Config
                        val routes: List<InetNetwork>?
                        try {
                            config = WgConfigMapper.toBackendConfig(profile, resolved, dnsPlan)
                            // Both parse the profile's text, so one unusable-profile handling covers the routes too:
                            // a profile the mapper refuses must fail the start the same way whichever parse sees it.
                            routes = WgConfigMapper.vpnRoutes(
                                profile, policy.allowLocalNetwork, secureEndpointAddresses,
                            )
                        } catch (e: com.wireguard.config.BadConfigException) {
                            // Enum names only: getText() and the message can carry key material.
                            android.util.Log.w("WireGuardEngine", "backend refused the profile's config: ${e.section} ${e.location} ${e.reason}")
                            throw UnusableProfileException(e)
                        } catch (e: com.wireguard.config.ParseException) {
                            // The parsed type's name only: getText() can carry key material.
                            android.util.Log.w("WireGuardEngine", "backend could not parse a ${e.parsingClass.simpleName} in the profile's config")
                            throw UnusableProfileException(e)
                        }
                        val t = EngineTunnel(profile.name, routes)
                        val state = backend.setState(t, Tunnel.State.UP, config)
                        // C4: setState returns the state it applied; anything but UP (without throwing) is a
                        // failed start — the published-tunnel paths below must not run for a dead tunnel.
                        check(state == Tunnel.State.UP) { "The tunnel did not start." }
                        // Published before anything that can still throw: from here on the new
                        // tunnel is the live datapath and must stay reachable via stop().
                        tunnel = t
                        onSplitTunnelResolved?.invoke(resolved)
                        t
                    }
                    activeProfileId = profile.id
                    if (!permitted && dnsPlan.intercept) {
                        // Plan ruling 10's race: the permission arrived between vpnPermitted() and setState, so no
                        // resolver was staged for this tunnel and it would drop every DNS query in silence. Bring it
                        // down and say why; the next start stages its resolver.
                        handleStartFailure(
                            profile, previousTunnel,
                            TunnelError.DnsResolverFailure("the VPN permission changed while connecting"),
                        )
                        return@withLock startOutcome(_status.value, profile.id)
                    }
                    dns.commit() // B1 §6.5 step 6: the new tunnel is live, so the previous resolver retires
                    connectedGeneration = ++generationCounter // G4 §4.4.2: a new session, a new generation
                    _status.value = TunnelStatus(ConnectionPhase.CONNECTED, profile.id, null, null, connectedGeneration)
                    sampler?.stop()
                    sampler = StatsSampler(
                        scope,
                        fastIntervalMs = statsFastIntervalMs,
                        slowIntervalMs = statsIntervalMs,
                        fastWindowMs = statsFastWindowMs,
                    ).also { it.start(backend, newTunnel, connectedGeneration) { s -> _stats.value = s } }
                    logNativeStats("turn-on")
                } catch (e: CancellationException) {
                    // The caller died mid-start (VpnTunnelService destroyed by a disconnect). A tunnel
                    // that setState published must still come down, and nothing cancellable may run
                    // in the recovery, so the whole handler is NonCancellable; then the cancellation
                    // propagates as it should.
                    withContext(NonCancellable) {
                        handleStartFailure(profile, previousTunnel, TunnelError.BackendError("connect cancelled"))
                    }
                    throw e
                } catch (e: java.net.UnknownHostException) {
                    handleStartFailure(profile, previousTunnel, TunnelError.DnsFailure(profile.peer.endpoint))
                } catch (e: UnusableProfileException) {
                    handleStartFailure(profile, previousTunnel, TunnelError.ParseError(UNUSABLE_PROFILE_MESSAGE))
                } catch (e: com.wireguard.android.backend.BackendException) {
                    handleStartFailure(profile, previousTunnel, backendError(e, profile))
                } catch (e: Exception) {
                    handleStartFailure(
                        profile, previousTunnel,
                        TunnelError.BackendError(e.message ?: e.javaClass.simpleName),
                    )
                }
            } finally {
                hold.close()
            }
            // This request's own settle (A6), read under the lock: a later start may overwrite _status,
            // and what it publishes is that start's outcome, not this one's.
            startOutcome(_status.value, profile.id)
        }

    /**
     * B1 §6.5 steps 2-4: builds the resolver for [profile] when [plan] intercepts, makes sure the DNS channel is
     * open, then stages the new resolver (null for a plain profile) with the previous one kept aside. Returns the
     * failure, or null and the staged session's secure endpoint addresses once staged. Nothing is staged on failure,
     * and a resolver built before the failure is closed. A timeout inside the build is a failure; a real cancellation
     * propagates.
     *
     * [expectedEpoch] is the deactivation ticket [start] captured: a revoke that deactivated the resolver while
     * this build ran makes the stage a no-op (A1). The candidate is closed, no error is reported — the start goes
     * on, so its late UP simply drops DNS until teardown — and the commit or rollback that follows finds nothing
     * staged.
     */
    private suspend fun stageResolver(profile: Profile, plan: DnsPlan, expectedEpoch: Long): ResolverStage {
        var built: ResolverSession? = null
        return try {
            if (plan.intercept) built = dns.build(profile)
            dns.ensureChannel(openChannel)
            if (!dns.stageIfUnchanged(built, expectedEpoch)) {
                // The revoke won: staging now would resurrect the session it retired. Closing the candidate is
                // all that is left — no error, so the start settles the way any late UP does.
                built?.close()
                return ResolverStage(null)
            }
            ResolverStage(null, built?.secureEndpointAddresses.orEmpty())
        } catch (e: TimeoutCancellationException) {
            built?.close()
            ResolverStage(e)
        } catch (e: CancellationException) {
            built?.close()
            throw e
        } catch (e: Exception) {
            built?.close()
            ResolverStage(e)
        }
    }

    /**
     * T-5 split of the failure paths. If the new tunnel was never published
     * (`tunnel === previousTunnel`) the pinned GoBackend restored the previous tunnel before
     * the exception escaped: report CONNECTED for it and make its resolver current again
     * ([DnsResolverController.rollback]). If the new tunnel WAS published (setState succeeded,
     * then something threw or the caller was cancelled) a half-alive tunnel must not report
     * CONNECTED: stop the new tunnel, retire every resolver and fail with the cause.
     */
    private suspend fun handleStartFailure(
        profile: Profile,
        previousTunnel: Tunnel?,
        error: TunnelError,
    ) {
        // Non-null exactly when the new tunnel is live: `tunnel` no longer points at the
        // pre-setState handle, and only setState's success reassigns it to a new one.
        val published = tunnel?.takeIf { it !== previousTunnel }
        if (published != null) {
            tunnel = null
            activeProfileId = null
            sampler?.stop()
            sampler = null
            _stats.value = TunnelStats.empty
            // NonCancellable: this must run even when the caller was cancelled (the T-5 orphan).
            runCatching { withContext(NonCancellable + Dispatchers.IO) { backend.setState(published, Tunnel.State.DOWN, null) } }
            logNativeStats("turn-off")
            dns.deactivate()
            _status.value = TunnelStatus(ConnectionPhase.FAILED, profile.id, error)
            return
        }
        failWhileTunnelUp(profile, error)
        // B1 §6.5 step 6: the previous tunnel survived, so its resolver answers again. When nothing survived,
        // failWhileTunnelUp already retired every resolver.
        if (tunnel != null && tunnel === previousTunnel) dns.rollback()
    }

    /**
     * A failed first connect (nothing live) reports FAILED. A failed *switch* leaves the
     * previous tunnel up — the pinned GoBackend restores it before the exception escapes —
     * and [tunnel] still holds its handle, so the status returns to CONNECTED for that
     * profile. A stale FAILED would strand a running tunnel with the notification torn down
     * and the Home card offering no Disconnect. The restore is not guaranteed though: the
     * pinned GoBackend's own restore runs through the same establish path, and from the
     * background the fresh VpnService start is platform-blocked (the tunnel-down already
     * stopSelf()ed the service). So the surviving handle's liveness is verified against the
     * backend before claiming CONNECTED — a dead "survivor" reports FAILED honestly and
     * releases the handle (a screen lock during the restart is the field case: the tunnel is
     * already down while the dashboard would otherwise still claim it was up).
     */
    private suspend fun failWhileTunnelUp(failed: Profile, error: TunnelError, restartReason: String? = null) {
        val handle = tunnel
        val surviving = handle != null && runCatching {
            backend.getState(handle) == Tunnel.State.UP
        }.getOrDefault(false)
        _status.value = if (!surviving) {
            tunnel = null
            activeProfileId = null
            sampler?.stop()
            sampler = null
            _stats.value = TunnelStats.empty
            dns.deactivate()
            TunnelStatus(ConnectionPhase.FAILED, failed.id, error)
        } else {
            val liveId = activeProfileId ?: failed.id
            // C2: a same-profile restart that was refused before setState while its old session survived.
            if (liveId == failed.id && restartReason != null) {
                restartRefusals[failed.id] = RestartRefusal(failed.name, restartReason)
            }
            // K3: a different profile's tunnel stays live, so the app can say the switch did not happen and why.
            if (liveId != failed.id) {
                val liveName = profileLookup(liveId)?.name ?: handle.name
                failedSwitches[failed.id] = SwitchFailure(
                    targetName = failed.name,
                    liveName = liveName,
                    unresolved = error is TunnelError.DnsFailure,
                )
            }
            TunnelStatus(
                ConnectionPhase.CONNECTED, liveId, null,
                if (liveId == failed.id && restartReason != null) TunnelNotice.SettingsNotApplied else null,
                connectedGeneration, // G4 §4.4.2: the live tunnel's generation, never a new stamp
            )
        }
    }

    /**
     * C1: maps GoBackend's failure reasons to readable copy. The BackendError message is shown verbatim
     * (errorMessage), so it is a full sentence with no config text; the two structured reasons reuse the
     * existing types. GO_ACTIVATION_ERROR_CODE carries the native code in its format args — logged, never
     * shown. The last two reasons are userspace-backend-only and never thrown by GoBackend; they are
     * mapped so the when is total.
     */
    private fun backendError(e: com.wireguard.android.backend.BackendException, profile: Profile): TunnelError =
        when (e.reason) {
            com.wireguard.android.backend.BackendException.Reason.DNS_RESOLUTION_FAILURE ->
                TunnelError.DnsFailure(profile.peer.endpoint)
            com.wireguard.android.backend.BackendException.Reason.VPN_NOT_AUTHORIZED -> TunnelError.PermissionDenied
            com.wireguard.android.backend.BackendException.Reason.TUNNEL_MISSING_CONFIG ->
                TunnelError.BackendError("The tunnel's configuration went missing.")
            com.wireguard.android.backend.BackendException.Reason.UNABLE_TO_START_VPN ->
                TunnelError.BackendError("Android refused to start the VPN.")
            com.wireguard.android.backend.BackendException.Reason.TUN_CREATION_ERROR ->
                TunnelError.BackendError("The tunnel interface could not be created.")
            com.wireguard.android.backend.BackendException.Reason.GO_ACTIVATION_ERROR_CODE -> {
                android.util.Log.w("WireGuardEngine", "backend activation error: ${e.format.firstOrNull()}")
                TunnelError.BackendError("WireGuard couldn't activate the tunnel.")
            }
            com.wireguard.android.backend.BackendException.Reason.UNKNOWN_KERNEL_MODULE_NAME ->
                TunnelError.BackendError("This device's kernel WireGuard support is unavailable.")
            com.wireguard.android.backend.BackendException.Reason.WG_QUICK_CONFIG_ERROR_CODE ->
                TunnelError.BackendError("The profile's wg-quick configuration could not be applied.")
        }

    override suspend fun stop() {
        // Non-cancellable: a caller dying mid-stop (service teardown, VM scope cancel) must
        // not abandon the tunnel-stop — an abandoned stop is an orphaned live tunnel.
        withContext(NonCancellable) {
            mutex.withLock {
                // Tear the resolver down first: its classify/upstreams must not answer for
                // an interface whose DNS stops pointing at it the moment the tunnel drops.
                dns.deactivate()
                restartRefusals.clear() // C2: a disconnect ends the session, so any refusal about it is stale
                sampler?.stop()
                sampler = null
                // DOWN, never TOGGLE: TOGGLE resolves against GoBackend's own current tunnel and
                // flips to UP (flapping the live tunnel) once the two handles have diverged.
                tunnel?.let {
                    runCatching { withContext(Dispatchers.IO) { backend.setState(it, Tunnel.State.DOWN, null) } }
                    logNativeStats("turn-off")
                }
                tunnel = null
                activeProfileId = null
                _stats.value = TunnelStats.empty
                _status.value = TunnelStatus.idle
            }
        }
    }

    /**
     * A1: retires the resolver NOW, before [VpnTunnelService.onRevoke]'s asynchronous teardown reaches [stop].
     * The platform has already revoked the VPN, so nothing may answer — or start a query — from here on; the
     * tunnel handle itself stays with teardown. Deliberately mutex-free: onRevoke runs on the main thread and a
     * start may hold [mutex] across a slow establish, and waiting for it would delay the one step that must be
     * synchronous. [DnsResolverController.deactivate] is idempotent, so teardown's own deactivation is harmless.
     */
    internal fun deactivateResolver() {
        dns.deactivate()
    }

    /**
     * Re-derives the truth after a VpnService instance died (F7) instead of assuming the tunnel died
     * with it: waits for any start/stop in flight, then drops the handle only when the backend no
     * longer reports it UP. A tunnel that is still up — a switch that re-established on another
     * service instance — keeps its CONNECTED status.
     * Production launches it on [TunnelControl.Default]'s `Dispatchers.Main.immediate` scope from
     * `VpnTunnelService.onDestroy`. The mutex excludes start/stop. The DOWN callback's `mutex.isLocked`
     * guard ignores callbacks while it runs, and GoBackend's own service onDestroy also runs on main, so
     * [onBackendTeardown]'s lock-free writes never race it.
     */
    suspend fun reconcile() {
        mutex.withLock {
            val handle = tunnel ?: return
            val up = runCatching { backend.getState(handle) == Tunnel.State.UP }.getOrDefault(false)
            if (!up) onBackendTeardown(handle)
        }
    }

    /** K3: the recorded failure of the last start of [profileId], once; null when that start left no other tunnel live. */
    fun takeFailedSwitch(profileId: String): SwitchFailure? = failedSwitches.remove(profileId)

    /** C2: the recorded refusal of the last restart of [profileId], once; null when that restart was not refused. */
    fun takeRestartRefusal(profileId: String): RestartRefusal? = restartRefusals.remove(profileId)

    suspend fun profileLookupFor(id: String): Profile? = profileLookup(id)

    /**
     * A start's tunnel handle. [routes] are the interface routes the local-network switch trimmed, or null to let
     * GoBackend route the peers' Allowed IPs as before ([VpnRoutes]).
     */
    private inner class EngineTunnel(
        private val tunnelName: String,
        private val routes: List<InetNetwork>?,
    ) : Tunnel, VpnRoutes {
        override fun getVpnRoutes(): List<InetNetwork>? = routes
        override fun getName(): String = tunnelName
        override fun onStateChange(newState: Tunnel.State) {
            // GoBackend reports DOWN for our own setState calls (the mutex is held: expected)
            // and for its VpnService.onDestroy teardown behind our back. Only the latter is news.
            if (newState == Tunnel.State.DOWN && !mutex.isLocked) onBackendTeardown(this)
        }
    }

    /**
     * The backend turned our tunnel off outside start/stop: nothing is left to bring down.
     *
     * These writes are lock-free on purpose: [mutex] is held for the whole of start/stop, so
     * taking it here would deadlock the synchronous callback GoBackend fires from inside setState,
     * and the guard above already admits only callbacks that arrive with the mutex free. That is
     * safe only because those paths are main-thread-dispatched — the engine's own scope and the VPN
     * service's scope are both `Dispatchers.Main.immediate`, and the service-destroy callback comes
     * from the platform. A multi-threaded scope would reintroduce a real data race with no compiler
     * or test signal.
     */
    private fun onBackendTeardown(t: Tunnel) {
        if (tunnel !== t) return
        tunnel = null
        activeProfileId = null
        sampler?.stop()
        sampler = null
        dns.deactivate()
        restartRefusals.clear() // C2: the session is gone, so a refusal about it must never surface later
        _stats.value = TunnelStats.empty
        _status.value = TunnelStatus.idle
        logNativeStats("turn-off")
    }

    /**
     * C3: the DNS channel died and three reopens failed. Only a CONNECTED session can carry the notice,
     * and it composes onto the CURRENT status rather than a cached one, so it never resurrects a phase.
     * Last-write-wins against the engine's own writes: the next start or disconnect rewrites the status
     * without a notice, and a start resets the reopen burst.
     */
    private fun markDnsNotAnswering() {
        val now = _status.value
        if (now.phase == ConnectionPhase.CONNECTED) {
            // G4 §4.4.2: a copy keeps the live generation; a fresh construction could lose it.
            _status.value = now.copy(notice = TunnelNotice.DnsNotAnswering)
        }
    }

    /** F31: libwg-go's goroutine and heap numbers, one log line per turn-on and turn-off. */
    private fun logNativeStats(event: String) {
        val line = runCatching { nativeStats() }.getOrNull() ?: return
        android.util.Log.i("WireGuardEngine", "libwg-go after $event: $line")
    }
}

/** Shown when the backend refuses to build a profile's config (F17); never the library's exception text. */
internal const val UNUSABLE_PROFILE_MESSAGE =
    "This profile has a setting the tunnel can't use. Open it in the editor to see which one."

/**
 * What [WireGuardEngine.stageResolver] leaves behind: the failure, or null and the staged session's secure endpoint
 * addresses, which the local-network switch keeps reachable with host routes. Every failure path carries no addresses.
 */
private data class ResolverStage(
    val problem: Exception?,
    val secureEndpointAddresses: List<String> = emptyList(),
)

/** The backend refused to build this profile's config: a row saved before a validation rule existed. */
private class UnusableProfileException(cause: Exception) : Exception(cause)
