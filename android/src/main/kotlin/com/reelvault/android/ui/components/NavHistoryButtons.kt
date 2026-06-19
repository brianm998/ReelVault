// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.android.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.reelvault.android.R

/**
 * Browser-style back/forward chevrons, disabled at the ends of the session
 * navigation history — the Android counterpart of iOS `NavHistoryButtons`.
 * Rendered at the start of the grid top-bar action row.
 */
@Composable
fun NavHistoryButtons(
    canGoBack: Boolean,
    canGoForward: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
) {
    IconButton(onClick = onBack, enabled = canGoBack) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.nav_history_back),
        )
    }
    IconButton(onClick = onForward, enabled = canGoForward) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = stringResource(R.string.nav_history_forward),
        )
    }
}
