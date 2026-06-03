import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URI

/**
 * Downloads Termux's ARM64 aapt2 and openjdk-17 .deb packages, extracts the two
 * executable binaries (`aapt2`, `java`) plus `libjli.so`, and renames them into
 * the APK's `lib/arm64-v8a/` layout as `lib*.so`.
 *
 * This lets Android's package installer extract them into `nativeLibraryDir` with
 * exec permissions, which is the only modern way to run our own binaries from a
 * targetSdk≥29 app without triggering SELinux denials.
 *
 * The full JDK and AAPT2 dependencies (~100MB) are still downloaded at runtime
 * — only the three tiny launcher binaries need to ship in the APK.
 *
 * NOTE: The .deb format is an `ar` archive containing `data.tar.xz`. We use the
 * same parsing logic as the runtime installer but in a simpler, single-pass form.
 *
 * Cache: `<project>/build/native-cache/` stores the downloaded .deb files so
 * successive builds don't re-download them. Delete the cache dir to force refresh.
 */
@CacheableTask
abstract class PrepareNativeBinariesTask : DefaultTask() {

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    /**
     * Map of Android ABI (e.g. "arm64-v8a", "x86_64") to the list of Termux
     * .deb URLs for that ABI. The task extracts `bin/aapt2`, `bin/java`, and
     * `lib/libjli.so` from each package into jniLibs/<abi>/.
     */
    @get:Input
    abstract val packagesByAbi: org.gradle.api.provider.MapProperty<String, List<String>>

    /**
     * Committed prebuilt launcher binaries laid out as `<abi>/lib*.so` (wired by
     * the convention plugin from `<rootProject>/prebuilts/native-jniLibs`). These
     * are seeded into the output BEFORE any network access — the offline-first
     * hardening so a fresh clone builds even when Termux is unreachable or has
     * rotated its package versions. Optional: if empty, the task downloads
     * everything from Termux as before.
     */
    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val prebuiltFiles: ConfigurableFileCollection

    init {
        group = "build"
        description = "Extracts aapt2 + java launcher from Termux .deb packages into jniLibs (multi-ABI)"
    }

