package com.androidcompiler.feature.settings.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.androidcompiler.core.common.model.AppSettings
import com.androidcompiler.core.common.model.CoreType
import com.androidcompiler.core.common.model.NetworkType
import com.androidcompiler.core.common.model.ThemeMode
import com.androidcompiler.core.data.repository.SettingsRepository
import com.androidcompiler.toolchain.signing.KeystoreManager
import com.androidcompiler.toolchain.update.ComponentUpdateChecker
import com.androidcompiler.toolchain.update.UpdateInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val updateChecker: ComponentUpdateChecker,
    private val keystoreManager: KeystoreManager
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AppSettings()
        )

    private val _updateResults = MutableStateFlow<List<UpdateInfo>>(emptyList())
    val updateResults: StateFlow<List<UpdateInfo>> = _updateResults.asStateFlow()

    private val _isCheckingUpdates = MutableStateFlow(false)
    val isCheckingUpdates: StateFlow<Boolean> = _isCheckingUpdates.asStateFlow()

    private val _updateMessage = MutableStateFlow<String?>(null)
    val updateMessage: StateFlow<String?> = _updateMessage.asStateFlow()

    private val _isApplyingUpdates = MutableStateFlow(false)
    val isApplyingUpdates: StateFlow<Boolean> = _isApplyingUpdates.asStateFlow()

    private val _hasDebugKeystore = MutableStateFlow(keystoreManager.hasDebugKeystore())
    val hasDebugKeystore: StateFlow<Boolean> = _hasDebugKeystore.asStateFlow()

    private val _keystoreMessage = MutableStateFlow<String?>(null)
    val keystoreMessage: StateFlow<String?> = _keystoreMessage.asStateFlow()

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
        _isCheckingUpdates.value = true
        _updateMessage.value = null
        viewModelScope.launch {
            try {
                val results = updateChecker.checkAll()
                _updateResults.value = results
                val updatesAvailable = results.count { it.hasUpdate }
                _updateMessage.value = if (updatesAvailable > 0) {
                    "$updatesAvailable update(s) available"
                } else {
                    "All components are up to date"
                }
            } catch (e: Exception) {
                _updateMessage.value = "Update check failed: ${e.message}"
            } finally {
                _isCheckingUpdates.value = false
            }
        }
    }

    fun applyAllUpdates() {
        val updates = _updateResults.value.filter { it.hasUpdate }
        if (updates.isEmpty()) return

        _isApplyingUpdates.value = true
        _updateMessage.value = "Updating ${updates.size} component(s)..."
        viewModelScope.launch {
            var successCount = 0
            var failCount = 0
            updateChecker.applyAllUpdates(
                updates = updates,
                onComponentProgress = { id, progress ->
                    val name = updates.find { it.componentId == id }?.displayName ?: id
                    _updateMessage.value = "Updating $name... ${(progress * 100).toInt()}%"
                },
                onComponentComplete = { id, success, error ->
                    if (success) successCount++ else failCount++
                }
            )
            _updateMessage.value = if (failCount == 0) {
                "$successCount component(s) updated successfully"
            } else {
                "$successCount updated, $failCount failed"
            }
            // Re-check to clear the update flags
            _updateResults.value = _updateResults.value.map {
                if (it.hasUpdate) it.copy(hasUpdate = false) else it
            }
            _isApplyingUpdates.value = false
        }
    }

    fun applySingleUpdate(componentId: String) {
        val update = _updateResults.value.find { it.componentId == componentId && it.hasUpdate }
            ?: return

        _isApplyingUpdates.value = true
        _updateMessage.value = "Updating ${update.displayName}..."
        viewModelScope.launch {
            val result = updateChecker.applyUpdate(update) { progress ->
                _updateMessage.value = "Updating ${update.displayName}... ${(progress * 100).toInt()}%"
            }
            if (result.isSuccess) {
                _updateMessage.value = "${update.displayName} updated to ${update.latestVersion}"
                _updateResults.value = _updateResults.value.map {
                    if (it.componentId == componentId) it.copy(hasUpdate = false) else it
                }
            } else {
                _updateMessage.value = "Failed to update ${update.displayName}: ${result.exceptionOrNull()?.message}"
            }
            _isApplyingUpdates.value = false
        }
    }

    fun generateDebugKeystore() {
        viewModelScope.launch {
            try {
                keystoreManager.getOrCreateDebugKeystore()
                _hasDebugKeystore.value = true
                _keystoreMessage.value = "Debug keystore created"
            } catch (e: Exception) {
                _keystoreMessage.value = "Failed: ${e.message}"
            }
        }
    }

    fun importKeystore(uri: Uri, password: String) {
        viewModelScope.launch {
            try {
                val tempFile = File(context.cacheDir, "import_keystore.tmp")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                }
                val result = keystoreManager.importKeystore(tempFile, password.toCharArray())
                tempFile.delete()
                if (result.isSuccess) {
                    _keystoreMessage.value = "Keystore imported successfully"
                } else {
                    _keystoreMessage.value = "Import failed: ${result.exceptionOrNull()?.message}"
                }
            } catch (e: Exception) {
                _keystoreMessage.value = "Import failed: ${e.message}"
            }
        }
    }
}
