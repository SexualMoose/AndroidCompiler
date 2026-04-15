package com.androidcompiler.toolchain.download

import android.content.Context
import com.androidcompiler.core.common.model.ComponentStatus
import com.androidcompiler.core.common.model.ComponentType
import com.androidcompiler.core.common.model.ToolchainComponent
import com.androidcompiler.network.ChunkedDownloader
import com.androidcompiler.toolchain.jdk.TermuxJdkInstaller
import com.androidcompiler.toolchain.registry.ToolchainRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ComponentDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val chunkedDownloader: ChunkedDownloader,
    private val registry: ToolchainRegistry,
    private val termuxJdkInstaller: TermuxJdkInstaller
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
                    ComponentType.JDK_ARCHIVE -> {
                        // Use TermuxJdkInstaller for Bionic-linked JDK
                        val result = termuxJdkInstaller.install(
                            onProgress = onProgress,
                            onLog = { /* logged by caller */ }
                        )
                        result.getOrThrow()
                        return@withContext Result.success(termuxJdkInstaller.jdkDir)
                    }
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

    /**
     * Extracts a .tar.gz archive into the target directory.
     * Uses Android's built-in GZIPInputStream and manual TAR parsing
     * (TAR format is simple enough to parse without a library).
     */
    private fun extractTarGz(tgzFile: File, targetDir: File) {
        targetDir.mkdirs()

        GZIPInputStream(BufferedInputStream(FileInputStream(tgzFile), 65536)).use { gzis ->
            val buffer = ByteArray(512) // TAR block size
            while (true) {
                // Read TAR header (512 bytes)
                val headerRead = readFully(gzis, buffer)
                if (headerRead < 512) break

                // Check for end-of-archive (two consecutive zero blocks)
                if (buffer.all { it == 0.toByte() }) break

                // Parse TAR header
                val name = String(buffer, 0, 100).trim('\u0000', ' ')
                if (name.isEmpty()) break

                val sizeOctal = String(buffer, 124, 12).trim('\u0000', ' ')
                val size = if (sizeOctal.isNotEmpty()) {
                    try { sizeOctal.toLong(8) } catch (_: Exception) { 0L }
                } else 0L

                val typeFlag = buffer[156]

                // Handle USTAR prefix for long paths
                val prefix = String(buffer, 345, 155).trim('\u0000', ' ')
                val fullName = if (prefix.isNotEmpty()) "$prefix/$name" else name

                val outFile = File(targetDir, fullName)

                // Security: prevent tar slip
                if (!outFile.canonicalPath.startsWith(targetDir.canonicalPath)) {
                    skipBytes(gzis, roundUp512(size))
                    continue
                }

                when (typeFlag.toInt().toChar()) {
                    '5', 'D' -> {
                        // Directory
                        outFile.mkdirs()
                    }
                    '0', '\u0000' -> {
                        // Regular file
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { out ->
                            var remaining = size
                            val fileBuf = ByteArray(8192)
                            while (remaining > 0) {
                                val toRead = minOf(remaining, fileBuf.size.toLong()).toInt()
                                val read = gzis.read(fileBuf, 0, toRead)
                                if (read <= 0) break
                                out.write(fileBuf, 0, read)
                                remaining -= read
                            }
                        }
                        // Skip padding to next 512-byte boundary
                        val padding = roundUp512(size) - size
                        if (padding > 0) skipBytes(gzis, padding)
                    }
                    '1', '2' -> {
                        // Hard link or symlink — skip for now
                        skipBytes(gzis, roundUp512(size))
                    }
                    else -> {
                        // Other types (block device, etc) — skip
                        skipBytes(gzis, roundUp512(size))
                    }
                }
            }
        }
    }

    private fun readFully(input: java.io.InputStream, buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read <= 0) return offset
            offset += read
        }
        return offset
    }

    private fun skipBytes(input: java.io.InputStream, count: Long) {
        var remaining = count
        val buf = ByteArray(8192)
        while (remaining > 0) {
            val toSkip = minOf(remaining, buf.size.toLong()).toInt()
            val read = input.read(buf, 0, toSkip)
            if (read <= 0) break
            remaining -= read
        }
    }

    private fun roundUp512(size: Long): Long {
        val remainder = size % 512
        return if (remainder == 0L) size else size + (512 - remainder)
    }

    /**
     * Sets executable permissions on all files in the JDK's bin/ directory
     * and any other executable scripts.
     */
    private fun setJdkPermissions(jdkDir: File) {
        // Find the bin directory (may be nested inside a version-named dir)
        val binDirs = jdkDir.walkTopDown()
            .filter { it.isDirectory && it.name == "bin" }
            .toList()

        for (binDir in binDirs) {
            binDir.listFiles()?.forEach { file ->
                if (file.isFile) {
                    file.setExecutable(true, false)
                    file.setReadable(true, false)
                }
            }
        }

        // Also set permissions on lib/ native files
        val libDirs = jdkDir.walkTopDown()
            .filter { it.isDirectory && it.name == "lib" }
            .toList()

        for (libDir in libDirs) {
            libDir.walkTopDown()
                .filter { it.isFile && (it.extension == "so" || it.name.startsWith("lib")) }
                .forEach { it.setExecutable(true, false) }
        }
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
