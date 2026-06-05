package com.androidcompiler.toolchain.jdk

import android.content.Context
import android.util.Log
import com.androidcompiler.network.ChunkedDownloader
import com.androidcompiler.toolchain.BuildConfig
import com.androidcompiler.toolchain.pipeline.BuildDiagnostics
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
        private const val TAG = "ACBuild"
        private const val TERMUX_REPO = "https://packages.termux.dev/apt/termux-main"
        private const val TERMUX_PREFIX = "data/data/com.termux/files/usr"

        /**
         * The OpenJDK 17 patch version baked into the APK's launcher binaries.
         * The runtime JDK download is PINNED to this exact version so the
         * launcher and libjvm.so always agree (a patch mismatch aborts JVM init).
         * Single source of truth: app/build.gradle.kts `bundledJdkVersion`,
         * mirrored into this module's BuildConfig (see toolchain/build.gradle.kts).
         */
        val BUNDLED_JDK_VERSION: String = BuildConfig.BUNDLED_JDK_VERSION

        /**
         * Primary ABI of this device as reported by Android.
         * Returns either "aarch64" (arm64-v8a) or "x86_64" — our two supported
         * architectures. Others fall through to aarch64 as the safest default.
         */
        fun termuxArch(): String {
            val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
            return when (abi) {
                "arm64-v8a" -> "aarch64"
                "x86_64" -> "x86_64"
                else -> "aarch64" // best-effort fallback for armv7/x86
            }
        }
    }

    private val packagesIndexUrl: String
        get() = "$TERMUX_REPO/dists/stable/main/binary-${termuxArch()}/Packages"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    val jdkDir: File get() = File(registry.toolchainDir, "jdk17")

    fun isInstalled(): Boolean {
        // Check JDK content presence via files that don't get symlinked on reinstall:
        // - lib/modules (the jimage containing all system modules, ~70MB)
        // - lib/server/libjvm.so (the JVM)
        // We deliberately avoid bin/java because GradleCompiler replaces it with
        // a symlink to nativeLibraryDir, and that symlink dangles after an app
        // reinstall (new install hash). The JDK is still usable — GradleCompiler
        // re-creates the symlink on each compile.
        val javaHome = getJavaHome() ?: return false
        val contentPresent = File(javaHome, "lib/modules").exists() ||
                File(javaHome, "lib/server/libjvm.so").exists()
        if (!contentPresent) return false
        // A stale JDK left over from a previous app version (different patch than
        // the bundled launcher) is NOT usable — its libjvm.so won't match the
        // launcher and JVM init aborts. Treat a version-mismatched JDK as "not
        // installed" so the readiness gate forces a fresh, matched download.
        if (!isVersionMatched()) {
            Log.w(TAG, "JDK present but version mismatched " +
                "(installed=${registry.getInstalledVersion("jdk")}, " +
                "expected=$BUNDLED_JDK_VERSION) → reporting NOT installed")
            return false
        }
        return true
    }

    /**
     * True when the recorded installed JDK version equals the bundled launcher's
     * version (BUNDLED_JDK_VERSION). A JDK installed by an older app build saved
     * either the legacy "17" marker or an older patch like "17.0.18"; either way
     * its libjvm.so won't match the current launcher, so this returns false and
     * the JDK gets re-downloaded.
     */
    fun isVersionMatched(): Boolean {
        val installed = registry.getInstalledVersion("jdk") ?: return false
        return installed == BUNDLED_JDK_VERSION
    }

    /**
     * Self-recovery: if a JDK is physically present but its recorded version
     * doesn't match the bundled launcher, delete jdkDir so the next readiness
     * check triggers a fresh, version-matched download. No user action required.
     * Safe to call before any compile/readiness probe. Returns true if it wiped.
     */
    fun invalidateIfMismatched(): Boolean {
        val javaHome = getJavaHome() ?: return false
        val contentPresent = File(javaHome, "lib/modules").exists() ||
                File(javaHome, "lib/server/libjvm.so").exists()
        if (!contentPresent) return false
        if (isVersionMatched()) return false
        Log.w(TAG, "Invalidating stale JDK at ${jdkDir.absolutePath} " +
            "(installed=${registry.getInstalledVersion("jdk")}, expected=$BUNDLED_JDK_VERSION)")
        return try {
            jdkDir.deleteRecursively()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete stale jdkDir: ${e.message}", e)
            false
        }
    }

    fun getJavaHome(): File? {
        // A JDK subdir "exists" if it has the real JDK content, even if bin/java
        // happens to be a stale symlink from a previous install.
        fun hasJdkContent(dir: File): Boolean =
            File(dir, "lib/modules").exists() ||
                    File(dir, "lib/server/libjvm.so").exists() ||
                    File(dir, "bin/java").exists()

        // Direct: jdk17/bin/java
        if (hasJdkContent(jdkDir)) return jdkDir
        // Nested: jdk17/lib/jvm/java-17-openjdk/bin/java
        val jvmDir = File(jdkDir, "lib/jvm")
        if (jvmDir.exists()) {
            jvmDir.listFiles()?.forEach { child ->
                if (hasJdkContent(child)) return child
            }
        }
        // Single subdirectory
        jdkDir.listFiles()?.filter { it.isDirectory }?.forEach { child ->
            if (hasJdkContent(child)) return child
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
        // Wrap onLog so every JDK-install line also lands in logcat and the
        // persistent compile log — previously this path produced ZERO logcat.
        val log: (String) -> Unit = { msg ->
            Log.i(TAG, "[jdk-install] $msg")
            persist("[jdk-install] $msg")
            onLog(msg)
        }
        try {
            log("Installing OpenJDK pinned to $BUNDLED_JDK_VERSION (arch=${termuxArch()})")
            log("Fetching Termux package index...")
            val packages = findPackages(log)
            if (packages.isEmpty()) {
                Log.e(TAG, "[jdk-install] no packages resolved for openjdk-17")
                return@withContext Result.failure(Exception("Could not find openjdk-17 in Termux repo"))
            }

            log("Found ${packages.size} package(s) to download")

            jdkDir.mkdirs()
            val totalPackages = packages.size
            var completed = 0

            for (pkg in packages) {
                log("Downloading: ${pkg.name} (${pkg.sizeBytes / 1024} KB) <- ${pkg.url}")
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
                    val err = "Failed to download ${pkg.name}: ${dlResult.exceptionOrNull()?.message}"
                    Log.e(TAG, "[jdk-install] $err")
                    persist("[jdk-install] $err")
                    return@withContext Result.failure(Exception(err))
                }

                log("Extracting: ${pkg.name}")
                extractDeb(debFile, jdkDir)
                debFile.delete()
                completed++
                onProgress(completed.toFloat() / totalPackages)
            }

            // Set permissions
            log("Setting permissions...")
            setExecutePermissions(jdkDir)

            // Verify — find java binary in the extracted tree
            val javaHome = getJavaHome()
            val javaBin = javaHome?.let { File(it, "bin/java") }
            if (javaBin == null || !javaBin.exists()) {
                val err = "Installation completed but java binary not found. " +
                    "Searched in: ${jdkDir.absolutePath}"
                Log.e(TAG, "[jdk-install] $err")
                persist("[jdk-install] $err")
                return@withContext Result.failure(Exception(err))
            }
            javaBin.setExecutable(true, false)

            log("JDK installed: ${javaHome.absolutePath}")
            // Save the FULL pinned version (not the legacy "17" marker) so the
            // version-match self-recovery can detect a future launcher bump and
            // re-download the matching JDK.
            registry.saveInstalledVersion("jdk", BUNDLED_JDK_VERSION)
            Result.success(jdkDir)
        } catch (e: Exception) {
            Log.e(TAG, "[jdk-install] JDK installation failed: ${e.message}", e)
            persist("[jdk-install] FAILED: ${e.message}")
            Result.failure(Exception("JDK installation failed: ${e.message}", e))
        }
    }

    /** The PINNED openjdk-17 .deb URL — must match the bundled launcher's patch
     *  version. We never trust the live index for THIS file because the index
     *  serves a moving target; a launcher↔libjvm.so mismatch aborts JVM init. */
    private fun pinnedJdkUrl(): String =
        "$TERMUX_REPO/pool/main/o/openjdk-17/openjdk-17_${BUNDLED_JDK_VERSION}_${termuxArch()}.deb"

    /**
     * Find the openjdk-17 package URL from Termux's Packages index.
     *
     * The openjdk-17 package itself is PINNED to [pinnedJdkUrl] (the exact patch
     * version the bundled launcher was built against). The live index is used
     * only to resolve the small dependency packages (libc++/libiconv/etc.), whose
     * versions don't affect JVM init.
     */
    private fun findPackages(onLog: (String) -> Unit): List<PackageInfo> {
        val packages = mutableListOf<PackageInfo>()

        // Try to fetch and parse the Packages index for our current arch
        try {
            val request = Request.Builder().url(packagesIndexUrl).build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            response.close()

            // Parse the Debian Packages file format
            val entries = parsePackagesIndex(body)

            // Find openjdk-17 (the meta-package that pulls in the JDK)
            val jdkPkg = entries["openjdk-17"] ?: entries["openjdk-17-jdk"]
            if (jdkPkg != null) {
                // Use the index entry ONLY for its dependency list + size, but
                // OVERRIDE the download URL with the pinned version.
                packages.add(jdkPkg.copy(url = pinnedJdkUrl()))
                // Add direct dependencies (these stay on the live index — only
                // the JDK's patch version is version-critical for the launcher).
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
            onLog("Using fallback package URLs (pinned openjdk-17 $BUNDLED_JDK_VERSION)")
            packages.addAll(getFallbackPackages())
        } else if (packages.none { it.name == "openjdk-17" }) {
            // Index resolved deps but not the JDK meta-package itself — add the
            // pinned JDK explicitly so we never silently skip it.
            onLog("Index missing openjdk-17 entry; adding pinned $BUNDLED_JDK_VERSION")
            packages.add(0, PackageInfo("openjdk-17", pinnedJdkUrl(), 95_507_372, emptyList()))
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
        val arch = termuxArch()
        return listOf(
            // openjdk-17 PINNED to the bundled launcher's patch version.
            PackageInfo("openjdk-17", pinnedJdkUrl(), 95_507_372, emptyList()),
            PackageInfo("libandroid-shmem", "$TERMUX_REPO/pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_$arch.deb", 7_216, emptyList()),
            PackageInfo("libandroid-spawn", "$TERMUX_REPO/pool/main/liba/libandroid-spawn/libandroid-spawn_0.3_$arch.deb", 15_216, emptyList()),
            PackageInfo("libiconv", "$TERMUX_REPO/pool/main/libi/libiconv/libiconv_1.18-1_$arch.deb", 561_152, emptyList()),
            PackageInfo("libjpeg-turbo", "$TERMUX_REPO/pool/main/libj/libjpeg-turbo/libjpeg-turbo_3.1.4.1_$arch.deb", 384_504, emptyList()),
            PackageInfo("littlecms", "$TERMUX_REPO/pool/main/l/littlecms/littlecms_2.18_$arch.deb", 142_676, emptyList()),
            PackageInfo("zlib", "$TERMUX_REPO/pool/main/z/zlib/zlib_1.3.2_$arch.deb", 62_840, emptyList()),
            PackageInfo("libc++", "$TERMUX_REPO/pool/main/libc/libc++/libc++_29_$arch.deb", 334_828, emptyList()),
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
                    // If path is a dangling/stale symlink (from a previous install's
                    // nativeLibraryDir linked to by GradleCompiler.linkJdkToolsToBundled),
                    // remove it so FileOutputStream can write through.
                    try {
                        val osClass = Class.forName("android.system.Os")
                        val stat = osClass.getMethod("lstat", String::class.java)
                            .invoke(null, outFile.absolutePath)
                        val mode = stat.javaClass.getField("st_mode").getInt(stat)
                        if ((mode and 0xF000) == 0xA000) outFile.delete()
                    } catch (_: Exception) { }
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

    /** Mirror an install line into the persistent on-device compile log. */
    private fun persist(line: String) = BuildDiagnostics.append(context, line)

    data class PackageInfo(
        val name: String,
        val url: String,
        val sizeBytes: Long,
        val depends: List<String>
    )
}
