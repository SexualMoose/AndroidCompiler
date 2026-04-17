import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URL

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

    @get:Input
    abstract val packageUrls: ListProperty<String>

    init {
        group = "build"
        description = "Extracts aapt2 + java launcher from Termux .deb packages into jniLibs"
    }

    @TaskAction
    fun prepare() {
        val outRoot = outputDir.get().asFile
        val abiDir = File(outRoot, "arm64-v8a").apply { mkdirs() }

        val cacheDir = File(project.layout.buildDirectory.get().asFile, "native-cache").apply { mkdirs() }

        // Target files (the whole point of this task)
        val libAapt2 = File(abiDir, "libaapt2.so")
        val libJava = File(abiDir, "libjava.so")
        val libJli = File(abiDir, "libjli.so")

        // Suffix matchers for TAR entry names. Termux .debs use a single prefix
        // for all files (either `./data/data/com.termux/files/usr/` or just `./usr/`),
        // so we match the trailing portion of the path.
        val suffixMatchers = listOf(
            "/bin/aapt2" to libAapt2,
            "/bin/java" to libJava,
            "/lib/libjli.so" to libJli
        )

        // Extract each package
        for (url in packageUrls.get()) {
            val fileName = url.substringAfterLast('/')
                .substringBefore('?')
                .substringBeforeLast(".deb") + ".deb"
            val debFile = File(cacheDir, fileName)
            if (!debFile.exists() || debFile.length() < 1000) {
                logger.lifecycle("Downloading $url")
                debFile.parentFile.mkdirs()
                URL(url).openStream().use { input ->
                    FileOutputStream(debFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }

            // Extract binaries we care about (by path suffix)
            extractDebBinaries(debFile, suffixMatchers)
        }

        // Verify we got what we need
        val missing = listOf(libAapt2 to "aapt2", libJava to "java", libJli to "libjli.so")
            .filter { !it.first.exists() || it.first.length() < 1000 }
            .joinToString(", ") { it.second }
        if (missing.isNotEmpty()) {
            throw IllegalStateException(
                "PrepareNativeBinariesTask failed to extract: $missing. " +
                "Check the Termux package URLs and contents."
            )
        }

        logger.lifecycle("Native binaries prepared in ${abiDir.absolutePath}")
        logger.lifecycle("  libaapt2.so: ${libAapt2.length()} bytes")
        logger.lifecycle("  libjava.so:  ${libJava.length()} bytes")
        logger.lifecycle("  libjli.so:   ${libJli.length()} bytes")
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
