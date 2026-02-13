package com.aerith.core.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("aerith_prefs", Context.MODE_PRIVATE)

    fun savePubkey(pubkey: String?) {
        prefs.edit().putString("pubkey", pubkey).apply()
    }

    fun getPubkey(): String? = prefs.getString("pubkey", null)

    fun saveSignerPackage(packageName: String?) {
        prefs.edit().putString("signer_package", packageName).apply()
    }

    fun getSignerPackage(): String? = prefs.getString("signer_package", null)

    fun saveRelays(relays: List<String>) {
        val array = JSONArray()
        relays.forEach { array.put(it) }
        prefs.edit().putString("cached_relays", array.toString()).apply()
    }

    fun getRelays(): List<String> {
        val json = prefs.getString("cached_relays", null) ?: return emptyList()
        val array = JSONArray(json)
        val list = mutableListOf<String>()
        for (i in 0 until array.length()) {
            list.add(array.getString(i))
        }
        return list
    }

    fun saveBlossomServers(servers: List<String>) {
        val array = JSONArray()
        servers.forEach { array.put(it) }
        prefs.edit().putString("cached_blossom_servers", array.toString()).apply()
    }

    fun getBlossomServers(): List<String> {
        val json = prefs.getString("cached_blossom_servers", null) ?: return emptyList()
        val array = JSONArray(json)
        val list = mutableListOf<String>()
        for (i in 0 until array.length()) {
            list.add(array.getString(i))
        }
        return list
    }

    fun saveProfile(name: String?, pictureUrl: String?) {
        prefs.edit().apply {
            putString("profile_name", name)
            putString("profile_url", pictureUrl)
        }.apply()
    }

    fun getProfileName(): String? = prefs.getString("profile_name", null)
    fun getProfileUrl(): String? = prefs.getString("profile_url", null)

    fun saveBlobCache(json: String) {
        prefs.edit().putString("blob_cache", json).apply()
    }

    fun getBlobCache(): String? = prefs.getString("blob_cache", null)

    fun clear() {
        prefs.edit().clear().apply()
    }
}
