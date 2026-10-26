@file:OptIn(ExperimentalMaterial3Api::class)

package com.twilightecho.android.feature.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.twilightecho.android.coreclient.CoreAlbumCollectionView
import com.twilightecho.android.coreclient.CoreArtistCollectionView
import com.twilightecho.android.coreclient.CoreBookmarkView
import com.twilightecho.android.coreclient.CoreEqConfigView
import com.twilightecho.android.coreclient.CoreLyricLine
import com.twilightecho.android.coreclient.CoreOperatingMode
import com.twilightecho.android.coreclient.CorePlaylistView
import com.twilightecho.android.coreclient.CoreQueueEntryView
import com.twilightecho.android.coreclient.CoreTrackView
import com.twilightecho.android.coreclient.PlayMode
import com.twilightecho.android.ui.components.GalleryArtworkStageCard
import com.twilightecho.android.ui.components.SegmentedPillSwitch
import com.twilightecho.android.ui.components.SignalPathArchitectureCard
import com.twilightecho.android.ui.components.SignalPathCapsule
import com.twilightecho.android.ui.components.TwilightIcons
import com.twilightecho.android.ui.components.TwilightWhiteCard
import com.twilightecho.android.ui.components.formatDurationSeconds
import com.twilightecho.android.ui.theme.TwilightTokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    viewModel: PlayerViewModel,
    onRequestScanMusicPermission: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Surface(
        modifier = modifier.fillMaxSize(),
        color = TwilightTokens.CanvasBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 1. Minimalist Editorial Header: Brand Identity + Core Mode Pill + Stage/Lyrics Toggle + Signal Pill
            TopEditorialBar(
                isLyricsMode = state.isLyricsMode,
                operatingMode = state.operatingMode,
                diagnostics = state.diagnostics,
                onSelectLyricsMode = { viewModel.onToggleLyricsMode(it) },
                onOpenSignalSheet = { viewModel.onToggleEqSheet(true) },
                onOpenRemoteCoreSheet = { viewModel.onToggleRemoteCoreSheet(true) }
            )

            // Optional Error Banner
            AnimatedVisibility(visible = state.errorBanner != null) {
                state.errorBanner?.let { errorMsg ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(TwilightTokens.FavoriteRoseSoft)
                            .border(0.8.dp, TwilightTokens.FavoriteRose.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = errorMsg,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TwilightTokens.FavoriteRose
                        )
                    }
                }
            }

            // 2. Center Stage (Adaptive Weight): Gallery White Artwork Sleeve OR Breathable Lyrics Card
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    targetState = state.isLyricsMode,
                    transitionSpec = {
                        fadeIn(animationSpec = tween(260)) togetherWith fadeOut(animationSpec = tween(220))
                    },
                    label = "stageSwitch"
                ) { showLyrics ->
                    if (showLyrics) {
                        SynchronizedLyricsCard(
                            lines = state.currentLyrics?.lines.orEmpty(),
                            activeIndex = state.activeLyricIndex,
                            onSeekToLine = { line ->
                                viewModel.onSeekTo(line.timeMs / 1000.0)
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        GalleryArtworkStageCard(
                            isPlaying = state.isPlaying,
                            codecLabel = state.diagnostics.codec,
                            sampleRateHz = state.diagnostics.actualSampleRateHz.takeIf { it > 0 }
                                ?: state.diagnostics.sourceSampleRateHz
                        )
                    }
                }
            }

            // 3. Editorial Track Identity + Micro-Action Icons + Bookmark Pills
            val trackTitle = state.currentTrack?.title ?: "未加载曲目"
            val trackArtist = state.currentTrack?.artist?.takeIf { it.isNotBlank() } ?: "点击下方音乐库扫描或选择本地曲目"
            val trackAlbum = state.currentTrack?.album?.takeIf { it.isNotBlank() }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            text = trackTitle,
                            style = MaterialTheme.typography.headlineMedium,
                            color = TwilightTokens.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (trackAlbum != null) "$trackArtist · $trackAlbum" else trackArtist,
                            style = MaterialTheme.typography.bodyLarge,
                            color = TwilightTokens.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Tactile Favorite Circle Button
                        val favBg = if (state.isCurrentTrackFavorite) TwilightTokens.FavoriteRoseSoft else TwilightTokens.CardSurface
                        val favBorder = if (state.isCurrentTrackFavorite) TwilightTokens.FavoriteRose.copy(alpha = 0.3f) else TwilightTokens.CardBorder
                        val favTint = if (state.isCurrentTrackFavorite) TwilightTokens.FavoriteRose else TwilightTokens.TextSecondary

                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .shadow(2.dp, CircleShape, ambientColor = TwilightTokens.AmbientShadowColor)
                                .clip(CircleShape)
                                .background(favBg)
                                .border(0.8.dp, favBorder, CircleShape)
                                .clickable { viewModel.onToggleFavoriteCurrentTrack() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (state.isCurrentTrackFavorite) TwilightIcons.HeartFilled else TwilightIcons.HeartOutline,
                                contentDescription = "Favorite",
                                tint = favTint,
                                modifier = Modifier.size(19.dp)
                            )
                        }

                        // Tactile Bookmark Circle Button
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .shadow(2.dp, CircleShape, ambientColor = TwilightTokens.AmbientShadowColor)
                                .clip(CircleShape)
                                .background(TwilightTokens.CardSurface)
                                .border(0.8.dp, TwilightTokens.CardBorder, CircleShape)
                                .clickable { viewModel.onAddBookmarkAtCurrentPosition() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = TwilightIcons.Bookmark,
                                contentDescription = "Add Bookmark",
                                tint = TwilightTokens.PrimaryCobalt,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                if (state.currentTrackBookmarks.isNotEmpty()) {
                    BookmarkChipsRow(
                        bookmarks = state.currentTrackBookmarks,
                        onSeekToBookmark = { bm -> viewModel.onSeekTo(bm.positionSec) },
                        onRemoveBookmark = { bm -> viewModel.onRemoveBookmark(bm.id) }
                    )
                }
            }

            // 4. Precision Transport Console White Card
            TransportConsoleCard(
                isPlaying = state.isPlaying,
                playMode = state.playMode,
                positionSeconds = state.interpolatedPositionSec,
                durationSeconds = state.durationSeconds,
                queueCount = state.queueItems.size,
                onSeekTo = { viewModel.onSeekTo(it) },
                onCyclePlayMode = { viewModel.onCyclePlayMode() },
                onPrevious = { viewModel.onPreviousTrack() },
                onTogglePlayPause = { viewModel.onTogglePlayPause() },
                onNext = { viewModel.onNextTrack() },
                onOpenQueue = { viewModel.onToggleQueueDrawer(true) }
            )

            Spacer(modifier = Modifier.height(10.dp))

            // 5. Floating Bottom Capsule Dock (Library | Signal & DSP | Queue)
            BottomFloatingCapsuleDock(
                libraryCount = state.libraryTrackCount,
                isEqActive = state.eqConfig.enabled,
                queueCount = state.queueItems.size,
                onOpenLibrary = { viewModel.onToggleLibrarySheet(true) },
                onOpenEqDsp = { viewModel.onToggleEqSheet(true) },
                onOpenQueue = { viewModel.onToggleQueueDrawer(true) }
            )
        }
    }

    // Modal Bottom Sheet 1: Local Music Library & Collections
    if (state.isLibrarySheetOpen) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { viewModel.onToggleLibrarySheet(false) },
            sheetState = sheetState,
            containerColor = TwilightTokens.CardSurface,
            scrimColor = Color(0x4D0F172A),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            LocalLibraryModalSheet(
                activeTab = state.activeLibraryTab,
                searchQuery = state.searchQuery,
                tracks = state.searchResults,
                albums = state.albums,
                artists = state.artists,
                playlists = state.playlists,
                newPlaylistNameDraft = state.newPlaylistNameDraft,
                favoriteIds = state.favoriteTrackIds,
                isScanning = state.isScanningLocalMusic,
                scanStatusMessage = state.scanStatusMessage,
                onSelectTab = { viewModel.onSelectLibraryTab(it) },
                onQueryChange = { viewModel.onSearchQueryChanged(it) },
                onScanDeviceMusicClick = {
                    if (onRequestScanMusicPermission != null) {
                        onRequestScanMusicPermission()
                    } else {
                        viewModel.onScanDeviceMusic()
                    }
                },
                onSelectTrack = { track -> viewModel.onPlayTrackFromLibrary(track) },
                onPlayCollectionTrackIds = { ids -> viewModel.onPlayTrackIdsCollection(ids) },
                onNewPlaylistNameChange = { viewModel.onNewPlaylistNameDraftChanged(it) },
                onCreatePlaylist = { viewModel.onCreatePlaylist(seedWithCurrentTrack = true) },
                onAddCurrentTrackToPlaylist = { plId -> viewModel.onAddCurrentTrackToPlaylist(plId) },
                onDeletePlaylist = { plId -> viewModel.onDeletePlaylist(plId) }
            )
        }
    }

    // Modal Bottom Sheet 2: Signal Path & 10-Band Parametric EQ / Native DSP
    if (state.isEqSheetOpen) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { viewModel.onToggleEqSheet(false) },
            sheetState = sheetState,
            containerColor = TwilightTokens.CardSurface,
            scrimColor = Color(0x4D0F172A),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            EqualizerDspModalSheet(
                diagnostics = state.diagnostics,
                eqConfig = state.eqConfig,
                onToggleEqEnabled = { viewModel.onToggleEqEnabled() },
                onSelectPreset = { viewModel.onSelectEqPreset(it) },
                onSetBandGain = { idx, db -> viewModel.onSetEqBandGain(idx, db) },
                onToggleBitPerfect = { viewModel.onToggleBitPerfect() },
                onToggleGapless = { viewModel.onToggleGapless() }
            )
        }
    }

    // Modal Bottom Sheet 3: Authoritative Playback Queue
    if (state.isQueueDrawerOpen) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
        ModalBottomSheet(
            onDismissRequest = { viewModel.onToggleQueueDrawer(false) },
            sheetState = sheetState,
            containerColor = TwilightTokens.CardSurface,
            scrimColor = Color(0x4D0F172A),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            QueueModalSheet(
                items = state.queueItems,
                currentQueueIndex = state.currentQueueIndex,
                onSelectEntry = { entry ->
                    viewModel.onSelectQueueItem(entry.index, entry.occurrenceId)
                    viewModel.onToggleQueueDrawer(false)
                }
            )
        }
    }

    // Modal Bottom Sheet 4: Roon-style Core-Bridge Mode Switcher (Standalone Local Core <-> Desktop Remote Core)
    if (state.isRemoteCoreSheetOpen) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { viewModel.onToggleRemoteCoreSheet(false) },
            sheetState = sheetState,
            containerColor = TwilightTokens.CardSurface,
            scrimColor = Color(0x4D0F172A),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            RemoteCoreBridgeModalSheet(
                operatingMode = state.operatingMode,
                remoteHostInput = state.remoteHostInput,
                remotePinInput = state.remotePinInput,
                isConnecting = state.isConnectingRemoteCore,
                statusMessage = state.remoteStatusMessage,
                onHostInputChange = { viewModel.onRemoteHostInputChanged(it) },
                onPinInputChange = { viewModel.onRemotePinInputChanged(it) },
                onConnectDesktopCore = { viewModel.onConnectDesktopCore() },
                onSwitchToStandaloneLocal = { viewModel.onSwitchToStandaloneLocalCore() }
            )
        }
    }
}

