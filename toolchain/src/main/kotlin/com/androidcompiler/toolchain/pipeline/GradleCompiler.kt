package com.androidcompiler.toolchain.pipeline

import android.content.Context
import com.androidcompiler.core.common.model.CompilationError
import com.androidcompiler.core.common.model.ErrorSeverity
import com.androidcompiler.toolchain.registry.ToolchainRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Compiles full Gradle-based Android projects on-device.
 *
 * KEY INSIGHT: Standard Linux JDK binaries (glibc-linked) cannot execute on
 * Android (Bionic libc + SELinux restrictions). Instead, we create a synthetic
 * JAVA_HOME with a `java` shim script that delegates to Android's `app_process`,
 * which CAN run Java/Kotlin code on ART.
 *
 * The approach:
 * 1. Create a synthetic JDK directory with bin/java as a shell script
 * 2. The java shim translates standard `java` CLI args to `app_process` format
 * 3. Gradle's wrapper and all spawned JVM processes use this shim
 * 4. ART executes the actual Java bytecode (Gradle, kotlinc, D8, etc.)
 */
@Singleton
class GradleCompiler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val registry: ToolchainRegistry
) {
    companion object {
        private const val GRADLE_WRAPPER_VERSION = "8.11.1"
    }

    private val syntheticJdkDir: File
        get() = File(registry.toolchainDir, "synthetic-jdk")

    suspend fun compile(
        projectDir: File,
        outputDir: File,
        projectInfo: ProjectAnalyzer.ProjectInfo,
        onLog: LogCallback
    ): StepResult = withContext(Dispatchers.IO) {

        onLog("Detected Gradle project: ${projectInfo.packageName ?: projectDir.name}", ErrorSeverity.INFO)

        // Step 1: Set up synthetic JDK with java shim
        val javaHome = setupSyntheticJdk(onLog)
        onLog("JAVA_HOME: ${javaHome.absolutePath}", ErrorSeverity.INFO)

        // Step 2: Ensure the project has a Gradle wrapper
        val wrapperJar = ensureGradleWrapperJar(projectDir, onLog)
        if (wrapperJar == null) {
            return@withContext StepResult.Failure(listOf(
                CompilationError("Gradle", ErrorSeverity.ERROR,
                    "Could not set up Gradle wrapper JAR.")
            ))
        }

        // Step 3: Find Android SDK
        val androidSdk = findAndroidSdk()
        if (androidSdk != null) {
            val localProps = File(projectDir, "local.properties")
            localProps.writeText("sdk.dir=${androidSdk.absolutePath.replace("\\", "/")}\n")
            onLog("Android SDK: ${androidSdk.absolutePath}", ErrorSeverity.INFO)
        } else {
            onLog("No Android SDK found — Gradle will attempt to download one", ErrorSeverity.WARNING)
        }

        // Step 4: Build the command — invoke java shim with gradle-wrapper.jar directly
        // Instead of running gradlew (shell script that may not work), we invoke the
        // wrapper's main class directly through our java shim
        val javaBin = File(javaHome, "bin/java").absolutePath
        val command = mutableListOf(
            javaBin,
            "-Xmx3g",
            "-Dfile.encoding=UTF-8",
            "-Dorg.gradle.appname=gradlew",
            "-classpath", wrapperJar.absolutePath,
            "org.gradle.wrapper.GradleWrapperMain",
            "assembleDebug",
            "--no-daemon",
            "--warning-mode=all",
            "--console=plain"
        )

        val env = mutableMapOf<String, String>()
        env["JAVA_HOME"] = javaHome.absolutePath
        env["ANDROID_HOME"] = androidSdk?.absolutePath ?: ""
        env["ANDROID_SDK_ROOT"] = androidSdk?.absolutePath ?: ""
        // Ensure our java shim is first in PATH so Gradle's spawned processes use it
        env["PATH"] = "${javaHome.absolutePath}/bin:/system/bin:/system/xbin:${System.getenv("PATH") ?: ""}"
        // Gradle properties
        env["GRADLE_USER_HOME"] = File(context.filesDir, "gradle_home").apply { mkdirs() }.absolutePath

        onLog("Running Gradle build...", ErrorSeverity.INFO)

        val result = executeGradleBuild(command, projectDir, env, onLog)

        if (result.exitCode != 0) {
            val errors = parseGradleErrors(result.stderr + "\n" + result.stdout)
            return@withContext StepResult.Failure(errors.ifEmpty {
                listOf(CompilationError("Gradle", ErrorSeverity.ERROR,
                    "Gradle build failed (exit code ${result.exitCode}).\n" +
                    result.stderr.takeLast(500) + "\n" + result.stdout.takeLast(500)))
            })
        }

        // Find the produced APK
        val apk = findProducedApk(projectDir)
        if (apk == null) {
            onLog("Gradle build succeeded but no APK found", ErrorSeverity.ERROR)
            return@withContext StepResult.Failure(listOf(
                CompilationError("Gradle", ErrorSeverity.ERROR,
                    "Build succeeded but no APK found in output directories")
            ))
        }

        val outputApk = File(outputDir, apk.name)
        apk.copyTo(outputApk, overwrite = true)
        onLog("APK: ${outputApk.name} (${outputApk.length() / 1024} KB)", ErrorSeverity.INFO)

        StepResult.Success(listOf(outputApk))
    }

    /**
     * Creates a synthetic JAVA_HOME with a `java` shim script that delegates
     * to Android's `app_process` command for actual JVM execution.
     *
     * `app_process` is Android's native mechanism for running Java code on ART.
     * It's how every Android app and system service starts. By wrapping it in
     * a script that accepts standard `java` command-line arguments, we make
     * Gradle (and all tools it spawns) work transparently on Android.
     */
    private fun setupSyntheticJdk(onLog: LogCallback): File {
        val binDir = File(syntheticJdkDir, "bin").apply { mkdirs() }
        val javaShim = File(binDir, "java")
        val javacShim = File(binDir, "javac") // Some tools check for this

        // Write the java shim script
        // This script parses standard java arguments and invokes app_process
        javaShim.writeText("""#!/system/bin/sh
# AndroidCompiler java shim — delegates to app_process for ART execution
# Parses standard java CLI arguments and translates to app_process format

CLASSPATH=""
MAIN_CLASS=""
JVM_ARGS=""
APP_ARGS=""
FOUND_MAIN=false

for arg in "${'$'}@"; do
    if [ "${'$'}FOUND_MAIN" = true ]; then
        APP_ARGS="${'$'}APP_ARGS ${'$'}arg"
    elif [ "${'$'}arg" = "-cp" ] || [ "${'$'}arg" = "-classpath" ]; then
        # Next arg will be the classpath
        NEXT_IS_CP=true
    elif [ "${'$'}NEXT_IS_CP" = true ]; then
        CLASSPATH="${'$'}arg"
        NEXT_IS_CP=false
    elif echo "${'$'}arg" | grep -q "^-"; then
        # JVM argument — collect but many are unsupported by app_process
        # Filter out ones that would cause errors
        case "${'$'}arg" in
            -Xmx*|-Xms*|-Xss*|-XX:*|-ea|-da)
                # Memory/GC args not supported by app_process — skip silently
                ;;
            -D*)
                JVM_ARGS="${'$'}JVM_ARGS ${'$'}arg"
                ;;
            -jar)
                NEXT_IS_JAR=true
                ;;
            *)
                JVM_ARGS="${'$'}JVM_ARGS ${'$'}arg"
                ;;
        esac
    elif [ "${'$'}NEXT_IS_JAR" = true ]; then
        # -jar mode: read main class from manifest
        CLASSPATH="${'$'}arg"
        MAIN_CLASS=""
        NEXT_IS_JAR=false
        FOUND_MAIN=true
    else
        # This is the main class
        MAIN_CLASS="${'$'}arg"
        FOUND_MAIN=true
    fi
done

# Export classpath for app_process
export CLASSPATH

# If -jar mode without explicit main class, need to read from JAR manifest
if [ -z "${'$'}MAIN_CLASS" ] && [ -n "${'$'}CLASSPATH" ]; then
    # Try to extract Main-Class from JAR manifest
    MAIN_CLASS=${'$'}(unzip -p "${'$'}CLASSPATH" META-INF/MANIFEST.MF 2>/dev/null | grep "Main-Class:" | head -1 | cut -d' ' -f2 | tr -d '\r')
fi

if [ -z "${'$'}MAIN_CLASS" ]; then
    echo "Error: No main class specified" >&2
    exit 1
fi

# app_process syntax: app_process [java-options] <process-dir> <class> [args...]
exec /system/bin/app_process ${'$'}JVM_ARGS / ${'$'}MAIN_CLASS ${'$'}APP_ARGS
""")
        javaShim.setExecutable(true, false)

        // javac shim — just delegates to java with ECJ
        javacShim.writeText("""#!/system/bin/sh
echo "javac shim: not directly supported, use ECJ via java" >&2
exit 1
""")
        javacShim.setExecutable(true, false)

        // Create a minimal release file that tools like Gradle check
        File(syntheticJdkDir, "release").writeText("""
JAVA_VERSION="17"
OS_ARCH="aarch64"
OS_NAME="Android"
IMPLEMENTOR="AndroidCompiler"
""".trimIndent())

        onLog("Synthetic JDK created with app_process shim", ErrorSeverity.INFO)
        return syntheticJdkDir
    }

    /**
     * Ensures the project has a gradle-wrapper.jar.
     * Claude-generated ZIPs often include gradle-wrapper.properties but omit the JAR.
     */
    private fun ensureGradleWrapperJar(projectDir: File, onLog: LogCallback): File? {
        val wrapperDir = File(projectDir, "gradle/wrapper").apply { mkdirs() }
        val wrapperJar = File(wrapperDir, "gradle-wrapper.jar")
        val wrapperProps = File(wrapperDir, "gradle-wrapper.properties")

        // If JAR already exists, use it
        if (wrapperJar.exists() && wrapperJar.length() > 1000) {
            onLog("Using project's gradle-wrapper.jar", ErrorSeverity.INFO)
            return wrapperJar
        }

        // Try to find a cached wrapper JAR from Gradle's home
        val gradleHome = File(context.filesDir, "gradle_home/wrapper/dists")
        if (gradleHome.exists()) {
            gradleHome.walkTopDown()
                .filter { it.name == "gradle-wrapper.jar" && it.length() > 10000 }
                .firstOrNull()
                ?.let { cached ->
                    cached.copyTo(wrapperJar, overwrite = true)
                    onLog("Injected gradle-wrapper.jar from cache", ErrorSeverity.INFO)
                    ensureWrapperProperties(wrapperProps)
                    return wrapperJar
                }
        }

        // Try the toolchain directory
        val toolchainJar = File(registry.toolchainDir, "gradle-wrapper.jar")
        if (toolchainJar.exists()) {
            toolchainJar.copyTo(wrapperJar, overwrite = true)
            onLog("Injected gradle-wrapper.jar from toolchain", ErrorSeverity.INFO)
            ensureWrapperProperties(wrapperProps)
            return wrapperJar
        }

        // Try system-wide Gradle cache
        val homeDir = System.getProperty("user.home", "/data")
        val systemCache = File(homeDir, ".gradle/wrapper/dists")
        if (systemCache.exists()) {
            systemCache.walkTopDown()
                .filter { it.name == "gradle-wrapper.jar" && it.length() > 10000 }
                .firstOrNull()
                ?.let { cached ->
                    cached.copyTo(wrapperJar, overwrite = true)
                    onLog("Injected gradle-wrapper.jar from system cache", ErrorSeverity.INFO)
                    ensureWrapperProperties(wrapperProps)
                    return wrapperJar
                }
        }

        onLog("No gradle-wrapper.jar found anywhere", ErrorSeverity.ERROR)
        return null
    }

    private fun ensureWrapperProperties(propsFile: File) {
        if (propsFile.exists()) return
        propsFile.writeText("""
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-$GRADLE_WRAPPER_VERSION-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
""".trimIndent())
    }

    private fun findAndroidSdk(): File? {
        val candidates = listOfNotNull(
            System.getenv("ANDROID_HOME"),
            System.getenv("ANDROID_SDK_ROOT"),
            "/data/data/com.termux/files/home/android-sdk",
            "${System.getProperty("user.home")}/Android/Sdk",
            "${System.getProperty("user.home")}/android-sdk"
        )
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
        try {
            val processBuilder = ProcessBuilder(command)
                .directory(workingDir)
                .redirectErrorStream(false)

            processBuilder.environment().putAll(env)

            val process = processBuilder.start()

            val stdoutBuilder = StringBuilder()
            val stderrBuilder = StringBuilder()

            val stdoutThread = Thread {
                try {
                    BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                        reader.lineSequence().forEach { line ->
                            stdoutBuilder.appendLine(line)
                            when {
                                line.startsWith("> Task") -> onLog(line, ErrorSeverity.INFO)
                                line.contains("BUILD SUCCESSFUL") -> onLog(line, ErrorSeverity.INFO)
                                line.contains("BUILD FAILED") -> onLog(line, ErrorSeverity.ERROR)
                                line.contains("error:", ignoreCase = true) -> onLog(line, ErrorSeverity.ERROR)
                                line.startsWith("e:") -> onLog(line, ErrorSeverity.ERROR)
                                line.startsWith("w:") -> onLog(line, ErrorSeverity.WARNING)
                                line.contains("Download") -> onLog(line, ErrorSeverity.INFO)
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            val stderrThread = Thread {
                try {
                    BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                        reader.lineSequence().forEach { line ->
                            stderrBuilder.appendLine(line)
                            if (line.isNotBlank()) onLog("[stderr] $line", ErrorSeverity.WARNING)
                        }
                    }
                } catch (_: Exception) {}
            }

            stdoutThread.start()
            stderrThread.start()

            val exitCode = process.waitFor()
            stdoutThread.join(10000)
            stderrThread.join(10000)

            return ProcessResult(exitCode, stdoutBuilder.toString(), stderrBuilder.toString())
        } catch (e: Exception) {
            onLog("Process execution failed: ${e.message}", ErrorSeverity.ERROR)
            return ProcessResult(-1, "", "Failed to start process: ${e.message}\n${e.stackTraceToString()}")
        }
    }

    private fun findProducedApk(projectDir: File): File? {
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
        return projectDir.walkTopDown()
            .filter { it.extension == "apk" && it.path.contains("outputs") && !it.name.contains("unsigned") }
            .maxByOrNull { it.lastModified() }
    }

    private fun parseGradleErrors(output: String): List<CompilationError> {
        val errors = mutableListOf<CompilationError>()

        val kotlinPattern = Regex("""e: file:///(.+?):(\d+):(\d+): (.+)""")
        for (match in kotlinPattern.findAll(output)) {
            errors.add(CompilationError(
                step = "kotlinc", severity = ErrorSeverity.ERROR,
                message = match.groupValues[4], filePath = match.groupValues[1],
                line = match.groupValues[2].toIntOrNull(),
                column = match.groupValues[3].toIntOrNull(),
                rawOutput = output.takeLast(2000)
            ))
        }

        val javaPattern = Regex("""(.+\.java):(\d+): error: (.+)""")
        for (match in javaPattern.findAll(output)) {
            errors.add(CompilationError(
                step = "javac", severity = ErrorSeverity.ERROR,
                message = match.groupValues[3], filePath = match.groupValues[1],
                line = match.groupValues[2].toIntOrNull(),
                rawOutput = output.takeLast(2000)
            ))
        }

        val aaptPattern = Regex("""AAPT: error: (.+)""")
        for (match in aaptPattern.findAll(output)) {
            errors.add(CompilationError(
                step = "AAPT2", severity = ErrorSeverity.ERROR,
                message = match.groupValues[1], rawOutput = output.takeLast(2000)
            ))
        }

        val failurePattern = Regex("""Execution failed for task '(.+?)'\.\s*\n>\s*(.+)""")
        for (match in failurePattern.findAll(output)) {
            errors.add(CompilationError(
                step = "Gradle", severity = ErrorSeverity.ERROR,
                message = "Task ${match.groupValues[1]} failed: ${match.groupValues[2]}",
                rawOutput = output.takeLast(2000)
            ))
        }

        return errors
    }
}
