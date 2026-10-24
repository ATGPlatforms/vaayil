import java.util.Properties
import java.util.regex.Pattern
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
    alias(libs.plugins.aboutLibraries)
}
    val keystoreProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
android {
    namespace = "dev.vaayil.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.vaayil.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.1.1"
    }
    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (keystoreProps.isNotEmpty()) signingConfigs.getByName("release") else null
        }
    }
    // F-Droid and reproducible-build checks reject AGP's dependency metadata in the APK;
    // the Play bundle keeps it, so includeInBundle stays true.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            // StoreAssetsTest (spec §4.6): the store images are written only with -PstoreAssets;
            // a bare -PstoreAssets (empty value) means on — only absence or =false turns it off.
            all { test ->
                val storeAssets = project.findProperty("storeAssets") as String?
                test.systemProperty("vaayil.storeAssets", if (storeAssets != null && storeAssets != "false") "true" else "false")
            }
        }
    }
    packaging {
        jniLibs {
            // GoBackend needs only libwg-go.so (GoBackend.java constructor: SharedLibraryLoader.loadSharedLibrary
            // of "wg-go"), and wireguard-go is MIT (its LICENSE). The vendored :third-party:wireguard-tunnel build
            // never produces libwg.so or libwg-quick.so, which upstream builds from wireguard-tools for its root
            // backend (upstream tools/CMakeLists.txt add_executable; ToolsInstaller installs them for
            // WgQuickBackend). wireguard-tools' COPYING is the GPL-2.0, and libwg-quick.so's source,
            // wg-quick/android.c, is GPL-2.0 only (its SPDX line). These excludes stay as a guard, because
            // GPL-2.0-only binaries cannot be combined with this GPL-3.0-or-later app.
            excludes += listOf("**/libwg.so", "**/libwg-quick.so")
        }
    }
}
// Manual notice entries (the bundled font and the native backend are not Maven deps)
// live in app/config/libraries and app/config/licenses — the plugin's documented layout.
aboutLibraries {
    collect {
        configPath = file("config")
    }
    library {
        // The detected protobuf entry's BSD-3-Clause arrives as an SPDX <<var;...>>
        // template; the app/config definition carries the concrete upstream text
        // instead (a merge would union the two licences and keep the template).
        exclusionPatterns.add(
            Pattern.compile("androidx\\.datastore:datastore-preferences-external-protobuf"),
        )
    }
}
dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:ui"))
    implementation(project(":core:config"))
    implementation(project(":core:tunnel-api"))
    implementation(project(":core:data"))
    implementation(project(":core:automation"))
    implementation(project(":core:tunnel-wg"))
    implementation(project(":third-party:wireguard-tunnel"))
    implementation(project(":feature:profiles"))
    implementation(project(":feature:tunnel"))
    implementation(project(":feature:settings"))
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.adaptive)
    implementation(libs.compose.material3.adaptive.layout)
    implementation(libs.compose.material.icons.extended) // brand mark + section glyphs (DESIGN.md iconography)
    implementation(libs.zxing.embedded)
    implementation(libs.zxing.core)
    implementation(libs.room.runtime)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.datastore.preferences)
    implementation(libs.aboutlibraries.compose.m3)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
    debugImplementation(libs.compose.ui.tooling.preview)
}
