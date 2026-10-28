package com.twilightecho.android.nativeintegration

import com.twilightecho.android.runtimehost.AudioSnapshot
import kotlinx.serialization.json.*

internal fun parseNativePlaybackSnapshot(payloadJson: String, current: AudioSnapshot, userVolume: Double, json: Json): AudioSnapshot? = runCatching {
    val obj = json.parseToJsonElement(payloadJson) as? JsonObject ?: return null
    val output = obj["outputInfo"] as? JsonObject
    fun positive(name: String) = output?.get(name)?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }
    fun text(name: String) = output?.get(name)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    val released = output?.get("outputReleased")?.jsonPrimitive?.booleanOrNull == true
    val state = when (val nativeState = obj["state"]?.jsonPrimitive?.contentOrNull) {
        "stopped" -> "idle"
        null -> current.state
        else -> nativeState
    }
    val posSec = obj["position"]?.jsonPrimitive?.doubleOrNull
    val durSec = obj["duration"]?.jsonPrimitive?.doubleOrNull
    val posMs = posSec?.let { (it * 1000.0).toLong() }
        ?: obj["positionMs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        ?: current.positionMs
    val durMs = durSec?.let { (it * 1000.0).toLong() }
        ?: obj["durationMs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        ?: current.durationMs

    current.copy(
        state = state,
        positionMs = posMs,
        durationMs = durMs,
        volume = userVolume,
        playbackRate = obj["playbackRate"]?.jsonPrimitive?.doubleOrNull ?: current.playbackRate,
        playMode = when (val mode = obj["playMode"]?.jsonPrimitive?.contentOrNull) {
            "sequential" -> "sequence"
            "listLoop" -> "loop"
            "repeat" -> "single"
            null -> current.playMode
            else -> mode
        },
        queueIndex = obj["queueIndex"]?.jsonPrimitive?.intOrNull ?: current.queueIndex,
        codec = obj["codec"]?.jsonPrimitive?.contentOrNull,
        sampleRate = obj["sourceSampleRate"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 },
        bitDepth = obj["sourceBitDepth"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 },
        outputBackend = obj["outputBackend"]?.jsonPrimitive?.contentOrNull,
        actualSampleRate = obj["actualSampleRate"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 },
        actualBitDepth = obj["actualBitDepth"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 },
        outputPerfect = obj["outputPerfect"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
        dspActive = obj["dspActive"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
        eqActive = obj["eqActive"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
        channels = obj["sourceChannels"]?.jsonPrimitive?.intOrNull,
        actualChannels = obj["actualChannels"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 },
        actualSampleFormat = text("actualOutputFormat"),
        requestedSampleRate = positive("requestedSampleRate"),
        requestedSampleFormat = text("requestedSampleFormat"),
        hardwareSampleRate = positive("hardwareSampleRate"),
        hardwareBitDepth = positive("hardwareBitDepth"),
        hardwareChannels = positive("hardwareChannels"),
        hardwareSampleFormat = text("hardwareSampleFormat"),
        perfectReason = obj["perfectReason"]?.jsonPrimitive?.contentOrNull,
        perfectReasonCode = obj["perfectReasonCode"]?.jsonPrimitive?.contentOrNull,
        exclusive = (obj["outputInfo"] as? JsonObject)?.get("exclusive")?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
        actualDeviceId = (obj["outputInfo"] as? JsonObject)?.get("actualDeviceId")?.jsonPrimitive?.contentOrNull,
        actualDeviceName = (obj["outputInfo"] as? JsonObject)?.get("actualDeviceName")?.jsonPrimitive?.contentOrNull,
        latencyMs = obj["latencyMs"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
        bufferSizeFrames = obj["bufferSizeFrames"]?.jsonPrimitive?.intOrNull ?: 0,
        gaplessActive = obj["gaplessActive"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
        gaplessBlockedReason = obj["gaplessBlockedReason"]?.jsonPrimitive?.contentOrNull,
        preloadReady = obj["preloadReady"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
        crossfadeActive = obj["crossfadeActive"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
        crossfadeSeconds = obj["crossfadeSeconds"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
        crossfadeMixActive = obj["crossfadeMixActive"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
        crossfadeEffectiveSeconds = obj["crossfadeEffectiveSeconds"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
        crossfadeCurve = obj["crossfadeCurve"]?.jsonPrimitive?.contentOrNull,
        crossfadeBlockedReason = obj["crossfadeBlockedReason"]?.jsonPrimitive?.contentOrNull
    ).let { if (released || state !in setOf("playing", "paused")) it.withoutOutputFacts() else it }
}.getOrNull()
