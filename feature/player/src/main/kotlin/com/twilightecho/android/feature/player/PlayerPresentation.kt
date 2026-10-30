package com.twilightecho.android.feature.player

import com.twilightecho.android.coreclient.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** UI data loading is independent of the playback host, and can be tested without native audio. */
internal data class LibraryContent(
    val tracks: List<CoreTrackView> = emptyList(),
    val total: Int = 0,
    val loading: Boolean = true,
    val error: String? = null,
    val query: String = "",
    val results: List<CoreTrackView> = emptyList(),
    val searching: Boolean = false,
    val searchError: String? = null,
    val offset: Int = 0, val searchOffset: Int = 0, val searchTotal: Int = 0
)

internal class LibraryContentLoader(
    private val scope: CoroutineScope,
    private val pageSearch: (suspend (String, Int) -> Result<CoreLibrarySearchPage>)? = null,
    private val search: suspend (String) -> Result<CoreLibrarySearchPage>
) {
    private val mutable = MutableStateFlow(LibraryContent())
    val state = mutable.asStateFlow()
    private var source = 0L
    private var libraryRequest = 0L
    private var searchRequest = 0L
    private var libraryJob: Job? = null
    private var searchJob: Job? = null

    fun refresh(resetSource: Boolean = false, offset: Int = 0) {
        if (resetSource) {
            source++
            searchRequest++
            searchJob?.cancel()
            mutable.update { LibraryContent(query = it.query) }
        }
        val origin = source
        val request = ++libraryRequest
        libraryJob?.cancel()
        mutable.update { it.copy(loading = true, error = null) }
        libraryJob = scope.launch {
            val result = pageSearch?.invoke("", offset) ?: search("")
            if (origin != source || request != libraryRequest) return@launch
            result.fold(
                onSuccess = { page ->
                    mutable.update { current ->
                        current.copy(tracks = page.items, total = page.total, loading = false, offset = page.offset,
                            searchTotal = if (current.query.isBlank()) page.total else current.searchTotal,
                            searchOffset = if (current.query.isBlank()) page.offset else current.searchOffset,
                            results = if (current.query.isBlank()) page.items else current.results,
                            searchError = if (current.query.isBlank()) null else current.searchError)
                    }
                    if (mutable.value.query.isNotBlank()) query(mutable.value.query, debounce = false)
                },
                onFailure = { error ->
                    mutable.update { it.copy(loading = false, error = error.message ?: "音乐库加载失败") }
                }
            )
        }
    }

    fun query(value: String, debounce: Boolean = true, offset: Int = 0) {
        val request = ++searchRequest
        val origin = source
        searchJob?.cancel()
        mutable.update { it.copy(query = value, searchError = null,
            results = if (value.isBlank()) it.tracks else emptyList(), searching = value.isNotBlank(),
            searchOffset = if (value.isBlank()) it.offset else offset,
            searchTotal = if (value.isBlank()) it.total else 0) }
        if (value.isBlank()) return
        searchJob = scope.launch {
            if (debounce) delay(180)
            val result = pageSearch?.invoke(value, offset) ?: search(value)
            if (origin != source || request != searchRequest) return@launch
            result.fold(
                onSuccess = { page -> mutable.update { it.copy(results = page.items, searching = false,
                    searchOffset = page.offset, searchTotal = page.total) } },
                onFailure = { error -> mutable.update {
                    it.copy(searching = false, searchError = error.message ?: "搜索失败，请重试")
                } }
            )
        }
    }
}

internal fun collectionTracks(ids: List<String>, tracks: List<CoreTrackView>): List<CoreTrackView> {
    val byId = tracks.associateBy { it.id }
    return ids.mapNotNull { byId[it] }
}

internal fun mergeQueueEntries(
    previous: List<CoreQueueEntryView>, next: List<CoreQueueEntryView>
): List<CoreQueueEntryView> {
    // Playback-clock snapshots normally carry the same window. Keep the list (and its
    // Compose row inputs) instead of rebuilding a map and sorting it on every clock tick.
    if (previous == next || next.isEmpty()) return previous
    return (previous + next).associateBy { it.occurrenceId }.values.sortedBy { it.index }
}

internal fun nextQueueOffset(items: List<CoreQueueEntryView>, total: Int): Int? {
    val indices = items.mapTo(HashSet()) { it.index }
    return (0 until total).firstOrNull { it !in indices }
}

internal fun filteredSearchTracks(state: PlayerUiState): List<CoreTrackView> =
    state.searchResults.filter { track -> when (state.activeSearchFilter) {
        SearchFilterCategory.ALL -> true
        SearchFilterCategory.LOSSLESS -> track.format?.uppercase() in setOf("FLAC", "WAV", "ALAC", "DSD", "AIFF")
        SearchFilterCategory.FAVORITES -> track.id in state.favoriteTrackIds
    } }

