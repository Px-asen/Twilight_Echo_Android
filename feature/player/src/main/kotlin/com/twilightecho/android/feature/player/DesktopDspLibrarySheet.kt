package com.twilightecho.android.feature.player

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.twilightecho.android.coreclient.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File

private data class DspConfirmation(val title: String, val explanation: String, val edit: JsonObject)
private fun dspEdit(kind: String, fields: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject { put("kind", kind); fields() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DesktopDspLibrarySheet(state: DesktopDspLibraryState, actions: DesktopDspLibraryActions) {
    if (!state.open) return
    val context = LocalContext.current.applicationContext
    val coroutine = rememberCoroutineScope()
    var preparing by remember { mutableStateOf(false) }
    var pickerTicket by remember { mutableStateOf("") }
    var saveTicket by remember { mutableStateOf<Pair<String, File>?>(null) }
    var fileError by remember { mutableStateOf<String?>(null) }
    var selectedScene by remember(state.zoneId) { mutableStateOf<String?>(null) }
    var tab by remember { mutableIntStateOf(0) }
    var creating by remember { mutableStateOf(false) }
    var sceneName by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf<DspConfirmation?>(null) }
    var bind by remember { mutableStateOf<DesktopDspAsset?>(null) }
    var plugin by remember { mutableStateOf<DesktopVst3Plugin?>(null) }
    var preset by remember { mutableStateOf<String?>(null) }
    val library = state.library
    val scene = library?.scenes?.firstOrNull { it.id == selectedScene && it.id != "default" }
    val busy = state.loading || state.writing || preparing
    val editable = !busy && !state.needsRefresh && library != null && !library.scanning
    val locked by rememberUpdatedState(state.writing || preparing)
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !locked || it != SheetValue.Hidden })
    val currentActions by rememberUpdatedState(actions)
    val currentState by rememberUpdatedState(state)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) coroutine.launch {
            preparing = true; fileError = null
            var file: File? = null
            try {
                val prepared = withContext(Dispatchers.IO) {
                    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    } ?: error("无法读取文件名")
                    val kind = when (name.substringAfterLast('.').lowercase()) {
                        "wav", "flac", "aiff", "aif" -> "impulseResponse"
                        "txt", "apo" -> "correctionProfile"
                        "vstpreset" -> "vst3Preset"
                        "vststate", "bin" -> "vst3State"
                        "tedsp" -> "profile"
                        else -> error("请选择音效资料或 .tedsp 配置包")
                    }
                    val temporary = File.createTempFile("server-dsp-", ".upload", context.cacheDir).also { file = it }
                    val max = currentState.library?.maxUploadBytes ?: error("请先读取电脑资料")
                    context.contentResolver.openInputStream(uri)?.use { input -> temporary.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024); var total = 0L
                        while (true) { val count = input.read(buffer); if (count < 0) break
                            total += count; require(total <= max) { "文件超过 ${max / 1024 / 1024} MB" }; output.write(buffer, 0, count) }
                        require(total > 0) { "文件为空" }
                    } } ?: error("无法读取文件")
                    Triple(kind, name, temporary)
                }
                currentActions.upload(pickerTicket, prepared.first, prepared.second, prepared.third); file = null
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { fileError = error.message }
            finally { file?.delete(); preparing = false }
        }
    }
    val savePackage = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val ticket = saveTicket; saveTicket = null
        if (uri != null && ticket != null && ticket.first == currentState.sessionId && ticket.second == currentState.exportedFile) coroutine.launch {
            preparing = true; fileError = null
            try {
                withContext(Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("无法写入所选位置")
                    output.use { target -> ticket.second.inputStream().use { it.copyTo(target) } }
                }
                currentActions.releaseExport()
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { fileError = "保存失败：${error.message}，可以重新选择位置" }
            finally { preparing = false }
        }
    }
    ModalBottomSheet(onDismissRequest = { if (!locked) actions.close() }, sheetState = sheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 1f), contentColor = MaterialTheme.colorScheme.onSurface) {
        com.twilightecho.android.feature.player.zones.SheetSystemBars()
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.94f).imePadding().padding(horizontal = 20.dp).testTag("desktopDspLibrary")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("资料与插件", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                TextButton(actions.close, enabled = !locked) { Text("关闭") }
            }
            Text(state.subject, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
            state.progress?.let { Text("传输 ${(it * 100).toInt()}%", style = MaterialTheme.typography.labelMedium) }
            (fileError ?: state.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                listOf("资料", "场景", "插件", "配置包").forEachIndexed { index, label ->
                    FilterChip(tab == index, { tab = index }, label = { Text(label) }, modifier = Modifier.padding(end = 8.dp))
                }
                }
                TextButton(actions.refresh, enabled = !busy) { Text("刷新") }
            }
            if (library != null) LazyColumn(Modifier.weight(1f).testTag("dspLibraryItems"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (tab == 0) {
                    item {
                        Button({ pickerTicket = state.sessionId; picker.launch(arrayOf("*/*")) }, enabled = editable, modifier = Modifier.fillMaxWidth().testTag("dspImport")) { Text("从手机导入资料") }
                        Text("IR · 校正 EQ · VST3 预设与状态，单个文件最多 ${library.maxUploadBytes / 1024 / 1024} MB。",
                            Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (library.assets.total == 0) item { Text("尚无资料。导入后可绑定到此声区的自定义场景。", Modifier.padding(vertical = 24.dp)) }
                    items(library.assets.items, key = { it.id }) { asset ->
                        ListItem(headlineContent = { Text(asset.name) }, supportingContent = {
                            Text("${assetLabel(asset.kind)} · ${fileSize(asset.byteSize)}${if (asset.referenceCount > 0) " · 已引用" else ""}") },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                            trailingContent = { Row {
                                if (asset.kind == "impulseResponse" || asset.kind == "correctionProfile")
                                    TextButton({ bind = asset }, enabled = editable) { Text("绑定") }
                                TextButton({ confirmation = DspConfirmation("删除「${asset.name}」？", "仅删除此声区的资料副本。",
                                    dspEdit("removeAsset") { put("id", asset.id) }) }, enabled = editable && asset.referenceCount == 0) { Text("删除") }
                            } })
                    }
                    if (library.assets.items.size < library.assets.total) item { TextButton(actions.moreAssets, enabled = !busy) { Text("更多资料") } }
                }
                if (tab == 1) {
                    item { OutlinedButton({ creating = true }, enabled = editable, modifier = Modifier.fillMaxWidth()) { Text("新建场景") } }
                    items(library.scenes.filter { it.id != "default" || it.nodes.any { node -> node.type == "vst3Plugin" } }, key = { it.id }) { row ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Text(row.name + if (row.active) " · 当前" else "", style = MaterialTheme.typography.titleMedium)
                            if (row.id != "default") Row {
                                TextButton({ confirmation = DspConfirmation("应用「${row.name}」？", "在「${state.subject}」固定此场景，并开启所需的卷积／均衡器模块。若 DSD 不兼容，允许转为 PCM；DSP 总开关仍由处理面板控制。",
                                    dspEdit("applyScene") { put("sceneId", row.id); put("confirmDsdPcmFallback", true) }) }, enabled = editable) { Text("应用") }
                                TextButton({ confirmation = DspConfirmation("删除「${row.name}」？", "此场景会移除；当前使用它时将重新匹配场景。",
                                    dspEdit("removeScene") { put("sceneId", row.id) }) }, enabled = editable) { Text("删除场景") }
                            }
                            row.nodes.forEach { node ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(nodeLabel(node.type) + node.assetId?.let { id -> library.assets.items.find { it.id == id }?.name?.let { " · $it" } }.orEmpty(), Modifier.weight(1f))
                                    if (node.type == "vst3Plugin") TextButton({ actions.parameters(row.id, node.id) }, enabled = editable) { Text("参数") }
                                    TextButton({ confirmation = DspConfirmation("移除${nodeLabel(node.type)}？", "保存后解除此节点的资料引用。",
                                        dspEdit("removeNode") { put("sceneId", row.id); put("nodeId", node.id) }) }, enabled = editable && row.id != "default") { Text("移除") }
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                    if (library.scenes.none { it.id != "default" }) item { Text("新建场景后，在资料或插件页绑定内容，再点应用。", Modifier.padding(vertical = 20.dp)) }
                }
                if (tab == 2) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("启用 VST3", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            Switch(library.vst3Enabled, { confirmation = DspConfirmation(if (it) "启用 VST3？" else "旁路 VST3？", "影响此声区的插件处理。同步组会先暂停以重新协调。",
                                dspEdit("vst3Enabled") { put("enabled", it) }) }, enabled = editable && library.helpers.platformSupported)
                        }
                        Text("${library.searchRootCount} 个已授权扫描目录", style = MaterialTheme.typography.bodyMedium)
                        if (!library.helpers.scannerPresent || !library.helpers.hostPresent)
                            Text("Server 缺少扫描器或插件宿主，请使用含 VST3 组件的安装包。", color = MaterialTheme.colorScheme.error)
                        if (library.searchRootCount == 0) Text("请在 Server 的 server.json 中设置 vst3Roots，并重启服务。", style = MaterialTheme.typography.bodyMedium)
                        if (library.scanning) Text("正在扫描，稍后刷新查看结果。同步组保持暂停。", color = MaterialTheme.colorScheme.primary)
                        if (library.scanFailed) Text("扫描未完成，请检查 Server 日志后重新扫描。", color = MaterialTheme.colorScheme.error)
                        Button({ actions.edit(dspEdit("scan")) }, enabled = editable && library.vst3Enabled && library.searchRootCount > 0 && library.helpers.scannerPresent,
                            modifier = Modifier.fillMaxWidth().testTag("dspScan")) { Text("扫描已授权目录") }
                    }
                    items(library.plugins.items, key = { it.id }) { row ->
                        ListItem(headlineContent = { Text(row.name) }, supportingContent = { Text("${row.vendor} ${row.version} · ${pluginStatus(row.status)}") },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                            trailingContent = {
                                if (row.status == "available") TextButton({ plugin = row; preset = null }, enabled = editable && library.vst3Enabled && library.helpers.hostPresent) { Text("添加") }
                                if (row.status == "quarantined") TextButton({ confirmation = DspConfirmation("重新扫描「${row.name}」？", "解除崩溃隔离并重新检查插件。同步组保持暂停。",
                                    dspEdit("clearQuarantine") { put("id", row.id) }) }, enabled = editable) { Text("重扫") }
                            })
                    }
                    if (library.plugins.items.size < library.plugins.total) item { TextButton(actions.morePlugins, enabled = !busy) { Text("更多插件") } }
                }
                if (tab == 3) item {
                    Text("保存或迁入整个声区的 DSP 场景和资料。", Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyLarge)
                    Button({ pickerTicket = state.sessionId; picker.launch(arrayOf("*/*")) }, enabled = editable,
                        modifier = Modifier.fillMaxWidth().testTag("dspProfileImport")) { Text("导入 .tedsp 配置包") }
                    Text("导入前预览内容；确认后新增场景，应用时再改变声音。", Modifier.padding(vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton({ actions.exportProfile(context.cacheDir) }, enabled = editable && state.exportedFile == null,
                        modifier = Modifier.fillMaxWidth().testTag("dspProfileExport")) { Text("导出此声区配置包") }
                    state.exportedFile?.let { file ->
                        Text("${state.exportName} · ${fileSize(file.length())}", Modifier.padding(vertical = 12.dp))
                        Button({ saveTicket = state.sessionId to file; savePackage.launch(state.exportName) }, enabled = !busy,
                            modifier = Modifier.fillMaxWidth().testTag("dspProfileSave")) { Text("保存到文件…") }
                    }
                }
                item { Spacer(Modifier.height(28.dp)) }
            }
        }
    }
    if (creating) AlertDialog(onDismissRequest = { creating = false }, title = { Text("新建 DSP 场景") },
        text = { OutlinedTextField(sceneName, { sceneName = it.take(80) }, singleLine = true, label = { Text("场景名称") }, modifier = Modifier.testTag("dspSceneName")) },
        confirmButton = { TextButton({ actions.edit(dspEdit("createScene") { put("name", sceneName.trim()) }); creating = false; sceneName = "" }, enabled = editable && sceneName.isNotBlank()) { Text("创建") } },
        dismissButton = { TextButton({ creating = false }) { Text("取消") } })
    if (bind != null || plugin != null) AlertDialog(onDismissRequest = { bind = null; plugin = null }, title = { Text(bind?.name ?: plugin!!.name) }, text = {
        Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
            Text("选择「${state.subject}」的场景")
            library?.scenes?.filter { it.id != "default" }?.forEach { row -> TextButton({ selectedScene = row.id }) { Text((if (selectedScene == row.id) "✓ " else "") + row.name) } }
            if (library?.scenes?.none { it.id != "default" } != false) Text("请先在场景页新建场景。")
            if (plugin != null) {
                Text("插件预设／状态")
                TextButton({ preset = null }) { Text((if (preset == null) "✓ " else "") + "默认参数") }
                library?.assets?.items?.filter { it.kind == "vst3Preset" || it.kind == "vst3State" }?.forEach { asset ->
                    TextButton({ preset = asset.id }) { Text((if (preset == asset.id) "✓ " else "") + asset.name) }
                }
            }
        }
    }, confirmButton = { TextButton({
        val target = scene ?: return@TextButton
        val asset = bind
        val edit = if (asset != null) dspEdit(if (asset.kind == "impulseResponse") "bindImpulse" else "bindCorrection") {
            put("sceneId", target.id); put("assetId", asset.id)
        } else dspEdit("addVst3") { put("sceneId", target.id); put("catalogId", plugin!!.id); put("stateAssetId", preset?.let(::JsonPrimitive) ?: JsonNull) }
        confirmation = DspConfirmation("保存到「${target.name}」？", "IR 或校正 EQ 会替换场景中同类节点。编辑当前场景会立即更新处理。", edit)
        bind = null; plugin = null
    }, enabled = editable && scene != null) { Text("保存到场景") } }, dismissButton = { TextButton({ bind = null; plugin = null }) { Text("取消") } })
    confirmation?.let { pending -> AlertDialog(onDismissRequest = { confirmation = null }, title = { Text(pending.title) }, text = { Text(pending.explanation) },
        confirmButton = { TextButton({ actions.edit(pending.edit); confirmation = null }, enabled = editable) { Text("确认") } },
        dismissButton = { TextButton({ confirmation = null }) { Text("取消") } }) }
    state.preview?.let { preview -> AlertDialog(onDismissRequest = { if (!busy) actions.cancelPreview() },
        title = { Text(preview.name) }, text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${preview.scenes.size} 个场景 · ${preview.assetCount} 份资料 · ${fileSize(preview.assetBytes)}")
                preview.scenes.forEach { Text("${it.name} · ${it.nodeCount} 个节点") }
                Text("加入「${state.subject}」后保持未启用。现有场景和当前选择保留。")
                preview.issues.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton(actions.importProfile, enabled = editable && preview.issues.isEmpty(), modifier = Modifier.testTag("dspProfileConfirm")) { Text("确认导入") } },
        dismissButton = { TextButton(actions.cancelPreview, enabled = !busy) { Text("取消") } }) }
    state.parameters?.let { page -> DspParametersDialog(page, editable, state.error, actions) }
}

