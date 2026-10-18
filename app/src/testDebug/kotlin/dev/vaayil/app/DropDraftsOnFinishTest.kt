package dev.vaayil.app

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Spec U7: drafts end with the task, never with a recreation (F30). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class DropDraftsOnFinishTest {
    private var drops = 0

    private fun launch() = Robolectric.buildActivity(ComponentActivity::class.java).setup().also { controller ->
        controller.get().setContent { DropDraftsOnFinish { drops++ } }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `finishing the activity drops the drafts`() {
        val controller = launch()
        controller.get().finish()
        controller.pause().stop().destroy()
        assertEquals(1, drops)
    }

    @Test
    fun `a teardown without finishing keeps them`() {
        // "Don't keep activities" destroys the activity without finishing it.
        launch().pause().stop().destroy()
        assertEquals(0, drops)
    }

    @Test
    fun `a rotation keeps them`() {
        launch().recreate()
        assertEquals(0, drops)
    }
}
