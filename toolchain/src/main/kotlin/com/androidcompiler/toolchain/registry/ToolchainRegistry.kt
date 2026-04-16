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
            version = "13.0.0.6",
            sizeBytes = 7_800_000, // ~7.5MB total (aapt2 + aapt + deps)
            type = ComponentType.NATIVE_BINARY,
            sources = listOf(
                // Termux's ARM64 AAPT2 — downloads multiple .deb packages
                DownloadSource("termux://aapt2", "termux", 1)
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
            id = "jdk",
            displayName = "OpenJDK 17 (Termux/Bionic)",
            version = "17",
            sizeBytes = 96_680_976, // ~96MB (JDK + native deps)
            type = ComponentType.JDK_ARCHIVE,
            sources = listOf(
                DownloadSource("termux://openjdk-17", "termux", 1)
            ),
            sha256 = "",
            installPath = "jdk17"
        ),
        ToolchainComponent(
            id = "gradle-wrapper",
            displayName = "Gradle Wrapper",
            version = "8.11.1",
            sizeBytes = 43_583,
            type = ComponentType.JAR,
            sources = listOf(
                DownloadSource("https://raw.githubusercontent.com/gradle/gradle/v8.11.1/gradle/wrapper/gradle-wrapper.jar", "github", 1),
                DownloadSource("https://services.gradle.org/distributions/gradle-8.11.1-bin.zip", "gradle", 2)
            ),
            sha256 = "",
            installPath = "gradle-wrapper.jar"
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
        if (component.type == ComponentType.JDK_ARCHIVE) {
            return if (isJdkInstalled()) ComponentStatus.Installed
                   else ComponentStatus.NotInstalled
        }
        val file = getComponentFile(component)
        return if (file.exists() && file.length() > 0) {
            ComponentStatus.Installed
        } else {
            ComponentStatus.NotInstalled
        }
    }

    fun isAllInstalled(): Boolean =
        getComponents().all { getComponentStatus(it) is ComponentStatus.Installed }

    fun getJdkDir(): File = File(toolchainDir, "jdk17")

    /**
     * Returns the JAVA_HOME path for the bundled JDK.
     * Checks the Termux-extracted JDK in multiple possible structures.
     */
    fun getJavaHome(): File? {
        val jdkDir = getJdkDir()
        if (!jdkDir.exists()) return null

        // Direct: jdk17/bin/java
        if (File(jdkDir, "bin/java").exists()) return jdkDir

        // Nested JVM: jdk17/lib/jvm/java-17-openjdk/bin/java
        val jvmDir = File(jdkDir, "lib/jvm")
        if (jvmDir.exists()) {
            jvmDir.listFiles()?.forEach { child ->
                if (File(child, "bin/java").exists()) return child
            }
        }

        // Single subdirectory: jdk17/jdk-17.0.x/bin/java
        jdkDir.listFiles()?.filter { it.isDirectory }?.forEach { child ->
            if (File(child, "bin/java").exists()) return child
        }

        return null
    }

    fun isJdkInstalled(): Boolean = getJavaHome() != null

    // --- Installed version tracking ---
    // Stores which version is actually installed (may differ from registry default after updates)
    private val versionsFile: File
        get() = File(toolchainDir, "installed_versions.properties")

    fun getInstalledVersion(componentId: String): String? {
        if (!versionsFile.exists()) return null
        val props = java.util.Properties()
        try {
            versionsFile.inputStream().use { props.load(it) }
        } catch (_: Exception) { return null }
        return props.getProperty(componentId)
    }

    fun saveInstalledVersion(componentId: String, version: String) {
        val props = java.util.Properties()
        try {
            if (versionsFile.exists()) versionsFile.inputStream().use { props.load(it) }
        } catch (_: Exception) { }
        props.setProperty(componentId, version)
        try {
            versionsFile.outputStream().use { props.store(it, "Installed toolchain component versions") }
        } catch (_: Exception) { }
    }

    fun getEcjJar(): File = File(toolchainDir, "ecj.jar")
    fun getKotlincJar(): File = File(toolchainDir, "kotlin-compiler-embeddable.jar")
    fun getKotlinStdlibJar(): File = File(toolchainDir, "kotlin-stdlib.jar")
    fun getKotlinScriptRuntimeJar(): File = File(toolchainDir, "kotlin-script-runtime.jar")
    fun getAapt2Binary(): File = File(toolchainDir, "aapt2")
    fun getR8Jar(): File = File(toolchainDir, "r8.jar")
    fun getAndroidJar(): File = File(toolchainDir, "android.jar")
}
