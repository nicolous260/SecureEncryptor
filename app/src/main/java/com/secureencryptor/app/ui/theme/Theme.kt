package com.secureencryptor.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Premium Slate & Vibrant Accent Color Palette
val PrimaryIndigo = Color(0xFF6366F1)
val PrimaryGradientEnd = Color(0xFF4F46E5)
val SecondaryTeal = Color(0xFF10B981)
val SecondaryGradientEnd = Color(0xFF059669)

val BackgroundDeep = Color(0xFF0B0F19)       // Rich deep space dark
val CardSurface = Color(0xFF161E2E)          // Premium elevated slate
val CardBorder = Color(0xFF2A364F)           // Subtle crisp border
val InputSurface = Color(0xFF0F172A)         // Sunken text field surface

val TextPrimary = Color(0xFFF8FAFC)
val TextSecondary = Color(0xFF94A3B8)
val TextMuted = Color(0xFF64748B)

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryIndigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1E1B4B),
    onPrimaryContainer = Color(0xFFE0E7FF),
    secondary = SecondaryTeal,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF064E3B),
    onSecondaryContainer = Color(0xFFA7F3D0),
    background = BackgroundDeep,
    onBackground = TextPrimary,
    surface = CardSurface,
    onSurface = TextPrimary,
    surfaceVariant = InputSurface,
    onSurfaceVariant = TextSecondary,
    outline = CardBorder,
    outlineVariant = Color(0xFF1E293B)
)

@Composable
fun SecureEncryptorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
