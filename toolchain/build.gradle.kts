plugins {
    id("androidcompiler.android.library")
    id("androidcompiler.android.hilt")
}

android {
    namespace = "com.androidcompiler.toolchain"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:data"))
    implementation(libs.apksig)
}
