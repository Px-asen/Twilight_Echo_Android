package com.twilightecho.android.coreclient

import kotlinx.serialization.json.*
import java.util.Locale
import kotlin.math.roundToLong

fun desktopAdaptiveObservation(path: JsonObject): List<String> {
    val receiver = path["receiver"] as? JsonObject ?: return emptyList()
    val adaptive = receiver["adaptive"] as? JsonObject ?: return emptyList()
    val timed = receiver["timedStart"] as? JsonObject ?: JsonObject(emptyMap())
    fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.doubleOrNull
    fun Double.decimal() = String.format(Locale.ROOT, "%.1f", this)
    val state = when ((timed["state"] as? JsonPrimitive)?.contentOrNull) {
        "prepared" -> "静音准备"; "armed" -> "等待起播"; "running" -> "运行中"; "completed" -> "已完成"; else -> "已停止"
    }
    val lines = mutableListOf("FFmpeg SWR · Kaiser FIR · $state", "时钟适配会重采样，不是位完美输出")
    val request = adaptive.number("requestedPpm")
    val effective = adaptive.number("effectivePpm")
    if ((adaptive.number("inputFrames") ?: 0.0) > 0 && request != null && effective != null)
        lines += "修正请求 ${request.decimal()} ppm · 已生成 PCM ${effective.decimal()} ppm"
    timed.number("presentedMediaFrames")?.let { lines += "媒体呈现 ${String.format(Locale.ROOT, "%.2f", it)} 帧" }
    return lines
}

/** Display only the Core's observations; the phone never substitutes its clock. */
fun desktopClockObservation(path: JsonObject): List<String> {
    val timing = path["timing"] as? JsonObject ?: return listOf("输出桥未提供时钟观测")
    val output = timing["output"] as? JsonObject ?: return listOf("输出桥未提供时钟观测")
    val network = timing["network"] as? JsonObject ?: JsonObject(emptyMap())
    fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }
    fun Double.decimal() = String.format(Locale.ROOT, "%.1f", this)
    val state = output.text("state")
    val lines = mutableListOf(when (state) {
        "unsupported" -> "此输出未提供设备播放时钟"
        "stopped" -> "输出已停止"
        "unreliable" -> "驱动时间戳暂不可靠"
        "warming" -> "正在积累漂移样本"
        "ready" -> "漂移已估计"
        "stale" -> "观测已过期"
        "discontinuity" -> "时钟不连续，等待输出重建"
        else -> "输出桥未提供时钟观测"
    })
    val drift = output.number("driftPpm")
    val window = output.number("windowMs")
    val samples = output.number("samples")
    if (state == "ready" && drift != null && window != null && samples != null)
        lines += "漂移 ${if (drift >= 0) "+" else ""}${drift.decimal()} ppm · ${(window / 1000).decimal()} s / ${samples.toLong()} 次"
    val position = output.number("positionFrames")
    val age = output.number("ageMs")
    if (state in listOf("ready", "warming") && position != null && age != null)
        lines += "呈现位置 ${position.roundToLong()} 帧 · 观测年龄 ${age.roundToLong()} ms"
    val rtt = network.number("roundTripMs")
    val uncertainty = network.number("uncertaintyMs")
    if (network.text("state") in listOf("ready", "warming") && rtt != null && uncertainty != null)
        lines += "网络往返 ${rtt.decimal()} ms · 时差估计 ±${uncertainty.decimal()} ms"
    else lines += "网络时钟采样已过期"
    return lines
}
