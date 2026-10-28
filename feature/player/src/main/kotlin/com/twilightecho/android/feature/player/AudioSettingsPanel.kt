package com.twilightecho.android.feature.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.twilightecho.android.coreclient.*
import com.twilightecho.android.ui.components.*
import com.twilightecho.android.ui.theme.LocalTwilightAccents
import com.twilightecho.android.ui.theme.TwilightTokens
import kotlinx.serialization.json.*
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

@Composable
internal fun AudioSettingsPanel(state: PlayerUiState, actions: PlayerActions) {
    var page by rememberSaveable { mutableStateOf("playback") }
    val pages = listOf("playback" to "播放与输出", "eq" to "均衡器", "rack" to "DSP 处理链", "scenes" to "场景")
    val processing = state.eqConfig.dsp.enabled
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        // Bypass sits above the pages so an A/B comparison is one tap from anywhere in audio settings.
        GlassCard(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp).fillMaxWidth().testTag("dsp-bypass")) {
            SettingsSwitch("声音处理", if (processing) "均衡器与处理链正在生效" else "已旁路均衡器与处理链", processing) {
                actions.editDsp { it.copy(enabled = !it.enabled) }
            }
        }
        PillTabs(pages.map { it.first }, page, { key -> pages.first { it.first == key }.second }, { page = it },
            tag = { "audio-page-$it" })
        when (page) {
            "playback" -> PlaybackSettings(state, actions)
            "eq" -> EqualizerSettings(state, actions)
            "rack" -> DspRackSettings(state, actions)
            "scenes" -> DspScenes(state, actions)
        }
    }
}

/** A titled glass panel; rows inside carry their own 18dp side padding. */
@Composable
internal fun AudioCard(title: String? = null, subtitle: String? = null,
    modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 22.dp)) {
        if (title != null) Column(Modifier.padding(start = 4.dp, bottom = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
            if (subtitle != null) SupportingText(subtitle, Modifier.padding(top = 2.dp))
        }
        GlassCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(vertical = 8.dp), content = content) }
    }
}

