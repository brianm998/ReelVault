// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.android.ui.components

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
import com.reelvault.data.remote.RemoteConnection
import com.reelvault.data.repository.VideoRepository
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────────────────────
// Public composable
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Loads and displays a video thumbnail, with a loading spinner and a
 * fallback icon when no image is available.
 *
 * Loading strategy (in priority order):
 *
 * 1. **Remote HTTP endpoint** — when a remote daemon is connected
 *    ([RemoteConnection.endpoint] is non-null), the thumbnail is fetched from
 *    `https://<host>:<mediaPort>/thumbnail/<videoId>?size=<size>&token=<token>`.
 *    This avoids the gRPC streaming call and lets Coil handle caching natively.
 *
 * 2. **gRPC stream** — in local mode (no remote endpoint), [repository] is used
 *    to fetch thumbnail bytes via `GetThumbnail`. The resulting [ByteArray] is
 *    fed directly to Coil as an in-memory model. This path is cached in memory
 *    only; the thumbnails are cheap to re-fetch from the local daemon.
 *
 * The `reelvault://thumbnail/<videoId>` URL scheme used in the grid cards
 * requires a custom Coil fetcher registered globally in the app's
 * [coil.ImageLoader]; callers may continue to use that scheme for the grid,
 * but [ThumbnailImage] resolves images itself so it works without the
 * registered fetcher.
 *
 * @param videoId         Stable video identifier.
 * @param repository      Used only in local mode for the gRPC thumbnail call.
 * @param size            Thumbnail size hint passed to the server ("small",
 *                        "medium", "large"). Defaults to "medium".
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
    val scope = rememberCoroutineScope()

    // ── Resolve the image model ───────────────────────────────────────────

    // Use a sealed-ish Either: null = loading, ByteArray = bytes, String = URL.
    // We hold both possibilities in a single `Any?` to avoid a sealed class.
    var imageModel: Any? by remember(videoId, size) { mutableStateOf(LOADING_SENTINEL) }

    val remoteEndpoint = RemoteConnection.endpoint

    LaunchedEffect(videoId, size, remoteEndpoint) {
        imageModel = LOADING_SENTINEL
        if (remoteEndpoint != null) {
            // Remote mode: construct an HTTPS URL Coil can fetch directly.
            // The daemon serves thumbnails at /thumbnail/<id>?size=<s> and
            // validates the token as a query parameter (avoids an extra header
            // injection step while Coil handles the request).
            imageModel = buildRemoteThumbnailUrl(remoteEndpoint, videoId, size)
        } else {
            // Local mode: gRPC stream -> ByteArray.
            scope.launch {
                imageModel = try {
                    repository.getThumbnailOrNull(videoId, size) ?: NO_THUMBNAIL_SENTINEL
                } catch (_: Exception) {
                    NO_THUMBNAIL_SENTINEL
                }
            }
        }
    }

    // ── Render ────────────────────────────────────────────────────────────

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

            NO_THUMBNAIL_SENTINEL -> {
                // No thumbnail available: show a generic video icon placeholder.
                Icon(
                    imageVector = Icons.Default.VideoFile,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.35f),
                    modifier = Modifier.size(40.dp),
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
                            imageModel = NO_THUMBNAIL_SENTINEL
                        }
                    },
                )
            }

            is String -> {
                // Remote HTTPS URL — Coil fetches and caches it.
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(model as String)
                        .crossfade(true)
                        .build(),
                    contentDescription = contentDescription,
                    contentScale = contentScale,
                    modifier = Modifier.fillMaxSize(),
                    onState = { state ->
                        if (state is AsyncImagePainter.State.Error) {
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

/**
 * Sentinels stored in the `imageModel` state slot to distinguish "still
 * loading" from "definitively no thumbnail". Using object identity avoids
 * collisions with real ByteArray / String values.
 */
private val LOADING_SENTINEL: Any = object {}
private val NO_THUMBNAIL_SENTINEL: Any = object {}

/**
 * Builds the media-server thumbnail URL for a remote connection.
 *
 * The daemon's media server exposes:
 *   GET /thumbnail/<videoId>?size=<size>
 * with `Authorization: Bearer <token>` or `?token=<token>` accepted.
 * We use the query-parameter form so Coil's [okhttp3.OkHttpClient] needs
 * no extra interceptor for thumbnail fetches.
 */
private fun buildRemoteThumbnailUrl(
    endpoint: RemoteConnection.Endpoint,
    videoId: String,
    size: String,
): String {
    val encoded = java.net.URLEncoder.encode(endpoint.token, "UTF-8")
    return "https://${endpoint.host}:${endpoint.mediaPort}/thumbnail/$videoId?size=$size&token=$encoded"
}
