package com.twilightecho.android.feature.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import com.twilightecho.android.ui.components.*
import com.twilightecho.android.ui.theme.Monet
import com.twilightecho.android.ui.theme.TwilightEchoTheme
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Density
import com.twilightecho.android.adapters.AndroidMusicFolderScanner
import com.twilightecho.android.coreclient.AccentSource
import com.twilightecho.android.coreclient.BackgroundStyle
import com.twilightecho.android.ui.theme.LocalContentSurfaceOpacity

@Composable
fun NowPlayingScreen(viewModel: PlayerViewModel, modifier: Modifier = Modifier,
    onRequestScanMusicPermission: (() -> Unit)? = null) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val latestState = rememberUpdatedState(state)
    LifecycleStartEffect(viewModel) {
        viewModel.onPresentationActive(true)
        onStopOrDispose { viewModel.onPresentationActive(false) }
    }
    var pluginsOpen by rememberSaveable { mutableStateOf(false) }
    var pluginsAccountOnly by rememberSaveable { mutableStateOf(false) }
    var lyricsTrackId by rememberSaveable { mutableStateOf<String?>(null) }
    var lyricsSource by rememberSaveable { mutableStateOf("") }
    val importLyrics = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lyricsTrackId?.let { viewModel.onImportLyricsDocument(it, lyricsSource, uri.toString()) }
    }
    val importBackground = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.onImportBackground(uri.toString())
    }
    val scanMusic = onRequestScanMusicPermission ?: viewModel::onScanDeviceMusic
    // A picked folder is read at once, so its songs and CUE splits appear without another tap.
    val pickMusicFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null && viewModel.onAddMusicFolder(uri.toString())) scanMusic()
    }
    var impulseNodeId by rememberSaveable { mutableStateOf<String?>(null) }
    val importImpulse = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) impulseNodeId?.let { viewModel.onImportImpulseResponse(it, uri.toString()) }
        impulseNodeId = null
    }
    val actions = remember(viewModel, onRequestScanMusicPermission) {
        PlayerActions(
            editPreferences = viewModel::onEditPreferences,
            pickBackground = { importBackground.launch(arrayOf("image/*")) },
            playbackTarget = viewModel::onSelectPlaybackTarget,
            startDiscovery = viewModel::onStartDesktopDiscovery, stopDiscovery = viewModel::onStopDesktopDiscovery,
            refreshDiscovery = viewModel::onRefreshDesktopDiscovery, selectDesktop = viewModel::onSelectDesktop,
            forgetDesktop = viewModel::onForgetDesktop,
            cancelConnection = viewModel::onCancelDesktopConnection,
            selectTab = viewModel::onSelectMainTab, selectLibraryTab = viewModel::onSelectLibraryTab,
            setPlayerOpen = viewModel::onSetImmersivePlayerOpen,
            setLyricsMode = { viewModel.onToggleLyricsMode(it) },
            setQueueOpen = { viewModel.onToggleQueueDrawer(it) }, loadMoreQueue = viewModel::onLoadMoreQueue,
            scan = scanMusic,
            addMusicFolder = { pickMusicFolder.launch(AndroidMusicFolderScanner.pickerStart()) },
            removeMusicFolder = viewModel::onRemoveMusicFolder,
            refreshLibrary = viewModel::onRefreshLibrary, query = viewModel::onSearchQueryChanged,
            retrySearch = viewModel::onRetrySearch, selectFilter = viewModel::onSelectSearchFilter,
            searchSource = viewModel::onSelectSearchSource, searchCategory = viewModel::onSelectNcmSearchCategory,
            searchPage = viewModel::onNcmSearchPage, submitSearch = viewModel::onSubmitSearch,
            playTracks = viewModel::onPlayTracks, togglePlayback = viewModel::onTogglePlayPause,
            previous = viewModel::onPreviousTrack, next = viewModel::onNextTrack,
            cyclePlayMode = viewModel::onCyclePlayMode, seek = viewModel::onSeekTo,
            toggleFavorite = viewModel::onToggleFavoriteCurrentTrack,
            selectQueueEntry = { viewModel.onSelectQueueItem(it.index, it.occurrenceId); viewModel.onToggleQueueDrawer(false) },
            addBookmark = { viewModel.onAddBookmarkAtCurrentPosition() }, removeBookmark = viewModel::onRemoveBookmark,
            playlistName = viewModel::onNewPlaylistNameDraftChanged,
            createPlaylist = { viewModel.onCreatePlaylist(seedWithCurrentTrack = false) },
            addToPlaylist = viewModel::onAddCurrentTrackToPlaylist, deletePlaylist = viewModel::onDeletePlaylist,
            toggleEq = { viewModel.onToggleEqEnabled() }, preset = viewModel::onSelectEqPreset,
            band = viewModel::onSetEqBandGain, preamp = viewModel::onSetEqPreamp,
            toggleBitPerfect = viewModel::onToggleBitPerfect, toggleGapless = viewModel::onToggleGapless,
            editDsp = viewModel::onEditDspConfig, editAudio = viewModel::onEditAudio, monitorAudio = viewModel::onMonitorAudio,
            importImpulse = { impulseNodeId = it; importImpulse.launch(arrayOf("audio/wav", "audio/x-wav", "audio/wave", "application/octet-stream")) },
            saveDspScene = viewModel::onSaveDspScene, applyDspScene = viewModel::onApplyDspScene,
            reapplyOutputPreset = viewModel::onReapplyOutputPreset,
            deleteDspScene = viewModel::onDeleteDspScene,
            remoteHost = viewModel::onRemoteHostInputChanged, remotePin = viewModel::onRemotePinInputChanged,
            connectRemote = viewModel::onConnectDesktopCore, useLocal = viewModel::onSwitchToStandaloneLocalCore,
            dismissError = viewModel::onDismissError,
            selectLibraryDevice = viewModel::onSelectLibraryDevice,
            disconnectStreaming = viewModel::onDisconnectPlayStreaming,
            disconnectReconnect = viewModel::onDisconnectReconnect,
            dismissDisconnect = viewModel::onDismissDisconnectPrompt,
            catalogPage = viewModel::onCatalogPage, openEntity = viewModel::onOpenEntity,
            closeEntity = viewModel::onCloseEntity, sourcePage = viewModel::onSourcePage,
            artistAlbums = viewModel::onArtistAlbums, retryArtistAlbums = viewModel::onRetryArtistAlbums,
            loadLibraryArtist = viewModel::onLoadLibraryArtist,
            playSource = viewModel::onPlaySource, requestArtwork = viewModel::onRequestArtwork
        )
    }
    val streaming = viewModel.streamingPresenter
    val openStreamingAccount: () -> Unit = remember(viewModel) { {
        val plugin = viewModel.pluginPresenter.repository.state.value.entries.find { it.id == NCM_PLUGIN_ID }
        pluginsAccountOnly = plugin?.installedVersion != null && plugin.enabled
        if (pluginsAccountOnly) viewModel.pluginPresenter.open(plugin!!) else viewModel.pluginPresenter.close()
        pluginsOpen = true
    } }
    val streamingActions = remember(viewModel, openStreamingAccount) { StreamingActions(account = openStreamingAccount, library = {
        streaming.libraryRoot()
        viewModel.onSelectLibraryTab(LibrarySectionTab.STREAMING)
        viewModel.onSelectMainTab(MainDestinationTab.LIBRARY)
    }, refresh = streaming::refresh, recommendation = { kind ->
        if (latestState.value.streaming.access == StreamingAccess.READY) streaming.openRecommendation(kind) else openStreamingAccount()
    }, group = streaming::openGroup, collection = {
        if (latestState.value.streaming.access == StreamingAccess.READY) streaming.openCollection(it) else openStreamingAccount()
    }, back = streaming::closeDetail,
        retry = streaming::retryDetail, page = streaming::page, artistTab = streaming::artistTab, root = streaming::libraryRoot,
        playlistQuery = streaming::playlistQuery, playlistSort = viewModel::onStreamingPlaylistSort, retryArtist = streaming::retryArtist,
        play = { tracks, track ->
            if (latestState.value.streaming.access == StreamingAccess.READY) viewModel.onPlayProviderTracks(tracks, track) else openStreamingAccount()
        }, createPlaylist = viewModel::onCreateStreamingPlaylist, deletePlaylist = viewModel::onDeleteStreamingPlaylist,
        addToPlaylist = viewModel::onAddToStreamingPlaylist, removeFromPlaylist = viewModel::onRemoveFromStreamingPlaylist) }
    if (pluginsOpen) {
        PlayerAppearance(state, actions.requestArtwork) {
            PluginsScreen(viewModel.pluginPresenter, onBack = { pluginsOpen = false; streaming.refreshAccount() }, onPlay = { tracks, selected ->
                viewModel.onPlayProviderTracks(tracks, selected); pluginsOpen = false
            }, modifier = modifier, accountOnly = pluginsAccountOnly)
        }
        return
    }
    val appActions = remember(actions, streamingActions, viewModel) { actions.copy(streaming = streamingActions, toggleFavorite = {
        val current = latestState.value
        if (current.currentTrack?.source?.startsWith("provider://ncm/") == true && current.streaming.access != StreamingAccess.READY)
            openStreamingAccount() else viewModel.onToggleFavoriteCurrentTrack()
    }, openPlugins = {
        pluginsAccountOnly = false; viewModel.pluginPresenter.close(); pluginsOpen = true
    }, importLyrics = {
        lyricsTrackId = latestState.value.currentTrack?.id
        lyricsSource = viewModel.currentLyricsSourceKey()
        importLyrics.launch(arrayOf("text/*", "application/octet-stream", "application/x-lrc"))
    }) }
    PlayerAppContent(state, appActions, modifier)
}


