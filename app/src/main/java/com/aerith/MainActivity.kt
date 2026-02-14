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
import com.aerith.ui.GalleryScreen
import com.aerith.ui.LoginScreen
import com.aerith.ui.MediaViewerScreen
import com.aerith.ui.SettingsScreen
import com.aerith.ui.theme.AerithTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

    // Auto-navigate based on login state
    LaunchedEffect(authState.isLoggedIn) {
        if (authState.isLoggedIn) {
            navController.navigate(com.aerith.ui.navigation.Screen.Gallery.route) {
                popUpTo(com.aerith.ui.navigation.Screen.Login.route) { inclusive = true }
            }
        } else {
            navController.navigate(com.aerith.ui.navigation.Screen.Login.route) {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    androidx.navigation.compose.NavHost(
        navController = navController,
        startDestination = if (authState.isLoggedIn) com.aerith.ui.navigation.Screen.Gallery.route else com.aerith.ui.navigation.Screen.Login.route
    ) {
        composable(com.aerith.ui.navigation.Screen.Login.route) {
            LoginScreen(
                onLoginSuccess = { 
                    /* Handled by LaunchedEffect above */ 
                },
                viewModel = authViewModel
            )
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
                galleryViewModel = galleryViewModel
            )
        }
    }
}
