// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.reelvault.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.reelvault.ui.components.FilenameDateInferenceControls
import com.reelvault.ui.components.Tooltip
import com.reelvault.ui.theme.ReelVaultSpacing
import com.reelvault.util.FilenameDateInference
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import com.reelvault.util.Strings

/**
 * Dialog for setting the capture date/time on one or more videos. Uses
 * Material 3's [DatePicker] for the calendar and [TimePicker] for the
 * hours/minutes, with a checkbox controlling whether the time component is
 * applied (so users who only know the day can leave time at noon UTC).
 *
 * The picked instant is converted to UTC Unix-ms for the RPC — same
 * encoding as everywhere else in the catalog.
 */
@Composable
fun CaptureDateDialog(
    targetVideoIds: List<String>,
    /** Existing capture timestamp (Unix ms, UTC), if any. */
    initialTimestampMs: Long?,
    /** Filename of the single target video, when exactly one is selected — used
     *  to offer filename-based date inference. Null for multi-select (per-file
     *  inference would differ) so the infer controls hide. */
    primaryFilename: String? = null,
    onDismiss: () -> Unit,
    onApply: (timestampMs: Long, writeToFile: Boolean) -> Unit,
) {
    // Local zone for the picker — the user thinks in their wall-clock time;
    // we convert to UTC ms only when committing.
    val zone = remember { ZoneId.systemDefault() }
    val initialLocal = remember(initialTimestampMs) {
        initialTimestampMs
            ?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), zone) }
            ?: LocalDateTime.now(zone).withSecond(0).withNano(0)
    }

    // DatePicker uses UTC-midnight epoch-millis as its internal selected value.
    // Translate the user's local date to that representation for the
    // initial selection.
    val initialUtcMidnight = remember(initialLocal) {
        initialLocal.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    }
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = initialUtcMidnight
    )

    var includeTime by remember { mutableStateOf(initialTimestampMs != null) }
    val timeState = rememberTimePickerState(
        initialHour = initialLocal.hour,
        initialMinute = initialLocal.minute,
        is24Hour = false,
    )
    var writeToFile by remember { mutableStateOf(false) }

    // --- Filename-based capture-date inference -------------------------------
    var showInfer by remember { mutableStateOf(false) }
    var inferFormat by remember { mutableStateOf(FilenameDateInference.defaultFormat()) }
    var inferPosition by remember { mutableStateOf(FilenameDateInference.defaultPosition()) }
    var setInferAsDefault by remember { mutableStateOf(false) }

    // The saved-default method's result for this filename — drives the quick
    // "Set as <date>" button, shown only when a default is configured and it
    // actually parses a date from this filename.
    val defaultInferred: LocalDate? = remember(primaryFilename) {
        if (primaryFilename != null && FilenameDateInference.defaultEnabled()) {
            FilenameDateInference.inferDate(
                primaryFilename,
                FilenameDateInference.defaultFormat(),
                FilenameDateInference.defaultPosition(),
            )
        } else null
    }
    // The current picker's result — the live preview in the infer section.
    val previewInferred: LocalDate? = remember(primaryFilename, inferFormat, inferPosition) {
        primaryFilename?.let { FilenameDateInference.inferDate(it, inferFormat, inferPosition) }
    }

    // Apply a date-only inference at noon local (matching the date-only commit
    // path below), then close.
    fun applyInferredDate(date: LocalDate) {
        val ms = date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        onApply(ms, writeToFile)
    }

    // Compose the selected Unix-ms from the picker components on commit.
    val commit: () -> Unit = commit@{
        val dayUtc = datePickerState.selectedDateMillis ?: return@commit
        val date = Instant.ofEpochMilli(dayUtc).atZone(ZoneOffset.UTC).toLocalDate()
        val ldt = if (includeTime) {
            date.atTime(timeState.hour, timeState.minute)
        } else {
            // No time picked → noon local. Noon avoids "midnight of the wrong
            // day" surprises when the timezone shifts across the date line.
            date.atTime(12, 0)
        }
        val ms = ldt.atZone(zone).toInstant().toEpochMilli()
        onApply(ms, writeToFile)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.CalendarMonth,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(ReelVaultSpacing.Small))
                Text(
                    if (targetVideoIds.size == 1) "Set Capture Date"
                    else "Set Capture Date for ${targetVideoIds.size} Videos"
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "Pick the day the video was recorded. Add a specific " +
                        "time only if you know it — otherwise the day defaults to noon " +
                        "local time so timezone math behaves sensibly.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(ReelVaultSpacing.Small))

                DatePicker(state = datePickerState, modifier = Modifier.fillMaxWidth())

                Spacer(modifier = Modifier.height(ReelVaultSpacing.Small))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = includeTime, onCheckedChange = { includeTime = it })
                    Text(
                        text = "Also set a specific time",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                if (includeTime) {
                    Spacer(modifier = Modifier.height(ReelVaultSpacing.Small))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        TimePicker(state = timeState)
                    }
                }

                Spacer(modifier = Modifier.height(ReelVaultSpacing.Small))

                // Live readout of the composed timestamp so the user can verify.
                val previewMs = datePickerState.selectedDateMillis?.let { dayUtc ->
                    val date = Instant.ofEpochMilli(dayUtc).atZone(ZoneOffset.UTC).toLocalDate()
                    val ldt = if (includeTime) {
                        date.atTime(timeState.hour, timeState.minute)
                    } else date.atTime(12, 0)
                    ldt.atZone(zone).toInstant().toEpochMilli()
                }
                if (previewMs != null) {
                    val fmt = if (includeTime) {
                        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm zzz").withZone(zone)
                    } else {
                        DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(zone)
                    }
                    Text(
                        text = "Will save as: ${fmt.format(Instant.ofEpochMilli(previewMs))}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                Spacer(modifier = Modifier.height(ReelVaultSpacing.Small))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = writeToFile, onCheckedChange = { writeToFile = it })
                    Column {
                        Text(
                            text = "Also embed in video file",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Uses ffmpeg to rewrite the file's creation_time " +
                                "metadata without re-encoding.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // ── Infer the capture date from the filename ───────────────
                // Only for a single target (per-file inference would differ).
                if (primaryFilename != null) {
                    Spacer(modifier = Modifier.height(ReelVaultSpacing.Small))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(modifier = Modifier.height(ReelVaultSpacing.Small))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(ReelVaultSpacing.Small),
                    ) {
                        // Quick-apply the default method — only when a default
                        // is configured AND it matches this filename.
                        if (defaultInferred != null) {
                            Tooltip(text = "Apply $defaultInferred, read from the filename with your default method.") {
                                Button(onClick = { applyInferredDate(defaultInferred) }) {
                                    Text(Strings.format("ui_set_as_default", defaultInferred))
                                }
                            }
                        }
                        Tooltip(text = "Choose how to read a date out of the filename — and optionally make it your default.") {
                            OutlinedButton(onClick = { showInfer = !showInfer }) {
                                Text(if (showInfer) "Hide filename options" else "Infer from filename…")
                            }
                        }
                    }

                    if (showInfer) {
                        Spacer(modifier = Modifier.height(ReelVaultSpacing.Small))
                        Text(
                            text = "Filename: $primaryFilename",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        FilenameDateInferenceControls(
                            format = inferFormat,
                            position = inferPosition,
                            onFormatChange = { inferFormat = it },
                            onPositionChange = { inferPosition = it },
                        )
                        Spacer(modifier = Modifier.height(ReelVaultSpacing.Small))
                        if (previewInferred != null) {
                            Text(
                                text = "Detected: $previewInferred",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            Text(
                                text = "No date matches this pattern in the filename.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = setInferAsDefault,
                                onCheckedChange = { setInferAsDefault = it },
                            )
                            Text(
                                text = "Remember this as my default capture-date method",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        Button(
                            onClick = {
                                if (setInferAsDefault) {
                                    FilenameDateInference.saveDefault(true, inferFormat, inferPosition)
                                }
                                previewInferred?.let { applyInferredDate(it) }
                            },
                            enabled = previewInferred != null,
                        ) { Text(Strings["ui_use_detected_date"]) }
                    }
                }
            }
        },
        confirmButton = {
            val enabled = datePickerState.selectedDateMillis != null
            Tooltip(
                text = if (enabled) {
                    val n = targetVideoIds.size
                    val plural = if (n == 1) "" else "s"
                    "Apply the selected date" + (if (includeTime) "/time" else "") +
                        " to $n video$plural."
                } else "Pick a date first."
            ) {
                Button(onClick = commit, enabled = enabled) { Text(Strings["ui_save"]) }
            }
        },
        dismissButton = {
            Tooltip(text = "Close without changing any capture date.") {
                TextButton(onClick = onDismiss) { Text(Strings["ui_cancel"]) }
            }
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.width(560.dp),
    )
}