@Composable
internal fun CardDivider() = HorizontalDivider(Modifier.padding(horizontal = 18.dp, vertical = 6.dp), thickness = 0.5.dp,
    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

@Composable
private fun PlaybackSettings(state: PlayerUiState, actions: PlayerActions) {
    val config = state.eqConfig
    val dsp = config.dsp
    AudioCard("连续聆听") {
        SettingsSwitch("无缝播放", if (state.diagnostics.preloadReady) "下一首已预加载" else "解码器提前准备下一首。", config.gapless, actions.toggleGapless)
        CardDivider()
        AudioNumber("交叉淡化", dsp.crossfadeSeconds, 0.0, 12.0, "s", tag = "crossfade") {
            actions.editDsp { current -> current.copy(crossfadeSeconds = it) }
        }
        if (dsp.crossfadeSeconds > 0) {
            AudioChoice("淡化曲线", dsp.crossfadeCurve, listOf("equal-power" to "等功率", "linear" to "线性")) {
                actions.editDsp { current -> current.copy(crossfadeCurve = it) }
            }
            AudioChoice("曲目策略", dsp.crossfadeContent, listOf("conservative" to "保守", "all" to "全部", "live" to "现场")) {
                actions.editDsp { current -> current.copy(crossfadeContent = it) }
            }
            SupportingText(if (state.diagnostics.crossfadeMixActive) "正在混合过渡" else
                state.diagnostics.crossfadeBlockedReason?.takeIf { it.isNotBlank() } ?: "在曲尾且下一首准备就绪时过渡。",
                Modifier.padding(horizontal = 18.dp, vertical = 4.dp))
        }
        CardDivider()
        AudioNumber("播放速度", dsp.playbackRate, 0.5, 2.0, "×", tag = "playback-rate") {
            actions.editDsp { current -> current.copy(playbackRate = it) }
        }
        MiniAction("恢复原速", Modifier.padding(start = 18.dp, bottom = 8.dp)) { actions.editDsp { it.copy(playbackRate = 1.0) } }
    }
    AudioCard("输出方式") {
        SettingsSwitch("原始采样率优先", "关闭后使用连续播放策略，以固定采样率减少切歌时重开设备。", config.bitPerfect, actions.toggleBitPerfect)
        if (!config.bitPerfect) AudioChoice("连续播放采样率", dsp.continuitySampleRate.toString(),
            listOf("44100" to "44.1 kHz", "48000" to "48 kHz", "96000" to "96 kHz")) { selected ->
            actions.editDsp { it.copy(continuitySampleRate = selected.toInt()) }
        }
        CardDivider()
        val devices = listOf("auto" to "跟随系统") + state.audioDevices.map { it.id to it.name }
        AudioChoice("输出设备", dsp.outputDeviceId, devices) { selected -> actions.editDsp { it.copy(outputDeviceId = selected) } }
        if (dsp.outputDeviceId != "auto" && devices.none { it.first == dsp.outputDeviceId }) {
            SupportingText("所选设备当前未连接，可改为跟随系统。", Modifier.padding(horizontal = 18.dp))
        }
        CardDivider()
        SettingsSwitch("请求 AAudio 独占", "需开启原始采样率优先。系统可能改用共享模式；实际模式显示在音频链路。", dsp.exclusive) {
            actions.editDsp { it.copy(exclusive = !it.exclusive) }
        }
        AudioChoice("缓冲区", dsp.bufferFrames.toString(), listOf("0" to "自动", "256" to "256 帧", "512" to "512 帧", "1024" to "1024 帧", "2048" to "2048 帧")) { selected ->
            actions.editDsp { it.copy(bufferFrames = selected.toInt()) }
        }
    }
    val stage = dsp.outputStage
    AudioCard("重采样与输出余量") {
        AudioChoice("目标采样率", stage.targetSampleRate.toString(), listOf("0" to "自动", "44100" to "44.1k", "48000" to "48k", "88200" to "88.2k",
            "96000" to "96k", "176400" to "176.4k", "192000" to "192k")) { selected ->
            actions.editDsp { it.copy(outputStage = it.outputStage.copy(targetSampleRate = selected.toInt())) }
        }
        AudioChoice("重采样质量", stage.resamplerQuality, listOf("native" to "默认", "high" to "高", "ultra" to "极高")) { selected ->
            actions.editDsp { it.copy(outputStage = it.outputStage.copy(resamplerQuality = selected)) }
        }
        AudioChoice("相位", stage.resamplerPhase, listOf("linear" to "线性", "minimum" to "最小", "linear-smooth" to "平滑线性", "minimum-smooth" to "平滑最小")) { selected ->
            actions.editDsp { it.copy(outputStage = it.outputStage.copy(resamplerPhase = selected)) }
        }
        CardDivider()
        SettingsSwitch("前置余量", "在处理链前衰减信号，给 EQ 和其他处理留出空间。", stage.headroomEnabled) {
            actions.editDsp { it.copy(outputStage = it.outputStage.copy(headroomEnabled = !it.outputStage.headroomEnabled)) }
        }
        if (stage.headroomEnabled) AudioNumber("余量增益", stage.headroomGainDb, -30.0, 0.0, "dB") { selected ->
            actions.editDsp { it.copy(outputStage = it.outputStage.copy(headroomGainDb = selected)) }
        }
        AudioChoice("抖动", stage.dither, listOf("off" to "关闭", "tpdf" to "TPDF", "highpassTpdf" to "高通 TPDF", "noiseShaped" to "噪声整形")) { selected ->
            actions.editDsp { it.copy(outputStage = it.outputStage.copy(dither = selected)) }
        }
        CardDivider()
        SettingsSwitch("输出安全限幅", "防止处理后的 PCM 超出满刻度。", stage.safetyClamp) {
            actions.editDsp { it.copy(outputStage = it.outputStage.copy(safetyClamp = !it.outputStage.safetyClamp)) }
        }
    }
}

@Composable
private fun EqualizerSettings(state: PlayerUiState, actions: PlayerActions) {
    val config = state.eqConfig
    val graphic = config.dsp.eqMode == "graphic"
    AudioCard {
        SettingsSwitch("均衡器", if (config.dsp.enabled) "共享 C++ 滤波器" else "声音处理已旁路，均衡器暂不生效。", config.enabled, actions.toggleEq)
        AudioChoice("均衡器模式", config.dsp.eqMode, listOf("graphic" to "十段均衡器", "parametric" to "参数均衡器"), tag = "eq-mode") { selected ->
            actions.editDsp { it.copy(eqMode = selected) }
        }
    }
    AudioCard("频率响应") {
        EqResponseCurve(config, Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
        EqHeadroomSummary(config, state.dspStatus.meter, config.dsp.nodes.any { it.type == "meter" && it.enabled }, actions)
        if (graphic) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CoreEqConfigView.PRESETS.keys.forEach { preset ->
                    ChoicePill(presetLabel(preset), config.preset == preset, Modifier) { actions.preset(preset) }
                }
            }
        }
    }
    AudioCard(if (graphic) "增益" else "参数频段") {
        EqSlider("前级增益", config.preampDb, actions.preamp, "preamp")
        if (graphic) {
            CardDivider()
            CoreEqConfigView.BAND_FREQUENCIES_HZ.forEachIndexed { i, label -> EqSlider(label, config.bandsDb.getOrElse(i) { 0.0 }, { actions.band(i, it) }, "eqBand-$i") }
            MiniAction("重置为平直", Modifier.padding(start = 18.dp, top = 4.dp, bottom = 8.dp)) { actions.preset("Flat") }
        } else {
            var selectedBand by rememberSaveable { mutableIntStateOf(0) }
            val bands = config.dsp.parametricBands
            AudioChoice("频段", selectedBand.toString(), bands.indices.map { it.toString() to "${it + 1}" }, tag = "peq-band") {
                selectedBand = it.toInt()
            }
            val index = selectedBand.coerceIn(0, (bands.size - 1).coerceAtLeast(0))
            bands.getOrNull(index)?.let { band ->
                val params = DefaultCoreClient.DefaultJson.encodeToJsonElement(band).jsonObject
                DspControls(eqBandControls, params) { key, value ->
                    actions.editDsp { current -> current.copy(parametricBands = current.parametricBands.mapIndexed { i, existing ->
                        if (i != index) existing else DefaultCoreClient.DefaultJson.decodeFromJsonElement<CoreParametricEqBand>(
                            JsonObject(DefaultCoreClient.DefaultJson.encodeToJsonElement(existing).jsonObject + (key to value)))
                    }) }
                }
                AudioChoice("作用声道", band.channelMask.toString(), listOf("4294967295" to "全部", "1" to "左", "2" to "右")) { mask ->
                    actions.editDsp { it.copy(parametricBands = it.parametricBands.mapIndexed { i, b -> if (i == index) b.copy(channelMask = mask.toLong()) else b }) }
                }
            }
            Row(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                PillButton("添加频段", {
                    selectedBand = bands.size
                    actions.editDsp { it.copy(parametricBands = it.parametricBands + CoreParametricEqBand()) }
                }, Modifier.testTag("peq-add"), TwilightIcons.Plus, enabled = bands.size < 32, tonal = true)
                MiniAction("删除频段", enabled = bands.isNotEmpty()) {
                    actions.editDsp { it.copy(parametricBands = it.parametricBands.filterIndexed { i, _ -> i != index }) }
                    selectedBand = (index - 1).coerceAtLeast(0)
                }
            }
        }
    }
    AudioCard("导入与导出") { EqTextTransfer(config, actions) }
}

