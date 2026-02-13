package com.aerith.ui.gallery

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aerith.core.blossom.BlossomBlob
import com.aerith.core.blossom.BlossomRepository
import com.aerith.core.nostr.BlossomAuthHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class GalleryState(
    val allBlobs: List<BlossomBlob> = emptyList(), // Source of truth
    val filteredBlobs: List<BlossomBlob> = emptyList(), // Displayed
    val servers: List<String> = emptyList(),
    val selectedServer: String? = null, // null = All
    val isLoading: Boolean = false,
    val error: String? = null,
    
    // Track which servers are currently performing a mirror operation
    val serverMirroringStates: Map<String, Boolean> = emptyMap(),

    // For the new 2-step upload flow
    val preparedUploadHash: String? = null,
    val preparedUploadSize: Long? = null
)

class GalleryViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = BlossomRepository(application)
    private val settingsRepository = com.aerith.core.data.SettingsRepository(application)
    private val _uiState = MutableStateFlow(GalleryState())
    val uiState: StateFlow<GalleryState> = _uiState.asStateFlow()

    private val moshi = com.squareup.moshi.Moshi.Builder()
        .add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
        .build()

    private val listAdapter = moshi.adapter<List<BlossomBlob>>(
        com.squareup.moshi.Types.newParameterizedType(List::class.java, BlossomBlob::class.java)
    )

    init {
        // Load from cache
        val cachedJson = settingsRepository.getBlobCache()
        if (cachedJson != null) {
            try {
                val cachedBlobs = listAdapter.fromJson(cachedJson) ?: emptyList()
                if (cachedBlobs.isNotEmpty()) {
                    val uniqueServers = cachedBlobs.mapNotNull { it.serverUrl }.filter { it.isNotEmpty() }.distinct().sorted()
                    _uiState.value = _uiState.value.copy(
                        allBlobs = cachedBlobs,
                        servers = uniqueServers,
                        filteredBlobs = cachedBlobs.distinctBy { it.sha256 }
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("GalleryViewModel", "Failed to load cache", e)
            }
        }
    }

    /**
     * Loads images from servers, optionally using authenticated headers.
     */
    fun loadImages(pubkey: String, servers: List<String>, authHeaders: Map<String, String> = emptyMap()) {
        if (servers.isEmpty()) {
            _uiState.value = _uiState.value.copy(error = "No Blossom servers found")
            return
        }
        
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            val result = repository.getFilesFromServers(pubkey, servers, authHeaders)
            
            // Filter for images/videos only
            val mediaBlobs = result.filter {
                val mime = it.getMimeType()
                mime?.startsWith("image/") == true || mime?.startsWith("video/") == true
            }
            
            val uniqueServers = mediaBlobs.mapNotNull { it.serverUrl }.filter { it.isNotEmpty() }.distinct().sorted()
            
            _uiState.value = _uiState.value.copy(
                allBlobs = mediaBlobs,
                servers = uniqueServers,
                isLoading = false
            )
            applyFilter()

            // Save to cache
            saveCache(mediaBlobs)
        }
    }

    private fun saveCache(blobs: List<BlossomBlob>) {
        if (blobs.isNotEmpty()) {
            try {
                val json = listAdapter.toJson(blobs)
                settingsRepository.saveBlobCache(json)
            } catch (e: Exception) {
                android.util.Log.e("GalleryViewModel", "Failed to save cache", e)
            }
        }
    }

    fun clear() {
        _uiState.value = GalleryState()
    }

    /**
     * Helper for the UI: Creates unsigned Kind 24242 'list' events for each server.
     */
    fun prepareListEvents(pubkey: String, servers: List<String>): Map<String, String> {
        return servers.associateWith { server ->
            BlossomAuthHelper.createListAuthEvent(pubkey, server)
        }
    }
    
    fun selectServer(serverUrl: String?) {
        _uiState.value = _uiState.value.copy(selectedServer = serverUrl)
        applyFilter()
    }
    
    private fun applyFilter() {
        val current = _uiState.value
        val filtered = if (current.selectedServer == null) {
            current.allBlobs.distinctBy { it.sha256 } // Deduplicate for curated "All" view
        } else {
            current.allBlobs.filter { it.serverUrl == current.selectedServer }
        }
        _uiState.value = current.copy(filteredBlobs = filtered)
    }

    // --- DELETE ---
    fun prepareDeleteEvent(pubkey: String, blob: BlossomBlob): String? {
        val serverUrl = blob.serverUrl ?: return null
        return BlossomAuthHelper.createDeleteAuthEvent(pubkey, blob.sha256, serverUrl)
    }

    fun deleteBlob(blob: BlossomBlob, signedEventJson: String) {
        val server = blob.serverUrl ?: return
        
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val authHeader = BlossomAuthHelper.encodeAuthHeader(signedEventJson)
            val result = repository.deleteBlob(server, blob.sha256, authHeader)
            
            if (result.isSuccess) {
                // Remove from list
                val newAll = _uiState.value.allBlobs.filter { it != blob }
                _uiState.value = _uiState.value.copy(allBlobs = newAll, isLoading = false)
                applyFilter()
                saveCache(newAll)
            } else {
                 _uiState.value = _uiState.value.copy(isLoading = false, error = "Delete failed: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    // --- UPLOAD (2-Step Flow) ---
    
    fun prepareUpload(uri: Uri, mimeType: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, preparedUploadHash = null, preparedUploadSize = null)
            val result = repository.prepareUpload(uri, mimeType)
            if (result.isSuccess) {
                val (hash, size) = result.getOrThrow()
                _uiState.value = _uiState.value.copy(
                    isLoading = false, 
                    preparedUploadHash = hash,
                    preparedUploadSize = size
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "File processing failed: ${result.exceptionOrNull()?.message}"
                )
            }
        }
    }

    fun uploadFile(
        server: String,
        uri: Uri,
        mimeType: String,
        signedEventJson: String,
        expectedHash: String
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val authHeader = BlossomAuthHelper.encodeAuthHeader(signedEventJson)
            val result = repository.uploadBytes(server, uri, mimeType, authHeader, expectedHash)

            if (result.isSuccess) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = null
                )
                // Note: We don't manually add the blob here because we don't have full metadata 
                // easily available without re-parsing the file. User should refresh.
            } else {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Upload failed: ${result.exceptionOrNull()?.message}"
                )
            }
            _uiState.value = _uiState.value.copy(preparedUploadHash = null, preparedUploadSize = null)
        }
    }

    fun mirrorBlob(
        server: String,
        sourceUrl: String,
        signedEventJson: String,
        originalBlob: BlossomBlob
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                serverMirroringStates = _uiState.value.serverMirroringStates + (server to true)
            )
            val authHeader = BlossomAuthHelper.encodeAuthHeader(signedEventJson)
            val result = repository.mirrorBlob(server, sourceUrl, authHeader)
            
            if (result.isSuccess) {
                val uploadResult = result.getOrThrow()
                val newBlob = originalBlob.copy(
                    url = uploadResult.url,
                    serverUrl = server
                )
                val newAll = _uiState.value.allBlobs + newBlob
                _uiState.value = _uiState.value.copy(
                    allBlobs = newAll,
                    serverMirroringStates = _uiState.value.serverMirroringStates - server
                )
                applyFilter()
                saveCache(newAll)
            } else {
                _uiState.value = _uiState.value.copy(
                    serverMirroringStates = _uiState.value.serverMirroringStates - server,
                    error = "Mirror failed: ${result.exceptionOrNull()?.message}"
                )
            }
        }
    }

    fun mirrorToAll(
        pubkey: String,
        blob: BlossomBlob,
        allServers: List<String>,
        signer: com.aerith.auth.Nip55Signer,
        signerPackage: String?
    ) {
        viewModelScope.launch {
            val currentServers = _uiState.value.allBlobs
                .filter { it.sha256 == blob.sha256 }
                .mapNotNull { it.serverUrl }
            
            val targetServers = allServers.filter { it !in currentServers }
            
            if (targetServers.isEmpty()) {
                _uiState.value = _uiState.value.copy(error = "Already on all servers")
                return@launch
            }

            // Set all target servers to mirroring state
            _uiState.value = _uiState.value.copy(
                serverMirroringStates = _uiState.value.serverMirroringStates + targetServers.associate { it to true }
            )

            var successCount = 0
            var failCount = 0
            val addedBlobs = mutableListOf<BlossomBlob>()

            targetServers.forEach { server ->
                val unsigned = BlossomAuthHelper.createUploadAuthEvent(
                    pubkey = pubkey,
                    sha256 = blob.sha256,
                    size = blob.getSizeAsLong(),
                    mimeType = blob.getMimeType(),
                    fileName = null,
                    serverUrl = server
                )

                val signed = if (signerPackage != null) {
                    signer.signEventBackground(signerPackage, unsigned, pubkey)
                } else null

                if (signed != null) {
                    val authHeader = BlossomAuthHelper.encodeAuthHeader(signed)
                    val result = repository.mirrorBlob(server, blob.url, authHeader)
                    if (result.isSuccess) {
                        successCount++
                        addedBlobs.add(blob.copy(url = result.getOrThrow().url, serverUrl = server))
                    } else {
                        failCount++
                    }
                } else {
                    failCount++
                }
                
                // Clear mirroring state for this server
                _uiState.value = _uiState.value.copy(
                    serverMirroringStates = _uiState.value.serverMirroringStates - server
                )
            }

            if (addedBlobs.isNotEmpty()) {
                val newAll = _uiState.value.allBlobs + addedBlobs
                _uiState.value = _uiState.value.copy(allBlobs = newAll)
                applyFilter()
                saveCache(newAll)
            }

            if (failCount > 0) {
                _uiState.value = _uiState.value.copy(error = "Mirrored to $successCount servers, $failCount failed.")
            }
        }
    }
}
