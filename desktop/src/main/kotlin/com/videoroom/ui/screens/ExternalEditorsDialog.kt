@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.videoroom.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.videoroom.data.editors.EditorCatalog
import com.videoroom.data.editors.EditorConfig
import com.videoroom.data.editors.EditorLicense
import com.videoroom.data.editors.EditorRegistry
import com.videoroom.data.editors.ExternalEditor
import com.videoroom.data.editors.HostPlatform
import com.videoroom.data.editors.editorsForCurrentPlatform
import com.videoroom.ui.components.Tooltip
import com.videoroom.ui.theme.VideoRoomSpacing

/**
 * "External Editors" preferences dialog. Lists every supported editor available
 * on the current platform, surfacing license status, install state, custom
 * path override, and links to the vendor homepage.
 *
 * Operates directly on the shared [EditorRegistry] — changes are persisted as
 * the user makes them, so there's no separate "Save" step. The dialog
 * re-detects installation status each time the user opens it.
 */
@Composable
fun ExternalEditorsDialog(
    onDismiss: () -> Unit,
    registry: EditorRegistry = EditorRegistry.Default,
) {
    val host = remember { HostPlatform.detect() }
    val editors = remember { editorsForCurrentPlatform() }

    // Bump this to force the row state to recompute (e.g. after a config save).
    var refreshKey by remember { mutableStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Build,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(VideoRoomSpacing.Small))
                Text("External Editors")
            }
        },
        text = {
            Column(modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 540.dp)
            ) {
                Text(
                    text = "Pick which video editors VideoRoom can hand off to. " +
                        "Enabled + installed editors show up in the right-click " +
                        "menu on every video card.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(VideoRoomSpacing.Small))
                Text(
                    text = "Running on ${host.displayName()}. " +
                        "${editors.size} editor${if (editors.size == 1) "" else "s"} " +
                        "shipped for this platform.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(VideoRoomSpacing.Medium))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    editors.forEachIndexed { i, editor ->
                        if (i > 0) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        // Re-keying on refreshKey makes the row re-read state
                        // from the registry whenever we tap one of the actions.
                        key(refreshKey, editor.id) {
                            EditorRow(
                                editor = editor,
                                registry = registry,
                                onChanged = { refreshKey++ }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Tooltip(text = "Close the preferences dialog. Changes are saved automatically.") {
                Button(onClick = onDismiss) { Text("Done") }
            }
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.width(640.dp)
    )
}

@Composable
private fun EditorRow(
    editor: ExternalEditor,
    registry: EditorRegistry,
    onChanged: () -> Unit,
) {
    val cfg = remember(editor.id) { registry.configFor(editor.id) }
    val resolvedPath = remember(editor.id) { registry.resolvePath(editor) }
    val isInstalled = resolvedPath != null

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = VideoRoomSpacing.Small)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = editor.name,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(VideoRoomSpacing.Small))
                    LicenseBadge(editor.license)
                    Spacer(modifier = Modifier.width(VideoRoomSpacing.Small))
                    InstallBadge(isInstalled)
                }
                if (editor.notes.isNotEmpty()) {
                    Text(
                        text = editor.notes,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (resolvedPath != null) {
                    Text(
                        text = resolvedPath,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            // Enable / disable toggle
            Tooltip(
                text = if (cfg.enabled) {
                    "Disabled editors don't appear in the right-click \"Open with\" menu."
                } else {
                    "Enable this editor to make it available in the right-click menu."
                }
            ) {
                Switch(
                    checked = cfg.enabled,
                    onCheckedChange = {
                        registry.update(editor.id, cfg.copy(enabled = it))
                        onChanged()
                    }
                )
            }
        }

        // Action row: Browse for custom path / Get installer / Reset path
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = VideoRoomSpacing.XSmall),
            horizontalArrangement = Arrangement.spacedBy(VideoRoomSpacing.Small),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Tooltip(
                text = "Choose the exact executable or .app bundle if VideoRoom " +
                    "couldn't find ${editor.name} automatically."
            ) {
                OutlinedButton(
                    onClick = {
                        val picked = pickEditorFile(editor)
                        if (!picked.isNullOrBlank()) {
                            registry.update(editor.id, cfg.copy(customPath = picked))
                            onChanged()
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Browse…", style = MaterialTheme.typography.labelSmall)
                }
            }

            if (cfg.customPath.isNotEmpty()) {
                Tooltip(text = "Forget the custom path and go back to auto-detecting from VideoRoom's defaults.") {
                    TextButton(
                        onClick = {
                            registry.update(editor.id, cfg.copy(customPath = ""))
                            onChanged()
                        }
                    ) {
                        Text("Reset", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Tooltip(text = "Open ${editor.name}'s download page in your default browser.") {
                TextButton(onClick = { EditorRegistry.Default.openHomepage(editor) }) {
                    Icon(
                        imageVector = Icons.Default.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        if (isInstalled) "Visit site" else "Get installer",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

@Composable
private fun LicenseBadge(license: EditorLicense) {
    val (bg, fg) = when (license) {
        EditorLicense.FreeOpenSource ->
            MaterialTheme.colorScheme.tertiaryContainer to
                MaterialTheme.colorScheme.onTertiaryContainer
        EditorLicense.Paid ->
            MaterialTheme.colorScheme.errorContainer to
                MaterialTheme.colorScheme.onErrorContainer
        EditorLicense.FreeAndPaid ->
            MaterialTheme.colorScheme.secondaryContainer to
                MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(color = bg, shape = MaterialTheme.shapes.small) {
        Text(
            text = license.display,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
        )
    }
}

@Composable
private fun InstallBadge(isInstalled: Boolean) {
    val (bg, fg, label) = if (isInstalled) {
        Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            "Installed"
        )
    } else {
        Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            "Not installed"
        )
    }
    Surface(color = bg, shape = MaterialTheme.shapes.small) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
        )
    }
}

private fun HostPlatform.displayName(): String = when (this) {
    HostPlatform.MacOS -> "macOS"
    HostPlatform.Windows -> "Windows"
    HostPlatform.Linux -> "Linux"
    HostPlatform.Unknown -> "this platform"
}

/**
 * Native file picker for choosing an editor's executable / app bundle.
 * Uses AWT's [java.awt.FileDialog] which falls back to a sensible native
 * picker on each platform.
 */
private fun pickEditorFile(editor: ExternalEditor): String? {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Choose ${editor.name}", java.awt.FileDialog.LOAD)
    dialog.isVisible = true
    val dir = dialog.directory ?: return null
    val file = dialog.file ?: return null
    return java.io.File(dir, file).absolutePath
}
