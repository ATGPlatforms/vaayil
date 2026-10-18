package dev.vaayil.feature.profiles

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * G3 (spec §4.3): loads a package's launcher icon off the main thread, rendered at the
 * displayed size (36dp in px), with a process-wide LRU so scrolling back does not decode again.
 * Injectable so Robolectric captures never see a half-loaded list.
 */
fun interface AppIconLoader {
    /** Returns null when the package is gone or its drawable fails to render; runs on Dispatchers.IO (the caller wraps it). */
    suspend fun load(packageName: String, sizePx: Int): Bitmap?
}

/** Process-wide icon cache: package name -> rendered bitmap, bounded by bytes (~8 MiB). */
internal object AppIconCache {
    private val lru = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    fun get(packageName: String): Bitmap? = lru.get(packageName)

    fun put(packageName: String, bitmap: Bitmap) {
        lru.put(packageName, bitmap)
    }

    fun clear() {
        lru.evictAll() // test isolation
    }
}

/** The production loader: the package manager lookup, rendered at [sizePx], cached by package;
    a lookup or render failure returns null (the row falls back to the label initial). */
class PackageManagerAppIconLoader(
    private val context: Context,
    private val resolveIcon: (String) -> Drawable = { context.packageManager.getApplicationIcon(it) },
) : AppIconLoader {
    override suspend fun load(packageName: String, sizePx: Int): Bitmap? {
        AppIconCache.get(packageName)?.let { return it }
        val drawable = runCatching { resolveIcon(packageName) }.getOrNull() ?: return null
        // The render is guarded as well: a third-party drawable's draw() can throw (hundreds of
        // packages under "Show system apps"), and that must degrade to the initial, not crash
        // the composition's effect scope.
        val bitmap = withContext(Dispatchers.IO) {
            runCatching { drawableToBitmap(drawable, sizePx) }.getOrNull()
        } ?: return null
        AppIconCache.put(packageName, bitmap)
        return bitmap
    }
}

/** Renders [drawable] into a [sizePx]×[sizePx] bitmap; a BitmapDrawable scales down to it too. */
internal fun drawableToBitmap(drawable: Drawable, sizePx: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, sizePx, sizePx)
    drawable.draw(canvas)
    return bitmap
}
