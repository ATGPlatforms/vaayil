package dev.vaayil.app

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * The notice screen's generated data (C10): concrete licence texts only — the
 * protobuf runtime's repackaged BSD-3-Clause arrived as an SPDX <<var;...>>
 * template — and the WireGuard copyright line in both the manual definitions
 * and the generated resource.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class AboutLibrariesContentTest {
    @Test
    fun `the notice screen data covers the shipped components`() {
        val libraries = generatedData().getJSONArray("libraries")
        val uniqueIds = (0 until libraries.length()).map { libraries.getJSONObject(it).getString("uniqueId") }
        listOf(
            "wireguard-android",            // vendored tunnel library — manual entry
            "com.squareup.okhttp3:okhttp",
            "SchibstedGrotesk",             // bundled font — manual entry
            "wireguard-go",                 // native backend — manual entry
            "go",                           // Go runtime and x/ modules inside libwg-go.so — manual entry
        ).forEach { expected ->
            assertTrue("expected a '$expected' entry in the licences list", expected in uniqueIds)
        }
    }

    /**
     * The Maven coordinate keeps its entry, but with the concrete upstream BSD
     * text: exactly one row, one licence id, no SPDX template anywhere in the
     * dataset (the templated entry loses its only user and must disappear).
     */
    @Test
    fun protobufNoticeIsConcrete() {
        val data = generatedData()
        val libraries = data.getJSONArray("libraries")
        val rows = (0 until libraries.length()).map { libraries.getJSONObject(it) }
            .filter { it.getString("uniqueId") == PROTOBUF_UNIQUE_ID }
        assertEquals("exactly one library entry for $PROTOBUF_UNIQUE_ID", 1, rows.size)
        val licenceIds = rows.single().getJSONArray("licenses").toList()
        assertEquals(listOf("bsd_3_clause_protobuf"), licenceIds)

        val licences = data.getJSONObject("licenses")
        val content = licenceIds.single().let { licences.getJSONObject(it).getString("content") }
        assertFalse("the protobuf notice still carries an SPDX template", content.contains("<<var;"))
        assertTrue("the protobuf notice must be the upstream BSD text", content.contains("Redistribution and use"))
        assertTrue(
            "the protobuf notice must carry its copyright line",
            content.contains("Copyright 2008 Google Inc.  All rights reserved."),
        )

        licences.keys().forEach { key ->
            val licence = licences.getJSONObject(key).optString("content")
            assertFalse("licence $key still carries an SPDX template", licence.contains("<<var;"))
        }
    }

    /**
     * `Copyright © WireGuard LLC` is the spec's exact line: it sits at the top
     * of both licence contents and in both descriptions, in the checked-in
     * definitions and in the generated resource the screen renders.
     */
    @Test
    fun wireGuardNoticesShowCopyright() {
        val line = "Copyright \u00a9 WireGuard LLC"
        val config = File(repoRoot, "app/config")
        listOf("wireguard_android", "wireguard_go").forEach { name ->
            val library = JSONObject(File(config, "libraries/$name.json").readText())
            assertTrue("$name.json must carry the WireGuard copyright line", library.getString("description").contains(line))
        }

        listOf("apache_2_0_wireguard_android", "mit_wireguard_go").forEach { file ->
            val licence = JSONObject(File(config, "licenses/$file.json").readText())
            assertTrue(
                "$file.json must open with the WireGuard copyright line",
                licence.getString("content").startsWith("$line\n\n"),
            )
        }

        val data = generatedData()
        val libraries = data.getJSONArray("libraries")
        val licences = data.getJSONObject("licenses")
        listOf("wireguard-android", "wireguard-go").forEach { uniqueId ->
            val library = (0 until libraries.length()).map { libraries.getJSONObject(it) }
                .single { it.getString("uniqueId") == uniqueId }
            assertTrue("$uniqueId's rendered description lost the copyright line", library.getString("description").contains(line))
            val content = library.getJSONArray("licenses").toList()
                .single().let { licences.getJSONObject(it).getString("content") }
            assertTrue("$uniqueId's rendered licence lost the copyright line", content.startsWith("$line\n\n"))
        }
    }

    /**
     * OkHttp compiles the Public Suffix List into
     * `okhttp3/internal/publicsuffix/publicsuffixes.gz`, which the release APK
     * ships; when the resource is on the test classpath the notice data must
     * name it, link the list and carry the complete MPL-2.0 text. (The APK
     * listing is the authoritative ship evidence; this gates on the resource.)
     */
    @Test
    fun shippedPublicSuffixListIsNoticed() {
        assumeTrue(
            "OkHttp's publicsuffixes.gz is not on the classpath",
            checkNotNull(javaClass.classLoader).getResource("okhttp3/internal/publicsuffix/publicsuffixes.gz") != null,
        )
        val data = generatedData()
        val libraries = data.getJSONArray("libraries")
        val rows = (0 until libraries.length()).map { libraries.getJSONObject(it) }
            .filter { it.getString("uniqueId") == "public-suffix-list" }
        assertEquals("exactly one Public Suffix List entry", 1, rows.size)
        val library = rows.single()
        assertEquals("https://publicsuffix.org/", library.getString("website"))
        assertEquals(listOf("mpl_2_0_public_suffix_list"), library.getJSONArray("licenses").toList())

        val licence = data.getJSONObject("licenses").getJSONObject("mpl_2_0_public_suffix_list")
        assertEquals("MPL-2.0", licence.getString("spdxId"))
        val content = licence.getString("content")
        assertTrue(
            "the notice must name the bundled resource",
            content.contains("Note that publicsuffixes.gz is compiled from The Public Suffix List:"),
        )
        assertTrue(content.contains("https://publicsuffix.org/list/public_suffix_list.dat"))
        assertTrue("the MPL-2.0 text must be complete", content.contains("Mozilla Public License Version 2.0"))
        assertTrue(
            "the MPL-2.0 text must run to its last exhibit",
            content.contains("Exhibit B - \"Incompatible With Secondary Licenses\" Notice"),
        )
    }

    private fun generatedData(): JSONObject {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val id = context.resources.getIdentifier("aboutlibraries", "raw", context.packageName)
        assertTrue("the aboutlibraries raw resource must be generated", id != 0)
        val json = context.resources.openRawResource(id).use { it.readBytes().decodeToString() }
        return JSONObject(json)
    }

    private fun JSONArray.toList(): List<String> = (0 until length()).map { getString(it) }

    /** Gradle runs unit tests with the app module as the working directory. */
    private val repoRoot: File by lazy {
        generateSequence(File(checkNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("AboutLibrariesContentTest needs a repo checkout above ${System.getProperty("user.dir")}")
    }

    private companion object {
        const val PROTOBUF_UNIQUE_ID = "androidx.datastore:datastore-preferences-external-protobuf"
    }
}
