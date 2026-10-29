package com.twilightecho.android.feature.player

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.twilightecho.android.coreclient.CoreTrackView
import com.twilightecho.android.ui.components.*
import com.twilightecho.android.ui.theme.LocalTwilightAccents
import com.twilightecho.android.ui.theme.TwilightEchoTheme
import com.twilightecho.android.ui.theme.TwilightTokens

@Composable
internal fun StreamingHome(state: StreamingUiState, actions: StreamingActions) {
    val ready = state.access == StreamingAccess.READY
    val open: (StreamingRecommendation) -> Unit = { if (ready) actions.recommendation(it) else actions.account() }
    Column(Modifier.fillMaxWidth().testTag("streamingHome")) {
        SectionLink("发现", actions.library, Modifier.padding(start = 20.dp, end = 16.dp).testTag("streaming-open-library")) {
            StreamingAccountButton(state, actions)
        }
        LazyRow(Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag("home-recommendations"), contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(StreamingRecommendation.entries, key = { it.name }) { kind ->
                val section = state.sections[kind]
                RecommendationCard(kind, section, { open(kind) },
                    section?.tracks?.firstOrNull()?.takeIf { ready }?.let { first -> { actions.play(section.tracks, first) } })
            }
        }
    }
}

/** The account sits where Apple puts the profile: a round badge beside the section title. */
@Composable
private fun StreamingAccountButton(state: StreamingUiState, actions: StreamingActions) {
    val colors = MaterialTheme.colorScheme
    val onClick = if (state.access == StreamingAccess.ERROR) actions.refresh else actions.account
    val enabled = state.access != StreamingAccess.LOADING
    if (state.access == StreamingAccess.READY) {
        Box(Modifier.size(48.dp).clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).testTag("streaming-account")
            .semantics { contentDescription = "网易云账号" }, contentAlignment = Alignment.Center) {
            NcmAvatar(state.nickname, state.profile?.avatarUrl, Modifier.fillMaxSize())
        }
    } else Text(when (state.access) {
        StreamingAccess.ERROR -> "重试"
        StreamingAccess.NOT_INSTALLED -> "接入网易云"
        StreamingAccess.DISABLED -> "启用网易云"
        StreamingAccess.LOADING -> "连接中"
        else -> "登录网易云"
    }, Modifier.clip(RoundedCornerShape(50)).background(colors.surfaceContainer)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).testTag("streaming-account")
        .padding(horizontal = 14.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = if (enabled) colors.primary else colors.onSurfaceVariant)
}

/**
 * One recommendation as a picture card: a mosaic of today's covers under an even dark veil, the icon
 * at the top, the name at the foot. Without covers yet it is a plain grey card.
 */
