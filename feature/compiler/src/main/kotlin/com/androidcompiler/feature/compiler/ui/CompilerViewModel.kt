package com.androidcompiler.feature.compiler.ui

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.androidcompiler.core.common.model.CompilationError
import com.androidcompiler.core.common.model.CompilationState
import com.androidcompiler.core.common.model.CompilationStep
import com.androidcompiler.core.common.model.ErrorSeverity
import com.androidcompiler.core.data.repository.SettingsRepository
import com.androidcompiler.toolchain.pipeline.CompilationPipeline
import com.androidcompiler.toolchain.pipeline.CompilationResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class LogEntry(
    val message: String,
    val severity: ErrorSeverity,
    val timestamp: Long = System.currentTimeMillis()
)

@HiltViewModel
class CompilerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val compilationPipeline: CompilationPipeline,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _compilationState = MutableStateFlow<CompilationState>(CompilationState.Idle)
    val compilationState: StateFlow<CompilationState> = _compilationState.asStateFlow()

    private val _logEntries = MutableStateFlow<List<LogEntry>>(emptyList())
    val logEntries: StateFlow<List<LogEntry>> = _logEntries.asStateFlow()

    private var currentProjectUri: Uri? = null
    private var currentProjectName: String = ""
    private var currentErrors: List<CompilationError> = emptyList()
    private var lastApkPath: String? = null

    fun loadProject(uri: Uri) {
        currentProjectUri = uri
        currentProjectName = DocumentFile.fromSingleUri(context, uri)?.name
            ?.removeSuffix(".zip") ?: "Unknown Project"
        _compilationState.value = CompilationState.ProjectLoaded(
            projectName = currentProjectName,
            projectPath = uri.toString()
        )
        currentErrors = emptyList()
        addLog("Project loaded: $currentProjectName", ErrorSeverity.INFO)
    }

    fun compile() {
        val uri = currentProjectUri ?: return

        if (!compilationPipeline.isToolchainReady()) {
            addLog("Toolchain not ready. Go to Components tab to download required tools.", ErrorSeverity.ERROR)
            return
        }

        viewModelScope.launch {
            currentErrors = emptyList()
            addLog("Starting compilation of $currentProjectName...", ErrorSeverity.INFO)

            // Copy ZIP from content URI to a local temp file
            val tempZip = withContext(Dispatchers.IO) {
                val file = File(context.cacheDir, "input_project.zip")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                file
            }

            if (!tempZip.exists() || tempZip.length() == 0L) {
                addLog("Failed to read project ZIP file", ErrorSeverity.ERROR)
                _compilationState.value = CompilationState.Failed(
                    listOf(CompilationError("IO", ErrorSeverity.ERROR, "Failed to read ZIP"))
                )
                return@launch
            }

            addLog("ZIP file: ${tempZip.length() / 1024} KB", ErrorSeverity.INFO)

            // Determine output directory
            val settings = settingsRepository.settings.first()
            val outputDir = if (settings.defaultOutputFolder != null) {
                File(settings.defaultOutputFolder!!).apply { mkdirs() }
            } else {
                File(context.getExternalFilesDir(null), "output").apply { mkdirs() }
            }

            val result = compilationPipeline.compile(
                zipPath = tempZip.absolutePath,
                outputDir = outputDir,
                onProgress = { step, progress ->
                    _compilationState.value = CompilationState.Compiling(step, progress)
                },
                onLog = { message, severity ->
                    addLog(message, severity)
                }
            )

            // Clean up temp file
            tempZip.delete()

            when (result) {
                is CompilationResult.Success -> {
                    lastApkPath = result.apkFile.absolutePath
                    _compilationState.value = CompilationState.Complete(result.apkFile.absolutePath)
                    addLog("BUILD SUCCESSFUL: ${result.apkFile.absolutePath}", ErrorSeverity.INFO)
                }
                is CompilationResult.Failure -> {
                    currentErrors = result.errors
                    _compilationState.value = CompilationState.Failed(result.errors)
                    result.errors.forEach { error ->
                        addLog("[${error.step}] ${error.message}", error.severity)
                    }
                }
            }
        }
    }

    fun clearLog() {
        _logEntries.value = emptyList()
    }

    fun getApkPath(): String? = lastApkPath

    fun generateClaudePrompt(): String {
        if (currentErrors.isEmpty()) return "No errors to report."

        return buildString {
            appendLine("Fix the following Android compilation errors in my project:")
            appendLine()

            currentErrors.groupBy { it.filePath }.forEach { (file, errors) ->
                appendLine("## File: ${file ?: "Unknown"}")

                // Include source context if we can read the file
                file?.let { filePath ->
                    try {
                        val sourceFile = File(filePath)
                        if (sourceFile.exists()) {
                            val lines = sourceFile.readLines()
                            val errorLines = errors.mapNotNull { it.line }.distinct().sorted()
                            appendLine("```kotlin")
                            errorLines.forEach { lineNum ->
                                val start = maxOf(0, lineNum - 4)
                                val end = minOf(lines.size, lineNum + 3)
                                for (i in start until end) {
                                    val marker = if (i + 1 == lineNum) " <<< ERROR" else ""
                                    appendLine("${i + 1}: ${lines[i]}$marker")
                                }
                                appendLine("...")
                            }
                            appendLine("```")
                        }
                    } catch (_: Exception) { }
                }

                errors.forEach { err ->
                    val location = buildString {
                        if (err.line != null) append("Line ${err.line}")
                        if (err.column != null) append(":${err.column}")
                    }
                    appendLine("- $location: ${err.message}")
                }
                appendLine()
            }
            appendLine("Please provide the corrected files with the full file content.")
        }
    }

    private fun addLog(message: String, severity: ErrorSeverity) {
        _logEntries.value = _logEntries.value + LogEntry(message, severity)
    }
}
