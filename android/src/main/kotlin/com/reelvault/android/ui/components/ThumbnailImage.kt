// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.android.ui.components

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import com.reelvault.data.repository.VideoRepository

// ─────────────────────────────────────────────────────────────────────────────
// Public composable
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Loads and displays a video thumbnail via gRPC ([VideoRepository.getThumbnailOrNull]),
 * showing a spinner while loading and a film-reel icon when none is available.
 *
 * Works identically in local mode (loopback daemon) and remote mode (LAN daemon)
 * because [VideoRepository] holds the connected gRPC channel regardless of which
 * mode is active. There is no HTTP `/thumbnail` endpoint on the media server —
 * gRPC is the only thumbnail transport.
 *
 * @param videoId         Stable video identifier.
 * @param repository      Provides the gRPC thumbnail call.
 * @param size            Size hint passed to the server ("small", "medium", "large").
 * @param contentScale    How the image fills its bounds.
 * @param contentDescription Accessibility description.
 * @param modifier        Layout modifier applied to the outer [Box].
 */
@Composable
fun ThumbnailImage(
    videoId: String,
    repository: VideoRepository,
    size: String = "medium",
    contentScale: ContentScale = ContentScale.Crop,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    var imageModel: Any? by remember(videoId, size) { mutableStateOf(LOADING_SENTINEL) }

    LaunchedEffect(videoId, size) {
        imageModel = LOADING_SENTINEL
        imageModel = try {
            repository.getThumbnailOrNull(videoId, size) ?: NO_THUMBNAIL_SENTINEL
        } catch (e: Exception) {
            Log.e("ThumbnailImage", "gRPC thumbnail failed for $videoId", e)
            NO_THUMBNAIL_SENTINEL
        }
    }

    Box(
        modifier = modifier.background(Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        when (val model = imageModel) {
            LOADING_SENTINEL -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = Color.White.copy(alpha = 0.6f),
                    strokeWidth = 2.dp,
                )
            }

            is ByteArray -> {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(model)
                        .crossfade(true)
                        .build(),
                    contentDescription = contentDescription,
                    contentScale = contentScale,
                    modifier = Modifier.fillMaxSize(),
                    onState = { state ->
                        if (state is AsyncImagePainter.State.Error) {
                            Log.e("ThumbnailImage", "Coil error for $videoId", state.result.throwable)
                            imageModel = NO_THUMBNAIL_SENTINEL
                        }
                    },
                )
            }

            else -> {
                Icon(
                    imageVector = Icons.Default.VideoFile,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.35f),
                    modifier = Modifier.size(40.dp),
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

private val LOADING_SENTINEL: Any = object {}
private val NO_THUMBNAIL_SENTINEL: Any = object {}
