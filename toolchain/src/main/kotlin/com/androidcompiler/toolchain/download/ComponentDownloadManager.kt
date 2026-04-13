package com.androidcompiler.toolchain.download

import android.content.Context
import com.androidcompiler.core.common.model.ComponentStatus
import com.androidcompiler.core.common.model.ComponentType
import com.androidcompiler.core.common.model.ToolchainComponent
import com.androidcompiler.network.ChunkedDownloader
import com.androidcompiler.toolchain.registry.ToolchainRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ComponentDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val chunkedDownloader: ChunkedDownloader,
    private val registry: ToolchainRegistry
) {
    suspend fun downloadComponent(
        component: ToolchainComponent,
        onProgress: (Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val targetFile = registry.getComponentFile(component)
        targetFile.parentFile?.mkdirs()

        // Try each source in priority order
        val sortedSources = component.sources.sortedBy { it.priority }
        var lastError: Exception? = null

        for (source in sortedSources) {
            try {
                val downloadFile = when (component.type) {
                    ComponentType.SDK_PLATFORM -> {
                        // SDK platform comes as a ZIP, download to temp then extract android.jar
                        val tempZip = File(context.cacheDir, "${component.id}_temp.zip")
                        val result = chunkedDownloader.download(
                            url = source.url,
                            outputFile = tempZip,
                            onProgress = { downloaded, total ->
                                if (total > 0) onProgress(downloaded.toFloat() / total)
                            }
                        )
                        result.getOrThrow()
                        extractAndroidJar(tempZip, targetFile)
                        tempZip.delete()
                        targetFile
                    }
                    ComponentType.NATIVE_BINARY -> {
                        // AAPT2 from Google Maven comes as a JAR containing the binary
                        if (source.url.endsWith(".jar") && component.id == "aapt2") {
                            val tempJar = File(context.cacheDir, "${component.id}_temp.jar")
                            val result = chunkedDownloader.download(
                                url = source.url,
                                outputFile = tempJar,
                                onProgress = { downloaded, total ->
                                    if (total > 0) onProgress(downloaded.toFloat() / total)
                                }
                            )
                            result.getOrThrow()
                            extractAapt2FromJar(tempJar, targetFile)
                            tempJar.delete()
                            targetFile
                        } else {
                            val result = chunkedDownloader.download(
                                url = source.url,
                                outputFile = targetFile,
                                onProgress = { downloaded, total ->
                                    if (total > 0) onProgress(downloaded.toFloat() / total)
                                }
                            )
                            result.getOrThrow()
                        }
                    }
                    ComponentType.JAR -> {
                        val result = chunkedDownloader.download(
                            url = source.url,
                            outputFile = targetFile,
                            onProgress = { downloaded, total ->
                                if (total > 0) onProgress(downloaded.toFloat() / total)
                            }
                        )
                        result.getOrThrow()
                    }
                }

                // Set execute permission for native binaries
                if (component.type == ComponentType.NATIVE_BINARY) {
                    targetFile.setExecutable(true, false)
                }

                return@withContext Result.success(targetFile)
            } catch (e: Exception) {
                lastError = e
                // Try next source
            }
        }

        Result.failure(lastError ?: Exception("No download sources available for ${component.displayName}"))
    }

    private fun extractAndroidJar(zipFile: File, targetFile: File) {
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name.endsWith("android.jar")) {
                    targetFile.outputStream().use { out ->
                        zis.copyTo(out)
                    }
                    return
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        throw Exception("android.jar not found in SDK platform ZIP")
    }

    private fun extractAapt2FromJar(jarFile: File, targetFile: File) {
        ZipInputStream(jarFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                // The AAPT2 JAR contains the binary at the root or under a platform dir
                if (entry.name.contains("aapt2") && !entry.isDirectory && !entry.name.endsWith(".jar")) {
                    targetFile.outputStream().use { out ->
                        zis.copyTo(out)
                    }
                    return
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        throw Exception("aapt2 binary not found in JAR")
    }

    suspend fun downloadAll(
        onComponentProgress: (componentId: String, progress: Float) -> Unit,
        onComponentComplete: (componentId: String, success: Boolean, error: String?) -> Unit
    ) = withContext(Dispatchers.IO) {
        val components = registry.getComponents()
        for (component in components) {
            if (registry.getComponentStatus(component) is ComponentStatus.Installed) {
                onComponentComplete(component.id, true, null)
                continue
            }
            val result = downloadComponent(component) { progress ->
                onComponentProgress(component.id, progress)
            }
            if (result.isSuccess) {
                onComponentComplete(component.id, true, null)
            } else {
                onComponentComplete(component.id, false, result.exceptionOrNull()?.message)
            }
        }
    }
}
