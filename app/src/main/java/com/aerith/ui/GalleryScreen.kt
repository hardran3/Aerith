package com.aerith.ui

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.aerith.auth.AuthState
import com.aerith.core.blossom.BlossomBlob
import com.aerith.core.nostr.BlossomAuthHelper
import com.aerith.ui.gallery.GalleryViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun GalleryScreen(
    authState: AuthState,
    authViewModel: com.aerith.auth.AuthViewModel,
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

    var pendingListAuth by remember { mutableStateOf<Iterator<Map.Entry<String, String>>?>(null) }
    var currentSigningServer by remember { mutableStateOf<String?>(null) }
    val authenticatedListHeaders = remember { mutableStateMapOf<String, String>() }
    var triggerNextSign by remember { mutableStateOf<android.content.Intent?>(null) }
    var showServerMenu by remember { mutableStateOf(false) }
    
    // Bulk Label Dialog State
    var showBulkLabelDialog by remember { mutableStateOf(false) }
    var showBulkDeleteServerDialog by remember { mutableStateOf(false) }
    var showBulkMirrorDialog by remember { mutableStateOf(false) }
    var isFilterExpanded by remember { mutableStateOf(false) }
    var bulkTagsInput by remember { mutableStateOf("") }

    // Track if we've performed the initial refresh this session
    var hasAutoRefreshed by rememberSaveable { mutableStateOf(false) }
    var isSigningFlowActive by remember { mutableStateOf(false) }

    val isSelectionMode = state.selectedHashes.isNotEmpty()

    BackHandler(enabled = isSelectionMode) {
        galleryViewModel.clearSelection()
    }

    val signLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        if (result.resultCode == Activity.RESULT_OK) {
            val signedJson = signer.parseSignEventResult(result.data) ?: run {
                isSigningFlowActive = false
                return@rememberLauncherForActivityResult
            }

            // Handle Upload signature (Sequential Flow)
            val currentUpload = uploadState.currentUpload
            if (currentUpload != null) {
                val pubkey = authState.pubkey ?: return@rememberLauncherForActivityResult
                uploadViewModel.uploadToCurrentServer(
                    uri = currentUpload.uri,
                    mimeType = currentUpload.mimeType,
                    signedEventJson = signedJson,
                    expectedHash = currentUpload.hash,
                    signer = signer,
                    signerPackage = authState.signerPackage,
                    pubkey = pubkey,
                    relays = authState.relays
                )
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
                        galleryViewModel.loadImages(
                            pk, authState.blossomServers, finalHeaders, 
                            authState.fileMetadata, authState.localBlossomUrl
                        )
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
                galleryViewModel.loadImages(
                    pubkey, servers, headers, 
                    authState.fileMetadata, authState.localBlossomUrl
                )
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
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            uploadViewModel.startBulkUpload(uris, context.contentResolver, authState.blossomServers)
        }
    }

    // --- Sequential Upload Queue Manager ---
    LaunchedEffect(uploadState.uploadQueue, uploadState.currentUpload, uploadState.targetServer, uploadState.isUploading, uploadState.showReviewDialog) {
        val pubkey = authState.pubkey
        val pkg = authState.signerPackage
        val relays = authState.relays
        
        if (pubkey != null && !isSigningFlowActive && !uploadState.isUploading && !uploadState.showReviewDialog) {
            // If we have a queue but nothing is being processed, pick the next one
            if (uploadState.uploadQueue.isNotEmpty() && uploadState.currentUpload == null) {
                uploadViewModel.processNextInQueue()
                return@LaunchedEffect
            }
            
            // If we have something to upload, trigger signing
            val current = uploadState.currentUpload
            val target = uploadState.targetServer
            if (current != null && target != null) {
                val unsignedEvent = BlossomAuthHelper.createUploadAuthEvent(
                    pubkey = pubkey, sha256 = current.hash, size = current.size,
                    mimeType = current.mimeType,
                    fileName = "${current.displayName}.${current.extension}",
                    serverUrl = target
                )
                
                var signed: String? = null
                if (pkg != null) {
                    signed = signer.signEventBackground(pkg, unsignedEvent, pubkey)
                }
                
                if (signed != null) {
                    uploadViewModel.uploadToCurrentServer(
                        uri = current.uri,
                        mimeType = current.mimeType,
                        signedEventJson = signed,
                        expectedHash = current.hash,
                        signer = signer,
                        signerPackage = pkg,
                        pubkey = pubkey,
                        relays = relays
                    )
                } else {
                    isSigningFlowActive = true
                    val intent = signer.getSignEventIntent(unsignedEvent, pubkey)
                    signLauncher.launch(intent)
                }
            }
        }
    }

    if (uploadState.showReviewDialog) {
        var batchTags by remember { mutableStateOf("") }
        val selectedServers = remember { mutableStateListOf<String>().apply { addAll(uploadState.selectedServers) } }
        val allExistingTags = galleryViewModel.getAllUniqueTags()

        AlertDialog(
            onDismissRequest = { uploadViewModel.dismissReview() },
            title = { Text("Upload Review (${uploadState.uploadQueue.size} items)") },
            text = {
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState())) {
                    Text("Batch Tags", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = batchTags,
                        onValueChange = { batchTags = it },
                        placeholder = { Text("nature, vacation...") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    if (allExistingTags.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Suggestions:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            allExistingTags.take(12).forEach { tag ->
                                val currentTags = batchTags.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                                val isSelected = tag in currentTags
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        val nextTags = if (isSelected) {
                                            currentTags.filter { it != tag }
                                        } else {
                                            (currentTags + tag).distinct()
                                        }
                                        batchTags = nextTags.joinToString(", ")
                                    },
                                    label = { Text(tag) }
                                )
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Target Servers", style = MaterialTheme.typography.titleMedium)
                    authState.blossomServers.forEach { server ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = selectedServers.contains(server),
                                onCheckedChange = { checked ->
                                    if (checked) selectedServers.add(server) else selectedServers.remove(server)
                                }
                            )
                            Text(server.removePrefix("https://").removeSuffix("/"))
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text("File Details", style = MaterialTheme.typography.titleMedium)
                    uploadState.uploadQueue.forEach { pending ->
                        Row(
                            modifier = Modifier.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AsyncImage(
                                model = pending.uri,
                                contentDescription = null,
                                modifier = Modifier.size(60.dp).clip(MaterialTheme.shapes.small),
                                contentScale = ContentScale.Crop
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                OutlinedTextField(
                                    value = pending.displayName,
                                    onValueChange = { uploadViewModel.updatePendingUpload(pending.hash, it) },
                                    label = { Text("Filename") },
                                    suffix = { Text(".${pending.extension}") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    text = "${pending.size / 1024} KB",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val tags = batchTags.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        uploadViewModel.setBatchOptions(selectedServers.toList(), tags)
                        uploadViewModel.confirmUpload()
                    },
                    enabled = selectedServers.isNotEmpty()
                ) {
                    Text("Upload All")
                }
            },
            dismissButton = {
                TextButton(onClick = { uploadViewModel.dismissReview() }) {
                    Text("Cancel")
                }
            }
        )
    }
    
    LaunchedEffect(authState.pubkey, authState.blossomServers, authState.isLoading) {
        if (authState.pubkey != null && authState.blossomServers.isNotEmpty() && !authState.isLoading && !hasAutoRefreshed) {
            hasAutoRefreshed = true
            triggerAuthenticatedList()
        }
    }

    LaunchedEffect(uploadState.successMessage) {
        if (uploadState.successMessage != null) {
            authViewModel.refreshMetadata()
            triggerAuthenticatedList()
            android.widget.Toast.makeText(context, uploadState.successMessage, android.widget.Toast.LENGTH_SHORT).show()
            uploadViewModel.clearState()
        }
    }

    // New: Re-sync labels when fresh metadata is discovered on relays
    LaunchedEffect(authState.fileMetadata) {
        if (authState.pubkey != null && authState.fileMetadata.isNotEmpty()) {
            galleryViewModel.refreshMetadataOnly(authState.fileMetadata)
        }
    }

    LaunchedEffect(uploadState.error) {
        if (uploadState.error != null) {
            android.widget.Toast.makeText(context, uploadState.error, android.widget.Toast.LENGTH_LONG).show()
            uploadViewModel.clearState()
        }
    }

    if (showBulkLabelDialog) {
        AlertDialog(
            onDismissRequest = { showBulkLabelDialog = false },
            title = { Text("Bulk Label (${state.selectedHashes.size} items)") },
            text = {
                Column {
                    Text("Enter comma-separated labels to apply to all selected items.")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = bulkTagsInput,
                        onValueChange = { bulkTagsInput = it },
                        label = { Text("Labels (e.g. nature, holiday)") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Suggestions
                    val allUniqueTags = galleryViewModel.getAllUniqueTags()
                    if (allUniqueTags.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Existing tags:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            allUniqueTags.take(15).forEach { tag ->
                                AssistChip(
                                    onClick = {
                                        val current = bulkTagsInput.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                                        if (tag !in current) {
                                            val newList = (current + tag).joinToString(", ")
                                            bulkTagsInput = newList
                                        }
                                    },
                                    label = { Text(tag) }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val pk = authState.pubkey
                    if (pk != null) {
                        val tags = bulkTagsInput.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        galleryViewModel.bulkUpdateLabels(pk, authState.relays, state.selectedHashes, tags, signer, authState.signerPackage)
                    }
                    showBulkLabelDialog = false
                    bulkTagsInput = ""
                }) {
                    Text("Apply")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBulkLabelDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showBulkDeleteServerDialog) {
        val selectedServers = remember { 
            mutableStateListOf<String>().apply { 
                val current = state.selectedServer
                if (current != null && current != "TRASH") {
                    add(current)
                } else {
                    addAll(authState.blossomServers)
                }
            } 
        }

        AlertDialog(
            onDismissRequest = { showBulkDeleteServerDialog = false },
            title = { Text("Delete ${state.selectedHashes.size} items") },
            text = {
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                    Text("Select servers to delete from. If an item is removed from ALL servers, a copy will be moved to your local Trash.")
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    authState.blossomServers.forEach { server ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable {
                                if (selectedServers.contains(server)) selectedServers.remove(server) else selectedServers.add(server)
                            }
                        ) {
                            Checkbox(
                                checked = selectedServers.contains(server),
                                onCheckedChange = { checked ->
                                    if (checked) selectedServers.add(server) else selectedServers.remove(server)
                                }
                            )
                            Text(server.removePrefix("https://").removeSuffix("/"))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val pk = authState.pubkey
                        if (pk != null) {
                            galleryViewModel.bulkDelete(pk, state.selectedHashes, selectedServers.toList(), signer, authState.signerPackage)
                        }
                        showBulkDeleteServerDialog = false
                    },
                    enabled = selectedServers.isNotEmpty(),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBulkDeleteServerDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showBulkMirrorDialog) {
        val selectedServers = remember { mutableStateListOf<String>().apply { addAll(authState.blossomServers) } }
        
        AlertDialog(
            onDismissRequest = { showBulkMirrorDialog = false },
            title = { Text("Mirror ${state.selectedHashes.size} items") },
            text = {
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                    Text("Select target servers:")
                    Spacer(modifier = Modifier.height(8.dp))
                    authState.blossomServers.forEach { server ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable {
                                if (selectedServers.contains(server)) selectedServers.remove(server) else selectedServers.add(server)
                            }
                        ) {
                            Checkbox(
                                checked = selectedServers.contains(server),
                                onCheckedChange = { checked ->
                                    if (checked) selectedServers.add(server) else selectedServers.remove(server)
                                }
                            )
                            Text(server.removePrefix("https://").removeSuffix("/"))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val pk = authState.pubkey
                        if (pk != null) {
                            galleryViewModel.bulkMirrorToAll(pk, state.selectedHashes, selectedServers.toList(), signer, authState.signerPackage, authState.localBlossomUrl)
                        }
                        showBulkMirrorDialog = false
                    },
                    enabled = selectedServers.isNotEmpty()
                ) {
                    Text("Mirror")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBulkMirrorDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            if (isSelectionMode) {
                TopAppBar(
                    title = { Text("${state.selectedHashes.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { galleryViewModel.clearSelection() }) {
                            Icon(Icons.Default.Close, "Clear Selection")
                        }
                    },
                    actions = {
                        IconButton(onClick = { showBulkLabelDialog = true }) {
                            Icon(Icons.Default.Label, "Bulk Label")
                        }
                        IconButton(onClick = { showBulkMirrorDialog = true }) {
                            Icon(Icons.Default.CloudSync, "Mirror to All")
                        }
                        IconButton(onClick = { showBulkDeleteServerDialog = true }) {
                            Icon(Icons.Default.Delete, "Delete from selected servers", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                )
            } else {
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
                                    val count = state.filteredBlobs.size
                                    val titleText = when (state.selectedServer) {
                                        null -> "All Media"
                                        "TRASH" -> "Trash"
                                        else -> state.selectedServer?.removePrefix("https://")?.removeSuffix("/") ?: "All Media"
                                    }
                                    Text(
                                        text = "$titleText ($count)",
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
                                    HorizontalDivider()
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
            }
        },
        floatingActionButton = {
            if (!isSelectionMode) {
                FloatingActionButton(onClick = { 
                    pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) 
                }) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = "Upload")
                }
            }
        }
    ) { padding ->
        val allTags = remember(state.filteredBlobs, state.selectedTags, state.fileMetadata) {
            // Count frequency of each tag in the current filtered results
            val counts = state.filteredBlobs
                .flatMap { it.getTags(state.fileMetadata) }
                .groupingBy { it }
                .eachCount()

            val visibleTags = counts.keys
            val selected = state.selectedTags.filter { it in visibleTags || state.selectedTags.contains(it) }
            val others = (visibleTags - state.selectedTags.toSet()).sorted()
            
            (selected + others).distinct().map { tag ->
                tag to (counts[tag] ?: 0)
            }
        }

        // Full unfiltered set for the Expanded Filter Panel and Labeling dialogs
        val (fullLibraryTags, fullLibraryExtensions) = remember(state.allBlobs, state.trashBlobs, state.fileMetadata) {
            galleryViewModel.getAllUniqueTags() to galleryViewModel.getAvailableExtensions()
        }

        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Background update/upload/mirror indicator
            val isBusy = uploadState.isUploading || state.isLoading || state.localSyncProgress != null || state.vaultSyncProgress != null
            val progressText = uploadState.progress ?: state.loadingMessage ?: state.localSyncProgress ?: state.vaultSyncProgress

            if (isBusy) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    progressText?.let { text ->
                        Text(
                            text = text,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(start = 8.dp, top = 2.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { isFilterExpanded = !isFilterExpanded },
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Icon(
                        imageVector = if (isFilterExpanded) Icons.Default.Close else Icons.Default.FilterList, 
                        contentDescription = "Filter Options", 
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                if (!isFilterExpanded) {
                    if (allTags.isNotEmpty()) {
                        ScrollableTabRow(
                            selectedTabIndex = -1,
                            edgePadding = 8.dp,
                            containerColor = Color.Transparent,
                            modifier = Modifier.weight(1f),
                            divider = {},
                            indicator = {}
                        ) {
                            allTags.forEach { (tag, count) ->
                                val isSelected = state.selectedTags.contains(tag)
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { galleryViewModel.toggleTag(tag) },
                                    label = { 
                                        Text(
                                            text = if (count > 0) "$tag $count" else tag,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontSize = 11.sp
                                        ) 
                                    },
                                    modifier = Modifier.padding(horizontal = 4.dp),
                                    leadingIcon = if (isSelected) {
                                        { Icon(Icons.Default.Check, null, modifier = Modifier.size(14.dp)) }
                                    } else null
                                )
                            }
                        }
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    
                    if (state.selectedTags.isNotEmpty()) {
                        TextButton(
                            onClick = { galleryViewModel.clearTags() },
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text("Clear")
                        }
                    }
                } else {
                    Text(
                        text = "Filters",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    if (state.selectedTags.isNotEmpty() || state.selectedExtensions.isNotEmpty()) {
                        TextButton(
                            onClick = { 
                                galleryViewModel.clearTags()
                                state.selectedExtensions.forEach { galleryViewModel.toggleExtension(it) }
                            },
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text("Reset All")
                        }
                    }
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (isFilterExpanded) {
                        Surface(
                            tonalElevation = 2.dp,
                            shadowElevation = 4.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 500.dp) // Cap height to keep some grid visible
                        ) {
                            Column {
                                // 1. Scrollable Tag Area
                                Box(
                                    modifier = Modifier
                                        .weight(1f, fill = false)
                                        .padding(horizontal = 16.dp)
                                ) {
                                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            "Tags",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        FlowRow(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            fullLibraryTags.forEach { tag ->
                                                val isSelected = state.selectedTags.contains(tag)
                                                // Get count from the filtered counts map
                                                val count = allTags.find { it.first == tag }?.second ?: 0
                                                
                                                FilterChip(
                                                    selected = isSelected,
                                                    onClick = { galleryViewModel.toggleTag(tag) },
                                                    label = {
                                                        Text(text = if (count > 0) "$tag $count" else tag)
                                                    },
                                                    leadingIcon = if (isSelected) {
                                                        {
                                                            Icon(
                                                                Icons.Default.Check,
                                                                null,
                                                                modifier = Modifier.size(14.dp)
                                                            )
                                                        }
                                                    } else null
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(16.dp))
                                    }
                                }

                                // 2. Fixed Footer
                                Column(
                                    modifier = Modifier
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                        .padding(16.dp)
                                ) {
                                    Text("Media Type", style = MaterialTheme.typography.titleSmall)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        FilterChip(
                                            selected = state.showImages,
                                            onClick = { galleryViewModel.toggleShowImages() },
                                            label = { Text("Images") },
                                            leadingIcon = if (state.showImages) {
                                                { Icon(Icons.Default.Check, null, modifier = Modifier.size(14.dp)) }
                                            } else null
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        FilterChip(
                                            selected = state.showVideos,
                                            onClick = { galleryViewModel.toggleShowVideos() },
                                            label = { Text("Videos") },
                                            leadingIcon = if (state.showVideos) {
                                                { Icon(Icons.Default.Check, null, modifier = Modifier.size(14.dp)) }
                                            } else null
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(12.dp))

                                    Text("File Types", style = MaterialTheme.typography.titleSmall)
                                    FlowRow(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        fullLibraryExtensions.forEach { ext ->
                                            FilterChip(
                                                selected = state.selectedExtensions.contains(ext),
                                                onClick = { galleryViewModel.toggleExtension(ext) },
                                                label = { Text(ext.uppercase()) },
                                                leadingIcon = if (state.selectedExtensions.contains(ext)) {
                                                    { Icon(Icons.Default.Check, null, modifier = Modifier.size(14.dp)) }
                                                } else null
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(12.dp))
                                    Button(
                                        onClick = { isFilterExpanded = false },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Show Results")
                                    }
                                }
                            }
                        }
                    }

                    // --- Gallery Grid ---
                    Box(modifier = Modifier.weight(1f)) {
                        // Initial load or background refresh when empty
                        val showFullLoading = state.isLoading && state.allBlobs.isEmpty()

                        if (showFullLoading && !uploadState.isUploading) {
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
                                contentPadding = PaddingValues(1.dp),
                                horizontalArrangement = Arrangement.spacedBy(1.dp),
                                verticalArrangement = Arrangement.spacedBy(1.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(
                                    items = state.filteredBlobs,
                                    key = { it.sha256 } // Stable keys for high-performance scrolling
                                ) { blob ->
                                    val isSelected = state.selectedHashes.contains(blob.sha256)
                                    val isVaulted = state.vaultedHashes.contains(blob.sha256)
                                    val isLocallyCached = state.locallyCachedHashes.contains(blob.sha256)
                                    val serverCount = remember(state.allBlobs, blob.sha256, isLocallyCached) {
                                        val remoteCount = state.allBlobs.filter { it.sha256 == blob.sha256 }.mapNotNull { it.serverUrl }.distinct().size
                                        if (isLocallyCached) remoteCount + 1 else remoteCount
                                    }
                                    val isDiscovered = remember(state.allBlobs, blob.sha256) {
                                        state.allBlobs.none { it.sha256 == blob.sha256 }
                                    }
                                    val hasMetadata = remember(state.fileMetadata, blob.sha256) {
                                        state.fileMetadata.containsKey(blob.sha256.lowercase())
                                    }

                                    MediaItem(
                                        blob = blob,
                                        isSelected = isSelected,
                                        inSelectionMode = isSelectionMode,
                                        isVaulted = isVaulted,
                                        isLocallyCached = isLocallyCached,
                                        localUrl = authState.localBlossomUrl,
                                        isDiscovered = isDiscovered,
                                        hasMetadata = hasMetadata,
                                        isFileTypeBadgeEnabled = state.isFileTypeBadgeEnabled,
                                        onClick = { 
                                            if (isSelectionMode) {
                                                galleryViewModel.toggleSelection(blob.sha256)
                                            } else {
                                                onMediaClick(blob.url) 
                                            }
                                        },
                                        onLongClick = {
                                            galleryViewModel.toggleSelection(blob.sha256)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaItem(
    blob: BlossomBlob, 
    isSelected: Boolean,
    inSelectionMode: Boolean,
    isVaulted: Boolean,
    isLocallyCached: Boolean,
    localUrl: String?,
    isDiscovered: Boolean = false,
    hasMetadata: Boolean = false,
    isFileTypeBadgeEnabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val isVideo = remember(blob.type, blob.mime) { 
        blob.getMimeType()?.startsWith("video/") == true 
    }
    val extension = remember(blob.type, blob.mime) {
        blob.getMimeType()?.substringAfterLast("/")?.substringBefore(";")?.uppercase() ?: "???"
    }
    val context = LocalContext.current
    val vaultManager = remember { com.aerith.core.data.BlobVaultManager(context) }
    
    val imageRequest = remember(blob.sha256, blob.url, isVaulted, isLocallyCached) {
        val model = when {
            isVaulted -> vaultManager.getVaultFile(blob.sha256)
            isLocallyCached && localUrl != null -> "$localUrl/${blob.sha256}"
            else -> blob.getThumbnailUrl()
        }

        coil.request.ImageRequest.Builder(context)
            .data(model)
            .size(400, 400)
            .precision(coil.size.Precision.INEXACT)
            .bitmapConfig(android.graphics.Bitmap.Config.RGB_565)
            .crossfade(true)
            .build()
    }
    
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        AsyncImage(
            model = imageRequest,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // --- Badges ---
        
        // 1. Top Right: Discovery Badge
        if (isDiscovered) {
            Box(
                modifier = Modifier
                    .padding(4.dp)
                    .align(Alignment.TopEnd)
                    .background(Color(0xFFE1BEE7), CircleShape)
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            ) {
                Text(
                    text = "RELAY",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Black,
                    fontSize = 7.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
            }
        }

        // 3. Bottom Left: File Extension (Pill)
        if (isFileTypeBadgeEnabled) {
            Box(
                modifier = Modifier
                    .padding(4.dp)
                    .align(Alignment.BottomStart)
                    .background(Color.White, MaterialTheme.shapes.extraSmall)
                    .padding(horizontal = 3.dp, vertical = 0.5.dp)
            ) {
                Text(
                    text = extension,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Black,
                    fontSize = 8.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
            }
        }
        
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

        if (inSelectionMode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (isSelected) Color.Black.copy(alpha = 0.4f) else Color.Transparent)
                    .padding(8.dp)
            ) {
                RadioButton(
                    selected = isSelected,
                    onClick = null, // Handled by Box click
                    modifier = Modifier.align(Alignment.TopEnd),
                    colors = RadioButtonDefaults.colors(
                        selectedColor = MaterialTheme.colorScheme.primary,
                        unselectedColor = Color.White
                    )
                )
            }
        }
    }
}
