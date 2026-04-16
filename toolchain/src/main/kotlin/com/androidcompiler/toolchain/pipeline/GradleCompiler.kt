package com.androidcompiler.toolchain.pipeline

import android.content.Context
import com.androidcompiler.core.common.model.CompilationError
import com.androidcompiler.core.common.model.ErrorSeverity
import com.androidcompiler.toolchain.jdk.TermuxJdkInstaller
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
 * STRATEGY: Use Termux's JDK if available, otherwise provide clear guidance.
 *
 * Why stock Android can't run Gradle directly:
 * - dalvikvm/ART's URLClassLoader cannot load .class files from JARs
 * - Gradle's entire plugin system depends on URLClassLoader
 * - This is a fundamental ART limitation, not a configuration issue
 *
 * What DOES work: Termux installs a Bionic-linked JDK (openjdk-17) that
 * runs as a full JVM, supporting URLClassLoader and all standard Java APIs.
 * This compiler detects Termux's JDK and uses it transparently.
 */
@Singleton
class GradleCompiler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val registry: ToolchainRegistry,
    private val jdkInstaller: TermuxJdkInstaller
) {
    companion object {
        private const val GRADLE_WRAPPER_VERSION = "8.11.1"
        private val TERMUX_JDK_PATHS = listOf(
            "/data/data/com.termux/files/usr",
            "/data/data/com.termux/files/usr/lib/jvm/java-17-openjdk"
        )
    }

    suspend fun compile(
        projectDir: File,
        outputDir: File,
        projectInfo: ProjectAnalyzer.ProjectInfo,
        onLog: LogCallback
    ): StepResult = withContext(Dispatchers.IO) {

        onLog("Detected Gradle project: ${projectInfo.packageName ?: projectDir.name}", ErrorSeverity.INFO)

        // Find a working JDK (Termux)
        val javaHome = findJavaHome()
        if (javaHome == null) {
            onLog("No compatible JDK found on device", ErrorSeverity.ERROR)
            return@withContext StepResult.Failure(listOf(
                CompilationError("Gradle", ErrorSeverity.ERROR, buildString {
                    appendLine("Gradle projects require a JDK (OpenJDK 17).")
                    appendLine()
                    appendLine("Go to the Components tab and download 'OpenJDK 17 (Termux/Bionic)'.")
                    appendLine("This downloads a ~200MB JDK that runs natively on Android.")
                    appendLine()
                    appendLine("For simple projects without external dependencies,")
                    appendLine("the built-in compiler works without the JDK.")
                })
            ))
        }

        val javaBin = File(javaHome, "bin/java")
        onLog("JDK: ${javaHome.absolutePath}", ErrorSeverity.INFO)
        onLog("java: ${javaBin.absolutePath}", ErrorSeverity.INFO)

        // Ensure wrapper JAR
        val wrapperJar = ensureGradleWrapperJar(projectDir, onLog)
        if (wrapperJar == null) {
            return@withContext StepResult.Failure(listOf(
                CompilationError("Gradle", ErrorSeverity.ERROR,
                    "gradle-wrapper.jar not found. Download it from the Components tab.")
            ))
        }

        // Android SDK
        val androidSdk = findAndroidSdk()
        if (androidSdk != null) {
            File(projectDir, "local.properties")
                .writeText("sdk.dir=${androidSdk.absolutePath.replace("\\", "/")}\n")
            onLog("Android SDK: ${androidSdk.absolutePath}", ErrorSeverity.INFO)
        } else {
            onLog("No local Android SDK — Gradle will download build tools", ErrorSeverity.WARNING)
        }

        // Configure for on-device build
        injectGradleProperties(projectDir, onLog)

        // Build command via /system/bin/sh — required because /data is noexec.
        // ProcessBuilder can't execute binaries from /data directly, but
        // /system/bin/sh (on exec-mounted /system) CAN exec them.
        val gradleHome = File(context.filesDir, "gradle_home").apply { mkdirs() }

        // Build LD_LIBRARY_PATH
        val ldPaths = mutableListOf<String>()
        val jdkLib = File(javaHome, "lib")
        if (jdkLib.exists()) ldPaths.add(jdkLib.absolutePath)
        val termuxLibDir = File(jdkInstaller.jdkDir, "lib")
        if (termuxLibDir.exists()) ldPaths.add(termuxLibDir.absolutePath)
        File(jdkLib, "server").let { if (it.exists()) ldPaths.add(it.absolutePath) }
        val ldPath = ldPaths.joinToString(":")

        // Temp dir — the Termux JDK defaults to /data/data/com.termux/files/usr/tmp
        // which doesn't exist on devices without Termux. Override to app cache.
        val tmpDir = File(context.cacheDir, "tmp").apply { mkdirs() }

        // User home — the Termux JDK defaults to /data/data/com.termux/files/home
        // which doesn't exist. AGP's AndroidLocationsBuildService needs ~/.android/.
        val userHome = File(context.filesDir, "home").apply { mkdirs() }
        File(userHome, ".android").mkdirs()

        // Build the full shell command with env vars inline
        val shellCmd = buildString {
            append("export JAVA_HOME='${javaHome.absolutePath}' && ")
            append("export ANDROID_HOME='${androidSdk?.absolutePath ?: ""}' && ")
            append("export ANDROID_SDK_ROOT='${androidSdk?.absolutePath ?: ""}' && ")
            append("export ANDROID_USER_HOME='${userHome.absolutePath}/.android' && ")
            append("export GRADLE_USER_HOME='${gradleHome.absolutePath}' && ")
            append("export PATH='${javaHome.absolutePath}/bin:/system/bin:/system/xbin' && ")
            append("export LD_LIBRARY_PATH='$ldPath' && ")
            append("export TMPDIR='${tmpDir.absolutePath}' && ")
            append("export HOME='${userHome.absolutePath}' && ")
            // JAVA_TOOL_OPTIONS is read by ALL JVM instances (wrapper + daemon).
            // This ensures the forked daemon JVM also uses fork() for ProcessBuilder
            // and has correct tmpdir/user.home. Gradle strips custom -D from daemon opts.
            append("export JAVA_TOOL_OPTIONS='-Djdk.lang.Process.launchMechanism=FORK -Djava.io.tmpdir=${tmpDir.absolutePath} -Duser.home=${userHome.absolutePath}' && ")
            append("exec '${javaBin.absolutePath}' ")
            append("-Xmx4g ")
            append("-Dfile.encoding=UTF-8 ")
            append("-Djava.io.tmpdir='${tmpDir.absolutePath}' ")
            append("-Duser.home='${userHome.absolutePath}' ")
            append("-Djdk.lang.Process.launchMechanism=FORK ")
            append("-Djava.library.path='$ldPath' ")
            append("-Dorg.gradle.appname=gradlew ")
            append("-Dorg.gradle.java.home='${javaHome.absolutePath}' ")
            append("-cp '${wrapperJar.absolutePath}' ")
            append("org.gradle.wrapper.GradleWrapperMain ")
            append("assembleDebug ")
            append("--no-daemon ")
            append("--console=plain ")
            append("--warning-mode=all")
        }

        // Execute via /system/bin/sh which is on an exec-mounted partition.
        // With targetSdk=28, the app runs in untrusted_app_29 SELinux domain
        // which allows executing binaries from the app's data directory.
        val command = listOf("/system/bin/sh", "-c", shellCmd)

        onLog("Running: gradlew assembleDebug --no-daemon", ErrorSeverity.INFO)
        onLog("LD_LIBRARY_PATH: $ldPath", ErrorSeverity.INFO)

        val result = executeGradleBuild(command, projectDir, onLog)

        if (result.exitCode != 0) {
            val allOutput = result.stderr + "\n" + result.stdout
            val errors = parseGradleErrors(allOutput)
            return@withContext StepResult.Failure(errors.ifEmpty {
                listOf(CompilationError("Gradle", ErrorSeverity.ERROR,
                    "Build failed (exit ${result.exitCode}).\n${allOutput.takeLast(1500)}"))
            })
        }

        val apk = findProducedApk(projectDir)
        if (apk == null) {
            return@withContext StepResult.Failure(listOf(
                CompilationError("Gradle", ErrorSeverity.ERROR,
                    "Build completed but no APK found.\n${result.stdout.takeLast(500)}")
            ))
        }

        val outputApk = File(outputDir, apk.name)
        apk.copyTo(outputApk, overwrite = true)
        onLog("APK: ${outputApk.name} (${outputApk.length() / 1024} KB)", ErrorSeverity.INFO)

        StepResult.Success(listOf(outputApk))
    }

    private fun findJavaHome(): File? {
        // Priority 1: Bundled JDK (downloaded from Termux repo by AndroidCompiler)
        val bundledJdk = jdkInstaller.getJavaHome() ?: registry.getJavaHome()
        if (bundledJdk != null && File(bundledJdk, "bin/java").exists()) return bundledJdk

        // Priority 2: Termux installed JDK (if user has Termux)
        for (path in TERMUX_JDK_PATHS) {
            val dir = File(path)
            if (File(dir, "bin/java").exists()) return dir
        }
        val jvmDir = File("/data/data/com.termux/files/usr/lib/jvm")
        if (jvmDir.exists()) {
            jvmDir.listFiles()?.forEach { child ->
                if (File(child, "bin/java").exists()) return child
            }
        }

        // Priority 3: Environment variable
        System.getenv("JAVA_HOME")?.let { path ->
            val dir = File(path)
            if (File(dir, "bin/java").exists()) return dir
        }

        return null
    }

    private fun findAndroidSdk(): File? {
        // Priority 1: Real SDK from environment or Termux
        listOfNotNull(
            System.getenv("ANDROID_HOME"),
            System.getenv("ANDROID_SDK_ROOT"),
            "/data/data/com.termux/files/home/android-sdk"
        ).map { File(it) }.firstOrNull { it.exists() && File(it, "platforms").exists() }
            ?.let { return it }

        // Priority 2: Create minimal SDK from our bundled android.jar
        val androidJar = registry.getAndroidJar()
        if (androidJar.exists()) {
            val sdkRoot = File(context.filesDir, "android-sdk")
            val platformDir = File(sdkRoot, "platforms/android-35")
            platformDir.mkdirs()
            val targetJar = File(platformDir, "android.jar")
            if (!targetJar.exists() || targetJar.length() != androidJar.length()) {
                androidJar.copyTo(targetJar, overwrite = true)
            }
            // Pre-accept SDK licenses so AGP doesn't reject the synthetic SDK
            val licensesDir = File(sdkRoot, "licenses").apply { mkdirs() }
            File(licensesDir, "android-sdk-license").writeText(
                "\n24333f8a63b6825ea9c5514f83c2829b004d1fee\n" +
                "\n84831b9409646a918e30573bab4c9c91346d8abd\n" +
                "\nd975f751698a77b662f1254ddbeed3901e976f5a\n"
            )
            File(licensesDir, "android-sdk-preview-license").writeText(
                "\n84831b9409646a918e30573bab4c9c91346d8abd\n"
            )
            return sdkRoot
        }

        return null
    }

    private fun injectGradleProperties(projectDir: File, onLog: LogCallback) {
        val propsFile = File(projectDir, "gradle.properties")
        val existing = if (propsFile.exists()) propsFile.readText() else ""

        val javaHome = findJavaHome()
        val jdkLibDir = jdkInstaller.jdkDir.absolutePath + "/lib"
        val jdkVmLib = javaHome?.absolutePath?.let { "$it/lib" } ?: ""

        // Remove any existing org.gradle.jvmargs lines so we can set our own
        val cleaned = existing.lines()
            .filter { !it.trim().startsWith("org.gradle.jvmargs") }
            .joinToString("\n")

        val injected = buildString {
            append(cleaned)
            appendLine()
            appendLine("# === AndroidCompiler on-device build settings ===")
            // Force ALL compilation in-process — no forked java processes
            appendLine("kotlin.compiler.execution.strategy=in-process")
            appendLine("org.gradle.daemon=false")
            appendLine("org.gradle.workers.max=2")
            appendLine("org.gradle.configuration-cache=false")
            appendLine("org.gradle.parallel=true")
            appendLine("android.useAndroidX=true")
            // CRITICAL: These JVM args MUST match what we pass on the java command line
            // in GradleCompiler.compile(). If they differ, Gradle forks a daemon process.
            // -Djdk.lang.Process.launchMechanism=FORK is required because Android's bionic
            // libc posix_spawn() doesn't work for process creation; fork() does.
            // -Djava.library.path ensures native libs (libjvm.so etc) are found by child processes.
            val tmpDir = File(context.cacheDir, "tmp").absolutePath
            val userHome = File(context.filesDir, "home").absolutePath
            appendLine("org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8 -Djava.io.tmpdir=$tmpDir -Duser.home=$userHome -Djdk.lang.Process.launchMechanism=FORK -Djava.library.path=$jdkVmLib:$jdkLibDir")
            // Use our bundled ARM64 AAPT2 instead of AGP's x86_64 Linux binary
            val aapt2 = registry.getAapt2Binary()
            if (aapt2.exists()) {
                appendLine("android.aapt2FromMavenOverride=${aapt2.absolutePath}")
            }
        }
        propsFile.writeText(injected)
        onLog("Gradle properties configured", ErrorSeverity.INFO)
    }

    private fun ensureGradleWrapperJar(projectDir: File, onLog: LogCallback): File? {
        val wrapperDir = File(projectDir, "gradle/wrapper").apply { mkdirs() }
        val wrapperJar = File(wrapperDir, "gradle-wrapper.jar")
        val wrapperProps = File(wrapperDir, "gradle-wrapper.properties")

        if (wrapperJar.exists() && wrapperJar.length() > 1000) {
            ensureWrapperProperties(wrapperProps)
            return wrapperJar
        }

        val toolchainJar = File(registry.toolchainDir, "gradle-wrapper.jar")
        if (toolchainJar.exists() && toolchainJar.length() > 1000) {
            toolchainJar.copyTo(wrapperJar, overwrite = true)
            onLog("Injected gradle-wrapper.jar from toolchain", ErrorSeverity.INFO)
            ensureWrapperProperties(wrapperProps)
            return wrapperJar
        }

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
