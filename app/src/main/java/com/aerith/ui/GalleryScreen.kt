package com.aerith.ui

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.aerith.auth.AuthState
import com.aerith.core.blossom.BlossomBlob
import com.aerith.core.nostr.BlossomAuthHelper
import com.aerith.ui.gallery.GalleryViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    authState: AuthState,
    onMediaClick: (String) -> Unit,
    onSettingsClick: () -> Unit,
    galleryViewModel: GalleryViewModel = viewModel(),
    uploadViewModel: com.aerith.ui.upload.UploadViewModel = viewModel()
) {
    val state by galleryViewModel.uiState.collectAsState()
    val uploadState by uploadViewModel.uiState.collectAsState()
    val context = LocalContext.current
    val signer = remember { com.aerith.auth.Nip55Signer(context) }
    val scope = rememberCoroutineScope()

    var pendingUpload by remember { mutableStateOf<Pair<Uri, String>?>(null) }
    var pendingListAuth by remember { mutableStateOf<Iterator<Map.Entry<String, String>>?>(null) }
    var currentSigningServer by remember { mutableStateOf<String?>(null) }
    val authenticatedListHeaders = remember { mutableStateMapOf<String, String>() }
    var triggerNextSign by remember { mutableStateOf<android.content.Intent?>(null) }
    var showServerMenu by remember { mutableStateOf(false) }
    
    // Track if we've performed the initial refresh this session
    var hasAutoRefreshed by rememberSaveable { mutableStateOf(false) }
    var isSigningFlowActive by remember { mutableStateOf(false) }

    val signLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        if (result.resultCode == Activity.RESULT_OK) {
            val signedJson = signer.parseSignEventResult(result.data) ?: run {
                isSigningFlowActive = false
                return@rememberLauncherForActivityResult
            }

            // Handle Upload signature
            pendingUpload?.let { uploadData ->
                val server = authState.blossomServers.firstOrNull() ?: return@rememberLauncherForActivityResult
                val hash = uploadState.preparedUploadHash ?: return@rememberLauncherForActivityResult
                uploadViewModel.uploadFile(
                    serverUrl = server,
                    uri = uploadData.first,
                    mimeType = uploadData.second,
                    signedEventJson = signedJson,
                    expectedHash = hash
                )
                pendingUpload = null
                isSigningFlowActive = false
                return@rememberLauncherForActivityResult
            }

            // Handle List signature
            pendingListAuth?.let { iterator ->
                currentSigningServer?.let { server ->
                    authenticatedListHeaders[server] = BlossomAuthHelper.encodeAuthHeader(signedJson)
                }

                if (iterator.hasNext()) {
                    val (nextServer, nextEvent) = iterator.next()
                    currentSigningServer = nextServer
                    authState.pubkey?.let {
                        triggerNextSign = signer.getSignEventIntent(nextEvent, it)
                    }
                } else {
                    // Finished all servers
                    val finalHeaders = authenticatedListHeaders.toMap()
                    authState.pubkey?.let { pk ->
                        galleryViewModel.loadImages(pk, authState.blossomServers, finalHeaders, authState.fileMetadata)
                    }
                    pendingListAuth = null
                    currentSigningServer = null
                    isSigningFlowActive = false
                }
            }
        } else {
            // Cancelled or failed
            pendingListAuth = null
            currentSigningServer = null
            pendingUpload = null
            isSigningFlowActive = false
        }
    }

    LaunchedEffect(triggerNextSign) {
        triggerNextSign?.let {
            signLauncher.launch(it)
            triggerNextSign = null
        }
    }

    fun triggerAuthenticatedList() {
        val pubkey = authState.pubkey
        val servers = authState.blossomServers
        val pkg = authState.signerPackage
        if (pubkey == null || servers.isEmpty() || isSigningFlowActive) return

        isSigningFlowActive = true
        scope.launch {
            val unsignedEvents = galleryViewModel.prepareListEvents(pubkey, servers)
            val headers = mutableMapOf<String, String>()
            val remainingServers = mutableListOf<Map.Entry<String, String>>()

            unsignedEvents.forEach { (server, event) ->
                var signed: String? = null
                if (pkg != null) {
                    signed = signer.signEventBackground(pkg, event, pubkey)
                }
                
                if (signed != null) {
                    headers[server] = BlossomAuthHelper.encodeAuthHeader(signed)
                } else {
                    remainingServers.add(java.util.AbstractMap.SimpleEntry(server, event))
                }
            }

            if (remainingServers.isEmpty()) {
                // All signed in background!
                galleryViewModel.loadImages(pubkey, servers, headers, authState.fileMetadata)
                isSigningFlowActive = false
            } else {
                // Some or all need UI interaction
                authenticatedListHeaders.clear()
                authenticatedListHeaders.putAll(headers)
                val iterator = remainingServers.iterator()
                pendingListAuth = iterator
                if (iterator.hasNext()) {
                    val (server, event) = iterator.next()
                    currentSigningServer = server
                    val intent = signer.getSignEventIntent(event, pubkey)
                    signLauncher.launch(intent)
                }
            }
        }
    }

    val pickMedia = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
            pendingUpload = Pair(uri, mimeType)
            uploadViewModel.prepareUpload(uri, mimeType)
        }
    }

    LaunchedEffect(uploadState.preparedUploadHash) {
        val hash = uploadState.preparedUploadHash
        val size = uploadState.preparedUploadSize
        val pubkey = authState.pubkey
        val pkg = authState.signerPackage

        if (hash != null && size != null && pubkey != null && !isSigningFlowActive) {
            val server = authState.blossomServers.firstOrNull() ?: return@LaunchedEffect
            val unsignedEvent = BlossomAuthHelper.createUploadAuthEvent(
                pubkey = pubkey, sha256 = hash, size = size,
                mimeType = pendingUpload?.second,
                fileName = pendingUpload?.first?.lastPathSegment,
                serverUrl = server
            )
            
            var signed: String? = null
            if (pkg != null) {
                signed = signer.signEventBackground(pkg, unsignedEvent, pubkey)
            }
            
            if (signed != null) {
                // Sign in background succeeded!
                uploadViewModel.uploadFile(
                    serverUrl = server,
                    uri = pendingUpload!!.first,
                    mimeType = pendingUpload!!.second,
                    signedEventJson = signed,
                    expectedHash = hash
                )
                pendingUpload = null
            } else {
                // Fallback to Intent
                isSigningFlowActive = true
                val intent = signer.getSignEventIntent(unsignedEvent, pubkey)
                signLauncher.launch(intent)
            }
        }
    }
    
    LaunchedEffect(authState.pubkey, authState.blossomServers, authState.isLoading) {
        if (authState.pubkey != null && authState.blossomServers.isNotEmpty() && !authState.isLoading && !hasAutoRefreshed) {
            hasAutoRefreshed = true
            triggerAuthenticatedList()
        }
    }

    LaunchedEffect(uploadState.successMessage) {
        if (uploadState.successMessage != null) {
            triggerAuthenticatedList()
            android.widget.Toast.makeText(context, uploadState.successMessage, android.widget.Toast.LENGTH_SHORT).show()
            uploadViewModel.clearState()
        }
    }

    // New: Re-sync labels when fresh metadata is discovered on relays
    LaunchedEffect(authState.fileMetadata) {
        if (authState.pubkey != null && authState.blossomServers.isNotEmpty() && authState.fileMetadata.isNotEmpty()) {
            galleryViewModel.loadImages(authState.pubkey, authState.blossomServers, emptyMap(), authState.fileMetadata)
        }
    }

    LaunchedEffect(uploadState.error) {
        if (uploadState.error != null) {
            android.widget.Toast.makeText(context, uploadState.error, android.widget.Toast.LENGTH_LONG).show()
            uploadViewModel.clearState()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // User Avatar
                        if (authState.profileUrl != null) {
                            AsyncImage(
                                model = authState.profileUrl,
                                contentDescription = "Profile",
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.AccountCircle,
                                contentDescription = "Profile",
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        
                        Spacer(modifier = Modifier.width(12.dp))

                        Box {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable { showServerMenu = true }
                            ) {
                                Text(
                                    text = when (state.selectedServer) {
                                        null -> "All Media"
                                        "TRASH" -> "Trash"
                                        else -> state.selectedServer?.removePrefix("https://")?.removeSuffix("/") ?: "All Media"
                                    },
                                    style = MaterialTheme.typography.titleLarge
                                )
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = null
                                )
                            }
                            
                            DropdownMenu(
                                expanded = showServerMenu,
                                onDismissRequest = { showServerMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("All Media") },
                                    onClick = {
                                        galleryViewModel.selectServer(null)
                                        showServerMenu = false
                                    },
                                    leadingIcon = {
                                        if (state.selectedServer == null) Icon(Icons.Default.Check, null)
                                    }
                                )
                                state.servers.forEach { server ->
                                    DropdownMenuItem(
                                        text = { Text(server.removePrefix("https://").removeSuffix("/")) },
                                        onClick = {
                                            galleryViewModel.selectServer(server)
                                            showServerMenu = false
                                        },
                                        leadingIcon = {
                                            if (state.selectedServer == server) Icon(Icons.Default.Check, null)
                                        }
                                    )
                                }
                                Divider()
                                DropdownMenuItem(
                                    text = { Text("Trash") },
                                    onClick = {
                                        galleryViewModel.selectServer("TRASH")
                                        showServerMenu = false
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Delete, null)
                                    },
                                    trailingIcon = {
                                        if (state.selectedServer == "TRASH") Icon(Icons.Default.Check, null)
                                    }
                                )
                            }
                        }
                    }
                },
                actions = {
                    if (state.selectedServer == "TRASH") {
                        IconButton(onClick = { galleryViewModel.emptyTrash() }) {
                            Icon(imageVector = Icons.Default.DeleteForever, contentDescription = "Empty Trash")
                        }
                    } else {
                        IconButton(onClick = { triggerAuthenticatedList() }) {
                            Icon(imageVector = Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(imageVector = Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { 
                pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) 
            }) {
                Icon(imageVector = Icons.Default.Add, contentDescription = "Upload")
            }
        }
    ) { padding ->
        val allTags = remember(state.allBlobs, state.trashBlobs, state.fileMetadata) {
            (state.allBlobs + state.trashBlobs).flatMap { it.getTags(state.fileMetadata) }.distinct().sorted()
        }

        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Background update indicator
            if (state.isLoading && state.allBlobs.isNotEmpty()) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }

            if (allTags.isNotEmpty()) {
                ScrollableTabRow(
                    selectedTabIndex = -1,
                    edgePadding = 16.dp,
                    containerColor = Color.Transparent,
                    divider = {},
                    indicator = {}
                ) {
                    allTags.forEach { tag ->
                        val isSelected = state.selectedTags.contains(tag)
                        FilterChip(
                            selected = isSelected,
                            onClick = { galleryViewModel.toggleTag(tag) },
                            label = { Text(tag) },
                            modifier = Modifier.padding(horizontal = 4.dp),
                            leadingIcon = if (isSelected) {
                                { Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp)) }
                            } else null
                        )
                    }
                    if (state.selectedTags.isNotEmpty()) {
                        TextButton(onClick = { galleryViewModel.clearTags() }) {
                            Text("Clear")
                        }
                    }
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                // Initial load or background refresh when empty
                val isInitialLoad = !hasAutoRefreshed || (state.isLoading && state.allBlobs.isEmpty())
                
                if (isInitialLoad && !uploadState.isUploading) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Blossom Servers Loading...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else if (state.error != null) {
                    Text(
                        text = "Gallery Error: ${state.error}",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center).padding(16.dp)
                    )
                } else if (state.filteredBlobs.isEmpty()) {
                    Text(
                        text = if (state.selectedServer == "TRASH") "Trash is empty." else "No media found.",
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 120.dp),
                        contentPadding = PaddingValues(4.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(
                            items = state.filteredBlobs,
                            key = { it.sha256 } // Stable keys for high-performance scrolling
                        ) { blob ->
                            MediaItem(
                                blob = blob,
                                onClick = { onMediaClick(blob.url) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MediaItem(blob: BlossomBlob, onClick: () -> Unit) {
    val isVideo = remember(blob.type, blob.mime) { 
        blob.getMimeType()?.startsWith("video/") == true 
    }
    val context = LocalContext.current
    
    // Pre-calculate image request to avoid building it every recomposition
    val imageRequest = remember(blob.sha256, blob.url) {
        coil.request.ImageRequest.Builder(context)
            .data(blob.getThumbnailUrl())
            .diskCacheKey(blob.sha256)
            .memoryCacheKey(blob.sha256)
            .size(400, 400)
            .precision(coil.size.Precision.INEXACT)
            .bitmapConfig(android.graphics.Bitmap.Config.RGB_565)
            .crossfade(true)
            .build()
    }
    
    Box(
        modifier = Modifier
            .padding(4.dp)
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onClick() }
    ) {
        AsyncImage(
            model = imageRequest,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        
        if (isVideo) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(
                        color = Color.Black.copy(alpha = 0.5f),
                        shape = CircleShape
                    )
                    .padding(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Video",
                    modifier = Modifier.size(24.dp),
                    tint = Color.White
                )
            }
        }
    }
}
