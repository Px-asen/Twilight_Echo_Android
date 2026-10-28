package com.twilightecho.android.feature.player

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.twilightecho.android.coreclient.*
import com.twilightecho.android.ui.components.PillButton
import com.twilightecho.android.ui.components.SupportingText
import com.twilightecho.android.ui.components.TwilightIcons
import com.twilightecho.android.ui.theme.TwilightTokens

private val ROUTE_TYPES = listOf("bluetooth", "wired_headset", "usb_dac", "speaker")

/** Scenes bound to phone outputs: the current output, what applies to it, and the bindings by priority. */
@Composable
internal fun OutputPresetsCard(state: PlayerUiState, actions: PlayerActions) {
    val settings = state.preferences.outputPresets
    val scenes = state.eqConfig.scenes
    var adding by remember { mutableStateOf(false) }
    fun edit(change: (OutputPresetPreferences) -> OutputPresetPreferences) =
        actions.editPreferences { it.copy(outputPresets = change(it.outputPresets)) }
    AudioCard("设备预设", modifier = Modifier.testTag("output-presets")) {
        SettingsSwitch("自动切换", if (settings.autoSwitch) "换设备时应用对应场景" else "换设备时保持当前声音", settings.autoSwitch) {
            edit { it.copy(autoSwitch = !it.autoSwitch) }
        }
        CardDivider()
        OutputPresetState(state.outputPreset, state.eqConfig, actions)
        settings.bindings.sortedBy { it.scope.ordinal }.forEach { binding ->
            CardDivider()
            val scene = scenes.firstOrNull { it.id == binding.sceneId }
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(binding.targetLabel, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    SupportingText(listOfNotNull(outputRouteTypeLabel(binding.routeType).takeIf { binding.scope == OutputPresetScope.DEVICE },
                        scene?.name ?: "${binding.sceneName}（已删除）").joinToString(" · "), maxLines = 1)
                }
                MiniAction("移除", Modifier.testTag("output-preset-remove-${binding.target}")) { edit { it.unbind(binding) } }
            }
        }
        PillButton("添加设备预设", { adding = true }, Modifier.padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth()
            .testTag("output-preset-add"), TwilightIcons.Plus, enabled = scenes.isNotEmpty(), tonal = true)
        if (scenes.isEmpty()) SupportingText("先保存一个场景。", Modifier.padding(horizontal = 18.dp, vertical = 4.dp))
    }
    if (adding) AddOutputPresetDialog(state.outputPreset.output, scenes, state.eqConfig, { adding = false }) { binding ->
        edit { it.bind(binding) }
        adding = false
    }
}

@Composable
private fun OutputPresetState(status: OutputPresetStatus, config: CoreEqConfigView, actions: PlayerActions) {
    val output = status.output
    val binding = status.binding
    val scene = binding?.let { bound -> config.scenes.firstOrNull { it.id == bound.sceneId } }
    val active = remember(config, scene) { scene != null && config.matchesScene(scene) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp).testTag("output-preset-status"),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(output?.let { "当前输出：${it.label}" } ?: "当前没有播放", style = MaterialTheme.typography.titleMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (output == null) {
            SupportingText("开始播放后识别输出设备")
            return@Column
        }
        val warning = status.failure ?: if (binding != null && scene == null) "场景“${binding.sceneName}”已删除，声音保持不变。" else null
        val (line, action) = when {
            binding == null -> "未设置预设，保持当前声音" to null
            warning != null -> warning to "重试".takeIf { status.failure != null }
            active -> "正在使用“${scene!!.name}”" to null
            status.appliedSceneId == scene!!.id -> "已手动调整，预设为“${scene.name}”" to "恢复预设"
            else -> "预设“${scene.name}”未生效" to "应用预设"
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(line, Modifier.weight(1f).padding(end = 8.dp), style = MaterialTheme.typography.bodyLarge,
                color = if (warning != null) TwilightTokens.SignalWarm else MaterialTheme.colorScheme.onSurface)
            if (action != null) MiniAction(action, Modifier.testTag("output-preset-reapply"), highlighted = true, onClick = actions.reapplyOutputPreset)
        }
        if (binding != null) SupportingText("匹配：${if (binding.scope == OutputPresetScope.DEVICE) "本设备" else binding.targetLabel}")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddOutputPresetDialog(output: OutputDeviceIdentity?, scenes: List<CoreDspScene>, config: CoreEqConfigView,
    dismiss: () -> Unit, save: (OutputPresetBinding) -> Unit) {
    // Bindable targets: this output, each output class, and the catch-all.
    val targets = remember(output) {
        listOfNotNull(output?.let { OutputPresetBinding(OutputPresetScope.DEVICE, "", routeType = it.routeType, deviceName = it.name) }) +
            (ROUTE_TYPES + listOfNotNull(output?.routeType)).distinct().map { OutputPresetBinding(OutputPresetScope.ROUTE_TYPE, "", routeType = it) } +
            OutputPresetBinding(OutputPresetScope.OTHER, "")
    }
    var target by remember { mutableStateOf(targets.first()) }
    var sceneId by remember { mutableStateOf(scenes.firstOrNull { config.matchesScene(it) }?.id) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("添加设备预设") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("适用于", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                targets.forEach { option ->
                    val label = if (option.scope == OutputPresetScope.DEVICE) "本设备" else option.targetLabel
                    ChoicePill(label, option.target == target.target, Modifier.testTag("output-preset-target-${option.target}")) { target = option }
                }
            }
            if (target.scope == OutputPresetScope.OTHER) SupportingText("没有更具体的预设时使用。")
            Text("场景", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                scenes.forEach { scene ->
                    ChoicePill(scene.name, scene.id == sceneId, Modifier.testTag("output-preset-scene-${scene.id}")) { sceneId = scene.id }
                }
            }
        }
    }, confirmButton = {
        TextButton(onClick = {
            val scene = scenes.first { it.id == sceneId }
            save(target.copy(sceneId = scene.id, sceneName = scene.name))
        }, enabled = scenes.any { it.id == sceneId }, modifier = Modifier.testTag("output-preset-save")) { Text("保存") }
    }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}
