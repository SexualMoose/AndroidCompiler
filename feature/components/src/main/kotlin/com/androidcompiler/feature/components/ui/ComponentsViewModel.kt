package com.androidcompiler.feature.components.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.androidcompiler.core.common.model.ComponentStatus
import com.androidcompiler.core.common.model.ToolchainComponent
import com.androidcompiler.toolchain.download.ComponentDownloadManager
import com.androidcompiler.toolchain.registry.ToolchainRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@HiltViewModel
class ComponentsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val registry: ToolchainRegistry,
    private val downloadManager: ComponentDownloadManager
) : ViewModel() {

    private val _components = MutableStateFlow<List<Pair<ToolchainComponent, ComponentStatus>>>(emptyList())
    val components: StateFlow<List<Pair<ToolchainComponent, ComponentStatus>>> = _components.asStateFlow()

    private val _isDownloadingAll = MutableStateFlow(false)
    val isDownloadingAll: StateFlow<Boolean> = _isDownloadingAll.asStateFlow()

    private val _cacheMessage = MutableStateFlow<String?>(null)
    val cacheMessage: StateFlow<String?> = _cacheMessage.asStateFlow()

    private val _isClearingCache = MutableStateFlow(false)
    val isClearingCache: StateFlow<Boolean> = _isClearingCache.asStateFlow()

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
            // Re-sync UI state with the filesystem. Some components report an error
            // from the last attempted source even when an earlier source produced
            // a valid file on disk — refreshing picks those up as Installed and
            // unblocks the setup flow.
            refreshAll()
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

    /**
     * Clears Gradle's dependency and wrapper caches. Useful after pm clear or when
     * kspClasspath / dependency resolution fails due to a corrupt cache state.
     * Does NOT delete the toolchain (JDK, AAPT2, android.jar) — just the Gradle
     * caches populated during a compile.
     */
    fun clearGradleCache() {
        if (_isClearingCache.value) return
        _isClearingCache.value = true
        _cacheMessage.value = "Clearing Gradle cache..."
        viewModelScope.launch {
            val bytesFreed = withContext(Dispatchers.IO) {
                val gradleHome = File(context.filesDir, "gradle_home")
                if (!gradleHome.exists()) return@withContext 0L
                val before = gradleHome.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                for (sub in listOf("caches", "wrapper", "daemon", "notifications")) {
                    try { File(gradleHome, sub).deleteRecursively() } catch (_: Exception) { }
                }
                val after = gradleHome.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                before - after
            }
            val mb = bytesFreed / 1_048_576.0
            _cacheMessage.value = "Cleared %.1f MB. Next compile will redownload dependencies.".format(mb)
            _isClearingCache.value = false
        }
    }

    fun dismissCacheMessage() {
        _cacheMessage.value = null
    }
}
