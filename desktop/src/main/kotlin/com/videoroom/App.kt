@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.videoroom

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.videoroom.data.repository.VideoRepository
import com.videoroom.ui.screens.GridScreen
import com.videoroom.ui.screens.DetailScreen
import com.videoroom.ui.theme.VideoRoomTheme
import com.videoroom.ui.theme.VideoRoomSpacing
import com.videoroom.viewmodel.GridViewModel
import com.videoroom.viewmodel.DetailViewModel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("VideoRoom")

// Window-level shift key tracking. Updated by the Window's key listener and
// read at click time by VideoCard / GridScreen.
val LocalShiftPressed = compositionLocalOf { false }

fun main() = application {
    val windowState = rememberWindowState(
        size = DpSize(width = 1400.dp, height = 900.dp)
    )

    var shiftPressed by remember { mutableStateOf(false) }
    // VideoRoomApp registers its "group selected" action here, so the Window-
    // level key listener can invoke it on Cmd/Ctrl+G regardless of focus.
    val groupSelectedAction = remember { mutableStateOf<() -> Unit>({}) }
    // Same pattern for the Tab key panel-toggle.
    val togglePanelsAction = remember { mutableStateOf<() -> Unit>({}) }

    Window(
        onCloseRequest = ::exitApplication,
        state = windowState,
        title = "VideoRoom - Video Library Manager",
        icon = null, // TODO: Add app icon
        // onPreviewKeyEvent fires BEFORE focused widgets consume the event,
        // so it works even when the search TextField is focused.
        onPreviewKeyEvent = { event ->
            // Track shift state for the rest of the UI.
            if (event.key == Key.ShiftLeft || event.key == Key.ShiftRight) {
                shiftPressed = event.type == KeyEventType.KeyDown
            }
            // Cmd+G (macOS) / Ctrl+G (Windows/Linux) → group selected videos.
            if (event.type == KeyEventType.KeyDown &&
                event.key == Key.G &&
                (event.isMetaPressed || event.isCtrlPressed)
            ) {
                groupSelectedAction.value()
                return@Window true // consume so default shortcuts don't also fire
            }
            // Tab → toggle both side panels (Lightroom-style).
            if (event.type == KeyEventType.KeyDown &&
                event.key == Key.Tab &&
                !event.isMetaPressed && !event.isCtrlPressed && !event.isAltPressed
            ) {
                togglePanelsAction.value()
                return@Window true // consume so focus traversal doesn't also fire
            }
            false
        }
    ) {
        CompositionLocalProvider(LocalShiftPressed provides shiftPressed) {
            VideoRoomApp(
                onRegisterGroupAction = { groupSelectedAction.value = it },
                onRegisterTogglePanelsAction = { togglePanelsAction.value = it }
            )
        }
    }
}

