package com.twilightecho.android.feature.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.twilightecho.android.coreclient.*
import com.twilightecho.android.ui.components.SupportingText
import kotlinx.serialization.json.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DesktopQueueSheet(state: DesktopQueueState, actions: DesktopQueueActions) {
    if (!state.open) return
    val writing by rememberUpdatedState(state.writing)
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !writing || it != SheetValue.Hidden })
    var queueTab by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    ModalBottomSheet(onDismissRequest = actions.close, sheetState = sheet,
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 1f)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.94f).imePadding().padding(horizontal = 20.dp).testTag("desktopSessionQueue")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(state.title, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                TextButton(actions.close, enabled = !state.writing) { Text("关闭") }
            }
            if (state.playlist == null) SessionDestinationMenu(state.destinations, state.destination, !state.loading && !state.writing && !state.needsRefresh && state.pending == null, actions.destination)
            if (state.loading || state.writing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            state.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium) }
            Row {
                FilterChip(!queueTab, { queueTab = false }, label = { Text("选歌") }, modifier = Modifier.padding(end = 8.dp))
                if (state.playlist == null) FilterChip(queueTab, { queueTab = true }, label = { Text("队列 · ${state.total}") })
                Spacer(Modifier.weight(1f))
                TextButton(actions.refresh, enabled = !state.loading && !state.writing) { Text("刷新") }
            }
            if (queueTab) SessionQueueList(state, actions, Modifier.weight(1f))
            else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(state.search, actions.search, label = { Text("搜索电脑曲库") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); actions.submit() }),
                        enabled = !state.writing, modifier = Modifier.weight(1f).testTag("sessionMusicSearch"))
                    TextButton({ focus.clearFocus(); actions.submit() }, enabled = !state.loading && !state.writing) { Text("搜索") }
                }
                if (state.entity != null) TextButton({ actions.entity(null) }, enabled = !state.loading && !state.writing) { Text("返回搜索结果") }
                LazyColumn(Modifier.weight(1f)) {
                    if (state.preset != null) item { SupportingText("已选好音乐，选择目标声区后点下方载入。", Modifier.padding(vertical = 20.dp)) }
                    if (state.entity == null) {
                        if (state.catalog == null) item { SupportingText("搜索歌曲，选择音源后载入所选声区。", Modifier.padding(vertical = 24.dp)) }
                        else if (state.catalog.total == 0) item { SupportingText("没有找到歌曲", Modifier.padding(vertical = 24.dp)) }
                        items(state.catalog?.items.orEmpty(), key = { it.id }) { entity ->
                            ListItem(headlineContent = { Text(entity.title) }, supportingContent = { Text(entity.artist) },
                                trailingContent = { Text("${entity.sourceCount} 个音源") },
                                modifier = Modifier.clickable(enabled = !state.loading && !state.writing) { actions.entity(entity) })
                        }
                        if ((state.catalog?.items?.size ?: 0) < (state.catalog?.total ?: 0)) item {
                            TextButton(actions.more, enabled = !state.loading && !state.writing) { Text("更多歌曲") }
                        }
                    } else {
                        item { Text(state.entity.title, Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.titleMedium) }
                        items(state.sources?.items.orEmpty(), key = { it.id }) { source ->
                            ListItem(headlineContent = { Text(source.track.title) }, supportingContent = {
                                Text(listOf(source.track.album, source.provider, source.track.format.orEmpty()).filter { it.isNotBlank() }.joinToString(" · "))
                            }, leadingContent = { Checkbox(source.id in state.selected, { actions.toggle(source) }, enabled = !state.writing && !state.loading) },
                                modifier = Modifier.clickable(enabled = !state.writing && !state.loading) { actions.toggle(source) })
                        }
                        if ((state.sources?.items?.size ?: 0) < (state.sources?.total ?: 0)) item {
                            TextButton(actions.moreSources, enabled = !state.loading && !state.writing) { Text("更多音源") }
                        }
                    }
                }
                Button(actions.prepare, enabled = (state.selected.isNotEmpty() || state.preset != null) && (state.destination != null || state.playlist != null) && !state.loading && !state.writing && !state.needsRefresh,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag("sessionPrepareQueue")) {
                    Text(if (state.playlist != null) "添加 ${state.selected.size} 首" else if (state.preset != null) "载入所选音乐到「${state.destination?.name}」" else if (state.selected.isEmpty()) "选择音源" else "载入 ${state.selected.size} 首到「${state.destination?.name}」")
                }
            }
        }
    }
    state.pending?.let {
        AlertDialog(onDismissRequest = { if (!state.writing) actions.cancel() }, title = { Text(state.playlist?.let { "添加到「${it.name}」" } ?: "载入到「${state.destination?.name}」") },
            text = { Text(if (state.playlist != null) "添加所选音源，不开始播放。" else if (state.destination?.queue != null) "将替换这个声区当前的队列。其他声区与手机队列保持不变。" else "只改变这个声区的队列。") },
            confirmButton = { TextButton({ actions.confirm(true) }, enabled = !state.loading && !state.writing) { Text(if (state.playlist != null) "添加" else "替换并播放") } },
            dismissButton = { Row {
                TextButton(actions.cancel, enabled = !state.writing) { Text("取消") }
                if (state.playlist == null) TextButton({ actions.confirm(false) }, enabled = !state.loading && !state.writing) { Text("仅载入，保持暂停") }
            } })
    }
}

