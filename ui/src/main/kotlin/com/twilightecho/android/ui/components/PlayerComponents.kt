package com.twilightecho.android.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.twilightecho.android.ui.theme.TwilightTokens
import java.util.Locale

/**
 * Frosted Translucent Gallery Card container with subtle ambient shadow and luminous hairline border,
 * allowing the blurred song cover backdrop to gently diffuse through while preserving crisp typography.
 */
@Composable
fun TwilightWhiteCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    elevation: Dp = 6.dp,
    containerColor: Color = Color.White.copy(alpha = 0.80f),
    borderColor: Color = Color.White.copy(alpha = 0.72f),
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .shadow(
                elevation = elevation,
                shape = shape,
                ambientColor = TwilightTokens.AmbientShadowColor,
                spotColor = TwilightTokens.AmbientShadowColor
            )
            .clip(shape)
            .background(containerColor)
            .border(width = 0.9.dp, color = borderColor, shape = shape),
        content = content
    )
}

/**
 * Compact, tactile Signal Path status pill for the top bar (inspired by Desktop "● 实时线路运行中").
 * Tapping opens the full Signal Path & DSP Modal Bottom Sheet.
 */
@Composable
fun SignalPathCapsule(
    codec: String,
    sampleRateHz: Int,
    bitDepth: Int,
    outputPerfect: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cleanCodec = codec.takeUnless {
        it.isBlank() || it == "未知" || it.equals("unknown", ignoreCase = true)
    } ?: "FLAC"
    val khz = String.format(Locale.US, "%.1fk", sampleRateHz / 1000.0).removeSuffix(".0k") +
        if (sampleRateHz % 1000 == 0) "k" else ""
    val dotColor = if (outputPerfect) TwilightTokens.SignalEmerald else TwilightTokens.PrimaryCobalt
    val bgColor = if (outputPerfect) TwilightTokens.SignalEmeraldSoft else TwilightTokens.PrimaryCobaltSoft
    val borderColor = if (outputPerfect) TwilightTokens.SignalEmeraldBorder else TwilightTokens.PrimaryCobaltBorder

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(bgColor)
            .border(0.8.dp, borderColor, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(
            text = "${cleanCodec.uppercase()} · ${bitDepth}-bit/${khz}Hz",
            style = MaterialTheme.typography.labelSmall,
            color = if (outputPerfect) TwilightTokens.SignalEmeraldDark else TwilightTokens.PrimaryCobalt
        )
    }
}

/**
 * Full 3-node Signal Path diagnostic card displayed inside the Signal & DSP Modal Sheet,
 * directly referencing the Desktop "SIGNAL PATH · 播放链路 · 实时线路运行中" card.
 */
@Composable
fun SignalPathArchitectureCard(
    codec: String,
    sourceSampleRateHz: Int,
    sourceBitDepth: Int,
    outputBackend: String,
    actualSampleRateHz: Int,
    actualBitDepth: Int,
    outputPerfect: Boolean,
    eqEnabled: Boolean,
    eqPreset: String,
    modifier: Modifier = Modifier
) {
    val srcKhz = String.format(Locale.US, "%.1f kHz", sourceSampleRateHz / 1000.0)
    val outKhz = String.format(Locale.US, "%.1f kHz", actualSampleRateHz / 1000.0)
    val statusText = if (outputPerfect) "实时无损直通" else "系统重采样链路"
    val statusColor = if (outputPerfect) TwilightTokens.SignalEmeraldDark else TwilightTokens.WarmAmber
    val statusBg = if (outputPerfect) TwilightTokens.SignalEmeraldSoft else TwilightTokens.WarmAmberSoft
    val statusBorder = if (outputPerfect) TwilightTokens.SignalEmeraldBorder else TwilightTokens.WarmAmberBorder

    TwilightWhiteCard(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 18.dp,
        elevation = 2.dp,
        containerColor = TwilightTokens.SubtleSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "SIGNAL PATH",
                        style = MaterialTheme.typography.labelSmall,
                        color = TwilightTokens.TextMuted
                    )
                    Text(
                        text = "音频播放链路",
                        style = MaterialTheme.typography.titleMedium,
                        color = TwilightTokens.TextPrimary
                    )
                }

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(statusBg)
                        .border(0.8.dp, statusBorder, RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelMedium,
                        color = statusColor
                    )
                }
            }

            // 3-Stage Node Chain: Source -> DSP Core -> Output
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SignalNodeBox(
                    stageLabel = "SOURCE",
                    primaryValue = codec.uppercase(),
                    secondaryValue = "${sourceBitDepth}-bit / $srcKhz",
                    accentColor = TwilightTokens.PrimaryCobalt,
                    modifier = Modifier.weight(1f)
                )
                SignalNodeBox(
                    stageLabel = "DSP ENGINE",
                    primaryValue = if (eqEnabled) "EQ · $eqPreset" else "Bit-Transparent",
                    secondaryValue = if (eqEnabled) "10-Band 64-bit" else "Bypass Direct",
                    accentColor = if (eqEnabled) TwilightTokens.PrimaryCobalt else TwilightTokens.SignalEmeraldDark,
                    modifier = Modifier.weight(1f)
                )
                SignalNodeBox(
                    stageLabel = "OUTPUT",
                    primaryValue = outputBackend.uppercase(),
                    secondaryValue = "${actualBitDepth}-bit / $outKhz",
                    accentColor = statusColor,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun SignalNodeBox(
    stageLabel: String,
    primaryValue: String,
    secondaryValue: String,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(TwilightTokens.CardSurface)
            .border(0.8.dp, TwilightTokens.CardBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            text = stageLabel,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp),
            color = TwilightTokens.TextMuted
        )
        Text(
            text = primaryValue,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = accentColor,
            maxLines = 1
        )
        Text(
            text = secondaryValue,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = TwilightTokens.TextSecondary,
            maxLines = 1
        )
    }
}

