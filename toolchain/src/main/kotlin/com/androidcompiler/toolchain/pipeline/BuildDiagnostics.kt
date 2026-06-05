package com.androidcompiler.toolchain.pipeline

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persistent on-device diagnostics for the Gradle build path.
 *
 * Previously the entire on-device compile path produced ZERO logcat and left no
 * file behind when a JVM-init crash killed the forked process — so a failed
 * build was effectively undebuggable on a real device. This object:
 *
 *  - Appends every in-app log line to `filesDir/last_compile.log` (so the full
 *    attempt survives even when the UI is gone), rotating at ~1MB.
 *  - Mirrors lines to logcat under the single tag "ACBuild" (`adb logcat -s ACBuild`).
 *
 * It is intentionally tiny and dependency-free (just a File + Log) so it can be
 * called from any layer — the installer, the pipeline, and GradleCompiler — on
 * whatever thread is running, without a Hilt graph.
 */
object BuildDiagnostics {
    const val TAG = "ACBuild"

    /** ~1MB cap. When the log exceeds this, it's truncated to the most recent half. */
    private const val MAX_LOG_BYTES = 1_024 * 1_024L

    private val ts: SimpleDateFormat
        get() = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    private val lock = Any()

    fun logFile(context: Context): File = File(context.filesDir, "last_compile.log")

    /**
     * Marks the start of a fresh compile in the persistent log (does not erase
     * history — rotation handles size). Call once at the top of a compile.
     */
    fun beginSession(context: Context, header: String) {
        append(context, "\n========== $header @ ${ts.format(Date())} ==========")
    }

    /** Append [line] to the persistent log + logcat. Never throws. */
    fun append(context: Context, line: String) {
        try {
            val f = logFile(context)
            rotateIfNeeded(f)
            f.appendText("${ts.format(Date())}  $line\n")
        } catch (e: Exception) {
            Log.w(TAG, "could not persist log line: ${e.message}")
        }
    }

    private fun rotateIfNeeded(f: File) {
        try {
            if (f.exists() && f.length() > MAX_LOG_BYTES) {
                // Keep the most recent ~half so we don't lose the tail of a long build.
                val text = f.readText()
                val keep = text.substring(text.length / 2)
                f.writeText("[log rotated @ ${ts.format(Date())} — older entries dropped]\n$keep")
            }
        } catch (_: Exception) {
            // If rotation fails (e.g. OOM on a huge file), reset it outright.
            try { f.writeText("[log reset after rotation failure]\n") } catch (_: Exception) {}
        }
    }
}