/** The engine's filter response at 48 kHz, preamp excluded; a dashed line shows the right channel when it differs. */
@Composable
private fun EqResponseCurve(config: CoreEqConfigView, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val active = config.enabled && config.dsp.enabled
    val line by animateColorAsState(if (active) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.5f), label = "eqLine")
    val grid = colors.onSurface.copy(alpha = 0.08f)
    val (curve, centers) = remember(config.dsp.eqMode, config.bandsDb, config.dsp.parametricBands) {
        EqResponse.curve(config, 160) to config.effectiveEqBands()
            .filter { it.enabled && it.frequency in EqResponse.MIN_HZ..EqResponse.MAX_HZ }
            .map { EqResponse.pointAt(config, it.frequency) }
    }
    val split = curve.any { abs(it.leftDb - it.rightDb) > 0.05 }
    val range = if ((curve + centers).any { abs(it.leftDb) > 12.5 || abs(it.rightDb) > 12.5 }) 24.0 else 12.0
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = colors.onSurfaceVariant)
    Column(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(156.dp).testTag("eq-response")) {
            val labels = listOf(20.0 to "20", 100.0 to "100", 1000.0 to "1k", 10000.0 to "10k", 20000.0 to "20k")
                .map { (hz, text) -> hz to measurer.measure(text, labelStyle) }
            val pad = 6.dp.toPx()
            val bottom = size.height - labels.maxOf { it.second.size.height } - 4.dp.toPx()
            val h = bottom - pad * 2
            val span = ln(EqResponse.MAX_HZ / EqResponse.MIN_HZ)
            fun x(hz: Double) = (ln(hz / EqResponse.MIN_HZ) / span).toFloat() * size.width
            fun y(db: Double) = pad + h / 2 - (db.coerceIn(-range, range) / range).toFloat() * h / 2
            listOf(-range, -range / 2, range / 2, range).forEach { drawLine(grid, Offset(0f, y(it)), Offset(size.width, y(it)), 1f) }
            listOf(100.0, 1000.0, 10000.0).forEach { drawLine(grid, Offset(x(it), pad), Offset(x(it), bottom - pad), 1f) }
            drawLine(colors.onSurface.copy(alpha = 0.2f), Offset(0f, y(0.0)), Offset(size.width, y(0.0)), 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
            fun path(db: (EqResponsePoint) -> Double) = Path().apply {
                curve.forEachIndexed { i, p -> if (i == 0) moveTo(x(p.frequencyHz), y(db(p))) else lineTo(x(p.frequencyHz), y(db(p))) }
            }
            val left = path { it.leftDb }
            val fill = Path().apply { addPath(left); lineTo(size.width, y(0.0)); lineTo(0f, y(0.0)); close() }
            drawPath(fill, line.copy(alpha = if (active) 0.14f else 0.05f))
            drawPath(left, line, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            if (split) drawPath(path { it.rightDb }, line, style = Stroke(2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))))
            centers.forEach { p ->
                val point = Offset(x(p.frequencyHz), y(if (abs(p.leftDb) >= abs(p.rightDb)) p.leftDb else p.rightDb))
                drawCircle(colors.surfaceContainerHigh, 4.5.dp.toPx(), point)
                drawCircle(line, 3.dp.toPx(), point)
            }
            labels.forEach { (hz, layout) ->
                drawText(layout, topLeft = Offset((x(hz) - layout.size.width / 2f).coerceIn(0f, size.width - layout.size.width),
                    size.height - layout.size.height))
            }
        }
        if (split) SupportingText("实线为左声道，虚线为右声道", Modifier.padding(top = 4.dp))
    }
}