/**
 * Legacy compatibility badge (upgraded to clean white-card styling).
 */
@Composable
fun AudioDiagnosticsBadge(
    codec: String,
    sourceSampleRateHz: Int,
    sourceBitDepth: Int,
    outputBackend: String,
    actualSampleRateHz: Int,
    actualBitDepth: Int,
    outputPerfect: Boolean,
    modifier: Modifier = Modifier
) {
    SignalPathCapsule(
        codec = codec,
        sampleRateHz = actualSampleRateHz.takeIf { it > 0 } ?: sourceSampleRateHz,
        bitDepth = actualBitDepth.takeIf { it > 0 } ?: sourceBitDepth,
        outputPerfect = outputPerfect,
        onClick = {},
        modifier = modifier
    )
}

/**
 * Gallery-Grade White Card Artwork Stage inspired by Desktop `Twilight_Echo`:
 * Features a subtle tilted backplate card + elevated pure white frame + precision dark charcoal
 * vinyl/architectural disc with micro-grooves and smooth rotation when playing.
 * Completely removes duplicate title/album text inside the artwork canvas.
 */
@Composable
fun GalleryArtworkStageCard(
    isPlaying: Boolean,
    codecLabel: String,
    sampleRateHz: Int,
    artwork: TrackArtworkVisual? = null,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "vinylSpin")
    val spinAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 22000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "discAngle"
    )
    val tonearmAngle by animateFloatAsState(
        targetValue = if (isPlaying) 18f else 0f,
        animationSpec = tween(durationMillis = 650),
        label = "tonearmAngle"
    )
    val cardScale by animateFloatAsState(
        targetValue = if (isPlaying) 1.0f else 0.965f,
        animationSpec = tween(durationMillis = 500),
        label = "stageScale"
    )

    val cleanCodec = codecLabel.takeUnless {
        it.isBlank() || it == "未知" || it.equals("unknown", ignoreCase = true)
    } ?: "HI-RES"

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        // Tilted secondary backplate card (referencing the Desktop layered album sleeve effect)
        Box(
            modifier = Modifier
                .fillMaxWidth(0.90f)
                .aspectRatio(1f)
                .graphicsLayer {
                    rotationZ = 4.2f
                    scaleX = cardScale * 0.96f
                    scaleY = cardScale * 0.96f
                    translationX = 16f
                    translationY = 12f
                }
                .clip(RoundedCornerShape(32.dp))
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            (artwork?.dominantColor ?: TwilightTokens.PrimaryCobalt).copy(alpha = 0.16f),
                            (artwork?.secondaryColor ?: TwilightTokens.SignalEmerald).copy(alpha = 0.18f)
                        )
                    )
                )
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.65f),
                    shape = RoundedCornerShape(32.dp)
                )
        )

        // Main Frosted Gallery Card Sleeve
        TwilightWhiteCard(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .aspectRatio(1f)
                .graphicsLayer {
                    scaleX = cardScale
                    scaleY = cardScale
                },
            cornerRadius = 32.dp,
            elevation = 14.dp,
            containerColor = Color.White.copy(alpha = 0.84f),
            borderColor = Color.White.copy(alpha = 0.85f)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                // Inner Matte Alabaster Well
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(24.dp))
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.92f),
                                    TwilightTokens.SubtleSurface.copy(alpha = 0.85f)
                                )
                            )
                        )
                        .border(0.8.dp, TwilightTokens.CardBorder, RoundedCornerShape(24.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    // Corner micro-markings (Industrial Hi-Fi aesthetic)
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isPlaying) TwilightTokens.SignalEmerald
                                    else TwilightTokens.PrimaryCobalt.copy(alpha = 0.55f)
                                )
                        )
                        Text(
                            text = if (isPlaying) "STAGE ACTIVE" else "PURE DIRECT",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp),
                            color = TwilightTokens.TextMuted
                        )
                    }

                    Text(
                        text = "${cleanCodec.uppercase()} · ${sampleRateHz / 1000}KHZ",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp),
                        color = TwilightTokens.TextMuted,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(14.dp)
                    )

                    // Precision Charcoal Vinyl / Acoustic Disc with Album Cover Center
                    Box(
                        modifier = Modifier
                            .fillMaxSize(0.82f)
                            .rotate(if (isPlaying) spinAngle else 0f),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val radius = size.minDimension / 2f
                            // Outer vinyl body
                            drawCircle(
                                color = TwilightTokens.VinylCharcoal,
                                radius = radius
                            )
                            // Subtle sheen highlight arc
                            drawArc(
                                brush = Brush.sweepGradient(
                                    colors = listOf(
                                        Color.White.copy(alpha = 0.02f),
                                        Color.White.copy(alpha = 0.12f),
                                        Color.White.copy(alpha = 0.02f),
                                        Color.Transparent,
                                        Color.White.copy(alpha = 0.09f),
                                        Color.White.copy(alpha = 0.02f)
                                    )
                                ),
                                startAngle = 0f,
                                sweepAngle = 360f,
                                useCenter = true
                            )
                            // Precision concentric micro-grooves
                            val grooveFractions = listOf(0.90f, 0.82f, 0.74f, 0.66f)
                            grooveFractions.forEachIndexed { idx, fraction ->
                                drawCircle(
                                    color = Color.White.copy(alpha = if (idx % 2 == 0) 0.08f else 0.04f),
                                    radius = radius * fraction,
                                    style = Stroke(width = 1.dp.toPx())
                                )
                            }
                        }

                        // Circular Cover Art Label in the Center of the Vinyl (56% diameter)
                        if (artwork != null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize(0.56f)
                                    .clip(CircleShape)
                                    .border(2.5.dp, Color.White.copy(alpha = 0.90f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    bitmap = artwork.imageBitmap,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                                // Center vinyl spindle ring
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .background(Color.White)
                                        .border(2.5.dp, TwilightTokens.VinylCharcoal, CircleShape)
                                )
                            }
                        } else {
                            Icon(
                                imageVector = TwilightIcons.MusicNote,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    // Minimalist Stylus / Tonearm Indicator in Top-Right
                    Canvas(
                        modifier = Modifier
                            .size(48.dp)
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                    ) {
                        val pivot = Offset(size.width * 0.78f, size.height * 0.22f)
                        drawCircle(
                            color = TwilightTokens.CardBorderStrong,
                            radius = 5.dp.toPx(),
                            center = pivot
                        )
                        drawCircle(
                            color = TwilightTokens.PrimaryCobalt,
                            radius = 2.5.dp.toPx(),
                            center = pivot
                        )
                        val rad = Math.toRadians((125f + tonearmAngle).toDouble())
                        val armLen = size.minDimension * 0.75f
                        val tip = Offset(
                            x = pivot.x + (kotlin.math.cos(rad) * armLen).toFloat(),
                            y = pivot.y + (kotlin.math.sin(rad) * armLen).toFloat()
                        )
                        drawLine(
                            color = TwilightTokens.TextSecondary.copy(alpha = 0.55f),
                            start = pivot,
                            end = tip,
                            strokeWidth = 2.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                    }
                }
            }
        }
    }
}

