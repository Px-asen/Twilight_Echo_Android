package com.twilightecho.android.feature.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.twilightecho.android.coreclient.*
import com.twilightecho.android.ui.components.SupportingText
import kotlinx.serialization.json.*

private data class WorkspacePrompt(val title: String, val initial: String = "", val message: String? = null,
    val destructive: Boolean = false, val confirm: (String) -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DesktopLibraryWorkspaceSheet(state: DesktopLibraryWorkspaceState, actions: DesktopLibraryWorkspaceActions,
    openPlugins: () -> Unit = {}) {
    if (!state.open) return
    var prompt by remember { mutableStateOf<WorkspacePrompt?>(null) }
    val writing by rememberUpdatedState(state.writing)
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !writing || it != SheetValue.Hidden })
    val enabled = !state.loading && !state.writing && !state.needsRefresh
    LaunchedEffect(state.notice) { if (state.notice != null) prompt = null }
    ModalBottomSheet(onDismissRequest = actions.close, sheetState = sheet,
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 1f)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.94f).imePadding().padding(horizontal = 20.dp).testTag("desktopLibraryWorkspace")) {
            Row {
                Column(Modifier.weight(1f)) {
                    Text("电脑曲库管理", style = MaterialTheme.typography.headlineSmall)
                    SupportingText(state.host)
                }
                TextButton(actions.close, enabled = !state.writing) { Text("关闭") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DesktopWorkspaceTab.entries.forEach { tab -> FilterChip(state.tab == tab, { actions.tab(tab) }, enabled = !state.writing, label = { Text(tab.label) }) }
            }
            if (state.loading || state.writing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            state.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(actions.refresh, enabled = !state.loading && !state.writing) { Text("重新读取") }
                TextButton(openPlugins, enabled = !state.writing, modifier = Modifier.testTag("desktopPluginsOpen")) { Text("Server 扩展") }
            }
            LazyColumn(Modifier.weight(1f).testTag("desktopWorkspaceItems"), contentPadding = PaddingValues(bottom = 24.dp)) {
                when (state.tab) {
                    DesktopWorkspaceTab.FOCUS -> focusWorkspace(state, actions, enabled) { prompt = it }
                    DesktopWorkspaceTab.TAGS -> tagsWorkspace(state, actions, enabled) { prompt = it }
                    DesktopWorkspaceTab.PLAYLISTS -> playlistsWorkspace(state, actions, enabled) { prompt = it }
                }
            }
        }
    }
    prompt?.let { current ->
        var name by remember(current) { mutableStateOf(current.initial) }
        AlertDialog(onDismissRequest = { if (!state.writing) prompt = null }, title = { Text(current.title) },
            text = {
                Column {
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (state.needsRefresh) SupportingText("请取消并重新读取，核对上次操作的结果。")
                    if (current.destructive) Text(current.message.orEmpty())
                    else OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("名称") }, enabled = !state.writing)
                }
            }, confirmButton = { TextButton({ current.confirm(name.trim()) }, enabled = enabled && (current.destructive || name.isNotBlank())) {
                Text(if (current.destructive) "确认删除" else "保存")
            } }, dismissButton = { TextButton({ prompt = null }, enabled = !state.writing) { Text("取消") } })
    }
}

