package com.androidcompiler.toolchain.update

import com.androidcompiler.core.common.model.ComponentType
import com.androidcompiler.core.common.model.DownloadSource
import com.androidcompiler.core.common.model.ToolchainComponent
import com.androidcompiler.toolchain.download.ComponentDownloadManager
import com.androidcompiler.toolchain.registry.ToolchainRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class UpdateInfo(
    val componentId: String,
    val displayName: String,
    val currentVersion: String,
    val latestVersion: String?,
    val hasUpdate: Boolean,
    val updatedComponent: ToolchainComponent? = null
)

/**
 * Only these components can be safely auto-updated because they have
 * predictable Maven Central URL patterns where version substitution works.
 *
 * Components excluded:
 * - aapt2: URL contains a build number suffix (8.7.3-12006047) that changes per version
 * - android-jar: version is an API level (35), not semver, and URL structure differs
 * - gradle-wrapper: tiny file, compatibility-sensitive, URL is to raw GitHub
 * - kotlin-script-runtime: must match kotlinc version exactly (updated as a group)
 */
private val UPDATABLE_COMPONENTS = setOf("ecj", "kotlinc", "kotlin-stdlib", "d8")

/**
 * Kotlin components that must be updated together to the same version.
 */
private val KOTLIN_GROUP = setOf("kotlinc", "kotlin-stdlib", "kotlin-script-runtime")

