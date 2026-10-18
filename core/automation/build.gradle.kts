plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.vaayil.core.automation"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // Robolectric needs the packaged resources for manifest/service shadowing.
    testOptions { unitTests { isIncludeAndroidResources = true; isReturnDefaultValues = true } }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:tunnel-api"))
    implementation(project(":core:tunnel-wg"))
    // buildDatabase(context) in AutomationReceiver surfaces VaayilDatabase : RoomDatabase;
    // the supertype must be on the compile classpath (core:data declares Room as implementation).
    implementation(libs.room.runtime)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlin.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.junit)
}
