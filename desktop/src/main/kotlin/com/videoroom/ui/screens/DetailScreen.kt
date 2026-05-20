package com.videoroom.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.videoroom.ui.theme.VideoRoomSpacing
import com.videoroom.viewmodel.DetailViewModel

@Composable
fun DetailScreen(
    viewModel: DetailViewModel,
    modifier: Modifier = Modifier
) {
    val metadata = viewModel.metadata
    val isLoading = viewModel.isLoading.collectAsState()
    val error = viewModel.error.collectAsState()
    val notes = viewModel.notes.collectAsState()

    Box(modifier = modifier.fillMaxSize()) {
        if (metadata.value == null && !isLoading.value) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(VideoRoomSpacing.Medium),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PermMedia,
                    contentDescription = "No video selected",
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.outline
                )
                Spacer(modifier = Modifier.height(VideoRoomSpacing.Medium))
                Text(
                    text = "Select a video to view details",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else if (metadata.value != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(VideoRoomSpacing.Medium)
            ) {
                // Error message
                if (error.value != null) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = VideoRoomSpacing.Small),
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = error.value!!,
                            modifier = Modifier.padding(VideoRoomSpacing.Small),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                // Filename
                Text(
                    text = metadata.value!!.filename,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(VideoRoomSpacing.Small))

                // Open in external app button
                Button(
                    onClick = {
                        val path = metadata.value!!.path
                        try {
                            val file = java.io.File(path)
                            if (file.exists()) {
                                java.awt.Desktop.getDesktop().open(file)
                            }
                        } catch (e: Exception) {
                            // Ignore
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayCircle,
                        contentDescription = "Open",
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(VideoRoomSpacing.Small))
                    Text("Open in External App")
                }

                Spacer(modifier = Modifier.height(VideoRoomSpacing.Medium))

                // Technical metadata
                MetadataItem("Resolution", metadata.value!!.resolution)
                MetadataItem("Duration", metadata.value!!.durationFormatted)
                MetadataItem("FPS", "%.2f".format(metadata.value!!.fps))
                MetadataItem("Video Codec", metadata.value!!.codecVideo.ifEmpty { "—" })
                if (metadata.value!!.codecAudio.isNotEmpty()) {
                    MetadataItem("Audio Codec", metadata.value!!.codecAudio)
                }
                MetadataItem("Bitrate", metadata.value!!.bitrateFormatted)
                MetadataItem("Size", metadata.value!!.sizeFormatted)

                if (metadata.value!!.colorSpace.isNotEmpty()) {
                    MetadataItem("Color Space", metadata.value!!.colorSpace)
                }

                if (metadata.value!!.hdr) {
                    MetadataItem("HDR", "Yes")
                }

                // EXIF / Camera section
                val hasExif = metadata.value!!.cameraModel.isNotEmpty() ||
                              metadata.value!!.lensModel.isNotEmpty() ||
                              metadata.value!!.gpsLatitude != 0.0 ||
                              metadata.value!!.creationDate > 0
                if (hasExif) {
                    Spacer(modifier = Modifier.height(VideoRoomSpacing.Medium))
                    Text(
                        text = "EXIF",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (metadata.value!!.cameraModel.isNotEmpty()) {
                        MetadataItem("Camera", metadata.value!!.cameraModel)
                    }
                    if (metadata.value!!.lensModel.isNotEmpty()) {
                        MetadataItem("Lens", metadata.value!!.lensModel)
                    }
                    if (metadata.value!!.creationDate > 0) {
                        MetadataItem("Captured", metadata.value!!.creationDateFormatted)
                    }
                    if (metadata.value!!.gpsLatitude != 0.0 || metadata.value!!.gpsLongitude != 0.0) {
                        MetadataItem(
                            "GPS",
                            "${"%.4f".format(metadata.value!!.gpsLatitude)}, ${"%.4f".format(metadata.value!!.gpsLongitude)}"
                        )
                    }
                }

                Spacer(modifier = Modifier.height(VideoRoomSpacing.Large))

                // Notes section
                Text(
                    text = "Notes",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextField(
                    value = notes.value,
                    onValueChange = { viewModel.updateNotes(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp),
                    placeholder = { Text("Add notes...") },
                    colors = TextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )

                Spacer(modifier = Modifier.height(VideoRoomSpacing.Large))

                // Tags section
                if (metadata.value!!.tags.isNotEmpty()) {
                    Text(
                        text = "Tags",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(VideoRoomSpacing.Small))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(VideoRoomSpacing.Small)
                    ) {
                        metadata.value!!.tags.forEach { tag ->
                            AssistChip(
                                onClick = { },
                                label = { Text(tag) },
                                trailingIcon = {
                                    Icon(Icons.Default.Close, contentDescription = "Remove tag")
                                }
                            )
                        }
                    }
                }
            }
        } else {
            // Loading
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
    }
}

@Composable
fun MetadataItem(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = VideoRoomSpacing.Small)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun FlowRow(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable () -> Unit
) {
    // Simplified flow row - just use horizontal layout with wrapping
    Row(
        modifier = modifier,
        horizontalArrangement = horizontalArrangement,
        verticalAlignment = Alignment.CenterVertically
    ) {
        content()
    }
}
