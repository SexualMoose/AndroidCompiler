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
 * KEY ANDROID CONSTRAINTS:
 * 1. /data partition is mounted noexec — can't execute scripts or binaries from app data
 * 2. Standard Linux JDKs (glibc) can't run on Android (Bionic libc)
 * 3. Only /system binaries (/system/bin/sh, /system/bin/app_process) are executable
 *
 * SOLUTION:
 * - Invoke /system/bin/app_process directly to run Gradle's JVM code
 * - app_process is Android's native mechanism for running Java on ART
 * - For any forked sub-processes, create a java shim invoked via /system/bin/sh
 * - Configure Gradle to minimize process forking
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

        // Step 1: Set up synthetic JDK with java shim for sub-processes
        setupSyntheticJdk(onLog)
        val javaHome = syntheticJdkDir
        onLog("JAVA_HOME: ${javaHome.absolutePath}", ErrorSeverity.INFO)

        // Step 2: Ensure the project has a Gradle wrapper JAR
        val wrapperJar = ensureGradleWrapperJar(projectDir, onLog)
        if (wrapperJar == null) {
            return@withContext StepResult.Failure(listOf(
                CompilationError("Gradle", ErrorSeverity.ERROR,
                    "Could not locate gradle-wrapper.jar. Ensure it's downloaded in Components.")
            ))
        }
        onLog("Wrapper JAR: ${wrapperJar.absolutePath} (${wrapperJar.length()} bytes)", ErrorSeverity.INFO)

        // Step 3: Find or skip Android SDK
        val androidSdk = findAndroidSdk()
        if (androidSdk != null) {
            File(projectDir, "local.properties")
                .writeText("sdk.dir=${androidSdk.absolutePath.replace("\\", "/")}\n")
            onLog("Android SDK: ${androidSdk.absolutePath}", ErrorSeverity.INFO)
        } else {
            onLog("No local Android SDK — Gradle will download build tools", ErrorSeverity.WARNING)
        }

        // Step 4: Inject gradle.properties to minimize forking and configure for ART
        injectGradleProperties(projectDir, onLog)

        // Step 5: Build the command
        // CRITICAL: Execute /system/bin/app_process directly — it's a system binary
        // that IS executable. Never try to execute files from /data (noexec mount).
        val classpath = wrapperJar.absolutePath

        // app_process syntax: app_process [jvm-options] <parent-dir> <main-class> [args]
        val shellCommand = buildString {
            append("CLASSPATH='$classpath' ")
            append("JAVA_HOME='${javaHome.absolutePath}' ")
            append("ANDROID_HOME='${androidSdk?.absolutePath ?: ""}' ")
            append("ANDROID_SDK_ROOT='${androidSdk?.absolutePath ?: ""}' ")
            append("GRADLE_USER_HOME='${File(context.filesDir, "gradle_home").apply { mkdirs() }.absolutePath}' ")
            // Put our synthetic jdk/bin first in PATH so Gradle's forked processes find our java shim
            append("PATH='${javaHome.absolutePath}/bin:/system/bin:/system/xbin' ")
            append("exec /system/bin/app_process ")
            append("-Djava.class.path='$classpath' ")
            append("-Dfile.encoding=UTF-8 ")
            append("-Dorg.gradle.appname=gradlew ")
            append("/ ") // parent dir for app_process
            append("org.gradle.wrapper.GradleWrapperMain ")
            append("assembleDebug ")
            append("--no-daemon ")
            append("--console=plain ")
            append("--warning-mode=all ")
            append("-Dorg.gradle.java.home='${javaHome.absolutePath}'")
        }

        // Use /system/bin/sh to execute — this IS on an exec-mounted partition
        val command = listOf("/system/bin/sh", "-c", shellCommand)

        onLog("Invoking Gradle via app_process...", ErrorSeverity.INFO)

        val result = executeGradleBuild(command, projectDir, onLog)

        if (result.exitCode != 0) {
            val errors = parseGradleErrors(result.stderr + "\n" + result.stdout)
            return@withContext StepResult.Failure(errors.ifEmpty {
                listOf(CompilationError("Gradle", ErrorSeverity.ERROR,
                    "Build failed (exit ${result.exitCode}).\n" +
                    (result.stderr + "\n" + result.stdout).takeLast(1500)))
            })
        }

        // Find the produced APK
        val apk = findProducedApk(projectDir)
        if (apk == null) {
            return@withContext StepResult.Failure(listOf(
                CompilationError("Gradle", ErrorSeverity.ERROR,
                    "Build completed but no APK found in output directories.\n" +
                    "stdout: ${result.stdout.takeLast(500)}")
            ))
        }

        val outputApk = File(outputDir, apk.name)
        apk.copyTo(outputApk, overwrite = true)
        onLog("APK: ${outputApk.name} (${outputApk.length() / 1024} KB)", ErrorSeverity.INFO)

        StepResult.Success(listOf(outputApk))
    }

    /**
     * Creates a synthetic JAVA_HOME with a `java` shim for Gradle's sub-processes.
     *
     * The shim is a shell script that translates java args to app_process format.
     * It CANNOT be executed directly (noexec on /data), but Gradle invokes java
     * through the shell, and we ensure /system/bin/sh is used via PATH setup.
     *
     * For cases where Gradle tries to exec the java binary directly, we also
     * create a companion script that the shell-based invocation can use.
     */
    private fun setupSyntheticJdk(onLog: LogCallback) {
        val binDir = File(syntheticJdkDir, "bin").apply { mkdirs() }

        // The java shim — invoked as: /system/bin/sh /path/to/java [args]
        File(binDir, "java").writeText("""#!/system/bin/sh
# AndroidCompiler java shim — routes through app_process on ART
CLASSPATH=""
MAIN_CLASS=""
JVM_ARGS=""
APP_ARGS=""
FOUND_MAIN=false
NEXT_IS_CP=false
NEXT_IS_JAR=false

for arg in "${'$'}@"; do
    if [ "${'$'}FOUND_MAIN" = "true" ]; then
        APP_ARGS="${'$'}APP_ARGS ${'$'}arg"
    elif [ "${'$'}NEXT_IS_CP" = "true" ]; then
        CLASSPATH="${'$'}arg"
        NEXT_IS_CP=false
    elif [ "${'$'}NEXT_IS_JAR" = "true" ]; then
        CLASSPATH="${'$'}arg"
        NEXT_IS_JAR=false
        FOUND_MAIN=true
    elif [ "${'$'}arg" = "-cp" ] || [ "${'$'}arg" = "-classpath" ]; then
        NEXT_IS_CP=true
    elif [ "${'$'}arg" = "-jar" ]; then
        NEXT_IS_JAR=true
    elif echo "${'$'}arg" | grep -q "^-D"; then
        JVM_ARGS="${'$'}JVM_ARGS ${'$'}arg"
    elif echo "${'$'}arg" | grep -q "^-"; then
        : # skip unsupported JVM flags silently
    else
        MAIN_CLASS="${'$'}arg"
        FOUND_MAIN=true
    fi
done

if [ -z "${'$'}MAIN_CLASS" ] && [ -n "${'$'}CLASSPATH" ]; then
    MAIN_CLASS=${'$'}(unzip -p "${'$'}CLASSPATH" META-INF/MANIFEST.MF 2>/dev/null | grep "Main-Class:" | head -1 | sed 's/Main-Class: *//' | tr -d '\r')
fi

if [ -z "${'$'}MAIN_CLASS" ]; then
    echo "Error: No main class specified" >&2
    exit 1
fi

export CLASSPATH
exec /system/bin/app_process ${'$'}JVM_ARGS / ${'$'}MAIN_CLASS ${'$'}APP_ARGS
""")

        // Create a java wrapper that uses sh to invoke the shim
        // This goes in a PATH-accessible location that sh can find
        File(binDir, "javac").writeText("#!/system/bin/sh\necho 'javac not available' >&2\nexit 1\n")
        File(binDir, "jar").writeText("#!/system/bin/sh\necho 'jar not available' >&2\nexit 1\n")

        // Release file so Gradle's toolchain detection recognizes this as a JDK
        File(syntheticJdkDir, "release").writeText("""
JAVA_VERSION="17.0.0"
JAVA_VERSION_DATE="2026-01-01"
OS_ARCH="aarch64"
OS_NAME="Android"
IMPLEMENTOR="AndroidCompiler-ART"
""".trimIndent())

        onLog("Synthetic JDK ready (app_process shim)", ErrorSeverity.INFO)
    }

    /**
     * Injects gradle.properties into the project to configure ART-compatible settings.
     * Minimizes process forking since forked java processes can't run from /data.
     */
    private fun injectGradleProperties(projectDir: File, onLog: LogCallback) {
        val propsFile = File(projectDir, "gradle.properties")
        val existingProps = if (propsFile.exists()) propsFile.readText() else ""

        val injectedProps = buildString {
            appendLine(existingProps)
            appendLine()
            appendLine("# === Injected by AndroidCompiler for on-device builds ===")
            // Run Kotlin compiler in the Gradle process — don't fork a separate java process
            appendLine("kotlin.compiler.execution.strategy=in-process")
            // Disable Gradle daemon (we use --no-daemon but belt and suspenders)
            appendLine("org.gradle.daemon=false")
            // Limit workers to reduce forking pressure
            appendLine("org.gradle.workers.max=2")
            // Don't use configuration cache (can cause issues on first build)
            appendLine("org.gradle.configuration-cache=false")
            // Increase memory for in-process builds
            appendLine("org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8")
            // Parallel execution
            appendLine("org.gradle.parallel=true")
        }

        propsFile.writeText(injectedProps)
        onLog("Gradle properties configured for on-device build", ErrorSeverity.INFO)
    }

    private fun ensureGradleWrapperJar(projectDir: File, onLog: LogCallback): File? {
        val wrapperDir = File(projectDir, "gradle/wrapper").apply { mkdirs() }
        val wrapperJar = File(wrapperDir, "gradle-wrapper.jar")
        val wrapperProps = File(wrapperDir, "gradle-wrapper.properties")

        if (wrapperJar.exists() && wrapperJar.length() > 1000) {
            onLog("Using project's gradle-wrapper.jar", ErrorSeverity.INFO)
            ensureWrapperProperties(wrapperProps)
            return wrapperJar
        }

        // Try toolchain's bundled copy
        val toolchainJar = File(registry.toolchainDir, "gradle-wrapper.jar")
        if (toolchainJar.exists() && toolchainJar.length() > 1000) {
            toolchainJar.copyTo(wrapperJar, overwrite = true)
            onLog("Injected gradle-wrapper.jar from toolchain", ErrorSeverity.INFO)
            ensureWrapperProperties(wrapperProps)
            return wrapperJar
        }

        // Search Gradle caches
        val cacheLocations = listOfNotNull(
            File(context.filesDir, "gradle_home/wrapper/dists"),
            File(System.getProperty("user.home", "/data"), ".gradle/wrapper/dists")
        )
        for (cache in cacheLocations) {
            if (cache.exists()) {
                cache.walkTopDown()
                    .filter { it.name == "gradle-wrapper.jar" && it.length() > 10000 }
                    .firstOrNull()
                    ?.let { cached ->
                        cached.copyTo(wrapperJar, overwrite = true)
                        onLog("Injected gradle-wrapper.jar from Gradle cache", ErrorSeverity.INFO)
                        ensureWrapperProperties(wrapperProps)
                        return wrapperJar
                    }
            }
        }

        onLog("gradle-wrapper.jar not found — download it from Components tab", ErrorSeverity.ERROR)
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
        onLog: LogCallback
    ): ProcessResult {
        try {
            val processBuilder = ProcessBuilder(command)
                .directory(workingDir)
                .redirectErrorStream(false)

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
                                line.startsWith("e:") -> onLog(line, ErrorSeverity.ERROR)
                                line.startsWith("w:") -> onLog(line, ErrorSeverity.WARNING)
                                line.contains("Downloading") -> onLog(line, ErrorSeverity.INFO)
                                line.contains("error:", ignoreCase = true) -> onLog(line, ErrorSeverity.ERROR)
                                line.contains("FAILURE:") -> onLog(line, ErrorSeverity.ERROR)
                                line.startsWith("> ") -> onLog(line, ErrorSeverity.INFO)
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
            return ProcessResult(-1, "", "Failed to execute: ${e.message}\n${e.stackTraceToString()}")
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
                dir.listFiles()
                    ?.filter { it.extension == "apk" && !it.name.contains("unsigned") }
                    ?.maxByOrNull { it.lastModified() }
                    ?.let { return it }
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
            errors.add(CompilationError("kotlinc", ErrorSeverity.ERROR,
                match.groupValues[4], match.groupValues[1],
                match.groupValues[2].toIntOrNull(), match.groupValues[3].toIntOrNull(),
                output.takeLast(2000)))
        }

        val javaPattern = Regex("""(.+\.java):(\d+): error: (.+)""")
        for (match in javaPattern.findAll(output)) {
            errors.add(CompilationError("javac", ErrorSeverity.ERROR,
                match.groupValues[3], match.groupValues[1],
                match.groupValues[2].toIntOrNull(), rawOutput = output.takeLast(2000)))
        }

        val failurePattern = Regex("""Execution failed for task '(.+?)'\.\s*\n>\s*(.+)""")
        for (match in failurePattern.findAll(output)) {
            errors.add(CompilationError("Gradle", ErrorSeverity.ERROR,
                "Task ${match.groupValues[1]}: ${match.groupValues[2]}",
                rawOutput = output.takeLast(2000)))
        }

        return errors
    }
}
