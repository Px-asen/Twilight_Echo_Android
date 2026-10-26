package com.twilightecho.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object TwilightTokens {
    val DeepNightBackground = Color(0xFF0B0E17)
    val ElevatedSurface = Color(0xFF141929)
    val valElevatedSurface = ElevatedSurface
    val GlassSurface = Color(0xFF1D243B)
    val AccentAmber = Color(0xFFFFB74D)
    val AccentViolet = Color(0xFF9575CD)
    val HiResEmerald = Color(0xFF4DB6AC)
    val TextPrimary = Color(0xFFF4F6FB)
    val TextSecondary = Color(0xFFA0AAC0)
    val ErrorRose = Color(0xFFEF5350)
}

private val TwilightDarkColorScheme = darkColorScheme(
    primary = TwilightTokens.AccentAmber,
    secondary = TwilightTokens.AccentViolet,
    tertiary = TwilightTokens.HiResEmerald,
    background = TwilightTokens.DeepNightBackground,
    surface = TwilightTokens.valElevatedSurface,
    surfaceVariant = TwilightTokens.GlassSurface,
    onPrimary = Color(0xFF1A1200),
    onSecondary = Color.White,
    onBackground = TwilightTokens.TextPrimary,
    onSurface = TwilightTokens.TextPrimary,
    onSurfaceVariant = TwilightTokens.TextSecondary,
    error = TwilightTokens.ErrorRose
)

val TwilightTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.3).sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.4.sp
    )
)

@Composable
fun TwilightEchoTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = TwilightDarkColorScheme,
        typography = TwilightTypography,
        content = content
    )
}
