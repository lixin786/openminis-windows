// OpenMinis Desktop — Material 3 theme: light/dark/system, accent palette, UI + code fonts, text size.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.SystemFont
import androidx.compose.ui.unit.Density
import com.openminis.desktop.core.AppSettings

/** "Success" green for tool cards (Material 3 has no success role). */
val LocalSuccessColor = staticCompositionLocalOf { Color(0xFF2E7D32) }

/** Font for code, tool arguments/results and command lines (Settings › Appearance › Code font). */
val LocalMonoFont = staticCompositionLocalOf<FontFamily> { FontFamily.Monospace }

/** A family installed on this computer, by name; blank = [fallback]. Unknown names fall back inside Skia. */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
fun systemFamily(name: String, fallback: FontFamily): FontFamily =
    if (name.isBlank()) fallback else FontFamily(
        SystemFont(name, FontWeight.Normal), SystemFont(name, FontWeight.Medium),
        SystemFont(name, FontWeight.SemiBold), SystemFont(name, FontWeight.Bold),
    )

private fun TextStyle.f(ff: FontFamily) = copy(fontFamily = ff)

private fun typographyWith(ff: FontFamily?): Typography {
    val t = Typography()
    if (ff == null) return t
    return Typography(
        displayLarge = t.displayLarge.f(ff), displayMedium = t.displayMedium.f(ff), displaySmall = t.displaySmall.f(ff),
        headlineLarge = t.headlineLarge.f(ff), headlineMedium = t.headlineMedium.f(ff), headlineSmall = t.headlineSmall.f(ff),
        titleLarge = t.titleLarge.f(ff), titleMedium = t.titleMedium.f(ff), titleSmall = t.titleSmall.f(ff),
        bodyLarge = t.bodyLarge.f(ff), bodyMedium = t.bodyMedium.f(ff), bodySmall = t.bodySmall.f(ff),
        labelLarge = t.labelLarge.f(ff), labelMedium = t.labelMedium.f(ff), labelSmall = t.labelSmall.f(ff),
    )
}

@Composable
fun MinisTheme(settings: AppSettings, content: @Composable () -> Unit) =
    MinisTheme(settings.darkTheme, settings.accent, settings.fontScale, settings.uiFont, settings.monoFont, content)

@Composable
fun MinisTheme(
    dark: Boolean? = null, accent: String = "indigo", fontScale: Float = 1f, uiFont: String = "", monoFont: String = "",
    content: @Composable () -> Unit,
) {
    val useDark = dark ?: isSystemInDarkTheme()
    val a = Appearance.Accent.of(accent)
    val colors = if (useDark) darkColorScheme(
        primary = Color(a.dark), secondary = Color(a.darkSecondary),
        background = Color(0xFF16171B), surface = Color(0xFF1C1D22), surfaceVariant = Color(0xFF26272E),
    ) else lightColorScheme(
        primary = Color(a.light), secondary = Color(a.lightSecondary),
        background = Color(0xFFFAFAFC), surface = Color.White, surfaceVariant = Color(0xFFF0F1F6),
    )
    val typography = remember(uiFont) { typographyWith(uiFont.takeIf { it.isNotBlank() }?.let { systemFamily(it, FontFamily.Default) }) }
    val mono = remember(monoFont) { systemFamily(monoFont, FontFamily.Monospace) }
    val base = LocalDensity.current
    val scale = Appearance.clampScale(fontScale)
    CompositionLocalProvider(
        LocalDensity provides Density(base.density, base.fontScale * scale),
        LocalMonoFont provides mono,
        LocalSuccessColor provides if (useDark) Color(0xFF81C784) else Color(0xFF2E7D32),
    ) {
        MaterialTheme(colorScheme = colors, typography = typography, content = content)
    }
}
