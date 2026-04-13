package com.androidcompiler.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class AvailableNetwork(
    val network: Network,
    val type: NetworkType,
    val bandwidthKbps: Int
) {
    enum class NetworkType { WIFI, CELLULAR, OTHER }
}

@Singleton
class MultiNetworkManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _availableNetworks = MutableStateFlow<List<AvailableNetwork>>(emptyList())
    val availableNetworks: StateFlow<List<AvailableNetwork>> = _availableNetworks.asStateFlow()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            updateNetworks()
        }

        override fun onLost(network: Network) {
            _availableNetworks.value = _availableNetworks.value.filter { it.network != network }
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            updateNetworks()
        }
    }

    fun startMonitoring() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
        updateNetworks()
    }

    fun stopMonitoring() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) { }
    }

    private fun updateNetworks() {
        val networks = connectivityManager.allNetworks.mapNotNull { network ->
            val caps = connectivityManager.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@mapNotNull null

            val type = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> AvailableNetwork.NetworkType.WIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> AvailableNetwork.NetworkType.CELLULAR
                else -> AvailableNetwork.NetworkType.OTHER
            }

            AvailableNetwork(
                network = network,
                type = type,
                bandwidthKbps = caps.linkDownstreamBandwidthKbps
            )
        }
        _availableNetworks.value = networks
    }
}
