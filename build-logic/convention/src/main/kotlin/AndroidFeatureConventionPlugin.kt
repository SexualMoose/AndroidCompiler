import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("androidcompiler.android.library")
                apply("androidcompiler.android.compose")
                apply("androidcompiler.android.hilt")
            }

            dependencies {
                add("implementation", project(":core:common"))
                add("implementation", project(":core:ui"))
                add("implementation", project(":core:data"))
                add("implementation", catalog.findLibrary("lifecycle-runtime-compose").get())
                add("implementation", catalog.findLibrary("lifecycle-viewmodel-compose").get())
                add("implementation", catalog.findLibrary("navigation-compose").get())
                add("implementation", catalog.findLibrary("hilt-navigation-compose").get())
            }
        }
    }
}

private val Project.catalog
    get() = extensions.getByType(org.gradle.api.artifacts.VersionCatalogsExtension::class.java)
        .named("libs")
