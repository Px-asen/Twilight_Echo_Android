package com.twilightecho.android.nativeintegration

import com.twilightecho.android.coreclient.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Translate the phone's ten-band controls, not the DSP algorithm. Native graph JSON passes through. */
internal fun nativeDspConfig(config: String, json: Json): String {
    val obj = json.parseToJsonElement(config).jsonObject
    if ("bandsDb" !in obj && "preampDb" !in obj && "bitPerfect" !in obj) return config
    val enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull ?: false
    val frequencies = listOf(31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)
    val gains = obj["bandsDb"]?.jsonArray.orEmpty()
    return buildJsonObject {
        put("dspEnabled", enabled)
        put("eqEnabled", enabled)
        put("eqMode", "graphic")
        put("eqPreamp", obj["preampDb"]?.jsonPrimitive?.doubleOrNull ?: 0.0)
        put("gapless", obj["gapless"]?.jsonPrimitive?.booleanOrNull ?: true)
        put("eqBands", buildJsonArray {
            frequencies.forEachIndexed { index, frequency -> add(buildJsonObject {
                put("frequency", frequency)
                put("gain", gains.getOrNull(index)?.jsonPrimitive?.doubleOrNull ?: 0.0)
                put("q", 1.4)
                put("enabled", true)
            }) }
        })
    }.toString()
}

/** Compile phone controls into the same serial graph consumed by the desktop engine. */
internal fun nativeDspState(config: CoreEqConfigView, json: Json, revision: Long): String {
    val dsp = config.dsp
    require(dsp.eqMode in setOf("graphic", "parametric")) { "均衡器模式无效" }
    require(dsp.parametricBands.size <= 32 && dsp.parametricBands.all {
        it.frequency.isFinite() && it.frequency in 10.0..96000.0 && it.gain.isFinite() && it.gain in -24.0..24.0 &&
            it.q.isFinite() && it.q in 0.1..30.0 && it.filterType in setOf("peak", "lowShelf", "highShelf", "lowPass", "highPass", "bandPass", "allPass", "notch")
    }) { "参数均衡器频段无效" }
    val supported = setOf("replayGain", "dynamicEqualizer", "convolver", "crossfeed", "channelMatrix", "channelStrip",
        "bassManagement", "gate", "compressor", "multibandCompressor", "stereoField", "loudnessContour", "truePeakLimiter", "meter")
    require(dsp.nodes.size <= 14 && dsp.nodes.all { it.type in supported && it.id.isNotBlank() && it.id != "phone-eq" } &&
        dsp.nodes.distinctBy { it.id }.size == dsp.nodes.size && dsp.nodes.distinctBy { it.type }.size == dsp.nodes.size) { "DSP 节点类型无效或重复" }
    require(dsp.crossfadeSeconds.isFinite() && dsp.crossfadeSeconds in 0.0..12.0 &&
        dsp.playbackRate.isFinite() && dsp.playbackRate in 0.5..2.0) { "播放速度或交叉淡化时长无效" }
    val legacy = json.parseToJsonElement(nativeDspConfig(json.encodeToString(config), json)).jsonObject
    val bands = if (dsp.eqMode == "parametric") json.encodeToJsonElement(dsp.parametricBands) else legacy.getValue("eqBands")
    return buildJsonObject {
        put("revision", revision)
        put("processing", buildJsonObject {
            legacy.forEach { (k, v) -> put(k, v) }
            put("dspEnabled", dsp.enabled)
            put("eqMode", dsp.eqMode)
            put("eqBands", bands)
            put("crossfadeSeconds", dsp.crossfadeSeconds)
            put("crossfadeCurve", dsp.crossfadeCurve)
            put("crossfadeContent", dsp.crossfadeContent)
            // AAudio is a PCM path; do not offer an unverified DoP/native DSD mode.
            put("dsdOutputMode", "pcm")
        })
        put("graph", buildJsonObject {
            put("version", 2)
            put("nodes", buildJsonArray {
                add(buildJsonObject {
                    put("id", "phone-eq"); put("type", "equalizer"); put("enabled", config.enabled)
                    put("params", buildJsonObject {
                        put("mode", dsp.eqMode); put("preampDb", config.preampDb); put("bands", bands)
                    })
                })
                dsp.nodes.forEach { add(json.encodeToJsonElement(it)) }
            })
            put("outputStage", buildJsonObject {
                json.encodeToJsonElement(dsp.outputStage).jsonObject.forEach { (k, v) -> put(k, v) }
                put("targetSampleRateMode", if (dsp.outputStage.targetSampleRate > 0) "fixed" else "device")
            })
        })
    }.toString()
}

internal fun nativeOutputConfig(config: CoreEqConfigView): String = buildJsonObject {
    put("playbackPolicy", if (config.bitPerfect) "bit-perfect-first" else "continuity-first")
    put("continuitySampleRate", config.dsp.continuitySampleRate)
    put("preferredBufferSize", config.dsp.bufferFrames)
}.toString()
