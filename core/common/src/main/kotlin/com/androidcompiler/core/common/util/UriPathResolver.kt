package com.androidcompiler.core.common.util

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

/**
 * Resolves content:// URIs (from SAF OpenDocumentTree) to real filesystem paths.
 * Falls back to a reasonable app-owned directory if resolution fails.
 */
object UriPathResolver {

    /**
     * Attempts to convert a SAF tree URI to a real filesystem path.
     * Returns null if the URI can't be resolved to a file path.
     */
    fun resolveTreeUri(context: Context, uriString: String): File? {
        // If it's already a file path, use it directly
        if (!uriString.startsWith("content://")) {
            val file = File(uriString)
            if (file.isAbsolute) return file
        }

        return try {
            val uri = Uri.parse(uriString)
            resolveDocumentUri(context, uri)
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveDocumentUri(context: Context, uri: Uri): File? {
        // Handle DocumentsProvider URIs
        if (!DocumentsContract.isTreeUri(uri)) return null

        val docId = DocumentsContract.getTreeDocumentId(uri)
        val authority = uri.authority

        return when (authority) {
            "com.android.externalstorage.documents" -> {
                // External storage - docId is "primary:path/to/folder" or "XXXX-XXXX:path"
                val parts = docId.split(":", limit = 2)
                val storageId = parts[0]
                val relativePath = parts.getOrElse(1) { "" }

                val rootDir = if (storageId.equals("primary", ignoreCase = true)) {
                    Environment.getExternalStorageDirectory()
                } else {
                    // SD card or other volume
                    File("/storage/$storageId")
                }

                if (relativePath.isNotEmpty()) {
                    File(rootDir, relativePath)
                } else {
                    rootDir
                }
            }
            "com.android.providers.downloads.documents" -> {
                // Downloads provider
                val downloadsDir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
                )
                if (docId.startsWith("raw:")) {
                    File(docId.removePrefix("raw:"))
                } else {
                    downloadsDir
                }
            }
            else -> null
        }
    }

    /**
     * Given a stored folder setting (which might be a content URI or a file path),
     * returns a usable File directory. Falls back to the app's external files dir.
     */
    fun resolveOutputDir(context: Context, storedPath: String?): File {
        if (storedPath == null) {
            return File(context.getExternalFilesDir(null), "output").apply { mkdirs() }
        }

        val resolved = resolveTreeUri(context, storedPath)
        if (resolved != null && (resolved.exists() || resolved.mkdirs())) {
            return resolved
        }

        // Fallback to app's own external directory
        return File(context.getExternalFilesDir(null), "output").apply { mkdirs() }
    }
}
