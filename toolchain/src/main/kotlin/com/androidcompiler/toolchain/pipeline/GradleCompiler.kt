package com.androidcompiler.toolchain.pipeline

import android.content.Context
import android.util.Log
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
        private const val TAG = BuildDiagnostics.TAG
        private const val GRADLE_WRAPPER_VERSION = "8.11.1"
        private val TERMUX_JDK_PATHS = listOf(
            "/data/data/com.termux/files/usr",
            "/data/data/com.termux/files/usr/lib/jvm/java-17-openjdk"
        )

        /**
         * Substrings in Gradle's output that indicate the forked build JVM could
         * not start — almost always a launcher↔libjvm.so patch mismatch or a
         * corrupt/partial JDK. Used to trigger the one-shot JDK-repair retry.
         */
        private val JVM_INIT_FAILURE_MARKERS = listOf(
            "Could not create the Java Virtual Machine",
            "Error occurred during initialization of VM",
            "libjvm",
            "Failed to load libjli",
            "JNI_CreateJavaVM",
            "error while loading shared libraries",
            "cannot execute binary file"
        )
    }

    /** Tee a line to both the in-app log UI and logcat/persistent file. */
    private fun logBoth(onLog: LogCallback, message: String, severity: ErrorSeverity) {
        when (severity) {
            ErrorSeverity.ERROR -> Log.e(TAG, message)
            ErrorSeverity.WARNING -> Log.w(TAG, message)
            else -> Log.i(TAG, message)
        }
        BuildDiagnostics.append(context, "[$severity] $message")
        onLog(message, severity)
    }

    /** True when [result]'s output looks like a JVM that failed to initialize. */
    private fun isJvmInitFailure(result: ProcessResult): Boolean {
        if (result.exitCode == 0) return false
        val output = result.stdout + "\n" + result.stderr
        return JVM_INIT_FAILURE_MARKERS.any { output.contains(it, ignoreCase = true) }
    }

    /** Overrides for the per-compile version selection. Null fields fall back to
     *  what was detected from projectInfo, then to registry defaults. */
    data class VersionOverrides(
        val gradleVersion: String? = null,
        val compileSdk: Int? = null
    )

    suspend fun compile(
        projectDir: File,
        outputDir: File,
        projectInfo: ProjectAnalyzer.ProjectInfo,
        onLog: LogCallback,
        overrides: VersionOverrides = VersionOverrides()
    ): StepResult = withContext(Dispatchers.IO) {

        // Persistent diagnostics: open a fresh section in last_compile.log and
        // tee key milestones to logcat (tag ACBuild). Previously this path had
        // ZERO logcat, so on-device build failures were undebuggable.
        BuildDiagnostics.beginSession(
            context,
            "Gradle compile: ${projectInfo.packageName ?: projectDir.name}"
        )

        // Self-recovery (step 3): if a JDK from a previous app version is left in
        // filesDir whose patch version no longer matches the bundled launcher,
        // wipe it now so the readiness check below forces a fresh, matched
        // download. Silent, no user action.
        if (jdkInstaller.invalidateIfMismatched()) {
            logBoth(onLog, "Removed a stale/mismatched JDK; a matched one will be re-downloaded.",
                ErrorSeverity.WARNING)
        }

        logBoth(onLog, "Detected Gradle project: ${projectInfo.packageName ?: projectDir.name}",
            ErrorSeverity.INFO)

        // Resolve the effective versions for this compile
        val effectiveGradleVersion = overrides.gradleVersion
            ?: projectInfo.requiredGradleVersion
            ?: registry.defaultGradleVersion
        val effectiveApiLevel = overrides.compileSdk
            ?: projectInfo.requiredCompileSdk
            ?: registry.defaultApiLevel
        logBoth(onLog, "Using Gradle $effectiveGradleVersion, compileSdk $effectiveApiLevel", ErrorSeverity.INFO)

        // Find a working JDK (Termux)
        val javaHome = findJavaHome()
        if (javaHome == null) {
            logBoth(onLog, "No compatible JDK found on device", ErrorSeverity.ERROR)
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

        val javaBin = pickJavaBinary(javaHome)
        val bundledJava = registry.getBundledJavaLauncher() != null && javaBin == registry.getBundledJavaLauncher()
        logBoth(onLog, "JDK: ${javaHome.absolutePath}", ErrorSeverity.INFO)
        logBoth(onLog, "java: ${javaBin.absolutePath} ${if (bundledJava) "(bundled APK)" else "(downloaded)"} " +
            "[pinned=${TermuxJdkInstaller.BUNDLED_JDK_VERSION}, installed=${registry.getInstalledVersion("jdk")}]",
            ErrorSeverity.INFO)

        // When using the bundled launcher, Gradle will still internally try to
        // spawn `$JAVA_HOME/bin/java` (JVM probing), `$JAVA_HOME/bin/jlink`
        // (JdkImageTransform), and possibly other tools directly. On targetSdk=35
        // those downloaded binaries in /data/data/ cannot be exec'd by SELinux.
        // Replace each with a symlink to its APK-bundled counterpart — exec
        // follows symlinks to the target's SELinux label, which is exec-allowed.
        if (bundledJava) {
            linkJdkToolsToBundled(javaHome, onLog)
        }

        // Same problem for AAPT2 — AGP requires the override path to end in
        // `aapt2` (not `libaapt2.so`). We expose a symlink at the expected
        // name pointing to the bundled binary. The symlink lives in app_data_file
        // but exec's through to the nativeLibraryDir label, so SELinux allows it.
        val bundledAapt2Source = registry.getBundledAapt2()
        if (bundledAapt2Source != null && bundledAapt2Source.exists()) {
            linkAapt2ToBundled(bundledAapt2Source, onLog)
        }

        // Ensure wrapper JAR (version-specific if available, fallback to bundled)
        val wrapperJar = ensureGradleWrapperJar(projectDir, effectiveGradleVersion, onLog)
        if (wrapperJar == null) {
            logBoth(onLog, "gradle-wrapper.jar not found", ErrorSeverity.ERROR)
            return@withContext StepResult.Failure(listOf(
                CompilationError("Gradle", ErrorSeverity.ERROR,
                    "gradle-wrapper.jar not found. Download it from the Components tab.")
            ))
        }

        // Android SDK (uses version-specific android.jar if available)
        val androidSdk = findAndroidSdk(effectiveApiLevel)
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

        // Pre-build verification: locate AAPT2 and ensure its shared libs are in place
        val aapt2Bin = pickAapt2Binary()
        val bundledAapt2 = aapt2Bin != null && aapt2Bin == registry.getBundledAapt2()
        if (aapt2Bin != null) {
            onLog("AAPT2: ${aapt2Bin.absolutePath} ${if (bundledAapt2) "(bundled APK)" else "(downloaded)"}",
                ErrorSeverity.INFO)
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

        // ── Force build OUTPUTS onto INTERNAL ext4 storage ───────────────────
        // The project SOURCES may live on external storage (/storage/emulated/0,
        // e.g. /Documents/MyProject). That volume is sdcardfs/FUSE and silently
        // REJECTS chmod(), so Gradle's Sync/Copy tasks die with:
        //   "Could not set file mode 770 on .../processDebugJavaRes/out/META-INF".
        // Internal app storage is REAL ext4 and supports POSIX modes, so we redirect
        // every module's buildDirectory (and the .gradle project cache) there via an
        // init script. Sources stay put — reads work fine on external storage; only
        // the chmod-heavy build outputs move. A stable per-project hash dir means
        // incremental builds still reuse outputs across runs.
        val buildWorkspace = File(context.filesDir,
            "build-workspace/" + Integer.toHexString(projectDir.absolutePath.hashCode())).apply { mkdirs() }
        val redirectedModulesRoot = File(buildWorkspace, "modules").apply { mkdirs() }
        val projectCacheDir = File(buildWorkspace, "gradle-project-cache").apply { mkdirs() }
        val gBuildRoot = redirectedModulesRoot.absolutePath.replace("\\", "\\\\").replace("'", "\\'")
        val buildDirInitScript = File(buildWorkspace, "redirect-build-dir.init.gradle").apply {
            writeText(
                "// AUTO-GENERATED by AndroidCompiler — DO NOT EDIT.\n" +
                "// Redirects every module's build dir onto internal ext4 storage so chmod()\n" +
                "// works; external sdcardfs/FUSE rejects it and breaks Sync/Copy tasks.\n" +
                "def __acBuildRoot = new File('$gBuildRoot')\n" +
                "gradle.rootProject { rp ->\n" +
                "    rp.allprojects { proj ->\n" +
                "        def __safe = (proj.path == ':' ? 'root' : proj.path.substring(1).replace(':', '_'))\n" +
                "        proj.layout.buildDirectory.set(new File(__acBuildRoot, __safe))\n" +
                "    }\n" +
                "}\n"
            )
        }
        logBoth(onLog, "Build outputs redirected to internal storage: ${redirectedModulesRoot.absolutePath}",
            ErrorSeverity.INFO)

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
            append("--stacktrace ")
            // Redirect build outputs + project cache to internal ext4 (chmod works there;
            // external sdcardfs rejects it → "Could not set file mode 770" failures).
            append("--init-script '${buildDirInitScript.absolutePath}' ")
            append("--project-cache-dir '${projectCacheDir.absolutePath}'")
        }

        // AAPT2 wrapper script: only needed when AAPT2 is downloaded (lives in /data/data/)
        // and we're in a SELinux domain that doesn't propagate LD_LIBRARY_PATH to AGP's
        // daemon. With the bundled AAPT2 (targetSdk=35), the binary is in nativeLibraryDir
        // and inherits the parent process's env correctly, so no wrapper is needed.
        if (!bundledAapt2 && aapt2Bin != null) {
            val realBin = File(aapt2Bin.parentFile, "aapt2-real")
            if (!realBin.exists()) {
                aapt2Bin.renameTo(realBin)
            }
            val logFile = File(context.filesDir, "aapt2_wrapper.log")
            aapt2Bin.writeText("""#!/system/bin/sh
echo "AAPT2 wrapper invoked: ${'$'}@" >> '${logFile.absolutePath}'
export LD_LIBRARY_PATH='${aapt2LibDir.absolutePath}:${File(jdkInstaller.jdkDir, "lib").absolutePath}:${File(javaHome, "lib").absolutePath}'
exec '${realBin.absolutePath}' "${'$'}@"
""")
            aapt2Bin.setExecutable(true, false)
            aapt2Bin.setReadable(true, false)
            onLog("AAPT2 wrapper created (legacy path)", ErrorSeverity.INFO)
        }

        // Execute via /system/bin/sh which is on an exec-mounted partition.
        // With targetSdk=28, the app runs in untrusted_app_29 SELinux domain
        // which allows executing binaries from the app's data directory.
        val command = listOf("/system/bin/sh", "-c", shellCmd)

        // Persist the EXACT command + key env BEFORE exec. A JVM-init crash can
        // kill the forked process before any output is captured; writing this
        // first guarantees the diagnostics file always shows what we tried to run.
        val buildOutputFile = File(context.filesDir, "gradle_build_output.txt")
        try {
            buildOutputFile.writeText(buildString {
                appendLine("=== COMMAND (written pre-exec) ===")
                appendLine("/system/bin/sh -c <<")
                appendLine(shellCmd)
                appendLine(">>")
                appendLine("JAVA_HOME=${javaHome.absolutePath}")
                appendLine("java binary=${javaBin.absolutePath} (bundled=$bundledJava)")
                appendLine("LD_LIBRARY_PATH=$ldPath")
                appendLine("BUNDLED_JDK_VERSION=${TermuxJdkInstaller.BUNDLED_JDK_VERSION}")
                appendLine("installed jdk version=${registry.getInstalledVersion("jdk")}")
                appendLine("=== (stdout/stderr appended after exec) ===")
            })
        } catch (_: Exception) { }

        logBoth(onLog, "Running: gradlew assembleDebug --no-daemon", ErrorSeverity.INFO)
        logBoth(onLog, "LD_LIBRARY_PATH: $ldPath", ErrorSeverity.INFO)

        var result = executeGradleBuild(command, projectDir, onLog)

        // ─── Single one-shot repair + retry ──────────────────────────────────
        // At most ONE retry, to avoid loops. The repair performed depends on how
        // the first attempt failed:
        //   (a) JVM-init failure (launcher↔libjvm.so mismatch / corrupt JDK):
        //       wipe jdk17, re-download the PINNED JDK, re-link the launchers,
        //       then retry. This is the self-recovery the user asked for — it
        //       runs inside this coroutine and never aborts the task.
        //   (b) Dependency-cache corruption (e.g. interrupted download after a
        //       pm clear): wipe caches/modules-2 and retry.
        if (result.exitCode != 0 && isJvmInitFailure(result)) {
            logBoth(onLog,
                "JVM failed to initialize — likely a stale/corrupt JDK. Re-downloading the " +
                "pinned OpenJDK ${TermuxJdkInstaller.BUNDLED_JDK_VERSION} and retrying once...",
                ErrorSeverity.WARNING)
            val repaired = repairJdkAndRelink(javaHome, bundledJava, onLog)
            if (repaired) {
                result = executeGradleBuild(command, projectDir, onLog)
            }
        } else if (result.exitCode != 0 && shouldRetryWithCacheReset(result)) {
            // If Gradle failed resolving its own classpath (typically kspClasspath after
            // a wiped cache from pm clear), nuke the modules cache and retry once.
            // The cache sits at gradle_home/caches/modules-2 and can be left in a
            // partially-populated state if a download was interrupted.
            logBoth(onLog,
                "Gradle dependency cache appears corrupted (${detectResolutionFailure(result)}). " +
                "Clearing caches/modules-2 and retrying once...",
                ErrorSeverity.WARNING
            )
            try {
                File(gradleHome, "caches/modules-2").deleteRecursively()
                File(gradleHome, "caches/transforms-4").deleteRecursively()
                File(gradleHome, "caches/jars-9").deleteRecursively()
            } catch (e: Exception) {
                logBoth(onLog, "Cache cleanup failed: ${e.message}", ErrorSeverity.WARNING)
            }
            result = executeGradleBuild(command, projectDir, onLog)
        }

        // Append full build output to the persistent diagnostics file (the
        // command + env were already written pre-exec above).
        try {
            buildOutputFile.appendText(
                "\n=== STDOUT ===\n${result.stdout}\n=== STDERR ===\n${result.stderr}\n=== EXIT: ${result.exitCode} ===\n"
            )
        } catch (_: Exception) { }

        if (result.exitCode != 0) {
            val allOutput = result.stderr + "\n" + result.stdout
            logBoth(onLog,
                "Gradle build failed (exit ${result.exitCode}). Full output in last_compile.log / gradle_build_output.txt",
                ErrorSeverity.ERROR)
            val errors = parseGradleErrors(allOutput)
            return@withContext StepResult.Failure(errors.ifEmpty {
                listOf(CompilationError("Gradle", ErrorSeverity.ERROR,
                    "Build failed (exit ${result.exitCode}).\n${allOutput.takeLast(1500)}"))
            })
        }

        val apk = findProducedApk(projectDir, redirectedModulesRoot)
        if (apk == null) {
            logBoth(onLog, "Build completed but no APK found", ErrorSeverity.ERROR)
            return@withContext StepResult.Failure(listOf(
                CompilationError("Gradle", ErrorSeverity.ERROR,
                    "Build completed but no APK found.\n${result.stdout.takeLast(500)}")
            ))
        }

        val outputApk = File(outputDir, apk.name)
        apk.copyTo(outputApk, overwrite = true)
        logBoth(onLog, "APK: ${outputApk.name} (${outputApk.length() / 1024} KB)", ErrorSeverity.INFO)

        StepResult.Success(listOf(outputApk))
    }

    /**
     * Self-recovery for a JVM-init failure: wipe the downloaded JDK, re-download
     * the PINNED OpenJDK (so the launcher and libjvm.so agree), and re-create the
     * symlinks from `$JAVA_HOME/bin/<tool>` to the APK-bundled launchers (the
     * wipe deleted them). Runs inside the existing compile coroutine and surfaces
     * a single WARNING; it never throws. Returns true if the JDK is usable again.
     *
     * The downloaded JDK always extracts to the same path (filesDir/toolchain/jdk17),
     * so [javaHome] and the LD_LIBRARY_PATH derived from it remain valid after the
     * reinstall — the caller can re-run the identical build command.
     */
    private suspend fun repairJdkAndRelink(
        javaHome: File,
        bundledJava: Boolean,
        onLog: LogCallback
    ): Boolean {
        return try {
            logBoth(onLog, "Self-recovery: wiping ${jdkInstaller.jdkDir.absolutePath}", ErrorSeverity.WARNING)
            jdkInstaller.jdkDir.deleteRecursively()
            // Clear the recorded version so a partial/failed reinstall can't be
            // mistaken for a matched JDK on the next readiness check.
            registry.saveInstalledVersion("jdk", "")

            val installResult = jdkInstaller.install(
                onProgress = { },
                onLog = { msg -> BuildDiagnostics.append(context, "[jdk-repair] $msg") }
            )
            if (installResult.isFailure) {
                logBoth(onLog,
                    "Self-recovery FAILED: could not re-download JDK: ${installResult.exceptionOrNull()?.message}",
                    ErrorSeverity.ERROR)
                return false
            }

            // Re-resolve JAVA_HOME (same path) and re-link the bundled launchers.
            val freshJavaHome = findJavaHome() ?: javaHome
            if (bundledJava) {
                linkJdkToolsToBundled(freshJavaHome, onLog)
            }
            logBoth(onLog,
                "Self-recovery OK: re-installed pinned OpenJDK ${TermuxJdkInstaller.BUNDLED_JDK_VERSION}; retrying build.",
                ErrorSeverity.INFO)
            true
        } catch (e: Exception) {
            logBoth(onLog, "Self-recovery threw: ${e.message}", ErrorSeverity.ERROR)
            false
        }
    }

    /**
     * Locates a usable JAVA_HOME directory.
     *
     * Returns the downloaded-JDK root in filesDir/toolchain/jdk17/lib/jvm/java-17-openjdk/,
     * regardless of whether we'll use the bundled launcher (from APK) or the launcher
     * inside that JDK. The java launcher uses JAVA_HOME to locate modules/, lib/server/libjvm.so,
     * etc. The actual `java` binary we exec is selected by [pickJavaBinary].
     */
    private fun findJavaHome(): File? {
        // Priority 1: Bundled JDK (downloaded from Termux repo by AndroidCompiler)
        val bundledJdk = jdkInstaller.getJavaHome() ?: registry.getJavaHome()
        if (bundledJdk != null && bundledJdk.exists()) return bundledJdk

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

    /**
     * Picks which `java` executable to run. Prefers the APK-bundled launcher because
     * it lives in nativeLibraryDir which is exec-allowed on all targetSdk levels.
     * Falls back to the downloaded JDK's bin/java which only works when the app is
     * in untrusted_app_29 or earlier (targetSdk ≤ 28).
     */
    private fun pickJavaBinary(javaHome: File): File {
        registry.getBundledJavaLauncher()?.let { bundled ->
            if (bundled.exists() && bundled.canExecute()) return bundled
        }
        return File(javaHome, "bin/java")
    }

    /**
     * Replaces each `$JAVA_HOME/bin/<tool>` with a symlink pointing to the
     * matching APK-bundled launcher in nativeLibraryDir. Needed because
     * Gradle's internal JVM probe execs `$javaHome/bin/java` and AGP's
     * JdkImageTransform execs `$javaHome/bin/jlink` directly — on targetSdk=35
     * those paths live in app_data_file which SELinux disallows exec for.
     *
     * Symlink target's SELinux label governs the exec check, so this lets the
     * exec succeed without giving up the JAVA_HOME pointed at the downloaded
     * JDK (which is still needed for JDK modules, libjvm.so, etc).
     *
     * Always regenerates — app reinstalls change the native lib dir (install
     * hash changes) so stale symlinks from a previous install would dangle.
     */
    private fun linkJdkToolsToBundled(javaHome: File, onLog: LogCallback) {
        // (jdk-bin-name → bundled-lib-name). Only tools where we have a
        // corresponding bundled launcher in nativeLibraryDir get linked; the
        // rest stay as their originally-downloaded file (Gradle won't use most
        // of them, but if it does it'll fail loudly and we'll add one more).
        val toolMap = mapOf(
            "java" to "libjava.so",
            "jlink" to "libjlink.so",
            "javac" to "libjavac.so",
            "jar" to "libjar.so",
            "jdeps" to "libjdeps.so",
            "jmod" to "libjmod.so",
            "keytool" to "libkeytool.so"
        )
        val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
        val linked = mutableListOf<String>()
        val osClass = try { Class.forName("android.system.Os") } catch (e: Exception) {
            onLog("Warning: android.system.Os not available: ${e.message}", ErrorSeverity.WARNING)
            return
        }

        for ((tool, libName) in toolMap) {
            val bundled = File(nativeLibDir, libName)
            if (!bundled.exists()) continue
            val jdkBin = File(javaHome, "bin/$tool")
            try {
                jdkBin.parentFile?.mkdirs()
                // Unconditional replace — exists() returns false for dangling
                // symlinks, so also unlink by path if lstat shows an entry.
                if (jdkBin.exists()) jdkBin.delete()
                else {
                    try {
                        osClass.getMethod("lstat", String::class.java)
                            .invoke(null, jdkBin.absolutePath)
                        File(jdkBin.absolutePath).delete()
                    } catch (_: Exception) { }
                }
                osClass.getMethod("symlink", String::class.java, String::class.java)
                    .invoke(null, bundled.absolutePath, jdkBin.absolutePath)
                linked.add(tool)
            } catch (e: Exception) {
                onLog("Warning: could not link $tool: ${e.message}", ErrorSeverity.WARNING)
            }
        }
        if (linked.isNotEmpty()) {
            onLog("Linked JDK tools to bundled launchers: ${linked.joinToString(", ")}", ErrorSeverity.INFO)
        }
    }

    /**
     * Exposes the bundled AAPT2 (lib/<abi>/libaapt2.so in the APK) at a path
     * named `aapt2` via symlink. AGP's android.aapt2FromMavenOverride enforces
     * that the executable is named exactly `aapt2` — the native-lib naming
     * convention is incompatible. Symlink exec resolves through to the target's
     * SELinux label, which is exec-allowed even on targetSdk=35.
     */
    private fun linkAapt2ToBundled(bundled: File, onLog: LogCallback) {
        try {
            val binDir = File(registry.toolchainDir, "aapt2-prefix/bin").apply { mkdirs() }
            val binPath = File(binDir, "aapt2")
            val osClass = Class.forName("android.system.Os")

            if (binPath.exists()) binPath.delete()
            else {
                try {
                    osClass.getMethod("lstat", String::class.java)
                        .invoke(null, binPath.absolutePath)
                    binPath.delete() // dangling symlink cleanup
                } catch (_: Exception) { }
            }

            osClass.getMethod("symlink", String::class.java, String::class.java)
                .invoke(null, bundled.absolutePath, binPath.absolutePath)
            onLog("Linked ${binPath.absolutePath} → ${bundled.absolutePath}", ErrorSeverity.INFO)
        } catch (e: Exception) {
            onLog("Warning: could not link AAPT2 to bundled: ${e.message}", ErrorSeverity.WARNING)
        }
    }

    /**
     * Resolves which `android.jar` file to use for a requested API level.
     * Checks (in order):
     *   1. The version-specific variant at toolchain/android-jar-variants/android-N.jar
     *   2. An installed variant at a lower API level (graceful degradation)
     *   3. An installed variant at a higher API level
     *   4. The legacy single toolchain/android.jar
     */
    private fun resolveAndroidJarForApi(requestedApi: Int): File? {
        registry.androidJarForApi(requestedApi).takeIf { it.exists() && it.length() > 0 }?.let { return it }
        val installed = registry.installedApiLevels()
        installed.filter { it <= requestedApi }.maxOrNull()?.let {
            return registry.androidJarForApi(it)
        }
        installed.minOrNull()?.let { return registry.androidJarForApi(it) }
        return registry.getAndroidJar().takeIf { it.exists() && it.length() > 0 }
    }

    /**
     * Returns the API level number that will actually back the SDK we hand to
     * AGP. Always a number — even when we're falling back to a lower API jar.
     */
    private fun resolveInstalledApiLevelForRequest(requestedApi: Int): Int {
        if (registry.androidJarForApi(requestedApi).let { it.exists() && it.length() > 0 }) {
            return requestedApi
        }
        val installed = registry.installedApiLevels()
        installed.filter { it <= requestedApi }.maxOrNull()?.let { return it }
        installed.minOrNull()?.let { return it }
        return requestedApi // no variants installed — use requested for the stub props
    }

    /**
     * Picks which AAPT2 binary to use. Same logic as [pickJavaBinary] — bundled first.
     */
    private fun pickAapt2Binary(): File? {
        registry.getBundledAapt2()?.let { bundled ->
            if (bundled.exists() && bundled.canExecute()) return bundled
        }
        val downloaded = registry.getAapt2Binary()
        return if (downloaded.exists()) downloaded else null
    }

    private fun findAndroidSdk(requestedApi: Int = registry.defaultApiLevel): File? {
        // Priority 1: Real SDK from environment or Termux
        listOfNotNull(
            System.getenv("ANDROID_HOME"),
            System.getenv("ANDROID_SDK_ROOT"),
            "/data/data/com.termux/files/home/android-sdk"
        ).map { File(it) }.firstOrNull { it.exists() && File(it, "platforms").exists() }
            ?.let { return it }

        // Priority 2: Create minimal SDK from a version-specific android.jar.
        // Prefer the variant for the requested API, then fall back through
        // installed variants, then the legacy single android.jar.
        val androidJar = resolveAndroidJarForApi(requestedApi)
        val resolvedApi = resolveInstalledApiLevelForRequest(requestedApi)
        if (androidJar != null && androidJar.exists()) {
            val sdkRoot = File(context.filesDir, "android-sdk")
            // Remove any stale build-tools stub that would cause AGP to report "corrupted"
            File(sdkRoot, "build-tools").let { if (it.exists()) it.deleteRecursively() }
            // Create platform dirs covering every name AGP might look for at
            // the requested API level, plus a couple of adjacent extension-level
            // variants as a safety net. We key the contents off [resolvedApi].
            val cfsmForResolved = registry.coreForSystemModulesForApi(resolvedApi)
                .takeIf { it.exists() }
                ?: File(registry.toolchainDir, "core-for-system-modules.jar").takeIf { it.exists() }
            val platformSuffixes = buildSet {
                add("android-$resolvedApi")
                add("android-$resolvedApi-2")
                add("android-$resolvedApi-ext7")
                add("android-$resolvedApi-ext14")
                // Also cover the originally requested API if different, so AGP
                // finds the dir it looks for even when our fallback used a lower API.
                add("android-$requestedApi")
                add("android-$requestedApi-2")
            }
            for (suffix in platformSuffixes) {
                val platformDir = File(sdkRoot, "platforms/$suffix")
                platformDir.mkdirs()
                val targetJar = File(platformDir, "android.jar")
                if (!targetJar.exists() || targetJar.length() != androidJar.length()) {
                    androidJar.copyTo(targetJar, overwrite = true)
                }
                targetJar.setReadable(true, false)
                targetJar.setWritable(false, false)

                val cfsm = File(platformDir, "core-for-system-modules.jar")
                if (cfsmForResolved != null &&
                    (!cfsm.exists() || cfsm.length() != cfsmForResolved.length())) {
                    cfsmForResolved.copyTo(cfsm, overwrite = true)
                } else if (!cfsm.exists()) {
                    cfsm.writeBytes(createEmptyJar())
                }

                val fwAidl = File(platformDir, "framework.aidl")
                if (!fwAidl.exists()) fwAidl.writeText("// stub\n")
                File(platformDir, "build.prop").apply {
                    if (!exists()) writeText(
                        "ro.build.version.sdk=$resolvedApi\n" +
                        "ro.build.version.codename=REL\n"
                    )
                }
                File(platformDir, "source.properties").apply {
                    if (!exists()) writeText(
                        "Pkg.Desc=Android SDK Platform $resolvedApi\n" +
                        "Pkg.Revision=1\n" +
                        "AndroidVersion.ApiLevel=$resolvedApi\n" +
                        "Layoutlib.Api=15\n" +
                        "Layoutlib.Revision=1\n"
                    )
                }
            }
            // Create build-tools with our ARM64 AAPT2.
            // AGP validates build-tools by checking for aapt, aapt2, dx, and other tools.
            // We provide our binary for aapt2 and stub scripts for tools AGP checks but
            // doesn't actually invoke during modern builds.
            val aapt2Src = pickAapt2Binary()
            if (aapt2Src != null && aapt2Src.exists()) {
                val btDir = File(sdkRoot, "build-tools/35.0.0")
                btDir.mkdirs()
                File(btDir, "source.properties").writeText(
                    "Pkg.Revision=35.0.0\nPkg.Desc=Android SDK Build-Tools 35\n"
                )
                // AAPT2 — when the source is the bundled binary (in
                // nativeLibraryDir), use a symlink so SELinux's exec check
                // follows through to the exec-allowed label. Otherwise copy
                // (legacy path — downloaded aapt2 in app_data_file).
                val btAapt2 = File(btDir, "aapt2")
                if (btAapt2.exists()) btAapt2.delete()
                val isBundledSrc = aapt2Src == registry.getBundledAapt2()
                if (isBundledSrc) {
                    try {
                        Class.forName("android.system.Os")
                            .getMethod("symlink", String::class.java, String::class.java)
                            .invoke(null, aapt2Src.absolutePath, btAapt2.absolutePath)
                    } catch (_: Exception) {
                        aapt2Src.copyTo(btAapt2, overwrite = true)
                        btAapt2.setExecutable(true, false)
                    }
                } else {
                    aapt2Src.copyTo(btAapt2, overwrite = true)
                    btAapt2.setExecutable(true, false)
                    // Legacy wrapper needs aapt2-real next to it
                    val realBin = File(aapt2Src.parentFile, "aapt2-real")
                    if (realBin.exists()) {
                        val btReal = File(btDir, "aapt2-real")
                        if (!btReal.exists()) realBin.copyTo(btReal)
                        btReal.setExecutable(true, false)
                    }
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
            // AGP's android.aapt2FromMavenOverride enforces that the pointed-to
            // file is named exactly `aapt2` (not `libaapt2.so`). Always use
            // `toolchain/aapt2-prefix/bin/aapt2` — that's either:
            //  - a symlink pointing at the bundled lib<abi>/libaapt2.so
            //    (created by linkAapt2ToBundled during this compile), OR
            //  - the downloaded Termux binary (legacy pre-bundled code path).
            val aapt2 = registry.getAapt2Binary()
            if (aapt2.exists()) {
                appendLine("android.aapt2FromMavenOverride=${aapt2.absolutePath}")
            }
        }
        propsFile.writeText(injected)
        onLog("Gradle properties configured", ErrorSeverity.INFO)
    }

    private fun ensureGradleWrapperJar(
        projectDir: File,
        targetGradleVersion: String,
        onLog: LogCallback
    ): File? {
        val wrapperDir = File(projectDir, "gradle/wrapper").apply { mkdirs() }
        val wrapperJar = File(wrapperDir, "gradle-wrapper.jar")
        val wrapperProps = File(wrapperDir, "gradle-wrapper.properties")

        // Always rewrite gradle-wrapper.properties so the target version matches
        // (the project may ship one pointing at a version we don't have locally).
        rewriteWrapperProperties(wrapperProps, targetGradleVersion)

        // Existing wrapper jar in the project takes priority — it's already
        // matched to some Gradle version that works.
        if (wrapperJar.exists() && wrapperJar.length() > 1000) {
            return wrapperJar
        }

        // 1. Version-specific downloaded wrapper
        val versionedJar = registry.gradleWrapperJarFor(targetGradleVersion)
        if (versionedJar.exists() && versionedJar.length() > 1000) {
            versionedJar.copyTo(wrapperJar, overwrite = true)
            onLog("Injected gradle-wrapper.jar for Gradle $targetGradleVersion", ErrorSeverity.INFO)
            return wrapperJar
        }

        // 2. Generic toolchain-level wrapper (the default bundled one)
        val toolchainJar = File(registry.toolchainDir, "gradle-wrapper.jar")
        if (toolchainJar.exists() && toolchainJar.length() > 1000) {
            toolchainJar.copyTo(wrapperJar, overwrite = true)
            onLog("Injected gradle-wrapper.jar from toolchain (default)", ErrorSeverity.INFO)
            return wrapperJar
        }

        // 3. Walk any existing Gradle caches for a working wrapper jar
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
                        return wrapperJar
                    }
            }
        }
        return null
    }

    private fun rewriteWrapperProperties(propsFile: File, version: String) {
        propsFile.parentFile?.mkdirs()
        propsFile.writeText("""
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-$version-bin.zip
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

    private fun findProducedApk(projectDir: File, redirectedBuildRoot: File? = null): File? {
        // With build dirs redirected to internal storage, the APK lands under
        // <redirectedBuildRoot>/<module>/outputs/apk/... (note: NO "build/" segment,
        // since buildDirectory itself points at <redirectedBuildRoot>/<module>).
        // Search the internal root first, then fall back to the legacy in-tree paths.
        val searchRoots = listOfNotNull(redirectedBuildRoot, projectDir)
        searchRoots.forEach { root ->
            listOf(
                "app/outputs/apk/debug", "app/outputs/apk/release",   // redirected layout
                "root/outputs/apk/debug", "root/outputs/apk/release",
                "app/build/outputs/apk/debug", "app/build/outputs/apk/release", // legacy in-tree
                "build/outputs/apk/debug", "build/outputs/apk/release"
            ).forEach { path ->
                val dir = File(root, path)
                if (dir.exists()) {
                    dir.listFiles()
                        ?.filter { it.extension == "apk" && !it.name.contains("unsigned") }
                        ?.maxByOrNull { it.lastModified() }
                        ?.let { return it }
                }
            }
        }
        return searchRoots.asSequence()
            .flatMap { it.walkTopDown() }
            .filter { it.extension == "apk" && it.path.contains("outputs") && !it.name.contains("unsigned") }
            .maxByOrNull { it.lastModified() }
    }

    /**
     * Detects the "Could not resolve all files for configuration" family of errors
     * that indicate a corrupted or incomplete Gradle modules cache. Typically
     * triggered by a wiped caches/modules-2 after `pm clear` where the subsequent
     * dependency download was interrupted.
     */
    private fun shouldRetryWithCacheReset(result: ProcessResult): Boolean {
        if (result.exitCode == 0) return false
        val output = result.stdout + result.stderr
        return output.contains("Could not resolve all files for configuration") ||
                output.contains("Could not resolve all artifacts for configuration") ||
                output.contains("Could not resolve all dependencies for configuration") ||
                output.contains("kspClasspath") ||
                output.contains("Could not find org.jetbrains.kotlin") ||
                output.contains("Could not find com.google.devtools.ksp") ||
                output.contains("Could not GET ") ||
                output.contains("PKIX path validation failed")
    }

    /** Produces a short human-readable tag for why we're retrying. */
    private fun detectResolutionFailure(result: ProcessResult): String {
        val output = result.stdout + result.stderr
        return when {
            output.contains("kspClasspath") -> "kspClasspath resolution failed"
            output.contains("Could not resolve all files") -> "could not resolve all files"
            output.contains("Could not resolve all artifacts") -> "could not resolve all artifacts"
            output.contains("PKIX path validation failed") -> "TLS validation failed mid-download"
            output.contains("Could not GET ") -> "HTTP GET failed mid-resolution"
            else -> "dependency resolution failed"
        }
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
