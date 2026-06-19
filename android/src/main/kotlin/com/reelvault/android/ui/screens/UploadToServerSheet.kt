// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.reelvault.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reelvault.android.R
import com.reelvault.android.data.UploadEndpoint
import com.reelvault.android.data.UploadManager
import com.reelvault.android.data.materializeLocalVideo
import com.reelvault.data.models.VideoSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Uploads the selected on-device videos to the last-paired server, with per-file
 * progress — the Android mirror of iOS's `UploadToServerSheet`. Each video is
 * materialized to a temp file, uploaded sequentially (so N large videos don't all
 * hit temp disk at once), then the temp is deleted. Non-`photos://` videos are
 * counted as skipped (v1 supports MediaStore-sourced videos only).
 */
@Composable
fun UploadToServerSheet(
    endpoint: UploadEndpoint,
    videos: List<VideoSummary>,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val uploader = remember(endpoint) { UploadManager(endpoint) }
    val jobs by uploader.jobs.collectAsStateWithLifecycle()
    var preparing by remember { mutableStateOf(true) }
    var skipped by remember { mutableIntStateOf(0) }
    val tempFiles = remember { mutableListOf<File>() }

    LaunchedEffect(Unit) {
        for (v in videos) {
            val tmp = withContext(Dispatchers.IO) {
                materializeLocalVideo(context, v.path, v.filename)
            }
            if (tmp == null) {
                skipped++
                continue
            }
            tempFiles.add(tmp)
            uploader.upload(tmp, v.filename) // sequential
            tmp.delete()
            tempFiles.remove(tmp)
        }
        preparing = false
    }
    DisposableEffect(Unit) {
        onDispose { tempFiles.forEach { it.delete() } }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.upload_title, endpoint.serverName),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(12.dp))

            LazyColumn {
                items(jobs, key = { it.id }) { job ->
                    UploadRow(job)
                }
            }

            if (preparing) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.upload_preparing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (skipped > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.upload_skipped, skipped),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(16.dp))
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.upload_close))
            }
        }
    }
}

@Composable
private fun UploadRow(job: UploadManager.Job) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = job.filename,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.height(0.dp))
            when (job.status) {
                UploadManager.Status.Finished -> Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = stringResource(R.string.upload_done),
                    tint = Color(0xFF2E7D32),
                )
                UploadManager.Status.Failed -> Icon(
                    Icons.Default.ErrorOutline,
                    contentDescription = stringResource(R.string.upload_failed),
                    tint = MaterialTheme.colorScheme.error,
                )
                UploadManager.Status.Uploading -> Text(
                    "${(job.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (job.status == UploadManager.Status.Uploading) {
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { job.progress },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
