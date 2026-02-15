package com.aerith.core.blossom

import android.content.Context
import android.net.Uri
import android.util.Log
import com.aerith.core.nostr.BlossomAuthHelper
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class BlossomRepository(private val context: Context) {

    private data class ProcessedFile(val bytes: ByteArray, val hash: String, val size: Long)

    private fun normalizeUrl(url: String): String {
        return url.removeSuffix("/").lowercase()
    }

    val client = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.HEADERS })
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .build()

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val listAdapter = moshi.adapter<List<BlossomBlob>>(
        Types.newParameterizedType(List::class.java, BlossomBlob::class.java)
    )

    /**
     * Checks if a local Blossom cache is running on the device.
     * Per documentation: HEAD http://127.0.0.1:24242
     * Also checks 10.0.2.2 for emulator support.
     */
    suspend fun detectLocalBlossom(): String? = withContext(Dispatchers.IO) {
        val hosts = listOf("127.0.0.1", "10.0.2.2")
        for (host in hosts) {
            val url = "http://$host:24242"
            val request = Request.Builder()
                .url(url)
                .head()
                .build()
            
            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful || response.code == 401 || response.code == 404) {
                        Log.i("BlossomRepo", "Detected local Blossom cache at $url")
                        return@withContext url
                    }
                }
            } catch (e: Exception) {
                // Continue to next host
            }
        }
        null
    }

    /**
     * Efficiently checks if a blob exists on a server using HEAD.
     */
    suspend fun checkBlobExists(serverUrl: String, hash: String): Boolean = withContext(Dispatchers.IO) {
        val cleanServer = serverUrl.removeSuffix("/")
        val url = "$cleanServer/$hash"
        val request = Request.Builder().url(url).head().build()
        try {
            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Instructs the local cache to fetch a blob from a remote server.
     * Per documentation: GET /<sha256>?xs=<server>
     */
    suspend fun fetchToLocalCache(hash: String, sourceUrl: String, localUrl: String): Result<Unit> = withContext(Dispatchers.IO) {
        val cleanLocal = localUrl.trim().removeSuffix("/")
        val cleanHash = hash.trim().lowercase()
        
        // Extract server root from sourceUrl (e.g., https://server.com/hash.jpg -> https://server.com)
        val serverRoot = try {
            val uri = Uri.parse(sourceUrl)
            "${uri.scheme}://${uri.host}${if (uri.port != -1 && uri.port != 80 && uri.port != 443) ":${uri.port}" else ""}"
        } catch (e: Exception) {
            sourceUrl.substringBeforeLast("/")
        }.trim()

        // Include extension in path so local server knows what to fetch
        val extension = sourceUrl.substringAfterLast("/", "").let { 
            val segment = it.substringBefore("?")
            if (segment.contains(".")) "." + segment.substringAfterLast(".") else ""
        }.trim()

        val url = cleanLocal.toHttpUrlOrNull()?.newBuilder()
            ?.addPathSegment("$cleanHash$extension")
            ?.addQueryParameter("xs", serverRoot)
            ?.build() ?: throw Exception("Invalid local URL: $cleanLocal")

        Log.d("BlossomRepo", "Local cache proxy-fetch: $url")
        val request = Request.Builder().url(url).get().build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) Result.success(Unit)
                else Result.failure(Exception("Local cache fetch failed: ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getFilesFromServers(
        pubkey: String, 
        servers: List<String>,
        authHeaders: Map<String, String> = emptyMap()
    ): List<BlossomBlob> = withContext(Dispatchers.IO) {
        val normalizedHeaders = authHeaders.mapKeys { normalizeUrl(it.key) }
        val limit = 256
        
        val deferredResults = servers.map { server ->
            async {
                val cleanServer = server.removeSuffix("/")
                val normalizedServer = normalizeUrl(server)
                val isLocal = cleanServer.contains("127.0.0.1") || cleanServer.contains("localhost")
                val authHeader = normalizedHeaders[normalizedServer] ?: normalizedHeaders[cleanServer]
                
                val allBlobsForServer = mutableListOf<BlossomBlob>()
                var cursor: String? = null
                var hasMore = true
                var pageCount = 0
                val maxPages = 100 // Safety limit

                while (hasMore && pageCount < maxPages) {
                    coroutineContext.ensureActive()
                    pageCount++
                    
                    val url = cleanServer.toHttpUrlOrNull()?.newBuilder()
                        ?.addPathSegment("list")
                        ?.addPathSegment(pubkey)
                        ?.addQueryParameter("limit", limit.toString())
                        ?.apply {
                            if (cursor != null) addQueryParameter("cursor", cursor)
                        }
                        ?.build() ?: break

                    Log.d("BlossomRepo", "Fetching list page $pageCount from: $url")
                    
                        suspend fun tryFetch(headerValue: String?): List<BlossomBlob>? {
                        var lastException: Exception? = null
                        for (attempt in 1..2) {
                            coroutineContext.ensureActive()
                            val request = Request.Builder()
                                .url(url)
                                .apply {
                                    if (headerValue != null) header("Authorization", headerValue)
                                }
                                .header("User-Agent", "Aerith/1.0")
                                .build()

                            try {
                                client.newCall(request).execute().use { response ->
                                    val code = response.code
                                    val bodyString = response.body?.string() ?: ""
                                    
                                    if (code == 200) {
                                        val blobs = listAdapter.fromJson(bodyString) ?: emptyList()
                                        Log.i("BlossomRepo", "SUCCESS page from $cleanServer (${blobs.size} items)")
                                        return blobs.map { it.copy(serverUrl = server) }
                                    } else if (code == 401 && !isLocal) {
                                        return null // Try next prefix
                                    } else {
                                        Log.w("BlossomRepo", "List failed for $cleanServer: HTTP $code")
                                        return emptyList<BlossomBlob>()
                                    }
                                }
                            } catch (e: Exception) {
                                lastException = e
                                if (attempt < 2) kotlinx.coroutines.delay(500)
                            }
                        }
                        return emptyList()
                    }

                    var pageResult = tryFetch(authHeader)
                    
                    if (pageResult == null && authHeader != null && authHeader.startsWith("Nostr ") && !isLocal) {
                        val blossomHeader = authHeader.replaceFirst("Nostr ", "Blossom ")
                        pageResult = tryFetch(blossomHeader)
                    }

                    val blobs = pageResult ?: emptyList()
                    if (blobs.isEmpty()) {
                        hasMore = false
                        continue
                    }

                    allBlobsForServer.addAll(blobs)
                    Log.d("BlossomRepo", "Collected ${blobs.size} items from $cleanServer. Running total: ${allBlobsForServer.size}")

                    // Check if the page is full and the cursor is moving
                    if (blobs.size >= 250) {
                        val nextCursor = blobs.last().sha256
                        if (nextCursor == cursor) {
                            Log.w("BlossomRepo", "Server returned identical page for $cleanServer (cursor didn't move). Stopping.")
                            hasMore = false
                        } else {
                            cursor = nextCursor
                            Log.d("BlossomRepo", "Requesting next page with cursor: ${cursor.take(8)}...")
                        }
                    } else {
                        Log.d("BlossomRepo", "Page is partial (${blobs.size} < 250), finishing fetch for $cleanServer")
                        hasMore = false
                    }
                }

                Log.i("BlossomRepo", "FINISHED fetching from $cleanServer. Total: ${allBlobsForServer.size} blobs in $pageCount pages")
                allBlobsForServer
            }
        }
        val allBlobs = deferredResults.awaitAll().flatten()
        allBlobs.sortedByDescending { it.getCreationTime() ?: 0L }
    }

    suspend fun prepareUpload(uri: Uri, mimeType: String): Result<Pair<String, Long>> = withContext(Dispatchers.IO) {
        try {
            val processed = processFile(uri, mimeType)
            Result.success(Pair(processed.hash, processed.size))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun uploadBytes(
        serverUrl: String,
        uri: Uri,
        mimeType: String,
        authHeader: String,
        expectedHash: String
    ): Result<BlossomUploadResult> = withContext(Dispatchers.IO) {
        try {
            // 1. Check if blob already exists via HEAD (optimization)
            if (checkBlobExists(serverUrl, expectedHash)) {
                Log.i("BlossomRepo", "Blob $expectedHash already exists on $serverUrl, skipping upload.")
                val cleanServer = serverUrl.removeSuffix("/")
                // Most Blossom servers serve at /<hash>
                return@withContext Result.success(BlossomUploadResult("$cleanServer/$expectedHash", expectedHash, serverUrl))
            }

            val processed = processFile(uri, mimeType)
            if (processed.hash != expectedHash) {
                return@withContext Result.failure(Exception("Hash mismatch during re-processing. Original: $expectedHash, New: ${processed.hash}"))
            }

            val result = performUpload(serverUrl, processed.bytes, processed.hash, mimeType, authHeader)

            if (result.serverHash != null && result.serverHash != processed.hash) {
                Log.w("Blossom", "Hash mismatch on $serverUrl: local=${processed.hash}, server=${result.serverHash}")
            }
            
            Result.success(result)
        } catch (e: Exception) {
            Log.e("BlossomRepository", "uploadBytes failed", e)
            Result.failure(e)
        }
    }

    private suspend fun performUpload(
        serverUrl: String,
        data: ByteArray,
        localHash: String,
        mimeType: String,
        authHeader: String
    ): BlossomUploadResult {
        val contentType = mimeType.ifBlank { "application/octet-stream" }
        val mediaType = contentType.toMediaTypeOrNull()
        val requestBody = data.toRequestBody(mediaType)
        val cleanServer = serverUrl.removeSuffix("/")
        var lastError = ""

        suspend fun tryUpload(header: String, method: String, path: String): BlossomUploadResult? {
            val request = Request.Builder()
                .url("$cleanServer/$path")
                .method(method, requestBody)
                .header("Authorization", header)
                .header("Content-Type", contentType)
                .header("Content-Length", data.size.toString())
                .header("User-Agent", "Aerith/1.0")
                .build()
            
            return try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        parseUploadResponse(response.body?.string(), cleanServer)
                    } else if (response.code == 401) {
                        null // Try next prefix
                    } else {
                        lastError += " | $method /$path: ${response.code} ${response.body?.string()?.take(100)}"
                        null
                    }
                }
            } catch (e: Exception) {
                lastError += " | $method /$path: ${e.message}"
                null
            }
        }

        // Strategy 1: PUT /upload (BUD-02)
        val prefixes = if (authHeader.startsWith("Nostr ")) listOf("Nostr ", "Blossom ") else listOf("Blossom ", "Nostr ")
        
        for (prefix in prefixes) {
            val currentHeader = if (authHeader.startsWith("Nostr ")) authHeader.replaceFirst("Nostr ", prefix) 
                               else authHeader.replaceFirst("Blossom ", prefix)
            
            val result = tryUpload(currentHeader, "PUT", "upload") ?: tryUpload(currentHeader, "POST", "upload")
            if (result != null) return result
        }
        
        throw Exception("All upload strategies failed: $lastError")
    }

    private fun parseUploadResponse(body: String?, serverUrl: String): BlossomUploadResult {
        if (body.isNullOrBlank()) throw Exception("Empty response body from server")
        val json = JSONObject(body)
        val url = json.optString("url")
        if (url.isBlank()) throw Exception("No 'url' in server response")
        val serverHash = json.optString("sha256").takeIf { it.isNotBlank() } ?: extractHashFromUrl(url)
        return BlossomUploadResult(url, serverHash, serverUrl)
    }

    private fun extractHashFromUrl(url: String): String? {
        val segment = url.substringAfterLast("/").substringBefore(".").substringBefore("?")
        return if (segment.length == 64 && segment.all { it.isLetterOrDigit() }) segment else null
    }

    private suspend fun processFile(uri: Uri, mimeType: String): ProcessedFile = withContext(Dispatchers.IO) {
        val tempFile = File.createTempFile("blossom_pre_process_", ".tmp", context.cacheDir)
        context.contentResolver.openInputStream(uri).use { input ->
            if (input == null) throw Exception("Cannot open URI: $uri")
            tempFile.outputStream().use { output ->
                when {
                    mimeType.startsWith("image/jpeg") -> stripJpegMetadata(input, output)
                    mimeType.startsWith("image/png") -> stripPngMetadata(input, output)
                    else -> input.copyTo(output)
                }
            }
        }
        val bytes = tempFile.readBytes()
        tempFile.delete()
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(bytes).joinToString("") { "%02x".format(it) }
        ProcessedFile(bytes, hash, bytes.size.toLong())
    }

    private fun stripJpegMetadata(input: InputStream, output: OutputStream): Boolean {
        // Verify SOI marker (0xFFD8)
        if (input.read() != 0xFF || input.read() != 0xD8) return false
        output.write(0xFF); output.write(0xD8)

        while (true) {
            // Find next marker
            var b = input.read()
            while (b != 0xFF) { if (b == -1) return false; b = input.read() }
            var type = input.read()
            while (type == 0xFF) { type = input.read() }
            if (type == -1) break

            // SOS (Start of Scan) — copy the rest of the file verbatim
            if (type == 0xDA) {
                output.write(0xFF); output.write(type)
                input.copyTo(output)
                return true
            }
            // EOI (End of Image)
            if (type == 0xD9) {
                output.write(0xFF); output.write(type)
                return true
            }

            // Read segment length
            val lenHigh = input.read()
            val lenLow = input.read()
            if (lenHigh == -1 || lenLow == -1) return false
            val length = (lenHigh shl 8) or lenLow

            // STRIP ONLY: APP1 (0xE1) — Exif / XMP
            // KEEP: APP0 (JFIF), APP2 (ICC), APP14 (Adobe), COM
            if (type == 0xE1) {
                // Skip this segment
                skipBytes(input, length - 2)
            } else {
                // Copy this segment
                output.write(0xFF)
                output.write(type)
                output.write(lenHigh)
                output.write(lenLow)
                copyBytes(input, output, length - 2)
            }
        }
        return true
    }

    private fun stripPngMetadata(input: InputStream, output: OutputStream): Boolean {
        // Verify PNG signature
        val sig = ByteArray(8)
        if (input.read(sig) != 8) return false
        val expected = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        if (!sig.contentEquals(expected)) return false
        output.write(sig)

        val header = ByteArray(8)
        while (input.read(header, 0, 8) == 8) {
            val length = ((header[0].toInt() and 0xFF) shl 24) or
                         ((header[1].toInt() and 0xFF) shl 16) or
                         ((header[2].toInt() and 0xFF) shl 8) or
                         (header[3].toInt() and 0xFF)
            val type = String(header, 4, 4, StandardCharsets.US_ASCII)

            // STRIP: eXIf, tEXt, zTXt, iTXt
            val shouldStrip = type in listOf("eXIf", "tEXt", "zTXt", "iTXt")

            if (shouldStrip) {
                skipBytes(input, length + 4) // data + CRC
            } else {
                output.write(header)
                copyBytes(input, output, length + 4) // data + CRC
                if (type == "IEND") return true
            }
        }
        return true
    }
    
    private fun skipBytes(input: InputStream, count: Int) {
        var remaining = count.toLong()
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) break
            remaining -= skipped
        }
    }
    
    private fun copyBytes(input: InputStream, output: OutputStream, count: Int) {
        val buffer = ByteArray(8192)
        var remaining = count
        while (remaining > 0) {
            val toRead = minOf(remaining, buffer.size)
            val read = input.read(buffer, 0, toRead)
            if (read == -1) break
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    suspend fun mirrorBlob(
        serverUrl: String,
        sourceUrl: String,
        authHeader: String
    ): Result<BlossomUploadResult> = withContext(Dispatchers.IO) {
        val cleanServer = serverUrl.removeSuffix("/")
        Log.i("BlossomRepo", "Attempting to mirror $sourceUrl to $cleanServer")
        var lastError = ""

        suspend fun tryMirror(header: String, method: String): BlossomUploadResult? {
            val jsonBody = JSONObject().put("url", sourceUrl).toString()
            val requestBody = jsonBody.toRequestBody("application/json".toMediaTypeOrNull())
            val request = Request.Builder()
                .url("$cleanServer/mirror")
                .method(method, requestBody)
                .header("Authorization", header)
                .header("Content-Type", "application/json")
                .header("User-Agent", "Aerith/1.0")
                .build()
            
            return try {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    if (response.isSuccessful) {
                        Log.i("BlossomRepo", "Mirror SUCCEEDED ($method) for $sourceUrl on $cleanServer with prefix ${header.take(10)}")
                        parseUploadResponse(body, cleanServer)
                    } else if (response.code == 401) {
                        null // Try next prefix
                    } else {
                        lastError += " | $method Mirror (${header.take(10)}): ${response.code} - ${body.take(100)}"
                        null
                    }
                }
            } catch (e: Exception) {
                lastError += " | $method Mirror exception: ${e.message}"
                null
            }
        }

        val prefixes = if (authHeader.startsWith("Nostr ")) listOf("Nostr ", "Blossom ") else listOf("Blossom ", "Nostr ")
        
        for (prefix in prefixes) {
            val currentHeader = if (authHeader.startsWith("Nostr ")) authHeader.replaceFirst("Nostr ", prefix) 
                               else authHeader.replaceFirst("Blossom ", prefix)
            
            // Try PUT then POST for each prefix
            val result = tryMirror(currentHeader, "PUT") ?: tryMirror(currentHeader, "POST")
            if (result != null) return@withContext Result.success(result)
        }

        Log.e("BlossomRepo", "Mirror failed for $sourceUrl on $serverUrl. Full log: $lastError")
        Result.failure(Exception("Mirror failed on all strategies for $serverUrl. Last error: $lastError"))
    }

    suspend fun deleteBlob(
        serverUrl: String,
        sha256: String,
        authHeader: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val cleanServer = serverUrl.removeSuffix("/")
        Log.i("BlossomRepo", "Attempting to delete blob $sha256 on $cleanServer")
        var lastError = ""

        suspend fun tryDelete(header: String, path: String): Boolean {
            val request = Request.Builder()
                .url("$cleanServer/$path")
                .delete()
                .header("Authorization", header)
                .header("User-Agent", "Aerith/1.0")
                .build()
            
            return try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.i("BlossomRepo", "Delete SUCCEEDED for $sha256 on $cleanServer via $path with prefix ${header.take(10)}")
                        true
                    } else {
                        val errorBody = response.body?.string() ?: "No response body"
                        lastError += " | $path (${header.take(10)}): ${response.code} - ${errorBody.take(100)}"
                        false
                    }
                }
            } catch (e: Exception) {
                lastError += " | $path exception: ${e.message}"
                false
            }
        }

        val prefixes = if (authHeader.startsWith("Nostr ")) listOf("Nostr ", "Blossom ") else listOf("Blossom ", "Nostr ")
        
        for (prefix in prefixes) {
            val currentHeader = if (authHeader.startsWith("Nostr ")) authHeader.replaceFirst("Nostr ", prefix) 
                               else authHeader.replaceFirst("Blossom ", prefix)
            
            if (tryDelete(currentHeader, sha256)) return@withContext Result.success(Unit)
            if (tryDelete(currentHeader, "media/$sha256")) return@withContext Result.success(Unit)
        }

        Log.e("BlossomRepo", "All delete strategies failed for $sha256 on $serverUrl. Full log: $lastError")
        Result.failure(Exception("Delete failed on all strategies for $serverUrl. Last error: $lastError"))
    }
}
