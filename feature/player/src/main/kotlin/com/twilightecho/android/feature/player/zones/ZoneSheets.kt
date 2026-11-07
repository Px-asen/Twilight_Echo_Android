package com.twilightecho.android.feature.player.zones

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.twilightecho.android.coreclient.DesktopDspScene
import com.twilightecho.android.coreclient.DesktopSyncGroup
import com.twilightecho.android.coreclient.DesktopZoneSession
import com.twilightecho.android.feature.player.PlayerActions
import com.twilightecho.android.feature.player.PlayerUiState
import com.twilightecho.android.feature.player.twilightFieldColors
import com.twilightecho.android.feature.player.twilightSliderColors
import com.twilightecho.android.feature.player.twilightSwitchColors
import com.twilightecho.android.ui.components.PillButton
import com.twilightecho.android.ui.components.SupportingText
import com.twilightecho.android.ui.components.rememberMotionEnabled
import com.twilightecho.android.ui.components.TwilightMotion
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale
import kotlin.math.roundToInt

/*
 * Sheets and dialogs of the 声区 page. Every command goes through the view model's single desktop-audio
 * lane; a sheet waits for that lane to finish, closes on success and keeps Core's message inline otherwise.
 */

/** Follows one command through the view model's busy flag: 0 idle, 1 sent, 2 seen in flight. */
@Stable
internal class CommandTracker {
    var phase by mutableIntStateOf(0)
    var failure by mutableStateOf<String?>(null)
    var noticeAtSend: String? = null
    val sending: Boolean get() = phase != 0
}

@Composable
internal fun rememberCommandTracker(state: PlayerUiState, onSuccess: () -> Unit): CommandTracker {
    val tracker = remember { CommandTracker() }
    val latest by rememberUpdatedState(state)
    val success by rememberUpdatedState(onSuccess)
    LaunchedEffect(tracker.phase, state.desktopAudioBusy) {
        val phase = tracker.phase
        if (phase == 0) return@LaunchedEffect
        if (state.desktopAudioBusy) {
            if (phase == 1) tracker.phase = 2
            return@LaunchedEffect
        }
        // Sent but never seen in flight: the lane either finished within one frame or ignored it.
        if (phase == 1) delay(1500)
        tracker.phase = 0
        val error = latest.desktopAudioError
        val notice = latest.desktopAudioNotice
        when {
            error != null -> tracker.failure = error
            phase == 2 || (notice != null && notice != tracker.noticeAtSend) -> success()
        }
    }
    return tracker
}

/** Sends [command] only when the lane would accept it, so the tracker never waits for nothing. */
internal fun CommandTracker.send(state: PlayerUiState, command: () -> Unit) {
    if (state.desktopAudio == null || state.desktopAudioBusy || sending) return
    failure = null
    noticeAtSend = state.desktopAudioNotice
    phase = 1
    command()
}

/**
 * The 声区 sheets. The ink is set explicitly: at full opacity the theme's surfaceContainer equals primaryContainer,
 * so Material's contentColorFor would hand every unstyled text the accent. A tall sheet stops below the status bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ZoneSheet(tag: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.statusBarsPadding().padding(top = 8.dp),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surfaceContainer.copy(alpha = 1f), contentColor = colors.onSurface) {
        SheetSystemBars()
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
            .padding(horizontal = 24.dp).padding(bottom = 24.dp).testTag(tag),
            verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

/**
 * Material's sheet window picks its bar icons from the system's dark setting, not the app's canvas: on a light system
 * the icons turned dark over the dark sheet and a pale contrast strip sat under it. Follow the canvas, as
 * SystemBarAppearance does for the activity window.
 */
@Composable
internal fun SheetSystemBars() {
    val view = LocalView.current
    val light = MaterialTheme.colorScheme.background.luminance() > 0.18f
    DisposableEffect(view, light) {
        (view.parent as? DialogWindowProvider)?.window?.let { window ->
            if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = light
                isAppearanceLightNavigationBars = light
            }
        }
        onDispose { }
    }
}

@Composable
internal fun SheetTitle(title: String, body: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
        if (body != null) SupportingText(body)
    }
}

@Composable
internal fun SheetSection(title: String, modifier: Modifier = Modifier) {
    Text(title, modifier.padding(top = 6.dp), style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Rows grouped on one quiet surface inside a sheet. */
@Composable
internal fun SheetGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)), content = content)
}

