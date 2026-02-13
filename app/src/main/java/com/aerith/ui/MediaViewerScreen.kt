package com.aerith.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.aerith.AerithApp
import com.aerith.auth.AuthState
import com.aerith.auth.Nip55Signer
import com.aerith.core.nostr.BlossomAuthHelper
import com.aerith.ui.gallery.GalleryViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaViewerScreen(
    url: String,
    authState: AuthState,
    galleryViewModel: GalleryViewModel = viewModel()
) {
    val uiState by galleryViewModel.uiState.collectAsState()
    val context = LocalContext.current
    val signer = remember { Nip55Signer(context) }
    
    // Find the blob for the current URL (search both active and trash)
    val currentBlob = remember(uiState.allBlobs, uiState.trashBlobs, url) { 
        uiState.allBlobs.find { it.url == url } ?: uiState.trashBlobs.find { it.url == url }
    }
    val isVideo = remember(currentBlob) { currentBlob?.getMimeType()?.startsWith("video/") == true }

    // ExoPlayer setup with caching
    val exoPlayer = remember {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Aerith/1.0")
            .setAllowCrossProtocolRedirects(true)

        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(AerithApp.videoCache)
            .setUpstreamDataSourceFactory(httpDataSourceFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheDataSourceFactory))
            .build().apply {
                repeatMode = Player.REPEAT_MODE_ONE
                playWhenReady = true
            }
    }

    LaunchedEffect(url) {
        if (isVideo) {
            val mediaItem = MediaItem.fromUri(url)
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }
    
    // Find all blobs with the same SHA256 (same file, different servers)
    val relatedBlobs = remember(uiState.allBlobs, currentBlob) {
        if (currentBlob != null) {
            uiState.allBlobs.filter { it.sha256 == currentBlob.sha256 }
        } else {
            emptyList()
        }
    }

    var showInfoSheet by remember { mutableStateOf(false) }
    var showServerSheet by remember { mutableStateOf(false) }
    val infoSheetState = rememberModalBottomSheetState()
    val serverSheetState = rememberModalBottomSheetState()
    
    // Signer State
    var blobToDelete by remember { mutableStateOf<com.aerith.core.blossom.BlossomBlob?>(null) }
    var pendingMirrorServer by remember { mutableStateOf<String?>(null) }
    
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
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (isVideo) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = true
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(url)
                    .diskCacheKey(currentBlob?.sha256)
                    .memoryCacheKey(currentBlob?.sha256)
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
        
        // Bottom Bar with Actions
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(16.dp)
                .padding(WindowInsets.navigationBars.asPaddingValues()),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Share Button
            IconButton(
                onClick = {
                    val sendIntent = android.content.Intent().apply {
                        action = android.content.Intent.ACTION_SEND
                        putExtra(android.content.Intent.EXTRA_TEXT, url)
                        type = "text/plain"
                    }
                    val shareIntent = android.content.Intent.createChooser(sendIntent, "Share Media Link")
                    context.startActivity(shareIntent)
                }
            ) {
                Icon(Icons.Default.Share, "Share", tint = Color.White)
            }

            // Server Settings Button
            IconButton(
                onClick = { showServerSheet = true }
            ) {
                Icon(Icons.Default.Storage, "Servers", tint = Color.White)
            }

            // Info Button
            IconButton(
                onClick = { showInfoSheet = true }
            ) {
                Icon(Icons.Default.Info, "Details", tint = Color.White)
            }
        }
        
        // --- Info Sheet ---
        if (showInfoSheet && currentBlob != null) {
            ModalBottomSheet(
                onDismissRequest = { showInfoSheet = false },
                sheetState = infoSheetState
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("File Details", style = MaterialTheme.typography.headlineSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Type: ${currentBlob.getMimeType()}")
                    Text("Size: ${currentBlob.getSizeAsLong()} bytes")
                    Text("SHA256: ${currentBlob.sha256}")
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
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
                                    
                                    if (isMirroring) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(24.dp),
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
                                                // Mirroring using BUD-04 (PUT /mirror)
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
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }
}
