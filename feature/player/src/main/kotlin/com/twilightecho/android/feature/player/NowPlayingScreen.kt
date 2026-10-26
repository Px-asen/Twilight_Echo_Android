package com.twilightecho.android.feature.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.twilightecho.android.coreclient.CoreAlbumCollectionView
import com.twilightecho.android.coreclient.CoreArtistCollectionView
import com.twilightecho.android.coreclient.CoreBookmarkView
import com.twilightecho.android.coreclient.CoreEqConfigView
import com.twilightecho.android.coreclient.CoreLyricLine
import com.twilightecho.android.coreclient.CorePlaylistView
import com.twilightecho.android.coreclient.CoreQueueEntryView
import com.twilightecho.android.coreclient.CoreTrackView
import com.twilightecho.android.ui.components.ArtworkDiscPlaceholder
import com.twilightecho.android.ui.components.AudioDiagnosticsBadge
import com.twilightecho.android.ui.components.formatDurationSeconds
import com.twilightecho.android.ui.theme.TwilightTokens

@Composable
fun NowPlayingScreen(
    viewModel: PlayerViewModel,
    onRequestScanMusicPermission: (() -> Unit)? = null,
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
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Bar: Core telemetry + Lyrics / Library / Queue toggles
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
                        text = "rev #${state.queueRevision} • seq ${state.sequence}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TwilightTokens.TextSecondary
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = { viewModel.onToggleLyricsMode() }
                    ) {
                        Text(
                            text = if (state.isLyricsMode) "Artwork" else "Lyrics",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (state.isLyricsMode) TwilightTokens.AccentAmber else TwilightTokens.TextPrimary
                        )
                    }

                    OutlinedButton(
                        onClick = { viewModel.onToggleLibrarySheet() }
                    ) {
                        Text(
                            text = "Library (${state.libraryTrackCount})",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (state.isLibrarySheetOpen) TwilightTokens.AccentAmber else TwilightTokens.TextPrimary
                        )
                    }

                    OutlinedButton(
                        onClick = { viewModel.onToggleEqSheet() }
                    ) {
                        Text(
                            text = if (state.eqConfig.enabled) "EQ: ${state.eqConfig.preset}" else "EQ / DSP",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (state.isEqSheetOpen || state.eqConfig.enabled) TwilightTokens.HiResEmerald else TwilightTokens.TextPrimary
                        )
                    }

                    OutlinedButton(
                        onClick = { viewModel.onToggleQueueDrawer() }
                    ) {
                        Text(
                            text = "Queue (${state.queueItems.size})",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (state.isQueueDrawerOpen) TwilightTokens.AccentAmber else TwilightTokens.TextPrimary
                        )
                    }
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

            // Center Stage: either Synchronized Lyrics View or Artwork Disc
            val trackTitle = state.currentTrack?.title ?: "No Track Loaded"
            val trackArtist = state.currentTrack?.artist ?: "Twilight Echo Core"
            val trackAlbum = state.currentTrack?.album ?: ""

            if (state.isLyricsMode) {
                SynchronizedLyricsPanel(
                    lines = state.currentLyrics?.lines.orEmpty(),
                    activeIndex = state.activeLyricIndex,
                    onSeekToLine = { line ->
                        viewModel.onSeekTo(line.timeMs / 1000.0)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(250.dp)
                )
            } else {
                ArtworkDiscPlaceholder(
                    title = trackTitle,
                    album = trackAlbum,
                    isPlaying = state.isPlaying
                )
            }

            // Track Metadata + Favorite (♥) & Add Bookmark (🔖) Actions
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

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { viewModel.onToggleFavoriteCurrentTrack() }
                    ) {
                        Text(
                            text = if (state.isCurrentTrackFavorite) "♥ Favorited" else "♡ Favorite",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (state.isCurrentTrackFavorite) TwilightTokens.ErrorRose else TwilightTokens.TextPrimary
                        )
                    }

                    OutlinedButton(
                        onClick = { viewModel.onAddBookmarkAtCurrentPosition() }
                    ) {
                        Text(
                            text = "🔖 +Bookmark (${formatDurationSeconds(state.interpolatedPositionSec)})",
                            style = MaterialTheme.typography.labelSmall,
                            color = TwilightTokens.AccentAmber
                        )
                    }
                }

                // Bookmark chips row (tap chip to seek to saved position, tap × to delete)
                if (state.currentTrackBookmarks.isNotEmpty()) {
                    BookmarkChipsRow(
                        bookmarks = state.currentTrackBookmarks,
                        onSeekToBookmark = { bm -> viewModel.onSeekTo(bm.positionSec) },
                        onRemoveBookmark = { bm -> viewModel.onRemoveBookmark(bm.id) }
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))
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
                val currentPos = state.interpolatedPositionSec.coerceIn(0.0, state.durationSeconds).toFloat()

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
                        text = formatDurationSeconds(state.interpolatedPositionSec),
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

            // Expandable Local Library, Collections & Playlists Sheet
            AnimatedVisibility(visible = state.isLibrarySheetOpen) {
                LocalLibrarySearchSheet(
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

            // Expandable Equalizer / DSP & Output Configuration Sheet
            AnimatedVisibility(visible = state.isEqSheetOpen) {
                EqualizerDspSheet(
                    eqConfig = state.eqConfig,
                    onToggleEqEnabled = { viewModel.onToggleEqEnabled() },
                    onSelectPreset = { viewModel.onSelectEqPreset(it) },
                    onSetBandGain = { idx, db -> viewModel.onSetEqBandGain(idx, db) },
                    onToggleBitPerfect = { viewModel.onToggleBitPerfect() },
                    onToggleGapless = { viewModel.onToggleGapless() }
                )
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
private fun SynchronizedLyricsPanel(
    lines: List<CoreLyricLine>,
    activeIndex: Int,
    onSeekToLine: (CoreLyricLine) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(TwilightTokens.valElevatedSurface)
            .border(1.dp, TwilightTokens.AccentViolet.copy(alpha = 0.35f), RoundedCornerShape(24.dp))
            .padding(16.dp)
    ) {
        if (lines.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No synchronized lyrics available",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TwilightTokens.TextSecondary
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(lines) { index, line ->
                    val isCurrent = index == activeIndex
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isCurrent) TwilightTokens.GlassSurface
                                else TwilightTokens.valElevatedSurface
                            )
                            .clickable { onSeekToLine(line) }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = line.text,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                            ),
                            color = if (isCurrent) TwilightTokens.AccentAmber else TwilightTokens.TextPrimary,
                            textAlign = TextAlign.Center
                        )
                        line.translation?.let { trans ->
                            Text(
                                text = trans,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isCurrent) TwilightTokens.HiResEmerald else TwilightTokens.TextSecondary,
                                textAlign = TextAlign.Center
                            )
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
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(TwilightTokens.GlassSurface)
                    .border(1.dp, TwilightTokens.AccentAmber.copy(alpha = 0.45f), RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "${bm.label} (${formatDurationSeconds(bm.positionSec)})",
                    style = MaterialTheme.typography.labelSmall,
                    color = TwilightTokens.AccentAmber,
                    modifier = Modifier.clickable { onSeekToBookmark(bm) }
                )
                Text(
                    text = "×",
                    style = MaterialTheme.typography.labelSmall,
                    color = TwilightTokens.TextSecondary,
                    modifier = Modifier.clickable { onRemoveBookmark(bm) }
                )
            }
        }
    }
}

