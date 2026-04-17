plugins {
    `kotlin-dsl`
}

group = "com.androidcompiler.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.compose.gradlePlugin)
    // XZ decompression for PrepareNativeBinariesTask (Termux .debs use tar.xz)
    implementation(libs.xz)
}

gradlePlugin {
    plugins {
        register("androidLibrary") {
            id = "androidcompiler.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidFeature") {
            id = "androidcompiler.android.feature"
            implementationClass = "AndroidFeatureConventionPlugin"
        }
        register("androidCompose") {
            id = "androidcompiler.android.compose"
            implementationClass = "AndroidComposeConventionPlugin"
        }
        register("androidHilt") {
            id = "androidcompiler.android.hilt"
            implementationClass = "AndroidHiltConventionPlugin"
        }
        register("androidNativeBinaries") {
            id = "androidcompiler.native.binaries"
            implementationClass = "AndroidNativeBinariesConventionPlugin"
        }
    }
}
