// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.reelvault.android.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.reelvault.android.R
import androidx.compose.ui.unit.sp
import com.reelvault.android.data.AndroidServerDiscovery
import com.reelvault.android.data.DefaultServerPrefs
import com.reelvault.data.remote.DiscoveredServer
import com.reelvault.data.remote.PairingClient
import com.reelvault.data.remote.RemoteConnection
import com.reelvault.data.remote.TokenStorage
import com.reelvault.data.repository.VideoRepository
import kotlinx.coroutines.launch

// ── State machine ─────────────────────────────────────────────────────────────

private sealed class ConnectionState {
    /** NSD scan in progress, no servers found yet. */
    object Discovering : ConnectionState()

    /**
     * One or more servers visible. [servers] are the live mDNS results.
     * The user may also choose to enter an address manually.
     */
    data class ChooseServer(val servers: List<DiscoveredServer>) : ConnectionState()

    /**
     * A server was chosen and requires a pairing PIN (it has a fingerprint
     * and no stored token). The user enters the 6-digit code here.
     */
    data class EnterPin(val server: DiscoveredServer) : ConnectionState()

    /**
     * Manual entry: the user is typing a host + port directly.
     * Optionally a fingerprint has already been fetched from the daemon
     * (the "Fetch fingerprint" button populates it).
     */
    object ManualEntry : ConnectionState()

    /** A connection or pairing call is in progress. */
    data class Connecting(val message: String) : ConnectionState()

    /** Something went wrong. User can retry or enter manually. */
    data class Error(val message: String) : ConnectionState()
}

// ── Screen ────────────────────────────────────────────────────────────────────

/**
 * Discovery and pairing flow: scans the LAN for ReelVault daemons via NSD,
 * shows a list of found servers plus a "Enter address manually" fallback,
 * handles the PIN pairing handshake when required, and calls [onConnected]
 * once the repository is wired up.
 *
 * Mirrors the iOS `DiscoveryErrorView` / `ServerPickerView` / `PairingCodeView`
 * trio (ConnectionFlowViews.swift) and the desktop arbitration logic (App.kt).
 */
