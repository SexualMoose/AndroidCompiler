plugins {
    id("androidcompiler.android.library")
    id("androidcompiler.android.hilt")
}

android {
    namespace = "com.androidcompiler.core.data"
}

dependencies {
    implementation(project(":core:common"))
    implementation(libs.datastore.preferences)
}