@Composable
private fun TopEditorialBar(
    isLyricsMode: Boolean,
    operatingMode: CoreOperatingMode,
    diagnostics: AudioDiagnosticsUiModel,
    onSelectLyricsMode: (Boolean) -> Unit,
    onOpenSignalSheet: () -> Unit,
    onOpenRemoteCoreSheet: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "TWILIGHT ECHO",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.1.sp
                    ),
                    color = TwilightTokens.PrimaryCobalt
                )
                val isRemote = operatingMode is CoreOperatingMode.RemoteDesktopCore
                val modeLabel = when (operatingMode) {
                    is CoreOperatingMode.StandaloneLocal -> "📱 本机独立 Core"
                    is CoreOperatingMode.RemoteDesktopCore -> "🖥️ 电脑 Core · ${operatingMode.hostLabel}"
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(
                            if (isRemote) TwilightTokens.PrimaryCobaltSoft else TwilightTokens.SubtleSurface
                        )
                        .border(
                            0.8.dp,
                            if (isRemote) TwilightTokens.PrimaryCobaltBorder else TwilightTokens.CardBorder,
                            RoundedCornerShape(999.dp)
                        )
                        .clickable { onOpenRemoteCoreSheet() }
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = modeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isRemote) TwilightTokens.PrimaryCobalt else TwilightTokens.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            SignalPathCapsule(
                codec = diagnostics.codec,
                sampleRateHz = diagnostics.actualSampleRateHz.takeIf { it > 0 } ?: diagnostics.sourceSampleRateHz,
                bitDepth = diagnostics.actualBitDepth.takeIf { it > 0 } ?: diagnostics.sourceBitDepth,
                outputPerfect = diagnostics.outputPerfect,
                onClick = onOpenSignalSheet
            )
        }

        SegmentedPillSwitch(
            options = listOf(false, true),
            selectedOption = isLyricsMode,
            labelProvider = { if (it) "歌词" else "封面" },
            onSelect = onSelectLyricsMode
        )
    }
}

