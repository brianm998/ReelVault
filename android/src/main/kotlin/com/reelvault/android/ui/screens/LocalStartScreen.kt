// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.android.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.reelvault.android.R
import com.reelvault.android.core.LocalCore
import com.reelvault.android.data.MediaStoreIngest
import com.reelvault.data.remote.RemoteConnection
import com.reelvault.data.repository.VideoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Boots the embedded Rust core for on-device (Local) mode, connects the
 * repository to its loopback gRPC port, and starts the MediaStore ingest — the
 * Android counterpart of iOS's `AppRouter.startLocal()`. Shows a spinner while
 * starting; on success calls [onStarted] (→ the grid), which then renders the
 * on-device catalog over loopback exactly like a remote library.
 *
 * Requests `READ_MEDIA_VIDEO` (or `READ_EXTERNAL_STORAGE` pre-33) up front — the
 * ingest can't enumerate device videos without it.
 */
@Composable
fun LocalStartScreen(
    repository: VideoRepository,
    onStarted: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_VIDEO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, permission) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var failed by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    // Bump to retry the boot after a failure without changing hasPermission.
    var attempt by remember { mutableIntStateOf(0) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        if (!granted) {
            failed = true
            message = context.getString(R.string.local_permission_needed)
        }
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) launcher.launch(permission)
    }

    LaunchedEffect(hasPermission, attempt) {
        if (!hasPermission) return@LaunchedEffect
        failed = false
        val ok = withContext(Dispatchers.IO) {
            val port = LocalCore.start(context) ?: return@withContext false
            repository.connect(overridePort = port)
        }
        if (ok) {
            // Local mode has no media server; the detail player plays the original
            // content:// URI directly (see VideoDetailScreen.localPlaybackUri).
            RemoteConnection.endpoint = null
            MediaStoreIngest.startObserver(context)
            MediaStoreIngest.kickoff(context)
            onStarted()
        } else {
            failed = true
            message = context.getString(R.string.local_start_failed)
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            if (failed) {
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onCancel) {
                        Text(stringResource(R.string.common_cancel))
                    }
                    Button(onClick = {
                        if (!hasPermission) launcher.launch(permission) else attempt++
                    }) {
                        Text(stringResource(R.string.conn_retry))
                    }
                }
            } else {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.local_starting),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
