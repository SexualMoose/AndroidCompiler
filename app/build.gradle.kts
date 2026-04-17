plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    // Downloads Termux .debs and extracts aapt2 + java launcher into jniLibs.
    // The resulting files are lib*.so so Android's installer places them in
    // nativeLibraryDir (exec-allowed on all targetSdk levels).
    id("androidcompiler.native.binaries")
}

nativeBinaries {
    packageUrls.set(listOf(
        // AAPT2 (Android Asset Packaging Tool v2) — ARM64, build tools 13.0.0.6
        "https://packages.termux.dev/apt/termux-main/pool/main/a/aapt2/aapt2_13.0.0.6-23_aarch64.deb",
        // OpenJDK 17 — ships the `java` launcher and libjli.so
        "https://packages.termux.dev/apt/termux-main/pool/main/o/openjdk-17/openjdk-17_17.0.18_aarch64.deb"
    ))
}

android {
    namespace = "com.androidcompiler"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.androidcompiler"
        minSdk = 28
        // Modern targetSdk — Android 15. We no longer rely on SELinux loopholes
        // in older domains because executable binaries (aapt2, java) now ship
        // bundled in the APK's native library dir, which is exec-allowed for
        // every targetSdk. See app/src/main/jniLibs/arm64-v8a/lib*.so.
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"

        // Only build ARM64 binaries — that's the only ABI we target.
        ndk {
            abiFilters += "arm64-v8a"
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // jniLibs source dir is wired by the androidcompiler.native.binaries plugin

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            // CRITICAL: Keep bundled binaries uncompressed so Android extracts them
            // directly to nativeLibraryDir (which is exec-allowed). With the default
            // compression, they'd get re-extracted to app_data_file at runtime and
            // fail SELinux exec checks.
            useLegacyPackaging = true
            // Don't let AGP strip these "libraries" — they're actually binaries
            // and their entry points aren't standard .so exports.
            keepDebugSymbols += "**/libaapt2.so"
            keepDebugSymbols += "**/libjava.so"
            keepDebugSymbols += "**/libjli.so"
        }
    }
}

dependencies {
    // Modules
    implementation(project(":core:common"))
    implementation(project(":core:ui"))
    implementation(project(":core:data"))
    implementation(project(":feature:compiler"))
    implementation(project(":feature:monitor"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:components"))
    implementation(project(":toolchain"))
    implementation(project(":network"))

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    // AndroidX
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // Serialization (for navigation routes)
    implementation(libs.serialization.json)
}
