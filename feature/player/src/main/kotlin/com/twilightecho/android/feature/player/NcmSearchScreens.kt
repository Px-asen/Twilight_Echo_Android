package com.twilightecho.android.feature.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.twilightecho.android.ui.components.*

@Composable
internal fun <T> SearchTabs(options: List<T>, selected: T, label: (T) -> String,
    onSelect: (T) -> Unit, modifier: Modifier = Modifier, tag: (T) -> String) {
    val motionEnabled = rememberMotionEnabled()
    Row(modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            val isSelected = option == selected
            val interaction = remember { MutableInteractionSource() }
            val containerColor by animateColorAsState(
                if (isSelected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
                tween(if (motionEnabled) 180 else 0),
                label = "searchTabBg"
            )
            val textColor by animateColorAsState(
                if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                tween(if (motionEnabled) 180 else 0),
                label = "searchTabInk"
            )
            Text(label(option), style = MaterialTheme.typography.titleMedium, maxLines = 1,
                color = textColor,
                modifier = Modifier.tactilePress(interaction, pressedScale = 0.94f)
                    .clip(RoundedCornerShape(50))
                    .background(containerColor)
                    .selectable(
                        selected = isSelected,
                        interactionSource = interaction,
                        indication = LocalIndication.current,
                        role = Role.Tab
                    ) { onSelect(option) }
                    .testTag(tag(option)).heightIn(min = 48.dp).padding(horizontal = 18.dp, vertical = 12.dp))
        }
    }
}

@Composable
internal fun NcmSearchResults(state: PlayerUiState, actions: PlayerActions, modifier: Modifier = Modifier) {
    val search = state.ncmSearch
    val motionEnabled = rememberMotionEnabled()
    val phase = when {
        state.streaming.access in setOf(StreamingAccess.NOT_INSTALLED, StreamingAccess.DISABLED) -> "access"
        search.category == NcmSearchCategory.PLAYLISTS && state.streaming.access != StreamingAccess.READY -> "login"
        search.query.isBlank() -> "blank"
        search.loading -> "loading"
        search.error != null -> "error"
        search.searched && search.count == 0 -> "empty"
        else -> "results"
    }
    AnimatedContent(
        targetState = search.category to phase,
        modifier = modifier.fillMaxSize(),
        transitionSpec = {
            if (initialState.first != targetState.first) {
                val dir = targetState.first.ordinal.compareTo(initialState.first.ordinal)
                TwilightMotion.directionalTabTransition(dir, motionEnabled)
            } else {
                TwilightMotion.stateFadeTransition(motionEnabled)
            }
        },
        label = "ncmSearchResultsBody"
    ) { (targetCategory, targetPhase) ->
        val listState = rememberLazyListState()
        LaunchedEffect(search.query, targetCategory, search.offset) { listState.scrollToItem(0) }
        LazyColumn(Modifier.fillMaxSize().testTag("ncmSearchResults"), state = listState,
            contentPadding = PaddingValues(bottom = LocalDockInset.current + 16.dp)) {
            when (targetPhase) {
                "access" -> item {
                    EmptyState("在网易云发现音乐", "接入音源，搜索歌曲、专辑、歌手和歌单。", TwilightIcons.Search,
                        actionLabel = if (state.streaming.access == StreamingAccess.DISABLED) "启用网易云" else "接入网易云",
                        onAction = actions.streaming.account)
                }
                "login" -> item {
                    EmptyState("搜索网易云歌单", "登录账号，找到你想听的歌单。", TwilightIcons.Queue,
                        actionLabel = "登录网易云", onAction = actions.streaming.account)
                }
                "blank" -> item {
                    EmptyState("想听什么？", "输入歌名、歌手、专辑或歌单名称。", TwilightIcons.Search)
                }
                "loading" -> item { LoadingContent("正在搜索网易云") }
                "error" -> item {
                    EmptyState("搜索遇到问题", search.error.orEmpty(), TwilightIcons.Search,
                        actionLabel = "重试", onAction = actions.retrySearch)
                }
                "empty" -> item {
                    EmptyState("换个关键词试试", "试试歌名、歌手全名，或切换搜索分类。", TwilightIcons.Search)
                }
                else -> {
                    itemsIndexed(search.tracks, key = { index, track -> "$index-${track.id}" }) { index, track ->
                        val current = state.currentTrack?.id == track.id
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .songRowClickable(
                                track = track,
                                enabled = track.unavailableReason == null,
                                onClick = { actions.streaming.play(search.tracks, track) }
                            )
                            .testTag("ncm-search-track-$index").padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            TrackArt(track, Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)), ContentScale.Crop)
                            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                                Text(track.title, style = MaterialTheme.typography.titleMedium, maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                Text(track.unavailableReason ?: listOf(track.artist, track.album).filter { it.isNotBlank() }.joinToString(" · "),
                                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (current) Icon(TwilightIcons.SignalWave, "正在播放", Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    itemsIndexed(search.collections, key = { index, item -> "$index-${item.kind}-${item.id}" }) { _, item ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .clickable(role = Role.Button) { actions.streaming.collection(item) }
                            .testTag("ncm-search-${item.kind}-${item.id}").padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            StreamingArtwork(item, Modifier.size(64.dp).clip(if (item.kind == "artist") CircleShape else RoundedCornerShape(12.dp)))
                            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                                Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                val subtitle = listOf(item.subtitle.takeIf { it.isNotBlank() },
                                    item.count.takeIf { it > 0 }?.let { "$it 首歌曲" }).filterNotNull().joinToString(" · ")
                                if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Icon(TwilightIcons.ArrowRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (targetPhase == "results" && search.searched && (search.offset > 0 || search.hasNext)) item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 16.dp).testTag("ncmSearchPagination"),
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { actions.searchPage((search.offset - NCM_SEARCH_PAGE_SIZE).coerceAtLeast(0)) },
                        enabled = search.offset > 0) { Text("上一页", style = MaterialTheme.typography.bodyLarge) }
                    Text("第 ${search.offset / NCM_SEARCH_PAGE_SIZE + 1} 页", Modifier.weight(1f),
                        textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = { actions.searchPage(search.offset + NCM_SEARCH_PAGE_SIZE) },
                        enabled = search.hasNext) { Text("下一页", style = MaterialTheme.typography.bodyLarge) }
                }
            }
        }
    }
}