private fun LazyListScope.focusWorkspace(state: DesktopLibraryWorkspaceState, actions: DesktopLibraryWorkspaceActions,
    enabled: Boolean, prompt: (WorkspacePrompt) -> Unit) {
    item {
        Text("筛选音乐", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(state.query["entity"]?.jsonPrimitive?.content == "recording", { actions.query(JsonObject(state.query + ("entity" to JsonPrimitive("recording")))) }, enabled = enabled, label = { Text("歌曲") })
            FilterChip(state.query["entity"]?.jsonPrimitive?.content == "release", { actions.query(JsonObject(state.query + ("entity" to JsonPrimitive("release")))) }, enabled = enabled, label = { Text("发行版") })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val scope = state.query["scope"]?.jsonPrimitive?.content ?: "library"
            FilterChip(scope == "library", { actions.query(JsonObject(state.query + ("scope" to JsonPrimitive("library")))) }, enabled = enabled, label = { Text("在库音乐") })
            FilterChip(scope == "catalog", { actions.query(JsonObject(state.query + ("scope" to JsonPrimitive("catalog")))) }, enabled = enabled, label = { Text("全部资料") })
            WorkspaceMenu(enabled, listOf("标题" to "title", "艺人" to "artist", "评分" to "personalRating", "最近播放" to "lastPlayed", "年份" to "releaseYear").map { (label, field) ->
                label to { actions.query(JsonObject(state.query + ("sort" to buildJsonArray { add(buildJsonObject {
                    put("field", field); put("direction", if (field in setOf("title", "artist")) "asc" else "desc")
                }) }))) }
            }, "排序")
        }
        FocusNodeEditor(state.query["where"] as? JsonObject ?: buildJsonObject { put("all", JsonArray(emptyList())) }, enabled, 0) { where ->
            actions.query(JsonObject(state.query + ("where" to where)))
        }
        Row {
            Button(actions.focus, enabled = enabled, modifier = Modifier.testTag("desktopFocusQuery")) { Text("查询") }
            TextButton({ prompt(WorkspacePrompt("保存这组筛选条件", confirm = actions.save)) }, enabled = enabled && state.saved != null) { Text("另存条件") }
        }
        state.selectedSaved?.let { saved ->
            Row {
                SupportingText("正在编辑「${saved.name}」", Modifier.weight(1f))
                TextButton(actions.updateSaved, enabled = enabled) { Text("更新条件") }
            }
        }
        Row {
            TextButton({ actions.facet("genre") }, enabled = enabled) { Text("流派分面") }
            TextButton({ actions.facet("provider") }, enabled = enabled) { Text("音源分面") }
            TextButton({ actions.facet("preference") }, enabled = enabled) { Text("个人偏好") }
        }
        TextButton(actions.focusTags, enabled = enabled) { Text("添加标签条件") }
    }
    if (state.showFocusTags) {
        items(state.tags?.items.orEmpty(), key = { "focus-tag:${it.id}" }) { tag ->
            ListItem(headlineContent = { Text(tag.name) }, supportingContent = { Text("${tag.memberCount} 个成员") },
                modifier = Modifier.clickable(enabled = enabled) { actions.query(appendFocusCondition(state.query,
                    buildJsonObject { put("tag", buildJsonObject { put("id", tag.id) }) })) })
        }
        if ((state.tags?.items?.size ?: 0) < (state.tags?.total ?: 0)) item { TextButton(actions.moreTags, enabled = enabled) { Text("更多标签条件") } }
    }
    state.facets?.let { page ->
        items(page.items, key = { "facet:${page.facet}:${it.value}" }) { facet ->
            ListItem(headlineContent = { Text(facet.label) }, trailingContent = { Text(facet.count.toString()) }, modifier = Modifier.clickable(enabled = enabled) {
                val node = when (page.facet) {
                    "genre" -> buildJsonObject { put("genre", buildJsonObject { put("id", facet.value); put("includeDescendants", true) }) }
                    "provider" -> buildJsonObject { put("source", buildJsonObject { put("provider", facet.value) }) }
                    else -> buildJsonObject { put("preference", facet.value) }
                }
                actions.query(appendFocusCondition(state.query, node))
            })
        }
        if (page.items.size < page.total) item { TextButton(actions.moreFacets, enabled = enabled) { Text("更多分面") } }
    }
    item {
        Row {
            Text(state.focus?.let { "${it.total} 项结果" } ?: "查询后显示结果", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton({ actions.playFocus(null) }, enabled = enabled && (state.focus?.total ?: 0) > 0) { Text("播放到…") }
        }
    }
    items(state.focus?.items.orEmpty(), key = { "focus:${it.id}" }) { item ->
        ListItem(headlineContent = { Text(item.title) }, supportingContent = { Text(item.artist) }, trailingContent = {
            if (item.playable) TextButton({ actions.playFocus(item.sourceId) }, enabled = enabled) { Text("从这里播放") }
        })
    }
    if ((state.focus?.items?.size ?: 0) < (state.focus?.total ?: 0)) item { TextButton(actions.moreFocus, enabled = enabled) { Text("更多结果") } }
    item { HorizontalDivider(Modifier.padding(vertical = 16.dp)); Text("保存的筛选条件", style = MaterialTheme.typography.titleMedium) }
    items(state.saved?.items.orEmpty(), key = { "saved:${it.id}" }) { saved ->
        ListItem(headlineContent = { Text(saved.name) }, modifier = Modifier.clickable(enabled = enabled) { actions.saved(saved) }, trailingContent = {
            WorkspaceMenu(enabled, listOf(
                "重命名" to { prompt(WorkspacePrompt("重命名筛选条件", saved.name, confirm = { actions.renameSaved(saved, it) })) },
                "删除" to { prompt(WorkspacePrompt("删除「${saved.name}」？", message = "只删除保存的条件，不删除音乐。", destructive = true, confirm = { actions.deleteSaved(saved) })) }
            ))
        })
    }
    if ((state.saved?.items?.size ?: 0) < (state.saved?.total ?: 0)) item { TextButton(actions.moreSaved, enabled = enabled) { Text("更多保存条件") } }
}

private fun LazyListScope.tagsWorkspace(state: DesktopLibraryWorkspaceState, actions: DesktopLibraryWorkspaceActions,
    enabled: Boolean, prompt: (WorkspacePrompt) -> Unit) {
    val detail = state.tag
    if (detail == null) {
        item { Row {
            Text("标签目录", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton({ prompt(WorkspacePrompt("新建标签", confirm = actions.createTag)) }, enabled = enabled && state.tags != null) { Text("新建") }
        } }
        if (state.tags?.total == 0) item { SupportingText("还没有标签。也可以在歌曲或专辑的资料表单中添加。") }
        items(state.tags?.items.orEmpty(), key = { it.id }) { tag ->
            ListItem(headlineContent = { Text(tag.name) }, supportingContent = { Text("${tag.memberCount} 个成员") },
                modifier = Modifier.clickable(enabled = enabled) { actions.tag(tag) }, trailingContent = {
                    WorkspaceMenu(enabled, listOf(
                        "重命名" to { prompt(WorkspacePrompt("重命名标签", tag.name, confirm = { actions.renameTag(tag, it) })) },
                        "删除" to { prompt(WorkspacePrompt("删除「${tag.name}」？", message = "移除标签与成员关联，不删除音乐。", destructive = true, confirm = { actions.deleteTag(tag) })) }
                    ))
                })
        }
        if ((state.tags?.items?.size ?: 0) < (state.tags?.total ?: 0)) item { TextButton(actions.moreTags, enabled = enabled) { Text("更多标签") } }
    } else {
        item {
            TextButton({ actions.tag(null) }, enabled = enabled) { Text("返回标签目录") }
            Text(detail.tag.name, style = MaterialTheme.typography.titleLarge)
            TextButton(actions.playTag, enabled = enabled && detail.total > 0) { Text("标签中的音乐播放到…") }
        }
        items(detail.items, key = { it.id }) { entity ->
            ListItem(headlineContent = { Text(entity.title) }, supportingContent = { Text(entity.artist) }, trailingContent = {
                TextButton({ actions.removeTagMember(entity.id) }, enabled = enabled) { Text("移除标签") }
            })
        }
        if (detail.items.size < detail.total) item { TextButton(actions.moreTagMembers, enabled = enabled) { Text("更多成员") } }
    }
}

private fun LazyListScope.playlistsWorkspace(state: DesktopLibraryWorkspaceState, actions: DesktopLibraryWorkspaceActions,
    enabled: Boolean, prompt: (WorkspacePrompt) -> Unit) {
    val playlist = state.playlist
    if (playlist == null) {
        item { Row {
            Text("Server 歌单", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton({ prompt(WorkspacePrompt("新建 Server 歌单", confirm = { name -> actions.playlistEdit(buildJsonObject { put("kind", "create"); put("name", name) }) })) }, enabled = enabled && state.playlists != null) { Text("新建") }
        } }
        if (state.playlists?.total == 0) item { SupportingText("电脑还没有歌单。这里的歌单与手机本地歌单分别保存。") }
        items(state.playlists?.items.orEmpty(), key = { it.id }) { row ->
            ListItem(headlineContent = { Text(row.name) }, supportingContent = { Text("${row.count} 首${if (row.imported) " · 导入歌单" else ""}") },
                modifier = Modifier.clickable(enabled = enabled) { actions.playlist(row) }, trailingContent = {
                    val options = mutableListOf<Pair<String, () -> Unit>>()
                    options += "复制" to { prompt(WorkspacePrompt("复制歌单", "${row.name} 副本", confirm = { name -> actions.playlistEdit(playlistEdit("copy", row.id) { put("name", name) }) })) }
                    options += (if (row.pinnedAt != null) "取消置顶" else "置顶") to { actions.playlistEdit(playlistEdit("pin", row.id) { put("pinned", row.pinnedAt == null) }) }
                    if (!row.isDefault) {
                        options += "重命名" to { prompt(WorkspacePrompt("重命名歌单", row.name, confirm = { name -> actions.playlistEdit(playlistEdit("rename", row.id) { put("name", name) }) })) }
                        options += "删除" to { prompt(WorkspacePrompt("删除「${row.name}」？", message = "只删除歌单，不删除音乐文件。", destructive = true, confirm = { actions.playlistEdit(playlistEdit("delete", row.id)) })) }
                    }
                    WorkspaceMenu(enabled, options)
                })
        }
        if ((state.playlists?.items?.size ?: 0) < (state.playlists?.total ?: 0)) item { TextButton(actions.morePlaylists, enabled = enabled) { Text("更多歌单") } }
    } else {
        item {
            TextButton({ actions.playlist(null) }, enabled = enabled) { Text("返回 Server 歌单") }
            Text(playlist.name, style = MaterialTheme.typography.titleLarge)
            Row {
                TextButton(actions.playPlaylist, enabled = enabled && (state.members?.total ?: 0) > 0) { Text("播放到…") }
                TextButton(actions.addSources, enabled = enabled && !playlist.imported && playlist.kind != "aggregate") { Text("添加歌曲") }
            }
        }
        val rows = state.members?.items.orEmpty()
        items(rows, key = { it.id }) { row ->
            ListItem(headlineContent = { Text(row.track?.title ?: "未解析的歌曲") }, supportingContent = { Text(row.track?.artist.orEmpty()) },
                leadingContent = { Text("${row.position + 1}") }, trailingContent = {
                    WorkspaceMenu(enabled, buildList {
                        if (!row.imported && playlist.kind != "aggregate") {
                            if (row.position > 0) add("上移" to { actions.playlistEdit(playlistEdit("reorder", playlist.id) { put("ids", buildJsonArray { add(row.id) }); put("target", row.position - 1) }) })
                            if (row.position + 1 < (state.members?.total ?: 0)) add("下移" to { actions.playlistEdit(playlistEdit("reorder", playlist.id) { put("ids", buildJsonArray { add(row.id) }); put("target", row.position + 2) }) })
                        }
                        add("移除" to { actions.playlistEdit(playlistEdit(if (row.imported) "importRemove" else "remove", playlist.id) {
                            put(if (row.imported) "entryIds" else "ids", buildJsonArray { add(row.id) })
                        }) })
                    })
                })
        }
        if (rows.size < (state.members?.total ?: 0)) item { TextButton(actions.moreMembers, enabled = enabled) { Text("更多歌曲") } }
    }
}

private fun playlistEdit(kind: String, id: String, fields: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject { put("kind", kind); put("id", id); fields() }

@Composable
private fun WorkspaceMenu(enabled: Boolean, options: List<Pair<String, () -> Unit>>, label: String = "更多") {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton({ expanded = true }, enabled = enabled) { Text(label) }
        DropdownMenu(expanded && enabled, { expanded = false }) {
            options.forEach { (label, action) -> DropdownMenuItem(text = { Text(label) }, onClick = { expanded = false; action() }) }
        }
    }
}

internal fun appendFocusCondition(query: JsonObject, node: JsonObject): JsonObject {
    val old = query["where"] as? JsonObject
    val conditions = if (old?.keys == setOf("all")) old["all"]!!.jsonArray.toList() else listOfNotNull(old)
    return JsonObject(query + ("where" to buildJsonObject { put("all", JsonArray(conditions + node)) }))
}

private val focusDefaults = linkedMapOf(
    "text" to ("关键词" to JsonPrimitive("")),
    "rating" to ("个人评分下限" to buildJsonObject { put("target", "self"); put("mode", "personal"); put("gteStars", 4) }),
    "year" to ("发行年份" to buildJsonObject { put("target", "release"); put("gte", 2000) }),
    "source" to ("音源／格式" to buildJsonObject { put("provider", "local") }),
    "preference" to ("个人偏好" to JsonPrimitive("favorite")),
    "notPlayedWithinDays" to ("最近未播放天数" to JsonPrimitive(30)),
    "all" to ("全部满足" to JsonArray(emptyList())), "any" to ("任一满足" to JsonArray(emptyList())),
    "not" to ("排除条件" to buildJsonObject { put("text", "") })
)

@Composable
private fun FocusNodeEditor(node: JsonObject, enabled: Boolean, depth: Int, change: (JsonObject) -> Unit) {
    val kind = node.keys.singleOrNull() ?: return
    val value = node.getValue(kind)
    Column(Modifier.fillMaxWidth().padding(start = if (depth == 0) 0.dp else 12.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (kind == "all" || kind == "any") {
            Text(if (kind == "all") "满足全部条件" else "满足任一条件", style = MaterialTheme.typography.titleSmall)
            val children = value.jsonArray
            children.forEachIndexed { index, child -> Row {
                Column(Modifier.weight(1f)) { FocusNodeEditor(child.jsonObject, enabled, depth + 1) { next ->
                    change(buildJsonObject { put(kind, JsonArray(children.mapIndexed { i, old -> if (i == index) next else old })) })
                } }
                TextButton({ change(buildJsonObject { put(kind, JsonArray(children.filterIndexed { i, _ -> i != index })) }) }, enabled = enabled) { Text("移除") }
            } }
            if (depth < 7 && children.size < 64) {
                WorkspaceMenu(enabled, focusDefaults.map { (key, option) -> option.first to {
                    change(buildJsonObject { put(kind, JsonArray(children + buildJsonObject { put(key, option.second) })) })
                } }, "添加条件")
            }
        } else if (kind == "not") {
            Text("排除", style = MaterialTheme.typography.titleSmall)
            FocusNodeEditor(value.jsonObject, enabled, depth + 1) { change(buildJsonObject { put("not", it) }) }
        } else if (kind == "text" || kind == "notPlayedWithinDays") {
            val raw = value.jsonPrimitive.content
            OutlinedTextField(raw, { text -> change(buildJsonObject { put(kind, if (kind == "text") JsonPrimitive(text) else text.toIntOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(text)) }) },
                label = { Text(focusDefaults[kind]!!.first) }, enabled = enabled, singleLine = true,
                isError = if (kind == "text") raw.isBlank() else raw.toIntOrNull()?.let { it < 0 } != false, modifier = Modifier.fillMaxWidth())
        } else if (kind == "preference") {
            Row { listOf("favorite" to "收藏", "ban" to "禁播", "none" to "无偏好").forEach { (key, label) ->
                FilterChip(value.jsonPrimitive.content == key, { change(buildJsonObject { put(kind, key) }) }, enabled = enabled, label = { Text(label) })
            } }
        } else if (kind in setOf("rating", "year", "source")) {
            Text(focusDefaults[kind]!!.first, style = MaterialTheme.typography.titleSmall)
            val fields = value.jsonObject
            if (kind == "rating") Row {
                listOf("personal" to "个人", "base" to "资料", "effective" to "有效").forEach { (mode, label) ->
                    FilterChip(fields["mode"]?.jsonPrimitive?.content == mode, { change(buildJsonObject { put(kind, JsonObject(fields + ("mode" to JsonPrimitive(mode)))) }) }, enabled = enabled, label = { Text(label) })
                }
            }
            if (kind == "year") Row {
                listOf("release" to "发行年份", "project" to "作品年份").forEach { (target, label) ->
                    FilterChip(fields["target"]?.jsonPrimitive?.content == target, { change(buildJsonObject { put(kind, JsonObject(fields + ("target" to JsonPrimitive(target)))) }) }, enabled = enabled, label = { Text(label) })
                }
            }
            val names = when (kind) {
                "rating" -> listOf("gteStars" to "最低星级", "lteStars" to "最高星级")
                "year" -> listOf("gte" to "起始年份", "lte" to "结束年份")
                else -> listOf("provider" to "音源标识", "format" to "格式，例如 FLAC")
            }
            names.forEach { (key, label) ->
                val raw = fields[key]?.jsonPrimitive?.content.orEmpty()
                OutlinedTextField(raw, { text ->
                    val next = fields.toMutableMap()
                    if (text.isBlank()) next.remove(key) else next[key] = if (kind == "source") JsonPrimitive(text)
                        else text.toDoubleOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(text)
                    change(buildJsonObject { put(kind, JsonObject(next)) })
                }, label = { Text(label) }, enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    isError = kind != "source" && raw.isNotBlank() && raw.toDoubleOrNull() == null)
            }
        } else {
            // Saved conditions outside the quick editor remain intact. Removing a
            // parent node is explicit; rendering never normalizes or flattens them.
            Text(when (kind) { "genre" -> "流派条件"; "tag" -> "标签条件"; else -> "已保存的高级条件 · $kind" }, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
