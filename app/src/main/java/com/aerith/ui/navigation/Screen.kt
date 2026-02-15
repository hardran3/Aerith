package com.aerith.ui.navigation

sealed class Screen(val route: String) {
    object Login : Screen("login")
    object Onboarding : Screen("onboarding")
    object Gallery : Screen("gallery")
    object Settings : Screen("settings")
    object MediaViewer : Screen("media_viewer/{encodedUrl}") {
        fun createRoute(url: String): String {
            val encoded = java.net.URLEncoder.encode(url, "UTF-8")
            return "media_viewer/$encoded"
        }
    }
}
