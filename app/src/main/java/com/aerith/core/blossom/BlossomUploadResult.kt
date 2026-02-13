package com.aerith.core.blossom

/**
 * Represents the result of a successful Blossom upload.
 *
 * @param url The public URL of the uploaded blob.
 * @param serverHash The SHA-256 hash of the blob as reported by the server. This
 *   should be verified against the locally computed hash to detect re-encoding.
 * @param serverUrl The URL of the Blossom server that is hosting the file.
 */
data class BlossomUploadResult(
    val url: String,
    val serverHash: String?,
    val serverUrl: String
)
