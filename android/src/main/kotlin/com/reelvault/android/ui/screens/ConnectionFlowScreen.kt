// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.reelvault.android.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
) {
    // We need a Context to build the NSD-backed discovery — pull it from the
    // Compose LocalContext rather than threading it through as a parameter.
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    // Mutable snapshot of the discovered-server list (added/removed by NSD).
    val discovered = remember { mutableStateListOf<DiscoveredServer>() }

    var state by remember { mutableStateOf<ConnectionState>(ConnectionState.Discovering) }

    // ── NSD discovery ─────────────────────────────────────────────────────────

    DisposableEffect(Unit) {
        val discovery = AndroidServerDiscovery(context)
        discovery.start(
            onFound = { server ->
                if (discovered.none { it.id == server.id }) {
                    discovered.add(server)
                }
                // If we are still on the Discovering screen, flip to ChooseServer
                // as soon as the first result arrives.
                if (state is ConnectionState.Discovering) {
                    state = ConnectionState.ChooseServer(discovered.toList())
                } else if (state is ConnectionState.ChooseServer) {
                    state = ConnectionState.ChooseServer(discovered.toList())
                }
            },
            onLost = { name ->
                discovered.removeAll { it.name == name }
                if (state is ConnectionState.ChooseServer) {
                    state = ConnectionState.ChooseServer(discovered.toList())
                }
            },
        )
        // After a short window, if nothing was found show the empty picker
        // so the user can enter a server manually.
        scope.launch {
            kotlinx.coroutines.delay(4_000)
            if (state is ConnectionState.Discovering) {
                state = ConnectionState.ChooseServer(emptyList())
            }
        }
        onDispose { discovery.stop() }
    }

    // ── Connect helper ────────────────────────────────────────────────────────

    /** Connect to a remote daemon that has already been paired (token known). */
    fun connectRemote(server: DiscoveredServer, token: String) {
        state = ConnectionState.Connecting("Connecting to ${server.displayName}…")
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
                    "Could not connect to ${server.displayName}. " +
                        "Check that the daemon is running and try again."
                )
            }
        }
    }

    /** Handle a server card tap: check token cache, pair if needed, or connect. */
    fun onServerChosen(server: DiscoveredServer) {
        val fp = server.fingerprintHex
        if (fp == null) {
            // No TLS fingerprint means a plaintext loopback daemon — connect directly.
            state = ConnectionState.Connecting("Connecting…")
            scope.launch {
                val ok = repository.connect()
                if (ok) {
                    DefaultServerPrefs(context).save(server)
                    onConnected()
                } else {
                    state = ConnectionState.Error("Could not connect to ${server.displayName}.")
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
        state = ConnectionState.Connecting("Pairing with ${server.displayName}…")
        scope.launch {
            val token = pairingClient.pair(server.host, server.mediaPort, fp, pin)
            if (token == null) {
                state = ConnectionState.Error(
                    "Pairing rejected — check the code and try again."
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
                "Looking for ReelVault servers…",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Scanning the local network",
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
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connect to ReelVault") },
                actions = {
                    TextButton(onClick = onRetry) { Text("Rescan") }
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
                                    "No servers found",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    "Start a ReelVault core daemon with --remote on a " +
                                        "computer on this Wi-Fi network, then rescan.",
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
                        "Available servers",
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
                    Text("Enter address manually")
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
                        "Requires pairing",
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
                title = { Text("Enter Pairing Code") },
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
                "Pair with ${server.displayName}",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "On the computer running ReelVault, choose File ▸ Pair a New Device " +
                    "to show a 6-digit code (a headless server also logs it). " +
                    "Enter it below — you only do this once.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = pin,
                onValueChange = { new -> pin = new.filter { it.isDigit() }.take(6) },
                placeholder = { Text("000000", fontFamily = FontFamily.Monospace) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.NumberPassword,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onDone = { if (canSubmit) onSubmit(pin) }
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
                TextButton(onClick = onCancel) { Text("Cancel") }
                Button(
                    onClick = { onSubmit(pin.trim()) },
                    enabled = canSubmit,
                ) {
                    Text("Pair")
                }
            }
        }
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

    val portInt = port.toIntOrNull() ?: 50051
    val canConnect = host.isNotBlank()
    val mediaPort = portInt + 1  // Convention: media port = gRPC port + 1

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connect Manually") },
                navigationIcon = {
                    TextButton(onClick = onCancel) { Text("Cancel") }
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
                label = { Text("Host or IP address") },
                placeholder = { Text("192.168.1.100") },
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
                label = { Text("gRPC port") },
                placeholder = { Text("50051") },
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
                    label = { Text("Cert fingerprint (SHA-256, optional)") },
                    placeholder = { Text("abcd1234…") },
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
                                fpError = "Could not reach ${host.trim()}:$mediaPort — " +
                                    "check host and that the daemon is running."
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
                        Text("Fetch")
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
                Text("Connect")
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
                OutlinedButton(onClick = onRetry) { Text("Retry") }
                Button(onClick = onEnterManually) { Text("Enter address") }
            }
        }
    }
}
