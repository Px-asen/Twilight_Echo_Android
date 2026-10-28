@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.twilightecho.android.feature.player

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.twilightecho.android.coreclient.*
import com.twilightecho.android.ui.components.*
import com.twilightecho.android.ui.theme.LocalTwilightAccents
import com.twilightecho.android.ui.theme.TwilightEchoTheme
import com.twilightecho.android.ui.theme.TwilightTokens
import java.time.LocalTime

@Composable
internal fun TrackArt(track: CoreTrackView?, modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit) {
    val art = rememberPlayerArtwork(track, size = 256)
    ArtworkImage(art, modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)
        .then(if (art.isRealEmbeddedArt) Modifier.testTag("loadedTrackArtwork") else Modifier), contentScale)
}

private fun greeting(): String = when (LocalTime.now().hour) {
    in 5..10 -> "早上好"
    in 11..13 -> "中午好"
    in 14..17 -> "下午好"
    in 18..22 -> "晚上好"
    else -> "夜深了"
}

/**
 * Home is the streaming page: today's picks, liked and recent songs, then the account's own,
 * collected and recommended playlists. Songs on the phone and computers live in the library.
 */
@Composable
internal fun HomeTabScreen(state: PlayerUiState, actions: PlayerActions) {
    val streaming = state.streaming
    val open = actions.streaming
    var creating by rememberSaveable { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(bottom = LocalDockInset.current + 24.dp),
        modifier = Modifier.fillMaxSize().testTag("home")) {
        item { PageHeading(greeting(), modifier = Modifier.padding(horizontal = 20.dp).padding(top = 8.dp)) }
        item { StreamingHome(streaming, open) }
        if (streaming.access != StreamingAccess.READY) {
            item { HomeAccessPrompt(streaming, open) }
            return@LazyColumn
        }
        item { HomeShortcuts(streaming, open) }
        val own = streaming.playlists.filter { it.owned == true }
        val collected = streaming.playlists.filter { it.owned != true }
        val libraryError = streaming.libraryError
        when {
            streaming.playlists.isEmpty() && streaming.libraryLoading -> item { LoadingContent("正在载入歌单") }
            streaming.playlists.isEmpty() && libraryError != null -> item {
                EmptyState("歌单未载入", libraryError, actionLabel = "重试", onAction = open.refresh)
            }
            else -> {
                item { PlaylistShelf("我的歌单", own, open, "home-own",
                    more = { open.group(StreamingLibraryGroup.CREATED) }, create = { creating = true }) }
                if (collected.isNotEmpty()) item {
                    PlaylistShelf("收藏的歌单", collected, open, "home-collected",
                        more = { open.group(StreamingLibraryGroup.PLAYLISTS) })
                }
            }
        }
        if (streaming.recommendedPlaylists.isNotEmpty()) item {
            PlaylistShelf("推荐歌单", streaming.recommendedPlaylists, open, "home-recommended")
        }
    }
    if (creating) StreamingPlaylistNameDialog({ creating = false }) { name ->
        creating = false
        open.createPlaylist(name, emptyList())
    }
}

/** Until the account is ready, Home says what is happening and offers the one step that helps. */
@Composable
private fun HomeAccessPrompt(state: StreamingUiState, actions: StreamingActions) = when (state.access) {
    StreamingAccess.LOADING -> LoadingContent("正在连接网易云音乐")
    StreamingAccess.ERROR -> EmptyState("网易云暂时无法连接", state.accountError ?: "请检查网络后重试。",
        TwilightIcons.SignalWave, actionLabel = "重新连接", onAction = actions.refresh)
    else -> EmptyState("登录后查看你的歌单", "我喜欢的音乐、创建和收藏的歌单、最近播放，都会出现在这里。",
        TwilightIcons.SignalWave, actionLabel = "登录网易云音乐", onAction = actions.account)
}

/** Liked and recently played songs, the two lists opened most, side by side under today's picks. */
@Composable
private fun HomeShortcuts(state: StreamingUiState, actions: StreamingActions) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        HomeShortcut(TwilightIcons.HeartFilled, TwilightTokens.FavoriteRose, "我喜欢的音乐",
            state.likedPlaylist?.count?.takeIf { it > 0 }?.let { "$it 首" },
            Modifier.weight(1f).testTag("home-liked")) { actions.group(StreamingLibraryGroup.LIKED) }
        HomeShortcut(TwilightIcons.RepeatSequence, MaterialTheme.colorScheme.primary, "最近播放", null,
            Modifier.weight(1f).testTag("home-recent")) { actions.group(StreamingLibraryGroup.RECENT) }
    }
}

