package com.androidcompiler.toolchain.pipeline

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Analyzes an extracted project to determine its type and build requirements.
 */
@Singleton
class ProjectAnalyzer @Inject constructor() {

    data class ProjectInfo(
        val type: ProjectType,
        val projectDir: File,
        val packageName: String?,
        val hasGradleWrapper: Boolean,
        val hasSettingsGradle: Boolean,
        val hasBuildGradle: Boolean,
        val sourceFiles: List<File>,
        val manifestFile: File?,
        val resourceDir: File?,
        /** Gradle version required by the project, parsed from gradle-wrapper.properties. */
        val requiredGradleVersion: String? = null,
        /** compileSdk from build.gradle(.kts). Null if not specified. */
        val requiredCompileSdk: Int? = null,
        /** minSdk from build.gradle(.kts). Informational — used for warnings. */
        val requiredMinSdk: Int? = null,
        /** AGP version from settings.gradle plugins block or build.gradle classpath. */
        val requiredAgpVersion: String? = null
    )

    enum class ProjectType {
        /** Full Gradle project with build.gradle, settings.gradle, dependencies */
        GRADLE_PROJECT,
        /** Simple source-only project that can be compiled with raw ECJ/kotlinc */
        SIMPLE_PROJECT,
        /** Unknown structure */
        UNKNOWN
    }

    fun analyze(projectDir: File): ProjectInfo {
        val hasGradleWrapper = File(projectDir, "gradlew").exists() ||
                File(projectDir, "gradle/wrapper/gradle-wrapper.properties").exists()
        val hasSettingsGradle = File(projectDir, "settings.gradle.kts").exists() ||
                File(projectDir, "settings.gradle").exists()
        val hasBuildGradle = File(projectDir, "build.gradle.kts").exists() ||
                File(projectDir, "build.gradle").exists()

        // Check for app module build files (multi-module Gradle project)
        val hasAppBuildGradle = File(projectDir, "app/build.gradle.kts").exists() ||
                File(projectDir, "app/build.gradle").exists()

        val manifestFile = findManifest(projectDir)
        val resourceDir = findResourceDir(projectDir)
        val sourceFiles = findSourceFiles(projectDir)
        val packageName = manifestFile?.let { extractPackageName(it) }
        val requiredGradleVersion = detectGradleVersion(projectDir)
        val requiredCompileSdk = detectCompileSdk(projectDir)
        val requiredMinSdk = detectMinSdk(projectDir)
        val requiredAgpVersion = detectAgpVersion(projectDir)

        // Determine if this needs Gradle or can be compiled directly
        val needsGradle = hasSettingsGradle || hasAppBuildGradle ||
                hasExternalDependencies(projectDir)

        val type = when {
            needsGradle -> ProjectType.GRADLE_PROJECT
            sourceFiles.isNotEmpty() && manifestFile != null -> ProjectType.SIMPLE_PROJECT
            else -> ProjectType.UNKNOWN
        }

        return ProjectInfo(
            type = type,
            projectDir = projectDir,
            packageName = packageName,
            hasGradleWrapper = hasGradleWrapper,
            hasSettingsGradle = hasSettingsGradle,
            hasBuildGradle = hasBuildGradle,
            sourceFiles = sourceFiles,
            manifestFile = manifestFile,
            resourceDir = resourceDir,
            requiredGradleVersion = requiredGradleVersion,
            requiredCompileSdk = requiredCompileSdk,
            requiredMinSdk = requiredMinSdk,
            requiredAgpVersion = requiredAgpVersion
        )
    }

    /** Parses gradle/wrapper/gradle-wrapper.properties to find the required Gradle version. */
    private fun detectGradleVersion(projectDir: File): String? {
        val props = File(projectDir, "gradle/wrapper/gradle-wrapper.properties")
        if (!props.exists()) return null
        val content = try { props.readText() } catch (_: Exception) { return null }
        // Matches: distributionUrl=https://...gradle-8.11.1-bin.zip
        val match = Regex("""gradle-(\d+\.\d+(?:\.\d+)?)-(bin|all)\.zip""").find(content)
        return match?.groupValues?.get(1)
    }

    /** Scans build.gradle(.kts) files for `compileSdk = N` / `compileSdkVersion N`. */
    private fun detectCompileSdk(projectDir: File): Int? {
        return findCompileSdkInAnyBuildFile(projectDir)
    }