    @TaskAction
    fun prepare() {
        val outRoot = outputDir.get().asFile
        val cacheDir = File(project.layout.buildDirectory.get().asFile, "native-cache").apply { mkdirs() }

        val abiPackages = packagesByAbi.get()
        require(abiPackages.isNotEmpty()) { "packagesByAbi is empty — nothing to prepare" }

        for ((abi, urls) in abiPackages) {
            val abiDir = File(outRoot, abi).apply { mkdirs() }
            val libAapt2 = File(abiDir, "libaapt2.so")
            val libJava = File(abiDir, "libjava.so")
            val libJli = File(abiDir, "libjli.so")
            // Extra JDK tool launchers. Gradle's AGP plugin invokes jlink during
            // the JdkImageTransform and javac for cross-SDK java compilation.
            // Each is a tiny ELF stub (~6KB) that loads libjli.so — we bundle
            // them all under lib*.so to make the exec-allowed nativeLibraryDir
            // path available at runtime.
            val libJlink = File(abiDir, "libjlink.so")
            val libJavac = File(abiDir, "libjavac.so")
            val libJar = File(abiDir, "libjar.so")
            val libJdeps = File(abiDir, "libjdeps.so")
            val libJmod = File(abiDir, "libjmod.so")
            val libKeytool = File(abiDir, "libkeytool.so")

            val suffixMatchers = listOf(
                "/bin/aapt2" to libAapt2,
                "/bin/java" to libJava,
                "/lib/libjli.so" to libJli,
                "/bin/jlink" to libJlink,
                "/bin/javac" to libJavac,
                "/bin/jar" to libJar,
                "/bin/jdeps" to libJdeps,
                "/bin/jmod" to libJmod,
                "/bin/keytool" to libKeytool
            )

            // Offline-first hardening: seed any committed prebuilt binaries for
            // this ABI before touching the network. A fresh clone with prebuilts
            // present never needs Termux at all.
            seedFromPrebuilts(abi, abiDir)

            // Only reach out to Termux for binaries the prebuilts didn't supply.
            val allTargets = suffixMatchers.map { it.second }
            if (allTargets.all { it.exists() && it.length() > 0 }) {
                logger.lifecycle("[$abi] all native binaries satisfied by committed prebuilts — skipping download")
            } else {
                for (url in urls) {
                    try {
                        val debFile = ensureDeb(url, cacheDir, abi)
                        extractDebBinaries(debFile, suffixMatchers)
                    } catch (e: Exception) {
                        // Non-fatal: prebuilts (or another package) may already
                        // cover the required set. The required-check below is the
                        // real gate — only a genuinely-missing binary fails the build.
                        logger.warn("[$abi] could not fetch/extract $url (${e.message}); " +
                            "relying on prebuilts / other packages")
                    }
                }
            }

            // Only aapt2 / java / libjli.so are strictly required. The other JDK
            // tools (jlink, javac, jar, …) are "nice to have" — if a specific
            // build of openjdk-17 omits one, the runtime will symlink only the
            // present ones and fall back to the unbundled binary if Gradle asks.
            val required = listOf(libAapt2 to "aapt2", libJava to "java", libJli to "libjli.so")
            val missing = required
                .filter { !it.first.exists() || it.first.length() < 1000 }
                .joinToString(", ") { it.second }
            if (missing.isNotEmpty()) {
                throw IllegalStateException(
                    "PrepareNativeBinariesTask[$abi] failed to extract: $missing. " +
                    "Check the Termux package URLs and contents."
                )
            }

            val optionalExtracted = listOf(
                libJlink to "jlink", libJavac to "javac", libJar to "jar",
                libJdeps to "jdeps", libJmod to "jmod", libKeytool to "keytool"
            ).filter { it.first.exists() && it.first.length() > 0 }
                .joinToString(",") { "${it.second}=${it.first.length()}" }

            logger.lifecycle("[$abi] core: libaapt2.so=${libAapt2.length()}, " +
                "libjava.so=${libJava.length()}, libjli.so=${libJli.length()}")
            if (optionalExtracted.isNotEmpty()) {
                logger.lifecycle("[$abi] tools: $optionalExtracted")
            }
        }
    }

    /**
     * Copies committed prebuilt `lib*.so` for [abi] into [abiDir], skipping any
     * that are already present and non-empty. Prebuilts live under
     * `<rootProject>/prebuilts/native-jniLibs/<abi>/` (wired by the convention
     * plugin); each file's parent-directory name identifies its ABI.
     */
    private fun seedFromPrebuilts(abi: String, abiDir: File) {
        val files = prebuiltFiles.files
        if (files.isEmpty()) return
        var copied = 0
        for (f in files) {
            if (!f.isFile || f.parentFile?.name != abi) continue
            val dest = File(abiDir, f.name)
            if (!dest.exists() || dest.length() == 0L) {
                f.copyTo(dest, overwrite = true)
                copied++
            }
        }
        if (copied > 0) logger.lifecycle("[$abi] seeded $copied prebuilt binary(ies) from repo")
    }

    /**
     * Returns the cached .deb for [url], downloading it if absent. If the pinned
     * URL fails (Termux's pool keeps only the latest patch and drops older files,
     * so a version-pinned URL 404s after a bump), self-heals by resolving the
     * current .deb for the same package + architecture from the pool listing.
     */
    private fun ensureDeb(url: String, cacheDir: File, abi: String): File {
        val debFile = debCacheFile(url, cacheDir)
        if (debFile.exists() && debFile.length() >= 1000) return debFile
        debFile.parentFile.mkdirs()
        try {
            logger.lifecycle("[$abi] Downloading $url")
            download(url, debFile)
            return debFile
        } catch (e: Exception) {
            logger.lifecycle("[$abi] pinned URL failed (${e.message}); resolving current version from the Termux pool…")
            val alt = resolveLatestUrl(url) ?: throw e
            if (alt == url) throw e
            val altFile = debCacheFile(alt, cacheDir)
            if (altFile.exists() && altFile.length() >= 1000) return altFile
            altFile.parentFile.mkdirs()
            logger.lifecycle("[$abi] resolved current package -> $alt")
            download(alt, altFile)
            return altFile
        }
    }