@Composable
private fun TransportConsoleCard(
    isPlaying: Boolean,
    playMode: PlayMode,
    positionSeconds: Double,
    durationSeconds: Double,
    queueCount: Int,
    onSeekTo: (Double) -> Unit,
    onCyclePlayMode: () -> Unit,
    onPrevious: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onOpenQueue: () -> Unit
) {
    val maxDuration = durationSeconds.coerceAtLeast(1.0).toFloat()
    val externalPos = positionSeconds.coerceIn(0.0, durationSeconds.coerceAtLeast(1.0)).toFloat()

    var isDragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(externalPos) }
    val sliderPos = if (isDragging) dragValue else externalPos

    TwilightWhiteCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 26.dp,
        elevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Precision Scrubber + Monospace Timestamps
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Slider(
                    value = sliderPos,
                    onValueChange = {
                        isDragging = true
                        dragValue = it
                    },
                    onValueChangeFinished = {
                        isDragging = false
                        onSeekTo(dragValue.toDouble())
                    },
                    valueRange = 0f..maxDuration,
                    thumb = {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .shadow(3.dp, CircleShape, ambientColor = TwilightTokens.PrimaryCobalt)
                                .clip(CircleShape)
                                .background(TwilightTokens.PrimaryCobalt)
                                .border(2.5.dp, Color.White, CircleShape)
                        )
                    },
                    track = { sliderState ->
                        val fraction = ((sliderState.value - sliderState.valueRange.start) /
                            (sliderState.valueRange.endInclusive - sliderState.valueRange.start).coerceAtLeast(0.0001f))
                            .coerceIn(0f, 1f)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(50))
                                .background(TwilightTokens.PillSurface)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(fraction)
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(TwilightTokens.PrimaryCobalt)
                            )
                        }
                    },
                    modifier = Modifier.height(22.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatDurationSeconds(sliderPos.toDouble()),
                        style = MaterialTheme.typography.labelSmall,
                        color = TwilightTokens.TextSecondary
                    )
                    Text(
                        text = formatDurationSeconds(durationSeconds),
                        style = MaterialTheme.typography.labelSmall,
                        color = TwilightTokens.TextMuted
                    )
                }
            }

            // 5-Point Vector Transport Controls (PlayMode | Prev | Cobalt Play/Pause | Next | Queue)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val (modeIcon, modeLabel) = when (playMode) {
                    PlayMode.SEQUENCE -> TwilightIcons.RepeatSequence to "顺序"
                    PlayMode.LOOP -> TwilightIcons.RepeatAll to "循环"
                    PlayMode.SINGLE -> TwilightIcons.RepeatOne to "单曲"
                    PlayMode.SHUFFLE, PlayMode.HEART -> TwilightIcons.Shuffle to "随机"
                }

                // Play Mode Button
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onCyclePlayMode)
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = modeIcon,
                        contentDescription = modeLabel,
                        tint = TwilightTokens.TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = modeLabel,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp),
                        color = TwilightTokens.TextMuted
                    )
                }

                // Previous Track Vector Button
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(TwilightTokens.SubtleSurface)
                        .border(0.8.dp, TwilightTokens.CardBorder, CircleShape)
                        .clickable(onClick = onPrevious),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = TwilightIcons.SkipPrevious,
                        contentDescription = "Previous",
                        tint = TwilightTokens.TextPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }

                // Signature Desktop-Style Cobalt Blue Play/Pause Button
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .shadow(
                            elevation = 12.dp,
                            shape = CircleShape,
                            ambientColor = TwilightTokens.PrimaryCobalt.copy(alpha = 0.45f),
                            spotColor = TwilightTokens.PrimaryCobalt.copy(alpha = 0.45f)
                        )
                        .clip(CircleShape)
                        .background(TwilightTokens.PrimaryCobalt)
                        .clickable(onClick = onTogglePlayPause),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isPlaying) TwilightIcons.Pause else TwilightIcons.Play,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }

                // Next Track Vector Button
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(TwilightTokens.SubtleSurface)
                        .border(0.8.dp, TwilightTokens.CardBorder, CircleShape)
                        .clickable(onClick = onNext),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = TwilightIcons.SkipNext,
                        contentDescription = "Next",
                        tint = TwilightTokens.TextPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }

                // Queue Quick Trigger
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onOpenQueue)
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = TwilightIcons.Queue,
                        contentDescription = "Queue",
                        tint = TwilightTokens.TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "${queueCount}首",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp),
                        color = TwilightTokens.TextMuted
                    )
                }
            }
        }
    }
}

