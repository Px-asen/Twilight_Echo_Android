package com.twilightecho.android.feature.player

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.twilightecho.android.coreclient.CoreBookmarkView
import com.twilightecho.android.coreclient.CoreClient
import com.twilightecho.android.coreclient.CoreLyricsView
import com.twilightecho.android.coreclient.CoreQueueEntryView
import com.twilightecho.android.coreclient.CoreQueueWindowView
import com.twilightecho.android.coreclient.CoreSnapshot
import com.twilightecho.android.coreclient.CoreTrackView
import com.twilightecho.android.coreclient.CoreTypedCommand
import com.twilightecho.android.coreclient.PlayMode
import com.twilightecho.android.playbackhost.AndroidPlaybackHost
import com.twilightecho.android.ui.components.formatDurationSeconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class AudioDiagnosticsUiModel(
    val codec: String = "FLAC",
    val sourceSampleRateHz: Int = 96000,
    val sourceBitDepth: Int = 24,
    val outputBackend: String = "oboe",
    val actualSampleRateHz: Int = 96000,
    val actualBitDepth: Int = 24,
    val outputPerfect: Boolean = true
)

data class PlayerUiState(
    val epoch: String = "epoch-boot",
    val sequence: Long = 0L,
    val queueRevision: Long = 0L,
    val isPlaying: Boolean = false,
    val playbackState: String = "stopped",
    val currentTrack: CoreTrackView? = null,
    val anchorPositionSeconds: Double = 0.0,
    val anchorMonotonicMs: Long = 0L,
    val displayedPositionSeconds: Double = 0.0,
    val durationSeconds: Double = 0.0,
    val playbackRate: Double = 1.0,
    val volume: Double = 1.0,
    val playMode: PlayMode = PlayMode.SEQUENCE,
    val diagnostics: AudioDiagnosticsUiModel = AudioDiagnosticsUiModel(),
    val queueItems: List<CoreQueueEntryView> = emptyList(),
    val currentQueueIndex: Int = 0,
    val isQueueDrawerOpen: Boolean = false,
    val isLyricsMode: Boolean = false,
    val isLibrarySheetOpen: Boolean = false,
    val favoriteTrackIds: List<String> = emptyList(),
    val isCurrentTrackFavorite: Boolean = false,
    val currentTrackBookmarks: List<CoreBookmarkView> = emptyList(),
    val currentLyrics: CoreLyricsView? = null,
    val activeLyricIndex: Int = -1,
    val libraryTrackCount: Int = 0,
    val searchQuery: String = "",
    val searchResults: List<CoreTrackView> = emptyList(),
    val isScanningLocalMusic: Boolean = false,
    val scanStatusMessage: String? = null,
    val errorBanner: String? = null
) {
    val interpolatedPositionSec: Double
        get() = displayedPositionSeconds
}

/**
 * Unidirectional Data Flow (UDF) ViewModel for the Player screen (Section 11.1, 13.5 + Phase 2).
 *
 * Projects `CoreSnapshot` + `CoreQueueWindowView` into `StateFlow<PlayerUiState>`, performs
 * monotonic display interpolation + real-time active lyric line tracking when `isPlaying == true`,
 * and dispatches Phase 2 Local MediaStore scanning, song search, favorites, and bookmarks.
 */
