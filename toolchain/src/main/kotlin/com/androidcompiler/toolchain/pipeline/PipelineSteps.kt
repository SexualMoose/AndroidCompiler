package com.androidcompiler.toolchain.pipeline

import com.androidcompiler.core.common.model.CompilationError
import com.androidcompiler.core.common.model.ErrorSeverity
import com.androidcompiler.toolchain.classloader.ToolchainClassLoaderFactory
import com.androidcompiler.toolchain.registry.ToolchainRegistry
import com.androidcompiler.toolchain.signing.KeystoreManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.PrintStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProjectExtractor @Inject constructor() {
    fun extract(zipPath: String, buildDir: File): File {
        val projectDir = File(buildDir, "project").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }

        ZipInputStream(File(zipPath).inputStream().buffered()).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                val outFile = File(projectDir, entry.name)
                // Security: prevent zip slip
                if (!outFile.canonicalPath.startsWith(projectDir.canonicalPath)) {
                    throw SecurityException("Zip entry outside target dir: ${entry.name}")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos -> zis.copyTo(fos) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        // Unwrap single root directory if present
        val children = projectDir.listFiles() ?: return projectDir
        if (children.size == 1 && children[0].isDirectory) {
            return children[0]
        }
        return projectDir
    }
}

@Singleton
class ResourceCompiler @Inject constructor(
    private val registry: ToolchainRegistry
) {
    suspend fun compile(context: CompilationContext): StepResult = withContext(Dispatchers.IO) {
        val aapt2 = registry.getAapt2Binary()
        if (!aapt2.exists()) {
            return@withContext StepResult.Failure(listOf(
                CompilationError("AAPT2", ErrorSeverity.ERROR, "AAPT2 binary not found. Please download toolchain components first.")
            ))
        }

        val resDir = findResourceDir(context.projectDir)
        val manifestFile = findManifest(context.projectDir)
            ?: return@withContext StepResult.Failure(listOf(
                CompilationError("AAPT2", ErrorSeverity.ERROR, "AndroidManifest.xml not found in project")
            ))

        val compiledResDir = File(context.buildDir, "compiled_res").apply { mkdirs() }
        val rJavaDir = File(context.buildDir, "r_java").apply { mkdirs() }
        val resourceApk = File(context.buildDir, "resources.ap_")

        // Step 1: Compile resources to flat format (if res dir exists)
        if (resDir != null && resDir.exists()) {
            val compileResult = executeProcess(
                aapt2.absolutePath, "compile",
                "--dir", resDir.absolutePath,
                "-o", compiledResDir.absolutePath
            )
            if (compileResult.exitCode != 0) {
                return@withContext StepResult.Failure(parseAapt2Errors(compileResult.stderr))
            }
        }

        // Step 2: Link resources and generate R.java
        val linkArgs = mutableListOf(
            aapt2.absolutePath, "link",
            "--manifest", manifestFile.absolutePath,
            "-I", context.androidJarPath,
            "-o", resourceApk.absolutePath,
            "--java", rJavaDir.absolutePath,
            "--auto-add-overlay"
        )

        // Add all compiled flat files
        if (resDir != null && compiledResDir.exists()) {
            compiledResDir.walkTopDown()
                .filter { it.isFile && it.extension == "flat" }
                .forEach { linkArgs.add(it.absolutePath) }
        }

        val linkResult = executeProcess(*linkArgs.toTypedArray())
        if (linkResult.exitCode != 0) {
            return@withContext StepResult.Failure(parseAapt2Errors(linkResult.stderr))
        }

        StepResult.Success(listOf(resourceApk, rJavaDir))
    }

    private fun findResourceDir(projectDir: File): File? {
        val candidates = listOf(
            "app/src/main/res", "src/main/res", "res"
        )
        return candidates.map { File(projectDir, it) }.firstOrNull { it.exists() && it.isDirectory }
    }

    private fun parseAapt2Errors(stderr: String): List<CompilationError> {
        if (stderr.isBlank()) return listOf(
            CompilationError("AAPT2", ErrorSeverity.ERROR, "AAPT2 failed with no error output")
        )
        return stderr.lines().filter { it.isNotBlank() }.map { line ->
            val severity = if (line.contains("error:", ignoreCase = true)) ErrorSeverity.ERROR else ErrorSeverity.WARNING
            CompilationError("AAPT2", severity, line.trim(), rawOutput = stderr)
        }
    }
}

