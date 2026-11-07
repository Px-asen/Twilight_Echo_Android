package com.twilightecho.android.feature.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.twilightecho.android.coreclient.DesktopDspScene
import com.twilightecho.android.coreclient.DesktopSessionHandoff
import com.twilightecho.android.coreclient.DesktopZoneSession
import com.twilightecho.android.coreclient.desktopClockObservation
import com.twilightecho.android.coreclient.desktopAdaptiveObservation
import kotlinx.serialization.json.*
import kotlin.math.roundToInt

private fun JsonObject.obj(key: String) = this[key] as? JsonObject ?: JsonObject(emptyMap())
private fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.doubleOrNull ?: 0.0
private fun JsonObject.audioFlag(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull == true
private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
private fun JsonObject.format(): String = if (number("sampleRate") > 0)
    listOfNotNull("${number("sampleRate") / 1000} kHz",
        number("bitDepth").takeIf { it > 0 }?.let { "${it.toInt()} bit" },
        number("channels").takeIf { it > 0 }?.let { "${it.toInt()} ch" }).joinToString(" · ")
    else "尚未确认"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DesktopAudioSheet(state: PlayerUiState, actions: PlayerActions) {
    var pendingScene by remember { mutableStateOf<DesktopDspScene?>(null) }
    var confirmAutomaticScene by remember { mutableStateOf(false) }
    var pendingHandoff by remember { mutableStateOf<DesktopSessionHandoff?>(null) }
    LaunchedEffect(state.desktopAudio?.zone?.zoneId) {
        pendingScene = null; confirmAutomaticScene = false; pendingHandoff = null
    }
    ModalBottomSheet(onDismissRequest = { actions.desktopAudioOpen(false) }) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f).verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("电脑输出与音频链", style = MaterialTheme.typography.headlineSmall)
            Text("手机只负责控制。解码和 DSP 在所选电脑的 Core 执行。", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.desktopAudioError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.desktopAudioNotice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            val audio = state.desktopAudio
            if (state.desktopZoneSessions.size > 1) {
                Text("独立播放会话", style = MaterialTheme.typography.titleMedium)
                state.desktopZoneSessions.forEach { session ->
                    OutlinedButton(onClick = { actions.desktopZone(session.id) }, enabled = !state.desktopAudioBusy,
                        modifier = Modifier.fillMaxWidth()) {
                        Text((if (audio?.zone?.zoneId == session.id) "✓ " else "") + session.name + (if (session.groupId != null) " · 同步组成员" else "") +
                            session.track?.title?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty())
                    }
                }
                Text("这里的选择只切换此面板的控制会话；主播放页仍显示主会话。", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.desktopZonesError?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
            DesktopSyncGroupsSection(state, actions)
            if (audio == null && state.desktopAudioError == null) CircularProgressIndicator()
            if (audio != null) {
                OutlinedButton({ actions.openDesktopDspLibrary(audio.zone.zoneId, audio.session?.name ?: "此电脑主会话") }, enabled = !state.desktopAudioBusy) {
                    Text("管理资料与插件")
                }
                val shared = audio.group
                val current = shared?.let { DesktopZoneSession(it.id, it.name, it.revision, it.track, it.playback, queue = it.queue) } ?: audio.session
                if (shared != null) {
                    Text("同步组 · ${shared.name}", style = MaterialTheme.typography.titleMedium)
                    Text("以下播放控制作用于全部成员。DSP 和信号链仅查看所选输出。", style = MaterialTheme.typography.bodyMedium)
                    shared.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                current?.let { session ->
                    Text(session.track?.title ?: session.name, style = MaterialTheme.typography.titleLarge)
                    Text("${session.playback?.state ?: "stopped"} · ${session.playback?.position?.toInt() ?: 0} 秒",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { actions.desktopZoneTransport("play") }, enabled = !state.desktopAudioBusy && audio.zone.connected) { Text(if (shared != null) "一起播放" else "播放") }
                        OutlinedButton(onClick = { actions.desktopZoneTransport("pause") }) { Text(if (shared != null) "全部暂停" else "暂停") }
                        OutlinedButton(onClick = { actions.desktopZoneTransport("stop") }) { Text(if (shared != null) "全部停止" else "停止") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { actions.desktopZoneTransport("previous") }, enabled = !state.desktopAudioBusy && audio.zone.connected) { Text("上一首") }
                        OutlinedButton(onClick = { actions.desktopZoneTransport("next") }, enabled = !state.desktopAudioBusy && audio.zone.connected) { Text("下一首") }
                    }
                    var volume by remember(audio.zone.zoneId, session.playback?.volume) {
                        mutableFloatStateOf((session.playback?.volume ?: 0.5).toFloat().coerceIn(0f, 1f))
                    }
                    Text("${if (shared != null) "组" else "此 Zone"}音量 ${(volume * 100).roundToInt()}%")
                    Slider(value = volume, onValueChange = { volume = it }, enabled = !state.desktopAudioBusy,
                        onValueChangeFinished = { actions.desktopZoneValue("volume", volume.toDouble()) })
                    val duration = session.playback?.duration?.takeIf { it.isFinite() && it > 0 }?.toFloat()
                    if (duration != null) {
                        var position by remember(audio.zone.zoneId, session.queue?.occurrenceId) { mutableFloatStateOf(0f) }
                        var seeking by remember(audio.zone.zoneId, session.queue?.occurrenceId) { mutableStateOf(false) }
                        LaunchedEffect(session.playback?.position) {
                            if (!seeking) position = (session.playback?.position ?: 0.0).toFloat().coerceIn(0f, duration)
                        }
                        Text("进度 ${position.toInt()} / ${duration.toInt()} 秒")
                        Slider(value = position.coerceIn(0f, duration), valueRange = 0f..duration,
                            enabled = !state.desktopAudioBusy && audio.zone.connected,
                            onValueChange = { seeking = true; position = it }, onValueChangeFinished = {
                                actions.desktopZoneValue("seek", position.toDouble()); seeking = false
                            })
                    }
                    Text("输出桥授权在电脑端管理。组内 DSP 延迟改变时，全部成员会共同重缓冲。", style = MaterialTheme.typography.bodyMedium)
                }
                val source = state.desktopZoneSessions.singleOrNull { it.id == audio.zone.zoneId }
                if (source?.groupId == null && source?.queue?.occurrenceId != null && state.desktopZoneSessions.size > 1 && state.desktopZonesError == null) {
                    Text("会话接力", style = MaterialTheme.typography.titleMedium)
                    state.desktopZoneSessions.filter { it.id != source.id && it.groupId == null }.forEach { target ->
                        OutlinedButton(onClick = { pendingHandoff = DesktopSessionHandoff(source, target, audio.targetGeneration) },
                            enabled = !state.desktopAudioBusy, modifier = Modifier.fillMaxWidth()) { Text("接力到 ${target.name}") }
                    }
                }
                if (audio.zone.outputs.isNotEmpty()) Text("播放输出", style = MaterialTheme.typography.titleMedium)
                audio.zone.outputs.forEach { output ->
                    OutlinedButton(onClick = { actions.desktopOutput(output.id) },
                        enabled = !state.desktopAudioBusy && output.available &&
                            (shared == null || output.id != "local") &&
                            (audio.zone.selectedOutputId != output.id || (output.id == "bridge" && !audio.zone.connected)),
                        modifier = Modifier.fillMaxWidth()) {
                        Text((if (audio.zone.selectedOutputId == output.id) "✓ " else "") + output.name +
                            if (output.id == "bridge" && !audio.zone.connected) " · 重新连接" else "")
                    }
                }
                audio.zone.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                HorizontalDivider()
                Text("Core DSP", style = MaterialTheme.typography.titleMedium)
                val processing = audio.dsp.processing
                listOf("dspEnabled" to "启用 DSP", "directMode" to "直通模式", "eqEnabled" to "启用均衡器").forEach { (key, label) ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, Modifier.weight(1f))
                        Switch(checked = processing.audioFlag(key), enabled = !state.desktopAudioBusy,
                            onCheckedChange = { actions.desktopDsp(buildJsonObject { put(key, it) }) })
                    }
                }
                var preamp by remember(audio.zone.zoneId, audio.dsp.revision) { mutableFloatStateOf(processing.number("eqPreamp").toFloat()) }
                Text("均衡器前级 ${((preamp * 10).roundToInt() / 10.0)} dB")
                Slider(value = preamp.coerceIn(-24f, 12f), onValueChange = { preamp = it }, valueRange = -24f..12f,
                    enabled = !state.desktopAudioBusy, onValueChangeFinished = {
                        actions.desktopDsp(buildJsonObject { put("eqPreamp", (preamp * 10).roundToInt() / 10.0) })
                    })
                val bands = processing["eqBands"] as? JsonArray ?: JsonArray(emptyList())
                if (processing.audioFlag("eqEnabled") && !processing.audioFlag("directMode")) bands.forEachIndexed { index, element ->
                    val band = element as? JsonObject ?: return@forEachIndexed
                    var gain by remember(audio.zone.zoneId, audio.dsp.revision, index) { mutableFloatStateOf(band.number("gain").toFloat()) }
                    Text("${band.number("frequency").toInt()} Hz · ${((gain * 10).roundToInt() / 10.0)} dB",
                        style = MaterialTheme.typography.bodyMedium)
                    Slider(value = gain.coerceIn(-24f, 24f), onValueChange = { gain = it }, valueRange = -24f..24f,
                        enabled = !state.desktopAudioBusy, onValueChangeFinished = {
                            val edited = bands.mapIndexed { n, value -> if (n == index) JsonObject(band +
                                ("gain" to JsonPrimitive((gain * 10).roundToInt() / 10.0))) else value }
                            actions.desktopDsp(buildJsonObject { put("eqBands", JsonArray(edited)) })
                        })
                }
                if (audio.dsp.scenes.isNotEmpty()) {
                    Text("电脑上的 DSP 场景", style = MaterialTheme.typography.titleSmall)
                    audio.dsp.scenes.forEach { scene ->
                        TextButton(onClick = { pendingScene = scene }, enabled = scene.enabled && !state.desktopAudioBusy) {
                            Text((if (scene.id == audio.dsp.activeSceneId) "✓ " else "") + scene.name)
                        }
                    }
                    TextButton(onClick = { confirmAutomaticScene = true }, enabled = !state.desktopAudioBusy) { Text("恢复自动场景") }
                }
                HorizontalDivider()
                Text("实际信号链", style = MaterialTheme.typography.titleMedium)
                val path = audio.path
                SignalRow("音源", path.obj("source").format())
                SignalRow("Core 解码", path.obj("decoded").format())
                val dsp = path.obj("dsp")
                SignalRow("Core DSP 应用状态", dsp.text("applyState").ifBlank { "尚未确认" })
                (dsp["nodes"] as? JsonArray)?.forEach { value ->
                    val node = value as? JsonObject ?: return@forEach
                    SignalRow(node.text("type"), when { node.audioFlag("active") -> "实际运行"; node.audioFlag("bypassed") -> "旁路"; else -> "待命" })
                }
                val stage = dsp.obj("outputStage")
                if (stage.audioFlag("resampled")) SignalRow("Core SRC", "${stage.number("actualSampleRate") / 1000} kHz · ${stage.text("effectiveResampler")}")
                val transport = path.obj("transport")
                if (transport.text("kind") in setOf("tls-pcm", "tls-dsd")) {
                    val wireFormat = transport.text("format")
                    val transportName = when {
                        wireFormat == "dop-int24-le" -> "DoP"
                        wireFormat.startsWith("dsd-") -> "原生 DSD"
                        else -> "Float32 PCM"
                    }
                    SignalRow("TLS · $transportName", "${transport.number("sampleRate") / 1000} kHz · ${transport.number("channels").toInt()} ch")
                    val receiver = path.obj("receiver")
                    val frameRate = receiver.number("sampleRate") / if (wireFormat.startsWith("dsd-")) 8 else 1
                    val adaptive = desktopAdaptiveObservation(path)
                    if (adaptive.isNotEmpty()) SignalRow("Bridge 时钟 SRC", adaptive.joinToString("\n"))
                    if (frameRate > 0) SignalRow("Bridge 缓冲 / 音频出队",
                        "${(receiver.number("bufferedFrames") * 1000 / frameRate).roundToInt()} ms · 已出队 ${receiver.number("consumedFrames").toLong()} 帧 · 欠载 ${receiver.number("underruns").toLong()}")
                    SignalRow("时钟观测", desktopClockObservation(path).joinToString("\n"))
                    Text("Bridge 直接驱动 DAC，不重复应用 Core DSP。", style = MaterialTheme.typography.bodySmall)
                }
                val output = path.obj("output")
                SignalRow(output.text("deviceName").ifBlank { output.text("backend").ifBlank { "输出驱动" } }, output.format())
                Text("输出格式来自驱动回报；DAC 内部处理未知。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    pendingHandoff?.let { command -> AlertDialog(
        onDismissRequest = { pendingHandoff = null },
        title = { Text("接力到 ${command.target.name}？") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("源会话先暂停，再复制队列与当前位置。目标保留自己的 DSP 和音量。")
            if (command.target.queue != null) {
                Text("目标现有：${command.target.track?.title ?: "播放队列"}")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = command.replace, onCheckedChange = { pendingHandoff = command.copy(replace = it) })
                    Text("确认替换目标队列", Modifier.weight(1f))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = command.resume, onCheckedChange = { pendingHandoff = command.copy(resume = it) })
                Text("在目标输出继续播放", Modifier.weight(1f))
            }
        } },
        confirmButton = { TextButton(enabled = !state.desktopAudioBusy && (command.target.queue == null || command.replace),
            onClick = { actions.desktopHandoff(command); pendingHandoff = null }) { Text(if (command.resume) "接力并播放" else "接力并暂停") } },
        dismissButton = { TextButton(onClick = { pendingHandoff = null }) { Text("取消") } }) }
    if (pendingScene != null || confirmAutomaticScene) AlertDialog(
        onDismissRequest = { pendingScene = null; confirmAutomaticScene = false },
        title = { Text(pendingScene?.let { "应用 ${it.name}？" } ?: "恢复自动场景？") },
        text = { Text("由电脑 Core 处理；若 DSD 与此场景不兼容，允许转换为 PCM。") },
        confirmButton = { TextButton(onClick = {
            actions.desktopScene(pendingScene?.id); pendingScene = null; confirmAutomaticScene = false
        }) { Text("应用并允许 PCM") } },
        dismissButton = { TextButton(onClick = { pendingScene = null; confirmAutomaticScene = false }) { Text("取消") } })
}

@Composable
private fun SignalRow(title: String, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