/** The actual application UI: no host, native engine, or service required by its callers. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlayerAppContent(state: PlayerUiState, actions: PlayerActions, modifier: Modifier = Modifier) {
    val position = rememberUpdatedState(state.interpolatedPositionSec)
    val lyricIndex = rememberUpdatedState(state.activeLyricIndex)
    val progress = remember { PlayerProgress(position, lyricIndex) }
    // Retain the same object across clock-only snapshots so strong skipping also skips unstable list inputs.
    val contentState = state.withoutProgressTicks().let { projection -> remember(projection) { projection } }
    CompositionLocalProvider(LocalPlayerProgress provides progress) {
        PlayerAppearance(contentState, actions.requestArtwork) { PlayerAppSurface(contentState, actions, modifier) }
    }
}

@Composable
private fun PlayerAppearance(state: PlayerUiState, requestArtwork: (String) -> Unit, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalRemoteArtwork provides RemoteArtworkEnvironment(
        remote = state.isDesktopLibrary,
        previews = state.desktopLibrary.artwork, revision = state.desktopLibrary.artworkRevision,
        request = requestArtwork)) {
        // Monet: the whole app takes its palette from the cover of what is playing.
        val artwork = rememberPlayerArtwork(state.currentTrack)
        val target = if (state.currentTrack == null) Monet.FallbackSeed else artwork.seedColor ?: Monet.FallbackSeed
        var seed by remember { mutableStateOf(if (artwork.isRealEmbeddedArt) target else Monet.FallbackSeed) }
        LaunchedEffect(target, artwork.isRealEmbeddedArt) {
            // A generated placeholder shows while the real cover decodes or downloads; hold the current palette
            // briefly so the app does not flash the placeholder's hue first.
            if (!artwork.isRealEmbeddedArt && state.currentTrack != null) kotlinx.coroutines.delay(800)
            seed = target
        }
        val appearance = state.preferences.appearance
        val density = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(density.density * appearance.densityScale, density.fontScale),
            LocalAmbientArtwork provides artwork.takeIf { state.currentTrack != null },
            LocalBackgroundVisuals provides BackgroundVisuals(useCover = appearance.backgroundStyle == BackgroundStyle.COVER,
                imagePath = appearance.backgroundImagePath.takeIf { appearance.backgroundStyle == BackgroundStyle.IMAGE })) {
            TwilightEchoTheme(seed = seed,
                customAccent = appearance.accentArgb.takeIf { appearance.accentSource == AccentSource.CUSTOM }?.let(::Color),
                backgroundColor = appearance.backgroundArgb.takeIf { appearance.backgroundStyle == BackgroundStyle.SOLID }?.let(::Color),
                imageBackground = appearance.backgroundStyle == BackgroundStyle.IMAGE,
                contentOpacity = appearance.contentOpacity) {
                SystemBarAppearance()
                content()
            }
        }
    }
}

/** Height reserved at the bottom of scrolling pages so content can pass under the floating dock. */
internal val LocalDockInset = compositionLocalOf { 0.dp }

