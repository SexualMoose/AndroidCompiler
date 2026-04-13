package com.androidcompiler.feature.monitor.ui

import android.app.ActivityManager
import android.content.Context
import android.net.TrafficStats
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.androidcompiler.core.common.model.CpuUsage
import com.androidcompiler.core.common.model.GpuStatus
import com.androidcompiler.core.common.model.NetworkInterfaceInfo
import com.androidcompiler.core.common.model.NetworkThroughput
import com.androidcompiler.core.common.model.RamUsage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.RandomAccessFile
import javax.inject.Inject

@HiltViewModel
class MonitorViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _cpuUsage = MutableStateFlow(CpuUsage(0f, emptyList()))
    val cpuUsage: StateFlow<CpuUsage> = _cpuUsage.asStateFlow()

    private val _ramUsage = MutableStateFlow(RamUsage(0, 0, 0, 0))
    val ramUsage: StateFlow<RamUsage> = _ramUsage.asStateFlow()

    private val _gpuStatus = MutableStateFlow(GpuStatus.IDLE)
    val gpuStatus: StateFlow<GpuStatus> = _gpuStatus.asStateFlow()

    private val _networkThroughput = MutableStateFlow(NetworkThroughput(0, 0, emptyList()))
    val networkThroughput: StateFlow<NetworkThroughput> = _networkThroughput.asStateFlow()

    init {
        startMonitoring()
    }

    private fun startMonitoring() {
        viewModelScope.launch { monitorCpu() }
        viewModelScope.launch { monitorRam() }
        viewModelScope.launch { monitorNetwork() }
    }

    private suspend fun monitorCpu() = withContext(Dispatchers.IO) {
        var prevCpuData = readCpuData()
        while (true) {
            delay(1000)
            val currentCpuData = readCpuData()
            val overall = calculateCpuPercent(prevCpuData.first, currentCpuData.first)
            val perCore = prevCpuData.second.zip(currentCpuData.second).map { (prev, curr) ->
                calculateCpuPercent(prev, curr)
            }
            _cpuUsage.value = CpuUsage(overall, perCore)
            prevCpuData = currentCpuData
        }
    }

    private fun readCpuData(): Pair<LongArray, List<LongArray>> {
        val overall = longArrayOf(0, 0)
        val cores = mutableListOf<LongArray>()
        try {
            val reader = RandomAccessFile("/proc/stat", "r")
            reader.use {
                var line = it.readLine()
                while (line != null) {
                    if (line.startsWith("cpu")) {
                        val parts = line.trim().split("\\s+".toRegex())
                        if (parts.size >= 5) {
                            val values = parts.drop(1).map { v -> v.toLongOrNull() ?: 0L }
                            val idle = values.getOrElse(3) { 0L }
                            val total = values.sum()
                            if (parts[0] == "cpu") {
                                overall[0] = idle
                                overall[1] = total
                            } else {
                                cores.add(longArrayOf(idle, total))
                            }
                        }
                    } else if (!line.startsWith("cpu")) {
                        break
                    }
                    line = it.readLine()
                }
            }
        } catch (_: Exception) {
            // /proc/stat may not be accessible on all devices
        }
        return overall to cores
    }

    private fun calculateCpuPercent(prev: LongArray, curr: LongArray): Float {
        val idleDelta = curr[0] - prev[0]
        val totalDelta = curr[1] - prev[1]
        return if (totalDelta > 0) {
            ((totalDelta - idleDelta).toFloat() / totalDelta * 100f).coerceIn(0f, 100f)
        } else 0f
    }

    private suspend fun monitorRam() = withContext(Dispatchers.IO) {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        while (true) {
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            val runtime = Runtime.getRuntime()
            _ramUsage.value = RamUsage(
                totalSystemBytes = memInfo.totalMem,
                availableSystemBytes = memInfo.availMem,
                heapUsedBytes = runtime.totalMemory() - runtime.freeMemory(),
                heapMaxBytes = runtime.maxMemory()
            )
            delay(1000)
        }
    }

    private suspend fun monitorNetwork() = withContext(Dispatchers.IO) {
        var prevRx = TrafficStats.getTotalRxBytes()
        var prevTx = TrafficStats.getTotalTxBytes()
        while (true) {
            delay(1000)
            val currRx = TrafficStats.getTotalRxBytes()
            val currTx = TrafficStats.getTotalTxBytes()
            val mobileRx = TrafficStats.getMobileRxBytes()
            val mobileTx = TrafficStats.getMobileTxBytes()

            val totalDownSpeed = currRx - prevRx
            val totalUpSpeed = currTx - prevTx

            val interfaces = mutableListOf<NetworkInterfaceInfo>()
            if (mobileRx > 0) {
                interfaces.add(
                    NetworkInterfaceInfo("Mobile", "Cellular", 0, 0)
                )
            }
            interfaces.add(
                NetworkInterfaceInfo("Total", "All", totalDownSpeed, totalUpSpeed)
            )

            _networkThroughput.value = NetworkThroughput(
                downloadBytesPerSec = totalDownSpeed,
                uploadBytesPerSec = totalUpSpeed,
                activeInterfaces = interfaces
            )

            prevRx = currRx
            prevTx = currTx
        }
    }
}
