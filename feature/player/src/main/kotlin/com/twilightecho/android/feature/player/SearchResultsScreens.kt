package com.twilightecho.android.feature.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.twilightecho.android.ui.components.*

@Composable
internal fun LocalSearchResults(state: PlayerUiState, actions: PlayerActions, onCollection: (UiCollection) -> Unit,
    modifier: Modifier = Modifier) {
    val tracks = remember(state.searchResults, state.activeSearchFilter, state.favoriteTrackIds) { filteredSearchTracks(state) }
    val favorites = remember(state.favoriteTrackIds) { state.favoriteTrackIds.toHashSet() }
    val playTracks = actions.playTracks
    val category = state.librarySearchCategory
    // Song results are already prepared by the presenter; do not group/sort the entire library here.
    val catalog = if (category == SearchCategory.SONGS) null else {
        val allCatalog = remember(state.allTracks) { searchLibraryCatalog(state.allTracks, "") }
        remember(allCatalog, state.searchQuery) { filterLibrarySearchCatalog(allCatalog, state.searchQuery) }
    }
    val byId = if (category == SearchCategory.ALBUMS) remember(state.allTracks) { state.allTracks.associateBy { it.id } }
        else emptyMap()
    val listState = rememberLazyListState()
    ScrollToTopOn("${state.searchQuery}:$category:${state.activeSearchFilter}", listState)
    val error = if (state.searchQuery.isBlank()) state.libraryError else state.searchError
    val empty = when (category) {
        SearchCategory.SONGS -> tracks.isEmpty()
        SearchCategory.ARTISTS -> catalog!!.artists.isEmpty()
        SearchCategory.ALBUMS -> catalog!!.albums.isEmpty()
    }
    when {
        state.isSearching || state.isLoadingLibrary && state.allTracks.isEmpty() -> LoadingContent("正在搜索")
        error != null -> EmptyState("暂时无法搜索", error, TwilightIcons.Search, actionLabel = "重试",
            onAction = if (state.searchQuery.isBlank()) actions.refreshLibrary else actions.retrySearch)
        empty -> EmptyState("没有找到${category.label}", "试试其他关键词，或切换搜索分类。", TwilightIcons.Search)
        else -> LazyColumn(modifier.fillMaxSize().testTag("searchResults"), state = listState,
            contentPadding = PaddingValues(bottom = LocalDockInset.current + 8.dp)) {
            when (category) {
                SearchCategory.SONGS -> items(tracks, key = { it.id }, contentType = { "track" }) { track ->
                    TrackRow(track, state.currentTrack?.id == track.id, state.isPlaying, track.id in favorites,
                        { playTracks(tracks, track.id) })
                }
                SearchCategory.ARTISTS -> items(catalog!!.artists, key = { it.id }, contentType = { "artist" }) { artist ->
                    SearchCollectionRow(artist.name, "${artist.trackCount} 首歌曲 · ${artist.albumCount} 张专辑",
                        Modifier.testTag("local-search-artist-${artist.id}"), artwork = {
                            ArtworkImage(rememberArtistPortrait(artist.id, artist.name, state.libraryArtistProfiles[artist.id]?.metadata,
                                size = 256, extractColors = false), Modifier.size(60.dp).clip(CircleShape))
                        }) { onCollection(UiCollection("artist", artist.id, artist.name, "歌手", artist.trackIds)) }
                }
                SearchCategory.ALBUMS -> items(catalog!!.albums, key = { it.id }, contentType = { "album" }) { album ->
                    SearchCollectionRow(album.title, listOf(album.artist, "${album.trackCount} 首歌曲")
                        .filter { it.isNotBlank() }.joinToString(" · "),
                        Modifier.testTag("local-search-album-${album.id}"), artwork = {
                            TrackArt(album.trackIds.firstNotNullOfOrNull { byId[it] }, Modifier.size(60.dp).clip(RoundedCornerShape(12.dp)))
                        }) { onCollection(UiCollection("album", album.id, album.title, album.artist, album.trackIds)) }
                }
            }
        }
    }
}

