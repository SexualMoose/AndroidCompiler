package com.androidcompiler.toolchain.compute

import android.content.Context
import com.androidcompiler.toolchain.registry.ToolchainRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Validates that the APK-bundled native binaries (libaapt2.so and libjava.so in
 * nativeLibraryDir) actually execute. Intended to run once on first launch and
 * whenever the app is updated, so the Components screen can surface a clear
 * error if this device's SELinux policy or linker is unusually restrictive.
 *
 * The Termux-built binaries depend on Android's bionic libc and libc++, which
 * are present on every Android ≥ 11. In practice this validator should always
 * succeed on supported devices (Android 15, ARM64).
 */
@Singleton
class BundledBinariesValidator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val registry: ToolchainRegistry
) {
    data class Report(
        val aapt2Works: Boolean,
        val aapt2Version: String?,
        val aapt2Error: String?,
        val javaWorks: Boolean,
        val javaVersion: String?,
        val javaError: String?
    ) {
        val allPassed: Boolean get() = aapt2Works && javaWorks
    }

    /**
     * Run both smoke tests. Takes a few hundred milliseconds total.
     */
    suspend fun validate(): Report = withContext(Dispatchers.IO) {
        val aapt2Result = runAapt2Version()
        val javaResult = runJavaVersion()
        Report(
            aapt2Works = aapt2Result.success,
            aapt2Version = aapt2Result.version,
            aapt2Error = aapt2Result.error,
            javaWorks = javaResult.success,
            javaVersion = javaResult.version,
            javaError = javaResult.error
        )
    }

    private fun runAapt2Version(): ExecResult {
        val bin = registry.getBundledAapt2()
            ?: return ExecResult(false, null, "libaapt2.so not found in nativeLibraryDir")
        // AAPT2 needs libc++_shared, libz.so.1, libprotobuf, etc. on LD_LIBRARY_PATH.
        // Those are downloaded to the aapt2-prefix/lib/ dir; validation only works
        // after the toolchain is fully installed. We still surface a clear error
        // in the "not yet installed" case rather than silently failing.
        val ldLibs = buildString {
            append(registry.getAapt2LibDir().absolutePath)
            val jdkLib = registry.getJavaHome()?.let { "${it.absolutePath}/lib" }
            if (jdkLib != null) append(":$jdkLib")
        }
        return runCommand(
            listOf(bin.absolutePath, "version"),
            env = mapOf("LD_LIBRARY_PATH" to ldLibs)
        )
    }

    private fun runJavaVersion(): ExecResult {
        val launcher = registry.getBundledJavaLauncher()
            ?: return ExecResult(false, null, "libjava.so not found in nativeLibraryDir")
        val javaHome = registry.getJavaHome()
            ?: return ExecResult(false, null, "JDK not installed (filesDir/toolchain/jdk17)")
        // The java launcher needs JAVA_HOME to find its module image and libjvm.so.
        val libjvmDirs = listOf(
            "${javaHome.absolutePath}/lib",
            "${javaHome.absolutePath}/lib/server",
            // Include the nativeLibraryDir so libjli.so is found
            context.applicationInfo.nativeLibraryDir
        ).joinToString(":")
        return runCommand(
            listOf(launcher.absolutePath, "-version"),
            env = mapOf(
                "JAVA_HOME" to javaHome.absolutePath,
                "LD_LIBRARY_PATH" to libjvmDirs
            )
        )
    }

    private fun runCommand(command: List<String>, env: Map<String, String>): ExecResult {
        return try {
            val pb = ProcessBuilder(command).redirectErrorStream(true)
            pb.environment().putAll(env)
            val process = pb.start()
            val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            val finished = process.waitFor(5, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return ExecResult(false, null, "timeout after 5s: ${command.first()}")
            }
            val exit = process.exitValue()
            val version = output.lines().firstOrNull { it.isNotBlank() }?.trim()
            // `java -version` writes to stderr and exits 0; `aapt2 version` writes to stdout
            if (exit == 0 && version != null && version.isNotBlank()) {
                ExecResult(true, version, null)
            } else {
                ExecResult(false, version, "exit=$exit: ${output.take(500)}")
            }
        } catch (e: Exception) {
            ExecResult(false, null, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private data class ExecResult(val success: Boolean, val version: String?, val error: String?)
}