@Composable
private fun HomeShortcut(icon: ImageVector, tint: Color, title: String, subtitle: String?, modifier: Modifier,
    onClick: () -> Unit) {
    Row(modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
        .clickable(role = Role.Button, onClick = onClick).heightIn(min = 68.dp)
        .padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(24.dp), tint = tint)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) SupportingText(subtitle, maxLines = 1)
        }
    }
}

/** A row of playlist covers. The title opens the whole list when there is one; [create] ends the row with a new playlist. */
@Composable
private fun PlaylistShelf(title: String, playlists: List<StreamingCollection>, actions: StreamingActions, tag: String,
    more: (() -> Unit)? = null, create: (() -> Unit)? = null) {
    val heading = Modifier.padding(horizontal = 20.dp).padding(top = 24.dp)
    if (more != null) SectionLink(title, more, heading)
    else SectionHeading(title, modifier = heading.padding(vertical = 6.dp))
    LazyRow(Modifier.padding(top = 8.dp).testTag(tag), contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(playlists.distinctBy { it.id }, key = { it.id }) { item ->
            ArtworkTile(rememberStreamingArtwork(item), item.title, item.count.takeIf { it > 0 }?.let { "$it 首" },
                { actions.collection(item) }, Modifier.width(148.dp).testTag("home-playlist-${item.id}"))
        }
        if (create != null) item(key = "create") {
            Column(Modifier.width(148.dp).clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button, onClick = create)
                .padding(bottom = 6.dp).testTag("home-create-playlist")) {
                Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) {
                    Icon(TwilightIcons.Plus, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("新建歌单", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp))
            }
        }
    }
}

/** Three bars that bounce while playing and rest flat when paused. */
@Composable
internal fun PlayingBars(playing: Boolean, color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "bars")
    val phases = listOf(0, 180, 360).map { delay ->
        transition.animateFloat(0.3f, 1f, infiniteRepeatable(tween(520, delay, FastOutSlowInEasing), RepeatMode.Reverse),
            label = "bar$delay")
    }
    Row(modifier.size(14.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        phases.forEach { phase ->
            Box(Modifier.weight(1f).fillMaxHeight(if (playing) phase.value else 0.3f).clip(RoundedCornerShape(1.dp))
                .background(color))
        }
    }
}

/** Scrolls a list back to the top when [key] changes, but keeps its place when the page is only shown again. */
@Composable
private fun ScrollToTopOn(key: String, listState: LazyListState) {
    var shown by rememberSaveable { mutableStateOf(key) }
    LaunchedEffect(key) { if (shown != key) { shown = key; listState.scrollToItem(0) } }
}

