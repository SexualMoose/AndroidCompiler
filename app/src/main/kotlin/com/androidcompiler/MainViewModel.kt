package com.androidcompiler

import android.os.PerformanceHintManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.androidcompiler.core.common.model.ThemeMode
import com.androidcompiler.core.data.repository.SettingsRepository
import com.androidcompiler.toolchain.compute.PerformanceHintHelper
import com.androidcompiler.toolchain.registry.ToolchainRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
    private val toolchainRegistry: ToolchainRegistry,
    private val performanceHintHelper: PerformanceHintHelper
) : ViewModel() {

    val themeMode: StateFlow<ThemeMode> = settingsRepository.settings
        .map { it.themeMode }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ThemeMode.OLED
        )

    val needsSetup: Boolean
        get() = !toolchainRegistry.isAllInstalled()

    fun initPerformanceHints(manager: PerformanceHintManager?) {
        performanceHintHelper.init(manager)
    }
}
