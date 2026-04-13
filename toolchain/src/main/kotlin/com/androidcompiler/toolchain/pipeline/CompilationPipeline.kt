package com.androidcompiler.toolchain.pipeline

import com.androidcompiler.core.common.model.CompilationError
import com.androidcompiler.core.common.model.CompilationStep
import com.androidcompiler.core.common.model.ErrorSeverity
import com.androidcompiler.toolchain.compute.PerformanceHintHelper
import com.androidcompiler.toolchain.registry.ToolchainRegistry
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
    private val projectExtractor: ProjectExtractor,
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
        onProgress: ProgressCallback,
        onLog: LogCallback
    ): CompilationResult = withContext(Dispatchers.Default) {
        val toolchainDir = registry.toolchainDir
        val buildDir = File(outputDir, "build").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }
        val androidJarPath = registry.getAndroidJar().absolutePath

        // Start performance hint session for big.LITTLE optimization
        val startTime = System.nanoTime()
        performanceHintHelper.beginCompilationSession()

        if (!registry.isAllInstalled()) {
            return@withContext CompilationResult.Failure(
                listOf(CompilationError("Setup", ErrorSeverity.ERROR, "Toolchain components not fully installed. Go to Components tab to download.")),
                CompilationStep.EXTRACTING
            )
        }

        // Step 1: Extract
        onProgress(CompilationStep.EXTRACTING, 0f)
        onLog("Extracting project...", ErrorSeverity.INFO)
        val projectDir = try {
            projectExtractor.extract(zipPath, buildDir)
        } catch (e: Exception) {
            return@withContext CompilationResult.Failure(
                listOf(CompilationError("Extract", ErrorSeverity.ERROR, "Failed to extract ZIP: ${e.message}")),
                CompilationStep.EXTRACTING
            )
        }
        onLog("Project extracted to: ${projectDir.name}", ErrorSeverity.INFO)
        onProgress(CompilationStep.EXTRACTING, 1f)

        val context = CompilationContext(
            projectDir = projectDir,
            outputDir = outputDir,
            buildDir = buildDir,
            toolchainDir = toolchainDir,
            androidJarPath = androidJarPath,
            incrementalFileNames = incrementalFileNames
        )

        // Step 2: Compile Resources
        onProgress(CompilationStep.COMPILING_RESOURCES, 0f)
        onLog("Compiling resources with AAPT2...", ErrorSeverity.INFO)
        val resourceResult = resourceCompiler.compile(context)
        if (resourceResult is StepResult.Failure) {
            onLog("Resource compilation failed", ErrorSeverity.ERROR)
            return@withContext CompilationResult.Failure(resourceResult.errors, CompilationStep.COMPILING_RESOURCES)
        }
        onLog("Resources compiled successfully", ErrorSeverity.INFO)
        onProgress(CompilationStep.COMPILING_RESOURCES, 1f)

        // Step 3: Compile Sources
        onProgress(CompilationStep.COMPILING_SOURCES, 0f)
        onLog("Compiling source files...", ErrorSeverity.INFO)
        val sourceResult = sourceCompiler.compile(context)
        if (sourceResult is StepResult.Failure) {
            onLog("Source compilation failed with ${sourceResult.errors.size} error(s)", ErrorSeverity.ERROR)
            return@withContext CompilationResult.Failure(sourceResult.errors, CompilationStep.COMPILING_SOURCES)
        }
        onLog("Source compilation successful", ErrorSeverity.INFO)
        onProgress(CompilationStep.COMPILING_SOURCES, 1f)

        // Step 4: DEX
        onProgress(CompilationStep.DEXING, 0f)
        onLog("Converting to DEX format...", ErrorSeverity.INFO)
        val dexResult = dexCompiler.compile(context)
        if (dexResult is StepResult.Failure) {
            onLog("DEX conversion failed", ErrorSeverity.ERROR)
            return@withContext CompilationResult.Failure(dexResult.errors, CompilationStep.DEXING)
        }
        onLog("DEX conversion successful", ErrorSeverity.INFO)
        onProgress(CompilationStep.DEXING, 1f)

        // Step 5: Package
        onProgress(CompilationStep.PACKAGING, 0f)
        onLog("Packaging APK...", ErrorSeverity.INFO)
        val packageResult = apkPackager.packageApk(context)
        if (packageResult is StepResult.Failure) {
            onLog("APK packaging failed", ErrorSeverity.ERROR)
            return@withContext CompilationResult.Failure(packageResult.errors, CompilationStep.PACKAGING)
        }
        onLog("APK packaged", ErrorSeverity.INFO)
        onProgress(CompilationStep.PACKAGING, 1f)

        // Step 6: Align
        onProgress(CompilationStep.ALIGNING, 0f)
        onLog("Aligning APK...", ErrorSeverity.INFO)
        val alignResult = apkAligner.align(context)
        if (alignResult is StepResult.Failure) {
            onLog("APK alignment failed", ErrorSeverity.ERROR)
            return@withContext CompilationResult.Failure(alignResult.errors, CompilationStep.ALIGNING)
        }
        onLog("APK aligned", ErrorSeverity.INFO)
        onProgress(CompilationStep.ALIGNING, 1f)

        // Step 7: Sign
        onProgress(CompilationStep.SIGNING, 0f)
        onLog("Signing APK...", ErrorSeverity.INFO)
        val signResult = apkSigner.sign(context)
        if (signResult is StepResult.Failure) {
            onLog("APK signing failed", ErrorSeverity.ERROR)
            return@withContext CompilationResult.Failure(signResult.errors, CompilationStep.SIGNING)
        }
        onProgress(CompilationStep.SIGNING, 1f)

        val apkFile = (signResult as StepResult.Success).outputs.first()
        onLog("Build complete: ${apkFile.name} (${apkFile.length() / 1024} KB)", ErrorSeverity.INFO)

        // Report actual compilation duration for performance hints
        val duration = System.nanoTime() - startTime
        performanceHintHelper.reportActualDuration(duration)
        performanceHintHelper.endSession()

        // Clean up build intermediates
        try { buildDir.deleteRecursively() } catch (_: Exception) {}

        CompilationResult.Success(apkFile)
    }
}

sealed interface StepResult {
    data class Success(val outputs: List<File>) : StepResult
    data class Failure(val errors: List<CompilationError>) : StepResult
}
