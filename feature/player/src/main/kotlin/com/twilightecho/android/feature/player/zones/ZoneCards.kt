package com.twilightecho.android.feature.player.zones

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.twilightecho.android.coreclient.DesktopSyncGroup
import com.twilightecho.android.coreclient.DesktopZonePlayback
import com.twilightecho.android.coreclient.DesktopZoneSession
import com.twilightecho.android.coreclient.DesktopZoneTrack
import com.twilightecho.android.ui.components.rememberMotionEnabled
import com.twilightecho.android.ui.components.tactilePress

/*
 * The wall of the 声区 page: one window card per place, and one frame for each sync group whose member
 * windows sit side by side. Cards are flat theme surfaces; the only colour is the cover and state.
 */

private val CardShape = RoundedCornerShape(24.dp)
private val WindowShape = RoundedCornerShape(16.dp)

/** Container colour of a card; the selected one is one tonal step up with the accent outline. */
@Composable
private fun cardContainer(selected: Boolean): Color {
    val colors = MaterialTheme.colorScheme
    val color by animateColorAsState(if (selected) colors.surfaceContainerHigh else colors.surfaceContainer,
        tween(if (rememberMotionEnabled()) 200 else 0), label = "zoneCardContainer")
    return color
}

@Composable
private fun CardFrame(tag: String, selected: Boolean, description: String, onOpen: () -> Unit, modifier: Modifier,
    content: @Composable ColumnScope.(container: Color) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val container = cardContainer(selected)
    val interaction = remember { MutableInteractionSource() }
    Column(modifier.fillMaxWidth().tactilePress(interaction, pressedScale = 0.985f).clip(CardShape).background(container)
        .zoneRing(selected, colors.primary, CardShape)
        .clickable(interaction, LocalIndication.current, role = Role.Button, onClickLabel = "查看详情", onClick = onOpen)
        .semantics { stateDescription = description }
        .padding(12.dp).testTag(tag)) { content(container) }
}

/**
 * One zone's window card. Wide enough cards put the window beside the text; narrow ones (the 2-up grid
 * on a small tablet) stack a landscape window over it.
 */
@Composable
internal fun ZoneCard(zone: DesktopZoneSession, selected: Boolean, onOpen: () -> Unit,
    onTransport: (String) -> Unit, onPickMusic: () -> Unit, modifier: Modifier = Modifier) {
    val light = zoneLight(zone)
    val status = zoneStatus(zone, light, zone.playback)
    CardFrame("zoneCard-${zone.id}", selected, status.label, onOpen, modifier) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val message = zoneWindowMessage(zone, light)
            if (maxWidth >= 300.dp) {
                val window = (maxWidth * 0.34f).coerceIn(112.dp, 132.dp)
                Row {
                    ZoneWindow(light, zone.track, message, Modifier.size(window), WindowShape)
                    Column(Modifier.weight(1f).height(window).padding(start = 14.dp)) {
                        CardInfo(zone, light, status, onTransport, onPickMusic, fixedHeight = true)
                    }
                }
            } else Column {
                ZoneWindow(light, zone.track, message, Modifier.fillMaxWidth().aspectRatio(1.6f), WindowShape)
                Column(Modifier.padding(top = 12.dp)) { CardInfo(zone, light, status, onTransport, onPickMusic, fixedHeight = false) }
            }
        }
        zone.error?.let { ZoneErrorNote(it, Modifier.padding(top = 10.dp), maxLines = 2) }
    }
}