@Composable
private fun BottomFloatingCapsuleDock(
    libraryCount: Int,
    isEqActive: Boolean,
    queueCount: Int,
    onOpenLibrary: () -> Unit,
    onOpenEqDsp: () -> Unit,
    onOpenQueue: () -> Unit
) {
    TwilightWhiteCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 999.dp,
        elevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            DockCapsuleItem(
                icon = TwilightIcons.Library,
                label = "音乐库",
                badgeText = if (libraryCount > 0) "$libraryCount" else null,
                isActive = false,
                onClick = onOpenLibrary,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(18.dp)
                    .background(TwilightTokens.CardBorder)
            )
            DockCapsuleItem(
                icon = TwilightIcons.Equalizer,
                label = "信号与均衡",
                badgeText = if (isEqActive) "ON" else null,
                isActive = isEqActive,
                onClick = onOpenEqDsp,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(18.dp)
                    .background(TwilightTokens.CardBorder)
            )
            DockCapsuleItem(
                icon = TwilightIcons.Queue,
                label = "播放队列",
                badgeText = if (queueCount > 0) "$queueCount" else null,
                isActive = false,
                onClick = onOpenQueue,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun DockCapsuleItem(
    icon: ImageVector,
    label: String,
    badgeText: String?,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (isActive) TwilightTokens.PrimaryCobalt else TwilightTokens.TextSecondary,
            modifier = Modifier.size(17.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (isActive) TwilightTokens.PrimaryCobalt else TwilightTokens.TextPrimary,
            maxLines = 1
        )
        if (badgeText != null) {
            Spacer(modifier = Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (isActive) TwilightTokens.PrimaryCobaltSoft
                        else TwilightTokens.PillSurface
                    )
                    .padding(horizontal = 6.dp, vertical = 1.5.dp)
            ) {
                Text(
                    text = badgeText,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp),
                    color = if (isActive) TwilightTokens.PrimaryCobalt else TwilightTokens.TextSecondary
                )
            }
        }
    }
}