@Composable
fun VideoRoomApp(
    /** Called once to register the "group selected" action for the Cmd/Ctrl+G shortcut. */
    onRegisterGroupAction: (() -> Unit) -> Unit = {},
    /** Called once to register the "toggle panels" action for the Tab shortcut. */
    onRegisterTogglePanelsAction: (() -> Unit) -> Unit = {}
) {
    var isDarkTheme by remember { mutableStateOf(true) }
    val repository = remember { VideoRepository.getInstance() }
    val gridViewModel = remember { GridViewModel(repository) }
    val detailViewModel = remember { DetailViewModel(repository) }

    // Side panel expansion state. Tab toggles both at once (Lightroom-style),
    // and each panel also has its own chevron to collapse/expand individually.
    var leftPanelExpanded by remember { mutableStateOf(true) }
    var rightPanelExpanded by remember { mutableStateOf(true) }

    // Thumbnail size controls the minimum column width for the adaptive grid.
    // Smaller value → more columns when there's space; larger → fewer, bigger cards.
    var thumbnailWidth by remember { mutableStateOf(220.dp) }

    // Register the keyboard shortcut handlers with the Window-level key listener.
    LaunchedEffect(gridViewModel) {
        onRegisterGroupAction { gridViewModel.groupSelectedVideos() }
    }
    LaunchedEffect(Unit) {
        onRegisterTogglePanelsAction {
            // If either is open, close both. If both are closed, open both.
            val anyOpen = leftPanelExpanded || rightPanelExpanded
            leftPanelExpanded = !anyOpen
            rightPanelExpanded = !anyOpen
        }
    }

    val scope = rememberCoroutineScope()
    var connectionState by remember { mutableStateOf(ConnectionState.Connecting) }
    var errorMessage by remember { mutableStateOf("") }

    // Attempt to connect — kept in a separate function so it can be retried.
    fun attemptConnect() {
        scope.launch {
            connectionState = ConnectionState.Connecting
            val connected = repository.connect()
            if (connected) {
                connectionState = ConnectionState.Connected
                gridViewModel.loadVideos()
                gridViewModel.loadLibraryLocations()
            } else {
                errorMessage = "Failed to connect to VideoRoom backend on localhost:50051"
                connectionState = ConnectionState.Failed
            }
        }
    }

    LaunchedEffect(Unit) {
        attemptConnect()
    }

    DisposableEffect(Unit) {
        onDispose {
            gridViewModel.onDestroy()
            detailViewModel.onDestroy()
            repository.disconnect()
        }
    }

    VideoRoomTheme(darkTheme = isDarkTheme) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (connectionState == ConnectionState.Connected) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Top bar
                    val currentSort = gridViewModel.currentSortField.collectAsState()
                    val sortAsc = gridViewModel.currentSortAscending.collectAsState()
                    val selectedIds = gridViewModel.selectedVideoIds.collectAsState()
                    VideoRoomTopBar(
                        isDarkTheme = isDarkTheme,
                        onThemeToggle = { isDarkTheme = !isDarkTheme },
                        onSearch = { gridViewModel.setSearchQuery(it) },
                        onAddLibrary = { path, autoGroup ->
                            gridViewModel.addLibraryAndScan(path, true, autoGroup)
                        },
                        onGroupSelected = { gridViewModel.groupSelectedVideos() },
                        selectedCount = selectedIds.value.size,
                        currentSort = currentSort.value,
                        sortAscending = sortAsc.value,
                        onSortChange = { field, ascending -> gridViewModel.setSort(field, ascending) }
                    )

                    // Scan status banner (during scan)
                    val scanStatus = gridViewModel.scanStatus.collectAsState()
                    if (scanStatus.value != null) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(VideoRoomSpacing.Small),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(VideoRoomSpacing.Small))
                                Text(
                                    text = scanStatus.value ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }

                    // Scan result banner (after scan)
                    val scanResult = gridViewModel.scanResult.collectAsState()
                    scanResult.value?.let { result ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = if (result.success) {
                                if (result.videosFound == 0) {
                                    MaterialTheme.colorScheme.tertiaryContainer
                                } else {
                                    MaterialTheme.colorScheme.primaryContainer
                                }
                            } else {
                                MaterialTheme.colorScheme.errorContainer
                            }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(VideoRoomSpacing.Small),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = when {
                                            !result.success -> Icons.Default.Error
                                            result.videosFound == 0 -> Icons.Default.Warning
                                            else -> Icons.Default.CheckCircle
                                        },
                                        contentDescription = "Status",
                                        modifier = Modifier.size(20.dp),
                                        tint = if (result.success) {
                                            if (result.videosFound == 0) {
                                                MaterialTheme.colorScheme.onTertiaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            }
                                        } else {
                                            MaterialTheme.colorScheme.onErrorContainer
                                        }
                                    )
                                    Spacer(modifier = Modifier.width(VideoRoomSpacing.Small))
                                    Text(
                                        text = result.message,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (result.success) {
                                            if (result.videosFound == 0) {
                                                MaterialTheme.colorScheme.onTertiaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            }
                                        } else {
                                            MaterialTheme.colorScheme.onErrorContainer
                                        }
                                    )
                                }
                                IconButton(
                                    onClick = { gridViewModel.clearScanResult() },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Dismiss",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Main content
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f)
                    ) {
                        val libraryLocations = gridViewModel.libraryLocations.collectAsState()
                        val selectedLocation = gridViewModel.selectedLocationPath.collectAsState()

                        // Library panel — expanded view or collapsed strip
                        if (leftPanelExpanded) {
                            com.videoroom.ui.components.LibraryPanel(
                                locations = libraryLocations.value,
                                selectedPath = selectedLocation.value,
                                totalVideosAcrossLibrary = libraryLocations.value
                                    .sumOf { it.videoCount },
                                onSelect = { path -> gridViewModel.setLocationFilter(path) },
                                onCollapse = { leftPanelExpanded = false },
                                modifier = Modifier
                                    .weight(0.18f)
                                    .fillMaxHeight()
                            )
                        } else {
                            com.videoroom.ui.components.CollapsedPanelStrip(
                                expandIconLeft = false,  // arrow points right (toward expand)
                                tooltip = "Show library panel (Tab)",
                                onClick = { leftPanelExpanded = true },
                                modifier = Modifier.fillMaxHeight()
                            )
                        }

                        Divider(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(1.dp)
                        )

                        // Grid view (middle) — fills remaining space
                        GridScreen(
                            viewModel = gridViewModel,
                            onVideoSelect = { video ->
                                detailViewModel.setCurrentVideo(video)
                                detailViewModel.loadMetadata(video.id)
                            },
                            thumbnailMinWidth = thumbnailWidth,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )

                        Divider(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(1.dp)
                        )

                        // Detail panel — expanded view or collapsed strip
                        if (rightPanelExpanded) {
                            DetailScreen(
                                viewModel = detailViewModel,
                                onCollapse = { rightPanelExpanded = false },
                                thumbnailWidth = thumbnailWidth,
                                onThumbnailWidthChange = { thumbnailWidth = it },
                                modifier = Modifier
                                    .weight(0.18f)
                                    .fillMaxHeight()
                            )
                        } else {
                            com.videoroom.ui.components.CollapsedPanelStrip(
                                expandIconLeft = true,  // arrow points left (toward expand)
                                tooltip = "Show details panel (Tab)",
                                onClick = { rightPanelExpanded = true },
                                modifier = Modifier.fillMaxHeight()
                            )
                        }
                    }
                }
            } else if (connectionState == ConnectionState.Connecting) {
                // Friendly loading screen while we attempt to reach the backend.
                ConnectingScreen()
            } else {
                // Connection failed — error screen with retry.
                ConnectionErrorScreen(
                    errorMessage = errorMessage,
                    onRetry = { attemptConnect() }
                )
            }
        }
    }
}

