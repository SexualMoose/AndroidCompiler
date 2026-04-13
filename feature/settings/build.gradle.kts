plugins {
    id("androidcompiler.android.feature")
}

android {
    namespace = "com.androidcompiler.feature.settings"
}

dependencies {
    implementation(project(":toolchain"))
    implementation(libs.documentfile)
}