class PlayerViewModel(
    private val playbackHost: AndroidPlaybackHost,
    private val coreClient: CoreClient = playbackHost.primaryCoreClient
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            coreClient.reconcileState()
            refreshLibrarySearch("")
        }

        viewModelScope.launch {
            combine(coreClient.snapshot, coreClient.queueWindow) { snap, queueWin ->
                snap to queueWin
            }.collect { (snap, queueWin) ->
                if (snap != null) {
                    applyCoreSnapshot(snap, queueWin)
                }
            }
        }

        // Engine-clock display interpolation & active lyric highlight loop (10Hz UI tick)
        viewModelScope.launch {
            while (isActive) {
                delay(100L)
                _uiState.update { state ->
                    if (!state.isPlaying || state.durationSeconds <= 0.0) {
                        state
                    } else {
                        val elapsedSeconds =
                            ((nowMonotonicMs() - state.anchorMonotonicMs).coerceAtLeast(0L) / 1000.0) *
                                state.playbackRate
                        val interpolated = (state.anchorPositionSeconds + elapsedSeconds)
                            .coerceIn(0.0, state.durationSeconds)
                        val activeLyricIdx = computeActiveLyricIndex(
                            lyrics = state.currentLyrics,
                            positionSec = interpolated
                        )
                        state.copy(
                            displayedPositionSeconds = interpolated,
                            activeLyricIndex = activeLyricIdx
                        )
                    }
                }
            }
        }
    }

    fun onTogglePlayPause() {
        val currentlyPlaying = _uiState.value.isPlaying
        viewModelScope.launch {
            if (currentlyPlaying) {
                coreClient.executeCommand(CoreTypedCommand.Pause)
            } else {
                playbackHost.requestFocusAndPlay()
            }
        }
    }

    fun onSeekTo(positionSeconds: Double) {
        val clamped = positionSeconds.coerceIn(0.0, _uiState.value.durationSeconds.coerceAtLeast(0.0))
        val monoNow = nowMonotonicMs()
        _uiState.update {
            it.copy(
                anchorPositionSeconds = clamped,
                anchorMonotonicMs = monoNow,
                displayedPositionSeconds = clamped,
                activeLyricIndex = computeActiveLyricIndex(it.currentLyrics, clamped)
            )
        }
        viewModelScope.launch {
            coreClient.executeCommand(CoreTypedCommand.Seek(clamped))
        }
    }

    fun onNextTrack() {
        viewModelScope.launch {
            coreClient.executeCommand(CoreTypedCommand.Next)
        }
    }

    fun onPreviousTrack() {
        viewModelScope.launch {
            coreClient.executeCommand(CoreTypedCommand.Previous)
        }
    }

    fun onCyclePlayMode() {
        val nextMode = when (_uiState.value.playMode) {
            PlayMode.SEQUENCE -> PlayMode.LOOP
            PlayMode.LOOP -> PlayMode.SINGLE
            PlayMode.SINGLE -> PlayMode.SHUFFLE
            PlayMode.SHUFFLE, PlayMode.HEART -> PlayMode.SEQUENCE
        }
        viewModelScope.launch {
            coreClient.executeCommand(CoreTypedCommand.SetPlayMode(nextMode))
        }
    }

    fun onSelectQueueItem(index: Int, occurrenceId: String) {
        val revision = _uiState.value.queueRevision
        viewModelScope.launch {
            coreClient.executeCommand(
                CoreTypedCommand.SelectQueueIndex(
                    revision = revision,
                    index = index,
                    occurrenceId = occurrenceId
                )
            )
            playbackHost.requestFocusAndPlay(0.0)
        }
    }

    fun onToggleQueueDrawer(open: Boolean? = null) {
        _uiState.update { state ->
            val nextOpen = open ?: !state.isQueueDrawerOpen
            state.copy(
                isQueueDrawerOpen = nextOpen,
                isLibrarySheetOpen = if (nextOpen) false else state.isLibrarySheetOpen
            )
        }
    }

    fun onToggleLyricsMode(enabled: Boolean? = null) {
        _uiState.update { state ->
            state.copy(isLyricsMode = enabled ?: !state.isLyricsMode)
        }
    }

    fun onToggleLibrarySheet(open: Boolean? = null) {
        val targetOpen = open ?: !_uiState.value.isLibrarySheetOpen
        _uiState.update { state ->
            state.copy(
                isLibrarySheetOpen = targetOpen,
                isQueueDrawerOpen = if (targetOpen) false else state.isQueueDrawerOpen
            )
        }
        if (targetOpen) {
            refreshLibrarySearch(_uiState.value.searchQuery)
        }
    }

    fun onToggleFavoriteCurrentTrack() {
        val trackId = _uiState.value.currentTrack?.id ?: return
        viewModelScope.launch {
            coreClient.toggleFavorite(trackId)
        }
    }

    fun onAddBookmarkAtCurrentPosition(customLabel: String? = null) {
        val track = _uiState.value.currentTrack ?: return
        val posSec = _uiState.value.displayedPositionSeconds
        val label = customLabel?.takeIf { it.isNotBlank() }
            ?: "🔖 ${formatDurationSeconds(posSec)}"
        viewModelScope.launch {
            coreClient.addBookmark(
                trackId = track.id,
                positionSec = posSec,
                label = label
            )
        }
    }

    fun onRemoveBookmark(bookmarkId: String) {
        viewModelScope.launch {
            coreClient.removeBookmark(bookmarkId)
        }
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        refreshLibrarySearch(query)
    }

    fun onScanDeviceMusic() {
        if (_uiState.value.isScanningLocalMusic) return
        _uiState.update {
            it.copy(
                isScanningLocalMusic = true,
                scanStatusMessage = "正在扫描手机本地音乐 (Scanning MediaStore)..."
            )
        }
        viewModelScope.launch {
            val result = playbackHost.scanAndSyncDeviceMusic()
            result.fold(
                onSuccess = { count ->
                    _uiState.update {
                        it.copy(
                            isScanningLocalMusic = false,
                            scanStatusMessage = "已同步 $count 首手机本地音乐 (Synced $count local tracks)"
                        )
                    }
                    refreshLibrarySearch(_uiState.value.searchQuery)
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            isScanningLocalMusic = false,
                            scanStatusMessage = "扫描失败: ${err.message}"
                        )
                    }
                }
            )
        }
    }

    fun onPlayTrackFromLibrary(track: CoreTrackView) {
        val currentList = _uiState.value.searchResults.ifEmpty { listOf(track) }
        val startIndex = currentList.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        val expectedRev = _uiState.value.queueRevision
        viewModelScope.launch {
            coreClient.executeCommand(
                CoreTypedCommand.ReplaceQueue(
                    revision = expectedRev,
                    tracks = currentList,
                    startIndex = startIndex,
                    play = true
                )
            )
            playbackHost.requestFocusAndPlay(0.0)
            _uiState.update { it.copy(isLibrarySheetOpen = false) }
        }
    }

    private fun refreshLibrarySearch(query: String) {
        viewModelScope.launch {
            coreClient.searchTracks(query).onSuccess { page ->
                _uiState.update {
                    it.copy(
                        searchResults = page.items,
                        libraryTrackCount = maxOf(it.libraryTrackCount, page.total)
                    )
                }
            }
        }
    }

    private fun applyCoreSnapshot(snapshot: CoreSnapshot, queueWindow: CoreQueueWindowView) {
        val playback = snapshot.playback
        val isPlaying = playback?.state == "playing"
        val positionSeconds = playback?.position ?: 0.0
        val durationSeconds = playback?.duration?.takeIf { it > 0.0 }
            ?: snapshot.track?.duration
            ?: 0.0
        val monoNow = nowMonotonicMs()
        val currentTrackId = snapshot.track?.id

        val diagnostics = AudioDiagnosticsUiModel(
            codec = playback?.codec ?: snapshot.track?.format ?: "FLAC",
            sourceSampleRateHz = playback?.sourceSampleRate ?: snapshot.track?.sampleRate ?: 96000,
            sourceBitDepth = playback?.sourceBitDepth ?: snapshot.track?.bitDepth ?: 24,
            outputBackend = playback?.outputBackend ?: "oboe",
            actualSampleRateHz = playback?.actualSampleRate ?: 96000,
            actualBitDepth = playback?.actualBitDepth ?: 24,
            outputPerfect = playback?.outputPerfect ?: true
        )

        val trackBookmarks = if (currentTrackId != null) {
            snapshot.bookmarks.filter { it.trackId == currentTrackId }.sortedBy { it.positionSec }
        } else {
            emptyList()
        }

        val activeLyricIdx = computeActiveLyricIndex(snapshot.currentLyrics, positionSeconds)

        _uiState.update { current ->
            current.copy(
                epoch = snapshot.epoch,
                sequence = snapshot.sequence,
                queueRevision = snapshot.queue?.revision ?: queueWindow.revision,
                isPlaying = isPlaying,
                playbackState = playback?.state ?: "stopped",
                currentTrack = snapshot.track,
                anchorPositionSeconds = positionSeconds,
                anchorMonotonicMs = monoNow,
                displayedPositionSeconds = positionSeconds,
                durationSeconds = durationSeconds,
                playbackRate = playback?.playbackRate ?: 1.0,
                volume = playback?.volume ?: 1.0,
                playMode = PlayMode.fromWireValue(playback?.playMode),
                diagnostics = diagnostics,
                queueItems = queueWindow.items,
                currentQueueIndex = snapshot.queue?.index ?: playback?.queueIndex ?: 0,
                favoriteTrackIds = snapshot.favoriteTrackIds,
                isCurrentTrackFavorite = currentTrackId != null && currentTrackId in snapshot.favoriteTrackIds,
                currentTrackBookmarks = trackBookmarks,
                currentLyrics = snapshot.currentLyrics,
                activeLyricIndex = activeLyricIdx,
                libraryTrackCount = snapshot.libraryTrackCount,
                errorBanner = snapshot.error
            )
        }
    }

    private fun computeActiveLyricIndex(lyrics: CoreLyricsView?, positionSec: Double): Int {
        val lines = lyrics?.lines ?: return -1
        if (lines.isEmpty()) return -1
        val posMs = (positionSec * 1000.0).toLong()
        var activeIdx = 0
        for (i in lines.indices) {
            if (lines[i].timeMs <= posMs) {
                activeIdx = i
            } else {
                break
            }
        }
        return activeIdx
    }

    private fun nowMonotonicMs(): Long = runCatching {
        SystemClock.elapsedRealtime()
    }.getOrElse {
        System.nanoTime() / 1_000_000L
    }

    class Factory(
        private val playbackHost: AndroidPlaybackHost
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return PlayerViewModel(playbackHost = playbackHost) as T
        }
    }
}
