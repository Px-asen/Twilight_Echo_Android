package com.twilightecho.android.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Fixed colors that do not follow the cover: status signals and the deep stage behind artwork. */
object TwilightTokens {
    val SignalLive = Color(0xFF8BE0A4)
    val SignalIdle = Color(0xFF6E7380)
    val SignalWarm = Color(0xFFF2C27B)
    val InkDeep = Color(0xFF0B0C10)
    val InkRaised = Color(0xFF1A1C22)
    val FavoriteRose = Color(0xFFFF8FA3)
    val OnStage = Color(0xFFF5F5F7)
}

val LocalTwilightAccents = staticCompositionLocalOf { Monet.accents(Monet.FallbackSeed) }
val LocalContentSurfaceOpacity = staticCompositionLocalOf { 0.86f }

private val TwilightSans = FontFamily.SansSerif

val TwilightTypography = Typography(
    displayLarge = TextStyle(fontFamily = TwilightSans, fontWeight = FontWeight.Black,
        fontSize = 48.sp, lineHeight = 52.sp, letterSpacing = (-1.6).sp),
    displayMedium = TextStyle(fontFamily = TwilightSans, fontWeight = FontWeight.ExtraBold,
        fontSize = 40.sp, lineHeight = 46.sp, letterSpacing = (-1.2).sp),
    displaySmall = TextStyle(fontFamily = TwilightSans, fontWeight = FontWeight.Bold,
        fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-1).sp),
    headlineLarge = TextStyle(fontFamily = TwilightSans, fontWeight = FontWeight.Bold,
        fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.8).sp),
    headlineMedium = TextStyle(fontFamily = TwilightSans, fontWeight = FontWeight.Bold,
        fontSize = 25.sp, lineHeight = 32.sp, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontFamily = TwilightSans, fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp, lineHeight = 28.sp, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontFamily = TwilightSans, fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp, lineHeight = 26.sp, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontFamily = TwilightSans, fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp, lineHeight = 23.sp),
    titleSmall = TextStyle(fontFamily = TwilightSans, fontWeight = FontWeight.Medium,
        fontSize = 15.sp, lineHeight = 21.sp),
    bodyLarge = TextStyle(fontFamily = TwilightSans, fontSize = 17.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontFamily = TwilightSans, fontSize = 15.sp, lineHeight = 22.sp),
    // Nothing in the app is set smaller than 13sp: tiny captions read as clutter, not refinement.
    bodySmall = TextStyle(fontFamily = TwilightSans, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = TwilightSans, fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp, lineHeight = 21.sp),
    labelMedium = TextStyle(fontFamily = TwilightSans, fontWeight = FontWeight.Medium,
        fontSize = 14.sp, lineHeight = 19.sp),
    labelSmall = TextStyle(fontFamily = TwilightSans, fontWeight = FontWeight.Medium,
        fontSize = 13.sp, lineHeight = 18.sp)
)

private val TwilightShapes = Shapes(extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(32.dp))

/**
 * Dark-first theme whose palette is generated from [seed] (normally the current cover).
 * Changing the seed cross-fades every role so a new track recolors the whole app smoothly.
 * [darkTheme] is kept for source compatibility; a custom solid canvas determines its own text contrast.
 */
