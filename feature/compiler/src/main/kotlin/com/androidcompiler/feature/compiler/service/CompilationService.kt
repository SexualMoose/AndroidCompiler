package com.androidcompiler.feature.compiler.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.androidcompiler.core.common.model.CompilationStep
import com.androidcompiler.core.common.model.ErrorSeverity
import com.androidcompiler.toolchain.pipeline.CompilationPipeline
import com.androidcompiler.toolchain.pipeline.CompilationResult
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class CompilationService : Service() {

    @Inject lateinit var compilationPipeline: CompilationPipeline

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    companion object {
        const val CHANNEL_ID = "compilation_channel"
        const val NOTIFICATION_ID = 1
        const val EXTRA_ZIP_PATH = "zip_path"
        const val EXTRA_OUTPUT_DIR = "output_dir"

        private val _compilationEvents = MutableSharedFlow<CompilationEvent>(extraBufferCapacity = 64)
        val compilationEvents: SharedFlow<CompilationEvent> = _compilationEvents.asSharedFlow()
    }

    sealed interface CompilationEvent {
        data class Progress(val step: CompilationStep, val progress: Float) : CompilationEvent
        data class Log(val message: String, val severity: ErrorSeverity) : CompilationEvent
        data class Complete(val result: CompilationResult) : CompilationEvent
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val zipPath = intent?.getStringExtra(EXTRA_ZIP_PATH) ?: return START_NOT_STICKY
        val outputDir = intent.getStringExtra(EXTRA_OUTPUT_DIR) ?: return START_NOT_STICKY

        val notification = createNotification("Preparing compilation...")
        startForeground(NOTIFICATION_ID, notification)

        scope.launch {
            val result = compilationPipeline.compile(
                zipPath = zipPath,
                outputDir = File(outputDir),
                onProgress = { step, progress ->
                    updateNotification("${step.displayName}... ${(progress * 100).toInt()}%")
                    _compilationEvents.tryEmit(CompilationEvent.Progress(step, progress))
                },
                onLog = { message, severity ->
                    _compilationEvents.tryEmit(CompilationEvent.Log(message, severity))
                }
            )
            _compilationEvents.tryEmit(CompilationEvent.Complete(result))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Compilation",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows compilation progress"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AndroidCompiler")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification(text))
    }
}
