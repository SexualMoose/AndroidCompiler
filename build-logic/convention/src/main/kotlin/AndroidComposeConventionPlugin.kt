import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("org.jetbrains.kotlin.plugin.compose")
            }

            val extension = extensions.findByType(com.android.build.gradle.LibraryExtension::class.java)
                ?: extensions.findByType(com.android.build.gradle.internal.dsl.BaseAppModuleExtension::class.java)

            (extension as? CommonExtension<*, *, *, *, *, *>)?.apply {
                buildFeatures {
                    compose = true
                }
            }

            dependencies {
                val bom = catalog.findLibrary("compose-bom").get()
                add("implementation", platform(bom))
                add("implementation", catalog.findLibrary("compose-ui").get())
                add("implementation", catalog.findLibrary("compose-ui-graphics").get())
                add("implementation", catalog.findLibrary("compose-ui-tooling-preview").get())
                add("implementation", catalog.findLibrary("compose-material3").get())
                add("implementation", catalog.findLibrary("compose-material-icons").get())
                add("debugImplementation", catalog.findLibrary("compose-ui-tooling").get())
            }
        }
    }
}

private val Project.catalog
    get() = extensions.getByType(org.gradle.api.artifacts.VersionCatalogsExtension::class.java)
        .named("libs")
