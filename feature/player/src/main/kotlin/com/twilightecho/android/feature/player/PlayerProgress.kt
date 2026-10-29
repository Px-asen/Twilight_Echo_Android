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

/** Clock bookkeeping belongs to the presenter, not to the library, theme or player chrome. */
internal fun PlayerUiState.withoutProgressTicks(): PlayerUiState = copy(
    sequence = 0L,
    anchorPositionSeconds = 0.0,
    anchorMonotonicMs = 0L,
    displayedPositionSeconds = 0.0,
    activeLyricIndex = -1
)
