package com.androidcompiler.toolchain.compute

import android.os.Build
import android.os.PerformanceHintManager
import android.os.Process
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps Android's PerformanceHintManager (API 31+) to hint the kernel
 * scheduler about compilation thread requirements.
 *
 * On big.LITTLE architectures (like the S26 Ultra's Snapdragon 8 Elite Gen 5),
 * this encourages the scheduler to place compilation threads on performance
 * cores rather than efficiency cores, improving compilation speed.
 */
@Singleton
class PerformanceHintHelper @Inject constructor() {

    private var session: Any? = null // PerformanceHintManager.Session
    private var hintManager: PerformanceHintManager? = null

    /**
     * Create a performance hint session for the current thread.
     * Call this before starting heavy compilation work.
     *
     * @param targetDurationNanos Expected duration of one compilation unit in nanoseconds.
     *        The system uses this to optimize scheduling.
     */
    fun beginCompilationSession(targetDurationNanos: Long = 100_000_000L) { // 100ms default
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return

        try {
            if (hintManager == null) return // Need context to get system service

            val tids = intArrayOf(Process.myTid())
            session = hintManager!!.createHintSession(tids, targetDurationNanos)
        } catch (_: Exception) {
            // PerformanceHintManager may not be supported on all devices
        }
    }

    /**
     * Initialize with system service. Call from Application or Activity context.
     */
    fun init(performanceHintManager: PerformanceHintManager?) {
        this.hintManager = performanceHintManager
    }

    /**
     * Report the actual duration of a compilation unit.
     * This helps the scheduler learn and optimize thread placement.
     */
    fun reportActualDuration(actualDurationNanos: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return

        try {
            val s = session ?: return
            val method = s.javaClass.getMethod("reportActualWorkDuration", Long::class.java)
            method.invoke(s, actualDurationNanos)
        } catch (_: Exception) { }
    }

    /**
     * Update the target duration if compilation characteristics change.
     */
    fun updateTargetDuration(targetDurationNanos: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.T) return

        try {
            val s = session ?: return
            val method = s.javaClass.getMethod("updateTargetWorkDuration", Long::class.java)
            method.invoke(s, targetDurationNanos)
        } catch (_: Exception) { }
    }

    /**
     * Close the hint session when compilation is complete.
     */
    fun endSession() {
        try {
            val s = session ?: return
            val method = s.javaClass.getMethod("close")
            method.invoke(s)
        } catch (_: Exception) { }
        session = null
    }
}
