package com.androidcompiler.feature.compiler.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.androidcompiler.core.common.model.CompilationError
import com.androidcompiler.core.common.model.CompilationState
import com.androidcompiler.core.common.model.ErrorSeverity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LogEntry(
    val message: String,
    val severity: ErrorSeverity,
    val timestamp: Long = System.currentTimeMillis()
)

@HiltViewModel
class CompilerViewModel @Inject constructor() : ViewModel() {

    private val _compilationState = MutableStateFlow<CompilationState>(CompilationState.Idle)
    val compilationState: StateFlow<CompilationState> = _compilationState.asStateFlow()

    private val _logEntries = MutableStateFlow<List<LogEntry>>(emptyList())
    val logEntries: StateFlow<List<LogEntry>> = _logEntries.asStateFlow()

    private var currentProjectUri: Uri? = null
    private var currentErrors: List<CompilationError> = emptyList()

    fun loadProject(uri: Uri) {
        currentProjectUri = uri
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "Unknown Project"
        _compilationState.value = CompilationState.ProjectLoaded(
            projectName = name,
            projectPath = uri.toString()
        )
        addLog("Project loaded: $name", ErrorSeverity.INFO)
    }

    fun compile() {
        val uri = currentProjectUri ?: return
        viewModelScope.launch {
            addLog("Starting compilation...", ErrorSeverity.INFO)
            // TODO: Wire up to CompilationPipeline
            addLog("Compilation pipeline not yet implemented", ErrorSeverity.WARNING)
        }
    }

    fun clearLog() {
        _logEntries.value = emptyList()
    }

    fun generateClaudePrompt(): String {
        if (currentErrors.isEmpty()) return "No errors to report."

        return buildString {
            appendLine("Fix the following Android compilation errors in my project:")
            appendLine()
            currentErrors.groupBy { it.filePath }.forEach { (file, errors) ->
                appendLine("## File: ${file ?: "Unknown"}")
                errors.forEach { err ->
                    val location = if (err.line != null) "Line ${err.line}" else ""
                    appendLine("- $location: ${err.message}")
                }
                appendLine()
            }
            appendLine("Please provide the corrected files.")
        }
    }

    private fun addLog(message: String, severity: ErrorSeverity) {
        _logEntries.value = _logEntries.value + LogEntry(message, severity)
    }
}
