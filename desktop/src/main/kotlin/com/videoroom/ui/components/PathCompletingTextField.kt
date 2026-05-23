// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

package com.videoroom.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.videoroom.LocalPathFieldFocused
import com.videoroom.util.PathCompletion

/**
 * Single-line text input with bash/zsh-style tab completion against the
 * local filesystem. Three behaviors layered together:
 *
 *   * **Tab** completes the partial path. If there's a single match, the
 *     field becomes that path. If multiple matches share a longer common
 *     prefix than what's typed, the field is extended to that prefix and
 *     a dropdown appears listing the remaining candidates.
 *   * **Inline grey ghost** — once the user has typed five characters or
 *     more, the alphabetically-first sibling whose name extends what
 *     they've typed is rendered past the cursor in a placeholder colour,
 *     so the user can see what Tab would do without committing.
 *   * **Dropdown** of every matching sibling whenever there's more than
 *     one. Click a row to select it.
 *
 * Implementation note: we use `BasicTextField` with a custom decoration
 * box so the ghost can sit in the same flow as the real text — letting
 * Compose handle baseline/font alignment rather than measuring strings
 * ourselves. The Tab keystroke is captured via `onPreviewKeyEvent` and
 * consumed before Compose's focus-traversal default takes effect.
 */
@Composable
fun PathCompletingTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
) {
    var matches by remember { mutableStateOf<List<String>>(emptyList()) }
    var ghostSuffix by remember { mutableStateOf("") }
    var dropdownOpen by remember { mutableStateOf(false) }
    var isFocused by remember { mutableStateOf(false) }
    // Window-level flag read by onPreviewKeyEvent to suppress Tab → panel-
    // toggle while this field is active. We write it here rather than having
    // the caller thread a callback through the dialog hierarchy.
    val pathFieldFocused = LocalPathFieldFocused.current

    // Recompute whenever the user's text changes — pure call, no IO
    // beyond a single `File.list()` on the parent directory.
    LaunchedEffect(value) {
        val result = PathCompletion.complete(value)
        matches = result.matches
        val best = result.bestMatch
        ghostSuffix = if (
            best != null
            && value.length >= GHOST_THRESHOLD
            && best.startsWith(value)
            && best.length > value.length
        ) best.substring(value.length) else ""
        // Dropdown auto-opens when there's genuine ambiguity; a unique
        // match collapses it (Tab finishes the job).
        dropdownOpen = matches.size > 1
    }

    val textStyle = LocalTextStyle.current.copy(
        color = MaterialTheme.colorScheme.onSurface,
        fontFamily = FontFamily.Monospace,
    )

    fun handleTab(): Boolean {
        when {
            matches.size == 1 -> {
                onValueChange(matches.first())
                dropdownOpen = false
                ghostSuffix = ""
            }
            matches.size > 1 -> {
                // Bash-style: extend to longest common prefix, then leave
                // the dropdown up for the user to pick from.
                val common = PathCompletion.longestCommonPrefix(matches)
                if (common.length > value.length) {
                    onValueChange(common)
                } else {
                    dropdownOpen = true
                }
            }
            else -> Unit
        }
        return true
    }

    Column(modifier = modifier) {
        // The field itself: BasicTextField with a decoration box that
        // tucks the ghost-suffix Text right after the inner editor. Using
        // BasicTextField (rather than OutlinedTextField/TextField) keeps
        // total control of the layout — Material's wrappers don't expose
        // an "after-cursor overlay" slot.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(
                    width = 1.dp,
                    color = if (isFocused)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.outline,
                    shape = RoundedCornerShape(4.dp),
                )
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = textStyle,
                singleLine = true,
                keyboardOptions = KeyboardOptions.Default,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { state ->
                        isFocused = state.isFocused
                        // Tell the Window-level key listener whether Tab
                        // should complete paths here or toggle panels.
                        pathFieldFocused.value = state.isFocused
                    }
                    .onPreviewKeyEvent { event ->
                        // Capture Tab BEFORE Compose's default focus
                        // traversal gets a chance — without this the
                        // field would lose focus to the next control.
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Tab) {
                            handleTab()
                        } else {
                            false
                        }
                    },
                decorationBox = { innerTextField ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Real-text and placeholder swap. innerTextField
                        // takes only as much width as the actual text
                        // needs; the ghost (or placeholder when empty)
                        // appears right after it.
                        Box {
                            if (value.isEmpty() && placeholder.isNotEmpty()) {
                                Text(
                                    text = placeholder,
                                    style = textStyle.copy(
                                        color = MaterialTheme.colorScheme
                                            .onSurfaceVariant.copy(alpha = 0.6f),
                                    ),
                                )
                            }
                            innerTextField()
                        }
                        if (ghostSuffix.isNotEmpty()) {
                            Text(
                                text = ghostSuffix,
                                style = textStyle.copy(
                                    color = MaterialTheme.colorScheme
                                        .onSurfaceVariant.copy(alpha = 0.55f),
                                ),
                            )
                        }
                    }
                },
            )
        }

        // Dropdown — opens beneath the field when there's more than one
        // candidate. Using a plain LazyColumn within a bordered Box (not
        // DropdownMenu) keeps focus on the text field so the user can
        // continue typing to narrow the list without the menu eating
        // keystrokes.
        if (dropdownOpen && matches.size > 1) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp)
                    .heightIn(max = 160.dp)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline,
                        shape = RoundedCornerShape(4.dp),
                    )
                    .clip(RoundedCornerShape(4.dp))
            ) {
                LazyColumn {
                    items(matches) { match ->
                        DropdownRow(
                            display = PathCompletion.displayName(match),
                            onClick = {
                                onValueChange(match)
                                dropdownOpen = false
                                ghostSuffix = ""
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DropdownRow(display: String, onClick: () -> Unit) {
    // Compose's interaction sources are the idiomatic way to combine
    // hover styling with click handling without writing our own gesture
    // detector. We don't take focus from the text field above — the
    // user can keep typing to narrow the list while the menu is up.
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .background(
                if (hovered) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else MaterialTheme.colorScheme.surface
            )
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = display,
            style = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
        )
    }
}

/** Minimum total characters before the ghost is shown. Below this the
 *  suggestions are too ambiguous to bother painting visually. */
private const val GHOST_THRESHOLD = 5
