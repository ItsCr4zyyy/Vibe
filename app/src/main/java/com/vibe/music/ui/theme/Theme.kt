package com.vibe.music.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object VibeColors {
    val Background = Color(0xFF0C0B10)
    val Surface = Color(0xFF121218)
    val SurfaceHigh = Color(0xFF1C1C25)
    val SurfaceHighest = Color(0xFF262631)
    val Accent = Color(0xFFB79CFF)
    val AccentDeep = Color(0xFF7C5CE0)
    val Violet = Color(0xFFF0A6D8)
    val TextPrimary = Color(0xFFF4F4F6)
    val TextSecondary = Color(0xFFA1A1AA)
    val Outline = Color(0xFF2C2C36)
}

private val VibeDark = darkColorScheme(
    primary = VibeColors.Accent,
    onPrimary = Color(0xFF1C0B45),
    primaryContainer = VibeColors.AccentDeep,
    onPrimaryContainer = Color.White,
    secondary = VibeColors.Violet,
    onSecondary = Color(0xFF3B0A2A),
    background = VibeColors.Background,
    onBackground = VibeColors.TextPrimary,
    surface = VibeColors.Background,
    onSurface = VibeColors.TextPrimary,
    surfaceVariant = VibeColors.SurfaceHigh,
    onSurfaceVariant = VibeColors.TextSecondary,
    surfaceContainerLowest = VibeColors.Background,
    surfaceContainerLow = VibeColors.Surface,
    surfaceContainer = VibeColors.Surface,
    surfaceContainerHigh = VibeColors.SurfaceHigh,
    surfaceContainerHighest = VibeColors.SurfaceHighest,
    outline = VibeColors.Outline,
    outlineVariant = VibeColors.Outline,
    error = Color(0xFFFF6B6B),
)

private val base = Typography()

private val VibeTypography = Typography(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Black, letterSpacing = (-1).sp),
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Black, letterSpacing = (-0.8).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.6).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp),
)

@Composable
fun VibeTheme(dynamicColor: Boolean = false, content: @Composable () -> Unit) {
    val scheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(LocalContext.current).copy(
            background = VibeColors.Background,
            surface = VibeColors.Background,
        )
    } else VibeDark
    MaterialTheme(colorScheme = scheme, typography = VibeTypography, content = content)
}
