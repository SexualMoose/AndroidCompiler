package com.androidcompiler.feature.components.ui

import androidx.lifecycle.ViewModel
import com.androidcompiler.core.common.model.ComponentStatus
import com.androidcompiler.core.common.model.ComponentType
import com.androidcompiler.core.common.model.DownloadSource
import com.androidcompiler.core.common.model.ToolchainComponent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class ComponentsViewModel @Inject constructor() : ViewModel() {

    private val _components = MutableStateFlow(getDefaultComponents())
    val components: StateFlow<List<Pair<ToolchainComponent, ComponentStatus>>> = _components.asStateFlow()

    fun refreshAll() {
        // TODO: Check actual installation status and available updates
    }

    fun download(componentId: String) {
        // TODO: Wire up to ComponentDownloader
    }

    private fun getDefaultComponents(): List<Pair<ToolchainComponent, ComponentStatus>> {
        val registry = listOf(
            ToolchainComponent(
                id = "ecj",
                displayName = "Eclipse Compiler for Java",
                version = "3.39.0",
                sizeBytes = 3_145_728,
                type = ComponentType.JAR,
                sources = listOf(
                    DownloadSource(
                        url = "https://repo1.maven.org/maven2/org/eclipse/jdt/ecj/3.39.0/ecj-3.39.0.jar",
                        mirror = "maven_central",
                        priority = 1
                    )
                ),
                sha256 = "",
                installPath = "toolchain/ecj.jar"
            ),
            ToolchainComponent(
                id = "kotlinc",
                displayName = "Kotlin Compiler",
                version = "2.1.0",
                sizeBytes = 68_157_440,
                type = ComponentType.JAR,
                sources = listOf(
                    DownloadSource(
                        url = "https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-compiler-embeddable/2.1.0/kotlin-compiler-embeddable-2.1.0.jar",
                        mirror = "maven_central",
                        priority = 1
                    )
                ),
                sha256 = "",
                installPath = "toolchain/kotlin-compiler-embeddable.jar"
            ),
            ToolchainComponent(
                id = "kotlin-stdlib",
                displayName = "Kotlin Standard Library",
                version = "2.1.0",
                sizeBytes = 1_887_437,
                type = ComponentType.JAR,
                sources = listOf(
                    DownloadSource(
                        url = "https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.1.0/kotlin-stdlib-2.1.0.jar",
                        mirror = "maven_central",
                        priority = 1
                    )
                ),
                sha256 = "",
                installPath = "toolchain/kotlin-stdlib.jar"
            ),
            ToolchainComponent(
                id = "aapt2",
                displayName = "AAPT2 (ARM64)",
                version = "8.7.3",
                sizeBytes = 8_388_608,
                type = ComponentType.NATIVE_BINARY,
                sources = listOf(
                    DownloadSource(
                        url = "https://github.com/nicholasng-7642/android-tools/releases/latest",
                        mirror = "github",
                        priority = 1
                    )
                ),
                sha256 = "",
                installPath = "toolchain/aapt2"
            ),
            ToolchainComponent(
                id = "d8",
                displayName = "D8 DEX Compiler",
                version = "8.7.3",
                sizeBytes = 20_971_520,
                type = ComponentType.JAR,
                sources = listOf(
                    DownloadSource(
                        url = "https://dl.google.com/android/maven2/com/android/tools/r8/8.7.3/r8-8.7.3.jar",
                        mirror = "google_maven",
                        priority = 1
                    )
                ),
                sha256 = "",
                installPath = "toolchain/r8.jar"
            ),
            ToolchainComponent(
                id = "apksigner",
                displayName = "APK Signer Library",
                version = "8.7.3",
                sizeBytes = 1_048_576,
                type = ComponentType.JAR,
                sources = listOf(
                    DownloadSource(
                        url = "https://dl.google.com/android/maven2/com/android/tools/build/apksigner/8.7.3/apksigner-8.7.3.jar",
                        mirror = "google_maven",
                        priority = 1
                    )
                ),
                sha256 = "",
                installPath = "toolchain/apksigner.jar"
            ),
            ToolchainComponent(
                id = "android-jar",
                displayName = "Android SDK Platform (API 35)",
                version = "35",
                sizeBytes = 36_700_160,
                type = ComponentType.SDK_PLATFORM,
                sources = listOf(
                    DownloadSource(
                        url = "https://dl.google.com/android/repository/platform-35_r01.zip",
                        mirror = "google",
                        priority = 1
                    )
                ),
                sha256 = "",
                installPath = "toolchain/android.jar"
            )
        )

        return registry.map { it to ComponentStatus.NotInstalled as ComponentStatus }
    }
}
