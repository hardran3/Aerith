package com.aerith.ui.gallery

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aerith.core.blossom.BlossomBlob
import com.aerith.core.blossom.BlossomRepository
import com.aerith.core.network.RelayClient
import com.aerith.core.nostr.BlossomAuthHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class GalleryState(
    val allBlobs: List<BlossomBlob> = emptyList(), // Source of truth
    val filteredBlobs: List<BlossomBlob> = emptyList(), // Displayed
    val trashBlobs: List<BlossomBlob> = emptyList(), // Locally known but not on servers
    val servers: List<String> = emptyList(),
    val selectedServer: String? = null, // null = All, "TRASH" = Trash
    val selectedTags: Set<String> = emptySet(),
    val fileMetadata: Map<String, List<String>> = emptyMap(), // hash -> tags
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
        loadFromCache()
    }

    private fun loadFromCache() {
        val cachedJson = settingsRepository.getBlobCache()
        val trashJson = settingsRepository.getTrashCache()
        val metaJson = settingsRepository.getFileMetadataCache()
        
        var cachedBlobs = emptyList<BlossomBlob>()
        var trashBlobs = emptyList<BlossomBlob>()
        var fileMetadata = emptyMap<String, List<String>>()

        try {
            if (cachedJson != null) {
                cachedBlobs = listAdapter.fromJson(cachedJson) ?: emptyList()
            }
            if (trashJson != null) {
                trashBlobs = listAdapter.fromJson(trashJson) ?: emptyList()
            }
            if (metaJson != null) {
                val json = JSONObject(metaJson)
                val map = mutableMapOf<String, List<String>>()
                json.keys().forEach { hash ->
                    val arr = json.getJSONArray(hash)
                    val tags = mutableListOf<String>()
                    for (i in 0 until arr.length()) {
                        tags.add(arr.getString(i))
                    }
                    map[hash] = tags
                }
                fileMetadata = map
            }
        } catch (e: Exception) {
            android.util.Log.e("GalleryViewModel", "Failed to load cache", e)
        }

        val uniqueServers = cachedBlobs.mapNotNull { it.serverUrl }.filter { it.isNotEmpty() }.distinct().sorted()
        _uiState.value = _uiState.value.copy(
            allBlobs = cachedBlobs,
            trashBlobs = trashBlobs,
            fileMetadata = fileMetadata,
            servers = uniqueServers
        )
        applyFilter()
    }

    /**
     * Loads images from servers, optionally using authenticated headers.
     */
    fun loadImages(
        pubkey: String, 
        servers: List<String>, 
        authHeaders: Map<String, String> = emptyMap(),
        externalMetadata: Map<String, List<String>> = emptyMap()
    ) {
        if (servers.isEmpty()) {
            _uiState.value = _uiState.value.copy(error = "No Blossom servers found")
            return
        }
        
        viewModelScope.launch {
            // Only show full-screen loading if we have NO data yet
            val showFullLoading = _uiState.value.allBlobs.isEmpty()
            if (showFullLoading) {
                _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            } else {
                _uiState.value = _uiState.value.copy(error = null)
            }

            val result = repository.getFilesFromServers(pubkey, servers, authHeaders)
            
            var mediaBlobs = result.filter {
                val mime = it.getMimeType()
                mime?.startsWith("image/") == true || mime?.startsWith("video/") == true
            }

            // Merge metadata (Cache > External > Server)
            val mergedMeta = _uiState.value.fileMetadata.toMutableMap()
            mergedMeta.putAll(externalMetadata)

            mediaBlobs = mediaBlobs.map { blob ->
                val normalizedHash = blob.sha256.lowercase()
                val tags = mergedMeta[normalizedHash] ?: mergedMeta[blob.sha256] ?: blob.getTags()
                if (tags.isNotEmpty()) {
                    blob.copy(nip94 = tags.map { listOf("t", it) })
                } else {
                    blob
                }
            }
            
            val currentHashes = mediaBlobs.map { it.sha256 }.toSet()
            val missingFromFetch = _uiState.value.allBlobs
                .filter { it.sha256 !in currentHashes }
                .distinctBy { it.sha256 }
            
            val newTrash = (_uiState.value.trashBlobs + missingFromFetch).distinctBy { it.sha256 }
            val uniqueServers = mediaBlobs.mapNotNull { it.serverUrl }.filter { it.isNotEmpty() }.distinct().sorted()
            
            _uiState.value = _uiState.value.copy(
                allBlobs = mediaBlobs,
                trashBlobs = newTrash,
                fileMetadata = mergedMeta,
                servers = uniqueServers,
                isLoading = false
            )
            applyFilter()
            saveCache(mediaBlobs, newTrash, mergedMeta)
            prefetchImages(mediaBlobs)
        }
    }

    private fun prefetchImages(blobs: List<BlossomBlob>) {
        val context = getApplication<Application>()
        val imageLoader = coil.Coil.imageLoader(context)
        
        viewModelScope.launch(Dispatchers.IO) {
            blobs.filter { it.getMimeType()?.startsWith("image/") == true }.forEach { blob ->
                val thumbRequest = coil.request.ImageRequest.Builder(context)
                    .data(blob.getThumbnailUrl())
                    .diskCacheKey(blob.sha256)
                    .memoryCacheKey(blob.sha256)
                    .size(400, 400)
                    .precision(coil.size.Precision.INEXACT)
                    .bitmapConfig(android.graphics.Bitmap.Config.RGB_565)
                    .build()
                imageLoader.enqueue(thumbRequest)

                val highResRequest = coil.request.ImageRequest.Builder(context)
                    .data(blob.url)
                    .diskCacheKey(blob.sha256)
                    .memoryCacheKey(blob.sha256)
                    .build()
                imageLoader.enqueue(highResRequest)
            }
        }
    }

    private fun saveCache(blobs: List<BlossomBlob>, trash: List<BlossomBlob>, metadata: Map<String, List<String>>) {
        try {
            settingsRepository.saveBlobCache(listAdapter.toJson(blobs))
            settingsRepository.saveTrashCache(listAdapter.toJson(trash))
            
            val metaObj = JSONObject()
            metadata.forEach { (h, t) ->
                val arr = JSONArray()
                t.forEach { arr.put(it) }
                metaObj.put(h, arr)
            }
            settingsRepository.saveFileMetadataCache(metaObj.toString())
        } catch (e: Exception) {
            android.util.Log.e("GalleryViewModel", "Failed to save cache", e)
        }
    }

    fun emptyTrash() {
        _uiState.value = _uiState.value.copy(trashBlobs = emptyList())
        if (_uiState.value.selectedServer == "TRASH") {
            applyFilter()
        }
        saveCache(_uiState.value.allBlobs, emptyList(), _uiState.value.fileMetadata)
    }

    fun clear() {
        _uiState.value = GalleryState()
    }

    fun prepareListEvents(pubkey: String, servers: List<String>): Map<String, String> {
        return servers.associateWith { server ->
            BlossomAuthHelper.createListAuthEvent(pubkey, server)
        }
    }
    
    fun selectServer(serverUrl: String?) {
        _uiState.value = _uiState.value.copy(selectedServer = serverUrl)
        applyFilter()
    }

    fun toggleTag(tag: String) {
        val current = _uiState.value.selectedTags
        val next = if (current.contains(tag)) current - tag else current + tag
        _uiState.value = _uiState.value.copy(selectedTags = next)
        applyFilter()
    }

    fun clearTags() {
        _uiState.value = _uiState.value.copy(selectedTags = emptySet())
        applyFilter()
    }
    
    private fun applyFilter() {
        val current = _uiState.value
        var filtered = when (current.selectedServer) {
            null -> current.allBlobs.distinctBy { it.sha256 }
            "TRASH" -> current.trashBlobs
            else -> current.allBlobs.filter { it.serverUrl == current.selectedServer }
        }

        if (current.selectedTags.isNotEmpty()) {
            filtered = filtered.filter { blob ->
                val tags = blob.getTags().toSet()
                current.selectedTags.all { it in tags }
            }
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
                val newAll = _uiState.value.allBlobs.filter { it != blob }
                val isStillHostedElsewhere = newAll.any { it.sha256 == blob.sha256 }
                val newTrash = if (!isStillHostedElsewhere) {
                    (_uiState.value.trashBlobs + blob.copy(serverUrl = null)).distinctBy { it.sha256 }
                } else {
                    _uiState.value.trashBlobs
                }

                _uiState.value = _uiState.value.copy(
                    allBlobs = newAll, 
                    trashBlobs = newTrash,
                    isLoading = false
                )
                applyFilter()
                saveCache(newAll, newTrash, _uiState.value.fileMetadata)
            } else {
                 _uiState.value = _uiState.value.copy(isLoading = false, error = "Delete failed: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    // --- UPLOAD & MIRROR ---
    fun uploadFile(server: String, uri: Uri, mimeType: String, signedEventJson: String, expectedHash: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val authHeader = BlossomAuthHelper.encodeAuthHeader(signedEventJson)
            val result = repository.uploadBytes(server, uri, mimeType, authHeader, expectedHash)
            _uiState.value = _uiState.value.copy(isLoading = false, error = if (result.isFailure) result.exceptionOrNull()?.message else null)
            _uiState.value = _uiState.value.copy(preparedUploadHash = null, preparedUploadSize = null)
        }
    }

    fun mirrorBlob(server: String, sourceUrl: String, signedEventJson: String, originalBlob: BlossomBlob) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(serverMirroringStates = _uiState.value.serverMirroringStates + (server to true))
            val authHeader = BlossomAuthHelper.encodeAuthHeader(signedEventJson)
            val result = repository.mirrorBlob(server, sourceUrl, authHeader)
            if (result.isSuccess) {
                val uploadResult = result.getOrThrow()
                val newBlob = originalBlob.copy(url = uploadResult.url, serverUrl = server)
                val newAll = _uiState.value.allBlobs + newBlob
                val newTrash = _uiState.value.trashBlobs.filter { it.sha256 != originalBlob.sha256 }
                _uiState.value = _uiState.value.copy(allBlobs = newAll, trashBlobs = newTrash, serverMirroringStates = _uiState.value.serverMirroringStates - server)
                applyFilter()
                saveCache(newAll, newTrash, _uiState.value.fileMetadata)
            } else {
                _uiState.value = _uiState.value.copy(serverMirroringStates = _uiState.value.serverMirroringStates - server, error = "Mirror failed: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    fun mirrorToAll(pubkey: String, blob: BlossomBlob, allServers: List<String>, signer: com.aerith.auth.Nip55Signer, signerPackage: String?) {
        viewModelScope.launch {
            val currentServers = _uiState.value.allBlobs.filter { it.sha256 == blob.sha256 }.mapNotNull { it.serverUrl }
            val targetServers = allServers.filter { it !in currentServers }
            if (targetServers.isEmpty()) {
                _uiState.value = _uiState.value.copy(error = "Already on all servers")
                return@launch
            }
            _uiState.value = _uiState.value.copy(serverMirroringStates = _uiState.value.serverMirroringStates + targetServers.associate { it to true })
            var successCount = 0
            var failCount = 0
            val addedBlobs = mutableListOf<BlossomBlob>()
            targetServers.forEach { server ->
                val unsigned = BlossomAuthHelper.createUploadAuthEvent(pubkey, blob.sha256, blob.getSizeAsLong(), blob.getMimeType(), null, server)
                val signed = if (signerPackage != null) signer.signEventBackground(signerPackage, unsigned, pubkey) else null
                if (signed != null) {
                    val authHeader = BlossomAuthHelper.encodeAuthHeader(signed)
                    val result = repository.mirrorBlob(server, blob.url, authHeader)
                    if (result.isSuccess) { successCount++; addedBlobs.add(blob.copy(url = result.getOrThrow().url, serverUrl = server)) } else { failCount++ }
                } else { failCount++ }
                _uiState.value = _uiState.value.copy(serverMirroringStates = _uiState.value.serverMirroringStates - server)
            }
            if (addedBlobs.isNotEmpty()) {
                val newAll = _uiState.value.allBlobs + addedBlobs
                val newTrash = _uiState.value.trashBlobs.filter { it.sha256 != blob.sha256 }
                _uiState.value = _uiState.value.copy(allBlobs = newAll, trashBlobs = newTrash)
                applyFilter()
                saveCache(newAll, newTrash, _uiState.value.fileMetadata)
            }
            if (failCount > 0) _uiState.value = _uiState.value.copy(error = "Mirrored to $successCount servers, $failCount failed.")
        }
    }

    // --- LABELS (Kind 1063) ---
    fun updateLabels(
        pubkey: String,
        relays: List<String>,
        blob: BlossomBlob,
        newTags: List<String>,
        signedKind1063Json: String,
        signer: com.aerith.auth.Nip55Signer,
        signerPackage: String?
    ) {
        android.util.Log.d("GalleryViewModel", "updateLabels started for ${blob.sha256}")
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isLoading = true)
            
            // 1. Publish Kind 1063 to relays
            var publishSuccess = false
            relays.forEach { url ->
                try {
                    val client = RelayClient(url)
                    android.util.Log.d("GalleryViewModel", "Publishing to $url...")
                    if (client.publishEvent(signedKind1063Json)) {
                        android.util.Log.d("GalleryViewModel", "SUCCESS on $url")
                        publishSuccess = true
                    }
                } catch (e: Exception) { 
                    android.util.Log.e("GalleryViewModel", "Failed to publish to $url", e)
                }
            }

            // 2. Mirror to current server with updated tags (to update Blossom metadata)
            val server = blob.serverUrl
            if (server != null) {
                val unsignedMirror = BlossomAuthHelper.createUploadAuthEvent(
                    pubkey = pubkey,
                    sha256 = blob.sha256,
                    size = blob.getSizeAsLong(),
                    mimeType = blob.getMimeType(),
                    fileName = null,
                    serverUrl = server
                )
                
                val mirrorEventObj = JSONObject(unsignedMirror)
                val tagsArr = mirrorEventObj.getJSONArray("tags")
                newTags.forEach { tag ->
                    tagsArr.put(JSONArray().put("t").put(tag))
                }
                
                val signedMirror = if (signerPackage != null) {
                    signer.signEventBackground(signerPackage, mirrorEventObj.toString(), pubkey)
                } else null

                if (signedMirror != null) {
                    repository.mirrorBlob(server, blob.url, BlossomAuthHelper.encodeAuthHeader(signedMirror))
                }
            }

            withContext(Dispatchers.Main) {
                val newMeta = _uiState.value.fileMetadata.toMutableMap()
                newMeta[blob.sha256] = newTags

                val updatedBlobs = _uiState.value.allBlobs.map { b ->
                    if (b.sha256 == blob.sha256) {
                        b.copy(nip94 = newTags.map { listOf("t", it) })
                    } else b
                }
                _uiState.value = _uiState.value.copy(
                    allBlobs = updatedBlobs,
                    fileMetadata = newMeta,
                    isLoading = false
                )
                applyFilter()
                saveCache(updatedBlobs, _uiState.value.trashBlobs, newMeta)
            }
        }
    }
}
