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

data class PendingUpload(
    val uri: Uri,
    val mimeType: String,
    val hash: String,
    val size: Long
)

data class UploadState(
    val isUploading: Boolean = false,
    val successMessage: String? = null,
    val error: String? = null,
    val progress: String? = null,
    
    // For sequential flow
    val uploadQueue: List<PendingUpload> = emptyList(),
    val currentUpload: PendingUpload? = null,
    val targetServer: String? = null
)

class UploadViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = BlossomRepository(application)
    private val _uiState = MutableStateFlow(UploadState())
    val uiState: StateFlow<UploadState> = _uiState.asStateFlow()

    fun startBulkUpload(uris: List<Uri>, contentResolver: android.content.ContentResolver) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isUploading = true, progress = "Processing files...")
            val pending = uris.mapNotNull { uri ->
                val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
                val result = repository.prepareUpload(uri, mimeType)
                if (result.isSuccess) {
                    val (hash, size) = result.getOrThrow()
                    PendingUpload(uri, mimeType, hash, size)
                } else null
            }
            
            if (pending.isNotEmpty()) {
                _uiState.value = _uiState.value.copy(
                    isUploading = false,
                    uploadQueue = pending,
                    progress = null
                )
                // The UI will see the queue and trigger the first signature
            } else {
                _uiState.value = _uiState.value.copy(isUploading = false, error = "Failed to process files")
            }
        }
    }

    fun processNextInQueue(servers: List<String>) {
        val currentQueue = _uiState.value.uploadQueue
        if (currentQueue.isEmpty()) {
            _uiState.value = _uiState.value.copy(isUploading = false, currentUpload = null, progress = null)
            return
        }

        val next = currentQueue.first()
        _uiState.value = _uiState.value.copy(
            currentUpload = next,
            targetServer = servers.firstOrNull() // Start with first server
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
        allServers: List<String>,
        signer: com.aerith.auth.Nip55Signer,
        signerPackage: String?,
        pubkey: String
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isUploading = true, progress = "Uploading to ${_uiState.value.targetServer}...")
            val server = _uiState.value.targetServer ?: return@launch
            val authHeader = BlossomAuthHelper.encodeAuthHeader(signedEventJson)
            val result = repository.uploadBytes(server, uri, mimeType, authHeader, expectedHash)
            
            if (result.isSuccess) {
                val uploadResult = result.getOrThrow()
                val remainingQueue = _uiState.value.uploadQueue.filter { it.hash != expectedHash }
                
                _uiState.value = _uiState.value.copy(
                    uploadQueue = remainingQueue,
                    progress = "Mirroring to other servers..."
                )
                
                // MIRROR to all other servers in background
                val otherServers = allServers.filter { it != server }
                otherServers.forEach { otherServer ->
                    val unsigned = BlossomAuthHelper.createUploadAuthEvent(pubkey, expectedHash, _uiState.value.currentUpload?.size ?: 0, mimeType, null, otherServer)
                    val signed = if (signerPackage != null) signer.signEventBackground(signerPackage, unsigned, pubkey) else null
                    if (signed != null) {
                        repository.mirrorBlob(otherServer, uploadResult.url, BlossomAuthHelper.encodeAuthHeader(signed))
                    }
                }

                if (remainingQueue.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        isUploading = false, 
                        successMessage = "All files uploaded and mirrored!",
                        currentUpload = null,
                        progress = null
                    )
                } else {
                    // Triggers next in queue via LaunchedEffect in UI
                    _uiState.value = _uiState.value.copy(isUploading = false, currentUpload = null)
                }
            } else {
                // TRY NEXT SERVER
                val currentIndex = allServers.indexOf(server)
                if (currentIndex != -1 && currentIndex < allServers.size - 1) {
                    val nextServer = allServers[currentIndex + 1]
                    _uiState.value = _uiState.value.copy(
                        targetServer = nextServer,
                        isUploading = false // Triggers re-sign for next server
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
