package com.twilightecho.android.feature.player

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.twilightecho.android.coreclient.*
import kotlinx.coroutines.*
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DesktopPluginsSheet(state: DesktopPluginsState, actions: DesktopPluginsActions) {
    if (!state.open) return
    val context = LocalContext.current.applicationContext
    val coroutine = rememberCoroutineScope()
    var preparing by remember { mutableStateOf(false) }
    var ticket by remember { mutableStateOf("") }
    var fileError by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<Pair<DesktopManagedPlugin, String>?>(null) }
    var removeData by remember(pending) { mutableStateOf(false) }
    val latest by rememberUpdatedState(state)
    val latestActions by rememberUpdatedState(actions)
    val busy = state.writing || state.loading || preparing
    val enabled = !busy && !state.needsRefresh && state.page?.canManage == true
    val locked by rememberUpdatedState(state.writing || preparing)
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !locked || it != SheetValue.Hidden })
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) coroutine.launch {
            preparing = true; fileError = null
            var file: File? = null
            try {
                val result = withContext(Dispatchers.IO) {
                    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    } ?: error("无法读取文件名")
                    require(name.endsWith(".tep", true)) { "请选择 .tep 扩展包" }
                    val maximum = latest.page?.maxUploadBytes ?: error("请先读取扩展列表")
                    val temporary = File.createTempFile("server-plugin-", ".tep", context.cacheDir).also { file = it }
                    context.contentResolver.openInputStream(uri)?.use { input -> temporary.outputStream().use { output ->
                        val bytes = ByteArray(64 * 1024); var total = 0L
                        while (true) { val count = input.read(bytes); if (count < 0) break
                            total += count; require(total <= maximum) { "安装包超过 50 MiB" }; output.write(bytes, 0, count) }
                        require(total > 0) { "安装包为空" }
                    } } ?: error("无法读取安装包")
                    name to temporary
                }
                latestActions.upload(ticket, result.first, result.second); file = null
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { fileError = error.message }
            finally { file?.delete(); preparing = false }
        }
    }
    ModalBottomSheet(onDismissRequest = { if (!locked) actions.close() }, sheetState = sheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 1f),
        contentColor = MaterialTheme.colorScheme.onSurface) {
        com.twilightecho.android.feature.player.zones.SheetSystemBars()
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.94f).padding(horizontal = 20.dp).testTag("desktopPlugins")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Server 扩展", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                TextButton(actions.close, enabled = !locked) { Text("关闭") }
            }
            Text(state.host, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
            Text("安装在这台电脑，供其 Core 使用。", Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.progress?.let { Text("上传 ${(it * 100).toInt()}%") }
            (fileError ?: state.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ ticket = state.sessionId; picker.launch(arrayOf("*/*")) }, enabled = enabled, modifier = Modifier.testTag("serverPluginUpload")) { Text("选择 .tep 安装包") }
                TextButton(actions.refresh, enabled = !busy) { Text("刷新") }
            }
            if (state.page?.canManage == false) Text("远程管理尚未开启。请在 Server 的 server.json 中设置 remotePluginManagement 为 true，然后重启。",
                Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium)
            LazyColumn(Modifier.weight(1f).testTag("serverPluginItems"), contentPadding = PaddingValues(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (state.page?.total == 0) item { Text("尚未安装扩展。") }
                items(state.page?.items.orEmpty(), key = { it.id }) { plugin ->
                    Column {
                        Text(plugin.name, style = MaterialTheme.typography.titleMedium)
                        Text("${plugin.version} · ${plugin.author} · ${pluginStatusText(plugin.status)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (plugin.description.isNotBlank()) Text(plugin.description, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium)
                        plugin.issues.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
                        Row {
                            TextButton({ pending = plugin to if (plugin.enabled) "disable" else "enable" }, enabled = enabled && (plugin.enabled || plugin.issues.isEmpty())) {
                                Text(if (plugin.enabled) "停用" else "启用")
                            }
                            if (!plugin.builtIn) TextButton({ pending = plugin to "uninstall" }, enabled = enabled) { Text("卸载") }
                            else Text("内置", Modifier.padding(12.dp), style = MaterialTheme.typography.labelMedium)
                        }
                        HorizontalDivider()
                    }
                }
                if ((state.page?.items?.size ?: 0) < (state.page?.total ?: 0)) item { TextButton(actions.more, enabled = !busy) { Text("更多扩展") } }
            }
        }
    }
    state.preview?.let { preview ->
        var trusted by remember(preview.previewId) { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { if (!busy) actions.cancelPreview() }, title = { Text(if (preview.previousVersion == null) "安装 ${preview.plugin.name}" else "更新 ${preview.plugin.name}") },
            text = { Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${preview.previousVersion?.let { "$it → " }.orEmpty()}${preview.plugin.version} · ${preview.plugin.author}")
                Text(preview.plugin.description)
                Text("声明权限：${preview.plugin.permissions.joinToString("、").ifEmpty { "无" }}")
                if (preview.plugin.dependencies.isNotEmpty()) Text("依赖：${preview.plugin.dependencies.entries.joinToString { "${it.key} ${it.value}" }}")
                Text("此包来自手机本地文件，未验证发布者签名。确认后会执行安装验证代码，权限等同于 Server 账号。")
                Text(if (preview.previouslyEnabled) "更新完成后继续启用；失败时恢复旧版本。" else "安装完成后保持停用，可在列表中启用。")
                preview.issues.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(trusted, { trusted = it }, enabled = !busy, modifier = Modifier.testTag("serverPluginTrust"))
                    Text("我信任此扩展的来源", style = MaterialTheme.typography.bodyMedium)
                }
            } }, confirmButton = { TextButton(actions.install, enabled = enabled && trusted && preview.issues.isEmpty(), modifier = Modifier.testTag("serverPluginConfirm")) { Text("确认安装") } },
            dismissButton = { TextButton(actions.cancelPreview, enabled = !busy) { Text("取消") } })
    }
    pending?.let { (plugin, action) ->
        val verb = when (action) { "enable" -> "启用"; "disable" -> "停用"; else -> "卸载" }
        AlertDialog(onDismissRequest = { if (!busy) pending = null }, title = { Text("$verb「${plugin.name}」？") }, text = {
            Column {
                Text(if (action == "enable") "扩展将在「${state.host}」运行。" else "使用此扩展的音源或功能将不可用。")
                if (action == "uninstall") Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(removeData, { removeData = it }, modifier = Modifier.testTag("serverPluginRemoveData"))
                    Text("同时删除设置和登录数据")
                }
            }
        }, confirmButton = { TextButton({ actions.edit(plugin, action, removeData); pending = null }, enabled = enabled) { Text("确认$verb") } },
            dismissButton = { TextButton({ pending = null }) { Text("取消") } })
    }
}
private fun pluginStatusText(status: String) = when (status) { "enabled" -> "已启用"; "disabled", "installed" -> "已停用"; "failed" -> "启动失败"; "invalid" -> "不兼容"; else -> status }
