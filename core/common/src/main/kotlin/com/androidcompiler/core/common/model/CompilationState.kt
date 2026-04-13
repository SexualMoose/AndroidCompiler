package com.androidcompiler.core.common.model

sealed interface CompilationState {
    data object Idle : CompilationState
    data class ProjectLoaded(val projectName: String, val projectPath: String) : CompilationState
    data class Compiling(val step: CompilationStep, val progress: Float) : CompilationState
    data class Complete(val apkPath: String) : CompilationState
    data class Failed(val errors: List<CompilationError>) : CompilationState
}

enum class CompilationStep(val displayName: String) {
    EXTRACTING("Extracting project"),
    COMPILING_RESOURCES("Compiling resources"),
    GENERATING_R("Generating R.java"),
    COMPILING_SOURCES("Compiling sources"),
    DEXING("Converting to DEX"),
    PACKAGING("Packaging APK"),
    ALIGNING("Aligning APK"),
    SIGNING("Signing APK")
}

data class CompilationError(
    val step: String,
    val severity: ErrorSeverity,
    val message: String,
    val filePath: String? = null,
    val line: Int? = null,
    val column: Int? = null,
    val rawOutput: String = ""
)

enum class ErrorSeverity { ERROR, WARNING, INFO }