@Composable
fun TwilightEchoTheme(darkTheme: Boolean = true, seed: Color = Monet.FallbackSeed,
    customAccent: Color? = null, backgroundColor: Color? = null, contentOpacity: Float = 0.86f,
    imageBackground: Boolean = false,
    content: @Composable () -> Unit) {
    val target = remember(seed, customAccent, backgroundColor, contentOpacity, imageBackground) {
        var palette = Monet.darkScheme(seed)
        if (imageBackground) palette = palette.copy(onSurfaceVariant = AppleGrey.Label.copy(alpha = 0.80f))
        if (backgroundColor != null) {
            val light = backgroundColor.luminance() > 0.18f
            val ink = if (light) Color.Black else Color.White
            var lift = if (light) Color.Black else Color.White
            val highest = lerp(backgroundColor, lift, 0.14f)
            val contrast = if (light) (highest.luminance() + 0.05f) / 0.05f else 1.05f / (highest.luminance() + 0.05f)
            // Near mid-grey, raise contrast rather than pushing a card toward the ink's luminance.
            if (contrast < 4.5f) lift = if (light) Color.White else Color.Black
            val raised = lerp(backgroundColor, lift, if (light) 0.06f else 0.08f)
            palette = palette.copy(background = backgroundColor, onBackground = ink,
                surface = backgroundColor, onSurface = ink, onSurfaceVariant = ink,
                surfaceVariant = raised, primaryContainer = raised, secondaryContainer = raised, tertiaryContainer = raised,
                onSecondaryContainer = ink, surfaceContainerLowest = backgroundColor,
                surfaceContainerLow = lerp(backgroundColor, lift, 0.04f), surfaceContainer = raised,
                surfaceContainerHigh = lerp(backgroundColor, lift, 0.10f),
                surfaceContainerHighest = lerp(backgroundColor, lift, 0.14f),
                outline = ink.copy(alpha = 0.4f), outlineVariant = ink.copy(alpha = 0.16f))
            // Cover-derived accents also need enough contrast on a light custom canvas.
            if (light && customAccent == null) {
                val (hue, chroma) = Monet.hueChroma(seed)
                val accent = Monet.tone(hue, chroma.coerceAtLeast(44.0), 40.0)
                palette = palette.copy(primary = accent, secondary = accent, tertiary = accent,
                    onPrimary = Color.White, onPrimaryContainer = accent, onTertiaryContainer = accent)
            }
        }
        customAccent?.let { accent ->
            val onAccent = if (accent.luminance() > 0.18f) Color.Black else Color.White
            palette = palette.copy(primary = accent, secondary = accent, tertiary = accent,
                onPrimary = onAccent, onSecondary = onAccent, onTertiary = onAccent,
                onPrimaryContainer = accent, onTertiaryContainer = accent, inversePrimary = accent)
        }
        palette.copy(surfaceContainerLow = palette.surfaceContainerLow.copy(alpha = contentOpacity),
            surfaceContainer = palette.surfaceContainer.copy(alpha = contentOpacity),
            // Dialogs and menus use High; they stay opaque over the customized page.
            surfaceContainerHigh = palette.surfaceContainerHigh,
            surfaceContainerHighest = palette.surfaceContainerHighest.copy(alpha = contentOpacity))
    }
    val effectiveSeed = customAccent ?: seed
    val accentsTarget = remember(effectiveSeed) { Monet.accents(effectiveSeed) }
    // Do not interpolate white ink into black ink over a simultaneously changing light/dark canvas.
    val scheme = key(backgroundColor?.let { it.luminance() > 0.18f } == true) { animateScheme(target) }
    val accents = TwilightAccents(
        glow = animated(accentsTarget.glow), glowSoft = animated(accentsTarget.glowSoft),
        companion = animated(accentsTarget.companion), deep = animated(accentsTarget.deep), seed = effectiveSeed)
    CompositionLocalProvider(LocalTwilightAccents provides accents, LocalContentSurfaceOpacity provides contentOpacity) {
        MaterialTheme(colorScheme = scheme, typography = TwilightTypography, shapes = TwilightShapes) {
            // Pages draw on AmbientBackground rather than a Surface, so set the default text color here.
            CompositionLocalProvider(LocalContentColor provides scheme.onSurface, content = content)
        }
    }
}

@Composable
private fun animated(color: Color): Color =
    animateColorAsState(color, tween(900), label = "monet").value

@Composable
private fun animateScheme(target: ColorScheme): ColorScheme = target.copy(
    primary = animated(target.primary), onPrimary = animated(target.onPrimary),
    primaryContainer = animated(target.primaryContainer), onPrimaryContainer = animated(target.onPrimaryContainer),
    secondary = animated(target.secondary), onSecondary = animated(target.onSecondary),
    secondaryContainer = animated(target.secondaryContainer), onSecondaryContainer = animated(target.onSecondaryContainer),
    tertiary = animated(target.tertiary), tertiaryContainer = animated(target.tertiaryContainer),
    onTertiaryContainer = animated(target.onTertiaryContainer),
    background = animated(target.background), onBackground = animated(target.onBackground),
    surface = animated(target.surface), onSurface = animated(target.onSurface),
    surfaceVariant = animated(target.surfaceVariant), onSurfaceVariant = animated(target.onSurfaceVariant),
    outline = animated(target.outline), outlineVariant = animated(target.outlineVariant),
    surfaceContainerLowest = animated(target.surfaceContainerLowest),
    surfaceContainerLow = animated(target.surfaceContainerLow),
    surfaceContainer = animated(target.surfaceContainer),
    surfaceContainerHigh = animated(target.surfaceContainerHigh),
    surfaceContainerHighest = animated(target.surfaceContainerHighest)
)
