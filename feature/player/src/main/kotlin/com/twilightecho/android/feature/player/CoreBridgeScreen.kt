package com.twilightecho.android.feature.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.twilightecho.android.coreclient.CoreOperatingMode
import com.twilightecho.android.coreclient.DesktopCoreDevice
import com.twilightecho.android.coreclient.LibrarySource
import com.twilightecho.android.ui.components.*
import com.twilightecho.android.ui.theme.TwilightTokens

/** Which computer the phone is linked to, whether it answers, and where the sound comes out. */
internal data class CoreTopology(val coreName: String, val coreOnDesktop: Boolean, val live: Boolean,
    val outputOnDesktop: Boolean) {
    val healthy get() = live || !coreOnDesktop
    val headline get() = when {
        !coreOnDesktop -> "还没有连接电脑"
        !live -> "$coreName 未连接"
        outputOnDesktop -> "正在用 $coreName 播放"
        else -> "已连接 $coreName"
    }
}

internal fun PlayerUiState.coreTopology(): CoreTopology {
    val remote = librarySource as? LibrarySource.Desktop
    return CoreTopology(remote?.hostLabel ?: "此手机", remote != null, remote?.connected == true,
        operatingMode is CoreOperatingMode.RemoteDesktopCore)
}

/** Healthy links show in the accent; a broken desktop link turns warm. */
@Composable
private fun CoreTopology.signal(): Color = if (healthy) MaterialTheme.colorScheme.primary else TwilightTokens.SignalWarm

@Composable
internal fun CoreTopologyCard(state: PlayerUiState, actions: PlayerActions, modifier: Modifier = Modifier) {
    val topology = state.coreTopology()
    val colors = MaterialTheme.colorScheme
    val motionEnabled = rememberMotionEnabled()
    Column(modifier.fillMaxWidth().testTag("coreTopology").clip(RoundedCornerShape(18.dp))
        .background(colors.surfaceContainer).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (topology.coreOnDesktop) StatusDot(topology.signal(), pulse = topology.live)
            Text(topology.headline, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = if (topology.coreOnDesktop) 4.dp else 0.dp))
        }
        Text(if (topology.coreOnDesktop) "电脑里的歌曲会和手机里的放在同一个音乐库，断开后也不会消失。"
            else "连接后，电脑里的歌曲会和手机里的放在同一个音乐库。",
            style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        AnimatedVisibility(
            visible = topology.coreOnDesktop,
            enter = TwilightMotion.sectionExpand(motionEnabled),
            exit = TwilightMotion.sectionCollapse(motionEnabled)
        ) {
            Column {
                Text("声音从哪里播放", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 20.dp))
                OutputSwitch(topology, actions, Modifier.padding(top = 10.dp))
            }
        }
    }
}

@Composable
private fun OutputSwitch(topology: CoreTopology, actions: PlayerActions, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(50))
        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)).padding(4.dp)) {
        OutputOption("此手机", TwilightIcons.Phone, !topology.outputOnDesktop, true, "coreOutputPhone",
            Modifier.weight(1f)) { actions.playbackTarget(true) }
        OutputOption(topology.coreName, TwilightIcons.Computer,
            topology.outputOnDesktop, topology.live, "coreOutputDesktop", Modifier.weight(1f)) { actions.playbackTarget(false) }
    }
}

@Composable
private fun OutputOption(label: String, icon: ImageVector, selected: Boolean, enabled: Boolean, tag: String,
    modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val motionEnabled = rememberMotionEnabled()
    val interaction = remember { MutableInteractionSource() }
    val background by animateColorAsState(if (selected) colors.primary else Color.Transparent,
        tween(if (motionEnabled) 180 else 0), label = "outputBg")
    val content by animateColorAsState(when {
        selected -> colors.onPrimary
        enabled -> colors.onSurface.copy(alpha = 0.8f)
        else -> colors.onSurface.copy(alpha = 0.3f)
    }, tween(if (motionEnabled) 180 else 0), label = "outputFg")
    Row(modifier.tactilePress(interaction, pressedScale = 0.95f, enabled = enabled && !selected)
        .heightIn(min = 46.dp).clip(RoundedCornerShape(50)).background(background)
        .clickable(interactionSource = interaction, indication = LocalIndication.current, enabled = enabled && !selected, role = Role.RadioButton, onClick = onClick)
        .testTag(tag).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Icon(icon, null, Modifier.size(18.dp), tint = content)
        Text(label, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
internal fun StatusDot(color: Color, pulse: Boolean = false, size: Dp = 8.dp) {
    val motion = rememberLyricsMotionEnabled()
    val halo = if (pulse && motion) {
        val transition = rememberInfiniteTransition(label = "statusDot")
        transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing)),
            label = "statusHalo").value
    } else 0f
    Canvas(Modifier.size(size * 2)) {
        if (halo > 0f) drawCircle(color.copy(alpha = 0.4f * (1f - halo)), radius = this.size.minDimension / 2 * (0.5f + halo * 0.5f))
        drawCircle(color, radius = size.toPx() / 2)
    }
}

@Composable
internal fun DesktopDeviceCard(device: DesktopCoreDevice, connected: Boolean, busy: Boolean,
    onForget: (() -> Unit)? = null, onConnect: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val motionEnabled = rememberMotionEnabled()
    var confirmForget by remember { mutableStateOf(false) }
    val status = when {
        connected -> "已连接"
        !device.discovered -> "不在线"
        device.trusted -> "可直接连接"
        else -> "首次连接需在电脑上确认"
    }
    val borderColor by animateColorAsState(
        if (connected) colors.primary.copy(alpha = 0.7f) else Color.Transparent,
        tween(if (motionEnabled) 220 else 0),
        label = "desktopBorder"
    )
    val iconContainer by animateColorAsState(
        if (connected) colors.primary else colors.onSurface.copy(alpha = 0.08f),
        tween(if (motionEnabled) 220 else 0),
        label = "desktopIconBg"
    )
    val iconContent by animateColorAsState(
        if (connected) colors.onPrimary else colors.onSurface,
        tween(if (motionEnabled) 220 else 0),
        label = "desktopIconFg"
    )
    val shape = RoundedCornerShape(14.dp)
    Row(Modifier.fillMaxWidth().testTag("desktop-${device.id}").clip(shape)
        .background(colors.surfaceContainer)
        .border(1.5.dp, borderColor, shape)
        .padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        IconTile(TwilightIcons.Computer, size = 48.dp,
            container = iconContainer,
            content = iconContent)
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(device.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            SupportingText(status, Modifier.padding(top = 2.dp), maxLines = 1)
        }
        if (!connected) PillButton(if (device.trusted) "重连" else "连接", onConnect, enabled = !busy, tonal = device.trusted)
        if (onForget != null) TextButton({ confirmForget = true }, enabled = !busy,
            modifier = Modifier.testTag("forgetDesktop-${device.id}")) { Text("忘记") }
    }
    if (confirmForget && onForget != null) AlertDialog(onDismissRequest = { confirmForget = false },
        title = { Text("忘记 ${device.name}？") },
        text = { Text("手机将删除这台电脑的授权和已缓存的歌曲，并断开与它的连接。之后再次连接需要在电脑上重新确认。") },
        confirmButton = { TextButton({ confirmForget = false; onForget() }, Modifier.testTag("confirmForgetDesktop")) { Text("忘记此电脑") } },
        dismissButton = { TextButton({ confirmForget = false }) { Text("取消") } })
}
