package com.aerith.core.nostr

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/**
 * Helper for Blossom authorization events (Kind 24242).
 *
 * This object implements the authorization flow as specified in the Blossom protocol,
 * ensuring correct tag order, data types, and encoding to maximize compatibility
 * with various Blossom servers.
 */
object BlossomAuthHelper {

    /**
     * Creates an unsigned Kind 24242 auth event for a DELETE operation.
     *
     * @param pubkey The user's public key (hex).
     * @param sha256 The SHA-256 hash of the blob to delete.
     * @param serverUrl The URL of the server receiving the request.
     * @return A JSON string of the unsigned Nostr event.
     */
    fun createDeleteAuthEvent(pubkey: String, sha256: String, serverUrl: String? = null): String {
        val tags = JSONArray()
        val now = System.currentTimeMillis() / 1000
        val expiration = now + 3600 // 1 hour

        // Tag order for delete: t, expiration, x, p, server
        tags.put(JSONArray().put("t").put("delete"))
        tags.put(JSONArray().put("expiration").put(expiration.toString()))
        tags.put(JSONArray().put("x").put(sha256))
        tags.put(JSONArray().put("p").put(pubkey))
        
        if (serverUrl != null) {
            tags.put(JSONArray().put("server").put(serverUrl.removeSuffix("/")))
        }

        val event = JSONObject()
        event.put("kind", 24242)
        event.put("content", "Deleting $sha256")
        event.put("pubkey", pubkey)
        event.put("created_at", now - 5) // 5s buffer for clock drift
        event.put("tags", tags)

        return event.toString()
    }

    fun createUploadAuthEvent(
        pubkey: String,
        sha256: String,
        size: Long,
        mimeType: String?,
        fileName: String?,
        serverUrl: String? = null
    ): String {
        val tags = JSONArray()
        val now = System.currentTimeMillis() / 1000
        val expiration = now + 3600 // 1 hour

        // ┌───────────────────────────────────────────────────────┐
        // │ TAG ORDER MATTERS: t → expiration → size → x         │
        // │ Some servers (Go/Rust parsers) are order-sensitive.   │
        // └───────────────────────────────────────────────────────┘
        tags.put(JSONArray().put("t").put("upload"))
        tags.put(JSONArray().put("expiration").put(expiration.toString()))
        tags.put(JSONArray().put("size").put(size.toString()))
        tags.put(JSONArray().put("x").put(sha256))

        // Optional tags
        if (serverUrl != null) tags.put(JSONArray().put("server").put(serverUrl.removeSuffix("/")))
        if (fileName != null) tags.put(JSONArray().put("name").put(fileName))
        if (mimeType != null) tags.put(JSONArray().put("type").put(mimeType))

        val event = JSONObject()
        event.put("kind", 24242)
        val displayFileName = fileName ?: sha256.take(8)
        event.put("content", "Uploading $displayFileName")
        event.put("pubkey", pubkey)
        event.put("created_at", now - 5)
        event.put("tags", tags)

        return event.toString()
    }

    fun createListAuthEvent(pubkey: String, serverUrl: String): String {
        val tags = JSONArray()
        val now = System.currentTimeMillis() / 1000
        val expiration = now + 3600 // 1 hour

        // Order: t, expiration, server
        tags.put(JSONArray().put("t").put("list"))
        tags.put(JSONArray().put("expiration").put(expiration.toString()))
        tags.put(JSONArray().put("server").put(serverUrl.removeSuffix("/")))
        
        val event = JSONObject()
        event.put("kind", 24242)
        event.put("content", "")
        event.put("pubkey", pubkey)
        event.put("created_at", now - 5)
        event.put("tags", tags)

        return event.toString()
    }

    /**
     * Creates an unsigned Kind 1063 File Metadata event.
     */
    fun createFileMetadataEvent(
        pubkey: String,
        sha256: String,
        url: String,
        mimeType: String?,
        tags: List<String>
    ): String {
        val eventTags = JSONArray()
        eventTags.put(JSONArray().put("x").put(sha256))
        eventTags.put(JSONArray().put("url").put(url))
        if (mimeType != null) eventTags.put(JSONArray().put("m").put(mimeType))
        
        tags.forEach { tag ->
            eventTags.put(JSONArray().put("t").put(tag))
        }

        val event = JSONObject()
        event.put("kind", 1063)
        event.put("content", "")
        event.put("pubkey", pubkey)
        event.put("created_at", System.currentTimeMillis() / 1000)
        event.put("tags", eventTags)

        return event.toString()
    }

    /**
     * Encodes the signed event JSON into a Base64 string suitable for the
     * `Authorization: Nostr <base64>` header.
     */
    fun encodeAuthHeader(signedEventJson: String): String {
        // CRITICAL: trim() before encoding — trailing whitespace breaks some parsers
        val cleanJson = signedEventJson.trim()
        val bytes = cleanJson.toByteArray(StandardCharsets.UTF_8)
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return "Nostr $base64"
    }
}
