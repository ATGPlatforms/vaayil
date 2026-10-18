package dev.vaayil.app

import android.app.Application
import dev.vaayil.core.automation.AutoConnectService
import dev.vaayil.core.data.AutomationRulesStore
import dev.vaayil.core.model.needsGuardService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class VaayilApplication : Application() {
    lateinit var container: AppContainer
        private set

    // Application lives for the whole process; nothing cancels this scope.
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Re-arm: if either automation rule was on when the process died, restart the
        // auto-connect foreground service (Design ruling 3: the service is the
        // rules' liveness, not a UI-side detail). BootReceiver re-arms it as well, on
        // BOOT_COMPLETED and on MY_PACKAGE_REPLACED after an app update (F1).
        applicationScope.launch {
            // Android 12+ restricts foreground-service starts from the background, but exempts
            // an app the user consented as a VPN (REASON_OP_ACTIVATE_VPN, AOSP ActiveServices),
            // so this cold-start re-arm is normally allowed. Before that consent it can still be
            // refused (ForegroundServiceStartNotAllowedException): silent degrade — BootReceiver
            // re-arms the service itself at boot and after an app update (F1).
            runCatching {
                if (AutomationRulesStore(this@VaayilApplication).snapshot().needsGuardService) {
                    AutoConnectService.start(this@VaayilApplication)
                }
            }
        }
    }
}
