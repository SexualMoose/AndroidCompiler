plugins {
    id("androidcompiler.android.feature")
}

android {
    namespace = "com.androidcompiler.feature.monitor"
}

dependencies {
    implementation(project(":toolchain"))
}
