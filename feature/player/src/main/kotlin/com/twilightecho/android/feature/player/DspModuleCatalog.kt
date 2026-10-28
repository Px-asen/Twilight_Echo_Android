package com.twilightecho.android.feature.player

import com.twilightecho.android.coreclient.CoreDspNode
import com.twilightecho.android.coreclient.EqLimits
import kotlinx.serialization.json.*

internal sealed class DspControl(val key: String, val label: String) {
    class Number(key: String, label: String, val default: Double, val min: Double, val max: Double,
        val unit: String = "", val logarithmic: Boolean = false) : DspControl(key, label)
    class Toggle(key: String, label: String, val default: Boolean) : DspControl(key, label)
    class Choice(key: String, label: String, val default: String, val options: List<Pair<String, String>>) : DspControl(key, label)
}

internal fun controlsJson(controls: List<DspControl>): JsonObject = buildJsonObject {
    controls.forEach { control -> when (control) {
        is DspControl.Number -> put(control.key, control.default)
        is DspControl.Toggle -> put(control.key, control.default)
        is DspControl.Choice -> put(control.key, control.default)
    } }
}

internal val compressorControls = listOf(
    DspControl.Number("thresholdDb", "阈值", -18.0, -80.0, 0.0, "dB"),
    DspControl.Number("ratio", "压缩比", 2.0, 1.0, 20.0, ":1"),
    DspControl.Number("attackMs", "启动时间", 15.0, 0.1, 1000.0, "ms", true),
    DspControl.Number("releaseMs", "释放时间", 180.0, 1.0, 5000.0, "ms", true),
    DspControl.Number("makeupDb", "补偿增益", 0.0, -24.0, 24.0, "dB")
)
internal val filterTypes = listOf("peak" to "峰值", "lowShelf" to "低架", "highShelf" to "高架",
    "lowPass" to "低通", "highPass" to "高通", "bandPass" to "带通", "notch" to "陷波", "allPass" to "全通")
internal val eqBandControls = listOf(
    DspControl.Toggle("enabled", "启用频段", true),
    DspControl.Choice("filterType", "滤波器", "peak", filterTypes),
    DspControl.Number("frequency", "频率", 1000.0, 10.0, 96000.0, "Hz", true),
    DspControl.Number("gain", "增益", 0.0, -24.0, 24.0, "dB"),
    // The shared biquads clamp Q to 20; a higher slider value would be stored but not heard.
    DspControl.Number("q", "Q 值", 1.0, EqLimits.MIN_Q, EqLimits.MAX_Q, logarithmic = true)
)
internal val dynamicEqControls = eqBandControls + listOf(
    DspControl.Number("thresholdDb", "阈值", -24.0, -100.0, 0.0, "dB"),
    DspControl.Number("ratio", "压缩比", 2.0, 1.0, 20.0, ":1"),
    DspControl.Number("rangeDb", "动态范围", -6.0, -24.0, 24.0, "dB"),
    DspControl.Number("attackMs", "启动时间", 15.0, 0.1, 1000.0, "ms", true),
    DspControl.Number("releaseMs", "释放时间", 180.0, 1.0, 5000.0, "ms", true)
)
internal val channelControls = listOf(
    DspControl.Number("gainDb", "增益", 0.0, -60.0, 24.0, "dB"),
    DspControl.Number("delayMs", "延迟", 0.0, 0.0, 250.0, "ms"),
    DspControl.Toggle("polarityInverted", "反转极性", false), DspControl.Toggle("mute", "静音", false)
)

internal data class DspModuleSpec(val type: String, val title: String, val subtitle: String,
    val controls: List<DspControl> = emptyList(), val extra: JsonObject = JsonObject(emptyMap())) {
    fun newNode() = CoreDspNode("phone-$type", type, enabled = type != "convolver",
        params = JsonObject(controlsJson(controls) + extra))
}

