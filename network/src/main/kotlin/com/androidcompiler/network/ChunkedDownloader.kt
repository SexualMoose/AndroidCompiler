package com.androidcompiler.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChunkedDownloader @Inject constructor(
    private val multiNetworkManager: MultiNetworkManager
) {
    private val baseClient = OkHttpClient.Builder().build()

    suspend fun download(
        url: String,
        outputFile: File,
        chunkSize: Long = 4 * 1024 * 1024,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            // Get file size via HEAD request. Non-2xx means a 404 error page or
            // similar — don't cache the response body as the file contents.
            val headRequest = Request.Builder().url(url).head().build()
            val headResponse = baseClient.newCall(headRequest).execute()
            if (!headResponse.isSuccessful) {
                headResponse.close()
                return@withContext Result.failure(Exception(
                    "HTTP ${headResponse.code} for $url — server returned error, not the expected file"
                ))
            }
            val contentLength = headResponse.header("Content-Length")?.toLongOrNull() ?: -1
            val acceptsRanges = headResponse.header("Accept-Ranges") == "bytes"
            headResponse.close()

            if (contentLength <= 0 || !acceptsRanges) {
                // Fallback: simple single-stream download
                return@withContext downloadSingle(url, outputFile, onProgress)
            }

            // Split into chunks and download in parallel across networks
            val networks = multiNetworkManager.availableNetworks.value
            val numChunks = ((contentLength + chunkSize - 1) / chunkSize).toInt()
            val downloadedBytes = AtomicLong(0)

            outputFile.parentFile?.mkdirs()
            RandomAccessFile(outputFile, "rw").use { raf ->
                raf.setLength(contentLength)
            }

            coroutineScope {
                val chunks = (0 until numChunks).map { i ->
                    val start = i * chunkSize
                    val end = minOf(start + chunkSize - 1, contentLength - 1)
                    Triple(i, start, end)
                }

                chunks.map { (index, start, end) ->
                    async(Dispatchers.IO) {
                        val network = if (networks.isNotEmpty()) {
                            networks[index % networks.size]
                        } else null

                        val client = if (network != null) {
                            baseClient.newBuilder()
                                .socketFactory(network.network.socketFactory)
                                .build()
                        } else baseClient

                        val request = Request.Builder()
                            .url(url)
                            .header("Range", "bytes=$start-$end")
                            .build()

                        val response = client.newCall(request).execute()
                        val body = response.body ?: throw Exception("Empty response body")

                        RandomAccessFile(outputFile, "rw").use { raf ->
                            raf.seek(start)
                            val buffer = ByteArray(8192)
                            var bytesRead: Int
                            body.byteStream().use { input ->
                                while (input.read(buffer).also { bytesRead = it } != -1) {
                                    raf.write(buffer, 0, bytesRead)
                                    val total = downloadedBytes.addAndGet(bytesRead.toLong())
                                    onProgress(total, contentLength)
                                }
                            }
                        }
                        response.close()
                    }
                }.awaitAll()
            }

            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun downloadSingle(
        url: String,
        outputFile: File,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): Result<File> {
        return try {
            val request = Request.Builder().url(url).build()
            val response = baseClient.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                throw Exception("HTTP ${response.code} for $url — server returned an error page, not the expected file")
            }
            val body = response.body ?: throw Exception("Empty response body")
            val total = body.contentLength()

            outputFile.parentFile?.mkdirs()
            var downloaded = 0L
            outputFile.outputStream().use { out ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                body.byteStream().use { input ->
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        out.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        onProgress(downloaded, total)
                    }
                }
            }
            response.close()
            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
