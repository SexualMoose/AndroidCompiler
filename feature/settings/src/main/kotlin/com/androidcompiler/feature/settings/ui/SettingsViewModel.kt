package com.androidcompiler.feature.settings.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.androidcompiler.core.common.model.AppSettings
import com.androidcompiler.core.common.model.CoreType
import com.androidcompiler.core.common.model.NetworkType
import com.androidcompiler.core.common.model.ThemeMode
import com.androidcompiler.core.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AppSettings()
        )

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    }

    fun setCoreTypes(types: Set<CoreType>) {
        viewModelScope.launch { settingsRepository.setCoreTypes(types) }
    }

    fun setNetworkTypes(types: Set<NetworkType>) {
        viewModelScope.launch { settingsRepository.setNetworkTypes(types) }
    }

    fun setDefaultInputFolder(path: String) {
        viewModelScope.launch { settingsRepository.setDefaultInputFolder(path) }
    }

    fun setDefaultOutputFolder(path: String) {
        viewModelScope.launch { settingsRepository.setDefaultOutputFolder(path) }
    }

    fun setIncrementalFileNames(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setIncrementalFileNames(enabled) }
    }

    fun checkForUpdates() {
        viewModelScope.launch {
            // TODO: Wire up to ComponentUpdateChecker
        }
    }
}
