plugins {
    id("androidcompiler.android.feature")
}

android {
    namespace = "com.androidcompiler.feature.components"
}

dependencies {
    implementation(project(":network"))
    implementation(project(":toolchain"))
}
