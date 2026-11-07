package com.twilightecho.android.feature.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.twilightecho.android.nativeintegration.usb.AndroidUsbDirectHost
import com.twilightecho.android.ui.components.SupportingText
import java.math.BigDecimal

@Composable
internal fun DsdOutputSettings(state: PlayerUiState, actions: PlayerActions) {
    val dsp = state.eqConfig.dsp
    val device = state.audioDevices.firstOrNull { it.id == dsp.outputDeviceId }
    var confirmDop by remember(dsp.outputDeviceId) { mutableStateOf(false) }
    var selectionError by remember(dsp.outputDeviceId, dsp.dsdOutputMode) { mutableStateOf<String?>(null) }
    AudioCard("DSD 输出", modifier = Modifier.testTag("dsd-output-settings")) {
        AudioChoice("DSD 文件", dsp.dsdOutputMode, listOf("pcm" to "PCM", "auto" to "自动", "native" to "Native", "dop" to "DoP"),
            tag = "dsd-mode") { mode ->
            if (mode == "dop") {
                if (device?.dopConfirmationKey.isNullOrBlank()) selectionError = "先连接并选择 USB 独占设备，再确认 DoP 支持。"
                else { selectionError = null; confirmDop = true }
            } else actions.editDsp { it.copy(dsdOutputMode = mode) }
        }
        if (!AndroidUsbDirectHost.isDirect(dsp.outputDeviceId)) SupportingText(
            "系统输出仅支持 PCM；Native／DoP 请先选择 USB 独占设备。", Modifier.padding(horizontal = 18.dp, vertical = 4.dp))
        if (dsp.dsdOutputMode == "auto") SupportingText("自动只尝试已识别的 Native DSD，不会自动启用 DoP。",
            Modifier.padding(horizontal = 18.dp, vertical = 4.dp))
        if (dsp.dsdOutputMode != "pcm") {
            SupportingText("直通需应用音量 100%、原速且无 DSP 处理；现有设置不会自动调整。",
                Modifier.padding(horizontal = 18.dp, vertical = 4.dp))
            AudioChoice("倍率策略", dsp.dsdRatePolicy, listOf("pcm-fallback" to "不兼容时转 PCM", "exact" to "保持原倍率", "downrate" to "允许降倍率"),
                tag = "dsd-rate-policy") { policy -> actions.editDsp { it.copy(dsdRatePolicy = policy) } }
            SupportingText(when (dsp.dsdRatePolicy) {
                "exact" -> "无法建立原倍率直通时停止，不改用 PCM。"
                "downrate" -> "先尝试原倍率，再降低 DSD 倍率并重新调制；仍不兼容时转 PCM。"
                else -> "无法直通时解码为 PCM，音频链路显示实际路径。"
            }, Modifier.padding(horizontal = 18.dp, vertical = 4.dp))
        }
        selectionError?.let { SupportingText(it, Modifier.padding(horizontal = 18.dp, vertical = 4.dp).testTag("dsd-selection-error")) }
        SupportingText(dsdOutputStatus(state.diagnostics), Modifier.padding(horizontal = 18.dp, vertical = 8.dp).testTag("dsd-output-status"))
    }
    if (confirmDop && device != null) AlertDialog(
        onDismissRequest = { confirmDop = false },
        title = { Text("确认 DAC 支持 DoP") },
        text = { Column {
            Text(device.name.removeSuffix(" · USB 独占"))
            Text("请按 DAC 说明书确认支持 DoP。仅支持高采样率 PCM 不代表支持 DoP；不支持时可能输出噪声。请先调低 DAC 硬件音量。")
        } },
        confirmButton = { TextButton(onClick = {
            confirmDop = false
            actions.editDsp { it.copy(dsdOutputMode = "dop", dopConfirmedDeviceKey = device.dopConfirmationKey) }
        }, modifier = Modifier.testTag("dsd-confirm-dop")) { Text("确认支持，使用 DoP") } },
        dismissButton = { TextButton(onClick = { confirmDop = false }) { Text("取消") } }
    )
}

internal fun dsdResolution(bitClock: Int, multiplier: Int): String = listOfNotNull(
    multiplier.takeIf { it > 0 }?.let { "DSD$it" },
    bitClock.takeIf { it > 0 }?.let { "${BigDecimal.valueOf(it.toLong(), 6).stripTrailingZeros().toPlainString()} MHz / 1 位" }
).joinToString(" · ")

internal fun outputResolution(d: AudioDiagnosticsUiModel): String = when (d.dsdMode) {
    "native" -> "Native ${dsdResolution(d.actualSampleRateHz, d.actualDsdRate)}"
    "dop" -> "DoP · 载波 ${audioResolution(d.actualSampleRateHz, d.actualBitDepth, d.actualSampleFormat)}"
    else -> audioResolution(d.actualSampleRateHz, d.actualBitDepth, d.actualSampleFormat)
}

internal fun requestedOutputResolution(d: AudioDiagnosticsUiModel): String =
    if (d.requestedSampleFormat?.startsWith("dsd-") == true) dsdResolution(d.requestedSampleRateHz, 0)
    else audioResolution(d.requestedSampleRateHz, 0, d.requestedSampleFormat)

internal fun dsdOutputStatus(d: AudioDiagnosticsUiModel): String {
    if (!d.isDsd) return "播放 DSF、DFF 或 SACD 音轨后显示实际输出。"
    if (d.actualSampleRateHz <= 0) return "当前未建立 DSD 输出，点播放后查看实际路径。"
    val path = when (d.dsdMode) {
        "native" -> "Native DSD${d.actualDsdRate.takeIf { it > 0 } ?: d.dsdRate}"
        "dop" -> "DoP · DSD${d.actualDsdRate.takeIf { it > 0 } ?: d.dsdRate}"
        else -> "DSD → PCM"
    }
    val detail = when {
        d.dsdConversion == "downrate" -> "已降低倍率并重新调制"
        d.dsdMode == "pcm" -> when (d.dsdConversionReason) {
            "dsd_output_mode_pcm" -> "按所选模式转换"
            "dsd_backend_cannot_carry" -> "当前后端不支持 DSD 直通"
            "dop_passthrough_unproven", "dop_carrier_mismatch", "dsd_source_unsupported" -> "DAC 未能建立所请求的 DSD 通路"
            else -> "当前处理或输出条件使用 PCM"
        }
        else -> null
    }
    return listOfNotNull(path, detail).joinToString(" · ")
}
