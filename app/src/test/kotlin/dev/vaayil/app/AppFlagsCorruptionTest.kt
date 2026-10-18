package dev.vaayil.app

import android.app.Application
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * C11/DATA-3: a `vaayil_flags` file the app cannot parse must read as an empty set of flags
 * instead of crashing every process start, and must be writable right after the reset.
 *
 * SDK 26 is deliberate: no other test in this module uses it, so Robolectric gives this class its
 * own sandbox — and with it its own copy of the process-wide `settingsDataStore` delegate. This
 * test must be that delegate's first access, because an already-bound delegate keeps reading the
 * store it created back then. One test method, same reason: a second one would share the bound
 * delegate with the first.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [26], application = Application::class)
class AppFlagsCorruptionTest {

    @Test
    fun corruptFlagsResetAndRemainWritable() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val flagsFile = File(app.filesDir, "datastore/vaayil_flags.preferences_pb")
        flagsFile.parentFile!!.mkdirs()
        flagsFile.writeBytes("not a preferences file".toByteArray())

        val settingsDataStore = app.settingsDataStore
        assertEquals(emptyPreferences(), settingsDataStore.data.first())

        val onboardingDone = booleanPreferencesKey("onboardingDone")
        settingsDataStore.edit { it[onboardingDone] = true }
        assertEquals(true, settingsDataStore.data.first()[onboardingDone])
    }
}
