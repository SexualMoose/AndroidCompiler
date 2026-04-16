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

        // Clean up any stale Gradle init scripts
        val gradleHomeDir = File(context.filesDir, "gradle_home")
        File(gradleHomeDir, "init.d/aapt2-env.gradle").let { if (it.exists()) it.delete() }

        // Build command via /system/bin/sh — required because /data is noexec.
        // ProcessBuilder can't execute binaries from /data directly, but
        // /system/bin/sh (on exec-mounted /system) CAN exec them.
        val gradleHome = File(context.filesDir, "gradle_home").apply { mkdirs() }

        // Build LD_LIBRARY_PATH — include JDK libs AND AAPT2 prefix libs
        val ldPaths = mutableListOf<String>()
        val jdkLib = File(javaHome, "lib")
        if (jdkLib.exists()) ldPaths.add(jdkLib.absolutePath)
        val termuxLibDir = File(jdkInstaller.jdkDir, "lib")
        if (termuxLibDir.exists()) ldPaths.add(termuxLibDir.absolutePath)
        File(jdkLib, "server").let { if (it.exists()) ldPaths.add(it.absolutePath) }
        // AAPT2's shared library dependencies (libprotobuf, libexpat, etc.)
        val aapt2LibDir = registry.getAapt2LibDir()
        if (aapt2LibDir.exists()) ldPaths.add(aapt2LibDir.absolutePath)
        val ldPath = ldPaths.joinToString(":")

        // Temp dir — the Termux JDK defaults to /data/data/com.termux/files/usr/tmp
        // which doesn't exist on devices without Termux. Override to app cache.
        val tmpDir = File(context.cacheDir, "tmp").apply { mkdirs() }

        // User home — the Termux JDK defaults to /data/data/com.termux/files/home
        // which doesn't exist. AGP's AndroidLocationsBuildService needs ~/.android/.
        val userHome = File(context.filesDir, "home").apply { mkdirs() }
        File(userHome, ".android").mkdirs()

        // Pre-build verification: check AAPT2 can find its shared libraries
        val aapt2Bin = registry.getAapt2Binary()
        if (aapt2Bin.exists()) {
            onLog("AAPT2: ${aapt2Bin.absolutePath}", ErrorSeverity.INFO)
            // Ensure shared libs from JDK are available in aapt2-prefix/lib.
            // AAPT2 depends on libc++_shared.so, libz.so.1, etc. which come from
            // Termux's JDK dependencies but aren't in the AAPT2 packages.
            val jdkLibSrc = File(jdkInstaller.jdkDir, "lib")
            if (jdkLibSrc.exists() && aapt2LibDir.exists()) {
                val neededLibs = listOf("libc++_shared.so", "libz.so", "libz.so.1", "libz.so.1.3.2")
                for (libName in neededLibs) {
                    val dest = File(aapt2LibDir, libName)
                    if (!dest.exists()) {
                        val src = File(jdkLibSrc, libName)
                        if (src.exists()) {
                            try { src.copyTo(dest); dest.setExecutable(true, false) }
                            catch (_: Exception) { }
                        }
                    }
                }
                onLog("Ensured AAPT2 shared library dependencies", ErrorSeverity.INFO)
            }
            val soFiles = aapt2LibDir.listFiles()?.filter { ".so" in it.name }?.size ?: 0
            onLog("AAPT2 libs: $soFiles .so files in ${aapt2LibDir.absolutePath}", ErrorSeverity.INFO)
        }

        // Build the full shell command with env vars inline
        val shellCmd = buildString {
            // Raise file descriptor limit — AAPT2 daemon keeps many files open
            append("ulimit -n 65535 2>/dev/null; ")
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
            append("--warning-mode=all ")
            append("--stacktrace")
        }

        // Create a wrapper script for AAPT2 that sets LD_LIBRARY_PATH.
        // The Termux AAPT2 binary has a hardcoded RPATH pointing to
        // /data/data/com.termux/files/usr/lib (which doesn't exist), so
        // LD_LIBRARY_PATH is required. However, AGP starts AAPT2 daemon
        // via ProcessBuilder and LD_LIBRARY_PATH may not propagate on
        // Android's linker namespace system. The wrapper ensures it's set.
        val aapt2Wrapper = File(aapt2Bin.parentFile, "aapt2-wrapper")
        if (aapt2Bin.exists()) {
            val realBin = File(aapt2Bin.parentFile, "aapt2-real")
            if (!realBin.exists()) {
                // First time: rename original binary to aapt2-real
                aapt2Bin.renameTo(realBin)
            }
            // (Re)write the wrapper script — it sets LD_LIBRARY_PATH then exec's the real binary
            // Also logs invocations for debugging
            val logFile = File(context.filesDir, "aapt2_wrapper.log")
            aapt2Bin.writeText("""#!/system/bin/sh
echo "AAPT2 wrapper invoked: ${'$'}@" >> '${logFile.absolutePath}'
echo "LD_LIBRARY_PATH before: ${'$'}LD_LIBRARY_PATH" >> '${logFile.absolutePath}'
export LD_LIBRARY_PATH='${aapt2LibDir.absolutePath}:${File(jdkInstaller.jdkDir, "lib").absolutePath}:${File(javaHome, "lib").absolutePath}'
echo "LD_LIBRARY_PATH after: ${'$'}LD_LIBRARY_PATH" >> '${logFile.absolutePath}'
exec '${realBin.absolutePath}' "${'$'}@"
""")
            aapt2Bin.setExecutable(true, false)
            aapt2Bin.setReadable(true, false)
            onLog("AAPT2 wrapper created with LD_LIBRARY_PATH", ErrorSeverity.INFO)
        }

        // Execute via /system/bin/sh which is on an exec-mounted partition.
        // With targetSdk=28, the app runs in untrusted_app_29 SELinux domain
        // which allows executing binaries from the app's data directory.
        val command = listOf("/system/bin/sh", "-c", shellCmd)

        onLog("Running: gradlew assembleDebug --no-daemon", ErrorSeverity.INFO)
        onLog("LD_LIBRARY_PATH: $ldPath", ErrorSeverity.INFO)

        val result = executeGradleBuild(command, projectDir, onLog)

        // Write full build output to persistent location for debugging
        try {
            File(context.filesDir, "gradle_build_output.txt").writeText(
                "=== STDOUT ===\n${result.stdout}\n=== STDERR ===\n${result.stderr}\n=== EXIT: ${result.exitCode} ===\n"
            )
        } catch (_: Exception) { }

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
            // Remove any stale build-tools stub that would cause AGP to report "corrupted"
            File(sdkRoot, "build-tools").let { if (it.exists()) it.deleteRecursively() }
            // Create platform dirs for multiple possible names AGP might look for.
            // Provide both API 34 and 35 platform dirs (our android.jar is API 34
            // which AAPT2 v2.19 can parse; API 35's resources.arsc uses a newer format).
            for (suffix in listOf("android-34", "android-34-ext7", "android-35", "android-35-2", "android-35-ext14")) {
                val platformDir = File(sdkRoot, "platforms/$suffix")
                platformDir.mkdirs()
                val targetJar = File(platformDir, "android.jar")
                if (!targetJar.exists() || targetJar.length() != androidJar.length()) {
                    androidJar.copyTo(targetJar, overwrite = true)
                }
                // Ensure android.jar is readable by all (AAPT2 daemon runs as same user
                // but explicit perms avoid SELinux surprises)
                targetJar.setReadable(true, false)
                targetJar.setWritable(false, false)
                // core-for-system-modules.jar — needed by AGP for Java compilation.
                // Extracted alongside android.jar from the platform ZIP.
                val cfsm = File(platformDir, "core-for-system-modules.jar")
                val cfsmSource = File(registry.toolchainDir, "core-for-system-modules.jar")
                if (cfsmSource.exists() && (!cfsm.exists() || cfsm.length() != cfsmSource.length())) {
                    cfsmSource.copyTo(cfsm, overwrite = true)
                } else if (!cfsm.exists()) {
                    // Fallback: empty JAR if not extracted from platform ZIP
                    cfsm.writeBytes(createEmptyJar())
                }
                // framework.aidl — needed by AGP for AIDL compilation
                val fwAidl = File(platformDir, "framework.aidl")
                if (!fwAidl.exists()) fwAidl.writeText("// stub\n")
                // AGP also looks for build.prop and source.properties — create stubs
                File(platformDir, "build.prop").apply {
                    if (!exists()) writeText(
                        "ro.build.version.sdk=35\n" +
                        "ro.build.version.codename=REL\n"
                    )
                }
                File(platformDir, "source.properties").apply {
                    if (!exists()) writeText(
                        "Pkg.Desc=Android SDK Platform 35\n" +
                        "Pkg.Revision=1\n" +
                        "AndroidVersion.ApiLevel=35\n" +
                        "Layoutlib.Api=15\n" +
                        "Layoutlib.Revision=1\n"
                    )
                }
            }
            // Create build-tools with our ARM64 AAPT2 wrapper.
            // AGP validates build-tools by checking for aapt, aapt2, dx, and other tools.
            // We provide our wrapper for aapt2 and stub scripts for tools AGP checks but
            // doesn't actually invoke during modern builds.
            val aapt2Src = registry.getAapt2Binary()
            if (aapt2Src.exists()) {
                val btDir = File(sdkRoot, "build-tools/35.0.0")
                btDir.mkdirs()
                File(btDir, "source.properties").writeText(
                    "Pkg.Revision=35.0.0\nPkg.Desc=Android SDK Build-Tools 35\n"
                )
                // AAPT2 — our wrapper with LD_LIBRARY_PATH
                val btAapt2 = File(btDir, "aapt2")
                aapt2Src.copyTo(btAapt2, overwrite = true)
                btAapt2.setExecutable(true, false)
                // Copy the real binary too if wrapper references it
                val realBin = File(aapt2Src.parentFile, "aapt2-real")
                if (realBin.exists()) {
                    val btReal = File(btDir, "aapt2-real")
                    if (!btReal.exists()) realBin.copyTo(btReal)
                    btReal.setExecutable(true, false)
                }
                // Stub scripts for tools that AGP checks but doesn't run in modern builds
                for (tool in listOf("aapt", "aidl", "dx", "dexdump", "split-select",
                    "llvm-rs-cc", "zipalign", "apksigner", "mainDexClasses")) {
                    val stub = File(btDir, tool)
                    if (!stub.exists()) {
                        stub.writeText("#!/system/bin/sh\necho 'stub: $tool not available on-device'\nexit 1\n")
                        stub.setExecutable(true, false)
                    }
                }
                // Empty JAR stubs for build-tools validation
                // AGP checks these exist but uses its own bundled versions at runtime
                val emptyJarBytes = createEmptyJar()
                for (jar in listOf("core-lambda-stubs.jar", "d8.jar",
                    "lib/dx.jar", "lib/shrinkedAndroid.jar")) {
                    val f = File(btDir, jar)
                    f.parentFile?.mkdirs()
                    if (!f.exists()) f.writeBytes(emptyJarBytes)
                }
                // renderscript/ directory that some AGP versions check
                File(btDir, "renderscript/lib").mkdirs()
                File(btDir, "renderscript/include").mkdirs()
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
            // Prevent AGP from downloading x86_64 build-tools over our ARM64 ones
            appendLine("android.builder.sdkDownload=false")
            appendLine("android.suppressUnsupportedCompileSdk=35")
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
                                line.contains("AAPT") -> onLog(line, ErrorSeverity.ERROR)
                                line.contains("aapt2") -> onLog(line, ErrorSeverity.WARNING)
                                line.contains("failed to load") -> onLog(line, ErrorSeverity.ERROR)
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

    /** Creates a minimal valid JAR file (just the manifest) */
    private fun createEmptyJar(): ByteArray {
        val baos = java.io.ByteArrayOutputStream()
        java.util.jar.JarOutputStream(baos).use { jos ->
            // JarOutputStream automatically adds a MANIFEST.MF entry
        }
        return baos.toByteArray()
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
