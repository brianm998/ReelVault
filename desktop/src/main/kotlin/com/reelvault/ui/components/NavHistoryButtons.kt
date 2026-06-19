// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable

/**
 * Browser-style back/forward chevrons, disabled at the ends of the session
 * navigation history — the Compose-desktop counterpart of iOS's
 * `NavHistoryButtons`. Rendered at the leading edge of the top bar.
 */
@Composable
fun NavHistoryButtons(
    canGoBack: Boolean,
    canGoForward: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
) {
    Tooltip(text = "Back") {
        IconButton(onClick = onBack, enabled = canGoBack) {
            Icon(
                imageVector = Icons.Default.ChevronLeft,
                contentDescription = "Back",
            )
        }
    }
    Tooltip(text = "Forward") {
        IconButton(onClick = onForward, enabled = canGoForward) {
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "Forward",
            )
        }
    }
}
