// OpenMinis Desktop — Material 3 theme.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Accent = Color(0xFF5B6CFF)

@Composable
fun MinisTheme(dark: Boolean? = null, content: @Composable () -> Unit) {
    val useDark = dark ?: isSystemInDarkTheme()
    val colors = if (useDark) darkColorScheme(
        primary = Color(0xFF9AA5FF), secondary = Color(0xFFB8C0FF),
        background = Color(0xFF16171B), surface = Color(0xFF1C1D22), surfaceVariant = Color(0xFF26272E),
    ) else lightColorScheme(
        primary = Accent, secondary = Color(0xFF4A55C8),
        background = Color(0xFFFAFAFC), surface = Color.White, surfaceVariant = Color(0xFFF0F1F6),
    )
    MaterialTheme(colorScheme = colors, content = content)
}
