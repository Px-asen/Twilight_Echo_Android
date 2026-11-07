package com.twilightecho.android.feature.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.twilightecho.android.coreclient.CoreOperatingMode
import com.twilightecho.android.nativeintegration.usb.*
import com.twilightecho.android.ui.components.SupportingText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.math.BigDecimal

data class UsbHardwareVolumeActions(
    val read: suspend (String) -> UsbHardwareVolumeSnapshot = { error("USB 直连不可用") },
    val volume: suspend (UsbHardwareControlId, Int) -> UsbAudioVolumeSnapshot = { _, _ -> error("USB 直连不可用") },
    val mute: suspend (UsbHardwareControlId, Boolean) -> Boolean = { _, _ -> error("USB 直连不可用") },
)

@Composable
internal fun UsbHardwareVolumeSetting(state: PlayerUiState, actions: UsbHardwareVolumeActions) {
    val deviceId = state.eqConfig.dsp.outputDeviceId
    val active = state.operatingMode is CoreOperatingMode.StandaloneLocal &&
        state.diagnostics.outputBackend == "usb-direct" && state.diagnostics.exclusive
    var open by remember(deviceId) { mutableStateOf(false) }
    TextButton(onClick = { open = true }, enabled = active,
        modifier = Modifier.padding(horizontal = 10.dp).testTag("usb-hardware-volume-open")) { Text("DAC 硬件音量") }
    if (open) key(deviceId, active) {
        UsbHardwareVolumeDialog(deviceId, active, actions) { open = false }
    }
}

@Composable
private fun UsbHardwareVolumeDialog(deviceId: String, active: Boolean, actions: UsbHardwareVolumeActions, close: () -> Unit) {
    var snapshot by remember { mutableStateOf<UsbHardwareVolumeSnapshot?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    suspend fun perform(action: suspend () -> Unit) {
        busy = true
        error = null
        try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            snapshot = null // After a failed write/readback, no old value is presented as current.
            error = failure.message ?: "DAC 音量操作失败，请重新读取"
        } finally { busy = false }
    }
    suspend fun read() = perform { snapshot = actions.read(deviceId) }
    fun updateChannel(id: UsbHardwareControlId, edit: (UsbHardwareChannel) -> UsbHardwareChannel) {
        snapshot = snapshot?.let { it.copy(channels = it.channels.map { channel -> if (channel.id == id) edit(channel) else channel }) }
    }
    LaunchedEffect(Unit) { if (active) read() }
    AlertDialog(onDismissRequest = close,
        title = { Text("DAC 硬件音量") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("直接调节 DAC，不改变应用音量和 DSP。")
                if (!active) Text("USB 接口已释放，点播放后再读取。", Modifier.testTag("usb-hardware-released"))
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("usb-hardware-busy"))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("usb-hardware-error")) }
                snapshot?.let { current ->
                    if (current.channels.isEmpty()) Text("当前 USB 播放路径未提供可读的硬件音量或静音控制。")
                    current.channels.forEach { channel -> key(channel.id) {
                        UsbHardwareChannelControls(channel, !busy && active, onVolume = { value ->
                            scope.launch { perform {
                                val actual = actions.volume(channel.id, value)
                                updateChannel(channel.id) { it.copy(volume = actual) }
                                if (actual.currentDb256 != value) error = "DAC 未接受请求；实际回读 ${usbVolumeDbText(actual.currentDb256)} dB。"
                            } }
                        }, onMute = { value ->
                            scope.launch { perform {
                                val actual = actions.mute(channel.id, value)
                                updateChannel(channel.id) { it.copy(muted = actual) }
                                if (actual != value) error = "DAC 未接受静音请求；已显示实际回读状态。"
                            } }
                        })
                    } }
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("完成") } },
        dismissButton = { TextButton(onClick = { scope.launch { read() } }, enabled = active && !busy,
            modifier = Modifier.testTag("usb-hardware-refresh")) { Text("重新读取") } })
}

@Composable
private fun UsbHardwareChannelControls(channel: UsbHardwareChannel, enabled: Boolean,
    onVolume: (Int) -> Unit, onMute: (Boolean) -> Unit) {
    val tag = "usb-hardware-${channel.id.unitId}-${channel.id.channel}"
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${if (channel.id.channel == 0) "主音量" else "声道 ${channel.id.channel}"} · 单元 ${channel.id.unitId}",
            style = MaterialTheme.typography.titleSmall)
        channel.volume?.let { volume ->
            Text("回读 ${usbVolumeDbText(volume.currentDb256)} dB${if (channel.volumeWritable) "" else " · 只读"}",
                Modifier.testTag("$tag-current"))
            if (channel.volumeWritable) {
                var draft by remember(volume) { mutableStateOf(usbVolumeDbText(volume.currentDb256)) }
                val parsed = usbVolumeDb256(draft)
                val valid = parsed != null && volume.ranges.any { it.contains(parsed) }
                OutlinedTextField(value = draft, onValueChange = { draft = it }, enabled = enabled,
                    label = { Text("目标音量 / dB") }, singleLine = true,
                    isError = draft.isNotBlank() && !valid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().testTag("$tag-input"))
                SupportingText(usbVolumeRangesText(volume.ranges))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (volume.ranges.any { it.contains(-32768) }) TextButton(onClick = { draft = "-∞" }, enabled = enabled) { Text("−∞") }
                    Button(onClick = { onVolume(parsed!!) }, enabled = enabled && valid && parsed != volume.currentDb256,
                        modifier = Modifier.testTag("$tag-apply")) { Text("设置音量") }
                }
            }
        }
        channel.muted?.let { muted ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(if (channel.muteWritable) "硬件静音" else "硬件静音 · 只读")
                Switch(checked = muted, onCheckedChange = onMute, enabled = enabled && channel.muteWritable,
                    modifier = Modifier.testTag("$tag-mute"))
            }
        }
        HorizontalDivider()
    }
}

internal fun usbVolumeDbText(db256: Int): String = if (db256 == -32768) "-∞"
    else BigDecimal.valueOf(db256.toLong()).divide(BigDecimal.valueOf(256)).stripTrailingZeros().toPlainString()

/** No rounding: the user's dB value must be exactly representable on the USB 1/256 dB grid. */
internal fun usbVolumeDb256(text: String): Int? {
    val value = text.trim().replace('−', '-')
    if (value == "-∞") return -32768
    return try {
        BigDecimal(value).multiply(BigDecimal.valueOf(256)).intValueExact().takeIf { it in -32767..32767 }
    } catch (_: NumberFormatException) { null }
    catch (_: ArithmeticException) { null }
}

internal fun usbVolumeRangesText(ranges: List<UsbAudioVolumeRange>): String = ranges.joinToString("\n") { range ->
    if (range.minimumDb256 == range.maximumDb256) "${usbVolumeDbText(range.minimumDb256)} dB"
    else "${usbVolumeDbText(range.minimumDb256)} … ${usbVolumeDbText(range.maximumDb256)} dB · " +
        if (range.resolutionDb256 == 0) "连续" else "步进 ${usbVolumeDbText(range.resolutionDb256)} dB"
}
