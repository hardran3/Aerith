package com.aerith.core.nostr

import android.util.Base64
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.nio.charset.StandardCharsets

/**
 * Helper for Blossom authorization events (Kind 24242).
 *
 * This object implements the authorization flow as specified in the Blossom protocol,
 * ensuring correct tag order, data types, and encoding to maximize compatibility
 * with various Blossom servers.
 */
object BlossomAuthHelper {

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val mapAdapter = moshi.adapter(Map::class.java)

    /**
     * Creates an unsigned Kind 24242 auth event for a DELETE operation.
     */
    fun createDeleteAuthEvent(pubkey: String, sha256: String, serverUrl: String? = null): String {
        val now = System.currentTimeMillis() / 1000
        val expiration = now + 3600 // 1 hour

        // Tag order for delete: t, expiration, x, p, server
        val tags = mutableListOf(
            listOf("t", "delete"),
            listOf("expiration", expiration.toString()),
            listOf("x", sha256),
            listOf("p", pubkey)
        )
        
        if (serverUrl != null) {
            tags.add(listOf("server", serverUrl.removeSuffix("/")))
        }

        val event = mutableMapOf<String, Any>(
            "kind" to 24242,
            "content" to "Deleting $sha256",
            "pubkey" to pubkey,
            "created_at" to now - 5,
            "tags" to tags
        )

        return mapAdapter.toJson(event)
    }

    fun createUploadAuthEvent(
        pubkey: String,
        sha256: String,
        size: Long,
        mimeType: String?,
        fileName: String?,
        serverUrl: String? = null
    ): String {
        val now = System.currentTimeMillis() / 1000
        val expiration = now + 3600 // 1 hour

        // ┌───────────────────────────────────────────────────────┐
        // │ TAG ORDER MATTERS: t → expiration → size → x         │
        // └───────────────────────────────────────────────────────┘
        val tags = mutableListOf(
            listOf("t", "upload"),
            listOf("expiration", expiration.toString()),
            listOf("size", size.toString()),
            listOf("x", sha256)
        )

        // Optional tags
        if (serverUrl != null) tags.add(listOf("server", serverUrl.removeSuffix("/")))
        if (fileName != null) tags.add(listOf("name", fileName))
        if (mimeType != null) tags.add(listOf("type", mimeType))

        val event = mutableMapOf<String, Any>(
            "kind" to 24242
        )
        val displayFileName = fileName ?: sha256.take(8)
        event["content"] = "Uploading $displayFileName"
        event["pubkey"] = pubkey
        event["created_at"] = now - 5
        event["tags"] = tags

        return mapAdapter.toJson(event)
    }

    fun createListAuthEvent(pubkey: String, serverUrl: String): String {
        val now = System.currentTimeMillis() / 1000
        val expiration = now + 3600 // 1 hour

        // Order: t, expiration, server
        val tags = listOf(
            listOf("t", "list"),
            listOf("expiration", expiration.toString()),
            listOf("server", serverUrl.removeSuffix("/"))
        )
        
        val event = mutableMapOf<String, Any>(
            "kind" to 24242,
            "content" to "",
            "pubkey" to pubkey,
            "created_at" to now - 5,
            "tags" to tags
        )

        return mapAdapter.toJson(event)
    }

    /**
     * Creates an unsigned Kind 1063 File Metadata event.
     */
    fun createFileMetadataEvent(
        pubkey: String,
        sha256: String,
        url: String,
        mimeType: String?,
        tags: List<String>,
        name: String? = null,
        alt: String? = null,
        summary: String? = null,
        fallbacks: List<String> = emptyList()
    ): String {
        val eventTags = mutableListOf(
            listOf("x", sha256),
            listOf("url", url)
        )
        if (mimeType != null) eventTags.add(listOf("m", mimeType))
        if (name != null) eventTags.add(listOf("name", name))
        if (alt != null) eventTags.add(listOf("alt", alt))
        if (summary != null) eventTags.add(listOf("summary", summary))
        
        tags.forEach { tag ->
            eventTags.add(listOf("t", tag))
        }

        fallbacks.forEach { fallbackUrl ->
            eventTags.add(listOf("fallback", fallbackUrl))
        }

        val event = mutableMapOf<String, Any>(
            "kind" to 1063,
            "content" to (summary ?: ""),
            "pubkey" to pubkey,
            "created_at" to System.currentTimeMillis() / 1000,
            "tags" to eventTags
        )

        return mapAdapter.toJson(event)
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
