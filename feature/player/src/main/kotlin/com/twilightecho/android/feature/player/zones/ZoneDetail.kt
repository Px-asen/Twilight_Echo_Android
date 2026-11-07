package com.twilightecho.android.feature.player.zones

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.twilightecho.android.coreclient.DesktopSyncGroup
import com.twilightecho.android.coreclient.DesktopZonePlayback
import com.twilightecho.android.coreclient.DesktopZoneSession
import com.twilightecho.android.coreclient.DesktopZoneTrack
import com.twilightecho.android.feature.player.LoadingContent
import com.twilightecho.android.feature.player.LocalDockInset
import com.twilightecho.android.feature.player.MainDestinationTab
import com.twilightecho.android.feature.player.PlayerActions
import com.twilightecho.android.feature.player.PlayerUiState
import com.twilightecho.android.feature.player.DesktopGroupAutoMixSection
import com.twilightecho.android.feature.player.twilightSliderColors
import com.twilightecho.android.ui.components.EmptyState
import com.twilightecho.android.ui.components.PageHeading
import com.twilightecho.android.ui.components.PillButton
import com.twilightecho.android.ui.components.QuietIconButton
import com.twilightecho.android.ui.components.SettingsGroup
import com.twilightecho.android.ui.components.SupportingText
import com.twilightecho.android.ui.components.TwilightIcons
import com.twilightecho.android.ui.components.tactilePress
import kotlinx.coroutines.delay

/*
 * Detail pages of the 声区 tab: one zone (big window, wavy seek bar, morphing play button, output) or one
 * sync group (member windows in one frame, phase, skew, members). Both follow their place in the view
 * model's single desktop-audio snapshot, so every command and sheet acts on exactly what is on screen.
 */

private enum class DetailSheet { HANDOFF, DSP, PATH, UNGROUP }

/**
 * Keeps the view model's snapshot on [target] while a detail page shows it. It stands aside while the
 * player's own desktop audio sheet is open, so the two never pull the snapshot back and forth.
 */
@Composable
private fun FollowZone(state: PlayerUiState, actions: PlayerActions, target: String?) {
    var requested by remember { mutableStateOf<String?>(null) }
    val followed = state.desktopAudio?.zone?.zoneId
    val known = target != null && state.desktopZoneSessions.any { it.id == target }
    LaunchedEffect(target, followed, known, state.desktopAudioBusy, state.desktopAudioOpen) {
        if (target == null || !known || state.desktopAudioBusy || state.desktopAudioOpen) return@LaunchedEffect
        if (followed == target) { requested = target; return@LaunchedEffect }
        // While a requested snapshot loads, followed is null; ask again only once something else is followed.
        if (followed != null || requested != target) { requested = target; actions.desktopZone(target) }
    }
}

/** A centred column; tablets keep detail content to a readable width. */
@Composable
private fun Centered(modifier: Modifier = Modifier, maxWidth: Dp = 560.dp, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(modifier.widthIn(max = maxWidth).fillMaxWidth().padding(horizontal = 20.dp), content = content)
    }
}