@Composable
internal fun SheetDivider() = HorizontalDivider(Modifier.padding(start = 16.dp), thickness = 0.5.dp,
    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

@Composable
private fun SheetFailure(tracker: CommandTracker) {
    val motion = rememberMotionEnabled()
    var shown by remember { mutableStateOf("") }
    tracker.failure?.let { shown = it }
    AnimatedVisibility(tracker.failure != null, enter = TwilightMotion.sectionExpand(motion),
        exit = TwilightMotion.sectionCollapse(motion)) {
        ZoneErrorNote(shown, Modifier.testTag("zoneSheetError"), maxLines = 4)
    }
}

@Composable
private fun SheetConfirm(label: String, tracker: CommandTracker, enabled: Boolean, tag: String, problem: String?,
    onClick: () -> Unit) {
    if (problem != null && !tracker.sending) Text(problem, style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("$tag-reason"))
    Box(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        PillButton(if (tracker.sending) "正在发送…" else label, onClick, Modifier.fillMaxWidth().testTag(tag),
            enabled = enabled && !tracker.sending)
        if (tracker.sending) CircularProgressIndicator(Modifier.align(Alignment.CenterEnd).padding(end = 18.dp).size(18.dp),
            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f), strokeWidth = 2.dp)
    }
}

/** A small window as a list thumbnail. */
@Composable
internal fun ZoneThumb(zone: DesktopZoneSession?, modifier: Modifier = Modifier, group: DesktopSyncGroup? = null) {
    val light = when {
        zone == null -> ZoneLight.OFFLINE
        group != null -> memberLight(zone, group)
        else -> zoneLight(zone)
    }
    ZoneWindow(light, group?.track ?: zone?.track, null, modifier.size(44.dp), RoundedCornerShape(12.dp), artworkSize = 160)
}

// ---------------------------------------------------------------------------------------------
// 接力

