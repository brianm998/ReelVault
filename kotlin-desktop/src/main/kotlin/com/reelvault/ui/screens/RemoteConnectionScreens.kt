// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.reelvault.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reelvault.data.remote.DiscoveredServer
import com.reelvault.data.remote.ServerChoice
import com.reelvault.util.Strings

/** Shown while the startup scan (mDNS + loopback probe) runs. */
@Composable
fun DiscoveringScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp), strokeWidth = 4.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(20.dp))
            Text(
                "Looking for ReelVault servers…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Scanning Wi-Fi and this computer",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Asked when both a local and a remote daemon (or several remotes) are found:
 * pick which to connect to, optionally as the default for next launch.
 */
@Composable
fun ServerPickerScreen(
    choices: List<ServerChoice>,
    onChoose: (ServerChoice, Boolean) -> Unit,
) {
    var makeDefault by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = 460.dp).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Choose a Library", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "More than one ReelVault server is available.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                for (choice in choices) {
                    Surface(
                        onClick = { onChoose(choice, makeDefault) },
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = if (choice is ServerChoice.Local) Icons.Default.Computer else Icons.Default.Wifi,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(choice.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(
                                    choice.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = makeDefault, onCheckedChange = { makeDefault = it })
                Text("Remember my choice (skip this next time)", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * The desktop is the CLIENT here: it enters the 6-digit code the operator reveals
 * on the server (a connected client's Allow banner, “Pair a New Device”, or the
 * daemon log). Mirrors the iOS / macOS pairing-code entry screen.
 */
@Composable
fun PairingCodeEntryScreen(
    server: DiscoveredServer,
    errorText: String?,
    busy: Boolean,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var code by remember { mutableStateOf(“”) }
    val canSubmit = code.trim().length >= 4 && !busy

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = 440.dp).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                “Pair with ${server.displayName}”,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                “Enter the 6-digit code shown on the server. Reveal it there with “ +
                    “”Pair a New Device”, the daemon log, or `reelvault-core pairing-code`.”,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = code,
                onValueChange = { new -> code = formatPairingCode(new) },
                placeholder = { Text(“000000”) },
                singleLine = true,
                enabled = !busy,
                textStyle = MaterialTheme.typography.headlineSmall.copy(
                    fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center, fontSize = 28.sp,
                ),
                modifier = Modifier.width(180.dp),
            )
            if (errorText != null) {
                Spacer(Modifier.height(10.dp))
                Text(errorText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onCancel, enabled = !busy) { Text(Strings[“ui_cancel”]) }
                Button(onClick = { onSubmit(code) }, enabled = canSubmit) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(Strings[“ui_pair”])
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
        digits.substring(0, 3) + “ “ + digits.substring(3)
    }
}
