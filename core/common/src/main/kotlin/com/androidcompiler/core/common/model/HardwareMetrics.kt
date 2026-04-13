package com.androidcompiler.core.common.model

data class CpuUsage(
    val overallPercent: Float,
    val perCorePercent: List<Float>
)

data class RamUsage(
    val totalSystemBytes: Long,
    val availableSystemBytes: Long,
    val heapUsedBytes: Long,
    val heapMaxBytes: Long
) {
    val usedSystemBytes: Long get() = totalSystemBytes - availableSystemBytes
    val systemUsagePercent: Float get() = if (totalSystemBytes > 0) usedSystemBytes.toFloat() / totalSystemBytes else 0f
}

data class NetworkThroughput(
    val downloadBytesPerSec: Long,
    val uploadBytesPerSec: Long,
    val activeInterfaces: List<NetworkInterfaceInfo>
)

data class NetworkInterfaceInfo(
    val name: String,
    val type: String,
    val downloadBytesPerSec: Long,
    val uploadBytesPerSec: Long
)

enum class GpuStatus {
    IDLE,
    ACTIVE
}

data class HardwareSnapshot(
    val cpu: CpuUsage,
    val ram: RamUsage,
    val gpu: GpuStatus,
    val network: NetworkThroughput
)