internal fun diagnosticsFor(snapshot: CoreSnapshot): AudioDiagnosticsUiModel {
    val p = snapshot.playback
    return AudioDiagnosticsUiModel(
        codec = p?.codec?.takeUnless { it.isBlank() || it.equals("unknown", true) || it == "未知" }
            ?: snapshot.track?.format.orEmpty(),
        sourceSampleRateHz = p?.sourceSampleRate ?: snapshot.track?.sampleRate ?: 0,
        sourceBitDepth = p?.sourceBitDepth ?: snapshot.track?.bitDepth ?: 0,
        outputBackend = p?.outputBackend.orEmpty(),
        actualSampleRateHz = p?.actualSampleRate ?: 0,
        actualBitDepth = p?.actualBitDepth ?: 0,
        actualSampleFormat = p?.actualSampleFormat,
        requestedSampleRateHz = p?.requestedSampleRate ?: 0,
        usbPcmPreferenceAccepted = p?.usbPcmPreferenceAccepted == true,
        usbPcmFormatMatched = p?.usbPcmFormatMatched == true,
        requestedSampleFormat = p?.requestedSampleFormat,
        hardwareSampleRateHz = p?.hardwareSampleRate ?: 0,
        hardwareBitDepth = p?.hardwareBitDepth ?: 0,
        hardwareSampleFormat = p?.hardwareSampleFormat,
        sourceChannels = p?.sourceChannels ?: 0,
        actualChannels = p?.actualChannels ?: 0,
        hardwareChannels = p?.hardwareChannels ?: 0,
        outputPerfect = p?.outputPerfect == true && (p.actualSampleRate ?: 0) > 0,
        perfectReason = p?.perfectReason, perfectReasonCode = p?.perfectReasonCode,
        exclusive = p?.exclusive == true, deviceName = p?.actualDeviceName,
        latencyMs = p?.latencyMs ?: 0.0, bufferFrames = p?.bufferSizeFrames ?: 0,
        dspActive = p?.dspActive == true || p?.eqActive == true, gaplessActive = p?.gaplessActive == true,
        preloadReady = p?.preloadReady == true, crossfadeMixActive = p?.crossfadeMixActive == true,
        crossfadeBlockedReason = p?.crossfadeBlockedReason,
        autoMixMode = p?.autoMixMode ?: "off", autoMixState = p?.autoMixState ?: "off",
        autoMixReason = p?.autoMixReason.orEmpty(), autoMixStrategy = p?.autoMixStrategy.orEmpty(),
        autoMixDurationSeconds = p?.autoMixDurationSeconds ?: 0.0
    )
}

/** The decoder's source facts take precedence over catalog metadata; output resampling is separate. */
internal fun sourceQualityLabel(state: PlayerUiState): String? {
    val track = state.currentTrack ?: return null
    val diagnostics = state.diagnostics
    fun codec(value: String?) = value?.trim()?.takeUnless {
        it.isBlank() || it.equals("unknown", true) || it == "未知"
    }?.uppercase(java.util.Locale.ROOT)
    val format = codec(diagnostics.codec) ?: codec(track.format)
    val rate = diagnostics.sourceSampleRateHz.takeIf { it > 0 } ?: track.sampleRate?.takeIf { it > 0 }
    val depth = diagnostics.sourceBitDepth.takeIf { it > 0 } ?: track.bitDepth?.takeIf { it > 0 }
    val khz = rate?.let {
        java.math.BigDecimal.valueOf(it.toLong(), 3).stripTrailingZeros().toPlainString()
    }
    val resolution = when {
        depth != null && khz != null -> "$depth/$khz"
        depth != null -> "$depth bit"
        khz != null -> "$khz kHz"
        else -> null
    }
    return listOfNotNull(format, resolution).joinToString(" ").takeIf { it.isNotBlank() }
}

