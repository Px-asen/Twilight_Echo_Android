package com.twilightecho.android.feature.player

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.twilightecho.android.adapters.MusicFolder
import com.twilightecho.android.coreclient.CoreAlbumCollectionView
import com.twilightecho.android.coreclient.CoreArtistCollectionView
import com.twilightecho.android.coreclient.CoreBookmarkView
import com.twilightecho.android.coreclient.CoreClient
import com.twilightecho.android.coreclient.CoreErrorCode
import com.twilightecho.android.coreclient.CoreEqConfigView
import com.twilightecho.android.coreclient.CoreDspConfig
import com.twilightecho.android.coreclient.CoreDspStatus
import com.twilightecho.android.coreclient.CoreDspScene
import com.twilightecho.android.coreclient.DefaultCoreClient
import com.twilightecho.android.coreclient.CoreLyricsView
import com.twilightecho.android.coreclient.CoreOperatingMode
import com.twilightecho.android.coreclient.LibrarySource
import com.twilightecho.android.coreclient.CatalogTrackReferences
import com.twilightecho.android.coreclient.CorePlaylistView
import com.twilightecho.android.coreclient.CoreQueueEntryView
import com.twilightecho.android.coreclient.CoreQueueWindowView
import com.twilightecho.android.coreclient.CoreSnapshot
import com.twilightecho.android.coreclient.CoreTrackView
import com.twilightecho.android.coreclient.CoreTypedCommand
import com.twilightecho.android.coreclient.CoreResult
import com.twilightecho.android.coreclient.CatalogEntity
import com.twilightecho.android.coreclient.CatalogSource
import com.twilightecho.android.coreclient.RemoteConnectionPhase
import com.twilightecho.android.coreclient.PlayMode
import com.twilightecho.android.coreclient.DesktopCoreDevice
import com.twilightecho.android.coreclient.DesktopDiscoveryState
import com.twilightecho.android.coreclient.DesktopMediaReference
import com.twilightecho.android.coreclient.mergeDesktopDevices
import com.twilightecho.android.coreclient.withScene
import com.twilightecho.android.coreclient.TrackArtworkPage
import com.twilightecho.android.coreclient.TrackArtworkPreview
import com.twilightecho.android.playbackhost.AndroidPlaybackHost
import com.twilightecho.android.ui.components.formatDurationSeconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.serialization.json.*
import java.util.UUID

data class AudioDiagnosticsUiModel(
    val codec: String = "",
    val sourceSampleRateHz: Int = 0,
    val sourceBitDepth: Int = 0,
    val outputBackend: String = "",
    val actualSampleRateHz: Int = 0,
    val actualBitDepth: Int = 0,
    val outputPerfect: Boolean = false,
    val perfectReason: String? = null,
    val perfectReasonCode: String? = null,
    val exclusive: Boolean = false,
    val deviceName: String? = null,
    val latencyMs: Double = 0.0,
    val bufferFrames: Int = 0,
    val dspActive: Boolean = false,
    val gaplessActive: Boolean = false,
    val preloadReady: Boolean = false,
    val crossfadeMixActive: Boolean = false,
    val crossfadeBlockedReason: String? = null,
    val actualSampleFormat: String? = null,
    val requestedSampleRateHz: Int = 0,
    val requestedSampleFormat: String? = null,
    val hardwareSampleRateHz: Int = 0,
    val hardwareBitDepth: Int = 0,
    val hardwareSampleFormat: String? = null,
    val sourceChannels: Int = 0,
    val actualChannels: Int = 0,
    val hardwareChannels: Int = 0,
    /** The phone's Bluetooth link while it plays to a Bluetooth output: "a2dp", "le_audio" or "call". */
    val bluetoothProfile: String = ""
)

data class AudioOutputDeviceUi(val id: String, val name: String, val sampleRates: List<Int> = emptyList())

enum class LibrarySectionTab(val label: String) {
    SONGS("歌曲"),
    ALBUMS("专辑"),
    ARTISTS("艺术家"),
    PLAYLISTS("歌单"),
    STREAMING("流媒体")
}

enum class MainDestinationTab(val label: String) {
    HOME("首页"),
    LIBRARY("音乐库"),
    SEARCH("搜索"),
    SETTINGS("设置")
}

enum class SearchFilterCategory(val label: String) {
    ALL("全部"),
    LOSSLESS("无损"),
    FAVORITES("收藏")
}

data class PlayerUiState(
    val epoch: String = "epoch-boot",
    val sequence: Long = 0L,
    val queueRevision: Long = 0L,
    val isPlaying: Boolean = false,
    /** Immediate button feedback while the Core acknowledges a play/pause request. */
    val requestedPlaying: Boolean? = null,
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
    val queueId: String = "default",
    val queueTotal: Int = 0,
    val isLoadingQueue: Boolean = false,
    val queueError: String? = null,
    val currentQueueIndex: Int = 0,
    val activeMainTab: MainDestinationTab = MainDestinationTab.HOME,
    val isImmersivePlayerOpen: Boolean = false,
    val activeSearchFilter: SearchFilterCategory = SearchFilterCategory.ALL,
    val isQueueDrawerOpen: Boolean = false,
    val isLyricsMode: Boolean = false,
    val isLibrarySheetOpen: Boolean = false,
    val isEqSheetOpen: Boolean = false,
    val activeLibraryTab: LibrarySectionTab = LibrarySectionTab.SONGS,
    val favoriteTrackIds: List<String> = emptyList(),
    val isCurrentTrackFavorite: Boolean = false,
    val isFavoriteBusy: Boolean = false,
    val currentTrackBookmarks: List<CoreBookmarkView> = emptyList(),
    val playlists: List<CorePlaylistView> = emptyList(),
    val albums: List<CoreAlbumCollectionView> = emptyList(),
    val artists: List<CoreArtistCollectionView> = emptyList(),
    val eqConfig: CoreEqConfigView = CoreEqConfigView(),
    val dspStatus: CoreDspStatus = CoreDspStatus(),
    val audioLevels: List<Float> = emptyList(),
    val audioDevices: List<AudioOutputDeviceUi> = emptyList(),
    val activeOutputSampleRates: List<Int> = emptyList(),
    val audioMonitorError: String? = null,
    val newPlaylistNameDraft: String = "",
    val currentLyrics: CoreLyricsView? = null,
    val activeLyricIndex: Int = -1,
    val libraryTrackCount: Int = 0,
    val libraryTracks: List<CoreTrackView> = emptyList(),
    val isLoadingLibrary: Boolean = true,
    val libraryError: String? = null,
    val searchQuery: String = "",
    val searchResults: List<CoreTrackView> = emptyList(),
    val isSearching: Boolean = false,
    val searchError: String? = null,
    val searchSource: SearchSource = SearchSource.LIBRARY,
    val ncmSearch: NcmSearchState = NcmSearchState(),
    val isScanningLocalMusic: Boolean = false,
    val scanStatusMessage: String? = null,
    /** Folders that could not be read and CUE sheets that split nothing, each with why. */
    val scanDetails: List<String> = emptyList(),
    val musicFolders: List<MusicFolder> = emptyList(),
    val operatingMode: CoreOperatingMode = CoreOperatingMode.StandaloneLocal,
    val librarySource: LibrarySource = LibrarySource.Phone,
    val isRemoteCoreSheetOpen: Boolean = false,
    val remoteHostInput: String = "",
    val remotePinInput: String = "",
    val isConnectingRemoteCore: Boolean = false,
    val desktopDiscovery: DesktopDiscoveryState = DesktopDiscoveryState(),
    val connectingDesktopId: String? = null,
    val waitingForDesktopApproval: Boolean = false,
    /** Six digits the desktop's approval prompt shows too; they differ if someone sits in between. */
    val desktopApprovalCode: String? = null,
    val remoteStatusMessage: String? = null,
    val errorBanner: String? = null,
    val desktopLibrary: DesktopBrowseState = DesktopBrowseState(),
    val libraryArtist: LibraryArtistProfile = LibraryArtistProfile(),
    val streaming: StreamingUiState = StreamingUiState(),
    val remoteConnectionPhase: RemoteConnectionPhase = RemoteConnectionPhase.CLOSED,
    /** Whose songs the library lists: [LIBRARY_ALL], [LIBRARY_PHONE] or a computer's server ID. */
    val libraryDevice: String = LIBRARY_ALL,
    /** Empty until the phone knows a computer, so a phone-only library needs no device menu. */
    val libraryDevices: List<LibraryDeviceOption> = emptyList(),
    /** Every song once across all devices; search and playlists read from here. */
    val allTracks: List<CoreTrackView> = emptyList(),
    /** Songs stored on this phone, whichever device the library shows. */
    val phoneTrackCount: Int = 0,
    val disconnectPrompt: DisconnectPrompt? = null,
    /** Home is the streaming page, so it only exists while a streaming provider is enabled; null until known. */
    val homeAvailable: Boolean? = true,
    val preferences: com.twilightecho.android.coreclient.UserPreferences = com.twilightecho.android.coreclient.UserPreferences(),
    val isImportingBackground: Boolean = false,
    val outputPreset: com.twilightecho.android.coreclient.OutputPresetStatus = com.twilightecho.android.coreclient.OutputPresetStatus()
) {
    val isDesktopLibrary: Boolean get() = librarySource is LibrarySource.Desktop
    /** The library shows exactly one connected computer, whose own albums and artists can be browsed. */
    val desktopCatalogMode: Boolean get() =
        (librarySource as? LibrarySource.Desktop)?.let { it.connected && it.serverId == libraryDevice } == true
    val interpolatedPositionSec: Double
        get() = displayedPositionSeconds
    val playbackButtonPlaying: Boolean get() = requestedPlaying ?: isPlaying
}

/** A song on a computer that cannot be reached right now, and the ways to still hear it. */
data class DisconnectPrompt(
    val track: CoreTrackView, val serverId: String, val hostLabel: String,
    val streamingFallback: Boolean = false, val searching: Boolean = false,
    val match: CoreTrackView? = null, val reconnecting: Boolean = false
)

