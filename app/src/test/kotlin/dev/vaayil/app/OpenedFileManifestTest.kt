package dev.vaayil.app

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * The two filters that offer Vaayil for a file from another app, and the limits of that offer:
 * content URIs only, with the unknown and plain-text types Open with asks for, and a share that
 * carries a file. A web page, another scheme or a share of text alone never lists Vaayil.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class OpenedFileManifestTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val pm: PackageManager get() = app.packageManager

    private val contentUri: Uri = Uri.parse("content://com.example.files/item/1")

    /** Whether a DEFAULT-only resolution of [intent] offers Vaayil. */
    private fun offers(intent: Intent): Boolean =
        pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .any { it.activityInfo.packageName == app.packageName }

    private fun vaayilMatch(intent: Intent): ResolveInfo? =
        pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .firstOrNull { it.activityInfo.packageName == app.packageName }

    @Test
    fun `Open with a file of unknown type offers Vaayil`() {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(contentUri, "application/octet-stream")
        assertEquals(MainActivity::class.java.name, vaayilMatch(intent)?.activityInfo?.name)
    }

    @Test
    fun `Open with a plain-text file offers Vaayil`() {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(contentUri, "text/plain")
        assertEquals(MainActivity::class.java.name, vaayilMatch(intent)?.activityInfo?.name)
    }

    @Test
    fun `Share of an unknown-type file offers Vaayil`() {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("application/octet-stream")
            .putExtra(Intent.EXTRA_STREAM, contentUri)
        assertEquals(MainActivity::class.java.name, vaayilMatch(intent)?.activityInfo?.name)
    }

    @Test
    fun `Vaayil is not offered beyond those`() {
        val fileUri = Uri.parse("file:///sdcard/Download/home.conf")
        val webUri = Uri.parse("https://example.com/home.conf")
        val viewFile = Intent(Intent.ACTION_VIEW).setDataAndType(fileUri, "application/octet-stream")
        val viewHtml = Intent(Intent.ACTION_VIEW).setDataAndType(contentUri, "text/html")
        val viewBrowsable = Intent(Intent.ACTION_VIEW)
            .setDataAndType(contentUri, "application/octet-stream")
            .addCategory(Intent.CATEGORY_BROWSABLE)
        val viewWeb = Intent(Intent.ACTION_VIEW).setDataAndType(webUri, "application/octet-stream")
        val shareText = Intent(Intent.ACTION_SEND).setType("text/plain")
        val shareMultiple = Intent(Intent.ACTION_SEND_MULTIPLE)
            .setType("application/octet-stream")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(contentUri))

        assertFalse("a file: URI", offers(viewFile))
        assertFalse("text/html", offers(viewHtml))
        assertFalse("CATEGORY_BROWSABLE", offers(viewBrowsable))
        assertFalse("an https URI", offers(viewWeb))
        assertFalse("a share of text", offers(shareText))
        assertFalse("a share of several files", offers(shareMultiple))
    }

    @Test
    fun `the launcher still opens Vaayil`() {
        // A launcher resolves without MATCH_DEFAULT_ONLY: the launcher filter carries no DEFAULT
        // category, so both Android and Robolectric drop it under that flag.
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val match = pm.queryIntentActivities(launcher, 0)
            .firstOrNull { it.activityInfo.packageName == app.packageName }
        assertNotNull("the launcher must still resolve MainActivity", match)
        assertEquals(MainActivity::class.java.name, match?.activityInfo?.name)
    }

    @Test
    fun `both filters say what choosing Vaayil does`() {
        // Robolectric builds each match from the activity's own label ("Vaayil"), not from the
        // label its filter carries, so the label is read where it is declared. Only MainActivity's
        // filters count, so a filter on another component can't hide a change to its label. The
        // unit tests run in the module directory.
        val manifest = File("src/main/AndroidManifest.xml")
        assertTrue("not in the app module: ${manifest.absolutePath}", manifest.isFile)
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        parser.setInput(manifest.reader())

        val labels = mutableMapOf<String, String>()
        var inMainActivity = false
        var inFilter = false
        var action: String? = null
        var label: String? = null
        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "activity" -> inMainActivity =
                        parser.getAttributeValue(ANDROID_NS, "name") == "dev.vaayil.app.MainActivity"
                    "intent-filter" -> if (inMainActivity) {
                        inFilter = true
                        action = null
                        label = parser.getAttributeValue(ANDROID_NS, "label")
                    }
                    "action" -> if (inFilter) action = parser.getAttributeValue(ANDROID_NS, "name")
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "intent-filter" -> if (inFilter) {
                        action?.let { labels[it] = label.orEmpty() }
                        inFilter = false
                    }
                    "activity" -> inMainActivity = false
                }
                XmlPullParser.END_DOCUMENT -> break
            }
        }

        assertEquals("Import into Vaayil", labels[Intent.ACTION_VIEW])
        assertEquals("Import into Vaayil", labels[Intent.ACTION_SEND])
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