@Composable
internal fun LibraryTabScreen(state: PlayerUiState, actions: PlayerActions,
    onOpenCollection: (String, String) -> Unit, onConnectComputer: () -> Unit) {
    var createDialog by rememberSaveable { mutableStateOf(false) }
    var streamingCreate by rememberSaveable { mutableStateOf(false) }
    val dock = LocalDockInset.current
    val tab = state.activeLibraryTab
    val streamingTab = tab == LibrarySectionTab.STREAMING
    // A connected computer shown on its own lists its own albums and artists, with their pictures and notes.
    val catalog = state.desktopCatalogMode && tab in DesktopCatalogTabs
    val computer = state.libraryDevices.find { it.id == state.libraryDevice && it.id != LIBRARY_ALL && it.id != LIBRARY_PHONE }
    Column(Modifier.fillMaxSize().testTag("library")) {
        if (catalog && state.desktopLibrary.selected != null) {
            RemoteCatalogScreen(state, actions, Modifier.fillMaxSize())
            return@Column
        }
        PageHeading("音乐库", when {
            streamingTab -> "网易云音乐"
            state.libraryError != null && state.libraryTracks.isEmpty() -> null
            else -> "${state.libraryTrackCount} 首歌曲 · ${state.albums.size} 张专辑"
        }, Modifier.padding(start = 20.dp, end = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!streamingTab && state.libraryDevices.isNotEmpty())
                    LibraryDeviceMenu(state, actions.selectLibraryDevice, onConnectComputer)
                when {
                    streamingTab && state.streaming.access == StreamingAccess.READY ->
                        GlassIconButton(TwilightIcons.Plus, "新建歌单", { streamingCreate = true })
                    streamingTab -> GlassIconButton(TwilightIcons.Settings, "管理音源", actions.openPlugins)
                    tab == LibrarySectionTab.PLAYLISTS -> GlassIconButton(TwilightIcons.Plus, "新建歌单", { createDialog = true })
                    // Scanning reads this phone, so it is offered while the library shows the phone's songs.
                    computer == null && !state.isScanningLocalMusic ->
                        GlassIconButton(TwilightIcons.RadarScan, "扫描本地音乐", actions.scan)
                }
            }
        }
        if (!streamingTab) {
            if (state.isScanningLocalMusic) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
            state.scanStatusMessage?.let { SupportingText(it, Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
        }
        PillTabs(LibrarySectionTab.entries, tab, { it.label }, actions.selectLibraryTab)
        Spacer(Modifier.height(16.dp))
        if (streamingTab) {
            StreamingLibraryScreen(state.streaming, actions.streaming, Modifier.weight(1f))
            return@Column
        }
        if (catalog) {
            RemoteCatalogScreen(state, actions, Modifier.weight(1f))
            return@Column
        }
        if (state.isLoadingLibrary && state.libraryTracks.isEmpty()) {
            LoadingContent("正在载入音乐库"); return@Column
        }
        state.libraryError?.takeIf { state.libraryTracks.isEmpty() }?.let { error ->
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("音乐库未载入", style = MaterialTheme.typography.titleLarge)
                SupportingText(error)
                PillButton("重试", actions.refreshLibrary, Modifier.padding(top = 6.dp))
            }
            return@Column
        }
        if (state.libraryError != null) {
            TextButton(onClick = actions.refreshLibrary, modifier = Modifier.padding(horizontal = 20.dp)) {
                Text("加载失败，点击重试", color = MaterialTheme.colorScheme.error)
            }
        }
        if (state.libraryTracks.isEmpty() && tab != LibrarySectionTab.PLAYLISTS) {
            if (computer != null) EmptyState("${computer.label} 还没有歌曲",
                if (computer.connected) "电脑整理好曲库后，歌曲会出现在这里。" else "这台电脑现在没有连接。",
                TwilightIcons.Computer, actionLabel = if (computer.connected) "刷新" else "连接电脑",
                onAction = if (computer.connected) actions.refreshLibrary else onConnectComputer)
            else EmptyState("还没有音乐", "扫描手机里的歌曲，它们会出现在这里。",
                actionLabel = if (state.isScanningLocalMusic) null else "扫描本地音乐", onAction = actions.scan)
            return@Column
        }
        val byId = remember(state.libraryTracks, state.allTracks) { (state.allTracks + state.libraryTracks).associateBy { it.id } }
        fun cover(ids: List<String>) = ids.firstNotNullOfOrNull { byId[it] }
        when (tab) {
            LibrarySectionTab.STREAMING -> Unit // Rendered above, independently of the songs on devices.
            LibrarySectionTab.SONGS -> {
                val listState = rememberLazyListState()
                ScrollToTopOn(state.libraryDevice, listState)
                LazyColumn(Modifier.weight(1f).testTag("librarySongs"), state = listState,
                    contentPadding = PaddingValues(bottom = dock + 8.dp)) {
                    items(state.libraryTracks, key = { it.id }) { track ->
                        TrackRow(track, state, { actions.playTracks(state.libraryTracks, track.id) })
                    }
                }
            }
            LibrarySectionTab.ALBUMS -> LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.weight(1f).testTag("albumGrid"),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = dock + 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                items(state.albums, key = { it.id }) { album ->
                    ArtworkTile(rememberPlayerArtwork(cover(album.trackIds)), album.title,
                        album.artist.ifBlank { "未知艺术家" }, { onOpenCollection("album", album.id) })
                }
            }
            LibrarySectionTab.ARTISTS -> LazyVerticalGrid(GridCells.Adaptive(104.dp), Modifier.weight(1f).testTag("artistGrid"),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = dock + 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                items(state.artists, key = { it.id }) { artist ->
                    ArtworkTile(rememberPlayerArtwork(cover(artist.trackIds)), artist.name, "${artist.trackCount} 首",
                        { onOpenCollection("artist", artist.id) }, round = true, centered = true)
                }
            }
            LibrarySectionTab.PLAYLISTS -> {
                if (state.playlists.isEmpty()) EmptyState("留住喜欢的旋律", "为自己的音乐创建一张歌单。",
                    TwilightIcons.Library, actionLabel = "新建歌单", onAction = { createDialog = true })
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = dock + 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(state.playlists, key = { it.id }) { playlist ->
                        CollectionRow(playlist.name, "${playlist.trackCount} 首歌曲", cover(playlist.trackIds)) {
                            onOpenCollection("playlist", playlist.id)
                        }
                    }
                }
            }
        }
    }
    if (createDialog) AlertDialog(onDismissRequest = { createDialog = false },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(28.dp),
        title = { Text("新建歌单") },
        text = { OutlinedTextField(state.newPlaylistNameDraft, actions.playlistName,
            label = { Text("歌单名称") }, singleLine = true, shape = RoundedCornerShape(16.dp),
            modifier = Modifier.testTag("playlistName")) },
        confirmButton = { TextButton(onClick = { actions.createPlaylist(); createDialog = false }) { Text("创建") } },
        dismissButton = { TextButton(onClick = { createDialog = false }) { Text("取消") } })
    if (streamingCreate) StreamingPlaylistNameDialog({ streamingCreate = false }) { name ->
        streamingCreate = false
        actions.streaming.createPlaylist(name, emptyList())
    }
}