/** Library tabs that list a connected computer's own albums and artists while the library shows only that computer. */
internal val DesktopCatalogTabs = setOf(LibrarySectionTab.ALBUMS, LibrarySectionTab.ARTISTS)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlayerAppSurface(state: PlayerUiState, actions: PlayerActions, modifier: Modifier = Modifier) {
    var collectionKind by rememberSaveable { mutableStateOf<String?>(null) }
    var collectionId by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsDestination by rememberSaveable { mutableStateOf(SettingsDestination.OVERVIEW) }
    // A streaming page shows in the tab it was opened from, and nowhere else.
    var streamingOrigin by rememberSaveable { mutableStateOf<MainDestinationTab?>(null) }
    val savedPages = rememberSaveableStateHolder()
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val playerOpen = state.isImmersivePlayerOpen && state.currentTrack != null
    val playerVisibility = remember { MutableTransitionState(false) }
    playerVisibility.targetState = playerOpen
    // Home is the streaming page: without a streaming provider the app starts in, and returns to, the library.
    val homeTab = if (state.homeAvailable == true) MainDestinationTab.HOME else MainDestinationTab.LIBRARY
    val activeTab = if (state.activeMainTab == MainDestinationTab.HOME) homeTab else state.activeMainTab
    val desktopDetailOpen = state.desktopCatalogMode && activeTab == MainDestinationTab.LIBRARY &&
        state.activeLibraryTab in DesktopCatalogTabs && state.desktopLibrary.selected != null
    val streamingTab = streamingOrigin
        ?: if (state.streaming.detail?.recommendation != null) MainDestinationTab.HOME else MainDestinationTab.LIBRARY
    val streamingDetailOpen = state.streaming.detail != null && state.streaming.access == StreamingAccess.READY &&
        streamingTab == activeTab && (activeTab == MainDestinationTab.HOME || activeTab == MainDestinationTab.SEARCH ||
            activeTab == MainDestinationTab.LIBRARY && state.activeLibraryTab == LibrarySectionTab.STREAMING)
    val lyricsIdentity = lyricsSceneIdentity(state)
    val lyricsViewport = rememberLyricsViewportState(lyricsIdentity)
    val snackbar = remember { SnackbarHostState() }
    if (state.homeAvailable == null) {
        // Which tabs exist is known once the installed plugins are read, a moment after launch.
        AmbientBackground(modifier.fillMaxSize()) { }
        return
    }

    LaunchedEffect(state.errorBanner) {
        state.errorBanner?.let {
            snackbar.showSnackbar(it, actionLabel = "知道了", duration = SnackbarDuration.Long)
            actions.dismissError()
        }
    }
    LaunchedEffect(state.libraryDevice) {
        collectionId = null
    }
    BackHandler(enabled = !imeVisible && (playerOpen || desktopDetailOpen || streamingDetailOpen ||
        (activeTab == MainDestinationTab.LIBRARY && collectionId != null) ||
        (activeTab == MainDestinationTab.SETTINGS && settingsDestination != SettingsDestination.OVERVIEW) ||
        activeTab != homeTab)) {
        when {
            playerOpen -> actions.setPlayerOpen(false)
            streamingDetailOpen -> actions.streaming.back()
            desktopDetailOpen -> actions.closeEntity()
            activeTab == MainDestinationTab.LIBRARY && collectionId != null -> collectionId = null
            activeTab == MainDestinationTab.SETTINGS && settingsDestination != SettingsDestination.OVERVIEW ->
                settingsDestination = SettingsDestination.OVERVIEW
            else -> actions.selectTab(homeTab)
        }
    }
    val openCollection: (String, String) -> Unit = { kind, id ->
        collectionKind = kind; collectionId = id; actions.selectTab(MainDestinationTab.LIBRARY)
    }
    val openSettings: (SettingsDestination) -> Unit = {
        settingsDestination = it; actions.setPlayerOpen(false); actions.selectTab(MainDestinationTab.SETTINGS)
    }
    // Opening a streaming page from a tab's own page starts a new trail that belongs to that tab.
    fun streamingFrom(tab: MainDestinationTab) = actions.streaming.copy(
        recommendation = { streamingOrigin = tab; actions.streaming.recommendation(it) },
        group = { streamingOrigin = tab; actions.streaming.group(it) },
        collection = { streamingOrigin = tab; actions.streaming.root(); actions.streaming.collection(it) },
        library = { collectionId = null; actions.streaming.library() })
    val dockInset = if (imeVisible) 0.dp else if (state.currentTrack != null) 136.dp else 68.dp
    val ambientArtwork = state.currentTrack?.let { rememberPlayerArtwork(it) }
    AmbientBackground(modifier.fillMaxSize(), ambientArtwork,
        showBackdrop = !playerVisibility.currentState || !playerVisibility.targetState) {
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()
            // Keep the page's saved scroll/composition, but do not paint it under the opaque player.
            // Enter/exit transitions still draw the page until the player fully covers it.
            .drawWithContent { if (!playerVisibility.currentState || !playerVisibility.targetState) drawContent() }
            .then(if (playerOpen) Modifier.clearAndSetSemantics { } else Modifier)) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val pageKey = if (streamingDetailOpen) "streaming:${state.streaming.detail!!.key}"
                    else if (desktopDetailOpen) "desktop:${state.desktopLibrary.selected!!.kind}:${state.desktopLibrary.selected.id}"
                    else when (activeTab) {
                    MainDestinationTab.LIBRARY -> if (collectionId != null) "collection:$collectionKind:$collectionId"
                        else "library:${state.activeLibraryTab}"
                    MainDestinationTab.SETTINGS -> "settings:$settingsDestination"
                    else -> activeTab.name
                }
                val headerResetKey = when {
                    streamingDetailOpen -> "${state.streaming.detail!!.offset}"
                    desktopDetailOpen -> "${state.desktopLibrary.sources?.offset ?: 0}"
                    activeTab == MainDestinationTab.LIBRARY && collectionId == null -> libraryHeaderResetKey(state)
                    activeTab == MainDestinationTab.SEARCH -> "${state.searchSource}:${state.searchQuery}"
                    else -> ""
                }
                // The tab bar sits below the pages in the layout, so pages only keep a small breathing space.
                CompositionLocalProvider(LocalDockInset provides 0.dp) {
                    savedPages.SaveableStateProvider(pageKey) {
                        PlaybackHeaderPage(state, actions, headerResetKey) {
                            if (streamingDetailOpen) StreamingDetailScreen(state.streaming.detail!!, state, actions.streaming)
                            else when (activeTab) {
                                MainDestinationTab.HOME -> HomeTabScreen(state, actions.copy(streaming = streamingFrom(MainDestinationTab.HOME)))
                                MainDestinationTab.LIBRARY -> if (collectionId != null) {
                                    CollectionDetailScreen(findCollection(state, collectionKind, collectionId), state, actions, { collectionId = null })
                                } else LibraryTabScreen(state, actions.copy(streaming = streamingFrom(MainDestinationTab.LIBRARY)),
                                    openCollection) { openSettings(SettingsDestination.REMOTE) }
                                MainDestinationTab.SEARCH -> SearchTabScreen(state, actions.copy(streaming = streamingFrom(MainDestinationTab.SEARCH)))
                                MainDestinationTab.SETTINGS -> SettingsTabScreen(state, actions, settingsDestination, { settingsDestination = it })
                            }
                        }
                    }
                }
            }
            if (!imeVisible) FloatingDock(state, activeTab, actions, Modifier) { tab ->
                if (tab == activeTab) when (tab) {
                    // Tapping the current tab again returns to its first page.
                    MainDestinationTab.HOME -> if (streamingDetailOpen) actions.streaming.root()
                    MainDestinationTab.LIBRARY -> {
                        collectionId = null
                        if (streamingDetailOpen) actions.streaming.root()
                        if (desktopDetailOpen) actions.closeEntity()
                    }
                    MainDestinationTab.SETTINGS -> settingsDestination = SettingsDestination.OVERVIEW
                    MainDestinationTab.SEARCH -> if (streamingDetailOpen) actions.streaming.root()
                }
                actions.selectTab(tab)
            }
        }
        AnimatedVisibility(playerVisibility,
            enter = slideInVertically(tween(320), initialOffsetY = { it / 3 }) + fadeIn(tween(220)),
            exit = slideOutVertically(tween(240), targetOffsetY = { it / 3 }) + fadeOut(tween(180))) {
            ImmersiveNowPlayingScreen(state, actions, lyricsViewport, { openSettings(SettingsDestination.AUDIO) })
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
            .padding(bottom = if (playerOpen || imeVisible) 12.dp else dockInset))
    }
    state.disconnectPrompt?.let { DisconnectDialog(it, actions) }
}

