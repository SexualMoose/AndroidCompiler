package com.androidcompiler.feature.components.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.androidcompiler.core.common.model.ComponentStatus
import com.androidcompiler.core.common.model.ToolchainComponent
import com.androidcompiler.toolchain.download.ComponentDownloadManager
import com.androidcompiler.toolchain.registry.ToolchainRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ComponentsViewModel @Inject constructor(
    private val registry: ToolchainRegistry,
    private val downloadManager: ComponentDownloadManager
) : ViewModel() {

    private val _components = MutableStateFlow<List<Pair<ToolchainComponent, ComponentStatus>>>(emptyList())
    val components: StateFlow<List<Pair<ToolchainComponent, ComponentStatus>>> = _components.asStateFlow()

    private val _isDownloadingAll = MutableStateFlow(false)
    val isDownloadingAll: StateFlow<Boolean> = _isDownloadingAll.asStateFlow()

    init {
        refreshAll()
    }

    fun refreshAll() {
        _components.value = registry.getComponents().map { component ->
            component to registry.getComponentStatus(component)
        }
    }

    fun download(componentId: String) {
        val component = registry.getComponents().find { it.id == componentId } ?: return

        updateComponentStatus(componentId, ComponentStatus.Downloading(0f))

        viewModelScope.launch {
            val result = downloadManager.downloadComponent(component) { progress ->
                updateComponentStatus(componentId, ComponentStatus.Downloading(progress))
            }

            if (result.isSuccess) {
                updateComponentStatus(componentId, ComponentStatus.Installed)
            } else {
                updateComponentStatus(
                    componentId,
                    ComponentStatus.Error(result.exceptionOrNull()?.message ?: "Download failed")
                )
            }
        }
    }

    fun downloadAll() {
        _isDownloadingAll.value = true

        viewModelScope.launch {
            downloadManager.downloadAll(
                onComponentProgress = { componentId, progress ->
                    updateComponentStatus(componentId, ComponentStatus.Downloading(progress))
                },
                onComponentComplete = { componentId, success, error ->
                    if (success) {
                        updateComponentStatus(componentId, ComponentStatus.Installed)
                    } else {
                        updateComponentStatus(componentId, ComponentStatus.Error(error ?: "Download failed"))
                    }
                }
            )
            _isDownloadingAll.value = false
        }
    }

    private fun updateComponentStatus(componentId: String, status: ComponentStatus) {
        _components.update { list ->
            list.map { (component, currentStatus) ->
                if (component.id == componentId) component to status
                else component to currentStatus
            }
        }
    }
}
