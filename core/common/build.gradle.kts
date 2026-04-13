plugins {
    id("androidcompiler.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.androidcompiler.core.common"
}

dependencies {
    implementation(libs.serialization.json)
}