/** A song whose computer cannot be reached: hear it from NetEase Cloud Music, reconnect, or leave it. */
@Composable
private fun DisconnectDialog(prompt: DisconnectPrompt, actions: PlayerActions) {
    val streamingReady = prompt.match != null && !prompt.searching
    AlertDialog(onDismissRequest = actions.dismissDisconnect,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(28.dp),
        modifier = Modifier.testTag("disconnectPrompt"),
        title = { Text("电脑未连接") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("「${prompt.track.title}」在 ${prompt.hostLabel} 上，电脑现在连不上。",
                    style = MaterialTheme.typography.bodyLarge)
                if (prompt.streamingFallback && !prompt.searching && prompt.match == null)
                    SupportingText("网易云上没有找到这首歌")
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (prompt.streamingFallback && (prompt.searching || streamingReady))
                    PillButton(if (prompt.searching) "正在网易云查找…" else "用网易云播放", actions.disconnectStreaming,
                        Modifier.fillMaxWidth().testTag("disconnectStreaming"), enabled = streamingReady)
                PillButton(if (prompt.reconnecting) "正在连接…" else "重新连接", actions.disconnectReconnect,
                    Modifier.fillMaxWidth().testTag("disconnectReconnect"), enabled = !prompt.reconnecting,
                    tonal = prompt.streamingFallback)
                TextButton(onClick = actions.dismissDisconnect, modifier = Modifier.fillMaxWidth()) { Text("取消") }
            }
        })
}

