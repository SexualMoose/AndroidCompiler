plugins {
    id("androidcompiler.android.feature")
}

android {
    namespace = "com.androidcompiler.feature.compiler"
}

dependencies {
    implementation(project(":toolchain"))
    implementation(libs.documentfile)
}
