package com.aerith.core.nostr

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.Buffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

object Nip98Helper {
    
    // Creates an unsigned Kind 27235 event JSON string
    fun createUnsignedAuthEvent(
        pubkey: String, 
        url: String, 
        method: String, 
        payload: ByteArray? = null
    ): String {
        val tags = mutableListOf<JSONArray>()
        tags.add(JSONArray().put("u").put(url))
        tags.add(JSONArray().put("method").put(method))
        
        if (payload != null) {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(payload).joinToString("") { "%02x".format(it) }
            tags.add(JSONArray().put("payload").put(hash))
        }

        val event = JSONObject()
        event.put("kind", 27235)
        event.put("content", "")
        event.put("pubkey", pubkey)
        event.put("created_at", System.currentTimeMillis() / 1000)
        
        val tagsArray = JSONArray()
        tags.forEach { tagsArray.put(it) }
        event.put("tags", tagsArray)
        
        return event.toString()
    }
    
    // Encodes the signed event into the Authorization header string
    fun encodeAuthHeader(signedEventJson: String): String {
        val bytes = signedEventJson.toByteArray(StandardCharsets.UTF_8)
        val base64 = Base64.getEncoder().encodeToString(bytes)
        return "Nostr $base64"
    }
}
