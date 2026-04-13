plugins {
    id("androidcompiler.android.library")
    id("androidcompiler.android.compose")
}

android {
    namespace = "com.androidcompiler.core.ui"
}

dependencies {
    implementation(project(":core:common"))
    implementation(libs.activity.compose)
}
