package com.localassistant.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Gruvbox Dark colors (gray background variant, dark mode, Gruvbox accents preserved)
private val GruvboxDarkPrimary = Color(0xFFF2A94C)   // Warm yellow/orange (Gruvbox accent)
private val GruvboxDarkSecondary = Color(0xFFD75D00)  // Deep orange (Gruvbox accent)
private val GruvboxDarkTertiary = Color(0xFFCC8836)   // Golden brown (Gruvbox accent)
private val GruvboxDarkBackground = Color(0xFF2B2B2B) // Neutral dark gray
private val GruvboxDarkSurface = Color(0xFF333333)    // Neutral gray
private val GruvboxDarkSurfaceLow = Color(0xFF2D2D2D) // Slightly darker gray
private val GruvboxDarkSurfaceMed = Color(0xFF3A3A3A) // Surface medium gray
private val GruvboxDarkSurfaceHigh = Color(0xFF444444) // Elevated gray

// Gruvbox Light colors (fallback palette)
private val GruvboxLightPrimary = Color(0xFFB57617)   // Golden brown
private val GruvboxLightSecondary = Color(0xFF8F6300)  // Darker orange
private val GruvboxLightTertiary = Color(0xFFA47D52)   // Warm gray-brown
private val GruvboxLightBackground = Color(0xFFFDF6E3) // Cream/beige
private val GruvboxLightSurface = Color(0xFFFBF1DE)    // Lighter cream

/**
 * Gruvbox Dark color scheme.
 */
val GruvboxDarkColorScheme = darkColorScheme(
    primary = GruvboxDarkPrimary,
    onPrimary = GruvboxDarkBackground,
    primaryContainer = Color(0xFFD48A30),
    onPrimaryContainer = GruvboxDarkBackground,

    secondary = GruvboxDarkSecondary,
    onSecondary = GruvboxDarkBackground,
    secondaryContainer = Color(0xFFB54E00),
    onSecondaryContainer = GruvboxDarkBackground,

    tertiary = GruvboxDarkTertiary,
    onTertiary = GruvboxDarkSurface,
    tertiaryContainer = Color(0xFFA86C1A),
    onTertiaryContainer = GruvboxDarkSurface,

    background = GruvboxDarkBackground,
    onBackground = Color(0xFFE8E8E8),

    surface = GruvboxDarkSurface,
    onSurface = Color(0xFFE8E8E8),  // Light gray for text on dark gray surfaces

    surfaceVariant = GruvboxDarkSurfaceLow,
    onSurfaceVariant = Color(0xFFABABAB),

    error = Color(0xFFCC3D2B),      // Red-orange for errors (Gruvbox style)
    onError = GruvboxDarkBackground,

    surfaceDim = GruvboxDarkSurfaceLow,
    surfaceBright = GruvboxDarkSurfaceMed,
    surfaceContainerLow = GruvboxDarkSurfaceLow,
    surfaceContainer = GruvboxDarkSurfaceMed,
    surfaceContainerHigh = GruvboxDarkSurfaceHigh,
    surfaceContainerHighest = Color(0xFF4F4F4F),
)

/**
 * Gruvbox Light color scheme (fallback).
 */
val GruvboxLightColorScheme = lightColorScheme(
    primary = GruvboxLightPrimary,
    onPrimary = GruvboxDarkSurface,
    primaryContainer = Color(0xFFD7A66E),
    onPrimaryContainer = GruvboxDarkSurface,

    secondary = GruvboxLightSecondary,
    onSecondary = GruvboxLightSurface,
    secondaryContainer = Color(0xFFE8B878),
    onSecondaryContainer = GruvboxDarkSurface,

    tertiary = GruvboxLightTertiary,
    onTertiary = GruvboxLightSurface,
    tertiaryContainer = Color(0xFFD4A673),
    onTertiaryContainer = GruvboxDarkSurface,

    background = GruvboxLightBackground,
    onBackground = GruvboxDarkSurface,

    surface = GruvboxLightSurface,
    onSurface = GruvboxDarkSurface,

    surfaceVariant = Color(0xFFF2E8D5),
    onSurfaceVariant = GruvboxLightTertiary,

    error = Color(0xFFAF3A1B),      // Red-orange for errors (Gruvbox style)
    onError = GruvboxLightSurface,

    surfaceDim = Color(0xFFEFE2CC),
    surfaceBright = GruvboxLightSurface,
    surfaceContainerLow = Color(0xFFF5E9D4),
    surfaceContainer = Color(0xFFF2E8D5),
    surfaceContainerHigh = Color(0xFFECE1CB),
    surfaceContainerHighest = Color(0xFFE8D9C1),
)

/**
 * Custom Gruvbox-themed Material3 theme.
 * Uses Gruvbox dark colors by default with yellow (#F2A94C), orange (#D75D00), and gray (#A89A8C).
 */
@Composable
fun LocalAssistantTheme(
    darkTheme: Boolean = true, // Default to dark theme
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) GruvboxDarkColorScheme else GruvboxLightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