private data class LibraryInputs(val phone: LibraryContent, val desktops: List<DesktopShelf>, val device: String)

/** What the user tried to play when the song's computer was offline, replayed after reconnecting. */
private class PendingPlay(val tracks: List<CoreTrackView>, val startId: String, val owners: Map<String, String>)

/** The desktop accepts at most this many songs in one queue replacement. */
private const val DESKTOP_QUEUE_LIMIT = 200
private const val RECONNECT_WAIT_MS = 5_000L

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

    private val _uiState = MutableStateFlow(PlayerUiState(homeAvailable = null, preferences = playbackHost.userPreferences.state.value))
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()
    internal val pluginPresenter = PluginPresenter(viewModelScope, playbackHost.plugins)
    internal val streamingPresenter = StreamingPresenter(viewModelScope) { method, args ->
        playbackHost.plugins.call(NCM_PLUGIN_ID, method, args)
    }
    private val ncmSearch = NcmSearchPresenter(viewModelScope) { method, args ->
        playbackHost.plugins.call(NCM_PLUGIN_ID, method, args)
    }
    // The phone's own songs; computer songs come from the lists the playback host remembers per computer.
    private val libraryLoader = LibraryContentLoader(viewModelScope, search = { playbackHost.phoneLibraryClient.searchTracks(it) })
    private val desktopLibrary = DesktopLibraryPresenter(viewModelScope, playbackHost.desktopLibrary, ::desktopArtwork)
    private val libraryArtist = LibraryArtistPresenter(viewModelScope) { artist ->
        val members = artist.trackIds.toHashSet()
        val online = playbackHost.connectedDesktops.value
        val desktop = playbackHost.desktopLibraries.value.firstOrNull { index ->
            index.serverId in online && index.tracks.any { it.id in members }
        }
        if (desktop == null) Result.success(null) else playbackHost.desktopArtistMetadata(desktop.serverId,
            desktop.tracks.filter { it.id in members }.mapNotNull { it.catalog?.sourceId }.toSet(), artist.name)
    }
    private val libraryDevice = MutableStateFlow(LIBRARY_ALL)
    private val searchText = MutableStateFlow("")
    private val sharedTracks = MutableStateFlow<List<CoreTrackView>>(emptyList())
    /** Remembered computer songs by ID → the computer they live on. */
    @Volatile private var trackOwners: Map<String, String> = emptyMap()
    private var phoneLibraryRevision: String? = null
    private var pendingPlay: PendingPlay? = null
    private var promptJob: Job? = null
    private var queueJob: Job? = null
    private var playTracksJob: Job? = null
    private var desktopConnectJob: Job? = null
    private var sourceGeneration = 0L
    private var loadedSource: String? = null
    private var lastPresentationRevision: Long? = null
    private var libraryArtistRevisions: Pair<Long?, Long?>? = null
    private var loadedLibrary: String? = null
    // Output presets edit the same config from the playback host.
    private val audioSettingsMutex: Mutex get() = playbackHost.audioConfigMutex
    private var audioMonitorJob: Job? = null
    private var presentationActive = false
    private var playPauseRequest = 0L
    private val playPauseMutex = Mutex()
    private var seekRequest = 0L
    private val seekMutex = Mutex()
    @Volatile private var pendingSeek: PendingSeekHold? = null
    private val attemptedAutoLyricsTrackIds: MutableSet<String> =
        java.util.Collections.synchronizedSet(LinkedHashSet())

    init {
        viewModelScope.launch {
            playbackHost.userPreferences.state.collect { preferences ->
                streamingPresenter.restoreSorts(preferences.collectionSorts)
                _uiState.update { it.copy(preferences = preferences) }
            }
        }
        viewModelScope.launch { playbackHost.outputPresets.status.collect { status -> _uiState.update { it.copy(outputPreset = status) } } }
        viewModelScope.launch { playbackHost.musicFolders.collect { folders -> _uiState.update { it.copy(musicFolders = folders) } } }
        viewModelScope.launch {
            playbackHost.outputRoute.collect { route ->
                _uiState.update { it.copy(diagnostics = it.diagnostics.copy(bluetoothProfile = route.bluetoothProfile),
                    activeOutputSampleRates = route.supportedSampleRates) }
            }
        }
        pluginPresenter.initialize()
        viewModelScope.launch {
            playbackHost.plugins.state.map { repository ->
                repository.installed.find { it.id == NCM_PLUGIN_ID }?.let { true to it.enabled } ?: (false to false)
            }.distinctUntilChanged().collect { (installed, enabled) ->
                streamingPresenter.availability(installed, enabled)
                ncmSearch.availability(enabled)
                if (enabled) playbackHost.adoptDesktopProviderSessions()
            }
        }
        // Home only shows streaming music, so it is hidden until a streaming provider is installed and enabled.
        viewModelScope.launch {
            playbackHost.plugins.state.map { repository ->
                if (!repository.ready) null else repository.installed.any { it.id == NCM_PLUGIN_ID && it.enabled }
            }.distinctUntilChanged().collect { available ->
                if (available == null) return@collect
                _uiState.update { state -> state.copy(homeAvailable = available,
                    activeMainTab = if (!available && state.activeMainTab == MainDestinationTab.HOME) MainDestinationTab.LIBRARY
                        else state.activeMainTab) }
            }
        }
        viewModelScope.launch {
            playbackHost.providerSessionAdopted.collect { host ->
                streamingPresenter.refreshAccount()
                _uiState.update { it.copy(errorBanner = "已同步「$host」上的网易云账号") }
            }
        }
        viewModelScope.launch { streamingPresenter.state.collect { streaming -> _uiState.update { current ->
            val cloud = current.currentTrack?.source?.startsWith("provider://ncm/") == true
            current.copy(streaming = streaming, isCurrentTrackFavorite = if (cloud)
                streaming.favoriteTrackId == current.currentTrack?.source?.substringAfterLast('/') && streaming.favoriteLiked == true
                else current.isCurrentTrackFavorite,
                isFavoriteBusy = cloud && streaming.favoriteBusy)
        } } }
        viewModelScope.launch { streamingPresenter.state.map { it.accountId }.distinctUntilChanged().collect(ncmSearch::account) }
        viewModelScope.launch { ncmSearch.state.collect { search -> _uiState.update { it.copy(ncmSearch = search) } } }
        viewModelScope.launch { libraryArtist.state.collect { artist -> _uiState.update { it.copy(libraryArtist = artist) } } }
        viewModelScope.launch { streamingPresenter.state.map { it.favoriteError }.distinctUntilChanged().collect { error ->
            if (error != null) { _uiState.update { it.copy(errorBanner = error) }; streamingPresenter.clearFavoriteError() }
        } }
        viewModelScope.launch {
            uiState.map { it.currentTrack }.distinctUntilChangedBy { it?.id }.collect { track ->
                streamingPresenter.observeTrack(track)
            }
        }
        viewModelScope.launch {
            combine(
                uiState.map { it.currentTrack }.distinctUntilChangedBy { it?.id },
                playbackHost.operatingMode,
                coreClient.snapshot.map { Triple(it?.epoch, it?.lyricsRevision, it?.lyricsContentRevision) }.distinctUntilChanged()
            ) { track, mode, revisions -> Triple(track, mode, revisions) }
                .collectLatest { (track, mode, _) ->
                    if (track != null) resolveLyricsForTrack(track, mode)
                }
        }
        val savedHost = playbackHost.getSavedRemoteDesktopUrl()
            ?: ""
        _uiState.update { it.copy(remoteHostInput = savedHost) }
        viewModelScope.launch {
            playbackHost.desktopReconnectNotice.filterNotNull().collect { notice ->
                _uiState.update { it.copy(remoteStatusMessage = notice) }
            }
        }
        viewModelScope.launch {
            combine(playbackHost.desktopDiscovery.state, playbackHost.knownDesktops) { discovery, known ->
                discovery.copy(devices = mergeDesktopDevices(discovery.devices, known))
            }.collect { discovery -> _uiState.update { it.copy(desktopDiscovery = discovery) } }
        }

        viewModelScope.launch {
            playbackHost.operatingMode.collect { mode ->
                fun key(value: CoreOperatingMode) = (value as? CoreOperatingMode.RemoteDesktopCore)?.baseUrl ?: "local"
                val changed = key(mode) != key(_uiState.value.operatingMode)
                _uiState.update { it.copy(operatingMode = mode) }
                if (changed) {
                    sourceGeneration++
                    pendingSeek = null
                    attemptedAutoLyricsTrackIds.clear()
                    queueJob?.cancel()
                    playTracksJob?.cancel()
                    _uiState.update { it.copy(queueItems = emptyList(), isLoadingQueue = false,
                        currentTrack = null, isPlaying = false) }
                }
            }
        }

        viewModelScope.launch {
            playbackHost.librarySource.collect { source ->
                val browsing = desktopCatalogMode()
                _uiState.update { it.copy(librarySource = source) }
                if (browsing && _uiState.value.desktopLibrary.page == null) browseDesktopCatalog(_uiState.value.activeLibraryTab)
            }
        }
        viewModelScope.launch {
            playbackHost.libraryCoreClient.snapshot.collect { snapshot ->
                if (snapshot == null) return@collect
                // Songs come from the remembered lists; this source only feeds the computer's album and artist pages.
                val key = "${playbackHost.libraryTransport.generation}:${snapshot.epoch}"
                if (loadedLibrary != key) {
                    loadedLibrary = key
                    desktopLibrary.reset()
                    libraryArtist.reset()
                    if (desktopCatalogMode()) browseDesktopCatalog(_uiState.value.activeLibraryTab)
                }
                if (playbackHost.librarySource.value is LibrarySource.Desktop) desktopLibrary.observe(snapshot.catalogRevision, snapshot.entityMetadataRevision)
                val artistRevisions = snapshot.catalogRevision to snapshot.entityMetadataRevision
                if (libraryArtistRevisions != null && libraryArtistRevisions != artistRevisions) libraryArtist.reset()
                libraryArtistRevisions = artistRevisions
                if (lastPresentationRevision != snapshot.presentationRevision) {
                    lastPresentationRevision = snapshot.presentationRevision; desktopLibrary.refreshArtwork(); libraryArtist.reset()
                }
            }
        }
        viewModelScope.launch {
            playbackHost.phoneLibraryClient.snapshot.collect { snapshot ->
                if (snapshot == null) return@collect
                val revision = "${snapshot.epoch}:${snapshot.libraryRevision}"
                if (phoneLibraryRevision != revision) {
                    phoneLibraryRevision = revision
                    libraryLoader.refresh()
                }
                if (_uiState.value.playlists != snapshot.playlists) _uiState.update { it.copy(playlists = snapshot.playlists) }
            }
        }
        // One library: the phone's songs plus every remembered computer's, narrowed to the chosen device.
        viewModelScope.launch {
            combine(libraryLoader.state, playbackHost.desktopLibraries, playbackHost.connectedDesktops, libraryDevice) { phone, indexes, online, device ->
                LibraryInputs(phone, indexes.map { DesktopShelf(it.serverId, it.hostLabel, it.serverId in online, it.tracks) }, device)
            }.collectLatest { input ->
                val device = input.device.takeIf { it == LIBRARY_ALL || it == LIBRARY_PHONE || input.desktops.any { d -> d.serverId == it } }
                    ?: LIBRARY_ALL
                val (all, view) = withContext(Dispatchers.Default) {
                    val all = sharedLibrary(input.phone.tracks, input.desktops, LIBRARY_ALL)
                    all to if (device == LIBRARY_ALL) all else sharedLibrary(input.phone.tracks, input.desktops, device)
                }
                trackOwners = desktopOwners(input.desktops)
                sharedTracks.value = all.tracks
                val devices = if (input.desktops.isEmpty()) emptyList() else
                    listOf(LibraryDeviceOption(LIBRARY_ALL, "全部设备"), LibraryDeviceOption(LIBRARY_PHONE, "此手机")) +
                        input.desktops.map { LibraryDeviceOption(it.serverId, it.hostLabel, it.connected) }
                if (_uiState.value.artists != view.artists || _uiState.value.libraryDevices != devices) libraryArtist.reset()
                _uiState.update { it.copy(libraryDevice = device, libraryDevices = devices, allTracks = all.tracks,
                    phoneTrackCount = input.phone.tracks.size,
                    libraryTracks = view.tracks, libraryTrackCount = view.tracks.size, albums = view.albums, artists = view.artists,
                    isLoadingLibrary = input.phone.loading && view.tracks.isEmpty(),
                    libraryError = input.phone.error?.takeIf { view.tracks.isEmpty() }) }
            }
        }
        viewModelScope.launch {
            combine(sharedTracks, searchText) { tracks, query -> tracks to query }.collectLatest { (tracks, query) ->
                if (query.isNotBlank()) delay(150)
                val results = withContext(Dispatchers.Default) { searchSharedLibrary(tracks, query) }
                _uiState.update { if (it.searchQuery != query) it else it.copy(searchResults = results, isSearching = false, searchError = null) }
            }
        }
        // A computer that comes back can serve the covers that failed while it was away.
        viewModelScope.launch { playbackHost.connectedDesktops.drop(1).collect { desktopLibrary.refreshArtwork() } }

        viewModelScope.launch {
            coreClient.reconcileState()
        }

        viewModelScope.launch { desktopLibrary.state.collect { data ->
            _uiState.update { it.copy(desktopLibrary = data) }
        } }
        viewModelScope.launch { playbackHost.remoteConnection.collect { connection ->
            if (connection.phase == RemoteConnectionPhase.CONNECTED &&
                _uiState.value.remoteConnectionPhase == RemoteConnectionPhase.RECONNECTING) desktopLibrary.refreshArtwork()
            _uiState.update { it.copy(remoteConnectionPhase = connection.phase,
                remoteStatusMessage = connection.message ?: it.remoteStatusMessage) }
        } }

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
                    if (!presentationActive || !state.isImmersivePlayerOpen || !state.isPlaying || state.durationSeconds <= 0.0) {
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
        val play = !_uiState.value.playbackButtonPlaying
        val request = ++playPauseRequest
        val origin = playbackHost.switchableTransport.generation
        _uiState.update { it.copy(requestedPlaying = play) }
        viewModelScope.launch {
            try {
                // A native Pause may still be fading out. Finish it before submitting Play,
                // and skip intermediate taps that have not started. The latest tap wins.
                playPauseMutex.withLock {
                    if (request == playPauseRequest && origin == playbackHost.switchableTransport.generation) {
                        val result = if (play) playbackHost.requestFocusAndPlay()
                            else coreClient.executeCommand(CoreTypedCommand.Pause)
                        if (request == playPauseRequest) report(result)
                    }
                }
            } finally {
                if (request == playPauseRequest) _uiState.update { it.copy(requestedPlaying = null) }
            }
        }
    }

    /** Only the visible full-screen player needs the 10 Hz interpolated position. */
    fun onPresentationActive(active: Boolean) { presentationActive = active }

    fun onSeekTo(positionSeconds: Double) {
        val clamped = positionSeconds.coerceIn(0.0, _uiState.value.durationSeconds.coerceAtLeast(0.0))
        val monoNow = nowMonotonicMs()
        val request = ++seekRequest
        val origin = playbackHost.switchableTransport.generation
        val currentState = _uiState.value
        val source = loadedSource ?: "$origin:${currentState.epoch}"
        pendingSeek = PendingSeekHold(
            request = request,
            source = source,
            trackId = currentState.currentTrack?.id,
            targetSeconds = clamped,
            anchorMonotonicMs = monoNow
        )
        _uiState.update {
            it.copy(
                anchorPositionSeconds = clamped,
                anchorMonotonicMs = monoNow,
                displayedPositionSeconds = clamped,
                activeLyricIndex = computeActiveLyricIndex(it.currentLyrics, clamped)
            )
        }
        viewModelScope.launch {
            seekMutex.withLock {
                if (request != seekRequest || origin != playbackHost.switchableTransport.generation) return@withLock
                val result = coreClient.executeCommand(CoreTypedCommand.Seek(clamped))
                if (request != seekRequest || origin != playbackHost.switchableTransport.generation) return@withLock
                when (result) {
                    is CoreResult.Success -> {
                        val settledAt = nowMonotonicMs()
                        val hold = pendingSeek
                        if (hold != null && hold.request == request) {
                            pendingSeek = hold.copy(settled = true, settledAtMonotonicMs = settledAt)
                        }
                    }
                    is CoreResult.Failure -> {
                        if (result.error.code != CoreErrorCode.CANCELLED) {
                            val hold = pendingSeek
                            if (hold != null && hold.request == request) pendingSeek = null
                            report(result)
                            coreClient.reconcileState()
                        }
                    }
                }
            }
        }
    }

    fun onNextTrack() {
        launchForCurrentSource {
            report(coreClient.executeCommand(CoreTypedCommand.Next))
        }
    }

    fun onPreviousTrack() {
        launchForCurrentSource {
            report(coreClient.executeCommand(CoreTypedCommand.Previous))
        }
    }

    fun onCyclePlayMode() {
        val nextMode = when (_uiState.value.playMode) {
            PlayMode.SEQUENCE -> PlayMode.LOOP
            PlayMode.LOOP -> PlayMode.SINGLE
            PlayMode.SINGLE -> PlayMode.SHUFFLE
            PlayMode.SHUFFLE, PlayMode.HEART -> PlayMode.SEQUENCE
        }
        launchForCurrentSource {
            report(coreClient.executeCommand(CoreTypedCommand.SetPlayMode(nextMode)))
        }
    }

    fun onSelectQueueItem(index: Int, occurrenceId: String) {
        val revision = _uiState.value.queueRevision
        val queueId = _uiState.value.queueId
        val origin = playbackHost.switchableTransport.generation
        launchForCurrentSource {
            val result = coreClient.executeCommand(
                CoreTypedCommand.SelectQueueIndex(
                    id = queueId,
                    revision = revision,
                    index = index,
                    occurrenceId = occurrenceId
                )
            )
            if (report(result) && origin == playbackHost.switchableTransport.generation &&
                _uiState.value.operatingMode is CoreOperatingMode.StandaloneLocal)
                report(playbackHost.requestFocusAndPlay(0.0))
        }
    }

    fun onSelectMainTab(tab: MainDestinationTab) {
        _uiState.update { state ->
            state.copy(
                activeMainTab = tab,
                isImmersivePlayerOpen = false,
                isQueueDrawerOpen = false
            )
        }
    }

    fun onSetImmersivePlayerOpen(open: Boolean) {
        _uiState.update { state ->
            state.copy(
                isImmersivePlayerOpen = open,
                isQueueDrawerOpen = if (!open) false else state.isQueueDrawerOpen
            )
        }
    }

    fun onSelectSearchFilter(filter: SearchFilterCategory) {
        _uiState.update { it.copy(activeSearchFilter = filter) }
    }

    fun onShufflePlayAll() {
        val library = _uiState.value.libraryTracks
        val pool = (if (library.isEmpty()) _uiState.value.queueItems.mapNotNull { it.track } else library.filter(::reachable)).shuffled()
        if (pool.isEmpty()) {
            if (library.isNotEmpty()) _uiState.update { it.copy(errorBanner = "电脑未连接，暂时没有能播放的歌曲") }
            return
        }
        viewModelScope.launch {
            val origin = playbackHost.switchableTransport.generation
            if (!report(coreClient.executeCommand(CoreTypedCommand.SetPlayMode(PlayMode.SHUFFLE))) ||
                origin != playbackHost.switchableTransport.generation) return@launch
            onPlayTracks(pool, pool.first().id)
        }
    }

    fun onToggleQueueDrawer(open: Boolean? = null) {
        _uiState.update { state ->
            val nextOpen = open ?: !state.isQueueDrawerOpen
            state.copy(
                isQueueDrawerOpen = nextOpen,
                isLibrarySheetOpen = if (nextOpen) false else state.isLibrarySheetOpen,
                isEqSheetOpen = if (nextOpen) false else state.isEqSheetOpen
            )
        }
        if (_uiState.value.isQueueDrawerOpen) onLoadMoreQueue()
    }

    fun onLoadMoreQueue() {
        val state = _uiState.value
        if (state.isLoadingQueue) return
        val offset = nextQueueOffset(state.queueItems, state.queueTotal) ?: return
        val generation = sourceGeneration
        val revision = state.queueRevision
        val queueId = state.queueId
        _uiState.update { it.copy(isLoadingQueue = true, queueError = null) }
        queueJob = viewModelScope.launch {
            val result = coreClient.queryQueueWindow(offset, 50)
            if (generation != sourceGeneration) return@launch
            _uiState.update { current ->
                if (current.queueRevision != revision || current.queueId != queueId) current.copy(isLoadingQueue = false)
                else when (result) {
                    is CoreResult.Success -> {
                        val window = result.value
                        if (window.revision != revision || window.queueId != queueId) {
                            current.copy(isLoadingQueue = false)
                        } else current.copy(
                            queueItems = mergeQueueEntries(current.queueItems, window.items),
                            queueTotal = window.total, isLoadingQueue = false,
                            queueError = if (window.items.isEmpty() && offset < window.total) "队列暂未载入，请重试" else null
                        )
                    }
                    is CoreResult.Failure -> current.copy(isLoadingQueue = false, queueError = result.error.message)
                }
            }
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
                isQueueDrawerOpen = if (targetOpen) false else state.isQueueDrawerOpen,
                isEqSheetOpen = if (targetOpen) false else state.isEqSheetOpen
            )
        }
        if (targetOpen) {
            libraryLoader.refresh()
        }
    }

    fun onToggleEqSheet(open: Boolean? = null) {
        val targetOpen = open ?: !_uiState.value.isEqSheetOpen
        _uiState.update { state ->
            state.copy(
                isEqSheetOpen = targetOpen,
                isLibrarySheetOpen = if (targetOpen) false else state.isLibrarySheetOpen,
                isQueueDrawerOpen = if (targetOpen) false else state.isQueueDrawerOpen,
                isRemoteCoreSheetOpen = if (targetOpen) false else state.isRemoteCoreSheetOpen
            )
        }
    }

    fun onToggleRemoteCoreSheet(open: Boolean? = null) {
        val targetOpen = open ?: !_uiState.value.isRemoteCoreSheetOpen
        _uiState.update { state ->
            state.copy(
                isRemoteCoreSheetOpen = targetOpen,
                isLibrarySheetOpen = if (targetOpen) false else state.isLibrarySheetOpen,
                isQueueDrawerOpen = if (targetOpen) false else state.isQueueDrawerOpen,
                isEqSheetOpen = if (targetOpen) false else state.isEqSheetOpen
            )
        }
    }

    fun onRemoteHostInputChanged(value: String) {
        _uiState.update { it.copy(remoteHostInput = value) }
    }

    fun onRemotePinInputChanged(value: String) {
        _uiState.update { it.copy(remotePinInput = value.filter { ch -> ch.isDigit() }.take(6)) }
    }

    fun onConnectDesktopCore() {
        val host = _uiState.value.remoteHostInput.trim()
        val pin = _uiState.value.remotePinInput.trim().takeIf { it.isNotEmpty() }
        connectDesktop(host, pin)
    }

    fun onStartDesktopDiscovery() = playbackHost.desktopDiscovery.start()
    fun onStopDesktopDiscovery() = playbackHost.desktopDiscovery.stop()
    fun onRefreshDesktopDiscovery() { playbackHost.desktopDiscovery.stop(); playbackHost.desktopDiscovery.start() }
    fun onCancelDesktopConnection() { desktopConnectJob?.cancel() }
    fun onSelectDesktop(device: DesktopCoreDevice) {
        connectDesktop(device.baseUrl, expectedServerId = device.id.takeIf { !it.contains(":") }, requestApproval = true, label = device.name)
    }

    private fun connectDesktop(host: String, pin: String? = null, expectedServerId: String? = null,
        requestApproval: Boolean = false, label: String = host, onResult: (Boolean) -> Unit = {}) {
        if (host.isEmpty() || _uiState.value.isConnectingRemoteCore) { onResult(false); return }
        _uiState.update {
            it.copy(
                isConnectingRemoteCore = true,
                connectingDesktopId = expectedServerId ?: host,
                waitingForDesktopApproval = false,
                remoteStatusMessage = "正在连接 $label…"
            )
        }
        desktopConnectJob = viewModelScope.launch {
            var connected = false
            try {
            val result = playbackHost.connectDesktopLibrary(hostOrUrl = host, pin = pin, expectedServerId = expectedServerId,
                requestApproval = requestApproval, waitingForApproval = { code ->
                    _uiState.update { it.copy(waitingForDesktopApproval = true, desktopApprovalCode = code,
                        remoteStatusMessage = "请在 $label 上核对验证码并允许连接") }
                })
            connected = result.isSuccess
            result.fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            isConnectingRemoteCore = false,
                            remotePinInput = "",
                            remoteStatusMessage = "已连接 $label"
                        )
                    }
                    // The new Core snapshot triggers source-scoped library loading.
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            isConnectingRemoteCore = false,
                            remoteStatusMessage = "连接失败: ${err.message}"
                        )
                    }
                }
            )
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                _uiState.update { it.copy(remoteStatusMessage = "已取消连接") }
                throw cancel
            } finally {
                _uiState.update { it.copy(isConnectingRemoteCore = false, waitingForDesktopApproval = false,
                    desktopApprovalCode = null, connectingDesktopId = null) }
                onResult(connected)
            }
        }
    }

    fun onSwitchToStandaloneLocalCore() {
        if (_uiState.value.isConnectingRemoteCore) return
        _uiState.update {
            it.copy(
                isConnectingRemoteCore = true,
                remoteStatusMessage = "正在切回本机音乐…"
            )
        }
        viewModelScope.launch {
            val result = playbackHost.selectPhoneLibrary()
            result.fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            isConnectingRemoteCore = false,
                            remoteStatusMessage = "已切回本机音乐"
                        )
                    }
                    // The new Core snapshot restores the local browse state.
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            isConnectingRemoteCore = false,
                            remoteStatusMessage = "切换失败: ${err.message}"
                        )
                    }
                }
            )
        }
    }

    fun onSelectLibraryTab(tab: LibrarySectionTab) {
        _uiState.update { it.copy(activeLibraryTab = tab) }
        if (tab == LibrarySectionTab.STREAMING) streamingPresenter.loadLibrary()
        if (desktopCatalogMode()) browseDesktopCatalog(tab)
    }

    /** Shows every device's songs, only the phone's, or only one computer's (also while it is offline). */
    fun onSelectLibraryDevice(device: String) {
        if (libraryDevice.value == device) return
        libraryDevice.value = device
        _uiState.update { it.copy(libraryDevice = device) }
        if (desktopCatalogMode(device)) browseDesktopCatalog(_uiState.value.activeLibraryTab)
    }

    private fun desktopCatalogMode(device: String = libraryDevice.value) =
        (playbackHost.librarySource.value as? LibrarySource.Desktop)?.let { it.connected && it.serverId == device } == true

    private fun browseDesktopCatalog(tab: LibrarySectionTab) = when (tab) {
        LibrarySectionTab.ALBUMS -> desktopLibrary.browse("release")
        LibrarySectionTab.ARTISTS -> desktopLibrary.browse("artist")
        else -> Unit
    }

    /** Covers come from the computer each song lives on; an offline computer's songs show placeholders. */
    private suspend fun desktopArtwork(ids: List<String>): Result<TrackArtworkPage> {
        val owners = trackOwners
        val online = playbackHost.connectedDesktops.value
        val (remembered, browsed) = ids.partition { it in owners }
        val items = ArrayList<TrackArtworkPreview>()
        var failure: Throwable? = null
        remembered.groupBy { owners.getValue(it) }.forEach { (serverId, group) ->
            if (serverId !in online) group.mapTo(items) { TrackArtworkPreview(it) }
            else playbackHost.desktopArtwork(serverId, group).fold({ items += it.items }, { failure = it })
        }
        if (browsed.isNotEmpty()) playbackHost.desktopLibrary.artwork(browsed).fold({ items += it.items }, { failure = it })
        return if (items.isEmpty() && failure != null) Result.failure(failure!!) else Result.success(TrackArtworkPage(0, items))
    }

    fun onCatalogPage(offset: Int) = desktopLibrary.browse(offset = offset)
    fun onOpenEntity(entity: CatalogEntity) = desktopLibrary.open(entity)
    fun onCloseEntity() = desktopLibrary.closeDetail()
    fun onSourcePage(offset: Int) = desktopLibrary.sourcePage(offset)
    fun onArtistAlbums(albums: Boolean) = desktopLibrary.artistTab(albums)
    fun onRetryArtistAlbums() = desktopLibrary.loadArtistAlbums()
    fun onLoadLibraryArtist(id: String, force: Boolean = false) {
        _uiState.value.artists.firstOrNull { it.id == id }?.let { libraryArtist.load(it, force) }
    }
    fun onRequestArtwork(id: String) = desktopLibrary.requestArtwork(id)
    fun onPlaySource(source: CatalogSource, revision: Long) {
        val desktop = playbackHost.librarySource.value as? LibrarySource.Desktop ?: return
        if (_uiState.value.operatingMode is CoreOperatingMode.RemoteDesktopCore && playbackHost.playbackDesktopServerId() == desktop.serverId) {
            launchForCurrentSource { report(coreClient.executeCommand(CoreTypedCommand.PlayCatalogSource(source.id, revision))) }
        } else {
            val track = source.track
            val view = CoreTrackView(id = source.id, title = track.title, artist = track.artist,
                album = track.album, duration = track.duration, format = track.format,
                sampleRate = track.sampleRate, bitDepth = track.bitDepth,
                catalog = CatalogTrackReferences(revision, source.id))
            playTracks(listOf(view), view.id) { desktop.serverId }
        }
    }

    /** Deletes the computer's token and pin on this phone; connecting again needs a new approval there. */
    fun onForgetDesktop(device: DesktopCoreDevice) {
        viewModelScope.launch {
            playbackHost.forgetDesktop(device.id).fold(
                { _uiState.update { it.copy(remoteStatusMessage = "已忘记 ${device.name}") } },
                { banner(it.message ?: "无法忘记这台电脑，请重试") })
        }
    }

    fun onSelectPlaybackTarget(phone: Boolean) {
        viewModelScope.launch { playbackHost.selectPlaybackTarget(phone).onFailure { error ->
            _uiState.update { it.copy(errorBanner = error.message ?: "无法切换播放目标") }
        } }
    }

    private fun requireLocal(): Boolean {
        val local = _uiState.value.operatingMode is CoreOperatingMode.StandaloneLocal
        if (!local) _uiState.update { it.copy(errorBanner = "此操作请在电脑端完成，或先切回本机音乐") }
        return local
    }

    fun onNewPlaylistNameDraftChanged(draft: String) {
        _uiState.update { it.copy(newPlaylistNameDraft = draft) }
    }

    internal fun onStreamingPlaylistSort(sort: com.twilightecho.android.coreclient.CollectionSort) {
        val detail = streamingPresenter.state.value.detail?.takeIf { it.isPlaylist } ?: return
        val key = streamingSortKey(streamingPresenter.state.value.accountId, detail)
        playbackHost.userPreferences.update { it.copy(collectionSorts = it.collectionSorts + (key to sort)) }
        streamingPresenter.playlistSort(sort)
    }

    fun onCreatePlaylist(seedWithCurrentTrack: Boolean = true) {
        if (!requireLocal()) return
        val name = _uiState.value.newPlaylistNameDraft.trim().ifEmpty { "未命名歌单 ${_uiState.value.playlists.size + 1}" }
        val seedIds = if (seedWithCurrentTrack) {
            listOfNotNull(_uiState.value.currentTrack?.id)
        } else {
            emptyList()
        }
        _uiState.update { it.copy(newPlaylistNameDraft = "") }
        launchForCurrentSource {
            reportResult(coreClient.createPlaylist(name = name, trackIds = seedIds))
        }
    }

    fun onDeletePlaylist(playlistId: String) {
        if (!requireLocal()) return
        launchForCurrentSource {
            reportResult(coreClient.deletePlaylist(playlistId))
        }
    }

    fun onAddCurrentTrackToPlaylist(playlistId: String) {
        if (!requireLocal()) return
        val currentId = _uiState.value.currentTrack?.id ?: return
        launchForCurrentSource {
            reportResult(coreClient.addTracksToPlaylist(playlistId, listOf(currentId)))
        }
    }

    fun onPlayTrackIdsCollection(trackIds: List<String>) {
        if (trackIds.isEmpty()) return
        val tracks = collectionTracks(trackIds, _uiState.value.libraryTracks + _uiState.value.allTracks)
        if (tracks.size != trackIds.size) {
            _uiState.update { it.copy(errorBanner = "部分曲目尚未载入，请刷新音乐库") }
            return
        }
        onPlayTracks(tracks, tracks.first().id)
    }

    fun onToggleEqEnabled(enabled: Boolean? = null) {
        editAudio { it.copy(enabled = enabled ?: !it.enabled) }
    }

    fun onSelectEqPreset(presetName: String) {
        editAudio { it.copy(enabled = true, preset = presetName,
            bandsDb = CoreEqConfigView.PRESETS[presetName] ?: it.bandsDb, dsp = it.dsp.copy(eqMode = "graphic")) }
    }

    fun onSetEqPreamp(preampDb: Double) {
        editAudio { it.copy(preampDb = preampDb.coerceIn(-12.0, 12.0)) }
    }

    fun onSetEqBandGain(bandIndex: Int, gainDb: Double) {
        editAudio { config -> if (bandIndex !in config.bandsDb.indices) config else config.copy(
            enabled = true, preset = "Custom", bandsDb = config.bandsDb.toMutableList().apply {
                this[bandIndex] = gainDb.coerceIn(-12.0, 12.0) }) }
    }

    fun onToggleBitPerfect() {
        editAudio { it.copy(bitPerfect = !it.bitPerfect) }
    }

    fun onToggleGapless() {
        editAudio { it.copy(gapless = !it.gapless, dsp = it.dsp.copy(crossfadeSeconds = if (it.gapless) 0.0 else it.dsp.crossfadeSeconds)) }
    }

    private suspend fun applyAudioEdit(edit: (CoreEqConfigView) -> CoreEqConfigView): Boolean = audioSettingsMutex.withLock {
        if (!requireLocal()) return@withLock false
        // Read from Core: an output preset may have just changed the config this client's snapshot lags behind.
        val current = coreClient.getEqConfig().getOrNull() ?: coreClient.snapshot.value?.eqConfig ?: _uiState.value.eqConfig
        val updated = edit(current)
        val result = coreClient.setEqConfig(updated)
        reportResult(result)
        if (result.isSuccess) _uiState.update { it.copy(eqConfig = coreClient.snapshot.value?.eqConfig ?: updated) }
        result.isSuccess
    }

    private fun editAudio(edit: (CoreEqConfigView) -> CoreEqConfigView) {
        if (!requireLocal()) return
        viewModelScope.launch { applyAudioEdit(edit) }
    }

    /** One revision for edits spanning EQ and DSP fields, such as an AutoEQ import or its undo. */
    fun onEditAudio(edit: (CoreEqConfigView) -> CoreEqConfigView) = editAudio(edit)

    fun onEditDspConfig(edit: (CoreDspConfig) -> CoreDspConfig) = editAudio {
        val config = edit(it.dsp)
        it.copy(dsp = config, gapless = if (config.crossfadeSeconds > 0) true else it.gapless)
    }

    fun onSaveDspScene(name: String) = editAudio { config ->
        if (name.isBlank() || config.scenes.size >= 32) config else config.copy(scenes = config.scenes + CoreDspScene(
            UUID.randomUUID().toString(), name.trim().take(80),
            JsonObject(DefaultCoreClient.DefaultJson.encodeToJsonElement(config).jsonObject - "scenes")))
    }

    fun onApplyDspScene(id: String) = editAudio { config -> config.scenes.find { it.id == id }?.let(config::withScene) ?: config }

    fun onReapplyOutputPreset() = playbackHost.outputPresets.reapply()

    fun onDeleteDspScene(id: String) = editAudio { it.copy(scenes = it.scenes.filterNot { scene -> scene.id == id }) }

    fun onImportImpulseResponse(nodeId: String, uri: String) {
        if (!requireLocal()) return
        viewModelScope.launch {
            var imported: java.io.File? = null
            try {
                val file = playbackHost.importImpulseResponse(uri).also { imported = it }
                var appliedToNode = false
                val accepted = applyAudioEdit { config -> config.copy(dsp = config.dsp.copy(nodes = config.dsp.nodes.map { node ->
                    if (node.id != nodeId || node.type != "convolver") node else {
                        appliedToNode = true
                        node.copy(enabled = true, params = JsonObject(node.params + ("impulseResponsePath" to JsonPrimitive(file.absolutePath))))
                    }
                })) }
                if (!accepted || !appliedToNode) file.delete()
            } catch (error: Exception) {
                imported?.delete()
                _uiState.update { it.copy(errorBanner = error.message ?: "脉冲响应导入失败") }
            }
        }
    }

    fun onMonitorAudio(enabled: Boolean) {
        val previous = audioMonitorJob
        previous?.cancel()
        audioMonitorJob = null
        if (!enabled) return
        audioMonitorJob = viewModelScope.launch {
            previous?.join()
            try {
                if (_uiState.value.operatingMode !is CoreOperatingMode.StandaloneLocal) return@launch
                playbackHost.nativeAudioEngine.setVisualizationDemand(true)
                while (isActive && _uiState.value.operatingMode is CoreOperatingMode.StandaloneLocal) {
                    val status = playbackHost.nativeAudioEngine.getDspStatus()
                    val visualization = playbackHost.nativeAudioEngine.getVisualization()
                    val levels = (visualization["visualizerBars"] as? JsonArray).orEmpty().map {
                        (it.jsonPrimitive.doubleOrNull ?: 0.0).toFloat().coerceIn(0f, 1f)
                    }
                    val devices = playbackHost.audioDeviceAdapter.listOutputDevices().map {
                        AudioOutputDeviceUi(it.deviceId.toString(), it.deviceName, it.supportedSampleRates)
                    }
                    val rates = playbackHost.audioDeviceAdapter.sampleRatesForOutput("auto")
                    _uiState.update { it.copy(dspStatus = status, audioLevels = levels, audioDevices = devices,
                        activeOutputSampleRates = rates, audioMonitorError = null) }
                    delay(100L)
                }
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) { _uiState.update { it.copy(audioMonitorError = error.message ?: "音频状态读取失败") } }
            finally { withContext(NonCancellable) { playbackHost.nativeAudioEngine.setVisualizationDemand(false) } }
        }
    }

    fun onToggleFavoriteCurrentTrack() {
        if (!requireLocal()) return
        if (_uiState.value.currentTrack?.source?.startsWith("provider://ncm/") == true) {
            streamingPresenter.toggleFavorite(); return
        }
        val trackId = _uiState.value.currentTrack?.id ?: return
        launchForCurrentSource {
            reportResult(coreClient.toggleFavorite(trackId))
        }
    }

    fun onAddBookmarkAtCurrentPosition(customLabel: String? = null) {
        if (!requireLocal()) return
        val track = _uiState.value.currentTrack ?: return
        val posSec = _uiState.value.displayedPositionSeconds
        val label = customLabel?.takeIf { it.isNotBlank() }
            ?: formatDurationSeconds(posSec)
        launchForCurrentSource {
            reportResult(coreClient.addBookmark(
                trackId = track.id,
                positionSec = posSec,
                label = label
            ))
        }
    }

    fun onRemoveBookmark(bookmarkId: String) {
        if (!requireLocal()) return
        launchForCurrentSource {
            reportResult(coreClient.removeBookmark(bookmarkId))
        }
    }

    fun currentLyricsSourceKey(): String = when (val mode = _uiState.value.operatingMode) {
        is CoreOperatingMode.RemoteDesktopCore -> mode.baseUrl
        CoreOperatingMode.StandaloneLocal -> "local"
    }

    fun onImportLyricsDocument(trackId: String, sourceKey: String, uri: String) {
        if (!requireLocal()) return
        viewModelScope.launch {
            runCatching {
                check(sourceKey == currentLyricsSourceKey()) { "播放来源已切换，请重新选择歌词文件" }
                val text = withContext(Dispatchers.IO) {
                    when (val resource = playbackHost.resourceAccessAdapter.openReadStream(uri)) {
                        is com.twilightecho.android.runtimehost.ResourceAccessResult.Ok ->
                            resource.value.bufferedReader().use { it.readText().removePrefix("\uFEFF") }
                        is com.twilightecho.android.runtimehost.ResourceAccessResult.Error -> error(resource.message)
                    }
                }
                require(text.isNotBlank()) { "歌词文件为空，请选择 LRC 文件" }
                check(sourceKey == currentLyricsSourceKey()) { "播放来源已切换，请重新选择歌词文件" }
                coreClient.importLrcLyrics(trackId, text).getOrThrow()
            }.onFailure { error -> _uiState.update { it.copy(errorBanner = error.message ?: "歌词导入失败") } }
        }
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update { it.copy(searchQuery = query, isSearching = query.isNotBlank(), searchError = null,
            searchResults = if (query.isBlank()) it.allTracks else it.searchResults) }
        searchText.value = query
        ncmSearch.query(query)
    }

    fun onSelectSearchSource(source: SearchSource) {
        _uiState.update { it.copy(searchSource = source) }
        ncmSearch.active(source == SearchSource.NETEASE)
    }
    fun onSelectNcmSearchCategory(category: NcmSearchCategory) = ncmSearch.category(category)
    fun onNcmSearchPage(offset: Int) = ncmSearch.search(offset)
    fun onSubmitSearch() { if (_uiState.value.searchSource == SearchSource.NETEASE) ncmSearch.search() }

    fun onRefreshLibrary() {
        if (desktopCatalogMode()) desktopLibrary.refreshCurrent()
        libraryLoader.refresh()
        playbackHost.connectedDesktops.value.forEach { playbackHost.refreshDesktopIndex(it, force = false) }
    }
    fun onRetrySearch() {
        if (_uiState.value.searchSource == SearchSource.NETEASE) ncmSearch.search(ncmSearch.state.value.offset)
        else libraryLoader.refresh()
    }
    fun onDismissError() { _uiState.update { it.copy(errorBanner = null) } }

    fun onScanDeviceMusic() {
        if (_uiState.value.isScanningLocalMusic) return
        _uiState.update {
            it.copy(
                isScanningLocalMusic = true,
                scanStatusMessage = "正在扫描本地音乐…"
            )
        }
        viewModelScope.launch {
            val result = playbackHost.scanAndSyncDeviceMusic()
            result.fold(
                onSuccess = { summary ->
                    val message = buildList {
                        add(if (summary.withDeviceMusic) "已同步 ${summary.songs} 首歌曲"
                            else "未获得音乐访问权限，只同步了音乐文件夹中的 ${summary.songs} 首歌曲")
                        if (summary.cueSongs > 0) add("其中 ${summary.cueSongs} 首来自 CUE 分轨")
                        if (summary.unreadableFolders.isNotEmpty()) add("${summary.unreadableFolders.size} 个文件夹无法读取")
                        if (summary.skipped.isNotEmpty()) add("${summary.skipped.size} 个 CUE 未分轨")
                    }.joinToString("，")
                    _uiState.update {
                        it.copy(
                            isScanningLocalMusic = false,
                            scanStatusMessage = message,
                            scanDetails = summary.unreadableFolders.map { folder -> "${folder.label}：无法读取，请移除后重新添加" } +
                                summary.skipped.map { skip -> "${skip.sheetName}：${skip.reason}" }
                        )
                    }
                    libraryLoader.refresh()
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            isScanningLocalMusic = false,
                            scanStatusMessage = "扫描失败: ${err.message}",
                            scanDetails = emptyList()
                        )
                    }
                }
            )
        }
    }

    /** Keeps the folder from the system picker; false, with the reason shown, when Android refuses. */
    fun onAddMusicFolder(uri: String): Boolean = playbackHost.addMusicFolder(uri).fold(
        onSuccess = { true },
        onFailure = { _uiState.update { it.copy(scanStatusMessage = "无法保存这个文件夹的访问权限，请重新选择") }; false }
    )

    /** Drops the folder's songs at once; the rescan does not ask for the music permission again. */
    fun onRemoveMusicFolder(uri: String) {
        playbackHost.removeMusicFolder(uri)
        onScanDeviceMusic()
    }

    fun onScanPermissionDenied() {
        // Picked folders carry their own access, so their songs still sync.
        if (_uiState.value.musicFolders.isNotEmpty()) return onScanDeviceMusic()
        _uiState.update { it.copy(isScanningLocalMusic = false,
            scanStatusMessage = "未获得音乐访问权限，请授权后重试") }
    }

    fun onPlayTrackFromLibrary(track: CoreTrackView) {
        val currentList = _uiState.value.searchResults.ifEmpty { listOf(track) }
        onPlayTracks(if (currentList.any { it.id == track.id }) currentList else listOf(track), track.id)
    }

    fun onPlayTracks(tracks: List<CoreTrackView>, startTrackId: String) = playTracks(tracks, startTrackId, ::ownerOf)

    /** The computer a listed song lives on, or null for the phone's own songs. */
    private fun ownerOf(track: CoreTrackView): String? = trackOwners[track.id]
        ?: (playbackHost.librarySource.value as? LibrarySource.Desktop)?.serverId?.takeIf { track.id.startsWith("remote-track:") }

    /** Whether the device that plays right now can reach this song. */
    private fun reachable(track: CoreTrackView): Boolean {
        val owner = ownerOf(track)
        val online = playbackHost.connectedDesktops.value
        return if (_uiState.value.operatingMode is CoreOperatingMode.RemoteDesktopCore)
            owner != null && owner == playbackHost.playbackDesktopServerId() && owner in online
        else owner == null || owner in online && track.catalog != null
    }

    private fun playTracks(tracks: List<CoreTrackView>, startTrackId: String, ownerOf: (CoreTrackView) -> String?) {
        val start = tracks.firstOrNull { it.id == startTrackId } ?: return
        val startOwner = ownerOf(start)
        val online = playbackHost.connectedDesktops.value
        if (_uiState.value.operatingMode is CoreOperatingMode.RemoteDesktopCore) {
            val target = playbackHost.playbackDesktopServerId()
            when {
                startOwner == null -> banner("手机里的歌曲不能在电脑上播放，请把播放设备切换到此手机")
                target == null -> banner("电脑未连接，请把播放设备切换到此手机")
                startOwner != target -> banner("这首歌在另一台电脑上，请把播放设备切换到此手机")
                startOwner !in online -> openDisconnectPrompt(tracks, start, startOwner, ownerOf)
                else -> playOnDesktop(target, tracks, start, ownerOf)
            }
            return
        }
        if (startOwner != null && startOwner !in online) { openDisconnectPrompt(tracks, start, startOwner, ownerOf); return }
        if (startOwner != null && start.catalog == null) { banner("这首歌缺少电脑端的音源信息，请刷新音乐库"); return }
        // The phone streams computer songs by their lasting reference; songs of offline computers are left out.
        val playable = tracks.filter { track -> ownerOf(track).let { it == null || it in online && track.catalog != null } }
        val skipped = tracks.size - playable.size
        val startIndex = playable.indexOfFirst { it.id == startTrackId }
        val expectedRev = _uiState.value.queueRevision
        val origin = playbackHost.switchableTransport.generation
        playTracksJob?.cancel()
        playTracksJob = viewModelScope.launch {
            if (origin != playbackHost.switchableTransport.generation) return@launch
            val queue = try {
                playable.map { track ->
                    val serverId = ownerOf(track) ?: return@map track
                    playbackHost.desktopStreamTracks(serverId, listOf(track)).single().also { desktopLibrary.aliasArtwork(it.id, track.id) }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) { banner(error.message ?: "播放失败，请重试"); return@launch }
            val result = coreClient.executeCommand(CoreTypedCommand.ReplaceQueue(revision = expectedRev, tracks = queue,
                startIndex = startIndex, play = false))
            if (!report(result) || origin != playbackHost.switchableTransport.generation) return@launch
            report(playbackHost.requestFocusAndPlay(0.0))
            _uiState.update { it.copy(isLibrarySheetOpen = false,
                errorBanner = if (skipped > 0) "已跳过 $skipped 首电脑上的歌曲：电脑未连接" else it.errorBanner) }
        }
    }

    /** A computer plays its own songs; at most [DESKTOP_QUEUE_LIMIT] around the chosen one. */
    private fun playOnDesktop(serverId: String, tracks: List<CoreTrackView>, start: CoreTrackView, ownerOf: (CoreTrackView) -> String?) {
        val own = tracks.filter { ownerOf(it) == serverId }
        val index = own.indexOfFirst { it.id == start.id }
        val from = (index - DESKTOP_QUEUE_LIMIT / 2).coerceIn(0, (own.size - DESKTOP_QUEUE_LIMIT).coerceAtLeast(0))
        val window = own.subList(from, minOf(own.size, from + DESKTOP_QUEUE_LIMIT)).map { it.copy(queueEntryId = null) }
        val expectedRev = _uiState.value.queueRevision
        launchForCurrentSource {
            val result = coreClient.executeCommand(CoreTypedCommand.ReplaceQueue(revision = expectedRev, tracks = window,
                startIndex = index - from, play = true))
            val catalog = start.catalog
            if (result.errorOrNull()?.code == CoreErrorCode.NOT_FOUND && catalog != null) {
                // The computer restarted since its list was copied: play the song by its lasting reference and copy the list again.
                playbackHost.refreshDesktopIndex(serverId)
                report(coreClient.executeCommand(CoreTypedCommand.PlayCatalogSource(catalog.sourceId, catalog.revision)))
            } else if (report(result)) _uiState.update { it.copy(isLibrarySheetOpen = false) }
        }
    }

    private fun openDisconnectPrompt(tracks: List<CoreTrackView>, track: CoreTrackView, serverId: String,
        ownerOf: (CoreTrackView) -> String?) {
        val label = playbackHost.desktopLibraries.value.find { it.serverId == serverId }?.hostLabel ?: "电脑"
        pendingPlay = PendingPlay(tracks, track.id, buildMap { tracks.forEach { t -> ownerOf(t)?.let { put(t.id, it) } } })
        val fallback = ncmEnabled()
        promptJob?.cancel()
        _uiState.update { it.copy(disconnectPrompt = DisconnectPrompt(track, serverId, label, streamingFallback = fallback, searching = fallback)) }
        if (!fallback) return
        promptJob = viewModelScope.launch {
            val match = try { streamingPresenter.findMatch(track) }
                catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                catch (error: Exception) { null }
            _uiState.update { state -> state.copy(disconnectPrompt = state.disconnectPrompt?.let { prompt ->
                if (prompt.track.id == track.id) prompt.copy(searching = false, match = match) else prompt }) }
        }
    }

    /** Plays the same song from NetEase Cloud Music on the phone. */
    fun onDisconnectPlayStreaming() {
        val match = _uiState.value.disconnectPrompt?.match ?: return
        onDismissDisconnectPrompt()
        onPlayProviderTracks(listOf(match), match)
    }

    /** Reconnects the song's computer, then plays what the user picked. */
    fun onDisconnectReconnect() {
        val prompt = _uiState.value.disconnectPrompt ?: return
        if (prompt.reconnecting) return
        val address = _uiState.value.desktopDiscovery.devices.find { it.id == prompt.serverId }?.baseUrl
            ?: playbackHost.savedDesktopAddress(prompt.serverId)
            ?: playbackHost.desktopLibraries.value.find { it.serverId == prompt.serverId }?.baseUrl
        if (address == null) { banner("找不到这台电脑，请在设置里重新连接"); return }
        _uiState.update { it.copy(disconnectPrompt = prompt.copy(reconnecting = true)) }
        connectDesktop(address, expectedServerId = prompt.serverId, requestApproval = true, label = prompt.hostLabel) { connected ->
            if (_uiState.value.disconnectPrompt?.track?.id != prompt.track.id) return@connectDesktop
            if (!connected) {
                _uiState.update { it.copy(disconnectPrompt = it.disconnectPrompt?.copy(reconnecting = false)) }
                return@connectDesktop
            }
            viewModelScope.launch {
                withTimeoutOrNull(RECONNECT_WAIT_MS) { playbackHost.connectedDesktops.first { prompt.serverId in it } }
                if (_uiState.value.disconnectPrompt?.track?.id != prompt.track.id) return@launch
                val pending = pendingPlay
                onDismissDisconnectPrompt()
                if (pending != null) playTracks(pending.tracks, pending.startId) { pending.owners[it.id] }
            }
        }
    }

    fun onDismissDisconnectPrompt() {
        promptJob?.cancel(); promptJob = null; pendingPlay = null
        _uiState.update { it.copy(disconnectPrompt = null) }
    }

    private fun ncmEnabled() = playbackHost.plugins.state.value.installed.any { it.id == NCM_PLUGIN_ID && it.enabled }

    fun onCreateStreamingPlaylist(name: String, tracks: List<CoreTrackView> = emptyList()) {
        if (name.isBlank()) return
        viewModelScope.launch {
            streamingPresenter.createPlaylist(name).fold({ playlist ->
                if (tracks.isEmpty()) banner("已创建歌单「${playlist.title}」")
                else streamingPresenter.addToPlaylist(playlist, tracks).fold(
                    { banner("已创建歌单「${playlist.title}」并加入歌曲") }, { banner(it.message ?: "加入歌单失败") })
            }, { banner(it.message ?: "创建歌单失败") })
        }
    }

    /** Deletes an own playlist, or stops collecting someone else's. */
    fun onDeleteStreamingPlaylist(playlist: StreamingCollection) {
        viewModelScope.launch {
            streamingPresenter.deletePlaylist(playlist).fold({ banner(if (playlist.owned == true) "已删除歌单" else "已取消收藏") },
                { banner(it.message ?: "操作失败，请重试") })
        }
    }

    fun onAddToStreamingPlaylist(playlist: StreamingCollection, tracks: List<CoreTrackView>) {
        viewModelScope.launch {
            streamingPresenter.addToPlaylist(playlist, tracks).fold({ banner("已加入「${playlist.title}」") },
                { banner(it.message ?: "加入歌单失败") })
        }
    }

    fun onRemoveFromStreamingPlaylist(playlist: StreamingCollection, track: CoreTrackView) {
        viewModelScope.launch {
            streamingPresenter.removeFromPlaylist(playlist, track).fold({ banner("已从歌单移除") },
                { banner(it.message ?: "移除失败，请重试") })
        }
    }

    private fun banner(message: String) = _uiState.update { it.copy(errorBanner = message) }

    internal fun onPlayProviderTracks(tracks: List<CoreTrackView>, selected: CoreTrackView) {
        val playable = tracks.filter { it.unavailableReason == null }
        val index = playable.indexOfFirst { if (selected.queueEntryId != null) it.queueEntryId == selected.queueEntryId else it.id == selected.id }
        if (index < 0) return
        viewModelScope.launch {
            val snapshot = playbackHost.selectPlaybackTarget(phone = true).getOrElse {
                _uiState.update { state -> state.copy(errorBanner = it.message) }; return@launch
            }
            val result = coreClient.executeCommand(CoreTypedCommand.ReplaceQueue(
                revision = snapshot.queue?.revision ?: 0L, tracks = playable, startIndex = index, play = false))
            if (report(result)) report(playbackHost.requestFocusAndPlay(0.0))
        }
    }

    private suspend fun resolveLyricsForTrack(track: CoreTrackView, mode: CoreOperatingMode) {
        if (mode is CoreOperatingMode.RemoteDesktopCore) {
            runCatching { coreClient.getCurrentLyrics(track.id) }
                .onFailure { if (it is CancellationException) throw it }
            return
        }

        val existing = coreClient.snapshot.value?.currentLyrics?.takeIf { it.trackId == track.id }
        if (existing != null && existing.lines.isNotEmpty()) return
        if (!attemptedAutoLyricsTrackIds.add(track.id)) return
        while (attemptedAutoLyricsTrackIds.size > 256) {
            val first = synchronized(attemptedAutoLyricsTrackIds) {
                attemptedAutoLyricsTrackIds.firstOrNull()
            } ?: break
            attemptedAutoLyricsTrackIds.remove(first)
        }

        val trackSource = track.source
        when {
            trackSource?.startsWith("provider://ncm/") == true -> {
                val ncmId = track.ncmSongId?.takeIf { it.isNotBlank() }
                    ?: trackSource.substringAfterLast('/').takeIf { it.isNotBlank() }
                if (ncmId != null && importNcmLyricsById(track.id, ncmId)) return
                fetchOnlineLyricsSilently(track.id)
            }
            trackSource != null && trackSource.startsWith("provider://") -> {
                val imported = try {
                    val lrc = playbackHost.plugins.providerLyrics(trackSource)
                    if (!lrc.isNullOrBlank() && isStillActiveLocalTrack(track.id)) {
                        coreClient.importLrcLyrics(track.id, lrc).isSuccess
                    } else false
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    false
                }
                if (!imported) fetchBestEffortOnlineLyrics(track)
            }
            track.id.startsWith(DesktopMediaReference.PREFIX) -> {
                val desktopView = try {
                    playbackHost.resolveDesktopLyrics(track.id).getOrNull()
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    null
                }
                val serialized = desktopView?.let(::serializeLyricsViewToLrc)
                if (serialized != null && isStillActiveLocalTrack(track.id)) {
                    val (original, translation) = serialized
                    if (coreClient.importLrcLyrics(track.id, original, translation).isSuccess) return
                }
                fetchBestEffortOnlineLyrics(track)
            }
            else -> {
                fetchBestEffortOnlineLyrics(track)
            }
        }
    }

    private fun isStillActiveLocalTrack(trackId: String): Boolean =
        playbackHost.operatingMode.value is CoreOperatingMode.StandaloneLocal &&
            uiState.value.currentTrack?.id == trackId

    private suspend fun importNcmLyricsById(trackId: String, ncmSongId: String): Boolean {
        return try {
            val lyrics = playbackHost.plugins.call(
                NCM_PLUGIN_ID,
                "getLyrics",
                kotlinx.serialization.json.buildJsonArray {
                    add(
                        kotlinx.serialization.json.buildJsonObject {
                            put("ncmSongId", kotlinx.serialization.json.JsonPrimitive(ncmSongId))
                        }
                    )
                }
            ) as? kotlinx.serialization.json.JsonObject
            val lrc = lyrics?.get("mobileLrc")?.jsonPrimitive?.contentOrNull
            if (!lrc.isNullOrBlank() && isStillActiveLocalTrack(trackId)) {
                coreClient.importLrcLyrics(trackId, lrc).isSuccess
            } else {
                false
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun fetchBestEffortOnlineLyrics(track: CoreTrackView) {
        if (!isStillActiveLocalTrack(track.id)) return
        if (ncmEnabled() && track.title.isNotBlank()) {
            val directNcmId = track.ncmSongId?.takeIf { it.isNotBlank() }
            if (directNcmId != null && importNcmLyricsById(track.id, directNcmId)) return
            val matchedId = try {
                val matched = streamingPresenter.findMatch(track)
                matched?.ncmSongId?.takeIf { it.isNotBlank() }
                    ?: matched?.source?.takeIf { it.startsWith("provider://ncm/") }?.substringAfterLast('/')
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                null
            }
            if (!matchedId.isNullOrBlank() && importNcmLyricsById(track.id, matchedId)) return
        }
        fetchOnlineLyricsSilently(track.id)
    }

    private suspend fun fetchOnlineLyricsSilently(trackId: String) {
        if (!isStillActiveLocalTrack(trackId)) return
        try {
            coreClient.fetchOnlineLyrics(trackId)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // Silent when no online lyrics exist for this track.
        }
    }

    private fun applyCoreSnapshot(snapshot: CoreSnapshot, queueWindow: CoreQueueWindowView) {
        val source = "${playbackHost.switchableTransport.generation}:${snapshot.epoch}"
        if (source != loadedSource) {
            loadedSource = source
            sourceGeneration++
            playPauseRequest++
            pendingSeek = null
            attemptedAutoLyricsTrackIds.clear()
            queueJob?.cancel()
            _uiState.update { it.copy(queueItems = emptyList(), isLoadingQueue = false, queueError = null, requestedPlaying = null) }
        }
        val playback = snapshot.playback
        val isPlaying = playback?.state == "playing"
        val positionSeconds = playback?.position ?: 0.0
        val durationSeconds = playback?.duration?.takeIf { it > 0.0 }
            ?: snapshot.track?.duration
            ?: 0.0
        val playbackRate = playback?.playbackRate ?: 1.0
        val monoNow = nowMonotonicMs()
        val currentTrackId = snapshot.track?.id

        val seekEval = evaluatePendingSeekHold(
            hold = pendingSeek,
            source = source,
            trackId = currentTrackId,
            incomingPositionSeconds = positionSeconds,
            durationSeconds = durationSeconds,
            isPlaying = isPlaying,
            playbackRate = playbackRate,
            monoNowMs = monoNow
        )
        pendingSeek = seekEval.hold

        val diagnostics = diagnosticsFor(snapshot)
        val queueId = snapshot.queue?.id ?: queueWindow.queueId
        val queueRevision = snapshot.queue?.revision ?: queueWindow.revision
        val validWindow = queueWindow.queueId == queueId && queueWindow.revision == queueRevision

        val trackBookmarks = if (currentTrackId != null) {
            snapshot.bookmarks.filter { it.trackId == currentTrackId }.sortedBy { it.positionSec }
        } else {
            emptyList()
        }

        val activeLyricIdx = computeActiveLyricIndex(snapshot.currentLyrics, seekEval.displayedPositionSeconds)

        _uiState.update { current ->
            current.copy(
                epoch = snapshot.epoch,
                sequence = snapshot.sequence,
                queueRevision = queueRevision,
                queueId = queueId,
                isPlaying = isPlaying,
                playbackState = playback?.state ?: "stopped",
                currentTrack = snapshot.track,
                anchorPositionSeconds = seekEval.anchorPositionSeconds,
                anchorMonotonicMs = seekEval.anchorMonotonicMs,
                displayedPositionSeconds = seekEval.displayedPositionSeconds,
                durationSeconds = durationSeconds,
                playbackRate = playbackRate,
                volume = playback?.volume ?: 1.0,
                playMode = PlayMode.fromWireValue(playback?.playMode),
                diagnostics = diagnostics.copy(bluetoothProfile = current.diagnostics.bluetoothProfile),
                queueItems = mergeQueueEntries(
                    if (current.queueId == queueId && current.queueRevision == queueRevision) current.queueItems else emptyList(),
                    if (validWindow) queueWindow.items else emptyList()
                ),
                queueTotal = snapshot.queue?.total ?: if (validWindow) queueWindow.total else 0,
                queueError = if (current.queueRevision != queueRevision) null else current.queueError,
                currentQueueIndex = snapshot.queue?.index ?: playback?.queueIndex ?: 0,
                favoriteTrackIds = snapshot.favoriteTrackIds,
                isCurrentTrackFavorite = if (snapshot.track?.source?.startsWith("provider://ncm/") == true)
                    current.streaming.favoriteTrackId == snapshot.track?.source?.substringAfterLast('/') && current.streaming.favoriteLiked == true
                    else currentTrackId != null && currentTrackId in snapshot.favoriteTrackIds,
                isFavoriteBusy = snapshot.track?.source?.startsWith("provider://ncm/") == true && current.streaming.favoriteBusy,
                currentTrackBookmarks = trackBookmarks,
                eqConfig = snapshot.eqConfig,
                currentLyrics = snapshot.currentLyrics,
                activeLyricIndex = activeLyricIdx,
                errorBanner = snapshot.error ?: current.errorBanner
            )
        }
    }

    private fun computeActiveLyricIndex(lyrics: CoreLyricsView?, positionSec: Double): Int {
        val lines = lyrics?.lines ?: return -1
        if (lines.isEmpty()) return -1
        val posMs = (positionSec * 1000.0).toLong()
        var activeIdx = -1
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

    private fun report(result: CoreResult<*>): Boolean {
        if (shouldSurfaceCoreError(result) && result is CoreResult.Failure) {
            _uiState.update { it.copy(errorBanner = result.error.message) }
        }
        return result.isSuccess
    }

    private fun launchForCurrentSource(action: suspend () -> Unit) {
        val origin = playbackHost.switchableTransport.generation
        viewModelScope.launch {
            if (origin == playbackHost.switchableTransport.generation) action()
        }
    }

    private fun reportResult(result: Result<*>) {
        result.onFailure { error -> _uiState.update { it.copy(errorBanner = error.message ?: "操作失败，请重试") } }
    }

    fun onEditPreferences(edit: (com.twilightecho.android.coreclient.UserPreferences) -> com.twilightecho.android.coreclient.UserPreferences) {
        playbackHost.userPreferences.update(edit)
    }

    fun onImportBackground(uri: String) {
        if (_uiState.value.isImportingBackground) return
        _uiState.update { it.copy(isImportingBackground = true) }
        viewModelScope.launch {
            try { playbackHost.userPreferences.importBackground(uri) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { _uiState.update { it.copy(errorBanner = "图片导入失败，请换一张图片") } }
            finally { _uiState.update { it.copy(isImportingBackground = false) } }
        }
    }

    override fun onCleared() { playbackHost.desktopDiscovery.stop(); super.onCleared() }

    class Factory(
        private val playbackHost: AndroidPlaybackHost
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return PlayerViewModel(playbackHost = playbackHost) as T
        }
    }
}

internal data class PendingSeekHold(
    val request: Long,
    val source: String,
    val trackId: String?,
    val targetSeconds: Double,
    val anchorMonotonicMs: Long,
    val settled: Boolean = false,
    val settledAtMonotonicMs: Long = 0L
)

internal data class SeekHoldEvaluation(
    val hold: PendingSeekHold?,
    val anchorPositionSeconds: Double,
    val anchorMonotonicMs: Long,
    val displayedPositionSeconds: Double
)

internal fun evaluatePendingSeekHold(
    hold: PendingSeekHold?,
    source: String,
    trackId: String?,
    incomingPositionSeconds: Double,
    durationSeconds: Double,
    isPlaying: Boolean,
    playbackRate: Double,
    monoNowMs: Long
): SeekHoldEvaluation {
    if (hold == null || hold.source != source || hold.trackId != trackId) {
        return SeekHoldEvaluation(
            hold = null,
            anchorPositionSeconds = incomingPositionSeconds,
            anchorMonotonicMs = monoNowMs,
            displayedPositionSeconds = incomingPositionSeconds
        )
    }
    val elapsed = if (isPlaying) {
        ((monoNowMs - hold.anchorMonotonicMs).coerceAtLeast(0L) / 1000.0) * playbackRate
    } else {
        0.0
    }
    val upperBound = if (durationSeconds > 0.0) maxOf(durationSeconds, hold.targetSeconds) else Double.MAX_VALUE
    val expected = (hold.targetSeconds + elapsed).coerceIn(0.0, upperBound)
    val tolerance = if (hold.settled) 1.5 else 0.25
    val caughtUp = kotlin.math.abs(incomingPositionSeconds - expected) <= tolerance
    val settledExpired = hold.settled && (monoNowMs - hold.settledAtMonotonicMs) >= 1500L
    val hardExpired = (monoNowMs - hold.anchorMonotonicMs) >= 5000L
    return if (caughtUp || settledExpired || hardExpired) {
        SeekHoldEvaluation(
            hold = null,
            anchorPositionSeconds = incomingPositionSeconds,
            anchorMonotonicMs = monoNowMs,
            displayedPositionSeconds = incomingPositionSeconds
        )
    } else {
        SeekHoldEvaluation(
            hold = hold,
            anchorPositionSeconds = hold.targetSeconds,
            anchorMonotonicMs = hold.anchorMonotonicMs,
            displayedPositionSeconds = expected
        )
    }
}

internal fun shouldSurfaceCoreError(result: CoreResult<*>): Boolean =
    result is CoreResult.Failure && result.error.code != CoreErrorCode.CANCELLED

internal fun serializeLyricsViewToLrc(view: CoreLyricsView): Pair<String, String?>? {
    if (view.lines.isEmpty()) return null
    if (!view.synced) {
        val original = view.lines.joinToString("\n") { it.text }.trim()
        if (original.isBlank()) return null
        val translation = if (view.lines.any { !it.translation.isNullOrBlank() }) {
            view.lines.joinToString("\n") { it.translation.orEmpty() }
        } else {
            null
        }
        return original to translation
    }
    var lastMs = -1L
    val origLines = ArrayList<String>(view.lines.size)
    val transLines = ArrayList<String>()
    for (line in view.lines) {
        val rawMs = line.timeMs.coerceAtLeast(0L)
        val safeMs = if (rawMs <= lastMs) lastMs + 1L else rawMs
        lastMs = safeMs
        val tag = formatLrcTimestamp(safeMs)
        val text = line.text.trim().ifEmpty { "…" }
        origLines.add("$tag$text")
        val trans = line.translation?.trim()
        if (!trans.isNullOrEmpty()) {
            transLines.add("$tag$trans")
        }
    }
    val original = origLines.joinToString("\n")
    val translation = transLines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    return original to translation
}

private fun formatLrcTimestamp(ms: Long): String {
    val minutes = ms / 60_000L
    val seconds = (ms / 1_000L) % 60L
    val millis = ms % 1_000L
    return String.format(java.util.Locale.US, "[%02d:%02d.%03d]", minutes, seconds, millis)
}