/** Whose songs the library lists: every device, this phone, or one computer, whether or not it is reachable. */
@Composable
private fun LibraryDeviceMenu(state: PlayerUiState, select: (String) -> Unit, onConnectComputer: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val current = state.libraryDevices.find { it.id == state.libraryDevice } ?: state.libraryDevices.first()
    Box {
        Row(Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(50)).background(colors.surfaceContainerHigh)
            .clickable(onClickLabel = "选择显示哪台设备的音乐", role = Role.DropdownList) { open = true }
            .padding(start = 14.dp, end = 8.dp).testTag("libraryDevice"), verticalAlignment = Alignment.CenterVertically) {
            Text(current.label, Modifier.widthIn(max = 112.dp), style = MaterialTheme.typography.labelLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(TwilightIcons.ChevronDown, null, Modifier.padding(start = 2.dp).size(18.dp), tint = colors.onSurfaceVariant)
        }
        DropdownMenu(open, { open = false }, shape = RoundedCornerShape(16.dp), containerColor = colors.surfaceContainerHigh) {
            state.libraryDevices.forEach { option ->
                val selected = option.id == current.id
                DropdownMenuItem(text = {
                    Column {
                        Text(option.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (selected) colors.primary else colors.onSurface)
                        if (!option.connected) Text("未连接", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    }
                }, onClick = { open = false; select(option.id) }, modifier = Modifier.testTag("libraryDevice-${option.id}"),
                    leadingIcon = { Icon(when (option.id) {
                        LIBRARY_ALL -> TwilightIcons.Library
                        LIBRARY_PHONE -> TwilightIcons.Phone
                        else -> TwilightIcons.Computer
                    }, null, Modifier.size(20.dp), tint = if (selected) colors.primary else colors.onSurfaceVariant) })
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = colors.outlineVariant.copy(alpha = 0.6f))
            DropdownMenuItem(text = { Text("连接电脑…", style = MaterialTheme.typography.bodyLarge) },
                onClick = { open = false; onConnectComputer() },
                leadingIcon = { Icon(TwilightIcons.Plus, null, Modifier.size(20.dp), tint = colors.onSurfaceVariant) })
        }
    }
}

internal data class UiCollection(val kind: String, val id: String, val title: String,
    val subtitle: String, val ids: List<String>)

internal fun findCollection(state: PlayerUiState, kind: String?, id: String?): UiCollection? = when (kind) {
    "album" -> state.albums.find { it.id == id }?.let { UiCollection(kind, it.id, it.title, it.artist, it.trackIds) }
    "artist" -> state.artists.find { it.id == id }?.let { UiCollection(kind, it.id, it.name, "艺术家", it.trackIds) }
    "playlist" -> state.playlists.find { it.id == id }?.let { UiCollection(kind, it.id, it.name, "你的歌单", it.trackIds) }
    else -> null
}

@Composable
internal fun CollectionDetailScreen(collection: UiCollection?, state: PlayerUiState,
    actions: PlayerActions, onBack: () -> Unit) {
    var confirmDelete by rememberSaveable(collection?.id) { mutableStateOf(false) }
    var query by rememberSaveable(collection?.id) { mutableStateOf("") }
    var artistAlbums by rememberSaveable(collection?.id) { mutableStateOf(false) }
    var openAlbum by rememberSaveable(collection?.id) { mutableStateOf<String?>(null) }
    if (openAlbum != null) {
        androidx.activity.compose.BackHandler { openAlbum = null }
        CollectionDetailScreen(findCollection(state, "album", openAlbum), state, actions, { openAlbum = null })
        return
    }
    // A playlist can hold songs from any device, whichever one the library is showing.
    val tracks = remember(collection, state.libraryTracks, state.allTracks) {
        collectionTracks(collection?.ids.orEmpty(), state.allTracks + state.libraryTracks)
    }
    val artwork = rememberPlayerArtwork(tracks.firstOrNull())
    val sort = collection?.let { state.preferences.collectionSorts[localSortKey(it)] } ?: CollectionSort()
    val visible = remember(tracks, query, sort, collection?.kind) {
        if (collection?.kind == "playlist") browseCollection(tracks, query, sort) else tracks
    }
    val albums = remember(collection, state.albums) {
        val ids = collection?.ids.orEmpty().toHashSet()
        state.albums.filter { album -> album.trackIds.any { it in ids } }
    }
    val profile = state.libraryArtist.takeIf { it.collectionId == collection?.id }
    LaunchedEffect(collection?.id, state.libraryArtist.collectionId, state.libraryDevice) {
        if (collection?.kind == "artist") actions.loadLibraryArtist(collection.id, false)
    }
    val dock = LocalDockInset.current
    // Each album, artist or playlist page takes its own Monet palette from its cover.
    TwilightEchoTheme(seed = artwork.seedColor ?: LocalTwilightAccents.current.seed) {
      AmbientBackground(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().testTag("collection"), contentPadding = PaddingValues(bottom = dock + 8.dp)) {
            if (collection == null) {
                item {
                    Row(Modifier.padding(horizontal = 8.dp)) { GlassIconButton(TwilightIcons.ArrowBack, "返回音乐库", onBack) }
                    EmptyState("合集已不可用", "返回音乐库查看当前内容。", actionLabel = "返回音乐库", onAction = onBack)
                }
                return@LazyColumn
            }
            if (collection.kind == "artist") {
                item {
                    LibraryArtistOverview(collection.title, "${tracks.size} 首歌曲 · ${albums.size} 张专辑", profile,
                        onBack, { actions.loadLibraryArtist(collection.id, true) })
                    ArtistContentTabs(artistAlbums, { artistAlbums = it })
                    if (!artistAlbums) PillButton("播放全部", { tracks.firstOrNull()?.let { actions.playTracks(tracks, it.id) } },
                        Modifier.padding(start = 20.dp, top = 4.dp, bottom = 14.dp), icon = TwilightIcons.Play,
                        enabled = tracks.isNotEmpty() && tracks.size == collection.ids.size)
                }
                if (artistAlbums) {
                    items(albums, key = { it.id }) { album ->
                        CollectionRow(album.title, "${album.trackCount} 首歌曲", tracks.firstOrNull { it.id in album.trackIds },
                            Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) { openAlbum = album.id }
                    }
                    if (albums.isEmpty()) item { EmptyState("暂无专辑", "音乐库中还没有这位歌手的专辑。") }
                    return@LazyColumn
                }
            } else item { CollectionHero(collection, visible, artwork, state, actions, onBack,
                tracks.size == collection.ids.size) { confirmDelete = true } }
            if (collection.kind == "playlist") item {
                PlaylistBrowseControls(query, { query = it }, sort, { selected ->
                    actions.editPreferences { it.copy(collectionSorts = it.collectionSorts + (localSortKey(collection) to selected)) }
                }, visible.size, tracks.size)
            }
            if (tracks.size != collection.ids.size) item {
                EmptyState("部分歌曲尚未载入", "刷新音乐库后再播放这个合集。", actionLabel = "刷新", onAction = actions.refreshLibrary)
            }
            if (collection.ids.isEmpty()) item {
                EmptyState("歌单还是空的", "播放一首喜欢的歌曲，再将它加入这里。", TwilightIcons.Library)
            }
            if (tracks.isNotEmpty() && visible.isEmpty()) item {
                EmptyState("没有找到歌曲", "试试其他歌名、歌手或专辑。", TwilightIcons.Search,
                    actionLabel = "清除搜索", onAction = { query = "" })
            }
            itemsIndexed(visible, key = { index, track -> "$index-${track.id}" }) { index, track ->
                TrackRow(track, state, { actions.playTracks(visible, track.id) }, index = index + 1)
            }
            if (tracks.isNotEmpty()) item {
                SupportingText("${tracks.size} 首歌曲 · ${formatDurationSeconds(tracks.sumOf { it.duration })}",
                    Modifier.padding(horizontal = 20.dp, vertical = 16.dp))
            }
        }
      }
    }
    if (confirmDelete && collection != null) AlertDialog(onDismissRequest = { confirmDelete = false },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(28.dp),
        title = { Text("删除“${collection.title}”？") }, text = { Text("只删除歌单，不会删除本地音乐文件。") },
        confirmButton = { TextButton(onClick = { actions.deletePlaylist(collection.id); confirmDelete = false; onBack() }) {
            Text("删除", color = MaterialTheme.colorScheme.error)
        } }, dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } })
}

