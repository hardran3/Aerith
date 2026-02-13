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
import coil.compose.SubcomposeAsyncImage
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
                        galleryViewModel.loadImages(pk, authState.blossomServers, finalHeaders)
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
        if (pubkey == null || servers.isEmpty() || isSigningFlowActive) return

        isSigningFlowActive = true
        scope.launch {
            val unsignedEvents = galleryViewModel.prepareListEvents(pubkey, servers)
            val iterator = unsignedEvents.entries.iterator()
            pendingListAuth = iterator
            authenticatedListHeaders.clear()

            if (iterator.hasNext()) {
                val (server, event) = iterator.next()
                currentSigningServer = server
                val intent = signer.getSignEventIntent(event, pubkey)
                signLauncher.launch(intent)
            } else {
                isSigningFlowActive = false
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

        if (hash != null && size != null && pubkey != null && !isSigningFlowActive) {
            isSigningFlowActive = true
            val server = authState.blossomServers.firstOrNull()
            val unsignedEvent = BlossomAuthHelper.createUploadAuthEvent(
                pubkey = pubkey, sha256 = hash, size = size,
                mimeType = pendingUpload?.second,
                fileName = pendingUpload?.first?.lastPathSegment,
                serverUrl = server
            )
            val intent = signer.getSignEventIntent(unsignedEvent, pubkey)
            signLauncher.launch(intent)
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
                                    text = if (state.selectedServer == null) "All Media" 
                                           else state.selectedServer?.removePrefix("https://")?.removeSuffix("/") ?: "All Media",
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
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { triggerAuthenticatedList() }) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = "Refresh")
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
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.isLoading || uploadState.isUploading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (state.error != null) {
                Text(
                    text = "Gallery Error: ${state.error}",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp)
                )
            } else if (state.allBlobs.isEmpty()) {
                Text(
                    text = "No media found.",
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 120.dp),
                    contentPadding = PaddingValues(4.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(state.filteredBlobs) { blob ->
                        MediaItem(blob = blob, onClick = { onMediaClick(blob.url) })
                    }
                }
            }
        }
    }
}

@Composable
fun MediaItem(blob: BlossomBlob, onClick: () -> Unit) {
    val isVideo = blob.getMimeType()?.startsWith("video/") == true
    
    Box(
        modifier = Modifier
            .padding(4.dp)
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onClick() }
    ) {
        AsyncImage(
            model = coil.request.ImageRequest.Builder(LocalContext.current)
                .data(blob.getThumbnailUrl())
                .diskCacheKey(blob.sha256)
                .memoryCacheKey(blob.sha256)
                .size(400, 400) // Don't decode full-size images for the grid
                .precision(coil.size.Precision.INEXACT)
                .bitmapConfig(android.graphics.Bitmap.Config.RGB_565) // Use 50% less memory per pixel
                .crossfade(true)
                .build(),
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
