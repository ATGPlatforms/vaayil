package dev.vaayil.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.data.DataGraph
import dev.vaayil.core.data.ProfileRepository
import dev.vaayil.core.data.VaayilDatabase
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * C11/APP-8: the one-time start-up upgrades are best-effort. One upgrade that throws must not
 * stop the ones after it, must not abort `AppContainer` construction, and must not stop engine
 * initialization; only cancellation stops the run. The graph is bound before the runner runs,
 * because a merged stream makes the no-argument `DataGraph.crypto()` require that binding.
 *
 * The steps' bodies are replaced by recorders so nothing touches the Keystore, DataStore or Room;
 * the real runner ([runStartupSteps]) still executes the container's own step list.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class AppStartupTest {

    private val stepNames = listOf("warm", "rescueLegacySplitTunnelRules", "reEncryptAtRest", "wipeLegacyFlags")

    private fun app(): Application = ApplicationProvider.getApplicationContext()

    /** Robolectric hands every test method a fresh Application, so the cached graph is stale. */
    @Before
    fun freshGraph() {
        DataGraph.reset()
    }

    /**
     * A stand-in for the production runner: every step body becomes a recorder and the one named
     * [failing] throws [failure] after recording, so what a throwing upgrade does to the ones
     * after it — and to construction and engine initialization — is what the test observes.
     */
    private fun substitutingRunner(
        failing: String?,
        ran: MutableList<String>,
        failure: Exception = IllegalStateException("startup upgrade failed"),
    ): suspend (List<StartupStep>) -> Unit = { steps ->
        runStartupSteps(
            steps.map { step ->
                StartupStep(step.name) {
                    ran += step.name
                    if (step.name == failing) throw failure
                }
            },
        )
    }

    @Test
    fun eachThrowingUpgradeStillConstructsTheContainer() {
        for (failing in stepNames) {
            val ran = mutableListOf<String>()
            val initialized = mutableListOf<AppContainer>()

            val container = AppContainer(
                appContext = app(),
                startupRunner = substitutingRunner(failing, ran),
                engineInitializer = { initialized += it },
            )

            assertEquals("every upgrade still runs, in order, when $failing fails", stepNames, ran)
            assertEquals("construction and engine initialization complete", listOf(container), initialized)
        }
    }

    @Test
    fun cancellationStopsStartup() {
        val ran = mutableListOf<String>()
        val initialized = mutableListOf<AppContainer>()

        assertThrows(CancellationException::class.java) {
            AppContainer(
                appContext = app(),
                startupRunner = substitutingRunner("reEncryptAtRest", ran, CancellationException("stopped")),
                engineInitializer = { initialized += it },
            )
        }

        assertEquals("nothing after the cancelled step runs", stepNames.take(3), ran)
        assertEquals("engine initialization is skipped", emptyList<AppContainer>(), initialized)
    }

    /**
     * The graph binding (`db`, `profiles`) runs before the startup runner. A merged stream makes
     * the no-argument `DataGraph.crypto()` throw until one of the two has bound a context (B4), so
     * a runner ahead of them would turn the `warm` step into a caught-and-logged failure on every
     * start. Resetting the graph inside the runner proves the fields were initialised already: a
     * field initialised afterwards would come from a fresh cache entry and diff by identity.
     */
    @Test
    fun graphIsBoundBeforeTheStartupRunner() {
        val capturedDb = mutableListOf<VaayilDatabase>()
        val capturedProfiles = mutableListOf<ProfileRepository>()

        val container = AppContainer(
            appContext = app(),
            startupRunner = {
                capturedDb += DataGraph.database(app())
                capturedProfiles += DataGraph.repository(app())
                DataGraph.reset()
            },
            engineInitializer = { },
        )

        assertSame(capturedDb.single(), container.db)
        assertSame(capturedProfiles.single(), container.profiles)
    }
}