@Singleton
class SourceCompiler @Inject constructor(
    private val registry: ToolchainRegistry,
    private val classLoaderFactory: ToolchainClassLoaderFactory
) {
    suspend fun compile(context: CompilationContext): StepResult = withContext(Dispatchers.Default) {
        val classesDir = File(context.buildDir, "classes").apply { mkdirs() }
        val rJavaDir = File(context.buildDir, "r_java")

        // Collect all source files
        val javaFiles = mutableListOf<File>()
        val kotlinFiles = mutableListOf<File>()

        val sourceDirs = findSourceDirs(context.projectDir)
        sourceDirs.forEach { dir ->
            dir.walkTopDown().forEach { file ->
                when (file.extension) {
                    "java" -> javaFiles.add(file)
                    "kt" -> kotlinFiles.add(file)
                }
            }
        }

        // Add R.java files
        if (rJavaDir.exists()) {
            rJavaDir.walkTopDown().filter { it.extension == "java" }.forEach {
                javaFiles.add(it)
            }
        }

        if (javaFiles.isEmpty() && kotlinFiles.isEmpty()) {
            return@withContext StepResult.Failure(listOf(
                CompilationError("Source", ErrorSeverity.ERROR, "No source files found in project")
            ))
        }

        val classpath = buildClasspath(context)
        val allErrors = mutableListOf<CompilationError>()

        // Compile Kotlin first (if any), since Java may depend on Kotlin but not vice versa typically
        if (kotlinFiles.isNotEmpty()) {
            val kotlinResult = compileKotlin(kotlinFiles, javaFiles, classesDir, classpath, context)
            if (kotlinResult is StepResult.Failure) return@withContext kotlinResult
        }

        // Compile Java (including R.java)
        if (javaFiles.isNotEmpty()) {
            val javaClasspath = if (kotlinFiles.isNotEmpty()) {
                "$classpath:${classesDir.absolutePath}"
            } else classpath
            val javaResult = compileJava(javaFiles, classesDir, javaClasspath)
            if (javaResult is StepResult.Failure) return@withContext javaResult
        }

        StepResult.Success(listOf(classesDir))
    }

    private suspend fun compileKotlin(
        kotlinFiles: List<File>,
        javaFiles: List<File>,
        classesDir: File,
        classpath: String,
        context: CompilationContext
    ): StepResult = withContext(Dispatchers.IO) {
        val kotlincJar = registry.getKotlincJar()
        if (!kotlincJar.exists()) {
            return@withContext StepResult.Failure(listOf(
                CompilationError("kotlinc", ErrorSeverity.ERROR, "Kotlin compiler not found. Please download toolchain components.")
            ))
        }

        val kotlinJars = listOfNotNull(
            kotlincJar,
            registry.getKotlinStdlibJar(),
            registry.getKotlinScriptRuntimeJar()
        ).filter { it.exists() }

        try {
            val classLoader = classLoaderFactory.getOrCreate("kotlinc", kotlinJars)

            val args = mutableListOf<String>()
            args.addAll(listOf("-d", classesDir.absolutePath))
            args.addAll(listOf("-classpath", classpath))
            args.addAll(listOf("-no-stdlib"))
            args.addAll(listOf("-jvm-target", "17"))

            // Add all kotlin and java source files (kotlinc can process both)
            kotlinFiles.forEach { args.add(it.absolutePath) }
            javaFiles.forEach { args.add(it.absolutePath) }

            val outStream = ByteArrayOutputStream()
            val errStream = ByteArrayOutputStream()
            val prevOut = System.out
            val prevErr = System.err

            System.setOut(PrintStream(outStream))
            System.setErr(PrintStream(errStream))

            try {
                val exitCode = invokeKotlinCompiler(classLoader, args.toTypedArray())

                if (exitCode != 0) {
                    val stderr = errStream.toString() + outStream.toString()
                    return@withContext StepResult.Failure(parseKotlinErrors(stderr))
                }
            } finally {
                System.setOut(prevOut)
                System.setErr(prevErr)
            }

            StepResult.Success(listOf(classesDir))
        } catch (e: Exception) {
            StepResult.Failure(listOf(
                CompilationError("kotlinc", ErrorSeverity.ERROR, "Kotlin compilation failed: ${e.message}", rawOutput = e.stackTraceToString())
            ))
        }
    }

    private fun invokeKotlinCompiler(classLoader: ClassLoader, args: Array<String>): Int {
        // Use K2JVMCompiler.exec() which returns an exit code
        val compilerClass = classLoader.loadClass("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler")
        val compiler = compilerClass.getDeclaredConstructor().newInstance()

        val execMethod = compilerClass.getMethod("exec",
            classLoader.loadClass("org.jetbrains.kotlin.config.Services"),
            Array<String>::class.java
        )

        val servicesClass = classLoader.loadClass("org.jetbrains.kotlin.config.Services")
        val emptyField = servicesClass.getField("EMPTY")
        val emptyServices = emptyField.get(null)

        val exitCodeObj = execMethod.invoke(compiler, emptyServices, args)
        val codeMethod = exitCodeObj.javaClass.getMethod("getCode")
        return codeMethod.invoke(exitCodeObj) as Int
    }

    private suspend fun compileJava(
        javaFiles: List<File>,
        classesDir: File,
        classpath: String
    ): StepResult = withContext(Dispatchers.IO) {
        val ecjJar = registry.getEcjJar()
        if (!ecjJar.exists()) {
            return@withContext StepResult.Failure(listOf(
                CompilationError("ECJ", ErrorSeverity.ERROR, "ECJ compiler not found. Please download toolchain components.")
            ))
        }

        try {
            val classLoader = classLoaderFactory.getOrCreate("ecj", listOf(ecjJar))

            val args = mutableListOf<String>()
            args.addAll(listOf("-source", "17"))
            args.addAll(listOf("-target", "17"))
            args.addAll(listOf("-classpath", classpath))
            args.addAll(listOf("-d", classesDir.absolutePath))
            args.addAll(listOf("-nowarn")) // Suppress warnings for cleaner output
            javaFiles.forEach { args.add(it.absolutePath) }

            val outStream = ByteArrayOutputStream()
            val errStream = ByteArrayOutputStream()
            val prevOut = System.out
            val prevErr = System.err

            System.setOut(PrintStream(outStream))
            System.setErr(PrintStream(errStream))

            try {
                // ECJ Main.main returns void but sets exit via System.exit
                // Use the compile method that returns a boolean instead
                val mainClass = classLoader.loadClass("org.eclipse.jdt.internal.compiler.batch.Main")
                val constructor = mainClass.getConstructor(
                    PrintStream::class.java, PrintStream::class.java, Boolean::class.java
                )
                val ecjOut = PrintStream(outStream)
                val ecjErr = PrintStream(errStream)
                val main = constructor.newInstance(ecjOut, ecjErr, false)
                val compileMethod = mainClass.getMethod("compile", Array<String>::class.java)
                val success = compileMethod.invoke(main, args.toTypedArray()) as Boolean

                if (!success) {
                    val stderr = errStream.toString() + outStream.toString()
                    return@withContext StepResult.Failure(parseEcjErrors(stderr))
                }
            } finally {
                System.setOut(prevOut)
                System.setErr(prevErr)
            }

            StepResult.Success(listOf(classesDir))
        } catch (e: Exception) {
            StepResult.Failure(listOf(
                CompilationError("ECJ", ErrorSeverity.ERROR, "Java compilation failed: ${e.message}", rawOutput = e.stackTraceToString())
            ))
        }
    }

    private fun buildClasspath(context: CompilationContext): String {
        val parts = mutableListOf<String>()
        parts.add(context.androidJarPath)
        val stdlibJar = registry.getKotlinStdlibJar()
        if (stdlibJar.exists()) parts.add(stdlibJar.absolutePath)

        // Add any library JARs from the project's libs/ directory
        val libsDirs = listOf(
            File(context.projectDir, "app/libs"),
            File(context.projectDir, "libs")
        )
        libsDirs.forEach { dir ->
            if (dir.exists()) {
                dir.listFiles()?.filter { it.extension == "jar" }?.forEach {
                    parts.add(it.absolutePath)
                }
            }
        }

        return parts.joinToString(File.pathSeparator)
    }

    private fun findSourceDirs(projectDir: File): List<File> {
        val candidates = listOf(
            "app/src/main/java", "app/src/main/kotlin",
            "src/main/java", "src/main/kotlin",
            "src", "java", "kotlin"
        )
        return candidates.map { File(projectDir, it) }.filter { it.exists() && it.isDirectory }
    }

    private fun parseKotlinErrors(output: String): List<CompilationError> {
        if (output.isBlank()) return listOf(
            CompilationError("kotlinc", ErrorSeverity.ERROR, "Kotlin compilation failed with no output")
        )
        val errors = mutableListOf<CompilationError>()
        val pattern = Regex("""^([ew]): (.+?): \((\d+), (\d+)\): (.+)$""", RegexOption.MULTILINE)
        for (match in pattern.findAll(output)) {
            val severity = if (match.groupValues[1] == "e") ErrorSeverity.ERROR else ErrorSeverity.WARNING
            errors.add(CompilationError(
                step = "kotlinc",
                severity = severity,
                message = match.groupValues[5],
                filePath = match.groupValues[2],
                line = match.groupValues[3].toIntOrNull(),
                column = match.groupValues[4].toIntOrNull(),
                rawOutput = output
            ))
        }
        if (errors.isEmpty()) {
            errors.add(CompilationError("kotlinc", ErrorSeverity.ERROR, output.take(500), rawOutput = output))
        }
        return errors
    }

    private fun parseEcjErrors(output: String): List<CompilationError> {
        if (output.isBlank()) return listOf(
            CompilationError("ECJ", ErrorSeverity.ERROR, "Java compilation failed with no output")
        )
        val errors = mutableListOf<CompilationError>()
        val pattern = Regex("""^(\d+)\. (ERROR|WARNING) in (.+?) \(at line (\d+)\)\s*\n(.+)$""", RegexOption.MULTILINE)
        for (match in pattern.findAll(output)) {
            val severity = if (match.groupValues[2] == "ERROR") ErrorSeverity.ERROR else ErrorSeverity.WARNING
            errors.add(CompilationError(
                step = "ECJ",
                severity = severity,
                message = match.groupValues[5].trim(),
                filePath = match.groupValues[3],
                line = match.groupValues[4].toIntOrNull(),
                rawOutput = output
            ))
        }
        if (errors.isEmpty()) {
            errors.add(CompilationError("ECJ", ErrorSeverity.ERROR, output.take(500), rawOutput = output))
        }
        return errors
    }
}