@Composable
fun ConnectionFlowScreen(
    repository: VideoRepository,
    pairingClient: PairingClient,
    tokenStorage: TokenStorage,
    onConnected: () -> Unit,
    onBrowseLocalMedia: () -> Unit = {},
    onBrowseOfflineLibrary: () -> Unit = {},
) {
    // We need a Context to build the NSD-backed discovery — pull it from the
    // Compose LocalContext rather than threading it through as a parameter.
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    // Mutable snapshot of the discovered-server list (added/removed by NSD).
    val discovered = remember { mutableStateListOf<DiscoveredServer>() }

    // Load saved credentials synchronously on first composition so the initial
    // state is correct from the very first frame — no flash of "Discovering"
    // when we have a stored server and token.
    val initialSaved: Pair<DefaultServerPrefs.Entry, String>? = remember {
        val entry = DefaultServerPrefs(context).load() ?: return@remember null
        val fp = entry.fingerprintHex ?: return@remember null
        val token = tokenStorage.get(fp) ?: return@remember null
        entry to token
    }

    var state by remember {
        mutableStateOf<ConnectionState>(
            if (initialSaved != null)
                ConnectionState.Connecting(
                    context.getString(R.string.conn_reconnecting_to, initialSaved.first.host)
                )
            else
                ConnectionState.Discovering
        )
    }
    // True while we are attempting a saved-credential auto-connect; NSD callbacks
    // skip state transitions until we finish so they don't interrupt the attempt.
    var autoConnecting by remember { mutableStateOf(initialSaved != null) }

    // ── Auto-connect with saved credentials ───────────────────────────────────

    LaunchedEffect(Unit) {
        val (saved, token) = initialSaved ?: return@LaunchedEffect
        val fp = saved.fingerprintHex ?: return@LaunchedEffect
        RemoteConnection.endpoint = RemoteConnection.Endpoint(
            host = saved.host,
            mediaPort = saved.mediaPort,
            fingerprintHex = fp,
            token = token,
        )
        val ok = repository.connectRemote(saved.host, saved.grpcPort, fp, token)
        if (ok) {
            onConnected()
            return@LaunchedEffect
        }
        // Auto-connect failed; fall through to discovery.
        RemoteConnection.endpoint = null
        autoConnecting = false
        // If the user has offline downloads, skip the NSD timeout and show the
        // server list (with "Browse Downloads" button) immediately — the server
        // is unreachable and waiting 4 s for NSD to time out just delays the
        // offline option. NSD keeps running in the background; if it finds a
        // server the ChooseServer view updates automatically.
        state = if (com.reelvault.android.data.OfflineLibrary.entries.value.isNotEmpty())
            ConnectionState.ChooseServer(discovered.toList())
        else
            ConnectionState.Discovering
    }

    // ── NSD discovery ─────────────────────────────────────────────────────────

    DisposableEffect(Unit) {
        val discovery = AndroidServerDiscovery(context)
        discovery.start(
            onFound = { server ->
                if (autoConnecting) return@start
                if (discovered.none { it.id == server.id }) {
                    discovered.add(server)
                }
                if (state is ConnectionState.Discovering) {
                    state = ConnectionState.ChooseServer(discovered.toList())
                } else if (state is ConnectionState.ChooseServer) {
                    state = ConnectionState.ChooseServer(discovered.toList())
                }
            },
            onLost = { name ->
                discovered.removeAll { it.name == name }
                if (!autoConnecting && state is ConnectionState.ChooseServer) {
                    state = ConnectionState.ChooseServer(discovered.toList())
                }
            },
        )
        // After a short window, if nothing was found show the empty picker
        // so the user can enter a server manually.
        scope.launch {
            kotlinx.coroutines.delay(4_000)
            if (!autoConnecting && state is ConnectionState.Discovering) {
                state = ConnectionState.ChooseServer(emptyList())
            }
        }
        onDispose { discovery.stop() }
    }

    // ── Connect helper ────────────────────────────────────────────────────────

    /** Connect to a remote daemon that has already been paired (token known). */
    fun connectRemote(server: DiscoveredServer, token: String) {
        state = ConnectionState.Connecting(context.getString(R.string.conn_connecting_to, server.displayName))
        scope.launch {
            RemoteConnection.endpoint = RemoteConnection.Endpoint(
                host = server.host,
                mediaPort = server.mediaPort,
                fingerprintHex = server.fingerprintHex ?: "",
                token = token,
            )
            val ok = repository.connectRemote(
                host = server.host,
                grpcPort = server.grpcPort,
                fingerprintHex = server.fingerprintHex ?: "",
                token = token,
            )
            if (ok) {
                DefaultServerPrefs(context).save(server)
                onConnected()
            } else {
                RemoteConnection.endpoint = null
                state = ConnectionState.Error(
                    context.getString(R.string.conn_could_not_connect_named, server.displayName)
                )
            }
        }
    }

    /** Handle a server card tap: check token cache, pair if needed, or connect. */
    fun onServerChosen(server: DiscoveredServer) {
        val fp = server.fingerprintHex
        if (fp == null) {
            // No TLS fingerprint means a plaintext loopback daemon — connect directly.
            state = ConnectionState.Connecting(context.getString(R.string.conn_connecting))
            scope.launch {
                val ok = repository.connect()
                if (ok) {
                    DefaultServerPrefs(context).save(server)
                    onConnected()
                } else {
                    state = ConnectionState.Error(context.getString(R.string.conn_could_not_connect_simple, server.displayName))
                }
            }
            return
        }
        val cachedToken = tokenStorage.get(fp)
        if (cachedToken != null) {
            connectRemote(server, cachedToken)
        } else {
            // Notify the server a new device wants to pair (pops Allow banner on
            // the operator's connected client) then ask for the PIN.
            scope.launch {
                pairingClient.requestPairing(server.host, server.mediaPort, fp)
            }
            state = ConnectionState.EnterPin(server)
        }
    }

    /** Redeem a PIN and, on success, cache the token and connect. */
    fun onSubmitPin(server: DiscoveredServer, pin: String) {
        val fp = server.fingerprintHex ?: return
        state = ConnectionState.Connecting(context.getString(R.string.conn_pairing_with, server.displayName))
        scope.launch {
            val token = pairingClient.pair(server.host, server.mediaPort, fp, pin)
            if (token == null) {
                state = ConnectionState.Error(
                    context.getString(R.string.conn_pairing_rejected)
                )
            } else {
                tokenStorage.set(fp, token)
                connectRemote(server, token)
            }
        }
    }

    // ── Routing ───────────────────────────────────────────────────────────────

    when (val s = state) {
        ConnectionState.Discovering -> DiscoveringView()

        is ConnectionState.ChooseServer -> ChooseServerView(
            servers = s.servers,
            onServerChosen = ::onServerChosen,
            onEnterManually = { state = ConnectionState.ManualEntry },
            onRetry = {
                discovered.clear()
                state = ConnectionState.Discovering
            },
            onBrowseLocalMedia = onBrowseLocalMedia,
            onBrowseOfflineLibrary = onBrowseOfflineLibrary,
        )

        is ConnectionState.EnterPin -> EnterPinView(
            server = s.server,
            onSubmit = { pin -> onSubmitPin(s.server, pin) },
            onCancel = { state = ConnectionState.ChooseServer(discovered.toList()) },
        )

        ConnectionState.ManualEntry -> ManualEntryView(
            pairingClient = pairingClient,
            onConnect = { host, port, fp ->
                val manual = DiscoveredServer(
                    name = host,
                    host = host,
                    grpcPort = port,
                    mediaPort = port + 1,
                    fingerprintHex = fp.ifBlank { null },
                    catalogName = null,
                    requiresPairing = fp.isNotBlank(),
                )
                onServerChosen(manual)
            },
            onCancel = { state = ConnectionState.ChooseServer(discovered.toList()) },
        )

        is ConnectionState.Connecting -> ConnectingView(message = s.message)

        is ConnectionState.Error -> ErrorView(
            message = s.message,
            onRetry = { state = ConnectionState.ChooseServer(discovered.toList()) },
            onEnterManually = { state = ConnectionState.ManualEntry },
        )
    }
}

