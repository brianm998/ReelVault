package com.videoroom.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.videoroom.data.models.VideoSummary
import com.videoroom.ui.components.VideoCard
import com.videoroom.ui.theme.VideoRoomSpacing
import com.videoroom.viewmodel.GridViewModel

@Composable
fun GridScreen(
    viewModel: GridViewModel,
    onVideoSelect: (VideoSummary) -> Unit,
    modifier: Modifier = Modifier
) {
    val videos = viewModel.videos.collectAsState()
    val selectedVideoId = viewModel.selectedVideoId.collectAsState()
    val isLoading = viewModel.isLoading.collectAsState()
    val hasMore = viewModel.hasMore.collectAsState()
    val totalCount = viewModel.totalCount.collectAsState()
    val error = viewModel.error.collectAsState()
    val thumbnails = viewModel.thumbnails.collectAsState()

    Column(modifier = modifier.fillMaxSize()) {
        // Status bar
        if (error.value != null) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(VideoRoomSpacing.Small),
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.small
            ) {
                Row(
                    modifier = Modifier
                        .padding(VideoRoomSpacing.Medium)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Error: ${error.value}",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall
                    )
                    IconButton(
                        onClick = { viewModel.clearError() },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Dismiss")
                    }
                }
            }
        }

        // Video count
        Text(
            text = "Videos: ${videos.value.size}${if (totalCount.value > 0) " / ${totalCount.value}" else ""}",
            modifier = Modifier.padding(VideoRoomSpacing.Medium),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        // Grid
        Box(modifier = Modifier.fillMaxSize()) {
            if (videos.value.isEmpty() && !isLoading.value) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .wrapContentSize(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = "No videos",
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(VideoRoomSpacing.Medium))
                    Text(
                        text = "No videos found",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(VideoRoomSpacing.Small),
                    horizontalArrangement = Arrangement.spacedBy(VideoRoomSpacing.Small),
                    verticalArrangement = Arrangement.spacedBy(VideoRoomSpacing.Small)
                ) {
                    items(
                        count = videos.value.size,
                        key = { index -> videos.value[index].id }
                    ) { index ->
                        val video = videos.value[index]
                        val isSelected = selectedVideoId.value == video.id

                        // Trigger thumbnail load when card appears
                        LaunchedEffect(video.id) {
                            if (video.hasThumbnail) {
                                viewModel.loadThumbnail(video.id)
                            }
                        }

                        VideoCard(
                            video = video,
                            isSelected = isSelected,
                            thumbnailBytes = thumbnails.value[video.id],
                            onClick = { onVideoSelect(video) },
                            onDoubleClick = { viewModel.openVideoInExternal(video.path) },
                            modifier = Modifier.fillMaxWidth()
                        )

                        // Load more when near the end
                        if (index == videos.value.size - 5 && hasMore.value) {
                            LaunchedEffect(Unit) {
                                viewModel.loadMore()
                            }
                        }
                    }

                    // Loading indicator at the end
                    if (isLoading.value && hasMore.value) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(100.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                }
            }

            // Loading overlay
            if (isLoading.value && videos.value.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}