@Composable
internal fun SearchCollectionRow(title: String, subtitle: String, modifier: Modifier = Modifier,
    artwork: @Composable () -> Unit, onClick: () -> Unit) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        artwork()
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(TwilightIcons.ArrowRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun ProviderSearchResults(state: PlayerUiState, actions: PlayerActions, modifier: Modifier = Modifier) {
    val search = state.providerSearch
    val listState = rememberLazyListState()
    ScrollToTopOn("${search.provider?.pluginId}:${search.query}:${search.category}:${search.offset}", listState)
    LazyColumn(modifier.fillMaxSize().testTag("providerSearchResults"), state = listState,
        contentPadding = PaddingValues(bottom = LocalDockInset.current + 12.dp)) {
        when {
            search.provider == null -> item { EmptyState("音源已不可用", "请重新启用音源，或选择其他音源。",
                TwilightIcons.SignalWave, actionLabel = "管理音源", onAction = actions.openPlugins) }
            !search.supported -> item { EmptyState("暂不支持${search.category.label}搜索",
                "试试其他分类，或切换音源。", TwilightIcons.Search) }
            search.query.isBlank() -> item { EmptyState("想听什么？", "输入关键词，搜索${search.category.label}。", TwilightIcons.Search) }
            search.loading -> item { LoadingContent("正在搜索${search.provider.name}") }
            search.error != null -> item { EmptyState("搜索遇到问题", search.error, TwilightIcons.Search,
                actionLabel = "重试", onAction = actions.retrySearch)
                TextButton(onClick = actions.providerSearchActions.account, modifier = Modifier.padding(horizontal = 20.dp)) {
                    Text("音源登录与设置")
                }
            }
            search.searched && search.tracks.isEmpty() && search.collections.isEmpty() -> item {
                EmptyState("没有找到${search.category.label}", "试试其他关键词，或切换搜索分类。", TwilightIcons.Search)
            }
            else -> {
                itemsIndexed(search.tracks, key = { index, track -> "$index-${track.id}" }) { index, track ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .songRowClickable(track, enabled = track.unavailableReason == null,
                            onClick = { actions.providerSearchActions.play(search.tracks, track) })
                        .testTag("provider-search-track-$index").padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        TrackArt(track, Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)))
                        Column(Modifier.weight(1f).padding(start = 14.dp)) {
                            Text(track.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                color = if (state.currentTrack?.id == track.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            Text(track.unavailableReason ?: listOf(track.artist, track.album).filter { it.isNotBlank() }.joinToString(" · "),
                                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                items(search.collections, key = { "${it.kind}:${it.id}" }) { item ->
                    SearchCollectionRow(item.title, listOf(item.subtitle, item.count.takeIf { it > 0 }?.let { "$it 首歌曲" }.orEmpty())
                        .filter { it.isNotBlank() }.joinToString(" · "), Modifier.testTag("provider-search-${item.kind}-${item.id}"),
                        artwork = { StreamingArtwork(item, Modifier.size(60.dp)
                            .clip(if (item.kind == "artist") CircleShape else RoundedCornerShape(12.dp))) }) {
                        actions.providerSearchActions.collection(item)
                    }
                }
            }
        }
        if (search.searched && !search.loading && search.error == null && (search.offset > 0 || search.nextOffset != null)) item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { actions.providerSearchPage((search.offset - search.pageSize).coerceAtLeast(0)) },
                    enabled = search.offset > 0) { Text("上一页") }
                val count = search.tracks.size + search.collections.size
                Text(if (count > 0) "${search.offset + 1}–${search.offset + count} / ${search.total}"
                    else "第 ${search.offset / search.pageSize.coerceAtLeast(1) + 1} 页",
                    Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { search.nextOffset?.let(actions.providerSearchPage) },
                    enabled = search.nextOffset != null) { Text("下一页") }
            }
        }
    }
}
