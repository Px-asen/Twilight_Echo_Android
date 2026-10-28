package com.twilightecho.android.feature.player

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import com.twilightecho.android.coreclient.CoreOperatingMode
import com.twilightecho.android.coreclient.LibrarySource
import com.twilightecho.android.ui.components.*
import com.twilightecho.android.ui.theme.LocalTwilightAccents
import com.twilightecho.android.ui.theme.TwilightTokens
import java.util.Locale

internal enum class SettingsDestination { OVERVIEW, APPEARANCE, LYRICS, STREAMING, AUDIO, LIBRARY, REMOTE }

@Composable
internal fun SettingsTabScreen(state: PlayerUiState, actions: PlayerActions,
    destination: SettingsDestination, navigate: (SettingsDestination) -> Unit) {
    val context = LocalContext.current
    DisposableEffect(destination) {
        if (destination == SettingsDestination.REMOTE) actions.startDiscovery()
        onDispose { if (destination == SettingsDestination.REMOTE) actions.stopDiscovery() }
    }
    var manualConnection by remember { mutableStateOf(false) }
    if (destination == SettingsDestination.AUDIO && state.operatingMode is CoreOperatingMode.StandaloneLocal) {
        LifecycleStartEffect(Unit) {
            actions.monitorAudio(true)
            onStopOrDispose { actions.monitorAudio(false) }
        }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("settings-${destination.name}"),
        contentPadding = PaddingValues(bottom = 24.dp + LocalDockInset.current)) {
        item {
            if (destination != SettingsDestination.OVERVIEW) {
                Row(Modifier.padding(start = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    QuietIconButton(TwilightIcons.ArrowBack, "返回设置", { navigate(SettingsDestination.OVERVIEW) })
                    Text("设置", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val (title, subtitle) = when (destination) {
                SettingsDestination.OVERVIEW -> "设置" to null
                SettingsDestination.AUDIO -> "音频与 DSP" to null
                SettingsDestination.LIBRARY -> "本地音乐" to null
                SettingsDestination.REMOTE -> "连接电脑" to null
                SettingsDestination.APPEARANCE -> "外观" to null
                SettingsDestination.LYRICS -> "歌词" to null
                SettingsDestination.STREAMING -> "网易云音质" to null
            }
            PageHeading(title, subtitle, Modifier.padding(horizontal = 20.dp)
                .padding(top = if (destination == SettingsDestination.OVERVIEW) 12.dp else 0.dp))
        }
        when (destination) {
            SettingsDestination.OVERVIEW -> item {
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    SettingsGroup(title = "个性化") {
                        SettingsItem(TwilightIcons.Sparkles, "外观", onClick = { navigate(SettingsDestination.APPEARANCE) },
                            modifier = Modifier.testTag("openAppearance"))
                        GroupDivider()
                        SettingsItem(TwilightIcons.MusicNote, "歌词", onClick = { navigate(SettingsDestination.LYRICS) },
                            modifier = Modifier.testTag("openLyricsSettings"))
                        GroupDivider()
                        SettingsItem(TwilightIcons.SignalWave, "网易云音质", onClick = { navigate(SettingsDestination.STREAMING) },
                            modifier = Modifier.testTag("openStreamingSettings"),
                            trailing = { Text(state.preferences.ncmQuality.label, style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant) })
                    }
                    SettingsGroup(title = "聆听方式") {
                        SettingsItem(TwilightIcons.MusicNote, "播放通知", "通知与锁屏显示", onClick = {
                            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                        }, modifier = Modifier.testTag("openNotificationSettings"))
                        GroupDivider()
                        SettingsItem(TwilightIcons.SignalWave, "插件与音源", "插件仓库、网易云音乐与账号", onClick = actions.openPlugins)
                        GroupDivider()
                        SettingsItem(TwilightIcons.Equalizer, "音频与 DSP", "输出、参数均衡器、DSP 处理链与场景",
                            onClick = { navigate(SettingsDestination.AUDIO) })
                        GroupDivider()
                        SettingsItem(TwilightIcons.Library, "本地音乐", "${state.phoneTrackCount} 首歌曲 · 扫描与更新",
                            onClick = { navigate(SettingsDestination.LIBRARY) })
                    }
                    SettingsGroup(title = "电脑") {
                        SettingsItem(TwilightIcons.Computer, "连接电脑",
                            if (state.librarySource is LibrarySource.Desktop)
                                "${state.librarySource.hostLabel} · ${if (state.librarySource.connected) "已连接" else "未连接"}"
                            else "未连接", onClick = { navigate(SettingsDestination.REMOTE) })
                    }
                    SettingsGroup(title = "关于") {
                        SettingsItem(TwilightIcons.Sparkles, "Twilight Echo", "版本 0.1.0")
                    }
                }
            }
            SettingsDestination.APPEARANCE -> appearanceSettings(state, actions)
            SettingsDestination.LYRICS -> lyricsSettings(state, actions)
            SettingsDestination.STREAMING -> streamingQualitySettings(state, actions)
            SettingsDestination.AUDIO -> item {
                AudioSignalSummary(state.diagnostics, Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                if (state.operatingMode is CoreOperatingMode.RemoteDesktopCore) {
                    GlassCard(Modifier.padding(20.dp).fillMaxWidth()) {
                        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconTile(TwilightIcons.Computer, Modifier.padding(end = 14.dp))
                            SupportingText("当前显示电脑的音频链路。DSP 与输出设置请在电脑端调整。")
                        }
                    }
                    return@item
                }
                AudioSettingsPanel(state, actions)
            }
            SettingsDestination.LIBRARY -> item {
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    LibraryStatsHero(state)
                    GlassCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconTile(TwilightIcons.RadarScan, Modifier.padding(end = 14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("曲库扫描", style = MaterialTheme.typography.titleMedium)
                                    SupportingText("扫描手机中的音频文件，更新你的音乐库。")
                                }
                            }
                            if (state.isScanningLocalMusic) LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)),
                                color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            PillButton(if (state.isScanningLocalMusic) "正在扫描…" else "扫描本地音乐", actions.scan,
                                Modifier.fillMaxWidth(), TwilightIcons.RadarScan, enabled = !state.isScanningLocalMusic)
                            state.scanStatusMessage?.let { SupportingText(it, Modifier.testTag("scanStatus")) }
                            if (state.scanDetails.isNotEmpty()) {
                                var detailsOpen by remember(state.scanDetails) { mutableStateOf(false) }
                                TextButton(onClick = { detailsOpen = !detailsOpen }, Modifier.testTag("scanDetailsToggle")) {
                                    Text(if (detailsOpen) "收起详情" else "详情（${state.scanDetails.size}）")
                                }
                                if (detailsOpen) state.scanDetails.forEach { SupportingText(it, Modifier.testTag("scanDetail")) }
                            }
                        }
                    }
                    GlassCard(Modifier.fillMaxWidth().testTag("musicFoldersCard")) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconTile(TwilightIcons.Library, Modifier.padding(end = 14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("音乐文件夹", style = MaterialTheme.typography.titleMedium)
                                    SupportingText("CUE 分轨和 APE、WavPack 文件从这些文件夹读取。")
                                }
                            }
                            state.musicFolders.forEach { folder ->
                                Row(Modifier.fillMaxWidth().testTag("musicFolder"), verticalAlignment = Alignment.CenterVertically) {
                                    Text(folder.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 2,
                                        overflow = TextOverflow.Ellipsis)
                                    QuietIconButton(TwilightIcons.Close, "移除 ${folder.label}", { actions.removeMusicFolder(folder.uri) },
                                        enabled = !state.isScanningLocalMusic)
                                }
                            }
                            PillButton("添加文件夹", actions.addMusicFolder, Modifier.fillMaxWidth().testTag("addMusicFolder"),
                                TwilightIcons.Plus, enabled = !state.isScanningLocalMusic, tonal = true)
                        }
                    }
                    if (state.libraryError != null) GlassCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(state.libraryError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                            PillButton("重新载入音乐库", actions.refreshLibrary, tonal = true)
                        }
                    }
                }
            }
            SettingsDestination.REMOTE -> item {
                val remote = state.librarySource as? LibrarySource.Desktop
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    CoreTopologyCard(state, actions, Modifier.padding(top = 4.dp, bottom = 14.dp))
                    SectionHeading("局域网中的电脑", "重新搜索", actions.refreshDiscovery)
                    state.desktopDiscovery.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                    if (state.desktopDiscovery.scanning) LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)),
                        color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    if (state.desktopDiscovery.devices.isEmpty()) GlassCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconTile(TwilightIcons.RadarScan, Modifier.padding(end = 14.dp), size = 48.dp,
                                container = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), content = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f)) {
                                Text(if (state.desktopDiscovery.error == null) "正在寻找你的电脑" else "暂时无法发现电脑",
                                    style = MaterialTheme.typography.titleMedium)
                                SupportingText("请连接同一 Wi-Fi，并在电脑端开启「局域网远程控制」。发现后会显示在这里。", Modifier.padding(top = 2.dp))
                            }
                        }
                    }
                    state.desktopDiscovery.devices.forEach { device ->
                        DesktopDeviceCard(device, remote?.baseUrl == device.baseUrl && remote.connected,
                            state.isConnectingRemoteCore, onForget = { actions.forgetDesktop(device) }.takeIf { device.trusted }) {
                            actions.selectDesktop(device)
                        }
                    }
                    state.remoteStatusMessage?.let {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(if (state.isConnectingRemoteCore) TwilightTokens.SignalWarm else MaterialTheme.colorScheme.primary,
                                pulse = state.isConnectingRemoteCore, size = 6.dp)
                            Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 4.dp).testTag("remoteStatus"))
                        }
                    }
                    if (state.isConnectingRemoteCore) {
                        state.desktopApprovalCode?.let { code ->
                            Text("验证码 ${code.take(3)} ${code.drop(3)}", style = MaterialTheme.typography.headlineMedium,
                                modifier = Modifier.testTag("desktopApprovalCode"))
                            SupportingText("电脑会弹出连接请求并显示验证码。两边一致时再允许；不一致请拒绝，可能有人在冒充这台电脑。")
                        }
                        if (state.waitingForDesktopApproval && state.desktopApprovalCode == null)
                            SupportingText("电脑会弹出连接请求。确认后，这部手机下次无需再次授权。")
                        PillButton("取消连接", actions.cancelConnection, Modifier.fillMaxWidth(), TwilightIcons.Close, tonal = true)
                    }
                    if (remote != null) PillButton("切回本机音乐", actions.useLocal, Modifier.fillMaxWidth(), TwilightIcons.Phone,
                        enabled = !state.isConnectingRemoteCore, tonal = true)
                    TextButton(onClick = { manualConnection = !manualConnection }, modifier = Modifier.testTag("manualDesktopConnection")) {
                        Text(if (manualConnection) "收起手动连接" else "找不到电脑？手动连接", color = MaterialTheme.colorScheme.primary)
                    }
                    if (manualConnection) GlassCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("手动连接", style = MaterialTheme.typography.titleMedium)
                            SupportingText("适用于旧版电脑或网络屏蔽了自动发现的情况。")
                            OutlinedTextField(state.remoteHostInput, actions.remoteHost, label = { Text("电脑地址") },
                                placeholder = { Text("192.168.1.100:18492") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), shape = RoundedCornerShape(16.dp),
                                colors = twilightFieldColors(), enabled = !state.isConnectingRemoteCore,
                                modifier = Modifier.fillMaxWidth().testTag("remoteHost"))
                            OutlinedTextField(state.remotePinInput, actions.remotePin, label = { Text("六位配对码") },
                                supportingText = { Text("已配对的电脑可留空") }, singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), shape = RoundedCornerShape(16.dp),
                                colors = twilightFieldColors(), enabled = !state.isConnectingRemoteCore,
                                modifier = Modifier.fillMaxWidth().testTag("remotePin"))
                            PillButton(if (state.isConnectingRemoteCore) "正在连接…" else "连接电脑 Core", actions.connectRemote,
                                Modifier.fillMaxWidth(), TwilightIcons.ArrowRight,
                                enabled = !state.isConnectingRemoteCore && state.remoteHostInput.isNotBlank())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupDivider() = HorizontalDivider(Modifier.padding(start = 70.dp, end = 16.dp), thickness = 0.5.dp,
    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

@Composable
internal fun twilightFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f),
    disabledBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
    focusedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
    unfocusedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
    disabledContainerColor = Color.Transparent,
    cursorColor = MaterialTheme.colorScheme.primary,
    focusedLabelColor = MaterialTheme.colorScheme.primary)