/** Apple-style tab bar under the pages: a grey strip with a hairline, icon over label, only the current tab in the accent. */
@Composable
private fun FloatingDock(state: PlayerUiState, active: MainDestinationTab, actions: PlayerActions, modifier: Modifier,
    select: (MainDestinationTab) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth()) {
        if (state.currentTrack != null) MiniPlayer(state, actions, Modifier.padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 8.dp))
        HorizontalDivider(thickness = 0.5.dp, color = colors.outlineVariant)
        Row(Modifier.fillMaxWidth().background(colors.background.copy(alpha = LocalContentSurfaceOpacity.current)).navigationBarsPadding()
            .height(58.dp), verticalAlignment = Alignment.CenterVertically) {
            MainDestinationTab.entries.filter { it != MainDestinationTab.HOME || state.homeAvailable == true }.forEach { tab ->
                val icon = when (tab) {
                    MainDestinationTab.HOME -> TwilightIcons.Home
                    MainDestinationTab.LIBRARY -> TwilightIcons.Library
                    MainDestinationTab.SEARCH -> TwilightIcons.Search
                    MainDestinationTab.SETTINGS -> TwilightIcons.Settings
                }
                val selected = active == tab
                val fg by animateColorAsState(if (selected) colors.primary else colors.onSurfaceVariant, label = "navFg")
                Column(Modifier.weight(1f).fillMaxHeight()
                    .selectable(selected, role = Role.Tab) { select(tab) }
                    .testTag("nav-${tab.name}").semantics { contentDescription = tab.label },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(icon, null, Modifier.size(24.dp), tint = fg)
                    Text(tab.label, style = MaterialTheme.typography.labelSmall, color = fg, maxLines = 1,
                        modifier = Modifier.padding(top = 2.dp).clearAndSetSemantics { })
                }
            }
        }
    }
}

