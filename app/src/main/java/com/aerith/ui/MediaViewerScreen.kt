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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
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
import com.aerith.AerithApp
import com.aerith.auth.AuthState
import com.aerith.auth.Nip55Signer
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
    
    // Find the blob for the current URL
    val currentBlob = remember(uiState.allBlobs, url) { uiState.allBlobs.find { it.url == url } }
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

    var showSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()
    
    // Deletion State
    var blobToDelete by remember { mutableStateOf<com.aerith.core.blossom.BlossomBlob?>(null) }
    
    val signLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val signedJson = signer.parseSignEventResult(result.data)
            if (signedJson != null) {
                if (blobToDelete != null) {
                    galleryViewModel.deleteBlob(blobToDelete!!, signedJson)
                    blobToDelete = null // Reset
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
                model = coil.request.ImageRequest.Builder(LocalContext.current)
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
                .background(Color.Black.copy(alpha = 0.5f)) // Semitransparent background
                .padding(16.dp)
                .padding(WindowInsets.navigationBars.asPaddingValues()), // Respect nav bar
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
                    val shareIntent = android.content.Intent.createChooser(sendIntent, "Share Image Link")
                    context.startActivity(shareIntent)
                }
            ) {
                Icon(
                    imageVector = androidx.compose.material.icons.Icons.Default.Share,
                    contentDescription = "Share",
                    tint = Color.White
                )
            }

            // Info Button
            IconButton(
                onClick = { showSheet = true }
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "Details",
                    tint = Color.White
                )
            }
        }
        
        if (showSheet && currentBlob != null) {
            ModalBottomSheet(
                onDismissRequest = { showSheet = false },
                sheetState = sheetState
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("File Details", style = MaterialTheme.typography.headlineSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Type: ${currentBlob.type}")
                    Text("Size: ${currentBlob.size} bytes")
                    Text("SHA256: ${currentBlob.sha256.take(8)}...")
                    
                    Divider(modifier = Modifier.padding(vertical = 16.dp))
                    
                    Text("Hosted On:", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    LazyColumn {
                        items(relatedBlobs) { blob ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = blob.serverUrl?.removePrefix("https://")?.removeSuffix("/") ?: "Unknown",
                                    modifier = Modifier.weight(1f)
                                )
                                
                                IconButton(
                                    onClick = {
                                        // Trigger Deletion Flow
                                        val pk = authState.pubkey
                                        val pkg = authState.signerPackage
                                        if (pk != null) {
                                            val eventJson = galleryViewModel.prepareDeleteEvent(pk, blob)
                                            if (eventJson != null) {
                                                var signed: String? = null
                                                if (pkg != null) {
                                                    signed = signer.signEventBackground(pkg, eventJson, pk)
                                                }
                                                
                                                if (signed != null) {
                                                    galleryViewModel.deleteBlob(blob, signed)
                                                } else {
                                                    blobToDelete = blob
                                                    val intent = signer.getSignEventIntent(eventJson, pk)
                                                    signLauncher.launch(intent)
                                                }
                                            }
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete",
                                        tint = MaterialTheme.colorScheme.error
                                    )
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