/** The cover, title and play buttons on the page's own plain background. */
@Composable
private fun CollectionHero(collection: UiCollection, tracks: List<CoreTrackView>, artwork: TrackArtworkVisual,
    state: PlayerUiState, actions: PlayerActions, onBack: () -> Unit, complete: Boolean, onDelete: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Column(Modifier.fillMaxWidth().padding(bottom = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(TwilightIcons.ArrowBack, "返回音乐库", onBack)
            Spacer(Modifier.weight(1f))
            if (collection.kind == "playlist") GlassIconButton(TwilightIcons.Trash, "删除歌单", onDelete)
        }
        ArtworkImage(artwork, Modifier.padding(top = 4.dp).fillMaxWidth(0.66f).widthIn(max = 280.dp)
            .aspectRatio(1f).shadow(24.dp, shape).clip(shape),
            ContentScale.Crop)
        Text(collection.title, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), textAlign = TextAlign.Center,
            maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 22.dp, bottom = 4.dp))
        SupportingText(listOf(collection.subtitle, "${collection.ids.size} 首歌曲").filter { it.isNotBlank() }.joinToString(" · "))
        Row(Modifier.padding(top = 22.dp, start = 20.dp, end = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton("播放全部", { tracks.firstOrNull()?.let { actions.playTracks(tracks, it.id) } },
                icon = TwilightIcons.Play, enabled = tracks.isNotEmpty() && complete)
            if (tracks.size > 1) PillButton("随机", { tracks.shuffled().let { actions.playTracks(it, it.first().id) } },
                icon = TwilightIcons.Shuffle, tonal = true,
                enabled = complete)
            if (collection.kind == "playlist" && state.currentTrack != null)
                PillButton("加入当前歌曲", { actions.addToPlaylist(collection.id) }, icon = TwilightIcons.Plus, tonal = true)
        }
    }
}

