package com.androidcompiler.toolchain.jdk

import android.content.Context
import com.androidcompiler.network.ChunkedDownloader
import com.androidcompiler.toolchain.registry.ToolchainRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads and installs OpenJDK 17 from Termux's package repository.
 *
 * Termux packages are Bionic-linked (Android's libc), so the java binary
 * runs natively on Android without Termux itself being installed.
 *
 * Process:
 * 1. Fetch Termux Packages index to find current openjdk-17 URL
 * 2. Download the .deb file (ar archive format)
 * 3. Extract data.tar.xz from the .deb
 * 4. Decompress XZ → TAR
 * 5. Extract TAR to the JDK directory
 * 6. Set execute permissions on all binaries
 */
@Singleton
class TermuxJdkInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val chunkedDownloader: ChunkedDownloader,
    private val registry: ToolchainRegistry
) {
    companion object {
        private const val TERMUX_REPO = "https://packages.termux.dev/apt/termux-main"
        private const val PACKAGES_INDEX = "$TERMUX_REPO/dists/stable/main/binary-aarch64/Packages"
        private const val TERMUX_PREFIX = "data/data/com.termux/files/usr"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    val jdkDir: File get() = File(registry.toolchainDir, "jdk17")

    fun isInstalled(): Boolean {
        val javaHome = getJavaHome() ?: return false
        val javaBin = File(javaHome, "bin/java")
        val libDir = File(javaHome, "lib")
        // Must have java binary AND lib directory (with modules/libjvm.so)
        return javaBin.exists() && libDir.exists() && libDir.listFiles()?.isNotEmpty() == true
    }

    fun getJavaHome(): File? {
        // Direct: jdk17/bin/java
        if (File(jdkDir, "bin/java").exists()) return jdkDir
        // Nested: jdk17/lib/jvm/java-17-openjdk/bin/java
        val jvmDir = File(jdkDir, "lib/jvm")
        if (jvmDir.exists()) {
            jvmDir.listFiles()?.forEach { child ->
                if (File(child, "bin/java").exists()) return child
            }
        }
        // Single subdirectory
        jdkDir.listFiles()?.filter { it.isDirectory }?.forEach { child ->
            if (File(child, "bin/java").exists()) return child
        }
        return null
    }

    /**
     * Download and install JDK from Termux repo.
     */
    suspend fun install(
        onProgress: (Float) -> Unit,
        onLog: (String) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            onLog("Fetching Termux package index...")
            val packages = findPackages(onLog)
            if (packages.isEmpty()) {
                return@withContext Result.failure(Exception("Could not find openjdk-17 in Termux repo"))
            }

            onLog("Found ${packages.size} package(s) to download")

            jdkDir.mkdirs()
            val totalPackages = packages.size
            var completed = 0

            for (pkg in packages) {
                onLog("Downloading: ${pkg.name} (${pkg.sizeBytes / 1024} KB)")
                val debFile = File(context.cacheDir, "${pkg.name}.deb")

                val dlResult = chunkedDownloader.download(
                    url = pkg.url,
                    outputFile = debFile,
                    onProgress = { downloaded, total ->
                        val pkgProgress = if (total > 0) downloaded.toFloat() / total else 0f
                        val overallProgress = (completed + pkgProgress) / totalPackages
                        onProgress(overallProgress)
                    }
                )

                if (dlResult.isFailure) {
                    debFile.delete()
                    return@withContext Result.failure(
                        Exception("Failed to download ${pkg.name}: ${dlResult.exceptionOrNull()?.message}")
                    )
                }

                onLog("Extracting: ${pkg.name}")
                extractDeb(debFile, jdkDir)
                debFile.delete()
                completed++
                onProgress(completed.toFloat() / totalPackages)
            }

            // Set permissions
            onLog("Setting permissions...")
            setExecutePermissions(jdkDir)

            // Verify — find java binary in the extracted tree
            val javaHome = getJavaHome()
            val javaBin = javaHome?.let { File(it, "bin/java") }
            if (javaBin == null || !javaBin.exists()) {
                return@withContext Result.failure(
                    Exception("Installation completed but java binary not found. " +
                        "Searched in: ${jdkDir.absolutePath}")
                )
            }
            javaBin.setExecutable(true, false)

            onLog("JDK installed: ${javaHome.absolutePath}")
            registry.saveInstalledVersion("jdk", "17")
            Result.success(jdkDir)
        } catch (e: Exception) {
            Result.failure(Exception("JDK installation failed: ${e.message}", e))
        }
    }

    /**
     * Find the openjdk-17 package URL from Termux's Packages index.
     */
    private fun findPackages(onLog: (String) -> Unit): List<PackageInfo> {
        val packages = mutableListOf<PackageInfo>()

        // Try to fetch and parse the Packages index
        try {
            val request = Request.Builder().url(PACKAGES_INDEX).build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            response.close()

            // Parse the Debian Packages file format
            val entries = parsePackagesIndex(body)

            // Find openjdk-17 (the meta-package that pulls in the JDK)
            val jdkPkg = entries["openjdk-17"] ?: entries["openjdk-17-jdk"]
            if (jdkPkg != null) {
                packages.add(jdkPkg)
                // Add direct dependencies
                jdkPkg.depends.forEach { dep ->
                    val depPkg = entries[dep]
                    if (depPkg != null) {
                        packages.add(depPkg)
                        // One level of transitive deps
                        depPkg.depends.forEach { transDep ->
                            entries[transDep]?.let { packages.add(it) }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            onLog("Failed to fetch package index: ${e.message}")
        }

        // If index parsing failed, try known direct URLs
        if (packages.isEmpty()) {
            onLog("Using fallback package URLs")
            packages.addAll(getFallbackPackages())
        }

        // Ensure critical packages are always present (libc++ is needed by
        // libandroid-spawn but may be missed in transitive dep resolution)
        val requiredPackages = listOf("libc++")
        val fallbackPkgs = getFallbackPackages()
        for (required in requiredPackages) {
            if (packages.none { it.name == required }) {
                val fallback = fallbackPkgs.firstOrNull { it.name == required }
                if (fallback != null) {
                    onLog("Adding required package: $required")
                    packages.add(fallback)
                }
            }
        }

        // Deduplicate
        return packages.distinctBy { it.name }
    }

    private fun parsePackagesIndex(content: String): Map<String, PackageInfo> {
        val entries = mutableMapOf<String, PackageInfo>()
        var currentName = ""
        var currentFilename = ""
        var currentSize = 0L
        var currentDepends = listOf<String>()

        for (line in content.lines()) {
            when {
                line.startsWith("Package: ") -> {
                    // Save previous entry
                    if (currentName.isNotEmpty() && currentFilename.isNotEmpty()) {
                        entries[currentName] = PackageInfo(
                            currentName,
                            "$TERMUX_REPO/$currentFilename",
                            currentSize,
                            currentDepends
                        )
                    }
                    currentName = line.removePrefix("Package: ").trim()
                    currentFilename = ""
                    currentSize = 0
                    currentDepends = emptyList()
                }
                line.startsWith("Filename: ") -> {
                    currentFilename = line.removePrefix("Filename: ").trim()
                }
                line.startsWith("Size: ") -> {
                    currentSize = line.removePrefix("Size: ").trim().toLongOrNull() ?: 0
                }
                line.startsWith("Depends: ") -> {
                    currentDepends = line.removePrefix("Depends: ").trim()
                        .split(",")
                        .map { it.trim().split(" ")[0].split("(")[0].trim() }
                        .filter { it.isNotEmpty() }
                }
            }
        }
        // Save last entry
        if (currentName.isNotEmpty() && currentFilename.isNotEmpty()) {
            entries[currentName] = PackageInfo(
                currentName, "$TERMUX_REPO/$currentFilename",
                currentSize, currentDepends
            )
        }
        return entries
    }

    private fun getFallbackPackages(): List<PackageInfo> {
        return listOf(
            PackageInfo("openjdk-17", "$TERMUX_REPO/pool/main/o/openjdk-17/openjdk-17_17.0.18_aarch64.deb", 95_507_372, emptyList()),
            PackageInfo("libandroid-shmem", "$TERMUX_REPO/pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb", 7_216, emptyList()),
            PackageInfo("libandroid-spawn", "$TERMUX_REPO/pool/main/liba/libandroid-spawn/libandroid-spawn_0.3_aarch64.deb", 15_216, emptyList()),
            PackageInfo("libiconv", "$TERMUX_REPO/pool/main/libi/libiconv/libiconv_1.18-1_aarch64.deb", 561_152, emptyList()),
            PackageInfo("libjpeg-turbo", "$TERMUX_REPO/pool/main/libj/libjpeg-turbo/libjpeg-turbo_3.1.4.1_aarch64.deb", 384_504, emptyList()),
            PackageInfo("littlecms", "$TERMUX_REPO/pool/main/l/littlecms/littlecms_2.18_aarch64.deb", 142_676, emptyList()),
            PackageInfo("zlib", "$TERMUX_REPO/pool/main/z/zlib/zlib_1.3.2_aarch64.deb", 62_840, emptyList()),
            PackageInfo("libc++", "$TERMUX_REPO/pool/main/libc/libc++/libc++_29_aarch64.deb", 334_828, emptyList()),
        )
    }

    /**
     * Extract a .deb file. Format: ar archive containing data.tar.xz
     */
    private fun extractDeb(debFile: File, targetDir: File) {
        FileInputStream(debFile).buffered().use { input ->
            // Read ar magic: "!<arch>\n"
            val magic = ByteArray(8)
            input.read(magic)
            val magicStr = String(magic)
            if (!magicStr.startsWith("!<arch>")) {
                throw Exception("Not a valid .deb file (bad ar magic: $magicStr)")
            }

            // Read ar entries
            while (input.available() > 0) {
                val header = ByteArray(60)
                val read = readFully(input, header)
                if (read < 60) break

                val name = String(header, 0, 16).trim()
                val sizeStr = String(header, 48, 10).trim()
                val size = sizeStr.toLongOrNull() ?: 0

                if (name.startsWith("data.tar")) {
                    // This is the file data — extract it
                    val dataFile = File(context.cacheDir, "data.tar.xz")
                    FileOutputStream(dataFile).use { out ->
                        copyBytes(input, out, size)
                    }

                    // Decompress XZ and extract TAR
                    extractTarXz(dataFile, targetDir)
                    dataFile.delete()
                    return
                } else {
                    // Skip this entry
                    skipBytes(input, size)
                }

                // ar entries are 2-byte aligned
                if (size % 2 != 0L) {
                    input.read() // padding byte
                }
            }
        }
        throw Exception("data.tar.xz not found in .deb archive")
    }

    /**
     * Extract a .tar.xz archive.
     */
    private fun extractTarXz(xzFile: File, targetDir: File) {
        XZInputStream(BufferedInputStream(FileInputStream(xzFile), 65536)).use { xzInput ->
            extractTar(xzInput, targetDir)
        }
    }

    /**
     * Extract TAR archive entries.
     * Strips the Termux prefix (data/data/com.termux/files/usr/) to get clean paths.
     *
     * Handles GNU TAR long name extensions (././@LongLink with type 'L' and 'K')
     * which are used when paths exceed the 100-byte name field limit.
     */
    private fun extractTar(input: java.io.InputStream, targetDir: File) {
        val header = ByteArray(512)
        var pendingLongName: String? = null
        var pendingLongLink: String? = null

        while (true) {
            val headerRead = readFully(input, header)
            if (headerRead < 512) break
            if (header.all { it == 0.toByte() }) break

            val rawName = String(header, 0, 100).trim('\u0000', ' ')
            if (rawName.isEmpty() && pendingLongName == null) break

            val sizeOctal = String(header, 124, 12).trim('\u0000', ' ')
            val size = if (sizeOctal.isNotEmpty()) {
                try { sizeOctal.toLong(8) } catch (_: Exception) { 0L }
            } else 0L

            val typeFlag = header[156]

            // GNU TAR long name: type 'L' means the data is the full filename
            // for the NEXT entry (used when paths > 100 chars)
            if (typeFlag.toInt().toChar() == 'L') {
                val nameBytes = ByteArray(size.toInt())
                readFully(input, nameBytes)
                pendingLongName = String(nameBytes).trim('\u0000')
                val padding = roundUp512(size) - size
                if (padding > 0) skipBytes(input, padding)
                continue
            }

            // GNU TAR long link target: type 'K'
            if (typeFlag.toInt().toChar() == 'K') {
                val linkBytes = ByteArray(size.toInt())
                readFully(input, linkBytes)
                pendingLongLink = String(linkBytes).trim('\u0000')
                val padding = roundUp512(size) - size
                if (padding > 0) skipBytes(input, padding)
                continue
            }

            // Use the pending long name if available, otherwise construct from header
            var fullName = if (pendingLongName != null) {
                val name = pendingLongName!!
                pendingLongName = null
                name
            } else {
                // Handle USTAR prefix
                val prefix = String(header, 345, 155).trim('\u0000', ' ')
                if (prefix.isNotEmpty()) "$prefix/$rawName" else rawName
            }

            // Strip Termux prefix: data/data/com.termux/files/usr/ → ""
            // Also handle ./data/data/... prefix
            fullName = fullName.removePrefix("./")
            if (fullName.startsWith(TERMUX_PREFIX)) {
                fullName = fullName.removePrefix(TERMUX_PREFIX).removePrefix("/")
            } else if (fullName.startsWith("data/")) {
                // Skip non-usr files (control files, etc.)
                pendingLongLink = null
                skipTarEntry(input, size)
                continue
            }

            if (fullName.isEmpty() || fullName == ".") {
                pendingLongLink = null
                skipTarEntry(input, size)
                continue
            }

            val outFile = File(targetDir, fullName)
            if (!outFile.canonicalPath.startsWith(targetDir.canonicalPath)) {
                pendingLongLink = null
                skipTarEntry(input, size)
                continue
            }

            when (typeFlag.toInt().toChar()) {
                '5', 'D' -> {
                    outFile.mkdirs()
                    pendingLongLink = null
                }
                '0', '\u0000' -> {
                    // Regular file
                    outFile.parentFile?.mkdirs()
                    // If path exists as directory, remove it first
                    if (outFile.exists() && outFile.isDirectory) {
                        outFile.deleteRecursively()
                    }
                    try {
                        FileOutputStream(outFile).use { out ->
                            copyBytes(input, out, size)
                        }
                    } catch (_: Exception) {
                        // If write failed partway, we still need to skip remaining data
                    }
                    val padding = roundUp512(size) - size
                    if (padding > 0) skipBytes(input, padding)
                    pendingLongLink = null
                }
                '2' -> {
                    // Symlink — use pending long link target if available
                    val linkTarget = pendingLongLink
                        ?: String(header, 157, 100).trim('\u0000', ' ')
                    pendingLongLink = null
                    // Remove existing file/dir before creating symlink
                    if (outFile.exists()) {
                        if (outFile.isDirectory) outFile.deleteRecursively() else outFile.delete()
                    }
                    outFile.parentFile?.mkdirs()
                    try {
                        val osClass = Class.forName("android.system.Os")
                        osClass.getMethod("symlink", String::class.java, String::class.java)
                            .invoke(null, linkTarget, outFile.absolutePath)
                    } catch (_: Exception) { }
                    skipTarEntry(input, size)
                }
                else -> {
                    pendingLongLink = null
                    skipTarEntry(input, size)
                }
            }
        }
    }

    private fun skipTarEntry(input: java.io.InputStream, size: Long) {
        val total = roundUp512(size)
        skipBytes(input, total)
    }

    private fun setExecutePermissions(dir: File) {
        // Recursively find and chmod ALL bin/ directories and .so files
        dir.walkTopDown().forEach { file ->
            if (file.isFile) {
                val shouldBeExec = when {
                    file.parentFile?.name == "bin" -> true
                    file.extension == "so" -> true
                    file.name.startsWith("lib") && file.parentFile?.name == "lib" -> true
                    file.name == "java" || file.name == "javac" -> true
                    else -> false
                }
                if (shouldBeExec) {
                    file.setExecutable(true, false)
                    file.setReadable(true, false)
                }
            }
        }
    }

    // --- Utility methods ---

    private fun readFully(input: java.io.InputStream, buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read <= 0) return offset
            offset += read
        }
        return offset
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
        val rem = size % 512
        return if (rem == 0L) size else size + (512 - rem)
    }

    data class PackageInfo(
        val name: String,
        val url: String,
        val sizeBytes: Long,
        val depends: List<String>
    )
}