/**
 *Segmented capsule toggle (used for "封面 / 歌词" and library sub-tabs).
 */
@Composable
fun <T> SegmentedPillSwitch(
    options: List<T>,
    selectedOption: T,
    labelProvider: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(TwilightTokens.PillSurface)
            .border(0.8.dp, TwilightTokens.CardBorder, RoundedCornerShape(50))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        options.forEach { option ->
            val selected = option == selectedOption
            val bg by animateColorAsState(
                targetValue = if (selected) TwilightTokens.CardSurface else Color.Transparent,
                animationSpec = tween(200),
                label = "pillBg"
            )
            val textColor by animateColorAsState(
                targetValue = if (selected) TwilightTokens.TextPrimary else TwilightTokens.TextSecondary,
                animationSpec = tween(200),
                label = "pillText"
            )
            Surface(
                color = bg,
                shape = RoundedCornerShape(50),
                shadowElevation = if (selected) 2.dp else 0.dp,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable { onSelect(option) }
            ) {
                Text(
                    text = labelProvider(option),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                    ),
                    color = textColor,
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
fun ArtworkDiscPlaceholder(
    title: String,
    album: String,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    GalleryArtworkStageCard(
        isPlaying = isPlaying,
        codecLabel = "HI-RES",
        sampleRateHz = 96000,
        modifier = modifier
    )
}

fun formatDurationSeconds(seconds: Double): String {
    val totalSec = seconds.coerceAtLeast(0.0).toLong()
    val mins = totalSec / 60
    val secs = totalSec % 60
    return String.format(Locale.US, "%02d:%02d", mins, secs)
}
