package com.aerith.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import com.aerith.core.nostr.Event
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class Nip55Signer(private val context: Context) {

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val eventAdapter = moshi.adapter(Event::class.java)

    fun isExternalSignerInstalled(): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:"))
        val info = context.packageManager.queryIntentActivities(intent, 0)
        return info.isNotEmpty()
    }

    /**
     * Helper to parse the result from the activity launcher.
     * This is a bit complex because the result comes back asynchronously via the ActivityResultCallback.
     * For simplicity in this initial phase, we will rely on the ViewModel to handle the ActivityResultLauncher.
     * This class will primarily generate the Intents.
     */
    
    fun getLoginIntent(): Intent {
        val permissions = """[{"type":"sign_event","kind":10002},{"type":"sign_event","kind":10063},{"type":"sign_event","kind":24242},{"type":"nip04_encrypt"},{"type":"nip04_decrypt"}]"""
        return Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:"))
            .putExtra("type", "get_public_key")
            .putExtra("permissions", permissions)
    }

    fun getSignEventIntent(eventJson: String, loggedInPubkey: String): Intent {
        return Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:$eventJson"))
            .putExtra("type", "sign_event")
            .putExtra("current_user", loggedInPubkey)
    }

    /**
     * Attempts to sign an event in the background without UI flashing.
     * Requires the user to have previously checked "Always allow" in the signer app.
     */
    fun signEventBackground(signerPackage: String, eventJson: String, loggedInPubkey: String): String? {
        val uri = Uri.parse("content://$signerPackage.SIGN_EVENT")
        val projection = arrayOf(eventJson, "", loggedInPubkey)
        
        return try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    // NIP-55: The signer application MUST return the signed event JSON in the 'event' column.
                    val eventIndex = cursor.getColumnIndex("event")
                    val resultIndex = cursor.getColumnIndex("result")
                    val sigIndex = cursor.getColumnIndex("signature")
                    
                    val result = when {
                        eventIndex >= 0 -> cursor.getString(eventIndex)
                        resultIndex >= 0 -> cursor.getString(resultIndex)
                        sigIndex >= 0 -> cursor.getString(sigIndex)
                        else -> null
                    }

                    // Blossom requires the FULL event JSON in the header. 
                    // If we got just a hex signature (64 bytes / 128 chars), this is insufficient.
                    if (result != null && result.trim().startsWith("{")) {
                        result
                    } else {
                        android.util.Log.w("Nip55Signer", "Background signer returned non-JSON result: $result")
                        null
                    }
                } else null
            }
        } catch (e: Exception) {
            android.util.Log.e("Nip55Signer", "Background signing failed", e)
            null
        }
    }
    
    fun parseSignEventResult(intent: Intent?): String? {
        // Amber returns the full signed event JSON in "event" or "result".
        // "signature" contains only the raw sig hex — NOT what we want for NIP-98.
        return intent?.getStringExtra("event")
            ?: intent?.getStringExtra("result")
            ?: intent?.getStringExtra("signature")
    }

    fun getNip04EncryptIntent(plainText: String, recipientPubkey: String, loggedInPubkey: String): Intent {
        return Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:$plainText"))
            .putExtra("type", "nip04_encrypt")
            .putExtra("pubKey", recipientPubkey)
            .putExtra("current_user", loggedInPubkey)
    }
    
    fun getNip04DecryptIntent(encryptedText: String, senderPubkey: String, loggedInPubkey: String): Intent {
        return Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:$encryptedText"))
            .putExtra("type", "nip04_decrypt")
            .putExtra("pubKey", senderPubkey)
            .putExtra("current_user", loggedInPubkey)
    }
}