// ── Sub-views ─────────────────────────────────────────────────────────────────

/** Spinner shown while the initial NSD scan runs. */
@Composable
private fun DiscoveringView() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(20.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(32.dp),
                strokeWidth = 3.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.conn_looking_for_servers),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.conn_scanning_network),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** List of discovered servers plus a manual-entry option. */
@Composable
private fun ChooseServerView(
    servers: List<DiscoveredServer>,
    onServerChosen: (DiscoveredServer) -> Unit,
    onEnterManually: () -> Unit,
    onRetry: () -> Unit,
    onBrowseLocalMedia: () -> Unit = {},
    onBrowseOfflineLibrary: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.conn_connect_title)) },
                actions = {
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.conn_rescan)) }
                },
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            if (servers.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.WifiOff,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    stringResource(R.string.conn_no_servers_found),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    stringResource(R.string.conn_no_servers_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            } else {
                item {
                    Text(
                        stringResource(R.string.conn_available_servers),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
                items(servers, key = { it.id }) { server ->
                    ServerCard(server = server, onClick = { onServerChosen(server) })
                }
            }

            item {
                if (servers.isNotEmpty()) Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = onEnterManually,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.conn_enter_address_manually))
                }
            }

            item {
                OutlinedButton(
                    onClick = onBrowseLocalMedia,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        Icons.Default.Smartphone,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.conn_browse_local_videos))
                }
            }

            item {
                OutlinedButton(
                    onClick = onBrowseOfflineLibrary,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        Icons.Default.DownloadForOffline,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.offline_browse_downloads))
                }
            }
        }
    }
}

/** A single discovered-server list row. */
@Composable
private fun ServerCard(server: DiscoveredServer, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (server.requiresPairing) Icons.Default.Lock else Icons.Default.Wifi,
                contentDescription = null,
                tint = if (server.requiresPairing)
                    MaterialTheme.colorScheme.tertiary
                else
                    MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    server.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "${server.host}:${server.grpcPort}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (server.requiresPairing) {
                    Text(
                        stringResource(R.string.conn_requires_pairing),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
    }
}