@Composable
private fun MiniPlayer(state: PlayerUiState, actions: PlayerActions, modifier: Modifier = Modifier) {
    val track = state.currentTrack ?: return
    val shape = RoundedCornerShape(14.dp)
    Row(modifier.fillMaxWidth().height(60.dp)
        .clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = LocalContentSurfaceOpacity.current))
        .clickable(role = Role.Button) { actions.setPlayerOpen(true) }.testTag("miniPlayer")
        .padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        TrackArt(track, Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)), androidx.compose.ui.layout.ContentScale.Crop)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(track.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface)
            Text(track.artist.ifBlank { "未知艺术家" }, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        QuietIconButton(if (state.playbackButtonPlaying) TwilightIcons.Pause else TwilightIcons.Play,
            if (state.playbackButtonPlaying) "暂停" else "播放", actions.togglePlayback)
        QuietIconButton(TwilightIcons.SkipNext, "下一首", actions.next)
    }
}

/** A light solid canvas also uses dark system icons. Image backgrounds carry their own dark scrim. */
@Composable
private fun SystemBarAppearance() {
    val view = LocalView.current
    val context = LocalContext.current
    val light = MaterialTheme.colorScheme.background.luminance() > 0.18f
    DisposableEffect(view, light) {
        val window = context.activity()?.window
        if (window != null) {
            if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = light
                isAppearanceLightNavigationBars = light
            }
        }
        onDispose { }
    }
}

private fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