/** Peak boost against the preamp and headroom stage, plus clips counted by an enabled meter. */
@Composable
private fun EqHeadroomSummary(config: CoreEqConfigView, meter: CoreDspMeter, metered: Boolean, actions: PlayerActions) {
    if (!config.enabled || !config.dsp.enabled) return
    val headroom = remember(config) { EqResponse.headroom(config) }
    Column(Modifier.padding(horizontal = 18.dp, vertical = 4.dp).testTag("eq-headroom"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SupportingText("最大提升 ${signedDb(roundDb(headroom.peakBoostDb))}（${frequencyLabel(headroom.peakFrequencyHz)}）· 前级 ${signedDb(config.preampDb)}")
        if (headroom.clippingRisk) Row(verticalAlignment = Alignment.CenterVertically) {
            Text("可能削波：峰值高出满刻度 ${EqualizerApoText.formatNumber(roundDb(headroom.outputPeakDb))} dB", Modifier.weight(1f).padding(end = 8.dp),
                style = MaterialTheme.typography.bodyLarge, color = TwilightTokens.SignalWarm)
            MiniAction("自动前级", Modifier.testTag("eq-auto-preamp"), highlighted = true) {
                actions.editAudio { current ->
                    val target = EqResponse.headroom(current)
                    current.copy(preampDb = target.recommendedPreampDb, dsp = target.recommendedStageDb?.let { stage ->
                        current.dsp.copy(outputStage = current.dsp.outputStage.copy(headroomEnabled = true, headroomGainDb = stage))
                    } ?: current.dsp)
                }
            }
        }
        if (metered && meter.clipCount > 0) Text("仪表已记录削波 ${meter.clipCount} 次", style = MaterialTheme.typography.bodyLarge,
            color = TwilightTokens.SignalWarm)
    }
}

private fun roundDb(value: Double): Double = kotlin.math.round(value * 10.0) / 10.0

private fun frequencyLabel(hz: Double): String =
    if (hz < 1000) "${hz.roundToInt()} Hz" else "${EqualizerApoText.formatNumber(kotlin.math.round(hz / 100.0) / 10.0)} kHz"

@Composable
private fun DspRackSettings(state: PlayerUiState, actions: PlayerActions) {
    val dsp = state.eqConfig.dsp
    AudioCard("实时监看") { AudioMonitor(state) }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    Column(Modifier.padding(start = 24.dp, end = 20.dp, top = 22.dp, bottom = 2.dp)) {
        Text("处理链", style = MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
        SupportingText("均衡器在前，其余模块按下列顺序串联。", Modifier.padding(top = 2.dp))
    }
    dsp.nodes.forEachIndexed { index, node ->
        val spec = dspModules.find { it.type == node.type } ?: return@forEachIndexed
        val status = state.dspStatus.nodes.find { it.id == node.id }
        fun updateNode(transform: (CoreDspNode) -> CoreDspNode) = actions.editDsp { config ->
            config.copy(nodes = config.nodes.map { if (it.id == node.id) transform(it) else it })
        }
        val running = dsp.enabled && node.enabled && status?.active == true
        GlassCard(Modifier.padding(horizontal = 20.dp, vertical = 6.dp).fillMaxWidth().testTag("dsp-${node.type}")) {
            Column(Modifier.padding(vertical = 14.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(36.dp).clip(CircleShape)
                        .background(if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                        contentAlignment = Alignment.Center) {
                        Text("${index + 1}", style = MaterialTheme.typography.labelLarge,
                            color = if (running) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp).clickable { expanded = if (expanded == node.id) null else node.id }) {
                        Text(spec.title, style = MaterialTheme.typography.titleMedium)
                        TagChip(when {
                            !dsp.enabled -> "总旁通"; !node.enabled -> "已旁通"; status?.active == true -> "运行中"
                            !status?.bypassReason.isNullOrBlank() -> "未生效"; else -> "已就绪"
                        }, Modifier.padding(top = 4.dp), highlighted = running)
                    }
                    Switch(node.enabled, { enabled -> updateNode { it.copy(enabled = enabled) } },
                        enabled = node.type != "convolver" || !node.params["impulseResponsePath"]?.jsonPrimitive?.contentOrNull.isNullOrBlank(),
                        colors = twilightSwitchColors(), modifier = Modifier.testTag("dsp-toggle-${node.type}"))
                }
                if (!status?.bypassReason.isNullOrBlank() && node.enabled && dsp.enabled) Text(status!!.bypassReason!!,
                    Modifier.padding(start = 64.dp, end = 16.dp, top = 6.dp), style = MaterialTheme.typography.bodySmall, color = TwilightTokens.SignalWarm)
                Row(Modifier.fillMaxWidth().padding(start = 60.dp, end = 12.dp, top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MiniAction(if (expanded == node.id) "收起" else "参数", highlighted = expanded == node.id) {
                        expanded = if (expanded == node.id) null else node.id
                    }
                    MiniAction("上移", enabled = index > 0) { actions.editDsp { it.copy(nodes = moveDspNode(it.nodes, index, -1)) } }
                    MiniAction("下移", enabled = index < dsp.nodes.lastIndex) { actions.editDsp { it.copy(nodes = moveDspNode(it.nodes, index, 1)) } }
                    MiniAction("移除") { actions.editDsp { it.copy(nodes = it.nodes.filterNot { it.id == node.id }) } }
                }
                if (expanded == node.id) {
                    CardDivider()
                    SupportingText(spec.subtitle, Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
                    DspNodeEditor(node, spec, actions.importImpulse) { key, value -> updateNode { it.copy(params = JsonObject(it.params + (key to value))) } }
                }
            }
        }
    }
    if (dsp.nodes.isEmpty()) EmptyState("处理链是空的", "按需添加处理模块；所有处理都在共享音频引擎中执行。", TwilightIcons.Equalizer)
    PillButton("添加 DSP 模块", { adding = true }, Modifier.padding(20.dp).fillMaxWidth().testTag("dsp-add"), TwilightIcons.Plus,
        enabled = dsp.nodes.size < dspModules.size, tonal = true)
    if (adding) AlertDialog(onDismissRequest = { adding = false }, title = { Text("添加处理模块") }, text = {
        androidx.compose.foundation.lazy.LazyColumn { dspModules.filter { spec -> dsp.nodes.none { it.type == spec.type } }.forEach { spec ->
            item { TextButton(onClick = {
                actions.editDsp { it.copy(nodes = it.nodes + spec.newNode()) }; expanded = "phone-${spec.type}"; adding = false
            }, Modifier.fillMaxWidth().testTag("dsp-add-${spec.type}")) { Text(spec.title, Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurface) } }
        } }
    }, confirmButton = { TextButton(onClick = { adding = false }) { Text("取消") } })
}

@Composable
private fun DspNodeEditor(node: CoreDspNode, spec: DspModuleSpec, importImpulse: (String) -> Unit, update: (String, JsonElement) -> Unit) {
    DspControls(spec.controls, node.params, update)
    when (node.type) {
        "convolver" -> {
            if (!node.params["impulseResponsePath"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) SupportingText("已导入 WAV 脉冲响应", Modifier.padding(horizontal = 18.dp))
            PillButton("选择 WAV 脉冲响应", { importImpulse(node.id) }, Modifier.padding(horizontal = 18.dp, vertical = 8.dp).fillMaxWidth(), tonal = true)
        }
        "dynamicEqualizer" -> DspArrayEditor("频段", "bands", node.params, dynamicEqControls, 8, update)
        "channelStrip" -> DspArrayEditor("声道", "channels", node.params, channelControls, 8, update)
        "multibandCompressor" -> {
            val crossovers = node.params["crossoversHz"] as? JsonArray ?: JsonArray(listOf(JsonPrimitive(200), JsonPrimitive(2500)))
            crossovers.forEachIndexed { index, element -> AudioNumber("分频点 ${index + 1}", element.jsonPrimitive.double, 20.0, 20000.0, "Hz", logarithmic = true) { value ->
                val previous = crossovers.getOrNull(index - 1)?.jsonPrimitive?.doubleOrNull ?: 19.0
                val next = crossovers.getOrNull(index + 1)?.jsonPrimitive?.doubleOrNull ?: 20001.0
                update("crossoversHz", JsonArray(crossovers.toMutableList().apply { this[index] = JsonPrimitive(value.coerceIn(previous + 1, next - 1)) }))
            } }
            DspArrayEditor("频段", "bands", node.params, listOf(DspControl.Toggle("enabled", "启用频段", true)) + compressorControls, 0, update)
        }
        "channelMatrix" -> {
            val matrix = node.params["matrix"] as? JsonArray ?: JsonArray(emptyList())
            val channels = kotlin.math.sqrt(matrix.size.toDouble()).roundToInt().coerceAtLeast(2)
            AudioChoice("矩阵声道数", channels.toString(), listOf("2" to "立体声", "6" to "5.1", "8" to "7.1")) { value ->
                val size = value.toInt(); update("matrix", buildJsonArray { repeat(size * size) { add(if (it / size == it % size) 1.0 else 0.0) } })
            }
            var output by rememberSaveable(node.id, channels) { mutableIntStateOf(0) }
            AudioChoice("输出声道", output.toString(), (0 until channels).map { it.toString() to "${it + 1}" }) { output = it.toInt() }
            repeat(channels) { input -> val index = output * channels + input
                AudioNumber("输入 ${input + 1} → 输出 ${output + 1}", matrix.getOrNull(index)?.jsonPrimitive?.doubleOrNull ?: 0.0, -2.0, 2.0) { value ->
                    update("matrix", JsonArray(matrix.toMutableList().apply { this[index] = JsonPrimitive(value) }))
                }
            }
        }
    }
}

@Composable
private fun DspArrayEditor(title: String, key: String, params: JsonObject, controls: List<DspControl>, max: Int, update: (String, JsonElement) -> Unit) {
    val items = params[key] as? JsonArray ?: JsonArray(emptyList())
    var selected by rememberSaveable(key) { mutableIntStateOf(0) }
    val index = selected.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    AudioChoice(title, index.toString(), items.indices.map { it.toString() to "${it + 1}" }) { selected = it.toInt() }
    (items.getOrNull(index) as? JsonObject)?.let { item -> DspControls(controls, item) { field, value ->
        update(key, JsonArray(items.toMutableList().apply { this[index] = JsonObject(item + (field to value)) }))
    } }
    if (max > 0) Row(Modifier.padding(horizontal = 18.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        MiniAction("添加$title", enabled = items.size < max) { update(key, JsonArray(items + controlsJson(controls))); selected = items.size }
        MiniAction("删除$title", enabled = items.size > 1) { update(key, JsonArray(items.filterIndexed { i, _ -> i != index })); selected = (index - 1).coerceAtLeast(0) }
    }
}

@Composable
private fun DspControls(controls: List<DspControl>, values: JsonObject, update: (String, JsonElement) -> Unit) {
    controls.forEach { control -> when (control) {
        is DspControl.Number -> AudioNumber(control.label, values[control.key]?.jsonPrimitive?.doubleOrNull ?: control.default,
            control.min, control.max, control.unit, control.logarithmic) { update(control.key, JsonPrimitive(it)) }
        is DspControl.Toggle -> SettingsSwitch(control.label, "", values[control.key]?.jsonPrimitive?.booleanOrNull ?: control.default) {
            update(control.key, JsonPrimitive(!(values[control.key]?.jsonPrimitive?.booleanOrNull ?: control.default)))
        }
        is DspControl.Choice -> AudioChoice(control.label, values[control.key]?.jsonPrimitive?.contentOrNull ?: control.default,
            control.options) { update(control.key, JsonPrimitive(it)) }
    } }
}

@Composable
private fun AudioMonitor(state: PlayerUiState) {
    val colors = MaterialTheme.colorScheme
    val track = colors.onSurface.copy(alpha = 0.08f)
    Column(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Canvas(Modifier.fillMaxWidth().height(84.dp).testTag("dsp-spectrum")) {
            val bars = state.audioLevels
            val count = bars.size.coerceAtLeast(48)
            val width = size.width / count
            val bar = width * 0.62f
            val radius = CornerRadius(bar / 2, bar / 2)
            val brush = SolidColor(colors.primary)
            repeat(count) { index ->
                val level = bars.getOrElse(index) { 0f }.coerceIn(0f, 1f)
                drawRoundRect(track, Offset(index * width, size.height - 3.dp.toPx()), Size(bar, 3.dp.toPx()), radius)
                if (level > 0) drawRoundRect(brush, Offset(index * width, size.height * (1 - level)), Size(bar, size.height * level), radius)
            }
        }
        val meter = state.dspStatus.meter
        if (state.eqConfig.dsp.nodes.any { it.type == "meter" && it.enabled }) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MeterReading("${meterValue(meter.integratedLufs)} LUFS", "响度", Modifier.weight(1f))
                MeterReading("${meterValue(meter.truePeakDb)} dBTP", "真峰值", Modifier.weight(1f))
            }
            SupportingText("相关性 ${String.format(Locale.US, "%.2f", meter.correlation)} · 削波 ${meter.clipCount}")
        }
        state.audioMonitorError?.let { Text(it, color = colors.error, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun MeterReading(value: String, caption: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)).padding(12.dp)) {
        Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    }
}

private fun meterValue(value: Double?): String = value?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "%.1f", it) } ?: "—"

@Composable
private fun DspScenes(state: PlayerUiState, actions: PlayerActions) {
    var saving by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    AudioCard("保存你的聆听方式", "场景包含均衡器、处理链、播放过渡与输出设置。") {
        state.eqConfig.scenes.forEachIndexed { i, scene ->
            if (i > 0) CardDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconTile(TwilightIcons.Sparkles, Modifier.padding(end = 12.dp), size = 36.dp)
                Text(scene.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1)
                MiniAction("应用", highlighted = true) { actions.applyDspScene(scene.id) }
                MiniAction("删除", Modifier.padding(start = 6.dp)) { actions.deleteDspScene(scene.id) }
            }
        }
        if (state.eqConfig.scenes.isEmpty()) SupportingText("还没有场景。调好声音后，把它保存下来。", Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
        PillButton("保存当前设置为场景", { saving = true }, Modifier.padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth()
            .testTag("dsp-scene-save"), TwilightIcons.Plus, enabled = state.eqConfig.scenes.size < 32)
        ScenePackageTransfer(state.eqConfig, actions)
    }
    OutputPresetsCard(state, actions)
    if (saving) AlertDialog(onDismissRequest = { saving = false }, title = { Text("保存场景") }, text = {
        OutlinedTextField(name, { name = it.take(80) }, label = { Text("场景名称") }, singleLine = true, shape = RoundedCornerShape(16.dp),
            colors = twilightFieldColors(), modifier = Modifier.testTag("dsp-scene-name"))
    }, confirmButton = { TextButton(onClick = { actions.saveDspScene(name); name = ""; saving = false }, enabled = name.isNotBlank()) { Text("保存") } },
        dismissButton = { TextButton(onClick = { saving = false }) { Text("取消") } })
}

/** Small secondary action rendered as a quiet pill. */
@Composable
internal fun MiniAction(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, highlighted: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val content = when {
        !enabled -> colors.onSurface.copy(alpha = 0.3f)
        highlighted -> colors.primary
        else -> colors.onSurface.copy(alpha = 0.85f)
    }
    Text(label, modifier.clip(RoundedCornerShape(50))
        .background(if (highlighted && enabled) colors.primary.copy(alpha = 0.16f) else colors.onSurface.copy(alpha = 0.06f))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).heightIn(min = 36.dp)
        .padding(horizontal = 14.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
}

@Composable
internal fun ChoicePill(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (selected) colors.primary else colors.onSurface.copy(alpha = 0.06f), label = "choiceBg")
    val fg by animateColorAsState(if (selected) colors.onPrimary else colors.onSurface.copy(alpha = 0.85f), label = "choiceFg")
    Box(modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(50)).background(bg)
        .border(1.dp, if (selected) Color.Transparent else colors.onSurface.copy(alpha = 0.08f), RoundedCornerShape(50))
        .selectable(selected, role = Role.RadioButton, onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
    }
}

@Composable
internal fun AudioChoice(label: String, selected: String, options: List<Pair<String, String>>, tag: String = "", onSelect: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label, Modifier.padding(horizontal = 18.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, text) ->
                ChoicePill(text, selected == value, Modifier.testTag(if (tag.isEmpty()) "$label-$value" else "$tag-$value")) { onSelect(value) }
            }
        }
    }
}

