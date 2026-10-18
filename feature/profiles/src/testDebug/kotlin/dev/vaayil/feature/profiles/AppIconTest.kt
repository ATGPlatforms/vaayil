package dev.vaayil.feature.profiles

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.ColorDrawable
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

/**
 * G3 (spec §4.3): the row icon loads on Dispatchers.IO, renders at the displayed 36dp,
 * caches per package, and falls back to the label's initial when the package is gone —
 * the placeholder while loading is the empty 36dp box.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppIconTest {
    @get:Rule val compose = createComposeRule()

    @After fun tearDown() { AppIconCache.clear() }

    @Test
    fun `the loader runs off the main thread`() {
        var loaderThread: Thread? = null
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val loader = AppIconLoader { _, _ ->
            loaderThread = Thread.currentThread()
            bitmap
        }
        compose.setContent { MaterialTheme { AppIcon("com.example.a", "App", loader) } }
        compose.waitUntil(5_000) { loaderThread != null }
        assertNotSame(Thread.currentThread(), loaderThread)
    }

    @Test
    fun `the first frame shows the empty placeholder then the icon`() {
        val gate = CompletableFuture<Bitmap?>()
        val loader = AppIconLoader { _, _ -> gate.get() }
        compose.setContent { MaterialTheme { AppIcon("com.example.b", "Notes", loader) } }
        compose.waitForIdle()
        compose.onNodeWithTag("app-icon-com.example.b").assertDoesNotExist()
        compose.onNodeWithText("N").assertDoesNotExist() // placeholder is the EMPTY box
        gate.complete(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888))
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("app-icon-com.example.b").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("app-icon-com.example.b").assertIsDisplayed()
    }

    @Test
    fun `a cached package is not resolved again`() {
        var resolutions = 0
        val context = ApplicationProvider.getApplicationContext<Context>()
        val loader = PackageManagerAppIconLoader(
            context,
            resolveIcon = { resolutions++; ColorDrawable(0xFF88CC88.toInt()) },
        )
        val first = runBlocking { loader.load("com.example.c", 36) }
        val second = runBlocking { loader.load("com.example.c", 36) }
        assertEquals(1, resolutions)
        assertSame(first, second)
        assertEquals(36, first!!.width)
    }

    @Test
    fun `a gone package shows the label initial`() {
        val loader = AppIconLoader { _, _ -> null }
        compose.setContent { MaterialTheme { AppIcon("com.example.gone", "Maps", loader) } }
        compose.waitForIdle()
        compose.onNodeWithText("M").assertIsDisplayed()
        compose.onNodeWithTag("app-icon-com.example.gone").assertDoesNotExist()
    }

    @Test
    fun `the loader receives 36dp in pixels`() {
        val receivedPx = AtomicInteger(-1)
        val loader = AppIconLoader { _, sizePx ->
            receivedPx.set(sizePx)
            Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        }
        compose.setContent { MaterialTheme { AppIcon("com.example.size", "Mail", loader) } }
        compose.waitUntil(5_000) { receivedPx.get() > 0 }
        assertEquals(with(compose.density) { 36.dp.roundToPx() }, receivedPx.get())
    }

    @Test
    fun `a drawable that cannot render falls back to the label initial`() {
        // Final review: the render is guarded like the lookup. A third-party drawable's
        // draw() can throw (hundreds of packages under "Show system apps"), and the tab
        // must degrade to the initial instead of crashing the composition's effect scope.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val loader = PackageManagerAppIconLoader(
            context,
            resolveIcon = {
                object : ColorDrawable(0xFF88CC88.toInt()) {
                    override fun draw(canvas: Canvas) = throw IllegalStateException("broken drawable")
                }
            },
        )
        compose.setContent { MaterialTheme { AppIcon("com.example.broken", "Broken", loader) } }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("B", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("B").assertIsDisplayed()
        compose.onNodeWithTag("app-icon-com.example.broken").assertDoesNotExist()
    }
}
