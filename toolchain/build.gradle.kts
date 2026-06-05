plugins {
    id("androidcompiler.android.library")
    id("androidcompiler.android.hilt")
}

android {
    namespace = "com.androidcompiler.toolchain"

    defaultConfig {
        // AUTHORITATIVE runtime pin for the OpenJDK 17 patch version that the
        // runtime JDK download (TermuxJdkInstaller) MUST fetch. It has to equal
        // the patch version of the launcher binaries baked into the APK — a
        // launcher↔libjvm.so patch mismatch aborts JVM init and every compile
        // fails. The bundled-binary side is pinned by `bundledJdkVersion` in
        // app/build.gradle.kts; KEEP THESE TWO LITERALS IDENTICAL when bumping.
        // (TermuxJdkInstaller lives in this module and can't read the app's
        // BuildConfig, so the pin is duplicated here on purpose.)
        buildConfigField("String", "BUNDLED_JDK_VERSION", "\"17.0.19\"")
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:data"))
    implementation(project(":network"))
    implementation(libs.apksig)
    implementation(libs.okhttp)
    implementation(libs.xz)
}
