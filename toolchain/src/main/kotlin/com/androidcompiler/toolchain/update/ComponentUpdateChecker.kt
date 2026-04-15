package com.androidcompiler.toolchain.update

import com.androidcompiler.core.common.model.ComponentType
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
        registry.getComponents()
            .filter { it.type != ComponentType.JDK_ARCHIVE } // JDK updates handled separately
            .map { component ->
                async(Dispatchers.IO) { checkComponent(component) }
            }.awaitAll()
    }

    /**
     * Apply a single component update with rollback on failure.
     * Backs up the old file, downloads new version, restores backup if download fails.
     */
    suspend fun applyUpdate(
        updateInfo: UpdateInfo,
        onProgress: (Float) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val updated = updateInfo.updatedComponent
            ?: return@withContext Result.failure(Exception("No updated component info"))

        val component = registry.getComponents().firstOrNull { it.id == updateInfo.componentId }
            ?: return@withContext Result.failure(Exception("Component not found in registry"))

        val targetFile = registry.getComponentFile(component)
        val backupFile = File(targetFile.parentFile, "${targetFile.name}.backup")

        // Step 1: Backup the existing file
        try {
            if (targetFile.exists()) {
                if (targetFile.isDirectory) {
                    // For JDK directory, skip complex backup
                    targetFile.renameTo(backupFile)
                } else {
                    targetFile.copyTo(backupFile, overwrite = true)
                }
            }
        } catch (e: Exception) {
            return@withContext Result.failure(Exception("Failed to backup existing component: ${e.message}"))
        }

        // Step 2: Delete old file and download new version
        try {
            if (targetFile.exists()) {
                if (targetFile.isDirectory) targetFile.deleteRecursively() else targetFile.delete()
            }

            val result = downloadManager.downloadComponent(updated, onProgress)
            result.getOrThrow()

            // Step 3: Success — save the installed version and remove backup
            registry.saveInstalledVersion(updateInfo.componentId, updated.version)
            if (backupFile.exists()) {
                if (backupFile.isDirectory) backupFile.deleteRecursively() else backupFile.delete()
            }

            Result.success(Unit)
        } catch (e: Exception) {
            // Step 4: Rollback — restore the backup
            try {
                if (targetFile.exists()) {
                    if (targetFile.isDirectory) targetFile.deleteRecursively() else targetFile.delete()
                }
                if (backupFile.exists()) {
                    backupFile.renameTo(targetFile)
                }
            } catch (_: Exception) {
                // Rollback itself failed — component may be in broken state
            }
            Result.failure(Exception("Update failed (rolled back): ${e.message}"))
        }
    }

    /**
     * Apply all available updates sequentially with rollback per-component.
     */
    suspend fun applyAllUpdates(
        updates: List<UpdateInfo>,
        onComponentProgress: (componentId: String, progress: Float) -> Unit,
        onComponentComplete: (componentId: String, success: Boolean, error: String?) -> Unit
    ) = withContext(Dispatchers.IO) {
        for (update in updates.filter { it.hasUpdate }) {
            val result = applyUpdate(update) { progress ->
                onComponentProgress(update.componentId, progress)
            }
            onComponentComplete(
                update.componentId,
                result.isSuccess,
                result.exceptionOrNull()?.message
            )
        }
    }

    private fun checkComponent(component: ToolchainComponent): UpdateInfo {
        // Use the *installed* version, not the registry's hardcoded version
        val installedVersion = registry.getInstalledVersion(component.id) ?: component.version

        val latestVersion = try {
            when {
                component.sources.any { it.mirror == "maven_central" } ->
                    checkMavenCentral(component)
                component.sources.any { it.mirror == "google_maven" } ->
                    checkGoogleMaven(component)
                component.sources.any { it.mirror == "github" } ->
                    checkGitHub(component)
                else -> null
            }
        } catch (_: Exception) {
            null
        }

        val hasUpdate = latestVersion != null
                && latestVersion != installedVersion
                && compareVersions(latestVersion, installedVersion) > 0

        val updatedComponent = if (hasUpdate && latestVersion != null) {
            component.copy(
                version = latestVersion,
                sources = component.sources.map { source ->
                    source.copy(
                        url = source.url.replace(component.version, latestVersion)
                    )
                }
            )
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

    private fun checkMavenCentral(component: ToolchainComponent): String? {
        val url = component.sources.first { it.mirror == "maven_central" }.url
        val parts = url.removePrefix("https://repo1.maven.org/maven2/").split("/")
        if (parts.size < 3) return null

        val artifactIndex = parts.indexOfLast { it.contains(".jar") } - 1
        if (artifactIndex < 1) return null
        val artifact = parts[artifactIndex - 1]
        val group = parts.subList(0, artifactIndex - 1).joinToString(".")

        val searchUrl = "https://search.maven.org/solrsearch/select?q=g:\"$group\"+AND+a:\"$artifact\"&rows=1&wt=json"
        val request = Request.Builder().url(searchUrl).build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return null
        response.close()

        return try {
            val json = JSONObject(body)
            val docs = json.getJSONObject("response").getJSONArray("docs")
            if (docs.length() > 0) docs.getJSONObject(0).getString("latestVersion") else null
        } catch (_: Exception) { null }
    }

    private fun checkGoogleMaven(component: ToolchainComponent): String? {
        val url = component.sources.first { it.mirror == "google_maven" }.url
        val parts = url.removePrefix("https://dl.google.com/android/maven2/").split("/")
        if (parts.size < 3) return null

        val groupPath = parts.dropLast(2).joinToString("/")
        val metadataUrl = "https://dl.google.com/android/maven2/$groupPath/maven-metadata.xml"
        val request = Request.Builder().url(metadataUrl).build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return null
        response.close()

        val latestRegex = Regex("<latest>(.+?)</latest>")
        val versionRegex = Regex("<version>(.+?)</version>")
        latestRegex.find(body)?.groupValues?.get(1)?.let { return it }
        versionRegex.findAll(body).lastOrNull()?.groupValues?.get(1)?.let { return it }
        return null
    }

    private fun checkGitHub(component: ToolchainComponent): String? {
        val url = component.sources.first { it.mirror == "github" }.url
        val match = Regex("github.com/([^/]+)/([^/]+)").find(url) ?: return null
        val owner = match.groupValues[1]
        val repo = match.groupValues[2]

        val apiUrl = "https://api.github.com/repos/$owner/$repo/releases/latest"
        val request = Request.Builder().url(apiUrl)
            .header("Accept", "application/vnd.github+json")
            .build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return null
        response.close()

        return try {
            JSONObject(body).optString("tag_name")?.removePrefix("v")
        } catch (_: Exception) { null }
    }

    private fun compareVersions(a: String, b: String): Int {
        val partsA = a.split(Regex("[.\\-+]")).map { it.toIntOrNull() ?: 0 }
        val partsB = b.split(Regex("[.\\-+]")).map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(partsA.size, partsB.size)
        for (i in 0 until maxLen) {
            val va = partsA.getOrElse(i) { 0 }
            val vb = partsB.getOrElse(i) { 0 }
            if (va != vb) return va.compareTo(vb)
        }
        return 0
    }
}