    private fun debCacheFile(url: String, cacheDir: File): File {
        val fileName = url.substringAfterLast('/')
            .substringBefore('?')
            .substringBeforeLast(".deb") + ".deb"
        return File(cacheDir, fileName)
    }

    private fun download(url: String, dest: File) {
        URI(url).toURL().openStream().use { input ->
            FileOutputStream(dest).use { output -> input.copyTo(output) }
        }
    }

    /**
     * Given a (possibly stale) Termux .deb URL, lists its pool directory and
     * returns the URL of the current .deb for the same package + architecture.
     * This makes the download path resilient to Termux's version rotation —
     * the build no longer breaks when openjdk-17 / aapt2 bump a patch version.
     * Returns null if the listing can't be fetched or parsed.
     */
    private fun resolveLatestUrl(originalUrl: String): String? {
        return try {
            val poolDir = originalUrl.substringBeforeLast('/')          // …/pool/main/o/openjdk-17
            val fileName = originalUrl.substringAfterLast('/')          // openjdk-17_17.0.18_aarch64.deb
            val pkg = fileName.substringBefore('_')                     // openjdk-17
            val arch = fileName.substringBeforeLast(".deb").substringAfterLast('_')  // aarch64
            val listing = URI("$poolDir/").toURL().readText()
            val rx = Regex(Regex.escape(pkg) + "_[^\"<>]*_" + Regex.escape(arch) + "\\.deb")
            rx.findAll(listing).map { it.value }.distinct().sortedDescending()
                .firstOrNull()?.let { "$poolDir/$it" }
        } catch (e: Exception) {
            logger.warn("Could not resolve current package URL from pool for $originalUrl: ${e.message}")
            null
        }
    }

    /**
     * Parses a .deb archive (ar format) and extracts files whose tar entry name
     * ends with one of the suffixes in [matchers], copying to the associated output file.
     */
    private fun extractDebBinaries(debFile: File, matchers: List<Pair<String, File>>) {
        FileInputStream(debFile).buffered().use { input ->
            val magic = ByteArray(8)
            input.read(magic)
            require(String(magic).startsWith("!<arch>")) {
                "$debFile is not a valid .deb archive (bad ar magic)"
            }

            while (input.available() > 0) {
                val header = ByteArray(60)
                val read = readFully(input, header)
                if (read < 60) break
                val name = String(header, 0, 16).trim()
                val sizeStr = String(header, 48, 10).trim()
                val size = sizeStr.toLongOrNull() ?: 0

                if (name.startsWith("data.tar")) {
                    val tempData = File.createTempFile("deb-data-", ".tar.xz")
                    try {
                        FileOutputStream(tempData).use { out ->
                            copyBytes(input, out, size)
                        }
                        // Now parse the tar inside, decompressing based on extension
                        val decompressed = File.createTempFile("deb-data-", ".tar")
                        try {
                            decompressTarPayload(tempData, decompressed, name)
                            extractTar(decompressed, matchers)
                        } finally {
                            decompressed.delete()
                        }
                    } finally {
                        tempData.delete()
                    }
                    return
                } else {
                    skipBytes(input, size)
                }
                if (size % 2 != 0L) input.read()
            }
        }
    }

