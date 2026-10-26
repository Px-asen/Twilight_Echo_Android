package com.twilightecho.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Gallery White Card Design Tokens aligned with Desktop `Twilight_Echo` (`pureWhite` preset).
 * Zero purple-black colors; built around architectural alabaster canvas, pure white elevated cards,
 * hairline slate borders, Klein/Cobalt primary accent, and Signal Emerald indicators.
 */
object TwilightTokens {
    // Architectural Surfaces
    val CanvasBackground = Color(0xFFF4F5F8)
    val CardSurface = Color(0xFFFFFFFF)
    val SubtleSurface = Color(0xFFF8FAFC)
    val PillSurface = Color(0xFFEEF1F6)
    val HoverSurface = Color(0xFFE8ECF4)
    val VinylCharcoal = Color(0xFF181B22)
    val VinylGroove = Color(0xFF262B35)

    // Borders & Ambient Shadows
    val CardBorder = Color(0x120F172A)        // rgba(15, 23, 42, 0.07)
    val CardBorderStrong = Color(0x1F0F172A)  // rgba(15, 23, 42, 0.12)
    val AmbientShadowColor = Color(0x140F172A)

    // Brand & Signal Accents (Aligned with Desktop --te-primary-500 & Signal Path)
    val PrimaryCobalt = Color(0xFF2563EB)
    val PrimaryCobaltHover = Color(0xFF3B82F6)
    val PrimaryCobaltSoft = Color(0xFFEFF6FF)
    val PrimaryCobaltBorder = Color(0x332563EB)

    val SignalEmerald = Color(0xFF10B981)
    val SignalEmeraldDark = Color(0xFF059669)
    val SignalEmeraldSoft = Color(0xFFECFDF5)
    val SignalEmeraldBorder = Color(0x3810B981)

    val FavoriteRose = Color(0xFFE11D48)
    val FavoriteRoseSoft = Color(0xFFFFF1F2)

    val WarmAmber = Color(0xFFD97706)
    val WarmAmberSoft = Color(0xFFFFFBEB)
    val WarmAmberBorder = Color(0x38D97706)

    // Editorial Typography Hierarchy
    val TextPrimary = Color(0xFF0F172A)
    val TextSecondary = Color(0xFF475569)
    val TextMuted = Color(0xFF94A3B8)
    val TextOnPrimary = Color(0xFFFFFFFF)

    // Semantic Compatibility Aliases
    val DeepNightBackground = CanvasBackground
    val ElevatedSurface = CardSurface
    val valElevatedSurface = CardSurface
    val GlassSurface = SubtleSurface
    val AccentAmber = PrimaryCobalt
    val HiResEmerald = SignalEmerald
    val ErrorRose = FavoriteRose
}

private val TwilightLightColorScheme = lightColorScheme(
    primary = TwilightTokens.PrimaryCobalt,
    onPrimary = TwilightTokens.TextOnPrimary,
    primaryContainer = TwilightTokens.PrimaryCobaltSoft,
    onPrimaryContainer = TwilightTokens.PrimaryCobalt,
    secondary = TwilightTokens.SignalEmerald,
    onSecondary = TwilightTokens.TextOnPrimary,
    secondaryContainer = TwilightTokens.SignalEmeraldSoft,
    onSecondaryContainer = TwilightTokens.SignalEmeraldDark,
    tertiary = TwilightTokens.WarmAmber,
    onTertiary = TwilightTokens.TextOnPrimary,
    background = TwilightTokens.CanvasBackground,
    onBackground = TwilightTokens.TextPrimary,
    surface = TwilightTokens.CardSurface,
    onSurface = TwilightTokens.TextPrimary,
    surfaceVariant = TwilightTokens.SubtleSurface,
    onSurfaceVariant = TwilightTokens.TextSecondary,
    outline = TwilightTokens.CardBorderStrong,
    outlineVariant = TwilightTokens.CardBorder,
    error = TwilightTokens.FavoriteRose,
    onError = TwilightTokens.TextOnPrimary
)

val TwilightTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.6).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.4).sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = (-0.2).sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 21.sp,
        letterSpacing = (-0.1).sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.5.sp,
        lineHeight = 19.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.3.sp
    )
)

@Composable
fun TwilightEchoTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = TwilightLightColorScheme,
        typography = TwilightTypography,
        content = content
    )
}