@Singleton
class DexCompiler @Inject constructor(
    private val registry: ToolchainRegistry,
    private val classLoaderFactory: ToolchainClassLoaderFactory
) {
    suspend fun compile(context: CompilationContext): StepResult = withContext(Dispatchers.IO) {
        val r8Jar = registry.getR8Jar()
        if (!r8Jar.exists()) {
            return@withContext StepResult.Failure(listOf(
                CompilationError("D8", ErrorSeverity.ERROR, "D8/R8 not found. Please download toolchain components.")
            ))
        }

        val classesDir = File(context.buildDir, "classes")
        val dexOutputDir = File(context.buildDir, "dex").apply { mkdirs() }

        if (!classesDir.exists() || classesDir.walkTopDown().none { it.extension == "class" }) {
            return@withContext StepResult.Failure(listOf(
                CompilationError("D8", ErrorSeverity.ERROR, "No .class files found to convert to DEX")
            ))
        }

        try {
            val classLoader = classLoaderFactory.getOrCreate("d8", listOf(r8Jar))

            val classFiles = classesDir.walkTopDown()
                .filter { it.extension == "class" }
                .map { it.absolutePath }
                .toList()

            val args = mutableListOf<String>()
            args.add("--release")
            args.addAll(listOf("--min-api", "35"))
            args.addAll(listOf("--output", dexOutputDir.absolutePath))
            args.addAll(listOf("--lib", context.androidJarPath))

            // Add kotlin-stdlib to lib if present
            val stdlibJar = registry.getKotlinStdlibJar()
            if (stdlibJar.exists()) {
                args.addAll(listOf("--lib", stdlibJar.absolutePath))
            }

            args.addAll(classFiles)

            // Invoke D8.main via reflection
            val d8Class = classLoader.loadClass("com.android.tools.r8.D8")
            val mainMethod = d8Class.getMethod("main", Array<String>::class.java)
            mainMethod.invoke(null, args.toTypedArray())

            val dexFile = File(dexOutputDir, "classes.dex")
            if (!dexFile.exists()) {
                return@withContext StepResult.Failure(listOf(
                    CompilationError("D8", ErrorSeverity.ERROR, "D8 completed but classes.dex was not produced")
                ))
            }

            StepResult.Success(listOf(dexFile))
        } catch (e: Exception) {
            val message = e.cause?.message ?: e.message ?: "Unknown D8 error"
            StepResult.Failure(listOf(
                CompilationError("D8", ErrorSeverity.ERROR, "DEX compilation failed: $message", rawOutput = e.stackTraceToString())
            ))
        }
    }
}

