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
            if (mediaBlobs.isNotEmpty()) {
                try {
                    val json = listAdapter.toJson(mediaBlobs)
                    settingsRepository.saveBlobCache(json)
                } catch (e: Exception) {
                    android.util.Log.e("GalleryViewModel", "Failed to save cache", e)
                }
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
            } else {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Upload failed: ${result.exceptionOrNull()?.message}"
                )
            }
            _uiState.value = _uiState.value.copy(preparedUploadHash = null, preparedUploadSize = null)
        }
    }
}
