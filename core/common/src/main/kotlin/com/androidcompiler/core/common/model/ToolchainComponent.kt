package com.androidcompiler.core.common.model

import kotlinx.serialization.Serializable

@Serializable
data class ToolchainComponent(
    val id: String,
    val displayName: String,
    val version: String,
    val sizeBytes: Long,
    val type: ComponentType,
    val sources: List<DownloadSource>,
    val sha256: String,
    val installPath: String
)

@Serializable
enum class ComponentType {
    JAR,
    NATIVE_BINARY,
    SDK_PLATFORM,
    JDK_ARCHIVE
}

@Serializable
data class DownloadSource(
    val url: String,
    val mirror: String,
    val priority: Int
)

sealed interface ComponentStatus {
    data object NotInstalled : ComponentStatus
    data object Installed : ComponentStatus
    data class UpdateAvailable(val newVersion: String) : ComponentStatus
    data class Downloading(val progress: Float) : ComponentStatus
    data class Error(val message: String) : ComponentStatus
}
