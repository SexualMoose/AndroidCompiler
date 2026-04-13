package com.androidcompiler.feature.monitor.ui

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.androidcompiler.core.common.model.CpuUsage
import com.androidcompiler.core.common.model.GpuStatus
import com.androidcompiler.core.common.model.NetworkInterfaceInfo
import com.androidcompiler.core.common.model.NetworkThroughput
import com.androidcompiler.core.common.model.RamUsage
import com.androidcompiler.toolchain.compute.GpuHasher
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
import java.net.NetworkInterface
import javax.inject.Inject

@HiltViewModel
class MonitorViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gpuHasher: GpuHasher
) : ViewModel() {

    private val _cpuUsage = MutableStateFlow(CpuUsage(0f, emptyList()))
    val cpuUsage: StateFlow<CpuUsage> = _cpuUsage.asStateFlow()

    private val _ramUsage = MutableStateFlow(RamUsage(0, 0, 0, 0))
    val ramUsage: StateFlow<RamUsage> = _ramUsage.asStateFlow()

    val gpuStatus: StateFlow<GpuStatus> = gpuHasher.status

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
            RandomAccessFile("/proc/stat", "r").use { reader ->
                var line = reader.readLine()
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
                    line = reader.readLine()
                }
            }
        } catch (_: Exception) { }
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
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // Track per-interface bytes for delta calculation
        var prevInterfaceBytes = readInterfaceBytes()
        var prevTotalRx = TrafficStats.getTotalRxBytes()
        var prevTotalTx = TrafficStats.getTotalTxBytes()

        while (true) {
            delay(1000)
            val currTotalRx = TrafficStats.getTotalRxBytes()
            val currTotalTx = TrafficStats.getTotalTxBytes()
            val currInterfaceBytes = readInterfaceBytes()

            val totalDownSpeed = currTotalRx - prevTotalRx
            val totalUpSpeed = currTotalTx - prevTotalTx

            // Build per-interface breakdown
            val interfaces = mutableListOf<NetworkInterfaceInfo>()

            // Identify interface types from ConnectivityManager
            val activeNetworks = connectivityManager.allNetworks
            val interfaceTypes = mutableMapOf<String, String>()

            for (network in activeNetworks) {
                val caps = connectivityManager.getNetworkCapabilities(network) ?: continue
                val linkProps = connectivityManager.getLinkProperties(network) ?: continue
                val ifaceName = linkProps.interfaceName ?: continue
                val type = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                    else -> "Other"
                }
                interfaceTypes[ifaceName] = type
            }

            // Calculate per-interface speed deltas
            for ((ifaceName, currBytes) in currInterfaceBytes) {
                val prevBytes = prevInterfaceBytes[ifaceName] ?: continue
                val rxSpeed = currBytes.first - prevBytes.first
                val txSpeed = currBytes.second - prevBytes.second

                if (rxSpeed > 0 || txSpeed > 0) {
                    val type = interfaceTypes[ifaceName] ?: classifyInterface(ifaceName)
                    interfaces.add(
                        NetworkInterfaceInfo(
                            name = ifaceName,
                            type = type,
                            downloadBytesPerSec = rxSpeed,
                            uploadBytesPerSec = txSpeed
                        )
                    )
                }
            }

            // Sort: most active interfaces first
            interfaces.sortByDescending { it.downloadBytesPerSec + it.uploadBytesPerSec }

            _networkThroughput.value = NetworkThroughput(
                downloadBytesPerSec = totalDownSpeed,
                uploadBytesPerSec = totalUpSpeed,
                activeInterfaces = interfaces
            )

            prevTotalRx = currTotalRx
            prevTotalTx = currTotalTx
            prevInterfaceBytes = currInterfaceBytes
        }
    }

    /**
     * Read per-interface RX/TX bytes from /proc/net/dev
     */
    private fun readInterfaceBytes(): Map<String, Pair<Long, Long>> {
        val result = mutableMapOf<String, Pair<Long, Long>>()
        try {
            RandomAccessFile("/proc/net/dev", "r").use { reader ->
                // Skip header lines
                reader.readLine()
                reader.readLine()
                var line = reader.readLine()
                while (line != null) {
                    val parts = line.trim().split("\\s+".toRegex())
                    if (parts.size >= 10) {
                        val ifaceName = parts[0].removeSuffix(":")
                        if (ifaceName != "lo") { // Skip loopback
                            val rxBytes = parts[1].toLongOrNull() ?: 0
                            val txBytes = parts[9].toLongOrNull() ?: 0
                            result[ifaceName] = rxBytes to txBytes
                        }
                    }
                    line = reader.readLine()
                }
            }
        } catch (_: Exception) { }
        return result
    }

    private fun classifyInterface(name: String): String = when {
        name.startsWith("wlan") -> "Wi-Fi"
        name.startsWith("rmnet") || name.startsWith("ccmni") -> "Cellular"
        name.startsWith("eth") -> "Ethernet"
        name.startsWith("tun") || name.startsWith("vpn") -> "VPN"
        else -> "Other"
    }
}
