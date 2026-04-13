package com.androidcompiler.toolchain.registry

import android.content.Context
import com.androidcompiler.core.common.model.ComponentStatus
import com.androidcompiler.core.common.model.ComponentType
import com.androidcompiler.core.common.model.DownloadSource
import com.androidcompiler.core.common.model.ToolchainComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ToolchainRegistry @Inject constructor(
    @ApplicationContext private val context: Context
) {
    val toolchainDir: File
        get() = File(context.filesDir, "toolchain").apply { mkdirs() }

    fun getComponents(): List<ToolchainComponent> = listOf(
        ToolchainComponent(
            id = "ecj",
            displayName = "Eclipse Compiler for Java",
            version = "3.39.0",
            sizeBytes = 3_145_728,
            type = ComponentType.JAR,
            sources = listOf(
                DownloadSource("https://repo1.maven.org/maven2/org/eclipse/jdt/ecj/3.39.0/ecj-3.39.0.jar", "maven_central", 1),
                DownloadSource("https://repo.eclipse.org/content/repositories/eclipse-releases/org/eclipse/jdt/ecj/3.39.0/ecj-3.39.0.jar", "eclipse", 2)
            ),
            sha256 = "",
            installPath = "ecj.jar"
        ),
        ToolchainComponent(
            id = "kotlinc",
            displayName = "Kotlin Compiler",
            version = "2.1.0",
            sizeBytes = 68_157_440,
            type = ComponentType.JAR,
            sources = listOf(
                DownloadSource("https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-compiler-embeddable/2.1.0/kotlin-compiler-embeddable-2.1.0.jar", "maven_central", 1)
            ),
            sha256 = "",
            installPath = "kotlin-compiler-embeddable.jar"
        ),
        ToolchainComponent(
            id = "kotlin-stdlib",
            displayName = "Kotlin Standard Library",
            version = "2.1.0",
            sizeBytes = 1_887_437,
            type = ComponentType.JAR,
            sources = listOf(
                DownloadSource("https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.1.0/kotlin-stdlib-2.1.0.jar", "maven_central", 1)
            ),
            sha256 = "",
            installPath = "kotlin-stdlib.jar"
        ),
        ToolchainComponent(
            id = "kotlin-script-runtime",
            displayName = "Kotlin Script Runtime",
            version = "2.1.0",
            sizeBytes = 41_984,
            type = ComponentType.JAR,
            sources = listOf(
                DownloadSource("https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-script-runtime/2.1.0/kotlin-script-runtime-2.1.0.jar", "maven_central", 1)
            ),
            sha256 = "",
            installPath = "kotlin-script-runtime.jar"
        ),
        ToolchainComponent(
            id = "aapt2",
            displayName = "AAPT2 (ARM64)",
            version = "8.7.3",
            sizeBytes = 8_388_608,
            type = ComponentType.NATIVE_BINARY,
            sources = listOf(
                DownloadSource("https://dl.google.com/android/maven2/com/android/tools/build/aapt2/8.7.3-12006047/aapt2-8.7.3-12006047-linux.jar", "google_maven", 1)
            ),
            sha256 = "",
            installPath = "aapt2"
        ),
        ToolchainComponent(
            id = "d8",
            displayName = "D8 DEX Compiler",
            version = "8.7.3",
            sizeBytes = 20_971_520,
            type = ComponentType.JAR,
            sources = listOf(
                DownloadSource("https://dl.google.com/android/maven2/com/android/tools/r8/8.7.3/r8-8.7.3.jar", "google_maven", 1),
                DownloadSource("https://repo1.maven.org/maven2/com/android/tools/r8/8.7.3/r8-8.7.3.jar", "maven_central", 2)
            ),
            sha256 = "",
            installPath = "r8.jar"
        ),
        ToolchainComponent(
            id = "android-jar",
            displayName = "Android SDK Platform (API 35)",
            version = "35",
            sizeBytes = 36_700_160,
            type = ComponentType.SDK_PLATFORM,
            sources = listOf(
                DownloadSource("https://dl.google.com/android/repository/platform-35_r01.zip", "google", 1)
            ),
            sha256 = "",
            installPath = "android.jar"
        )
    )

    fun getComponentFile(component: ToolchainComponent): File =
        File(toolchainDir, component.installPath)

    fun getComponentStatus(component: ToolchainComponent): ComponentStatus {
        val file = getComponentFile(component)
        return if (file.exists() && file.length() > 0) {
            ComponentStatus.Installed
        } else {
            ComponentStatus.NotInstalled
        }
    }

    fun isAllInstalled(): Boolean =
        getComponents().all { getComponentStatus(it) is ComponentStatus.Installed }

    fun getEcjJar(): File = File(toolchainDir, "ecj.jar")
    fun getKotlincJar(): File = File(toolchainDir, "kotlin-compiler-embeddable.jar")
    fun getKotlinStdlibJar(): File = File(toolchainDir, "kotlin-stdlib.jar")
    fun getKotlinScriptRuntimeJar(): File = File(toolchainDir, "kotlin-script-runtime.jar")
    fun getAapt2Binary(): File = File(toolchainDir, "aapt2")
    fun getR8Jar(): File = File(toolchainDir, "r8.jar")
    fun getAndroidJar(): File = File(toolchainDir, "android.jar")
}
