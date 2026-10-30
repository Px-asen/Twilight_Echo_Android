package com.twilightecho.android.feature.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.twilightecho.android.coreclient.CorePlaylistView
import com.twilightecho.android.coreclient.CoreQueueEntryView
import com.twilightecho.android.coreclient.CoreTrackView
import com.twilightecho.android.coreclient.CueRange
import com.twilightecho.android.ui.components.*
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Context passed when a user long-presses a song row anywhere in the app. */
internal data class SongMenuRequest(
    val track: CoreTrackView,
    val localPlaylistId: String? = null,
    val localPlaylistName: String? = null,
    val onRemoveFromStreamingPlaylist: (() -> Unit)? = null,
    val queueEntry: CoreQueueEntryView? = null
)

/** Ambient callback provided at the root surface so any track list can open the song action sheet. */
internal val LocalSongMenuOpener = compositionLocalOf<(SongMenuRequest) -> Unit> { {} }

/** Modifier helper attaching click, long-press context menu with haptics, and an accessibility custom action. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.songRowClickable(
    track: CoreTrackView,
    enabled: Boolean = true,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
): Modifier {
    val openSongMenu = LocalSongMenuOpener.current
    val haptics = LocalHapticFeedback.current
    val triggerMenu = onLongClick ?: { openSongMenu(SongMenuRequest(track = track)) }
    return this
        .combinedClickable(
            enabled = true,
            role = Role.Button,
            onClick = { if (enabled) onClick() },
            onLongClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                triggerMenu()
            }
        )
        .semantics {
            customActions = listOf(
                CustomAccessibilityAction("更多歌曲操作：${track.title}") {
                    triggerMenu()
                    true
                }
            )
        }
}

private enum class PlaylistDestinationKind(val label: String) {
    LOCAL("本机歌单"),
    NETEASE("网易云歌单")
}

/**
 * Root host for the long-press song context sheet, playlist picker, delete confirmation dialog,
 * and on-demand track metadata/tag inspector sheet.
 */
