package com.androidcompiler.toolchain.update

import com.androidcompiler.core.common.model.ToolchainComponent
import com.androidcompiler.toolchain.registry.ToolchainRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class UpdateInfo(
    val componentId: String,
    val currentVersion: String,
    val latestVersion: String?,
    val hasUpdate: Boolean
)

@Singleton
class ComponentUpdateChecker @Inject constructor(
    private val registry: ToolchainRegistry
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun checkAll(): List<UpdateInfo> = coroutineScope {
        registry.getComponents().map { component ->
            async(Dispatchers.IO) { checkComponent(component) }
        }.awaitAll()
    }

    private fun checkComponent(component: ToolchainComponent): UpdateInfo {
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

        return UpdateInfo(
            componentId = component.id,
            currentVersion = component.version,
            latestVersion = latestVersion,
            hasUpdate = latestVersion != null && latestVersion != component.version
                    && compareVersions(latestVersion, component.version) > 0
        )
    }

    private fun checkMavenCentral(component: ToolchainComponent): String? {
        val url = component.sources.first { it.mirror == "maven_central" }.url
        // Extract group:artifact from URL pattern
        // e.g., https://repo1.maven.org/maven2/org/eclipse/jdt/ecj/3.39.0/ecj-3.39.0.jar
        val parts = url.removePrefix("https://repo1.maven.org/maven2/")
            .split("/")
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

        val json = JSONObject(body)
        val docs = json.getJSONObject("response").getJSONArray("docs")
        if (docs.length() > 0) {
            return docs.getJSONObject(0).getString("latestVersion")
        }
        return null
    }

    private fun checkGoogleMaven(component: ToolchainComponent): String? {
        // Google Maven uses a group-index.xml approach
        // For simplicity, check the known URL pattern
        val url = component.sources.first { it.mirror == "google_maven" }.url
        val parts = url.removePrefix("https://dl.google.com/android/maven2/")
            .split("/")
        if (parts.size < 3) return null

        val groupPath = parts.dropLast(2).joinToString("/")
        val metadataUrl = "https://dl.google.com/android/maven2/$groupPath/maven-metadata.xml"
        val request = Request.Builder().url(metadataUrl).build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return null
        response.close()

        // Simple XML parsing for <latest> or last <version>
        val latestRegex = Regex("<latest>(.+?)</latest>")
        val versionRegex = Regex("<version>(.+?)</version>")

        latestRegex.find(body)?.groupValues?.get(1)?.let { return it }
        versionRegex.findAll(body).lastOrNull()?.groupValues?.get(1)?.let { return it }
        return null
    }

    private fun checkGitHub(component: ToolchainComponent): String? {
        // Extract owner/repo from GitHub URL
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

        val json = JSONObject(body)
        return json.optString("tag_name")?.removePrefix("v")
    }

    private fun compareVersions(a: String, b: String): Int {
        val partsA = a.split(".").map { it.toIntOrNull() ?: 0 }
        val partsB = b.split(".").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(partsA.size, partsB.size)
        for (i in 0 until maxLen) {
            val va = partsA.getOrElse(i) { 0 }
            val vb = partsB.getOrElse(i) { 0 }
            if (va != vb) return va.compareTo(vb)
        }
        return 0
    }
}
