package com.aerith.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aerith.auth.AuthState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    authState: AuthState,
    onBack: () -> Unit,
    onLogout: () -> Unit,
    galleryViewModel: com.aerith.ui.gallery.GalleryViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settingsRepository = remember { com.aerith.core.data.SettingsRepository(context) }
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("General", "Nostr", "Blossom")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Tabs
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }

            // Tab Content
            when (selectedTab) {
                0 -> GeneralTab(onLogout, settingsRepository, galleryViewModel)
                1 -> NostrTab(authState)
                2 -> BlossomTab(authState)
            }
        }
    }
}

@Composable
private fun GeneralTab(
    onLogout: () -> Unit,
    settingsRepository: com.aerith.core.data.SettingsRepository,
    galleryViewModel: com.aerith.ui.gallery.GalleryViewModel
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Display Section
        Text(
            text = "Display",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column {
                var serverBadgeEnabled by remember { mutableStateOf(settingsRepository.isServerBadgeEnabled()) }
                var fileTypeBadgeEnabled by remember { mutableStateOf(settingsRepository.isFileTypeBadgeEnabled()) }

                ListItem(
                    headlineContent = { Text("Server Count Badge") },
                    supportingContent = { Text("Show how many servers host this file") },
                    trailingContent = {
                        Switch(
                            checked = serverBadgeEnabled,
                            onCheckedChange = {
                                serverBadgeEnabled = it
                                settingsRepository.setServerBadgeEnabled(it)
                                galleryViewModel.refreshDisplaySettings()
                            }
                        )
                    }
                )
                
                ListItem(
                    headlineContent = { Text("File Type Badge") },
                    supportingContent = { Text("Show extension (JPG, MP4, etc)") },
                    trailingContent = {
                        Switch(
                            checked = fileTypeBadgeEnabled,
                            onCheckedChange = {
                                fileTypeBadgeEnabled = it
                                settingsRepository.setFileTypeBadgeEnabled(it)
                                galleryViewModel.refreshDisplaySettings()
                            }
                        )
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Session",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )

        Button(
            onClick = onLogout,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.error
            )
        ) {
            Text("Logout")
        }
        
        Text(
            text = "Logging out will clear your cached public key, relay lists, and Blossom servers.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun NostrTab(authState: AuthState) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Identity Section
        item {
            Text(
                text = "Identity",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Public Key", style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = authState.pubkey ?: "Not logged in",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        if (authState.profileName != null) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Display Name", style = MaterialTheme.typography.labelMedium)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = authState.profileName,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }

        // Relays Section
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Relays (${authState.relays.size})",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }

        if (authState.relays.isEmpty()) {
            item {
                Text(
                    text = "No relays discovered.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(authState.relays) { relay ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = relay.removePrefix("wss://").removePrefix("ws://").removeSuffix("/"),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BlossomTab(authState: AuthState) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "Local Storage",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text("Local Blossom Cache") },
                    supportingContent = { 
                        Text(if (authState.localBlossomUrl != null) "Active on port 24242" else "Service not detected") 
                    },
                    leadingContent = {
                        Icon(
                            imageVector = if (authState.localBlossomUrl != null) Icons.Default.Dns else Icons.Default.CloudOff,
                            contentDescription = null,
                            tint = if (authState.localBlossomUrl != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                        )
                    },
                    trailingContent = {
                        if (authState.localBlossomUrl != null) {
                            Icon(Icons.Default.CheckCircle, null, tint = Color.Green)
                        }
                    }
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Blossom Servers (${authState.blossomServers.size})",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }

        if (authState.blossomServers.isEmpty()) {
            item {
                Text(
                    text = "No Blossom servers discovered.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(authState.blossomServers) { server ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = server.removePrefix("https://").removeSuffix("/"),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = server,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}
