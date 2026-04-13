plugins {
    id("androidcompiler.android.library")
    id("androidcompiler.android.hilt")
}

android {
    namespace = "com.androidcompiler.network"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:data"))
    implementation(libs.okhttp)
}