/** 6-digit PIN entry screen shown when a server requires pairing. */
@Composable
private fun EnterPinView(
    server: DiscoveredServer,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    val canSubmit = pin.trim().length >= 4

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.conn_enter_pairing_code)) },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.conn_pair_with, server.displayName),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.conn_pairing_instructions),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = pin,
                onValueChange = { new -> pin = formatPairingCode(new) },
                placeholder = { Text(stringResource(R.string.conn_pin_placeholder), fontFamily = FontFamily.Monospace) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.NumberPassword,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onDone = { if (canSubmit) onSubmit(pin.trim()) }
                ),
                textStyle = MaterialTheme.typography.headlineMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    fontSize = 28.sp,
                ),
                modifier = Modifier.width(180.dp),
            )
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
                Button(
                    onClick = { onSubmit(pin.trim()) },
                    enabled = canSubmit,
                ) {
                    Text(stringResource(R.string.conn_pair_button))
                }
            }
        }
    }
}

private fun formatPairingCode(input: String): String {
    val digits = input.filter { it.isDigit() }.take(6)
    return if (digits.length <= 3) {
        digits
    } else {
        digits.substring(0, 3) + " " + digits.substring(3)
    }
}

/** Manual host / port / fingerprint entry form. */
@Composable
private fun ManualEntryView(
    pairingClient: PairingClient,
    onConnect: (host: String, port: Int, fingerprintHex: String) -> Unit,
    onCancel: () -> Unit,
) {
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("50051") }
    var fingerprint by remember { mutableStateOf("") }
    var fetchingFp by remember { mutableStateOf(false) }
    var fpError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    val portInt = port.toIntOrNull() ?: 50051
    val canConnect = host.isNotBlank()
    val mediaPort = portInt + 1  // Convention: media port = gRPC port + 1

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.conn_connect_manually)) },
                navigationIcon = {
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = host,
                onValueChange = {
                    host = it
                    fingerprint = ""
                    fpError = null
                },
                label = { Text(stringResource(R.string.conn_host_label)) },
                placeholder = { Text(stringResource(R.string.conn_host_placeholder)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
                label = { Text(stringResource(R.string.conn_grpc_port_label)) },
                placeholder = { Text(stringResource(R.string.conn_grpc_port_placeholder)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            // Fingerprint row: text field + Fetch button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = fingerprint,
                    onValueChange = {
                        fingerprint = it
                        fpError = null
                    },
                    label = { Text(stringResource(R.string.conn_fingerprint_label)) },
                    placeholder = { Text(stringResource(R.string.conn_fingerprint_placeholder)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Done,
                    ),
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    modifier = Modifier.weight(1f),
                    enabled = !fetchingFp,
                )

                // "Fetch" button: TOFU-reads the fingerprint from the daemon's
                // HTTPS media server without pinning (first-time only).
                FilledTonalButton(
                    onClick = {
                        fetchingFp = true
                        fpError = null
                        scope.launch {
                            val fetched = pairingClient.fetchFingerprint(
                                host.trim(), mediaPort
                            )
                            fetchingFp = false
                            if (fetched != null) {
                                fingerprint = fetched
                            } else {
                                fpError = context.getString(R.string.conn_could_not_reach, host.trim(), mediaPort)
                            }
                        }
                    },
                    enabled = host.isNotBlank() && !fetchingFp,
                ) {
                    if (fetchingFp) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(stringResource(R.string.conn_fetch))
                    }
                }
            }

            if (fpError != null) {
                Text(
                    fpError!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(4.dp))

            Button(
                onClick = {
                    onConnect(host.trim(), portInt, fingerprint.trim().lowercase())
                },
                enabled = canConnect,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.conn_connect_button))
            }
        }
    }
}

/** Spinner shown while a connection or pairing call is in flight. */
@Composable
private fun ConnectingView(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp),
                strokeWidth = 4.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(20.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** Error screen with retry and manual-entry escapes. */
@Composable
private fun ErrorView(
    message: String,
    onRetry: () -> Unit,
    onEnterManually: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .widthIn(max = 400.dp)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Default.WifiOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.conn_retry)) }
                Button(onClick = onEnterManually) { Text(stringResource(R.string.conn_enter_address)) }
            }
        }
    }
}