@Composable
private fun RecommendationCard(kind: StreamingRecommendation, section: StreamingSection?, onOpen: () -> Unit,
    onPlay: (() -> Unit)?) {
    val covers = section?.tracks.orEmpty().take(6)
    val shape = RoundedCornerShape(16.dp)
    val ink = if (covers.isEmpty()) MaterialTheme.colorScheme.onSurface else Color.White
    Box(Modifier.width(264.dp).height(168.dp).clip(shape).background(MaterialTheme.colorScheme.surfaceContainer)
        .clickable(role = Role.Button, onClick = onOpen).testTag("streaming-rec-${kind.name}")) {
        if (covers.isNotEmpty()) {
            Column(Modifier.matchParentSize()) {
                repeat(2) { row ->
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        repeat(3) { column ->
                            TrackArt(covers[(row * 3 + column) % covers.size], Modifier.weight(1f).fillMaxHeight(), ContentScale.Crop)
                        }
                    }
                }
            }
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.5f)))
        }
        Icon(recommendationIcon(kind), null, Modifier.align(Alignment.TopStart).padding(18.dp).size(26.dp), tint = ink)
        if (onPlay != null) Box(Modifier.align(Alignment.TopEnd).padding(14.dp).size(40.dp).clip(CircleShape)
            .background(Color.White).clickable(role = Role.Button, onClick = onPlay), contentAlignment = Alignment.Center) {
            Icon(TwilightIcons.Play, "播放${kind.title}", Modifier.size(20.dp), tint = Color.Black)
        }
        Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
            Text(kind.title, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = ink)
            Text(sectionCaption(kind, section), style = MaterialTheme.typography.bodyMedium,
                color = ink.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun sectionCaption(kind: StreamingRecommendation, section: StreamingSection?) = when {
    section?.loading == true -> "正在准备"
    section?.error != null -> "点击重试"
    else -> kind.caption
}

@Composable
internal fun StreamingLibraryScreen(state: StreamingUiState, actions: StreamingActions, modifier: Modifier = Modifier) {
    if (state.access != StreamingAccess.READY) {
        StreamingAccessPrompt(state, actions, modifier)
        return
    }
    LazyColumn(modifier.fillMaxSize().testTag("streamingLibrary"),
        contentPadding = PaddingValues(bottom = 24.dp + LocalDockInset.current)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                NcmAvatar(state.nickname, state.profile?.avatarUrl, Modifier.padding(end = 14.dp).size(56.dp)
                    .clickable(role = Role.Button, onClick = actions.account))
                Column(Modifier.weight(1f)) {
                    Text(state.nickname.ifBlank { "我的在线音乐" }, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    state.profile?.signature?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                TextButton(onClick = actions.account) { Text("账号") }
                TextButton(onClick = actions.refresh, enabled = !state.libraryLoading) { Text("刷新") }
            }
            if (state.libraryLoading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp))
            state.libraryError?.let {
                EmptyState("音乐库未载入", it, actionLabel = "重试", onAction = actions.refresh)
            }
            val shape = RoundedCornerShape(14.dp)
            Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp).fillMaxWidth().clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .clickable(role = Role.Button) { actions.group(StreamingLibraryGroup.LIKED) }
                .testTag("streaming-group-LIKED").padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(TwilightTokens.FavoriteRose.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center) {
                    Icon(TwilightIcons.HeartFilled, null, Modifier.size(28.dp), tint = TwilightTokens.FavoriteRose)
                }
                Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                    Text("我喜欢的音乐", style = MaterialTheme.typography.titleMedium)
                    state.likedPlaylist?.let { SupportingText("${it.count} 首", Modifier.padding(top = 2.dp)) }
                }
                Icon(TwilightIcons.ArrowRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            SettingsGroup(Modifier.padding(horizontal = 20.dp)) {
                StreamingLibraryGroup.entries.filter { it != StreamingLibraryGroup.LIKED }.forEachIndexed { index, group ->
                    val subtitle = when (group) {
                        StreamingLibraryGroup.CREATED -> if (state.libraryLoaded) "${state.playlists.count { it.owned == true }} 个歌单" else null
                        StreamingLibraryGroup.PLAYLISTS -> if (state.libraryLoaded) "${state.playlists.count { it.owned != true }} 个歌单" else null
                        else -> null
                    }
                    if (index > 0) HorizontalDivider(Modifier.padding(start = 70.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SettingsItem(groupIcon(group), group.title, subtitle,
                        Modifier.testTag("streaming-group-${group.name}"), onClick = { actions.group(group) })
                }
            }
        }
    }
}

@Composable
private fun StreamingAccessPrompt(state: StreamingUiState, actions: StreamingActions, modifier: Modifier) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = LocalDockInset.current)
        .testTag("streamingAccess")) {
        if (state.access == StreamingAccess.LOADING) LoadingContent("正在连接网易云音乐")
        else EmptyState(when (state.access) {
            StreamingAccess.NOT_INSTALLED -> "把网易云带进音乐库"
            StreamingAccess.DISABLED -> "启用网易云音源"
            StreamingAccess.ERROR -> "网易云暂时无法连接"
            else -> "你的在线收藏，都在这里"
        }, state.accountError ?: "登录网易云，查看喜欢的音乐、创建和收藏的歌单、收藏的专辑与歌手。",
            TwilightIcons.SignalWave, actionLabel = when (state.access) {
                StreamingAccess.NOT_INSTALLED -> "接入网易云音乐"
                StreamingAccess.DISABLED -> "管理并启用音源"
                StreamingAccess.ERROR -> "重新连接"
                else -> "登录网易云音乐"
            }, onAction = if (state.access == StreamingAccess.ERROR) actions.refresh else actions.account)
    }
}

