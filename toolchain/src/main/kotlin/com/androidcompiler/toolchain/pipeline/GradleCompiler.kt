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

        // Build command — use the real JDK's java binary
        val gradleHome = File(context.filesDir, "gradle_home").apply { mkdirs() }

        val command = mutableListOf(
            javaBin.absolutePath,
            "-Xmx3g",
            "-Dfile.encoding=UTF-8",
            "-Dorg.gradle.appname=gradlew",
            "-Dorg.gradle.java.home=${javaHome.absolutePath}",
            "-cp", wrapperJar.absolutePath,
            "org.gradle.wrapper.GradleWrapperMain",
            "assembleDebug",
            "--no-daemon",
            "--console=plain",
            "--warning-mode=all"
        )

        val env = mutableMapOf<String, String>()
        env["JAVA_HOME"] = javaHome.absolutePath
        env["ANDROID_HOME"] = androidSdk?.absolutePath ?: ""
        env["ANDROID_SDK_ROOT"] = androidSdk?.absolutePath ?: ""
        env["GRADLE_USER_HOME"] = gradleHome.absolutePath
        env["PATH"] = "${javaHome.absolutePath}/bin:/system/bin:/system/xbin:${System.getenv("PATH") ?: ""}"

        // Termux-specific: ensure LD_LIBRARY_PATH includes Termux's libs
        val termuxLib = File(javaHome, "lib")
        if (termuxLib.exists()) {
            env["LD_LIBRARY_PATH"] = "${termuxLib.absolutePath}:${System.getenv("LD_LIBRARY_PATH") ?: ""}"
        }

        onLog("Running: gradlew assembleDebug --no-daemon", ErrorSeverity.INFO)

        val result = executeGradleBuild(command, projectDir, env, onLog)

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
        return listOfNotNull(
            System.getenv("ANDROID_HOME"),
            System.getenv("ANDROID_SDK_ROOT"),
            "/data/data/com.termux/files/home/android-sdk"
        ).map { File(it) }.firstOrNull { it.exists() && File(it, "platforms").exists() }
    }

    private fun injectGradleProperties(projectDir: File, onLog: LogCallback) {
        val propsFile = File(projectDir, "gradle.properties")
        val existing = if (propsFile.exists()) propsFile.readText() else ""

        val injected = buildString {
            append(existing)
            appendLine()
            appendLine("# === AndroidCompiler on-device build settings ===")
            appendLine("kotlin.compiler.execution.strategy=in-process")
            appendLine("org.gradle.daemon=false")
            appendLine("org.gradle.workers.max=2")
            appendLine("org.gradle.configuration-cache=false")
            appendLine("org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8")
            appendLine("org.gradle.parallel=true")
            appendLine("android.useAndroidX=true")
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
        env: Map<String, String>,
        onLog: LogCallback
    ): ProcessResult {
        try {
            val process = ProcessBuilder(command)
                .directory(workingDir)
                .redirectErrorStream(false)
                .also { it.environment().putAll(env) }
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
