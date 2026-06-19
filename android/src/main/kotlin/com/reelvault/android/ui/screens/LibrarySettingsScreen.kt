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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.reelvault.android.BuildConfig
import com.reelvault.android.R
import com.reelvault.android.data.DefaultServerPrefs
import com.reelvault.data.models.LibraryLocation
import com.reelvault.data.remote.PairingClient
import com.reelvault.data.remote.RemoteConnection
import com.reelvault.data.remote.TokenStorage
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
    pairingClient: PairingClient,
    tokenStorage: TokenStorage,
    onBack: () -> Unit,
    onForget: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    // ── Forget-server confirmation dialog ─────────────────────────────────
    var showForgetConfirm by remember { mutableStateOf(false) }

    if (showForgetConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showForgetConfirm = false },
            title = { Text(stringResource(R.string.settings_forget_server_confirm_title)) },
            text = { Text(stringResource(R.string.settings_forget_server_confirm_body)) },
            confirmButton = {
                Button(
                    onClick = {
                        showForgetConfirm = false
                        scope.launch {
                            // Capture credentials BEFORE clearing them so the
                            // async revoke still has what it needs.
                            val endpoint = RemoteConnection.endpoint
                            val prefs = DefaultServerPrefs(context)
                            val saved = prefs.load()

                            // Best-effort server-side revoke (mirrors iOS forgetCurrentServer).
                            if (endpoint != null) {
                                try {
                                    pairingClient.revoke(
                                        host = endpoint.host,
                                        mediaPort = endpoint.mediaPort,
                                        fingerprintHex = endpoint.fingerprintHex,
                                        token = endpoint.token,
                                    )
                                } catch (_: Exception) { /* best-effort */ }
                            }
                            // Also revoke the saved-prefs credentials if they differ from
                            // the live endpoint (e.g. connection failed but prefs still set).
                            if (saved != null) {
                                val fp = saved.fingerprintHex
                                if (fp != null && fp != endpoint?.fingerprintHex) {
                                    val savedToken = tokenStorage.get(fp)
                                    if (savedToken != null) {
                                        try {
                                            pairingClient.revoke(
                                                host = saved.host,
                                                mediaPort = saved.mediaPort,
                                                fingerprintHex = fp,
                                                token = savedToken,
                                            )
                                        } catch (_: Exception) { /* best-effort */ }
                                    }
                                }
                            }

                            // Clear the live endpoint token from storage.
                            if (endpoint != null) {
                                tokenStorage.clear(endpoint.fingerprintHex)
                            }
                            // Clear any saved-prefs token too.
                            saved?.fingerprintHex?.let { tokenStorage.clear(it) }

                            // Wipe the remembered server so auto-connect won't fire.
                            prefs.clear()

                            // Disconnect the live gRPC channel.
                            repository.disconnect()
                            RemoteConnection.endpoint = null

                            // Navigate to the connection flow.
                            onForget()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(stringResource(R.string.settings_forget_server_confirm_action))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showForgetConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

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
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = stringResource(R.string.settings_back),
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
                    title = stringResource(R.string.settings_library_locations),
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
                                        stringResource(R.string.settings_no_watched_folders),
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
                                                snackbar.showSnackbar(context.getString(R.string.settings_rescan_complete))
                                            } catch (e: Exception) {
                                                snackbar.showSnackbar(
                                                    context.getString(
                                                        R.string.settings_rescan_failed,
                                                        e.message ?: context.getString(R.string.common_unknown_error),
                                                    )
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
                    title = stringResource(R.string.settings_about),
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
                        headlineContent = { Text(stringResource(R.string.settings_app_version)) },
                        supportingContent = { Text(BuildConfig.VERSION_NAME) },
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    // Daemon info — async; shows a spinner until ready.
                    when {
                        daemonLoading -> LoadingRow(label = stringResource(R.string.settings_loading_daemon_info))
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
                    title = stringResource(R.string.settings_connection),
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
                        headlineContent = { Text(stringResource(R.string.settings_server_connection)) },
                        supportingContent = {
                            Text(
                                if (repository.isRemote) {
                                    stringResource(R.string.settings_connected_remote, repository.host, repository.currentPort)
                                } else {
                                    stringResource(R.string.settings_connected_local)
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
                                    onClick = { showForgetConfirm = true },
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
                                    Text(stringResource(R.string.settings_forget_server))
                                }
                                FilledTonalButton(
                                    onClick = {
                                        scope.launch {
                                            val ok = repository.connect()
                                            snackbar.showSnackbar(
                                                if (ok) context.getString(R.string.settings_reconnected)
                                                else context.getString(R.string.settings_reconnect_failed)
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
                                    Text(stringResource(R.string.settings_reconnect))
                                }
                            }
                        },
                    )
                }
            }

            // ── 4. Scan ───────────────────────────────────────────────────
            item(key = "scan_header") {
                SectionCard(
                    title = stringResource(R.string.settings_scan),
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
                                stringResource(R.string.settings_full_rescan),
                                fontWeight = FontWeight.Medium,
                            )
                        },
                        supportingContent = {
                            Text(
                                stringResource(R.string.settings_full_rescan_desc),
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
                                            snackbar.showSnackbar(context.getString(R.string.settings_full_scan_complete))
                                        } catch (e: Exception) {
                                            snackbar.showSnackbar(
                                                context.getString(
                                                    R.string.settings_scan_failed,
                                                    e.message ?: context.getString(R.string.common_unknown_error),
                                                )
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
                                Text(if (scanInProgress) stringResource(R.string.settings_scanning) else stringResource(R.string.settings_start_full_scan))
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
                contentDescription = if (expanded) stringResource(R.string.settings_collapse) else stringResource(R.string.settings_expand),
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
        stringResource(R.string.settings_last_scanned, sdf.format(Date(location.lastScanned)))
    } else {
        stringResource(R.string.settings_never_scanned)
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
            val videoCountInt = location.videoCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val videoCountText = pluralStringResource(
                R.plurals.settings_location_video_count, videoCountInt, videoCountInt,
            )
            val nonRecursiveSuffix = stringResource(R.string.settings_non_recursive)
            val disabledSuffix = stringResource(R.string.settings_disabled)
            Column {
                Text(
                    text = location.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Text(
                    text = buildString {
                        append(videoCountText)
                        if (!location.recursive) append(nonRecursiveSuffix)
                        if (!location.enabled) append(disabledSuffix)
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
                        contentDescription = stringResource(R.string.settings_rescan_path, location.path),
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
        headlineContent = { Text(stringResource(R.string.settings_daemon_version)) },
        supportingContent = {
            Text(version ?: stringResource(R.string.common_unavailable))
        },
    )
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_videos_indexed)) },
        supportingContent = {
            Text(
                when (totalVideos) {
                    null -> stringResource(R.string.common_unavailable)
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
        headlineContent = { Text(stringResource(R.string.settings_cache_size)) },
        supportingContent = {
            Text(
                when {
                    cacheSizeBytes == null -> stringResource(R.string.common_unavailable)
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
        headlineContent = { Text(stringResource(R.string.settings_uptime)) },
        supportingContent = {
            Text(
                when {
                    uptimeSeconds == null -> stringResource(R.string.common_unavailable)
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
private fun LoadingRow(label: String = stringResource(R.string.settings_loading)) {
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
