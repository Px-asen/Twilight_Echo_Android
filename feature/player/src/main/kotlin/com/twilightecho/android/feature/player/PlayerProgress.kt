package com.twilightecho.android.feature.player

import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.staticCompositionLocalOf

/** Independent subscriptions: the seek bar ticks at 10 Hz; lyrics change only at a line boundary. */
@Stable
internal class PlayerProgress(val positionSeconds: State<Double>, val lyricIndex: State<Int>)

internal val LocalPlayerProgress = staticCompositionLocalOf<PlayerProgress> {
    error("Player progress is provided by PlayerAppContent")
}

/** Hold the last visible fraction of a second during starvation, until the real clock catches up. */
internal fun bufferingProgressFloor(
    previous: PlayerUiState,
    incomingPosition: Double,
    incomingState: String,
    floor: Double?,
    sameTimeline: Boolean,
    seeking: Boolean
): Double? {
    // A real backwards seek/loop, another occurrence, or an explicit seek owns its new position.
    if (!sameTimeline || seeking || incomingPosition < previous.anchorPositionSeconds) return null
    val held = if (incomingState == "buffering") maxOf(previous.displayedPositionSeconds, floor ?: 0.0) else floor
    return held?.takeIf { incomingState in setOf("playing", "buffering") && incomingPosition < it }
}

/** Clock bookkeeping belongs to the presenter, not to the library, theme or player chrome. */
internal fun PlayerUiState.withoutProgressTicks(): PlayerUiState = copy(
    sequence = 0L,
    anchorPositionSeconds = 0.0,
    anchorMonotonicMs = 0L,
    displayedPositionSeconds = 0.0,
    activeLyricIndex = -1
)