@Composable
internal fun SongActionOverlays(
    activeRequest: SongMenuRequest?,
    onDismissRequest: () -> Unit,
    state: PlayerUiState,
    actions: PlayerActions
) {
    var playlistPickerTrack by remember { mutableStateOf<CoreTrackView?>(null) }
    var confirmDeleteTrack by remember { mutableStateOf<CoreTrackView?>(null) }

    if (activeRequest != null) {
        SongContextBottomSheet(
            request = activeRequest,
            state = state,
            onDismiss = onDismissRequest,
            onPlayNext = {
                val target = activeRequest.track
                onDismissRequest()
                actions.playNext(target)
            },
            onAddToPlaylist = {
                val target = activeRequest.track
                val isPhone = target.id in state.phoneTrackIds
                val isNcm = isNetEaseTrack(target)
                onDismissRequest()
                if (!isPhone && isNcm && state.streaming.access != StreamingAccess.READY) {
                    actions.streaming.account()
                } else {
                    playlistPickerTrack = target
                }
            },
            onInspectTrack = {
                val target = activeRequest.track
                onDismissRequest()
                actions.inspectTrack(target)
            },
            onRemoveFromPlaylist = activeRequest.let { req ->
                when {
                    req.localPlaylistId != null -> {
                        {
                            val pid = req.localPlaylistId
                            val target = req.track
                            onDismissRequest()
                            actions.removeTrackFromLocalPlaylist(pid, target)
                        }
                    }
                    req.onRemoveFromStreamingPlaylist != null -> {
                        {
                            val callback = req.onRemoveFromStreamingPlaylist
                            onDismissRequest()
                            callback()
                        }
                    }
                    else -> null
                }
            },
            onRemoveFromQueue = activeRequest.queueEntry?.let { entry ->
                {
                    onDismissRequest()
                    actions.removeQueueEntry(entry)
                }
            },
            onDeleteLocalTrack = if (activeRequest.track.id in state.phoneTrackIds) {
                {
                    val target = activeRequest.track
                    onDismissRequest()
                    confirmDeleteTrack = target
                }
            } else null
        )
    }

    playlistPickerTrack?.let { track ->
        SongPlaylistPickerHost(
            track = track,
            state = state,
            actions = actions,
            onDismiss = { playlistPickerTrack = null }
        )
    }

    confirmDeleteTrack?.let { track ->
        AlertDialog(
            onDismissRequest = { confirmDeleteTrack = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.testTag("deleteTrackDialog"),
            title = { Text("从音乐库移除歌曲？") },
            text = {
                Text(
                    "将从本机音乐库、歌单和当前播放队列中移除「${track.title}」，不会删除手机存储中的音频文件。\n\n如需找回，可在「设置 → 本地音乐」中恢复或重置音乐库。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = track
                        confirmDeleteTrack = null
                        actions.deleteLocalTrack(target)
                    },
                    modifier = Modifier.testTag("confirmDeleteTrack")
                ) {
                    Text("移除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteTrack = null }) {
                    Text("取消")
                }
            }
        )
    }

    state.inspectedTrack?.let { inspection ->
        TrackInfoBottomSheet(
            inspection = inspection,
            onDismiss = actions.dismissTrackInspection
        )
    }
}

private fun isNetEaseTrack(track: CoreTrackView): Boolean =
    !track.ncmSongId.isNullOrBlank() || track.source?.startsWith("provider://ncm/") == true

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SongContextBottomSheet(
    request: SongMenuRequest,
    state: PlayerUiState,
    onDismiss: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onInspectTrack: () -> Unit,
    onRemoveFromPlaylist: (() -> Unit)?,
    onRemoveFromQueue: (() -> Unit)?,
    onDeleteLocalTrack: (() -> Unit)?
) {
    val track = request.track
    val colors = MaterialTheme.colorScheme
    val isPhoneTrack = track.id in state.phoneTrackIds
    val isNcmTrack = isNetEaseTrack(track)
    val canAddPlaylist = isPhoneTrack || isNcmTrack
    val badge = remember(track) { formatTrackQuickBadge(track) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surfaceContainer.copy(alpha = 1f)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
                .testTag("songContextSheet")
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                TrackArt(track, Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)), ContentScale.Crop)
                Column(Modifier.weight(1f)) {
                    Text(
                        track.title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = colors.onSurface
                    )
                    val subtitle = listOf(
                        track.artist.ifBlank { "未知艺术家" },
                        track.album.takeIf { it.isNotBlank() }
                    ).filterNotNull().joinToString(" · ")
                    SupportingText(subtitle, Modifier.padding(top = 2.dp), maxLines = 1)
                    if (badge != null) {
                        Text(
                            badge,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.primary,
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(colors.primary.copy(alpha = 0.12f))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            HorizontalDivider(
                Modifier.padding(horizontal = 22.dp, vertical = 10.dp),
                color = colors.outlineVariant.copy(alpha = 0.5f)
            )

            SongActionSheetItem(
                icon = TwilightIcons.PlayNext,
                title = "下一首播放",
                subtitle = when {
                    track.unavailableReason != null -> track.unavailableReason
                    state.queueTotal > 0 -> "插入到当前播放曲目之后"
                    else -> "立即加入队列并开始播放"
                },
                enabled = track.unavailableReason == null,
                testTag = "songAction-playNext",
                onClick = onPlayNext
            )

            SongActionSheetItem(
                icon = TwilightIcons.PlaylistAdd,
                title = "添加到歌单",
                subtitle = when {
                    isPhoneTrack && isNcmTrack -> "加入本机歌单或网易云歌单"
                    isPhoneTrack -> "加入本机歌单或新建歌单"
                    isNcmTrack && state.streaming.access == StreamingAccess.READY -> "加入网易云歌单或新建歌单"
                    isNcmTrack -> "登录网易云账号后加入歌单"
                    else -> "电脑曲库歌曲请在电脑端管理歌单"
                },
                enabled = canAddPlaylist,
                testTag = "songAction-addToPlaylist",
                onClick = onAddToPlaylist
            )

            SongActionSheetItem(
                icon = TwilightIcons.Info,
                title = "查看信息/标签",
                subtitle = "曲目标签、音频规格与文件来源",
                testTag = "songAction-viewInfo",
                onClick = onInspectTrack
            )

            if (onRemoveFromPlaylist != null) {
                val removeLabel = if (!request.localPlaylistName.isNullOrBlank()) {
                    "从「${request.localPlaylistName}」移除"
                } else {
                    "从歌单移除"
                }
                SongActionSheetItem(
                    icon = TwilightIcons.Close,
                    title = removeLabel,
                    subtitle = "仅从当前歌单移出",
                    testTag = "songAction-removeFromPlaylist",
                    onClick = onRemoveFromPlaylist
                )
            }

            if (onRemoveFromQueue != null) {
                SongActionSheetItem(
                    icon = TwilightIcons.Close,
                    title = "从播放队列移除",
                    subtitle = "移出当前待播条目",
                    testTag = "songAction-removeFromQueue",
                    onClick = onRemoveFromQueue
                )
            }

            if (onDeleteLocalTrack != null) {
                HorizontalDivider(
                    Modifier.padding(horizontal = 22.dp, vertical = 6.dp),
                    color = colors.outlineVariant.copy(alpha = 0.35f)
                )
                SongActionSheetItem(
                    icon = TwilightIcons.Trash,
                    title = "删除",
                    subtitle = "从本机音乐库移除（不删除音频文件）",
                    tint = colors.error,
                    testTag = "songAction-delete",
                    onClick = onDeleteLocalTrack
                )
            }
        }
    }
}

@Composable
private fun SongActionSheetItem(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    testTag: String,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val contentAlpha = if (enabled) 1f else 0.42f
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = (if (tint == colors.error) colors.error else colors.primary).copy(alpha = contentAlpha)
        )
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = tint.copy(alpha = contentAlpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant.copy(alpha = contentAlpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun SongPlaylistPickerHost(
    track: CoreTrackView,
    state: PlayerUiState,
    actions: PlayerActions,
    onDismiss: () -> Unit
) {
    val canLocal = track.id in state.phoneTrackIds
    val canNetease = isNetEaseTrack(track) && state.streaming.access == StreamingAccess.READY
    var destination by rememberSaveable(track.id) {
        mutableStateOf(if (canLocal) PlaylistDestinationKind.LOCAL else PlaylistDestinationKind.NETEASE)
    }
    var naming by rememberSaveable(track.id) { mutableStateOf(false) }
    var draftName by rememberSaveable(track.id) { mutableStateOf("") }
    val motionEnabled = rememberMotionEnabled()
    val colors = MaterialTheme.colorScheme
    val ownStreaming = state.streaming.playlists.filter { it.owned == true }

    if (!canLocal && canNetease) {
        StreamingPlaylistPicker(state.streaming, listOf(track), actions.streaming, onDismiss)
        return
    }

    val dialogModifier = when {
        naming -> Modifier
        destination == PlaylistDestinationKind.NETEASE -> Modifier.testTag("streamingPlaylistPicker")
        else -> Modifier.testTag("localPlaylistPicker")
    }

    AlertDialog(
        onDismissRequest = {
            if (naming && destination == PlaylistDestinationKind.LOCAL) naming = false else onDismiss()
        },
        containerColor = colors.surfaceContainerHigh,
        shape = RoundedCornerShape(28.dp),
        modifier = dialogModifier,
        title = {
            AnimatedContent(
                targetState = naming to destination,
                transitionSpec = { TwilightMotion.stateFadeTransition(motionEnabled) },
                label = "songPlaylistPickerTitle"
            ) { (isNaming, targetDest) ->
                Text(
                    when {
                        isNaming && targetDest == PlaylistDestinationKind.LOCAL -> "新建本机歌单"
                        isNaming -> "新建歌单"
                        targetDest == PlaylistDestinationKind.NETEASE -> "加入歌单"
                        else -> "添加到歌单"
                    }
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AnimatedVisibility(
                    visible = canLocal && canNetease && !naming,
                    enter = TwilightMotion.sectionExpand(motionEnabled),
                    exit = TwilightMotion.sectionCollapse(motionEnabled)
                ) {
                    PillTabs(
                        PlaylistDestinationKind.entries,
                        destination,
                        { it.label },
                        { destination = it }
                    )
                }
                AnimatedContent(
                    targetState = naming to destination,
                    transitionSpec = {
                        if (initialState.first != targetState.first) {
                            TwilightMotion.hierarchicalTransition(
                                forward = targetState.first,
                                motionEnabled = motionEnabled
                            )
                        } else {
                            val dir = targetState.second.ordinal.compareTo(initialState.second.ordinal)
                            TwilightMotion.directionalTabTransition(dir, motionEnabled)
                        }
                    },
                    label = "songPlaylistPickerBody"
                ) { (isNaming, targetDest) ->
                    if (isNaming) {
                        OutlinedTextField(
                            value = draftName,
                            onValueChange = { draftName = it.take(40) },
                            label = { Text("歌单名称") },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth().testTag(
                                if (targetDest == PlaylistDestinationKind.NETEASE) "streamingPlaylistName"
                                else "localPlaylistNameInput"
                            )
                        )
                    } else if (targetDest == PlaylistDestinationKind.NETEASE) {
                        LazyColumn(Modifier.heightIn(max = 360.dp)) {
                            item {
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                        .clickable(role = Role.Button) { draftName = ""; naming = true }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        Modifier.size(48.dp).clip(RoundedCornerShape(10.dp))
                                            .background(colors.surfaceContainerHighest),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(TwilightIcons.Plus, null, Modifier.size(24.dp), tint = colors.primary)
                                    }
                                    Text("新建歌单", Modifier.padding(start = 14.dp), style = MaterialTheme.typography.titleMedium)
                                }
                            }
                            if (ownStreaming.isEmpty() && state.streaming.libraryLoading) {
                                item { LoadingContent("正在载入歌单") }
                            }
                            items(ownStreaming, key = { it.id }) { playlist ->
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                        .clickable(role = Role.Button) {
                                            onDismiss()
                                            actions.streaming.addToPlaylist(playlist, listOf(track))
                                        }
                                        .padding(vertical = 8.dp).testTag("pick-playlist-${playlist.id}"),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    StreamingArtwork(playlist, Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)))
                                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                        Text(playlist.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        SupportingText("${playlist.count} 首", maxLines = 1)
                                    }
                                }
                            }
                        }
                    } else {
                        LazyColumn(Modifier.heightIn(max = 360.dp)) {
                            item {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable(role = Role.Button) { draftName = ""; naming = true }
                                        .padding(vertical = 8.dp, horizontal = 4.dp)
                                        .testTag("createNewLocalPlaylistRow"),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        Modifier
                                            .size(46.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(colors.surfaceContainerHighest),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(TwilightIcons.Plus, null, Modifier.size(22.dp), tint = colors.primary)
                                    }
                                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                        Text("新建歌单", style = MaterialTheme.typography.titleMedium)
                                        SupportingText("创建歌单并加入「${track.title}」", maxLines = 1)
                                    }
                                }
                            }
                            if (state.playlists.isEmpty()) {
                                item {
                                    SupportingText(
                                        "还没有本机歌单，点击上方「新建歌单」即可创建。",
                                        Modifier.padding(vertical = 16.dp, horizontal = 4.dp)
                                    )
                                }
                            }
                            items(state.playlists, key = { it.id }) { playlist ->
                                val alreadyIn = track.id in playlist.trackIds
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable(enabled = !alreadyIn, role = Role.Button) {
                                            onDismiss()
                                            actions.addTrackToLocalPlaylist(playlist.id, playlist.name, track)
                                        }
                                        .padding(vertical = 8.dp, horizontal = 4.dp)
                                        .testTag("pick-local-playlist-${playlist.id}"),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        Modifier
                                            .size(46.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(colors.primary.copy(alpha = if (alreadyIn) 0.08f else 0.14f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            TwilightIcons.Queue,
                                            null,
                                            Modifier.size(22.dp),
                                            tint = colors.primary.copy(alpha = if (alreadyIn) 0.45f else 1f)
                                        )
                                    }
                                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                        Text(
                                            playlist.name,
                                            style = MaterialTheme.typography.titleMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = colors.onSurface.copy(alpha = if (alreadyIn) 0.5f else 1f)
                                        )
                                        SupportingText(
                                            if (alreadyIn) "已在歌单中 · ${playlist.trackCount} 首" else "${playlist.trackCount} 首歌曲",
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (naming) {
                if (destination == PlaylistDestinationKind.NETEASE) {
                    TextButton(
                        onClick = {
                            onDismiss()
                            actions.streaming.createPlaylist(draftName.trim(), listOf(track))
                        },
                        enabled = draftName.isNotBlank()
                    ) { Text("创建") }
                } else {
                    TextButton(
                        onClick = {
                            onDismiss()
                            actions.createLocalPlaylistWithTracks(draftName.trim(), listOf(track))
                        },
                        enabled = draftName.isNotBlank(),
                        modifier = Modifier.testTag("confirmCreateLocalPlaylist")
                    ) { Text("创建并加入") }
                }
            }
        },
        dismissButton = {
            if (naming && destination == PlaylistDestinationKind.LOCAL) {
                TextButton(onClick = { naming = false }) { Text("返回") }
            } else {
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrackInfoBottomSheet(
    inspection: TrackInspectionUiState,
    onDismiss: () -> Unit
) {
    val track = inspection.track
    val details = inspection.details
    val colors = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    val motionEnabled = rememberMotionEnabled()
    var copiedLabel by remember(track.id) { mutableStateOf<String?>(null) }
    val sections = remember(inspection) { buildTrackMetadataSections(inspection) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surfaceContainer.copy(alpha = 1f)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.84f)
                .navigationBarsPadding()
                .testTag("trackInfoSheet")
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 22.dp, end = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("歌曲信息与标签", style = MaterialTheme.typography.headlineSmall)
                    AnimatedContent(
                        targetState = copiedLabel,
                        transitionSpec = { TwilightMotion.stateFadeTransition(motionEnabled) },
                        label = "copiedHint"
                    ) { label ->
                        SupportingText(
                            label?.let { "已复制「$it」到剪贴板" } ?: "点击任意条目可复制内容",
                            Modifier.padding(top = 2.dp)
                        )
                    }
                }
                QuietIconButton(TwilightIcons.Close, "关闭歌曲信息", onDismiss, tint = colors.onSurfaceVariant)
            }

            AnimatedVisibility(
                visible = inspection.loading,
                enter = TwilightMotion.sectionExpand(motionEnabled),
                exit = TwilightMotion.sectionCollapse(motionEnabled)
            ) {
                LinearProgressIndicator(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp),
                    color = colors.primary,
                    trackColor = colors.onSurface.copy(alpha = 0.08f)
                )
            }

            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    GlassCard(Modifier.fillMaxWidth(), RoundedCornerShape(20.dp)) {
                        Row(
                            Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            TrackArt(track, Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)), ContentScale.Crop)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    details?.title ?: track.title,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                SupportingText(
                                    (details?.artist ?: track.artist).ifBlank { "未知艺术家" },
                                    Modifier.padding(top = 2.dp),
                                    maxLines = 1
                                )
                                Text(
                                    inspection.sourceLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.primary,
                                    modifier = Modifier
                                        .padding(top = 6.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(colors.primary.copy(alpha = 0.12f))
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                }

                items(sections, key = { it.title }) { section ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            section.title,
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.primary,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                        GlassCard(Modifier.fillMaxWidth(), RoundedCornerShape(20.dp)) {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                                section.fields.forEachIndexed { idx, field ->
                                    if (idx > 0) {
                                        HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.45f))
                                    }
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable(role = Role.Button) {
                                                clipboard.setText(AnnotatedString(field.value))
                                                copiedLabel = field.label
                                            }
                                            .padding(vertical = 11.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Text(
                                            field.label,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = colors.onSurfaceVariant,
                                            modifier = Modifier.width(88.dp)
                                        )
                                        Text(
                                            field.value,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = colors.onSurface,
                                            modifier = Modifier.weight(1f)
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
}

internal data class TrackMetadataField(
    val label: String,
    val value: String
)

internal data class TrackMetadataSection(
    val title: String,
    val fields: List<TrackMetadataField>
)

internal fun buildTrackMetadataSections(inspection: TrackInspectionUiState): List<TrackMetadataSection> {
    val track = inspection.track
    val d = inspection.details

    val tagFields = buildList {
        fun addIfPresent(label: String, value: String?) {
            val cleaned = value?.trim()?.takeIf { it.isNotEmpty() } ?: return
            add(TrackMetadataField(label, cleaned))
        }
        addIfPresent("标题", d?.title ?: track.title)
        addIfPresent("艺术家", (d?.artist ?: track.artist).ifBlank { "未知艺术家" })
        addIfPresent("专辑", (d?.album ?: track.album).ifBlank { "未知专辑" })
        addIfPresent("专辑艺术家", d?.albumArtist ?: track.albumArtist)
        addIfPresent("作曲", d?.composer)
        val trackNo = (track.trackNumber ?: d?.trackNumber)?.takeIf { it > 0 }?.toString()
        addIfPresent("曲目号", trackNo)
        val discNo = (track.discNumber ?: d?.discNumber)?.takeIf { it > 0 }?.toString()
        addIfPresent("碟号", discNo)
        addIfPresent("发行日期", track.releaseDate ?: d?.releaseDate)
        addIfPresent("流派", d?.genre ?: track.genre)
        val bpmText = track.bpm?.takeIf { it > 0.0 }?.let { formatBpmValue(it) }
        addIfPresent("BPM", bpmText)
        addIfPresent("ISRC", track.isrc)
        addIfPresent("状态说明", track.unavailableReason)
    }

    val specFields = buildList {
        fun addIfPresent(label: String, value: String?) {
            val cleaned = value?.trim()?.takeIf { it.isNotEmpty() } ?: return
            add(TrackMetadataField(label, cleaned))
        }
        addIfPresent("音频格式", formatCodecLabel(d?.format ?: track.format))
        addIfPresent("MIME 类型", d?.mimeType)
        val duration = track.duration.takeIf { it > 0.0 } ?: d?.durationSeconds?.takeIf { it > 0.0 }
        addIfPresent("时长", duration?.let(::formatDurationSeconds))
        val sampleRate = (d?.sampleRate ?: track.sampleRate)?.takeIf { it > 0 }
        addIfPresent("采样率", sampleRate?.let(::formatSampleRateHz))
        val bitDepth = (d?.bitDepth ?: track.bitDepth)?.takeIf { it > 0 }
        addIfPresent("位深", bitDepth?.let { "$it bit" })
        addIfPresent("声道", d?.channels?.takeIf { it > 0 }?.let(::formatChannelCount))
        val bitrateKbps = (d?.bitrateKbps ?: track.bitrate)?.takeIf { it > 0.0 }
        addIfPresent("码率", bitrateKbps?.let { "${kotlin.math.round(it).toLong()} kbps" })
        val sizeBytes = (d?.fileSize ?: track.size)?.takeIf { it > 0L }
        addIfPresent("文件大小", sizeBytes?.let(::formatFileSizeBytes))
        addIfPresent("CUE 分轨", track.cueRange?.let { formatCueRange(it) })
    }

    val sourceFields = buildList {
        fun addIfPresent(label: String, value: String?) {
            val cleaned = value?.trim()?.takeIf { it.isNotEmpty() } ?: return
            add(TrackMetadataField(label, cleaned))
        }
        addIfPresent("音源", inspection.sourceLabel)
        addIfPresent("文件名", d?.fileName)
        addIfPresent("路径", d?.displayPath)
        addIfPresent("网易云 ID", track.ncmSongId)
        val rawSource = track.source?.takeIf { it.isNotBlank() && it != d?.displayPath }
        addIfPresent("资源标识", rawSource)
        addIfPresent("曲目 ID", track.id)
        addIfPresent("入库时间", track.addedAt?.takeIf { it > 0L }?.let(::formatAddedTimestamp))
    }

    return listOfNotNull(
        TrackMetadataSection("标签信息", tagFields).takeIf { it.fields.isNotEmpty() },
        TrackMetadataSection("音频规格", specFields).takeIf { it.fields.isNotEmpty() },
        TrackMetadataSection("来源与文件", sourceFields).takeIf { it.fields.isNotEmpty() }
    )
}

internal fun formatAddedTimestamp(epochMs: Long): String {
    if (epochMs <= 0L) return ""
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(epochMs))
}

internal fun formatCodecLabel(raw: String?): String? {
    val trimmed = raw?.trim()?.takeUnless {
        it.isEmpty() || it.equals("unknown", ignoreCase = true) || it == "未知"
    } ?: return null
    return trimmed.uppercase(Locale.ROOT)
}

internal fun formatSampleRateHz(hz: Int): String {
    if (hz <= 0) return ""
    val khz = BigDecimal.valueOf(hz.toLong(), 3).stripTrailingZeros().toPlainString()
    return "$khz kHz ($hz Hz)"
}

internal fun formatChannelCount(channels: Int): String = when (channels) {
    1 -> "单声道 (1 ch)"
    2 -> "立体声 (2 ch)"
    6 -> "5.1 环绕声 (6 ch)"
    8 -> "7.1 环绕声 (8 ch)"
    else -> "$channels 声道"
}

internal fun formatFileSizeBytes(bytes: Long): String {
    if (bytes <= 0L) return ""
    val formattedBytes = String.format(Locale.US, "%,d", bytes)
    return when {
        bytes >= 1024L * 1024L * 1024L ->
            String.format(Locale.US, "%.2f GB (%s 字节)", bytes / (1024.0 * 1024.0 * 1024.0), formattedBytes)
        bytes >= 1024L * 1024L ->
            String.format(Locale.US, "%.2f MB (%s 字节)", bytes / (1024.0 * 1024.0), formattedBytes)
        bytes >= 1024L ->
            String.format(Locale.US, "%.1f KB (%s 字节)", bytes / 1024.0, formattedBytes)
        else -> "$bytes 字节"
    }
}

internal fun formatCueRange(cue: CueRange): String {
    fun formatCueClock(seconds: Double): String {
        val totalMs = (seconds * 1000.0).toLong().coerceAtLeast(0L)
        val mins = totalMs / 60_000L
        val secs = (totalMs / 1_000L) % 60L
        val ms = totalMs % 1_000L
        return if (ms == 0L) {
            String.format(Locale.US, "%02d:%02d", mins, secs)
        } else {
            String.format(Locale.US, "%02d:%02d.%03d", mins, secs, ms)
        }
    }
    val start = formatCueClock(cue.startSeconds)
    val end = formatCueClock(cue.endSeconds)
    return "$start → $end"
}

internal fun formatBpmValue(bpm: Double): String {
    val rounded = kotlin.math.round(bpm)
    return if (kotlin.math.abs(bpm - rounded) < 0.05) {
        rounded.toLong().toString()
    } else {
        String.format(Locale.US, "%.1f", bpm)
    }
}

internal fun formatTrackQuickBadge(track: CoreTrackView): String? {
    val parts = buildList {
        formatCodecLabel(track.format)?.let(::add)
        val rate = track.sampleRate?.takeIf { it > 0 }
        val depth = track.bitDepth?.takeIf { it > 0 }
        val khz = rate?.let { BigDecimal.valueOf(it.toLong(), 3).stripTrailingZeros().toPlainString() }
        when {
            depth != null && khz != null -> add("$depth bit / $khz kHz")
            depth != null -> add("$depth bit")
            khz != null -> add("$khz kHz")
        }
        if (track.cueRange != null) add("CUE")
    }
    return parts.joinToString(" · ").takeIf { it.isNotEmpty() }
}
