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

// ── SINGLE SOURCE OF TRUTH for the bundled OpenJDK 17 patch version ──────────
// The launcher binaries baked into the APK (libjava.so / libjli.so) come from
// THIS exact openjdk-17 .deb. The runtime JDK download (TermuxJdkInstaller) MUST
// resolve the identical patch version — a launcher↔libjvm.so patch mismatch
// aborts JVM init and every Gradle compile fails. Both the bundled-binary URLs
// below AND the runtime pin (toolchain BuildConfig.BUNDLED_JDK_VERSION) derive
// from this one string, so bumping it here updates everything atomically.
val bundledJdkVersion = "17.0.19"

nativeBinaries {
    // Ship native binaries for both primary Android ABIs.
    // arm64-v8a covers every modern phone (S26 Ultra, etc.); x86_64 covers
    // Chromebooks, Windows Subsystem for Android, and the stock Android Studio
    // emulator for iterative development.
    // These are the PINNED versions. They're a starting point, not a hard
    // requirement: Termux's pool keeps only the latest patch of each package, so
    // a pinned URL 404s after a bump. The prepareNativeBinaries task self-heals by
    // (1) seeding committed prebuilts from prebuilts/native-jniLibs/ first, and
    // (2) if a pinned URL fails, resolving the current .deb from the pool listing.
    // Bump these when convenient, but the build no longer breaks if they go stale.
    // openjdk-17 URLs are built from `bundledJdkVersion` so the bundled launcher
    // and the runtime-downloaded JDK never drift apart.
    val pool = "https://packages.termux.dev/apt/termux-main/pool/main"
    packagesByAbi.set(mapOf(
        "arm64-v8a" to listOf(
            "$pool/a/aapt2/aapt2_13.0.0.6-23_aarch64.deb",
            "$pool/o/openjdk-17/openjdk-17_${bundledJdkVersion}_aarch64.deb"
        ),
        "x86_64" to listOf(
            "$pool/a/aapt2/aapt2_13.0.0.6-23_x86_64.deb",
            "$pool/o/openjdk-17/openjdk-17_${bundledJdkVersion}_x86_64.deb"
        )
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
        versionCode = 7
        versionName = "1.1.5"

        // Exposed to runtime so the in-app UI / diagnostics can show which JDK
        // the APK's launcher was built against. The authoritative runtime pin
        // for the JDK DOWNLOAD lives in the :toolchain module's BuildConfig
        // (same literal), because TermuxJdkInstaller can't read the app's
        // BuildConfig. Keep both in sync via `bundledJdkVersion` above.
        buildConfigField("String", "BUNDLED_JDK_VERSION", "\"$bundledJdkVersion\"")

        // Ship both primary Android ABIs. arm64-v8a is the real-device target,
        // x86_64 lets Android Studio's stock emulator run the same APK.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
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
        buildConfig = true
    }

    packaging {
        jniLibs {
            // CRITICAL — DO NOT flip this to false. useLegacyPackaging=true sets
            // extractNativeLibs=true, so the installer EXTRACTS our bundled
            // binaries (libjava.so, libjli.so, libaapt2.so) into the app's
            // nativeLibraryDir at install time. nativeLibraryDir is the only
            // exec-allowed location on targetSdk>=29, and exec'ing the launcher
            // from there is exactly what makes on-device compilation work.
            // (The libs ARE compressed inside the APK — that's fine; what matters
            // is that they're extracted to the exec-allowed dir, not mmap'd
            // uncompressed from the APK, which would NOT be exec-allowed.)
            // Setting useLegacyPackaging=false (extractNativeLibs=false) would
            // leave them only inside the APK → no exec path → every build fails.
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