@Composable
private fun DspParametersDialog(page: DesktopParameterPage, editable: Boolean, error: String?, actions: DesktopDspLibraryActions) {
    AlertDialog(onDismissRequest = actions.closeParameters, title = { Text(page.name) }, text = {
        Column {
            Text("参数以 0–100% 标准化值显示。未读取的预设值可显式覆盖。", style = MaterialTheme.typography.bodyMedium)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            LazyColumn(Modifier.heightIn(max = 440.dp).testTag("dspParameterItems")) {
                items(page.items, key = { it.id }) { parameter ->
                    var changing by remember(page.revision, parameter.id) { mutableStateOf(false) }
                    var value by remember(page.revision, parameter.id) { mutableFloatStateOf((parameter.value ?: parameter.defaultNormalizedValue).toFloat()) }
                    Column(Modifier.padding(vertical = 12.dp)) {
                        Text(parameter.title, style = MaterialTheme.typography.titleMedium)
                        Text(parameter.value?.let { "%.2f%%".format(it * 100) } ?: "预设值（未读取）", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!parameter.editable) Text("只读", style = MaterialTheme.typography.labelMedium)
                        else {
                            if (parameter.value != null || changing) Slider(value, { value = it; changing = true }, enabled = editable,
                                steps = if (parameter.stepCount in 2..1000) parameter.stepCount - 1 else 0,
                                modifier = Modifier.testTag("dspParameter-${parameter.id}"))
                            Row {
                                if (parameter.value == null && !changing) TextButton({ changing = true }, enabled = editable) { Text("设置覆盖值") }
                                if (changing) TextButton({ actions.parameter(parameter.id, value.toDouble()) }, enabled = editable) { Text("保存 ${"%.1f".format(value * 100)}%") }
                                if (parameter.overridden) TextButton({ actions.parameter(parameter.id, null) }, enabled = editable) { Text("移除覆盖") }
                            }
                        }
                        HorizontalDivider()
                    }
                }
                if (page.items.size < page.total) item { TextButton(actions.moreParameters, enabled = editable) { Text("更多参数") } }
            }
        }
    }, confirmButton = { TextButton(actions.closeParameters, enabled = editable || error != null) { Text("关闭") } })
}
private fun assetLabel(kind: String) = when (kind) { "impulseResponse" -> "脉冲响应"; "correctionProfile" -> "校正 EQ"; "vst3Preset" -> "VST3 预设"; else -> "VST3 状态" }
private fun fileSize(bytes: Long) = if (bytes >= 1024 * 1024) "%.1f MB".format(bytes / 1048576.0) else "${(bytes + 1023) / 1024} KB"
private fun nodeLabel(type: String) = when (type) { "convolver" -> "卷积"; "equalizer" -> "均衡器"; "vst3Plugin" -> "VST3"; "meter" -> "计量"; else -> type }
private fun pluginStatus(status: String) = when (status) { "available" -> "可用"; "quarantined" -> "已隔离"; "failed" -> "扫描失败"; else -> "不兼容" }
