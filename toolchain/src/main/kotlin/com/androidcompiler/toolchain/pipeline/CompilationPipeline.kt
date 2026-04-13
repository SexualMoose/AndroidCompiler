package com.androidcompiler.toolchain.pipeline

import com.androidcompiler.core.common.model.CompilationError
import com.androidcompiler.core.common.model.CompilationStep
import com.androidcompiler.core.common.model.ErrorSeverity
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
    val androidJarPath: String
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
    private val apkSigner: ApkSigner
) {
    suspend fun compile(
        zipPath: String,
        outputDir: File,
        toolchainDir: File,
        onProgress: ProgressCallback,
        onLog: LogCallback
    ): CompilationResult = withContext(Dispatchers.Default) {

        val buildDir = File(outputDir, "build").apply { mkdirs() }
        val androidJarPath = File(toolchainDir, "android.jar").absolutePath

        // Step 1: Extract
        onProgress(CompilationStep.EXTRACTING, 0f)
        onLog("Extracting project...", ErrorSeverity.INFO)
        val projectDir = projectExtractor.extract(zipPath, buildDir)
        onProgress(CompilationStep.EXTRACTING, 1f)

        val context = CompilationContext(
            projectDir = projectDir,
            outputDir = outputDir,
            buildDir = buildDir,
            toolchainDir = toolchainDir,
            androidJarPath = androidJarPath
        )

        // Step 2: Compile Resources
        onProgress(CompilationStep.COMPILING_RESOURCES, 0f)
        onLog("Compiling resources with AAPT2...", ErrorSeverity.INFO)
        val resourceResult = resourceCompiler.compile(context)
        if (resourceResult is StepResult.Failure) {
            return@withContext CompilationResult.Failure(resourceResult.errors, CompilationStep.COMPILING_RESOURCES)
        }
        onProgress(CompilationStep.COMPILING_RESOURCES, 1f)

        // Step 3: Compile Sources
        onProgress(CompilationStep.COMPILING_SOURCES, 0f)
        onLog("Compiling source files...", ErrorSeverity.INFO)
        val sourceResult = sourceCompiler.compile(context)
        if (sourceResult is StepResult.Failure) {
            return@withContext CompilationResult.Failure(sourceResult.errors, CompilationStep.COMPILING_SOURCES)
        }
        onProgress(CompilationStep.COMPILING_SOURCES, 1f)

        // Step 4: DEX
        onProgress(CompilationStep.DEXING, 0f)
        onLog("Converting to DEX...", ErrorSeverity.INFO)
        val dexResult = dexCompiler.compile(context)
        if (dexResult is StepResult.Failure) {
            return@withContext CompilationResult.Failure(dexResult.errors, CompilationStep.DEXING)
        }
        onProgress(CompilationStep.DEXING, 1f)

        // Step 5: Package
        onProgress(CompilationStep.PACKAGING, 0f)
        onLog("Packaging APK...", ErrorSeverity.INFO)
        val packageResult = apkPackager.packageApk(context)
        if (packageResult is StepResult.Failure) {
            return@withContext CompilationResult.Failure(packageResult.errors, CompilationStep.PACKAGING)
        }
        onProgress(CompilationStep.PACKAGING, 1f)

        // Step 6: Align
        onProgress(CompilationStep.ALIGNING, 0f)
        onLog("Aligning APK...", ErrorSeverity.INFO)
        val alignResult = apkAligner.align(context)
        if (alignResult is StepResult.Failure) {
            return@withContext CompilationResult.Failure(alignResult.errors, CompilationStep.ALIGNING)
        }
        onProgress(CompilationStep.ALIGNING, 1f)

        // Step 7: Sign
        onProgress(CompilationStep.SIGNING, 0f)
        onLog("Signing APK...", ErrorSeverity.INFO)
        val signResult = apkSigner.sign(context)
        if (signResult is StepResult.Failure) {
            return@withContext CompilationResult.Failure(signResult.errors, CompilationStep.SIGNING)
        }
        onProgress(CompilationStep.SIGNING, 1f)

        val apkFile = (signResult as StepResult.Success).outputs.first()
        onLog("Build complete: ${apkFile.name}", ErrorSeverity.INFO)
        CompilationResult.Success(apkFile)
    }
}

sealed interface StepResult {
    data class Success(val outputs: List<File>) : StepResult
    data class Failure(val errors: List<CompilationError>) : StepResult
}