/** Detail pages re-theme themselves from their own cover, so each playlist or album carries its own colors. */
@Composable
internal fun StreamingDetailScreen(detail: StreamingDetail, state: PlayerUiState, actions: StreamingActions) {
    val collection = detail.collection?.let { item -> if (item.kind == "artist")
        item.copy(cover = detail.artistProfile?.portrait ?: item.cover) else item }
    val visual = collection?.let { rememberStreamingArtwork(it) }
        ?: detail.tracks.firstOrNull()?.let { rememberPlayerArtwork(it) }
    val seed = visual?.seedColor ?: LocalTwilightAccents.current.seed
    TwilightEchoTheme(seed = seed) {
        AmbientBackground(Modifier.fillMaxSize()) { StreamingDetailContent(detail, state, actions, visual) }
    }
}

@Composable
private fun StreamingDetailContent(detail: StreamingDetail, state: PlayerUiState, actions: StreamingActions, visual: TrackArtworkVisual?) {
    val listState = rememberLazyListState()
    LaunchedEffect(detail.key, detail.offset) { listState.scrollToItem(0) }
    val artist = detail.collection?.kind == "artist"
    val viewTotal = detail.matchingTotal ?: detail.total
    val playlist = detail.collection?.takeIf { it.kind == "playlist" }
    // The liked list is the account's favourites, not a playlist one can delete or leave.
    val removable = playlist?.takeIf { it.owned != null && it.id != state.streaming.likedPlaylist?.id }
    val ownPlaylist = removable?.takeIf { it.owned == true }
    var confirmRemove by rememberSaveable(detail.key) { mutableStateOf(false) }
    var picking by remember { mutableStateOf<List<CoreTrackView>?>(null) }
    Column(Modifier.fillMaxSize().testTag("streamingDetail")) {
        if (detail.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f).testTag("streamingDetailList"), state = listState,
            contentPadding = PaddingValues(bottom = 20.dp + LocalDockInset.current)) {
            item {
                val collectionsPage = detail.artistAlbums || detail.group in setOf(StreamingLibraryGroup.CREATED,
                    StreamingLibraryGroup.PLAYLISTS, StreamingLibraryGroup.ALBUMS, StreamingLibraryGroup.ARTISTS)
                val summary = listOf(detail.subtitle.takeIf { it.isNotBlank() },
                    if (detail.loading) "正在载入" else "${detail.total} ${if (collectionsPage) "项" else "首歌曲"}")
                    .filterNotNull().joinToString(" · ")
                if (artist) {
                    ImmersiveArtistHero(detail.artistProfile?.name?.ifBlank { null } ?: detail.title, detail.subtitle,
                        visual, actions.back, "返回流媒体音乐") {
                        TextButton(onClick = actions.retry, enabled = !detail.loading,
                            modifier = Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.35f))) {
                            Text("刷新", color = Color.White)
                        }
                    }
                    val profile = detail.artistProfile
                    when {
                        profile?.introduction?.isNotBlank() == true -> ArtistBiography(profile.introduction, "网易云音乐")
                        profile?.loading == true -> SupportingText("正在载入歌手简介…", Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
                        profile?.error != null -> Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                            SupportingText("歌手简介未载入", Modifier.weight(1f))
                            TextButton(onClick = actions.retryArtist) { Text("重试") }
                        }
                        else -> SupportingText("暂无歌手简介", Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
                    }
                    ArtistContentTabs(detail.artistAlbums, actions.artistTab)
                    if (!detail.artistAlbums && detail.tracks.isNotEmpty()) {
                        val playable = detail.tracks.firstOrNull { it.unavailableReason == null }
                        PillButton(if (state.operatingMode is com.twilightecho.android.coreclient.CoreOperatingMode.RemoteDesktopCore)
                            "在此手机播放" else "播放全部", { playable?.let { actions.play(detail.tracks, it) } },
                            Modifier.padding(start = 20.dp, top = 4.dp, bottom = 14.dp), icon = TwilightIcons.Play,
                            enabled = !detail.loading && playable != null)
                    }
                } else Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        GlassIconButton(TwilightIcons.ArrowBack, "返回流媒体音乐", actions.back)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = actions.retry, enabled = !detail.loading) {
                            Text(if (detail.recommendation == StreamingRecommendation.FM) "换一批" else "刷新")
                        }
                        if (removable != null) GlassIconButton(TwilightIcons.Trash,
                            if (ownPlaylist != null) "删除歌单" else "取消收藏歌单", { confirmRemove = true })
                    }
                    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        val shape = RoundedCornerShape(20.dp)
                        if (detail.collection != null && visual != null) ArtworkImage(visual,
                            Modifier.padding(top = 4.dp).size(196.dp)
                                .shadow(28.dp, shape).clip(shape), ContentScale.Crop)
                        else if (detail.recommendation != null) Box(Modifier.padding(top = 4.dp).size(120.dp)
                            .clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainer),
                            contentAlignment = Alignment.Center) {
                            Icon(recommendationIcon(detail.recommendation), null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        Text(detail.title, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), textAlign = TextAlign.Center,
                            maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 20.dp))
                        SupportingText(summary, Modifier.padding(top = 6.dp), maxLines = 2)
                        val playable = detail.tracks.firstOrNull { it.unavailableReason == null }
                        if (detail.tracks.isNotEmpty()) {
                            val page = detail.offset > 0 || detail.nextOffset != null
                            PillButton(if (state.operatingMode is com.twilightecho.android.coreclient.CoreOperatingMode.RemoteDesktopCore)
                                if (page) "在此手机播放本页" else "在此手机播放"
                                else if (page) "播放本页" else "播放全部",
                                { playable?.let { actions.play(detail.tracks, it) } }, Modifier.padding(top = 18.dp),
                                icon = TwilightIcons.Play, enabled = !detail.loading && playable != null)
                        }
                    }
                }
            }
            if (detail.isPlaylist) item {
                PlaylistBrowseControls(detail.query, actions.playlistQuery, detail.sort, actions.playlistSort,
                    viewTotal, detail.total, detail.loadingPlaylist)
            }
            detail.error?.let { error -> item { EmptyState("内容未载入", error, actionLabel = "重试", onAction = actions.retry) } }
            items(detail.collections, key = { "${it.kind}:${it.id}" }) { collection ->
                Row(Modifier.fillMaxWidth().clickable(enabled = !detail.loading) { actions.collection(collection) }
                    .testTag("streaming-collection-${collection.kind}-${collection.id}")
                    .padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    StreamingArtwork(collection, Modifier.size(60.dp)
                        .clip(if (collection.kind == "artist") CircleShape else RoundedCornerShape(12.dp)))
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        Text(collection.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        SupportingText(collection.subtitle.ifBlank { "${collection.count} 首歌曲" }, maxLines = 1)
                    }
                    Icon(TwilightIcons.ArrowRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            itemsIndexed(detail.tracks, key = { index, track -> "$index-${track.id}" }) { index, track ->
                StreamingTrackRow(detail.offset + index + 1, track, state.currentTrack?.id == track.id,
                    enabled = !detail.loading && track.unavailableReason == null,
                    modifier = Modifier.testTag("streaming-track-$index"),
                    addToPlaylist = { picking = listOf(track) },
                    removeFromPlaylist = ownPlaylist?.let { owned -> { actions.removeFromPlaylist(owned, track) } }) {
                    actions.play(detail.tracks, track)
                }
            }
            if (!detail.loading && detail.error == null && detail.tracks.isEmpty() && detail.collections.isEmpty()) item {
                if (detail.isPlaylist && detail.query.isNotBlank()) EmptyState("没有找到歌曲", "试试其他歌名、歌手或专辑。",
                    TwilightIcons.Search, actionLabel = "清除搜索", onAction = { actions.playlistQuery("") })
                else EmptyState(if (artist && detail.artistAlbums) "暂无专辑" else "这里还没有音乐",
                    if (artist) "音源暂未提供这些作品。" else "收藏与推荐来自当前网易云账号。可以刷新，或先在网易云添加喜欢的内容。")
            }
            if (!detail.loading && detail.error == null && (detail.offset > 0 || detail.nextOffset != null)) item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { actions.page((detail.offset - 100).coerceAtLeast(0)) }, enabled = detail.offset > 0) { Text("上一页") }
                    Text("${if (detail.tracks.isEmpty()) 0 else detail.offset + 1}–${detail.offset + detail.tracks.size} / $viewTotal",
                        Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { detail.nextOffset?.let(actions.page) }, enabled = detail.nextOffset != null) { Text("下一页") }
                }
            }
        }
    }
    if (confirmRemove && removable != null) AlertDialog(onDismissRequest = { confirmRemove = false },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(28.dp),
        title = { Text(if (ownPlaylist != null) "删除“${removable.title}”？" else "取消收藏“${removable.title}”？") },
        text = { Text(if (ownPlaylist != null) "歌单会从你的网易云账号中删除。" else "歌单会从“收藏的歌单”中移除。") },
        confirmButton = { TextButton(onClick = { confirmRemove = false; actions.deletePlaylist(removable) }) {
            Text(if (ownPlaylist != null) "删除" else "取消收藏", color = MaterialTheme.colorScheme.error)
        } }, dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("保留") } })
    picking?.let { tracks -> StreamingPlaylistPicker(state.streaming, tracks, actions) { picking = null } }
}

