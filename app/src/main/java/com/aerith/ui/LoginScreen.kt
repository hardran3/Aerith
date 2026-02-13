package com.aerith.ui

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aerith.auth.AuthViewModel
import com.aerith.auth.Nip55Signer

@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    viewModel: AuthViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val signer = Nip55Signer(context)

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.onLoginResult(result.data)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Welcome to Aerith", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(32.dp))

        if (state.isLoading) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text("Discovering relays and servers...")
        } else if (!state.isLoggedIn) {
            Button(onClick = {
                if (signer.isExternalSignerInstalled()) {
                    launcher.launch(signer.getLoginIntent())
                } else {
                    // Show error or prompt to install Amber
                }
            }) {
                Text("Login with External Signer (NIP-55)")
            }
        } else {
            // Logged in, show summary or proceed
            Text("Logged in as: ${state.pubkey?.take(8)}...")
            Spacer(modifier = Modifier.height(8.dp))
            Text("Relays found: ${state.relays.size}")
            Text("Blossom Servers found: ${state.blossomServers.size}")
            
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onLoginSuccess) {
                Text("Continue to Gallery")
            }
        }
        
        state.error?.let { error ->
            Spacer(modifier = Modifier.height(16.dp))
            Text(error, color = MaterialTheme.colorScheme.error)
        }
    }
}
