package dev.vaayil.core.tunnel.wg

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.util.Log
import com.wireguard.android.backend.GoBackend
import dev.vaayil.core.data.ActiveProfileStore
import dev.vaayil.core.model.Profile
import dev.vaayil.core.model.SplitTunnelPolicy
import dev.vaayil.wgtunnel.DnsIntercept
import java.net.DatagramSocket
import java.net.Socket
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import dev.vaayil.core.tunnel.api.isLive
import okhttp3.OkHttpClient

/** True when any network on the device is a VPN (F32: the resume never takes over a VPN that is up). */
@Suppress("DEPRECATION")
internal fun anyVpnNetworkUp(context: Context): Boolean {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
    return cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
}

/**
 * Process-wide engine holder. :app initializes it in Application.onCreate with
 * `EngineProvider.init(appContext, { installedApps() }, { id -> profiles.get(id) }, { rules.snapshot() })`.
 * The engine holds no database reference; lookups come from the injected lambda.
 * `init` also launches the same-boot crash resume (F32).
 */
object EngineProvider {
    @Volatile var engine: WireGuardEngine? = null
        private set

    /**
     * J3: bars the same-boot crash resume once a disconnect, a revoke or any ordinary start has
     * been handled in this process. Tests assign a fresh gate to reset it.
     */
    @Volatile internal var crashResumeGate = CrashResumeGate()

    /**
     * The live VpnService's socket-protect callbacks. Set by VpnTunnelService.onCreate, cleared
     * in onDestroy. Null when no VpnService instance is live. VpnService.protect is UID-scoped,
     * so these shield the resolver's bootstrap and split-DNS bypass sockets from the tunnel;
     * UDP and TCP need the two disjoint protect(DatagramSocket)/protect(Socket) overloads.
     */
    @Volatile var protectSocket: ((Socket) -> Boolean)? = null
    @Volatile var protectDatagramSocket: ((DatagramSocket) -> Boolean)? = null

    fun init(
        context: Context,
        installedApps: suspend () -> Set<String>,
        profileLookup: suspend (String) -> Profile?,
        splitTunnelRules: suspend () -> SplitTunnelPolicy,
    ) {
        if (engine != null) return
        val backend = GoBackend(context)
        GoBackend.setAlwaysOnCallback {
            context.startService(Intent(context, VpnTunnelService::class.java))
        }
        // Outlives service instances: the engine (and its resolver) is created once, while
        // the protect callbacks come and go with each VpnTunnelService — hence the
        // set/clear-on-the-field pattern instead of wiring the service in directly.
        val resolver = DnsResolverController(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            protect = { protectSocket },
            protectDatagram = { protectDatagramSocket },
            underlying = { UnderlyingNetworkProbe.route(context) },
            ok = OkHttpClient(),
        )
        val created = WireGuardEngine(
            context, backend,
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            installedApps, profileLookup, splitTunnelRules,
            dns = resolver,
            // B1: libwg-go is loaded by now (GoBackend's constructor loads it through
            // SharedLibraryLoader.loadSharedLibrary into the app's class loader: libcore System.loadLibrary and
            // System.load), so DnsIntercept's natives resolve (ART java_vm_ext.cc Libraries::FindNativeMethod
            // searches the libraries of the declaring class's loader).
            openChannel = { addresses -> ResolverChannel(DnsIntercept.open(addresses)) },
            vpnPermitted = { VpnService.prepare(context) == null },
            serviceHold = BoundServiceHold(context),
            nativeStats = { DnsIntercept.runtimeStats() },
        )
        engine = created
        // The registry follows the engine for the process lifetime, never a service instance's (F7/F5).
        ConnectionRegistry.bridge(created, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
        // F32: a tunnel that died with the previous process comes back once, in the same boot. Off the main thread
        // (a DataStore read and write); a failure here must never crash the process start.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { resumeInterruptedSession(context) }.onFailure { Log.w(TAG, "crash resume skipped", it) }
        }
    }

