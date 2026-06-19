// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.android

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.reelvault.android.data.DefaultServerPrefs
import com.reelvault.android.ui.screens.*
import com.reelvault.android.ui.screens.OfflineLibraryScreen
import com.reelvault.android.viewmodel.GridViewModel

sealed class Screen(val route: String) {
    object Connection : Screen("connection")
    object Grid : Screen("grid")
    object Detail : Screen("detail/{videoId}") {
        fun createRoute(videoId: String) = "detail/$videoId"
    }
    object Settings : Screen("settings")
    object Map : Screen("map")
    object LocalMedia : Screen("local_media")
    object OfflineLibrary : Screen("offline_library")
}

@Composable
fun AppRouter() {
    val app = ReelVaultApp.instance
    val navController = rememberNavController()
    var isConnected by remember { mutableStateOf(false) }

    // One GridViewModel shared across the Grid, Map and Detail destinations
    // (activity-scoped) so the map can apply a location filter the grid reads,
    // and "Show on Map" can focus the map — mirroring the single shared `grid`
    // view-model on iOS/macOS.
    val context = LocalContext.current
    val gridViewModel: GridViewModel = viewModel(
        factory = GridViewModel.Factory(app.videoRepository, context)
    )

    // Persist the user's last destination so we can return there on relaunch.
    val prefs = remember { DefaultServerPrefs(context) }
    // Read synchronously — SharedPreferences, no I/O wait.
    val startDestination = remember {
        when (prefs.loadLastMode()) {
            "local" -> Screen.LocalMedia.route
            else -> Screen.Connection.route
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Screen.Connection.route) {
            ConnectionFlowScreen(
                repository = app.videoRepository,
                pairingClient = app.pairingClient,
                tokenStorage = app.tokenStorage,
                onConnected = {
                    prefs.saveLastMode("remote")
                    isConnected = true
                    navController.navigate(Screen.Grid.route) {
                        popUpTo(Screen.Connection.route) { inclusive = true }
                    }
                },
                onBrowseLocalMedia = {
                    prefs.saveLastMode("local")
                    navController.navigate(Screen.LocalMedia.route) {
                        popUpTo(Screen.Connection.route) { inclusive = false }
                    }
                },
                onBrowseOfflineLibrary = {
                    navController.navigate(Screen.OfflineLibrary.route) {
                        popUpTo(Screen.Connection.route) { inclusive = false }
                    }
                },
            )
        }
        composable(Screen.Grid.route) {
            LibraryGridScreen(
                repository = app.videoRepository,
                vm = gridViewModel,
                onVideoSelected = { videoId ->
                    navController.navigate(Screen.Detail.createRoute(videoId))
                },
                onOpenSettings = { navController.navigate(Screen.Settings.route) },
                onOpenMap = { navController.navigate(Screen.Map.route) },
                onOpenLocalMedia = {
                    navController.navigate(Screen.LocalMedia.route)
                },
                onDisconnect = {
                    app.videoRepository.disconnect()
                    // Activity-scoped VM outlives the connection — wipe the old
                    // session's filters/state so the next connection starts clean.
                    gridViewModel.resetForNewSession()
                    prefs.clearLastMode()
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
                gridViewModel = gridViewModel,
                onBack = { navController.popBackStack() },
                onShowOnMap = { lat, lon ->
                    gridViewModel.setMapFocus(lat, lon)
                    // Replace Detail with Map so "back" from the map returns to
                    // the grid (and a marker tap there filters the grid as usual).
                    navController.navigate(Screen.Map.route) {
                        popUpTo(Screen.Detail.route) { inclusive = true }
                    }
                },
            )
        }
        composable(Screen.Settings.route) {
            LibrarySettingsScreen(
                repository = app.videoRepository,
                pairingClient = app.pairingClient,
                tokenStorage = app.tokenStorage,
                onBack = { navController.popBackStack() },
                onForget = {
                    // Wipe grid state left over from the abandoned session, then
                    // return to the connection flow (identical to onDisconnect but
                    // credentials have already been cleared by the settings screen).
                    gridViewModel.resetForNewSession()
                    prefs.clearLastMode()
                    isConnected = false
                    navController.navigate(Screen.Connection.route) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }
        composable(Screen.Map.route) {
            LibraryMapScreen(
                repository = app.videoRepository,
                grid = gridViewModel,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Screen.LocalMedia.route) {
            LocalMediaScreen(
                onBack = {
                    // If LocalMedia was the start destination there's nothing to
                    // pop back to — navigate to the connection screen instead.
                    if (!navController.popBackStack()) {
                        navController.navigate(Screen.Connection.route)
                    }
                }
            )
        }
        composable(Screen.OfflineLibrary.route) {
            OfflineLibraryScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
