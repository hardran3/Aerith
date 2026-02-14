package com.aerith.auth

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aerith.core.network.RelayClient
import com.aerith.core.nostr.Event
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

data class AuthState(
    val isLoggedIn: Boolean = false,
    val pubkey: String? = null,
    val relays: List<String> = emptyList(),
    val blossomServers: List<String> = emptyList(),
    val profileUrl: String? = null,
    val profileName: String? = null,
    val signerPackage: String? = null,
    val localBlossomUrl: String? = null,
    val fileMetadata: Map<String, List<List<String>>> = emptyMap(), // hash -> List of tags [["t", "tag"], ["name", "file"]]
    val isLoading: Boolean = false,
    val error: String? = null
)

class AuthViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepository = com.aerith.core.data.SettingsRepository(application)
    private val blossomRepository = com.aerith.core.blossom.BlossomRepository(application)
    private val _uiState = MutableStateFlow(AuthState())
    val uiState: StateFlow<AuthState> = _uiState.asStateFlow()

    private val nip55Signer = Nip55Signer(application)

    init {
        // Load cached data
        val cachedPubkey = settingsRepository.getPubkey()
        if (cachedPubkey != null) {
            val cachedMetadataJson = settingsRepository.getFileMetadataCache()
            val metadata = if (cachedMetadataJson != null) {
                try {
                    val json = org.json.JSONObject(cachedMetadataJson)
                    val map = mutableMapOf<String, List<List<String>>>()
                    json.keys().forEach { hash ->
                        val tagsArray = json.getJSONArray(hash)
                        val tagsList = mutableListOf<List<String>>()
                        for (i in 0 until tagsArray.length()) {
                            val tag = tagsArray.getJSONArray(i)
                            val tagFields = mutableListOf<String>()
                            for (j in 0 until tag.length()) {
                                tagFields.add(tag.getString(j))
                            }
                            tagsList.add(tagFields)
                        }
                        map[hash] = tagsList
                    }
                    map
                } catch (e: Exception) {
                    // Fallback for old cache format (hash -> List<String>)
                    try {
                        val json = org.json.JSONObject(cachedMetadataJson)
                        val map = mutableMapOf<String, List<List<String>>>()
                        json.keys().forEach { hash ->
                            val tagsArray = json.getJSONArray(hash)
                            val tagsList = mutableListOf<List<String>>()
                            for (i in 0 until tagsArray.length()) {
                                tagsList.add(listOf("t", tagsArray.getString(i)))
                            }
                            map[hash] = tagsList
                        }
                        map
                    } catch (e2: Exception) { emptyMap() }
                }
            } else emptyMap()

            _uiState.value = AuthState(
                isLoggedIn = true,
                pubkey = cachedPubkey,
                relays = settingsRepository.getRelays(),
                blossomServers = settingsRepository.getBlossomServers(),
                profileName = settingsRepository.getProfileName(),
                profileUrl = settingsRepository.getProfileUrl(),
                signerPackage = settingsRepository.getSignerPackage(),
                fileMetadata = metadata
            )
            // Still refresh data in background
            fetchRelayList(cachedPubkey)
            checkLocalBlossom()
        }
    }

    private fun checkLocalBlossom() {
        viewModelScope.launch {
            val localUrl = blossomRepository.detectLocalBlossom()
            _uiState.value = _uiState.value.copy(localBlossomUrl = localUrl)
        }
    }

    fun onLoginResult(intent: Intent?) {
        val rawPubkey = intent?.getStringExtra("signature") ?: return 
        val packageName = intent.getStringExtra("package") ?: intent.getStringExtra("result")?.let {
            // Some signers return pkg name in standard way, some might need manual check.
            // But NIP-55 says extra "package" should be present.
            null
        }
        
        // If package is missing, try to find it from the calling package if possible?
        // Actually, NIP-55 says: "The signer application MUST return its package name in the package extra"
        val finalPackage = packageName ?: intent.component?.packageName
        
        // Ensure we store HEX pubkey in state
        val pubkey = if (rawPubkey.startsWith("npub")) {
             try {
                 val (_, bytes) = com.aerith.core.nostr.Bech32.decode(rawPubkey)
                 com.aerith.core.nostr.Bech32.toHex(bytes)
             } catch(e: Exception) { 
                 Log.e("AuthViewModel", "Failed to decode pubkey", e)
                 rawPubkey 
             }
        } else {
             rawPubkey
        }
        
        if (pubkey.isNotEmpty()) {
            settingsRepository.savePubkey(pubkey)
            settingsRepository.saveSignerPackage(finalPackage)
            _uiState.value = _uiState.value.copy(
                isLoggedIn = true,
                pubkey = pubkey,
                signerPackage = finalPackage,
                isLoading = true
            )
            fetchRelayList(pubkey)
        } else {
            _uiState.value = _uiState.value.copy(error = "Login failed: No pubkey returned")
        }
    }

    private fun fetchRelayList(pubkey: String) {
        Log.d("AuthViewModel", "fetchRelayList called for: $pubkey")
        viewModelScope.launch(Dispatchers.IO) {
            Log.d("AuthViewModel", "Coroutine started. Thread: ${Thread.currentThread().name}")
            
            // 1. Try to fetch Kind 10002 from Inderxer & Bootstrap relays
            val defaultRelays = listOf(
                "wss://purplepag.es", 
                "wss://user.kindpag.es", 
                "wss://relay.damus.io", 
                "wss://nos.lol", 
                "wss://relay.primal.net",
                "wss://relay.snort.social"
            )
            var relayListEvent: Event? = null
            val debugBuf = StringBuffer() 

            for (url in defaultRelays) {
                Log.d("AuthViewModel", "Trying relay: $url")
                try {
                    val client = RelayClient(url)
                    val filter = """{"kinds": [10002], "authors": ["$pubkey"], "limit": 1}"""
                    Log.d("AuthViewModel", "Fetching from $url with filter: $filter")
                    
                    val events = client.fetchEvent(filter) 
                    if (events.isNotEmpty()) {
                        Log.d("AuthViewModel", "SUCCESS: Found K10002 on $url")
                        relayListEvent = events.first()
                        debugBuf.append("Found K10002 on $url. ")
                        break
                    } else {
                        Log.d("AuthViewModel", "EMPTY: No events on $url")
                        debugBuf.append("Empty on $url. ")
                    }
                } catch (e: Exception) {
                    Log.e("AuthViewModel", "EXCEPTION on $url: ${e.message}", e)
                    debugBuf.append("Err $url: ${e.message}. ")
                }
            }

            val relays = parseRelayList(relayListEvent)
            
            withContext(Dispatchers.Main) {
                if (relays.isEmpty() && _uiState.value.relays.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        relays = emptyList(),
                        error = "Failed to find Relay List (Kind 10002). \nDebug: $debugBuf"
                    )
                } else if (relays.isNotEmpty()) {
                     settingsRepository.saveRelays(relays)
                     _uiState.value = _uiState.value.copy(relays = relays)
                }
            }
            
            val relaysToUse = if (relays.isEmpty()) {
                if (_uiState.value.relays.isNotEmpty()) _uiState.value.relays else defaultRelays
            } else relays
            
            // 2. Fetch Blossom Servers
            fetchBlossomServers(pubkey, relaysToUse)
            
            // 3. Fetch User Profile (Kind 0)
            fetchProfile(pubkey, relaysToUse)

            // 4. Fetch File Metadata (Kind 1063)
            fetchFileMetadata(pubkey, relaysToUse)
        }
    }

    private fun parseRelayList(event: Event?): List<String> {
        if (event == null) return emptyList()
        // Kinds 10002 tags: ["r", "wss://...", "read"|"write"]
        // We want 'write' or unspecified (which implies both)
        val allRelays = event.tags.filter { it.size >= 2 && it[0] == "r" }
        
        val writeRelays = allRelays.filter { 
            it.size < 3 || it[2] == "write" 
        }.map { it[1] }
        
        val readRelays = allRelays.filter {
            it.size >= 3 && it[2] == "read"
        }.map { it[1] }
        
        // Return write relays first, then others (just in case)
        return (writeRelays + readRelays).distinct()
    }

    private fun fetchBlossomServers(pubkey: String, relays: List<String>) {
         viewModelScope.launch(Dispatchers.IO) {
             val indexers = listOf("wss://purplepag.es", "wss://user.kindpag.es")
             // Combine discovered relays (from Kind 10002) with indexers for best coverage
             val uniqueRelays = (relays + indexers + listOf("wss://relay.damus.io")).distinct()
             
             // We want to query multiple relays in parallel because sequential is too slow and might miss it
             // if the first few fail or don't have it.
             // We'll check the top 8 unique relays.
             val relaysToCheck = uniqueRelays.take(8)
             
             val debugLogs = StringBuffer() // Thread-safe string buffer
             debugLogs.append("Checking Kind 10063 on: ${relaysToCheck.joinToString(", ")}. ")
             
             // Concurrent fetch
             val deferredEvents = relaysToCheck.map { url ->
                 async {
                     try {
                         val client = RelayClient(url)
                         val filter = """{"kinds": [10063], "authors": ["$pubkey"], "limit": 1}"""
                         val events = client.fetchEvent(filter)
                         if (events.isNotEmpty()) {
                             debugLogs.append("FOUND on $url. ")
                             events.first()
                         } else {
                             debugLogs.append("Not found on $url. ")
                             null
                         }
                     } catch (e: Exception) {
                         debugLogs.append("Error $url: ${e.message}. ")
                         null
                     }
                 }
             }
             
             // Wait for all specific checks to complete (or first non-null? simplify: wait all for now)
             val results = deferredEvents.awaitAll()
             val blossomEvent = results.firstNotNullOfOrNull { it }
             
             val servers = parseBlossomServers(blossomEvent)
             
             withContext(Dispatchers.Main) {
                 if (servers.isEmpty() && _uiState.value.blossomServers.isEmpty()) {
                     _uiState.value = _uiState.value.copy(
                         blossomServers = emptyList(),
                         isLoading = false, // Stop loading even if failed
                         error = "No Blossom servers (Kind 10063) found. \nLog: $debugLogs"
                     )
                 } else if (servers.isNotEmpty()) {
                     settingsRepository.saveBlossomServers(servers)
                     _uiState.value = _uiState.value.copy(
                         blossomServers = servers,
                         isLoading = false,
                         error = null
                     )
                 } else {
                     _uiState.value = _uiState.value.copy(isLoading = false)
                 }
             }
         }
    }

    private fun parseBlossomServers(event: Event?): List<String> {
        if (event == null) return emptyList()
        // Kind 10063: tags: [["server", "https://..."], ...]
        return event.tags
            .filter { it.size >= 2 && it[0] == "server" }
            .map { it[1] }
    }

    private fun fetchProfile(pubkey: String, relays: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            val indexers = listOf("wss://purplepag.es", "wss://relay.damus.io", "wss://relay.primal.net")
            val uniqueRelays = (relays + indexers).distinct().take(6)
             
            val deferred = uniqueRelays.map { url -> 
                async {
                    try {
                         val client = RelayClient(url)
                         client.fetchEvent("""{"kinds": [0], "authors": ["$pubkey"], "limit": 1}""")
                    } catch(e: Exception) { emptyList<Event>() }
                }
            }
            
            val events = deferred.awaitAll().flatten()
            val profileEvent = events.maxByOrNull { it.createdAt }
            
            if (profileEvent != null) {
                try {
                    val json = org.json.JSONObject(profileEvent.content)
                    val picture = json.optString("picture")
                    val name = json.optString("name").ifEmpty { json.optString("display_name") }
                    
                    withContext(Dispatchers.Main) {
                        settingsRepository.saveProfile(name, picture)
                        _uiState.value = _uiState.value.copy(
                            profileUrl = if (picture.isNotEmpty()) picture else null,
                             profileName = if (name.isNotEmpty()) name else null
                        )
                    }
                } catch (e: Exception) {
                    Log.e("AuthViewModel", "Failed to parse profile", e)
                }
            }
        }
    }

    private fun fetchFileMetadata(pubkey: String, relays: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            val uniqueRelays = (relays + listOf("wss://purplepag.es", "wss://relay.damus.io")).distinct().take(8)
            
            val deferred = uniqueRelays.map { url ->
                async {
                    try {
                        val client = RelayClient(url)
                        // Kind 1063: File Metadata
                        client.fetchEvent("""{"kinds": [1063], "authors": ["$pubkey"]}""")
                    } catch (e: Exception) { emptyList<Event>() }
                }
            }
            
            val allEvents = deferred.awaitAll().flatten()
            val metadataMap = mutableMapOf<String, List<List<String>>>()
            
            // Group by hash 'x' and take latest event per hash
            allEvents.groupBy { event ->
                event.tags.find { it.firstOrNull() == "x" }?.getOrNull(1)?.lowercase()
            }.forEach { (hash, events) ->
                if (hash != null) {
                    val latest = events.maxByOrNull { it.createdAt }
                    // Keep all NIP-94 relevant tags
                    val relevantTags = latest?.tags?.filter { tag ->
                        tag.firstOrNull() in listOf("t", "name", "alt", "summary", "thumb", "blurhash", "dim")
                    } ?: emptyList()
                    metadataMap[hash] = relevantTags
                }
            }

            withContext(Dispatchers.Main) {
                if (metadataMap.isNotEmpty()) {
                    val currentMetadata = _uiState.value.fileMetadata.toMutableMap()
                    // Update current metadata with fresh data from relays
                    metadataMap.forEach { (h, t) ->
                        currentMetadata[h] = t
                    }
                    
                    _uiState.value = _uiState.value.copy(fileMetadata = currentMetadata)
                    
                    // Save to cache
                    saveMetadataToCache(currentMetadata)
                }
            }
        }
    }

    private fun saveMetadataToCache(metadata: Map<String, List<List<String>>>) {
        try {
            val json = org.json.JSONObject()
            metadata.forEach { (h, tagsList) ->
                val tagsArray = org.json.JSONArray()
                tagsList.forEach { tag ->
                    val tagArray = org.json.JSONArray()
                    tag.forEach { tagArray.put(it) }
                    tagsArray.put(tagArray)
                }
                json.put(h, tagsArray)
            }
            settingsRepository.saveFileMetadataCache(json.toString())
        } catch (e: Exception) {
            Log.e("AuthViewModel", "Failed to save metadata cache", e)
        }
    }

    fun logout() {
        settingsRepository.clear()
        _uiState.value = AuthState()
    }

    fun updateMetadata(hash: String, tags: List<List<String>>) {
        val current = _uiState.value.fileMetadata.toMutableMap()
        current[hash.lowercase()] = tags
        _uiState.value = _uiState.value.copy(fileMetadata = current)
        
        // Persist immediately
        saveMetadataToCache(current)
    }

    fun refreshMetadata() {
        val cachedMetadataJson = settingsRepository.getFileMetadataCache()
        if (cachedMetadataJson != null) {
            try {
                val json = org.json.JSONObject(cachedMetadataJson)
                val map = mutableMapOf<String, List<List<String>>>()
                json.keys().forEach { hash ->
                    val tagsArray = json.getJSONArray(hash)
                    val tagsList = mutableListOf<List<String>>()
                    for (i in 0 until tagsArray.length()) {
                        val tag = tagsArray.getJSONArray(i)
                        val tagFields = mutableListOf<String>()
                        for (j in 0 until tag.length()) {
                            tagFields.add(tag.getString(j))
                        }
                        tagsList.add(tagFields)
                    }
                    map[hash] = tagsList
                }
                _uiState.value = _uiState.value.copy(fileMetadata = map)
            } catch (e: Exception) {}
        }
    }
}

