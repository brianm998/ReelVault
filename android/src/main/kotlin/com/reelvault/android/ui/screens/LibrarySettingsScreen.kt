// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.reelvault.android.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.reelvault.android.BuildConfig
import com.reelvault.data.models.LibraryLocation
import com.reelvault.data.repository.VideoRepository
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen settings / library management screen for Android.
 *
 * Matches the feature set of the iOS library management and settings dialogs
 * presented as a single scrollable screen:
 *
 *   1. Library Locations — watched folders with video count, last-scan
 *      timestamp, and a per-location rescan button.
 *   2. About — app version (from [BuildConfig.VERSION_NAME]) and daemon
 *      info (version, uptime, total videos, cache size) fetched via
 *      [VideoRepository].
 *   3. Connection — forget the current server and reconnect.
 *   4. Scan — trigger a full rescan of all library locations.
 *
 * Each section is individually collapsible. All async operations are
 * launched in [rememberCoroutineScope] so they survive recomposition;
 * results and errors are surfaced via a [SnackbarHost].
 */
@Composable
fun LibrarySettingsScreen(
    repository: VideoRepository,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // ── Section expand/collapse state ─────────────────────────────────────
    var locationsExpanded by remember { mutableStateOf(true) }
    var aboutExpanded by remember { mutableStateOf(true) }
    var connectionExpanded by remember { mutableStateOf(true) }
    var scanExpanded by remember { mutableStateOf(true) }

    // ── Library locations ─────────────────────────────────────────────────
    var locations by remember { mutableStateOf<List<LibraryLocation>>(emptyList()) }
    var locationsLoading by remember { mutableStateOf(true) }

    // ── Daemon info ───────────────────────────────────────────────────────
    var daemonVersion by remember { mutableStateOf<String?>(null) }
    var daemonUptime by remember { mutableStateOf<Long?>(null) }
    var daemonTotalVideos by remember { mutableStateOf<Long?>(null) }
    var daemonCacheSizeBytes by remember { mutableStateOf<Long?>(null) }
    var daemonLoading by remember { mutableStateOf(true) }

    // ── Scan state ────────────────────────────────────────────────────────
    var scanInProgress by remember { mutableStateOf(false) }

    // ── Rescan-per-location tracking ──────────────────────────────────────
    var rescanningPaths by remember { mutableStateOf<Set<String>>(emptySet()) }

    // ── Initial data load ─────────────────────────────────────────────────
    LaunchedEffect(Unit) {
        // Library locations
        try {
            locations = repository.listLibraryLocations()
        } catch (_: Exception) {
            /* non-fatal; list stays empty */
        } finally {
            locationsLoading = false
        }
        // Daemon status
        daemonLoading = true
        try {
            val status = repository.getDaemonStatus()
            if (status != null) {
                daemonVersion = status.version.ifBlank { null }
                daemonUptime = status.uptimeSeconds
                daemonTotalVideos = status.totalVideos
                daemonCacheSizeBytes = status.cacheSizeBytes
            }
        } catch (_: Exception) {
            /* leave null — UI shows "unavailable" */
        } finally {
            daemonLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = "Back",
                            modifier = Modifier.size(28.dp),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        LazyColumn(
            contentPadding = innerPadding,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── 1. Library Locations ──────────────────────────────────────
            item(key = "locations_header") {
                Spacer(modifier = Modifier.height(4.dp))
                SectionCard(
                    title = "Library Locations",
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    expanded = locationsExpanded,
                    onToggle = { locationsExpanded = !locationsExpanded },
                ) {
                    when {
                        locationsLoading -> LoadingRow()
                        locations.isEmpty() -> {
                            ListItem(
                                headlineContent = {
                                    Text(
                                        "No watched folders",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                            )
                        }
                        else -> {
                            locations.forEachIndexed { index, location ->
                                if (index > 0) HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                )
                                val isRescanning = location.path in rescanningPaths
                                LibraryLocationRow(
                                    location = location,
                                    isRescanning = isRescanning,
                                    onRescan = {
                                        scope.launch {
                                            rescanningPaths = rescanningPaths + location.path
                                            try {
                                                repository.scanLibrary(
                                                    locationPath = location.path,
                                                ).collect { /* consume progress */ }
                                                locations = repository.listLibraryLocations()
                                                snackbar.showSnackbar("Rescan complete")
                                            } catch (e: Exception) {
                                                snackbar.showSnackbar(
                                                    "Rescan failed: ${e.message ?: "unknown error"}"
                                                )
                                            } finally {
                                                rescanningPaths = rescanningPaths - location.path
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // ── 2. About ─────────────────────────────────────────────────
            item(key = "about_header") {
                SectionCard(
                    title = "About",
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    expanded = aboutExpanded,
                    onToggle = { aboutExpanded = !aboutExpanded },
                ) {
                    // App version — always available from BuildConfig.
                    ListItem(
                        headlineContent = { Text("App Version") },
                        supportingContent = { Text(BuildConfig.VERSION_NAME) },
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    // Daemon info — async; shows a spinner until ready.
                    when {
                        daemonLoading -> LoadingRow(label = "Loading daemon info…")
                        else -> {
                            DaemonInfoRow(
                                version = daemonVersion,
                                uptimeSeconds = daemonUptime,
                                totalVideos = daemonTotalVideos,
                                cacheSizeBytes = daemonCacheSizeBytes,
                            )
                        }
                    }
                }
            }

            // ── 3. Connection ─────────────────────────────────────────────
            item(key = "connection_header") {
                SectionCard(
                    title = "Connection",
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.WifiOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    expanded = connectionExpanded,
                    onToggle = { connectionExpanded = !connectionExpanded },
                ) {
                    ListItem(
                        headlineContent = { Text("Server connection") },
                        supportingContent = {
                            Text(
                                if (repository.isRemote) {
                                    "Connected to remote daemon at ${repository.host}:${repository.currentPort}"
                                } else {
                                    "Connected to local daemon"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    ListItem(
                        headlineContent = {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            repository.disconnect()
                                            snackbar.showSnackbar("Disconnected from server")
                                        }
                                    },
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error,
                                    ),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CloudOff,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Forget Server")
                                }
                                FilledTonalButton(
                                    onClick = {
                                        scope.launch {
                                            val ok = repository.connect()
                                            snackbar.showSnackbar(
                                                if (ok) "Reconnected successfully"
                                                else "Reconnect failed"
                                            )
                                        }
                                    },
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Reconnect")
                                }
                            }
                        },
                    )
                }
            }

            // ── 4. Scan ───────────────────────────────────────────────────
            item(key = "scan_header") {
                SectionCard(
                    title = "Scan",
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    expanded = scanExpanded,
                    onToggle = { scanExpanded = !scanExpanded },
                ) {
                    ListItem(
                        headlineContent = {
                            Text(
                                "Full rescan",
                                fontWeight = FontWeight.Medium,
                            )
                        },
                        supportingContent = {
                            Text(
                                "Re-index all watched folders, extract metadata and generate " +
                                    "thumbnails for any new or changed videos.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailingContent = {
                            if (scanInProgress) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            }
                        },
                    )
                    ListItem(
                        headlineContent = {
                            Button(
                                onClick = {
                                    if (scanInProgress) return@Button
                                    scope.launch {
                                        scanInProgress = true
                                        try {
                                            repository.scanLibrary().collect { /* consume */ }
                                            locations = repository.listLibraryLocations()
                                            snackbar.showSnackbar("Full scan complete")
                                        } catch (e: Exception) {
                                            snackbar.showSnackbar(
                                                "Scan failed: ${e.message ?: "unknown error"}"
                                            )
                                        } finally {
                                            scanInProgress = false
                                        }
                                    }
                                },
                                enabled = !scanInProgress,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (scanInProgress) "Scanning…" else "Start Full Scan")
                            }
                        },
                    )
                }
            }

            item(key = "bottom_spacer") {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Section card helper
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A [Card] with a clickable header row that expands/collapses [content].
 * The header shows a [leadingIcon], the section [title], and a chevron
 * indicating the expand state.
 */
@Composable
private fun SectionCard(
    title: String,
    leadingIcon: @Composable () -> Unit,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        // Header row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leadingIcon()
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        // Collapsible content
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                content()
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Library location row
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun LibraryLocationRow(
    location: LibraryLocation,
    isRescanning: Boolean,
    onRescan: () -> Unit,
) {
    val lastScannedText = if (location.lastScanned > 0L) {
        val sdf = remember { SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()) }
        "Last scanned: ${sdf.format(Date(location.lastScanned))}"
    } else {
        "Never scanned"
    }

    ListItem(
        headlineContent = {
            Text(
                text = location.path.substringAfterLast('/').ifBlank { location.path },
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        },
        supportingContent = {
            Column {
                Text(
                    text = location.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Text(
                    text = buildString {
                        append("${location.videoCount} video${if (location.videoCount == 1L) "" else "s"}")
                        if (!location.recursive) append(" (non-recursive)")
                        if (!location.enabled) append(" (disabled)")
                        append(" • ")
                        append(lastScannedText)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        leadingContent = {
            Icon(
                imageVector = if (location.enabled) Icons.Default.Folder else Icons.Default.SyncProblem,
                contentDescription = null,
                tint = if (location.enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.size(24.dp),
            )
        },
        trailingContent = {
            if (isRescanning) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            } else {
                IconButton(onClick = onRescan) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Rescan ${location.path}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Daemon info rows
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun DaemonInfoRow(
    version: String?,
    uptimeSeconds: Long?,
    totalVideos: Long?,
    cacheSizeBytes: Long?,
) {
    ListItem(
        headlineContent = { Text("Daemon Version") },
        supportingContent = {
            Text(version ?: "Unavailable")
        },
    )
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
    ListItem(
        headlineContent = { Text("Videos Indexed") },
        supportingContent = {
            Text(
                when (totalVideos) {
                    null -> "Unavailable"
                    else -> "%,d".format(totalVideos)
                }
            )
        },
    )
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
    ListItem(
        headlineContent = { Text("Cache Size") },
        supportingContent = {
            Text(
                when {
                    cacheSizeBytes == null -> "Unavailable"
                    cacheSizeBytes < 1024L -> "${cacheSizeBytes} B"
                    cacheSizeBytes < 1024L * 1024L -> "%.1f KB".format(cacheSizeBytes / 1024.0)
                    cacheSizeBytes < 1024L * 1024L * 1024L -> "%.1f MB".format(
                        cacheSizeBytes / (1024.0 * 1024.0)
                    )
                    else -> "%.2f GB".format(cacheSizeBytes / (1024.0 * 1024.0 * 1024.0))
                }
            )
        },
    )
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
    ListItem(
        headlineContent = { Text("Uptime") },
        supportingContent = {
            Text(
                when {
                    uptimeSeconds == null -> "Unavailable"
                    uptimeSeconds < 60L -> "${uptimeSeconds}s"
                    uptimeSeconds < 3600L -> "${uptimeSeconds / 60}m ${uptimeSeconds % 60}s"
                    else -> {
                        val h = uptimeSeconds / 3600
                        val m = (uptimeSeconds % 3600) / 60
                        "${h}h ${m}m"
                    }
                }
            )
        },
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Loading indicator row
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun LoadingRow(label: String = "Loading…") {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
