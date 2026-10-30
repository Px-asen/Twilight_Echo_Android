package com.twilightecho.android.feature.player

import kotlinx.serialization.json.*

internal fun resamplerRolloff(phase: String): String =
    if (phase.endsWith("-smooth")) "linear-smooth" else "linear"

internal fun resamplerPhaseChoice(phase: String): String = if (phase.startsWith("minimum")) "minimum" else "linear"
internal fun resamplerWithPhase(current: String, phase: String): String =
    phase + if (current.endsWith("-smooth")) "-smooth" else ""
internal fun resamplerWithRolloff(current: String, rolloff: String): String =
    resamplerPhaseChoice(current) + if (rolloff.endsWith("-smooth")) "-smooth" else ""

/** Only decode-stream observations describe an active filter, never saved settings. */
internal fun resamplerStatusText(status: JsonObject): String {
    fun text(key: String) = (status[key] as? JsonPrimitive)?.contentOrNull
    val converted = (status["resampled"] as? JsonPrimitive)?.booleanOrNull
    val engine = text("effectiveResampler")
    if (engine == "none" && converted == false) return "本引擎未重采样"
    if (engine !in setOf("swr", "soxr") || converted != true) return "播放后显示实际重采样状态"
    val quality = when (text("effectiveResamplerQuality")) {
        "native" -> "默认"; "high" -> "高"; "ultra" -> "极高"
        "soxrHq" -> "HQ"; "soxrVhq" -> "VHQ"; else -> null
    }
    val phase = when (text("effectiveResamplerPhase")) {
        "linear" -> "线性相位 · 标准滚降"
        "linear-smooth" -> "线性相位 · 平滑滚降"
        "minimum" -> "最小相位 · 标准滚降"
        "minimum-smooth" -> "最小相位 · 平滑滚降"
        else -> "相位待确认"
    }
    val fallback = if ((status["effectiveResamplerFallback"] as? JsonPrimitive)?.booleanOrNull == true)
        "（SoX 不可用）" else ""
    return "实际：${if (engine == "swr") "SWR" else "SoX"}${quality?.let { " $it" }.orEmpty()}$fallback · $phase"
}