@Composable
private fun SynchronizedLyricsCard(
    lines: List<CoreLyricLine>,
    activeIndex: Int,
    onSeekToLine: (CoreLyricLine) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0 && activeIndex < lines.size) {
            val targetScroll = (activeIndex - 2).coerceAtLeast(0)
            listState.animateScrollToItem(targetScroll)
        }
    }

    TwilightWhiteCard(
        modifier = modifier,
        cornerRadius = 28.dp,
        elevation = 8.dp
    ) {
        if (lines.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = TwilightIcons.MusicNote,
                    contentDescription = null,
                    tint = TwilightTokens.TextMuted,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "暂无同步歌词",
                    style = MaterialTheme.typography.titleMedium,
                    color = TwilightTokens.TextSecondary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "支持内嵌 LRC 与同名外挂歌词自动解析",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TwilightTokens.TextMuted
                )
            }
        } else {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(vertical = 28.dp, horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(lines) { index, line ->
                    val isCurrent = index == activeIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                if (isCurrent) TwilightTokens.PrimaryCobaltSoft
                                else Color.Transparent
                            )
                            .border(
                                width = if (isCurrent) 0.8.dp else 0.dp,
                                color = if (isCurrent) TwilightTokens.PrimaryCobaltBorder else Color.Transparent,
                                shape = RoundedCornerShape(16.dp)
                            )
                            .clickable { onSeekToLine(line) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isCurrent) {
                            Box(
                                modifier = Modifier
                                    .width(3.5.dp)
                                    .height(22.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(TwilightTokens.PrimaryCobalt)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                        }

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                text = line.text,
                                style = if (isCurrent) {
                                    MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                                } else {
                                    MaterialTheme.typography.bodyLarge
                                },
                                color = if (isCurrent) TwilightTokens.TextPrimary else TwilightTokens.TextMuted
                            )
                            line.translation?.let { trans ->
                                Text(
                                    text = trans,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (isCurrent) TwilightTokens.PrimaryCobalt else TwilightTokens.TextMuted.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BookmarkChipsRow(
    bookmarks: List<CoreBookmarkView>,
    onSeekToBookmark: (CoreBookmarkView) -> Unit,
    onRemoveBookmark: (CoreBookmarkView) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        bookmarks.forEach { bm ->
            val cleanLabel = bm.label.replace("🔖", "").trim()
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(TwilightTokens.CardSurface)
                    .border(0.8.dp, TwilightTokens.PrimaryCobaltBorder, RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = TwilightIcons.Bookmark,
                    contentDescription = null,
                    tint = TwilightTokens.PrimaryCobalt,
                    modifier = Modifier.size(12.dp)
                )
                Text(
                    text = "$cleanLabel · ${formatDurationSeconds(bm.positionSec)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TwilightTokens.TextPrimary,
                    modifier = Modifier.clickable { onSeekToBookmark(bm) }
                )
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(TwilightTokens.PillSurface)
                        .clickable { onRemoveBookmark(bm) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = TwilightIcons.Close,
                        contentDescription = "Remove bookmark",
                        tint = TwilightTokens.TextSecondary,
                        modifier = Modifier.size(10.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun LocalLibraryModalSheet(
    activeTab: LibrarySectionTab,
    searchQuery: String,
    tracks: List<CoreTrackView>,
    albums: List<CoreAlbumCollectionView>,
    artists: List<CoreArtistCollectionView>,
    playlists: List<CorePlaylistView>,
    newPlaylistNameDraft: String,
    favoriteIds: List<String>,
    isScanning: Boolean,
    scanStatusMessage: String?,
    onSelectTab: (LibrarySectionTab) -> Unit,
    onQueryChange: (String) -> Unit,
    onScanDeviceMusicClick: () -> Unit,
    onSelectTrack: (CoreTrackView) -> Unit,
    onPlayCollectionTrackIds: (List<String>) -> Unit,
    onNewPlaylistNameChange: (String) -> Unit,
    onCreatePlaylist: () -> Unit,
    onAddCurrentTrackToPlaylist: (String) -> Unit,
    onDeletePlaylist: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.84f)
            .padding(horizontal = 20.dp)
            .padding(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Sheet Header + Scan Action Pill
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "LOCAL LIBRARY",
                    style = MaterialTheme.typography.labelSmall,
                    color = TwilightTokens.TextMuted
                )
                Text(
                    text = "本地音乐库与收藏",
                    style = MaterialTheme.typography.headlineMedium,
                    color = TwilightTokens.TextPrimary
                )
            }

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (isScanning) TwilightTokens.PillSurface else TwilightTokens.PrimaryCobalt
                    )
                    .clickable(enabled = !isScanning, onClick = onScanDeviceMusicClick)
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = TwilightIcons.RadarScan,
                    contentDescription = null,
                    tint = if (isScanning) TwilightTokens.TextSecondary else Color.White,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = if (isScanning) "正在扫描..." else "扫描本地音乐",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isScanning) TwilightTokens.TextSecondary else Color.White
                )
            }
        }

        scanStatusMessage?.let { msg ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(TwilightTokens.SignalEmeraldSoft)
                    .border(0.8.dp, TwilightTokens.SignalEmeraldBorder, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = msg,
                    style = MaterialTheme.typography.labelMedium,
                    color = TwilightTokens.SignalEmeraldDark
                )
            }
        }

        // Segmented Category Tabs
        SegmentedPillSwitch(
            options = LibrarySectionTab.entries,
            selectedOption = activeTab,
            labelProvider = { tab ->
                val count = when (tab) {
                    LibrarySectionTab.SONGS -> tracks.size
                    LibrarySectionTab.ALBUMS -> albums.size
                    LibrarySectionTab.ARTISTS -> artists.size
                    LibrarySectionTab.PLAYLISTS -> playlists.size
                }
                val cnLabel = when (tab) {
                    LibrarySectionTab.SONGS -> "单曲"
                    LibrarySectionTab.ALBUMS -> "专辑"
                    LibrarySectionTab.ARTISTS -> "歌手"
                    LibrarySectionTab.PLAYLISTS -> "歌单"
                }
                "$cnLabel ($count)"
            },
            onSelect = onSelectTab,
            modifier = Modifier.fillMaxWidth()
        )

        when (activeTab) {
            LibrarySectionTab.SONGS -> {
                // Modern Pill Search Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(TwilightTokens.SubtleSurface)
                        .border(0.8.dp, TwilightTokens.CardBorder, RoundedCornerShape(16.dp))
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = TwilightIcons.Search,
                        contentDescription = null,
                        tint = TwilightTokens.TextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                    Box(modifier = Modifier.weight(1f)) {
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = "搜索曲名、歌手、专辑或无损格式...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TwilightTokens.TextMuted
                            )
                        }
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = onQueryChange,
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = TwilightTokens.TextPrimary
                            ),
                            cursorBrush = SolidColor(TwilightTokens.PrimaryCobalt),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (searchQuery.isNotEmpty()) {
                        Icon(
                            imageVector = TwilightIcons.Close,
                            contentDescription = "Clear",
                            tint = TwilightTokens.TextMuted,
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { onQueryChange("") }
                        )
                    }
                }

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(tracks, key = { it.id }) { track ->
                        val isFav = track.id in favoriteIds
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(TwilightTokens.SubtleSurface)
                                .border(0.8.dp, TwilightTokens.CardBorder, RoundedCornerShape(16.dp))
                                .clickable { onSelectTrack(track) }
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(11.dp))
                                        .background(TwilightTokens.CardSurface)
                                        .border(0.8.dp, TwilightTokens.CardBorder, RoundedCornerShape(11.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = TwilightIcons.MusicNote,
                                        contentDescription = null,
                                        tint = TwilightTokens.PrimaryCobalt,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = track.title,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = TwilightTokens.TextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        if (isFav) {
                                            Icon(
                                                imageVector = TwilightIcons.HeartFilled,
                                                contentDescription = null,
                                                tint = TwilightTokens.FavoriteRose,
                                                modifier = Modifier.size(13.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        text = "${track.artist} · ${track.album}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = TwilightTokens.TextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(
                                horizontalAlignment = Alignment.End,
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Text(
                                    text = formatDurationSeconds(track.duration),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TwilightTokens.TextSecondary
                                )
                                track.format?.let { fmt ->
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(TwilightTokens.PrimaryCobaltSoft)
                                            .padding(horizontal = 6.dp, vertical = 1.5.dp)
                                    ) {
                                        Text(
                                            text = fmt.uppercase(),
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp),
                                            color = TwilightTokens.PrimaryCobalt
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            LibrarySectionTab.ALBUMS -> {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(albums, key = { it.id }) { album ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(TwilightTokens.SubtleSurface)
                                .border(0.8.dp, TwilightTokens.CardBorder, RoundedCornerShape(16.dp))
                                .clickable { onPlayCollectionTrackIds(album.trackIds) }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(TwilightTokens.CardSurface)
                                        .border(0.8.dp, TwilightTokens.CardBorder, RoundedCornerShape(12.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = TwilightIcons.AlbumDisc,
                                        contentDescription = null,
                                        tint = TwilightTokens.PrimaryCobalt,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = album.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = TwilightTokens.TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${album.artist} · ${album.trackCount} 首曲目",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = TwilightTokens.TextSecondary
                                    )
                                }
                            }
                            Text(
                                text = formatDurationSeconds(album.totalDurationSec),
                                style = MaterialTheme.typography.labelSmall,
                                color = TwilightTokens.PrimaryCobalt
                            )
                        }
                    }
                }
            }

            LibrarySectionTab.ARTISTS -> {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(artists, key = { it.id }) { artist ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(TwilightTokens.SubtleSurface)
                                .border(0.8.dp, TwilightTokens.CardBorder, RoundedCornerShape(16.dp))
                                .clickable { onPlayCollectionTrackIds(artist.trackIds) }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clip(CircleShape)
                                        .background(TwilightTokens.CardSurface)
                                        .border(0.8.dp, TwilightTokens.CardBorder, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = TwilightIcons.ArtistMic,
                                        contentDescription = null,
                                        tint = TwilightTokens.PrimaryCobalt,
                                        modifier = Modifier.size(19.dp)
                                    )
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = artist.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = TwilightTokens.TextPrimary
                                    )
                                    Text(
                                        text = "${artist.albumCount} 张专辑 · ${artist.trackCount} 首曲目",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = TwilightTokens.TextSecondary
                                    )
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(TwilightTokens.PrimaryCobaltSoft)
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    text = "播放全部",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TwilightTokens.PrimaryCobalt
                                )
                            }
                        }
                    }
                }
            }

            LibrarySectionTab.PLAYLISTS -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(TwilightTokens.SubtleSurface)
                            .border(0.8.dp, TwilightTokens.CardBorder, RoundedCornerShape(14.dp))
                            .padding(horizontal = 14.dp, vertical = 11.dp)
                    ) {
                        if (newPlaylistNameDraft.isEmpty()) {
                            Text(
                                text = "输入新歌单名称...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TwilightTokens.TextMuted
                            )
                        }
                        BasicTextField(
                            value = newPlaylistNameDraft,
                            onValueChange = onNewPlaylistNameChange,
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = TwilightTokens.TextPrimary
                            ),
                            cursorBrush = SolidColor(TwilightTokens.PrimaryCobalt),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(TwilightTokens.PrimaryCobalt)
                            .clickable(onClick = onCreatePlaylist)
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = TwilightIcons.Plus,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "新建歌单",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White
                        )
                    }
                }

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(playlists, key = { it.id }) { pl ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(TwilightTokens.SubtleSurface)
                                .border(0.8.dp, TwilightTokens.CardBorder, RoundedCornerShape(16.dp))
                                .clickable { onPlayCollectionTrackIds(pl.trackIds) }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = pl.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TwilightTokens.TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${pl.trackCount} 首曲目",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TwilightTokens.TextSecondary
                                )
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(TwilightTokens.PrimaryCobaltSoft)
                                        .clickable { onAddCurrentTrackToPlaylist(pl.id) }
                                        .padding(horizontal = 10.dp, vertical = 5.dp)
                                ) {
                                    Text(
                                        text = "+加入当前",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = TwilightTokens.PrimaryCobalt
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(TwilightTokens.FavoriteRoseSoft)
                                        .clickable { onDeletePlaylist(pl.id) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = TwilightIcons.Trash,
                                        contentDescription = "Delete playlist",
                                        tint = TwilightTokens.FavoriteRose,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EqualizerDspModalSheet(
    diagnostics: AudioDiagnosticsUiModel,
    eqConfig: CoreEqConfigView,
    onToggleEqEnabled: () -> Unit,
    onSelectPreset: (String) -> Unit,
    onSetBandGain: (Int, Double) -> Unit,
    onToggleBitPerfect: () -> Unit,
    onToggleGapless: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.86f)
            .padding(horizontal = 20.dp)
            .padding(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Desktop-Inspired Signal Path Diagnostic Card
        SignalPathArchitectureCard(
            codec = diagnostics.codec,
            sourceSampleRateHz = diagnostics.sourceSampleRateHz,
            sourceBitDepth = diagnostics.sourceBitDepth,
            outputBackend = diagnostics.outputBackend,
            actualSampleRateHz = diagnostics.actualSampleRateHz,
            actualBitDepth = diagnostics.actualBitDepth,
            outputPerfect = diagnostics.outputPerfect,
            eqEnabled = eqConfig.enabled,
            eqPreset = eqConfig.preset
        )

        // 2. Three Tactile Engine Switch Cards (EQ | Bit-Perfect | Gapless)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DspSwitchTile(
                title = "参量均衡 EQ",
                subtitle = if (eqConfig.enabled) "已启用 (${eqConfig.preset})" else "直通关闭",
                active = eqConfig.enabled,
                onClick = onToggleEqEnabled,
                modifier = Modifier.weight(1f)
            )
            DspSwitchTile(
                title = "Bit-Perfect",
                subtitle = if (eqConfig.bitPerfect) "独占采样开启" else "系统混音",
                active = eqConfig.bitPerfect,
                onClick = onToggleBitPerfect,
                modifier = Modifier.weight(1f)
            )
            DspSwitchTile(
                title = "Gapless 无缝",
                subtitle = if (eqConfig.gapless) "预载衔接开启" else "标准切歌",
                active = eqConfig.gapless,
                onClick = onToggleGapless,
                modifier = Modifier.weight(1f)
            )
        }

        // 3. Preset Pills Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CoreEqConfigView.PRESETS.keys.forEach { presetName ->
                val isSelected = eqConfig.preset.equals(presetName, ignoreCase = true)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (isSelected) TwilightTokens.PrimaryCobalt
                            else TwilightTokens.SubtleSurface
                        )
                        .border(
                            width = 0.8.dp,
                            color = if (isSelected) TwilightTokens.PrimaryCobalt else TwilightTokens.CardBorder,
                            shape = RoundedCornerShape(50)
                        )
                        .clickable { onSelectPreset(presetName) }
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Text(
                        text = presetName,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isSelected) Color.White else TwilightTokens.TextSecondary
                    )
                }
            }
        }

        // 4. 10-Band Parametric Equalizer Faders
        TwilightWhiteCard(
            modifier = Modifier.fillMaxSize(),
            cornerRadius = 20.dp,
            elevation = 2.dp,
            containerColor = TwilightTokens.SubtleSurface
        ) {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(CoreEqConfigView.BAND_FREQUENCIES_HZ) { idx, freqLabel ->
                    val bandGain = eqConfig.bandsDb.getOrElse(idx) { 0.0 }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = freqLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = TwilightTokens.TextSecondary,
                            modifier = Modifier.width(46.dp)
                        )
                        Slider(
                            value = bandGain.toFloat(),
                            onValueChange = { onSetBandGain(idx, it.toDouble()) },
                            valueRange = -12f..12f,
                            thumb = {
                                Box(
                                    modifier = Modifier
                                        .size(13.dp)
                                        .shadow(2.dp, CircleShape, ambientColor = TwilightTokens.PrimaryCobalt)
                                        .clip(CircleShape)
                                        .background(TwilightTokens.PrimaryCobalt)
                                        .border(2.dp, Color.White, CircleShape)
                                )
                            },
                            track = { sliderState ->
                                val fraction = ((sliderState.value - sliderState.valueRange.start) /
                                    (sliderState.valueRange.endInclusive - sliderState.valueRange.start).coerceAtLeast(0.0001f))
                                    .coerceIn(0f, 1f)
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(3.5.dp)
                                        .clip(RoundedCornerShape(50))
                                        .background(TwilightTokens.PillSurface)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(fraction)
                                            .height(3.5.dp)
                                            .clip(RoundedCornerShape(50))
                                            .background(TwilightTokens.PrimaryCobalt)
                                    )
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(28.dp)
                        )
                        Text(
                            text = "%+.1f dB".format(bandGain),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (bandGain != 0.0) TwilightTokens.PrimaryCobalt else TwilightTokens.TextMuted,
                            modifier = Modifier.width(58.dp),
                            textAlign = TextAlign.End
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DspSwitchTile(
    title: String,
    subtitle: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (active) TwilightTokens.PrimaryCobaltSoft else TwilightTokens.SubtleSurface)
            .border(
                width = 0.8.dp,
                color = if (active) TwilightTokens.PrimaryCobaltBorder else TwilightTokens.CardBorder,
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = if (active) TwilightTokens.PrimaryCobalt else TwilightTokens.TextPrimary,
            maxLines = 1
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = if (active) TwilightTokens.PrimaryCobalt else TwilightTokens.TextMuted,
            maxLines = 1
        )
    }
}