@Composable
internal fun SearchTabScreen(state: PlayerUiState, actions: PlayerActions) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    ScrollToTopOn(state.searchQuery, listState)
    var firstVisit by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(Unit) { if (firstVisit) { focusRequester.requestFocus(); firstVisit = false } }
    val results = remember(state.searchResults, state.activeSearchFilter, state.favoriteTrackIds) { filteredSearchTracks(state) }
    Column(Modifier.fillMaxSize().testTag("search")) {
        PageHeading("搜索", modifier = Modifier.padding(horizontal = 20.dp))
        SearchTabs(SearchSource.entries, state.searchSource, { it.label }, actions.searchSource,
            Modifier.padding(bottom = 12.dp).testTag("searchSources"), tag = { "search-source-$it" })
        TextField(value = state.searchQuery, onValueChange = actions.query, singleLine = true,
            placeholder = { Text(if (state.searchSource == SearchSource.NETEASE) "搜索网易云音乐" else "歌曲、艺术家、专辑") },
            leadingIcon = { Icon(TwilightIcons.Search, null) },
            trailingIcon = { if (state.searchQuery.isNotEmpty()) QuietIconButton(TwilightIcons.Close, "清除搜索", { actions.query("") }) },
            shape = RoundedCornerShape(28.dp), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); actions.submitSearch() }),
            colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.8f),
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                focusedLeadingIconColor = MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).heightIn(min = 56.dp)
                .focusRequester(focusRequester).testTag("searchInput"))
        if (state.searchSource == SearchSource.NETEASE) {
            SearchTabs(NcmSearchCategory.entries, state.ncmSearch.category, { it.label }, actions.searchCategory,
                Modifier.padding(vertical = 14.dp), tag = { "search-category-$it" })
            NcmSearchResults(state, actions.copy(streaming = actions.streaming.copy(
                play = { tracks, track -> keyboard?.hide(); actions.streaming.play(tracks, track) },
                collection = { keyboard?.hide(); actions.streaming.collection(it) })), Modifier.weight(1f))
            return@Column
        }
        PillTabs(SearchFilterCategory.entries, state.activeSearchFilter, { it.label }, actions.selectFilter,
            Modifier.padding(vertical = 14.dp))
        val error = if (state.searchQuery.isBlank()) state.libraryError else state.searchError
        when {
            state.isSearching || (state.isLoadingLibrary && state.searchQuery.isBlank()) -> LoadingContent("正在搜索")
            error != null -> EmptyState("暂时无法搜索", error, TwilightIcons.Search, actionLabel = "重试",
                onAction = if (state.searchQuery.isBlank()) actions.refreshLibrary else actions.retrySearch)
            results.isEmpty() -> EmptyState("没有找到音乐", "试试其他关键词，或更换筛选条件。", TwilightIcons.Search)
            else -> LazyColumn(Modifier.weight(1f).testTag("searchResults"), state = listState,
                contentPadding = PaddingValues(bottom = LocalDockInset.current + 8.dp)) {
                items(results, key = { it.id }) { track -> TrackRow(track, state, {
                    keyboard?.hide(); actions.playTracks(results, track.id)
                }) }
            }
        }
    }
}