/** Changes commit on release; the value button also allows exact numerical entry. */
@Composable
internal fun AudioNumber(label: String, value: Double, min: Double, max: Double, unit: String = "", logarithmic: Boolean = false,
    tag: String = label, onCommit: (Double) -> Unit) {
    fun position(number: Double): Float = (if (logarithmic) (ln(number.coerceIn(min, max)) - ln(min)) / (ln(max) - ln(min))
        else (number.coerceIn(min, max) - min) / (max - min)).toFloat()
    fun number(position: Float): Double = if (logarithmic) exp(ln(min) + position * (ln(max) - ln(min))) else min + position * (max - min)
    var draft by remember(value) { mutableFloatStateOf(position(value)) }
    var editing by remember { mutableStateOf(false) }
    var text by remember(value, editing) { mutableStateOf(String.format(Locale.US, "%.3f", value).trimEnd('0').trimEnd('.')) }
    val shown = number(draft)
    Column(Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Text("${String.format(Locale.US, if (max >= 500) "%.0f" else "%.2f", shown)} $unit".trim(),
                Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                    .clickable(role = Role.Button) { editing = true }.testTag("$tag-value").heightIn(min = 32.dp)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(draft, { draft = it }, onValueChangeFinished = { onCommit(number(draft)) }, colors = twilightSliderColors(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag(tag))
    }
    if (editing) {
        val parsed = text.toDoubleOrNull()
        val valid = parsed != null && parsed.isFinite() && parsed in min..max
        AlertDialog(onDismissRequest = { editing = false }, title = { Text(label) }, text = {
            OutlinedTextField(text, { text = it }, singleLine = true, label = { Text(unit.ifBlank { label }) },
                supportingText = { Text("$min … $max") }, isError = !valid, shape = RoundedCornerShape(16.dp), colors = twilightFieldColors(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        }, confirmButton = { TextButton(onClick = { onCommit(parsed!!); editing = false }, enabled = valid) { Text("应用") } },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("取消") } })
    }
}
