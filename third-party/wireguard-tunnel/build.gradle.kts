// SPDX-License-Identifier: GPL-3.0-or-later

// wireguard-android's tunnel library at 1.0.20260102, vendored. The upstream code and the patches to it stay
// Apache-2.0 (see LICENSE); Vaayil's own files in this module are GPL-3.0-or-later. README.md's Licensing
// section says which is which.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.wireguard.android.tunnel"
    compileSdk = 36
    ndkVersion = "27.0.12077973"
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    externalNativeBuild {
        cmake {
            path("tools/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    lint {
        // Upstream code trips NewApi (GoBackend's isAlwaysOn/isLockdownEnabled), which upstream disables
        // module-wide (upstream tunnel/build.gradle.kts at 1.0.20260102: lint { disable += "NewApi" }).
        // A baseline keeps those four upstream findings and fails on anything new.
        baseline = file("lint-baseline.xml")
    }
    buildTypes {
        all {
            externalNativeBuild {
                cmake {
                    targets("libwg-go.so")
                    arguments(
                        "-DGRADLE_USER_HOME=${gradle.gradleUserHomeDir}",
                        "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                    )
                }
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.collection)
    compileOnly(libs.jsr305)
    testImplementation(libs.junit)
}

// go vet and go test -race for libwg-go's dnsintercept package, on the host toolchain the Makefile downloads.
val goTest = tasks.register<Exec>("goTest") {
    group = "verification"
    description = "Runs go vet and go test -race on libwg-go's dnsintercept package."
    val goDir = layout.projectDirectory.dir("tools/libwg-go")
    val goBuildDir = layout.buildDirectory.dir("go-test")
    val marker = layout.buildDirectory.file("go-test/passed.txt")
    inputs.files(
        fileTree(goDir) {
            include("**/*.go", "go.mod", "go.sum", "Makefile", "*.diff")
            exclude("build/**", "out/**")
        },
    ).withPropertyName("goSources").withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(marker).withPropertyName("marker")
    workingDir(goDir)
    commandLine(
        "make",
        "test",
        "BUILDDIR=${goBuildDir.get().asFile.absolutePath}",
        "GRADLE_USER_HOME=${gradle.gradleUserHomeDir.absolutePath}",
    )
    doLast { marker.get().asFile.writeText("go vet and go test -race passed\n") }
}
tasks.named("check") { dependsOn(goTest) }
