// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.android.ui.theme

import androidx.compose.ui.graphics.Color
import com.reelvault.data.models.ColorLabel

val ColorLabel.swatch: Color get() = Color(swatchArgb.toInt())
val ColorLabel.dimmed: Color get() = Color(swatchArgb.toInt()).copy(alpha = 0.45f)
val ColorLabel.secondary: Color get() = Color(swatchArgb.toInt()).copy(alpha = 0.72f)