/** Three-state connection lifecycle for the startup flow. */
private enum class ConnectionState { Connecting, Connected, Failed }

@Composable
fun ConnectingScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp),
                strokeWidth = 4.dp,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(VideoRoomSpacing.Large))
            Text(
                text = "Connecting to VideoRoom…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(VideoRoomSpacing.Small))
            Text(
                text = "Reaching out to the backend on localhost:50051",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun VideoRoomTopBar(
    isDarkTheme: Boolean,
    onThemeToggle: () -> Unit,
    onSearch: (String) -> Unit,
    onAddLibrary: (path: String, autoGroup: Boolean) -> Unit,
    onGroupSelected: () -> Unit = {},
    selectedCount: Int = 0,
    currentSort: String = "indexed_at",
    sortAscending: Boolean = false,
    onSortChange: (String, Boolean) -> Unit = { _, _ -> }
) {
    var searchQuery by remember { mutableStateOf("") }
    var showAddLibraryDialog by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }

    val sortOptions = listOf(
        "filename" to "Filename",
        "indexed_at" to "Date Added",
        "creation_date" to "Date Captured",
        "duration" to "Duration",
        "size" to "File Size",
        "resolution" to "Resolution",
        "fps" to "Frame Rate",
        "codec" to "Codec",
        "bitrate" to "Bitrate",
        "camera" to "Camera",
    )

    TopAppBar(
        title = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = VideoRoomSpacing.Medium),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "VideoRoom",
                    style = MaterialTheme.typography.headlineSmall
                )

                // Search bar - use OutlinedTextField which has a more compact
                // default height that fits inside the TopAppBar without
                // clipping text.
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = {
                        searchQuery = it
                        onSearch(it)
                    },
                    placeholder = {
                        Text(
                            "Search videos...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    modifier = Modifier.width(300.dp),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    leadingIcon = {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = "Search",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    shape = MaterialTheme.shapes.small,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    )
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Sort menu
                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.Sort,
                                contentDescription = "Sort"
                            )
                        }
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false }
                        ) {
                            Text(
                                text = "Sort by",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(
                                    horizontal = VideoRoomSpacing.Medium,
                                    vertical = VideoRoomSpacing.Small
                                )
                            )
                            sortOptions.forEach { (key, label) ->
                                val isSelected = key == currentSort
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = label,
                                                color = if (isSelected) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurface
                                                }
                                            )
                                            if (isSelected) {
                                                Spacer(modifier = Modifier.width(VideoRoomSpacing.Small))
                                                Icon(
                                                    imageVector = if (sortAscending) {
                                                        Icons.Default.ArrowUpward
                                                    } else {
                                                        Icons.Default.ArrowDownward
                                                    },
                                                    contentDescription = if (sortAscending) "Ascending" else "Descending",
                                                    modifier = Modifier.size(16.dp),
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        if (isSelected) {
                                            // Toggle direction on second click
                                            onSortChange(key, !sortAscending)
                                        } else {
                                            // Default to descending for most fields, ascending for filename
                                            onSortChange(key, key == "filename" || key == "camera" || key == "codec")
                                        }
                                        showSortMenu = false
                                    }
                                )
                            }
                        }
                    }

                    // Group Selected button: enabled when 2+ videos are multi-selected.
                    // Shows a small count badge to make the selection visible.
                    Box {
                        IconButton(
                            onClick = onGroupSelected,
                            enabled = selectedCount >= 2
                        ) {
                            Icon(
                                imageVector = Icons.Default.Layers,
                                contentDescription = if (selectedCount >= 2) {
                                    "Group $selectedCount selected videos"
                                } else {
                                    "Shift+click to select videos to group"
                                },
                                tint = if (selectedCount >= 2) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                }
                            )
                        }
                        if (selectedCount > 0) {
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = (-4).dp, y = 4.dp),
                                shape = androidx.compose.foundation.shape.CircleShape,
                                color = MaterialTheme.colorScheme.primary
                            ) {
                                Text(
                                    text = selectedCount.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }

                    // Add Library button
                    IconButton(onClick = { showAddLibraryDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.CreateNewFolder,
                            contentDescription = "Add Library Location"
                        )
                    }

                    // Theme toggle
                    IconButton(onClick = onThemeToggle) {
                        Icon(
                            imageVector = if (isDarkTheme) {
                                Icons.Default.LightMode
                            } else {
                                Icons.Default.DarkMode
                            },
                            contentDescription = "Toggle theme"
                        )
                    }
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface
        )
    )

    if (showAddLibraryDialog) {
        AddLibraryDialog(
            onDismiss = { showAddLibraryDialog = false },
            onConfirm = { path, autoGroup ->
                onAddLibrary(path, autoGroup)
                showAddLibraryDialog = false
            }
        )
    }
}

