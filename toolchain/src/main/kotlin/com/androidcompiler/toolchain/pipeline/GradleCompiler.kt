package com.androidcompiler.toolchain.pipeline

import com.androidcompiler.core.common.model.CompilationError
import com.androidcompiler.core.common.model.ErrorSeverity
import com.androidcompiler.toolchain.registry.ToolchainRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Compiles full Gradle-based Android projects by invoking the project's
 * own Gradle wrapper (or a bundled one) on-device.
 *
 * This handles real-world projects with external dependencies, annotation
 * processing (Hilt/KSP), Compose compiler plugin, multi-module builds, etc.
 *
 * Requirements:
 * - A JDK available on the device (bundled via toolchain)
 * - The project must include a Gradle wrapper, or we inject one
 * - Sufficient memory (Gradle + kotlinc can use 2-4GB)
 */
@Singleton
class GradleCompiler @Inject constructor(
    private val registry: ToolchainRegistry
) {
    companion object {
        private const val GRADLE_WRAPPER_VERSION = "8.11.1"
    }

    suspend fun compile(
        projectDir: File,
        outputDir: File,
        projectInfo: ProjectAnalyzer.ProjectInfo,
        onLog: LogCallback
    ): StepResult = withContext(Dispatchers.IO) {

        onLog("Detected Gradle project: ${projectInfo.packageName ?: projectDir.name}", ErrorSeverity.INFO)

        // Ensure the project has a Gradle wrapper
        val gradlew = ensureGradleWrapper(projectDir, onLog)
            ?: return@withContext StepResult.Failure(listOf(
                CompilationError("Gradle", ErrorSeverity.ERROR,
                    "Could not set up Gradle wrapper. Ensure the project includes gradlew or gradle-wrapper.properties.")
            ))

        // Set up environment
        val javaHome = findJavaHome()
        if (javaHome == null) {
            onLog("No suitable JDK found on device", ErrorSeverity.ERROR)
            return@withContext StepResult.Failure(listOf(
                CompilationError("Gradle", ErrorSeverity.ERROR,
                    "No JDK found. Go to the Components tab and ensure 'OpenJDK 17 (ARM64)' is downloaded. " +
                    "This provides a portable JDK for on-device Gradle builds.")
            ))
        }

        val androidSdk = findAndroidSdk()

        // Write local.properties with SDK path if we found one
        if (androidSdk != null) {
            val localProps = File(projectDir, "local.properties")
            localProps.writeText("sdk.dir=${androidSdk.absolutePath.replace("\\", "/")}\n")
            onLog("SDK path: ${androidSdk.absolutePath}", ErrorSeverity.INFO)
        }

        // Build the Gradle command
        val gradleCommand = mutableListOf(
            gradlew.absolutePath,
            "assembleDebug",
            "--no-daemon",
            "--no-build-cache",
            "--warning-mode=all"
        )

        // Set memory limits appropriate for mobile device
        val env = mutableMapOf<String, String>()
        env["JAVA_HOME"] = javaHome.absolutePath
        env["ANDROID_HOME"] = androidSdk?.absolutePath ?: ""
        env["ANDROID_SDK_ROOT"] = androidSdk?.absolutePath ?: ""
        env["GRADLE_OPTS"] = "-Xmx3g -Dfile.encoding=UTF-8"

        onLog("Running: gradlew assembleDebug", ErrorSeverity.INFO)
        onLog("JAVA_HOME: ${javaHome.absolutePath}", ErrorSeverity.INFO)

        // Execute Gradle build
        val result = executeGradleBuild(gradleCommand, projectDir, env, onLog)

        if (result.exitCode != 0) {
            val errors = parseGradleErrors(result.stderr + "\n" + result.stdout)
            return@withContext StepResult.Failure(errors.ifEmpty {
                listOf(CompilationError("Gradle", ErrorSeverity.ERROR,
                    "Gradle build failed with exit code ${result.exitCode}.\n${result.stderr.takeLast(1000)}"))
            })
        }

        // Find the produced APK
        val apk = findProducedApk(projectDir)
        if (apk == null) {
            onLog("Gradle build succeeded but no APK found in build outputs", ErrorSeverity.ERROR)
            return@withContext StepResult.Failure(listOf(
                CompilationError("Gradle", ErrorSeverity.ERROR, "Build succeeded but no APK found in output directories")
            ))
        }

        // Copy APK to output directory
        val outputApk = File(outputDir, apk.name)
        apk.copyTo(outputApk, overwrite = true)
        onLog("APK produced: ${outputApk.name} (${outputApk.length() / 1024} KB)", ErrorSeverity.INFO)

        StepResult.Success(listOf(outputApk))
    }

    private fun ensureGradleWrapper(projectDir: File, onLog: LogCallback): File? {
        val gradlewFile = File(projectDir, "gradlew")
        val gradlewBat = File(projectDir, "gradlew.bat")
        val wrapperProps = File(projectDir, "gradle/wrapper/gradle-wrapper.properties")
        val wrapperJar = File(projectDir, "gradle/wrapper/gradle-wrapper.jar")

        // If gradlew exists and is executable, use it
        if (gradlewFile.exists()) {
            gradlewFile.setExecutable(true)
            onLog("Using project's Gradle wrapper", ErrorSeverity.INFO)

            // Check if wrapper JAR exists — project ZIPs often omit it
            if (!wrapperJar.exists() && wrapperProps.exists()) {
                onLog("Gradle wrapper JAR missing, injecting...", ErrorSeverity.WARNING)
                injectWrapperJar(projectDir)
            }
            return gradlewFile
        }

        // If wrapper properties exist but no gradlew script, generate one
        if (wrapperProps.exists()) {
            onLog("Generating Gradle wrapper scripts...", ErrorSeverity.INFO)
            generateGradlewScripts(projectDir)
            if (!wrapperJar.exists()) {
                injectWrapperJar(projectDir)
            }
            val generated = File(projectDir, "gradlew")
            generated.setExecutable(true)
            return generated
        }

        // No Gradle wrapper at all — inject a full wrapper
        onLog("No Gradle wrapper found, injecting v$GRADLE_WRAPPER_VERSION...", ErrorSeverity.WARNING)
        injectFullGradleWrapper(projectDir)
        val injected = File(projectDir, "gradlew")
        return if (injected.exists()) {
            injected.setExecutable(true)
            injected
        } else null
    }

    private fun injectWrapperJar(projectDir: File) {
        val wrapperDir = File(projectDir, "gradle/wrapper")
        wrapperDir.mkdirs()
        val targetJar = File(wrapperDir, "gradle-wrapper.jar")

        // Try to find a cached wrapper JAR from Gradle's cache
        val gradleCache = File(System.getProperty("user.home", "/data"), ".gradle/wrapper/dists")
        if (gradleCache.exists()) {
            gradleCache.walkTopDown()
                .filter { it.name == "gradle-wrapper.jar" && it.length() > 10000 }
                .firstOrNull()
                ?.let { cachedJar ->
                    cachedJar.copyTo(targetJar, overwrite = true)
                    return
                }
        }

        // Try the toolchain directory (we could bundle a wrapper JAR)
        val bundledJar = File(registry.toolchainDir, "gradle-wrapper.jar")
        if (bundledJar.exists()) {
            bundledJar.copyTo(targetJar, overwrite = true)
        }
    }

    private fun generateGradlewScripts(projectDir: File) {
        // Generate minimal POSIX gradlew script
        val gradlew = File(projectDir, "gradlew")
        gradlew.writeText("""
            |#!/bin/sh
            |APP_HOME=${'$'}(cd "${'$'}{0%"${'$'}{0##*/}"}" > /dev/null && pwd -P)
            |CLASSPATH=${'$'}APP_HOME/gradle/wrapper/gradle-wrapper.jar
            |exec java ${'$'}JAVA_OPTS -classpath "${'$'}CLASSPATH" org.gradle.wrapper.GradleWrapperMain "${'$'}@"
        """.trimMargin())
        gradlew.setExecutable(true)
    }

    private fun injectFullGradleWrapper(projectDir: File) {
        val wrapperDir = File(projectDir, "gradle/wrapper")
        wrapperDir.mkdirs()

        // Write wrapper properties
        File(wrapperDir, "gradle-wrapper.properties").writeText("""
            |distributionBase=GRADLE_USER_HOME
            |distributionPath=wrapper/dists
            |distributionUrl=https\://services.gradle.org/distributions/gradle-$GRADLE_WRAPPER_VERSION-bin.zip
            |networkTimeout=10000
            |validateDistributionUrl=true
            |zipStoreBase=GRADLE_USER_HOME
            |zipStorePath=wrapper/dists
        """.trimMargin())

        // Inject wrapper JAR and scripts
        injectWrapperJar(projectDir)
        generateGradlewScripts(projectDir)
    }

    private fun findJavaHome(): File? {
        // Priority 1: Bundled JDK from toolchain (downloaded on first launch)
        val bundledJdk = registry.getJavaHome()
        if (bundledJdk != null) {
            val javaBin = File(bundledJdk, "bin/java")
            if (javaBin.exists()) return bundledJdk
        }

        // Priority 2: Termux JDK
        val termuxJdk = File("/data/data/com.termux/files/usr")
        if (File(termuxJdk, "bin/java").exists()) return termuxJdk

        // Priority 3: Environment variable
        val envJavaHome = System.getenv("JAVA_HOME")
        if (envJavaHome != null) {
            val dir = File(envJavaHome)
            if (File(dir, "bin/java").exists()) return dir
        }

        // Priority 4: Common system locations
        val systemCandidates = listOf(
            "/usr/lib/jvm/java-17-openjdk",
            "/usr/lib/jvm/java-21-openjdk"
        )
        for (path in systemCandidates) {
            val dir = File(path)
            if (File(dir, "bin/java").exists()) return dir
        }

        // Priority 5: Check if java is in PATH
        try {
            val process = ProcessBuilder("java", "-version")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            if (process.exitValue() == 0 || output.contains("version")) {
                return File(System.getProperty("java.home", "/system"))
            }
        } catch (_: Exception) {}

        return null
    }

    private fun findAndroidSdk(): File? {
        val candidates = listOf(
            System.getenv("ANDROID_HOME"),
            System.getenv("ANDROID_SDK_ROOT"),
            "/data/data/com.termux/files/home/android-sdk",
            "${System.getProperty("user.home")}/Android/Sdk",
            "${System.getProperty("user.home")}/android-sdk"
        ).filterNotNull()

        for (path in candidates) {
            val dir = File(path)
            if (dir.exists() && File(dir, "platforms").exists()) return dir
        }
        return null
    }

    private fun executeGradleBuild(
        command: List<String>,
        workingDir: File,
        env: Map<String, String>,
        onLog: LogCallback
    ): ProcessResult {
        val processBuilder = ProcessBuilder(command)
            .directory(workingDir)
            .redirectErrorStream(false)

        processBuilder.environment().putAll(env)

        val process = processBuilder.start()

        val stdoutBuilder = StringBuilder()
        val stderrBuilder = StringBuilder()

        // Read stdout in real-time for live logging
        val stdoutThread = Thread {
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                reader.lineSequence().forEach { line ->
                    stdoutBuilder.appendLine(line)
                    // Log key lines to the UI
                    when {
                        line.startsWith("> Task") -> onLog(line, ErrorSeverity.INFO)
                        line.contains("BUILD SUCCESSFUL") -> onLog(line, ErrorSeverity.INFO)
                        line.contains("BUILD FAILED") -> onLog(line, ErrorSeverity.ERROR)
                        line.contains("error:", ignoreCase = true) -> onLog(line, ErrorSeverity.ERROR)
                        line.contains("warning:", ignoreCase = true) -> onLog(line, ErrorSeverity.WARNING)
                        line.startsWith("e:") -> onLog(line, ErrorSeverity.ERROR)
                        line.startsWith("w:") -> onLog(line, ErrorSeverity.WARNING)
                    }
                }
            }
        }

        val stderrThread = Thread {
            BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                reader.lineSequence().forEach { line ->
                    stderrBuilder.appendLine(line)
                    if (line.isNotBlank()) onLog("[stderr] $line", ErrorSeverity.WARNING)
                }
            }
        }

        stdoutThread.start()
        stderrThread.start()

        val exitCode = process.waitFor()
        stdoutThread.join(5000)
        stderrThread.join(5000)

        return ProcessResult(exitCode, stdoutBuilder.toString(), stderrBuilder.toString())
    }

    private fun findProducedApk(projectDir: File): File? {
        // Search common APK output locations
        val searchDirs = listOf(
            File(projectDir, "app/build/outputs/apk/debug"),
            File(projectDir, "app/build/outputs/apk/release"),
            File(projectDir, "build/outputs/apk/debug"),
            File(projectDir, "build/outputs/apk/release")
        )

        for (dir in searchDirs) {
            if (dir.exists()) {
                val apk = dir.listFiles()
                    ?.filter { it.extension == "apk" && !it.name.contains("unsigned") }
                    ?.maxByOrNull { it.lastModified() }
                if (apk != null) return apk
            }
        }

        // Broader search
        return projectDir.walkTopDown()
            .filter { it.extension == "apk" && it.path.contains("outputs") && !it.name.contains("unsigned") }
            .maxByOrNull { it.lastModified() }
    }

    private fun parseGradleErrors(output: String): List<CompilationError> {
        val errors = mutableListOf<CompilationError>()

        // Kotlin errors: e: file:///path/File.kt:line:col: message
        val kotlinPattern = Regex("""e: file:///(.+?):(\d+):(\d+): (.+)""")
        for (match in kotlinPattern.findAll(output)) {
            errors.add(CompilationError(
                step = "kotlinc",
                severity = ErrorSeverity.ERROR,
                message = match.groupValues[4],
                filePath = match.groupValues[1],
                line = match.groupValues[2].toIntOrNull(),
                column = match.groupValues[3].toIntOrNull(),
                rawOutput = output.takeLast(2000)
            ))
        }

        // Java errors: path/File.java:line: error: message
        val javaPattern = Regex("""(.+\.java):(\d+): error: (.+)""")
        for (match in javaPattern.findAll(output)) {
            errors.add(CompilationError(
                step = "javac",
                severity = ErrorSeverity.ERROR,
                message = match.groupValues[3],
                filePath = match.groupValues[1],
                line = match.groupValues[2].toIntOrNull(),
                rawOutput = output.takeLast(2000)
            ))
        }

        // AAPT2 errors
        val aaptPattern = Regex("""AAPT: error: (.+)""")
        for (match in aaptPattern.findAll(output)) {
            errors.add(CompilationError(
                step = "AAPT2",
                severity = ErrorSeverity.ERROR,
                message = match.groupValues[1],
                rawOutput = output.takeLast(2000)
            ))
        }

        // Generic Gradle failure
        val failurePattern = Regex("""Execution failed for task '(.+?)'\.\s*\n>\s*(.+)""")
        for (match in failurePattern.findAll(output)) {
            errors.add(CompilationError(
                step = "Gradle",
                severity = ErrorSeverity.ERROR,
                message = "Task ${match.groupValues[1]} failed: ${match.groupValues[2]}",
                rawOutput = output.takeLast(2000)
            ))
        }

        return errors
    }
}
