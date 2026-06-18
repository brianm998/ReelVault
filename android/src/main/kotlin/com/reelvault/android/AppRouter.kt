// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.android

import androidx.compose.runtime.*
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.reelvault.android.ui.screens.*

sealed class Screen(val route: String) {
    object Connection : Screen("connection")
    object Grid : Screen("grid")
    object Detail : Screen("detail/{videoId}") {
        fun createRoute(videoId: String) = "detail/$videoId"
    }
    object Settings : Screen("settings")
    object Map : Screen("map")
    object LocalMedia : Screen("local_media")
}

@Composable
fun AppRouter() {
    val app = ReelVaultApp.instance
    val navController = rememberNavController()
    var isConnected by remember { mutableStateOf(false) }

    NavHost(
        navController = navController,
        startDestination = Screen.Connection.route
    ) {
        composable(Screen.Connection.route) {
            ConnectionFlowScreen(
                repository = app.videoRepository,
                pairingClient = app.pairingClient,
                tokenStorage = app.tokenStorage,
                onConnected = {
                    isConnected = true
                    navController.navigate(Screen.Grid.route) {
                        popUpTo(Screen.Connection.route) { inclusive = true }
                    }
                },
                onBrowseLocalMedia = {
                    navController.navigate(Screen.LocalMedia.route) {
                        popUpTo(Screen.Connection.route) { inclusive = false }
                    }
                },
            )
        }
        composable(Screen.Grid.route) {
            LibraryGridScreen(
                repository = app.videoRepository,
                onVideoSelected = { videoId ->
                    navController.navigate(Screen.Detail.createRoute(videoId))
                },
                onOpenSettings = { navController.navigate(Screen.Settings.route) },
                onOpenMap = { navController.navigate(Screen.Map.route) },
                onDisconnect = {
                    app.videoRepository.disconnect()
                    isConnected = false
                    navController.navigate(Screen.Connection.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }
        composable(Screen.Detail.route) { backStack ->
            val videoId = backStack.arguments?.getString("videoId") ?: return@composable
            VideoDetailScreen(
                videoId = videoId,
                repository = app.videoRepository,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Screen.Settings.route) {
            LibrarySettingsScreen(
                repository = app.videoRepository,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Screen.Map.route) {
            LibraryMapScreen(
                repository = app.videoRepository,
                onVideoSelected = { videoId ->
                    navController.navigate(Screen.Detail.createRoute(videoId))
                },
                onBack = { navController.popBackStack() }
            )
        }
        composable(Screen.LocalMedia.route) {
            LocalMediaScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
