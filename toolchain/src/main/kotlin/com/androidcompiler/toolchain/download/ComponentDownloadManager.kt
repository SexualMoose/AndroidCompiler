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
     *
     * Installs into a Termux-like prefix directory:
     *   toolchain/aapt2-prefix/bin/aapt2   (binary)
     *   toolchain/aapt2-prefix/lib/         (shared libs)
     *
     * This layout matches the AAPT2 binary's ELF RPATH ($ORIGIN/../lib),
     * so shared libraries are found automatically without LD_LIBRARY_PATH.
     * We still set LD_LIBRARY_PATH as a fallback for robustness.
     */
    private suspend fun installTermuxAapt2(targetFile: File, onProgress: (Float) -> Unit) {
        val repo = "https://packages.termux.dev/apt/termux-main"
        val arch = TermuxJdkInstaller.termuxArch()
        // Packages we need for AAPT2. Version numbers change over time so we
        // resolve the current Filename from the Termux Packages index rather
        // than hardcoding — a stale version means 404 and a silent extraction
        // failure that leaves AAPT2 unable to load its .so deps.
        val neededPackages = listOf(
            "aapt2", "aapt", "abseil-cpp", "libprotobuf", "fmt",
            "libexpat", "libpng", "libzopfli", "zlib", "libc++"
        )
        val packages = resolvePackageUrls(repo, arch, neededPackages)

        // Lib dir in the AAPT2 prefix — matches RPATH ($ORIGIN/../lib from bin/)
        val libDir = registry.getAapt2LibDir()
        libDir.mkdirs()
        targetFile.parentFile?.mkdirs()

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

        // Copy libc++_shared.so from JDK — AAPT2 needs it but it's not in its own packages.
        // The JDK installer downloads it as part of the libc++ Termux package.
        val jdkLibDir = File(registry.getJdkDir(), "lib")
        val libcpp = File(jdkLibDir, "libc++_shared.so")
        if (libcpp.exists()) {
            val destLibcpp = File(libDir, "libc++_shared.so")
            if (!destLibcpp.exists()) {
                libcpp.copyTo(destLibcpp)
                destLibcpp.setExecutable(true, false)
                destLibcpp.setReadable(true, false)
            }
        }

        // Verify symlinks and fix any that are broken
        fixBrokenSymlinks(libDir)
    }

    /**
     * Looks up current download URLs for a set of Termux package names by
     * parsing the repo's Packages index. Falls back to a guessed URL pattern
     * if the index lookup fails — that covers offline/proxy scenarios but will
     * 404 if Termux rolled a new version since the fallback pattern was pinned.
     */
    private fun resolvePackageUrls(repo: String, arch: String, packageNames: List<String>): List<String> {
        val indexUrl = "$repo/dists/stable/main/binary-$arch/Packages"
        val indexContent = try {
            java.net.URL(indexUrl).openStream().bufferedReader().use { it.readText() }
        } catch (_: Exception) { "" }

        val filenameByPkg = mutableMapOf<String, String>()
        if (indexContent.isNotEmpty()) {
            var currentPkg = ""
            for (line in indexContent.lines()) {
                when {
                    line.startsWith("Package: ") ->
                        currentPkg = line.removePrefix("Package: ").trim()
                    line.startsWith("Filename: ") ->
                        filenameByPkg[currentPkg] = line.removePrefix("Filename: ").trim()
                }
            }
        }

        return packageNames.map { pkg ->
            val filename = filenameByPkg[pkg]
            if (filename != null) "$repo/$filename" else fallbackTermuxUrl(repo, arch, pkg)
        }
    }

    /**
     * Last-resort URL guess when the Packages index can't be fetched. These
     * versions will go stale over time — the index lookup is the primary path.
     */
    private fun fallbackTermuxUrl(repo: String, arch: String, pkg: String): String = when (pkg) {
        "aapt2" -> "$repo/pool/main/a/aapt2/aapt2_13.0.0.6-23_$arch.deb"
        "aapt" -> "$repo/pool/main/a/aapt/aapt_13.0.0.6-23_$arch.deb"
        "abseil-cpp" -> "$repo/pool/main/a/abseil-cpp/abseil-cpp_20250814.1_$arch.deb"
        "libprotobuf" -> "$repo/pool/main/libp/libprotobuf/libprotobuf_2%3A33.1-1_$arch.deb"
        "fmt" -> "$repo/pool/main/f/fmt/fmt_1%3A11.2.0_$arch.deb"
        "libexpat" -> "$repo/pool/main/libe/libexpat/libexpat_2.7.5_$arch.deb"
        "libpng" -> "$repo/pool/main/libp/libpng/libpng_1.6.58_$arch.deb"
        "libzopfli" -> "$repo/pool/main/libz/libzopfli/libzopfli_1.0.3-5_$arch.deb"
        "zlib" -> "$repo/pool/main/z/zlib/zlib_1.3.2_$arch.deb"
        "libc++" -> "$repo/pool/main/libc/libc++/libc++_29_$arch.deb"
        else -> "$repo/pool/main/${pkg.first()}/$pkg/${pkg}_unknown_$arch.deb"
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
                            // A previous compile may have replaced this path with
                            // a symlink into nativeLibraryDir. After reinstall the
                            // symlink dangles and FileOutputStream fails trying
                            // to create the non-existent target. Remove first.
                            unlinkIfSymlink(aapt2Target)
                            FileOutputStream(aapt2Target).use { out ->
                                copyBytes(input, out, size)
                            }
                            val padding = roundUp512(size) - size
                            if (padding > 0) skipBytes(input, padding)
                        }
                        // Match .so files including versioned names like libexpat.so.1.11.3
                        ".so" in fileName && fullName.startsWith("lib/") -> {
                            val outFile = File(libDir, fileName)
                            unlinkIfSymlink(outFile)
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
                    val rawLinkTarget = String(header, 157, 100).trim('\u0000', ' ')
                    val fileName = File(fullName).name
                    if (".so" in fileName && fullName.startsWith("lib/")) {
                        val outFile = File(libDir, fileName)
                        // Strip any path from link target — use just the basename.
                        // Termux TAR may store targets as relative paths like
                        // "libexpat.so.1.11.3" or absolute Termux paths. We only
                        // need the filename since source and target are in the same dir.
                        val linkTargetName = File(rawLinkTarget).name
                        if (outFile.exists()) outFile.delete()
                        try {
                            val osClass = Class.forName("android.system.Os")
                            osClass.getMethod("symlink", String::class.java, String::class.java)
                                .invoke(null, linkTargetName, outFile.absolutePath)
                        } catch (_: Exception) {
                            // Fallback: copy the target file if symlink creation fails
                            val targetFile = File(libDir, linkTargetName)
                            if (targetFile.exists() && !outFile.exists()) {
                                try {
                                    targetFile.copyTo(outFile)
                                    outFile.setExecutable(true, false)
                                } catch (_: Exception) { }
                            }
                        }
                    }
                    skipBytes(input, roundUp512(size))
                }
                else -> skipBytes(input, roundUp512(size))
            }
        }
    }

    /**
     * Scans [libDir] for broken symlinks and replaces them with copies of
     * the target file. Also ensures all .so files have execute permission.
     */
    private fun fixBrokenSymlinks(libDir: File) {
        val files = libDir.listFiles() ?: return
        for (file in files) {
            try {
                // Check if this is a symlink
                val osClass = Class.forName("android.system.Os")
                val lstatMethod = osClass.getMethod("lstat", String::class.java)
                val stat = lstatMethod.invoke(null, file.absolutePath)
                val modeField = stat.javaClass.getField("st_mode")
                val mode = modeField.getInt(stat)
                val S_IFLNK = 0xA000
                val isSymlink = (mode and 0xF000) == S_IFLNK

                if (isSymlink) {
                    val readlinkMethod = osClass.getMethod("readlink", String::class.java)
                    val target = readlinkMethod.invoke(null, file.absolutePath) as String

                    // Resolve the target relative to the symlink's directory
                    val resolvedTarget = if (File(target).isAbsolute) {
                        File(target)
                    } else {
                        File(file.parentFile, target)
                    }

                    if (!resolvedTarget.exists()) {
                        // Broken symlink — try to find the target by name in the same dir
                        val targetName = File(target).name
                        val localTarget = File(libDir, targetName)
                        file.delete()
                        if (localTarget.exists()) {
                            // Re-create symlink with just the filename
                            try {
                                osClass.getMethod("symlink", String::class.java, String::class.java)
                                    .invoke(null, targetName, file.absolutePath)
                            } catch (_: Exception) {
                                // Last resort: copy the file
                                localTarget.copyTo(file)
                                file.setExecutable(true, false)
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // If we can't check symlinks, just ensure permissions
            }
            if (file.exists()) {
                file.setExecutable(true, false)
                file.setReadable(true, false)
            }
        }
    }

    /**
     * Removes a path if it's a symlink (possibly dangling). Regular files are
     * left alone — FileOutputStream will truncate them. Dangling symlinks would
     * cause FileOutputStream to try to create a file at the non-existent target,
     * which throws because the app doesn't have write access to /data/app/.
     */
    private fun unlinkIfSymlink(file: File) {
        try {
            val osClass = Class.forName("android.system.Os")
            val stat = osClass.getMethod("lstat", String::class.java)
                .invoke(null, file.absolutePath)
            val mode = stat.javaClass.getField("st_mode").getInt(stat)
            val isSymlink = (mode and 0xF000) == 0xA000
            if (isSymlink) file.delete()
        } catch (_: Exception) { /* entry doesn't exist — fine, nothing to unlink */ }
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
        // Validate this is actually a ZIP — a 404 HTML response would silently
        // pass through ZipInputStream as "no entries" and confuse the user.
        FileInputStream(zipFile).use { input ->
            val magic = ByteArray(4)
            if (input.read(magic) != 4) {
                throw Exception("Downloaded file is empty or too small to be a ZIP (${zipFile.length()} bytes)")
            }
            // Local file header magic: 0x04034b50 (little-endian) → "PK\x03\x04"
            // Empty ZIP can also start with central directory magic "PK\x05\x06"
            val isZip = magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte() &&
                    (magic[2] == 0x03.toByte() || magic[2] == 0x05.toByte() || magic[2] == 0x07.toByte())
            if (!isZip) {
                val preview = magic.joinToString(" ") { "0x%02X".format(it) }
                throw Exception(
                    "Downloaded file is not a valid ZIP (magic bytes: $preview). " +
                    "This usually means the URL returned an error page instead of the file."
                )
            }
        }

        // Extract android.jar and core-for-system-modules.jar from the platform ZIP
        val coreModulesTarget = File(targetFile.parentFile, "core-for-system-modules.jar")
        var foundAndroidJar = false

        try {
            ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    when {
                        entry.name.endsWith("android.jar") -> {
                            targetFile.outputStream().use { out -> zis.copyTo(out) }
                            foundAndroidJar = true
                        }
                        entry.name.endsWith("core-for-system-modules.jar") -> {
                            coreModulesTarget.outputStream().use { out -> zis.copyTo(out) }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } catch (e: Exception) {
            // Clean up any partial writes so the next retry starts fresh
            if (targetFile.exists() && !foundAndroidJar) targetFile.delete()
            throw e
        }

        if (!foundAndroidJar) {
            // Delete whatever partial file may have been produced
            if (targetFile.exists()) targetFile.delete()
            throw Exception("android.jar not found in SDK platform ZIP")
        }
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

    /**
     * Downloads the android.jar (and core-for-system-modules.jar) for a specific
     * API level into the android-jar-variants directory. Returns Result.success
     * with the android.jar file.
     */
    suspend fun downloadAndroidJarForApi(
        apiLevel: Int,
        onProgress: (Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val target = registry.androidJarForApi(apiLevel)
        if (target.exists() && target.length() > 0) return@withContext Result.success(target)

        val url = try { registry.platformZipUrl(apiLevel) }
        catch (e: Exception) { return@withContext Result.failure(e) }

        val tempZip = File(context.cacheDir, "platform-${apiLevel}.zip")
        try {
            val result = chunkedDownloader.download(
                url = url,
                outputFile = tempZip,
                onProgress = { d, t -> if (t > 0) onProgress(d.toFloat() / t) }
            )
            result.getOrThrow()
            // Extract android.jar and core-for-system-modules.jar to variant dir
            target.parentFile?.mkdirs()
            val coreTarget = registry.coreForSystemModulesForApi(apiLevel)
            extractAndroidJarToVariantDir(tempZip, target, coreTarget)
            Result.success(target)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            tempZip.delete()
        }
    }

    /**
     * Downloads a specific Gradle distribution zip into gradle-dists/ and extracts
     * gradle-wrapper.jar for that version.
     */
    suspend fun downloadGradleDistribution(
        version: String,
        onProgress: (Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val target = registry.gradleDistZip(version)
        if (target.exists() && target.length() > 0) return@withContext Result.success(target)

        val url = registry.gradleDistributionUrl(version)
        target.parentFile?.mkdirs()
        try {
            val result = chunkedDownloader.download(
                url = url,
                outputFile = target,
                onProgress = { d, t -> if (t > 0) onProgress(d.toFloat() / t) }
            )
            result.getOrThrow()
            extractGradleWrapperJar(target, registry.gradleWrapperJarFor(version))
            Result.success(target)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Extracts android.jar and core-for-system-modules.jar from a platform ZIP. */
    private fun extractAndroidJarToVariantDir(zipFile: File, androidJarOut: File, coreJarOut: File) {
        FileInputStream(zipFile).use { input ->
            val magic = ByteArray(4)
            if (input.read(magic) != 4 ||
                magic[0] != 0x50.toByte() || magic[1] != 0x4B.toByte()) {
                throw Exception("Downloaded file is not a valid ZIP (magic check failed)")
            }
        }
        var foundAndroidJar = false
        try {
            ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    when {
                        entry.name.endsWith("android.jar") -> {
                            androidJarOut.outputStream().use { out -> zis.copyTo(out) }
                            foundAndroidJar = true
                        }
                        entry.name.endsWith("core-for-system-modules.jar") -> {
                            coreJarOut.outputStream().use { out -> zis.copyTo(out) }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } catch (e: Exception) {
            if (androidJarOut.exists() && !foundAndroidJar) androidJarOut.delete()
            throw e
        }
        if (!foundAndroidJar) {
            if (androidJarOut.exists()) androidJarOut.delete()
            throw Exception("android.jar not found in platform ZIP for API")
        }
    }

    /** Extracts gradle-wrapper.jar from a gradle-X.X-bin.zip distribution. */
    private fun extractGradleWrapperJar(distZip: File, wrapperJarOut: File) {
        ZipInputStream(distZip.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name.endsWith("/lib/gradle-wrapper.jar") ||
                    entry.name.endsWith("/lib/plugins/gradle-wrapper-main-plugin.jar") ||
                    entry.name.endsWith("gradle-wrapper.jar")) {
                    wrapperJarOut.parentFile?.mkdirs()
                    wrapperJarOut.outputStream().use { out -> zis.copyTo(out) }
                    return
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        // Gradle distributions don't always ship gradle-wrapper.jar directly;
        // we fall back to the bundled default wrapper if this fails. Not a hard error.
    }
}