@Composable
internal fun TrackRow(track: CoreTrackView, state: PlayerUiState, onClick: () -> Unit, index: Int? = null) {
    val current = state.currentTrack?.id == track.id
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(16.dp))
        .background(if (current) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else Color.Transparent)
        .clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 8.dp).heightIn(min = 52.dp).testTag("track-${track.id}"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (index != null) Box(Modifier.width(26.dp), contentAlignment = Alignment.Center) {
            if (current) PlayingBars(state.isPlaying, MaterialTheme.colorScheme.primary)
            else Text(index.toString(), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        else TrackArt(track, Modifier.size(50.dp).clip(RoundedCornerShape(12.dp)), ContentScale.Crop)
        Column(Modifier.weight(1f)) {
            Text(track.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            SupportingText(track.artist.ifBlank { "未知艺术家" }, Modifier.padding(top = 2.dp), maxLines = 1)
        }
        if (current && state.isPlaying && index == null) Icon(TwilightIcons.SignalWave, "正在播放",
            Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        else if (track.id in state.favoriteTrackIds) Icon(TwilightIcons.HeartFilled, "已收藏",
            Modifier.size(16.dp), tint = TwilightTokens.FavoriteRose)
        Text(formatDurationSeconds(track.duration), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CollectionRow(title: String, subtitle: String, track: CoreTrackView?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    GlassCard(modifier.fillMaxWidth(), RoundedCornerShape(22.dp), onClick = onClick) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TrackArt(track, Modifier.size(68.dp).clip(RoundedCornerShape(16.dp)), ContentScale.Crop)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                SupportingText(subtitle, Modifier.padding(top = 4.dp))
            }
            Icon(TwilightIcons.ArrowRight, null, Modifier.padding(end = 8.dp).size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun LoadingContent(label: String) {
    Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        CircularProgressIndicator(Modifier.size(30.dp), strokeWidth = 2.5.dp, color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
        SupportingText(label)
    }
}