@Composable
private fun LocalLibrarySearchSheet(
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
            .height(265.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(TwilightTokens.valElevatedSurface)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "LOCAL LIBRARY & COLLECTIONS",
                style = MaterialTheme.typography.labelSmall,
                color = TwilightTokens.AccentAmber
            )
            Button(
                onClick = onScanDeviceMusicClick,
                enabled = !isScanning,
                colors = ButtonDefaults.buttonColors(
                    containerColor = TwilightTokens.HiResEmerald,
                    contentColor = TwilightTokens.DeepNightBackground
                )
            ) {
                Text(
                    text = if (isScanning) "扫描中..." else "扫描本地音乐 (Scan)",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }

        // Sub-tabs: Songs | Albums | Artists | Playlists
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LibrarySectionTab.entries.forEach { tab ->
                val selected = tab == activeTab
                val countBadge = when (tab) {
                    LibrarySectionTab.SONGS -> tracks.size
                    LibrarySectionTab.ALBUMS -> albums.size
                    LibrarySectionTab.ARTISTS -> artists.size
                    LibrarySectionTab.PLAYLISTS -> playlists.size
                }
                OutlinedButton(
                    onClick = { onSelectTab(tab) }
                ) {
                    Text(
                        text = "${tab.label} ($countBadge)",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) TwilightTokens.AccentAmber else TwilightTokens.TextSecondary
                    )
                }
            }
        }

        scanStatusMessage?.let { msg ->
            Text(
                text = msg,
                style = MaterialTheme.typography.labelSmall,
                color = TwilightTokens.HiResEmerald
            )
        }

        when (activeTab) {
            LibrarySectionTab.SONGS -> {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onQueryChange,
                    placeholder = {
                        Text(
                            text = "Search songs, artists, albums...",
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(tracks, key = { it.id }) { track ->
                        val isFav = track.id in favoriteIds
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(TwilightTokens.GlassSurface)
                                .clickable { onSelectTrack(track) }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = (if (isFav) "♥ " else "") + track.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TwilightTokens.TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${track.artist} • ${track.album} (${track.format ?: "AUDIO"})",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TwilightTokens.TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = formatDurationSeconds(track.duration),
                                style = MaterialTheme.typography.labelSmall,
                                color = TwilightTokens.AccentAmber
                            )
                        }
                    }
                }
            }

            LibrarySectionTab.ALBUMS -> {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(albums, key = { it.id }) { album ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(TwilightTokens.GlassSurface)
                                .clickable { onPlayCollectionTrackIds(album.trackIds) }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "💿 ${album.title}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TwilightTokens.TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${album.artist} • ${album.trackCount} tracks",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TwilightTokens.TextSecondary
                                )
                            }
                            Text(
                                text = formatDurationSeconds(album.totalDurationSec),
                                style = MaterialTheme.typography.labelSmall,
                                color = TwilightTokens.AccentAmber
                            )
                        }
                    }
                }
            }

            LibrarySectionTab.ARTISTS -> {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(artists, key = { it.id }) { artist ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(TwilightTokens.GlassSurface)
                                .clickable { onPlayCollectionTrackIds(artist.trackIds) }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "🎙 ${artist.name}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TwilightTokens.TextPrimary
                                )
                                Text(
                                    text = "${artist.albumCount} albums • ${artist.trackCount} tracks",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TwilightTokens.TextSecondary
                                )
                            }
                            Text(
                                text = "Play All",
                                style = MaterialTheme.typography.labelSmall,
                                color = TwilightTokens.AccentAmber
                            )
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
                    OutlinedTextField(
                        value = newPlaylistNameDraft,
                        onValueChange = onNewPlaylistNameChange,
                        placeholder = {
                            Text(
                                text = "New playlist name...",
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = onCreatePlaylist,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TwilightTokens.AccentAmber,
                            contentColor = TwilightTokens.DeepNightBackground
                        )
                    ) {
                        Text(
                            text = "+Create",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(playlists, key = { it.id }) { pl ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(TwilightTokens.GlassSurface)
                                .clickable { onPlayCollectionTrackIds(pl.trackIds) }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "🎶 ${pl.name}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TwilightTokens.TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${pl.trackCount} tracks",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TwilightTokens.TextSecondary
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "+Add Current",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TwilightTokens.HiResEmerald,
                                    modifier = Modifier.clickable { onAddCurrentTrackToPlaylist(pl.id) }
                                )
                                Text(
                                    text = "Delete",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TwilightTokens.ErrorRose,
                                    modifier = Modifier.clickable { onDeletePlaylist(pl.id) }
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
private fun EqualizerDspSheet(
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
            .height(235.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(TwilightTokens.valElevatedSurface)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "10-BAND PARAMETRIC EQ & NATIVE DSP",
                style = MaterialTheme.typography.labelSmall,
                color = TwilightTokens.AccentAmber
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = onToggleEqEnabled) {
                    Text(
                        text = if (eqConfig.enabled) "EQ: ON" else "EQ: OFF",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (eqConfig.enabled) TwilightTokens.HiResEmerald else TwilightTokens.TextSecondary
                    )
                }
                OutlinedButton(onClick = onToggleBitPerfect) {
                    Text(
                        text = if (eqConfig.bitPerfect) "Bit-Perfect: ON" else "Bit-Perfect: OFF",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (eqConfig.bitPerfect) TwilightTokens.AccentAmber else TwilightTokens.TextSecondary
                    )
                }
                OutlinedButton(onClick = onToggleGapless) {
                    Text(
                        text = if (eqConfig.gapless) "Gapless: ON" else "Gapless: OFF",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (eqConfig.gapless) TwilightTokens.AccentAmber else TwilightTokens.TextSecondary
                    )
                }
            }
        }

        // Presets Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            CoreEqConfigView.PRESETS.keys.forEach { presetName ->
                val isSelected = eqConfig.preset.equals(presetName, ignoreCase = true)
                OutlinedButton(onClick = { onSelectPreset(presetName) }) {
                    Text(
                        text = presetName,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) TwilightTokens.AccentAmber else TwilightTokens.TextPrimary
                    )
                }
            }
        }

        // 10-band quick adjustment list
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(4.dp),
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
                        modifier = Modifier.width(48.dp)
                    )
                    Slider(
                        value = bandGain.toFloat(),
                        onValueChange = { onSetBandGain(idx, it.toDouble()) },
                        valueRange = -12f..12f,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = TwilightTokens.HiResEmerald,
                            activeTrackColor = TwilightTokens.HiResEmerald,
                            inactiveTrackColor = TwilightTokens.GlassSurface
                        )
                    )
                    Text(
                        text = "%+.1fdB".format(bandGain),
                        style = MaterialTheme.typography.labelSmall,
                        color = TwilightTokens.AccentAmber,
                        modifier = Modifier.width(56.dp),
                        textAlign = TextAlign.End
                    )
                }
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