@Composable
private fun ZoneBackRow(onBack: () -> Unit) {
    Row(Modifier.padding(start = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        QuietIconButton(TwilightIcons.ArrowBack, "返回声区", onBack, Modifier.testTag("zoneBack"))
        Text("声区", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------------------------------------------------------------------------------------------
// One zone

@Composable
internal fun ZoneDetailScreen(state: PlayerUiState, actions: PlayerActions, zoneId: String, back: () -> Unit,
    openDetail: (String?) -> Unit) {
    val zone = state.desktopZoneSessions.firstOrNull { it.id == zoneId }
    FollowZone(state, actions, zoneId.takeIf { zone != null })
    var sheet by rememberSaveable { mutableStateOf<DetailSheet?>(null) }
    LazyColumn(Modifier.fillMaxSize().testTag("zoneDetail"),
        contentPadding = PaddingValues(bottom = 24.dp + LocalDockInset.current)) {
        item(key = "back") { ZoneBackRow(back) }
        if (zone == null) {
            item(key = "gone") {
                if (state.desktopZoneSessions.isEmpty() && state.desktopZonesError == null) LoadingContent("正在读取声区")
                else EmptyState("声区已移除", "电脑上已移除这个声区。", TwilightIcons.Speaker, actionLabel = "返回声区", onAction = back)
            }
            return@LazyColumn
        }
        item(key = "heading") {
            val light = zoneLight(zone)
            val status = zoneStatus(zone, light, zone.playback)
            Centered {
                PageHeading(zone.name, zoneOutputLabel(zone)) {
                    ZoneChip(status.label, status.tone, Modifier.testTag("zoneDetailStatus"), playing = light == ZoneLight.PLAYING)
                }
                zone.error?.let { ZoneErrorNote(it, Modifier.padding(bottom = 14.dp)) }
            }
        }
        item(key = "player") { ZonePlayer(state, actions, zone, openDetail) }
        item(key = "actions") { ZoneActions(state, actions, zone) { sheet = it } }
        item(key = "output") { OutputCard(zone) }
    }
    if (zone != null) when (sheet) {
        DetailSheet.HANDOFF -> HandoffSheet(state, actions, zone.id) { sheet = null }
        DetailSheet.DSP -> ZoneDspSheet(state, actions, zone.id, zone.name) { sheet = null }
        DetailSheet.PATH -> SignalPathSheet(state, zone.id, zone.name, zone.output?.peerName) { sheet = null }
        DetailSheet.UNGROUP, null -> Unit
    }
}

@Composable
private fun ZonePlayer(state: PlayerUiState, actions: PlayerActions, zone: DesktopZoneSession, openDetail: (String?) -> Unit) {
    val light = zoneLight(zone)
    val playing = light == ZoneLight.PLAYING
    val grouped = zone.groupId != null
    // Commands go to the followed snapshot; a grouped member is played by its group.
    val following = state.desktopAudio?.zone?.zoneId == zone.id && !grouped
    val hasMusic = zone.queue != null && zone.track != null
    val outputUp = !zoneOutputDown(zone)
    Centered {
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ZoneWindow(light, zone.track, zoneWindowMessage(zone, light), Modifier.size(min(maxWidth, 420.dp)).testTag("zoneDetailWindow"),
                RoundedCornerShape(28.dp), artworkSize = 1024)
        }
        TrackBlock(zone.track, zone.playback, zone, if (hasMusic) "未知曲目" else "队列为空")
        when {
            light == ZoneLight.OFFLINE || light == ZoneLight.UNPAIRED -> OutputDownAction(state, actions, zone)
            !hasMusic && !grouped -> PillButton("选择音乐", { actions.openDesktopQueue("zone", zone.id) },
                Modifier.padding(top = 16.dp).testTag("zonePickMusic"), icon = TwilightIcons.Library, tonal = true)
            !hasMusic -> SupportingText("在同步组中选择音乐。", Modifier.padding(top = 10.dp))
        }
        if (hasMusic) {
            WavySeekBar(rememberZonePosition(zone.playback), zone.playback?.duration ?: 0.0, playing,
                enabled = following && outputUp, onSeek = { actions.desktopZoneValue("seek", it) },
                modifier = Modifier.padding(top = 18.dp).testTag("zoneSeek"))
            TransportRow(playing, toggleEnabled = following && (playing || outputUp), stepEnabled = following && outputUp,
                loading = zone.playback?.state == "loading" || zone.playback?.state == "buffering", name = zone.name) { kind ->
                actions.desktopZoneTransportFor(zone.id, kind)
            }
        }
        VolumeRow(zone.playback?.volume, following, "音量", "zoneVolume") { actions.desktopZoneValue("volume", it) }
        if (grouped) Row(Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)).padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(TwilightIcons.Link, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("此声区已加入同步组，由同步组统一播放。", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            CardTextAction("查看同步组", { openDetail("group:${zone.groupId}") }, Modifier.testTag("zoneOpenGroup"))
        }
        ZoneFeedback(state)
    }
}

/** What a dark window can do from the phone: the local zone re-selects its bridge; a bridge zone is connected on the computer. */
@Composable
private fun OutputDownAction(state: PlayerUiState, actions: PlayerActions, zone: DesktopZoneSession) {
    val connecting = zoneOutputState(zone) == ZoneOutputState.CONNECTING
    val bridgeAvailable = state.desktopAudio?.takeIf { it.zone.zoneId == zone.id }?.zone?.outputs
        ?.any { it.id == "bridge" && it.available } == true
    when {
        connecting -> SupportingText("正在连接输出桥…", Modifier.padding(top = 10.dp))
        !zone.isBridgeZone -> PillButton("重新连接", { actions.desktopOutput("bridge") },
            Modifier.padding(top = 16.dp).testTag("zoneReconnect"), icon = TwilightIcons.Link,
            enabled = bridgeAvailable && !state.desktopAudioBusy, tonal = true)
        else -> SupportingText("请在电脑上连接输出桥。输出桥离线时，这个声区不会改用电脑的扬声器。", Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun TrackBlock(track: DesktopZoneTrack?, playback: DesktopZonePlayback?, zone: DesktopZoneSession?, fallback: String) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(top = 20.dp)) {
        Text(track?.title?.takeIf { it.isNotBlank() } ?: fallback,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), maxLines = 2, overflow = TextOverflow.Ellipsis)
        val subtitle = trackSubtitle(track)
        if (subtitle.isNotEmpty()) Text(subtitle, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val format = if (track != null) zoneFormatLine(playback, track, zone) else ""
        if (format.isNotEmpty()) Text(format, Modifier.padding(top = 6.dp).testTag("zoneFormat"), style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TransportRow(playing: Boolean, toggleEnabled: Boolean, stepEnabled: Boolean, loading: Boolean, name: String,
    onTransport: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally)) {
        TransportButton(TwilightIcons.SkipPrevious, "上一首", stepEnabled, "zonePrevious") { onTransport("previous") }
        MorphPlayButton(playing, { onTransport(if (playing) "pause" else "play") }, Modifier.testTag("zoneDetailPlayPause"),
            size = 72.dp, enabled = toggleEnabled, loading = loading, label = "${if (playing) "暂停" else "播放"}$name")
        TransportButton(TwilightIcons.SkipNext, "下一首", stepEnabled, "zoneNext") { onTransport("next") }
    }
}

@Composable
private fun TransportButton(icon: ImageVector, label: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val ink = MaterialTheme.colorScheme.onSurface
    Box(Modifier.size(56.dp).tactilePress(interaction, pressedScale = 0.88f, enabled = enabled).clip(CircleShape)
        .clickable(interaction, LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label }.testTag(tag), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(30.dp), tint = if (enabled) ink else ink.copy(alpha = 0.38f))
    }
}

/** Volume commits once, when the finger lifts, and holds the new value until Core's next report. */
@Composable
private fun VolumeRow(volume: Double?, enabled: Boolean, label: String, tag: String, onCommit: (Double) -> Unit) {
    val colors = MaterialTheme.colorScheme
    var draft by remember { mutableStateOf<Float?>(null) }
    var held by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(held) { if (held != null) { delay(1600); held = null } }
    val shown = draft ?: held ?: (volume ?: 0.0).toFloat().coerceIn(0f, 1f)
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(TwilightIcons.Speaker, null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
        Slider(shown, { draft = it }, Modifier.weight(1f).padding(horizontal = 12.dp).heightIn(min = 44.dp).testTag(tag)
            .semantics { contentDescription = label }, enabled = enabled && volume != null,
            onValueChangeFinished = { draft?.let { held = it; onCommit(it.toDouble()) }; draft = null },
            colors = twilightSliderColors())
        Text(if (volume == null) "--" else volumeLabel(shown.toDouble()), Modifier.widthIn(min = 40.dp),
            style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant, textAlign = TextAlign.End)
    }
}

@Composable
private fun ZoneActions(state: PlayerUiState, actions: PlayerActions, zone: DesktopZoneSession, open: (DetailSheet) -> Unit) {
    val canHandoff = handoffSourceProblem(zone) == null && state.desktopZoneSessions.any { it.id != zone.id }
    Centered(Modifier.padding(top = 22.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ZoneActionTile(TwilightIcons.Handoff, "接力到…", { open(DetailSheet.HANDOFF) }, Modifier.testTag("zoneHandoff"),
                enabled = canHandoff)
            ZoneActionTile(TwilightIcons.Queue, "选歌与队列", {
                actions.openDesktopQueue("zone", zone.id)
            }, Modifier.testTag("zoneQueue"), enabled = zone.groupId == null)
            ZoneActionTile(TwilightIcons.Equalizer, "处理", { open(DetailSheet.DSP) }, Modifier.testTag("zoneDsp"))
            ZoneActionTile(TwilightIcons.SignalWave, "信号链", { open(DetailSheet.PATH) }, Modifier.testTag("zoneSignalPath"))
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.widthIn(min = 72.dp), style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        trailing()
    }
}

@Composable
private fun CardDivider() = HorizontalDivider(Modifier.padding(start = 16.dp), thickness = 0.5.dp,
    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

/** Output facts as Core and the receiver reported them; nothing is inferred on the phone. */
@Composable
private fun OutputCard(zone: DesktopZoneSession) {
    val output = zone.output
    val outputState = zoneOutputState(zone)
    val receiver = output?.takeIf { it.connected }?.receiver
    val bridgeSelected = zone.isBridgeZone || output?.selected == true
    Centered(Modifier.padding(top = 26.dp)) {
        SettingsGroup(Modifier.testTag("zoneOutputCard"), title = "输出") {
            DetailRow("输出", zoneOutputLabel(zone)) { ZoneChip(outputState.label, outputState.tone) }
            if (receiver != null) {
                if (receiver.deviceName.isNotBlank()) { CardDivider(); DetailRow("设备", receiver.deviceName) }
                val format = listOf(backendLabel(receiver.backend, receiver.exclusive),
                    formatAudio(receiver.actualSampleRate, receiver.actualBitDepth)).filter { it.isNotEmpty() }.joinToString(" · ")
                if (format.isNotEmpty()) { CardDivider(); DetailRow("格式", format) }
                CardDivider()
                DetailRow("时钟自适应", if (receiver.adaptiveSupported) "支持" else "不支持") {
                    if (receiver.failed) ZoneChip("接收端故障", ZoneTone.DANGER)
                }
            } else if (!bridgeSelected) {
                val playback = zone.playback
                val format = listOf(backendLabel(playback?.outputBackend), formatAudio(playback?.actualSampleRate, playback?.actualBitDepth))
                    .filter { it.isNotEmpty() }.joinToString(" · ")
                CardDivider()
                DetailRow("格式", format.ifEmpty { "开始播放后显示" })
            }
            output?.error?.let { ZoneErrorNote(it, Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) }
        }
        SupportingText("输出格式来自驱动回报；DAC 内部处理未知。", Modifier.padding(start = 4.dp, top = 10.dp))
    }
}

// ---------------------------------------------------------------------------------------------
// One sync group

@Composable
internal fun GroupDetailScreen(state: PlayerUiState, actions: PlayerActions, groupId: String, back: () -> Unit) {
    val group = state.desktopSyncGroups?.groups?.firstOrNull { it.id == groupId }
    val zones = state.desktopZoneSessions
    val memberIds = group?.members?.map { it.zoneId }.orEmpty()
    var chosen by rememberSaveable(groupId) { mutableStateOf<String?>(null) }
    val followed = state.desktopAudio?.zone?.zoneId
    // DSP and signal path are per member: show the chosen one, else whichever member is already followed.
    val target = chosen?.takeIf { it in memberIds }
        ?: followed?.takeIf { it in memberIds }
        ?: memberIds.firstOrNull { id -> zones.any { it.id == id } }
    FollowZone(state, actions, target)
    var sheet by rememberSaveable { mutableStateOf<DetailSheet?>(null) }
    // The group vanishing while its ungroup dialog is open means Core dissolved it (this phone's command or
    // the computer's): the page ends, and the wall shows the members on their own again.
    LaunchedEffect(group == null) { if (group == null && sheet == DetailSheet.UNGROUP) { sheet = null; back() } }
    LazyColumn(Modifier.fillMaxSize().testTag("groupDetail"),
        contentPadding = PaddingValues(bottom = 24.dp + LocalDockInset.current)) {
        item(key = "back") { ZoneBackRow(back) }
        if (group == null) {
            item(key = "gone") {
                if (state.desktopSyncGroups == null && state.desktopGroupsError == null) LoadingContent("正在读取同步组")
                else EmptyState("同步组已解除", "成员已回到各自的队列。", TwilightIcons.Unlink, actionLabel = "返回声区", onAction = back)
            }
            return@LazyColumn
        }
        val members = groupMembersOf(group, zones)
        item(key = "heading") {
            val phase = groupPhase(group)
            Centered {
                PageHeading(group.name, "同步组 · ${group.members.size} 个声区") {
                    ZoneChip(phase.label, phase.tone, Modifier.testTag("groupDetailPhase"), playing = phase == GroupPhase.PLAYING)
                }
                group.error?.let { ZoneErrorNote(it, Modifier.padding(bottom = 14.dp)) }
            }
        }
        item(key = "player") { GroupPlayer(state, actions, group, members) }
        item(key = "alignment") { GroupAlignment(group) }
        item(key = "automix") {
            Centered(Modifier.padding(top = 26.dp)) {
                DesktopGroupAutoMixSection(group, enabled = state.desktopAudio != null, busy = state.desktopAudioBusy) {
                    actions.desktopGroupAutoMix(group, it)
                }
            }
        }
        item(key = "members") { GroupMembers(group, members, target) { chosen = it } }
        item(key = "actions") {
            Centered(Modifier.padding(top = 22.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    ZoneActionTile(TwilightIcons.Equalizer, "处理", { sheet = DetailSheet.DSP }, Modifier.testTag("zoneDsp"),
                        enabled = target != null)
                    ZoneActionTile(TwilightIcons.SignalWave, "信号链", { sheet = DetailSheet.PATH }, Modifier.testTag("zoneSignalPath"),
                        enabled = target != null)
                    ZoneActionTile(TwilightIcons.Unlink, "解除编组…", { sheet = DetailSheet.UNGROUP }, Modifier.testTag("zoneUngroup"),
                        danger = true)
                }
            }
        }
    }
    val member = target?.let { id -> zones.firstOrNull { it.id == id } }
    if (group != null) when (sheet) {
        DetailSheet.DSP -> if (member != null) ZoneDspSheet(state, actions, member.id, member.name) { sheet = null }
        DetailSheet.PATH -> if (member != null) SignalPathSheet(state, member.id, member.name, member.output?.peerName) { sheet = null }
        DetailSheet.UNGROUP -> UngroupDialog(state, group, actions, onDismiss = { sheet = null }) { sheet = null; back() }
        DetailSheet.HANDOFF, null -> Unit
    }
}

@Composable
private fun GroupPlayer(state: PlayerUiState, actions: PlayerActions, group: DesktopSyncGroup, members: List<DesktopZoneSession?>) {
    val colors = MaterialTheme.colorScheme
    val playing = group.playback?.state == "playing"
    val hasMusic = group.queue != null && group.track != null
    val ready = groupReady(group, state.desktopZoneSessions)
    // The followed snapshot carries the group when its member is followed; transport then fences on the group.
    val following = state.desktopAudio?.group?.id == group.id
    Centered {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(colors.surfaceContainer).padding(12.dp)
            .testTag("groupDetailWindows")) {
            MemberWindows(group, members, colors.surfaceContainer, playing, maxHeight = 220.dp, artworkSize = 1024)
        }
        TrackBlock(group.track, group.playback, null, if (hasMusic) "未知曲目" else "队列为空")
        if (hasMusic) {
            WavySeekBar(rememberZonePosition(group.playback), group.playback?.duration ?: 0.0, playing, enabled = following,
                onSeek = { actions.desktopZoneValue("seek", it) }, modifier = Modifier.padding(top = 18.dp).testTag("zoneSeek"))
            TransportRow(playing, toggleEnabled = following && (playing || ready), stepEnabled = following,
                loading = group.playback?.state == "loading" || group.playback?.state == "buffering", name = group.name) { kind ->
                actions.desktopZoneTransport(kind)
            }
        }
        PillButton("选歌与队列", { actions.openDesktopQueue("group", group.id) }, Modifier.padding(top = 12.dp).testTag("groupQueue"),
            icon = TwilightIcons.Queue, tonal = true)
        if (!ready) Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ToneDot(ZoneTone.WARNING, Modifier.padding(top = 7.dp))
            SupportingText("同步播放需要每个成员都连接支持时钟自适应的输出桥。", Modifier.testTag("groupReadinessHint"))
        }
        VolumeRow(group.playback?.volume, following, "组音量", "zoneVolume") { actions.desktopZoneValue("volume", it) }
        ZoneFeedback(state)
    }
}

@Composable
private fun GroupAlignment(group: DesktopSyncGroup) {
    val phase = groupPhase(group)
    val skew = groupSkew(group)
    val coordination = groupCoordination(group)
    Centered(Modifier.padding(top = 26.dp)) {
        SettingsGroup(Modifier.testTag("groupAlignment"), title = "同步对齐") {
            Column(Modifier.padding(16.dp)) {
                PhaseStepper(phase)
                SkewMeter(skew, Modifier.padding(top = 20.dp))
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (skew.measured) "偏差 ${formatMs(skew.skewMs)} · 观测范围 ±${formatMs(skew.rangeMs)}" else "偏差尚未测量",
                        Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    ZoneChip(skew.label, when { !skew.measured -> ZoneTone.NEUTRAL; skew.aligned -> ZoneTone.LIVE; else -> ZoneTone.WARNING })
                }
                coordination?.error?.let { ZoneErrorNote(it, Modifier.padding(top = 12.dp)) }
            }
        }
    }
}

/** Members in the group's order; the chosen one (outlined) is the one 处理 and 信号链 show. */
@Composable
private fun GroupMembers(group: DesktopSyncGroup, members: List<DesktopZoneSession?>, target: String?, choose: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val coordination = groupCoordination(group)
    val shape = RoundedCornerShape(14.dp)
    Centered(Modifier.padding(top = 26.dp)) {
        SettingsGroup(title = "成员") {
            Column(Modifier.padding(6.dp)) {
                group.members.forEachIndexed { index, entry ->
                    val member = members.getOrNull(index)
                    val selected = entry.zoneId == target
                    val readiness = memberReadiness(member)
                    val dspDelay = coordination?.members?.firstOrNull { it.id == entry.zoneId }?.dspDelayMs?.takeIf { it > 0 }
                    Row(Modifier.fillMaxWidth().clip(shape)
                        .background(if (selected) colors.onSurface.copy(alpha = 0.06f) else Color.Transparent)
                        .zoneRing(selected, colors.primary, shape)
                        .selectable(selected, enabled = member != null, role = Role.RadioButton) { choose(entry.zoneId) }
                        .padding(10.dp).testTag("groupMemberRow-${entry.zoneId}"),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ZoneThumb(member, group = group)
                        Column(Modifier.weight(1f)) {
                            Text(member?.name ?: "已移除的声区", style = MaterialTheme.typography.titleSmall, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Text(member?.let(::zoneOutputLabel) ?: "电脑上已移除", style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull("下游延迟 ${formatDelayMs(entry.downstreamDelayMs)}",
                                dspDelay?.let { "DSP 延迟 ${formatMs(it, 1)}" }).joinToString(" · "),
                                style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1)
                        }
                        ZoneChip(readiness.label, readiness.tone)
                    }
                }
            }
        }
        SupportingText("处理和信号链显示所选成员；下游延迟在建立同步组时设定。", Modifier.padding(start = 4.dp, top = 10.dp))
    }
}