internal val dspModules = listOf(
    DspModuleSpec("replayGain", "响度标准化", "读取曲目或专辑的 ReplayGain 标签。", listOf(
        DspControl.Choice("mode", "模式", "track", listOf("off" to "关闭", "track" to "曲目", "album" to "专辑")),
        DspControl.Number("preampDb", "前置增益", 0.0, -24.0, 24.0, "dB"),
        DspControl.Number("fallbackDb", "缺少标签时的增益", 0.0, -24.0, 24.0, "dB"),
        DspControl.Toggle("clip", "防止削波", true))),
    DspModuleSpec("crossfeed", "耳机交叉馈送", "混入少量对侧声道，缓解过度分离。", listOf(
        DspControl.Choice("algorithm", "算法", "bs2b", listOf("custom" to "自定义", "bauer" to "Bauer", "bs2b" to "BS2B", "meier" to "Meier")),
        DspControl.Number("strength", "强度", 0.3, 0.0, 1.0),
        DspControl.Number("delayMs", "延迟", 0.35, 0.05, 2.0, "ms"),
        DspControl.Number("cutoffHz", "截止频率", 700.0, 80.0, 4000.0, "Hz", true))),
    DspModuleSpec("convolver", "卷积校正", "导入 WAV 脉冲响应，用于耳机或房间校正。", listOf(
        DspControl.Number("wet", "湿声", 1.0, 0.0, 1.0), DspControl.Number("dry", "干声", 0.0, 0.0, 1.0),
        DspControl.Number("gainDb", "增益", 0.0, -60.0, 24.0, "dB"),
        DspControl.Number("delayMs", "延迟", 0.0, 0.0, 250.0, "ms"),
        DspControl.Toggle("polarityInverted", "反转极性", false))),
    DspModuleSpec("stereoField", "立体声声场", "调节宽度、平衡与中侧声道。", listOf(
        DspControl.Number("width", "声场宽度", 1.0, 0.0, 2.0),
        DspControl.Number("balance", "左右平衡", 0.0, -1.0, 1.0),
        DspControl.Number("midGainDb", "中声道增益", 0.0, -24.0, 24.0, "dB"),
        DspControl.Number("sideGainDb", "侧声道增益", 0.0, -24.0, 24.0, "dB"),
        DspControl.Toggle("swap", "交换左右声道", false), DspControl.Toggle("mono", "混合为单声道", false),
        DspControl.Toggle("invertLeft", "反转左声道极性", false), DspControl.Toggle("invertRight", "反转右声道极性", false))),
    DspModuleSpec("compressor", "压缩器", "控制动态范围。", compressorControls),
    DspModuleSpec("dynamicEqualizer", "动态均衡器", "按频段电平动态调节增益。", extra = buildJsonObject {
        put("bands", buildJsonArray { add(controlsJson(dynamicEqControls)) }) }),
    DspModuleSpec("multibandCompressor", "多段压缩器", "独立控制低、中、高频的动态。", extra = buildJsonObject {
        put("crossoversHz", buildJsonArray { add(200.0); add(2500.0) })
        put("bands", buildJsonArray { repeat(3) { add(JsonObject(controlsJson(compressorControls) + ("enabled" to JsonPrimitive(true)))) } }) }),
    DspModuleSpec("gate", "噪声门", "在低电平处衰减信号。", listOf(
        DspControl.Number("thresholdDb", "阈值", -60.0, -100.0, 0.0, "dB"),
        DspControl.Number("attackMs", "启动时间", 2.0, 0.1, 1000.0, "ms", true),
        DspControl.Number("releaseMs", "释放时间", 120.0, 1.0, 5000.0, "ms", true))),
    DspModuleSpec("loudnessContour", "等响度补偿", "在较低音量下补偿听感。", listOf(
        DspControl.Number("amount", "补偿强度", 0.3, 0.0, 1.0),
        DspControl.Number("referenceVolume", "参考音量", 0.75, 0.0, 1.0))),
    DspModuleSpec("channelStrip", "逐声道校准", "每个声道独立增益、延迟与极性。", extra = buildJsonObject {
        put("channels", buildJsonArray { repeat(2) { add(controlsJson(channelControls)) } }) }),
    DspModuleSpec("channelMatrix", "声道矩阵", "自定义立体声输入到输出的混音系数。", extra = buildJsonObject {
        put("matrix", buildJsonArray { add(1.0); add(0.0); add(0.0); add(1.0) }) }),
    DspModuleSpec("bassManagement", "低频管理", "用于带 LFE 声道的多声道输出。", listOf(
        DspControl.Number("crossoverHz", "分频点", 80.0, 20.0, 500.0, "Hz", true),
        DspControl.Number("lfeGainDb", "LFE 增益", 0.0, -24.0, 12.0, "dB"),
        DspControl.Toggle("redirectLfe", "重定向低频到 LFE", true))),
    DspModuleSpec("truePeakLimiter", "真峰值限制器", "限制采样间峰值，留出输出余量。", listOf(
        DspControl.Number("ceilingDb", "峰值上限", -1.0, -12.0, 0.0, "dBTP"),
        DspControl.Number("attackMs", "启动时间", 0.2, 0.1, 1000.0, "ms", true),
        DspControl.Number("releaseMs", "释放时间", 80.0, 1.0, 5000.0, "ms", true),
        DspControl.Number("lookaheadMs", "前瞻", 1.0, 0.1, 20.0, "ms", true))),
    DspModuleSpec("meter", "响度与电平表", "测量 LUFS、真峰值、声道相关性与削波。")
)

internal fun moveDspNode(nodes: List<CoreDspNode>, index: Int, delta: Int): List<CoreDspNode> {
    val target = index + delta
    if (index !in nodes.indices || target !in nodes.indices) return nodes
    return nodes.toMutableList().apply { add(target, removeAt(index)) }
}
