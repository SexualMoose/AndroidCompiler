package com.androidcompiler.core.common.model

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.OLED,
    val coreTypes: Set<CoreType> = CoreType.entries.toSet(),
    val networkTypes: Set<NetworkType> = NetworkType.entries.toSet(),
    val defaultInputFolder: String? = null,
    val defaultOutputFolder: String? = null,
    val incrementalFileNames: Boolean = true
)

enum class CoreType(val displayName: String) {
    PERFORMANCE("Performance"),
    EFFICIENCY("Efficiency")
}

enum class NetworkType(val displayName: String) {
    WIFI("Wi-Fi"),
    CELLULAR("Cellular")
}