@Composable
private fun QueueModalSheet(
    items: List<CoreQueueEntryView>,
    currentQueueIndex: Int,
    onSelectEntry: (CoreQueueEntryView) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.72f)
            .padding(horizontal = 20.dp)
            .padding(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "PLAYBACK QUEUE",
                    style = MaterialTheme.typography.labelSmall,
                    color = TwilightTokens.TextMuted
                )
                Text(
                    text = "当前播放队列 (${items.size})",
                    style = MaterialTheme.typography.headlineMedium,
                    color = TwilightTokens.TextPrimary
                )
            }
        }

        if (items.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "队列为空，请从音乐库选择曲目播放",
                    style = MaterialTheme.typography.bodyLarge,
                    color = TwilightTokens.TextMuted
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(items, key = { it.occurrenceId }) { entry ->
                    val isCurrent = entry.index == currentQueueIndex
                    val track = entry.track
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                if (isCurrent) TwilightTokens.PrimaryCobaltSoft
                                else TwilightTokens.SubtleSurface
                            )
                            .border(
                                width = 0.8.dp,
                                color = if (isCurrent) TwilightTokens.PrimaryCobaltBorder else TwilightTokens.CardBorder,
                                shape = RoundedCornerShape(16.dp)
                            )
                            .clickable { onSelectEntry(entry) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (isCurrent) {
                                Icon(
                                    imageVector = TwilightIcons.SignalWave,
                                    contentDescription = "Playing",
                                    tint = TwilightTokens.PrimaryCobalt,
                                    modifier = Modifier.size(18.dp)
                                )
                            } else {
                                Text(
                                    text = "%02d".format(entry.index + 1),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TwilightTokens.TextMuted
                                )
                            }

                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = track?.title ?: "Unknown Track",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (isCurrent) TwilightTokens.PrimaryCobalt else TwilightTokens.TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = track?.artist ?: "",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TwilightTokens.TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Text(
                            text = formatDurationSeconds(track?.duration ?: 0.0),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isCurrent) TwilightTokens.PrimaryCobalt else TwilightTokens.TextMuted
                        )
                    }
                }
            }
        }
    }
}


