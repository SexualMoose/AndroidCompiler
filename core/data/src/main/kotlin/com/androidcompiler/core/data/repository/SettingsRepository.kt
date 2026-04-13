package com.androidcompiler.core.data.repository

import com.androidcompiler.core.common.model.AppSettings
import com.androidcompiler.core.common.model.CoreType
import com.androidcompiler.core.common.model.NetworkType
import com.androidcompiler.core.common.model.ThemeMode
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setCoreTypes(types: Set<CoreType>)
    suspend fun setNetworkTypes(types: Set<NetworkType>)
    suspend fun setDefaultInputFolder(path: String?)
    suspend fun setDefaultOutputFolder(path: String?)
    suspend fun setIncrementalFileNames(enabled: Boolean)
}
