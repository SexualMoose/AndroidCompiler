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
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
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
                        if (source.url.startsWith("termux://") && component.id == "aapt2") {
                            // Download ARM64 AAPT2 + deps from Termux repo
                            installTermuxAapt2(targetFile, onProgress)
                            targetFile
                        } else if (source.url.endsWith(".jar") && component.id == "aapt2") {
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

    /**
     * Downloads Termux ARM64 AAPT2 binary and its shared library dependencies.
     * Extracts the aapt2 binary to [targetFile] and .so deps to jdk17/lib.
     */
    private suspend fun installTermuxAapt2(targetFile: File, onProgress: (Float) -> Unit) {
        val repo = "https://packages.termux.dev/apt/termux-main"
        val packages = listOf(
            "$repo/pool/main/a/aapt2/aapt2_13.0.0.6-23_aarch64.deb",
            "$repo/pool/main/a/aapt/aapt_13.0.0.6-23_aarch64.deb",
            "$repo/pool/main/a/abseil-cpp/abseil-cpp_20250814.1_aarch64.deb",
            "$repo/pool/main/libp/libprotobuf/libprotobuf_2%3A33.1-1_aarch64.deb",
            "$repo/pool/main/f/fmt/fmt_1%3A11.2.0_aarch64.deb",
            "$repo/pool/main/libe/libexpat/libexpat_2.7.5_aarch64.deb",
            "$repo/pool/main/libp/libpng/libpng_1.6.57_aarch64.deb",
            "$repo/pool/main/libz/libzopfli/libzopfli_1.0.3-5_aarch64.deb",
        )

        // Lib dir where .so files go (same as JDK native libs so they're on LD_LIBRARY_PATH)
        val libDir = File(registry.getJdkDir(), "lib")
        libDir.mkdirs()

        val total = packages.size
        for ((index, url) in packages.withIndex()) {
            val debFile = File(context.cacheDir, "aapt2_pkg_$index.deb")
            try {
                val result = chunkedDownloader.download(
                    url = url,
                    outputFile = debFile,
                    onProgress = { _, _ ->
                        onProgress((index.toFloat() + 0.5f) / total)
                    }
                )
                result.getOrThrow()
                extractDebForAapt2(debFile, targetFile, libDir)
            } finally {
                debFile.delete()
            }
            onProgress((index + 1).toFloat() / total)
        }

        targetFile.setExecutable(true, false)
        targetFile.setReadable(true, false)
    }

    /**
     * Extracts a Termux .deb file, placing the aapt2 binary at [aapt2Target]
     * and all .so files into [libDir].
     */
    private fun extractDebForAapt2(debFile: File, aapt2Target: File, libDir: File) {
        FileInputStream(debFile).buffered().use { input ->
            val magic = ByteArray(8)
            input.read(magic)
            if (!String(magic).startsWith("!<arch>")) return

            while (input.available() > 0) {
                val header = ByteArray(60)
                val read = readFully(input, header)
                if (read < 60) break

                val name = String(header, 0, 16).trim()
                val sizeStr = String(header, 48, 10).trim()
                val size = sizeStr.toLongOrNull() ?: 0

                if (name.startsWith("data.tar")) {
                    val dataFile = File(context.cacheDir, "aapt2_data.tar.xz")
                    FileOutputStream(dataFile).use { out ->
                        copyBytes(input, out, size)
                    }

                    // Decompress XZ and extract
                    XZInputStream(BufferedInputStream(FileInputStream(dataFile), 65536)).use { xzInput ->
                        extractTarForAapt2(xzInput, aapt2Target, libDir)
                    }
                    dataFile.delete()
                    return
                } else {
                    skipBytes(input, size)
                }
                if (size % 2 != 0L) input.read()
            }
        }
    }

    /**
     * Extracts TAR entries, placing bin/aapt2 at [aapt2Target] and .so files in [libDir].
     * Handles GNU TAR long names (type 'L').
     */
    private fun extractTarForAapt2(
        input: java.io.InputStream,
        aapt2Target: File,
        libDir: File
    ) {
        val header = ByteArray(512)
        val termuxPrefix = "data/data/com.termux/files/usr"
        var pendingLongName: String? = null

        while (true) {
            val headerRead = readFully(input, header)
            if (headerRead < 512) break
            if (header.all { it == 0.toByte() }) break

            val rawName = String(header, 0, 100).trim('\u0000', ' ')
            val sizeOctal = String(header, 124, 12).trim('\u0000', ' ')
            val size = if (sizeOctal.isNotEmpty()) {
                try { sizeOctal.toLong(8) } catch (_: Exception) { 0L }
            } else 0L
            val typeFlag = header[156]

            // GNU TAR long name
            if (typeFlag.toInt().toChar() == 'L') {
                val nameBytes = ByteArray(size.toInt())
                readFully(input, nameBytes)
                pendingLongName = String(nameBytes).trim('\u0000')
                val padding = roundUp512(size) - size
                if (padding > 0) skipBytes(input, padding)
                continue
            }

            var fullName = if (pendingLongName != null) {
                val n = pendingLongName!!; pendingLongName = null; n
            } else {
                val prefix = String(header, 345, 155).trim('\u0000', ' ')
                if (prefix.isNotEmpty()) "$prefix/$rawName" else rawName
            }

            fullName = fullName.removePrefix("./")
            if (fullName.startsWith(termuxPrefix)) {
                fullName = fullName.removePrefix(termuxPrefix).removePrefix("/")
            }

            when (typeFlag.toInt().toChar()) {
                '0', '\u0000' -> {
                    val fileName = File(fullName).name
                    when {
                        fullName == "bin/aapt2" -> {
                            aapt2Target.parentFile?.mkdirs()
                            FileOutputStream(aapt2Target).use { out ->
                                copyBytes(input, out, size)
                            }
                            val padding = roundUp512(size) - size
                            if (padding > 0) skipBytes(input, padding)
                        }
                        // Match .so files including versioned names like libexpat.so.1.11.3
                        ".so" in fileName && fullName.startsWith("lib/") -> {
                            val outFile = File(libDir, fileName)
                            FileOutputStream(outFile).use { out ->
                                copyBytes(input, out, size)
                            }
                            outFile.setExecutable(true, false)
                            val padding = roundUp512(size) - size
                            if (padding > 0) skipBytes(input, padding)
                        }
                        else -> {
                            skipBytes(input, roundUp512(size))
                        }
                    }
                }
                '2' -> {
                    // Symlink — create for versioned .so files (e.g., libexpat.so.1 -> libexpat.so.1.11.3)
                    val linkTarget = String(header, 157, 100).trim('\u0000', ' ')
                    val fileName = File(fullName).name
                    if (".so" in fileName && fullName.startsWith("lib/")) {
                        val outFile = File(libDir, fileName)
                        if (outFile.exists()) outFile.delete()
                        try {
                            val osClass = Class.forName("android.system.Os")
                            osClass.getMethod("symlink", String::class.java, String::class.java)
                                .invoke(null, linkTarget, outFile.absolutePath)
                        } catch (_: Exception) { }
                    }
                    skipBytes(input, roundUp512(size))
                }
                else -> skipBytes(input, roundUp512(size))
            }
        }
    }

    private fun copyBytes(input: java.io.InputStream, output: java.io.OutputStream, count: Long) {
        var remaining = count
        val buf = ByteArray(8192)
        while (remaining > 0) {
            val toRead = minOf(remaining, buf.size.toLong()).toInt()
            val read = input.read(buf, 0, toRead)
            if (read <= 0) break
            output.write(buf, 0, read)
            remaining -= read
        }
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