@Composable
private fun RemoteCoreBridgeModalSheet(
    operatingMode: CoreOperatingMode,
    remoteHostInput: String,
    remotePinInput: String,
    isConnecting: Boolean,
    statusMessage: String?,
    onHostInputChange: (String) -> Unit,
    onPinInputChange: (String) -> Unit,
    onConnectDesktopCore: () -> Unit,
    onSwitchToStandaloneLocal: () -> Unit
) {
    val isRemote = operatingMode is CoreOperatingMode.RemoteDesktopCore
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "核桥分离模式 · Core & Remote Bridge",
            style = MaterialTheme.typography.headlineMedium,
            color = TwilightTokens.TextPrimary
        )

        Text(
            text = if (isRemote) {
                "当前处于【电脑 Core 远程控制器模式 (Roon Remote)】：手机直接控制局域网电脑 Core 的曲库、队列与播放。"
            } else {
                "当前处于【本机独立听歌模式 (Standalone Local Core)】：由手机内置 Music Core 与硬件音频引擎独立播放。"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = TwilightTokens.TextSecondary
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "电脑端局域网地址 (IP:Port，在电脑端设置 -> 远程控制查看)",
                style = MaterialTheme.typography.labelSmall,
                color = TwilightTokens.TextSecondary
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(TwilightTokens.SubtleSurface)
                    .border(0.8.dp, TwilightTokens.CardBorder, RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                BasicTextField(
                    value = remoteHostInput,
                    onValueChange = onHostInputChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = TwilightTokens.TextPrimary),
                    cursorBrush = SolidColor(TwilightTokens.PrimaryCobalt),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "6 位配对 PIN 码 (首次连接或电脑重置 PIN 时填写，已配对可留空)",
                style = MaterialTheme.typography.labelSmall,
                color = TwilightTokens.TextSecondary
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(TwilightTokens.SubtleSurface)
                    .border(0.8.dp, TwilightTokens.CardBorder, RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                BasicTextField(
                    value = remotePinInput,
                    onValueChange = onPinInputChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = TwilightTokens.TextPrimary),
                    cursorBrush = SolidColor(TwilightTokens.PrimaryCobalt),
                    modifier = Modifier.fillMaxWidth()
                )
                if (remotePinInput.isEmpty()) {
                    Text(
                        text = "例如: 123456 (已配对可直接连接)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TwilightTokens.TextMuted
                    )
                }
            }
        }

        if (!statusMessage.isNullOrBlank()) {
            Text(
                text = statusMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = TwilightTokens.PrimaryCobalt
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(TwilightTokens.PrimaryCobalt)
                    .clickable(enabled = !isConnecting) { onConnectDesktopCore() }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isConnecting) "连接中..." else "🖥️ 连接电脑 Core",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
            }

            if (isRemote) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(TwilightTokens.SubtleSurface)
                        .border(0.8.dp, TwilightTokens.CardBorder, RoundedCornerShape(16.dp))
                        .clickable(enabled = !isConnecting) { onSwitchToStandaloneLocal() }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "📱 切回本机独立听歌",
                        style = MaterialTheme.typography.titleMedium,
                        color = TwilightTokens.TextPrimary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}