/** Event boundary for the complete UI. Previews and tests supply only the actions they exercise. */
data class PlayerActions(
    val streaming: StreamingActions = StreamingActions(),
    val openPlugins: () -> Unit = {},
    val playbackTarget: (Boolean) -> Unit = {},
    val startDiscovery: () -> Unit = {}, val stopDiscovery: () -> Unit = {},
    val refreshDiscovery: () -> Unit = {}, val cancelConnection: () -> Unit = {},
    val selectDesktop: (com.twilightecho.android.coreclient.DesktopCoreDevice) -> Unit = {},
    val forgetDesktop: (com.twilightecho.android.coreclient.DesktopCoreDevice) -> Unit = {},
    val selectLibraryDevice: (String) -> Unit = {},
    val disconnectStreaming: () -> Unit = {}, val disconnectReconnect: () -> Unit = {},
    val dismissDisconnect: () -> Unit = {},
    val catalogPage: (Int) -> Unit = {}, val openEntity: (CatalogEntity) -> Unit = {},
    val closeEntity: () -> Unit = {}, val sourcePage: (Int) -> Unit = {},
    val artistAlbums: (Boolean) -> Unit = {}, val retryArtistAlbums: () -> Unit = {},
    val loadLibraryArtist: (String, Boolean) -> Unit = { _, _ -> },
    val playSource: (CatalogSource, Long) -> Unit = { _, _ -> },
    val requestArtwork: (String) -> Unit = {},
    val lookupArtwork: (String) -> String? = { null },
    val selectTab: (MainDestinationTab) -> Unit = {},
    val selectLibraryTab: (LibrarySectionTab) -> Unit = {},
    val setPlayerOpen: (Boolean) -> Unit = {},
    val setLyricsMode: (Boolean) -> Unit = {},
    val importLyrics: () -> Unit = {},
    val setQueueOpen: (Boolean) -> Unit = {},
    val loadMoreQueue: () -> Unit = {},
    val scan: () -> Unit = {},
    val addMusicFolder: () -> Unit = {},
    val removeMusicFolder: (String) -> Unit = {},
    val refreshLibrary: () -> Unit = {},
    val query: (String) -> Unit = {},
    val retrySearch: () -> Unit = {},
    val selectFilter: (SearchFilterCategory) -> Unit = {},
    val searchSource: (SearchSource) -> Unit = {},
    val searchCategory: (NcmSearchCategory) -> Unit = {},
    val searchPage: (Int) -> Unit = {}, val submitSearch: () -> Unit = {},
    val playTracks: (List<CoreTrackView>, String) -> Unit = { _, _ -> },
    val togglePlayback: () -> Unit = {},
    val previous: () -> Unit = {},
    val next: () -> Unit = {},
    val cyclePlayMode: () -> Unit = {},
    val seek: (Double) -> Unit = {},
    val toggleFavorite: () -> Unit = {},
    val selectQueueEntry: (CoreQueueEntryView) -> Unit = {},
    val addBookmark: () -> Unit = {},
    val removeBookmark: (String) -> Unit = {},
    val playlistName: (String) -> Unit = {},
    val createPlaylist: () -> Unit = {},
    val addToPlaylist: (String) -> Unit = {},
    val deletePlaylist: (String) -> Unit = {},
    val playNext: (CoreTrackView) -> Unit = {},
    val addTrackToLocalPlaylist: (String, String, CoreTrackView) -> Unit = { _, _, _ -> },
    val createLocalPlaylistWithTracks: (String, List<CoreTrackView>) -> Unit = { _, _ -> },
    val removeTrackFromLocalPlaylist: (String, CoreTrackView) -> Unit = { _, _ -> },
    val deleteLocalTrack: (CoreTrackView) -> Unit = {},
    val resetLocalMusicLibrary: (Boolean) -> Unit = {},
    val restoreExcludedLocalTracks: () -> Unit = {},
    val inspectTrack: (CoreTrackView) -> Unit = {},
    val dismissTrackInspection: () -> Unit = {},
    val removeQueueEntry: (CoreQueueEntryView) -> Unit = {},
    val toggleEq: () -> Unit = {},
    val preset: (String) -> Unit = {},
    val band: (Int, Double) -> Unit = { _, _ -> },
    val preamp: (Double) -> Unit = {},
    val toggleBitPerfect: () -> Unit = {},
    val toggleGapless: () -> Unit = {},
    val autoMixMode: (String) -> Unit = {},
    val editDsp: ((com.twilightecho.android.coreclient.CoreDspConfig) -> com.twilightecho.android.coreclient.CoreDspConfig) -> Unit = {},
    val editAudio: ((CoreEqConfigView) -> CoreEqConfigView) -> Unit = {},
    val monitorAudio: (Boolean) -> Unit = {},
    val importImpulse: (String) -> Unit = {},
    val saveDspScene: (String) -> Unit = {}, val applyDspScene: (String) -> Unit = {}, val deleteDspScene: (String) -> Unit = {},
    val reapplyOutputPreset: () -> Unit = {},
    val remoteHost: (String) -> Unit = {},
    val remotePin: (String) -> Unit = {},
    val connectRemote: () -> Unit = {},
    val useLocal: () -> Unit = {},
    val dismissError: () -> Unit = {},
    val editPreferences: ((com.twilightecho.android.coreclient.UserPreferences) -> com.twilightecho.android.coreclient.UserPreferences) -> Unit = {},
    val pickBackground: () -> Unit = {}
)
