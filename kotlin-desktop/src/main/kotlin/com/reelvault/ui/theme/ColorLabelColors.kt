// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.ui.theme

import androidx.compose.ui.graphics.Color
import com.reelvault.data.models.ColorLabel

/** Compose Color from the packed ARGB long stored on [ColorLabel]. */
val ColorLabel.swatch: Color get() = Color(swatchArgb)
/** Dimmed variant (45 % opacity) for unselected cards that carry this label. */
val ColorLabel.dimmed: Color get() = Color(swatchArgb).copy(alpha = 0.45f)
/** Mid-brightness variant (72 % opacity) for secondary-selected cards. */
val ColorLabel.secondary: Color get() = Color(swatchArgb).copy(alpha = 0.72f)