@Composable
private fun LibraryStatsHero(state: PlayerUiState) {
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.fillMaxWidth().clip(shape)
        .background(MaterialTheme.colorScheme.surfaceContainer).padding(20.dp)) {
        Text("${state.phoneTrackCount} 首歌曲", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
        SupportingText("保存在这部手机上", Modifier.padding(top = 4.dp))
    }
}

/** Decoder, processing and negotiated output are separate, readable groups. */
@Composable
internal fun AudioSignalSummary(diagnostics: AudioDiagnosticsUiModel, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    val hasOutput = diagnostics.actualSampleRateHz > 0
    val hasSource = diagnostics.codec.isNotBlank() || diagnostics.sourceSampleRateHz > 0
    GlassCard(modifier.fillMaxWidth().testTag("audioSignalSummary"), RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("音频链路", style = MaterialTheme.typography.headlineSmall)
            if (!hasSource && !hasOutput) {
                Text("选择歌曲查看音频链路", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
            } else {
                SignalStage("音源", listOfNotNull(diagnostics.codec.takeIf { it.isNotBlank() }?.uppercase(),
                    audioResolution(diagnostics.sourceSampleRateHz, diagnostics.sourceBitDepth).takeIf { it.isNotBlank() })
                    .joinToString(" · "))
                if (hasOutput) {
                    SignalStage("声音处理", if (diagnostics.dspActive) "均衡与音效" else "标准播放")
                    SignalStage("播放输出 · ${if (diagnostics.exclusive) "独占" else "共享"}",
                        listOfNotNull(diagnostics.deviceName?.takeIf { it.isNotBlank() },
                            audioResolution(diagnostics.actualSampleRateHz, diagnostics.actualBitDepth, diagnostics.actualSampleFormat))
                            .joinToString("\n"))
                    if (diagnostics.outputPerfect) Text("逐位输出", style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = { expanded = !expanded }, modifier = Modifier
                        .heightIn(min = 48.dp).testTag("audioOutputDetails"), contentPadding = PaddingValues(0.dp)) {
                        Text(if (expanded) "收起输出详情" else "输出详情", style = MaterialTheme.typography.titleMedium)
                    }
                    if (expanded) {
                        Column(Modifier.testTag("audioOutputFacts"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            if (diagnostics.requestedSampleRateHz > 0) SignalStage("引擎请求",
                                audioResolution(diagnostics.requestedSampleRateHz, 0, diagnostics.requestedSampleFormat))
                            SignalStage("播放流", formatWithChannels(diagnostics.actualSampleRateHz, diagnostics.actualBitDepth,
                                diagnostics.actualSampleFormat, diagnostics.actualChannels))
                            if (diagnostics.hardwareSampleRateHz > 0) SignalStage("系统硬件格式",
                                formatWithChannels(diagnostics.hardwareSampleRateHz, diagnostics.hardwareBitDepth,
                                    diagnostics.hardwareSampleFormat, diagnostics.hardwareChannels))
                            bluetoothProfileLabel(diagnostics.bluetoothProfile)?.let { profile ->
                                SignalStage("蓝牙", profile)
                                // Android gives the codec list and the negotiated codec only to system apps.
                                SignalStage("蓝牙编码", "仅系统应用可读取")
                            }
                        }
                    }
                } else {
                    Text("播放歌曲查看输出", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SignalStage(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (value.isNotBlank()) Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

internal fun bluetoothProfileLabel(profile: String): String? = when (profile) {
    "a2dp" -> "A2DP"
    "le_audio" -> "LE Audio"
    "call" -> "通话线路"
    else -> null
}

private fun formatWithChannels(rate: Int, bits: Int, format: String?, channels: Int): String =
    listOfNotNull(audioResolution(rate, bits, format).takeIf { it.isNotBlank() },
        when (channels) { 0 -> null; 1 -> "单声道"; 2 -> "双声道"; else -> "$channels 声道" }).joinToString(" · ")

internal fun audioResolution(sampleRate: Int, bits: Int, format: String? = null): String {
    val depth = when {
        format?.startsWith("float", true) == true -> "${bits.takeIf { it > 0 } ?: 32} 位浮点"
        bits > 0 -> "$bits 位"
        format?.startsWith("int") == true -> "${format.removePrefix("int")} 位"
        else -> null
    }
    return listOfNotNull(sampleRate.takeIf { it > 0 }?.let {
        "${java.math.BigDecimal.valueOf(it.toLong(), 3).stripTrailingZeros().toPlainString()} kHz"
    }, depth).joinToString(" / ")
}

@Composable
internal fun SettingsSwitch(title: String, subtitle: String, checked: Boolean, toggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle.isNotBlank()) SupportingText(subtitle, Modifier.padding(top = 2.dp))
        }
        Switch(checked, { toggle() }, modifier = Modifier.heightIn(min = 48.dp), colors = twilightSwitchColors())
    }
}

@Composable
internal fun twilightSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = MaterialTheme.colorScheme.onPrimary, checkedTrackColor = MaterialTheme.colorScheme.primary,
    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
    uncheckedTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
    uncheckedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f))

@Composable
internal fun twilightSliderColors() = SliderDefaults.colors(
    thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary,
    inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
    activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent)

@Composable
internal fun EqSlider(label: String, value: Double, onCommit: (Double) -> Unit, tag: String) {
    var draft by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column(Modifier.padding(horizontal = 18.dp, vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(String.format(Locale.US, "%+.1f dB", draft), style = MaterialTheme.typography.labelLarge,
                color = if (draft == 0f) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
        }
        Slider(draft, { draft = it }, valueRange = -12f..12f, onValueChangeFinished = { onCommit(draft.toDouble()) },
            colors = twilightSliderColors(), modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag(tag))
    }
}

internal fun presetLabel(value: String): String = when (value) {
    "Flat" -> "平直"; "Bass Boost" -> "低音"; "Vocal" -> "人声"
    "Rock" -> "摇滚"; "Electronic" -> "电子"; "Classical" -> "古典"
    else -> value
}