@Composable
internal fun HandoffSheet(state: PlayerUiState, actions: PlayerActions, sourceId: String, onDismiss: () -> Unit) {
    val zones = state.desktopZoneSessions
    val source = zones.firstOrNull { it.id == sourceId }
    var targetId by rememberSaveable { mutableStateOf<String?>(null) }
    var replace by rememberSaveable { mutableStateOf(false) }
    var resume by rememberSaveable { mutableStateOf(true) }
    val tracker = rememberCommandTracker(state, onDismiss)
    ZoneSheet("handoffSheet", onDismiss) {
        SheetTitle("接力到另一个声区", "${source?.name ?: "此声区"} 会先暂停，再把队列和播放位置复制到目标声区。")
        if (source == null) {
            SupportingText("声区已移除。")
            return@ZoneSheet
        }
        val targets = handoffTargets(source, zones)
        val target = targets.firstOrNull { it.zone.id == targetId && it.enabled }?.zone
        SheetGroup {
            targets.forEachIndexed { index, option ->
                if (index > 0) SheetDivider()
                val selected = option.zone.id == targetId
                Row(Modifier.fillMaxWidth().selectable(selected, enabled = option.enabled && !tracker.sending, role = Role.RadioButton) {
                        if (targetId != option.zone.id) { targetId = option.zone.id; replace = false }
                    }.heightIn(min = 64.dp).padding(horizontal = 14.dp, vertical = 10.dp).testTag("handoffTarget-${option.zone.id}"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ZoneThumb(option.zone)
                    Column(Modifier.weight(1f)) {
                        Text(option.zone.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (option.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(option.reason ?: handoffTargetLine(option.zone), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    RadioButton(selected, onClick = null, enabled = option.enabled,
                        colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary))
                }
            }
        }
        if (target != null && handoffNeedsReplace(target)) {
            SheetGroup {
                Row(Modifier.fillMaxWidth().toggleable(replace, enabled = !tracker.sending, role = Role.Checkbox) { replace = it }
                    .padding(horizontal = 14.dp, vertical = 12.dp).testTag("handoffReplace"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Checkbox(replace, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary))
                    Column(Modifier.weight(1f)) {
                        Text("替换 ${target.name} 当前的队列", style = MaterialTheme.typography.titleSmall)
                        target.track?.title?.takeIf { it.isNotBlank() }?.let {
                            SupportingText("正在放：$it", Modifier.padding(top = 2.dp), maxLines = 1)
                        }
                    }
                }
            }
        }
        val blocked = target != null && handoffResumeBlocked(target)
        SheetGroup {
            Row(Modifier.fillMaxWidth().toggleable(resume && !blocked, enabled = !blocked && !tracker.sending, role = Role.Switch) { resume = it }
                .padding(horizontal = 16.dp, vertical = 12.dp).testTag("handoffResume"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("接力后继续播放", style = MaterialTheme.typography.titleSmall,
                        color = if (blocked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                    if (blocked) SupportingText(HANDOFF_RESUME_BLOCKED_HINT, Modifier.padding(top = 2.dp))
                }
                Switch(resume && !blocked, onCheckedChange = null, enabled = !blocked, colors = twilightSwitchColors())
            }
        }
        SheetFailure(tracker)
        val generation = state.desktopAudio?.targetGeneration
        val command = if (target != null && generation != null) buildHandoff(source, target, generation, replace, resume) else null
        SheetConfirm(if (command?.resume == true) "接力并播放" else "接力", tracker, enabled = command != null && !state.desktopAudioBusy,
            tag = "handoffConfirm", problem = handoffSourceProblem(source) ?: target?.let { handoffProblem(source, it, replace) }) {
            command?.let { tracker.send(state) { actions.desktopHandoff(it) } }
        }
    }
}

private fun handoffTargetLine(zone: DesktopZoneSession): String {
    val light = zoneLight(zone)
    return when (light) {
        ZoneLight.PLAYING, ZoneLight.PAUSED -> listOfNotNull(zone.track?.title?.takeIf { it.isNotBlank() },
            playbackStateLabel(zone.playback?.state)).joinToString(" · ")
        else -> zoneWindowMessage(zone, light) ?: zoneOutputLabel(zone)
    }
}

// ---------------------------------------------------------------------------------------------
// 建立同步组

@Composable
internal fun GroupCreateSheet(state: PlayerUiState, actions: PlayerActions, onDismiss: () -> Unit) {
    val zones = state.desktopZoneSessions
    val candidates = groupCandidates(zones)
    val groupCount = state.desktopSyncGroups?.groups?.size ?: 0
    var name by rememberSaveable { mutableStateOf("同步组 ${groupCount + 1}") }
    var selected by rememberSaveable { mutableStateOf(listOf<String>()) }
    var sourceId by rememberSaveable { mutableStateOf<String?>(null) }
    var delays by remember { mutableStateOf(mapOf<String, Double>()) }
    val draft = GroupDraft(name, selected, sourceId, delays)
    val tracker = rememberCommandTracker(state, onDismiss)
    ZoneSheet("groupCreateSheet", onDismiss) {
        SheetTitle("建立同步组", "选择 2–4 个声区，采用其中一份队列。编组后暂停待播，各自原队列保留。")
        OutlinedTextField(name, { name = it.take(MAX_GROUP_NAME_LENGTH) }, Modifier.fillMaxWidth().testTag("groupName"),
            label = { Text("组名") }, singleLine = true, enabled = !tracker.sending, colors = twilightFieldColors(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), shape = RoundedCornerShape(14.dp))
        SheetSection("成员 · 已选 ${selected.size}/$MAX_GROUP_MEMBERS")
        SheetGroup {
            candidates.forEachIndexed { index, zone ->
                if (index > 0) SheetDivider()
                val checked = zone.id in selected
                val full = !checked && selected.size >= MAX_GROUP_MEMBERS
                Column {
                    Row(Modifier.fillMaxWidth().toggleable(checked, enabled = !full && !tracker.sending, role = Role.Checkbox) {
                            val next = draft.toggle(zone.id)
                            selected = next.selected; sourceId = next.sourceId
                        }.heightIn(min = 64.dp).padding(horizontal = 14.dp, vertical = 10.dp).testTag("groupMember-${zone.id}"),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Checkbox(checked, onCheckedChange = null, enabled = !full,
                            colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary))
                        ZoneThumb(zone)
                        // The readiness chip shares the name's line so the output keeps the full width.
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(zone.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                                val readiness = memberReadiness(zone)
                                ZoneChip(readiness.label, readiness.tone)
                            }
                            Text(zoneOutputLabel(zone), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    AnimatedVisibility(checked, enter = TwilightMotion.sectionExpand(rememberMotionEnabled()),
                        exit = TwilightMotion.sectionCollapse(rememberMotionEnabled())) {
                        DelaySlider(draft.delayOf(zone.id), enabled = !tracker.sending, tag = "groupDelay-${zone.id}") {
                            delays = delays + (zone.id to clampDelay(it))
                        }
                    }
                }
            }
        }
        SupportingText("0–250 ms；0 表示 DAC 模拟输出尚未校准。")
        val sources = groupSourceOptions(zones, draft)
        SheetSection("采用的队列")
        if (sources.isEmpty()) SupportingText(if (selected.isEmpty()) "先选择成员，再选择采用哪一份队列。" else "所选声区都没有队列。")
        else SheetGroup {
            sources.forEachIndexed { index, zone ->
                if (index > 0) SheetDivider()
                val chosen = zone.id == sourceId
                Row(Modifier.fillMaxWidth().selectable(chosen, enabled = !tracker.sending, role = Role.RadioButton) { sourceId = zone.id }
                    .heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 10.dp).testTag("groupSource-${zone.id}"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RadioButton(chosen, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary))
                    Column(Modifier.weight(1f)) {
                        Text("${zone.name} 的队列", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        zone.track?.title?.takeIf { it.isNotBlank() }?.let {
                            SupportingText(listOf(it, trackSubtitle(zone.track)).filter(String::isNotEmpty).joinToString(" · "), maxLines = 1)
                        }
                    }
                }
            }
        }
        val unready = selected.mapNotNull { id -> zones.firstOrNull { it.id == id } }.any { !memberReady(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ToneDot(if (unready) ZoneTone.WARNING else ZoneTone.NEUTRAL, Modifier.padding(top = 7.dp))
            SupportingText("同步播放需要每个成员都连接支持时钟自适应的输出桥。")
        }
        SheetFailure(tracker)
        val revision = state.desktopSyncGroups?.revision
        val generation = state.desktopAudio?.targetGeneration
        val command = if (revision != null && generation != null) buildGroupCreate(draft, zones, revision, generation) else null
        SheetConfirm("建立并暂停", tracker, enabled = command != null && !state.desktopAudioBusy, tag = "groupCreateConfirm",
            problem = groupDraftProblem(draft, zones) ?: state.desktopGroupsError) {
            command?.let { tracker.send(state) { actions.desktopCreateGroup(it) } }
        }
    }
}

@Composable
private fun DelaySlider(value: Double, enabled: Boolean, tag: String, onChange: (Double) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 60.dp, end = 16.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("下游延迟", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatDelayMs(value), style = MaterialTheme.typography.labelLarge,
                color = if (value > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value.toFloat(), { onChange(it.toDouble()) }, Modifier.fillMaxWidth().heightIn(min = 40.dp).testTag(tag)
            .semantics { contentDescription = "下游延迟" }, enabled = enabled,
            valueRange = 0f..MAX_DOWNSTREAM_DELAY_MS.toFloat(), colors = twilightSliderColors())
    }
}

// ---------------------------------------------------------------------------------------------
// 解除编组

@Composable
internal fun UngroupDialog(state: PlayerUiState, group: DesktopSyncGroup, actions: PlayerActions, onDismiss: () -> Unit,
    onDone: () -> Unit) {
    val tracker = rememberCommandTracker(state, onDone)
    AlertDialog(onDismissRequest = { if (!tracker.sending) onDismiss() },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(28.dp),
        modifier = Modifier.testTag("ungroupDialog"),
        title = { Text("解除 ${group.name}？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("所有成员停止，回到各自原来的队列并保持暂停，不保留组队列。", style = MaterialTheme.typography.bodyLarge)
                SheetFailure(tracker)
            }
        },
        confirmButton = {
            TextButton(onClick = { tracker.send(state) { actions.desktopRemoveGroup(group) } },
                enabled = !tracker.sending && !state.desktopAudioBusy && state.desktopAudio != null,
                modifier = Modifier.testTag("ungroupConfirm")) {
                Text(if (tracker.sending) "正在解除…" else "停止并解除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !tracker.sending) { Text("取消") } })
}

// ---------------------------------------------------------------------------------------------
// 处理 (DSP)

/**
 * The computer's processing for [zoneId], with the same rules as the computer's own panel. It waits
 * until the view model follows that zone, so it never edits another place's settings.
 */
@Composable
internal fun ZoneDspSheet(state: PlayerUiState, actions: PlayerActions, zoneId: String, subject: String, onDismiss: () -> Unit) {
    val audio = state.desktopAudio?.takeIf { it.zone.zoneId == zoneId }
    val busy = state.desktopAudioBusy
    var pendingScene by remember { mutableStateOf<DesktopDspScene?>(null) }
    var automaticScene by remember { mutableStateOf(false) }
    ZoneSheet("dspSheet", onDismiss) {
        SheetTitle("处理", "由电脑 Core 处理「$subject」的声音，手机只负责控制。")
        if (audio == null) {
            val error = state.desktopAudioError
            if (error != null) ZoneErrorNote(error) else LoadingRow("正在读取处理设置")
            return@ZoneSheet
        }
        val processing = audio.dsp.processing
        TextButton({ actions.openDesktopDspLibrary(zoneId, subject) }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("openDspLibrary")) {
            Text("管理资料与插件")
        }
        val direct = processing.processingFlag("directMode")
        val enabled = processing.processingFlag("dspEnabled")
        val eq = processing.processingFlag("eqEnabled")
        SheetGroup {
            DspSwitch("启用 DSP", "关闭时旁路全部处理", enabled, !busy && !direct, "dspSwitch-dspEnabled") {
                actions.desktopDsp(buildJsonObject { put("dspEnabled", it) })
            }
            SheetDivider()
            DspSwitch("直通模式", "开启后旁路全部音效", direct, !busy, "dspSwitch-directMode") {
                actions.desktopDsp(buildJsonObject { put("directMode", it) })
            }
            SheetDivider()
            DspSwitch("启用均衡器", "参数均衡器与前级", eq, !busy, "dspSwitch-eqEnabled") {
                actions.desktopDsp(buildJsonObject { put("eqEnabled", it) })
            }
        }
        when {
            direct -> SupportingText("直通模式正在旁路音效，退出直通后才能听到调整。")
            !enabled -> SupportingText("DSP 总开关已旁路：可以继续编辑，开启后才会处理音频。")
        }
        SheetSection("均衡器")
        val bands = processing.processingBands()
        SheetGroup {
            DbSlider("前级", processing.processingNumber("eqPreamp"), -24f..12f, !busy, "dspPreamp",
                key = "${audio.zone.zoneId}:${audio.dsp.revision}") { value ->
                actions.desktopDsp(buildJsonObject { put("eqPreamp", value) })
            }
            if (eq && !direct) bands.forEachIndexed { index, band ->
                SheetDivider()
                DbSlider(frequencyLabel(band.processingNumber("frequency")), band.processingNumber("gain"), -24f..24f, !busy,
                    "dspBand-$index", key = "${audio.zone.zoneId}:${audio.dsp.revision}:$index") { value ->
                    val raw = processing["eqBands"] as? JsonArray ?: JsonArray(emptyList())
                    val edited = raw.mapIndexed { n, element ->
                        if (n == index && element is JsonObject) JsonObject(element + ("gain" to JsonPrimitive(value))) else element
                    }
                    actions.desktopDsp(buildJsonObject { put("eqBands", JsonArray(edited)) })
                }
            }
        }
        if (bands.isNotEmpty() && (!eq || direct)) SupportingText("开启均衡器并退出直通后可调整各频段。")
        if (audio.dsp.scenes.isNotEmpty()) {
            SheetSection("电脑上的 DSP 场景")
            SheetGroup {
                audio.dsp.scenes.forEachIndexed { index, scene ->
                    if (index > 0) SheetDivider()
                    val active = scene.id == audio.dsp.activeSceneId
                    Row(Modifier.fillMaxWidth().selectable(active, enabled = scene.enabled && !busy, role = Role.RadioButton) {
                            pendingScene = scene
                        }.heightIn(min = 52.dp).padding(horizontal = 14.dp, vertical = 8.dp).testTag("dspScene-${scene.id}"),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        RadioButton(active, onClick = null, enabled = scene.enabled,
                            colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary))
                        Text(scene.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (scene.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                        if (scene.id == audio.dsp.pinnedSceneId) Text("固定", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            TextButton(onClick = { automaticScene = true }, enabled = !busy, modifier = Modifier.testTag("dspSceneAuto")) {
                Text("恢复自动场景")
            }
        }
        state.desktopAudioError?.let { ZoneErrorNote(it) }
    }
    if (pendingScene != null || automaticScene) AlertDialog(
        onDismissRequest = { pendingScene = null; automaticScene = false },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(28.dp),
        modifier = Modifier.testTag("dspSceneDialog"),
        title = { Text(pendingScene?.let { "应用 ${it.name}？" } ?: "恢复自动场景？") },
        text = { Text("由电脑 Core 处理；若 DSD 与此场景不兼容，允许转换为 PCM。", style = MaterialTheme.typography.bodyLarge) },
        confirmButton = {
            TextButton(onClick = { actions.desktopScene(pendingScene?.id); pendingScene = null; automaticScene = false },
                enabled = !busy) { Text("应用并允许 PCM") }
        },
        dismissButton = { TextButton(onClick = { pendingScene = null; automaticScene = false }) { Text("取消") } })
}

private fun frequencyLabel(hz: Double): String = when {
    hz <= 0 -> "频段"
    hz < 1000 -> "${hz.roundToInt()} Hz"
    else -> "${String.format(Locale.ROOT, "%.1f", hz / 1000).trimEnd('0').trimEnd('.')} kHz"
}

@Composable
private fun DspSwitch(title: String, subtitle: String, checked: Boolean, enabled: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
        .heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 10.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            SupportingText(subtitle, Modifier.padding(top = 2.dp), maxLines = 2)
        }
        Switch(checked, onCheckedChange = null, enabled = enabled, colors = twilightSwitchColors())
    }
}

/** A dB slider that commits once, when the finger lifts; rounded to 0.1 dB like the computer's panel. */
@Composable
private fun DbSlider(label: String, value: Double, range: ClosedFloatingPointRange<Float>, enabled: Boolean, tag: String,
    key: String, onCommit: (Double) -> Unit) {
    var draft by remember(key, value) { mutableFloatStateOf(value.toFloat().coerceIn(range.start, range.endInclusive)) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Text(String.format(Locale.ROOT, "%+.1f dB", draft), style = MaterialTheme.typography.labelLarge,
                color = if (draft == 0f) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
        }
        Slider(draft, { draft = it }, Modifier.fillMaxWidth().heightIn(min = 40.dp).testTag(tag)
            .semantics { contentDescription = label }, enabled = enabled, valueRange = range,
            onValueChangeFinished = { onCommit((draft * 10).roundToInt() / 10.0) }, colors = twilightSliderColors())
    }
}

@Composable
internal fun LoadingRow(label: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
        SupportingText(label)
    }
}

// ---------------------------------------------------------------------------------------------
// 信号链

/** Core's reported path for [zoneId], stage by stage, each node with the colour of what it does to the sound. */
@Composable
internal fun SignalPathSheet(state: PlayerUiState, zoneId: String, subject: String, peerName: String?, onDismiss: () -> Unit) {
    val audio = state.desktopAudio?.takeIf { it.zone.zoneId == zoneId }
    val body = "「$subject」实际经过的处理，由 Core 回报。"
    ZoneSheet("signalPathSheet", onDismiss) {
        if (audio == null) {
            SheetTitle("信号链", body)
            val error = state.desktopAudioError
            if (error != null) ZoneErrorNote(error) else LoadingRow("正在读取信号链")
            return@ZoneSheet
        }
        val stages = signalPathStages(audio.path, peerName)
        val (verdict, tone) = signalPathVerdict(stages)
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) { SheetTitle("信号链", body) }
            ZoneChip(verdict, tone, Modifier.padding(top = 4.dp).testTag("signalPathVerdict"))
        }
        stages.forEach { stage ->
            SheetSection(stage.label)
            SheetGroup {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    stage.nodes.forEachIndexed { index, node ->
                        PathNodeRow(node, last = index == stage.nodes.lastIndex,
                            next = stage.nodes.getOrNull(index + 1)?.tone)
                    }
                }
            }
        }
        SupportingText("输出格式来自驱动回报；DAC 内部处理未知。")
    }
}

@Composable
private fun PathNodeRow(node: PathNode, last: Boolean, next: PathTone?) {
    val dot = toneColor(node.tone.tone)
    val line = next?.let { toneColor(it.tone).copy(alpha = 0.45f) } ?: MaterialTheme.colorScheme.outlineVariant
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)
        .semantics(mergeDescendants = true) { stateDescription = node.tone.label }
        .testTag("pathNode-${node.id}")) {
        Canvas(Modifier.width(18.dp).fillMaxHeight()) {
            val cx = size.width / 2
            val cy = 11.dp.toPx()
            drawCircle(dot, 5.dp.toPx(), Offset(cx, cy))
            if (!last) drawLine(line, Offset(cx, cy + 9.dp.toPx()), Offset(cx, size.height + 2.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp, bottom = if (last) 0.dp else 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(node.label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Text(node.tone.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(node.value, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodyLarge)
            node.detail.filter { it.isNotBlank() }.forEach {
                Text(it, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
