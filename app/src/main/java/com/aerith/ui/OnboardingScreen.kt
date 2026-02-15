package com.aerith.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aerith.auth.AuthState

@Composable
fun OnboardingScreen(
    authState: AuthState
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Setting up your library...",
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp)
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Text(
                text = "We're fetching your relays, Blossom servers, and file metadata from the Nostr network.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (authState.error != null) {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = authState.error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
