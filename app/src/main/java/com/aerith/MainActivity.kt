package com.aerith

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.activity.result.contract.ActivityResultContracts
import com.aerith.ui.GalleryScreen
import com.aerith.ui.LoginScreen
import com.aerith.ui.MediaViewerScreen
import com.aerith.ui.OnboardingScreen
import com.aerith.ui.SettingsScreen
import com.aerith.ui.theme.AerithTheme

class MainActivity : ComponentActivity() {
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Permissions granted, gallery will refresh vaulted hashes automatically
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Request media permissions for Vault discovery
        val permissions = if (android.os.Build.VERSION.SDK_INT >= 33) {
            arrayOf(
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.READ_MEDIA_VIDEO
            )
        } else {
            arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        requestPermissionLauncher.launch(permissions)

        setContent {
            AerithTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AerithAppContent()
                }
            }
        }
    }
}

@Composable

fun AerithAppContent(
    authViewModel: com.aerith.auth.AuthViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    galleryViewModel: com.aerith.ui.gallery.GalleryViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val navController = androidx.navigation.compose.rememberNavController()
    val authState by authViewModel.uiState.collectAsState()

    // Auto-navigate based on login and onboarding state
    LaunchedEffect(authState.isLoggedIn, authState.isOnboarding) {
        if (authState.isLoggedIn) {
            if (authState.isOnboarding) {
                navController.navigate(com.aerith.ui.navigation.Screen.Onboarding.route) {
                    popUpTo(com.aerith.ui.navigation.Screen.Login.route) { inclusive = true }
                }
            } else {
                navController.navigate(com.aerith.ui.navigation.Screen.Gallery.route) {
                    popUpTo(0) { inclusive = true }
                }
            }
        } else {
            navController.navigate(com.aerith.ui.navigation.Screen.Login.route) {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    androidx.navigation.compose.NavHost(
        navController = navController,
        startDestination = when {
            authState.isLoggedIn && authState.isOnboarding -> com.aerith.ui.navigation.Screen.Onboarding.route
            authState.isLoggedIn -> com.aerith.ui.navigation.Screen.Gallery.route
            else -> com.aerith.ui.navigation.Screen.Login.route
        }
    ) {
        composable(com.aerith.ui.navigation.Screen.Login.route) {
            LoginScreen(
                onLoginSuccess = { 
                    /* Handled by LaunchedEffect above */ 
                },
                viewModel = authViewModel
            )
        }

        composable(com.aerith.ui.navigation.Screen.Onboarding.route) {
            OnboardingScreen(authState = authState)
        }
        
        composable(com.aerith.ui.navigation.Screen.Gallery.route) {
            GalleryScreen(
                authState = authState,
                authViewModel = authViewModel,
                onMediaClick = { url ->
                    navController.navigate(com.aerith.ui.navigation.Screen.MediaViewer.createRoute(url))
                },
                onSettingsClick = {
                    navController.navigate(com.aerith.ui.navigation.Screen.Settings.route)
                },
                galleryViewModel = galleryViewModel
            )
        }

        composable(com.aerith.ui.navigation.Screen.Settings.route) {
            SettingsScreen(
                authState = authState,
                onBack = { navController.popBackStack() },
                onLogout = {
                    authViewModel.logout()
                    galleryViewModel.clear()
                },
                galleryViewModel = galleryViewModel
            )
        }

        composable(
            route = com.aerith.ui.navigation.Screen.MediaViewer.route,
            arguments = listOf(androidx.navigation.navArgument("encodedUrl") { type = androidx.navigation.NavType.StringType })
        ) { backStackEntry ->
            val encodedUrl = backStackEntry.arguments?.getString("encodedUrl") ?: ""
            val url = java.net.URLDecoder.decode(encodedUrl, "UTF-8")
            MediaViewerScreen(
                url = url,
                authState = authState,
                authViewModel = authViewModel,
                onBack = { navController.popBackStack() },
                galleryViewModel = galleryViewModel
            )
        }
    }
}
