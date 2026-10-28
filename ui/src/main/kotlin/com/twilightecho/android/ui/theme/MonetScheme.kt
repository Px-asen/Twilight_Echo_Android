package com.twilightecho.android.ui.theme

import android.graphics.Bitmap
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.*

/**
 * Monet-style dynamic color derived from cover art.
 *
 * Material's HCT space is approximated with CIE LCh(ab): hue and chroma come from the seed, tone is L*.
 * Colors outside sRGB are pulled back by lowering chroma, so every tone keeps its hue.
 */
object Monet {
    /** Apple Music red, used as the accent when nothing is playing or the cover has no usable colour. */
    val FallbackSeed = Color(0xFFFA2D48)

    /** Picks the most representative vivid hue of a bitmap, like Material's Score. */
    fun seedFromBitmap(bitmap: Bitmap): Color? {
        val step = max(1, min(bitmap.width, bitmap.height) / 64)
        val bins = 36
        val weight = DoubleArray(bins)
        val sumL = DoubleArray(bins); val sumA = DoubleArray(bins); val sumB = DoubleArray(bins)
        val count = IntArray(bins)
        var total = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val px = bitmap.getPixel(x, y)
                if ((px ushr 24) > 200) {
                    total++
                    val lab = argbToLab(px)
                    val chroma = hypot(lab[1], lab[2])
                    if (chroma >= 12 && lab[0] in 18.0..92.0) {
                        val hue = (Math.toDegrees(atan2(lab[2], lab[1])) + 360) % 360
                        val bin = (hue / (360.0 / bins)).toInt().coerceIn(0, bins - 1)
                        // Favor colorful, mid-tone pixels; large flat areas still win through count.
                        weight[bin] += chroma.coerceAtMost(70.0) * (1 - abs(lab[0] - 55) / 80)
                        sumL[bin] += lab[0]; sumA[bin] += lab[1]; sumB[bin] += lab[2]; count[bin]++
                    }
                }
                x += step
            }
            y += step
        }
        if (total == 0) return null
        // Smooth across neighbouring bins so a hue split over a boundary is not under-counted.
        val smoothed = DoubleArray(bins) { i -> weight[i] + 0.5 * (weight[(i + 1) % bins] + weight[(i + bins - 1) % bins]) }
        val best = smoothed.indices.maxByOrNull { smoothed[it] } ?: return null
        if (count[best] < total * 0.01) return null
        val n = count[best].toDouble()
        return Color(labToArgb(sumL[best] / n, sumA[best] / n, sumB[best] / n))
    }

    fun hueChroma(color: Color): Pair<Double, Double> {
        val lab = argbToLab(colorToArgb(color))
        return ((Math.toDegrees(atan2(lab[2], lab[1])) + 360) % 360) to hypot(lab[1], lab[2])
    }

    /** One tone of a palette: L* = tone, chroma reduced until the color fits in sRGB. */
    fun tone(hue: Double, chroma: Double, tone: Double): Color {
        if (tone <= 0.5) return Color.Black
        if (tone >= 99.5) return Color.White
        var low = 0.0
        var high = chroma
        var best = lch(tone, 0.0, hue) ?: return Color(labToArgb(tone, 0.0, 0.0))
        if (lch(tone, chroma, hue)?.let { best = it; true } == true) return Color(best)
        repeat(14) {
            val mid = (low + high) / 2
            val candidate = lch(tone, mid, hue)
            if (candidate != null) { best = candidate; low = mid } else high = mid
        }
        return Color(best)
    }

    class Palette(val hue: Double, val chroma: Double) {
        fun tone(t: Int): Color = Monet.tone(hue, chroma, t.toDouble())
    }

    /**
     * Apple-style dark scheme: every surface is a fixed neutral grey, and the cover's colour only
     * reaches the accent roles (play buttons, selected tab, links). Containers stay grey, so tiles and
     * chips never pick up a tint.
     */
    fun darkScheme(seed: Color): ColorScheme {
        val (hue, seedChroma) = hueChroma(seed)
        val fallback = seed == FallbackSeed
        val accent = if (fallback) seed else tone(hue, max(seedChroma, 44.0).coerceAtMost(64.0), 74.0)
        val onAccent = if (fallback) Color.White else tone(hue, min(seedChroma, 30.0), 12.0)
        return darkColorScheme(
            primary = accent, onPrimary = onAccent,
            primaryContainer = AppleGrey.Elevated, onPrimaryContainer = accent,
            inversePrimary = accent,
            secondary = accent, onSecondary = onAccent,
            secondaryContainer = AppleGrey.Elevated, onSecondaryContainer = AppleGrey.Label,
            tertiary = accent, onTertiary = onAccent,
            tertiaryContainer = AppleGrey.Elevated, onTertiaryContainer = accent,
            background = AppleGrey.Base, onBackground = AppleGrey.Label,
            surface = AppleGrey.Base, onSurface = AppleGrey.Label,
            surfaceVariant = AppleGrey.Elevated, onSurfaceVariant = AppleGrey.Secondary,
            surfaceTint = Color.Transparent,
            inverseSurface = AppleGrey.Label, inverseOnSurface = AppleGrey.Base,
            error = Color(0xFFFF6961), onError = Color.White,
            errorContainer = Color(0xFF5C1A17), onErrorContainer = Color(0xFFFFDAD6),
            outline = Color(0xFF545458), outlineVariant = AppleGrey.Separator,
            scrim = Color.Black,
            surfaceBright = Color(0xFF3A3A3C), surfaceDim = AppleGrey.Base,
            surfaceContainerLowest = Color(0xFF151517), surfaceContainerLow = Color(0xFF232325),
            surfaceContainer = AppleGrey.Elevated, surfaceContainerHigh = Color(0xFF323235),
            surfaceContainerHighest = Color(0xFF3A3A3C)
        )
    }

    /** Former glow and gradient accents, now neutral: depth comes from greys, not from coloured light. */
    fun accents(seed: Color): TwilightAccents = TwilightAccents(
        glow = Color(0xFF000000), glowSoft = AppleGrey.Elevated, companion = AppleGrey.Elevated,
        deep = Color(0xFF121214), seed = seed)

    // --- CIE Lab conversions (D65) ---

    private fun lch(l: Double, c: Double, hDeg: Double): Int? {
        val h = Math.toRadians(hDeg)
        return labToArgbOrNull(l, c * cos(h), c * sin(h))
    }

    private fun colorToArgb(color: Color): Int {
        fun ch(v: Float) = (v.coerceIn(0f, 1f) * 255 + 0.5f).toInt()
        return (0xFF shl 24) or (ch(color.red) shl 16) or (ch(color.green) shl 8) or ch(color.blue)
    }

    private fun linearize(c: Int): Double { val v = c / 255.0; return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4) }
    private fun delinearize(v: Double): Double = if (v <= 0.0031308) v * 12.92 else 1.055 * v.pow(1 / 2.4) - 0.055

    private const val XN = 0.95047; private const val YN = 1.0; private const val ZN = 1.08883

    internal fun argbToLab(argb: Int): DoubleArray {
        val r = linearize((argb shr 16) and 0xFF); val g = linearize((argb shr 8) and 0xFF); val b = linearize(argb and 0xFF)
        val x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / XN
        val y = (0.2126729 * r + 0.7151522 * g + 0.0721750 * b) / YN
        val z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / ZN
        fun f(t: Double) = if (t > 216.0 / 24389) cbrt(t) else (24389.0 / 27 * t + 16) / 116
        val fx = f(x); val fy = f(y); val fz = f(z)
        return doubleArrayOf(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))
    }

    private fun labToLinear(l: Double, a: Double, b: Double): DoubleArray {
        val fy = (l + 16) / 116; val fx = fy + a / 500; val fz = fy - b / 200
        fun inv(t: Double) = if (t * t * t > 216.0 / 24389) t * t * t else (116 * t - 16) / (24389.0 / 27)
        val x = inv(fx) * XN; val y = inv(fy) * YN; val z = inv(fz) * ZN
        return doubleArrayOf(
            3.2404542 * x - 1.5371385 * y - 0.4985314 * z,
            -0.9692660 * x + 1.8760108 * y + 0.0415560 * z,
            0.0556434 * x - 0.2040259 * y + 1.0572252 * z)
    }

    private fun pack(rgb: DoubleArray): Int {
        fun ch(v: Double) = (delinearize(v.coerceIn(0.0, 1.0)) * 255 + 0.5).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(rgb[0]) shl 16) or (ch(rgb[1]) shl 8) or ch(rgb[2])
    }

    private fun labToArgbOrNull(l: Double, a: Double, b: Double): Int? {
        val rgb = labToLinear(l, a, b)
        return if (rgb.all { it in -0.0001..1.0001 }) pack(rgb) else null
    }

    internal fun labToArgb(l: Double, a: Double, b: Double): Int = pack(labToLinear(l, a, b))
}

data class TwilightAccents(val glow: Color, val glowSoft: Color, val companion: Color, val deep: Color, val seed: Color)

/** The neutral greys of Apple's dark appearance. */
object AppleGrey {
    val Base = Color(0xFF1C1C1E)
    val Elevated = Color(0xFF2C2C2E)
    val Separator = Color(0xFF38383A)
    val Label = Color(0xFFF5F5F7)
    val Secondary = Color(0xFF98989F)
}
