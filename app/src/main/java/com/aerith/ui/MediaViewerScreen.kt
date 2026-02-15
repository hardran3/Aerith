package com.aerith.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil.ImageLoader
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.aerith.AerithApp
import com.aerith.auth.AuthState
import com.aerith.auth.Nip55Signer
import com.aerith.core.nostr.BlossomAuthHelper
import com.aerith.ui.gallery.GalleryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MediaViewerScreen(
    url: String,
    authState: AuthState,
    authViewModel: com.aerith.auth.AuthViewModel,
    onBack: () -> Unit,
    galleryViewModel: GalleryViewModel = viewModel()
) {
    val uiState by galleryViewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val signer = remember { Nip55Signer(context) }
    
    val currentBlob = remember(uiState.allBlobs, uiState.trashBlobs, url) { 
        uiState.allBlobs.find { it.url == url } ?: uiState.trashBlobs.find { it.url == url }
    }
    
    var showInfoSheet by remember { mutableStateOf(false) }
    var showServerSheet by remember { mutableStateOf(false) }
    val isVaulted = remember(currentBlob, uiState.vaultedHashes) {
        currentBlob != null && uiState.vaultedHashes.contains(currentBlob.sha256)
    }
    val isVideo = remember(currentBlob) { currentBlob?.getMimeType()?.startsWith("video/") == true }
    val isLocallyCached = remember(currentBlob, uiState.locallyCachedHashes) {
        currentBlob != null && uiState.locallyCachedHashes.contains(currentBlob.sha256)
    }
    val vaultManager = remember { com.aerith.core.data.BlobVaultManager(context) }

    val exoPlayer = remember {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Aerith/1.0")
            .setAllowCrossProtocolRedirects(true)

        // Use DefaultDataSource to support both local file:// and remote http:// URIs
        val dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(context, httpDataSourceFactory)

        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(AerithApp.videoCache)
            .setUpstreamDataSourceFactory(dataSourceFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheDataSourceFactory))
            .build().apply {
                repeatMode = Player.REPEAT_MODE_ONE
                playWhenReady = true
            }
    }

    LaunchedEffect(url, isVaulted, isLocallyCached) {
        if (isVideo) {
            val mediaUri = when {
                isVaulted && currentBlob != null -> Uri.fromFile(vaultManager.getVaultFile(currentBlob.sha256))
                isLocallyCached && currentBlob != null && authState.localBlossomUrl != null -> Uri.parse("${authState.localBlossomUrl}/${currentBlob.sha256}")
                else -> Uri.parse(url)
            }
            val mediaItem = MediaItem.fromUri(mediaUri)
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }
    
    val relatedBlobs = remember(uiState.allBlobs, currentBlob) {
        if (currentBlob != null) {
            uiState.allBlobs.filter { it.sha256 == currentBlob.sha256 }
        } else {
            emptyList()
        }
    }

    // Background re-verification: Check servers that didn't list the file as soon as we open fullscreen
    LaunchedEffect(currentBlob) {
        if (currentBlob != null) {
            authState.blossomServers.forEach { server ->
                val isOnServer = relatedBlobs.any { it.serverUrl == server }
                if (!isOnServer) {
                    galleryViewModel.verifyBlobExistence(server, currentBlob)
                }
            }
        }
    }

    var showJsonDialog by remember { mutableStateOf(false) }
    var tagToRemove by remember { mutableStateOf<String?>(null) }
    var isHudVisible by remember { mutableStateOf(true) }
    val infoSheetState = rememberModalBottomSheetState()
    val serverSheetState = rememberModalBottomSheetState()
    
    val currentTags = remember(currentBlob?.nip94, uiState.fileMetadata) { 
        currentBlob?.getTags(uiState.fileMetadata) ?: emptyList()
    }

    var blobToDelete by remember { mutableStateOf<com.aerith.core.blossom.BlossomBlob?>(null) }
    var pendingMirrorServer by remember { mutableStateOf<String?>(null) }
    var pendingLabelUpdateTags by remember { mutableStateOf<List<String>?>(null) }
    var pendingNameUpdate by remember { mutableStateOf<String?>(null) }
    
    val signLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val signedJson = signer.parseSignEventResult(result.data)
            if (signedJson != null) {
                if (blobToDelete != null) {
                    galleryViewModel.deleteBlob(blobToDelete!!, signedJson)
                    blobToDelete = null
                } else if (pendingMirrorServer != null && currentBlob != null) {
                    galleryViewModel.mirrorBlob(pendingMirrorServer!!, url, signedJson, currentBlob)
                    pendingMirrorServer = null
                } else if (pendingLabelUpdateTags != null && currentBlob != null) {
                    val pk = authState.pubkey ?: return@rememberLauncherForActivityResult
                    galleryViewModel.updateLabels(pk, authState.relays, currentBlob, pendingLabelUpdateTags!!, signedJson, signer, authState.signerPackage)
                    
                    val existingNip94 = (currentBlob.nip94 as? List<*>)?.filterIsInstance<List<String>>() ?: emptyList()
                    val otherTags = existingNip94.filter { it.firstOrNull() != "t" }
                    val newNip94 = otherTags + pendingLabelUpdateTags!!.map { listOf("t", it) }
                    authViewModel.updateMetadata(currentBlob.sha256, newNip94)
                    
                    pendingLabelUpdateTags = null
                } else if (pendingNameUpdate != null && currentBlob != null) {
                    val pk = authState.pubkey ?: return@rememberLauncherForActivityResult
                    val currentTagsForName = currentBlob.getTags(uiState.fileMetadata)
                    galleryViewModel.updateLabels(pk, authState.relays, currentBlob, currentTagsForName, signedJson, signer, authState.signerPackage, newName = pendingNameUpdate)
                    
                    val existingNip94 = (currentBlob.nip94 as? List<*>)?.filterIsInstance<List<String>>() ?: emptyList()
                    val otherTags = existingNip94.filter { it.firstOrNull() != "name" }
                    val newNip94 = otherTags + listOf(listOf("name", pendingNameUpdate!!))
                    authViewModel.updateMetadata(currentBlob.sha256, newNip94)
                    
                    pendingNameUpdate = null
                }
            }
        }
    }

    // Background re-verification: Check servers that didn't list the file as soon as we open fullscreen
    LaunchedEffect(currentBlob) {
        if (currentBlob != null) {
            authState.blossomServers.forEach { server ->
                val isOnServer = relatedBlobs.any { it.serverUrl == server }
                if (!isOnServer) {
                    galleryViewModel.verifyBlobExistence(server, currentBlob)
                }
            }
        }
    }

    fun shareMedia(context: Context, url: String, mimeType: String, hash: String) {
        scope.launch(Dispatchers.IO) {
            try {
                // 1. Try to get from Vault first
                var file = vaultManager.getVaultFile(hash)

                // 2. Try to get from Coil Disk Cache (for images)
                if (file == null || !file.exists()) {
                    if (!mimeType.startsWith("video/")) {
                        val imageLoader = coil.Coil.imageLoader(context)
                        val snapshot = imageLoader.diskCache?.get(hash)
                        file = snapshot?.data?.toFile()
                    }
                }

                // 3. If still not found or is video, download to a temp file for sharing
                if (file == null || !file.exists()) {
                    val tempFile = File(context.cacheDir, "temp_share_${System.currentTimeMillis()}.${mimeType.substringAfter("/")}")
                    try {
                        val effectiveUrl = if (isLocallyCached && currentBlob != null && authState.localBlossomUrl != null) {
                            "${authState.localBlossomUrl}/$hash"
                        } else {
                            url
                        }
                        val request = okhttp3.Request.Builder().url(effectiveUrl).build()
                        val client = okhttp3.OkHttpClient()
                        client.newCall(request).execute().use { response ->
                            if (response.isSuccessful) {
                                response.body?.byteStream()?.use { input ->
                                    tempFile.outputStream().use { output ->
                                        input.copyTo(output)
                                    }
                                }
                                file = tempFile
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("MediaViewer", "Download for share failed", e)
                    }
                }

                if (file != null && file!!.exists()) {
                    val sharedFile = File(context.cacheDir, "shared_media.${mimeType.substringAfter("/")}")
                    file!!.copyTo(sharedFile, overwrite = true)
                    
                    val contentUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", sharedFile)
                    
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = mimeType
                        putExtra(Intent.EXTRA_STREAM, contentUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "Share Media"))
                } else {
                    // Final fallback to URL
                    withContext(Dispatchers.Main) {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, url)
                        }
                        context.startActivity(Intent.createChooser(intent, "Share Media Link"))
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("MediaViewer", "Failed to share media", e)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = { isHudVisible = !isHudVisible }
            )
    ) {
        if (isVideo) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = isHudVisible
                        setControllerAutoShow(false) // We manage it
                    }
                },
                update = { view ->
                    view.useController = isHudVisible
                    if (isHudVisible) view.showController() else view.hideController()
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            val model = when {
                isVaulted && currentBlob != null -> vaultManager.getVaultFile(currentBlob.sha256)
                isLocallyCached && currentBlob != null && authState.localBlossomUrl != null -> "${authState.localBlossomUrl}/${currentBlob.sha256}"
                else -> url
            }

            SubcomposeAsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(model)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
                loading = {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            )
        }

        // --- Top Left: Tags ---
        androidx.compose.animation.AnimatedVisibility(
            visible = isHudVisible && currentTags.isNotEmpty(),
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically(),
            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically(),
            modifier = Modifier.align(Alignment.TopStart)
        ) {
            FlowRow(
                modifier = Modifier
                    .padding(16.dp)
                    .padding(WindowInsets.statusBars.asPaddingValues())
                    .fillMaxWidth(0.7f),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                currentTags.forEach { tag ->
                    SuggestionChip(
                        onClick = { tagToRemove = tag },
                        label = { Text(tag, color = Color.White) },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = Color.Black.copy(alpha = 0.4f)
                        ),
                        border = SuggestionChipDefaults.suggestionChipBorder(
                            enabled = true,
                            borderColor = Color.White.copy(alpha = 0.3f)
                        )
                    )
                }
            }
        }

        // --- Tag Removal Confirmation ---
        if (tagToRemove != null) {
            AlertDialog(
                onDismissRequest = { tagToRemove = null },
                title = { Text("Remove Label") },
                text = { Text("Are you sure you want to remove the label \"${tagToRemove}\"?") },
                confirmButton = {
                    TextButton(onClick = {
                        val tag = tagToRemove!!
                        val pk = authState.pubkey
                        val pkg = authState.signerPackage
                        if (pk != null && currentBlob != null) {
                            val updatedTags = currentTags.filter { it != tag }
                            val unsigned = BlossomAuthHelper.createFileMetadataEvent(
                                pk, currentBlob.sha256, url, currentBlob.getMimeType(), updatedTags
                            )
                            val signed = if (pkg != null) signer.signEventBackground(pkg, unsigned, pk) else null
                            if (signed != null) {
                                galleryViewModel.updateLabels(pk, authState.relays, currentBlob, updatedTags, signed, signer, pkg)
                                
                                val existingNip94 = (currentBlob.nip94 as? List<*>)?.filterIsInstance<List<String>>() ?: emptyList()
                                val otherTags = existingNip94.filter { it.firstOrNull() != "t" }
                                val newNip94 = otherTags + updatedTags.map { listOf("t", it) }
                                authViewModel.updateMetadata(currentBlob.sha256, newNip94)
                            } else {
                                pendingLabelUpdateTags = updatedTags
                                signLauncher.launch(signer.getSignEventIntent(unsigned, pk))
                            }
                        }
                        tagToRemove = null
                    }) {
                        Text("Remove", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { tagToRemove = null }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // --- Fast Local indicator ---
        androidx.compose.animation.AnimatedVisibility(
            visible = isHudVisible && isVaulted && !isVideo,
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically(),
            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically(),
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            Box(
                modifier = Modifier
                    .padding(16.dp)
                    .padding(WindowInsets.statusBars.asPaddingValues())
                    .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Verified, 
                        null, 
                        tint = Color(0xFFC8E6C9), 
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "VAULT", 
                        style = MaterialTheme.typography.labelSmall, 
                        color = Color.White,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                }
            }
        }
        
        // Bottom Bar with Actions
        androidx.compose.animation.AnimatedVisibility(
            visible = isHudVisible,
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically(initialOffsetY = { it }),
            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column {
                // Spacer to avoid overlapping with video controls (seek bar)
                if (isVideo) {
                    Spacer(modifier = Modifier.height(48.dp))
                }
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(16.dp)
                        .padding(WindowInsets.navigationBars.asPaddingValues()),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Web Share (Copy Link) - Far Left
                    IconButton(
                        onClick = {
                            val firstUrl = relatedBlobs.find { it.serverUrl != null }?.url ?: url
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val clip = android.content.ClipData.newPlainText("Media Link", firstUrl)
                            clipboard.setPrimaryClip(clip)
                            android.widget.Toast.makeText(context, "Link copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Icon(Icons.Default.Language, "Copy Link", tint = Color.White)
                    }

                    // Share Button
                    IconButton(
                        onClick = {
                            if (currentBlob != null) {
                                shareMedia(context, url, currentBlob.getMimeType() ?: "image/*", currentBlob.sha256)
                            }
                        }
                    ) {
                        Icon(Icons.Default.Share, "Share", tint = Color.White)
                    }

                    // Server Settings Button
                    IconButton(
                        onClick = { showServerSheet = true }
                    ) {
                        val serverCount = remember(relatedBlobs, isLocallyCached) {
                            val remoteCount = relatedBlobs.mapNotNull { it.serverUrl }.distinct().size
                            if (isLocallyCached) remoteCount + 1 else remoteCount
                        }
                        
                        BadgedBox(
                            badge = {
                                if (serverCount > 0) {
                                    Badge(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                    ) {
                                        Text(serverCount.toString())
                                    }
                                }
                            }
                        ) {
                            Icon(Icons.Default.Storage, "Servers", tint = Color.White)
                        }
                    }

                    // Info Button
                    IconButton(
                        onClick = { showInfoSheet = true }
                    ) {
                        Icon(Icons.Default.Info, "Details", tint = Color.White)
                    }
                }
            }
        }
        
        // --- Info Sheet ---
        if (showInfoSheet && currentBlob != null) {
            ModalBottomSheet(
                onDismissRequest = { showInfoSheet = false },
                sheetState = infoSheetState
            ) {
                var newTag by remember { mutableStateOf("") }
                val pk = authState.pubkey
                val pkg = authState.signerPackage
                val currentTags = remember(currentBlob.nip94, uiState.fileMetadata) { 
                    currentBlob.getTags(uiState.fileMetadata)
                }

                Column(modifier = Modifier.padding(16.dp)) {
                    Text("File Details", style = MaterialTheme.typography.headlineSmall)
                    Spacer(modifier = Modifier.height(16.dp))

                    // --- Filename Section ---
                    var fileNameInput by remember { mutableStateOf(currentBlob.getName(uiState.fileMetadata) ?: "") }
                    var isEditingName by remember { mutableStateOf(false) }

                    Text("Filename", style = MaterialTheme.typography.titleMedium)
                    if (isEditingName) {
                        OutlinedTextField(
                            value = fileNameInput,
                            onValueChange = { fileNameInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                IconButton(onClick = {
                                    if (pk != null) {
                                        val currentTags = currentBlob.getTags(uiState.fileMetadata)
                                        val unsigned = BlossomAuthHelper.createFileMetadataEvent(
                                            pk, currentBlob.sha256, url, currentBlob.getMimeType(), currentTags,
                                            name = fileNameInput.trim()
                                        )
                                        val signed = if (pkg != null) signer.signEventBackground(pkg, unsigned, pk) else null
                                        if (signed != null) {
                                            galleryViewModel.updateLabels(pk, authState.relays, currentBlob, currentTags, signed, signer, pkg, newName = fileNameInput.trim())
                                            
                                            val existingNip94 = (currentBlob.nip94 as? List<*>)?.filterIsInstance<List<String>>() ?: emptyList()
                                            val otherTags = existingNip94.filter { it.firstOrNull() != "name" }
                                            val newNip94 = otherTags + listOf(listOf("name", fileNameInput.trim()))
                                            authViewModel.updateMetadata(currentBlob.sha256, newNip94)
                                        } else {
                                            pendingNameUpdate = fileNameInput.trim()
                                            signLauncher.launch(signer.getSignEventIntent(unsigned, pk))
                                        }
                                    }
                                    isEditingName = false
                                }) {
                                    Icon(Icons.Default.Save, "Save Name")
                                }
                            }
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { isEditingName = true }.padding(vertical = 4.dp)
                        ) {
                            Text(
                                text = fileNameInput.ifEmpty { "Add filename..." },
                                color = if (fileNameInput.isEmpty()) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(Icons.Default.Edit, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.secondary)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // --- Labels Section ---
                    Text("Labels", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // Tag Chips
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        currentTags.forEach { tag ->
                            InputChip(
                                selected = true,
                                onClick = {
                                    if (pk != null) {
                                        val updatedTags = currentTags.filter { it != tag }
                                        val unsigned = BlossomAuthHelper.createFileMetadataEvent(
                                            pk, currentBlob.sha256, url, currentBlob.getMimeType(), updatedTags
                                        )
                                        val signed = if (pkg != null) signer.signEventBackground(pkg, unsigned, pk) else null
                                        if (signed != null) {
                                            galleryViewModel.updateLabels(pk, authState.relays, currentBlob, updatedTags, signed, signer, pkg)
                                            
                                            // Sync to AuthViewModel with full NIP-94 tags
                                            val existingNip94 = (currentBlob.nip94 as? List<*>)?.filterIsInstance<List<String>>() ?: emptyList()
                                            val otherTags = existingNip94.filter { it.firstOrNull() != "t" }
                                            val newNip94 = otherTags + updatedTags.map { listOf("t", it) }
                                            authViewModel.updateMetadata(currentBlob.sha256, newNip94)
                                        } else {
                                            pendingLabelUpdateTags = updatedTags
                                            signLauncher.launch(signer.getSignEventIntent(unsigned, pk))
                                        }
                                    }
                                },
                                label = { Text(tag) },
                                trailingIcon = { Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp)) }
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // Add Tag Input
                    OutlinedTextField(
                        value = newTag,
                        onValueChange = { newTag = it },
                        label = { Text("Add label...") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    if (newTag.isNotBlank() && pk != null) {
                                        val updatedTags = (currentTags + newTag.trim()).distinct()
                                        val unsigned = BlossomAuthHelper.createFileMetadataEvent(
                                            pk, currentBlob.sha256, url, currentBlob.getMimeType(), updatedTags
                                        )
                                        val signed = if (pkg != null) signer.signEventBackground(pkg, unsigned, pk) else null
                                        
                                        if (signed != null) {
                                            galleryViewModel.updateLabels(pk, authState.relays, currentBlob, updatedTags, signed, signer, pkg)
                                            
                                            val existingNip94 = (currentBlob.nip94 as? List<*>)?.filterIsInstance<List<String>>() ?: emptyList()
                                            val otherTags = existingNip94.filter { it.firstOrNull() != "t" }
                                            val newNip94 = otherTags + updatedTags.map { listOf("t", it) }
                                            authViewModel.updateMetadata(currentBlob.sha256, newNip94)
                                            newTag = ""
                                        } else {
                                            pendingLabelUpdateTags = updatedTags
                                            signLauncher.launch(signer.getSignEventIntent(unsigned, pk))
                                            newTag = ""
                                        }
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Add, null)
                            }
                        }
                    )

                    // Suggestions
                    val allUniqueTags = galleryViewModel.getAllUniqueTags()
                    val suggestedTags = allUniqueTags.filter { it !in currentTags }
                    
                    if (suggestedTags.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Suggested:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            suggestedTags.take(10).forEach { tag ->
                                AssistChip(
                                    onClick = {
                                        if (pk != null) {
                                            val updatedTags = (currentTags + tag).distinct()
                                            val unsigned = BlossomAuthHelper.createFileMetadataEvent(
                                                pk, currentBlob.sha256, url, currentBlob.getMimeType(), updatedTags
                                            )
                                            val signed = if (pkg != null) signer.signEventBackground(pkg, unsigned, pk) else null
                                            if (signed != null) {
                                                galleryViewModel.updateLabels(pk, authState.relays, currentBlob, updatedTags, signed, signer, pkg)
                                                
                                                val existingNip94 = (currentBlob.nip94 as? List<*>)?.filterIsInstance<List<String>>() ?: emptyList()
                                                val otherTags = existingNip94.filter { it.firstOrNull() != "t" }
                                                val newNip94 = otherTags + updatedTags.map { listOf("t", it) }
                                                authViewModel.updateMetadata(currentBlob.sha256, newNip94)
                                            } else {
                                                pendingLabelUpdateTags = updatedTags
                                                signLauncher.launch(signer.getSignEventIntent(unsigned, pk))
                                            }
                                        }
                                    },
                                    label = { Text(tag) }
                                )
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                    
                    Text("Metadata", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    val isDiscovered = uiState.allBlobs.none { it.sha256 == currentBlob.sha256 }
                    if (isDiscovered) {
                        Text(
                            text = "Source: Discovered via Nostr Relay (Kind 1063)",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelMedium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    Text("Type: ${currentBlob.getMimeType()}")
                    Text("Size: ${currentBlob.getSizeAsLong()} bytes")
                    Text("SHA256: ${currentBlob.sha256}")
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { showJsonDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Code, null)
                        Spacer(Modifier.width(8.dp))
                        Text("View Kind 1063 JSON")
                    }
                    
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }

        if (showJsonDialog && currentBlob != null) {
            val json = remember(currentBlob, uiState.fileMetadata) {
                val currentTags = currentBlob.getTags(uiState.fileMetadata)
                val unsigned = BlossomAuthHelper.createFileMetadataEvent(
                    authState.pubkey ?: "", 
                    currentBlob.sha256, 
                    url, 
                    currentBlob.getMimeType(), 
                    currentTags,
                    name = currentBlob.getName(uiState.fileMetadata)
                )
                
                // Pretty print using Moshi instead of JSONObject to avoid escaping slashes
                try {
                    val moshi = com.squareup.moshi.Moshi.Builder()
                        .add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
                        .build()
                    val adapter = moshi.adapter(Any::class.java).indent("    ")
                    val obj = adapter.fromJson(unsigned)
                    adapter.toJson(obj)
                } catch (e: Exception) {
                    unsigned // Fallback to raw if pretty-print fails
                }
            }

            AlertDialog(
                onDismissRequest = { showJsonDialog = false },
                title = { Text("Kind 1063 Event") },
                text = {
                    Column {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 400.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
                                .padding(8.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = json,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clip = android.content.ClipData.newPlainText("Nostr Event JSON", json)
                        clipboard.setPrimaryClip(clip)
                        android.widget.Toast.makeText(context, "JSON copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
                    }) {
                        Text("Copy JSON")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showJsonDialog = false }) {
                        Text("Close")
                    }
                }
            )
        }

        // --- Server Management Sheet ---
        if (showServerSheet && currentBlob != null) {
            ModalBottomSheet(
                onDismissRequest = { showServerSheet = false },
                sheetState = serverSheetState
            ) {
                val pk = authState.pubkey
                val pkg = authState.signerPackage
                
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Server Distribution", style = MaterialTheme.typography.headlineSmall)
                        
                        if (pk != null) {
                            TextButton(onClick = {
                                galleryViewModel.mirrorToAll(pk, currentBlob, authState.blossomServers, signer, pkg)
                            }) {
                                Text("Mirror to All")
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    LazyColumn {
                        // 1. Show Local Cache if detected
                        authState.localBlossomUrl?.let { localUrl ->
                            item {
                                val isMirroring = uiState.serverMirroringStates[localUrl] ?: false

                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Local Cache", style = MaterialTheme.typography.bodyLarge)
                                        Text(
                                            text = if (isLocallyCached) "Stored locally" else "Available for sync",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (isLocallyCached) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                                        )
                                    }
                                    
                                    if (isMirroring) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp).padding(4.dp), strokeWidth = 2.dp)
                                    } else if (!isLocallyCached) {
                                        IconButton(onClick = {
                                            if (currentBlob != null) {
                                                galleryViewModel.mirrorToLocalCache(currentBlob.sha256, url, currentBlob, localUrl)
                                            }
                                        }) {
                                            Icon(Icons.Default.DownloadForOffline, "Save to Local Cache", tint = MaterialTheme.colorScheme.primary)
                                        }
                                    } else {
                                        Icon(Icons.Default.CheckCircle, "Cached", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(8.dp))
                                    }
                                }
                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            }
                        }

                        items(authState.blossomServers) { server ->
                            val isOnServer = relatedBlobs.any { it.serverUrl == server }
                            
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    val cleanServerName = server.removePrefix("https://").removeSuffix("/")
                                    Text(
                                        text = cleanServerName,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Text(
                                        text = if (isOnServer) "Hosted" else "Not present",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isOnServer) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                                    )
                                }
                                
                                if (pk != null) {
                                    val isMirroring = uiState.serverMirroringStates[server] ?: false
                                    
                                    Row {
                                        if (isOnServer) {
                                            val blobOnServer = relatedBlobs.find { it.serverUrl == server }!!
                                            IconButton(
                                                onClick = {
                                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                                    val clip = android.content.ClipData.newPlainText("Media Link", blobOnServer.url)
                                                    clipboard.setPrimaryClip(clip)
                                                    android.widget.Toast.makeText(context, "Link copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
                                                }
                                            ) {
                                                Icon(Icons.Default.ContentCopy, "Copy Link", tint = MaterialTheme.colorScheme.secondary)
                                            }
                                        }

                                        if (isMirroring) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(24.dp).padding(4.dp),
                                                strokeWidth = 2.dp
                                            )
                                        } else if (isOnServer) {
                                            val blobOnServer = relatedBlobs.find { it.serverUrl == server }!!
                                            IconButton(
                                                onClick = {
                                                    val eventJson = galleryViewModel.prepareDeleteEvent(pk, blobOnServer)
                                                    if (eventJson != null) {
                                                        val signed = if (pkg != null) signer.signEventBackground(pkg, eventJson, pk) else null
                                                        if (signed != null) {
                                                            galleryViewModel.deleteBlob(blobOnServer, signed)
                                                        } else {
                                                            blobToDelete = blobOnServer
                                                            signLauncher.launch(signer.getSignEventIntent(eventJson, pk))
                                                        }
                                                    }
                                                }
                                            ) {
                                                Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                                            }
                                        } else {
                                            IconButton(
                                                onClick = {
                                                    val unsigned = BlossomAuthHelper.createUploadAuthEvent(
                                                        pubkey = pk,
                                                        sha256 = currentBlob.sha256,
                                                        size = currentBlob.getSizeAsLong(),
                                                        mimeType = currentBlob.getMimeType(),
                                                        fileName = null,
                                                        serverUrl = server
                                                    )
                                                    val signed = if (pkg != null) signer.signEventBackground(pkg, unsigned, pk) else null
                                                    if (signed != null) {
                                                        galleryViewModel.mirrorBlob(server, url, signed, currentBlob)
                                                    } else {
                                                        pendingMirrorServer = server
                                                        signLauncher.launch(signer.getSignEventIntent(unsigned, pk))
                                                    }
                                                }
                                            ) {
                                                Icon(Icons.Default.CloudUpload, "Mirror to this server", tint = MaterialTheme.colorScheme.primary)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }
}
