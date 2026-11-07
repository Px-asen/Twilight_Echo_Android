package com.twilightecho.android.feature.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.twilightecho.android.playbackhost.OfflineDownload
import com.twilightecho.android.playbackhost.OfflineDownloadStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OfflineDownloadsSheet(state: PlayerUiState, actions: PlayerActions) {
    val data = state.offlineDownloads
    val policy = state.preferences.mobileNetwork
    var removing by remember { mutableStateOf<OfflineDownload?>(null) }
    var settings by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = { actions.offlineDownloadsOpen(false) }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 1f), contentColor = MaterialTheme.colorScheme.onSurface) {
        com.twilightecho.android.feature.player.zones.SheetSystemBars()
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).padding(horizontal = 20.dp).testTag("offlineDownloads")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("离线音乐", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                TextButton({ actions.offlineDownloadsOpen(false) }) { Text("关闭") }
            }
            val used = data.items.sumOf { it.bytes }
            Text("${offlineBytes(used)} / ${offlineBytes(policy.offlineQuotaBytes)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton({ settings = !settings }, modifier = Modifier.testTag("offlineNetworkSettings")) { Text("网络与存储") }
            if (settings) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("允许计费网络下载", Modifier.weight(1f))
                    Switch(policy.allowMeteredDownloads, { actions.mobileNetworkPreferences(policy.copy(allowMeteredDownloads = it)) }, Modifier.testTag("meteredDownloads"))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("计费网络播放电脑音乐", Modifier.weight(1f))
                    Switch(policy.allowMeteredStreaming, { actions.mobileNetworkPreferences(policy.copy(allowMeteredStreaming = it)) }, Modifier.testTag("meteredStreaming"))
                }
                Text("关闭后仅在非计费网络传输；已下载音乐随时可播。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1L, 5L, 10L, 20L).forEach { size ->
                        FilterChip(policy.offlineQuotaBytes == size * 1024 * 1024 * 1024,
                            { actions.mobileNetworkPreferences(policy.copy(offlineQuotaBytes = size * 1024 * 1024 * 1024)) }, label = { Text("$size GiB") })
                    }
                }
            }
            data.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.errorBanner?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            LazyColumn(Modifier.weight(1f).testTag("offlineDownloadItems"), contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                if (data.items.isEmpty()) item {
                    Text("长按电脑曲库中的歌曲，选择“下载到手机”。", Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(data.items, key = { it.id }) { entry ->
                    Column {
                        Text(entry.track.title, style = MaterialTheme.typography.titleMedium)
                        Text("${entry.track.artist} · ${entry.host}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val status = when (entry.status) {
                            OfflineDownloadStatus.QUEUED -> "等待下载"
                            OfflineDownloadStatus.DOWNLOADING -> "正在下载"
                            OfflineDownloadStatus.PAUSED -> "已暂停"
                            OfflineDownloadStatus.READY -> "可离线播放"
                            OfflineDownloadStatus.FAILED -> "下载未完成"
                        }
                        Text("$status · ${offlineBytes(entry.bytes)}${if (entry.size > 0) " / ${offlineBytes(entry.size)}" else ""}", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall)
                        if (entry.status == OfflineDownloadStatus.DOWNLOADING && entry.size > 0)
                            LinearProgressIndicator(progress = { entry.bytes.toFloat() / entry.size }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                        entry.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                        Row {
                            when (entry.status) {
                                OfflineDownloadStatus.READY -> TextButton({ actions.offlineDownloadAction(entry.id, "play") }) { Text("播放") }
                                OfflineDownloadStatus.QUEUED, OfflineDownloadStatus.DOWNLOADING -> TextButton({ actions.offlineDownloadAction(entry.id, "pause") }) { Text("暂停") }
                                else -> TextButton({ actions.offlineDownloadAction(entry.id, "resume") }) { Text("继续") }
                            }
                            TextButton({ removing = entry }) { Text("移除") }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
    removing?.let { entry -> AlertDialog(onDismissRequest = { removing = null }, title = { Text("移除下载？") },
        text = { Text("删除手机上的「${entry.track.title}」下载文件及任务。电脑原文件保留。") },
        confirmButton = { TextButton({ actions.offlineDownloadAction(entry.id, "remove"); removing = null }) { Text("移除") } },
        dismissButton = { TextButton({ removing = null }) { Text("取消") } }) }
}
private fun offlineBytes(bytes: Long): String = if (bytes >= 1024L * 1024 * 1024) "%.1f GiB".format(bytes / (1024.0 * 1024 * 1024))
    else "%.1f MiB".format(bytes / (1024.0 * 1024))
