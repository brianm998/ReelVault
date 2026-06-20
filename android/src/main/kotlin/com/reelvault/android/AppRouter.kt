// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.android

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.reelvault.android.core.LocalCore
import com.reelvault.android.data.DefaultServerPrefs
import com.reelvault.android.data.MediaStoreIngest
import com.reelvault.android.ui.screens.*
import com.reelvault.android.ui.screens.OfflineLibraryScreen
import com.reelvault.android.viewmodel.GridViewModel
import com.reelvault.android.viewmodel.NavEntry
import com.reelvault.android.viewmodel.NavRoute
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop

sealed class Screen(val route: String) {
    object Connection : Screen("connection")
    object Grid : Screen("grid")
    object Detail : Screen("detail/{videoId}") {
        fun createRoute(videoId: String) = "detail/$videoId"
    }
    object Settings : Screen("settings")
    object Map : Screen("map")
    // Boots the embedded core for on-device (Local) mode, then hands off to Grid.
    object LocalStart : Screen("local_start")
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
            "local" -> Screen.LocalStart.route
            else -> Screen.Connection.route
        }
    }

    // ── Session navigation history (browser-style back/forward) ─────────────
    // Three passive observers record a new browse location whenever the user
    // lands on a grid/detail/map destination, toggles grid<->list, or picks a
    // different source. recordNav() dedups (so returning to where you were adds
    // nothing) and absorbs restore-driven fires (so the chevrons don't pollute
    // the history). Detail/Map are SEPARATE NavController destinations, so we
    // observe the back-stack route rather than an iOS-style single view mode.
    val canGoBack by gridViewModel.canGoBack.collectAsStateWithLifecycle()
    val canGoForward by gridViewModel.canGoForward.collectAsStateWithLifecycle()

    LaunchedEffect(navController) {
        navController.currentBackStackEntryFlow.collect { entry ->
            when (entry.destination.route) {
                Screen.Grid.route   -> gridViewModel.recordNav(NavRoute.GRID, null)
                Screen.Map.route    -> gridViewModel.recordNav(NavRoute.MAP, null)
                Screen.Detail.route -> gridViewModel.recordNav(
                    NavRoute.DETAIL, entry.arguments?.getString("videoId"))
                else -> { /* Connection / Settings / LocalMedia / Offline: not browse */ }
            }
        }
    }
    LaunchedEffect(navController) {
        // A grid<->list toggle is a new browse location (iOS records it too).
        gridViewModel.viewMode.drop(1).collect {
            if (navController.currentDestination?.route == Screen.Grid.route)
                gridViewModel.recordNav(NavRoute.GRID, null)
        }
    }
    LaunchedEffect(navController) {
        // A sidebar source pick / clear, while browsing the grid.
        combine(
            gridViewModel.selectedCollectionId,
            gridViewModel.filterTagId,
            gridViewModel.locationPathFlow,
        ) { _, _, _ -> Unit }
            .drop(1)  // ignore the initial conflated replay
            .collect {
                if (navController.currentDestination?.route == Screen.Grid.route)
                    gridViewModel.recordNav(NavRoute.GRID, null)
            }
    }

    // The single funnel every chevron / history-back goes through: apply the
    // entry's VM state, then drive the NavController to its 2-level target shape.
    fun applyEntry(e: NavEntry) {
        gridViewModel.applyEntryState(e)
        navigateToBrowseRoute(navController, e)
    }
    // Chevron back: walk history; on Detail/Map after process death (empty
    // history) fall back to a plain pop so the back button can never freeze.
    val onHistoryBack: () -> Unit = {
        val e = gridViewModel.consumeBack()
        if (e != null) applyEntry(e) else navController.popBackStack()
    }
    val onHistoryForward: () -> Unit = {
        gridViewModel.consumeForward()?.let { applyEntry(it) }
    }

    // Foreground catch-up: re-run the cheap incremental MediaStore ingest when
    // the app returns to the foreground while the on-device library is active
    // (mirrors iOS scenePhase .active). No-op in remote mode.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME &&
                LocalCore.port > 0 && !app.videoRepository.isRemote
            ) {
                MediaStoreIngest.kickoff(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
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
                    navController.navigate(Screen.LocalStart.route) {
                        popUpTo(Screen.Connection.route) { inclusive = true }
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
            // Start the live server health monitor in remote mode so the
            // grid can detect a dropped connection and offer offline browsing.
            LaunchedEffect(Unit) {
                if (app.videoRepository.isRemote) {
                    val saved = DefaultServerPrefs(context).load()
                    if (saved != null) {
                        gridViewModel.startConnectionMonitor(saved.host, saved.grpcPort)
                    }
                }
            }
            LibraryGridScreen(
                repository = app.videoRepository,
                vm = gridViewModel,
                canGoBack = canGoBack,
                canGoForward = canGoForward,
                onHistoryBack = onHistoryBack,
                onHistoryForward = onHistoryForward,
                onVideoSelected = { videoId ->
                    // Keep the back stack at the 2-level shape [Grid, overlay] so
                    // the NavController stack and the session history never diverge
                    // in depth (the rich history lives in NavHistory, not here).
                    navController.navigate(Screen.Detail.createRoute(videoId)) {
                        popUpTo(Screen.Grid.route) { inclusive = false }
                        launchSingleTop = true
                    }
                },
                onOpenSettings = { navController.navigate(Screen.Settings.route) },
                onOpenMap = {
                    navController.navigate(Screen.Map.route) {
                        popUpTo(Screen.Grid.route) { inclusive = false }
                        launchSingleTop = true
                    }
                },
                isLocal = !app.videoRepository.isRemote,
                onOpenLocalMedia = {
                    // Catalog switcher: server → on-device library.
                    app.videoRepository.disconnect()
                    gridViewModel.resetForNewSession()
                    prefs.saveLastMode("local")
                    navController.navigate(Screen.LocalStart.route) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onSwitchToServer = {
                    // Catalog switcher: on-device library → server (Connection
                    // auto-reconnects to the last-paired server if creds exist).
                    MediaStoreIngest.requestCancel()
                    MediaStoreIngest.stopObserver(context)
                    app.videoRepository.disconnect()
                    LocalCore.stop()
                    gridViewModel.resetForNewSession()
                    prefs.saveLastMode("remote")
                    isConnected = false
                    navController.navigate(Screen.Connection.route) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onDisconnect = {
                    // Works for both modes: also stop the embedded core + ingest
                    // if they were running (both no-ops when in remote mode).
                    MediaStoreIngest.requestCancel()
                    MediaStoreIngest.stopObserver(context)
                    app.videoRepository.disconnect()
                    LocalCore.stop()
                    // Activity-scoped VM outlives the connection — wipe the old
                    // session's filters/state so the next connection starts clean.
                    gridViewModel.resetForNewSession()
                    prefs.clearLastMode()
                    isConnected = false
                    navController.navigate(Screen.Connection.route) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onGoOffline = {
                    // Server is unreachable; user chose to browse downloaded videos.
                    // Keep the last-server pref so the next launch reconnects to it.
                    app.videoRepository.disconnect()
                    gridViewModel.resetForNewSession()
                    isConnected = false
                    navController.navigate(Screen.OfflineLibrary.route) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }
        composable(Screen.Detail.route) { backStack ->
            val videoId = backStack.arguments?.getString("videoId") ?: return@composable
            VideoDetailScreen(
                videoId = videoId,
                repository = app.videoRepository,
                gridViewModel = gridViewModel,
                // The nav-arrow / system back go through the history so leaving
                // Detail moves the cursor (no phantom grid entry, no desync).
                onBack = onHistoryBack,
                canGoForward = canGoForward,
                onHistoryForward = onHistoryForward,
                onShowOnMap = { lat, lon ->
                    gridViewModel.setMapFocus(lat, lon)
                    // Map sits directly on Grid (2-level shape). History still
                    // records […, Detail(X), Map], so chevron-back from the map
                    // returns to Detail(X) while system back goes to the grid.
                    navController.navigate(Screen.Map.route) {
                        popUpTo(Screen.Grid.route) { inclusive = false }
                        launchSingleTop = true
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
                // Cluster tap → pop back to the grid. The geo filter it applies
                // is a facet (intentionally not part of the recorded source, per
                // iOS parity), so the resulting grid entry matches the one behind
                // the map and chevron-back from it returns to the map.
                onBack = { navController.popBackStack() },
                // Nav-arrow / system back: walk the session history instead.
                onHistoryBack = onHistoryBack,
                onHistoryForward = onHistoryForward,
                canGoForward = canGoForward,
            )
        }
        composable(Screen.LocalStart.route) {
            LocalStartScreen(
                repository = app.videoRepository,
                onStarted = {
                    isConnected = true
                    navController.navigate(Screen.Grid.route) {
                        popUpTo(Screen.LocalStart.route) { inclusive = true }
                    }
                },
                onCancel = {
                    prefs.clearLastMode()
                    navController.navigate(Screen.Connection.route) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }
        composable(Screen.OfflineLibrary.route) {
            OfflineLibraryScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}

/**
 * Drive the NavController to the destination a restored [NavEntry] lives on,
 * idempotently and to the 2-level shape `[Grid, (Detail|Map)?]`. Source/view-mode
 * are applied separately (in the VM) before this runs, so a GRID target only has
 * to pop any overlay; Detail/Map navigate over Grid unless already showing.
 */
private fun navigateToBrowseRoute(nav: NavController, e: NavEntry) {
    when (e.route) {
        NavRoute.GRID -> nav.popBackStack(Screen.Grid.route, /* inclusive = */ false)
        NavRoute.DETAIL -> {
            val vid = e.videoId ?: return  // malformed entry; nothing to show
            val already = nav.currentDestination?.route == Screen.Detail.route &&
                nav.currentBackStackEntry?.arguments?.getString("videoId") == vid
            if (!already) nav.navigate(Screen.Detail.createRoute(vid)) {
                popUpTo(Screen.Grid.route) { inclusive = false }
                launchSingleTop = true
            }
        }
        NavRoute.MAP -> {
            if (nav.currentDestination?.route != Screen.Map.route) nav.navigate(Screen.Map.route) {
                popUpTo(Screen.Grid.route) { inclusive = false }
                launchSingleTop = true
            }
        }
    }
}