    private fun decompressTarPayload(compressed: File, output: File, arName: String) {
        val normalized = arName.trimEnd('/', ' ', '\u0000')
        when {
            normalized.endsWith(".xz") || normalized.endsWith(".tar.xz") -> {
                XZInputStream(BufferedInputStream(FileInputStream(compressed), 65536)).use { xz ->
                    FileOutputStream(output).use { out -> xz.copyTo(out) }
                }
            }
            normalized.endsWith(".gz") || normalized.endsWith(".tar.gz") -> {
                java.util.zip.GZIPInputStream(BufferedInputStream(FileInputStream(compressed), 65536)).use { gz ->
                    FileOutputStream(output).use { out -> gz.copyTo(out) }
                }
            }
            normalized.endsWith(".zst") || normalized.endsWith(".tar.zst") -> {
                throw UnsupportedOperationException(".tar.zst is not supported; expected .tar.xz")
            }
            else -> {
                // Unknown extension — try xz first (Termux's default), fall back to raw
                try {
                    XZInputStream(BufferedInputStream(FileInputStream(compressed), 65536)).use { xz ->
                        FileOutputStream(output).use { out -> xz.copyTo(out) }
                    }
                } catch (e: Exception) {
                    logger.warn("Unknown payload compression ($normalized); copying raw: ${e.message}")
                    compressed.copyTo(output, overwrite = true)
                }
            }
        }
    }

    private fun extractTar(tarFile: File, matchers: List<Pair<String, File>>) {
        FileInputStream(tarFile).buffered().use { input ->
            val header = ByteArray(512)
            var pendingLongName: String? = null

            while (true) {
                val read = readFully(input, header)
                if (read < 512) break
                if (header.all { it == 0.toByte() }) break

                val rawName = String(header, 0, 100).trim('\u0000', ' ')
                val sizeOctal = String(header, 124, 12).trim('\u0000', ' ')
                val size = sizeOctal.takeIf { it.isNotEmpty() }
                    ?.let { try { it.toLong(8) } catch (_: Exception) { 0L } } ?: 0L
                val typeFlag = header[156]

                // GNU TAR long name
                if (typeFlag.toInt().toChar() == 'L') {
                    val nameBytes = ByteArray(size.toInt())
                    readFully(input, nameBytes)
                    pendingLongName = String(nameBytes).trim('\u0000')
                    skipBytes(input, roundUp512(size) - size)
                    continue
                }

                val fullName = pendingLongName ?: run {
                    val prefix = String(header, 345, 155).trim('\u0000', ' ')
                    if (prefix.isNotEmpty()) "$prefix/$rawName" else rawName
                }
                pendingLongName = null

                // Find a matcher whose suffix matches this entry
                val target = matchers.firstOrNull { (suffix, _) -> fullName.endsWith(suffix) }?.second

                if (target != null && (typeFlag.toInt().toChar() == '0' || typeFlag.toInt().toChar() == '\u0000')) {
                    // Only write if we haven't already extracted this one (first match wins)
                    if (!target.exists() || target.length() == 0L) {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { out ->
                            copyBytes(input, out, size)
                        }
                        logger.lifecycle("Extracted: $fullName -> ${target.name} (${size} bytes)")
                    } else {
                        skipBytes(input, size)
                    }
                    skipBytes(input, roundUp512(size) - size)
                } else {
                    skipBytes(input, roundUp512(size))
                }
            }
        }
    }

    private fun readFully(input: java.io.InputStream, buffer: ByteArray): Int {
        var off = 0
        while (off < buffer.size) {
            val r = input.read(buffer, off, buffer.size - off)
            if (r <= 0) return off
            off += r
        }
        return off
    }

    private fun copyBytes(input: java.io.InputStream, output: java.io.OutputStream, count: Long) {
        var remaining = count
        val buf = ByteArray(65536)
        while (remaining > 0) {
            val toRead = minOf(remaining, buf.size.toLong()).toInt()
            val r = input.read(buf, 0, toRead)
            if (r <= 0) break
            output.write(buf, 0, r)
            remaining -= r
        }
    }

    private fun skipBytes(input: java.io.InputStream, count: Long) {
        var remaining = count
        val buf = ByteArray(65536)
        while (remaining > 0) {
            val toSkip = minOf(remaining, buf.size.toLong()).toInt()
            val r = input.read(buf, 0, toSkip)
            if (r <= 0) break
            remaining -= r
        }
    }

    private fun roundUp512(size: Long): Long {
        val rem = size % 512
        return if (rem == 0L) size else size + (512 - rem)
    }
}