    /** Scans build.gradle(.kts) files for `minSdk = N` / `minSdkVersion N`. */
    private fun detectMinSdk(projectDir: File): Int? {
        val buildFiles = findBuildFiles(projectDir)
        val pattern = Regex("""\bminSdk(?:Version)?\s*[=\s]\s*(\d+)""")
        for (file in buildFiles) {
            val content = try { file.readText() } catch (_: Exception) { continue }
            pattern.find(content)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        }
        return null
    }

    /** Scans build.gradle(.kts) files for the AGP plugin version. */
    private fun detectAgpVersion(projectDir: File): String? {
        val files = findBuildFiles(projectDir) + listOfNotNull(
            File(projectDir, "settings.gradle.kts").takeIf { it.exists() },
            File(projectDir, "settings.gradle").takeIf { it.exists() }
        )
        // Pattern 1: classpath "com.android.tools.build:gradle:X.Y.Z"
        val classpathPattern = Regex("""com\.android\.tools\.build:gradle:(\d+\.\d+\.\d+)""")
        // Pattern 2: id "com.android.application" version "X.Y.Z"
        val idPattern = Regex("""id\s*\(?\s*["']com\.android\.(?:application|library)["']\s*\)?\s*(?:version\s*\(?\s*)?["'](\d+\.\d+\.\d+)""")
        for (file in files) {
            val content = try { file.readText() } catch (_: Exception) { continue }
            classpathPattern.find(content)?.groupValues?.getOrNull(1)?.let { return it }
            idPattern.find(content)?.groupValues?.getOrNull(1)?.let { return it }
        }
        return null
    }

    private fun findCompileSdkInAnyBuildFile(projectDir: File): Int? {
        val buildFiles = findBuildFiles(projectDir)
        val pattern = Regex("""\bcompileSdk(?:Version)?\s*[=\s]\s*(\d+)""")
        for (file in buildFiles) {
            val content = try { file.readText() } catch (_: Exception) { continue }
            pattern.find(content)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        }
        return null
    }

    private fun findBuildFiles(projectDir: File): List<File> = listOfNotNull(
        File(projectDir, "app/build.gradle.kts").takeIf { it.exists() },
        File(projectDir, "app/build.gradle").takeIf { it.exists() },
        File(projectDir, "build.gradle.kts").takeIf { it.exists() },
        File(projectDir, "build.gradle").takeIf { it.exists() }
    )

    private fun findResourceDir(projectDir: File): File? {
        val candidates = listOf(
            "app/src/main/res", "src/main/res", "res"
        )
        return candidates.map { File(projectDir, it) }.firstOrNull { it.exists() && it.isDirectory }
    }

    private fun findSourceFiles(projectDir: File): List<File> {
        val sourceDirs = listOf(
            "app/src/main/java", "app/src/main/kotlin",
            "src/main/java", "src/main/kotlin",
            "src", "java", "kotlin"
        )
        return sourceDirs
            .map { File(projectDir, it) }
            .filter { it.exists() }
            .flatMap { dir ->
                dir.walkTopDown().filter { it.extension in listOf("java", "kt") }.toList()
            }
    }

    private fun extractPackageName(manifestFile: File): String? {
        val content = manifestFile.readText()
        val match = Regex("""package\s*=\s*"([^"]+)"""").find(content)
        return match?.groupValues?.get(1)
    }

    /**
     * Checks if the project references external dependencies that require
     * Gradle dependency resolution (can't be compiled with raw ECJ/kotlinc).
     */
    private fun hasExternalDependencies(projectDir: File): Boolean {
        val buildFiles = listOf(
            File(projectDir, "app/build.gradle.kts"),
            File(projectDir, "app/build.gradle"),
            File(projectDir, "build.gradle.kts"),
            File(projectDir, "build.gradle")
        )

        for (buildFile in buildFiles) {
            if (!buildFile.exists()) continue
            val content = buildFile.readText()
            // If it has implementation/api dependencies, it needs Gradle
            if (content.contains("implementation(") || content.contains("implementation \"") ||
                content.contains("api(") || content.contains("ksp(") ||
                content.contains("kapt(")) {
                return true
            }
        }
        return false
    }
}
