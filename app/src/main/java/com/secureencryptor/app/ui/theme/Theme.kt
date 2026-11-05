package com.secureencryptor.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// A vault at night: graphite surfaces, one brass accent.

// Surfaces (graphite ramp)
val SurfaceDeep = Color(0xFF0F0F10)      // sunken fields, result readout
val BackgroundDeep = Color(0xFF141415)   // page
val CardSurface = Color(0xFF19191B)      // cards
val SurfaceRaised = Color(0xFF232325)    // active tab, secondary buttons
val InputSurface = SurfaceDeep
val CardBorder = Color(0x14EDEDED)       // hairline, text at 8%
val BorderStrong = Color(0x29EDEDED)     // text at 16%

// Text roles
val TextPrimary = Color(0xFFEDEDED)
val TextSecondary = Color(0xFFA1A1A6)
val TextMuted = Color(0xFF85858B)

// Accent and semantics
val Brass = Color(0xFFD4A24C)
val Positive = Color(0xFF4CC38A)
val Negative = Color(0xFFE5484D)

// One easing curve for everything
val AppEase = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

private val DarkColorScheme = darkColorScheme(
    primary = Brass,
    onPrimary = BackgroundDeep,
    primaryContainer = SurfaceRaised,
    onPrimaryContainer = TextPrimary,
    secondary = TextSecondary,
    onSecondary = BackgroundDeep,
    secondaryContainer = SurfaceRaised,
    onSecondaryContainer = TextPrimary,
    background = BackgroundDeep,
    onBackground = TextPrimary,
    surface = CardSurface,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceDeep,
    onSurfaceVariant = TextSecondary,
    outline = BorderStrong,
    outlineVariant = CardBorder,
    error = Negative
)

@Composable
fun SecureEncryptorTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
