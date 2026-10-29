package com.twilightecho.android.feature.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt

/** Explicit list resets reveal the header; merely returning to a saved page keeps its scroll state. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun rememberScrollHeaderState(resetKey: String): TopAppBarState {
    val state = rememberTopAppBarState()
    var previous by rememberSaveable { mutableStateOf(resetKey) }
    LaunchedEffect(resetKey) {
        if (previous != resetKey) {
            previous = resetKey
            state.heightOffset = 0f
            state.contentOffset = 0f
        }
    }
    return state
}

internal fun libraryHeaderResetKey(state: PlayerUiState): String =
    "${state.libraryDevice}:${if (state.desktopCatalogMode && state.activeLibraryTab in DesktopCatalogTabs) state.desktopLibrary.page?.offset ?: 0 else 0}"

/** Keeps the header's natural size while returning its collapsed space to the page below it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScrollAwayHeader(state: TopAppBarState, modifier: Modifier = Modifier,
    content: @Composable () -> Unit) {
    Layout(content = { Box(Modifier.fillMaxWidth()) { content() } }, modifier = modifier.clipToBounds()) { measurables, constraints ->
        val header = measurables.single().measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
        val limit = -header.height.toFloat()
        if (state.heightOffsetLimit != limit) {
            val collapsed = state.heightOffset == state.heightOffsetLimit
            state.heightOffsetLimit = limit
            state.heightOffset = if (collapsed) limit else state.heightOffset
        }
        val offset = state.heightOffset.roundToInt()
        layout(header.width, (header.height + offset).coerceAtLeast(0)) {
            header.placeRelative(0, offset)
        }
    }
}

/** Saved with the page: the output selector returns only after that page reaches its top. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaybackHeaderPage(state: PlayerUiState, actions: PlayerActions, resetKey: String, content: @Composable () -> Unit) {
    val visible = state.isDesktopLibrary || state.operatingMode is com.twilightecho.android.coreclient.CoreOperatingMode.RemoteDesktopCore
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberScrollHeaderState(resetKey))
    Column(Modifier.fillMaxSize().then(if (visible) Modifier.nestedScroll(scroll.nestedScrollConnection) else Modifier)) {
        if (visible) ScrollAwayHeader(scroll.state, Modifier.testTag("playbackHeader")) {
            LibraryPlaybackHeader(state, actions)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}