@Composable
internal fun SessionDestinationMenu(destinations: List<DesktopQueueDestination>, selected: DesktopQueueDestination?, enabled: Boolean,
    choose: (DesktopQueueDestination) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ expanded = true }, enabled = enabled && destinations.isNotEmpty(), modifier = Modifier.fillMaxWidth().testTag("sessionDestination")) {
            Text("播放到 · ${selected?.name ?: "选择声区"}", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded && enabled, { expanded = false }) {
            destinations.forEach { destination -> DropdownMenuItem(text = { Text(destination.name) }, onClick = { expanded = false; choose(destination) }) }
        }
    }
}

internal fun sessionQueueMove(items: List<DesktopSessionQueueItem>, total: Int, index: Int, down: Boolean): JsonObject? {
    val item = items.getOrNull(index) ?: return null
    val before = if (!down) items.getOrNull(index - 1)?.queueEntryId ?: return null else {
        if (index + 1 >= total || index + 1 >= items.size || index + 2 < total && index + 2 >= items.size) return null
        items.getOrNull(index + 2)?.queueEntryId
    }
    return buildJsonObject { put("kind", "move"); put("entryId", item.queueEntryId); put("beforeEntryId", before?.let(::JsonPrimitive) ?: JsonNull) }
}

@Composable
private fun SessionQueueList(state: DesktopQueueState, actions: DesktopQueueActions, modifier: Modifier) {
    val enabled = !state.loading && !state.writing && !state.needsRefresh
    var clearing by remember(state.destination?.target?.id) { mutableStateOf(false) }
    LazyColumn(modifier.testTag("sessionQueueItems")) {
        item { Row {
            TextButton({ actions.edit(buildJsonObject { put("kind", "order"); put("order", "shuffle") }) }, enabled = enabled && state.total > 1) { Text("随机排序") }
            TextButton({ actions.edit(buildJsonObject { put("kind", "order"); put("order", "original") }) }, enabled = enabled && state.total > 1) { Text("原始顺序") }
            TextButton({ clearing = true }, enabled = enabled && state.total > 0) { Text("清空") }
        } }
        if (state.total == 0) item { SupportingText("队列为空，先到“选歌”载入音乐。", Modifier.padding(vertical = 24.dp)) }
        items(state.items, key = { it.queueEntryId }) { item ->
            val index = state.items.indexOf(item)
            Column {
                ListItem(headlineContent = { Text(item.track?.title ?: "歌曲暂不可用", maxLines = 2) },
                    supportingContent = { Text(item.track?.artist.orEmpty()) }, leadingContent = { Text("${item.position + 1}") },
                    modifier = Modifier.clickable(enabled = enabled && item.track != null) { actions.select(item) })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    val up = sessionQueueMove(state.items, state.total, index, false)
                    val down = sessionQueueMove(state.items, state.total, index, true)
                    TextButton({ up?.let(actions.edit) }, enabled = enabled && up != null) { Text("上移") }
                    TextButton({ down?.let(actions.edit) }, enabled = enabled && down != null) { Text("下移") }
                    TextButton({ actions.edit(buildJsonObject { put("kind", "remove"); put("entryIds", buildJsonArray { add(item.queueEntryId) }) }) }, enabled = enabled) { Text("移除") }
                }
            }
        }
        if (state.items.size < state.total) item { TextButton(actions.moreQueue, enabled = enabled) { Text("更多队列项") } }
    }
    if (clearing) AlertDialog(onDismissRequest = { clearing = false }, title = { Text("清空「${state.destination?.name}」的队列？") },
        text = { Text("停止这个声区的播放并移除队列项，音乐文件保留。") },
        confirmButton = { TextButton({ clearing = false; actions.edit(buildJsonObject { put("kind", "clear") }) }, enabled = enabled) { Text("清空队列") } },
        dismissButton = { TextButton({ clearing = false }) { Text("取消") } })
}
