package com.aerith.core.blossom

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class BlossomBlob(
    @Json(name = "url") val url: String,
    @Json(name = "sha256") val sha256: String,
    @Json(name = "size") val size: Any?, // Can be String or Number
    @Json(name = "type") val type: String? = null,
    @Json(name = "created") val created: Any? = null,
    
    // Some servers use different field names
    @Json(name = "mime") val mime: String? = null,
    @Json(name = "uploaded") val uploaded: Any? = null,
    @Json(name = "created_at") val created_at: Any? = null,
    @Json(name = "nip94") val nip94: Any? = null,

    // UI Helper
    val serverUrl: String? = null
) {
    /**
     * Helper to get the MIME type regardless of which field the server used.
     */
    fun getMimeType(): String? = type ?: mime

    /**
     * Helper to get the thumbnail URL from NIP-94 tags or metadata if available.
     */
    fun getThumbnailUrl(): String {
        val raw = nip94 ?: return url
        
        // Handle List format: [["thumb", "url"], ["x", "hash"]]
        if (raw is List<*>) {
            val list = raw.filterIsInstance<List<String>>()
            val thumbTag = list.find { it.firstOrNull() == "thumb" }
            return thumbTag?.getOrNull(1) ?: url
        }
        
        // Handle Map format: {"thumb": "url", "x": "hash"}
        if (raw is Map<*, *>) {
            return (raw["thumb"] as? String) ?: url
        }
        
        return url
    }

    /**
     * Helper to get the creation timestamp regardless of which field the server used.
     */
    fun getCreationTime(): Long? {
        val raw = created ?: uploaded ?: created_at
        return when (raw) {
            is Number -> raw.toLong()
            is String -> raw.toLongOrNull()
            else -> null
        }
    }

    /**
     * Helper to get the size as a Long.
     */
    fun getSizeAsLong(): Long {
        return when (val s = size) {
            is Number -> s.toLong()
            is String -> s.toLongOrNull() ?: 0L
            else -> 0L
        }
    }
}