@Composable
private fun ColumnScope.CardInfo(zone: DesktopZoneSession, light: ZoneLight, status: ZoneBadge,
    onTransport: (String) -> Unit, onPickMusic: () -> Unit, fixedHeight: Boolean) {
    val colors = MaterialTheme.colorScheme
    CardTitle(zone.name, status, light == ZoneLight.PLAYING)
    Text(zoneOutputLabel(zone), Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodyMedium,
        color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    if (fixedHeight) Spacer(Modifier.weight(1f)) else Spacer(Modifier.height(12.dp))
    when (light) {
        ZoneLight.PLAYING, ZoneLight.PAUSED -> {
            val playing = light == ZoneLight.PLAYING
            // Pausing is always allowed; starting needs the place's own output. A tap while another
            // command is in flight is dropped by the view model instead of greying every card.
            CardTrack(zone.track, zone.playback, playing, enabled = playing || !zoneOutputDown(zone),
                tag = "zonePlayPause-${zone.id}", name = zone.name, onToggle = { onTransport(if (playing) "pause" else "play") })
        }
        ZoneLight.EMPTY -> if (!zone.isBridgeZone) Row(verticalAlignment = Alignment.CenterVertically) {
            Text("在音乐库选一首歌", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            CardTextAction("选择音乐", onPickMusic, Modifier.testTag("zonePickMusic-${zone.id}"))
        } else CardCaption("可从其他声区接力到这里")
        // Core's own error note under the card already says what to do.
        ZoneLight.OFFLINE, ZoneLight.UNPAIRED -> if (zone.error == null) CardCaption(when {
            zoneOutputState(zone) == ZoneOutputState.CONNECTING -> "正在连接输出桥…"
            zone.isBridgeZone -> "请在电脑上连接输出桥"
            else -> "打开详情可重新连接"
        })
    }
}

@Composable
private fun CardTitle(name: String, status: ZoneBadge, playing: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, Modifier.weight(1f).padding(end = 8.dp),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        ZoneChip(status.label, status.tone, playing = playing)
    }
}

@Composable
private fun CardCaption(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@Composable
internal fun CardTextAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    Text(label, modifier.tactilePress(interaction, pressedScale = 0.94f).clip(RoundedCornerShape(50))
        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
        .clickable(interaction, LocalIndication.current, role = Role.Button, onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
}

/** Track line, slim progress and the card's own play/pause. */
@Composable
private fun CardTrack(track: DesktopZoneTrack?, playback: DesktopZonePlayback?, playing: Boolean, enabled: Boolean,
    tag: String, name: String, onToggle: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(track?.title?.takeIf { it.isNotBlank() } ?: "未知曲目", style = MaterialTheme.typography.titleSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            val subtitle = trackSubtitle(track)
            if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        MorphPlayButton(playing, onToggle, Modifier.testTag(tag), size = 44.dp, enabled = enabled, tonal = true,
            label = "${if (playing) "暂停" else "播放"}$name")
    }
    val position = rememberZonePosition(playback)
    val progressColor by animateColorAsState(if (playing) colors.primary else colors.onSurfaceVariant,
        tween(if (rememberMotionEnabled()) 240 else 0), label = "cardProgress")
    SlimProgress(position, playback?.duration ?: 0.0, Modifier.padding(top = 8.dp), color = progressColor)
}

/** A sync group: ONE frame holding the member windows side by side, with shared music and transport. */
@Composable
internal fun GroupCard(group: DesktopSyncGroup, members: List<DesktopZoneSession?>, zones: List<DesktopZoneSession>,
    selected: Boolean, onOpen: () -> Unit, onTransport: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val phase = groupPhase(group)
    val playing = group.playback?.state == "playing"
    val hasMusic = group.queue != null && group.track != null
    val ready = groupReady(group, zones)
    CardFrame("zoneGroupCard-${group.id}", selected, phase.label, onOpen, modifier) { container ->
        MemberWindows(group, members, container, playing)
        Column(Modifier.padding(start = 2.dp, top = 14.dp)) {
            CardTitle(group.name, ZoneBadge(phase.label, phase.tone), phase == GroupPhase.PLAYING)
            Text(groupCaption(group, members, ready), Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(12.dp))
            if (hasMusic) CardTrack(group.track, group.playback, playing, enabled = playing || ready,
                tag = "zonePlayPause-${group.id}", name = group.name, onToggle = { onTransport(if (playing) "pause" else "play") })
            else CardCaption("队列为空")
        }
        group.error?.let { ZoneErrorNote(it, Modifier.padding(top = 10.dp), maxLines = 2) }
    }
}

/**
 * Member windows inside one frame, each lit by its own output, joined by a round link badge on every
 * seam. [ring] is the frame colour so the badge reads as cut into the windows.
 */
@Composable
internal fun MemberWindows(group: DesktopSyncGroup, members: List<DesktopZoneSession?>, ring: Color, playing: Boolean,
    modifier: Modifier = Modifier, maxHeight: Dp = 132.dp, artworkSize: Int = 512) {
    val shown = members.ifEmpty { listOf(null) }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val gap = 10.dp
        val count = shown.size
        val width = (maxWidth - gap * (count - 1)) / count
        val height = min(width * (if (count <= 2) 0.78f else 1f), maxHeight)
        Box {
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                shown.forEach { member ->
                    val light = member?.let { memberLight(it, group) } ?: ZoneLight.OFFLINE
                    val message = if (member == null) "声区已移除" else zoneWindowMessage(member, light)
                    Column(Modifier.width(width)) {
                        ZoneWindow(light, group.track, message, Modifier.fillMaxWidth().height(height), WindowShape, artworkSize)
                        Row(Modifier.padding(top = 8.dp, start = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            ToneDot(memberReadiness(member).tone, size = 7.dp)
                            Text(member?.name ?: "已移除", Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            for (seam in 1 until count) {
                val x = width * seam + gap * (seam - 1) + gap / 2 - 14.dp
                LinkBadge(Modifier.offset(x = x, y = height / 2 - 14.dp), ring, active = playing)
            }
        }
    }
}
