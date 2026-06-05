package com.androidcompiler.toolchain.pipeline

import android.content.Context
import android.util.Log
import com.androidcompiler.core.common.model.CompilationError
import com.androidcompiler.core.common.model.CompilationStep
import com.androidcompiler.core.common.model.ErrorSeverity
import com.androidcompiler.toolchain.compute.PerformanceHintHelper
import com.androidcompiler.toolchain.registry.ToolchainRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class CompilationContext(
    val projectDir: File,
    val outputDir: File,
    val buildDir: File,
    val toolchainDir: File,
    val androidJarPath: String,
    val incrementalFileNames: Boolean = true
)

sealed interface CompilationResult {
    data class Success(val apkFile: File) : CompilationResult
    data class Failure(val errors: List<CompilationError>, val step: CompilationStep) : CompilationResult
}

typealias ProgressCallback = (step: CompilationStep, progress: Float) -> Unit
typealias LogCallback = (message: String, severity: ErrorSeverity) -> Unit

@Singleton
class CompilationPipeline @Inject constructor(
    @ApplicationContext private val context: Context,
    private val projectExtractor: ProjectExtractor,
    private val projectAnalyzer: ProjectAnalyzer,
    private val gradleCompiler: GradleCompiler,
    private val resourceCompiler: ResourceCompiler,
    private val sourceCompiler: SourceCompiler,
    private val dexCompiler: DexCompiler,
    private val apkPackager: ApkPackager,
    private val apkAligner: ApkAligner,
    private val apkSigner: ApkSigner,
    private val registry: ToolchainRegistry,
    private val performanceHintHelper: PerformanceHintHelper
) {
    fun isToolchainReady(): Boolean = registry.isAllInstalled()

    suspend fun compile(
        zipPath: String,
        outputDir: File,
        incrementalFileNames: Boolean = true,
        overrides: GradleCompiler.VersionOverrides = GradleCompiler.VersionOverrides(),
        onProgress: ProgressCallback,
        onLog: LogCallback
    ): CompilationResult = withContext(Dispatchers.Default) {
        // Tee every pipeline log line to logcat (tag ACBuild) + the persistent
        // last_compile.log, then forward to the in-app UI callback. This is the
        // single place the whole compile is captured for on-device debugging.
        BuildDiagnostics.beginSession(context, "Compile pipeline: ${File(zipPath).name}")
        val onLog: LogCallback = { message, severity ->
            when (severity) {
                ErrorSeverity.ERROR -> Log.e(BuildDiagnostics.TAG, message)
                ErrorSeverity.WARNING -> Log.w(BuildDiagnostics.TAG, message)
                else -> Log.i(BuildDiagnostics.TAG, message)
            }
            BuildDiagnostics.append(context, "[$severity] $message")
            onLog(message, severity)
        }

        val toolchainDir = registry.toolchainDir
        val buildDir = File(outputDir, "build").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }
        val androidJarPath = registry.getAndroidJar().absolutePath

        // Start performance hint session for big.LITTLE optimization
        val startTime = System.nanoTime()
        performanceHintHelper.beginCompilationSession()

        // Step 1: Extract
        onProgress(CompilationStep.EXTRACTING, 0f)
        onLog("Extracting project...", ErrorSeverity.INFO)
        val projectDir = try {
            projectExtractor.extract(zipPath, buildDir)
        } catch (e: Exception) {
            onLog("Failed to extract ZIP: ${e.message}", ErrorSeverity.ERROR)
            return@withContext CompilationResult.Failure(
                listOf(CompilationError("Extract", ErrorSeverity.ERROR, "Failed to extract ZIP: ${e.message}")),
                CompilationStep.EXTRACTING
            )
        }
        onLog("Project extracted to: ${projectDir.name}", ErrorSeverity.INFO)
        onProgress(CompilationStep.EXTRACTING, 1f)

        // Step 2: Analyze project type
        val projectInfo = projectAnalyzer.analyze(projectDir)
        onLog("Project type: ${projectInfo.type.name}", ErrorSeverity.INFO)
        onLog("Package: ${projectInfo.packageName ?: "unknown"}", ErrorSeverity.INFO)
        onLog("Source files: ${projectInfo.sourceFiles.size}", ErrorSeverity.INFO)

        // Route to appropriate compilation mode
        val result = when (projectInfo.type) {
            ProjectAnalyzer.ProjectType.GRADLE_PROJECT -> {
                onLog("Using Gradle build system (full project with dependencies)", ErrorSeverity.INFO)
                compileWithGradle(projectDir, outputDir, projectInfo, overrides, onProgress, onLog)
            }
            ProjectAnalyzer.ProjectType.SIMPLE_PROJECT -> {
                if (!registry.isAllInstalled()) {
                    onLog("Toolchain components not fully installed", ErrorSeverity.ERROR)
                    return@withContext CompilationResult.Failure(
                        listOf(CompilationError("Setup", ErrorSeverity.ERROR,
                            "Toolchain components not fully installed. Go to Components tab to download.")),
                        CompilationStep.EXTRACTING
                    )
                }
                onLog("Using direct compilation (simple project, no external deps)", ErrorSeverity.INFO)
                compileSimple(projectDir, outputDir, buildDir, toolchainDir, androidJarPath,
                    incrementalFileNames, onProgress, onLog)
            }
            ProjectAnalyzer.ProjectType.UNKNOWN -> {
                onLog("Could not determine project structure", ErrorSeverity.ERROR)
                return@withContext CompilationResult.Failure(
                    listOf(CompilationError("Analyze", ErrorSeverity.ERROR,
                        "Could not determine project structure. Expected AndroidManifest.xml and source files, " +
                        "or a Gradle project with build.gradle.kts/settings.gradle.kts.")),
                    CompilationStep.EXTRACTING
                )
            }
        }

        // Report actual compilation duration for performance hints
        val duration = System.nanoTime() - startTime
        performanceHintHelper.reportActualDuration(duration)
        performanceHintHelper.endSession()

        result
    }

    private suspend fun compileWithGradle(
        projectDir: File,
        outputDir: File,
        projectInfo: ProjectAnalyzer.ProjectInfo,
        overrides: GradleCompiler.VersionOverrides,
        onProgress: ProgressCallback,
        onLog: LogCallback
    ): CompilationResult {
        onProgress(CompilationStep.GRADLE_BUILD, 0.02f)
        onLog("Starting Gradle build...", ErrorSeverity.INFO)

        // Drive the progress bar from Gradle's LIVE task output. gradleCompiler streams
        // each "> Task :…" line (and Configure/Downloading) to onLog as the build runs,
        // so we advance a MONOTONIC 0..0.97 estimate from the task count (asymptotic —
        // Gradle never prints a task total up front) and snap to known milestones
        // (resources → kotlin compile → java-res → dex → package → assemble), which also
        // moves the step LABEL through the real phases. Previously this passed the raw
        // onLog and a fixed 0f, so the bar sat EMPTY for the whole (minutes-long) build
        // then jumped straight to full — exactly the "empty bar" the user saw.
        var tasksSeen = 0
        var lastP = 0.02f
        var curStep = CompilationStep.GRADLE_BUILD
        val progressOnLog: LogCallback = { message, severity ->
            val t = message.trimStart()
            if (t.startsWith("> Task")) tasksSeen++
            val milestone = gradleMilestone(t)
            if (milestone != null) curStep = milestone.first
            val byCount = 0.05f + 0.85f * (tasksSeen.toFloat() / (tasksSeen + 14f))
            val p = maxOf(lastP, milestone?.second ?: 0f, byCount).coerceAtMost(0.97f)
            if (p > lastP || milestone != null) {
                lastP = p
                onProgress(curStep, p)
            }
            onLog(message, severity)
        }

        val result = gradleCompiler.compile(projectDir, outputDir, projectInfo, progressOnLog, overrides)

        return when (result) {
            is StepResult.Success -> {
                onProgress(CompilationStep.SIGNING, 1f)
                val apkFile = result.outputs.first()
                onLog("BUILD SUCCESSFUL: ${apkFile.name} (${apkFile.length() / 1024} KB)", ErrorSeverity.INFO)
                CompilationResult.Success(apkFile)
            }
            is StepResult.Failure -> {
                onLog("GRADLE BUILD FAILED with ${result.errors.size} error(s)", ErrorSeverity.ERROR)
                CompilationResult.Failure(result.errors, CompilationStep.COMPILING_SOURCES)
            }
        }
    }

    /**
     * Maps a Gradle "> Task :…" line (or "BUILD SUCCESSFUL") to a (step, fraction) so the
     * progress bar + label track the real build phase. The compiler always runs
     * assembleDebug, so only debug task names are matched. Returns null for lines that
     * aren't progress milestones (the caller still advances the bar by task count).
     */
    private fun gradleMilestone(line: String): Pair<CompilationStep, Float>? = when {
        line.contains("BUILD SUCCESSFUL") -> CompilationStep.SIGNING to 0.99f
        line.startsWith("> Configure project") -> CompilationStep.GRADLE_BUILD to 0.05f
        !line.startsWith("> Task") -> null
        line.contains("assembleDebug") -> CompilationStep.SIGNING to 0.97f
        line.contains("packageDebug") -> CompilationStep.PACKAGING to 0.90f
        line.contains("DexBuilderDebug") || line.contains("dexBuilderDebug") ||
            line.contains("mergeDexDebug") || line.contains("mergeProjectDexDebug") ||
            line.contains("mergeExtDexDebug") -> CompilationStep.DEXING to 0.80f
        line.contains("processDebugJavaRes") || line.contains("mergeDebugJavaResource") ->
            CompilationStep.COMPILING_SOURCES to 0.68f
        line.contains("compileDebugJavaWithJavac") -> CompilationStep.COMPILING_SOURCES to 0.60f
        line.contains("compileDebugKotlin") || line.contains("kaptDebug") ||
            line.contains("kspDebug") -> CompilationStep.COMPILING_SOURCES to 0.50f
        line.contains("mergeDebugResources") || line.contains("processDebugResources") ->
            CompilationStep.COMPILING_RESOURCES to 0.32f
        line.contains("processDebugManifest") -> CompilationStep.COMPILING_RESOURCES to 0.28f
        line.contains("generateDebugResources") || line.contains("mergeDebugAssets") ||
            line.contains("mapDebugSourceSetPaths") -> CompilationStep.COMPILING_RESOURCES to 0.22f
        line.contains("preBuild") || line.contains("preDebugBuild") ||
            line.contains("generateDebugResValues") -> CompilationStep.GRADLE_BUILD to 0.10f
        else -> null
    }

    private suspend fun compileSimple(
        projectDir: File,
        outputDir: File,
        buildDir: File,
        toolchainDir: File,
        androidJarPath: String,
        incrementalFileNames: Boolean,
        onProgress: ProgressCallback,
        onLog: LogCallback
    ): CompilationResult {
        val context = CompilationContext(
            projectDir = projectDir,
            outputDir = outputDir,
            buildDir = buildDir,
            toolchainDir = toolchainDir,
            androidJarPath = androidJarPath,
            incrementalFileNames = incrementalFileNames
        )

        // Resource compilation
        onProgress(CompilationStep.COMPILING_RESOURCES, 0f)
        onLog("Compiling resources with AAPT2...", ErrorSeverity.INFO)
        val resourceResult = resourceCompiler.compile(context)
        if (resourceResult is StepResult.Failure) {
            return CompilationResult.Failure(resourceResult.errors, CompilationStep.COMPILING_RESOURCES)
        }
        onProgress(CompilationStep.COMPILING_RESOURCES, 1f)

        // Source compilation
        onProgress(CompilationStep.COMPILING_SOURCES, 0f)
        onLog("Compiling source files...", ErrorSeverity.INFO)
        val sourceResult = sourceCompiler.compile(context)
        if (sourceResult is StepResult.Failure) {
            return CompilationResult.Failure(sourceResult.errors, CompilationStep.COMPILING_SOURCES)
        }
        onProgress(CompilationStep.COMPILING_SOURCES, 1f)

        // DEX
        onProgress(CompilationStep.DEXING, 0f)
        onLog("Converting to DEX format...", ErrorSeverity.INFO)
        val dexResult = dexCompiler.compile(context)
        if (dexResult is StepResult.Failure) {
            return CompilationResult.Failure(dexResult.errors, CompilationStep.DEXING)
        }
        onProgress(CompilationStep.DEXING, 1f)

        // Package
        onProgress(CompilationStep.PACKAGING, 0f)
        onLog("Packaging APK...", ErrorSeverity.INFO)
        val packageResult = apkPackager.packageApk(context)
        if (packageResult is StepResult.Failure) {
            return CompilationResult.Failure(packageResult.errors, CompilationStep.PACKAGING)
        }
        onProgress(CompilationStep.PACKAGING, 1f)

        // Align
        onProgress(CompilationStep.ALIGNING, 0f)
        onLog("Aligning APK...", ErrorSeverity.INFO)
        val alignResult = apkAligner.align(context)
        if (alignResult is StepResult.Failure) {
            return CompilationResult.Failure(alignResult.errors, CompilationStep.ALIGNING)
        }
        onProgress(CompilationStep.ALIGNING, 1f)

        // Sign
        onProgress(CompilationStep.SIGNING, 0f)
        onLog("Signing APK...", ErrorSeverity.INFO)
        val signResult = apkSigner.sign(context)
        if (signResult is StepResult.Failure) {
            return CompilationResult.Failure(signResult.errors, CompilationStep.SIGNING)
        }
        onProgress(CompilationStep.SIGNING, 1f)

        val apkFile = (signResult as StepResult.Success).outputs.first()
        onLog("Build complete: ${apkFile.name} (${apkFile.length() / 1024} KB)", ErrorSeverity.INFO)

        // Clean up build intermediates
        try { buildDir.deleteRecursively() } catch (_: Exception) {}

        return CompilationResult.Success(apkFile)
    }
}

sealed interface StepResult {
    data class Success(val outputs: List<File>) : StepResult
    data class Failure(val errors: List<CompilationError>) : StepResult
}
