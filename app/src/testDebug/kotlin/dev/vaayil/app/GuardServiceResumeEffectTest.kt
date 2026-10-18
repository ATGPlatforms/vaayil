package dev.vaayil.app

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** M5 (spec U7): every resume sends the guard service its start command while a rule is on. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class GuardServiceResumeEffectTest {
    @get:Rule val compose = createComposeRule()

    /** Stands in for the activity's lifecycle. */
    private class Host : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private val host = Host()

    private fun moveTo(state: Lifecycle.State) = compose.runOnIdle { host.registry.currentState = state }

    @Test
    fun `every resume starts the guard while a rule is on`() {
        var ruleOn = true
        var starts = 0
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides host) {
                GuardServiceResumeEffect(needsGuard = { ruleOn }, start = { starts++ })
            }
        }
        moveTo(Lifecycle.State.RESUMED)
        compose.runOnIdle { assertEquals(1, starts) }
        // Back from system settings: the activity stopped, then resumed.
        moveTo(Lifecycle.State.CREATED)
        moveTo(Lifecycle.State.RESUMED)
        compose.runOnIdle { assertEquals(2, starts) }
        // Both rules off: a resume starts nothing.
        ruleOn = false
        moveTo(Lifecycle.State.CREATED)
        moveTo(Lifecycle.State.RESUMED)
        compose.runOnIdle { assertEquals(2, starts) }
    }

    @Test
    fun `a start the platform refuses is dropped`() {
        var attempts = 0
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides host) {
                GuardServiceResumeEffect(
                    needsGuard = { true },
                    start = { attempts++; throw IllegalStateException("background start not allowed") },
                )
            }
        }
        moveTo(Lifecycle.State.RESUMED)
        compose.runOnIdle { assertEquals(1, attempts) }
    }
}
