package com.aerith.ui.upload

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aerith.core.blossom.BlossomRepository
import com.aerith.core.nostr.BlossomAuthHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class PendingUpload(
    val uri: Uri,
    val mimeType: String,
    val hash: String,
    val size: Long,
    val originalFileName: String,
    val displayName: String, // Editable main part
    val extension: String
)

data class UploadState(
    val isUploading: Boolean = false,
    val successMessage: String? = null,
    val error: String? = null,
    val progress: String? = null,
    
    // For review flow
    val showReviewDialog: Boolean = false,
    val uploadQueue: List<PendingUpload> = emptyList(),
    val selectedServers: List<String> = emptyList(),
    val globalTags: List<String> = emptyList(),

    // Sequential execution state
    val currentUpload: PendingUpload? = null,
    val targetServer: String? = null,
    val uploadedUrls: List<String> = emptyList() // To collect for fallbacks
)

class UploadViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = BlossomRepository(application)
    private val settingsRepository = com.aerith.core.data.SettingsRepository(application)
    private val _uiState = MutableStateFlow(UploadState())
    val uiState: StateFlow<UploadState> = _uiState.asStateFlow()

    fun startBulkUpload(uris: List<Uri>, contentResolver: android.content.ContentResolver, defaultServers: List<String>) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isUploading = true, progress = "Processing files...")
            val pending = uris.mapNotNull { uri ->
                val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
                val result = repository.prepareUpload(uri, mimeType)
                if (result.isSuccess) {
                    val (hash, size) = result.getOrThrow()
                    val fileName = uri.lastPathSegment ?: "file"
                    val ext = fileName.substringAfterLast(".", "")
                    val mainPart = fileName.substringBeforeLast(".")
                    PendingUpload(uri, mimeType, hash, size, fileName, mainPart, ext)
                } else null
            }
            
            if (pending.isNotEmpty()) {
                _uiState.value = _uiState.value.copy(
                    isUploading = false,
                    uploadQueue = pending,
                    selectedServers = defaultServers,
                    showReviewDialog = true,
                    progress = null
                )
            } else {
                _uiState.value = _uiState.value.copy(isUploading = false, error = "Failed to process files")
            }
        }
    }

    fun updatePendingUpload(hash: String, newDisplayName: String) {
        val updatedQueue = _uiState.value.uploadQueue.map {
            if (it.hash == hash) it.copy(displayName = newDisplayName) else it
        }
        _uiState.value = _uiState.value.copy(uploadQueue = updatedQueue)
    }

    fun setBatchOptions(servers: List<String>, tags: List<String>) {
        _uiState.value = _uiState.value.copy(selectedServers = servers, globalTags = tags)
    }

    fun confirmUpload() {
        _uiState.value = _uiState.value.copy(showReviewDialog = false)
        // Sequential process begins via LaunchedEffect in UI watching currentUpload/uploadQueue
    }

    fun dismissReview() {
        _uiState.value = UploadState()
    }

    fun processNextInQueue() {
        val currentQueue = _uiState.value.uploadQueue
        if (currentQueue.isEmpty()) {
            _uiState.value = _uiState.value.copy(isUploading = false, currentUpload = null, progress = null)
            return
        }

        val next = currentQueue.first()
        val servers = _uiState.value.selectedServers
        _uiState.value = _uiState.value.copy(
            currentUpload = next,
            targetServer = servers.firstOrNull(),
            uploadedUrls = emptyList()
        )
    }

    /**
     * Uploads to current target server. If it fails, tries next server in list.
     */
    fun uploadToCurrentServer(
        uri: Uri,
        mimeType: String,
        signedEventJson: String,
        expectedHash: String,
        signer: com.aerith.auth.Nip55Signer,
        signerPackage: String?,
        pubkey: String,
        relays: List<String>
    ) {
        viewModelScope.launch {
            val server = _uiState.value.targetServer ?: return@launch
            val currentUpload = _uiState.value.currentUpload ?: return@launch
            
            _uiState.value = _uiState.value.copy(isUploading = true, progress = "Uploading to $server...")
            
            val authHeader = BlossomAuthHelper.encodeAuthHeader(signedEventJson)
            val result = repository.uploadBytes(server, uri, mimeType, authHeader, expectedHash)
            
            if (result.isSuccess) {
                val uploadResult = result.getOrThrow()
                val mainUrl = uploadResult.url
                val collectedUrls = mutableListOf(mainUrl)
                
                _uiState.value = _uiState.value.copy(
                    progress = "Mirroring to other servers..."
                )
                
                // MIRROR to all other servers in background
                val otherServers = _uiState.value.selectedServers.filter { it != server }
                otherServers.forEach { otherServer ->
                    val unsigned = BlossomAuthHelper.createUploadAuthEvent(
                        pubkey, expectedHash, currentUpload.size, mimeType, 
                        "${currentUpload.displayName}.${currentUpload.extension}", 
                        otherServer
                    )
                    
                    val signed = if (signerPackage != null) signer.signEventBackground(signerPackage, unsigned, pubkey) else null
                    if (signed != null) {
                        val mirrorResult = repository.mirrorBlob(otherServer, mainUrl, BlossomAuthHelper.encodeAuthHeader(signed))
                        if (mirrorResult.isSuccess) {
                            collectedUrls.add(mirrorResult.getOrThrow().url)
                        }
                    }
                }

                // --- FINAL STEP: Kind 1063 ---
                _uiState.value = _uiState.value.copy(progress = "Publishing metadata...")
                val fallbacks = if (collectedUrls.size > 1) collectedUrls.drop(1) else emptyList()
                val unsigned1063 = BlossomAuthHelper.createFileMetadataEvent(
                    pubkey = pubkey,
                    sha256 = expectedHash,
                    url = mainUrl,
                    mimeType = mimeType,
                    tags = _uiState.value.globalTags,
                    name = "${currentUpload.displayName}.${currentUpload.extension}",
                    fallbacks = fallbacks
                )
                
                val signed1063 = if (signerPackage != null) signer.signEventBackground(signerPackage, unsigned1063, pubkey) else null
                if (signed1063 != null) {
                    relays.forEach { relayUrl ->
                        try {
                            com.aerith.core.network.RelayClient(relayUrl).publishEvent(signed1063)
                        } catch (e: Exception) {}
                    }
                }

                // Update local metadata
                val currentMeta = settingsRepository.getFileMetadataCache()
                val metaObj = if (currentMeta != null) JSONObject(currentMeta) else JSONObject()
                
                val tagsArr = JSONArray()
                // Add labels
                _uiState.value.globalTags.forEach { label ->
                    val tagArr = JSONArray().put("t").put(label)
                    tagsArr.put(tagArr)
                }
                // Add name
                val fileName = "${currentUpload.displayName}.${currentUpload.extension}"
                tagsArr.put(JSONArray().put("name").put(fileName))
                
                metaObj.put(expectedHash.lowercase(), tagsArr)
                settingsRepository.saveFileMetadataCache(metaObj.toString())

                val remainingQueue = _uiState.value.uploadQueue.filter { it.hash != expectedHash }
                
                if (remainingQueue.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        isUploading = false, 
                        successMessage = "All files uploaded, mirrored, and tagged!",
                        currentUpload = null,
                        uploadQueue = emptyList(),
                        progress = null
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        isUploading = false, 
                        currentUpload = null,
                        uploadQueue = remainingQueue
                    )
                }
            } else {
                // TRY NEXT SERVER
                val allServers = _uiState.value.selectedServers
                val currentIndex = allServers.indexOf(server)
                if (currentIndex != -1 && currentIndex < allServers.size - 1) {
                    val nextServer = allServers[currentIndex + 1]
                    _uiState.value = _uiState.value.copy(
                        targetServer = nextServer,
                        isUploading = false // Triggers re-sign for next server in UI
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        isUploading = false, 
                        error = "Failed to upload to any server: ${result.exceptionOrNull()?.message}"
                    )
                }
            }
        }
    }
    
    fun clearState() {
        _uiState.value = UploadState()
    }
}
