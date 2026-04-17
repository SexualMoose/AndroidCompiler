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
            // Placed in aapt2-prefix/bin/ to match Termux's directory layout.
            // AAPT2's ELF RPATH is $ORIGIN/../lib, so aapt2-prefix/lib/ is found automatically.
            installPath = "aapt2-prefix/bin/aapt2"
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
            displayName = "Android SDK Platform (API 34)",
            version = "34",
            sizeBytes = 63_180_081,
            type = ComponentType.SDK_PLATFORM,
            sources = listOf(
                // API 34 — compatible with AAPT2 v2.19 (API 35's resources.arsc uses
                // a newer format that AAPT2 v2.19 can't parse: "illegal map type 'string' (22)").
                // URLs verified against https://dl.google.com/android/repository/repository2-3.xml.
                DownloadSource("https://dl.google.com/android/repository/platform-34-ext7_r03.zip", "google-ext7", 1),
                DownloadSource("https://dl.google.com/android/repository/platform-34-ext8_r01.zip", "google-ext8", 2),
                DownloadSource("https://dl.google.com/android/repository/platform-35_r02.zip", "google-35", 3),
                DownloadSource("https://dl.google.com/android/repository/platform-35-ext14_r01.zip", "google-35-ext14", 4)
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
    fun getAapt2Binary(): File = File(toolchainDir, "aapt2-prefix/bin/aapt2")
    fun getAapt2LibDir(): File = File(toolchainDir, "aapt2-prefix/lib")
    fun getR8Jar(): File = File(toolchainDir, "r8.jar")
    fun getAndroidJar(): File = File(toolchainDir, "android.jar")

    // ───────────────────────────────────────────────────────────────────────
    // Version-specific variants
    //
    // For per-project compile-SDK and Gradle-distribution choice. Each entry
    // corresponds to a single downloadable artifact (android.jar for an API
    // level, or a gradle-X.X-bin.zip distribution).
    //
    // These are separate from getComponents() which represents the "base
    // toolchain" (compilers, JDK, AAPT2). Variants are downloaded on demand
    // when a project requires a specific version.
    // ───────────────────────────────────────────────────────────────────────

    /** Supported Android API levels. API 34 is the recommended default
     *  because AAPT2 v2.19 can parse its resources.arsc format reliably. */
    val supportedApiLevels: List<Int> = listOf(29, 30, 31, 32, 33, 34, 35)

    /** Supported Gradle distribution versions (bundled wrapper extraction). */
    val supportedGradleVersions: List<String> = listOf(
        "7.6.4",
        "8.0.2",
        "8.1.1",
        "8.2.1",
        "8.3",
        "8.4",
        "8.5",
        "8.7",
        "8.9",
        "8.10.2",
        "8.11.1",
        "8.13"
    )

    /** The default Gradle version when a project doesn't specify one. */
    val defaultGradleVersion: String = "8.11.1"

    /** The default API level when a project doesn't specify one. */
    val defaultApiLevel: Int = 34

    /** Returns the download URL for a specific Android platform ZIP. */
    fun platformZipUrl(apiLevel: Int): String = when (apiLevel) {
        29 -> "https://dl.google.com/android/repository/platform-29_r05.zip"
        30 -> "https://dl.google.com/android/repository/platform-30_r03.zip"
        31 -> "https://dl.google.com/android/repository/platform-31_r01.zip"
        32 -> "https://dl.google.com/android/repository/platform-32_r01.zip"
        33 -> "https://dl.google.com/android/repository/platform-33-ext5_r01.zip"
        34 -> "https://dl.google.com/android/repository/platform-34-ext7_r03.zip"
        35 -> "https://dl.google.com/android/repository/platform-35_r02.zip"
        else -> throw IllegalArgumentException("Unsupported API level: $apiLevel")
    }

    /** Directory that holds per-API-level android.jar variants. */
    fun androidJarVariantsDir(): File = File(toolchainDir, "android-jar-variants").apply { mkdirs() }

    /** Returns the android.jar file path for a specific API level (may not exist yet). */
    fun androidJarForApi(apiLevel: Int): File =
        File(androidJarVariantsDir(), "android-$apiLevel.jar")

    /** core-for-system-modules.jar for a specific API level. */
    fun coreForSystemModulesForApi(apiLevel: Int): File =
        File(androidJarVariantsDir(), "core-for-system-modules-$apiLevel.jar")

    /** Returns all API levels that have a downloaded android.jar. */
    fun installedApiLevels(): List<Int> = supportedApiLevels.filter {
        androidJarForApi(it).exists() && androidJarForApi(it).length() > 0
    }

    /** Gradle distribution zip URL for a specific version. */
    fun gradleDistributionUrl(version: String): String =
        "https://services.gradle.org/distributions/gradle-$version-bin.zip"

    /** Directory that holds downloaded gradle-X.X-bin.zip files. */
    fun gradleDistDir(): File = File(toolchainDir, "gradle-dists").apply { mkdirs() }

    /** The zip file path for a specific Gradle version (may not exist yet). */
    fun gradleDistZip(version: String): File =
        File(gradleDistDir(), "gradle-$version-bin.zip")

    /** Returns the gradle-wrapper.jar extracted from a downloaded distribution. */
    fun gradleWrapperJarFor(version: String): File =
        File(gradleDistDir(), "gradle-wrapper-$version.jar")

    /** Returns all Gradle versions that have been downloaded and extracted. */
    fun installedGradleVersions(): List<String> = supportedGradleVersions.filter {
        gradleDistZip(it).exists() && gradleDistZip(it).length() > 0
    }

    // ───────────────────────────────────────────────────────────────────────
    // Bundled binaries (shipped in APK as jniLibs — always exec-allowed)
    //
    // These are the *executable* binaries that live in nativeLibraryDir,
    // which is the only exec-allowed location for a targetSdk≥29 app.
    // They are populated at install time from lib/arm64-v8a/lib*.so entries
    // inside the APK; PrepareNativeBinariesTask in build-logic produces them
    // during the Gradle build by extracting from Termux .deb packages.
    //
    // The shared libraries they depend on (libjvm.so, libprotobuf.so, etc.)
    // are downloaded at runtime to filesDir and pointed to via LD_LIBRARY_PATH.
    // ───────────────────────────────────────────────────────────────────────

    private val nativeLibDir: File
        get() = File(context.applicationInfo.nativeLibraryDir)

    /** ARM64 AAPT2 binary shipped in the APK. Always exec-allowed. */
    fun getBundledAapt2(): File? =
        File(nativeLibDir, "libaapt2.so").takeIf { it.exists() }

    /** OpenJDK 17 `java` launcher shipped in the APK. Always exec-allowed. */
    fun getBundledJavaLauncher(): File? =
        File(nativeLibDir, "libjava.so").takeIf { it.exists() }

    /** libjli.so (Java Launcher Infrastructure) shipped in the APK — loaded by the java launcher. */
    fun getBundledJli(): File? =
        File(nativeLibDir, "libjli.so").takeIf { it.exists() }

    /** True when the APK ships its own exec-capable binaries. */
    fun hasBundledBinaries(): Boolean =
        getBundledAapt2() != null && getBundledJavaLauncher() != null
}
