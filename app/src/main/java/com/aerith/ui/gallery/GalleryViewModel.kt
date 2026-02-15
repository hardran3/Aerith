package com.aerith.ui.gallery

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aerith.core.blossom.BlossomBlob
import com.aerith.core.blossom.BlossomRepository
import com.aerith.core.blossom.BlossomUploadResult
import com.aerith.core.network.RelayClient
import com.aerith.core.nostr.BlossomAuthHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

    data class GalleryState(
    val allBlobs: List<BlossomBlob> = emptyList(), // Source of truth
    val filteredBlobs: List<BlossomBlob> = emptyList(), // Displayed
    val trashBlobs: List<BlossomBlob> = emptyList(), // Locally known but not on servers
    val servers: List<String> = emptyList(),
    val selectedServer: String? = null, // null = All, "TRASH" = Trash
    val selectedTags: List<String> = emptyList(),
    val selectedHashes: Set<String> = emptySet(),
    val selectedExtensions: Set<String> = emptySet(),
    val showImages: Boolean = true,
    val showVideos: Boolean = true,
    val vaultedHashes: Set<String> = emptySet(),
    val locallyCachedHashes: Set<String> = emptySet(),    val localServerUrl: String? = null,
    val isFileTypeBadgeEnabled: Boolean = true,
    val lastAuthHeaders: Map<String, String> = emptyMap(),
    val fileMetadata: Map<String, List<List<String>>> = emptyMap(), // hash -> List of tags [["t", "tag"], ["name", "file"]]
    val isLoading: Boolean = false,
    val loadingMessage: String? = null,
    val error: String? = null,
    val localSyncProgress: String? = null,
    val vaultSyncProgress: String? = null,
    
    // Track which servers are currently performing a mirror operation
    val serverMirroringStates: Map<String, Boolean> = emptyMap(),

    // For the new discovery flow
    val discoveredBlobs: List<BlossomBlob> = emptyList(), // Found via relays
    
    // For the new 2-step upload flow
    val preparedUploadHash: String? = null,
    val preparedUploadSize: Long? = null
)

class GalleryViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = BlossomRepository(application)
    private val settingsRepository = com.aerith.core.data.SettingsRepository(application)
    private val vaultManager = com.aerith.core.data.BlobVaultManager(application)
    private val _uiState = MutableStateFlow(GalleryState())
    val uiState: StateFlow<GalleryState> = _uiState.asStateFlow()

    private var currentLoadJob: kotlinx.coroutines.Job? = null

    private val moshi = com.squareup.moshi.Moshi.Builder()
        .add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
        .build()

    private val listAdapter = moshi.adapter<List<BlossomBlob>>(
        com.squareup.moshi.Types.newParameterizedType(List::class.java, BlossomBlob::class.java)
    )
    
    private val mapAdapter = moshi.adapter(Map::class.java)

    init {
        // Load from cache
        loadFromCache()
        scanVault() // New: Discover local files before servers
        refreshVaultedHashes()
    }

    /**
     * Scans the local vault and adds any confirmed hashes to the registry.
     * This handles the "Discovery after Reinstall" scenario.
     */
    private fun scanVault() {
        viewModelScope.launch(Dispatchers.IO) {
            val vaultedHashes = vaultManager.getVaultedHashes()
            if (vaultedHashes.isEmpty()) return@launch

            val currentBlobs = _uiState.value.allBlobs
            val currentHashes = currentBlobs.map { it.sha256 }.toSet()
            
            val newLocalBlobs = mutableListOf<BlossomBlob>()
            
            vaultedHashes.forEach { hash ->
                if (hash !in currentHashes) {
                    val file = vaultManager.getVaultFile(hash)
                    if (file != null) {
                        // Reconstruct basic blob info from file
                        val ext = file.extension
                        val mimeType = when(ext.lowercase()) {
                            "jpg", "jpeg" -> "image/jpeg"
                            "png" -> "image/png"
                            "gif" -> "image/gif"
                            "mp4" -> "video/mp4"
                            else -> "application/octet-stream"
                        }
                        
                        newLocalBlobs.add(BlossomBlob(
                            url = file.toURI().toString(),
                            sha256 = hash,
                            size = file.length(),
                            type = mimeType,
                            serverUrl = "LOCAL_VAULT"
                        ))
                    }
                }
            }

            if (newLocalBlobs.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    val updatedAll = (_uiState.value.allBlobs + newLocalBlobs).distinctBy { it.sha256 }
                    _uiState.value = _uiState.value.copy(allBlobs = updatedAll)
                    applyFilter()
                    saveCache(updatedAll, _uiState.value.trashBlobs, _uiState.value.fileMetadata)
                }
            }
        }
    }

    private fun refreshVaultedHashes() {
        viewModelScope.launch(Dispatchers.IO) {
            val vaulted = vaultManager.getVaultedHashes()
            val localHashes = settingsRepository.getLocallyCachedHashes()
            
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    vaultedHashes = vaulted,
                    locallyCachedHashes = localHashes,
                    isFileTypeBadgeEnabled = settingsRepository.isFileTypeBadgeEnabled()
                )
            }
        }
    }

    fun refreshDisplaySettings() {
        _uiState.value = _uiState.value.copy(
            isFileTypeBadgeEnabled = settingsRepository.isFileTypeBadgeEnabled()
        )
    }

    private fun loadFromCache() {
        val cachedJson = settingsRepository.getBlobCache()
        val trashJson = settingsRepository.getTrashCache()
        val metaJson = settingsRepository.getFileMetadataCache()
        val localHashes = settingsRepository.getLocallyCachedHashes()
        
        var cachedBlobs = emptyList<BlossomBlob>()
        var trashBlobs = emptyList<BlossomBlob>()
        var fileMetadata = emptyMap<String, List<List<String>>>()

        try {
            if (cachedJson != null) {
                cachedBlobs = listAdapter.fromJson(cachedJson) ?: emptyList()
            }
            if (trashJson != null) {
                trashBlobs = listAdapter.fromJson(trashJson) ?: emptyList()
            }
            if (metaJson != null) {
                val json = JSONObject(metaJson)
                val map = mutableMapOf<String, List<List<String>>>()
                json.keys().forEach { hash ->
                    val arr = json.getJSONArray(hash)
                    val tagsList = mutableListOf<List<String>>()
                    for (i in 0 until arr.length()) {
                        try {
                            // New format: hash -> [["t", "tag"], ["name", "file"]]
                            val tagArr = arr.getJSONArray(i)
                            val tagFields = mutableListOf<String>()
                            for (j in 0 until tagArr.length()) {
                                tagFields.add(tagArr.getString(j))
                            }
                            tagsList.add(tagFields)
                        } catch (e: Exception) {
                            // Fallback for old format: hash -> ["tag1", "tag2"]
                            tagsList.add(listOf("t", arr.getString(i)))
                        }
                    }
                    map[hash] = tagsList
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
            locallyCachedHashes = localHashes,
            servers = uniqueServers,
            isFileTypeBadgeEnabled = settingsRepository.isFileTypeBadgeEnabled()
        )
        applyFilter()
    }

    fun refreshMetadataOnly(externalMetadata: Map<String, List<List<String>>>) {
        if (externalMetadata.isEmpty()) return
        
        val mergedMeta = (externalMetadata + _uiState.value.fileMetadata).toMutableMap()
        val updatedBlobs = _uiState.value.allBlobs.map { blob ->
            val normalizedHash = blob.sha256.lowercase()
            val tags = mergedMeta[normalizedHash] ?: mergedMeta[blob.sha256] ?: blob.nip94 as? List<List<String>> ?: emptyList()
            if (tags.isNotEmpty()) {
                blob.copy(nip94 = tags)
            } else {
                blob
            }
        }

        val updatedTrash = _uiState.value.trashBlobs.map { blob ->
            val normalizedHash = blob.sha256.lowercase()
            val tags = mergedMeta[normalizedHash] ?: mergedMeta[blob.sha256] ?: blob.nip94 as? List<List<String>> ?: emptyList()
            if (tags.isNotEmpty()) {
                blob.copy(nip94 = tags)
            } else {
                blob
            }
        }

        _uiState.value = _uiState.value.copy(
            allBlobs = updatedBlobs,
            trashBlobs = updatedTrash,
            fileMetadata = mergedMeta
        )
        applyFilter()
        saveCache(updatedBlobs, updatedTrash, mergedMeta)
    }

    /**
     * Loads images from servers, optionally using authenticated headers.
     */
    fun loadImages(
        pubkey: String, 
        servers: List<String>, 
        authHeaders: Map<String, String> = emptyMap(),
        externalMetadata: Map<String, List<List<String>>> = emptyMap(),
        localBlossomUrl: String? = null
    ) {
        // Only fetch from remote servers. Local cache is handled as an overlay.
        val remoteServers = servers.filter { it != localBlossomUrl }
        
        if (remoteServers.isEmpty()) {
            _uiState.value = _uiState.value.copy(error = "No Blossom servers found")
            return
        }

        // Robust auth: if incoming headers are empty, use last known good ones
        val effectiveHeaders = if (authHeaders.isNotEmpty()) {
            authHeaders
        } else {
            _uiState.value.lastAuthHeaders
        }
        
        currentLoadJob?.cancel()
        currentLoadJob = viewModelScope.launch {
            // Only show full-screen loading if we have NO data in the registry yet
            val isRegistryEmpty = _uiState.value.allBlobs.isEmpty()
            if (isRegistryEmpty) {
                _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            } else {
                // Silent refresh: clear error but keep current blobs visible
                _uiState.value = _uiState.value.copy(error = null)
            }

            val result = repository.getFilesFromServers(pubkey, remoteServers, effectiveHeaders)
            
            var mediaBlobs = result.filter {
                val mime = it.getMimeType()
                mime?.startsWith("image/") == true || mime?.startsWith("video/") == true
            }

            // Merge metadata: Incoming relay data < Current UI state
            val mergedMeta = (externalMetadata + _uiState.value.fileMetadata).toMutableMap()

            mediaBlobs = mediaBlobs.map { blob ->
                val normalizedHash = blob.sha256.lowercase()
                val tags = mergedMeta[normalizedHash] ?: mergedMeta[blob.sha256] ?: blob.nip94 as? List<List<String>> ?: emptyList()
                if (tags.isNotEmpty()) {
                    blob.copy(nip94 = tags)
                } else {
                    blob
                }
            }
            
            // --- Registry-First Merge Logic ---
            // 1. Current Registry (initialized from cache on startup)
            val currentRegistry = _uiState.value.allBlobs.toMutableList()
            
            // 2. Upsert results from servers
            mediaBlobs.forEach { incoming ->
                val existingIndex = currentRegistry.indexOfFirst { it.sha256 == incoming.sha256 && it.serverUrl == incoming.serverUrl }
                if (existingIndex != -1) {
                    // Update existing record (confirmed still on server)
                    currentRegistry[existingIndex] = incoming
                } else {
                    // New discovery (new server for hash, or entirely new hash)
                    currentRegistry.add(incoming)
                }
            }

            // 3. Deduplicate and Sort
            val updatedRegistry = currentRegistry.distinctBy { it.sha256 + it.serverUrl }
                .sortedByDescending { it.getCreationTime() ?: 0L }

            val uniqueServers = (updatedRegistry.mapNotNull { it.serverUrl } + listOfNotNull(localBlossomUrl))
                .filter { it.isNotEmpty() }
                .distinct()
                .sorted()
            
            // Check if anything actually changed
            val hasChanged = _uiState.value.allBlobs != updatedRegistry || 
                             effectiveHeaders != _uiState.value.lastAuthHeaders ||
                             _uiState.value.isLoading

            if (hasChanged) {
                _uiState.value = _uiState.value.copy(
                    allBlobs = updatedRegistry,
                    fileMetadata = mergedMeta,
                    lastAuthHeaders = effectiveHeaders,
                    servers = uniqueServers,
                    localServerUrl = localBlossomUrl,
                    isLoading = false
                )
                applyFilter()
                saveCache(updatedRegistry, _uiState.value.trashBlobs, mergedMeta)
                refreshVaultedHashes()
                prefetchImages(updatedRegistry)

                // --- AUTO-VAULTING & LOCAL SYNC ---
                syncToVault(updatedRegistry)
                if (localBlossomUrl != null) {
                    syncToLocalCache(updatedRegistry + _uiState.value.trashBlobs, localBlossomUrl)
                }
            } else {
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    private fun syncToVault(blobs: List<BlossomBlob>) {
        viewModelScope.launch(Dispatchers.IO) {
            val uniqueBlobs = blobs.distinctBy { it.sha256 }
            val vaultedSet = vaultManager.getVaultedHashes()
            val toVault = uniqueBlobs.filter { it.sha256 !in vaultedSet }
            if (toVault.isEmpty()) return@launch

            val total = toVault.size
            val completedCount = java.util.concurrent.atomic.AtomicInteger(0)

            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(vaultSyncProgress = "Securing to Vault: 0 / $total")
            }

            android.util.Log.d("GalleryViewModel", "syncToVault: downloading ${toVault.size} blobs")
            
            // Limit concurrency strictly
            val semaphore = kotlinx.coroutines.sync.Semaphore(2)
            
            coroutineScope {
                toVault.forEach { blob ->
                    launch {
                        semaphore.withPermit {
                            // 1. Try to vault from existing Coil cache first
                            if (!vaultManager.contains(blob.sha256, vaultedSet)) {
                                vaultManager.vaultFromCache(blob.sha256, blob.getExtension())
                            }
                            
                            // 2. If still missing, download raw bytes and save to vault
                            if (!vaultManager.contains(blob.sha256)) { 
                                try {
                                    val request = okhttp3.Request.Builder()
                                        .url(blob.url)
                                        .header("User-Agent", "Aerith/1.0")
                                        .build()
                                    
                                    repository.client.newCall(request).execute().use { response ->
                                        if (response.isSuccessful) {
                                            val bytes = response.body?.bytes()
                                            if (bytes != null) {
                                                val tempFile = File.createTempFile("vault_download_", ".tmp", getApplication<Application>().cacheDir)
                                                tempFile.writeBytes(bytes)
                                                vaultManager.saveToVault(blob.sha256, blob.getExtension(), tempFile)
                                                tempFile.delete()
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("GalleryViewModel", "Failed to auto-vault ${blob.sha256}", e)
                                }
                            }
                        }
                        val current = completedCount.incrementAndGet()
                        withContext(Dispatchers.Main) {
                            _uiState.value = _uiState.value.copy(vaultSyncProgress = "Securing to Vault: $current / $total")
                        }
                    }
                }
            }
            // Final cleanup and refresh
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(vaultSyncProgress = null)
                refreshVaultedHashes()
            }
        }
    }

    private fun syncToLocalCache(blobs: List<BlossomBlob>, localUrl: String) {
        val uniqueBlobs = blobs.distinctBy { it.sha256 }
        android.util.Log.d("GalleryViewModel", "syncToLocalCache: total blobs=${blobs.size}, unique=${uniqueBlobs.size}, localUrl=$localUrl")
        viewModelScope.launch(Dispatchers.IO) {
            val localHashes = settingsRepository.getLocallyCachedHashes()
            val toSync = uniqueBlobs.filter { it.sha256 !in localHashes }
            
            android.util.Log.d("GalleryViewModel", "syncToLocalCache: needing sync=${toSync.size}")
            
            if (toSync.isEmpty()) {
                return@launch
            }

            val total = toSync.size
            toSync.forEachIndexed { index, blob ->
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(localSyncProgress = "Syncing to local: ${index + 1} / $total")
                }
                
                val exists = repository.checkBlobExists(localUrl, blob.sha256)
                if (exists) {
                    settingsRepository.addLocallyCachedHash(blob.sha256)
                } else {
                    android.util.Log.i("GalleryViewModel", "Mirroring ${blob.sha256} via remote fetch...")
                    val result = repository.fetchToLocalCache(blob.sha256, blob.url, localUrl)
                    if (result.isSuccess) {
                        settingsRepository.addLocallyCachedHash(blob.sha256)
                    } else {
                        android.util.Log.e("GalleryViewModel", "Failed to mirror ${blob.sha256}: ${result.exceptionOrNull()?.message}")
                    }
                }
            }
            withContext(Dispatchers.Main) { 
                _uiState.value = _uiState.value.copy(localSyncProgress = null)
                refreshVaultedHashes() 
            }
        }
    }

    private fun prefetchImages(blobs: List<BlossomBlob>) {
        val context = getApplication<Application>()
        val imageLoader = coil.Coil.imageLoader(context)
        
        viewModelScope.launch(Dispatchers.IO) {
            blobs.filter { it.getMimeType()?.startsWith("image/") == true }.forEach { blob ->
                val thumbRequest = coil.request.ImageRequest.Builder(context)
                    .data(blob.getThumbnailUrl())
                    .size(400, 400)
                    .precision(coil.size.Precision.INEXACT)
                    .bitmapConfig(android.graphics.Bitmap.Config.RGB_565)
                    .build()
                imageLoader.enqueue(thumbRequest)

                val highResRequest = coil.request.ImageRequest.Builder(context)
                    .data(blob.url)
                    .build()
                imageLoader.enqueue(highResRequest)
            }
        }
    }

    private fun saveCache(blobs: List<BlossomBlob>, trash: List<BlossomBlob>, metadata: Map<String, List<List<String>>>) {
        try {
            settingsRepository.saveBlobCache(listAdapter.toJson(blobs))
            settingsRepository.saveTrashCache(listAdapter.toJson(trash))
            
            val metaObj = JSONObject()
            metadata.forEach { (h, tagsList) ->
                val tagsArray = JSONArray()
                tagsList.forEach { tag ->
                    val tagArray = JSONArray()
                    tag.forEach { tagArray.put(it) }
                    tagsArray.put(tagArray)
                }
                metaObj.put(h, tagsArray)
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
        vaultManager.clearAll()
        refreshVaultedHashes()
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
        _uiState.value = _uiState.value.copy(selectedTags = emptyList())
        applyFilter()
    }

    fun toggleSelection(hash: String) {
        val current = _uiState.value.selectedHashes
        val next = if (current.contains(hash)) current - hash else current + hash
        _uiState.value = _uiState.value.copy(selectedHashes = next)
    }

    fun clearSelection() {
        _uiState.value = _uiState.value.copy(selectedHashes = emptySet())
    }

    fun toggleShowImages() {
        _uiState.value = _uiState.value.copy(showImages = !_uiState.value.showImages)
        applyFilter()
    }

    fun toggleShowVideos() {
        _uiState.value = _uiState.value.copy(showVideos = !_uiState.value.showVideos)
        applyFilter()
    }

    fun toggleExtension(ext: String) {
        val current = _uiState.value.selectedExtensions
        val next = if (current.contains(ext)) current - ext else current + ext
        _uiState.value = _uiState.value.copy(selectedExtensions = next)
        applyFilter()
    }

    /**
     * Returns a list of every unique tag currently known across all files and metadata.
     */
    fun getAllUniqueTags(): List<String> {
        val state = _uiState.value
        // Pull tags from ALL blobs in the registry and trash
        val blobTags = (state.allBlobs + state.trashBlobs)
            .flatMap { it.getTags(state.fileMetadata) }
        
        // Also pull any tags from the metadata cache that might not have confirmed blobs
        val orphanedTags = state.fileMetadata.values.flatMap { tagsList ->
            tagsList.filter { it.firstOrNull() == "t" }.mapNotNull { it.getOrNull(1) }
        }

        return (blobTags + orphanedTags).distinct().sorted()
    }

    /**
     * Returns a list of every file extension known in the library.
     */
    fun getAvailableExtensions(): List<String> {
        val state = _uiState.value
        return (state.allBlobs + state.trashBlobs)
            .mapNotNull { it.getMimeType()?.substringAfterLast("/")?.substringBefore(";") }
            .distinct()
            .sorted()
    }
    
    private fun applyFilter() {
        val current = _uiState.value
        
        // 1. Determine base set of blobs
        var filtered = when (current.selectedServer) {
            null -> {
                // All Media: Combine server blobs + discovered blobs (deduplicated by hash)
                (current.allBlobs + current.discoveredBlobs).distinctBy { it.sha256 }
            }
            "TRASH" -> current.trashBlobs.distinctBy { it.sha256 }
            "NOSTR" -> {
                // Nostr: Show only files found via 1063 that ARE NOT confirmed on any remote server
                current.discoveredBlobs
                    .filter { disc -> current.allBlobs.none { it.sha256 == disc.sha256 } }
                    .distinctBy { it.sha256 }
            }
            current.localServerUrl -> {
                // Local cache overlay
                (current.allBlobs + current.trashBlobs + current.discoveredBlobs)
                    .filter { it.sha256 in current.locallyCachedHashes }
                    .distinctBy { it.sha256 }
            }
            else -> {
                // Specific server: Only show files confirmed to be on that server
                current.allBlobs
                    .filter { it.serverUrl == current.selectedServer }
                    .distinctBy { it.sha256 }
            }
        }

        // Filter by Media Type
        filtered = filtered.filter { blob ->
            val mime = blob.getMimeType() ?: ""
            val isImg = mime.startsWith("image/")
            val isVid = mime.startsWith("video/")
            (current.showImages && isImg) || (current.showVideos && isVid)
        }

        // Filter by Extension
        if (current.selectedExtensions.isNotEmpty()) {
            filtered = filtered.filter { blob ->
                val ext = blob.getMimeType()?.substringAfterLast("/")?.substringBefore(";")
                ext in current.selectedExtensions
            }
        }

        if (current.selectedTags.isNotEmpty()) {
            filtered = filtered.filter { blob ->
                val tags = blob.getTags(current.fileMetadata).toSet()
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

    /**
     * Checks if a blob exists on a server (low-impact HEAD) and updates state if confirmed.
     */
    fun verifyBlobExistence(server: String, originalBlob: BlossomBlob) {
        viewModelScope.launch(Dispatchers.IO) {
            val exists = repository.checkBlobExists(server, originalBlob.sha256)
            if (exists) {
                withContext(Dispatchers.Main) {
                    val cleanServer = server.removeSuffix("/")
                    val newBlob = originalBlob.copy(url = "$cleanServer/${originalBlob.sha256}", serverUrl = server)
                    val newAll = (_uiState.value.allBlobs + newBlob).distinctBy { it.serverUrl + it.sha256 }
                    _uiState.value = _uiState.value.copy(allBlobs = newAll)
                    applyFilter()
                    saveCache(newAll, _uiState.value.trashBlobs, _uiState.value.fileMetadata)
                }
            }
        }
    }

    fun mirrorBlob(server: String, sourceUrl: String, signedEventJson: String, originalBlob: BlossomBlob) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(serverMirroringStates = _uiState.value.serverMirroringStates + (server to true))
            val authHeader = BlossomAuthHelper.encodeAuthHeader(signedEventJson)
            
            val vaultFile = vaultManager.getVaultFile(originalBlob.sha256)
            val result = if (vaultFile != null && vaultFile.exists()) {
                // Actual UPLOAD from Vault
                repository.uploadBytes(server, Uri.fromFile(vaultFile), originalBlob.getMimeType() ?: "application/octet-stream", authHeader, originalBlob.sha256)
                    .map { uploadResult -> BlossomUploadResult(uploadResult.url, uploadResult.serverHash, server) }
            } else {
                // Fallback to MIRROR
                repository.mirrorBlob(server, sourceUrl, authHeader)
            }

            if (result.isSuccess) {
                val uploadResult = result.getOrThrow()
                val newBlob = originalBlob.copy(url = uploadResult.url, serverUrl = server)
                val newAll = (_uiState.value.allBlobs + newBlob).distinctBy { it.serverUrl + it.sha256 }
                val newTrash = _uiState.value.trashBlobs.filter { it.sha256 != originalBlob.sha256 }
                _uiState.value = _uiState.value.copy(allBlobs = newAll, trashBlobs = newTrash, serverMirroringStates = _uiState.value.serverMirroringStates - server)
                applyFilter()
                saveCache(newAll, newTrash, _uiState.value.fileMetadata)
            } else {
                _uiState.value = _uiState.value.copy(serverMirroringStates = _uiState.value.serverMirroringStates - server, error = "Action failed: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    fun mirrorToLocalCache(hash: String, sourceUrl: String, originalBlob: BlossomBlob, localBlossomUrl: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(serverMirroringStates = _uiState.value.serverMirroringStates + (localBlossomUrl to true))
            
            val vaultFile = vaultManager.getVaultFile(hash)
            val result = if (vaultFile != null && vaultFile.exists()) {
                // Actual UPLOAD from Vault to Local Blossom
                val authHeader = "" // Local typically doesn't need auth, or we can add it if needed
                repository.uploadBytes(localBlossomUrl, Uri.fromFile(vaultFile), originalBlob.getMimeType() ?: "application/octet-stream", authHeader, hash)
                    .map { uploadResult -> BlossomUploadResult(uploadResult.url, uploadResult.serverHash, localBlossomUrl) }
            } else {
                // Fallback to fetching
                repository.fetchToLocalCache(hash, sourceUrl, localBlossomUrl)
                    .map { unused -> BlossomUploadResult("$localBlossomUrl/$hash", hash, localBlossomUrl) }
            }

            if (result.isSuccess) {
                settingsRepository.addLocallyCachedHash(hash)
                val uploadResult = result.getOrThrow()
                val newBlob = originalBlob.copy(url = uploadResult.url, serverUrl = localBlossomUrl)
                val newAll = (_uiState.value.allBlobs + newBlob).distinctBy { it.serverUrl + it.sha256 }
                val newTrash = _uiState.value.trashBlobs.filter { it.sha256 != originalBlob.sha256 }
                _uiState.value = _uiState.value.copy(allBlobs = newAll, trashBlobs = newTrash, serverMirroringStates = _uiState.value.serverMirroringStates - localBlossomUrl)
                applyFilter()
                saveCache(newAll, newTrash, _uiState.value.fileMetadata)
                refreshVaultedHashes()
            } else {
                _uiState.value = _uiState.value.copy(serverMirroringStates = _uiState.value.serverMirroringStates - localBlossomUrl, error = "Local cache sync failed: ${result.exceptionOrNull()?.message}")
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
            
            val vaultFile = vaultManager.getVaultFile(blob.sha256)

            targetServers.forEach { server ->
                val unsigned = BlossomAuthHelper.createUploadAuthEvent(pubkey, blob.sha256, blob.getSizeAsLong(), blob.getMimeType(), null, server)
                val signed = if (signerPackage != null) signer.signEventBackground(signerPackage, unsigned, pubkey) else null
                
                if (signed != null) {
                    val authHeader = BlossomAuthHelper.encodeAuthHeader(signed)
                    
                    val result = if (vaultFile != null && vaultFile.exists()) {
                        // ACTUAL UPLOAD
                        repository.uploadBytes(server, Uri.fromFile(vaultFile), blob.getMimeType() ?: "application/octet-stream", authHeader, blob.sha256)
                            .map { uploadResult -> BlossomUploadResult(uploadResult.url, uploadResult.serverHash, server) }
                    } else {
                        // Fallback to MIRROR
                        repository.mirrorBlob(server, blob.url, authHeader)
                    }

                    if (result.isSuccess) { 
                        successCount++
                        addedBlobs.add(blob.copy(url = result.getOrThrow().url, serverUrl = server)) 
                    } else { 
                        failCount++ 
                    }
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
            if (failCount > 0) _uiState.value = _uiState.value.copy(error = "Action completed: $successCount success, $failCount failed.")
        }
    }

    fun bulkMirrorToAll(
        pubkey: String,
        hashes: Set<String>,
        allServers: List<String>,
        signer: com.aerith.auth.Nip55Signer,
        signerPackage: String?,
        localBlossomUrl: String? = null
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, loadingMessage = "Preparing to mirror...")
            val total = hashes.size
            val completedCount = java.util.concurrent.atomic.AtomicInteger(0)
            val allAddedBlobs = java.util.Collections.synchronizedList(mutableListOf<BlossomBlob>())
            
            kotlinx.coroutines.coroutineScope {
                hashes.forEach { hash ->
                    launch(Dispatchers.IO) {
                        val originalBlob = _uiState.value.allBlobs.find { it.sha256 == hash }
                            ?: _uiState.value.trashBlobs.find { it.sha256 == hash }
                        
                        if (originalBlob != null) {
                            val currentServers = _uiState.value.allBlobs.filter { it.sha256 == hash }.mapNotNull { it.serverUrl }
                            val targetServers = allServers.filter { it !in currentServers }
                            
                            // 1. Local Cache Mirror (Async)
                            val localMirrorJob = if (localBlossomUrl != null && localBlossomUrl !in currentServers) {
                                launch {
                                    val result = repository.fetchToLocalCache(hash, originalBlob.url, localBlossomUrl)
                                    if (result.isSuccess) {
                                        allAddedBlobs.add(originalBlob.copy(url = "$localBlossomUrl/$hash", serverUrl = localBlossomUrl))
                                    }
                                }
                            } else null

                            // 2. Remote Server Mirrors/Uploads (Parallel)
                            targetServers.forEach { server ->
                                if (server == localBlossomUrl) return@forEach
                                launch {
                                    val vaultFile = vaultManager.getVaultFile(hash)
                                    val unsigned = BlossomAuthHelper.createUploadAuthEvent(
                                        pubkey, hash, originalBlob.getSizeAsLong(), 
                                        originalBlob.getMimeType(), 
                                        originalBlob.getName(_uiState.value.fileMetadata), 
                                        server
                                    )
                                    val signed = if (signerPackage != null) signer.signEventBackground(signerPackage, unsigned, pubkey) else null
                                    
                                    if (signed != null) {
                                        val authHeader = BlossomAuthHelper.encodeAuthHeader(signed)
                                        
                                        val result = if (vaultFile != null && vaultFile.exists()) {
                                            // ACTUAL UPLOAD from Vault
                                            repository.uploadBytes(server, Uri.fromFile(vaultFile), originalBlob.getMimeType() ?: "application/octet-stream", authHeader, hash)
                                                .map { uploadResult -> BlossomUploadResult(uploadResult.url, uploadResult.serverHash, server) } // Map to Result<BlossomUploadResult>
                                        } else {
                                            // Fallback to MIRROR if not in vault
                                            repository.mirrorBlob(server, originalBlob.url, authHeader)
                                        }

                                        if (result.isSuccess) {
                                            allAddedBlobs.add(originalBlob.copy(url = result.getOrThrow().url, serverUrl = server))
                                        }
                                    }
                                }
                            }
                        }
                        val current = completedCount.incrementAndGet()
                        withContext(Dispatchers.Main) {
                            _uiState.value = _uiState.value.copy(loadingMessage = "Mirroring $current / $total...")
                        }
                    }
                }
            }

            val newAll = (_uiState.value.allBlobs + allAddedBlobs).distinctBy { it.serverUrl + it.sha256 }
            val newTrash = _uiState.value.trashBlobs.filter { t -> allAddedBlobs.none { a -> a.sha256 == t.sha256 } }
            
            _uiState.value = _uiState.value.copy(
                allBlobs = newAll,
                trashBlobs = newTrash,
                isLoading = false,
                loadingMessage = null,
                selectedHashes = emptySet()
            )
            applyFilter()
            saveCache(newAll, newTrash, _uiState.value.fileMetadata)
        }
    }

    fun bulkDelete(
        pubkey: String,
        hashes: Set<String>,
        targetServers: List<String>,
        signer: com.aerith.auth.Nip55Signer,
        signerPackage: String?
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, loadingMessage = "Preparing to delete...")
            val total = hashes.size
            val completedCount = java.util.concurrent.atomic.AtomicInteger(0)
            
            // Collect items that were fully removed from selected servers
            val deletedInstances = java.util.Collections.synchronizedList(mutableListOf<Pair<String, String>>()) // hash, server

            kotlinx.coroutines.coroutineScope {
                hashes.forEach { hash ->
                    launch(Dispatchers.IO) {
                        // Find all server instances of this hash that are in our target list
                        val instancesToDelete = _uiState.value.allBlobs.filter { it.sha256 == hash && it.serverUrl in targetServers }
                        
                        // Delete from selected servers in parallel
                        instancesToDelete.map { blob ->
                            launch {
                                val server = blob.serverUrl ?: return@launch
                                val unsigned = BlossomAuthHelper.createDeleteAuthEvent(pubkey, hash, server)
                                val signed = if (signerPackage != null) signer.signEventBackground(signerPackage, unsigned, pubkey) else null
                                
                                if (signed != null) {
                                    val result = repository.deleteBlob(server, hash, authHeader = BlossomAuthHelper.encodeAuthHeader(signed))
                                    if (result.isSuccess) {
                                        deletedInstances.add(hash to server)
                                    }
                                }
                            }
                        }

                        val current = completedCount.incrementAndGet()
                        withContext(Dispatchers.Main) {
                            _uiState.value = _uiState.value.copy(loadingMessage = "Deleting $current / $total...")
                        }
                    }
                }
            }

            // Perform final state update on Main thread
            val deletedHashesAndServers = deletedInstances.toSet()
            val newAllBlobs = _uiState.value.allBlobs.filter { (it.sha256 to it.serverUrl) !in deletedHashesAndServers }
            
            // Files that are no longer on ANY remote server should move to trash
            val remainingRemoteHashes = newAllBlobs.map { it.sha256 }.toSet()
            val fullyRemovedHashes = hashes.filter { it !in remainingRemoteHashes }
            
            val newTrash = (_uiState.value.trashBlobs + fullyRemovedHashes.mapNotNull { hash ->
                val representative = _uiState.value.allBlobs.find { it.sha256 == hash }
                if (representative != null) {
                    // Try to vault from cache if not already in vault
                    vaultManager.vaultFromCache(hash, representative.getExtension())
                    representative.copy(serverUrl = null)
                } else null
            }).distinctBy { it.sha256 }
            
            _uiState.value = _uiState.value.copy(
                allBlobs = newAllBlobs,
                trashBlobs = newTrash,
                isLoading = false,
                loadingMessage = null,
                selectedHashes = emptySet()
            )
            applyFilter()
            saveCache(newAllBlobs, newTrash, _uiState.value.fileMetadata)
            refreshVaultedHashes()
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
        signerPackage: String?,
        newName: String? = null
    ) {
        android.util.Log.d("GalleryViewModel", "updateLabels started for ${blob.sha256}")
        
        // 1. Optimistic Update: Update state immediately on Main thread
        val currentMeta = _uiState.value.fileMetadata.toMutableMap()
        
        val optimisticallyUpdatedBlobs = _uiState.value.allBlobs.map { b ->
            if (b.sha256 == blob.sha256) {
                val newNip94 = newTags.map { listOf("t", it) }.toMutableList()
                if (newName != null) {
                    newNip94.add(listOf("name", newName))
                } else {
                    // Preserve existing name if it exists
                    val existingName = b.getName(_uiState.value.fileMetadata)
                    if (existingName != null) {
                        newNip94.add(listOf("name", existingName))
                    }
                }
                
                // Update map for persistence
                currentMeta[blob.sha256] = newNip94
                
                b.copy(nip94 = newNip94)
            } else b
        }
        
        _uiState.value = _uiState.value.copy(
            allBlobs = optimisticallyUpdatedBlobs,
            fileMetadata = currentMeta
        )
        applyFilter()

        // 2. Perform network operations in parallel
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isLoading = true)
            
            coroutineScope {
                // Parallelize Relay Publishing
                relays.forEach { url ->
                    launch {
                        try {
                            val client = RelayClient(url)
                            client.publishEvent(signedKind1063Json)
                        } catch (e: Exception) { 
                            android.util.Log.e("GalleryViewModel", "Failed to publish to $url", e)
                        }
                    }
                }

                // Mirror to current server with updated tags
                val server = blob.serverUrl
                if (server != null) {
                    launch {
                        val unsignedMirrorString = BlossomAuthHelper.createUploadAuthEvent(
                            pubkey = pubkey,
                            sha256 = blob.sha256,
                            size = blob.getSizeAsLong(),
                            mimeType = blob.getMimeType(),
                            fileName = newName ?: blob.getName(_uiState.value.fileMetadata),
                            serverUrl = server
                        )
                        
                        // Add tags using Moshi instead of JSONObject to avoid escaping slashes
                        val mirrorEventMap = (mapAdapter.fromJson(unsignedMirrorString) as? Map<String, Any>)?.toMutableMap()
                        if (mirrorEventMap != null) {
                            val existingTags = (mirrorEventMap["tags"] as? List<*>) ?: emptyList<Any>()
                            val tagsList = existingTags.toMutableList()
                            newTags.forEach { tag ->
                                tagsList.add(listOf("t", tag))
                            }
                            mirrorEventMap["tags"] = tagsList
                            
                            val finalMirrorJson = mapAdapter.toJson(mirrorEventMap)
                            val signedMirror = if (signerPackage != null) {
                                signer.signEventBackground(signerPackage, finalMirrorJson, pubkey)
                            } else null

                            if (signedMirror != null) {
                                repository.mirrorBlob(server, blob.url, BlossomAuthHelper.encodeAuthHeader(signedMirror))
                            }
                        }
                    }
                }
            }

            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(isLoading = false)
                saveCache(_uiState.value.allBlobs, _uiState.value.trashBlobs, _uiState.value.fileMetadata)
            }
        }
    }

    fun bulkUpdateLabels(
        pubkey: String,
        relays: List<String>,
        hashes: Set<String>,
        newTags: List<String>,
        signer: com.aerith.auth.Nip55Signer,
        signerPackage: String?
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, loadingMessage = "Preparing to update labels...")
            val total = hashes.size
            val completedCount = java.util.concurrent.atomic.AtomicInteger(0)
            val updatedMetadata = java.util.Collections.synchronizedMap(_uiState.value.fileMetadata.toMutableMap())

            kotlinx.coroutines.coroutineScope {
                hashes.forEach { hash ->
                    launch(Dispatchers.IO) {
                        val blob = _uiState.value.allBlobs.find { it.sha256 == hash } 
                            ?: _uiState.value.trashBlobs.find { it.sha256 == hash }
                        
                        if (blob != null) {
                            // Merge with existing tags
                            val currentTags = blob.getTags(_uiState.value.fileMetadata)
                            val combinedLabels = (currentTags + newTags).distinct()
                            
                            // Build full tag list preserving other NIP-94 tags (like name)
                            val existingNip94 = (blob.nip94 as? List<*>)?.filterIsInstance<List<String>>() ?: emptyList()
                            val otherTags = existingNip94.filter { it.firstOrNull() != "t" }
                            val newNip94 = otherTags + combinedLabels.map { listOf("t", it) }

                            // 1. Create and Sign Kind 1063
                            val unsigned = BlossomAuthHelper.createFileMetadataEvent(
                                pubkey, hash, blob.url, blob.getMimeType(), combinedLabels,
                                name = blob.getName(_uiState.value.fileMetadata)
                            )
                            val signed = if (signerPackage != null) {
                                signer.signEventBackground(signerPackage, unsigned, pubkey)
                            } else null

                            if (signed != null) {
                                // 2. Parallel Tasks: Publish to relays and Mirror to server
                                launch {
                                    relays.forEach { url ->
                                        launch {
                                            try { RelayClient(url).publishEvent(signed) } catch (e: Exception) {}
                                        }
                                    }
                                }

                                launch {
                                    // 3. Mirror to server
                                    val server = blob.serverUrl
                                    if (server != null) {
                                        val unsignedMirrorString = BlossomAuthHelper.createUploadAuthEvent(
                                            pubkey, hash, blob.getSizeAsLong(), blob.getMimeType(), blob.getName(_uiState.value.fileMetadata), server
                                        )
                                        
                                        // Add tags using Moshi instead of JSONObject to avoid escaping slashes
                                        val mirrorEventMap = (mapAdapter.fromJson(unsignedMirrorString) as? Map<String, Any>)?.toMutableMap()
                                        if (mirrorEventMap != null) {
                                            val existingTags = (mirrorEventMap["tags"] as? List<*>) ?: emptyList<Any>()
                                            val tagsList = existingTags.toMutableList()
                                            combinedLabels.forEach { tag ->
                                                tagsList.add(listOf("t", tag))
                                            }
                                            mirrorEventMap["tags"] = tagsList
                                            
                                            val finalMirrorJson = mapAdapter.toJson(mirrorEventMap)
                                            val signedMirror = signer.signEventBackground(signerPackage!!, finalMirrorJson, pubkey)
                                            if (signedMirror != null) {
                                                repository.mirrorBlob(server, blob.url, BlossomAuthHelper.encodeAuthHeader(signedMirror))
                                            }
                                        }
                                    }
                                }
                                updatedMetadata[hash] = newNip94
                            }
                        }
                        
                        val current = completedCount.incrementAndGet()
                        withContext(Dispatchers.Main) {
                            _uiState.value = _uiState.value.copy(loadingMessage = "Updating labels $current / $total...")
                        }
                    }
                }
            }

            withContext(Dispatchers.Main) {
                val finalMeta = updatedMetadata.toMap()
                val updatedBlobs = _uiState.value.allBlobs.map { b ->
                    if (b.sha256 in hashes) {
                        b.copy(nip94 = finalMeta[b.sha256] ?: b.nip94)
                    } else b
                }
                
                _uiState.value = _uiState.value.copy(
                    allBlobs = updatedBlobs,
                    fileMetadata = finalMeta,
                    selectedHashes = emptySet(),
                    isLoading = false,
                    loadingMessage = null
                )
                applyFilter()
                saveCache(updatedBlobs, _uiState.value.trashBlobs, finalMeta)
            }
        }
    }
}
