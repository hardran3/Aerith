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

data class UploadState(
    val isUploading: Boolean = false,
    val successMessage: String? = null,
    val error: String? = null,
    
    // For the new 2-step upload flow
    val preparedUploadHash: String? = null,
    val preparedUploadSize: Long? = null
)

class UploadViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = BlossomRepository(application)
    private val _uiState = MutableStateFlow(UploadState())
    val uiState: StateFlow<UploadState> = _uiState.asStateFlow()

    /**
     * Step 1: Process a file to get its hash and size.
     * The result is stored in the UI state for the next step.
     */
    fun prepareUpload(uri: Uri, mimeType: String) {
        viewModelScope.launch {
            _uiState.value = UploadState(isUploading = true) // Use isUploading for processing state
            val result = repository.prepareUpload(uri, mimeType)
            if (result.isSuccess) {
                val (hash, size) = result.getOrThrow()
                _uiState.value = _uiState.value.copy(
                    isUploading = false,
                    preparedUploadHash = hash,
                    preparedUploadSize = size
                )
            } else {
                _uiState.value = UploadState(
                    error = "File processing failed: ${result.exceptionOrNull()?.message}"
                )
            }
        }
    }

    /**
     * Step 2: Once the UI has the hash/size and gets a signature from the user,
     * this function performs the actual upload.
     */
    fun uploadFile(
        serverUrl: String,
        uri: Uri,
        mimeType: String,
        signedEventJson: String,
        expectedHash: String
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isUploading = true)
            val authHeader = BlossomAuthHelper.encodeAuthHeader(signedEventJson)
            val result = repository.uploadBytes(serverUrl, uri, mimeType, authHeader, expectedHash)
            
            if (result.isSuccess) {
                val uploadResult = result.getOrThrow()
                _uiState.value = UploadState(successMessage = "Upload successful! URL: ${uploadResult.url}")
            } else {
                _uiState.value = UploadState(error = "Upload failed: ${result.exceptionOrNull()?.message}")
            }
        }
    }
    
    fun clearState() {
        _uiState.value = UploadState()
    }
}
