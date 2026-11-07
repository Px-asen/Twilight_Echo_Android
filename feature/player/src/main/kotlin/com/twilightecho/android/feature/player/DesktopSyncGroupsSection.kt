package com.twilightecho.android.feature.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.twilightecho.android.coreclient.DesktopSyncGroup
import com.twilightecho.android.coreclient.DesktopSyncGroupCreate
import com.twilightecho.android.coreclient.DesktopSyncSelection
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

@Composable
internal fun DesktopSyncGroupsSection(state: PlayerUiState, actions: PlayerActions) {
    val generation = state.desktopAudio?.targetGeneration
    var creating by remember(generation) { mutableStateOf(false) }
    var removing by remember(generation) { mutableStateOf<DesktopSyncGroup?>(null) }
    var name by remember(generation) { mutableStateOf("") }
    var selected by remember(generation) { mutableStateOf(emptySet<String>()) }
    var sourceId by remember(generation) { mutableStateOf("") }
    val delays = remember(generation) { mutableStateMapOf<String, String>() }
    val available = state.desktopZoneSessions.filter { it.groupId == null }
    val sources = available.filter { it.id in selected && it.queue?.occurrenceId != null }
    val source = sources.singleOrNull { it.id == sourceId }
    Text("同步组", style = MaterialTheme.typography.titleMedium)
    state.desktopGroupsError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    state.desktopSyncGroups?.groups?.forEach { group ->
        Text(group.name, style = MaterialTheme.typography.titleSmall)
        Text(group.track?.title ?: "组队列为空", style = MaterialTheme.typography.bodyMedium)
        group.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        group.coordination?.get("estimatedSkewMs")?.jsonPrimitive?.doubleOrNull?.let { skew ->
            Text("组间估计偏差 ${"%.2f".format(skew)} ms", style = MaterialTheme.typography.bodyMedium)
        }
        group.members.forEach { member ->
            val output = state.desktopZoneSessions.singleOrNull { it.id == member.zoneId }
            OutlinedButton(onClick = { actions.desktopZone(member.zoneId) }, enabled = !state.desktopAudioBusy && output != null,
                modifier = Modifier.fillMaxWidth()) {
                Text("${output?.name ?: member.zoneId} · ${if (output?.output?.connected == true) "已连接" else "离线"} · ${member.downstreamDelayMs} ms")
            }
        }
        TextButton(onClick = { removing = group }, enabled = !state.desktopAudioBusy && generation != null) { Text("解除编组…") }
        DesktopGroupAutoMixSection(group, enabled = generation != null, busy = state.desktopAudioBusy) {
            actions.desktopGroupAutoMix(group, it)
        }
    }
    if (available.size >= 2 && state.desktopSyncGroups != null && state.desktopGroupsError == null) {
        OutlinedButton(onClick = { creating = true }, enabled = !state.desktopAudioBusy && generation != null) { Text("建立同步组") }
    }
    if (creating) AlertDialog(onDismissRequest = { creating = false }, title = { Text("建立同步组") },
        text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("选择至少两个输出，采用其中一份队列。编组后暂停待播，原独立队列保留。")
            OutlinedTextField(value = name, onValueChange = { name = it.take(80) }, label = { Text("组名") }, singleLine = true)
            available.forEach { output ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = output.id in selected, onCheckedChange = { checked ->
                        selected = if (checked) selected + output.id else selected - output.id
                        if (sourceId !in selected) sourceId = ""
                    })
                    Text(output.name, Modifier.weight(1f))
                }
                if (output.id in selected) OutlinedTextField(value = delays[output.id] ?: "0", onValueChange = { delays[output.id] = it },
                    label = { Text("${output.name}下游延迟（ms）") }, singleLine = true)
            }
            Text("采用哪份队列", style = MaterialTheme.typography.titleSmall)
            sources.forEach { output -> Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = sourceId == output.id, onClick = { sourceId = output.id })
                Text("${output.name} · ${output.track?.title.orEmpty()}", Modifier.weight(1f))
            } }
            Text("下游延迟 0–250 ms；0 表示 DAC 模拟输出尚未校准。播放前须在电脑端连接支持时钟自适应的 Bridge。")
        } }, confirmButton = { TextButton(enabled = !state.desktopAudioBusy && generation != null && name.isNotBlank() && source != null &&
            selected.size >= 2 && available.count { it.id in selected } == selected.size &&
            selected.all { (delays[it] ?: "0").toDoubleOrNull()?.let { ms -> ms.isFinite() && ms in 0.0..250.0 } == true },
            onClick = {
                actions.desktopCreateGroup(DesktopSyncGroupCreate(state.desktopSyncGroups!!.revision, name, source!!,
                    available.filter { it.id in selected }.map { DesktopSyncSelection(it, (delays[it.id] ?: "0").toDouble()) }, generation!!))
                creating = false
            }) { Text("建立并暂停") } }, dismissButton = { TextButton(onClick = { creating = false }) { Text("取消") } })
    removing?.let { group -> AlertDialog(onDismissRequest = { removing = null }, title = { Text("解除 ${group.name}？") },
        text = { Text("全部输出停止，回到各自原独立队列并保持暂停，不采用组队列。") },
        confirmButton = { TextButton(enabled = !state.desktopAudioBusy && generation != null, onClick = {
            actions.desktopRemoveGroup(group); removing = null
        }) { Text("停止并解除") } }, dismissButton = { TextButton(onClick = { removing = null }) { Text("取消") } }) }
}