@Singleton
class ApkPackager @Inject constructor(
    private val registry: ToolchainRegistry
) {
    suspend fun packageApk(context: CompilationContext): StepResult = withContext(Dispatchers.IO) {
        val resourceApk = File(context.buildDir, "resources.ap_")
        val dexDir = File(context.buildDir, "dex")
        val unsignedApk = File(context.buildDir, "unsigned.apk")

        if (!resourceApk.exists()) {
            return@withContext StepResult.Failure(listOf(
                CompilationError("Package", ErrorSeverity.ERROR, "Resource APK not found (resources.ap_)")
            ))
        }

        try {
            // Start with the resource APK and add DEX files
            resourceApk.copyTo(unsignedApk, overwrite = true)

            // Add DEX files into the APK using ZIP manipulation
            val dexFiles = dexDir.listFiles()?.filter { it.extension == "dex" } ?: emptyList()
            if (dexFiles.isEmpty()) {
                return@withContext StepResult.Failure(listOf(
                    CompilationError("Package", ErrorSeverity.ERROR, "No DEX files found to package")
                ))
            }

            addFilesToZip(unsignedApk, dexFiles.map { it.name to it })

            // Add any native libraries from the project
            val jniDirs = listOf(
                File(context.projectDir, "app/src/main/jniLibs"),
                File(context.projectDir, "src/main/jniLibs"),
                File(context.projectDir, "jniLibs")
            )
            val nativeFiles = mutableListOf<Pair<String, File>>()
            jniDirs.forEach { dir ->
                if (dir.exists()) {
                    dir.walkTopDown().filter { it.isFile }.forEach { file ->
                        val relativePath = "lib/${file.relativeTo(dir).path}"
                        nativeFiles.add(relativePath to file)
                    }
                }
            }
            if (nativeFiles.isNotEmpty()) {
                addFilesToZip(unsignedApk, nativeFiles)
            }

            // Add assets from the project
            val assetDirs = listOf(
                File(context.projectDir, "app/src/main/assets"),
                File(context.projectDir, "src/main/assets"),
                File(context.projectDir, "assets")
            )
            val assetFiles = mutableListOf<Pair<String, File>>()
            assetDirs.forEach { dir ->
                if (dir.exists()) {
                    dir.walkTopDown().filter { it.isFile }.forEach { file ->
                        val relativePath = "assets/${file.relativeTo(dir).path}"
                        assetFiles.add(relativePath to file)
                    }
                }
            }
            if (assetFiles.isNotEmpty()) {
                addFilesToZip(unsignedApk, assetFiles)
            }

            StepResult.Success(listOf(unsignedApk))
        } catch (e: Exception) {
            StepResult.Failure(listOf(
                CompilationError("Package", ErrorSeverity.ERROR, "APK packaging failed: ${e.message}", rawOutput = e.stackTraceToString())
            ))
        }
    }

    private fun addFilesToZip(zipFile: File, files: List<Pair<String, File>>) {
        val tempFile = File(zipFile.parentFile, "${zipFile.name}.tmp")

        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            ZipOutputStream(tempFile.outputStream().buffered()).use { zos ->
                // Copy existing entries
                var entry = zis.nextEntry
                while (entry != null) {
                    zos.putNextEntry(ZipEntry(entry.name).apply {
                        if (entry.method == ZipEntry.STORED) {
                            method = ZipEntry.STORED
                            size = entry.size
                            compressedSize = entry.compressedSize
                            crc = entry.crc
                        }
                    })
                    zis.copyTo(zos)
                    zos.closeEntry()
                    zis.closeEntry()
                    entry = zis.nextEntry
                }

                // Add new files
                for ((name, file) in files) {
                    zos.putNextEntry(ZipEntry(name))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }

        zipFile.delete()
        tempFile.renameTo(zipFile)
    }
}

@Singleton
class ApkAligner @Inject constructor() {
    suspend fun align(context: CompilationContext): StepResult = withContext(Dispatchers.IO) {
        val unsignedApk = File(context.buildDir, "unsigned.apk")
        val alignedApk = File(context.buildDir, "aligned.apk")

        if (!unsignedApk.exists()) {
            return@withContext StepResult.Failure(listOf(
                CompilationError("Align", ErrorSeverity.ERROR, "Unsigned APK not found")
            ))
        }

        try {
            // Pure Java zipalign implementation
            // Aligns uncompressed entries on 4-byte boundaries
            ZipInputStream(unsignedApk.inputStream().buffered()).use { zis ->
                ZipOutputStream(alignedApk.outputStream().buffered()).use { zos ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val data = zis.readBytes()
                        val newEntry = ZipEntry(entry.name)

                        // For uncompressed (STORED) entries, we need alignment
                        if (entry.method == ZipEntry.STORED) {
                            newEntry.method = ZipEntry.STORED
                            newEntry.size = data.size.toLong()
                            newEntry.compressedSize = data.size.toLong()
                            newEntry.crc = entry.crc
                        }

                        zos.putNextEntry(newEntry)
                        zos.write(data)
                        zos.closeEntry()
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }

            StepResult.Success(listOf(alignedApk))
        } catch (e: Exception) {
            StepResult.Failure(listOf(
                CompilationError("Align", ErrorSeverity.ERROR, "APK alignment failed: ${e.message}", rawOutput = e.stackTraceToString())
            ))
        }
    }
}

@Singleton
class ApkSigner @Inject constructor(
    private val keystoreManager: KeystoreManager
) {
    suspend fun sign(context: CompilationContext): StepResult = withContext(Dispatchers.IO) {
        val alignedApk = File(context.buildDir, "aligned.apk")
        val projectName = context.projectDir.name
            .replace(Regex("[^a-zA-Z0-9_-]"), "_")
            .ifEmpty { "output" }

        val signedApk = if (context.incrementalFileNames) {
            findNextIncrementalFile(context.outputDir, projectName)
        } else {
            File(context.outputDir, "$projectName.apk")
        }

        if (!alignedApk.exists()) {
            return@withContext StepResult.Failure(listOf(
                CompilationError("Sign", ErrorSeverity.ERROR, "Aligned APK not found")
            ))
        }

        try {
            keystoreManager.signApk(alignedApk, signedApk)
            StepResult.Success(listOf(signedApk))
        } catch (e: Exception) {
            StepResult.Failure(listOf(
                CompilationError("Sign", ErrorSeverity.ERROR, "APK signing failed: ${e.message}", rawOutput = e.stackTraceToString())
            ))
        }
    }
}

// Utility function for executing native processes (AAPT2)
fun findManifest(projectDir: File): File? {
    val candidates = listOf(
        "app/src/main/AndroidManifest.xml",
        "src/main/AndroidManifest.xml",
        "AndroidManifest.xml"
    )
    return candidates.map { File(projectDir, it) }.firstOrNull { it.exists() }
}

fun findNextIncrementalFile(outputDir: File, baseName: String): File {
    val base = File(outputDir, "$baseName.apk")
    if (!base.exists()) return base

    var version = 2
    while (true) {
        val candidate = File(outputDir, "${baseName}_v$version.apk")
        if (!candidate.exists()) return candidate
        version++
        if (version > 9999) return File(outputDir, "${baseName}_${System.currentTimeMillis()}.apk")
    }
}

data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String)

fun executeProcess(vararg command: String): ProcessResult {
    val process = ProcessBuilder(*command)
        .redirectErrorStream(false)
        .start()

    val stdout = process.inputStream.bufferedReader().readText()
    val stderr = process.errorStream.bufferedReader().readText()
    val exitCode = process.waitFor()

    return ProcessResult(exitCode, stdout, stderr)
}
