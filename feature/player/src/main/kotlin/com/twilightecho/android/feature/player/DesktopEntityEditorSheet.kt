package com.twilightecho.android.feature.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.twilightecho.android.coreclient.*
import com.twilightecho.android.ui.components.SupportingText
import kotlin.math.roundToInt

data class DesktopEntityEditorActions(
    val open: (CatalogEntity) -> Unit = {}, val close: () -> Unit = {}, val refresh: () -> Unit = {},
    val target: (String) -> Unit = {}, val rate: (Int?) -> Unit = {}, val prefer: (String) -> Unit = {},
    val tag: (String, Boolean) -> Unit = { _, _ -> }, val tagName: (String) -> Unit = {}, val createTag: () -> Unit = {},
    val introductionDraft: (String) -> Unit = {}, val introduction: (String) -> Unit = {},
    val source: (String?) -> Unit = {}, val artwork: (String) -> Unit = {},
    val pickArtwork: (DesktopEntityEditTicket) -> Unit = {}
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun DesktopEntityEditorSheet(state: DesktopEntityEditorState, actions: DesktopEntityEditorActions) {
    val entity = state.entity ?: return
    val busy by rememberUpdatedState(state.busy)
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true,
        confirmValueChange = { busy == null || it != SheetValue.Hidden })
    val idle = state.busy == null
    ModalBottomSheet(onDismissRequest = actions.close, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).verticalScroll(rememberScrollState())
            .imePadding().padding(horizontal = 24.dp).padding(bottom = 32.dp).testTag("desktopEntityEditor"),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(entity.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (entity.artist.isNotBlank()) SupportingText(entity.artist)
                    SupportingText("整理 · ${state.host}")
                }
                TextButton(onClick = actions.close, enabled = state.busy == null) { Text("关闭") }
            }
            if (state.metadataLoading || state.personalLoading || state.tagsLoading || state.busy != null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                SupportingText(state.busy?.let { "$it…" } ?: "正在读取电脑资料…")
            }
            state.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium) }
            TextButton(onClick = actions.refresh, enabled = idle && !state.metadataLoading && !state.personalLoading &&
                !state.tagsLoading && state.ticket != null) { Text("重新读取电脑上的最新资料") }

            if (state.targets.size > 1) {
                Text("选择具体版本", style = MaterialTheme.typography.titleMedium)
                SupportingText("评分、收藏和标签只应用于选中的版本。")
                state.targets.forEachIndexed { index, target ->
                    FilterChip(selected = state.selectedTarget?.id == target.id,
                        onClick = { actions.target(target.id) }, enabled = idle && !state.personalLoading,
                        label = { Column {
                            Text("${index + 1}. ${target.title}")
                            if (target.artist.isNotBlank()) Text(target.artist, style = MaterialTheme.typography.bodySmall)
                            Text(target.id, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        } }, modifier = Modifier.fillMaxWidth())
                }
            }
            state.personalError?.let { EditorError(it) }
            if (entity.kind == "album_project") SupportingText("这是专辑聚合页。请打开具体发行版后设置评分、收藏和标签。")
            if (state.selectedTarget != null) {
                val enabled = idle && state.personalWritable
                state.rating?.let { rating ->
                    HorizontalDivider()
                    var steps by remember(state.ticket, rating.entityId, rating.value?.steps) {
                        mutableIntStateOf(rating.value?.steps ?: 10)
                    }
                    Text("个人评分", style = MaterialTheme.typography.titleMedium)
                    SupportingText(rating.value?.steps?.let { "已保存 ${stars(it)} 星" } ?: "尚未评分")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Slider(steps.toFloat(), { steps = it.roundToInt() }, valueRange = 1f..10f, steps = 8,
                            enabled = enabled, modifier = Modifier.weight(1f).testTag("desktopRatingSlider"))
                        Text("${stars(steps)} 星", Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleMedium)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilledTonalButton(onClick = { actions.rate(steps) }, enabled = enabled && steps != rating.value?.steps) { Text("保存评分") }
                        TextButton(onClick = { actions.rate(null) }, enabled = enabled && rating.value?.steps != null) { Text("清除评分") }
                    }
                }
                state.preference?.let { preference ->
                    Text("个人偏好", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf("favorite" to "收藏", "ban" to "禁播", "none" to "无偏好").forEach { (value, label) ->
                            val selected = (preference.value?.state ?: "none") == value
                            FilterChip(selected = selected, onClick = { if (!selected) actions.prefer(value) },
                                enabled = enabled, label = { Text(label) })
                        }
                    }
                }
                if (entity.kind == "artist" && !state.preferenceEligible)
                    SupportingText("这位艺人尚未确认身份；确认后可设置收藏或禁播。")
                HorizontalDivider()
                Text("标签", style = MaterialTheme.typography.titleMedium)
                state.tagsError?.let { EditorError(it) }
                var search by remember(state.ticket, state.selectedTarget.id) { mutableStateOf("") }
                var shown by remember(search, state.ticket, state.selectedTarget.id) { mutableIntStateOf(20) }
                if (state.tags.isNotEmpty()) {
                    OutlinedTextField(search, { search = it }, label = { Text("查找标签") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    val filtered = state.tags.filter { it.name.contains(search, ignoreCase = true) }
                        .sortedByDescending { it.id in state.assignedTags }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        filtered.take(shown).forEach { tag ->
                            val selected = tag.id in state.assignedTags
                            FilterChip(selected = selected, onClick = { actions.tag(tag.id, !selected) },
                                enabled = idle && state.tagsWritable, label = { Text(tag.name) })
                        }
                    }
                    if (filtered.size > shown) TextButton(onClick = { shown += 20 }) { Text("显示更多（共 ${filtered.size} 个）") }
                } else if (!state.personalLoading && !state.tagsLoading && state.tagsError == null) SupportingText("还没有标签，新建一个开始整理。")
                OutlinedTextField(state.tagNameDraft, actions.tagName, label = { Text("新标签") }, singleLine = true,
                    isError = state.tagNameDraft.trim().length > 80,
                    supportingText = { if (state.tagNameDraft.trim().length > 80) Text("标签名称最多 80 字") },
                    enabled = idle && state.tagsWritable, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = actions.createTag, enabled = idle && state.tagsWritable && state.tagNameDraft.trim().length in 1..80) { Text("新建并添加") }
            }

            HorizontalDivider()
            Text("简介", style = MaterialTheme.typography.titleMedium)
            state.metadataError?.let { EditorError(it) }
            val metadataEnabled = idle && state.metadataWritable
            OutlinedTextField(state.introductionDraft, actions.introductionDraft, label = { Text("个人简介") },
                enabled = metadataEnabled, minLines = 4, maxLines = 10, modifier = Modifier.fillMaxWidth().testTag("desktopIntroductionDraft"),
                supportingText = { if (state.introductionDraft.length > 20000) Text("简介最多 20000 字") })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { actions.introduction("value") },
                    enabled = metadataEnabled && state.introductionDraft.isNotBlank() && state.introductionDraft.length <= 20000 && state.introductionDirty) { Text("保存简介") }
                TextButton(onClick = { actions.introduction("clear") }, enabled = metadataEnabled) { Text("隐藏简介") }
                TextButton(onClick = { actions.introduction("reset") }, enabled = metadataEnabled) { Text("恢复自动简介") }
            }

            if (entity.kind in setOf("artist", "release", "album_project")) {
                Text(if (entity.kind == "artist") "艺人图片" else "专辑封面", style = MaterialTheme.typography.titleMedium)
                SupportingText(when (state.metadata?.artworkOverride?.mode) { "value" -> "正在使用个人图片"; "clear" -> "图片已隐藏"; else -> "使用自动图片" })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { state.ticket?.let(actions.pickArtwork) }, enabled = metadataEnabled) { Text("选择图片") }
                    TextButton(onClick = { actions.artwork("clear") }, enabled = metadataEnabled) { Text("隐藏图片") }
                    TextButton(onClick = { actions.artwork("reset") }, enabled = metadataEnabled) { Text("恢复自动图片") }
                }
            }
            state.metadata?.let { metadata ->
                if (metadata.sourceOptions.isNotEmpty() || metadata.sourcePreference?.provider != null) {
                    Text("资料来源", style = MaterialTheme.typography.titleMedium)
                    val selected = metadata.sourcePreference?.provider
                    if (selected != null && metadata.sourcePreference?.available == false) SupportingText("所选来源暂不可用：$selected")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = selected == null, onClick = { if (selected != null) actions.source(null) },
                            enabled = metadataEnabled, label = { Text("自动") })
                        metadata.sourceOptions.forEach { provider ->
                            FilterChip(selected = provider == selected, onClick = { if (provider != selected) actions.source(provider) },
                                enabled = metadataEnabled, label = { Text(provider) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorError(message: String) { Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
private fun stars(steps: Int): String = if (steps % 2 == 0) (steps / 2).toString() else "${steps / 2}.5"
