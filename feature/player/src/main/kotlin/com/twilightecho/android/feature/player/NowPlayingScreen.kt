package com.twilightecho.android.feature.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.twilightecho.android.coreclient.CoreQueueEntryView
import com.twilightecho.android.ui.components.ArtworkDiscPlaceholder
import com.twilightecho.android.ui.components.AudioDiagnosticsBadge
import com.twilightecho.android.ui.components.formatDurationSeconds
import com.twilightecho.android.ui.theme.TwilightTokens

@Composable
fun NowPlayingScreen(
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Surface(
        modifier = modifier.fillMaxSize(),
        color = TwilightTokens.DeepNightBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top bar: Core epoch/sequence status & Queue toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "TWILIGHT ECHO",
                        style = MaterialTheme.typography.labelSmall,
                        color = TwilightTokens.AccentAmber
                    )
                    Text(
                        text = "Core rev #${state.queueRevision} • seq ${state.sequence}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TwilightTokens.TextSecondary
                    )
                }

                OutlinedButton(
                    onClick = { viewModel.onToggleQueueDrawer() }
                ) {
                    Text(
                        text = if (state.isQueueDrawerOpen) "Hide Queue" else "Queue (${state.queueItems.size})",
                        style = MaterialTheme.typography.labelSmall,
                        color = TwilightTokens.TextPrimary
                    )
                }
            }

            state.errorBanner?.let { errorMsg ->
                Surface(
                    color = TwilightTokens.ErrorRose.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = errorMsg,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TwilightTokens.ErrorRose,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            // Artwork & Track Metadata
            val trackTitle = state.currentTrack?.title ?: "No Track Loaded"
            val trackArtist = state.currentTrack?.artist ?: "Twilight Echo Core"
            val trackAlbum = state.currentTrack?.album ?: ""

            ArtworkDiscPlaceholder(
                title = trackTitle,
                album = trackAlbum,
                isPlaying = state.isPlaying
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = trackTitle,
                    style = MaterialTheme.typography.headlineMedium,
                    color = TwilightTokens.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "$trackArtist • $trackAlbum",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TwilightTokens.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                AudioDiagnosticsBadge(
                    codec = state.diagnostics.codec,
                    sourceSampleRateHz = state.diagnostics.sourceSampleRateHz,
                    sourceBitDepth = state.diagnostics.sourceBitDepth,
                    outputBackend = state.diagnostics.outputBackend,
                    actualSampleRateHz = state.diagnostics.actualSampleRateHz,
                    actualBitDepth = state.diagnostics.actualBitDepth,
                    outputPerfect = state.diagnostics.outputPerfect
                )
            }

            // Progress Slider with Engine-Clock Interpolation
            Column(modifier = Modifier.fillMaxWidth()) {
                val maxDuration = state.durationSeconds.coerceAtLeast(1.0).toFloat()
                val currentPos = state.displayedPositionSeconds.coerceIn(0.0, state.durationSeconds).toFloat()

                Slider(
                    value = currentPos,
                    onValueChange = { newPos -> viewModel.onSeekTo(newPos.toDouble()) },
                    valueRange = 0f..maxDuration,
                    colors = SliderDefaults.colors(
                        thumbColor = TwilightTokens.AccentAmber,
                        activeTrackColor = TwilightTokens.AccentAmber,
                        inactiveTrackColor = TwilightTokens.GlassSurface
                    )
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatDurationSeconds(state.displayedPositionSeconds),
                        style = MaterialTheme.typography.labelSmall,
                        color = TwilightTokens.TextSecondary
                    )
                    Text(
                        text = formatDurationSeconds(state.durationSeconds),
                        style = MaterialTheme.typography.labelSmall,
                        color = TwilightTokens.TextSecondary
                    )
                }
            }

            // Playback Transport Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = { viewModel.onCyclePlayMode() }) {
                    Text(
                        text = state.playMode.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = TwilightTokens.AccentAmber
                    )
                }

                OutlinedButton(onClick = { viewModel.onPreviousTrack() }) {
                    Text(text = "Prev", color = TwilightTokens.TextPrimary)
                }

                Button(
                    onClick = { viewModel.onTogglePlayPause() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TwilightTokens.AccentAmber,
                        contentColor = TwilightTokens.DeepNightBackground
                    )
                ) {
                    Text(
                        text = if (state.isPlaying) "Pause" else "Play",
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                OutlinedButton(onClick = { viewModel.onNextTrack() }) {
                    Text(text = "Next", color = TwilightTokens.TextPrimary)
                }
            }

            // Expandable Authoritative Queue Drawer
            AnimatedVisibility(visible = state.isQueueDrawerOpen) {
                QueueDrawerSection(
                    items = state.queueItems,
                    currentQueueIndex = state.currentQueueIndex,
                    onSelectEntry = { entry ->
                        viewModel.onSelectQueueItem(entry.index, entry.occurrenceId)
                    }
                )
            }
        }
    }
}

@Composable
private fun QueueDrawerSection(
    items: List<CoreQueueEntryView>,
    currentQueueIndex: Int,
    onSelectEntry: (CoreQueueEntryView) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(190.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(TwilightTokens.valElevatedSurface)
            .padding(12.dp)
    ) {
        Text(
            text = "PLAYBACK QUEUE (SINGLE SOURCE OF TRUTH)",
            style = MaterialTheme.typography.labelSmall,
            color = TwilightTokens.AccentAmber,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(items, key = { it.occurrenceId }) { entry ->
                val isCurrent = entry.index == currentQueueIndex
                val track = entry.track
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (isCurrent) TwilightTokens.GlassSurface
                            else TwilightTokens.valElevatedSurface
                        )
                        .clickable { onSelectEntry(entry) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${entry.index + 1}. ${track?.title ?: "Unknown"}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isCurrent) TwilightTokens.AccentAmber else TwilightTokens.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = track?.artist ?: "",
                            style = MaterialTheme.typography.labelSmall,
                            color = TwilightTokens.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = formatDurationSeconds(track?.duration ?: 0.0),
                        style = MaterialTheme.typography.labelSmall,
                        color = TwilightTokens.TextSecondary
                    )
                }
            }
        }
    }
}