@Composable
private fun StreamingTrackRow(number: Int, track: CoreTrackView, current: Boolean, enabled: Boolean, modifier: Modifier,
    addToPlaylist: () -> Unit, removeFromPlaylist: (() -> Unit)?, onClick: () -> Unit) {
    val alpha = if (track.unavailableReason == null) 1f else 0.45f
    var menu by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)
        .then(if (current) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)) else Modifier)
        .padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(32.dp)) {
            if (current) Icon(TwilightIcons.SignalWave, "正在聆听", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            else Text(number.toString().padStart(2, '0'), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
        }
        TrackArt(track, Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)), ContentScale.Crop)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(track.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
            SupportingText(track.unavailableReason ?: listOf(track.artist, track.album).filter { it.isNotBlank() }
                .joinToString(" · "), Modifier.padding(top = 3.dp), maxLines = 1)
        }
        if (track.duration > 0) Text(formatDurationSeconds(track.duration), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
        if (track.ncmSongId != null) Box {
            QuietIconButton(TwilightIcons.More, "更多操作：${track.title}", { menu = true },
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            DropdownMenu(menu, { menu = false }, shape = RoundedCornerShape(16.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
                DropdownMenuItem(text = { Text("加入歌单", style = MaterialTheme.typography.bodyLarge) },
                    onClick = { menu = false; addToPlaylist() },
                    leadingIcon = { Icon(TwilightIcons.Plus, null, Modifier.size(20.dp)) })
                if (removeFromPlaylist != null) DropdownMenuItem(
                    text = { Text("从歌单移除", style = MaterialTheme.typography.bodyLarge) },
                    onClick = { menu = false; removeFromPlaylist() },
                    leadingIcon = { Icon(TwilightIcons.Trash, null, Modifier.size(20.dp)) })
            }
        }
    }
}

/** Names a new playlist in the signed-in account. */
@Composable
internal fun StreamingPlaylistNameDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(28.dp),
        title = { Text("新建歌单") },
        text = { OutlinedTextField(name, { name = it.take(40) }, label = { Text("歌单名称") }, singleLine = true,
            shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().testTag("streamingPlaylistName")) },
        confirmButton = { TextButton(onClick = { onCreate(name.trim()) }, enabled = name.isNotBlank()) { Text("创建") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

/** Puts songs into one of the account's own playlists, or into a new one named here. */
@Composable
internal fun StreamingPlaylistPicker(state: StreamingUiState, tracks: List<CoreTrackView>, actions: StreamingActions,
    onDismiss: () -> Unit) {
    var naming by rememberSaveable { mutableStateOf(false) }
    if (naming) {
        StreamingPlaylistNameDialog(onDismiss) { name -> onDismiss(); actions.createPlaylist(name, tracks) }
        return
    }
    val own = state.playlists.filter { it.owned == true }
    AlertDialog(onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(28.dp),
        modifier = Modifier.testTag("streamingPlaylistPicker"),
        title = { Text("加入歌单") },
        text = {
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                item {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { naming = true }
                        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                            contentAlignment = Alignment.Center) {
                            Icon(TwilightIcons.Plus, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        Text("新建歌单", Modifier.padding(start = 14.dp), style = MaterialTheme.typography.titleMedium)
                    }
                }
                if (own.isEmpty() && state.libraryLoading) item { LoadingContent("正在载入歌单") }
                items(own, key = { it.id }) { playlist ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.Button) { onDismiss(); actions.addToPlaylist(playlist, tracks) }
                        .padding(vertical = 8.dp).testTag("pick-playlist-${playlist.id}"), verticalAlignment = Alignment.CenterVertically) {
                        StreamingArtwork(playlist, Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)))
                        Column(Modifier.weight(1f).padding(start = 14.dp)) {
                            Text(playlist.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            SupportingText("${playlist.count} 首", maxLines = 1)
                        }
                    }
                }
            }
        },
        confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
internal fun rememberStreamingArtwork(item: StreamingCollection, size: Int = 1024, extractColors: Boolean = true): TrackArtworkVisual {
    item.cover?.let { return rememberProviderArtwork(it, size, extractColors) }
    return rememberTrackArtwork("streaming-${item.kind}-${item.id}", item.title, item.subtitle,
        null, null, previewOnly = true, maxSize = size, extractColors = extractColors)
}

@Composable
internal fun StreamingArtwork(item: StreamingCollection, modifier: Modifier, size: Int = 256) {
    val artwork = rememberStreamingArtwork(item, size, extractColors = false)
    ArtworkImage(artwork, modifier.then(if (artwork.isRealEmbeddedArt) Modifier.testTag("loadedStreamingArtwork") else Modifier), ContentScale.Crop)
}

private fun recommendationIcon(kind: StreamingRecommendation): ImageVector = when (kind) {
    StreamingRecommendation.DAILY -> TwilightIcons.Sparkles
    StreamingRecommendation.FM -> TwilightIcons.SignalWave
    StreamingRecommendation.RADAR -> TwilightIcons.RadarScan
}
private fun groupIcon(group: StreamingLibraryGroup): ImageVector = when (group) {
    StreamingLibraryGroup.LIKED -> TwilightIcons.HeartFilled
    StreamingLibraryGroup.CREATED -> TwilightIcons.Queue
    StreamingLibraryGroup.PLAYLISTS -> TwilightIcons.Library
    StreamingLibraryGroup.ALBUMS -> TwilightIcons.AlbumDisc
    StreamingLibraryGroup.ARTISTS -> TwilightIcons.ArtistMic
    StreamingLibraryGroup.RECENT -> TwilightIcons.RepeatSequence
    StreamingLibraryGroup.RANKING -> TwilightIcons.Equalizer
}
