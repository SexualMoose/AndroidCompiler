import com.android.build.gradle.internal.dsl.BaseAppModuleExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register

/**
 * Registers the `prepareNativeBinaries` task on the application module and wires
 * its output directory as an additional jniLibs source dir. Usage:
 *
 * ```kotlin
 * plugins {
 *     id("androidcompiler.native.binaries")
 * }
 *
 * nativeBinaries {
 *     packageUrls.set(listOf("https://.../aapt2.deb", "https://.../openjdk-17.deb"))
 * }
 * ```
 */
class AndroidNativeBinariesConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            val ext = extensions.create("nativeBinaries", NativeBinariesExtension::class.java)

            // Offline-first: committed prebuilt launcher binaries, laid out as
            // <rootProject>/prebuilts/native-jniLibs/<abi>/lib*.so. Seeded into the
            // task output before any network access so a fresh clone builds even
            // when Termux is unreachable or has rotated its package versions. A
            // fileTree of a missing dir is simply empty (no error).
            val prebuilts = rootProject.fileTree("prebuilts/native-jniLibs")

            val prepareTask = tasks.register<PrepareNativeBinariesTask>("prepareNativeBinaries") {
                outputDir.set(layout.buildDirectory.dir("generated/native-jniLibs"))
                packagesByAbi.set(ext.packagesByAbi)
                prebuiltFiles.from(prebuilts)
            }

            // Wire the generated output into jniLibs source dirs.
            extensions.configure<BaseAppModuleExtension> {
                sourceSets.getByName("main").jniLibs.srcDir(
                    prepareTask.flatMap { it.outputDir }
                )
            }

            // Belt-and-suspenders: ensure any merge*JniLibFolders task runs after
            // our prepare task. Without this, with certain AGP versions the
            // generated files exist on disk but aren't picked up because the
            // jniLibs source dir was registered after source set finalization.
            afterEvaluate {
                tasks.matching { t ->
                    t.name.startsWith("merge") && t.name.contains("JniLibFolders")
                }.configureEach { dependsOn(prepareTask) }
                tasks.matching { t ->
                    t.name.startsWith("package") && (t.name.endsWith("Debug") || t.name.endsWith("Release"))
                }.configureEach { dependsOn(prepareTask) }
            }
        }
    }
}

abstract class NativeBinariesExtension {
    /** ABI → list of .deb URLs for that ABI. e.g. "arm64-v8a" → [aapt2, openjdk-17]. */
    abstract val packagesByAbi: org.gradle.api.provider.MapProperty<String, List<String>>
}
