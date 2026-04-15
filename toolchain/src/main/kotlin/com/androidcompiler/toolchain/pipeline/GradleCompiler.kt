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
 * ANDROID CONSTRAINTS:
 * 1. /data is noexec — can't execute ANY file from app data directory
 * 2. Standard Linux JDKs (glibc) won't run on Android (Bionic libc)
 * 3. app_process crashes (SIGABRT) because it tries to init Zygote/framework
 *
 * SOLUTION: Use dalvikvm64 — Android's standalone JVM runner.
 * Unlike app_process, dalvikvm runs Java classes directly on ART without
 * initializing the Android framework. It accepts java-compatible arguments:
 *   dalvikvm64 -cp classpath MainClass [args]
 *
 * For Gradle's spawned sub-processes (kotlinc, etc.), we create a symlink:
 *   synthetic-jdk/bin/java → /system/bin/dalvikvm64
 * Symlinks bypass noexec because the kernel resolves to the target on /system.
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

        // Step 1: Find dalvikvm binary
        val dalvikvm = findDalvikvm()
        if (dalvikvm == null) {
            return@withContext StepResult.Failure(listOf(
                CompilationError("Gradle", ErrorSeverity.ERROR,
                    "dalvikvm not found on device. Expected at /system/bin/dalvikvm64 or /system/bin/dalvikvm.")
            ))
        }
        onLog("Using: ${dalvikvm.absolutePath}", ErrorSeverity.INFO)

        // Step 2: Set up synthetic JDK with java → dalvikvm symlink
        setupSyntheticJdk(dalvikvm, onLog)
        onLog("JAVA_HOME: ${syntheticJdkDir.absolutePath}", ErrorSeverity.INFO)

        // Step 3: Ensure gradle-wrapper.jar
        val wrapperJar = ensureGradleWrapperJar(projectDir, onLog)
        if (wrapperJar == null) {
            return@withContext StepResult.Failure(listOf(
                CompilationError("Gradle", ErrorSeverity.ERROR,
                    "gradle-wrapper.jar not found. Download it from the Components tab.")
            ))
        }
        onLog("Wrapper: ${wrapperJar.name} (${wrapperJar.length()} bytes)", ErrorSeverity.INFO)

        // Step 4: Android SDK
        val androidSdk = findAndroidSdk()
        if (androidSdk != null) {
            File(projectDir, "local.properties")
                .writeText("sdk.dir=${androidSdk.absolutePath.replace("\\", "/")}\n")
            onLog("Android SDK: ${androidSdk.absolutePath}", ErrorSeverity.INFO)
        } else {
            onLog("No local Android SDK — Gradle will attempt to download build tools", ErrorSeverity.WARNING)
        }

        // Step 5: Configure gradle.properties for on-device builds
        injectGradleProperties(projectDir, onLog)

        // Step 6: Build and execute the command
        val gradleHome = File(context.filesDir, "gradle_home").apply { mkdirs() }

        // dalvikvm syntax: dalvikvm [options] -cp classpath class [args]
        // We invoke via /system/bin/sh -c to set environment variables
        val shellCommand = buildString {
            // Environment variables
            append("JAVA_HOME='${syntheticJdkDir.absolutePath}' ")
            append("ANDROID_HOME='${androidSdk?.absolutePath ?: ""}' ")
            append("ANDROID_SDK_ROOT='${androidSdk?.absolutePath ?: ""}' ")
            append("GRADLE_USER_HOME='${gradleHome.absolutePath}' ")
            append("PATH='${syntheticJdkDir.absolutePath}/bin:/system/bin:/system/xbin' ")
            // dalvikvm invocation
            append("exec ${dalvikvm.absolutePath} ")
            append("-Dfile.encoding=UTF-8 ")
            append("-Dorg.gradle.appname=gradlew ")
            append("-Dorg.gradle.java.home='${syntheticJdkDir.absolutePath}' ")
            append("-cp '${wrapperJar.absolutePath}' ")
            append("org.gradle.wrapper.GradleWrapperMain ")
            append("assembleDebug ")
            append("--no-daemon ")
            append("--console=plain ")
            append("--warning-mode=all")
        }

        val command = listOf("/system/bin/sh", "-c", shellCommand)
        onLog("Invoking Gradle via dalvikvm...", ErrorSeverity.INFO)

        val result = executeGradleBuild(command, projectDir, onLog)

        if (result.exitCode != 0) {
            val allOutput = result.stderr + "\n" + result.stdout
            val errors = parseGradleErrors(allOutput)
            return@withContext StepResult.Failure(errors.ifEmpty {
                listOf(CompilationError("Gradle", ErrorSeverity.ERROR,
                    "Build failed (exit ${result.exitCode}).\n${allOutput.takeLast(1500)}"))
            })
        }

        // Find the produced APK
        val apk = findProducedApk(projectDir)
        if (apk == null) {
            return@withContext StepResult.Failure(listOf(
                CompilationError("Gradle", ErrorSeverity.ERROR,
                    "Build succeeded but no APK found in output directories.\n" +
                    "stdout tail: ${result.stdout.takeLast(500)}")
            ))
        }

        val outputApk = File(outputDir, apk.name)
        apk.copyTo(outputApk, overwrite = true)
        onLog("APK: ${outputApk.name} (${outputApk.length() / 1024} KB)", ErrorSeverity.INFO)

        StepResult.Success(listOf(outputApk))
    }

    /**
     * Finds the dalvikvm binary. Prefers 64-bit version.
     */
    private fun findDalvikvm(): File? {
        val candidates = listOf(
            "/system/bin/dalvikvm64",
            "/system/bin/dalvikvm",
            "/apex/com.android.art/bin/dalvikvm64",
            "/apex/com.android.art/bin/dalvikvm"
        )
        return candidates.map { File(it) }.firstOrNull { it.exists() }
    }

    /**
     * Creates synthetic JAVA_HOME with java → dalvikvm symlink.
     *
     * The symlink bypasses /data's noexec restriction because the kernel
     * resolves the symlink target (/system/bin/dalvikvm64) which IS on
     * an exec-mounted partition. When Gradle calls $JAVA_HOME/bin/java,
     * it actually executes dalvikvm64.
     *
     * dalvikvm accepts java-compatible arguments: -cp, -D, class name, args.
     */
    private fun setupSyntheticJdk(dalvikvm: File, onLog: LogCallback) {
        val binDir = File(syntheticJdkDir, "bin").apply { mkdirs() }
        val javaLink = File(binDir, "java")

        // Remove old file/symlink if exists
        if (javaLink.exists()) javaLink.delete()

        // Create symlink: java → /system/bin/dalvikvm64
        try {
            Os_symlink(dalvikvm.absolutePath, javaLink.absolutePath)
            onLog("Symlinked java -> ${dalvikvm.absolutePath}", ErrorSeverity.INFO)
        } catch (e: Exception) {
            // Fallback: write a shell script (invoked via sh, not directly)
            onLog("Symlink failed (${e.message}), using shell wrapper", ErrorSeverity.WARNING)
            javaLink.writeText("""#!/system/bin/sh
exec ${dalvikvm.absolutePath} "${'$'}@"
""")
        }

        // Release file for Gradle's JDK detection
        File(syntheticJdkDir, "release").writeText("""
JAVA_VERSION="17.0.0"
OS_ARCH="aarch64"
OS_NAME="Android"
IMPLEMENTOR="AndroidCompiler-ART"
""".trimIndent())
    }

    /**
     * Create a symlink using Android's Os.symlink API.
     */
    private fun Os_symlink(target: String, link: String) {
        // Use reflection to call android.system.Os.symlink() which exists on API 21+
        val osClass = Class.forName("android.system.Os")
        val symlinkMethod = osClass.getMethod("symlink", String::class.java, String::class.java)
        symlinkMethod.invoke(null, target, link)
    }

    /**
     * Inject gradle.properties for ART-compatible on-device builds.
     */
    private fun injectGradleProperties(projectDir: File, onLog: LogCallback) {
        val propsFile = File(projectDir, "gradle.properties")
        val existing = if (propsFile.exists()) propsFile.readText() else ""

        val injected = buildString {
            append(existing)
            appendLine()
            appendLine("# === AndroidCompiler on-device build settings ===")
            // Run kotlinc in Gradle's JVM — don't fork a separate java process
            appendLine("kotlin.compiler.execution.strategy=in-process")
            appendLine("org.gradle.daemon=false")
            appendLine("org.gradle.workers.max=2")
            appendLine("org.gradle.configuration-cache=false")
            appendLine("org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8")
            appendLine("org.gradle.parallel=true")
            appendLine("android.useAndroidX=true")
        }

        propsFile.writeText(injected)
        onLog("Gradle properties configured for ART", ErrorSeverity.INFO)
    }

    private fun ensureGradleWrapperJar(projectDir: File, onLog: LogCallback): File? {
        val wrapperDir = File(projectDir, "gradle/wrapper").apply { mkdirs() }
        val wrapperJar = File(wrapperDir, "gradle-wrapper.jar")
        val wrapperProps = File(wrapperDir, "gradle-wrapper.properties")

        if (wrapperJar.exists() && wrapperJar.length() > 1000) {
            ensureWrapperProperties(wrapperProps)
            return wrapperJar
        }

        // Check toolchain
        val toolchainJar = File(registry.toolchainDir, "gradle-wrapper.jar")
        if (toolchainJar.exists() && toolchainJar.length() > 1000) {
            toolchainJar.copyTo(wrapperJar, overwrite = true)
            onLog("Injected wrapper JAR from toolchain", ErrorSeverity.INFO)
            ensureWrapperProperties(wrapperProps)
            return wrapperJar
        }

        // Search Gradle caches
        for (cache in listOf(
            File(context.filesDir, "gradle_home/wrapper/dists"),
            File(System.getProperty("user.home", "/data"), ".gradle/wrapper/dists")
        )) {
            if (cache.exists()) {
                cache.walkTopDown()
                    .filter { it.name == "gradle-wrapper.jar" && it.length() > 10000 }
                    .firstOrNull()
                    ?.let {
                        it.copyTo(wrapperJar, overwrite = true)
                        onLog("Injected wrapper JAR from cache", ErrorSeverity.INFO)
                        ensureWrapperProperties(wrapperProps)
                        return wrapperJar
                    }
            }
        }

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
        return listOfNotNull(
            System.getenv("ANDROID_HOME"),
            System.getenv("ANDROID_SDK_ROOT"),
            "/data/data/com.termux/files/home/android-sdk"
        ).map { File(it) }.firstOrNull { it.exists() && File(it, "platforms").exists() }
    }

    private fun executeGradleBuild(
        command: List<String>,
        workingDir: File,
        onLog: LogCallback
    ): ProcessResult {
        try {
            val process = ProcessBuilder(command)
                .directory(workingDir)
                .redirectErrorStream(false)
                .start()

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
                                line.contains("FAILURE") -> onLog(line, ErrorSeverity.ERROR)
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
            onLog("Process failed: ${e.message}", ErrorSeverity.ERROR)
            return ProcessResult(-1, "", "Execute failed: ${e.message}\n${e.stackTraceToString()}")
        }
    }

    private fun findProducedApk(projectDir: File): File? {
        listOf("app/build/outputs/apk/debug", "app/build/outputs/apk/release",
            "build/outputs/apk/debug", "build/outputs/apk/release"
        ).forEach { path ->
            val dir = File(projectDir, path)
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

        Regex("""e: file:///(.+?):(\d+):(\d+): (.+)""").findAll(output).forEach { m ->
            errors.add(CompilationError("kotlinc", ErrorSeverity.ERROR,
                m.groupValues[4], m.groupValues[1],
                m.groupValues[2].toIntOrNull(), m.groupValues[3].toIntOrNull(),
                output.takeLast(2000)))
        }

        Regex("""(.+\.java):(\d+): error: (.+)""").findAll(output).forEach { m ->
            errors.add(CompilationError("javac", ErrorSeverity.ERROR,
                m.groupValues[3], m.groupValues[1],
                m.groupValues[2].toIntOrNull(), rawOutput = output.takeLast(2000)))
        }

        Regex("""Execution failed for task '(.+?)'\.\s*\n>\s*(.+)""").findAll(output).forEach { m ->
            errors.add(CompilationError("Gradle", ErrorSeverity.ERROR,
                "Task ${m.groupValues[1]}: ${m.groupValues[2]}",
                rawOutput = output.takeLast(2000)))
        }

        return errors
    }
}
