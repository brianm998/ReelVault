// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.reelvault.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reelvault.data.repository.VideoRepository
import kotlinx.coroutines.delay
import com.reelvault.util.Strings

/**
 * "Pair a New Device" dialog. Asks the daemon (over the loopback connection) to
 * mint a one-time 6-digit code and displays it for the operator to type on a
 * remote device (the iOS app), which redeems it at the daemon's pairing
 * endpoint for a long-lived token.
 */
@Composable
fun PairDeviceDialog(
    repository: VideoRepository,
    onDismiss: () -> Unit,
) {
    var loading by remember { mutableStateOf(true) }
    var pairing by remember { mutableStateOf<VideoRepository.PairingCode?>(null) }
    var failed by remember { mutableStateOf(false) }
    var reloadTick by remember { mutableStateOf(0) }

    LaunchedEffect(reloadTick) {
        loading = true
        failed = false
        pairing = null
        val result = repository.startPairing()
        if (result == null) {
            failed = true
        } else {
            pairing = result
        }
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.PhoneIphone,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Pair a New Device", fontWeight = FontWeight.SemiBold)
            }
        },
        text = {
            Column(
                modifier = Modifier.width(380.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when {
                    loading -> {
                        Spacer(Modifier.height(8.dp))
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.height(8.dp))
                        Text("Generating code…", style = MaterialTheme.typography.bodySmall)
                    }
                    failed -> {
                        Text(
                            "Couldn't generate a pairing code. Make sure a catalog is open " +
                                "and the daemon is running.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                    else -> pairing?.let { code ->
                        Text(
                            "On your iPhone or iPad, open ReelVault, choose this computer's " +
                                "server, then enter:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = spacedCode(code.code),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 40.sp,
                        )
                        Spacer(Modifier.height(8.dp))
                        CountdownText(expiresAtMs = code.expiresAtMs)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(Strings["ui_done"]) }
        },
        dismissButton = {
            if (failed) {
                TextButton(onClick = { reloadTick++ }) { Text(Strings["ui_try_again"]) }
            }
        },
    )
}

/** "123456" → "123 456" for legibility. */
private fun spacedCode(code: String): String =
    if (code.length == 6) "${code.substring(0, 3)} ${code.substring(3)}" else code

/** Live "Expires in m:ss" label driven off the code's wall-clock expiry. */
@Composable
private fun CountdownText(expiresAtMs: Long) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(expiresAtMs) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val remaining = ((expiresAtMs - now) / 1000).toInt()
    if (remaining > 0) {
        Text(
            "Expires in ${remaining / 60}:${(remaining % 60).toString().padStart(2, '0')}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Text(
            "Code expired — close and try again.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