@Composable
fun AddLibraryDialog(
    onDismiss: () -> Unit,
    onConfirm: (path: String, autoGroup: Boolean) -> Unit
) {
    var path by remember { mutableStateOf("") }
    var autoGroup by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Library Location") },
        text = {
            Column {
                Text(
                    text = "Enter the full path to a directory containing videos:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(VideoRoomSpacing.Small))
                TextField(
                    value = path,
                    onValueChange = { path = it },
                    placeholder = { Text("/Users/you/Videos") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(VideoRoomSpacing.Small))
                Text(
                    text = "The directory will be scanned recursively for video files.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(VideoRoomSpacing.Medium))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = autoGroup,
                        onCheckedChange = { autoGroup = it }
                    )
                    Column {
                        Text(
                            text = "Auto-group similar variants",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Stack videos that share a base name, duration, and frame rate (e.g. multiple resolutions of the same source).",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (path.isNotBlank()) onConfirm(path.trim(), autoGroup) },
                enabled = path.isNotBlank()
            ) {
                Text("Add & Scan")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun ConnectionErrorScreen(
    errorMessage: String,
    onRetry: () -> Unit = {}
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(VideoRoomSpacing.Large)
        ) {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = "Error",
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.error
            )

            Spacer(modifier = Modifier.height(VideoRoomSpacing.Large))

            Text(
                text = "Connection Error",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(VideoRoomSpacing.Medium))

            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(VideoRoomSpacing.Large))

            Text(
                text = "Make sure the VideoRoom backend is running:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = "cd core && cargo run --bin videoroom-core",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(VideoRoomSpacing.Large))

            Button(onClick = onRetry) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(VideoRoomSpacing.Small))
                Text("Retry")
            }
        }
    }
}
