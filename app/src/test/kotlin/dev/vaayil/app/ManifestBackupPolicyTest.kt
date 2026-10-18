package dev.vaayil.app

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/**
 * Keys are encrypted at rest with an AndroidKeyStore-wrapped key that never leaves the
 * device, so a cloud/device-to-device backup of vaayil.db restored elsewhere cannot be
 * decrypted — and a privacy-first VPN must not mirror profiles and endpoints into
 * Google's cloud backup. Backups are disabled at the manifest level; users migrate
 * devices via .vylconf export/import instead.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class ManifestBackupPolicyTest {
    @Test
    fun `cloud backup is disabled`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val info = context.packageManager.getApplicationInfo(context.packageName, 0)
        val allowBackup = (info.flags and ApplicationInfo.FLAG_ALLOW_BACKUP) != 0
        assertFalse("allowBackup must be false in AndroidManifest.xml", allowBackup)
    }

    @Test
    fun `device-to-device transfer is excluded by data extraction rules`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Robolectric never fills ApplicationInfo.dataExtractionRulesRes (the platform populates it
        // outside the manifest parser), so the rules resource is resolved through the package.
        val rulesRes =
            context.resources.getIdentifier("data_extraction_rules", "xml", context.packageName)
        assertTrue("res/xml/data_extraction_rules.xml must be packaged", rulesRes != 0)
        val parser = context.resources.getXml(rulesRes)
        val excluded = mutableSetOf<String>()
        var inDeviceTransfer = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "device-transfer") inDeviceTransfer = true
            if (event == XmlPullParser.END_TAG && parser.name == "device-transfer") inDeviceTransfer = false
            if (event == XmlPullParser.START_TAG && parser.name == "exclude" && inDeviceTransfer) {
                excluded += parser.getAttributeValue(null, "domain")
            }
            event = parser.next()
        }
        assertEquals(setOf("root", "file", "database", "sharedpref", "external"), excluded)
    }

    @Test
    fun `main activity cannot be hijacked by task affinity`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val info = context.packageManager.getActivityInfo(ComponentName(context, MainActivity::class.java), 0)
        // application taskAffinity="" normalises to a null affinity (framework buildTaskAffinityName
        // turns an empty name into null); without it MainActivity inherits the package name.
        assertNull(info.taskAffinity)
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TASK, info.launchMode)
    }
}
