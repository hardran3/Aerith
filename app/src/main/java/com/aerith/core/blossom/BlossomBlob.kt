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
     * Helper to get all 't' tags (labels) from NIP-94 tags or metadata.
     */
    fun getTags(fileMetadata: Map<String, List<List<String>>> = emptyMap()): List<String> {
        // Priority 1: Local/Relay metadata (manual labels)
        // Ensure case-insensitive hash lookup
        val normalizedHash = sha256.lowercase()
        val localNip94 = fileMetadata[normalizedHash] ?: fileMetadata[sha256]
        if (localNip94 != null) {
            return localNip94.filter { it.firstOrNull() == "t" }.mapNotNull { it.getOrNull(1) }.distinct()
        }

        // Priority 2: Server-side nip94 tags
        val raw = nip94 ?: return emptyList()

        // Handle List format: [["t", "label1"], ["t", "label2"]]
        if (raw is List<*>) {
            return raw.mapNotNull { item ->
                val tagList = item as? List<*>
                if (tagList?.firstOrNull() == "t") {
                    tagList.getOrNull(1) as? String
                } else null
            }.distinct()
        }
        
        // Handle Map format: {"tags": ["label1", "label2"]}
        if (raw is Map<*, *>) {
            val tags = raw["tags"] as? List<*>
            if (tags != null) return tags.filterIsInstance<String>().distinct()
        }

        return emptyList()
    }

    /**
     * Helper to get the filename from NIP-94 tags or metadata if available.
     */
    fun getName(fileMetadata: Map<String, List<List<String>>> = emptyMap()): String? {
        // Priority 1: Local/Relay metadata
        val normalizedHash = sha256.lowercase()
        val localNip94 = fileMetadata[normalizedHash] ?: fileMetadata[sha256]
        if (localNip94 != null) {
            val nameTag = localNip94.find { it.firstOrNull() == "name" }
            if (nameTag != null) return nameTag.getOrNull(1)
        }

        // Priority 2: Server-side nip94 tags
        val raw = nip94 ?: return null
        
        // Handle List format: [["name", "filename.jpg"], ["x", "hash"]]
        if (raw is List<*>) {
            val list = raw.filterIsInstance<List<String>>()
            val nameTag = list.find { it.firstOrNull() == "name" }
            return nameTag?.getOrNull(1)
        }
        
        // Handle Map format: {"name": "filename.jpg"}
        if (raw is Map<*, *>) {
            return (raw["name"] as? String)
        }
        
        return null
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
