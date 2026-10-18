package dev.vaayil.core.tunnel.wg

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class ServiceHoldTest {

    @Test
    fun `the production hold binds VpnTunnelService and releases the binding`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val handle = BoundServiceHold(app).acquire()
        assertEquals(1, shadowOf(app).boundServiceConnections.size)
        handle.close()
        assertEquals(1, shadowOf(app).unboundServiceConnections.size)
    }

    @Test
    fun `the hold is an auto-create binding to VpnTunnelService`() {
        var boundIntent: Intent? = null
        var boundFlags = 0
        val recording = object : ContextWrapper(ApplicationProvider.getApplicationContext()) {
            override fun bindService(service: Intent, conn: android.content.ServiceConnection, flags: Int): Boolean {
                boundIntent = service
                boundFlags = flags
                return true
            }
            override fun unbindService(conn: android.content.ServiceConnection) = Unit
        }
        BoundServiceHold(recording).acquire().close()
        assertEquals(VpnTunnelService::class.java.name, boundIntent?.component?.className)
        // BIND_AUTO_CREATE is the whole point (F7): a plain bind would not keep the instance, or GoBackend's future, alive.
        assertTrue((boundFlags and Context.BIND_AUTO_CREATE) != 0)
    }

    @Test
    fun `a hold whose bind fails still lets the start run`() {
        val throwing = object : ContextWrapper(ApplicationProvider.getApplicationContext()) {
            override fun bindService(service: Intent, conn: android.content.ServiceConnection, flags: Int): Boolean =
                throw SecurityException("scripted")
        }
        val handle = BoundServiceHold(throwing).acquire() // must not throw
        handle.close() // must not throw either
        val logged = org.robolectric.shadows.ShadowLog.getLogsForTag("ServiceHold")
        assertTrue(logged.any { it.throwable is SecurityException }, "the bind failure's cause is logged: ${logged.map { it.msg }}")
    }
}