@Singleton
class ComponentUpdateChecker @Inject constructor(
    private val registry: ToolchainRegistry,
    private val downloadManager: ComponentDownloadManager
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun checkAll(): List<UpdateInfo> = coroutineScope {
        val components = registry.getComponents()
        val updatable = components.filter { it.id in UPDATABLE_COMPONENTS }

        val results = updatable.map { component ->
            async(Dispatchers.IO) { checkComponent(component) }
        }.awaitAll()

        // If kotlinc has an update, also mark kotlin-stdlib and kotlin-script-runtime
        val kotlinUpdate = results.find { it.componentId == "kotlinc" && it.hasUpdate }
        val finalResults = results.toMutableList()

        if (kotlinUpdate != null) {
            // Add kotlin-script-runtime as needing update to same version
            val scriptRuntime = components.find { it.id == "kotlin-script-runtime" }
            if (scriptRuntime != null) {
                finalResults.add(UpdateInfo(
                    componentId = "kotlin-script-runtime",
                    displayName = scriptRuntime.displayName,
                    currentVersion = registry.getInstalledVersion(scriptRuntime.id) ?: scriptRuntime.version,
                    latestVersion = kotlinUpdate.latestVersion,
                    hasUpdate = true,
                    updatedComponent = buildUpdatedComponent(scriptRuntime, kotlinUpdate.latestVersion!!)
                ))
            }
        }

        // Add non-updatable components as "up to date" for display
        val checkedIds = finalResults.map { it.componentId }.toSet()
        components.filter { it.id !in checkedIds }.forEach { component ->
            finalResults.add(UpdateInfo(
                componentId = component.id,
                displayName = component.displayName,
                currentVersion = registry.getInstalledVersion(component.id) ?: component.version,
                latestVersion = null,
                hasUpdate = false
            ))
        }

        finalResults
    }

    /**
     * Apply a single component update with rollback on failure.
     */
    suspend fun applyUpdate(
        updateInfo: UpdateInfo,
        onProgress: (Float) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val updated = updateInfo.updatedComponent
            ?: return@withContext Result.failure(Exception("No updated component info"))

        val component = registry.getComponents().firstOrNull { it.id == updateInfo.componentId }
            ?: return@withContext Result.failure(Exception("Component not found"))

        val targetFile = registry.getComponentFile(component)
        val backupFile = File(targetFile.parentFile, "${targetFile.name}.backup")

        // Backup
        try {
            if (targetFile.exists()) {
                targetFile.copyTo(backupFile, overwrite = true)
            }
        } catch (e: Exception) {
            return@withContext Result.failure(Exception("Backup failed: ${e.message}"))
        }

        // Download new version
        try {
            if (targetFile.exists()) targetFile.delete()
            downloadManager.downloadComponent(updated, onProgress).getOrThrow()
            registry.saveInstalledVersion(updateInfo.componentId, updated.version)
            backupFile.delete()
            Result.success(Unit)
        } catch (e: Exception) {
            // Rollback
            try {
                if (targetFile.exists()) targetFile.delete()
                if (backupFile.exists()) backupFile.renameTo(targetFile)
            } catch (_: Exception) {}
            Result.failure(Exception("Download failed, rolled back: ${e.message}"))
        }
    }

    /**
     * Apply all available updates. Each update is independent — one failure
     * does not prevent others from being applied.
     */
    suspend fun applyAllUpdates(
        updates: List<UpdateInfo>,
        onComponentProgress: (componentId: String, progress: Float) -> Unit,
        onComponentComplete: (componentId: String, success: Boolean, error: String?) -> Unit
    ) = withContext(Dispatchers.IO) {
        val toUpdate = updates.filter { it.hasUpdate && it.updatedComponent != null }
        for (update in toUpdate) {
            try {
                val result = applyUpdate(update) { progress ->
                    onComponentProgress(update.componentId, progress)
                }
                onComponentComplete(update.componentId, result.isSuccess, result.exceptionOrNull()?.message)
            } catch (e: Exception) {
                onComponentComplete(update.componentId, false, e.message)
                // Continue with next component — don't let one failure block others
            }
        }
    }

    private fun checkComponent(component: ToolchainComponent): UpdateInfo {
        val installedVersion = registry.getInstalledVersion(component.id) ?: component.version

        val latestVersion = try {
            when (component.id) {
                "ecj" -> checkMavenCentral("org.eclipse.jdt", "ecj")
                "kotlinc" -> checkMavenCentral("org.jetbrains.kotlin", "kotlin-compiler-embeddable")
                "kotlin-stdlib" -> checkMavenCentral("org.jetbrains.kotlin", "kotlin-stdlib")
                "d8" -> checkGoogleMaven("com/android/tools", "r8")
                else -> null
            }
        } catch (_: Exception) {
            null
        }

        val hasUpdate = latestVersion != null
                && latestVersion != installedVersion
                && isNewerVersion(latestVersion, installedVersion)

        val updatedComponent = if (hasUpdate && latestVersion != null) {
            buildUpdatedComponent(component, latestVersion)
        } else null

        return UpdateInfo(
            componentId = component.id,
            displayName = component.displayName,
            currentVersion = installedVersion,
            latestVersion = latestVersion,
            hasUpdate = hasUpdate,
            updatedComponent = updatedComponent
        )
    }

    /**
     * Builds an updated component with correct download URLs for the new version.
     * Uses explicit URL construction per component instead of fragile string replacement.
     */
    private fun buildUpdatedComponent(component: ToolchainComponent, newVersion: String): ToolchainComponent? {
        val newSources = when (component.id) {
            "ecj" -> listOf(
                DownloadSource("https://repo1.maven.org/maven2/org/eclipse/jdt/ecj/$newVersion/ecj-$newVersion.jar", "maven_central", 1)
            )
            "kotlinc" -> listOf(
                DownloadSource("https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-compiler-embeddable/$newVersion/kotlin-compiler-embeddable-$newVersion.jar", "maven_central", 1)
            )
            "kotlin-stdlib" -> listOf(
                DownloadSource("https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/$newVersion/kotlin-stdlib-$newVersion.jar", "maven_central", 1)
            )
            "kotlin-script-runtime" -> listOf(
                DownloadSource("https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-script-runtime/$newVersion/kotlin-script-runtime-$newVersion.jar", "maven_central", 1)
            )
            "d8" -> listOf(
                DownloadSource("https://dl.google.com/android/maven2/com/android/tools/r8/$newVersion/r8-$newVersion.jar", "google_maven", 1)
            )
            else -> return null
        }

        return component.copy(version = newVersion, sources = newSources)
    }

    /**
     * Query Maven Central for the latest version of a specific group:artifact.
     */
    private fun checkMavenCentral(group: String, artifact: String): String? {
        val searchUrl = "https://search.maven.org/solrsearch/select?q=g:%22$group%22+AND+a:%22$artifact%22&rows=1&wt=json"
        val request = Request.Builder().url(searchUrl).build()
        val response = client.newCall(request).execute()
        val body = response.body?.string()
        response.close()
        if (body == null) return null

        return try {
            val json = JSONObject(body)
            val docs = json.getJSONObject("response").getJSONArray("docs")
            if (docs.length() > 0) docs.getJSONObject(0).getString("latestVersion") else null
        } catch (_: Exception) { null }
    }

    /**
     * Query Google Maven for the latest version via maven-metadata.xml.
     */
    private fun checkGoogleMaven(groupPath: String, artifact: String): String? {
        val metadataUrl = "https://dl.google.com/android/maven2/$groupPath/$artifact/maven-metadata.xml"
        val request = Request.Builder().url(metadataUrl).build()
        val response = client.newCall(request).execute()
        val body = response.body?.string()
        response.close()
        if (body == null) return null

        // Parse <latest> or last <version> from metadata
        val latestRegex = Regex("<latest>(.+?)</latest>")
        latestRegex.find(body)?.groupValues?.get(1)?.let { return it }

        val versionRegex = Regex("<version>(.+?)</version>")
        return versionRegex.findAll(body).lastOrNull()?.groupValues?.get(1)
    }

    /**
     * Conservative version comparison. Returns true only if both versions
     * are valid semver-ish and a is clearly newer than b.
     */
    private fun isNewerVersion(candidate: String, current: String): Boolean {
        // Reject versions with non-numeric parts we can't compare
        val candidateParts = candidate.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }

        // If either version couldn't be fully parsed, don't report an update
        if (candidateParts.size < 2 || currentParts.size < 2) return false
        if (candidateParts.size != candidate.split(".").size) return false
        if (currentParts.size != current.split(".").size) return false

        val maxLen = maxOf(candidateParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val va = candidateParts.getOrElse(i) { 0 }
            val vb = currentParts.getOrElse(i) { 0 }
            if (va != vb) return va > vb
        }
        return false
    }
}
