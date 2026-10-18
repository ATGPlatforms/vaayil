plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}
android {
    namespace = "dev.vaayil.core.tunnel.wg"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests { isIncludeAndroidResources = true; isReturnDefaultValues = true } }
}
dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:tunnel-api"))
    implementation(project(":core:dns"))
    implementation(project(":third-party:wireguard-tunnel"))
    implementation(libs.kotlin.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.minidns.core)
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.kotlin.coroutines.test)
    testImplementation(libs.datastore.preferences)
    testImplementation(libs.turbine)
}
