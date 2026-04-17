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
import com.androidcompiler.core.common.util.UriPathResolver
import com.androidcompiler.core.data.repository.SettingsRepository
import com.androidcompiler.toolchain.download.ComponentDownloadManager
import com.androidcompiler.toolchain.pipeline.CompilationPipeline
import com.androidcompiler.toolchain.pipeline.CompilationResult
import com.androidcompiler.toolchain.pipeline.ProjectAnalyzer
import com.androidcompiler.toolchain.registry.ToolchainRegistry
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
import java.util.zip.ZipInputStream
import javax.inject.Inject

data class LogEntry(
    val message: String,
    val severity: ErrorSeverity,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Version info detected from the loaded project + any user-selected overrides.
 * When both fields on a row are non-null, effective = override (yellow chip in UI).
 */
data class DetectedVersions(
    val detectedGradleVersion: String? = null,
    val detectedCompileSdk: Int? = null,
    val detectedAgpVersion: String? = null,
    val gradleVersionOverride: String? = null,
    val compileSdkOverride: Int? = null
) {
    val effectiveGradleVersion: String? get() = gradleVersionOverride ?: detectedGradleVersion
    val effectiveCompileSdk: Int? get() = compileSdkOverride ?: detectedCompileSdk
}

@HiltViewModel
class CompilerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val compilationPipeline: CompilationPipeline,
    private val settingsRepository: SettingsRepository,
    private val registry: ToolchainRegistry,
    private val downloadManager: ComponentDownloadManager,
    private val projectAnalyzer: ProjectAnalyzer
) : ViewModel() {

    private val _compilationState = MutableStateFlow<CompilationState>(CompilationState.Idle)
    val compilationState: StateFlow<CompilationState> = _compilationState.asStateFlow()

    private val _logEntries = MutableStateFlow<List<LogEntry>>(emptyList())
    val logEntries: StateFlow<List<LogEntry>> = _logEntries.asStateFlow()

    private val _detectedVersions = MutableStateFlow(DetectedVersions())
    val detectedVersions: StateFlow<DetectedVersions> = _detectedVersions.asStateFlow()

    /** All Gradle versions the app can use (subset that's installed + all supported). */
    val supportedGradleVersions: List<String> get() = registry.supportedGradleVersions
    /** All API levels the app can use. */
    val supportedApiLevels: List<Int> get() = registry.supportedApiLevels

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

        // Peek inside the ZIP to detect required versions without a full extract.
        // Same regex parsers as ProjectAnalyzer but on streamed ZIP entries.
        viewModelScope.launch {
            detectVersionsFromZip(uri)
        }
    }

    private suspend fun detectVersionsFromZip(uri: Uri) = withContext(Dispatchers.IO) {
        var gradleVersion: String? = null
        var compileSdk: Int? = null
        var agpVersion: String? = null

        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input.buffered()).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val name = entry.name.lowercase()
                        val isWrapperProps = name.endsWith("gradle-wrapper.properties")
                        val isBuildFile = name.endsWith("build.gradle.kts") ||
                                name.endsWith("build.gradle") ||
                                name.endsWith("settings.gradle.kts") ||
                                name.endsWith("settings.gradle")
                        if ((isWrapperProps || isBuildFile) && !entry.isDirectory) {
                            val content = zis.readBytes().decodeToString()
                            if (isWrapperProps && gradleVersion == null) {
                                gradleVersion = Regex("""gradle-(\d+\.\d+(?:\.\d+)?)-(bin|all)\.zip""")
                                    .find(content)?.groupValues?.get(1)
                            }
                            if (isBuildFile) {
                                if (compileSdk == null) {
                                    compileSdk = Regex("""\bcompileSdk(?:Version)?\s*[=\s]\s*(\d+)""")
                                        .find(content)?.groupValues?.get(1)?.toIntOrNull()
                                }
                                if (agpVersion == null) {
                                    agpVersion = Regex("""com\.android\.tools\.build:gradle:(\d+\.\d+\.\d+)""")
                                        .find(content)?.groupValues?.get(1)
                                        ?: Regex("""id\s*\(?\s*["']com\.android\.(?:application|library)["']\s*\)?\s*(?:version\s*\(?\s*)?["'](\d+\.\d+\.\d+)""")
                                            .find(content)?.groupValues?.get(1)
                                }
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
        } catch (e: Exception) {
            addLog("Version detection failed: ${e.message}", ErrorSeverity.WARNING)
        }

        _detectedVersions.value = _detectedVersions.value.copy(
            detectedGradleVersion = gradleVersion,
            detectedCompileSdk = compileSdk,
            detectedAgpVersion = agpVersion,
            // Clear any stale overrides when loading a new project
            gradleVersionOverride = null,
            compileSdkOverride = null
        )

        val g = gradleVersion ?: "default"
        val s = compileSdk?.toString() ?: "default"
        addLog("Detected: Gradle $g, compileSdk $s" + if (agpVersion != null) ", AGP $agpVersion" else "",
            ErrorSeverity.INFO)
    }

    fun setGradleVersionOverride(version: String?) {
        _detectedVersions.value = _detectedVersions.value.copy(gradleVersionOverride = version)
    }

    fun setCompileSdkOverride(apiLevel: Int?) {
        _detectedVersions.value = _detectedVersions.value.copy(compileSdkOverride = apiLevel)
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

            // Determine output directory (resolve SAF content URIs to real paths)
            val settings = settingsRepository.settings.first()
            val outputDir = UriPathResolver.resolveOutputDir(context, settings.defaultOutputFolder)
            addLog("Output directory: ${outputDir.absolutePath}", ErrorSeverity.INFO)

            // Build the version overrides from user selections (null fields fall
            // through to what ProjectAnalyzer detected, then to registry defaults).
            val overrides = com.androidcompiler.toolchain.pipeline.GradleCompiler.VersionOverrides(
                gradleVersion = _detectedVersions.value.gradleVersionOverride,
                compileSdk = _detectedVersions.value.compileSdkOverride
            )

            val result = compilationPipeline.compile(
                zipPath = tempZip.absolutePath,
                outputDir = outputDir,
                incrementalFileNames = settings.incrementalFileNames,
                overrides = overrides,
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
