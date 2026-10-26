package com.twilightecho.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.twilightecho.android.ui.theme.TwilightTokens
import java.util.Locale

/**
 * Displays verified vs. active audio signal chain diagnostics (Architecture Plan Section 13.6:
 * distinguishes source format, actual output format, and backend rather than blindly claiming bit-perfect).
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
    val srcKhz = String.format(Locale.US, "%.1f kHz", sourceSampleRateHz / 1000.0)
    val outKhz = String.format(Locale.US, "%.1f kHz", actualSampleRateHz / 1000.0)
    val statusLabel = if (outputPerfect) "DIRECT" else "RESAMPLED"
    val badgeColor = if (outputPerfect) TwilightTokens.HiResEmerald else TwilightTokens.AccentAmber

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(TwilightTokens.GlassSurface)
            .border(1.dp, badgeColor.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$codec • $sourceBitDepth-bit/$srcKhz",
            style = MaterialTheme.typography.labelSmall,
            color = TwilightTokens.TextPrimary
        )
        Text(
            text = "→ ${outputBackend.uppercase()} ($actualBitDepth-bit/$outKhz • $statusLabel)",
            style = MaterialTheme.typography.labelSmall,
            color = badgeColor
        )
    }
}

@Composable
fun ArtworkDiscPlaceholder(
    title: String,
    album: String,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(250.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        TwilightTokens.AccentViolet.copy(alpha = 0.35f),
                        TwilightTokens.GlassSurface,
                        TwilightTokens.AccentAmber.copy(alpha = 0.25f)
                    )
                )
            )
            .border(
                width = 1.5.dp,
                color = if (isPlaying) TwilightTokens.AccentAmber.copy(alpha = 0.6f)
                else TwilightTokens.TextSecondary.copy(alpha = 0.2f),
                shape = RoundedCornerShape(28.dp)
            )
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                color = TwilightTokens.DeepNightBackground.copy(alpha = 0.65f),
                shape = RoundedCornerShape(50)
            ) {
                Text(
                    text = if (isPlaying) "NOW PLAYING" else "PAUSED",
                    style = MaterialTheme.typography.labelSmall,
                    color = TwilightTokens.AccentAmber,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = TwilightTokens.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = album,
                style = MaterialTheme.typography.bodyMedium,
                color = TwilightTokens.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

fun formatDurationSeconds(seconds: Double): String {
    val totalSec = seconds.coerceAtLeast(0.0).toLong()
    val mins = totalSec / 60
    val secs = totalSec % 60
    return String.format(Locale.US, "%02d:%02d", mins, secs)
}