    /**
     * Starts [profileId] and reports what happened. REFUSED: no engine, an unknown id (a stale
     * active pointer is cleared so it stops re-arming every system start), or an [admit] check
     * that fails. All connect paths (UI, boot, Wi-Fi guard, automation) use this funnel.
     * STARTED: the engine's own start for this request settled CONNECTED for this profile (A6:
     * never a status read afterwards, which a start queued behind it may have overwritten);
     * only then are the active and last-connected records updated. FAILED: anything else; the
     * pointers keep whatever they held. [admit] runs after the profile lookup and immediately
     * before the engine call, with no suspension between them (J3: the crash resume passes the
     * gate here, so a disconnect that landed during the lookup still wins); false returns
     * REFUSED with no engine call and no pointer write.
     */
    suspend fun connectById(context: Context, profileId: String, admit: () -> Boolean = { true }): StartOutcome {
        val current = engine ?: return StartOutcome.REFUSED
        val store = ActiveProfileStore(context)
        val profile = current.profileLookupFor(profileId)
        if (profile == null) {
            if (store.activeId.first() == profileId) store.setActive(null)
            return StartOutcome.REFUSED
        }
        if (!admit()) return StartOutcome.REFUSED
        val outcome = current.startWithOutcome(profile)
        if (outcome == StartOutcome.STARTED) {
            store.setActive(profileId)
            // Teardown clears ACTIVE (fail-closed) but never lastConnected: the Wi-Fi
            // guard and boot path reconnect to it after any disconnect.
            store.setLastConnected(profileId)
        }
        return outcome
    }

    /**
     * F32: the previous process died with a tunnel up (ACTIVE names a profile written in this boot), so resume it
     * once through the control plane. [ActiveProfileStore.takeResumableActive] clears ACTIVE before the start is
     * issued, and connectById writes it again only on STARTED, so a resume whose process dies first never loops.
     * The start is tagged [VpnTunnelService.EXTRA_RESUME]: the service drops it when this process already handled
     * another start. A VPN that is already up is never taken over ([anyVpnNetworkUp] — VpnService.prepare() would
     * hand it over silently); the record stays consumed. Only then is VPN consent consulted: without it the start
     * is never issued, because [VpnTunnelService] promotes itself to a `systemExempted` foreground service at once,
     * which the platform refuses on API 34+ without the OP_ACTIVATE_VPN app-op (A5). ACTIVE stays consumed either
     * way. Immediately before the start is issued the crash-resume gate is consulted once more (J3): a disconnect,
     * a revoke or an ordinary start that reached this process in the meantime logs, issues nothing, and ACTIVE
     * stays consumed. ACTIVE from an earlier boot, or unstamped, is cleared without a resume: reconnecting after a
     * reboot is BootReceiver's connect-on-boot decision. A refused foreground start is logged and ACTIVE stays cleared.
     */
    internal suspend fun resumeInterruptedSession(context: Context, store: ActiveProfileStore = ActiveProfileStore(context), vpnUp: () -> Boolean = { anyVpnNetworkUp(context) }) {
        val profileId = store.takeResumableActive() ?: return
        if (vpnUp()) {
            Log.i(TAG, "crash resume skipped: a VPN is already up")
            return
        }
        // Consulted after the VPN-up test: prepare() would hand another app's VPN over silently.
        if (VpnService.prepare(context) != null) {
            Log.i(TAG, "crash resume skipped: VPN permission is off")
            return
        }
        if (!crashResumeGate.mayIssue()) {
            Log.i(TAG, "crash resume skipped: a disconnect or another start came first")
            return
        }
        runCatching {
            context.startForegroundService(
                Intent(context, VpnTunnelService::class.java)
                    .setAction(VpnTunnelService.ACTION_CONNECT)
                    .putExtra(VpnTunnelService.EXTRA_PROFILE_ID, profileId)
                    .putExtra(VpnTunnelService.EXTRA_RESUME, true),
            )
        }.onFailure { Log.w(TAG, "crash resume start refused", it) }
    }

    /**
     * J3: bars the crash resume for the rest of this process and returns the accepted resume's
     * Job, if one is registered, so a caller can wait for it before tearing anything down.
     */
    fun suppressCrashResume(): Job? = crashResumeGate.suppress()

    /**
     * J3: whether a tunnel is live right now (CONNECTING or CONNECTED). Reads the engine's own
     * status when the engine exists, so the process bridge's lag cannot hide a fresh UP behind
     * an older registry phase.
     */
    fun isTunnelLive(): Boolean {
        val phase = engine?.status?.value?.phase ?: ConnectionRegistry.status.value.phase
        return phase.isLive
    }

    private const val TAG = "EngineProvider"
}
